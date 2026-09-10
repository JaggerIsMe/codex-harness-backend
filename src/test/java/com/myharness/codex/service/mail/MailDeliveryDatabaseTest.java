package com.myharness.codex.service.mail;

import com.myharness.codex.entity.po.MailDeliveryTaskPO;
import com.myharness.codex.mapper.MailDeliveryTaskMapper;
import com.myharness.codex.support.IsolatedMysql;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "mysql.isolated.integration", matches = "true")
class MailDeliveryDatabaseTest {
    private static IsolatedMysql database;
    private static MailDeliveryTaskMapper tasks;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 10, 10, 0);

    @BeforeAll static void startDatabase() throws Exception {
        database = IsolatedMysql.start();
        database.createTablesFromSchema("mail_delivery_task");
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(database.dataSource());
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(MailDeliveryTaskMapper.class);
        bean.setConfiguration(configuration);
        SqlSessionFactory factory = bean.getObject();
        tasks = new SqlSessionTemplate(factory).getMapper(MailDeliveryTaskMapper.class);
        jdbc = new JdbcTemplate(database.dataSource());
        transaction = new TransactionTemplate(new DataSourceTransactionManager(database.dataSource()));
    }

    @AfterAll static void stopDatabase() throws Exception { if (database != null) database.close(); }

    @BeforeEach void clearTasks() { jdbc.update("DELETE FROM mail_delivery_task"); }

    private MailDeliveryTaskPO insert(String key, String template, Long challengeId) {
        MailDeliveryTaskPO task = new MailDeliveryTaskPO();
        task.setUserId(7L);
        task.setChallengeId(challengeId);
        task.setTemplate(template);
        task.setRecipient("person@example.com");
        task.setEncryptedPayload("test-ciphertext");
        task.setIdempotencyKey(key);
        task.setNextAttemptAt(now);
        task.setExpiresAt(now.plusHours(1));
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        tasks.insert(task);
        return task;
    }

    @Test void enqueueRollsBackWithItsBusinessTransactionAndIsIdempotent() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            insert("event-1", "ACTIVATION", 4L);
            throw new IllegalStateException("business mutation failed");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mail_delivery_task", Integer.class));
        insert("event-1", "ACTIVATION", 4L);
        insert("event-1", "ACTIVATION", 4L);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mail_delivery_task", Integer.class));
    }

    @Test void exactlyOneConcurrentWorkerClaimsAvailableTask() throws Exception {
        MailDeliveryTaskPO task = insert("event-1", "ACTIVATION", 4L);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String lease = "worker-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return tasks.claim(task.getId(), lease, now, now.plusSeconds(120), 5);
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Integer> result : results) winners += result.get(10, TimeUnit.SECONDS);
            assertEquals(1, winners);
            assertEquals(1, jdbc.queryForObject("SELECT attempts FROM mail_delivery_task", Integer.class));
            assertTrue(tasks.findAvailable(now, 5, 20).isEmpty());
        }
    }

    @Test void expiredLeaseCanBeRecoveredAndOldWorkerCannotAcknowledgeNewLease() {
        MailDeliveryTaskPO task = insert("event-1", "ACTIVATION", 4L);
        assertEquals(1, tasks.claim(task.getId(), "old", now, now.plusSeconds(120), 5));
        LocalDateTime later = now.plusSeconds(121);
        assertEquals(List.of(task.getId()), tasks.findAvailable(later, 5, 20));
        assertEquals(1, tasks.claim(task.getId(), "new", later, later.plusSeconds(120), 5));
        assertNull(tasks.selectClaimed(task.getId(), "old"));
        assertEquals(0, tasks.finish(task.getId(), "old", "ACCEPTED", null, later, later));
        assertEquals(1, tasks.finish(task.getId(), "new", "ACCEPTED", null, later, later));
        assertNull(jdbc.queryForObject("SELECT encrypted_payload FROM mail_delivery_task", String.class));
        assertEquals("ACCEPTED", tasks.latestActivationStatus(7L));
    }

    @Test void revocationCancelsPendingAndProcessingChallengesButPreservesNotifications() {
        MailDeliveryTaskPO task = insert("event-1", "ACTIVATION", 4L);
        insert("event-2", "PASSWORD_RESET", 5L);
        insert("event-3", "PASSWORD_CHANGED", null);
        tasks.claim(task.getId(), "lease", now, now.plusSeconds(120), 5);
        assertEquals(1, tasks.cancelChallenges(7L, "ACTIVATION"));
        assertNull(tasks.selectClaimed(task.getId(), "lease"));
        assertEquals(1, tasks.cancelChallenges(7L, null));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mail_delivery_task WHERE status='CANCELLED' AND encrypted_payload IS NULL", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mail_delivery_task WHERE status='PENDING' AND challenge_id IS NULL", Integer.class));
    }

    @Test void expirationAndExhaustionScrubPayloadAndMetadataIsEventuallyDeleted() {
        MailDeliveryTaskPO expired = insert("event-1", "ACTIVATION", 4L);
        insert("event-2", "PASSWORD_RESET", 5L);
        jdbc.update("UPDATE mail_delivery_task SET expires_at=? WHERE id=?", now, expired.getId());
        jdbc.update("UPDATE mail_delivery_task SET attempts=5 WHERE template='PASSWORD_RESET'");
        assertEquals(1, tasks.expire(now));
        assertEquals(1, tasks.failExhausted(now, 5));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mail_delivery_task WHERE encrypted_payload IS NULL", Integer.class));
        assertEquals(2, tasks.deleteOldMetadata(now.plusDays(31)));
    }
}
