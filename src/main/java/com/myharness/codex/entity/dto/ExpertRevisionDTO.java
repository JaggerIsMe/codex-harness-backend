package com.myharness.codex.entity.dto;

public class ExpertRevisionDTO {
    private @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long revision;
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
}

