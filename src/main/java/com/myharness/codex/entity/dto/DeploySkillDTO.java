package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public class DeploySkillDTO {
    @NotNull(message = "Skill 版本不能为空")
    private Long versionId;
    @NotNull(message = "下发作用域不能为空")
    @Pattern(regexp = "GLOBAL|PROJECT", message = "下发作用域必须是 GLOBAL 或 PROJECT")
    private String scopeType;
    @NotNull(message = "下发目标不能为空")
    private Long targetId;

    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
}
