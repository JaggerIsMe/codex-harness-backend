package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

public class ModelConfigurationVersionPO {
    private Long id; private Long modelConfigurationId; private Long versionNo; private String configurationCode;
    private String name; private String description; private String runtimeSpec; private String encryptedApiKey;
    private String configDigest; private String status; private String configurationStatus; private LocalDateTime createdAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getModelConfigurationId(){return modelConfigurationId;} public void setModelConfigurationId(Long v){modelConfigurationId=v;}
    public Long getVersionNo(){return versionNo;} public void setVersionNo(Long v){versionNo=v;}
    public String getConfigurationCode(){return configurationCode;} public void setConfigurationCode(String v){configurationCode=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public String getRuntimeSpec(){return runtimeSpec;} public void setRuntimeSpec(String v){runtimeSpec=v;}
    public String getEncryptedApiKey(){return encryptedApiKey;} public void setEncryptedApiKey(String v){encryptedApiKey=v;}
    public String getConfigDigest(){return configDigest;} public void setConfigDigest(String v){configDigest=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getConfigurationStatus(){return configurationStatus;} public void setConfigurationStatus(String v){configurationStatus=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
