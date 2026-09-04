package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

public class SkillDeploymentPO {
    private Long id; private Long deviceId; private Long skillVersionId; private Long skillId;
    private Long projectId; private String scopeType; private String scopeKey; private String projectName; private String workspaceName;
    private String skillName; private String version; private String sha256; private String installStatus; private String deviceCode; private String deviceName;
    private String errorMessage; private LocalDateTime requestedAt; private LocalDateTime installedAt; private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long value){id=value;} public Long getDeviceId(){return deviceId;}
    public void setDeviceId(Long value){deviceId=value;} public Long getSkillVersionId(){return skillVersionId;}
    public void setSkillVersionId(Long value){skillVersionId=value;} public Long getSkillId(){return skillId;}
    public void setSkillId(Long value){skillId=value;} public String getVersion(){return version;} public void setVersion(String value){version=value;}
    public String getSha256(){return sha256;} public void setSha256(String value){sha256=value;} public String getInstallStatus(){return installStatus;}
    public void setInstallStatus(String value){installStatus=value;} public String getDeviceCode(){return deviceCode;} public void setDeviceCode(String value){deviceCode=value;}
    public String getSkillName(){return skillName;} public void setSkillName(String value){skillName=value;}
    public String getDeviceName(){return deviceName;} public void setDeviceName(String value){deviceName=value;}
    public String getErrorMessage(){return errorMessage;} public void setErrorMessage(String value){errorMessage=value;}
    public LocalDateTime getRequestedAt(){return requestedAt;} public void setRequestedAt(LocalDateTime value){requestedAt=value;}
    public LocalDateTime getInstalledAt(){return installedAt;} public void setInstalledAt(LocalDateTime value){installedAt=value;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime value){updatedAt=value;}
    public Long getProjectId(){return projectId;} public void setProjectId(Long value){projectId=value;}
    public String getScopeType(){return scopeType;} public void setScopeType(String value){scopeType=value;}
    public String getScopeKey(){return scopeKey;} public void setScopeKey(String value){scopeKey=value;}
    public String getProjectName(){return projectName;} public void setProjectName(String value){projectName=value;}
    public String getWorkspaceName(){return workspaceName;} public void setWorkspaceName(String value){workspaceName=value;}
}
