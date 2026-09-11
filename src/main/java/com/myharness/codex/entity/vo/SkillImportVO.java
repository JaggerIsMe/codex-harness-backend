package com.myharness.codex.entity.vo;

import java.time.LocalDateTime;
import java.util.List;

public final class SkillImportVO {
    private SkillImportVO() {}
    public record Upload(String uploadId, String filename, long fileSize, String sha256,
                         String skillName, String description, Long matchedSkillId, LocalDateTime expiresAt) {}
    public record Impact(Long expertId, String expertName, String expertStatus, String source,
                         Long expertVersionId, Long expertVersionNo, Long skillVersionId) {}
    public record Item(String itemId, Long skillId, String skillName, String version, String currentVersion,
                       String status, String message, List<Impact> experts, String fingerprint) {}
    public record Preview(String previewId, List<Item> items, long affectedExpertCount, LocalDateTime expiresAt) {}
    public record Result(String itemId, String status, String message, Long skillId, Long versionId) {}
    public record Submission(String submissionId, boolean complete, List<Result> items,
                             long successCount, long failedCount, long skippedCount) {}
}
