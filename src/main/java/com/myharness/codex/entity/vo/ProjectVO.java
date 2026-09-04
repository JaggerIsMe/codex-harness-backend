package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.po.ProjectPO;
import java.time.LocalDateTime;

public class ProjectVO {
    private final Long id; private final String projectName; private final String status; private final String isolationMode;
    private final Long deviceId; private final String deviceCode; private final String deviceName; private final String deviceStatus;
    private final Long workspaceId; private final String workspaceName; private final String rootPath; private final String workspaceStatus;
    private final Integer conversationCount; private final LocalDateTime createdAt;
    public ProjectVO(ProjectPO value) {
        id=value.getId(); projectName=value.getProjectName(); status=value.getStatus(); isolationMode=value.getIsolationMode();
        deviceId=value.getDeviceId(); deviceCode=value.getDeviceCode(); deviceName=value.getDeviceName(); deviceStatus=value.getDeviceStatus();
        workspaceId=value.getWorkspaceId(); workspaceName=value.getWorkspaceName(); rootPath=value.getRootPath(); workspaceStatus=value.getWorkspaceStatus();
        conversationCount=value.getConversationCount(); createdAt=value.getCreatedAt();
    }
    public Long getId(){return id;} public String getProjectName(){return projectName;} public String getStatus(){return status;}
    public String getIsolationMode(){return isolationMode;} public Long getDeviceId(){return deviceId;} public String getDeviceCode(){return deviceCode;}
    public String getDeviceName(){return deviceName;} public String getDeviceStatus(){return deviceStatus;} public Long getWorkspaceId(){return workspaceId;}
    public String getWorkspaceName(){return workspaceName;} public String getRootPath(){return rootPath;} public String getWorkspaceStatus(){return workspaceStatus;}
    public Integer getConversationCount(){return conversationCount;} public LocalDateTime getCreatedAt(){return createdAt;}
}
