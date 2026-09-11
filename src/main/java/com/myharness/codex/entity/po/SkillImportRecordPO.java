package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

public record SkillImportRecordPO(String id, String kind, Long ownerId, String payload, LocalDateTime expiresAt) {}
