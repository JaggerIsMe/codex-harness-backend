package com.myharness.codex.service.mail;

import com.myharness.codex.config.AccountMailConfiguration;
import com.myharness.codex.config.AccountMailProperties;
import com.myharness.codex.entity.po.MailDeliveryTaskPO;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.mapper.MailDeliveryTaskMapper;
import com.myharness.codex.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountMailSchedulingTest {
    @Test void blockedSmtpDeliveryDoesNotDelayOrdinaryTasksOrMailCleanup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(ScheduledFixture.class)
                .withPropertyValues("harness.mail.delivery-poll-milliseconds=10", "harness.mail.cleanup-poll-milliseconds=10",
                        "spring.task.scheduling.pool.size=1", "spring.task.scheduling.thread-name-prefix=ordinary-test-")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    BlockingMail adapter = context.getBean(BlockingMail.class);
                    OrdinaryProbe ordinary = context.getBean(OrdinaryProbe.class);
                    try {
                        assertTrue(adapter.entered.await(5, TimeUnit.SECONDS), "The production worker must reach the blocking SMTP adapter");
                        assertTrue(ordinary.triggeredWhileMailBlocked.await(5, TimeUnit.SECONDS), "An ordinary scheduled task must run while SMTP remains blocked");
                        assertTrue(adapter.cleanupWhileBlocked.await(5, TimeUnit.SECONDS), "Mail cleanup must remain on the ordinary scheduler");
                        assertThat(adapter.release.getCount()).isEqualTo(1);
                        assertThat(adapter.threadName).startsWith("account-mail-");
                        assertThat(ordinary.threadName).startsWith("ordinary-test-");
                        assertThat(context.getBean("taskScheduler", ThreadPoolTaskScheduler.class).getPoolSize()).isEqualTo(1);
                        assertThat(context.getBean("accountMailScheduler")).isNotSameAs(context.getBean("taskScheduler"));
                    } finally {
                        adapter.release.countDown();
                        adapter.finished.await(5, TimeUnit.SECONDS);
                    }
                });
    }

    static class BlockingMail implements MailDeliveryAdapter {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch cleanupWhileBlocked = new CountDownLatch(1);
        volatile String threadName;

        @Override public void send(String recipient, String subject, String text) {
            threadName = Thread.currentThread().getName();
            entered.countDown();
            try { release.await(15, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { finished.countDown(); }
        }
    }

    static class OrdinaryProbe {
        private final BlockingMail mail;
        final CountDownLatch triggeredWhileMailBlocked = new CountDownLatch(1);
        volatile String threadName;
        OrdinaryProbe(BlockingMail mail) { this.mail = mail; }

        @Scheduled(fixedDelay = 10)
        public void tick() {
            if (mail.entered.getCount() == 0 && mail.release.getCount() == 1) {
                threadName = Thread.currentThread().getName();
                triggeredWhileMailBlocked.countDown();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(AccountMailConfiguration.class)
    static class ScheduledFixture {
        @Bean BlockingMail adapter() { return new BlockingMail(); }
        @Bean OrdinaryProbe ordinary(BlockingMail adapter) { return new OrdinaryProbe(adapter); }
        @Bean AccountMailProperties properties() {
            AccountMailProperties properties = new AccountMailProperties();
            properties.setEnabled(true);
            return properties;
        }
        @Bean AccountMailCrypto crypto(AccountMailProperties properties) { return new AccountMailCrypto(properties); }
        @Bean MailChallengeEligibility eligibility() { return mock(MailChallengeEligibility.class); }
        @Bean SysUserMapper users() {
            SysUserMapper users = mock(SysUserMapper.class);
            SysUserPO user = new SysUserPO();
            user.setId(7L);
            user.setEmail("person@example.com");
            when(users.selectById(7L)).thenReturn(user);
            return users;
        }
        @Bean MailDeliveryTaskMapper tasks(BlockingMail adapter) {
            MailDeliveryTaskMapper tasks = mock(MailDeliveryTaskMapper.class);
            AtomicBoolean claimed = new AtomicBoolean();
            MailDeliveryTaskPO task = new MailDeliveryTaskPO();
            task.setId(9L);
            task.setUserId(7L);
            task.setTemplate("PASSWORD_CHANGED");
            task.setRecipient("person@example.com");
            task.setExpiresAt(LocalDateTime.now(Clock.systemUTC()).plusHours(1));
            when(tasks.findAvailable(any(), anyInt(), anyInt())).thenReturn(List.of(9L));
            when(tasks.claim(eq(9L), anyString(), any(), any(), anyInt())).thenAnswer(call -> claimed.compareAndSet(false, true) ? 1 : 0);
            when(tasks.selectClaimed(eq(9L), anyString())).thenReturn(task);
            when(tasks.expire(any())).thenAnswer(call -> {
                if (adapter.entered.getCount() == 0 && adapter.release.getCount() == 1) adapter.cleanupWhileBlocked.countDown();
                return 0;
            });
            return tasks;
        }
        @Bean MailDeliveryWorker worker(MailDeliveryTaskMapper tasks, SysUserMapper users, MailChallengeEligibility eligibility,
                                       AccountMailCrypto crypto, AccountMailProperties properties, BlockingMail adapter, Clock clock) {
            return new MailDeliveryWorker(tasks, users, eligibility, crypto, properties, adapter, clock);
        }
    }
}
