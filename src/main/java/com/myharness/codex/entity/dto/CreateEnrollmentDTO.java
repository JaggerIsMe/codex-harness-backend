package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class CreateEnrollmentDTO {
    @Min(1) @Max(1440)
    private Long expiresInMinutes;

    public Long getExpiresInMinutes() { return expiresInMinutes; }
    public void setExpiresInMinutes(Long expiresInMinutes) { this.expiresInMinutes = expiresInMinutes; }
}
