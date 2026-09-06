package com.myharness.codex.entity.dto;

import java.util.List;

public class ModelRuntimeDTO extends ModelRuntimeSpecDTO {
    private int schemaVersion=1;
    private Long configurationId;
    private Long configurationVersionId;
    private Long versionNo;
    private String configurationCode;
    private String name;
    private String configDigest;
    private String runtimeKey;
    private String apiKey;
    public int getSchemaVersion(){return schemaVersion;}
    public void setSchemaVersion(int value){schemaVersion=value;}
    public Long getConfigurationId(){return configurationId;}
    public void setConfigurationId(Long value){configurationId=value;}
    public Long getConfigurationVersionId(){return configurationVersionId;}
    public void setConfigurationVersionId(Long value){configurationVersionId=value;}
    public Long getVersionNo(){return versionNo;}
    public void setVersionNo(Long value){versionNo=value;}
    public String getConfigurationCode(){return configurationCode;}
    public void setConfigurationCode(String value){configurationCode=value;}
    public String getName(){return name;}
    public void setName(String value){name=value;}
    public String getConfigDigest(){return configDigest;}
    public void setConfigDigest(String value){configDigest=value;}
    public String getRuntimeKey(){return runtimeKey;}
    public void setRuntimeKey(String value){runtimeKey=value;}
    public String getApiKey(){return apiKey;}
    public void setApiKey(String value){apiKey=value;}
    public boolean supports(String modality){return getInputModalities().contains(modality);}
    @Override public void setInputModalities(List<String> value){super.setInputModalities(value);}
}
