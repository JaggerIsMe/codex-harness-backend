package com.myharness.codex.entity.dto;
import jakarta.validation.constraints.*;
public record UpdateUserDTO(@NotBlank @Size(max=128) String displayName,
                            @NotBlank @Pattern(regexp="ENABLED|DISABLED") String status) {}

