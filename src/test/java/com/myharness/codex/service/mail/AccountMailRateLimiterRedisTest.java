package com.myharness.codex.service.mail;

import com.myharness.codex.config.AccountMailProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Uses a disposable process and random loopback port; never loads application connection settings. */
@EnabledIfSystemProperty(named = "redis.integration", matches = "true")
class AccountMailRateLimiterRedisTest {
    @TempDir static Path directory;
    private static Process process;
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private AccountMailProperties properties;
    private AccountMailCrypto crypto;
    private AccountMailRateLimiter limiter;

    @BeforeAll static void start() throws Exception {
        int port;
        try (ServerSocket available = new ServerSocket(0)) { port = available.getLocalPort(); }
        process = new ProcessBuilder(System.getProperty("redis.executable", "redis-server"), "--bind", "127.0.0.1", "--port", Integer.toString(port), "--save", "", "--appendonly", "no")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("redis.log").toFile()).start();
        connections = new LettuceConnectionFactory("127.0.0.1", port);
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
        boolean ready = false;
        for (int i = 0; i < 100; i++) {
            try (var connection = connections.getConnection()) { connection.ping(); ready = true; break; }
            catch (RuntimeException ignored) { Thread.sleep(50); }
        }
        assertTrue(ready, "Disposable Redis did not start");
    }

    @AfterAll static void stop() throws Exception {
        if (connections != null) connections.destroy();
        if (process != null) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
            for (int i = 0; i < 20; i++) {
                try { java.nio.file.Files.deleteIfExists(directory.resolve("redis.log")); break; }
                catch (java.nio.file.FileSystemException ignored) { Thread.sleep(50); }
            }
        }
    }

    @BeforeEach void clearDisposableDatabase() {
        try (var connection = connections.getConnection()) { connection.serverCommands().flushDb(); }
        properties = AccountMailCryptoTest.properties();
        crypto = new AccountMailCrypto(properties);
        limiter = new AccountMailRateLimiter(redis, crypto, properties);
    }

    private String key(String raw) { return "harness:{account-mail}:" + crypto.opaqueKey(raw); }

    @Test void rejectedSendDoesNotConsumeOtherBudgetsOrExtendCooldown() {
        limiter.checkSend("one@example.com", "ACTIVATION", "192.0.2.1");
        String cooldown = key("send:cooldown:ACTIVATION:one@example.com");
        redis.expire(cooldown, java.time.Duration.ofSeconds(42));
        MailRateLimitException error = assertThrows(MailRateLimitException.class,
                () -> limiter.checkSend("one@example.com", "ACTIVATION", "192.0.2.2"));
        assertTrue(error.getRetryAfterSeconds() <= 42);
        assertNull(redis.opsForValue().get(key("send:ip:192.0.2.2")));
        assertEquals("1", redis.opsForValue().get(key("send:email:one@example.com")));
    }

    @Test void changingPurposeCannotResetMailboxHourlyOrIpBudgets() {
        properties.setEmailHourlyLimit(2);
        limiter.checkSend("one@example.com", "ACTIVATION", "192.0.2.1");
        limiter.checkSend("one@example.com", "PASSWORD_RESET", "192.0.2.2");
        redis.delete(key("send:cooldown:ACTIVATION:one@example.com"));
        assertThrows(MailRateLimitException.class, () -> limiter.checkSend("one@example.com", "ACTIVATION", "192.0.2.3"));
        properties.setIpSendHourlyLimit(1);
        assertThrows(MailRateLimitException.class, () -> limiter.checkSend("two@example.com", "ACTIVATION", "192.0.2.1"));
    }

    @Test void verificationBudgetSurvivesRegeneratingCodesAndChangingIp() {
        properties.setEmailVerifyHourlyLimit(2);
        limiter.checkVerify("one@example.com", "PASSWORD_RESET", "192.0.2.1");
        limiter.checkVerify("one@example.com", "PASSWORD_RESET", "192.0.2.2");
        assertThrows(MailRateLimitException.class, () -> limiter.checkVerify("one@example.com", "PASSWORD_RESET", "192.0.2.3"));
    }

    @Test void concurrentRequestsOnlyConsumeOneCooldownSlot() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) results.add(executor.submit(() -> {
                start.await();
                try { limiter.checkSend("one@example.com", "ACTIVATION", "192.0.2.1"); return true; }
                catch (MailRateLimitException ignored) { return false; }
            }));
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
            assertEquals("1", redis.opsForValue().get(key("send:email:one@example.com")));
        }
    }
}
