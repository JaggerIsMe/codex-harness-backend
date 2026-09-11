-- My Harness For Codex V1 database schema.
-- MySQL 8.0.19+ (including the accompanying seed-rbac.sql).
-- Application code must read/write DATETIME values as UTC.

CREATE DATABASE IF NOT EXISTS `harness`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `harness`;

CREATE TABLE IF NOT EXISTS `sys_user` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `email` VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Canonical login email',
    `password_hash` VARCHAR(100) NULL COMMENT 'BCrypt password hash; NULL before activation',
    `token_version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
    `must_change_password` BOOLEAN NOT NULL DEFAULT FALSE,
    `password_changed_at` DATETIME(3) NULL,
    `display_name` VARCHAR(128) NOT NULL COMMENT 'Display name',
    `email_verified_at` DATETIME(3) NULL,
    `activated_at` DATETIME(3) NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    `last_login_at` DATETIME(3) NULL COMMENT 'Last successful login time (UTC)',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_email` (`email`),
    KEY `idx_sys_user_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Email-based user accounts';

-- One-time account credentials, consumed in the same transaction as account mutations.
CREATE TABLE IF NOT EXISTS account_email_challenge (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    generation VARCHAR(36) NOT NULL,
    digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    used_at DATETIME(3) NULL,
    revoked_at DATETIME(3) NULL,
    PRIMARY KEY(id),
    UNIQUE KEY uk_account_email_digest(digest),
    UNIQUE KEY uk_account_email_generation(user_id,purpose,generation),
    KEY idx_account_email_user(user_id,purpose,id),
    KEY idx_account_email_expiry(status,expires_at),
    CONSTRAINT fk_account_email_user FOREIGN KEY(user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Kept independently of account lifetime: deleting/disabling an account must not bootstrap it again.
CREATE TABLE IF NOT EXISTS system_initialization (
    initialization_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    PRIMARY KEY(initialization_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Only encrypted challenge material is stored; completed tasks retain non-secret metadata.
CREATE TABLE IF NOT EXISTS mail_delivery_task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    challenge_id BIGINT UNSIGNED NULL,
    template VARCHAR(32) NOT NULL,
    recipient VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    encrypted_payload MEDIUMTEXT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    lease_until DATETIME(3) NULL,
    lease_token VARCHAR(36) NULL,
    error_code VARCHAR(64) NULL,
    accepted_at DATETIME(3) NULL,
    expires_at DATETIME(3) NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(id),
    UNIQUE KEY uk_mail_delivery_event(idempotency_key),
    KEY idx_mail_delivery_due(status,next_attempt_at),
    KEY idx_mail_delivery_lease(status,lease_until),
    KEY idx_mail_delivery_user(user_id,template,id),
    KEY idx_mail_delivery_challenge(challenge_id),
    KEY idx_mail_delivery_expiry(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
    `conversation_attachments` TINYINT NOT NULL DEFAULT 0,
    `workspace_files` TINYINT(1) NOT NULL DEFAULT 0,
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `enrollment_id` BIGINT UNSIGNED NULL COMMENT 'Enrollment used to register this device',
    `device_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Public stable device identifier',
    `device_name` VARCHAR(128) NOT NULL COMMENT 'Human-readable device name',
    `token_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA-256 of device token',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ONLINE/OFFLINE/DISABLED',
    `agent_version` VARCHAR(64) NULL COMMENT 'Last reported Harness Agent version',
    `os_name` VARCHAR(128) NULL COMMENT 'Last reported operating system name',
    `os_version` VARCHAR(128) NULL COMMENT 'Last reported operating system version',
    `isolation_mode` VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/UNSUPPORTED/WINDOWS_PROJECT_PROFILE',
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
    `request_key` VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED/DISABLED',
    `isolation_mode` VARCHAR(32) NOT NULL DEFAULT 'WINDOWS_PROJECT_PROFILE' COMMENT 'Required execution isolation',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_codex_project_owner_name` (`user_id`, `project_name`),
    UNIQUE KEY `uk_project_user_request` (`user_id`, `request_key`),
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
    `agent_timestamp` BIGINT UNSIGNED NOT NULL COMMENT 'Agent supplied Unix timestamp in milliseconds',
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



