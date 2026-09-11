package com.myharness.codex.entity.dto;
import jakarta.validation.constraints.*;
import java.util.List;
public record SkillExpertAssignmentDTO(@NotNull Long skillId, @NotNull Long versionId,
        @NotEmpty @Size(max=50) List<@NotNull Long> expertIds) {}
