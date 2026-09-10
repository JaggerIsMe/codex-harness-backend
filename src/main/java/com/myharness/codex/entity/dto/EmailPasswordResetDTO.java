package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EmailPasswordResetDTO(@NotBlank @Size(max = 256) String email,
                                    @NotBlank @Pattern(regexp = "[0-9]{6}") String code,
                                    @NotBlank @Size(max = 64) String newPassword) {
    @Override public String toString() { return "EmailPasswordResetDTO[redacted]"; }
}
