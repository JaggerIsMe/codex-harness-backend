package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

public class AccountEmailChallengePO {
    private Long id;
    private Long userId;
    private String email;
    private String purpose;
    private String generation;
    private String digest;
    private LocalDateTime expiresAt;
    private int failedAttempts;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime usedAt;
    private LocalDateTime revokedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getEmail() { return email; }
    public void setEmail(String value) { email = value; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String value) { purpose = value; }
    public String getGeneration() { return generation; }
    public void setGeneration(String value) { generation = value; }
    public String getDigest() { return digest; }
    public void setDigest(String value) { digest = value; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime value) { expiresAt = value; }
    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int value) { failedAttempts = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUsedAt() { return usedAt; }
    public void setUsedAt(LocalDateTime value) { usedAt = value; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime value) { revokedAt = value; }
}
