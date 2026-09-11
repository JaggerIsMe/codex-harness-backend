package com.myharness.codex.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record SkillExpertBatchAssignmentDTO(
        @NotEmpty @Size(max=30) List<@NotNull @Valid Target> targets,
        @NotEmpty @Size(max=50) List<@NotNull Long> expertIds) {
    public record Target(@NotNull Long skillId, @NotNull Long versionId) {}
    public record Query(@NotEmpty @Size(max=30) List<@NotNull @Valid Target> targets,
                        @Size(max=128) String keyword, @Min(1) @Max(100000) int page,
                        @Min(1) @Max(50) int size) {}
}
