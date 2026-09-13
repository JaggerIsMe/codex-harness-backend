package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.*;

public record CreateOrchestrationDTO(
    @NotBlank @Size(max=120) String title,
    @NotBlank @Size(max=12000) String goal,
    @NotBlank @Pattern(regexp="[a-zA-Z0-9-]{1,64}") String requestKey,
    @NotNull WorkflowDTO workflow
) {}
