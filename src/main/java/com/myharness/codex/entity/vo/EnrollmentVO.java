package com.myharness.codex.entity.vo;

import java.time.LocalDateTime;

public class EnrollmentVO {
    private final String enrollmentCode;
    private final LocalDateTime expiresAt;

    public EnrollmentVO(String enrollmentCode, LocalDateTime expiresAt) {
        this.enrollmentCode = enrollmentCode;
        this.expiresAt = expiresAt;
    }
    public String getEnrollmentCode() { return enrollmentCode; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
}
