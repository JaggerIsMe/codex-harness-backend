package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ActivateAccountDTO(@NotBlank @Size(max = 128) String token,
                                 @NotBlank @Size(max = 64) String newPassword,
                                 @Size(max = 128) String displayName) {
    @Override public String toString() { return "ActivateAccountDTO[redacted]"; }
}
