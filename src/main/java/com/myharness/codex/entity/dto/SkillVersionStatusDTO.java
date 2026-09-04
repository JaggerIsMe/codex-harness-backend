package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class SkillVersionStatusDTO {
    @NotBlank(message = "版本状态不能为空")
    @Pattern(regexp = "ACTIVE|DISABLED", message = "版本状态不正确")
    private String status;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
