package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class StartTurnDTO {
    @NotBlank @Size(max=100000) private String message;
    @Size(max=128) private String model;
    @Size(max=32) private String reasoningEffort;
    public String getMessage() { return message; }
    public void setMessage(String value) { message=value; }
    public String getModel() { return model; }
    public void setModel(String value) { model=value; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String value) { reasoningEffort=value; }
}
