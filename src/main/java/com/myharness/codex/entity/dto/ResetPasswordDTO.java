package com.myharness.codex.entity.dto;
import jakarta.validation.constraints.*;
public record ResetPasswordDTO(@NotBlank @Size(min=12,max=64) String password) {}

