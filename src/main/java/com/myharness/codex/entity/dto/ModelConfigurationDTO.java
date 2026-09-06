package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public class ModelConfigurationDTO extends ModelRuntimeSpecDTO {
    @NotBlank @Size(max=64) private String configurationCode;
    @NotBlank @Size(max=128) private String name;
    @Size(max=2000) private String description;
    @NotBlank @Size(max=4096) private String apiKey;
    @PositiveOrZero private Long revision;
    public String getConfigurationCode(){return configurationCode;}
    public void setConfigurationCode(String value){configurationCode=value;}
    public String getName(){return name;}
    public void setName(String value){name=value;}
    public String getDescription(){return description;}
    public void setDescription(String value){description=value;}
    public String getApiKey(){return apiKey;}
    public void setApiKey(String value){apiKey=value;}
    public Long getRevision(){return revision;}
    public void setRevision(Long value){revision=value;}
    @Override public void setInputModalities(List<String> value){super.setInputModalities(value);}
}
