package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;

public class ApprovalDecisionDTO {
    @NotBlank private String decision;
    public String getDecision() { return decision; }
    public void setDecision(String value) { decision=value; }
}
