package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.Size;

public class CreateConversationDTO {
    @Size(max=255) private String title;
    @Size(max=128) private String model;
    public String getTitle() { return title; }
    public void setTitle(String value) { title=value; }
    public String getModel() { return model; }
    public void setModel(String value) { model=value; }
}
