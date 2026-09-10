package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ActivationTokenDTO(@NotBlank @Size(max = 128) String token) {
    @Override public String toString() { return "ActivationTokenDTO[redacted]"; }
}
