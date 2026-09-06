-- Apply after migration-experts.sql, before deploying the V2 server and Agent.
ALTER TABLE conversation ADD COLUMN expert_runtime_key CHAR(64) NULL COMMENT '当前 Codex 线程的专家运行配置标识';
-- Old online Agents must reconnect and advertise CONVERSATION_EXPERTS_V2.
UPDATE agent_device SET project_experts = 0;
