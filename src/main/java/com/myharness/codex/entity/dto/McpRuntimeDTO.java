package com.myharness.codex.entity.dto;

public class McpRuntimeDTO extends McpRuntimeSpecDTO {
    private Long configurationId;
    private Long configurationVersionId;
    private Long versionNo;
    private String serverCode;
    private String name;
    private String configDigest;
    public Long getConfigurationId() { return configurationId; }
    public void setConfigurationId(Long value) { configurationId=value; }
    public Long getConfigurationVersionId() { return configurationVersionId; }
    public void setConfigurationVersionId(Long value) { configurationVersionId=value; }
    public Long getVersionNo() { return versionNo; }
    public void setVersionNo(Long value) { versionNo=value; }
    public String getServerCode() { return serverCode; }
    public void setServerCode(String value) { serverCode=value; }
    public String getName() { return name; }
    public void setName(String value) { name=value; }
    public String getConfigDigest() { return configDigest; }
    public void setConfigDigest(String value) { configDigest=value; }
}
