-- Apply once after migration-conversation-expert-assignment.sql and before deploying this release.
-- Also repairs installations made from the earlier full schema that put this column on expert.
-- Select the target harness database first; run only if expert_version.compatible_upgrade is missing.
-- Preserves existing rows; existing versions default to non-compatible. Fresh corrected schema includes this column.
ALTER TABLE expert_version
    ADD COLUMN compatible_upgrade TINYINT NOT NULL DEFAULT 0 AFTER skill_version_ids;
