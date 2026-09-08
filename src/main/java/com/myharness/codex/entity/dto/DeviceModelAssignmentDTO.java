package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.*;

public class DeviceModelAssignmentDTO {
    @NotBlank @Pattern(regexp="LOCAL_CODEX|MANAGED_PROVIDER") private String runtimeMode;
    @Positive private Long modelConfigurationVersionId;
    @NotNull @PositiveOrZero private Long revision;
    public String getRuntimeMode(){return runtimeMode;}
    public void setRuntimeMode(String value){runtimeMode=value;}
    public Long getModelConfigurationVersionId(){return modelConfigurationVersionId;}
    public void setModelConfigurationVersionId(Long value){modelConfigurationVersionId=value;}
    public Long getRevision(){return revision;}
    public void setRevision(Long value){revision=value;}
}
