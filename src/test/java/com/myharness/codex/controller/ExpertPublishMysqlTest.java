package com.myharness.codex.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.exception.GlobalExceptionHandler;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.security.UserPrincipal;
import com.myharness.codex.service.ExpertService;
import com.myharness.codex.service.McpConfigurationService;
import com.myharness.codex.support.IsolatedMysql;
import java.util.List;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The installation schema must support the same publish API used by the administrator UI. */
@EnabledIfSystemProperty(named = "mysql.isolated.integration", matches = "true")
class ExpertPublishMysqlTest {
    @ParameterizedTest(name = "publish compatible expert versions; repair existing schema = {0}")
    @ValueSource(booleans = {false, true})
    void administratorCanPublishVersionsAfterInstallationOrCompatibleUpgradeRepair(boolean repairExistingSchema) throws Exception {
        try (IsolatedMysql mysql = IsolatedMysql.start()) {
            mysql.applyResource("db/schema.sql");
            mysql.applyResource("db/seed-rbac.sql");
            JdbcTemplate jdbc = new JdbcTemplate(mysql.dataSource());
            if (repairExistingSchema) {
                // Reproduce the installed schema that misplaced the flag on the draft aggregate.
                mysql.execute("ALTER TABLE expert_version DROP COLUMN compatible_upgrade",
                        "ALTER TABLE expert ADD COLUMN compatible_upgrade TINYINT NOT NULL DEFAULT 0 AFTER mcp_version_ids");
            }
            jdbc.update("INSERT INTO sys_user(id,email,password_hash,display_name,status,email_verified_at,activated_at) "
                    + "VALUES(901,'expert-publish-fixture@example.test','unused-test-hash','Publish fixture administrator','ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT 901,id FROM sys_role WHERE role_code='SYS_ADMIN'");

            Configuration configuration = new Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            for (Class<?> mapper : List.of(ExpertMapper.class, ProjectMapper.class, ConversationMapper.class,
                    SkillMapper.class, AgentDeviceMapper.class, RbacMapper.class)) configuration.addMapper(mapper);
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(mysql.dataSource());
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new ClassPathResource("mapper/SysUserMapper.xml"));
            SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
            RbacMapper rbac = session.getMapper(RbacMapper.class);
            AuthorizationService access = new AuthorizationService(rbac, session.getMapper(SysUserMapper.class));
            McpConfigurationService mcp = mock(McpConfigurationService.class);
            when(mcp.runtimes(List.of())).thenReturn(List.of());
            ObjectMapper json = new ObjectMapper().findAndRegisterModules();
            ExpertService service = new ExpertService(session.getMapper(ExpertMapper.class), session.getMapper(ProjectMapper.class),
                    session.getMapper(ConversationMapper.class), session.getMapper(SkillMapper.class), session.getMapper(AgentDeviceMapper.class),
                    rbac, access, new TransactionTemplate(new DataSourceTransactionManager(mysql.dataSource())), json, new AgentProperties(), mcp);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExpertController(service))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();

