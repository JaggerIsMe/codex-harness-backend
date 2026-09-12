-- Select the Harness database and run before deploying the Skill tag release.
-- Existing Skills receive an empty tag. Safe to rerun; no version data changes.
SET @skill_tag_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'skill' AND COLUMN_NAME = 'tag'),
    'SELECT 1',
    'ALTER TABLE skill ADD COLUMN tag VARCHAR(200) NOT NULL DEFAULT '''' COMMENT ''User-entered Skill tag or remark'' AFTER description'
);
PREPARE skill_tag_statement FROM @skill_tag_ddl;
EXECUTE skill_tag_statement;
DEALLOCATE PREPARE skill_tag_statement;
