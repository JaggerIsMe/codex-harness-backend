package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Run only against an explicitly supplied disposable database with schema.sql already applied. */
@EnabledIfSystemProperty(named="expert.mysql.url",matches=".+")
class ExpertDatabaseTest {
    @Test void publishedBindingsSelectionsSnapshotsAndDownloadAuthorizationRoundTrip() throws Exception {
        var ds=new DriverManagerDataSource(System.getProperty("expert.mysql.url"),"root","");
        var config=new org.apache.ibatis.session.Configuration(); config.setMapUnderscoreToCamelCase(true);
        for(var type:List.of(ExpertMapper.class,ProjectMapper.class,ConversationMapper.class,SkillMapper.class,AgentDeviceMapper.class,RbacMapper.class,SysUserMapper.class)) config.addMapper(type);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/SysUserMapper.xml"));
        var session=new SqlSessionTemplate(factory.getObject());var jdbc=new JdbcTemplate(ds);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var mapper=session.getMapper(ExpertMapper.class);var conversations=session.getMapper(ConversationMapper.class);var skills=session.getMapper(SkillMapper.class);var rbac=session.getMapper(RbacMapper.class);
        var properties=new AgentProperties();properties.setPublicBaseUrl("http://localhost:9010");
        var mcp=org.mockito.Mockito.mock(McpConfigurationService.class);
        org.mockito.Mockito.when(mcp.runtimes(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of());
        var service=new ExpertService(mapper,session.getMapper(ProjectMapper.class),conversations,skills,session.getMapper(AgentDeviceMapper.class),rbac,
                new AuthorizationService(rbac,session.getMapper(SysUserMapper.class)),tx,new ObjectMapper(),properties,mcp);
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.update("INSERT INTO sys_user(id,email,password_hash,display_name,must_change_password) VALUES(901,'expert-test-admin@example.test','unused','Test',0),(902,'expert-test-other@example.test','unused','Other',0)");
            jdbc.update("INSERT INTO sys_user_role SELECT 901,id FROM sys_role WHERE role_code='SYS_ADMIN'");
            jdbc.update("INSERT INTO sys_user_role SELECT 902,id FROM sys_role WHERE role_code='USER'");
            jdbc.update("INSERT INTO agent_device(id,device_code,device_name,token_hash,status,isolation_mode,project_experts) VALUES(901,'expert-test-device','Test',REPEAT('a',64),'ONLINE','LINUX_PROJECT_PROFILE_V1',1),(902,'expert-other-device','Other',REPEAT('b',64),'ONLINE','LINUX_PROJECT_PROFILE_V1',1)");
            jdbc.update("INSERT INTO agent_workspace(id,device_id,workspace_name,root_path) VALUES(901,901,'expert-test','D:/test/expert'),(902,901,'other-project','D:/test/other')");
            jdbc.update("INSERT INTO codex_project(id,user_id,device_id,workspace_id,project_name) VALUES(901,901,901,901,'Test project'),(902,902,901,902,'Other project')");
            jdbc.update("INSERT INTO user_device_assignment(user_id,device_id,assigned_by) VALUES(901,901,901),(902,901,901)");
            jdbc.update("INSERT INTO conversation(id,user_id,device_id,workspace_id,project_id,title,codex_thread_id) VALUES(901,901,901,901,901,'Test','test-thread')");
            var skill=new SkillPO();skill.setSkillName("expert-test-skill");skill.setDescription("test");skill.setCreatedBy(901L);skills.insertSkill(skill);
            var skillVersion=new SkillVersionPO();skillVersion.setSkillId(skill.getId());skillVersion.setVersion("1.0");skillVersion.setSha256("a".repeat(64));skillVersion.setStoragePath("D:/test/skill.zip");skillVersion.setFileSize(1L);skillVersion.setCreatedBy(901L);skills.insertVersion(skillVersion);
            var draft=new ExpertDraftDTO();draft.setName("专家 A");draft.setDescription("测试专家");draft.setSystemPrompt("Expert A instructions");draft.setSkillVersionIds(List.of(skillVersion.getId()));
            var created=service.save(null,draft,901L);var published=service.publish(created.id(),created.revision(),false,901L);
            jdbc.update("INSERT INTO user_expert_assignment(user_id,expert_id,assigned_by) VALUES(901,?,901),(902,?,901)",created.id(),created.id());
            assertEquals("PUBLISHED",published.status());assertNull(service.list("",false,902L).getFirst().systemPrompt());
            assertEquals(1,service.versions(created.id(),902L).size());assertFalse(service.versions(created.id(),902L).getFirst().compatibleUpgrade());
            var bind=new ExpertBindingDTO();bind.setExpertVersionId(published.publishedVersionId());bind.setProjectRevision(0L);
            var project=service.bind(901L,bind,901L);assertEquals(1L,project.projectRevision());
            assertEquals(1,mapper.requiredSkills(901L).size());assertNotNull(skills.selectDownload(skillVersion.getId(),901L));assertNull(skills.selectDownload(skillVersion.getId(),902L));
            assertTrue(service.projectExperts(902L,902L).experts().isEmpty());
            var locked=conversations.lockConversation(901L);service.bindAtCreation(locked,created.id());
            jdbc.update("UPDATE conversation SET selected_expert_id=?,selected_expert_version_id=?,expert_selection_revision=1 WHERE id=901",locked.getSelectedExpertId(),locked.getSelectedExpertVersionId());
            locked=conversations.lockConversation(901L);assertEquals(created.id(),service.selection(901L,901L,901L).expertId());
            var runtime=service.freeze(locked,mapper.lockProject(901L));
            assertEquals("Expert A instructions",runtime.getSystemPrompt());assertEquals(1,runtime.getSkills().size());
            var turn=new ConversationTurnPO();turn.setConversationId(901L);turn.setClientRequestId("expert-request");turn.setRequestHash("f".repeat(64));turn.setExpertVersionId(runtime.getExpertVersionId());turn.setExpertName(runtime.getName());turn.setExpertRuntime(service.write(runtime));conversations.insertTurn(turn);
            assertEquals(runtime.getExpertVersionId(),conversations.selectTurn(turn.getId()).getExpertVersionId());
            assertEquals("专家 A",mapper.turnExperts(901L).getFirst().expertName());
            assertEquals(turn.getId(),conversations.byClientRequest(901L,"expert-request").getId());
            assertEquals(1,mapper.activeTurns(901L));
            var now=java.time.LocalDateTime.now();
            assertEquals(0,conversations.replaceExpertThread(901L,902L,turn.getId(),"test-thread","new-thread",runtime.getRuntimeKey(),now));
            assertEquals(0,conversations.replaceExpertThread(901L,901L,turn.getId(),"stale-thread","new-thread",runtime.getRuntimeKey(),now));
            assertEquals(0,conversations.replaceExpertThread(901L,901L,turn.getId(),"test-thread","new-thread","b".repeat(64),now));
            assertEquals(1,conversations.replaceExpertThread(901L,901L,turn.getId(),"test-thread","new-thread",runtime.getRuntimeKey(),now));
            assertEquals(runtime.getRuntimeKey(),conversations.selectConversation(901L).getExpertRuntimeKey());
            assertEquals("new-thread",conversations.selectConversation(901L).getCodexThreadId());
            assertEquals(0,conversations.replaceExpertThread(901L,901L,turn.getId(),"test-thread","stale-replay",runtime.getRuntimeKey(),now));
            conversations.setTurnStarted(turn.getId(),901L,901L,"native-turn",now);
            assertEquals(0,conversations.replaceExpertThread(901L,901L,turn.getId(),"new-thread","late-thread",runtime.getRuntimeKey(),now));
            assertFalse(conversations.canRecreateExpertThread(901L,turn.getId()+1,runtime.getRuntimeKey()));
            conversations.finishTurn(turn.getId(),901L,901L,"COMPLETED",null,null,java.time.LocalDateTime.now());
            draft.setRevision(published.revision());draft.setSystemPrompt("Expert A compatible v2");
            var updated=service.save(created.id(),draft,901L);var compatible=service.publish(created.id(),updated.revision(),true,901L);
            var upgrade=new ExpertBindingDTO();upgrade.setExpertVersionId(compatible.publishedVersionId());upgrade.setProjectRevision(1L);
            service.bind(901L,upgrade,901L);
            var upgradedConversation=conversations.lockConversation(901L);
            assertEquals(compatible.publishedVersionId(),upgradedConversation.getSelectedExpertVersionId());
            var upgradedRuntime=service.freeze(upgradedConversation,mapper.lockProject(901L));assertTrue(upgradedRuntime.isCompatibleUpgrade());
            var upgradedTurn=new ConversationTurnPO();upgradedTurn.setConversationId(901L);upgradedTurn.setClientRequestId("compatible-upgrade");
            upgradedTurn.setRequestHash("e".repeat(64));upgradedTurn.setExpertVersionId(upgradedRuntime.getExpertVersionId());
            upgradedTurn.setExpertName(upgradedRuntime.getName());upgradedTurn.setExpertRuntime(service.write(upgradedRuntime));conversations.insertTurn(upgradedTurn);
            assertEquals(1,conversations.updateCompatibleExpertRuntime(901L,901L,upgradedTurn.getId(),"new-thread",runtime.getRuntimeKey(),upgradedRuntime.getRuntimeKey(),now));
            conversations.finishTurn(upgradedTurn.getId(),901L,901L,"COMPLETED",null,null,java.time.LocalDateTime.now());
            draft.setRevision(compatible.revision());draft.setSystemPrompt("Expert A breaking v3");
            var breakingDraft=service.save(created.id(),draft,901L);var breaking=service.publish(created.id(),breakingDraft.revision(),false,901L);
            var breakingUpgrade=new ExpertBindingDTO();breakingUpgrade.setExpertVersionId(breaking.publishedVersionId());breakingUpgrade.setProjectRevision(2L);
            service.bind(901L,breakingUpgrade,901L);
            assertEquals(compatible.publishedVersionId(),conversations.lockConversation(901L).getSelectedExpertVersionId());
            service.unbind(901L,created.id(),3L,901L);
            assertFalse(service.selection(901L,901L,901L).available());assertNull(skills.selectDownload(skillVersion.getId(),901L));
            assertEquals("专家 A",mapper.turnExperts(901L).getFirst().expertName());
        });
    }
}
