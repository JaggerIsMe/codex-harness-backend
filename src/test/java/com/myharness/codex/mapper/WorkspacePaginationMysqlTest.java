package com.myharness.codex.mapper;

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Read-only CTE fixtures shadow all business tables, including permissions and latest Turn state. */
@EnabledIfSystemProperty(named="mysql.integration",matches="true")
class WorkspacePaginationMysqlTest {
    private static final String FIXTURES="""
            WITH codex_project AS (
                SELECT id,IF(id=125,8,7) user_id,IF(id=124,11,10) device_id,20 workspace_id,
                       CONCAT('Project ',id) project_name,'ACTIVE' status,'WINDOWS_PROJECT_PROFILE' isolation_mode,
                       '2026-09-09 12:00:00' created_at,'2026-09-09 12:00:00' updated_at,NULL request_key
                FROM (__PROJECT_IDS__) numbers
            ), agent_device AS (
                SELECT 10 id,CAST('device-code' AS CHAR CHARACTER SET ascii) COLLATE ascii_bin device_code,
                       'Office computer' device_name,'ONLINE' status
                UNION ALL SELECT 11,CAST('revoked-device' AS CHAR CHARACTER SET ascii) COLLATE ascii_bin,
                       'Revoked computer','ONLINE'
            ), agent_workspace AS (
                SELECT 20 id,'workspace-name' workspace_name,'D:/work-root' root_path,'ENABLED' status,
                       NULL failure_code,NULL failure_message
            ), user_device_assignment AS (
                SELECT 7 user_id,10 device_id,'ENABLED' status
                UNION ALL SELECT 7,11,'DISABLED' UNION ALL SELECT 8,10,'ENABLED'
            ), conversation AS (
                SELECT id,IF(id=205,8,7) user_id,10 device_id,20 workspace_id,IF(id=204,10,9) project_id,
                       CASE WHEN id=1 THEN 'older-needle' WHEN id=205 THEN 'private-needle'
                            WHEN id=203 THEN 'literal_%_needle' ELSE CONCAT('Conversation ',id) END title,
                       NULL codex_thread_id,'ACTIVE' status,'2026-09-09 12:00:00' last_activity_at,
                       NULL selected_expert_id,NULL selected_expert_version_id,0 expert_selection_revision,
                       NULL expert_runtime_key,NULL model_runtime_key FROM (__CONVERSATION_IDS__) numbers
            ), conversation_turn AS (
                SELECT 1 id,1 conversation_id,'RUNNING' status,NULL failure_message
                UNION ALL SELECT 203,203,'COMPLETED',NULL
            ), conversation_message AS (
                SELECT 203 turn_id,'ASSISTANT' role,'COMPLETED' status
            )
            """.replace("__PROJECT_IDS__",numbers(125)).replace("__CONVERSATION_IDS__",numbers(205));

    private static final String UNICODE_SEARCH_FIXTURES=FIXTURES
            .replace("CONCAT('Project ',id) project_name",
                    "IF(id=9,'中文项目🧪',CONCAT('Project ',id)) project_name")
            .replace("'older-needle'","'中文会话🧪'");

    private static final String ACTIVE_PROJECT_FIXTURES=FIXTURES
            .replace("'2026-09-09 12:00:00' created_at",
                    "IF(id=122,'2026-09-10 15:00:00','2026-09-09 12:00:00') created_at")
            .replace("'2026-09-09 12:00:00' updated_at",
                    "IF(id=123,'2026-09-12 20:00:00','2026-09-09 12:00:00') updated_at")
            .replace("'2026-09-09 12:00:00' last_activity_at", """
                    CASE WHEN id=1 THEN '2026-09-10 14:00:00' WHEN id=203 THEN '2026-09-10 13:00:00'
                         WHEN id=204 THEN '2026-09-10 10:00:00' WHEN id=205 THEN '2026-09-11 20:00:00'
                         ELSE '2026-09-09 12:00:00' END last_activity_at
                    """);

    private final Configuration configuration=configuration();

    private static String numbers(int count) {
        return IntStream.rangeClosed(1,count).mapToObj(id -> "SELECT "+id+" id").collect(Collectors.joining(" UNION ALL "));
    }

