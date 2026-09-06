-- Apply once after migration-expert-compatible-upgrade.sql and before deploying MCP-capable Agents.
ALTER TABLE agent_device ADD COLUMN expert_mcp TINYINT NOT NULL DEFAULT 0 AFTER project_experts;

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
ALTER TABLE expert ADD COLUMN mcp_version_ids TEXT NULL AFTER skill_version_ids;
UPDATE expert SET mcp_version_ids='[]' WHERE mcp_version_ids IS NULL;
ALTER TABLE expert MODIFY COLUMN mcp_version_ids TEXT NOT NULL;
ALTER TABLE expert_version ADD COLUMN mcp_version_ids TEXT NULL AFTER skill_version_ids;
UPDATE expert_version SET mcp_version_ids='[]' WHERE mcp_version_ids IS NULL;
ALTER TABLE expert_version MODIFY COLUMN mcp_version_ids TEXT NOT NULL;

INSERT IGNORE INTO sys_permission(permission_code,permission_name) VALUES ('mcp:manage','MCP 配置管理');
INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
 SELECT r.id,p.id FROM sys_role r CROSS JOIN sys_permission p
 WHERE r.role_code='SYS_ADMIN' AND p.permission_code='mcp:manage';
