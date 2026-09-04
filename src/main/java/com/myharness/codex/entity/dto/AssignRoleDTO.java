package com.myharness.codex.entity.dto;
import jakarta.validation.constraints.*;
public record AssignRoleDTO(@NotBlank @Pattern(regexp="SYS_ADMIN|USER") String role) {}

