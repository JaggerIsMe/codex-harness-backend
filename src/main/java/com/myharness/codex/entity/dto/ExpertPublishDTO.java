package com.myharness.codex.entity.dto;

public class ExpertPublishDTO {
    private @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long revision;
    private @jakarta.validation.constraints.NotNull Boolean compatibleUpgrade;
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
    public Boolean getCompatibleUpgrade() { return compatibleUpgrade; }
    public void setCompatibleUpgrade(Boolean value) { compatibleUpgrade = value; }
}
