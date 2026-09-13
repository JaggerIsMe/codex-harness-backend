-- 多 Expert 编排 V1。先执行迁移，再启用 harness.orchestration.enabled。
CREATE TABLE IF NOT EXISTS orchestration_execution (
 id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
 project_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL, device_id BIGINT UNSIGNED NOT NULL,
 title VARCHAR(120) NOT NULL, goal TEXT NOT NULL,
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) NOT NULL, plan_json JSON NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'QUEUED', failure_message VARCHAR(1000), cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_orchestration_request(user_id,request_key),
 KEY idx_orchestration_schedule(status,id), KEY idx_orchestration_project(project_id,id),
 CONSTRAINT fk_orchestration_project FOREIGN KEY(project_id) REFERENCES codex_project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS orchestration_step (
 id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT, execution_id BIGINT UNSIGNED NOT NULL,
 position INT NOT NULL, name VARCHAR(80) NOT NULL, expert_id BIGINT UNSIGNED NOT NULL,
 objective TEXT NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
 conversation_id BIGINT UNSIGNED, turn_id BIGINT UNSIGNED,
 input_snapshot MEDIUMTEXT, result_json JSON, failure_message VARCHAR(1000), terminal_status VARCHAR(32),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_orchestration_step(execution_id,position),
 UNIQUE KEY uk_orchestration_conversation(conversation_id), UNIQUE KEY uk_orchestration_turn(turn_id),
 CONSTRAINT fk_orchestration_step_execution FOREIGN KEY(execution_id) REFERENCES orchestration_execution(id) ON DELETE CASCADE,
 CONSTRAINT fk_orchestration_step_conversation FOREIGN KEY(conversation_id) REFERENCES conversation(id) ON DELETE SET NULL,
 CONSTRAINT fk_orchestration_step_turn FOREIGN KEY(turn_id) REFERENCES conversation_turn(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
