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
import java.time.LocalDateTime;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** EXPLAIN only: validates the real MySQL schema without modifying any records. */
@EnabledIfSystemProperty(named="mysql.integration",matches="true")
class ApprovalMapperMysqlTest {
    @Test void mysqlPlansApprovalGuardsLockingAndExpiryStatements() {
        var configuration=new Configuration();configuration.addMapper(ApprovalMapper.class);
        var args=Map.<String,Object>ofEntries(Map.entry("id",0L),Map.entry("userId",0L),
                Map.entry("turnId",0L),Map.entry("conversationId",0L),Map.entry("deviceId",0L),
                Map.entry("remoteRequestId","plan-only"),Map.entry("requestId","plan-only"),
                Map.entry("approvalType","EXECUTION_CONFIRMATION"),Map.entry("payload","{}"),
                Map.entry("status","APPROVED"),Map.entry("decidedBy",0L),Map.entry("now",LocalDateTime.now()));
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.sql.init.mode=never")
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class)).run(context -> {
                    assertThat(context).hasNotFailed();
                    try(var connection=context.getBean(DataSource.class).getConnection()) {
                        for(var method:ApprovalMapper.class.getDeclaredMethods()) {
                            var statement=configuration.getMappedStatement(ApprovalMapper.class.getName()+"."+method.getName());
                            var bound=statement.getBoundSql(args);
                            try(var query=connection.prepareStatement("EXPLAIN "+bound.getSql())) {
                                new DefaultParameterHandler(statement,args,bound).setParameters(query);
                                try(var rows=query.executeQuery()) {assertThat(rows.next()).as(method.getName()).isTrue();}
                            }
                        }
                    }
                });
    }
}
