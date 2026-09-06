package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AssignExpertsDTO(
        @NotNull @Size(max=200) List<@NotNull @Positive Long> expertIds) {}
