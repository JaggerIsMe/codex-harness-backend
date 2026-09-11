package com.myharness.codex.service;

import com.myharness.codex.support.IsolatedMysql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "mysql.isolated.integration", matches = "true")
class SkillExpertAssignmentMigrationMysqlTest {
    private static final String MIGRATION = "db/migration-skill-expert-assignment.sql";
    private static final String DRAFT_ROWS = """
            SELECT id,name,description,system_prompt,skill_version_ids,mcp_version_ids,
                   status,published_version_id,revision,created_by,created_at,updated_at
            FROM expert ORDER BY id
            """;

    @Test
    void upgradesOldSchemaAndPreservesDraftsSnapshotsAndSubsequentWritesOnRerun() throws Exception {
        try (var mysql = IsolatedMysql.start()) {
            mysql.applyResource("db/schema.sql");
            mysql.execute("DROP TABLE skill_expert_assignment_item", "DROP TABLE skill_expert_assignment_batch",
                    "ALTER TABLE expert DROP COLUMN draft_changed",
                    "CREATE TABLE device_skill (id BIGINT PRIMARY KEY)");
            var jdbc = new JdbcTemplate(mysql.dataSource());
            seedUser(jdbc);
            String[] states = {"PUBLISHED", "DRAFT", "DISABLED", "PUBLISHED", "UNPUBLISHED", "PUBLISHED", "DRAFT", "DRAFT"};
            for (int i = 0; i < states.length; i++) {
                long id = i + 1;
                jdbc.update("""
                        INSERT INTO expert(id,name,description,system_prompt,skill_version_ids,mcp_version_ids,
                            status,revision,created_by,updated_at)
                        VALUES(?,'Expert','Description','Prompt','[11]','[21]',?,7,901,'2026-01-01 00:00:00.123')
                        """, id, states[i]);
                if (id <= 6) {
                    jdbc.update("""
                            INSERT INTO expert_version(id,expert_id,version_no,name,description,system_prompt,
                                skill_version_ids,mcp_version_ids)
                            SELECT id,id,1,name,description,system_prompt,skill_version_ids,mcp_version_ids
                            FROM expert WHERE id=?
                            """, id);
                    jdbc.update("UPDATE expert SET published_version_id=id WHERE id=?", id);
                }
            }
            mysql.execute("UPDATE expert SET name='expert' WHERE id=2",
                    "UPDATE expert SET system_prompt='Prompt ' WHERE id=3",
                    "UPDATE expert SET skill_version_ids='[12]' WHERE id=4",
                    "UPDATE expert SET mcp_version_ids='[22]' WHERE id=5",
                    "UPDATE expert SET description='Edited' WHERE id=6",
                    "UPDATE expert SET published_version_id=1 WHERE id=8");
            var drafts = jdbc.queryForList(DRAFT_ROWS);
            var snapshots = jdbc.queryForList("SELECT * FROM expert_version ORDER BY id");

            mysql.applyResource(MIGRATION);

            assertThat(jdbc.queryForList("SELECT draft_changed FROM expert ORDER BY id", Integer.class))
                    .containsExactly(0, 1, 1, 1, 1, 1, 1, 1);
            assertThat(jdbc.queryForList(DRAFT_ROWS)).isEqualTo(drafts);
            assertThat(jdbc.queryForList("SELECT * FROM expert_version ORDER BY id")).isEqualTo(snapshots);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema=DATABASE() AND table_name='device_skill'
                    """, Integer.class)).isZero();

            jdbc.update("UPDATE expert SET draft_changed=1 WHERE id=1");
            jdbc.update("UPDATE expert SET draft_changed=0 WHERE id=7");
            jdbc.update("INSERT INTO skill(id,skill_name,created_by) VALUES(11,'migration-fixture',901)");
            jdbc.update("""
                    INSERT INTO skill_version(id,skill_id,version,storage_path,sha256,file_size,created_by)
                    VALUES(11,11,'1','skill-storage/fixture.zip',REPEAT('a',64),1,901)
                    """);
            jdbc.update("""
                    INSERT INTO skill_expert_assignment_batch(id,owner_id,skill_id,version_id,payload,expires_at)
                    VALUES('migration-batch',901,11,11,'{}','2027-01-01')
                    """);
            jdbc.update("""
                    INSERT INTO skill_expert_assignment_item(batch_id,expert_id,payload)
                    VALUES('migration-batch',1,'{"status":"SUCCESS"}')
                    """);
            var batches = jdbc.queryForList("SELECT * FROM skill_expert_assignment_batch");
            var items = jdbc.queryForList("SELECT * FROM skill_expert_assignment_item");

            mysql.applyResource(MIGRATION);

            assertThat(jdbc.queryForList("SELECT draft_changed FROM expert ORDER BY id", Integer.class))
                    .containsExactly(1, 1, 1, 1, 1, 1, 0, 1);
            assertThat(jdbc.queryForList("SELECT * FROM skill_expert_assignment_batch")).isEqualTo(batches);
            assertThat(jdbc.queryForList("SELECT * FROM skill_expert_assignment_item")).isEqualTo(items);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO skill_expert_assignment_item(batch_id,expert_id,payload)
                    VALUES('missing-batch',1,'{}')
                    """)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            jdbc.update("DELETE FROM skill_expert_assignment_batch WHERE id='migration-batch'");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM skill_expert_assignment_item", Integer.class)).isZero();
        }
    }

    @Test
    void currentFreshSchemaDoesNotReinitializeExistingDraftFlag() throws Exception {
        try (var mysql = IsolatedMysql.start()) {
            mysql.applyResource("db/schema.sql");
            var jdbc = new JdbcTemplate(mysql.dataSource());
            seedUser(jdbc);
            jdbc.update("""
                    INSERT INTO expert(name,system_prompt,skill_version_ids,mcp_version_ids,draft_changed,created_by)
                    VALUES('Existing','Prompt','[]','[]',0,901)
                    """);
            var before = jdbc.queryForList("SELECT * FROM expert");
            mysql.applyResource(MIGRATION);
            mysql.applyResource(MIGRATION);
            assertThat(jdbc.queryForList("SELECT * FROM expert")).isEqualTo(before);
        }
    }

    private static void seedUser(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO sys_user(id,email,password_hash,display_name,status)
                VALUES(901,'migration@test.local','unused','Fixture','ENABLED')
                """);
    }
}
