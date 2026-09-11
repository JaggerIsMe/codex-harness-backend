package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.SecurityProperties;
import com.myharness.codex.entity.dto.ChangePasswordDTO;
import com.myharness.codex.entity.dto.LoginDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.entity.vo.LoginVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.mapper.ExpertMapper;
import com.myharness.codex.mapper.RbacMapper;
import com.myharness.codex.mapper.SysUserMapper;
import com.myharness.codex.security.AuthorizationService;
import com.myharness.codex.security.JwtTokenService;
import com.myharness.codex.security.LoginAttemptLimiter;
import com.myharness.codex.security.RedisLoginSessionStore;
import com.myharness.codex.security.UserAuthenticationService;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.security.UserPrincipal;
import com.myharness.codex.service.impl.AuthServiceImpl;
import com.myharness.codex.service.mail.AccountMailService;
import com.myharness.codex.support.IsolatedMysql;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperties;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Real authentication transactions and mappings; both database processes are disposable and loopback-only. */
@EnabledIfSystemProperties({
        @EnabledIfSystemProperty(named = "mysql.isolated.integration", matches = "true"),
        @EnabledIfSystemProperty(named = "redis.integration", matches = "true")
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SingleLoginDatabaseTest {
    private static final String EMAIL = "single-login@example.test";
    private static final String PASSWORD = "OriginalPassword123";
    private static final String NEW_PASSWORD = "ChangedPassword456";

    @TempDir static Path redisDirectory;
    private IsolatedMysql mysql;
    private JdbcTemplate jdbc;
    private Process redisProcess;
    private LettuceConnectionFactory redisConnections;
    private StringRedisTemplate redis;
    private RedisLoginSessionStore sessions;
    private SysUserMapper users;
    private RbacMapper rbac;
    private AuthorizationService authorization;
    private JwtTokenService jwt;
    private BCryptPasswordEncoder passwords;
    private DataSourceTransactionManager transactions;
    private ClientEventWebSocketHandler sockets;
    private AuthServiceImpl auth;
    private UserAuthenticationService authentication;
    private UserManagementService management;
    private Long userId;

    @BeforeAll void startIsolatedDatabasesAndMappings() throws Exception {
        mysql = IsolatedMysql.start();
        mysql.createTablesFromSchema("sys_user", "sys_role", "sys_permission", "sys_user_role", "sys_role_permission", "audit_log");
        jdbc = new JdbcTemplate(mysql.dataSource());
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(RbacMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(mysql.dataSource());
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/SysUserMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        users = sql.getMapper(SysUserMapper.class);
        rbac = sql.getMapper(RbacMapper.class);
        authorization = new AuthorizationService(rbac, users);
        transactions = new DataSourceTransactionManager(mysql.dataSource());
        passwords = new BCryptPasswordEncoder(4);
        SecurityProperties properties = new SecurityProperties();
        properties.setJwtSecret("isolated-single-login-test-secret-with-32-bytes");
        properties.setJwtExpireMinutes(120L);
        jwt = new JwtTokenService(properties);
        sockets = mock(ClientEventWebSocketHandler.class);

        int port;
        try (ServerSocket available = new ServerSocket(0)) { port = available.getLocalPort(); }
        redisProcess = new ProcessBuilder(System.getProperty("redis.executable", "redis-server"), "--bind", "127.0.0.1",
                "--port", Integer.toString(port), "--save", "", "--appendonly", "no")
                .directory(redisDirectory.toFile()).redirectErrorStream(true).redirectOutput(redisDirectory.resolve("redis.log").toFile()).start();
        redisConnections = new LettuceConnectionFactory("127.0.0.1", port);
        redisConnections.afterPropertiesSet();
        redis = new StringRedisTemplate(redisConnections);
        redis.afterPropertiesSet();
        boolean ready = false;
        for (int i = 0; i < 100; i++) {
            try (var connection = redisConnections.getConnection()) { connection.ping(); ready = true; break; }
            catch (RuntimeException ignored) { Thread.sleep(50); }
        }
        assertTrue(ready, "Disposable Redis did not start");
        sessions = new RedisLoginSessionStore(redis);
        authentication = new UserAuthenticationService(jwt, users, sessions);
    }

    @BeforeEach void resetOnlyDisposableState() throws Exception {
        mysql.execute("SET FOREIGN_KEY_CHECKS=0", "TRUNCATE audit_log", "TRUNCATE sys_user_role", "TRUNCATE sys_user",
                "TRUNCATE sys_role_permission", "TRUNCATE sys_permission", "TRUNCATE sys_role", "SET FOREIGN_KEY_CHECKS=1");
        mysql.applyResource("db/seed-rbac.sql");
        try (var connection = redisConnections.getConnection()) { connection.serverCommands().flushDb(); }
        reset(sockets);
        UserContext.clear();
        SysUserPO user = new SysUserPO();
        user.setEmail(EMAIL);
        user.setDisplayName("Single Login User");
        user.setPasswordHash(passwords.encode(PASSWORD));
        user.setStatus("ENABLED");
        user.setActivatedAt(LocalDateTime.now(ZoneOffset.UTC));
        user.setEmailVerifiedAt(user.getActivatedAt());
        assertEquals(1, users.insert(user));
        userId = user.getId();
        assertEquals(1, rbac.assignRole(userId, "USER"));
        auth = authenticationService(sessions, transactions, users);
        management = proxy(new UserManagementService(rbac, users, mock(AgentDeviceMapper.class), mock(ExpertMapper.class),
                passwords, authorization, sockets, new ObjectMapper(), mock(AccountEmailService.class),
                mock(AccountMailService.class), sessions), transactions);
    }

    @AfterEach void clearRequestContext() { UserContext.clear(); }

    @AfterAll void stopAndRemoveOnlyOwnedProcesses() throws Exception {
        try {
            if (redisConnections != null) redisConnections.destroy();
            if (redisProcess != null) {
                redisProcess.destroy();
                if (!redisProcess.waitFor(5, TimeUnit.SECONDS)) { redisProcess.destroyForcibly(); redisProcess.waitFor(5, TimeUnit.SECONDS); }
                redisProcess.getInputStream().close();
                redisProcess.getErrorStream().close();
                redisProcess.getOutputStream().close();
                for (int i = 0; i < 20; i++) {
                    try { Files.deleteIfExists(redisDirectory.resolve("redis.log")); break; }
                    catch (java.nio.file.FileSystemException ignored) { Thread.sleep(50); }
                }
            }
        } finally {
            if (mysql != null) mysql.close();
        }
    }

    @Test void sequentialLoginsRevokeOlderTokensEvenWhenRedisRestoresAnOlderValue() {
        LoginVO first = auth.login(login(PASSWORD));
        String retiredValue = redis.opsForValue().get(key());
        LoginVO second = auth.login(login(PASSWORD));

        assertEquals(2, users.selectById(userId).getTokenVersion());
        assertError(ErrorCode.SESSION_REPLACED, () -> authentication.authenticate(first.getAccessToken()));
        assertEquals(userId, authentication.authenticate(second.getAccessToken()).getId());
        assertEquals(7200, second.getExpiresInSeconds());

        redis.opsForValue().set(key(), retiredValue);
        assertError(ErrorCode.UNAUTHORIZED, () -> authentication.authenticate(first.getAccessToken()));
        assertError(ErrorCode.UNAUTHORIZED, () -> authentication.authenticate(second.getAccessToken()));
        LoginVO recovered = auth.login(login(PASSWORD));
        assertEquals(userId, authentication.authenticate(recovered.getAccessToken()).getId());
        assertEquals(3, users.selectById(userId).getTokenVersion());
    }

    @Test void concurrentLoginsUseDifferentVersionsAndOnlyOneRemainsValid() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<LoginVO> attempt = () -> {
            ready.countDown();
            await(start);
            return auth.login(login(PASSWORD));
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<LoginVO> results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertNotEquals(tokenSession(results.getFirst()).version(), tokenSession(results.getLast()).version());
            int accepted = 0;
            for (LoginVO result : results) {
                try { authentication.authenticate(result.getAccessToken()); accepted++; }
                catch (BusinessException exception) { assertEquals(ErrorCode.SESSION_REPLACED, exception.getErrorCode()); }
            }
            assertEquals(1, accepted);
            assertEquals(2, users.selectById(userId).getTokenVersion());
            assertEquals(2, sessions.read(userId).version());
        }
    }

    @Test void incorrectPasswordKeepsTheExistingLoginAndVersion() {
        LoginVO current = auth.login(login(PASSWORD));
        var existing = sessions.read(userId);
        reset(sockets);

        assertError(ErrorCode.INVALID_CREDENTIALS, () -> auth.login(login("incorrect-password")));

        assertEquals(1, users.selectById(userId).getTokenVersion());
        assertEquals(existing, sessions.read(userId));
        assertEquals(userId, authentication.authenticate(current.getAccessToken()).getId());
        verifyNoInteractions(sockets);
    }

    @Test void sqlCommitFailureRollsBackItsVersionAndCannotLaterDeleteTheNextLogin() {
        LoginVO retired = auth.login(login(PASSWORD));
        auth.login(login(PASSWORD));
        AtomicReference<RedisLoginSessionStore.Session> attempted = new AtomicReference<>();
        RedisLoginSessionStore observed = spy(sessions);
        doAnswer(invocation -> {
            attempted.set(invocation.getArgument(2));
            return invocation.callRealMethod();
        }).when(observed).replace(eq(userId), any(), any());
        var failingTransactions = new DataSourceTransactionManager(mysql.dataSource()) {
            @Override protected void doCommit(DefaultTransactionStatus status) {
                throw new TransactionSystemException("Injected SQL commit failure");
            }
        };
        failingTransactions.setRollbackOnCommitFailure(true);
        AuthServiceImpl failing = authenticationService(observed, failingTransactions, users);
        reset(sockets);

        assertThrows(TransactionSystemException.class, () -> failing.login(login(PASSWORD)));

        assertEquals(2, users.selectById(userId).getTokenVersion());
        assertNull(sessions.read(userId));
        assertError(ErrorCode.UNAUTHORIZED, () -> authentication.authenticate(retired.getAccessToken()));
        verifyNoInteractions(sockets);
        LoginVO next = auth.login(login(PASSWORD));
        assertNotNull(attempted.get());
        assertFalse(sessions.revoke(userId, attempted.get()), "Delayed cleanup must not delete a later successful login");
        assertEquals(userId, authentication.authenticate(next.getAccessToken()).getId());
    }

    @Test void unknownRedisWriteResultRollsBackSqlAndNeverRestoresThePreviousSid() {
        LoginVO retired = auth.login(login(PASSWORD));
        auth.login(login(PASSWORD));
        AtomicReference<RedisLoginSessionStore.Session> attempted = new AtomicReference<>();
        RedisLoginSessionStore uncertain = spy(sessions);
        doAnswer(invocation -> {
            attempted.set(invocation.getArgument(2));
            assertEquals(Boolean.TRUE, invocation.callRealMethod());
            throw new BusinessException(ErrorCode.SESSION_UNAVAILABLE);
        }).when(uncertain).replace(eq(userId), any(), any());
        AuthServiceImpl failing = authenticationService(uncertain, transactions, users);
        reset(sockets);

        assertError(ErrorCode.SESSION_UNAVAILABLE, () -> failing.login(login(PASSWORD)));

        assertEquals(2, users.selectById(userId).getTokenVersion());
        assertNull(sessions.read(userId));
        assertError(ErrorCode.UNAUTHORIZED, () -> authentication.authenticate(retired.getAccessToken()));
        verifyNoInteractions(sockets);
        LoginVO next = auth.login(login(PASSWORD));
        assertFalse(sessions.revoke(userId, attempted.get()));
        assertEquals(userId, authentication.authenticate(next.getAccessToken()).getId());
    }

    @Test void previouslyAuthenticatedLogoutCannotRevokeANewerLogin() {
        LoginVO first = auth.login(login(PASSWORD));
        UserPrincipal oldRequest = principal(first);
        LoginVO current = auth.login(login(PASSWORD));
        var currentSession = sessions.read(userId);
        reset(sockets);
        UserContext.set(oldRequest);

        assertError(ErrorCode.UNAUTHORIZED, management::logout);

        assertEquals(currentSession, sessions.read(userId));
        assertEquals(2, users.selectById(userId).getTokenVersion());
        assertEquals(userId, authentication.authenticate(current.getAccessToken()).getId());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='USER_LOGOUT'", Integer.class));
        verifyNoInteractions(sockets);
        UserContext.set(principal(current));
        management.logout();
        assertNull(sessions.read(userId));
        assertEquals(3, users.selectById(userId).getTokenVersion());
        assertError(ErrorCode.UNAUTHORIZED, () -> authentication.authenticate(current.getAccessToken()));
    }

    @Test void previouslyAuthenticatedPasswordChangeCannotInvalidateANewerLogin() {
        LoginVO first = auth.login(login(PASSWORD));
        UserPrincipal oldRequest = principal(first);
        LoginVO current = auth.login(login(PASSWORD));
        UserContext.set(oldRequest);

        assertError(ErrorCode.UNAUTHORIZED, () -> management.changeOwnPassword(new ChangePasswordDTO(PASSWORD, NEW_PASSWORD)));

        assertTrue(passwords.matches(PASSWORD, users.selectById(userId).getPasswordHash()));
        assertEquals(2, users.selectById(userId).getTokenVersion());
        assertEquals(userId, authentication.authenticate(current.getAccessToken()).getId());
    }

    @Test void loginWaitsForPasswordChangeAndChecksThePasswordAfterAcquiringTheUserLock() throws Exception {
        LoginVO current = auth.login(login(PASSWORD));
        UserPrincipal changingUser = principal(current);
        CountDownLatch passwordWritten = new CountDownLatch(1);
        CountDownLatch finishPasswordChange = new CountDownLatch(1);
        CountDownLatch loginReachedLock = new CountDownLatch(1);
        SysUserMapper observedUsers = spy(users);
        doAnswer(invocation -> {
            loginReachedLock.countDown();
            return users.lockByEmail(invocation.getArgument(0));
        }).when(observedUsers).lockByEmail(EMAIL);
        AuthServiceImpl waitingLogin = authenticationService(sessions, transactions, observedUsers);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var change = executor.submit(() -> {
                UserContext.set(changingUser);
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        management.changeOwnPassword(new ChangePasswordDTO(PASSWORD, NEW_PASSWORD));
                        passwordWritten.countDown();
                        await(finishPasswordChange);
                    });
                } finally { UserContext.clear(); }
            });
            try {
                assertTrue(passwordWritten.await(10, TimeUnit.SECONDS));
                var oldPasswordLogin = executor.submit(() -> {
                    try { return waitingLogin.login(login(PASSWORD)); }
                    catch (BusinessException exception) { return exception; }
                });
                assertTrue(loginReachedLock.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> oldPasswordLogin.get(150, TimeUnit.MILLISECONDS));
                finishPasswordChange.countDown();
                change.get(15, TimeUnit.SECONDS);
                Object rejected = oldPasswordLogin.get(15, TimeUnit.SECONDS);
                assertInstanceOf(BusinessException.class, rejected);
                assertEquals(ErrorCode.INVALID_CREDENTIALS, ((BusinessException) rejected).getErrorCode());
                assertEquals(2, users.selectById(userId).getTokenVersion());
                assertError(ErrorCode.UNAUTHORIZED, () -> authentication.authenticate(current.getAccessToken()));
                LoginVO replacement = auth.login(login(NEW_PASSWORD));
                assertEquals(userId, authentication.authenticate(replacement.getAccessToken()).getId());
            } finally {
                finishPasswordChange.countDown();
            }
        }
    }

    private AuthServiceImpl authenticationService(RedisLoginSessionStore store, DataSourceTransactionManager manager, SysUserMapper mapper) {
        return proxy(new AuthServiceImpl(mapper, passwords, jwt, authorization, new LoginAttemptLimiter(), store, sockets), manager);
    }

    @Test void refreshKeepsTheLoginIdentityAndDoesNotExtendIdleOrAbsoluteExpiry() {
        LoginVO login=auth.login(login(PASSWORD));
        var original=sessions.read(userId);
        assertEquals(7200,login.getExpiresInSeconds());
        assertEquals(604800,login.getSessionExpiresAt()-jwt.parseClaims(login.getAccessToken()).getIssuedAt().toInstant().getEpochSecond());
        assertFalse(redis.opsForValue().get(key()).contains(login.getRefreshCookieValue()));
        long shortenedIdle=java.time.Instant.now().plusSeconds(30).getEpochSecond();
        var nearingIdle=new RedisLoginSessionStore.Session(original.version(),original.sid(),original.expiresAtEpochSeconds(),shortenedIdle,
                original.credentialGeneration(),original.refreshDigest(),original.previousRefreshDigest(),original.previousRefreshValidUntilEpochSeconds());
        assertTrue(sessions.replace(userId,original,nearingIdle));

        var refreshed=auth.refresh(login.getSessionId(),login.getRefreshCookieValue());

        assertEquals(login.getSessionId(),refreshed.getSessionId());
        assertEquals(2,refreshed.getCredentialGeneration());
        assertEquals(1,users.selectById(userId).getTokenVersion());
        assertEquals(shortenedIdle,refreshed.getIdleExpiresAt());
        assertEquals(login.getSessionExpiresAt(),refreshed.getSessionExpiresAt());
        assertNotEquals(login.getRefreshCookieValue(),refreshed.getRefreshCookieValue());
        assertEquals(userId,authentication.authenticate(login.getAccessToken()).getId());
        assertEquals(userId,authentication.authenticate(refreshed.getAccessToken()).getId());
        UserContext.set(principal(login));
        var activity=auth.activity();
        assertTrue(activity.idleExpiresAt()>shortenedIdle);
        assertTrue(activity.idleExpiresAt()<=activity.sessionExpiresAt());
        assertEquals(2,sessions.read(userId).credentialGeneration());
    }

    @Test void refreshWorksAfterAccessExpiryButExpiredIdleTakesPrecedence() {
        LoginVO login=auth.login(login(PASSWORD));
        var current=sessions.read(userId);
        String expiredAccess=jwt.createToken(userId,current.version(),current.sid(),java.time.Instant.now().minusSeconds(7300));
        assertError(ErrorCode.ACCESS_TOKEN_EXPIRED,()->authentication.authenticate(expiredAccess));
        var renewed=auth.refresh(login.getSessionId(),login.getRefreshCookieValue());
        assertEquals(userId,authentication.authenticate(renewed.getAccessToken()).getId());

        var rotated=sessions.read(userId);
        var idleExpired=new RedisLoginSessionStore.Session(rotated.version(),rotated.sid(),rotated.expiresAtEpochSeconds(),
                java.time.Instant.now().minusSeconds(1).getEpochSecond(),rotated.credentialGeneration(),rotated.refreshDigest(),rotated.previousRefreshDigest(),rotated.previousRefreshValidUntilEpochSeconds());
        redis.opsForValue().set(key(),encoded(idleExpired));
        assertError(ErrorCode.UNAUTHORIZED,()->authentication.authenticate(expiredAccess));
        assertError(ErrorCode.UNAUTHORIZED,()->auth.refresh(renewed.getSessionId(),renewed.getRefreshCookieValue()));
        assertEquals(1,users.selectById(userId).getTokenVersion());
        assertEquals(idleExpired,sessions.read(userId));
    }

    @Test void refreshedAccessIsCappedAtTheLoginAbsoluteDeadline() {
        LoginVO login=auth.login(login(PASSWORD));
        var current=sessions.read(userId);
        long absolute=java.time.Instant.now().plusSeconds(30).getEpochSecond();
        var nearEnd=new RedisLoginSessionStore.Session(current.version(),current.sid(),absolute,absolute,
                current.credentialGeneration(),current.refreshDigest(),"",0);
        assertTrue(sessions.replace(userId,current,nearEnd));
        var refreshed=auth.refresh(login.getSessionId(),login.getRefreshCookieValue());
        assertEquals(absolute,refreshed.getExpiresAt());
        assertEquals(absolute,jwt.parseClaims(refreshed.getAccessToken()).getExpiration().toInstant().getEpochSecond());
        assertTrue(refreshed.getExpiresInSeconds()>0 && refreshed.getExpiresInSeconds()<=30);
        assertEquals(absolute,refreshed.getSessionExpiresAt());
        assertEquals(absolute,refreshed.getIdleExpiresAt());
    }

    @Test void provenRefreshReplayCommitsVersionRevocationButRandomCredentialsDoNot() {
        LoginVO login=auth.login(login(PASSWORD));
        var refreshed=auth.refresh(login.getSessionId(),login.getRefreshCookieValue());
        assertError(ErrorCode.REFRESH_CONFLICT,()->auth.refresh(login.getSessionId(),login.getRefreshCookieValue()));
        assertError(ErrorCode.UNAUTHORIZED,()->auth.refresh(login.getSessionId(),com.myharness.codex.security.RefreshCredentials.create()));
        assertEquals(1,users.selectById(userId).getTokenVersion());
        assertEquals(userId,authentication.authenticate(refreshed.getAccessToken()).getId());

        var current=sessions.read(userId);
        var elapsed=new RedisLoginSessionStore.Session(current.version(),current.sid(),current.expiresAtEpochSeconds(),current.idleExpiresAtEpochSeconds(),
                current.credentialGeneration(),current.refreshDigest(),current.previousRefreshDigest(),java.time.Instant.now().minusSeconds(1).getEpochSecond());
        assertTrue(sessions.replace(userId,current,elapsed));
        reset(sockets);
        assertError(ErrorCode.UNAUTHORIZED,()->auth.refresh(login.getSessionId(),login.getRefreshCookieValue()));
        assertEquals(2,users.selectById(userId).getTokenVersion(),"Replay revocation must commit despite the 401 response");
        assertNull(sessions.read(userId));
        assertNull(sessions.userIdForSid(login.getSessionId()));
        verify(sockets).disconnectBeforeVersion(userId,2,false);
        assertError(ErrorCode.UNAUTHORIZED,()->authentication.authenticate(refreshed.getAccessToken()));
        var newer=auth.login(login(PASSWORD));
        assertError(ErrorCode.UNAUTHORIZED,()->auth.refresh(login.getSessionId(),login.getRefreshCookieValue()));
        assertEquals(userId,authentication.authenticate(newer.getAccessToken()).getId());
    }

    @Test void unknownRotationOutcomeDoesNotRestoreAConsumedCookieOrRevokeANewerLogin() {
        LoginVO login=auth.login(login(PASSWORD));
        RedisLoginSessionStore uncertain=spy(sessions);
        doAnswer(invocation->{
            assertEquals(RedisLoginSessionStore.RotationStatus.ROTATED,((RedisLoginSessionStore.Rotation)invocation.callRealMethod()).status());
            throw new BusinessException(ErrorCode.SESSION_UNAVAILABLE);
        }).when(uncertain).rotate(eq(userId),any(),any(),any(),anyLong());
        var failing=authenticationService(uncertain,transactions,users);
        assertError(ErrorCode.SESSION_UNAVAILABLE,()->failing.refresh(login.getSessionId(),login.getRefreshCookieValue()));
        assertEquals(1,users.selectById(userId).getTokenVersion());
        assertEquals(2,sessions.read(userId).credentialGeneration());
        assertError(ErrorCode.REFRESH_CONFLICT,()->auth.refresh(login.getSessionId(),login.getRefreshCookieValue()));
        var newer=auth.login(login(PASSWORD));
        assertError(ErrorCode.UNAUTHORIZED,()->auth.refresh(login.getSessionId(),login.getRefreshCookieValue()));
        assertEquals(userId,authentication.authenticate(newer.getAccessToken()).getId());
    }

    @Test void oldAccessCanLogoutItsOwnSidAfterRefreshHasAdvancedCredentialMetadata() {
        LoginVO login=auth.login(login(PASSWORD));
        UserPrincipal beforeRefresh=principal(login);
        var refreshed=auth.refresh(login.getSessionId(),login.getRefreshCookieValue());
        UserContext.set(beforeRefresh);
        management.logout();
        assertNull(sessions.read(userId));
        assertEquals(2,users.selectById(userId).getTokenVersion());
        assertError(ErrorCode.UNAUTHORIZED,()->authentication.authenticate(refreshed.getAccessToken()));
        assertError(ErrorCode.UNAUTHORIZED,()->auth.refresh(refreshed.getSessionId(),refreshed.getRefreshCookieValue()));
    }

    private String encoded(RedisLoginSessionStore.Session s) {
        return s.version()+":"+s.sid()+":"+s.expiresAtEpochSeconds()+":"+s.idleExpiresAtEpochSeconds()+":"+s.credentialGeneration()
                +":"+s.refreshDigest()+":"+s.previousRefreshDigest()+":"+s.previousRefreshValidUntilEpochSeconds();
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target, DataSourceTransactionManager manager) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    private LoginDTO login(String password) {
        LoginDTO dto = new LoginDTO();
        dto.setEmail(EMAIL);
        dto.setPassword(password);
        return dto;
    }

    private RedisLoginSessionStore.Session tokenSession(LoginVO login) { return jwt.session(jwt.parseClaims(login.getAccessToken())); }

    private UserPrincipal principal(LoginVO login) {
        var authenticated = authentication.authenticateSession(login.getAccessToken());
        var user = authenticated.user();
        return new UserPrincipal(user.getId(), user.getEmail(), user.getDisplayName(), authenticated.session());
    }

    private String key() { return "harness:auth:session:" + userId; }

    private void assertError(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(BusinessException.class, action).getErrorCode());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test barrier timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
