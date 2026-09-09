-- MySQL 8：将已停用写入的 newharness 迁移为 harness；目标库必须不存在。
-- 本地数据库已于 2026-09-09 完成迁移并删除空旧库，此文件留作其他环境迁移参考，勿在本地重复执行。
-- 2026-09-09 实际表清单（32 张 InnoDB 表）；执行前备份并检查无视图/触发器/存储过程/事件/跨库依赖。
-- 使用一个 RENAME TABLE 语句整体迁移，保留数据、自增值、索引及外键。
-- 任一步报错即停止；不要在客户端启用忽略错误继续执行。
-- 文档：https://dev.mysql.com/doc/refman/8.0/en/rename-table.html
SET SESSION lock_wait_timeout = 15;
CREATE DATABASE `harness` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
RENAME TABLE
    `newharness`.`agent_device` TO `harness`.`agent_device`,
    `newharness`.`agent_enrollment` TO `harness`.`agent_enrollment`,
    `newharness`.`agent_event_message` TO `harness`.`agent_event_message`,
    `newharness`.`agent_workspace` TO `harness`.`agent_workspace`,
    `newharness`.`agent_workspace_root` TO `harness`.`agent_workspace_root`,
    `newharness`.`approval_request` TO `harness`.`approval_request`,
    `newharness`.`audit_log` TO `harness`.`audit_log`,
    `newharness`.`codex_project` TO `harness`.`codex_project`,
    `newharness`.`conversation` TO `harness`.`conversation`,
    `newharness`.`conversation_attachment` TO `harness`.`conversation_attachment`,
    `newharness`.`conversation_message` TO `harness`.`conversation_message`,
    `newharness`.`conversation_message_attachment` TO `harness`.`conversation_message_attachment`,
    `newharness`.`conversation_turn` TO `harness`.`conversation_turn`,
    `newharness`.`device_model_assignment` TO `harness`.`device_model_assignment`,
    `newharness`.`device_skill` TO `harness`.`device_skill`,
    `newharness`.`expert` TO `harness`.`expert`,
    `newharness`.`expert_version` TO `harness`.`expert_version`,
    `newharness`.`mcp_configuration` TO `harness`.`mcp_configuration`,
    `newharness`.`mcp_configuration_version` TO `harness`.`mcp_configuration_version`,
    `newharness`.`model_configuration` TO `harness`.`model_configuration`,
    `newharness`.`model_configuration_version` TO `harness`.`model_configuration_version`,
    `newharness`.`project_expert_binding` TO `harness`.`project_expert_binding`,
    `newharness`.`skill` TO `harness`.`skill`,
    `newharness`.`skill_version` TO `harness`.`skill_version`,
    `newharness`.`sys_permission` TO `harness`.`sys_permission`,
    `newharness`.`sys_role` TO `harness`.`sys_role`,
    `newharness`.`sys_role_permission` TO `harness`.`sys_role_permission`,
    `newharness`.`sys_user` TO `harness`.`sys_user`,
    `newharness`.`sys_user_role` TO `harness`.`sys_user_role`,
    `newharness`.`user_device_assignment` TO `harness`.`user_device_assignment`,
    `newharness`.`user_expert_assignment` TO `harness`.`user_expert_assignment`,
    `newharness`.`workspace_file_operation` TO `harness`.`workspace_file_operation`;

-- 验证所有表和外键已转入 harness，再清理空的原库。
SELECT TABLE_SCHEMA,COUNT(*) AS table_count FROM information_schema.TABLES
WHERE TABLE_SCHEMA IN ('newharness','harness') GROUP BY TABLE_SCHEMA;
SELECT COUNT(*) AS old_foreign_key_references FROM information_schema.KEY_COLUMN_USAGE
WHERE REFERENCED_TABLE_SCHEMA='newharness';
-- 核对逐表数据/校验和一致，且旧库没有任何对象后，单独执行：
-- DROP DATABASE `newharness`;
