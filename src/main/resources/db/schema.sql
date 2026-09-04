-- My Harness For Codex V1 database schema.
-- Compatible with MySQL 5.7.19 and MySQL 8.x.
-- Application code must read/write DATETIME values as UTC.

CREATE DATABASE IF NOT EXISTS `newharness`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `newharness`;

CREATE TABLE IF NOT EXISTS `sys_user` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `username` VARCHAR(64) NOT NULL COMMENT 'Login name',
    `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt password hash',
    `display_name` VARCHAR(128) NOT NULL COMMENT 'Display name',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    `last_login_at` DATETIME(3) NULL COMMENT 'Last successful login time (UTC)',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_username` (`username`),
    KEY `idx_sys_user_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Administrator accounts';

CREATE TABLE IF NOT EXISTS `agent_enrollment` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `enrollment_code_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA-256 of one-time enrollment code',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/USED/EXPIRED/REVOKED',
    `expires_at` DATETIME(3) NOT NULL COMMENT 'Expiration time (UTC)',
    `used_at` DATETIME(3) NULL COMMENT 'Use time (UTC)',
    `created_by` BIGINT UNSIGNED NOT NULL COMMENT 'Administrator that created the enrollment',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_enrollment_code_hash` (`enrollment_code_hash`),
    KEY `idx_agent_enrollment_status_expires` (`status`, `expires_at`),
    KEY `idx_agent_enrollment_created_by` (`created_by`),
    CONSTRAINT `fk_agent_enrollment_created_by` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='One-time device enrollments';

CREATE TABLE IF NOT EXISTS `agent_device` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `enrollment_id` BIGINT UNSIGNED NULL COMMENT 'Enrollment used to register this device',
    `device_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Public stable device identifier',
    `device_name` VARCHAR(128) NOT NULL COMMENT 'Human-readable device name',
    `token_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA-256 of device token',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ONLINE/OFFLINE/DISABLED',
    `agent_version` VARCHAR(64) NULL COMMENT 'Last reported Harness Agent version',
    `os_name` VARCHAR(128) NULL COMMENT 'Last reported operating system name',
    `os_version` VARCHAR(128) NULL COMMENT 'Last reported operating system version',
    `isolation_mode` VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/UNSUPPORTED/WINDOWS_ELEVATED',
    `last_heartbeat_at` DATETIME(3) NULL COMMENT 'Last accepted heartbeat time (UTC)',
    `registered_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Registration time (UTC)',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_device_enrollment` (`enrollment_id`),
    UNIQUE KEY `uk_agent_device_code` (`device_code`),
    UNIQUE KEY `uk_agent_device_token_hash` (`token_hash`),
    KEY `idx_agent_device_status_heartbeat` (`status`, `last_heartbeat_at`),
    CONSTRAINT `fk_agent_device_enrollment` FOREIGN KEY (`enrollment_id`) REFERENCES `agent_enrollment` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Registered target computers';

