package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

public class SystemInitializationPO {
    private String initializationKey;
    private Long userId;
    private String email;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    public String getInitializationKey() { return initializationKey; }
    public void setInitializationKey(String value) { initializationKey = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getEmail() { return email; }
    public void setEmail(String value) { email = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime value) { completedAt = value; }
}
