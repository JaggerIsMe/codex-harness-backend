package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.vo.SkillImportVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import com.myharness.codex.support.IsolatedMysql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="mysql.isolated.integration", matches="true")
class SkillImportMysqlTest {
    static IsolatedMysql mysql;
    static JdbcTemplate jdbc;
    static SqlSessionTemplate session;
    static TransactionTemplate tx;
    @TempDir Path directory;
    SkillImportService service;
    AgentProperties properties;
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    @BeforeAll static void database() throws Exception {
        mysql=IsolatedMysql.start();
        mysql.applyResource("db/schema.sql");
        mysql.applyResource("db/migration-skill-batch-import.sql"); // Also safe after fresh installation.
        jdbc=new JdbcTemplate(mysql.dataSource());
        jdbc.update("INSERT INTO sys_user(id,email,password_hash,display_name,status) VALUES(901,'skill-import@example.test','unused','Fixture','ENABLED')");
        Configuration config=new Configuration(); config.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(SkillMapper.class,SkillImportMapper.class,ExpertMapper.class)) config.addMapper(mapper);
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean(); factory.setConfiguration(config); factory.setDataSource(mysql.dataSource());
        session=new SqlSessionTemplate(factory.getObject());
        tx=new TransactionTemplate(new DataSourceTransactionManager(mysql.dataSource()));
    }
    @AfterAll static void closeDatabase() throws Exception { if(mysql!=null) mysql.close(); }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM expert_version"); jdbc.update("DELETE FROM expert");
        jdbc.update("DELETE FROM skill_version"); jdbc.update("DELETE FROM skill"); jdbc.update("DELETE FROM skill_import_record");
        properties=new AgentProperties(); properties.setSkillStorageDir(directory.toString());
        service=new SkillImportService(session.getMapper(SkillMapper.class),session.getMapper(SkillImportMapper.class),tx,json,properties);
    }
    @Test void previewIncludesDraftAndHistoricalExpertVersionsAndDeduplicatesExperts() throws Exception {
        var first=create("alpha"); var second=create("beta");
        ExpertService experts=experts();
        ExpertDraftDTO input=draft("Shared expert",List.of(first.versionId(),second.versionId()));
        var expert=experts.save(null,input,901L);
        var published=experts.publish(expert.id(),expert.revision(),false,901L);
        input.setRevision(published.revision());
        experts.save(expert.id(),input,901L);
        var preview=service.preview(new SkillImportDTO(SkillImportDTO.Mode.UPDATE,List.of(update(first.skillId(),"alpha","2"),update(second.skillId(),"beta","2"))),901L);
        assertThat(preview.affectedExpertCount()).isEqualTo(1);
        assertThat(preview.items()).allSatisfy(item -> assertThat(item.experts()).extracting(SkillImportVO.Impact::source).containsExactly("DRAFT","VERSION"));
        var result=service.commit(new SkillImportDTO.Commit(preview.previewId(),UUID.randomUUID().toString()),901L);
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(status(first.versionId())).isEqualTo("DISABLED");
        assertThat(status(second.versionId())).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT skill_version_ids FROM expert_version WHERE id=?",String.class,published.publishedVersionId())).contains(first.versionId().toString());
        assertThatThrownBy(() -> experts.publish(expert.id(),published.revision()+1,true,901L)).isInstanceOf(BusinessException.class).hasMessageContaining("已禁用");
    }
    @Test void stalePreviewIsRejectedAfterExpertDependencyChanges() throws Exception {
        var first=create("alpha");
        var preview=service.preview(new SkillImportDTO(SkillImportDTO.Mode.UPDATE,List.of(update(first.skillId(),"alpha","2"))),901L);
        experts().save(null,draft("New dependency",List.of(first.versionId())),901L);
        assertThatThrownBy(() -> service.commit(new SkillImportDTO.Commit(preview.previewId(),"stale"),901L)).hasMessageContaining("重新预览");
        assertThat(status(first.versionId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM skill_version",Integer.class)).isEqualTo(1);
    }
    @Test void disabledDraftStillAppearsInImpactPreview() throws Exception {
        var first=create("alpha");
        var experts=experts();
        var expert=experts.save(null,draft("Disabled draft",List.of(first.versionId())),901L);
        experts.status(expert.id(),"DISABLED",expert.revision(),901L);
        var preview=service.preview(new SkillImportDTO(SkillImportDTO.Mode.UPDATE,List.of(update(first.skillId(),"alpha","2"))),901L);
        assertThat(preview.affectedExpertCount()).isEqualTo(1);
        assertThat(preview.items().getFirst().experts().getFirst().expertStatus()).isEqualTo("DISABLED");
    }
    @Test void singleUploadFailureAlsoRollsBackAndReclaimsItsFile() throws Exception {
        var first=create("alpha");
        var single=new com.myharness.codex.service.impl.SkillServiceImpl(session.getMapper(SkillMapper.class),properties,tx);
        jdbc.execute("CREATE TRIGGER reject_single_version BEFORE INSERT ON skill_version FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture failure'");
        try {
            assertThatThrownBy(() -> single.uploadVersion(first.skillId(),"2",zip("alpha"),901L)).isInstanceOf(RuntimeException.class);
            assertThat(status(first.versionId())).isEqualTo("ACTIVE");
            try(var paths=Files.list(directory)) { assertThat(paths.filter(Files::isRegularFile).count()).isEqualTo(1); }
        } finally { jdbc.execute("DROP TRIGGER reject_single_version"); }
    }
    @Test void corruptStagedArchiveCannotDisableTheOldVersion() throws Exception {
        var first=create("alpha");
        var item=update(first.skillId(),"alpha","2");
        var preview=service.preview(new SkillImportDTO(SkillImportDTO.Mode.UPDATE,List.of(item)),901L);
        Path path=directory.resolve(item.uploadId()+".zip");
        byte[] bytes=Files.readAllBytes(path); bytes[bytes.length-1]^=1; Files.write(path,bytes);
        var result=service.commit(new SkillImportDTO.Commit(preview.previewId(),"corrupted"),901L);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.items().getFirst().message()).contains("校验失败");
        assertThat(status(first.versionId())).isEqualTo("ACTIVE");
    }
    @Test void itemFailureRollsBackDeactivationAndOtherItemsCommit() throws Exception {
        var first=create("alpha"); var second=create("beta");
        var preview=service.preview(new SkillImportDTO(SkillImportDTO.Mode.UPDATE,List.of(update(first.skillId(),"alpha","fail-write"),update(second.skillId(),"beta","2"))),901L);
        jdbc.execute("CREATE TRIGGER reject_import_version BEFORE INSERT ON skill_version FOR EACH ROW BEGIN IF NEW.version='fail-write' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture failure'; END IF; END");
        try {
            var request=new SkillImportDTO.Commit(preview.previewId(),"partial");
            var result=service.commit(request,901L);
            assertThat(result.failedCount()).isEqualTo(1); assertThat(result.successCount()).isEqualTo(1);
            assertThat(status(first.versionId())).isEqualTo("ACTIVE"); assertThat(status(second.versionId())).isEqualTo("DISABLED");
            SkillImportService restarted=new SkillImportService(session.getMapper(SkillMapper.class),session.getMapper(SkillImportMapper.class),tx,json,properties);
            assertThat(restarted.commit(request,901L)).isEqualTo(result);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM skill_version",Integer.class)).isEqualTo(3);
        } finally { jdbc.execute("DROP TRIGGER reject_import_version"); }
    }
    @Test void concurrentRepeatCommitCreatesOnlyOneVersion() throws Exception {
        var first=create("alpha");
        var preview=service.preview(new SkillImportDTO(SkillImportDTO.Mode.UPDATE,List.of(update(first.skillId(),"alpha","2"))),901L);
        var request=new SkillImportDTO.Commit(preview.previewId(),"concurrent");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> service.commit(request,901L)); var b=pool.submit(() -> service.commit(request,901L));
            assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM skill_version",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM skill_version WHERE status='ACTIVE'",Integer.class)).isEqualTo(1);
    }
    @Test void expertWritesParticipateInCatalogLock() throws Exception {
        var first=create("alpha");
        CountDownLatch locked=new CountDownLatch(1); CountDownLatch release=new CountDownLatch(1); CountDownLatch writing=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var lock=pool.submit(() -> tx.executeWithoutResult(state -> {
                session.getMapper(SkillMapper.class).lockCatalog(); locked.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("lock fixture timed out"); }
                catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var write=pool.submit(() -> { writing.countDown(); return experts().save(null,draft("Waiting expert",List.of(first.versionId())),901L); });
            try {
                assertThat(writing.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> write.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            lock.get(5,TimeUnit.SECONDS); assertThat(write.get(5,TimeUnit.SECONDS).id()).isNotNull();
        }
    }
    @Test void sameArchiveSkipsWithoutReactivationAndOtherOwnerCannotUseStaging() throws Exception {
        var first=create("alpha");
        jdbc.update("UPDATE skill_version SET status='DISABLED' WHERE id=?",first.versionId());
        var item=update(first.skillId(),"alpha","1");
        var preview=service.preview(new SkillImportDTO(SkillImportDTO.Mode.UPDATE,List.of(item)),901L);
        assertThat(preview.items().getFirst().status()).isEqualTo("SKIP");
        assertThat(service.commit(new SkillImportDTO.Commit(preview.previewId(),"skip"),901L).skippedCount()).isEqualTo(1);
        assertThat(status(first.versionId())).isEqualTo("DISABLED");
        var foreign=update(first.skillId(),"alpha","2");
        assertThat(service.preview(new SkillImportDTO(SkillImportDTO.Mode.UPDATE,List.of(foreign)),902L).items().getFirst().status()).isEqualTo("INVALID");
        assertThatThrownBy(() -> service.submission("skip",902L)).isInstanceOf(BusinessException.class);
    }
    @Test void cleanupRemovesExpiredStagingButPreservesPublishedArchive() throws Exception {
        var published=create("alpha");
        var upload=service.upload(zip("beta"),901L);
        Path orphan=directory.resolve(upload.uploadId()+".zip");
        jdbc.update("UPDATE skill_import_record SET expires_at=DATE_SUB(NOW(),INTERVAL 2 DAY) WHERE id=?",upload.uploadId());
        new SkillImportCleanup(session.getMapper(SkillMapper.class),session.getMapper(SkillImportMapper.class),tx,json,properties).cleanup();
        assertThat(orphan).doesNotExist();
        assertThat(Path.of(jdbc.queryForObject("SELECT storage_path FROM skill_version WHERE id=?",String.class,published.versionId()))).exists();
    }
    @Test void discardedFilesKeepTheirQuotaReferenceUntilCleanup() throws Exception {
        var upload=service.upload(zip("discarded"),901L);
        Path path=directory.resolve(upload.uploadId()+".zip");
        service.discard(upload.uploadId(),901L);
        assertThat(path).exists();
        assertThat(session.getMapper(SkillImportMapper.class).uploads(901L)).hasSize(1);
        new SkillImportCleanup(session.getMapper(SkillMapper.class),session.getMapper(SkillImportMapper.class),tx,json,properties).cleanup();
        assertThat(path).doesNotExist();
        assertThat(session.getMapper(SkillImportMapper.class).uploads(901L)).isEmpty();
    }
    @Test void singleAndBatchUploadsShareTheStorageRoot() throws Exception {
        var batch=create("alpha");
        Path batchPath=versionPath(batch.versionId());
        assertThat(batchPath.getParent()).isEqualTo(directory);
        var single=new com.myharness.codex.service.impl.SkillServiceImpl(session.getMapper(SkillMapper.class),properties,tx);
        single.uploadVersion(batch.skillId(),"2",zip("alpha"),901L);
        var paths=jdbc.queryForList("SELECT storage_path FROM skill_version",String.class);
        assertThat(paths).allSatisfy(path -> assertThat(Path.of(path).getParent()).isEqualTo(directory));
        assertThat(directory.resolve("imports")).doesNotExist();
    }
    @Test void cleanupLeavesLegacyFilesAndVersionPathsUntouched() throws Exception {
        var published=create("alpha");
        Path target=versionPath(published.versionId());
        Path legacy=moveToLegacy(target);
        jdbc.update("UPDATE skill_version SET storage_path=? WHERE id=?",legacy.toString(),published.versionId());
        Path orphan=legacy.getParent().resolve(UUID.randomUUID()+".zip");
        Files.writeString(orphan,"legacy orphan");
        var oldTime=java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(2)));
        Files.setLastModifiedTime(legacy,oldTime);
        Files.setLastModifiedTime(orphan,oldTime);
        String digest=SkillArchive.digest(legacy);

        cleanupService().cleanup();

        assertThat(versionPath(published.versionId())).isEqualTo(legacy);
        assertThat(SkillArchive.digest(legacy)).isEqualTo(digest);
        assertThat(Files.readString(orphan)).isEqualTo("legacy orphan");
        assertThat(target).doesNotExist();
    }
    private SkillImportCleanup cleanupService() {
        return new SkillImportCleanup(session.getMapper(SkillMapper.class),session.getMapper(SkillImportMapper.class),tx,json,properties);
    }
    private Path versionPath(Long id) { return Path.of(jdbc.queryForObject("SELECT storage_path FROM skill_version WHERE id=?",String.class,id)); }
    private Path moveToLegacy(Path source) throws Exception {
        Path legacy=directory.resolve("imports"); Files.createDirectories(legacy);
        return Files.move(source,legacy.resolve(source.getFileName()));
    }
    private SkillImportVO.Result create(String name) throws Exception {
        var file=service.upload(zip(name),901L);
        assertThat(file.skillName()).isEqualTo(name);
        var item=new SkillImportDTO.Item(UUID.randomUUID().toString(),file.uploadId(),null,name,"Description","1");
        var preview=service.preview(new SkillImportDTO(SkillImportDTO.Mode.CREATE,List.of(item)),901L);
        var result=service.commit(new SkillImportDTO.Commit(preview.previewId(),UUID.randomUUID().toString()),901L);
        assertThat(result.successCount()).isEqualTo(1); return result.items().getFirst();
    }
    private SkillImportDTO.Item update(Long id,String name,String version) throws Exception {
        var file=service.upload(zip(name),901L);
        return new SkillImportDTO.Item(UUID.randomUUID().toString(),file.uploadId(),id,name,"",version);
    }
    private MockMultipartFile zip(String name) throws Exception {
        var output=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(output)) {
            ZipEntry entry=new ZipEntry("SKILL.md"); entry.setTime(0); zip.putNextEntry(entry);
            zip.write(("---\nname: "+name+"\ndescription: Fixture\n---\n# Skill").getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return new MockMultipartFile("file",name+".zip","application/zip",output.toByteArray());
    }
    private String status(Long version) { return jdbc.queryForObject("SELECT status FROM skill_version WHERE id=?",String.class,version); }
    private ExpertDraftDTO draft(String name,List<Long> ids) { var draft=new ExpertDraftDTO(); draft.setName(name); draft.setSystemPrompt("Fixture"); draft.setSkillVersionIds(ids); return draft; }
    private ExpertService experts() {
        var mcp=mock(McpConfigurationService.class); when(mcp.runtimes(List.of())).thenReturn(List.of()); when(mcp.updates(List.of())).thenReturn(List.of());
        return new ExpertService(session.getMapper(ExpertMapper.class),mock(ProjectMapper.class),mock(ConversationMapper.class),
                session.getMapper(SkillMapper.class),mock(AgentDeviceMapper.class),mock(RbacMapper.class),mock(AuthorizationService.class),tx,json,properties,mcp);
    }
}
