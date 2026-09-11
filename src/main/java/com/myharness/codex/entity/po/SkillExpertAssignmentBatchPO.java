package com.myharness.codex.entity.po;
import java.time.LocalDateTime;
public record SkillExpertAssignmentBatchPO(String id,Long ownerId,Long skillId,Long versionId,
        String payload,boolean started,LocalDateTime expiresAt,LocalDateTime createdAt) {}