            UserContext.set(new UserPrincipal(901L, "expert-publish-fixture@example.test", "Publish fixture administrator"));
            try {
                var created = mvc.perform(post("/api/v1/admin/experts").contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Publish regression expert","description":"Isolated test fixture",
                                         "systemPrompt":"Help with fixture tasks.","skillVersionIds":[],"mcpBindings":[],"knowledgeBindings":[]}
                                        """))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT")).andReturn();
                var draft = json.readTree(created.getResponse().getContentAsString()).path("data");
                long expertId = draft.path("id").asLong();
                long revision = draft.path("revision").asLong();

                if (repairExistingSchema) {
                    mysql.applyResource("db/migration-expert-compatible-upgrade.sql");
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expert WHERE id=?", Integer.class, expertId)).isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT name FROM expert WHERE id=?", String.class, expertId)).isEqualTo("Publish regression expert");
                    assertThat(jdbc.queryForObject("SELECT system_prompt FROM expert WHERE id=?", String.class, expertId)).isEqualTo("Help with fixture tasks.");
                    assertThat(jdbc.queryForObject("SELECT revision FROM expert WHERE id=?", Long.class, expertId)).isEqualTo(revision);
                    assertThat(jdbc.queryForObject("SELECT status FROM expert WHERE id=?", String.class, expertId)).isEqualTo("DRAFT");
                }

                var published = mvc.perform(post("/api/v1/admin/experts/{id}/publish", expertId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"revision\":" + revision + ",\"compatibleUpgrade\":false}"))
                        .andReturn();
                Throwable cause = published.getResolvedException();
                while (cause != null && cause.getCause() != null) cause = cause.getCause();
                assertThat(published.getResponse().getStatus())
                        .as("Publish API must return 200; underlying error: %s", cause == null ? "none" : cause.getMessage())
                        .isEqualTo(200);

                var response = json.readTree(published.getResponse().getContentAsString());
                assertThat(response.path("status").asText()).isEqualTo("success");
                assertThat(response.path("code").asInt()).isEqualTo(200);
                assertThat(response.path("data").path("status").asText()).isEqualTo("PUBLISHED");
                long versionId = response.path("data").path("publishedVersionId").asLong();
                long publishedRevision = response.path("data").path("revision").asLong();
                assertThat(versionId).isPositive();
                assertThat(publishedRevision).isEqualTo(revision + 1);
                assertThat(jdbc.queryForObject("SELECT status FROM expert WHERE id=?", String.class, expertId)).isEqualTo("PUBLISHED");
                assertThat(jdbc.queryForObject("SELECT published_version_id FROM expert WHERE id=?", Long.class, expertId)).isEqualTo(versionId);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expert_version WHERE expert_id=?", Integer.class, expertId)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT version_no FROM expert_version WHERE id=?", Integer.class, versionId)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT compatible_upgrade FROM expert_version WHERE id=?", Boolean.class, versionId)).isFalse();

                var edited = mvc.perform(put("/api/v1/admin/experts/{id}/draft", expertId).contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Publish regression expert","description":"Compatible second version",
                                         "systemPrompt":"Help with fixture tasks and concise explanations.","skillVersionIds":[],
                                         "mcpBindings":[],"knowledgeBindings":[],"revision":%d}
                                        """.formatted(publishedRevision)))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PUBLISHED")).andExpect(jsonPath("$.data.draftChanged").value(true)).andReturn();
                long secondDraftRevision = json.readTree(edited.getResponse().getContentAsString()).path("data").path("revision").asLong();
                assertThat(secondDraftRevision).isEqualTo(publishedRevision + 1);
                var secondPublished = mvc.perform(post("/api/v1/admin/experts/{id}/publish", expertId).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"revision\":" + secondDraftRevision + ",\"compatibleUpgrade\":true}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                        .andExpect(jsonPath("$.data.status").value("PUBLISHED")).andReturn();
                var secondVersion = json.readTree(secondPublished.getResponse().getContentAsString()).path("data");
                long secondVersionId = secondVersion.path("publishedVersionId").asLong();
                long secondPublishedRevision = secondVersion.path("revision").asLong();
                assertThat(secondVersionId).isGreaterThan(versionId);
                assertThat(secondPublishedRevision).isEqualTo(secondDraftRevision + 1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expert_version WHERE expert_id=?", Integer.class, expertId)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT version_no FROM expert_version WHERE id=?", Integer.class, secondVersionId)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT compatible_upgrade FROM expert_version WHERE id=?", Boolean.class, secondVersionId)).isTrue();
                assertThat(jdbc.queryForObject("SELECT compatible_upgrade FROM expert_version WHERE id=?", Boolean.class, versionId)).isFalse();
                assertThat(jdbc.queryForObject("SELECT system_prompt FROM expert_version WHERE id=?", String.class, versionId)).isEqualTo("Help with fixture tasks.");
                assertThat(jdbc.queryForObject("SELECT system_prompt FROM expert_version WHERE id=?", String.class, secondVersionId)).isEqualTo("Help with fixture tasks and concise explanations.");
                assertThat(jdbc.queryForObject("SELECT published_version_id FROM expert WHERE id=?", Long.class, expertId)).isEqualTo(secondVersionId);
                assertThat(jdbc.queryForObject("SELECT revision FROM expert WHERE id=?", Long.class, expertId)).isEqualTo(secondPublishedRevision);
                assertThat(jdbc.queryForObject("SELECT status FROM expert WHERE id=?", String.class, expertId)).isEqualTo("PUBLISHED");
            } finally {
                UserContext.clear();
            }
        }
    }
}
