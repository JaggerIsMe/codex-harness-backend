-- MySQL 8：清空 harness 当前全部 32 张业务表，保留表结构并重置自增 ID。
-- 按 2026-09-09 实际数据库表清单生成；后续新增表需同步补充。
-- 包含用户、管理员、角色、权限、设备、配置、会话和文件元数据。
-- 执行前停止 Server/Agent 写入并备份；TRUNCATE 隐式提交，不能 ROLLBACK。
-- 本脚本不恢复角色/权限等初始化数据，不删除磁盘文件，也不清理 Redis。
-- 清空后、启动 Server 前，先执行同目录 seed-rbac.sql 恢复内置角色权限；bootstrap-admin 再创建管理员。
-- 请在同一数据库连接中执行完整脚本；最后恢复该连接原有的外键检查设置。

USE `harness`;
SET @harness_saved_foreign_key_checks = @@SESSION.FOREIGN_KEY_CHECKS;
SET SESSION FOREIGN_KEY_CHECKS = 0;

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
TRUNCATE TABLE `harness`.`device_skill`;
TRUNCATE TABLE `harness`.`expert`;
TRUNCATE TABLE `harness`.`expert_version`;
TRUNCATE TABLE `harness`.`mcp_configuration`;
TRUNCATE TABLE `harness`.`mcp_configuration_version`;
TRUNCATE TABLE `harness`.`model_configuration`;
TRUNCATE TABLE `harness`.`model_configuration_version`;
TRUNCATE TABLE `harness`.`project_expert_binding`;
TRUNCATE TABLE `harness`.`skill`;
TRUNCATE TABLE `harness`.`skill_version`;
TRUNCATE TABLE `harness`.`sys_permission`;
TRUNCATE TABLE `harness`.`sys_role`;
TRUNCATE TABLE `harness`.`sys_role_permission`;
TRUNCATE TABLE `harness`.`sys_user`;
TRUNCATE TABLE `harness`.`sys_user_role`;
TRUNCATE TABLE `harness`.`user_device_assignment`;
TRUNCATE TABLE `harness`.`user_expert_assignment`;
TRUNCATE TABLE `harness`.`workspace_file_operation`;

SET SESSION FOREIGN_KEY_CHECKS = @harness_saved_foreign_key_checks;
SET @harness_saved_foreign_key_checks = NULL;
