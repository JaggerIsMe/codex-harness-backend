-- Apply once after migration-mcp.sql and migration-expert-runtime-isolation.sql.
ALTER TABLE agent_device ADD COLUMN managed_models TINYINT NOT NULL DEFAULT 0 AFTER expert_mcp;

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
 model_configuration_version_id BIGINT UNSIGNED NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0, assigned_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_device_model_assignment_device FOREIGN KEY(device_id) REFERENCES agent_device(id) ON DELETE CASCADE,
 CONSTRAINT fk_device_model_assignment_version FOREIGN KEY(model_configuration_version_id) REFERENCES model_configuration_version(id),
 CONSTRAINT fk_device_model_assignment_user FOREIGN KEY(assigned_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE conversation ADD COLUMN model_runtime_key CHAR(64) NULL AFTER expert_runtime_key;
ALTER TABLE conversation_turn
 ADD COLUMN model_configuration_version_id BIGINT UNSIGNED NULL AFTER expert_runtime,
 ADD COLUMN model_name VARCHAR(128) NULL AFTER model_configuration_version_id,
 ADD COLUMN model_runtime MEDIUMTEXT NULL AFTER model_name,
 ADD CONSTRAINT fk_turn_model_version FOREIGN KEY(model_configuration_version_id) REFERENCES model_configuration_version(id);

INSERT IGNORE INTO sys_permission(permission_code,permission_name) VALUES ('model:manage','模型配置与 Device 模型分配');
INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
 SELECT r.id,p.id FROM sys_role r CROSS JOIN sys_permission p
 WHERE r.role_code='SYS_ADMIN' AND p.permission_code='model:manage';
