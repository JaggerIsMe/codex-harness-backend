-- Repeatable: add name editing/deletion permissions to roles that already create the same resource.
-- Run before deploying the new server; this script changes permissions only.
START TRANSACTION;

INSERT IGNORE INTO sys_permission(permission_code,permission_name) VALUES
 ('project:update','修改个人项目'),('project:delete','删除个人项目'),
 ('conversation:update','修改个人会话'),('conversation:delete','删除个人会话');

INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
 SELECT r.id,p.id FROM sys_role r CROSS JOIN sys_permission p
 WHERE p.permission_code IN ('project:update','project:delete','conversation:update','conversation:delete')
 AND r.role_code IN ('SYS_ADMIN','USER');

INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
 SELECT existing.role_id,target.id FROM sys_role_permission existing
 JOIN sys_permission source ON source.id=existing.permission_id
 JOIN sys_permission target ON
   (source.permission_code='project:create' AND target.permission_code IN ('project:update','project:delete'))
   OR (source.permission_code='conversation:create' AND target.permission_code IN ('conversation:update','conversation:delete'));

COMMIT;