    @Test
    void projectPagesAndCountsExcludeForeignOwnersAndRevokedAssignmentsBeyondTheFirstPage() {
        withConnection(connection -> {
            var args=arguments("",20,20);
            assertThat(count(connection,"ProjectMapper.countOwnedProjects",args)).isEqualTo(123);
            var page=ids(connection,"ProjectMapper.selectOwnedProjects",args);
            assertThat(page).hasSize(20).startsWith(103L).endsWith(84L);
            assertThat(ids(connection,"ProjectMapper.selectOwnedProjects",arguments("",20,120))).containsExactly(3L,2L,1L);
        });
    }

    @Test
    void recentConversationActivityMovesAnOlderProjectToTheFirstPageWithoutBreakingPageBoundaries() {
        withConnection(connection -> {
            assertThat(ids(connection,"ProjectMapper.selectOwnedProjects",arguments("",20,0))).doesNotContain(9L,10L);
            var first=ids(connection,ACTIVE_PROJECT_FIXTURES,"ProjectMapper.selectOwnedProjects",arguments("",20,0));
            var second=ids(connection,ACTIVE_PROJECT_FIXTURES,"ProjectMapper.selectOwnedProjects",arguments("",20,20));
            assertThat(first).hasSize(20).startsWith(122L,9L,10L,123L).endsWith(106L);
            assertThat(second).hasSize(20).startsWith(105L).endsWith(86L);
            assertThat(first).doesNotContainAnyElementsOf(second).doesNotContain(124L,125L);
            assertThat(count(connection,"ProjectMapper.countOwnedProjects",arguments("",20,0))).isEqualTo(123);
            assertThat(ids(connection,ACTIVE_PROJECT_FIXTURES,"ProjectMapper.selectOwnedProjects",arguments("",20,120)))
                    .containsExactly(3L,2L,1L);
        });
    }

