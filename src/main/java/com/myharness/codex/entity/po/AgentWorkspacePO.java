package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

public class AgentWorkspacePO {
    private Long id;
    private Long deviceId;
    private String workspaceName;
    private String rootPath;
    private String rootPathHash;
    private String status;
    private String parentName;
    private String projectType;
    private String failureCode;
    private String failureMessage;
    private Long createdBy;
    private LocalDateTime lastReportedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long deviceId) { this.deviceId = deviceId; }
    public String getWorkspaceName() { return workspaceName; }
    public void setWorkspaceName(String value) { this.workspaceName = value; }
    public String getRootPath() { return rootPath; }
    public void setRootPath(String value) { this.rootPath = value; }
    public String getRootPathHash() { return rootPathHash; }
    public void setRootPathHash(String value) { this.rootPathHash = value; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getParentName() { return parentName; }
    public void setParentName(String parentName) { this.parentName = parentName; }
    public String getProjectType() { return projectType; }
    public void setProjectType(String projectType) { this.projectType = projectType; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getLastReportedAt() { return lastReportedAt; }
    public void setLastReportedAt(LocalDateTime value) { this.lastReportedAt = value; }
}
