-- Apply once to an existing database before starting the upgraded Server.
ALTER TABLE agent_device ADD COLUMN conversation_attachments TINYINT NOT NULL DEFAULT 0;
ALTER TABLE conversation_turn ADD COLUMN client_request_id VARCHAR(64) NULL,
    ADD COLUMN request_hash CHAR(64) NULL,
    ADD COLUMN preparation_phase VARCHAR(32) NULL,
    ADD UNIQUE KEY uk_turn_client_request (conversation_id,client_request_id);
CREATE TABLE IF NOT EXISTS conversation_attachment (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT UNSIGNED NOT NULL, project_id BIGINT UNSIGNED NOT NULL, conversation_id BIGINT UNSIGNED NOT NULL,
 file_name VARCHAR(255) NOT NULL, storage_key VARCHAR(64) NOT NULL,
 media_type VARCHAR(128) NOT NULL, size_bytes BIGINT UNSIGNED NOT NULL, sha256 CHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_attachment_storage (storage_key), KEY idx_attachment_pending (conversation_id,status),
 KEY idx_attachment_cleanup (status,created_at),
 CONSTRAINT fk_attachment_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS conversation_message_attachment (
 message_id BIGINT UNSIGNED NOT NULL, attachment_id BIGINT UNSIGNED NOT NULL, position INT NOT NULL,
 PRIMARY KEY(message_id,attachment_id), UNIQUE KEY uk_message_attachment_position(message_id,position),
 CONSTRAINT fk_ma_message FOREIGN KEY(message_id) REFERENCES conversation_message(id),
 CONSTRAINT fk_ma_attachment FOREIGN KEY(attachment_id) REFERENCES conversation_attachment(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
