USE harness;
-- Managed provider accounting only; UTC timestamps, Asia/Shanghai budget periods.
CREATE TABLE IF NOT EXISTS model_price_version (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 model_version_id BIGINT UNSIGNED NOT NULL,
 currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
 rule VARCHAR(40) NOT NULL DEFAULT 'RESPONSES_TEXT_V1',
 input_rate DECIMAL(20,8) NOT NULL, cached_rate DECIMAL(20,8) NOT NULL, output_rate DECIMAL(20,8) NOT NULL,
 max_output_tokens INT NOT NULL, created_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY idx_price_version(model_version_id,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS user_quota_policy (
 user_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 daily_budget DECIMAL(24,12) NULL, monthly_budget DECIMAL(24,12) NULL,
 max_concurrent_turns INT NOT NULL DEFAULT 100
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS user_quota_bucket (
 user_id BIGINT UNSIGNED NOT NULL, period VARCHAR(10) NOT NULL,
 spent DECIMAL(24,12) NOT NULL DEFAULT 0, reserved DECIMAL(24,12) NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id,period)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS model_usage_record (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 user_id BIGINT UNSIGNED NOT NULL, turn_id BIGINT UNSIGNED NOT NULL, device_id BIGINT UNSIGNED NOT NULL,
 project_id BIGINT UNSIGNED NULL, conversation_id BIGINT UNSIGNED NULL, expert_version_id BIGINT UNSIGNED NULL,
 model_version_id BIGINT UNSIGNED NOT NULL, price_version_id BIGINT UNSIGNED NOT NULL,
 state VARCHAR(16) NOT NULL, reserved_amount DECIMAL(24,12) NOT NULL, cost DECIMAL(24,12) NULL,
 day_period VARCHAR(10) NOT NULL, month_period VARCHAR(7) NOT NULL,
 input_tokens BIGINT NULL, cached_tokens BIGINT NULL, output_tokens BIGINT NULL, reasoning_tokens BIGINT NULL,
 response_id VARCHAR(200) NULL, actual_model VARCHAR(128) NULL, outcome VARCHAR(32) NULL,
 created_at DATETIME(3) NOT NULL, settled_at DATETIME(3) NULL,
 KEY idx_usage_user_time(user_id,created_at), KEY idx_usage_turn(turn_id), KEY idx_usage_state(state,created_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS user_quota_ledger (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL, user_id BIGINT UNSIGNED NOT NULL,
 action VARCHAR(24) NOT NULL, amount DECIMAL(24,12) NOT NULL,
 operator_id BIGINT UNSIGNED NULL, reason VARCHAR(500) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY idx_ledger_request(request_id), KEY idx_ledger_user(user_id,created_at)
) ENGINE=InnoDB;
