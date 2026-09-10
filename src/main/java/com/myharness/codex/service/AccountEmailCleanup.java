package com.myharness.codex.service;

import com.myharness.codex.config.AccountMailProperties;
import com.myharness.codex.mapper.AccountEmailChallengeMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AccountEmailCleanup {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountEmailCleanup.class);
    private final AccountEmailChallengeMapper challenges;
    private final AccountMailProperties properties;
    private final Clock clock;

    public AccountEmailCleanup(AccountEmailChallengeMapper challenges, AccountMailProperties properties,
                               @Qualifier("accountMailClock") Clock clock) {
        this.challenges = challenges;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${harness.mail.cleanup-poll-milliseconds:60000}", initialDelayString = "${harness.mail.cleanup-poll-milliseconds:60000}")
    public void cleanExpiredCredentials() {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            challenges.expire(now);
            challenges.deleteOldMetadata(now.minusDays(properties.getMetadataRetentionDays()));
        } catch (RuntimeException ex) {
            LOGGER.warn("Account email credential cleanup unavailable; cleanup will retry on the next cycle");
        }
    }
}
