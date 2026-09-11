package com.myharness.codex.service;

import com.myharness.codex.config.AccountMailProperties;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.AccountEmailChallengePO;
import com.myharness.codex.entity.po.MailDeliveryTaskPO;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.service.mail.*;
import com.myharness.codex.support.IsolatedMysql;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executes the real MySQL mappings and Spring transactions against a disposable server, without SMTP or Redis. */
@EnabledIfSystemProperty(named = "mysql.isolated.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountEmailDatabaseTest {
    private IsolatedMysql mysql;
    private JdbcTemplate jdbc;
    private AccountEmailService accounts;
    private SysUserMapper users;
    private AccountEmailChallengeMapper challenges;
    private AccountMailCrypto crypto;
    private AccountMailSettings settings;
    private AccountMailRateLimiter limiter;
    private ClientEventWebSocketHandler sockets;
    private BCryptPasswordEncoder passwords;
    private DefaultMailChallengeEligibility eligibility;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), ZoneOffset.UTC);

    @BeforeAll
    void startIsolatedServerAndRealMappings() throws Exception {
        mysql = IsolatedMysql.start();
        mysql.createTablesFromSchema("sys_user", "sys_role", "sys_permission", "sys_user_role", "sys_role_permission",
                "account_email_challenge", "system_initialization", "mail_delivery_task", "audit_log");
        jdbc = new JdbcTemplate(mysql.dataSource());
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(RbacMapper.class);
        configuration.addMapper(AccountEmailChallengeMapper.class);
        configuration.addMapper(SystemInitializationMapper.class);
        configuration.addMapper(MailDeliveryTaskMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(mysql.dataSource());
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/SysUserMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        users = session.getMapper(SysUserMapper.class);
        challenges = session.getMapper(AccountEmailChallengeMapper.class);
        AccountMailProperties properties = new AccountMailProperties();
        properties.setEnabled(true);
        properties.setHmacKey(Base64.getEncoder().encodeToString("1".repeat(32).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        properties.setEncryptionKey(Base64.getEncoder().encodeToString("2".repeat(32).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        crypto = new AccountMailCrypto(properties);
        settings = mock(AccountMailSettings.class);
        limiter = mock(AccountMailRateLimiter.class);
        sockets = mock(ClientEventWebSocketHandler.class);
        passwords = new BCryptPasswordEncoder(4);
        var transactions = new DataSourceTransactionManager(mysql.dataSource());
        AccountMailService mail = proxy(new AccountMailService(session.getMapper(MailDeliveryTaskMapper.class), crypto, settings, properties, clock), transactions);
        accounts = proxy(new AccountEmailService(users, session.getMapper(RbacMapper.class), challenges,
                session.getMapper(SystemInitializationMapper.class), mail, crypto, limiter, properties, passwords, sockets, transactions, clock), transactions);
        eligibility = new DefaultMailChallengeEligibility(challenges, users, properties);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target, DataSourceTransactionManager transactions) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    @BeforeEach
    void clearOnlyDisposableDatabase() throws Exception {
        mysql.execute("SET FOREIGN_KEY_CHECKS=0", "TRUNCATE audit_log", "TRUNCATE mail_delivery_task",
                "TRUNCATE account_email_challenge", "TRUNCATE system_initialization", "TRUNCATE sys_user_role",
                "TRUNCATE sys_user", "TRUNCATE sys_role_permission", "TRUNCATE sys_permission", "TRUNCATE sys_role",
                "SET FOREIGN_KEY_CHECKS=1");
        mysql.applyResource("db/seed-rbac.sql");
        reset(settings, limiter, sockets);
    }

    @AfterAll
    void stopAndRemoveIsolatedServer() throws Exception {
        if (mysql != null) mysql.close();
    }

    @Test
    void invitationPreviewAndActivationPersistOneCredentialAndFormalPassword() {
        SysUserPO user = invite(" Alice@Example.com ");
        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        assertThat(user.getDisplayName()).isEqualTo("alice");
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.isActivated()).isFalse();
        String token = secret(user.getId(), "ACTIVATION");
        assertThat(token).hasSize(43);
        assertThat(jdbc.queryForObject("SELECT encrypted_payload FROM mail_delivery_task", String.class)).doesNotContain(token);
        assertThat(accounts.validateActivation(token).maskedEmail()).isEqualTo("a***@example.com");
        assertThat(users.selectById(user.getId()).isActivated()).isFalse();

        accounts.activate(token, "AformalPassword123", "  Alice  ");

        SysUserPO activated = users.selectById(user.getId());
        assertThat(activated.isActivated()).isTrue();
        assertThat(activated.getDisplayName()).isEqualTo("Alice");
        assertThat(activated.isMustChangePassword()).isFalse();
        assertThat(passwords.matches("AformalPassword123", activated.getPasswordHash())).isTrue();
        assertThat(challenges.latest(user.getId(), "ACTIVATION").getStatus()).isEqualTo("USED");
        assertThat(jdbc.queryForObject("SELECT encrypted_payload FROM mail_delivery_task", String.class)).isNull();
        verify(sockets).disconnectBeforeVersion(user.getId(),user.getTokenVersion()+1,false);
        assertError(ErrorCode.EMAIL_VERIFICATION_USED, () -> accounts.activate(token, "AnotherPassword123", null));
    }

    @Test
    void resendRevokesPreviousTokenAndOnlyCurrentGenerationCanBeDelivered() {
        SysUserPO user = invite("resend@example.com");
        String previous = secret(user.getId(), "ACTIVATION");
        Long oldId = challenges.latest(user.getId(), "ACTIVATION").getId();

        accounts.publicResendActivation(user.getEmail(), "127.0.0.1");

        String latest = secret(user.getId(), "ACTIVATION");
        assertThat(latest).isNotEqualTo(previous);
        assertError(ErrorCode.EMAIL_VERIFICATION_INVALID, () -> accounts.validateActivation(previous));
        assertThat(eligibility.isDeliverable(oldId, user.getId(), "ACTIVATION", user.getEmail(), now())).isFalse();
        assertThat(eligibility.isDeliverable(challenges.latest(user.getId(), "ACTIVATION").getId(), user.getId(), "ACTIVATION", user.getEmail(), now())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mail_delivery_task WHERE status='CANCELLED' AND encrypted_payload IS NULL", Integer.class)).isEqualTo(1);
        assertThat(accounts.validateActivation(latest)).isNotNull();
    }

    @Test
    void disabledOrIncompleteAccountsCannotUseActivationOrRecovery() {
        SysUserPO pending = invite("pending@example.com");
        String token = secret(pending.getId(), "ACTIVATION");
        assertThat(accounts.requestPasswordReset(pending.getEmail(), "127.0.0.1"))
                .isEqualTo(accounts.requestPasswordReset("unknown@example.com", "127.0.0.1"));
        jdbc.update("UPDATE sys_user SET status='DISABLED' WHERE id=?", pending.getId());
        assertError(ErrorCode.EMAIL_VERIFICATION_INVALID, () -> accounts.activate(token, "AformalPassword123", null));
        accounts.publicResendActivation(pending.getEmail(), "127.0.0.1");
        accounts.requestPasswordReset(pending.getEmail(), "127.0.0.1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mail_delivery_task", Integer.class)).isEqualTo(1);
        assertThat(users.selectById(pending.getId()).getPasswordHash()).isNull();
        verify(limiter).checkSend("unknown@example.com", "PASSWORD_RESET", "127.0.0.1");
    }

    @Test
    void expiredActivationAndResetCredentialsCannotChangeAccountState() {
        SysUserPO pending = invite("expired@example.com");
        String token = secret(pending.getId(), "ACTIVATION");
        jdbc.update("UPDATE account_email_challenge SET expires_at=? WHERE user_id=?", now(), pending.getId());
        assertError(ErrorCode.EMAIL_VERIFICATION_EXPIRED, () -> accounts.validateActivation(token));
        assertError(ErrorCode.EMAIL_VERIFICATION_EXPIRED, () -> accounts.activate(token, "AformalPassword123", null));
        assertThat(users.selectById(pending.getId()).isActivated()).isFalse();
        SysUserPO active = active("reset-expired@example.com");
        accounts.requestPasswordReset(active.getEmail(), "127.0.0.1");
        String code = secret(active.getId(), "PASSWORD_RESET");
        jdbc.update("UPDATE account_email_challenge SET expires_at=? WHERE user_id=? AND purpose='PASSWORD_RESET'", now(), active.getId());
        assertError(ErrorCode.EMAIL_VERIFICATION_INVALID, () -> accounts.resetPassword(active.getEmail(), code, "ReplacementPassword123", "127.0.0.1"));
        assertThat(users.selectById(active.getId()).getTokenVersion()).isEqualTo(active.getTokenVersion());
    }

    @Test
    void incorrectCodesCommitAttemptsAndTheFifthAttemptRevokesTheCredential() {
        SysUserPO user = active("attempts@example.com");
        accounts.requestPasswordReset(user.getEmail(), "127.0.0.1");
        String realCode = secret(user.getId(), "PASSWORD_RESET");
        String wrong = realCode.equals("000000") ? "000001" : "000000";
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertError(ErrorCode.EMAIL_VERIFICATION_INVALID, () -> accounts.resetPassword(user.getEmail(), wrong, "ReplacementPassword123", "127.0.0.1"));
            assertThat(challenges.latest(user.getId(), "PASSWORD_RESET").getFailedAttempts()).isEqualTo(attempt);
        }
        assertThat(challenges.latest(user.getId(), "PASSWORD_RESET").getStatus()).isEqualTo("REVOKED");
        assertError(ErrorCode.EMAIL_VERIFICATION_INVALID, () -> accounts.resetPassword(user.getEmail(), realCode, "ReplacementPassword123", "127.0.0.1"));
        assertThat(jdbc.queryForObject("SELECT encrypted_payload FROM mail_delivery_task WHERE template='PASSWORD_RESET'", String.class)).isNull();
        assertThat(users.selectById(user.getId()).getTokenVersion()).isEqualTo(user.getTokenVersion());
    }

    @Test
    void successfulRecoveryRevokesCredentialsAndCreatesNotificationWithinTransaction() {
        SysUserPO user = active("recover@example.com");
        accounts.requestPasswordReset(user.getEmail(), "127.0.0.1");
        String code = secret(user.getId(), "PASSWORD_RESET");
        clearInvocations(sockets);

        accounts.resetPassword(user.getEmail(), code, "ReplacementPassword123", "127.0.0.1");

        SysUserPO changed = users.selectById(user.getId());
        assertThat(passwords.matches("ReplacementPassword123", changed.getPasswordHash())).isTrue();
        assertThat(changed.getTokenVersion()).isEqualTo(user.getTokenVersion() + 1);
        assertThat(changed.isMustChangePassword()).isFalse();
        assertThat(challenges.latest(user.getId(), "PASSWORD_RESET").getStatus()).isEqualTo("USED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mail_delivery_task WHERE template='PASSWORD_CHANGED' AND encrypted_payload IS NULL", Integer.class)).isEqualTo(1);
        verify(sockets).disconnectBeforeVersion(user.getId(),user.getTokenVersion()+1,false);
        assertError(ErrorCode.EMAIL_VERIFICATION_INVALID, () -> accounts.resetPassword(user.getEmail(), code, "YetAnotherPassword123", "127.0.0.1"));
    }

    @Test
    void passwordUpdateFailureRollsBackCredentialConsumptionAndNotification() throws Exception {
        SysUserPO user = active("rollback@example.com");
        accounts.requestPasswordReset(user.getEmail(), "127.0.0.1");
        String code = secret(user.getId(), "PASSWORD_RESET");
        mysql.execute("CREATE TRIGGER reject_password_update BEFORE UPDATE ON sys_user FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test rollback'");
        clearInvocations(sockets);
        try {
            assertThatThrownBy(() -> accounts.resetPassword(user.getEmail(), code, "ReplacementPassword123", "127.0.0.1"))
                    .isNotInstanceOf(BusinessException.class);
            assertThat(challenges.latest(user.getId(), "PASSWORD_RESET").getStatus()).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mail_delivery_task WHERE template='PASSWORD_CHANGED'", Integer.class)).isZero();
            verifyNoInteractions(sockets);
        } finally { mysql.execute("DROP TRIGGER reject_password_update"); }
        accounts.resetPassword(user.getEmail(), code, "ReplacementPassword123", "127.0.0.1");
        assertThat(challenges.latest(user.getId(), "PASSWORD_RESET").getStatus()).isEqualTo("USED");
    }

    @Test
    void concurrentRecoveryCanConsumeOneCodeOnlyOnce() throws Exception {
        SysUserPO user = active("concurrent@example.com");
        accounts.requestPasswordReset(user.getEmail(), "127.0.0.1");
        String code = secret(user.getId(), "PASSWORD_RESET");
        List<Object> results = simultaneously(() -> {
            accounts.resetPassword(user.getEmail(), code, "ReplacementPassword123", "127.0.0.1");
            return "success";
        });
        assertThat(results.stream().filter("success"::equals).count()).isEqualTo(1);
        assertThat(results.stream().filter(BusinessException.class::isInstance).count()).isEqualTo(1);
        assertThat(users.selectById(user.getId()).getTokenVersion()).isEqualTo(user.getTokenVersion() + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mail_delivery_task WHERE template='PASSWORD_CHANGED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentActivationChangesTheAccountOnlyOnce() throws Exception {
        SysUserPO user = invite("activate-concurrent@example.com");
        String token = secret(user.getId(), "ACTIVATION");
        List<Object> results = simultaneously(() -> {
            accounts.activate(token, "FormalPassword123", null);
            return "success";
        });
        assertThat(results.stream().filter("success"::equals).count()).isEqualTo(1);
        assertThat(results.stream().filter(BusinessException.class::isInstance).count()).isEqualTo(1);
        assertThat(users.selectById(user.getId()).getTokenVersion()).isEqualTo(1);
    }

    @Test
    void concurrentInvitesRelyOnTheCanonicalEmailUniqueConstraint() throws Exception {
        List<Object> results = simultaneously(() -> {
            invite("Same@Example.com");
            return "success";
        });
        assertThat(results.stream().filter("success"::equals).count()).isEqualTo(1);
        assertThat(results.stream().filter(BusinessException.class::isInstance).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mail_delivery_task", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentBootstrapAndRestartNeverRecreateOrRegrantInitialAdministrator() throws Exception {
        assertThat(simultaneously(() -> {
            accounts.bootstrap("bootstrap@example.com", "管理员");
            return "success";
        })).containsExactly("success", "success");
        SysUserPO user = users.selectByEmail("bootstrap@example.com");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_initialization", Integer.class)).isEqualTo(1);
        String token = secret(user.getId(), "ACTIVATION");
        accounts.activate(token, "FormalPassword123", null);
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM system_initialization", Boolean.class)).isTrue();
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", user.getId());
        jdbc.update("UPDATE sys_user SET status='DISABLED' WHERE id=?", user.getId());

        accounts.bootstrap("changed@example.com", "Changed name");
        accounts.bootstrap(null, null);

        assertThat(users.selectByEmail("changed@example.com")).isNull();
        assertThat(users.selectById(user.getId()).getStatus()).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mail_delivery_task", Integer.class)).isEqualTo(1);
    }

    @Test
    void bootstrapRejectsIncompletePermissionSeedsBeforeCreatingUser() {
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id=(SELECT id FROM sys_role WHERE role_code='SYS_ADMIN') LIMIT 1");
        assertThatThrownBy(() -> accounts.bootstrap("bootstrap@example.com", null)).hasMessageContaining("seed-rbac.sql");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
    }

    @Test
    void existingOrdinaryUserCannotBePromotedByBootstrapConfiguration() {
        SysUserPO user = invite("occupied@example.com");
        assertThatThrownBy(() -> accounts.bootstrap(user.getEmail(), "管理员")).hasMessageContaining("users already exist");
        assertThat(jdbc.queryForObject("SELECT r.role_code FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=?", String.class, user.getId())).isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_initialization", Integer.class)).isZero();
    }

    @Test
    void configurationFailureLeavesFreshSystemEmpty() {
        doThrow(new BusinessException(ErrorCode.EMAIL_UNAVAILABLE)).when(settings).requireSendingConfigured();
        assertError(ErrorCode.EMAIL_UNAVAILABLE, () -> accounts.bootstrap("bootstrap@example.com", null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_email_challenge", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_initialization", Integer.class)).isZero();
    }

    @Test
    void expiredCredentialCleanupRetainsEveryRecordStillReferencedByMailTasks() {
        SysUserPO first = invite("cleanup-first@example.com");
        SysUserPO second = invite("cleanup-second@example.com");
        jdbc.update("UPDATE account_email_challenge SET expires_at=?", now().minusDays(31));

        assertThat(challenges.expire(now())).isEqualTo(2);
        assertThat(challenges.latest(first.getId(), "ACTIVATION").getStatus()).isEqualTo("EXPIRED");
        assertThat(challenges.deleteOldMetadata(now().minusDays(30))).isZero();
        jdbc.update("DELETE FROM mail_delivery_task WHERE user_id=?", first.getId());

        assertThat(challenges.deleteOldMetadata(now().minusDays(30))).isEqualTo(1);
        assertThat(challenges.latest(first.getId(), "ACTIVATION")).isNull();
        assertThat(challenges.latest(second.getId(), "ACTIVATION")).isNotNull();
    }

    private SysUserPO invite(String email) { return accounts.createInvitedUser(email, "USER", null, null); }
    private SysUserPO active(String email) {
        SysUserPO user = invite(email);
        accounts.activate(secret(user.getId(), "ACTIVATION"), "OriginalPassword123", null);
        return users.selectById(user.getId());
    }
    private String secret(Long userId, String purpose) {
        MailDeliveryTaskPO task = jdbc.queryForObject("SELECT * FROM mail_delivery_task WHERE user_id=? AND template=? ORDER BY id DESC LIMIT 1",
                new BeanPropertyRowMapper<>(MailDeliveryTaskPO.class), userId, purpose);
        return crypto.decrypt(task.getEncryptedPayload(), task.payloadBinding());
    }
    private java.time.LocalDateTime now() { return java.time.LocalDateTime.now(clock); }
    private void assertError(ErrorCode code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }
    private List<Object> simultaneously(Callable<Object> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> task = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test barrier timed out");
            try { return action.call(); } catch (Exception ex) { return ex; }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }
}
