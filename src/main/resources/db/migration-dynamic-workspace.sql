-- One-time migration for Web-created dynamic workspaces.
-- Do not run after applying the current schema.sql to a fresh database.
USE `harness`;

ALTER TABLE `agent_workspace`
    MODIFY COLUMN `root_path` VARCHAR(1024) NULL COMMENT 'Canonical absolute path reported by Agent; null while creating',
    MODIFY COLUMN `root_path_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'SHA-256 of normalized root_path',
    ADD COLUMN `parent_name` VARCHAR(128) NULL COMMENT 'Logical authorized parent used for dynamic creation' AFTER `status`,
    ADD COLUMN `project_type` VARCHAR(32) NULL COMMENT 'Workspace initialization type' AFTER `parent_name`,
    ADD COLUMN `failure_code` VARCHAR(64) NULL COMMENT 'Stable creation failure code' AFTER `project_type`,
    ADD COLUMN `failure_message` VARCHAR(2000) NULL COMMENT 'Sanitized creation failure description' AFTER `failure_code`,
    ADD COLUMN `created_by` BIGINT UNSIGNED NULL COMMENT 'Administrator that requested dynamic creation' AFTER `failure_message`,
    ADD KEY `idx_agent_workspace_created_by` (`created_by`),
    ADD CONSTRAINT `fk_agent_workspace_created_by` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

CREATE TABLE `agent_workspace_root` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `device_id` BIGINT UNSIGNED NOT NULL,
    `root_name` VARCHAR(128) NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    `last_reported_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_workspace_root_device_name` (`device_id`, `root_name`),
    KEY `idx_agent_workspace_root_device_status` (`device_id`, `status`),
    CONSTRAINT `fk_agent_workspace_root_device` FOREIGN KEY (`device_id`) REFERENCES `agent_device` (`id`) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
