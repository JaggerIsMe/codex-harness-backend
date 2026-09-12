package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;

public class ApprovalDecisionDTO {
    private com.fasterxml.jackson.databind.JsonNode answers;
    public com.fasterxml.jackson.databind.JsonNode getAnswers() { return answers; }
    public void setAnswers(com.fasterxml.jackson.databind.JsonNode value) { answers=value; }
    @NotBlank private String decision;
    public String getDecision() { return decision; }
    public void setDecision(String value) { decision=value; }
}
