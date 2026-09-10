package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AttachmentDownloadRequestDTO(@NotBlank @Size(max=36) String requestKey) {}
