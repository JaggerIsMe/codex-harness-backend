package com.myharness.codex.service.mail;

import com.myharness.codex.config.AccountMailProperties;
import com.myharness.codex.entity.po.MailDeliveryTaskPO;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.mapper.MailDeliveryTaskMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AccountMailService {
    private final MailDeliveryTaskMapper tasks;
    private final AccountMailCrypto crypto;
    private final AccountMailSettings settings;
    private final AccountMailProperties properties;
    private final Clock clock;

    public AccountMailService(MailDeliveryTaskMapper tasks, AccountMailCrypto crypto, AccountMailSettings settings,
                              AccountMailProperties properties, @Qualifier("accountMailClock") Clock clock) {
        this.tasks = tasks;
        this.crypto = crypto;
        this.settings = settings;
        this.properties = properties;
        this.clock = clock;
    }

    public void requireSendingConfigured() { settings.requireSendingConfigured(); }

    public int resendCooldownSeconds() { return properties.getResendCooldownSeconds(); }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueActivation(SysUserPO user, Long challengeId, String token, LocalDateTime expiresAt) {
        enqueueChallenge(user, challengeId, "ACTIVATION", token, expiresAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueuePasswordReset(SysUserPO user, Long challengeId, String code, LocalDateTime expiresAt) {
        enqueueChallenge(user, challengeId, "PASSWORD_RESET", code, expiresAt);
    }

    private void enqueueChallenge(SysUserPO user, Long challengeId, String template, String credential, LocalDateTime expiresAt) {
        requireSendingConfigured();
        MailDeliveryTaskPO task = task(user, template, "challenge:" + challengeId, expiresAt);
        task.setChallengeId(challengeId);
        task.setEncryptedPayload(crypto.encrypt(credential, task.payloadBinding()));
        tasks.insert(task);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueuePasswordChanged(SysUserPO user) { enqueuePasswordChanged(user, UUID.randomUUID().toString()); }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueuePasswordChanged(SysUserPO user, String eventId) {
        // This fixed notification contains no credential or personalized template parameters.
        // It is still recorded while sending is disabled, without requiring an encryption key.
        tasks.insert(task(user, "PASSWORD_CHANGED", "password-changed:" + user.getId() + ":" + eventId,
                LocalDateTime.now(clock).plusHours(properties.getNotificationTtlHours())));
    }

    private MailDeliveryTaskPO task(SysUserPO user, String template, String key, LocalDateTime expiresAt) {
        LocalDateTime now = LocalDateTime.now(clock);
        MailDeliveryTaskPO task = new MailDeliveryTaskPO();
        task.setUserId(user.getId());
        task.setRecipient(user.getEmail());
        task.setTemplate(template);
        task.setStatus("PENDING");
        task.setIdempotencyKey(key);
        task.setExpiresAt(expiresAt);
        task.setNextAttemptAt(now);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeChallengesTasks(Long userId) { tasks.cancelChallenges(userId, null); }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeChallengesTasks(Long userId, String purpose) { tasks.cancelChallenges(userId, purpose); }

    public String latestActivationStatus(Long userId) {
        String status = tasks.latestActivationStatus(userId);
        return "ACCEPTED".equals(status) ? "SENT" : status;
    }
}
