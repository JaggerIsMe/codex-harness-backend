package com.myharness.codex.entity.dto;

public class McpStatusDTO {
    private @jakarta.validation.constraints.NotBlank String status;
    private @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long revision;
    public String getStatus() { return status; }
    public void setStatus(String value) { status=value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision=value; }
}
