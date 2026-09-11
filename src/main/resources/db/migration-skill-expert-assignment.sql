-- MySQL 8.0.19+: upgrade the existing Skill/expert schema before deploying this release.
-- Stop the backend first. Select the target database (normally harness), then execute
-- this entire file on one connection, stopping on errors. DDL implicitly commits.
-- Existing expert/Skill/version data is preserved. The obsolete device_skill table
-- is intentionally dropped; its machine/project installations are not migrated.
-- Safe to rerun after successful completion, including on the current fresh schema.

-- Initialize the flag only when adding the column, never on subsequent executions.
SET @skill_assignment_add_draft_changed = (
    SELECT COUNT(*) = 0 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'expert' AND column_name = 'draft_changed'
);
SET @skill_assignment_ddl = IF(@skill_assignment_add_draft_changed,
    'ALTER TABLE expert ADD COLUMN draft_changed TINYINT NOT NULL DEFAULT 1 AFTER mcp_version_ids',
    'DO 0');
PREPARE skill_assignment_statement FROM @skill_assignment_ddl;
EXECUTE skill_assignment_statement;
DEALLOCATE PREPARE skill_assignment_statement;

-- Compare the draft to its own published snapshot; absent snapshots remain dirty.
-- Binary comparison detects case/trailing-space differences. Different JSON text
-- is conservatively considered changed. Preserve lifecycle state/revision/timestamps.
UPDATE expert e
LEFT JOIN expert_version v ON v.id = e.published_version_id AND v.expert_id = e.id
SET e.draft_changed = CASE WHEN v.id IS NOT NULL
        AND CAST(e.name AS BINARY) = CAST(v.name AS BINARY)
        AND CAST(e.description AS BINARY) = CAST(v.description AS BINARY)
        AND CAST(e.system_prompt AS BINARY) = CAST(v.system_prompt AS BINARY)
        AND CAST(e.skill_version_ids AS BINARY) = CAST(v.skill_version_ids AS BINARY)
        AND CAST(e.mcp_version_ids AS BINARY) = CAST(v.mcp_version_ids AS BINARY)
    THEN 0 ELSE 1 END,
    e.updated_at = e.updated_at
WHERE @skill_assignment_add_draft_changed = 1;

CREATE TABLE IF NOT EXISTS skill_expert_assignment_batch (
    id VARCHAR(36) PRIMARY KEY, owner_id BIGINT UNSIGNED NOT NULL,
    skill_id BIGINT UNSIGNED NOT NULL, version_id BIGINT UNSIGNED NOT NULL,
    payload JSON NOT NULL, started TINYINT NOT NULL DEFAULT 0,
    expires_at DATETIME(3) NOT NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_skill_assignment_owner(owner_id,created_at),
    FOREIGN KEY(owner_id) REFERENCES sys_user(id), FOREIGN KEY(skill_id) REFERENCES skill(id),
    FOREIGN KEY(version_id) REFERENCES skill_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS skill_expert_assignment_item (
    batch_id VARCHAR(36) NOT NULL, expert_id BIGINT UNSIGNED NOT NULL, payload JSON NOT NULL,
    PRIMARY KEY(batch_id,expert_id),
    FOREIGN KEY(batch_id) REFERENCES skill_expert_assignment_batch(id) ON DELETE CASCADE,
    FOREIGN KEY(expert_id) REFERENCES expert(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS device_skill;

SET @skill_assignment_add_draft_changed = NULL, @skill_assignment_ddl = NULL;
