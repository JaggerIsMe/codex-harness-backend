package com.myharness.codex.security;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Tests only a disposable Redis process on a random loopback port. */
@EnabledIfSystemProperty(named = "redis.integration", matches = "true")
class RedisLoginSessionStoreTest {
    private static final Long USER_ID = 17L;
    private static final String KEY = "harness:auth:session:" + USER_ID;
    @TempDir static Path directory;
    private static Process process;
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private RedisLoginSessionStore sessions;

    @BeforeAll static void startRedis() throws Exception {
        int port;
        try (ServerSocket available = new ServerSocket(0)) { port = available.getLocalPort(); }
        process = new ProcessBuilder(System.getProperty("redis.executable", "redis-server"), "--bind", "127.0.0.1",
                "--port", Integer.toString(port), "--save", "", "--appendonly", "no")
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

    @AfterAll static void stopRedis() throws Exception {
        if (connections != null) connections.destroy();
        if (process != null) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
            for (int i = 0; i < 20; i++) {
                try { Files.deleteIfExists(directory.resolve("redis.log")); break; }
                catch (java.nio.file.FileSystemException ignored) { Thread.sleep(50); }
            }
        }
    }

    @BeforeEach void clearDisposableDatabase() {
        try (var connection = connections.getConnection()) { connection.serverCommands().flushDb(); }
        sessions = new RedisLoginSessionStore(redis);
    }

    @Test void missingSessionIsNotAuthenticatedAndNewSessionHasFixedExpiry() {
        assertNull(sessions.read(USER_ID));
        RedisLoginSessionStore.Session next = session(1, "first", 120);

        assertTrue(sessions.replace(USER_ID, null, next));
        assertEquals(next, sessions.read(USER_ID));
        long before = System.currentTimeMillis();
        Long ttl = redis.getExpire(KEY, TimeUnit.MILLISECONDS);
        long after = System.currentTimeMillis();
        assertNotNull(ttl);
        assertTrue(ttl <= next.expiresAtEpochSeconds() * 1000 - before + 50);
        assertTrue(ttl >= next.expiresAtEpochSeconds() * 1000 - after - 50);

        redis.expire(KEY, Duration.ofSeconds(3));
        for (int i = 0; i < 5; i++) assertEquals(next, sessions.read(USER_ID));
        assertTrue(redis.getExpire(KEY, TimeUnit.MILLISECONDS) <= 3000, "Reading a session must not renew its expiry");
    }

    @Test void lateLoginCannotReplaceAChangedSessionOrExtendItsExpiry() {
        var original = session(1, "original", 120);
        var current = session(2, "current", 120);
        var late = session(3, "late", 240);
        assertTrue(sessions.replace(USER_ID, null, original));
        assertTrue(sessions.replace(USER_ID, original, current));
        redis.expire(KEY, Duration.ofSeconds(3));

        assertFalse(sessions.replace(USER_ID, original, late));
        assertFalse(sessions.replace(USER_ID, null, late));
        assertEquals(current, sessions.read(USER_ID));
        assertTrue(redis.getExpire(KEY, TimeUnit.MILLISECONDS) <= 3000);
    }

    @Test void onlyOneConcurrentLoginCanReplaceTheSameObservedSession() throws Exception {
        var original = session(1, "original", 120);
        assertTrue(sessions.replace(USER_ID, null, original));
        assertSingleWinner(original);
    }

    @Test void onlyOneConcurrentLoginCanClaimAnAbsentSession() throws Exception {
        assertSingleWinner(null);
    }

