package com.myharness.codex.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.SkillExpertAssignmentVO.*;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import com.myharness.codex.support.IsolatedMysql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@EnabledIfSystemProperty(named="mysql.isolated.integration",matches="true")
class SkillExpertAssignmentMysqlTest {
    static IsolatedMysql mysql; static JdbcTemplate jdbc; static SqlSessionTemplate session; static TransactionTemplate tx;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    AuthorizationService access; ExpertSkillAssignmentService service;
    @BeforeAll static void database() throws Exception {
        mysql=IsolatedMysql.start();mysql.applyResource("db/schema.sql");jdbc=new JdbcTemplate(mysql.dataSource());
        jdbc.update("INSERT INTO sys_user(id,email,password_hash,display_name,status) VALUES(901,'assignment@test.local','unused','Fixture','ENABLED')");
        var config=new Configuration();config.setMapUnderscoreToCamelCase(true);
        for(Class<?> mapper:List.of(SkillMapper.class,ExpertMapper.class,SkillExpertAssignmentMapper.class))config.addMapper(mapper);
        var factory=new SqlSessionFactoryBean();factory.setConfiguration(config);factory.setDataSource(mysql.dataSource());
        session=new SqlSessionTemplate(factory.getObject());tx=new TransactionTemplate(new DataSourceTransactionManager(mysql.dataSource()));
    }
    @AfterAll static void close() throws Exception {if(mysql!=null)mysql.close();}
    @BeforeEach void setup(){access=mock(AuthorizationService.class);service=new ExpertSkillAssignmentService(session.getMapper(SkillMapper.class),session.getMapper(ExpertMapper.class),session.getMapper(SkillExpertAssignmentMapper.class),access,tx,json);}
    @Test void addsAndReplacesOnlySelectedSkillPreservingPublishedSnapshotAndStatus() {
        var skill=skill();var unrelated=skill();long add=expert(List.of(unrelated[1]),"PUBLISHED"),replace=expert(List.of(skill[0],unrelated[1]),"PUBLISHED");
        long release=publishFixture(replace,List.of(skill[0],unrelated[1]));
        var preview=preview(skill,List.of(add,replace));
        assertThat(preview.items()).extracting(Candidate::action).containsExactly("ADD","REPLACE");
        assertThat(preview.items().get(1).draftVersion()).isEqualTo("1");assertThat(preview.items().get(1).publishedVersion()).isEqualTo("1");
        var result=service.commit(preview.batchId(),901L);assertThat(result.complete()).isTrue();
        assertThat(result.items()).extracting(Result::status).containsOnly("SUCCESS");
        for(long id:List.of(add,replace)){
            var updated=session.getMapper(ExpertMapper.class).get(id);
            assertThat(updated.getStatus()).isEqualTo("PUBLISHED");assertThat(updated.isDraftChanged()).isTrue();assertThat(updated.getRevision()).isEqualTo(1);
            assertThat(updated.getSkillVersionIds()).contains(skill[1].toString(),unrelated[1].toString());
            assertThat(updated.getSystemPrompt()).isEqualTo("Keep prompt");assertThat(updated.getMcpVersionIds()).isEqualTo("[]");
        }
        assertThat(session.getMapper(ExpertMapper.class).version(release).getSkillVersionIds()).isEqualTo("["+skill[0]+","+unrelated[1]+"]");
        assertThat(session.getMapper(ExpertMapper.class).get(replace).getPublishedVersionId()).isEqualTo(release);
    }
    @Test void excludesAlreadyBoundAndDisabledExpertsAndPaginatesBeyondTwoHundred() {
        var skill=skill();String prefix=UUID.randomUUID().toString();
        for(int i=0;i<205;i++) {long id=expert(List.of(),"DRAFT");jdbc.update("UPDATE expert SET name=? WHERE id=?",prefix+"-"+i,id);}
        long bound=expert(List.of(skill[1]),"DRAFT"),disabled=expert(List.of(),"DISABLED");
        jdbc.update("UPDATE expert SET name=? WHERE id IN (?,?)",prefix,bound,disabled);
        var page=service.candidates(skill[2],skill[1],prefix,5,50,901L);
        assertThat(page.total()).isEqualTo(205);assertThat(page.items()).hasSize(5);
    }
    @Test void concurrentReplayIsIdempotent() throws Exception {
        var skill=skill();long id=expert(List.of(),"UNPUBLISHED");var preview=preview(skill,List.of(id));
        try(var executor=Executors.newFixedThreadPool(2)){
            var one=executor.submit(()->service.commit(preview.batchId(),901L));var two=executor.submit(()->service.commit(preview.batchId(),901L));
            assertThat(one.get(20,TimeUnit.SECONDS).complete()).isTrue();assertThat(two.get(20,TimeUnit.SECONDS).complete()).isTrue();
        }
        var value=session.getMapper(ExpertMapper.class).get(id);assertThat(value.getRevision()).isEqualTo(1);assertThat(value.getStatus()).isEqualTo("UNPUBLISHED");
        assertThat(service.commit(preview.batchId(),901L).items()).hasSize(1);
    }
    @Test void revisionConflictDoesNotOverwriteOtherChangesAndOtherExpertsSucceed() {
        var skill=skill();long changed=expert(List.of(),"DRAFT"),ok=expert(List.of(),"DRAFT");var preview=preview(skill,List.of(changed,ok));
        jdbc.update("UPDATE expert SET revision=revision+1,system_prompt='Concurrent edit' WHERE id=?",changed);
        var result=service.commit(preview.batchId(),901L);
        assertThat(result.items()).extracting(Result::status).containsExactly("FAILED","SUCCESS");
        assertThat(session.getMapper(ExpertMapper.class).get(changed).getSkillVersionIds()).isEqualTo("[]");
        assertThat(session.getMapper(ExpertMapper.class).get(changed).getSystemPrompt()).isEqualTo("Concurrent edit");
    }
    @Test void failedItemRollsBackWithoutPreventingOtherAssignments() {
        var skill=skill();long failing=expert(List.of(),"DRAFT"),ok=expert(List.of(),"DRAFT");var preview=preview(skill,List.of(failing,ok));
        jdbc.execute("CREATE TRIGGER reject_assignment BEFORE UPDATE ON expert FOR EACH ROW BEGIN IF NEW.id="+failing+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture failure'; END IF; END");
        try {
            var result=service.commit(preview.batchId(),901L);assertThat(result.items()).extracting(Result::status).containsExactly("FAILED","SUCCESS");
            assertThat(session.getMapper(ExpertMapper.class).get(failing).getRevision()).isZero();
        } finally {jdbc.execute("DROP TRIGGER reject_assignment");}
    }
    @Test void targetDisabledAfterPreviewRejectsCommitAndOwnershipIsEnforced() {
        var skill=skill();long id=expert(List.of(),"DRAFT");var preview=preview(skill,List.of(id));
        assertThatThrownBy(()->service.commit(preview.batchId(),902L)).hasMessageContaining("不存在");
        jdbc.update("UPDATE skill_version SET status='DISABLED' WHERE id=?",skill[1]);
        assertThatThrownBy(()->service.commit(preview.batchId(),901L)).hasMessageContaining("停用");
        assertThat(session.getMapper(ExpertMapper.class).get(id).getSkillVersionIds()).isEqualTo("[]");
    }
    @Test void alreadyAssignedAfterPreviewSkipsAndThirtySkillLimitIsEnforced() {
        var skill=skill();long id=expert(List.of(),"DRAFT");var preview=preview(skill,List.of(id));
        jdbc.update("UPDATE expert SET skill_version_ids=?,revision=revision+1 WHERE id=?","["+skill[1]+"]",id);
        assertThat(service.commit(preview.batchId(),901L).items().getFirst().status()).isEqualTo("SKIPPED");
        List<Long> full=new ArrayList<>();for(int i=0;i<30;i++)full.add(skill()[1]);
        long fullExpert=expert(full,"DRAFT");var blocked=preview(skill,List.of(fullExpert));
        assertThat(blocked.items().getFirst().action()).isEqualTo("BLOCKED");
        assertThatThrownBy(()->service.commit(blocked.batchId(),901L)).hasMessageContaining("不可分配");
    }
    @Test void ordinaryDraftSaveAlsoPreservesPublishedStateAndPublicationClearsDirtyFlag() {
        var skill=skill();long id=expert(List.of(skill[1]),"PUBLISHED");publishFixture(id,List.of(skill[1]));
        var experts=new ExpertService(session.getMapper(ExpertMapper.class),mock(ProjectMapper.class),mock(ConversationMapper.class),
                session.getMapper(SkillMapper.class),mock(AgentDeviceMapper.class),mock(RbacMapper.class),access,tx,json,new AgentProperties(),mock(McpConfigurationService.class));
        var input=new ExpertDraftDTO();input.setName("Edited");input.setSystemPrompt("New prompt");input.setSkillVersionIds(List.of(skill[1]));input.setRevision(0L);
        var saved=experts.save(id,input,901L);assertThat(saved.status()).isEqualTo("PUBLISHED");assertThat(saved.draftChanged()).isTrue();
        var published=experts.publish(id,saved.revision(),false,901L);assertThat(published.draftChanged()).isFalse();
        assertThat(session.getMapper(ExpertMapper.class).versions(id)).hasSize(2);
    }
    @Test void requiresBothManagementPermissions() {
        var skill=skill();doThrow(new IllegalStateException("forbidden")).when(access).requirePermission(901L,"expert:manage");
        assertThatThrownBy(()->service.candidates(skill[2],skill[1],"",1,20,901L)).hasMessage("forbidden");
        verify(access).requirePermission(901L,"skill:manage");
    }
    @Test void downloadAuthorizationUsesActualProjectAndConversationVersions() {
        var oldSkill=skill();var currentSkill=skill();var unusedSkill=skill();
        long expert=expert(List.of(currentSkill[1]),"PUBLISHED");long old=publishFixture(expert,List.of(oldSkill[1]));
        jdbc.update("INSERT INTO expert_version(expert_id,version_no,name,description,system_prompt,skill_version_ids,mcp_version_ids) VALUES(?,2,'Current','','prompt',?,'[]')",expert,"["+currentSkill[1]+"]");
        long current=jdbc.queryForObject("SELECT id FROM expert_version WHERE expert_id=? AND version_no=2",Long.class,expert);
        jdbc.update("INSERT INTO expert_version(expert_id,version_no,name,description,system_prompt,skill_version_ids,mcp_version_ids) VALUES(?,3,'Unused','','prompt',?,'[]')",expert,"["+unusedSkill[1]+"]");
        jdbc.update("UPDATE expert SET published_version_id=? WHERE id=?",current,expert);
        jdbc.update("INSERT INTO agent_device(id,device_code,device_name,token_hash,status) VALUES(8001,'download-fixture','Fixture',?,'ONLINE')","b".repeat(64));
        jdbc.update("INSERT INTO agent_workspace(id,device_id,workspace_name,root_path,status) VALUES(8001,8001,'fixture','D:/fixture','ENABLED')");
        jdbc.update("INSERT INTO user_device_assignment(user_id,device_id,assigned_by) VALUES(901,8001,901)");
        jdbc.update("INSERT INTO codex_project(id,user_id,device_id,workspace_id,project_name) VALUES(8001,901,8001,8001,'Download fixture')");
        jdbc.update("INSERT INTO user_expert_assignment(user_id,expert_id,assigned_by) VALUES(901,?,901)",expert);
        jdbc.update("INSERT INTO project_expert_binding(project_id,expert_id,expert_version_id) VALUES(8001,?,?)",expert,current);
        var mapper=session.getMapper(SkillMapper.class);
        assertThat(mapper.selectDownload(currentSkill[1],8001L)).isNotNull();
        assertThat(mapper.selectDownload(oldSkill[1],8001L)).isNull();
        jdbc.update("INSERT INTO conversation(user_id,device_id,workspace_id,project_id,selected_expert_id,selected_expert_version_id) VALUES(901,8001,8001,8001,?,?)",expert,old);
        assertThat(mapper.selectDownload(oldSkill[1],8001L)).isNotNull();
        assertThat(mapper.selectDownload(unusedSkill[1],8001L)).isNull();
        assertThat(mapper.selectDownload(currentSkill[1],8002L)).isNull();
        jdbc.update("UPDATE user_expert_assignment SET status='DISABLED' WHERE expert_id=?",expert);
        assertThat(mapper.selectDownload(oldSkill[1],8001L)).isNull();
        assertThat(mapper.selectDownload(currentSkill[1],8001L)).isNull();
    }
    @Test void batchPatchesAllSelectedSkillsOnceAndPreservesUnrelatedBindingsAndSnapshot() {
        var replace=skill();var add=skill();var skip=skill();var unrelated=skill();
        long id=expert(List.of(replace[0],skip[1],unrelated[1]),"PUBLISHED");
        long published=publishFixture(id,List.of(replace[0],skip[1],unrelated[1]));
        var snapshot=session.getMapper(ExpertMapper.class).version(published).getSkillVersionIds();
        var preview=service.preview(batch(List.of(replace,add,skip),List.of(id)),901L);
        assertThat(preview.targets()).hasSize(3);
        assertThat(preview.items().getFirst().changes()).extracting(Change::action).containsExactly("REPLACE","ADD","SKIP");
        var result=service.commit(preview.batchId(),901L);
        assertThat(result.items()).extracting(Result::status).containsExactly("SUCCESS");
        var updated=session.getMapper(ExpertMapper.class).get(id);
        assertThat(updated.getRevision()).isEqualTo(1);
        assertThat(updated.isDraftChanged()).isTrue();assertThat(updated.getStatus()).isEqualTo("PUBLISHED");
        assertThat(updated.getSystemPrompt()).isEqualTo("Keep prompt");assertThat(updated.getMcpVersionIds()).isEqualTo("[]");
        assertThat(updated.getSkillVersionIds()).isEqualTo("["+skip[1]+","+unrelated[1]+","+replace[1]+","+add[1]+"]");
        assertThat(session.getMapper(ExpertMapper.class).version(published).getSkillVersionIds()).isEqualTo(snapshot);
        assertThat(service.commit(preview.batchId(),901L)).isEqualTo(result);
        assertThat(service.history(1,50,901L).stream().filter(h -> h.batchId().equals(preview.batchId())).findFirst().orElseThrow().targets()).hasSize(3);
    }
    @Test void batchCandidatesIncludePartiallyBoundExpertsAndCheckCombinedLimit() {
        var first=skill();var second=skill();var prefix=UUID.randomUUID().toString();
        long partial=expert(List.of(first[1]),"PUBLISHED"),full=expert(List.of(first[1],second[1]),"PUBLISHED"),disabled=expert(List.of(),"DISABLED");
        List<Long> bindings=new ArrayList<>();for(int i=0;i<29;i++) bindings.add(skill()[1]);
        long limited=expert(bindings,"DRAFT");
        jdbc.update("UPDATE expert SET name=? WHERE id IN (?,?,?,?)",prefix,partial,full,disabled,limited);
        var input=batch(List.of(first,second),List.of(partial,limited));
        var page=service.candidates(input.targets(),prefix,1,50,901L);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(Candidate::expertId).containsExactly(limited,partial);
        assertThat(page.items().getFirst().action()).isEqualTo("BLOCKED");
        var preview=service.preview(input,901L);
        assertThatThrownBy(() -> service.commit(preview.batchId(),901L)).hasMessageContaining("不可分配");
        assertThat(session.getMapper(ExpertMapper.class).get(partial).getRevision()).isZero();
    }
    @Test void batchRejectsDuplicateSkillsAndInvalidNonFirstTargetBeforeWriting() {
        var first=skill();var second=skill();long id=expert(List.of(),"DRAFT");
        assertThatThrownBy(() -> service.preview(batch(List.of(first,first),List.of(id)),901L)).hasMessageContaining("不同 Skill");
        var tooMany=new ArrayList<SkillExpertBatchAssignmentDTO.Target>();
        for(int i=0;i<31;i++) tooMany.add(new SkillExpertBatchAssignmentDTO.Target((long)i,(long)i));
        assertThatThrownBy(() -> service.preview(new SkillExpertBatchAssignmentDTO(tooMany,List.of(id)),901L)).hasMessageContaining("1～30");
        var preview=service.preview(batch(List.of(first,second),List.of(id)),901L);
        jdbc.update("UPDATE skill_version SET status='DISABLED' WHERE id=?",second[1]);
        assertThatThrownBy(() -> service.commit(preview.batchId(),901L)).hasMessageContaining("停用");
        assertThat(session.getMapper(ExpertMapper.class).get(id).getSkillVersionIds()).isEqualTo("[]");
    }
    @Test void batchRollsBackAllSkillsForFailedExpertAndStillProcessesOtherExperts() {
        var first=skill();var second=skill();long failed=expert(List.of(first[0]),"PUBLISHED"),ok=expert(List.of(),"DRAFT");
        var preview=service.preview(batch(List.of(first,second),List.of(failed,ok)),901L);
        jdbc.execute("CREATE TRIGGER reject_batch_result BEFORE INSERT ON skill_expert_assignment_item FOR EACH ROW BEGIN IF NEW.expert_id="+failed+" AND JSON_UNQUOTE(JSON_EXTRACT(NEW.payload,'$.status'))='SUCCESS' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture result failure'; END IF; END");
        try {
            var result=service.commit(preview.batchId(),901L);
            assertThat(result.items()).extracting(Result::status).containsExactly("FAILED","SUCCESS");
            assertThat(session.getMapper(ExpertMapper.class).get(failed).getSkillVersionIds()).isEqualTo("["+first[0]+"]");
            assertThat(session.getMapper(ExpertMapper.class).get(failed).getRevision()).isZero();
            assertThat(session.getMapper(ExpertMapper.class).get(ok).getSkillVersionIds()).isEqualTo("["+first[1]+","+second[1]+"]");
        } finally {jdbc.execute("DROP TRIGGER reject_batch_result");}
    }
    @Test void batchConcurrentReplayDoesNotConflictWithItsOwnDraftRevision() throws Exception {
        var first=skill();var second=skill();long id=expert(List.of(),"PUBLISHED");
        var preview=service.preview(batch(List.of(first,second),List.of(id)),901L);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var one=executor.submit(() -> service.commit(preview.batchId(),901L));
            var two=executor.submit(() -> service.commit(preview.batchId(),901L));
            assertThat(one.get(20,TimeUnit.SECONDS).items().getFirst().status()).isEqualTo("SUCCESS");
            assertThat(two.get(20,TimeUnit.SECONDS).items().getFirst().status()).isEqualTo("SUCCESS");
        }
        assertThat(session.getMapper(ExpertMapper.class).get(id).getRevision()).isEqualTo(1);
    }
    @Test void batchRevisionConflictChangesNoneOfTheSelectedSkills() {
        var first=skill();var second=skill();long id=expert(List.of(first[0]),"PUBLISHED");
        var preview=service.preview(batch(List.of(first,second),List.of(id)),901L);
        jdbc.update("UPDATE expert SET system_prompt='Concurrent edit',revision=revision+1 WHERE id=?",id);
        assertThat(service.commit(preview.batchId(),901L).items().getFirst().status()).isEqualTo("FAILED");
        var expert=session.getMapper(ExpertMapper.class).get(id);
        assertThat(expert.getSkillVersionIds()).isEqualTo("["+first[0]+"]");assertThat(expert.getSystemPrompt()).isEqualTo("Concurrent edit");
    }
    @Test void restoresSingleSkillPreviewPersistedBeforeBatchSupport() {
        var skill=skill();long id=expert(List.of(),"DRAFT");var preview=preview(skill,List.of(id));
        jdbc.update("UPDATE skill_expert_assignment_batch SET payload=JSON_REMOVE(payload,'$.targets','$.items[0].changes') WHERE id=?",preview.batchId());
        assertThat(service.commit(preview.batchId(),901L).items().getFirst().status()).isEqualTo("SUCCESS");
        assertThat(service.submission(preview.batchId(),901L).targets()).hasSize(1);
    }
    private SkillExpertBatchAssignmentDTO batch(List<Long[]> skills,List<Long> ids) {
        return new SkillExpertBatchAssignmentDTO(skills.stream().map(s -> new SkillExpertBatchAssignmentDTO.Target(s[2],s[1])).toList(),ids);
    }
    private Preview preview(Long[] skill,List<Long> ids){return service.preview(new SkillExpertAssignmentDTO(skill[2],skill[1],ids),901L);}
    private Long[] skill(){
        var mapper=session.getMapper(SkillMapper.class);var skill=new SkillPO();skill.setSkillName(UUID.randomUUID().toString());skill.setDescription("Fixture");skill.setCreatedBy(901L);mapper.insertSkill(skill);
        var ids=new Long[3];ids[2]=skill.getId();
        for(int i=0;i<2;i++){var v=new SkillVersionPO();v.setSkillId(skill.getId());v.setVersion(""+(i+1));v.setStoragePath("fixture.zip");v.setSha256("a".repeat(64));v.setFileSize(1L);v.setCreatedBy(901L);mapper.insertVersion(v);ids[i]=v.getId();if(i==0)mapper.updateVersionStatus(v.getId(),"DISABLED");}
        return ids;
    }
    private long expert(List<Long> ids,String status){
        var e=new ExpertPO();e.setName(UUID.randomUUID().toString());e.setDescription("Fixture");e.setSystemPrompt("Keep prompt");e.setSkillVersionIds(ids.toString().replace(" ",""));e.setMcpVersionIds("[]");e.setCreatedBy(901L);
        session.getMapper(ExpertMapper.class).insert(e);jdbc.update("UPDATE expert SET status=?,draft_changed=0 WHERE id=?",status,e.getId());return e.getId();
    }
    private long publishFixture(long expert,List<Long> ids){
        var v=new ExpertVersionPO();v.setExpertId(expert);v.setVersionNo(1L);v.setName("Published fixture");v.setDescription("");v.setSystemPrompt("Published prompt");v.setSkillVersionIds(ids.toString().replace(" ",""));v.setMcpVersionIds("[]");v.setCompatibleUpgrade(false);
        session.getMapper(ExpertMapper.class).publish(v);jdbc.update("UPDATE expert SET published_version_id=? WHERE id=?",v.getId(),expert);return v.getId();
    }
}
