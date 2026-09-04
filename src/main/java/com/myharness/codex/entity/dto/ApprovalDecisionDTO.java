package com.myharness.codex.entity.dto;

import javax.validation.constraints.NotBlank;

public class ApprovalDecisionDTO {
    @NotBlank private String decision;
    public String getDecision() { return decision; }
    public void setDecision(String value) { decision=value; }
}