    private void assertSingleWinner(RedisLoginSessionStore.Session expected) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<RedisLoginSessionStore.Session>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                var candidate = session(i + 2, "candidate-" + i, 120);
                results.add(executor.submit(() -> {
                    start.await();
                    return sessions.replace(USER_ID, expected, candidate) ? candidate : null;
                }));
            }
            start.countDown();
            List<RedisLoginSessionStore.Session> winners = new ArrayList<>();
            for (var result : results) {
                var winner = result.get(10, TimeUnit.SECONDS);
                if (winner != null) winners.add(winner);
            }
            assertEquals(1, winners.size());
            assertEquals(winners.getFirst(), sessions.read(USER_ID));
        }
    }

    @Test void logoutCannotRevokeANewerSessionAndUsesStableIdentity() {
        var original = session(1, "original", 120);
        var current = session(2, "current", 120);
        assertTrue(sessions.replace(USER_ID, null, original));
        assertTrue(sessions.replace(USER_ID, original, current));

        assertFalse(sessions.revoke(USER_ID, original));
        assertFalse(sessions.revoke(USER_ID, new RedisLoginSessionStore.Session(1, current.sid(), current.expiresAtEpochSeconds())));
        assertEquals(current, sessions.read(USER_ID));
        assertTrue(sessions.revoke(USER_ID, new RedisLoginSessionStore.Session(current.version(), current.sid(), current.expiresAtEpochSeconds() + 1)));
        assertNull(sessions.read(USER_ID));
        assertFalse(sessions.revoke(USER_ID, current));
    }

    @Test void expiredReplacementCannotDestroyACurrentSessionOrCreateANewOne() {
        var original = session(1, "original", 120);
        var expired = session(2, "expired", -1);
        assertTrue(sessions.replace(USER_ID, null, original));

        assertFalse(sessions.replace(USER_ID, original, expired));
        assertEquals(original, sessions.read(USER_ID));
        assertFalse(sessions.replace(18L, null, expired));
        assertNull(sessions.read(18L));
    }

    @Test void redisExpiresTheSessionAtTheTokenDeadline() throws Exception {
        var expiring = session(1, "short-lived", 2);
        assertTrue(sessions.replace(USER_ID, null, expiring));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (redis.hasKey(KEY) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(redis.hasKey(KEY));
        assertNull(sessions.read(USER_ID));
    }

    @Test void malformedStoredSessionsFailClosed() {
        long expires = Instant.now().plusSeconds(120).getEpochSecond();
        for (String malformed : List.of("broken", "1:sid", "1::" + expires, "-1:sid:" + expires,
                "01:sid:" + expires, "1:sid:not-a-number", "1:sid:" + expires + ":extra", "1:space sid:" + expires)) {
            redis.opsForValue().set(KEY, malformed);
            assertUnavailable(() -> sessions.read(USER_ID));
            assertEquals(malformed, redis.opsForValue().get(KEY));
        }
    }

    @Test void wrongRedisValueTypeFailsClosedForEveryOperation() {
        redis.opsForList().leftPush(KEY, "invalid");
        var next = session(1, "next", 120);
        assertUnavailable(() -> sessions.read(USER_ID));
        assertUnavailable(() -> sessions.replace(USER_ID, null, next));
        assertUnavailable(() -> sessions.revoke(USER_ID, next));
    }

    @Test void unavailableRedisFailsClosedForReadsReplacementsAndLogout() throws Exception {
        int unavailablePort;
        try (ServerSocket available = new ServerSocket(0)) { unavailablePort = available.getLocalPort(); }
        var configuration = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(250))
                .shutdownTimeout(Duration.ZERO).build();
        var unavailableConnections = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", unavailablePort), configuration);
        unavailableConnections.afterPropertiesSet();
        try {
            var unavailableRedis = new StringRedisTemplate(unavailableConnections);
            unavailableRedis.afterPropertiesSet();
            var unavailable = new RedisLoginSessionStore(unavailableRedis);
            var next = session(1, "next", 120);
            assertUnavailable(() -> unavailable.read(USER_ID));
            assertUnavailable(() -> unavailable.replace(USER_ID, null, next));
            assertUnavailable(() -> unavailable.revoke(USER_ID, next));
        } finally {
            unavailableConnections.destroy();
        }
    }

    private RedisLoginSessionStore.Session session(long version, String sid, long secondsRemaining) {
        return new RedisLoginSessionStore.Session(version, sid, Instant.now().plusSeconds(secondsRemaining).getEpochSecond());
    }

    @Test void rotatingRefreshCredentialsKeepsSidAndBothDeadlines() {
        long now=Instant.now().getEpochSecond();
        var first=refreshSession(1,"rotating",now+3600,now+120,"a".repeat(64));
        assertTrue(sessions.replace(USER_ID,null,first));
        var result=sessions.rotate(USER_ID,first,"a".repeat(64),"b".repeat(64),10);
        assertEquals(RedisLoginSessionStore.RotationStatus.ROTATED,result.status());
        var next=result.session();
        assertTrue(first.sameLogin(next));
        assertEquals(first.expiresAtEpochSeconds(),next.expiresAtEpochSeconds());
        assertEquals(first.idleExpiresAtEpochSeconds(),next.idleExpiresAtEpochSeconds());
        assertEquals(2,next.credentialGeneration());
        assertEquals("b".repeat(64),next.refreshDigest());
        assertEquals("a".repeat(64),next.previousRefreshDigest());
        assertEquals(USER_ID,sessions.userIdForSid(first.sid()));
        assertTrue(redis.getExpire(KEY)<=120);
        assertTrue(redis.getExpire("harness:auth:sid:"+first.sid())<=120);
    }

    @Test void concurrentRefreshesRotateOnlyOnceAndOtherRequestsReceiveConflict() throws Exception {
        long now=Instant.now().getEpochSecond();
        var first=refreshSession(1,"concurrent-refresh",now+3600,now+120,"a".repeat(64));
        assertTrue(sessions.replace(USER_ID,null,first));
        try (ExecutorService executor=Executors.newFixedThreadPool(8)) {
            CountDownLatch start=new CountDownLatch(1);
            List<Future<RedisLoginSessionStore.Rotation>> results=new ArrayList<>();
            for (int i=1;i<=16;i++) {
                String digest=String.format("%064x",i);
                results.add(executor.submit(()->{start.await();return sessions.rotate(USER_ID,first,"a".repeat(64),digest,10);}));
            }
            start.countDown();
            int rotated=0;
            for (var future:results) {
                var result=future.get(10,TimeUnit.SECONDS);
                if (result.status()==RedisLoginSessionStore.RotationStatus.ROTATED) rotated++;
                else assertEquals(RedisLoginSessionStore.RotationStatus.CONFLICT,result.status());
            }
            assertEquals(1,rotated);
            assertEquals(2,sessions.read(USER_ID).credentialGeneration());
        }
    }

    @Test void knownReplayRevokesOnlyItsOwnSidAndUnknownCredentialsCannotRevoke() {
        long now=Instant.now().getEpochSecond();
        var first=refreshSession(1,"replayed",now+3600,now+120,"a".repeat(64));
        assertTrue(sessions.replace(USER_ID,null,first));
        var next=sessions.rotate(USER_ID,first,"a".repeat(64),"b".repeat(64),10).session();
        assertEquals(RedisLoginSessionStore.RotationStatus.INVALID,sessions.rotate(USER_ID,next,"c".repeat(64),"d".repeat(64),10).status());
        assertEquals(next,sessions.read(USER_ID));
        assertEquals(RedisLoginSessionStore.RotationStatus.CONFLICT,sessions.rotate(USER_ID,first,"a".repeat(64),"d".repeat(64),10).status());
        var elapsed=new RedisLoginSessionStore.Session(next.version(),next.sid(),next.expiresAtEpochSeconds(),next.idleExpiresAtEpochSeconds(),
                next.credentialGeneration(),next.refreshDigest(),next.previousRefreshDigest(),now-1);
        assertTrue(sessions.replace(USER_ID,next,elapsed));
        assertEquals(RedisLoginSessionStore.RotationStatus.REPLAYED,sessions.rotate(USER_ID,first,"a".repeat(64),"d".repeat(64),10).status());
        assertNull(sessions.read(USER_ID));
        assertNull(sessions.userIdForSid(first.sid()));
        var newer=refreshSession(2,"newer",now+3600,now+120,"e".repeat(64));
        assertTrue(sessions.replace(USER_ID,null,newer));
        assertEquals(RedisLoginSessionStore.RotationStatus.INVALID,sessions.rotate(USER_ID,first,"a".repeat(64),"d".repeat(64),10).status());
        assertEquals(newer,sessions.read(USER_ID));
    }

    @Test void explicitActivityExtendsIdleMonotonicallyAndNeverExceedsAbsoluteExpiry() {
        long now=Instant.now().getEpochSecond();
        var first=refreshSession(1,"active",now+90,now+20,"a".repeat(64));
        assertTrue(sessions.replace(USER_ID,null,first));
        var touched=sessions.touch(USER_ID,first,7200);
        assertEquals(first.expiresAtEpochSeconds(),touched.idleExpiresAtEpochSeconds());
        assertEquals(first.refreshDigest(),touched.refreshDigest());
        assertEquals(first.credentialGeneration(),touched.credentialGeneration());
        assertEquals(touched,sessions.touch(USER_ID,first,1));
        assertTrue(redis.getExpire(KEY)<=90);
        assertTrue(redis.getExpire("harness:auth:sid:"+first.sid())<=90);
        var later=refreshSession(2,"later",now+3600,now+120,"b".repeat(64));
        assertTrue(sessions.replace(USER_ID,first,later),"Activity metadata must not break replacing the observed login");
        assertNull(sessions.userIdForSid(first.sid()));
        assertEquals(USER_ID,sessions.userIdForSid(later.sid()));
        assertNull(sessions.touch(USER_ID,first,7200));
    }

    @Test void idleExpiryCannotBeRevivedEvenWhenItsRedisValueWasRestored() {
        long now=Instant.now().getEpochSecond();
        var expired=refreshSession(1,"expired-idle",now+3600,now-1,"a".repeat(64));
        redis.opsForValue().set(KEY,encoded(expired));
        assertNull(sessions.touch(USER_ID,expired,7200));
        assertEquals(RedisLoginSessionStore.RotationStatus.INVALID,sessions.rotate(USER_ID,expired,"a".repeat(64),"b".repeat(64),10).status());
        assertEquals(expired,sessions.read(USER_ID));
    }

    private RedisLoginSessionStore.Session refreshSession(long version,String sid,long absolute,long idle,String digest) {
        return new RedisLoginSessionStore.Session(version,sid,absolute,idle,1,digest,"",0);
    }
    private String encoded(RedisLoginSessionStore.Session s) {
        return s.version()+":"+s.sid()+":"+s.expiresAtEpochSeconds()+":"+s.idleExpiresAtEpochSeconds()+":"+s.credentialGeneration()
                +":"+s.refreshDigest()+":"+s.previousRefreshDigest()+":"+s.previousRefreshValidUntilEpochSeconds();
    }

    private void assertUnavailable(org.junit.jupiter.api.function.Executable action) {
        BusinessException exception = assertThrows(BusinessException.class, action);
        assertEquals(ErrorCode.SESSION_UNAVAILABLE, exception.getErrorCode());
    }
}
