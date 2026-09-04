package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.po.AgentWorkspacePO;
import java.time.LocalDateTime;

public class AgentWorkspaceVO {
    private final Long id; private final Long deviceId; private final String workspaceName; private final String rootPath;
    private final String status; private final LocalDateTime lastReportedAt;
    private final String parentName; private final String projectType; private final String failureCode; private final String failureMessage;
    public AgentWorkspaceVO(AgentWorkspacePO value) {
        id=value.getId(); deviceId=value.getDeviceId(); workspaceName=value.getWorkspaceName(); rootPath=value.getRootPath();
        status=value.getStatus(); lastReportedAt=value.getLastReportedAt();
        parentName=value.getParentName(); projectType=value.getProjectType(); failureCode=value.getFailureCode(); failureMessage=value.getFailureMessage();
    }
    public Long getId(){return id;} public Long getDeviceId(){return deviceId;} public String getWorkspaceName(){return workspaceName;}
    public String getRootPath(){return rootPath;} public String getStatus(){return status;} public LocalDateTime getLastReportedAt(){return lastReportedAt;}
    public String getParentName(){return parentName;} public String getProjectType(){return projectType;}
    public String getFailureCode(){return failureCode;} public String getFailureMessage(){return failureMessage;}
}
