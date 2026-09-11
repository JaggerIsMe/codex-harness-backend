-- MySQL 8：清空 harness 当前全部表，保留表结构并重置自增 ID，恢复 Skill 目录锁哨兵行。
-- 按 2026-09-12 最终 schema 表清单生成；后续新增表需同步补充。
-- 包含用户、管理员、角色、权限、设备、配置、会话和文件元数据。
-- 执行前停止 Server/Agent 写入并备份；TRUNCATE 隐式提交，不能 ROLLBACK。
-- 本脚本不恢复角色/权限等初始化数据，不删除磁盘文件，也不清理 Redis。
-- 仅用于最终邮箱 schema；旧结构仅清空数据不能升级为邮箱结构。
-- 重装还需轮换 JWT 密钥并清理旧 Redis 凭证命名空间，避免 ID 复用后旧凭证恢复。
-- 清空 system_initialization 会允许重新初始化管理员，这是全系统重装语义。
-- 清空后、启动 Server 前，先执行同目录 seed-rbac.sql 恢复内置角色权限；bootstrap-admin 再创建管理员。
-- 请在同一数据库连接中执行完整脚本；最后恢复该连接原有的外键检查设置。

USE `harness`;
SET @harness_saved_foreign_key_checks = @@SESSION.FOREIGN_KEY_CHECKS;
SET SESSION FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE `harness`.`skill_expert_assignment_item`;
TRUNCATE TABLE `harness`.`skill_expert_assignment_batch`;

TRUNCATE TABLE `harness`.`account_email_challenge`;
TRUNCATE TABLE `harness`.`agent_device`;
TRUNCATE TABLE `harness`.`agent_enrollment`;
TRUNCATE TABLE `harness`.`agent_event_message`;
TRUNCATE TABLE `harness`.`agent_workspace`;
TRUNCATE TABLE `harness`.`agent_workspace_root`;
TRUNCATE TABLE `harness`.`approval_request`;
TRUNCATE TABLE `harness`.`audit_log`;
TRUNCATE TABLE `harness`.`codex_project`;
TRUNCATE TABLE `harness`.`conversation`;
TRUNCATE TABLE `harness`.`conversation_attachment`;
TRUNCATE TABLE `harness`.`conversation_message`;
TRUNCATE TABLE `harness`.`conversation_message_attachment`;
TRUNCATE TABLE `harness`.`conversation_turn`;
TRUNCATE TABLE `harness`.`device_model_assignment`;
TRUNCATE TABLE `harness`.`expert`;
TRUNCATE TABLE `harness`.`expert_version`;
TRUNCATE TABLE `harness`.`mail_delivery_task`;
TRUNCATE TABLE `harness`.`mcp_configuration`;
TRUNCATE TABLE `harness`.`mcp_configuration_version`;
TRUNCATE TABLE `harness`.`model_configuration`;
TRUNCATE TABLE `harness`.`model_configuration_version`;
TRUNCATE TABLE `harness`.`project_expert_binding`;
TRUNCATE TABLE `harness`.`skill`;
TRUNCATE TABLE `harness`.`skill_import_record`;
TRUNCATE TABLE `harness`.`skill_catalog_lock`;
INSERT INTO `harness`.`skill_catalog_lock` (id) VALUES (1);
TRUNCATE TABLE `harness`.`skill_version`;
TRUNCATE TABLE `harness`.`sys_permission`;
TRUNCATE TABLE `harness`.`sys_role`;
TRUNCATE TABLE `harness`.`sys_role_permission`;
TRUNCATE TABLE `harness`.`sys_user`;
TRUNCATE TABLE `harness`.`sys_user_role`;
TRUNCATE TABLE `harness`.`system_initialization`;
TRUNCATE TABLE `harness`.`user_device_assignment`;
TRUNCATE TABLE `harness`.`user_expert_assignment`;
TRUNCATE TABLE `harness`.`workspace_file_operation`;
TRUNCATE TABLE `harness`.`workspace_file_operation_item`;

SET SESSION FOREIGN_KEY_CHECKS = @harness_saved_foreign_key_checks;
SET @harness_saved_foreign_key_checks = NULL;
