-- Apply once after migration-conversation-expert-assignment.sql and before deploying this release.
ALTER TABLE expert_version
    ADD COLUMN compatible_upgrade TINYINT NOT NULL DEFAULT 0 AFTER skill_version_ids;