CREATE TABLE IF NOT EXISTS `agent_workspace` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `device_id` BIGINT UNSIGNED NOT NULL COMMENT 'Owning device',
    `workspace_name` VARCHAR(128) NOT NULL COMMENT 'Name from Agent configuration',
    `root_path` VARCHAR(1024) NULL COMMENT 'Canonical absolute path reported by Agent; null while creating',
    `root_path_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'SHA-256 of normalized root_path',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'CREATING/ENABLED/FAILED/MISSING/DISABLED',
    `parent_name` VARCHAR(128) NULL COMMENT 'Logical authorized parent used for dynamic creation',
    `project_type` VARCHAR(32) NULL COMMENT 'Workspace initialization type',
    `failure_code` VARCHAR(64) NULL COMMENT 'Stable creation failure code',
    `failure_message` VARCHAR(2000) NULL COMMENT 'Sanitized creation failure description',
    `created_by` BIGINT UNSIGNED NULL COMMENT 'Administrator that requested dynamic creation',
    `last_reported_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Last Agent report time (UTC)',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_workspace_device_name` (`device_id`, `workspace_name`),
    UNIQUE KEY `uk_agent_workspace_device_path` (`device_id`, `root_path_hash`),
    UNIQUE KEY `uk_agent_workspace_id_device` (`id`, `device_id`),
    KEY `idx_agent_workspace_device_status` (`device_id`, `status`),
    KEY `idx_agent_workspace_created_by` (`created_by`),
    CONSTRAINT `fk_agent_workspace_device` FOREIGN KEY (`device_id`) REFERENCES `agent_device` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_agent_workspace_created_by` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Device-approved Codex workspaces';

CREATE TABLE IF NOT EXISTS `agent_workspace_root` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `device_id` BIGINT UNSIGNED NOT NULL COMMENT 'Owning device',
    `root_name` VARCHAR(128) NOT NULL COMMENT 'Logical parent name reported by Agent',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    `last_reported_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_workspace_root_device_name` (`device_id`, `root_name`),
    KEY `idx_agent_workspace_root_device_status` (`device_id`, `status`),
    CONSTRAINT `fk_agent_workspace_root_device` FOREIGN KEY (`device_id`) REFERENCES `agent_device` (`id`) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent-authorized workspace creation parents';

CREATE TABLE IF NOT EXISTS `codex_project` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id` BIGINT UNSIGNED NOT NULL COMMENT 'Project owner',
    `device_id` BIGINT UNSIGNED NOT NULL COMMENT 'Execution device',
    `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Exclusively bound execution workspace',
    `project_name` VARCHAR(128) NOT NULL COMMENT 'User-facing project name',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED/DISABLED',
    `isolation_mode` VARCHAR(32) NOT NULL DEFAULT 'WINDOWS_ELEVATED' COMMENT 'Required execution isolation',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_codex_project_owner_name` (`user_id`, `project_name`),
    UNIQUE KEY `uk_codex_project_workspace` (`workspace_id`),
    UNIQUE KEY `uk_codex_project_scope` (`id`, `user_id`, `device_id`, `workspace_id`),
    KEY `idx_codex_project_owner_status` (`user_id`, `status`),
    KEY `idx_codex_project_workspace_device` (`workspace_id`, `device_id`),
    CONSTRAINT `fk_codex_project_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_codex_project_device` FOREIGN KEY (`device_id`) REFERENCES `agent_device` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_codex_project_workspace_device` FOREIGN KEY (`workspace_id`, `device_id`) REFERENCES `agent_workspace` (`id`, `device_id`) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User projects with exclusive execution roots';

CREATE TABLE IF NOT EXISTS `agent_event_message` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `device_id` BIGINT UNSIGNED NOT NULL COMMENT 'Source device',
    `message_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Protocol message UUID',
    `event_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Agent event type',
    `agent_timestamp` BIGINT NOT NULL COMMENT 'Agent supplied Unix timestamp in milliseconds',
    `duplicate_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Number of duplicate deliveries observed',
    `last_seen_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Most recent delivery time',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_event_device_message` (`device_id`, `message_id`),
    KEY `idx_agent_event_created` (`created_at`),
    KEY `idx_agent_event_device_created` (`device_id`, `created_at`),
    CONSTRAINT `fk_agent_event_device` FOREIGN KEY (`device_id`) REFERENCES `agent_device` (`id`) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Idempotency ledger for Agent events';

CREATE TABLE IF NOT EXISTS `skill` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `skill_name` VARCHAR(128) NOT NULL COMMENT 'Canonical Skill name',
    `description` VARCHAR(1000) NOT NULL DEFAULT '' COMMENT 'Skill description',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    `created_by` BIGINT UNSIGNED NOT NULL COMMENT 'Creator user',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skill_name` (`skill_name`),
    KEY `idx_skill_status` (`status`),
    KEY `idx_skill_created_by` (`created_by`),
    CONSTRAINT `fk_skill_created_by` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Logical Skills';

CREATE TABLE IF NOT EXISTS `skill_version` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `skill_id` BIGINT UNSIGNED NOT NULL COMMENT 'Owning Skill',
    `version` VARCHAR(64) NOT NULL COMMENT 'Version identifier',
    `storage_path` VARCHAR(1024) NOT NULL COMMENT 'Server-controlled ZIP storage path',
    `sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA-256 of original ZIP',
    `file_size` BIGINT UNSIGNED NOT NULL COMMENT 'Original ZIP size in bytes',
    `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/DISABLED',
    `created_by` BIGINT UNSIGNED NOT NULL COMMENT 'Uploader user',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skill_version_skill_version` (`skill_id`, `version`),
    KEY `idx_skill_version_skill_status` (`skill_id`, `status`),
    KEY `idx_skill_version_sha256` (`sha256`),
    KEY `idx_skill_version_created_by` (`created_by`),
    CONSTRAINT `fk_skill_version_skill` FOREIGN KEY (`skill_id`) REFERENCES `skill` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_skill_version_created_by` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Immutable Skill versions';

CREATE TABLE IF NOT EXISTS `device_skill` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `device_id` BIGINT UNSIGNED NOT NULL COMMENT 'Target device',
    `skill_version_id` BIGINT UNSIGNED NOT NULL COMMENT 'Installed Skill version',
    `scope_type` VARCHAR(16) NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/PROJECT',
    `project_id` BIGINT UNSIGNED NULL COMMENT 'Target project for PROJECT scope',
    `scope_key` VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'GLOBAL' COMMENT 'Stable uniqueness key: GLOBAL or PROJECT:{id}',
    `install_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/INSTALLING/INSTALLED/REMOVING/FAILED/REMOVED',
    `error_message` VARCHAR(2000) NULL COMMENT 'Last installation error without secrets',
    `requested_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Latest install request time (UTC)',
    `installed_at` DATETIME(3) NULL COMMENT 'Successful install time (UTC)',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_skill_scope` (`device_id`, `skill_version_id`, `scope_key`),
    KEY `idx_device_skill_device_status` (`device_id`, `install_status`),
    KEY `idx_device_skill_version` (`skill_version_id`),
    KEY `idx_device_skill_project_status` (`project_id`, `install_status`),
    CONSTRAINT `fk_device_skill_device` FOREIGN KEY (`device_id`) REFERENCES `agent_device` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_device_skill_version` FOREIGN KEY (`skill_version_id`) REFERENCES `skill_version` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_device_skill_project` FOREIGN KEY (`project_id`) REFERENCES `codex_project` (`id`) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill installation state per device';

