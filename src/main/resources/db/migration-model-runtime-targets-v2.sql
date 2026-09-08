-- Explicit Device Model Runtime Targets. Apply after migration-managed-models.sql.
ALTER TABLE agent_device
 ADD COLUMN model_runtime_targets TINYINT NOT NULL DEFAULT 0 AFTER managed_models;

ALTER TABLE device_model_assignment
 ADD COLUMN runtime_mode VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MANAGED_PROVIDER' AFTER device_id,
 MODIFY COLUMN model_configuration_version_id BIGINT UNSIGNED NULL,
 ADD CONSTRAINT chk_device_model_runtime_target CHECK (
  (runtime_mode='LOCAL_CODEX' AND model_configuration_version_id IS NULL) OR
  (runtime_mode='MANAGED_PROVIDER' AND model_configuration_version_id IS NOT NULL)
 );
