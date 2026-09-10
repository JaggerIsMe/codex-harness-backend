package com.myharness.codex.mapper;

import com.myharness.codex.support.IsolatedMysql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "mysql.isolated.integration", matches = "true")
class EmailSchemaInstallationTest {
    @Test void completeInstallationAndRepeatableRbacSeedSupportTheEmailIdentityModel() throws Exception {
        try (IsolatedMysql mysql = IsolatedMysql.start()) {
            mysql.applyResource("db/schema.sql");
            mysql.applyResource("db/seed-rbac.sql");
            mysql.applyResource("db/seed-rbac.sql");
            JdbcTemplate jdbc = new JdbcTemplate(mysql.dataSource());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class)).isEqualTo(36);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_permission", Integer.class)).isEqualTo(20);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission", Integer.class)).isEqualTo(34);
            assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_user'", String.class))
                    .contains("email", "display_name", "activated_at", "email_verified_at").doesNotContain("username");
            assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_user' AND column_name='password_hash'", String.class)).isEqualTo("YES");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_initialization", Integer.class)).isZero();
        }
    }
}
