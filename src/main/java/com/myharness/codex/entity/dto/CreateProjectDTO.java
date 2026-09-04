package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class CreateProjectDTO {
    @NotBlank @Size(max=128) private String projectName;
    @NotNull private Long deviceId;
    @NotBlank @jakarta.validation.constraints.Pattern(regexp="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private String requestKey;

    public String getProjectName() { return projectName; }
    public void setProjectName(String value) { projectName=value; }
    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long value) { deviceId=value; }
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String value) { requestKey=value; }
}
