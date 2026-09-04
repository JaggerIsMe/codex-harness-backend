package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class CreateProjectDTO {
    @NotBlank @Size(max=128) private String projectName;
    @NotNull private Long deviceId;
    @NotNull private Long workspaceId;

    public String getProjectName() { return projectName; }
    public void setProjectName(String value) { projectName=value; }
    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long value) { deviceId=value; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long value) { workspaceId=value; }
}
