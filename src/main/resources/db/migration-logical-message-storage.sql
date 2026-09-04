-- Apply once after backup, with active Turns drained and all writers stopped.
-- Existing rows remain untouched; NULL message_key deliberately preserves legacy history.
ALTER TABLE conversation_message
    ADD COLUMN message_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN item_id VARCHAR(128) NULL,
    ADD COLUMN phase VARCHAR(32) NULL,
    ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN metadata LONGTEXT NULL,
    ADD COLUMN truncated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN updated_at DATETIME(3) NULL,
    ADD COLUMN completed_at DATETIME(3) NULL,
    ADD UNIQUE KEY uk_conversation_message_key (conversation_id, message_key);

ALTER TABLE agent_event_message ADD KEY idx_agent_event_created (created_at);
