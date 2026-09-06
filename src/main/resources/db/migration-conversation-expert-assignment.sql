-- Apply once after migration-expert-runtime-isolation.sql and before deploying this release.
ALTER TABLE conversation
    ADD COLUMN selected_expert_version_id BIGINT UNSIGNED NULL AFTER selected_expert_id;

UPDATE conversation c
JOIN project_expert_binding b ON b.project_id=c.project_id AND b.expert_id=c.selected_expert_id
SET c.selected_expert_version_id=b.expert_version_id
WHERE c.selected_expert_id IS NOT NULL AND c.selected_expert_version_id IS NULL;

ALTER TABLE conversation ADD CONSTRAINT fk_conversation_expert_version
    FOREIGN KEY(selected_expert_version_id) REFERENCES expert_version(id);

CREATE TABLE user_expert_assignment (
 user_id BIGINT UNSIGNED NOT NULL, expert_id BIGINT UNSIGNED NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ENABLED', assigned_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(user_id,expert_id), KEY idx_user_expert_expert(expert_id,status),
 CONSTRAINT fk_user_expert_user FOREIGN KEY(user_id) REFERENCES sys_user(id),
 CONSTRAINT fk_user_expert_expert FOREIGN KEY(expert_id) REFERENCES expert(id),
 CONSTRAINT fk_user_expert_assigner FOREIGN KEY(assigned_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
