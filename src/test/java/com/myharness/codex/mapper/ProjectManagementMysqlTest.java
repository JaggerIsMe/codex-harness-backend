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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Read-only SQL plans and CTE fixtures; no deletes, updates or migrations are executed. */
@EnabledIfSystemProperty(named="mysql.integration",matches="true")
class ProjectManagementMysqlTest {
    private final Configuration configuration=new Configuration();

    ProjectManagementMysqlTest() {configuration.addMapper(ProjectManagementMapper.class);}

    @Test void mysqlAcceptsEveryScopedLockAndCleanupStatement() {
        withConnection(connection -> {
            // MySQL rejects plans containing FOR UPDATE in a READ ONLY transaction.
            // Plain EXPLAIN only plans these statements; it never executes their mutations.
            connection.setReadOnly(false);
            var args=Map.<String,Object>of("id",0L,"projectId",0L,"userId",0L,"deviceId",0L,"name","Plan only","title","Plan only");
            for(var method:ProjectManagementMapper.class.getDeclaredMethods()) {
                var statement=configuration.getMappedStatement(ProjectManagementMapper.class.getName()+"."+method.getName());
                var bound=statement.getBoundSql(args);
                try(var query=connection.prepareStatement("EXPLAIN "+bound.getSql())) {
                    new DefaultParameterHandler(statement,args,bound).setParameters(query);
                    try(var rows=query.executeQuery()) {assertThat(rows.next()).as(method.getName()).isTrue();}
                }
            }
        });
    }

    @Test void activityGuardIncludesPreparationRunningAndApprovalButAllowsAllTerminalStates() {
        withConnection(connection -> {
            var statement=configuration.getMappedStatement(ProjectManagementMapper.class.getName()+".lockActiveTurn");
            var args=Map.of("id",7L);var bound=statement.getBoundSql(args);
            // Remove only the locking clause: CTE fixtures have no durable rows to lock.
            String sql="WITH conversation_turn AS (SELECT 9 id,7 conversation_id,? status) "+bound.getSql().replace(" FOR UPDATE","");
            for(String state:List.of("CREATED","RUNNING","WAITING_APPROVAL","COMPLETED","FAILED","INTERRUPTED")) {
                try(var query=connection.prepareStatement(sql)) {
                    query.setString(1,state);query.setLong(2,7L);
                    try(var rows=query.executeQuery()) {
                        assertThat(rows.next()).as(state).isEqualTo(List.of("CREATED","RUNNING","WAITING_APPROVAL").contains(state));
                    }
                }
            }
        });
    }

    @Test void currentSchemaCascadesConversationHistoryAndApprovalRecords() {
        withConnection(connection -> {
            String sql="SELECT constraint_name,delete_rule FROM information_schema.referential_constraints " +
                    "WHERE constraint_schema=DATABASE() AND constraint_name IN " +
                    "('fk_conversation_turn_conversation','fk_conversation_message_conversation','fk_approval_request_conversation')";
            try(var statement=connection.prepareStatement(sql);var rows=statement.executeQuery()) {
                int count=0;
                while(rows.next()) {assertThat(rows.getString("delete_rule")).as(rows.getString("constraint_name")).isEqualTo("CASCADE");count++;}
                assertThat(count).isEqualTo(3);
            }
        });
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
}
