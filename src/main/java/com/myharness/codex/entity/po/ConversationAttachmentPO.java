package com.myharness.codex.entity.po;

public class ConversationAttachmentPO {
    private String workspaceLocationState = "AVAILABLE";
    private long locationRevision;
    private Long lastFileOperationId;
    public String getWorkspaceLocationState() {return workspaceLocationState;}
    public void setWorkspaceLocationState(String value) {workspaceLocationState=value;}
    public long getLocationRevision() {return locationRevision;}
    public void setLocationRevision(long value) {locationRevision=value;}
    public Long getLastFileOperationId() {return lastFileOperationId;}
    public void setLastFileOperationId(Long value) {lastFileOperationId=value;}
    private String workspacePath;
    private Long workspaceOperationId;
    public String getWorkspacePath() {return workspacePath;}
    public void setWorkspacePath(String value) {workspacePath=value;}
    public Long getWorkspaceOperationId() {return workspaceOperationId;}
    public void setWorkspaceOperationId(Long value) {workspaceOperationId=value;}
    private Long id;
    public Long getId() { return id; }
    public void setId(Long value) { id=value; }
    private Long userId;
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId=value; }
    private Long projectId;
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long value) { projectId=value; }
    private Long conversationId;
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long value) { conversationId=value; }
    private String fileName;
    public String getFileName() { return fileName; }
    public void setFileName(String value) { fileName=value; }
    private String storageKey;
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String value) { storageKey=value; }
    private String mediaType;
    public String getMediaType() { return mediaType; }
    public void setMediaType(String value) { mediaType=value; }
    private long sizeBytes;
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long value) { sizeBytes=value; }
    private String sha256;
    public String getSha256() { return sha256; }
    public void setSha256(String value) { sha256=value; }
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { status=value; }
    private java.time.LocalDateTime createdAt;
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { createdAt=value; }
}
