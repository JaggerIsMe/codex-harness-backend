package com.myharness.codex.entity.po;

public class WorkspaceFileOperationPO {
    private Long id;
    public Long getId() { return id; }
    public void setId(Long value) { id=value; }
    private String requestKey;
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String value) { requestKey=value; }
    private Long userId;
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId=value; }
    private Long projectId;
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long value) { projectId=value; }
    private Long deviceId;
    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long value) { deviceId=value; }
    private String workspaceName;
    public String getWorkspaceName() { return workspaceName; }
    public void setWorkspaceName(String value) { workspaceName=value; }
    private String kind;
    public String getKind() { return kind; }
    public void setKind(String value) { kind=value; }
    private String path;
    public String getPath() { return path; }
    public void setPath(String value) { path=value; }
    private String cursor;
    public String getPageCursor() { return cursor; }
    public void setPageCursor(String value) { cursor=value; }
    public String getCursor() { return cursor; }
    public void setCursor(String value) { cursor=value; }
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { status=value; }
    private String storageKey;
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String value) { storageKey=value; }
    private String sha256;
    public String getSha256() { return sha256; }
    public void setSha256(String value) { sha256=value; }
    private long sizeBytes;
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long value) { sizeBytes=value; }
    private Long attachmentId;
    public Long getAttachmentId() { return attachmentId; }
    public void setAttachmentId(Long value) { attachmentId=value; }
    private String error;
    public String getError() { return error; }
    public void setError(String value) { error=value; }
    private java.time.LocalDateTime createdAt;
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { createdAt=value; }
    private java.time.LocalDateTime updatedAt;
    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime value) { updatedAt=value; }
}
