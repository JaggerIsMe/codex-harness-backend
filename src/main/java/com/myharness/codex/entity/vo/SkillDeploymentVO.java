package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.po.SkillDeploymentPO;
import java.time.LocalDateTime;

public class SkillDeploymentVO {
    private final Long id; private final Long deviceId; private final Long skillVersionId; private final Long skillId;
    private final String skillName; private final String version; private final String deviceName; private final String installStatus;
    private final String errorMessage; private final LocalDateTime requestedAt; private final LocalDateTime installedAt; private final LocalDateTime updatedAt;
    private final String scopeType; private final Long projectId; private final String projectName;
    public SkillDeploymentVO(SkillDeploymentPO value){id=value.getId();deviceId=value.getDeviceId();skillVersionId=value.getSkillVersionId();skillId=value.getSkillId();
        skillName=value.getSkillName();version=value.getVersion();deviceName=value.getDeviceName();installStatus=value.getInstallStatus();errorMessage=value.getErrorMessage();
        requestedAt=value.getRequestedAt();installedAt=value.getInstalledAt();updatedAt=value.getUpdatedAt();scopeType=value.getScopeType();
        projectId=value.getProjectId();projectName=value.getProjectName();}
    public Long getId(){return id;} public Long getDeviceId(){return deviceId;} public Long getSkillVersionId(){return skillVersionId;}
    public Long getSkillId(){return skillId;} public String getSkillName(){return skillName;} public String getVersion(){return version;}
    public String getDeviceName(){return deviceName;} public String getInstallStatus(){return installStatus;} public String getErrorMessage(){return errorMessage;}
    public LocalDateTime getRequestedAt(){return requestedAt;} public LocalDateTime getInstalledAt(){return installedAt;} public LocalDateTime getUpdatedAt(){return updatedAt;}
    public String getScopeType(){return scopeType;} public Long getProjectId(){return projectId;} public String getProjectName(){return projectName;}
}
