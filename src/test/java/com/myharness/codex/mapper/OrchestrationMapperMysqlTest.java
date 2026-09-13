package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.*;
import org.apache.ibatis.session.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Only connection-local temporary tables are written; no deployment migration is executed. */
@EnabledIfSystemProperty(named="mysql.orchestration",matches="true")
class OrchestrationMapperMysqlTest {
    @Test void durableClaimsLinksReceiptsAndRollbackUseRealMysql() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.sql.init.mode=never")
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class)).run(context -> {
                var ds=context.getBean(DataSource.class);
                try(var connection=ds.getConnection();var sql=connection.createStatement()) {
                    sql.execute("CREATE TEMPORARY TABLE conversation(id BIGINT UNSIGNED PRIMARY KEY,project_id BIGINT UNSIGNED,device_id BIGINT UNSIGNED)");
                    sql.execute("CREATE TEMPORARY TABLE conversation_turn(id BIGINT UNSIGNED PRIMARY KEY,conversation_id BIGINT UNSIGNED,status VARCHAR(32))");
                    String migration;
                    try(var input=getClass().getResourceAsStream("/db/migration-orchestration.sql")) {
                        migration=new String(input.readAllBytes(),StandardCharsets.UTF_8);
                    }
                    // MySQL TEMPORARY tables cannot have foreign keys. Preserve production column/index definitions.
                    migration=migration.replace("CREATE TABLE IF NOT EXISTS","CREATE TEMPORARY TABLE")
                        .replaceAll("(?m)^.*CONSTRAINT fk_orchestration.*\\r?\\n","")
                        .replaceAll(",\\s*\\) ENGINE",") ENGINE");
                    for(String statement:migration.split(";"))if(!statement.isBlank())sql.execute(statement);
                    // The same connection's temporary table shadows the real table during this ALTER.
                    try(var input=getClass().getResourceAsStream("/db/migration-workflow-canvas.sql")) {
                        sql.execute(new String(input.readAllBytes(),StandardCharsets.UTF_8));
                    }
                    sql.execute("INSERT INTO conversation VALUES(6,2,4)");
                    sql.execute("INSERT INTO conversation_turn VALUES(7,6,'RUNNING')");
                    connection.setAutoCommit(false);
                    var config=new Configuration(new Environment("temporary",new JdbcTransactionFactory(),ds));
                    config.setMapUnderscoreToCamelCase(true);config.addMapper(OrchestrationMapper.class);
                    try(var session=new org.apache.ibatis.session.defaults.DefaultSqlSessionFactory(config).openSession(connection)) {
                        var mapper=session.getMapper(OrchestrationMapper.class);
                        var execution=new OrchestrationExecutionPO();execution.setProjectId(2L);execution.setUserId(3L);execution.setDeviceId(4L);
                        execution.setTitle("temporary");execution.setGoal("temporary");execution.setRequestKey("request");execution.setRequestHash("a".repeat(64));execution.setPlanJson("{}");
                        mapper.insert(execution);
                        var branch=new OrchestrationStepPO();branch.setExecutionId(execution.getId());branch.setPosition(1);branch.setName("分支");branch.setObjective("");
                        mapper.insertStep(branch);assertNull(mapper.step(branch.getId()).getExpertId());
                        mapper.result(branch.getId(),"{\"schemaVersion\":1,\"summary\":\"false\",\"sourceMessageIds\":[],\"sourceTurnId\":null,\"expertVersionId\":null,\"truncated\":false}");
                        assertEquals("SUCCEEDED",mapper.step(branch.getId()).getStatus());
                        var step=new OrchestrationStepPO();step.setExecutionId(execution.getId());step.setPosition(0);step.setName("分析");step.setExpertId(8L);step.setObjective("temporary");mapper.insertStep(step);
                        session.commit();
                        mapper.status(execution.getId(),"RUNNING",null);
                        assertEquals(1,mapper.claim(step.getId(),"PENDING","CREATING",null));
                        assertEquals(0,mapper.claim(step.getId(),"PENDING","CREATING",null));
                        assertEquals(0,mapper.linkConversation(step.getId(),6L,2L,99L));
                        assertEquals(1,mapper.linkConversation(step.getId(),6L,2L,3L));
                        assertEquals(1,mapper.claim(step.getId(),"WAITING_THREAD","DISPATCHING","frozen input"));
                        assertEquals(1,mapper.linkTurn(step.getId(),7L,6L,2L,3L));
                        session.rollback();
                        assertEquals("PENDING",mapper.step(step.getId()).getStatus());assertNull(mapper.step(step.getId()).getConversationId());
                        mapper.status(execution.getId(),"RUNNING",null);mapper.claim(step.getId(),"PENDING","CREATING",null);
                        mapper.linkConversation(step.getId(),6L,2L,3L);mapper.claim(step.getId(),"WAITING_THREAD","DISPATCHING","frozen input");
                        mapper.linkTurn(step.getId(),7L,6L,2L,3L);
                        assertEquals(0,mapper.terminal(7L,6L,99L,"COMPLETED"));
                        assertEquals(1,mapper.terminal(7L,6L,4L,"COMPLETED"));assertEquals(0,mapper.terminal(7L,6L,4L,"FAILED"));
                        assertEquals("COMPLETED",mapper.step(step.getId()).getTerminalStatus());
                        assertEquals(1,mapper.activeDeviceTurns(4L));assertEquals(1,mapper.managedConversation(6L));
                        mapper.status(execution.getId(),"CANCELING","stop");mapper.status(execution.getId(),"NEEDS_ATTENTION","uncertain");
                        assertTrue(mapper.get(execution.getId()).isCancelRequested());
                        assertEquals(execution.getId(),mapper.byRequest(3L,"request").getId());
                        assertEquals(1,mapper.list(2L,3L,"temp").size());assertTrue(mapper.list(2L,99L,"").isEmpty());
                        mapper.status(execution.getId(),"SUCCEEDED",null);
                        assertEquals(0,mapper.reservedProject(2L));
                        assertEquals(1,mapper.managedConversation(6L));
                        assertEquals(java.util.List.of(6L),mapper.managedConversations(java.util.List.of(6L,99L)));
                        session.rollback();
                    }
                }
            });
    }
}
