-- MySQL 8; select the target database in the SQL client before running.
-- 前置条件：已执行 migration-workspace-files.sql；停用旧 Server/Agent 并备份数据库。
-- 不包含 USE，避免覆盖 SQL 客户端当前选择的数据库；可重复执行。
-- 删除表：conversation_artifact（包括所有历史产物元数据）。
-- 清理数据：无 workspace_path / workspace_operation_id 的旧附件及消息关联。
-- 更新数据：解除上述旧附件对 workspace_file_operation 的引用，终止未完成操作。
-- 保留表：conversation_attachment、conversation_message_attachment、workspace_file_operation。
-- 保留字段：agent_device.conversation_attachments（当前消息附件能力仍使用）。
-- 保留所有 Conversation / Turn / Message 正文及有效的 Workspace 附件关联。
-- 本脚本不操作磁盘文件；DROP TABLE 属于 DDL，提交后不能使用 ROLLBACK 撤销。

-- 执行前核对影响范围；storage_key 可用于核对退役的平台传输文件。
SELECT DATABASE() AS target_database;
SELECT id, conversation_id, storage_key, status
FROM conversation_attachment
WHERE workspace_path IS NULL OR workspace_path='' OR workspace_operation_id IS NULL;
SELECT COUNT(*) AS workspace_attachments_to_keep
FROM conversation_attachment
WHERE workspace_path IS NOT NULL AND workspace_path<>'' AND workspace_operation_id IS NOT NULL;

START TRANSACTION;
UPDATE workspace_file_operation op
JOIN conversation_attachment a ON a.id=op.attachment_id
SET op.attachment_id=NULL,
    op.error=CASE WHEN op.status IN ('QUEUED','RUNNING') THEN 'Legacy attachment retired' ELSE op.error END,
    op.status=CASE WHEN op.status IN ('QUEUED','RUNNING') THEN 'FAILED' ELSE op.status END
WHERE a.workspace_path IS NULL OR a.workspace_path='' OR a.workspace_operation_id IS NULL;
DELETE ma FROM conversation_message_attachment ma
JOIN conversation_attachment a ON a.id=ma.attachment_id
WHERE a.workspace_path IS NULL OR a.workspace_path='' OR a.workspace_operation_id IS NULL;
DELETE FROM conversation_attachment
WHERE workspace_path IS NULL OR workspace_path='' OR workspace_operation_id IS NULL;
COMMIT;
DROP TABLE IF EXISTS conversation_artifact;

-- 执行后验收：两个数量都应为 0。
SELECT COUNT(*) AS remaining_artifact_tables FROM information_schema.TABLES
WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='conversation_artifact';
SELECT COUNT(*) AS remaining_legacy_attachments FROM conversation_attachment
WHERE workspace_path IS NULL OR workspace_path='' OR workspace_operation_id IS NULL;
