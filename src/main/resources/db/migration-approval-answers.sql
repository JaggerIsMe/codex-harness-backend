-- Run once before upgrading the Server. Does not change historical decisions.
ALTER TABLE approval_request ADD COLUMN decision_message_id CHAR(36)
    CHARACTER SET ascii COLLATE ascii_bin NULL
    COMMENT 'In-flight decision command UUID; cleared on acknowledgement';
