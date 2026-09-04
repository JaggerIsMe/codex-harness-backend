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
