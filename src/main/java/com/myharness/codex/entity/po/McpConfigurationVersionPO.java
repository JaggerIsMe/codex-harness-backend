package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

public class McpConfigurationVersionPO {
    private Long id;
    private Long mcpConfigurationId;
    private Long versionNo;
    private String serverCode;
    private String name;
    private String description;
    private String runtimeSpec;
    private String configDigest;
    private String versionStatus;
    private String configurationStatus;
    private LocalDateTime createdAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getMcpConfigurationId(){return mcpConfigurationId;} public void setMcpConfigurationId(Long v){mcpConfigurationId=v;}
    public Long getVersionNo(){return versionNo;} public void setVersionNo(Long v){versionNo=v;}
    public String getServerCode(){return serverCode;} public void setServerCode(String v){serverCode=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public String getRuntimeSpec(){return runtimeSpec;} public void setRuntimeSpec(String v){runtimeSpec=v;}
    public String getConfigDigest(){return configDigest;} public void setConfigDigest(String v){configDigest=v;}
    public String getVersionStatus(){return versionStatus;} public void setVersionStatus(String v){versionStatus=v;}
    public String getConfigurationStatus(){return configurationStatus;} public void setConfigurationStatus(String v){configurationStatus=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
