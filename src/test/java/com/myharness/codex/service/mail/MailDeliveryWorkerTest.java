package com.myharness.codex.service.mail;

import com.myharness.codex.config.AccountMailProperties;
import com.myharness.codex.entity.po.MailDeliveryTaskPO;
import com.myharness.codex.mapper.MailDeliveryTaskMapper;
import com.myharness.codex.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailDeliveryWorkerTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), ZoneOffset.UTC);
    private final LocalDateTime now = LocalDateTime.now(clock);
    private final AccountMailProperties properties = AccountMailCryptoTest.properties();
    private final AccountMailCrypto crypto = new AccountMailCrypto(properties);
    private final MailDeliveryTaskMapper tasks = mock(MailDeliveryTaskMapper.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final MailChallengeEligibility eligibility = mock(MailChallengeEligibility.class);
    private final MailDeliveryAdapter adapter = mock(MailDeliveryAdapter.class);
    private final MailDeliveryWorker worker = new MailDeliveryWorker(tasks, users, eligibility, crypto, properties, adapter, clock);
    private MailDeliveryTaskPO task;

    @BeforeEach void prepare() {
        properties.setEnabled(true);
        properties.setPublicBaseUrl("https://harness.example.com/");
        task = new MailDeliveryTaskPO();
        task.setId(9L);
        task.setUserId(7L);
        task.setChallengeId(4L);
        task.setTemplate("PASSWORD_RESET");
        task.setRecipient("person@example.com");
        task.setIdempotencyKey("challenge:4");
        task.setExpiresAt(now.plusMinutes(10));
        task.setAttempts(1);
        task.setEncryptedPayload(crypto.encrypt("000007", task.payloadBinding()));
        when(tasks.selectClaimed(9L, "lease")).thenReturn(task);
        when(eligibility.isDeliverable(4L, 7L, "PASSWORD_RESET", "person@example.com", now)).thenReturn(true);
    }

    @Test void smtpAcceptanceMarksTaskAcceptedAndRequestsPayloadScrubbing() {
        worker.deliverClaimed(9L, "lease");
        verify(adapter).send(eq("person@example.com"), contains("验证码"), contains("000007"));
        verify(tasks).finish(9L, "lease", "ACCEPTED", null, now, now);
    }

    @Test void invalidatedChallengeNeverReachesSmtp() {
        when(eligibility.isDeliverable(any(), any(), any(), any(), any())).thenReturn(false);
        worker.deliverClaimed(9L, "lease");
        verifyNoInteractions(adapter);
        verify(tasks).finish(9L, "lease", "CANCELLED", "CHALLENGE_INVALID", null, now);
    }

    @Test void expiredChallengeNeverReachesSmtp() {
        task.setExpiresAt(now);
        worker.deliverClaimed(9L, "lease");
        verifyNoInteractions(adapter);
        verify(tasks).finish(9L, "lease", "CANCELLED", "CHALLENGE_INVALID", null, now);
    }

    @Test void temporaryFailureRetriesSameEncryptedCredentialWithoutExtendingExpiry() {
        doThrow(new IllegalStateException("must never enter stored errors")).when(adapter).send(any(), any(), any());
        String encrypted = task.getEncryptedPayload();
        worker.deliverClaimed(9L, "lease");
        verify(tasks).retry(9L, "lease", now.plusSeconds(30), now);
        assertEquals(encrypted, task.getEncryptedPayload());
        assertEquals(now.plusMinutes(10), task.getExpiresAt());
        verify(tasks, never()).finish(any(), any(), any(), any(), any(), any());
    }

    @Test void lastAttemptFailsAndScrubsInsteadOfSchedulingAnotherRetry() {
        task.setAttempts(properties.getMaxDeliveryAttempts());
        doThrow(new IllegalStateException()).when(adapter).send(any(), any(), any());
        worker.deliverClaimed(9L, "lease");
        verify(tasks).finish(9L, "lease", "FAILED", "SMTP_RETRY_EXHAUSTED", null, now);
        verify(tasks, never()).retry(any(), any(), any(), any());
    }

    @Test void tamperedPayloadIsTerminalAndIsNeverSent() {
        task.setRecipient("other@example.com");
        when(eligibility.isDeliverable(any(), any(), any(), any(), any())).thenReturn(true);
        worker.deliverClaimed(9L, "lease");
        verifyNoInteractions(adapter);
        verify(tasks).finish(9L, "lease", "FAILED", "PAYLOAD_INVALID", null, now);
    }

    @Test void losingAtomicLeaseCannotSendTheTask() {
        when(tasks.findAvailable(now, 5, 20)).thenReturn(List.of(9L));
        when(tasks.claim(eq(9L), anyString(), eq(now), eq(now.plusSeconds(120)), eq(5))).thenReturn(0);
        worker.deliverPending();
        verify(tasks, never()).selectClaimed(any(), any());
        verifyNoInteractions(adapter);
    }

    @Test void disabledSendingStillAllowsExpirationCleanup() {
        properties.setEnabled(false);
        worker.deliverPending();
        verifyNoInteractions(tasks, adapter);
        worker.cleanUp();
        verify(tasks).expire(now);
        verify(tasks).failExhausted(now, 5);
        verify(tasks).deleteOldMetadata(now.minusDays(30));
    }
}
