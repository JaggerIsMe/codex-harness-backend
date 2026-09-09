-- Adds GLOBAL / PROJECT deployment scopes to an existing Harness database.
-- Run once after deploying the matching server and Agent versions.

USE `harness`;

ALTER TABLE `device_skill`
    ADD COLUMN `scope_type` VARCHAR(16) NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/PROJECT' AFTER `skill_version_id`,
    ADD COLUMN `project_id` BIGINT UNSIGNED NULL COMMENT 'Target project for PROJECT scope' AFTER `scope_type`,
    ADD COLUMN `scope_key` VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL or PROJECT:{id}' AFTER `project_id`;

ALTER TABLE `device_skill`
    DROP INDEX `uk_device_skill_device_version`,
    ADD UNIQUE KEY `uk_device_skill_scope` (`device_id`, `skill_version_id`, `scope_key`),
    ADD KEY `idx_device_skill_project_status` (`project_id`, `install_status`),
    ADD CONSTRAINT `fk_device_skill_project` FOREIGN KEY (`project_id`) REFERENCES `codex_project` (`id`) ON UPDATE RESTRICT ON DELETE CASCADE;
