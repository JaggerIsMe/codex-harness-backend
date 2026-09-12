package com.myharness.codex.entity.vo;
import java.time.LocalDateTime;
import java.util.List;
public final class SkillExpertAssignmentVO {
    private SkillExpertAssignmentVO() {}
    public record Target(Long skillId,Long versionId,String skillName,String version) {}
    public record Change(Long skillId,Long versionId,String skillName,String version,Long draftVersionId,
                         String draftVersion,String publishedVersion,String action) {}
    public record Candidate(Long expertId,String name,String status,Long revision,Long draftVersionId,
            String draftVersion,String publishedVersion,String action,String reason,List<Change> changes) {}
    public record Page(List<Candidate> items,long total,int page,int size) {}
    public record Preview(String batchId,String skillName,String version,List<Candidate> items,LocalDateTime expiresAt,List<Target> targets) {}
    public record Result(Long expertId,String name,String action,Long previousVersionId,Long versionId,
            String status,String message,Long revision,List<Change> changes) {}
    public record Counts(long total,long successCount,long failedCount,long skippedCount,long pendingCount) {}
    public record Submission(String batchId,String skillName,String version,boolean started,boolean complete,
            List<Result> items,List<Target> targets,Counts expertResults,Counts bindingResults,
            Long ownerId,String ownerName,boolean canResume) {}
    public record History(String batchId,String skillName,String version,LocalDateTime createdAt,long successCount,long failedCount,long skippedCount,
                          List<Target> targets,Counts expertResults,Counts bindingResults,Long ownerId,String ownerName,boolean complete) {}
}
