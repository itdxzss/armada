DROP TABLE IF EXISTS group_link_health;
DROP TABLE IF EXISTS group_link_preview;

ALTER TABLE group_link_import_detail
    DROP COLUMN existing_origin,
    DROP COLUMN success_type;

ALTER TABLE group_link_import_batch
    ADD COLUMN skipped_rows INT NOT NULL DEFAULT 0 COMMENT '批内重复跳过行数' AFTER adopted_rows;

UPDATE group_link_import_batch
SET skipped_rows = duplicate_rows,
    failed_rows = GREATEST(failed_rows - duplicate_rows, 0),
    adopted_rows = 0;

-- V010 folds old skipped_rows + old adopted_rows into duplicate_rows.
-- The exact split cannot be reconstructed during rollback, so adopted_rows
-- stays 0 and skipped_rows carries the merged duplicate count.

ALTER TABLE group_link_import_batch
    DROP COLUMN duplicate_rows;

ALTER TABLE group_link
    DROP INDEX idx_group_link_membership,
    DROP INDEX idx_group_link_origin,
    DROP COLUMN membership_state,
    DROP COLUMN origin;
