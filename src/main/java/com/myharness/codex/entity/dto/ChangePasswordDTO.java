package com.myharness.codex.entity.dto;
import jakarta.validation.constraints.*;
public record ChangePasswordDTO(@NotBlank @Size(max=128) String currentPassword,
                                @NotBlank @Size(min=12,max=64) String newPassword) {
    @Override public String toString() { return "ChangePasswordDTO[credentials=REDACTED]"; }
}

