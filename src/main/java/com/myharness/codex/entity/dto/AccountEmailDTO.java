package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountEmailDTO(@NotBlank @Size(max = 256) String email) {
    @Override public String toString() { return "AccountEmailDTO[redacted]"; }
}
