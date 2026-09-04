package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

public class AgentWorkspaceRootPO {
    private Long id;
    private Long deviceId;
    private String rootName;
    private String status;
    private LocalDateTime lastReportedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long deviceId) { this.deviceId = deviceId; }
    public String getRootName() { return rootName; }
    public void setRootName(String rootName) { this.rootName = rootName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getLastReportedAt() { return lastReportedAt; }
    public void setLastReportedAt(LocalDateTime lastReportedAt) { this.lastReportedAt = lastReportedAt; }
}
