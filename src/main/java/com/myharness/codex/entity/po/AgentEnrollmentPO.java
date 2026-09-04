package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

public class AgentEnrollmentPO {
    private Long id;
    private String enrollmentCodeHash;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
    private Long createdBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEnrollmentCodeHash() { return enrollmentCodeHash; }
    public void setEnrollmentCodeHash(String value) { this.enrollmentCodeHash = value; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getUsedAt() { return usedAt; }
    public void setUsedAt(LocalDateTime usedAt) { this.usedAt = usedAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}
