package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.po.ConversationPO;

public class ConversationVO {
    private final Long id;
    private final Long deviceId;
    private final Long workspaceId;
    private final Long projectId;
    private final String projectName;
    private final String title;
    private final String status;
    private final String codexThreadId;

    public ConversationVO(ConversationPO value) {
        id=value.getId(); deviceId=value.getDeviceId(); workspaceId=value.getWorkspaceId(); projectId=value.getProjectId(); projectName=value.getProjectName();
        title=value.getTitle(); status=value.getStatus(); codexThreadId=value.getCodexThreadId();
    }
    public Long getId() { return id; }
    public Long getDeviceId() { return deviceId; }
    public Long getWorkspaceId() { return workspaceId; }
    public Long getProjectId() { return projectId; }
    public String getProjectName() { return projectName; }
    public String getTitle() { return title; }
    public String getStatus() { return status; }
    public String getCodexThreadId() { return codexThreadId; }
}
