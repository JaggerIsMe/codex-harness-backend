package com.myharness.codex.entity.dto;
public record WorkspaceFileRequestDTO(
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max=2048) String path,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=36) String requestKey) {}
