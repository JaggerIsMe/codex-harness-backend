package com.myharness.codex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly enabled, read-only JDBC smoke test; never starts the application or runs schema SQL. */
@EnabledIfSystemProperty(named = "mysql.integration", matches = "true")
class MysqlCompatibilityTest {
    @Test
    @EnabledIfSystemProperty(named="harness.recovery.conversations",matches="[0-9,]+")
    void verifiesExplicitlySelectedUnstartedConversationsWithoutChangingThem() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.sql.init.mode=never")
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class)).run(context->{
            try(var connection=context.getBean(DataSource.class).getConnection()) {
                connection.setReadOnly(true);
                for(String raw:System.getProperty("harness.recovery.conversations").split(",")) {
                    long id=Long.parseLong(raw);
                    String sql="SELECT " + com.myharness.codex.mapper.ConversationMapper.RECREATABLE_THREAD
                            .replace("#{id}",Long.toString(id)).replace("#{turnId}","0");
                    try(var statement=connection.createStatement();var result=statement.executeQuery(sql)) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getBoolean(1)).as("Conversation %s must have only confirmed pre-generation failures",id).isTrue();
                    }
                }
            }
        });
    }
    @Test void threadRecreationPredicateProtectsExistingOrUncertainHistory() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.sql.init.mode=never")
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class)).run(context->{
            try(var connection=context.getBean(DataSource.class).getConnection()) {
                connection.setReadOnly(true);
                // CTE fixtures shadow table names. Only SELECT is issued; no business records or schema are changed.
                String sql="WITH conversation_turn AS (SELECT 1 conversation_id,2 id,? codex_turn_id,? status,? failure_code,? failure_message), " +
                        "conversation_message AS (SELECT 1 conversation_id,? role) SELECT " +
                        com.myharness.codex.mapper.ConversationMapper.RECREATABLE_THREAD.replace("#{id}","1").replace("#{turnId}","99");
                String missing="Codex method failed: thread/read: thread not loaded: missing";
                Object[][] cases={
                        {null,"FAILED","COMMAND_FAILED",missing,"USER",true},
                        {null,"FAILED","COMMAND_FAILED","Codex method failed: turn/start: failed to load configuration: test","USER",true},
                        {"executed","FAILED","COMMAND_FAILED",missing,"USER",false},
                        {null,"COMPLETED",null,null,"USER",false},
                        {null,"FAILED","COMMAND_TIMEOUT","Uncertain delivery","USER",false},
                        {null,"FAILED","COMMAND_FAILED",missing,"ASSISTANT",false},
                        {null,"FAILED","COMMAND_FAILED","Connection lost","USER",false},
                        {null,"FAILED",null,null,"USER",false}
                };
                for(Object[] fixture:cases) try(var statement=connection.prepareStatement(sql)) {
                    for(int index=0;index<5;index++) statement.setObject(index+1,fixture[index]);
                    try(var result=statement.executeQuery()) { assertThat(result.next()).isTrue();assertThat(result.getBoolean(1)).isEqualTo(fixture[5]); }
                }
            }
        });
    }
    @Test
    void connectsToConfiguredMysqlWithBootManagedDriver() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.sql.init.mode=never")
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    try (var connection = context.getBean(DataSource.class).getConnection()) {
                        connection.setReadOnly(true);
                        assertThat(connection.getMetaData().getDatabaseMajorVersion()).isGreaterThanOrEqualTo(8);
                        assertThat(connection.getMetaData().getDriverName()).contains("MySQL Connector/J");
                        try (var statement = connection.createStatement();
                             var result = statement.executeQuery("SELECT VERSION(), 1")) {
                            assertThat(result.next()).isTrue();
                            assertThat(result.getString(1)).isNotBlank();
                            assertThat(result.getInt(2)).isEqualTo(1);
                        }
                    }
                });
    }
}
