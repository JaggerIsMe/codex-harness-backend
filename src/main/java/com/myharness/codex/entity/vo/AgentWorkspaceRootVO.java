package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.po.AgentWorkspaceRootPO;

public class AgentWorkspaceRootVO {
    private final Long id;
    private final Long deviceId;
    private final String rootName;
    private final String status;

    public AgentWorkspaceRootVO(AgentWorkspaceRootPO value) {
        this.id = value.getId();
        this.deviceId = value.getDeviceId();
        this.rootName = value.getRootName();
        this.status = value.getStatus();
    }

    public Long getId() { return id; }
    public Long getDeviceId() { return deviceId; }
    public String getRootName() { return rootName; }
    public String getStatus() { return status; }
}
