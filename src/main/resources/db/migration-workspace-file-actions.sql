-- Apply once after migration-workspace-files.sql and migration-retire-legacy-files.sql.
-- Additive migration only. No file operations or data cleanup are performed by this script.
ALTER TABLE agent_device
 ADD COLUMN workspace_file_mutations TINYINT(1) NOT NULL DEFAULT 0,
 ADD COLUMN workspace_archive_download TINYINT(1) NOT NULL DEFAULT 0,
 ADD COLUMN workspace_file_limits TEXT NULL;

ALTER TABLE workspace_file_operation
 ADD COLUMN target_path VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 ADD COLUMN request_digest CHAR(64) NULL,
 ADD COLUMN payload_json MEDIUMTEXT NULL,
 ADD COLUMN result_json MEDIUMTEXT NULL,
 ADD COLUMN code VARCHAR(64) NULL,
 ADD COLUMN content_state VARCHAR(16) NOT NULL DEFAULT 'NONE',
 ADD COLUMN plan_id VARCHAR(36) NULL,
 ADD COLUMN delete_plan_operation_id BIGINT NULL,
 ADD COLUMN attachment_count BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN items_digest CHAR(64) NULL,
 ADD UNIQUE KEY uk_workspace_delete_plan_consumer(delete_plan_operation_id),
 ADD KEY idx_workspace_mutation_gate(project_id,status,kind),
 ADD KEY idx_workspace_delete_plan(project_id,plan_id);
UPDATE workspace_file_operation SET content_state=CASE
 WHEN storage_key IS NOT NULL AND status='SUCCEEDED' THEN 'AVAILABLE'
 WHEN storage_key IS NOT NULL AND status IN ('QUEUED','RUNNING') THEN 'PENDING'
 WHEN kind='PREPARE_WORKSPACE_DOWNLOAD' AND status='EXPIRED' THEN 'EXPIRED'
 ELSE 'NONE' END;

CREATE TABLE IF NOT EXISTS workspace_file_operation_item (
 operation_id BIGINT NOT NULL,
 item_index INT NOT NULL,
 path VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 entry_type VARCHAR(16) NOT NULL,
 status VARCHAR(16) NOT NULL,
 code VARCHAR(64) NULL,
 error VARCHAR(1000) NULL,
 PRIMARY KEY(operation_id,item_index),
 CONSTRAINT fk_workspace_file_item_operation FOREIGN KEY(operation_id) REFERENCES workspace_file_operation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE conversation_attachment
 ADD COLUMN workspace_location_state VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
 ADD COLUMN location_revision BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN last_file_operation_id BIGINT NULL,
 ADD KEY idx_attachment_file_location(project_id,last_file_operation_id,workspace_location_state);
