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
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Connection-local TEMPORARY tables shadow the real names; no production rows or schema are modified. */
@EnabledIfSystemProperty(named="mysql.approval.delivery",matches="true")
class ApprovalDeliveryMysqlTest {
    @Test void onlyMatchingUnacknowledgedAttemptCanBeReopenedWhileTurnIsActive() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.sql.init.mode=never")
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class)).run(context -> {
            try(var connection=context.getBean(DataSource.class).getConnection();var sql=connection.createStatement()) {
                sql.execute("CREATE TEMPORARY TABLE conversation_turn(id BIGINT PRIMARY KEY,status VARCHAR(32))");
                sql.execute("CREATE TEMPORARY TABLE approval_request(id BIGINT PRIMARY KEY,turn_id BIGINT,device_id BIGINT,status VARCHAR(32),decided_by BIGINT,decided_at DATETIME,decision_message_id VARCHAR(36))");
                try {
                    sql.execute("INSERT INTO conversation_turn VALUES(7,'WAITING_APPROVAL')");
                    sql.execute("INSERT INTO approval_request VALUES(11,7,1,'APPROVED',9,NOW(),'attempt-1')");
                    assertEquals(0,update(connection,"rejectDispatch","old-attempt",1));
                    assertEquals(0,update(connection,"rejectDispatch","attempt-1",2));
                    assertEquals(1,update(connection,"rejectDispatch","attempt-1",1));
                    assertEquals(0,update(connection,"rejectDispatch","attempt-1",1));
                    sql.execute("UPDATE approval_request SET status='APPROVED',decision_message_id='attempt-2'");
                    assertEquals(0,update(connection,"acknowledgeDispatch","attempt-1",1));
                    assertEquals(1,update(connection,"acknowledgeDispatch","attempt-2",1));
                    assertEquals(0,update(connection,"rejectDispatch","attempt-2",1));
                    sql.execute("UPDATE approval_request SET decision_message_id='attempt-3'");
                    sql.execute("UPDATE conversation_turn SET status='INTERRUPTED'");
                    assertEquals(0,update(connection,"rejectDispatch","attempt-3",1));
                } finally {sql.execute("DROP TEMPORARY TABLE approval_request");sql.execute("DROP TEMPORARY TABLE conversation_turn");}
            }
        });
    }
    private int update(Connection connection,String method,String attempt,long device) throws Exception {
        var configuration=new Configuration();configuration.addMapper(ApprovalMapper.class);
        var statement=configuration.getMappedStatement(ApprovalMapper.class.getName()+"."+method);
        var parameters=Map.<String,Object>of("id",11L,"deviceId",device,"messageId",attempt);
        var bound=statement.getBoundSql(parameters);
        try(var query=connection.prepareStatement(bound.getSql())) {
            new DefaultParameterHandler(statement,parameters,bound).setParameters(query);return query.executeUpdate();
        }
    }
}
