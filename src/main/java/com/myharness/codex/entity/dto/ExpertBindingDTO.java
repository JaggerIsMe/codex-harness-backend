package com.myharness.codex.entity.dto;

public class ExpertBindingDTO {
    private @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive Long expertVersionId;
    private @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long projectRevision;
    public Long getExpertVersionId() { return expertVersionId; }
    public void setExpertVersionId(Long value) { expertVersionId = value; }
    public Long getProjectRevision() { return projectRevision; }
    public void setProjectRevision(Long value) { projectRevision = value; }
}

