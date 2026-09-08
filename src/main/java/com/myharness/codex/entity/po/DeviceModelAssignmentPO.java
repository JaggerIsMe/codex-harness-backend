package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

public class DeviceModelAssignmentPO {
    private Long deviceId; private String runtimeMode; private Long modelConfigurationVersionId; private Long revision; private Long assignedBy;
    private Long configurationId; private Long versionNo; private String configurationCode; private String name;
    private String modelId; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    public Long getDeviceId(){return deviceId;} public void setDeviceId(Long v){deviceId=v;}
    public String getRuntimeMode(){return runtimeMode;} public void setRuntimeMode(String v){runtimeMode=v;}
    public Long getModelConfigurationVersionId(){return modelConfigurationVersionId;} public void setModelConfigurationVersionId(Long v){modelConfigurationVersionId=v;}
    public Long getRevision(){return revision;} public void setRevision(Long v){revision=v;}
    public Long getAssignedBy(){return assignedBy;} public void setAssignedBy(Long v){assignedBy=v;}
    public Long getConfigurationId(){return configurationId;} public void setConfigurationId(Long v){configurationId=v;}
    public Long getVersionNo(){return versionNo;} public void setVersionNo(Long v){versionNo=v;}
    public String getConfigurationCode(){return configurationCode;} public void setConfigurationCode(String v){configurationCode=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getModelId(){return modelId;} public void setModelId(String v){modelId=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
