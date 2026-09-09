package com.myharness.codex.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** CTE fixtures shadow the business tables; every database operation is a read-only SELECT. */
@EnabledIfSystemProperty(named = "mysql.integration", matches = "true")
class ConversationStatusProjectionMysqlTest {
    private static final String FIXTURES = """
            WITH conversation_ids AS (
                SELECT 1 id,7 user_id,9 project_id UNION ALL SELECT 2,7,9
                UNION ALL SELECT 3,7,9 UNION ALL SELECT 4,7,9
                UNION ALL SELECT 5,8,9 UNION ALL SELECT 6,7,10
                UNION ALL SELECT 7,7,9
            ), conversation AS (
                SELECT id,user_id,10 device_id,20 workspace_id,project_id,CONCAT('Conversation ',id) title,
                       NULL codex_thread_id,'ACTIVE' status,'2026-09-09 12:00:00' last_activity_at,
                       NULL selected_expert_id,NULL selected_expert_version_id,0 expert_selection_revision,
                       NULL expert_runtime_key,NULL model_runtime_key FROM conversation_ids
            ), agent_device AS (SELECT 10 id,'device-1' device_code,'Device' device_name),
            agent_workspace AS (SELECT 20 id,'Workspace' workspace_name,'D:/Workspace' root_path),
            codex_project AS (SELECT 9 id,'Project' project_name UNION ALL SELECT 10,'Other Project'),
            conversation_turn AS (
                SELECT 101 id,1 conversation_id,'FAILED' status,'Old failure' failure_message
                UNION ALL SELECT 102,1,'RUNNING',NULL
                UNION ALL SELECT 201,2,'RUNNING',NULL
                UNION ALL SELECT 202,2,'COMPLETED',NULL
                UNION ALL SELECT 301,3,'FAILED','Agent connection was lost'
                UNION ALL SELECT 501,5,'RUNNING',NULL
                UNION ALL SELECT 601,6,'RUNNING',NULL
                UNION ALL SELECT 701,7,'COMPLETED',NULL
            ), conversation_message AS (
                SELECT 101 turn_id,'ASSISTANT' role,'INCOMPLETE' status
                UNION ALL SELECT 202,'ASSISTANT','INCOMPLETE'
                UNION ALL SELECT 301,'USER','INCOMPLETE'
                UNION ALL SELECT 701,'ASSISTANT','COMPLETED'
            )
            """;

    @Test
    void listProjectsLatestTurnForEveryOwnedConversationWithoutDroppingEmptyConversations() {
        withConnection(connection -> {
            String sql = mapperSql("selectProjectConversations", Long.class, Long.class, String.class, int.class, long.class)
                    .replace("#{keyword}", "''").replace("#{limit}", "20").replace("#{offset}", "0");
            List<Long> ids = new ArrayList<>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(FIXTURES + sql)) {
                while (rows.next()) {
                    long id = rows.getLong("id");
                    ids.add(id);
                    switch ((int) id) {
                        case 1 -> {
                            assertThat(rows.getLong("latest_turn_id")).isEqualTo(102L);
                            assertThat(rows.getString("latest_turn_status")).isEqualTo("RUNNING");
                            assertThat(rows.getString("latest_turn_failure_message")).isNull();
                            assertThat(rows.getBoolean("latest_turn_has_incomplete_message")).isFalse();
                        }
                        case 2 -> {
                            assertThat(rows.getLong("latest_turn_id")).isEqualTo(202L);
                            assertThat(rows.getString("latest_turn_status")).isEqualTo("COMPLETED");
                            assertThat(rows.getBoolean("latest_turn_has_incomplete_message")).isTrue();
                        }
                        case 3 -> {
                            assertThat(rows.getLong("latest_turn_id")).isEqualTo(301L);
                            assertThat(rows.getString("latest_turn_status")).isEqualTo("FAILED");
                            assertThat(rows.getString("latest_turn_failure_message")).isEqualTo("Agent connection was lost");
                            assertThat(rows.getBoolean("latest_turn_has_incomplete_message")).isFalse();
                        }
                        case 4 -> {
                            assertThat(rows.getObject("latest_turn_id")).isNull();
                            assertThat(rows.getString("latest_turn_status")).isNull();
                            assertThat(rows.getString("latest_turn_failure_message")).isNull();
                            assertThat(rows.getBoolean("latest_turn_has_incomplete_message")).isFalse();
                        }
                        case 7 -> {
                            assertThat(rows.getLong("latest_turn_id")).isEqualTo(701L);
                            assertThat(rows.getString("latest_turn_status")).isEqualTo("COMPLETED");
                            assertThat(rows.getBoolean("latest_turn_has_incomplete_message")).isFalse();
                        }
                        default -> throw new AssertionError("Unexpected Conversation " + id);
                    }
                }
            }
            assertThat(ids).containsExactly(7L,4L,3L,2L,1L);
        });
    }

    @Test
    void detailProjectsLatestFailureAndRetainsOwnershipAndProjectFilters() {
        withConnection(connection -> {
            String sql = mapperSql("selectOwnedConversation", Long.class, Long.class, Long.class);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(FIXTURES + sql.replace("#{id}", "3"))) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("latest_turn_id")).isEqualTo(301L);
                assertThat(rows.getString("latest_turn_status")).isEqualTo("FAILED");
                assertThat(rows.getString("latest_turn_failure_message")).isEqualTo("Agent connection was lost");
                assertThat(rows.next()).isFalse();
            }
            for (long forbidden : List.of(5L,6L)) {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(FIXTURES + sql.replace("#{id}", Long.toString(forbidden)))) {
                    assertThat(rows.next()).isFalse();
                }
            }
        });
    }

    private String mapperSql(String method, Class<?>... parameterTypes) throws Exception {
        return String.join(" ", ConversationMapper.class.getMethod(method, parameterTypes).getAnnotation(Select.class).value())
                .replace("#{userId}", "7").replace("#{projectId}", "9");
    }

    private void withConnection(SqlCheck check) {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.sql.init.mode=never")
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class)).run(context -> {
                    assertThat(context).hasNotFailed();
                    try (var connection = context.getBean(DataSource.class).getConnection()) {
                        connection.setReadOnly(true);
                        check.run(connection);
                    }
                });
    }

    @FunctionalInterface
    private interface SqlCheck {
        void run(Connection connection) throws Exception;
    }
}
