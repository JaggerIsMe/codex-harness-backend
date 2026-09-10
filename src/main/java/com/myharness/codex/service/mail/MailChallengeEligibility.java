package com.myharness.codex.service.mail;

import java.time.LocalDateTime;

/** Rechecks the challenge's current generation and user eligibility immediately before delivery. */
public interface MailChallengeEligibility {
    boolean isDeliverable(Long challengeId, Long userId, String purpose, String recipient, LocalDateTime now);
}
