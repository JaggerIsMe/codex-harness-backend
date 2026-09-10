package com.myharness.codex.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** One bounded request vocabulary; each operation validates its own required fields. */
public record WorkspaceFileActionRequestDTO(
        @NotBlank @Size(max=36) String requestKey,
        @Size(max=2048) String path, @Size(max=255) String name,
        @Size(max=2048) String targetDirectory, @Size(max=1024) String expectedRevision,
        @Size(max=128) String planId, @Size(max=64) String planDigest,
        @Size(max=100) List<@Valid Item> items) {
    public record Item(@NotBlank @Size(max=2048) String path,
                       @NotBlank @Size(max=1024) String expectedRevision) {}
}