CREATE TABLE IF NOT EXISTS `conversation` (
  `expert_runtime_key` CHAR(64) NULL COMMENT '当前 Codex 线程的专家运行配置标识',
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
    `client_request_id` VARCHAR(64) NULL,
    `request_hash` CHAR(64) NULL,
    `preparation_phase` VARCHAR(32) NULL,
    UNIQUE KEY uk_turn_client_request (conversation_id,client_request_id),
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

CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    role_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    built_in BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY(id), UNIQUE KEY uk_sys_role_code(role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    permission_code VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    permission_name VARCHAR(128) NOT NULL,
    PRIMARY KEY(id), UNIQUE KEY uk_sys_permission_code(permission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY(user_id,role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY(user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_user_role_role FOREIGN KEY(role_id) REFERENCES sys_role(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sys_role_permission (
    role_id BIGINT UNSIGNED NOT NULL,
    permission_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY(role_id,permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY(role_id) REFERENCES sys_role(id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY(permission_id) REFERENCES sys_permission(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS user_device_assignment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    device_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    assigned_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY(id),
    UNIQUE KEY uk_user_device(user_id,device_id),
    KEY idx_assignment_device(device_id,status,user_id),
    CONSTRAINT fk_assignment_user FOREIGN KEY(user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_assignment_device FOREIGN KEY(device_id) REFERENCES agent_device(id),
    CONSTRAINT fk_assignment_operator FOREIGN KEY(assigned_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO sys_role(role_code,role_name) VALUES ('SYS_ADMIN','管理员'),('USER','普通用户');
INSERT IGNORE INTO sys_permission(permission_code,permission_name) VALUES
('system:user:manage','用户与角色管理'),('device:manage','机器与执行目录管理'),
('skill:manage','平台 Skills 管理'),('workspace:use','使用已分配机器'),
('project:create','创建个人项目'),('project:read','读取个人项目'),
('conversation:create','创建个人会话'),('conversation:read','读取个人会话'),
('turn:start','启动 Turn'),('turn:interrupt','中断 Turn'),('approval:decide','处理个人审批');
INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM sys_role r CROSS JOIN sys_permission p WHERE r.role_code='SYS_ADMIN';
INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM sys_role r CROSS JOIN sys_permission p WHERE r.role_code='USER'
AND p.permission_code IN ('workspace:use','project:create','project:read','conversation:create','conversation:read','turn:start','turn:interrupt','approval:decide');

-- Conversation attachments
CREATE TABLE IF NOT EXISTS conversation_attachment (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT UNSIGNED NOT NULL, project_id BIGINT UNSIGNED NOT NULL, conversation_id BIGINT UNSIGNED NOT NULL,
 file_name VARCHAR(255) NOT NULL, storage_key VARCHAR(64) NOT NULL,
 workspace_path VARCHAR(2048) NULL, workspace_operation_id BIGINT NULL,
 media_type VARCHAR(128) NOT NULL, size_bytes BIGINT UNSIGNED NOT NULL, sha256 CHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_attachment_storage (storage_key), KEY idx_attachment_pending (conversation_id,status),
 KEY idx_attachment_cleanup (status,created_at),
 CONSTRAINT fk_attachment_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS conversation_message_attachment (
 message_id BIGINT UNSIGNED NOT NULL, attachment_id BIGINT UNSIGNED NOT NULL, position INT NOT NULL,
 PRIMARY KEY(message_id,attachment_id), UNIQUE KEY uk_message_attachment_position(message_id,position),
 CONSTRAINT fk_ma_message FOREIGN KEY(message_id) REFERENCES conversation_message(id),
 CONSTRAINT fk_ma_attachment FOREIGN KEY(attachment_id) REFERENCES conversation_attachment(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Apply once after the existing RBAC and message attachment migrations.
ALTER TABLE agent_device ADD COLUMN project_experts TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN expert_mcp TINYINT NOT NULL DEFAULT 0;
ALTER TABLE codex_project ADD COLUMN expert_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE conversation ADD COLUMN selected_expert_id BIGINT UNSIGNED NULL,
    ADD COLUMN selected_expert_version_id BIGINT UNSIGNED NULL,
    ADD COLUMN expert_selection_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE conversation_turn ADD COLUMN expert_version_id BIGINT UNSIGNED NULL,
    ADD COLUMN expert_name VARCHAR(128) NULL, ADD COLUMN expert_runtime MEDIUMTEXT NULL;

CREATE TABLE mcp_configuration (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 server_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name VARCHAR(128) NOT NULL, description VARCHAR(2000) NOT NULL DEFAULT '',
 status VARCHAR(16) NOT NULL DEFAULT 'ENABLED', current_version_id BIGINT UNSIGNED NULL,
 revision BIGINT NOT NULL DEFAULT 0, created_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_mcp_configuration_code(server_code),
 CONSTRAINT fk_mcp_configuration_creator FOREIGN KEY(created_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE mcp_configuration_version (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 mcp_configuration_id BIGINT UNSIGNED NOT NULL, version_no BIGINT NOT NULL,
 server_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name VARCHAR(128) NOT NULL, description VARCHAR(2000) NOT NULL DEFAULT '',
 runtime_spec MEDIUMTEXT NOT NULL, config_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_mcp_configuration_version(mcp_configuration_id,version_no),
 CONSTRAINT fk_mcp_version_configuration FOREIGN KEY(mcp_configuration_id) REFERENCES mcp_configuration(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE mcp_configuration ADD CONSTRAINT fk_mcp_current_version
 FOREIGN KEY(current_version_id) REFERENCES mcp_configuration_version(id);

CREATE TABLE expert (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(128) NOT NULL, description VARCHAR(2000) NOT NULL DEFAULT '',
 system_prompt MEDIUMTEXT NOT NULL, skill_version_ids TEXT NOT NULL, mcp_version_ids TEXT NOT NULL,
 draft_changed TINYINT NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', published_version_id BIGINT UNSIGNED NULL,
 revision BIGINT NOT NULL DEFAULT 0, created_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_expert_creator FOREIGN KEY(created_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE expert_version (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, expert_id BIGINT UNSIGNED NOT NULL,
 version_no BIGINT NOT NULL, name VARCHAR(128) NOT NULL, description VARCHAR(2000) NOT NULL,
 system_prompt MEDIUMTEXT NOT NULL, skill_version_ids TEXT NOT NULL, mcp_version_ids TEXT NOT NULL,
 compatible_upgrade TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_expert_version(expert_id,version_no),
 CONSTRAINT fk_expert_version FOREIGN KEY(expert_id) REFERENCES expert(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE project_expert_binding (
 project_id BIGINT UNSIGNED NOT NULL, expert_id BIGINT UNSIGNED NOT NULL,
 expert_version_id BIGINT UNSIGNED NOT NULL,
 PRIMARY KEY(project_id,expert_id),
 CONSTRAINT fk_project_expert_project FOREIGN KEY(project_id) REFERENCES codex_project(id),
 CONSTRAINT fk_project_expert_expert FOREIGN KEY(expert_id) REFERENCES expert(id),
 CONSTRAINT fk_project_expert_version FOREIGN KEY(expert_version_id) REFERENCES expert_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE user_expert_assignment (
 user_id BIGINT UNSIGNED NOT NULL, expert_id BIGINT UNSIGNED NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ENABLED', assigned_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(user_id,expert_id), KEY idx_user_expert_expert(expert_id,status),
 CONSTRAINT fk_user_expert_user FOREIGN KEY(user_id) REFERENCES sys_user(id),
 CONSTRAINT fk_user_expert_expert FOREIGN KEY(expert_id) REFERENCES expert(id),
 CONSTRAINT fk_user_expert_assigner FOREIGN KEY(assigned_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE conversation ADD CONSTRAINT fk_conversation_expert_version
 FOREIGN KEY(selected_expert_version_id) REFERENCES expert_version(id);
INSERT IGNORE INTO sys_permission(permission_code,permission_name) VALUES
 ('expert:manage','专家管理与发布'),('expert:read','专家市场'),('expert:use','项目专家使用'),
 ('mcp:manage','MCP 配置管理');
INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
 SELECT r.id,p.id FROM sys_role r CROSS JOIN sys_permission p
 WHERE (r.role_code='SYS_ADMIN' AND p.permission_code IN ('expert:manage','expert:read','expert:use','mcp:manage'))
 OR (r.role_code='USER' AND p.permission_code IN ('expert:read','expert:use'));

-- Managed model configuration and Device assignments. Existing installations apply migration-managed-models.sql.
ALTER TABLE agent_device ADD COLUMN managed_models TINYINT NOT NULL DEFAULT 0 AFTER expert_mcp,
 ADD COLUMN model_runtime_targets TINYINT NOT NULL DEFAULT 0 AFTER managed_models;
CREATE TABLE model_configuration (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 configuration_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name VARCHAR(128) NOT NULL, description VARCHAR(2000) NOT NULL DEFAULT '',
 status VARCHAR(16) NOT NULL DEFAULT 'ENABLED', current_version_id BIGINT UNSIGNED NULL,
 revision BIGINT NOT NULL DEFAULT 0, created_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_model_configuration_code(configuration_code),
 CONSTRAINT fk_model_configuration_creator FOREIGN KEY(created_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE model_configuration_version (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 model_configuration_id BIGINT UNSIGNED NOT NULL, version_no BIGINT NOT NULL,
 configuration_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name VARCHAR(128) NOT NULL, description VARCHAR(2000) NOT NULL DEFAULT '',
 runtime_spec MEDIUMTEXT NOT NULL, encrypted_api_key TEXT NOT NULL,
 config_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_model_configuration_version(model_configuration_id,version_no),
 CONSTRAINT fk_model_version_configuration FOREIGN KEY(model_configuration_id) REFERENCES model_configuration(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE model_configuration ADD CONSTRAINT fk_model_current_version
 FOREIGN KEY(current_version_id) REFERENCES model_configuration_version(id);
CREATE TABLE device_model_assignment (
 device_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 runtime_mode VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 model_configuration_version_id BIGINT UNSIGNED NULL, revision BIGINT NOT NULL DEFAULT 0,
 assigned_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_device_model_assignment_device FOREIGN KEY(device_id) REFERENCES agent_device(id) ON DELETE CASCADE,
 CONSTRAINT fk_device_model_assignment_version FOREIGN KEY(model_configuration_version_id) REFERENCES model_configuration_version(id),
 CONSTRAINT fk_device_model_assignment_user FOREIGN KEY(assigned_by) REFERENCES sys_user(id),
 CONSTRAINT chk_device_model_runtime_target CHECK (
  (runtime_mode='LOCAL_CODEX' AND model_configuration_version_id IS NULL) OR
  (runtime_mode='MANAGED_PROVIDER' AND model_configuration_version_id IS NOT NULL)
 )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE conversation ADD COLUMN model_runtime_key CHAR(64) NULL AFTER expert_runtime_key;
ALTER TABLE conversation_turn ADD COLUMN model_configuration_version_id BIGINT UNSIGNED NULL AFTER expert_runtime,
 ADD COLUMN model_name VARCHAR(128) NULL AFTER model_configuration_version_id,
 ADD COLUMN model_runtime MEDIUMTEXT NULL AFTER model_name,
 ADD CONSTRAINT fk_turn_model_version FOREIGN KEY(model_configuration_version_id) REFERENCES model_configuration_version(id);
INSERT IGNORE INTO sys_permission(permission_code,permission_name) VALUES ('model:manage','模型配置与 Device 模型分配');
INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
 SELECT r.id,p.id FROM sys_role r CROSS JOIN sys_permission p
 WHERE r.role_code='SYS_ADMIN' AND p.permission_code='model:manage';

CREATE TABLE IF NOT EXISTS workspace_file_operation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_key VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    device_id BIGINT NOT NULL,
    workspace_name VARCHAR(64) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    path VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    page_cursor VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL,
    storage_key VARCHAR(36) NULL,
    sha256 VARCHAR(64) NULL,
    size_bytes BIGINT NOT NULL DEFAULT 0,
    attachment_id BIGINT NULL,
    error VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_workspace_operation_request(project_id,request_key),
    KEY idx_workspace_operation_queue(status,id),
    KEY idx_workspace_operation_project(project_id,id)
);

INSERT IGNORE INTO sys_permission(permission_code,permission_name) VALUES
 ('project:update','修改个人项目'),('project:delete','删除个人项目'),
 ('conversation:update','修改个人会话'),('conversation:delete','删除个人会话');
INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
 SELECT r.id,p.id FROM sys_role r CROSS JOIN sys_permission p
 WHERE r.role_code IN ('SYS_ADMIN','USER')
 AND p.permission_code IN ('project:update','project:delete','conversation:update','conversation:delete');

-- Apply once after migration-workspace-files.sql and migration-retire-legacy-files.sql.
-- Additive migration only. No file operations or data cleanup are performed by this script.
ALTER TABLE agent_device
 ADD COLUMN workspace_file_mutations TINYINT(1) NOT NULL DEFAULT 0,
 ADD COLUMN workspace_archive_download TINYINT(1) NOT NULL DEFAULT 0,
 ADD COLUMN workspace_file_limits TEXT NULL;

ALTER TABLE workspace_file_operation
 ADD COLUMN target_path VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 ADD COLUMN request_digest CHAR(64) NULL,
 ADD COLUMN payload_json MEDIUMTEXT NULL,
 ADD COLUMN result_json MEDIUMTEXT NULL,
 ADD COLUMN code VARCHAR(64) NULL,
 ADD COLUMN content_state VARCHAR(16) NOT NULL DEFAULT 'NONE',
 ADD COLUMN plan_id VARCHAR(36) NULL,
 ADD COLUMN delete_plan_operation_id BIGINT NULL,
 ADD COLUMN attachment_count BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN items_digest CHAR(64) NULL,
 ADD UNIQUE KEY uk_workspace_delete_plan_consumer(delete_plan_operation_id),
 ADD KEY idx_workspace_mutation_gate(project_id,status,kind),
 ADD KEY idx_workspace_delete_plan(project_id,plan_id);
UPDATE workspace_file_operation SET content_state=CASE
 WHEN storage_key IS NOT NULL AND status='SUCCEEDED' THEN 'AVAILABLE'
 WHEN storage_key IS NOT NULL AND status IN ('QUEUED','RUNNING') THEN 'PENDING'
 WHEN kind='PREPARE_WORKSPACE_DOWNLOAD' AND status='EXPIRED' THEN 'EXPIRED'
 ELSE 'NONE' END;

CREATE TABLE IF NOT EXISTS workspace_file_operation_item (
 operation_id BIGINT NOT NULL,
 item_index INT NOT NULL,
 path VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 entry_type VARCHAR(16) NOT NULL,
 status VARCHAR(16) NOT NULL,
 code VARCHAR(64) NULL,
 error VARCHAR(1000) NULL,
 PRIMARY KEY(operation_id,item_index),
 CONSTRAINT fk_workspace_file_item_operation FOREIGN KEY(operation_id) REFERENCES workspace_file_operation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE conversation_attachment
 ADD COLUMN workspace_location_state VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
 ADD COLUMN location_revision BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN last_file_operation_id BIGINT NULL,
 ADD KEY idx_attachment_file_location(project_id,last_file_operation_id,workspace_location_state);


-- Apply before deploying the Skill batch import server.
CREATE TABLE IF NOT EXISTS skill_catalog_lock (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT IGNORE INTO skill_catalog_lock(id) VALUES(1);

CREATE TABLE IF NOT EXISTS skill_import_record (
    id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    kind VARCHAR(16) NOT NULL,
    owner_id BIGINT UNSIGNED NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    KEY idx_skill_import_owner (owner_id,kind),
    KEY idx_skill_import_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Skill assignment changes expert drafts; there is no device installation registry.
CREATE TABLE skill_expert_assignment_batch (
 id VARCHAR(36) PRIMARY KEY, owner_id BIGINT UNSIGNED NOT NULL,
 skill_id BIGINT UNSIGNED NOT NULL, version_id BIGINT UNSIGNED NOT NULL,
 payload JSON NOT NULL, started TINYINT NOT NULL DEFAULT 0,
 expires_at DATETIME(3) NOT NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY idx_skill_assignment_owner(owner_id,created_at),
 FOREIGN KEY(owner_id) REFERENCES sys_user(id), FOREIGN KEY(skill_id) REFERENCES skill(id),
 FOREIGN KEY(version_id) REFERENCES skill_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE skill_expert_assignment_item (
 batch_id VARCHAR(36) NOT NULL, expert_id BIGINT UNSIGNED NOT NULL, payload JSON NOT NULL,
 PRIMARY KEY(batch_id,expert_id), FOREIGN KEY(batch_id) REFERENCES skill_expert_assignment_batch(id) ON DELETE CASCADE,
 FOREIGN KEY(expert_id) REFERENCES expert(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
