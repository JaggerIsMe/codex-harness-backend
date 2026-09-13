-- 在 migration-orchestration.sql 之后执行；保留已有执行历史。
-- 控制节点无 Expert，仅由中台判断条件。
ALTER TABLE orchestration_step MODIFY expert_id BIGINT UNSIGNED NULL;
