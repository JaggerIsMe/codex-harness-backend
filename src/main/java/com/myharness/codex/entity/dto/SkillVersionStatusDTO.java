package com.myharness.codex.entity.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

public class SkillVersionStatusDTO {
    @NotBlank(message = "版本状态不能为空")
    @Pattern(regexp = "ACTIVE|DISABLED", message = "版本状态不正确")
    private String status;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
