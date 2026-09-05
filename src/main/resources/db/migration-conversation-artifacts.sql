-- Apply to an existing database before starting the upgraded Server.
CREATE TABLE IF NOT EXISTS conversation_artifact (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT UNSIGNED NOT NULL, project_id BIGINT UNSIGNED NOT NULL,
 conversation_id BIGINT UNSIGNED NOT NULL, turn_id BIGINT UNSIGNED NOT NULL, device_id BIGINT UNSIGNED NOT NULL,
 artifact_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 file_name VARCHAR(255) NOT NULL, storage_key VARCHAR(64) NOT NULL,
 media_type VARCHAR(128) NOT NULL, size_bytes BIGINT UNSIGNED NOT NULL, sha256 CHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'UPLOADING', error_message VARCHAR(255) NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_artifact_turn_key (turn_id,artifact_key),
 UNIQUE KEY uk_artifact_storage (storage_key),
 KEY idx_artifact_conversation (conversation_id,id), KEY idx_artifact_recovery (status,updated_at),
 CONSTRAINT fk_artifact_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id),
 CONSTRAINT fk_artifact_turn FOREIGN KEY (turn_id) REFERENCES conversation_turn(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
