package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.*;
public record PublishArtifactDTO(
        @NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,64}") String artifactKey,
        @NotBlank @Size(max=255) String fileName,
        @Min(0) long sizeBytes,
        @NotNull @Pattern(regexp="[a-f0-9]{64}") String sha256) {}
