package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.*;

public class DeviceModelAssignmentDTO {
    @NotNull @Positive private Long modelConfigurationVersionId;
    @NotNull @PositiveOrZero private Long revision;
    public Long getModelConfigurationVersionId(){return modelConfigurationVersionId;}
    public void setModelConfigurationVersionId(Long value){modelConfigurationVersionId=value;}
    public Long getRevision(){return revision;}
    public void setRevision(Long value){revision=value;}
}
