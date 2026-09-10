package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

/** Deliberately has no toString: the encrypted payload and recipient must not enter routine logs. */
public class MailDeliveryTaskPO {
    private Long id;
    private Long userId;
    private Long challengeId;
    private String template;
    private String recipient;
    private String encryptedPayload;
    private String status;
    private int attempts;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime leaseUntil;
    private String leaseToken;
    private String errorCode;
    private LocalDateTime acceptedAt;
    private LocalDateTime expiresAt;
    private String idempotencyKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String payloadBinding() { return userId + "\n" + challengeId + "\n" + template + "\n" + recipient + "\n" + idempotencyKey; }
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public Long getChallengeId() { return challengeId; }
    public void setChallengeId(Long value) { challengeId = value; }
    public String getTemplate() { return template; }
    public void setTemplate(String value) { template = value; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String value) { recipient = value; }
    public String getEncryptedPayload() { return encryptedPayload; }
    public void setEncryptedPayload(String value) { encryptedPayload = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int value) { attempts = value; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime value) { nextAttemptAt = value; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime value) { leaseUntil = value; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String value) { leaseToken = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public LocalDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(LocalDateTime value) { acceptedAt = value; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime value) { expiresAt = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
