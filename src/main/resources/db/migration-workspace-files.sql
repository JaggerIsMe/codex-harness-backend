-- Apply once before deploying the upgraded server and Agent.
ALTER TABLE agent_device ADD COLUMN workspace_files TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE conversation_attachment ADD COLUMN workspace_path VARCHAR(2048) NULL,
    ADD COLUMN workspace_operation_id BIGINT NULL;
CREATE TABLE workspace_file_operation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_key VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    device_id BIGINT NOT NULL,
    workspace_name VARCHAR(64) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    path VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    page_cursor VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL,
    storage_key VARCHAR(36) NULL,
    sha256 VARCHAR(64) NULL,
    size_bytes BIGINT NOT NULL DEFAULT 0,
    attachment_id BIGINT NULL,
    error VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_workspace_operation_request(project_id,request_key),
    KEY idx_workspace_operation_queue(status,id),
    KEY idx_workspace_operation_project(project_id,id)
);
