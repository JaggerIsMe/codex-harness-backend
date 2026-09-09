-- Run once after existing migrations, with the application stopped and a database backup.
-- Replace NULL with the explicitly approved existing administrator's user id.
USE harness;
SET @rbac_admin_user_id = 1;
DROP PROCEDURE IF EXISTS assert_rbac_administrator;
DELIMITER //
CREATE PROCEDURE assert_rbac_administrator()
BEGIN
    IF @rbac_admin_user_id IS NULL OR NOT EXISTS(SELECT 1 FROM sys_user WHERE id=@rbac_admin_user_id AND status='ENABLED') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Set rbac_admin_user_id to an explicitly selected enabled administrator before migration';
    END IF;
END//
DELIMITER ;
CALL assert_rbac_administrator();
DROP PROCEDURE assert_rbac_administrator;
-- Do not run mysql with --force: validation must stop execution on error.
ALTER TABLE sys_user
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN password_changed_at DATETIME(3) NULL;
ALTER TABLE codex_project
    ADD COLUMN request_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_project_user_request(user_id,request_key);
ALTER TABLE codex_project ALTER COLUMN isolation_mode SET DEFAULT 'WINDOWS_PROJECT_PROFILE';
UPDATE codex_project SET isolation_mode='WINDOWS_PROJECT_PROFILE';
-- Device capability is not backfilled. Only the upgraded Agent may advertise the new mode.
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

INSERT IGNORE INTO sys_user_role(user_id,role_id)
SELECT u.id,r.id FROM sys_user u CROSS JOIN sys_role r
WHERE r.role_code=IF(u.id=@rbac_admin_user_id,'SYS_ADMIN','USER');
INSERT IGNORE INTO user_device_assignment(user_id,device_id,status,assigned_by)
SELECT DISTINCT user_id,device_id,'ENABLED',@rbac_admin_user_id FROM codex_project;
UPDATE sys_user SET token_version=token_version+1;
