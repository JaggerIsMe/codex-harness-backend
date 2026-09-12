package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class UpdateSkillDTO {
    @NotBlank(message = "Skill 名称不能为空")
    @Size(max = 128, message = "Skill 名称不能超过 128 个字符")
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*", message = "Skill 名称只能包含字母、数字、点、下划线和连字符")
    private String skillName;
    @Size(max = 1000, message = "Skill 描述不能超过 1000 个字符")
    private String description;
    @Size(max = 200, message = "Skill 标签不能超过 200 个字符")
    private String tag;
    @Pattern(regexp = "ENABLED|DISABLED", message = "Skill 状态不正确")
    private String status;

    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
