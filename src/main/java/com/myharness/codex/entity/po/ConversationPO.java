package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

public class ConversationPO {
    private String expertRuntimeKey;
    public String getExpertRuntimeKey() { return expertRuntimeKey; }
    public void setExpertRuntimeKey(String value) { expertRuntimeKey=value; }
    private Long selectedExpertId;
    public Long getSelectedExpertId() {return selectedExpertId;}
    public void setSelectedExpertId(Long value) {selectedExpertId=value;}
    private Long selectedExpertVersionId;
    public Long getSelectedExpertVersionId() {return selectedExpertVersionId;}
    public void setSelectedExpertVersionId(Long value) {selectedExpertVersionId=value;}
    private Long expertSelectionRevision = 0L;
    public Long getExpertSelectionRevision() {return expertSelectionRevision;}
    public void setExpertSelectionRevision(Long value) {expertSelectionRevision=value;}
    private Long id;
    private Long userId;
    private Long deviceId;
    private Long workspaceId;
    private Long projectId;
    private String title;
    private String codexThreadId;
    private String status;
    private LocalDateTime lastActivityAt;
    private String deviceCode;
    private String workspaceName;
    private String projectName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { this.userId = value; }
    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long value) { this.deviceId = value; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long value) { this.workspaceId = value; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long value) { projectId=value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { this.title = value; }
    public String getCodexThreadId() { return codexThreadId; }
    public void setCodexThreadId(String value) { this.codexThreadId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public LocalDateTime getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(LocalDateTime value) { this.lastActivityAt = value; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String value) { this.deviceCode = value; }
    public String getWorkspaceName() { return workspaceName; }
    public void setWorkspaceName(String value) { this.workspaceName = value; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String value) { projectName=value; }
}
