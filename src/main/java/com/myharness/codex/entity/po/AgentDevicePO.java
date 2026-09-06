package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

public class AgentDevicePO {
    private Boolean managedModels;
    public Boolean getManagedModels(){return managedModels;}
    public void setManagedModels(Boolean value){managedModels=value;}
    private Boolean projectExperts;
    public Boolean getProjectExperts() {return projectExperts;}
    public void setProjectExperts(Boolean value) {projectExperts=value;}
    private Boolean expertMcp;
    public Boolean getExpertMcp() {return expertMcp;}
    public void setExpertMcp(Boolean value) {expertMcp=value;}
    private Boolean conversationAttachments;
    public Boolean getConversationAttachments(){return conversationAttachments;}
    public void setConversationAttachments(Boolean value){conversationAttachments=value;}
    private Long id;
    private Long enrollmentId;
    private String deviceCode;
    private String deviceName;
    private String tokenHash;
    private String status;
    private String agentVersion;
    private String osName;
    private String osVersion;
    private String isolationMode;
    private LocalDateTime lastHeartbeatAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEnrollmentId() { return enrollmentId; }
    public void setEnrollmentId(Long enrollmentId) { this.enrollmentId = enrollmentId; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }
    public String getOsName() { return osName; }
    public void setOsName(String osName) { this.osName = osName; }
    public String getOsVersion() { return osVersion; }
    public void setOsVersion(String osVersion) { this.osVersion = osVersion; }
    public String getIsolationMode() { return isolationMode; }
    public void setIsolationMode(String value) { isolationMode=value; }
    public LocalDateTime getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(LocalDateTime value) { this.lastHeartbeatAt = value; }
}
