package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

public class ProjectPO {
    private Long id;
    private Long userId;
    private Long deviceId;
    private Long workspaceId;
    private String projectName;
    private String status;
    private String isolationMode;
    private String deviceCode;
    private String deviceName;
    private String deviceStatus;
    private String workspaceName;
    private String rootPath;
    private String workspaceStatus;
    private Integer conversationCount;
    private LocalDateTime createdAt;

    public Long getId(){return id;} public void setId(Long value){id=value;}
    public Long getUserId(){return userId;} public void setUserId(Long value){userId=value;}
    public Long getDeviceId(){return deviceId;} public void setDeviceId(Long value){deviceId=value;}
    public Long getWorkspaceId(){return workspaceId;} public void setWorkspaceId(Long value){workspaceId=value;}
    public String getProjectName(){return projectName;} public void setProjectName(String value){projectName=value;}
    public String getStatus(){return status;} public void setStatus(String value){status=value;}
    public String getIsolationMode(){return isolationMode;} public void setIsolationMode(String value){isolationMode=value;}
    public String getDeviceCode(){return deviceCode;} public void setDeviceCode(String value){deviceCode=value;}
    public String getDeviceName(){return deviceName;} public void setDeviceName(String value){deviceName=value;}
    public String getDeviceStatus(){return deviceStatus;} public void setDeviceStatus(String value){deviceStatus=value;}
    public String getWorkspaceName(){return workspaceName;} public void setWorkspaceName(String value){workspaceName=value;}
    public String getRootPath(){return rootPath;} public void setRootPath(String value){rootPath=value;}
    public String getWorkspaceStatus(){return workspaceStatus;} public void setWorkspaceStatus(String value){workspaceStatus=value;}
    public Integer getConversationCount(){return conversationCount;} public void setConversationCount(Integer value){conversationCount=value;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime value){createdAt=value;}
}
