package com.myharness.codex.service.mail;

import com.myharness.codex.config.AccountMailProperties;
import com.myharness.codex.entity.po.AccountEmailChallengePO;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.mapper.AccountEmailChallengeMapper;
import com.myharness.codex.mapper.SysUserMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DefaultMailChallengeEligibility implements MailChallengeEligibility {
    private final AccountEmailChallengeMapper challenges;
    private final SysUserMapper users;
    private final AccountMailProperties properties;

    public DefaultMailChallengeEligibility(AccountEmailChallengeMapper challenges, SysUserMapper users, AccountMailProperties properties) {
        this.challenges = challenges;
        this.users = users;
        this.properties = properties;
    }

    @Override
    public boolean isDeliverable(Long challengeId, Long userId, String purpose, String recipient, LocalDateTime now) {
        AccountEmailChallengePO challenge = challenges.findById(challengeId);
        if (challenge == null || !Objects.equals(userId, challenge.getUserId()) || !purpose.equals(challenge.getPurpose())
                || !recipient.equals(challenge.getEmail()) || !"ACTIVE".equals(challenge.getStatus())
                || !challenge.getExpiresAt().isAfter(now) || challenge.getFailedAttempts() >= properties.getMaxVerificationFailures()) return false;
        AccountEmailChallengePO latest = challenges.latest(userId, purpose);
        if (latest == null || !Objects.equals(challengeId, latest.getId())) return false;
        SysUserPO user = users.selectById(userId);
        if (user == null || !"ENABLED".equals(user.getStatus()) || !recipient.equals(user.getEmail())) return false;
        return switch (purpose) {
            case "ACTIVATION" -> user.getActivatedAt() == null && user.getEmailVerifiedAt() == null && user.getPasswordHash() == null;
            case "PASSWORD_RESET" -> user.isActivated();
            default -> false;
        };
    }
}