    @Test
    void projectActivityUsesTheLatestOwnedConversationAndEmptyProjectsFallBackToCreationTime() {
        withConnection(connection -> {
            for(var expected:Map.of(9L,"2026-09-10 14:00:00",10L,"2026-09-10 10:00:00",
                    122L,"2026-09-10 15:00:00",123L,"2026-09-09 12:00:00").entrySet()) {
                var args=arguments("",20,0);args.put("id",expected.getKey());
                query(connection,ACTIVE_PROJECT_FIXTURES,"ProjectMapper.selectOwned",args,rows -> {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("last_activity_at")).isEqualTo(expected.getValue());
                    assertThat(rows.next()).isFalse();return null;
                });
            }
            var args=arguments("",20,0);args.put("id",125L);
            assertThat(ids(connection,ACTIVE_PROJECT_FIXTURES,"ProjectMapper.selectOwned",args)).isEmpty();
            assertThat(ids(connection,ACTIVE_PROJECT_FIXTURES,"ProjectMapper.selectOwnedProjects",arguments("older-needle",20,0)))
                    .containsExactly(9L);
        });
    }

    @Test
    void projectSearchIncludesOwnedConversationTitlesAndEveryProjectFieldWithoutWildcardExpansion() {
        withConnection(connection -> {
            for(String keyword:List.of("older-needle","literal_%_needle")) {
                var args=arguments(keyword,20,0);
                assertThat(count(connection,"ProjectMapper.countOwnedProjects",args)).isEqualTo(1);
                assertThat(ids(connection,"ProjectMapper.selectOwnedProjects",args)).containsExactly(9L);
            }
            for(String keyword:List.of("private-needle","' OR 1=1 --")) {
                var args=arguments(keyword,20,0);
                assertThat(count(connection,"ProjectMapper.countOwnedProjects",args)).isZero();
                assertThat(ids(connection,"ProjectMapper.selectOwnedProjects",args)).isEmpty();
            }
            for(String keyword:List.of("Office computer","device-code","workspace-name","work-root"))
                assertThat(count(connection,"ProjectMapper.countOwnedProjects",arguments(keyword,20,0))).isEqualTo(123);
            assertThat(ids(connection,"ProjectMapper.selectOwnedProjects",arguments("Project 123",20,0))).containsExactly(123L);
        });
    }

    @Test
    void conversationPagesContinuePastOneHundredWithMatchingCountsAndStableTieOrdering() {
        withConnection(connection -> {
            var args=arguments("",20,20);
            assertThat(count(connection,"ConversationMapper.countProjectConversations",args)).isEqualTo(203);
            assertThat(ids(connection,"ConversationMapper.selectProjectConversations",args)).hasSize(20).startsWith(183L).endsWith(164L);
            assertThat(ids(connection,"ConversationMapper.selectProjectConversations",arguments("",20,200))).containsExactly(3L,2L,1L);
            assertThat(ids(connection,"ConversationMapper.selectProjectConversations",arguments("",20,220))).isEmpty();
        });
    }

    @Test
    void conversationSearchFindsOldTitlesAndReturnsAllOwnedConversationsWhenTheProjectMatches() {
        withConnection(connection -> {
            var args=arguments("older-needle",20,0);
            assertThat(count(connection,"ConversationMapper.countProjectConversations",args)).isEqualTo(1);
            assertThat(ids(connection,"ConversationMapper.selectProjectConversations",args)).containsExactly(1L);
            assertThat(ids(connection,"ConversationMapper.selectProjectConversations",arguments("_%_",20,0))).containsExactly(203L);
            assertThat(count(connection,"ConversationMapper.countProjectConversations",arguments("private-needle",20,0))).isZero();
            for(String keyword:List.of("Project 9","Office computer","device-code","workspace-name","work-root")) {
                args=arguments(keyword,20,200);
                assertThat(count(connection,"ConversationMapper.countProjectConversations",args)).isEqualTo(203);
                assertThat(ids(connection,"ConversationMapper.selectProjectConversations",args)).containsExactly(3L,2L,1L);
            }
        });
    }

    @Test
    void projectSearchHandlesUnicodeNamesTitlesAndNoMatchesAlongsideAsciiDeviceCodes() {
        withConnection(connection -> {
            for(String keyword:List.of("中文项目","中文会话","🧪")) {
                var args=arguments(keyword,20,0);
                assertThat(count(connection,UNICODE_SEARCH_FIXTURES,"ProjectMapper.countOwnedProjects",args)).isEqualTo(1);
                assertThat(ids(connection,UNICODE_SEARCH_FIXTURES,"ProjectMapper.selectOwnedProjects",args)).containsExactly(9L);
            }
            for(String keyword:List.of("测试不存在","DEVICE-CODE")) {
                var missing=arguments(keyword,20,0);
                assertThat(count(connection,UNICODE_SEARCH_FIXTURES,"ProjectMapper.countOwnedProjects",missing)).isZero();
                assertThat(ids(connection,UNICODE_SEARCH_FIXTURES,"ProjectMapper.selectOwnedProjects",missing)).isEmpty();
            }
            assertThat(count(connection,UNICODE_SEARCH_FIXTURES,"ProjectMapper.countOwnedProjects",arguments("device-code",20,0)))
                    .isEqualTo(123);
        });
    }

    @Test
    void conversationSearchHandlesUnicodeProjectAndConversationMatchesAlongsideAsciiDeviceCodes() {
        withConnection(connection -> {
            var title=arguments("中文会话",20,0);
            assertThat(count(connection,UNICODE_SEARCH_FIXTURES,"ConversationMapper.countProjectConversations",title)).isEqualTo(1);
            assertThat(ids(connection,UNICODE_SEARCH_FIXTURES,"ConversationMapper.selectProjectConversations",title)).containsExactly(1L);
            for(String keyword:List.of("中文项目","🧪","device-code")) {
                var args=arguments(keyword,20,200);
                assertThat(count(connection,UNICODE_SEARCH_FIXTURES,"ConversationMapper.countProjectConversations",args)).isEqualTo(203);
                assertThat(ids(connection,UNICODE_SEARCH_FIXTURES,"ConversationMapper.selectProjectConversations",args)).containsExactly(3L,2L,1L);
            }
            for(String keyword:List.of("测试不存在","DEVICE-CODE")) {
                var missing=arguments(keyword,20,0);
                assertThat(count(connection,UNICODE_SEARCH_FIXTURES,"ConversationMapper.countProjectConversations",missing)).isZero();
                assertThat(ids(connection,UNICODE_SEARCH_FIXTURES,"ConversationMapper.selectProjectConversations",missing)).isEmpty();
            }
        });
    }

    @Test
    void statusQueryOnlyReadsRequestedOwnedIdsAndAnEmptyIdSetCannotBecomeAnUnboundedRead() {
        withConnection(connection -> {
            var args=arguments("",20,0);
            args.put("ids",List.of(1L,203L));
            assertThat(ids(connection,"ConversationMapper.selectConversationStatuses",args)).containsExactly(203L,1L);
            query(connection,"ConversationMapper.selectConversationStatuses",args,rows -> {
                assertThat(rows.next()).isTrue();assertThat(rows.getString("latest_turn_status")).isEqualTo("COMPLETED");
                assertThat(rows.next()).isTrue();assertThat(rows.getString("latest_turn_status")).isEqualTo("RUNNING");
                return null;
            });
            args.put("ids",List.of(1L,204L,205L));
            assertThat(ids(connection,"ConversationMapper.selectConversationStatuses",args)).containsExactly(1L);
            args.put("ids",List.of());
            assertThat(ids(connection,"ConversationMapper.selectConversationStatuses",args)).isEmpty();
            args.put("ids",null);
            assertThat(ids(connection,"ConversationMapper.selectConversationStatuses",args)).isEmpty();
        });
    }

    private static Configuration configuration() {
        var configuration=new Configuration();
        configuration.addMapper(ProjectMapper.class);configuration.addMapper(ConversationMapper.class);
        return configuration;
    }

    private Map<String,Object> arguments(String keyword,int limit,long offset) {
        var args=new HashMap<String,Object>();
        args.put("userId",7L);args.put("projectId",9L);args.put("keyword",keyword);args.put("limit",limit);args.put("offset",offset);
        return args;
    }

    private List<Long> ids(Connection connection,String method,Map<String,Object> args) throws Exception {
        return ids(connection,FIXTURES,method,args);
    }

    private List<Long> ids(Connection connection,String fixtures,String method,Map<String,Object> args) throws Exception {
        return query(connection,fixtures,method,args,rows -> {
            var ids=new ArrayList<Long>();while(rows.next()) ids.add(rows.getLong("id"));return ids;
        });
    }

    private long count(Connection connection,String method,Map<String,Object> args) throws Exception {
        return count(connection,FIXTURES,method,args);
    }

    private long count(Connection connection,String fixtures,String method,Map<String,Object> args) throws Exception {
        return query(connection,fixtures,method,args,rows -> {assertThat(rows.next()).isTrue();return rows.getLong(1);});
    }

    private <T> T query(Connection connection,String method,Map<String,Object> args,RowRead<T> read) throws Exception {
        return query(connection,FIXTURES,method,args,read);
    }

    private <T> T query(Connection connection,String fixtures,String method,Map<String,Object> args,RowRead<T> read) throws Exception {
        var mapped=configuration.getMappedStatement("com.myharness.codex.mapper."+method);
        var bound=mapped.getBoundSql(args);
        try(var statement=connection.prepareStatement(fixtures+bound.getSql())) {
            new DefaultParameterHandler(mapped,args,bound).setParameters(statement);
            try(var rows=statement.executeQuery()) {return read.run(rows);}
        }
    }

    private void withConnection(SqlCheck check) {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.sql.init.mode=never")
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class)).run(context -> {
                    assertThat(context).hasNotFailed();
                    try(var connection=context.getBean(DataSource.class).getConnection()) {
                        connection.setReadOnly(true);check.run(connection);
                    }
                });
    }

    @FunctionalInterface private interface SqlCheck {void run(Connection connection) throws Exception;}
    @FunctionalInterface private interface RowRead<T> {T run(ResultSet rows) throws Exception;}
}
