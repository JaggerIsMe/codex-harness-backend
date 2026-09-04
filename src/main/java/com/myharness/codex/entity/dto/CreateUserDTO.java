package com.myharness.codex.entity.dto;
import jakarta.validation.constraints.*;
import java.util.List;
public record CreateUserDTO(
    @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._-]{2,63}") String username,
    @NotBlank @Size(max=128) String displayName,
    @NotBlank @Size(min=12,max=64) String password,
    @NotBlank @Pattern(regexp="SYS_ADMIN|USER") String role,
    @NotNull @Size(max=200) List<@NotNull @Positive Long> deviceIds) {}

