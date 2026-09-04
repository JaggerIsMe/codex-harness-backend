package com.myharness.codex.entity.dto;
import jakarta.validation.constraints.*;
import java.util.List;
public record AssignDevicesDTO(@NotNull @Size(max=200) List<@NotNull @Positive Long> deviceIds) {}

