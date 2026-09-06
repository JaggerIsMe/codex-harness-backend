package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class CreateConversationDTO {
    @NotNull @Positive private Long expertId;
    @Size(max=255) private String title;
    @Size(max=128) private String model;
    public String getTitle() { return title; }
    public void setTitle(String value) { title=value; }
    public String getModel() { return model; }
    public void setModel(String value) { model=value; }
    public Long getExpertId() { return expertId; }
    public void setExpertId(Long value) { expertId=value; }
}
