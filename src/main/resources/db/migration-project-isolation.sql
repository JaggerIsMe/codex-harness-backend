-- One-time migration for installations created before project isolation.
-- Backfills one project per existing user/workspace pair, then makes project_id mandatory.

USE `harness`;

ALTER TABLE agent_device ADD COLUMN isolation_mode VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' AFTER os_version;

CREATE TABLE IF NOT EXISTS `codex_project` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT UNSIGNED NOT NULL,
    `device_id` BIGINT UNSIGNED NOT NULL,
    `workspace_id` BIGINT UNSIGNED NOT NULL,
    `project_name` VARCHAR(128) NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `isolation_mode` VARCHAR(32) NOT NULL DEFAULT 'WINDOWS_ELEVATED',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_codex_project_owner_name` (`user_id`, `project_name`),
    UNIQUE KEY `uk_codex_project_workspace` (`workspace_id`),
    UNIQUE KEY `uk_codex_project_scope` (`id`, `user_id`, `device_id`, `workspace_id`),
    KEY `idx_codex_project_owner_status` (`user_id`, `status`),
    CONSTRAINT `fk_codex_project_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_codex_project_device` FOREIGN KEY (`device_id`) REFERENCES `agent_device` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_codex_project_workspace_device` FOREIGN KEY (`workspace_id`, `device_id`) REFERENCES `agent_workspace` (`id`, `device_id`) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO codex_project(user_id,device_id,workspace_id,project_name,status,isolation_mode)
SELECT c.user_id,c.device_id,c.workspace_id,
       CONCAT(LEFT(w.workspace_name, 96), '-', c.workspace_id),
       'ACTIVE','WINDOWS_ELEVATED'
FROM conversation c
JOIN agent_workspace w ON w.id=c.workspace_id
LEFT JOIN codex_project p ON p.workspace_id=c.workspace_id
WHERE p.id IS NULL
GROUP BY c.user_id,c.device_id,c.workspace_id,w.workspace_name;

ALTER TABLE conversation ADD COLUMN project_id BIGINT UNSIGNED NULL AFTER workspace_id;
UPDATE conversation c JOIN codex_project p
  ON p.user_id=c.user_id AND p.device_id=c.device_id AND p.workspace_id=c.workspace_id
SET c.project_id=p.id
WHERE c.project_id IS NULL;
ALTER TABLE conversation MODIFY project_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE conversation ADD KEY idx_conversation_project_activity(project_id,last_activity_at);
ALTER TABLE conversation ADD KEY idx_conversation_project_scope(project_id,user_id,device_id,workspace_id);
ALTER TABLE conversation ADD CONSTRAINT fk_conversation_project_scope
  FOREIGN KEY(project_id,user_id,device_id,workspace_id)
  REFERENCES codex_project(id,user_id,device_id,workspace_id)
  ON UPDATE RESTRICT ON DELETE RESTRICT;