CREATE TABLE IF NOT EXISTS `conversation` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id` BIGINT UNSIGNED NOT NULL COMMENT 'Conversation owner',
    `device_id` BIGINT UNSIGNED NOT NULL COMMENT 'Execution device',
    `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Execution workspace',
    `project_id` BIGINT UNSIGNED NOT NULL COMMENT 'Owning isolated project',
    `title` VARCHAR(255) NOT NULL DEFAULT '新会话' COMMENT 'Display title',
    `codex_thread_id` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Remote Codex thread identifier',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED/FAILED',
    `last_activity_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Last conversation activity (UTC)',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_codex_thread` (`codex_thread_id`),
    KEY `idx_conversation_user_activity` (`user_id`, `last_activity_at`),
    KEY `idx_conversation_device_status` (`device_id`, `status`),
    KEY `idx_conversation_workspace_device` (`workspace_id`, `device_id`),
    KEY `idx_conversation_project_activity` (`project_id`, `last_activity_at`),
    KEY `idx_conversation_project_scope` (`project_id`, `user_id`, `device_id`, `workspace_id`),
    CONSTRAINT `fk_conversation_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_conversation_device` FOREIGN KEY (`device_id`) REFERENCES `agent_device` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_conversation_workspace_device` FOREIGN KEY (`workspace_id`, `device_id`) REFERENCES `agent_workspace` (`id`, `device_id`) ON UPDATE RESTRICT ON DELETE RESTRICT
    ,CONSTRAINT `fk_conversation_project_scope` FOREIGN KEY (`project_id`, `user_id`, `device_id`, `workspace_id`) REFERENCES `codex_project` (`id`, `user_id`, `device_id`, `workspace_id`) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Durable Codex conversations';

CREATE TABLE IF NOT EXISTS `conversation_turn` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `conversation_id` BIGINT UNSIGNED NOT NULL COMMENT 'Owning conversation',
    `codex_turn_id` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Remote Codex turn identifier',
    `status` VARCHAR(32) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/RUNNING/WAITING_APPROVAL/COMPLETED/FAILED/INTERRUPTED/DECLINED',
    `active_slot` TINYINT GENERATED ALWAYS AS (CASE WHEN `status` IN ('CREATED', 'RUNNING', 'WAITING_APPROVAL') THEN 1 ELSE NULL END) STORED COMMENT 'Enforces one active turn per conversation',
    `failure_code` VARCHAR(64) NULL COMMENT 'Stable failure code',
    `failure_message` VARCHAR(2000) NULL COMMENT 'Sanitized failure description',
    `started_at` DATETIME(3) NULL COMMENT 'Execution start time (UTC)',
    `finished_at` DATETIME(3) NULL COMMENT 'Terminal state time (UTC)',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_turn_active` (`conversation_id`, `active_slot`),
    UNIQUE KEY `uk_conversation_turn_codex` (`conversation_id`, `codex_turn_id`),
    UNIQUE KEY `uk_conversation_turn_id_conversation` (`id`, `conversation_id`),
    KEY `idx_conversation_turn_status_created` (`status`, `created_at`),
    CONSTRAINT `fk_conversation_turn_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `conversation` (`id`) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Individual requests within conversations';

CREATE TABLE IF NOT EXISTS `conversation_message` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `conversation_id` BIGINT UNSIGNED NOT NULL COMMENT 'Owning conversation',
    `turn_id` BIGINT UNSIGNED NULL COMMENT 'Producing turn; null for conversation-level system messages',
    `sequence_no` BIGINT UNSIGNED NOT NULL COMMENT 'Monotonic sequence within conversation',
    `role` VARCHAR(16) NOT NULL COMMENT 'USER/ASSISTANT/SYSTEM',
    `message_type` VARCHAR(32) NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/COMMENTARY/REASONING/COMMAND/COMMAND_OUTPUT/FILE_CHANGE/ACTIVITY/ERROR',
    `content` LONGTEXT NOT NULL COMMENT 'Message or event content',
    `message_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Stable logical Message key; null for legacy records',
    `item_id` VARCHAR(128) NULL,
    `phase` VARCHAR(32) NULL,
    `status` VARCHAR(24) NOT NULL DEFAULT 'COMPLETED',
    `revision` BIGINT UNSIGNED NOT NULL DEFAULT 0,
    `metadata` LONGTEXT NULL,
    `truncated` BOOLEAN NOT NULL DEFAULT FALSE,
    `updated_at` DATETIME(3) NULL,
    `completed_at` DATETIME(3) NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_message_sequence` (`conversation_id`, `sequence_no`),
    UNIQUE KEY `uk_conversation_message_key` (`conversation_id`, `message_key`),
    KEY `idx_conversation_message_turn` (`turn_id`, `conversation_id`),
    KEY `idx_conversation_message_created` (`conversation_id`, `created_at`),
    CONSTRAINT `fk_conversation_message_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `conversation` (`id`) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fk_conversation_message_turn` FOREIGN KEY (`turn_id`, `conversation_id`) REFERENCES `conversation_turn` (`id`, `conversation_id`) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Ordered conversation messages and visible events';

CREATE TABLE IF NOT EXISTS `approval_request` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `conversation_id` BIGINT UNSIGNED NOT NULL COMMENT 'Owning conversation',
    `turn_id` BIGINT UNSIGNED NOT NULL COMMENT 'Blocked turn',
    `device_id` BIGINT UNSIGNED NOT NULL COMMENT 'Device that owns the remote approval request',
    `remote_request_id` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Approval identifier from Codex/Agent',
    `approval_type` VARCHAR(32) NOT NULL COMMENT 'COMMAND/FILE_CHANGE/NETWORK/OTHER',
    `payload` JSON NOT NULL COMMENT 'Structured sanitized approval details',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/CANCELLED/EXPIRED',
    `decided_by` BIGINT UNSIGNED NULL COMMENT 'Administrator who made the decision',
    `decision_note` VARCHAR(1000) NULL COMMENT 'Optional decision note',
    `expires_at` DATETIME(3) NULL COMMENT 'Decision deadline (UTC)',
    `decided_at` DATETIME(3) NULL COMMENT 'Decision time (UTC)',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_approval_request_remote` (`device_id`, `remote_request_id`),
    KEY `idx_approval_request_conversation_status` (`conversation_id`, `status`),
    KEY `idx_approval_request_turn_conversation` (`turn_id`, `conversation_id`),
    KEY `idx_approval_request_decided_by` (`decided_by`),
    KEY `idx_approval_request_pending_expiry` (`status`, `expires_at`),
    CONSTRAINT `fk_approval_request_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `conversation` (`id`) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fk_approval_request_turn` FOREIGN KEY (`turn_id`, `conversation_id`) REFERENCES `conversation_turn` (`id`, `conversation_id`) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fk_approval_request_device` FOREIGN KEY (`device_id`) REFERENCES `agent_device` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_approval_request_decided_by` FOREIGN KEY (`decided_by`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Sensitive action approvals';

CREATE TABLE IF NOT EXISTS `audit_log` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `operator_type` VARCHAR(16) NOT NULL COMMENT 'USER/AGENT/SYSTEM',
    `operator_id` BIGINT UNSIGNED NULL COMMENT 'User ID when operator_type is USER',
    `action` VARCHAR(64) NOT NULL COMMENT 'Stable audited action name',
    `target_type` VARCHAR(32) NOT NULL COMMENT 'Target aggregate type',
    `target_id` VARCHAR(128) NULL COMMENT 'Target identifier',
    `result` VARCHAR(16) NOT NULL COMMENT 'SUCCESS/FAILURE',
    `detail` JSON NULL COMMENT 'Structured sanitized audit detail',
    `ip_address` VARCHAR(45) NULL COMMENT 'IPv4 or IPv6 address when applicable',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_audit_log_operator_created` (`operator_type`, `operator_id`, `created_at`),
    KEY `idx_audit_log_target_created` (`target_type`, `target_id`, `created_at`),
    KEY `idx_audit_log_action_created` (`action`, `created_at`),
    CONSTRAINT `fk_audit_log_operator` FOREIGN KEY (`operator_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Basic security and business audit trail';
