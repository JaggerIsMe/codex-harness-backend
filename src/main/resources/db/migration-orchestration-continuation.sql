-- Apply once before deploying the Server. Existing completed executions remain immutable.
ALTER TABLE orchestration_step ADD COLUMN checkpoint_json JSON NULL;
CREATE TABLE IF NOT EXISTS orchestration_step_turn (
 step_id BIGINT UNSIGNED NOT NULL, turn_id BIGINT UNSIGNED NOT NULL,
 checkpoint_json JSON NULL,
 PRIMARY KEY(turn_id), KEY idx_step_turn(step_id,turn_id),
 CONSTRAINT fk_step_turn_step FOREIGN KEY(step_id) REFERENCES orchestration_step(id) ON DELETE CASCADE,
 CONSTRAINT fk_step_turn_turn FOREIGN KEY(turn_id) REFERENCES conversation_turn(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO orchestration_step_turn(step_id,turn_id)
 SELECT id,turn_id FROM orchestration_step WHERE turn_id IS NOT NULL;
