package com.myharness.codex.service.mail;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;

public class MailRateLimitException extends BusinessException {
    private final long retryAfterSeconds;

    public MailRateLimitException(long retryAfterSeconds) {
        super(ErrorCode.EMAIL_RATE_LIMITED);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
