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
    private final Long latestTurnId;
    private final String latestTurnStatus;
    private final String latestTurnFailureMessage;
    private final boolean latestTurnHasIncompleteMessage;

    public ConversationVO(ConversationPO value) {
        id=value.getId(); deviceId=value.getDeviceId(); workspaceId=value.getWorkspaceId(); projectId=value.getProjectId(); projectName=value.getProjectName();
        title=value.getTitle(); status=value.getStatus(); codexThreadId=value.getCodexThreadId();
        latestTurnId=value.getLatestTurnId(); latestTurnStatus=value.getLatestTurnStatus();
        latestTurnFailureMessage=value.getLatestTurnFailureMessage();
        latestTurnHasIncompleteMessage=value.getLatestTurnHasIncompleteMessage();
    }
    public Long getId() { return id; }
    public Long getDeviceId() { return deviceId; }
    public Long getWorkspaceId() { return workspaceId; }
    public Long getProjectId() { return projectId; }
    public String getProjectName() { return projectName; }
    public String getTitle() { return title; }
    public String getStatus() { return status; }
    public String getCodexThreadId() { return codexThreadId; }
    public Long getLatestTurnId() { return latestTurnId; }
    public String getLatestTurnStatus() { return latestTurnStatus; }
    public String getLatestTurnFailureMessage() { return latestTurnFailureMessage; }
    public boolean getLatestTurnHasIncompleteMessage() { return latestTurnHasIncompleteMessage; }
}
