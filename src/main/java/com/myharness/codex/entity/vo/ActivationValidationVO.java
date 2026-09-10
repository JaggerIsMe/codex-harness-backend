package com.myharness.codex.entity.vo;

import java.time.LocalDateTime;

public record ActivationValidationVO(String maskedEmail, String displayName, LocalDateTime expiresAt) {}
