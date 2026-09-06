package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.*;

public class ModelStatusDTO {
    @NotBlank private String status;
    @NotNull @PositiveOrZero private Long revision;
    public String getStatus(){return status;}
    public void setStatus(String value){status=value;}
    public Long getRevision(){return revision;}
    public void setRevision(Long value){revision=value;}
}
