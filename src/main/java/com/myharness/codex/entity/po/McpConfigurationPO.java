package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

public class McpConfigurationPO {
    private Long id;
    private String serverCode;
    private String name;
    private String description;
    private String status;
    private Long currentVersionId;
    private Long currentVersionNo;
    private String runtimeSpec;
    private String configDigest;
    private Long revision;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getServerCode(){return serverCode;} public void setServerCode(String v){serverCode=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public Long getCurrentVersionId(){return currentVersionId;} public void setCurrentVersionId(Long v){currentVersionId=v;}
    public Long getCurrentVersionNo(){return currentVersionNo;} public void setCurrentVersionNo(Long v){currentVersionNo=v;}
    public String getRuntimeSpec(){return runtimeSpec;} public void setRuntimeSpec(String v){runtimeSpec=v;}
    public String getConfigDigest(){return configDigest;} public void setConfigDigest(String v){configDigest=v;}
    public Long getRevision(){return revision;} public void setRevision(Long v){revision=v;}
    public Long getCreatedBy(){return createdBy;} public void setCreatedBy(Long v){createdBy=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
