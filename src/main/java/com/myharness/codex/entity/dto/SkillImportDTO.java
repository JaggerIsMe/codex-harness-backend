package com.myharness.codex.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record SkillImportDTO(@NotNull Mode mode, @NotEmpty @Size(max=50) List<@NotNull @Valid Item> items) {
    public enum Mode { CREATE, UPDATE }
    public record Item(@NotBlank @Size(max=64) String itemId, @NotBlank @Size(max=64) String uploadId,
                       @Positive Long skillId, @Size(max=128) String skillName, @Size(max=1000) String description,
                       @NotBlank @Size(max=64) String version,
                       @Size(max=200) String tag) {}
    public record Commit(@NotBlank @Size(max=64) String previewId,
                         @NotBlank @Pattern(regexp="[A-Za-z0-9-]{1,64}") String submissionId) {}
}
