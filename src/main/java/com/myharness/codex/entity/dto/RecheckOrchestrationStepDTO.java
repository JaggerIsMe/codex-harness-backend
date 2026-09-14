package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RecheckOrchestrationStepDTO(@NotNull @Positive Long expectedTurnId) {}
