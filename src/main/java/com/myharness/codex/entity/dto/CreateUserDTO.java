package com.myharness.codex.entity.dto;
import jakarta.validation.constraints.*;
public record CreateUserDTO(
    @NotBlank @Size(max=254) String email,
    @NotBlank @Pattern(regexp="SYS_ADMIN|USER") String role) {}

