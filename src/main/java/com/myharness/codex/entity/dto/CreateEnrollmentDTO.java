package com.myharness.codex.entity.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

public class CreateEnrollmentDTO {
    @Min(1) @Max(1440)
    private Long expiresInMinutes;

    public Long getExpiresInMinutes() { return expiresInMinutes; }
    public void setExpiresInMinutes(Long expiresInMinutes) { this.expiresInMinutes = expiresInMinutes; }
}
