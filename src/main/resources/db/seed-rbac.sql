-- MySQL 8.0.19+：harness 完整内置角色、权限和角色权限预置。
-- 用途：清空业务数据后、启动 Server 前执行。
-- 前置条件：sys_role / sys_permission / sys_role_permission 表及其唯一索引已存在。
-- 可重复执行；通过角色码/权限码查找 ID，不依赖自增值，也不执行 TRUNCATE 或 ALTER。
-- 补齐并启用 SYS_ADMIN、USER；补齐 20 项权限及 34 条内置角色权限关联。
-- 只操作角色、权限及角色权限关联，不读取或创建用户，不依赖 username/email 字段。
-- 不删除自定义角色、权限及已有授权；管理员账号由应用的初始化流程创建。
-- 在同一连接中执行，遇错停止并 ROLLBACK；不要使用 mysql --force。

USE `harness`;
SET NAMES utf8mb4;

START TRANSACTION;

INSERT INTO sys_role(role_code, role_name, status, built_in) VALUES
    ('SYS_ADMIN', '管理员', 'ENABLED', TRUE),
    ('USER', '普通用户', 'ENABLED', TRUE)
AS preset
ON DUPLICATE KEY UPDATE
    role_name = preset.role_name,
    status = preset.status,
    built_in = preset.built_in;

INSERT INTO sys_permission(permission_code, permission_name) VALUES
    ('system:user:manage', '用户与角色管理'),
    ('device:manage', '机器与执行目录管理'),
    ('skill:manage', '平台 Skills 管理'),
    ('workspace:use', '使用已分配机器'),
    ('project:create', '创建个人项目'),
    ('project:read', '读取个人项目'),
    ('project:update', '修改个人项目'),
    ('project:delete', '删除个人项目'),
    ('conversation:create', '创建个人会话'),
    ('conversation:read', '读取个人会话'),
    ('conversation:update', '修改个人会话'),
    ('conversation:delete', '删除个人会话'),
    ('turn:start', '启动 Turn'),
    ('turn:interrupt', '中断 Turn'),
    ('approval:decide', '处理个人审批'),
    ('expert:manage', '专家管理与发布'),
    ('expert:read', '专家市场'),
    ('expert:use', '项目专家使用'),
    ('mcp:manage', 'MCP 配置管理'),
    ('model:manage', '模型配置与 Device 模型分配')
AS preset
ON DUPLICATE KEY UPDATE permission_name = preset.permission_name;

-- 管理员：全部 20 项已定义的系统权限，不自动授予未知自定义权限。
INSERT INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p
WHERE r.role_code = 'SYS_ADMIN'
  AND p.permission_code IN (
    'system:user:manage', 'device:manage', 'skill:manage', 'workspace:use',
    'project:create', 'project:read', 'conversation:create', 'conversation:read',
    'project:update', 'project:delete', 'conversation:update', 'conversation:delete',
    'turn:start', 'turn:interrupt', 'approval:decide',
    'expert:manage', 'expert:read', 'expert:use', 'mcp:manage', 'model:manage'
  )
ON DUPLICATE KEY UPDATE permission_id = sys_role_permission.permission_id;

-- 普通用户：14 项使用权限；不新增用户/设备/Skills/专家/MCP/模型管理权限。
INSERT INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p
WHERE r.role_code = 'USER'
  AND p.permission_code IN (
    'workspace:use', 'project:create', 'project:read',
    'conversation:create', 'conversation:read', 'turn:start', 'turn:interrupt',
    'project:update', 'project:delete', 'conversation:update', 'conversation:delete',
    'approval:decide', 'expert:read', 'expert:use'
  )
ON DUPLICATE KEY UPDATE permission_id = sys_role_permission.permission_id;

COMMIT;

-- 验收：干净数据库应为 SYS_ADMIN=20、USER=14；原有自定义授权也会显示。
SELECT r.role_code, r.role_name, r.status, r.built_in, COUNT(rp.permission_id) AS permission_count
FROM sys_role r LEFT JOIN sys_role_permission rp ON rp.role_id = r.id
WHERE r.role_code IN ('SYS_ADMIN', 'USER')
GROUP BY r.id, r.role_code, r.role_name, r.status, r.built_in ORDER BY r.role_code;

SELECT r.role_code, p.permission_code, p.permission_name
FROM sys_role r JOIN sys_role_permission rp ON rp.role_id = r.id
JOIN sys_permission p ON p.id = rp.permission_id
WHERE r.role_code IN ('SYS_ADMIN', 'USER') ORDER BY r.role_code, p.permission_code;

