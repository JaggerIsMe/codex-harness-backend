-- Apply once after the existing RBAC and message attachment migrations.
ALTER TABLE agent_device ADD COLUMN project_experts TINYINT NOT NULL DEFAULT 0;
ALTER TABLE codex_project ADD COLUMN expert_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE conversation ADD COLUMN selected_expert_id BIGINT UNSIGNED NULL,
    ADD COLUMN expert_selection_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE conversation_turn ADD COLUMN expert_version_id BIGINT UNSIGNED NULL,
    ADD COLUMN expert_name VARCHAR(128) NULL, ADD COLUMN expert_runtime MEDIUMTEXT NULL;

CREATE TABLE expert (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(128) NOT NULL, description VARCHAR(2000) NOT NULL DEFAULT '',
 system_prompt MEDIUMTEXT NOT NULL, skill_version_ids TEXT NOT NULL,
 compatible_upgrade TINYINT NOT NULL DEFAULT 0,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', published_version_id BIGINT UNSIGNED NULL,
 revision BIGINT NOT NULL DEFAULT 0, created_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_expert_creator FOREIGN KEY(created_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE expert_version (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, expert_id BIGINT UNSIGNED NOT NULL,
 version_no BIGINT NOT NULL, name VARCHAR(128) NOT NULL, description VARCHAR(2000) NOT NULL,
 system_prompt MEDIUMTEXT NOT NULL, skill_version_ids TEXT NOT NULL,
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
INSERT IGNORE INTO sys_permission(permission_code,permission_name) VALUES
 ('expert:manage','专家管理与发布'),('expert:read','专家市场'),('expert:use','项目专家使用');
INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
 SELECT r.id,p.id FROM sys_role r CROSS JOIN sys_permission p
 WHERE (r.role_code='SYS_ADMIN' AND p.permission_code IN ('expert:manage','expert:read','expert:use'))
 OR (r.role_code='USER' AND p.permission_code IN ('expert:read','expert:use'));
