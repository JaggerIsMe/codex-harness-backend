package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.*;

public record ContinueOrchestrationStepDTO(@NotNull @Positive Long expectedTurnId,
    @NotBlank @Size(max=64) @Pattern(regexp="[a-zA-Z0-9_-]+") String requestKey,
    @NotBlank @Size(max=50000) String message) {}
