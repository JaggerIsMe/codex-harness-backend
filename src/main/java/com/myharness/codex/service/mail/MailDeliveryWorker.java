package com.myharness.codex.service.mail;

import com.myharness.codex.config.AccountMailProperties;
import com.myharness.codex.entity.po.MailDeliveryTaskPO;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.mapper.MailDeliveryTaskMapper;
import com.myharness.codex.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class MailDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(MailDeliveryWorker.class);
    private final MailDeliveryTaskMapper tasks;
    private final SysUserMapper users;
    private final MailChallengeEligibility eligibility;
    private final AccountMailCrypto crypto;
    private final AccountMailProperties properties;
    private final MailDeliveryAdapter adapter;
    private final Clock clock;

    public MailDeliveryWorker(MailDeliveryTaskMapper tasks, SysUserMapper users, MailChallengeEligibility eligibility,
                              AccountMailCrypto crypto, AccountMailProperties properties, MailDeliveryAdapter adapter,
                              @Qualifier("accountMailClock") Clock clock) {
        this.tasks = tasks;
        this.users = users;
        this.eligibility = eligibility;
        this.crypto = crypto;
        this.properties = properties;
        this.adapter = adapter;
        this.clock = clock;
    }

    @Scheduled(scheduler = "accountMailScheduler", fixedDelayString = "${harness.mail.delivery-poll-milliseconds:5000}", initialDelayString = "${harness.mail.delivery-poll-milliseconds:5000}")
    public void deliverPending() {
        if (!properties.isEnabled()) return;
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            for (Long id : tasks.findAvailable(now, properties.getMaxDeliveryAttempts(), properties.getDeliveryBatchSize())) {
                String lease = UUID.randomUUID().toString();
                LocalDateTime claimTime = LocalDateTime.now(clock);
                if (tasks.claim(id, lease, claimTime, claimTime.plusSeconds(properties.getDeliveryLeaseSeconds()), properties.getMaxDeliveryAttempts()) == 1) {
                    deliverClaimed(id, lease);
                }
            }
        } catch (RuntimeException ex) {
            // Driver/SMTP exception messages can contain recipient addresses or message contents.
            log.warn("Account mail delivery cycle unavailable; tasks will recover using their leases");
        }
    }

    void deliverClaimed(Long id, String lease) {
        MailDeliveryTaskPO task = tasks.selectClaimed(id, lease);
        if (task == null) return;
        LocalDateTime now = LocalDateTime.now(clock);
        if (!task.getExpiresAt().isAfter(now) || !deliverable(task, now)) {
            tasks.finish(id, lease, "CANCELLED", "CHALLENGE_INVALID", null, now);
            return;
        }
        String text;
        String subject;
        try {
            switch (task.getTemplate()) {
                case "ACTIVATION" -> {
                    String token = crypto.decrypt(task.getEncryptedPayload(), task.payloadBinding());
                    if (!token.matches("[A-Za-z0-9_-]{43}")) throw new IllegalStateException();
                    String base = properties.getPublicBaseUrl().replaceAll("/+$", "");
                    subject = "激活你的 My Harness For Codex 账号";
                    text = "你已受邀使用 My Harness For Codex。\n\n请打开以下链接设置密码并激活账号：\n"
                            + base + "/activate?token=" + token + "\n\n链接有效至 " + expiry(task)
                            + "。链接只能成功使用一次，重发后旧链接失效。\n如非本人操作，请忽略此邮件。";
                }
                case "PASSWORD_RESET" -> {
                    String code = crypto.decrypt(task.getEncryptedPayload(), task.payloadBinding());
                    if (!code.matches("[0-9]{6}")) throw new IllegalStateException();
                    subject = "My Harness For Codex 密码重置验证码";
                    text = "你的密码重置验证码为：" + code + "\n\n有效至 " + expiry(task)
                            + "。请勿向他人提供验证码，重发后旧验证码失效。\n如非本人操作，请忽略此邮件，你的密码不会因此改变。";
                }
                case "PASSWORD_CHANGED" -> {
                    subject = "My Harness For Codex 密码已变更";
                    text = "你的 My Harness For Codex 账号密码已变更。旧登录凭证已失效。\n\n如果这不是你本人操作，请立即通过忘记密码重新设置密码并联系系统管理员。";
                }
                default -> throw new IllegalStateException();
            }
        } catch (RuntimeException ex) {
            tasks.finish(id, lease, "FAILED", "PAYLOAD_INVALID", null, LocalDateTime.now(clock));
            return;
        }
        try { adapter.send(task.getRecipient(), subject, text); }
        catch (RuntimeException ex) {
            LocalDateTime failedAt = LocalDateTime.now(clock);
            long delay = Math.min(3600, properties.getRetryBaseSeconds() * (1L << Math.min(10, task.getAttempts() - 1)));
            LocalDateTime next = failedAt.plusSeconds(delay);
            if (task.getAttempts() >= properties.getMaxDeliveryAttempts() || !next.isBefore(task.getExpiresAt())) {
                tasks.finish(id, lease, "FAILED", "SMTP_RETRY_EXHAUSTED", null, failedAt);
            } else tasks.retry(id, lease, next, failedAt);
            log.warn("Account mail delivery attempt failed; taskId={}, attempt={}", id, task.getAttempts());
            return;
        }
        // Database acknowledgement may fail after SMTP accepts the message. Lease recovery may send
        // the same credential again; the account service guarantees that it can only be consumed once.
        LocalDateTime accepted = LocalDateTime.now(clock);
        tasks.finish(id, lease, "ACCEPTED", null, accepted, accepted);
    }

    private boolean deliverable(MailDeliveryTaskPO task, LocalDateTime now) {
        if (task.getChallengeId() != null) {
            return eligibility.isDeliverable(task.getChallengeId(), task.getUserId(), task.getTemplate(), task.getRecipient(), now);
        }
        SysUserPO user = users.selectById(task.getUserId());
        return "PASSWORD_CHANGED".equals(task.getTemplate()) && user != null && task.getRecipient().equals(user.getEmail());
    }

    private String expiry(MailDeliveryTaskPO task) {
        return task.getExpiresAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "（" + clock.getZone() + "）";
    }

    @Scheduled(fixedDelayString = "${harness.mail.cleanup-poll-milliseconds:60000}", initialDelayString = "${harness.mail.cleanup-poll-milliseconds:60000}")
    public void cleanUp() {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            tasks.expire(now);
            tasks.failExhausted(now, properties.getMaxDeliveryAttempts());
            tasks.deleteOldMetadata(now.minusDays(properties.getMetadataRetentionDays()));
        } catch (RuntimeException ex) { log.warn("Account mail cleanup unavailable; cleanup will be retried"); }
    }
}
