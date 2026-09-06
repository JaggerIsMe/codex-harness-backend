-- Apply once before deploying the single-active Skill Version release.
-- Keep the newest uploaded version active for each Skill and disable every older version.
START TRANSACTION;

UPDATE skill_version
SET status = 'DISABLED';

UPDATE skill_version newest
JOIN (
    SELECT skill_id, MAX(id) AS latest_id
    FROM skill_version
    GROUP BY skill_id
) latest ON latest.latest_id = newest.id
SET newest.status = 'ACTIVE';

COMMIT;
