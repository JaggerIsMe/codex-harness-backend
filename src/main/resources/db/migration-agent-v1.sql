-- One-time migration for databases initialized before Harness Agent server integration.
-- Do not run after applying the current schema.sql to a fresh database.
USE `newharness`;

ALTER TABLE `agent_device`
    ADD COLUMN `os_name` VARCHAR(128) NULL COMMENT 'Last reported operating system name' AFTER `agent_version`,
    ADD COLUMN `os_version` VARCHAR(128) NULL COMMENT 'Last reported operating system version' AFTER `os_name`;

CREATE TABLE `agent_event_message` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `device_id` BIGINT UNSIGNED NOT NULL,
    `message_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `event_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `agent_timestamp` BIGINT NOT NULL,
    `duplicate_count` INT UNSIGNED NOT NULL DEFAULT 0,
    `last_seen_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_event_device_message` (`device_id`, `message_id`),
    KEY `idx_agent_event_device_created` (`device_id`, `created_at`),
    CONSTRAINT `fk_agent_event_device` FOREIGN KEY (`device_id`) REFERENCES `agent_device` (`id`) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `approval_request`
    DROP INDEX `uk_approval_request_remote`,
    ADD COLUMN `device_id` BIGINT UNSIGNED NULL AFTER `turn_id`;

UPDATE `approval_request` a
JOIN `conversation` c ON c.id=a.conversation_id
SET a.device_id=c.device_id
WHERE a.device_id IS NULL;

ALTER TABLE `approval_request`
    MODIFY COLUMN `device_id` BIGINT UNSIGNED NOT NULL,
    ADD UNIQUE KEY `uk_approval_request_remote` (`device_id`, `remote_request_id`),
    ADD CONSTRAINT `fk_approval_request_device` FOREIGN KEY (`device_id`) REFERENCES `agent_device` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;
