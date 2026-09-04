package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CreateWorkspaceDTO {
    @NotBlank
    @Size(max = 128)
    private String parentName;

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}", message = "工作区名称只能包含字母、数字、点、下划线和短横线")
    private String workspaceName;

    @Pattern(regexp = "empty", message = "当前仅支持 empty 项目类型")
    private String projectType = "empty";

    public String getParentName() { return parentName; }
    public void setParentName(String parentName) { this.parentName = parentName; }
    public String getWorkspaceName() { return workspaceName; }
    public void setWorkspaceName(String workspaceName) { this.workspaceName = workspaceName; }
    public String getProjectType() { return projectType; }
    public void setProjectType(String projectType) { this.projectType = projectType; }
}
