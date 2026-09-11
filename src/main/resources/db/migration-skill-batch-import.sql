-- Apply before deploying the Skill batch import server.
CREATE TABLE IF NOT EXISTS skill_catalog_lock (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT IGNORE INTO skill_catalog_lock(id) VALUES(1);

CREATE TABLE IF NOT EXISTS skill_import_record (
    id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    kind VARCHAR(16) NOT NULL,
    owner_id BIGINT UNSIGNED NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    KEY idx_skill_import_owner (owner_id,kind),
    KEY idx_skill_import_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
