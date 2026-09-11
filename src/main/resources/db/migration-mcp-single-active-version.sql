-- Apply once before deploying the single-active MCP Configuration Version release.
-- Revoke historical versions; preserve the current version's existing status.
-- Never reactivate a version that an administrator already revoked.
START TRANSACTION;

UPDATE codex_project project
JOIN (
    SELECT binding.project_id
    FROM project_expert_binding binding
    JOIN expert_version expert ON expert.id = binding.expert_version_id
    JOIN mcp_configuration_version version ON JSON_CONTAINS(expert.mcp_version_ids, CAST(version.id AS JSON), '$')
    JOIN mcp_configuration configuration ON configuration.id = version.mcp_configuration_id
    WHERE version.status = 'ACTIVE' AND version.id <> configuration.current_version_id
    UNION
    SELECT conversation.project_id
    FROM conversation
    JOIN expert_version expert ON expert.id = conversation.selected_expert_version_id
    JOIN mcp_configuration_version version ON JSON_CONTAINS(expert.mcp_version_ids, CAST(version.id AS JSON), '$')
    JOIN mcp_configuration configuration ON configuration.id = version.mcp_configuration_id
    WHERE version.status = 'ACTIVE' AND version.id <> configuration.current_version_id
) impacted ON impacted.project_id = project.id
SET project.expert_revision = project.expert_revision + 1;

UPDATE mcp_configuration_version version
JOIN mcp_configuration configuration ON configuration.id = version.mcp_configuration_id
SET version.status = 'REVOKED'
WHERE version.status = 'ACTIVE' AND version.id <> configuration.current_version_id;

COMMIT;
