DROP TABLE IF EXISTS group_link_health;
DROP TABLE IF EXISTS group_link_preview;

ALTER TABLE group_link_import_detail
    DROP COLUMN existing_origin,
    DROP COLUMN success_type;

ALTER TABLE group_link_import_detail
    MODIFY COLUMN group_name VARCHAR(128) DEFAULT NULL COMMENT '群名称(可空)',
    MODIFY COLUMN result TINYINT NOT NULL COMMENT '导入结果:1=成功新增 2=收编 3=批内重复 4=格式错误',
    MODIFY COLUMN fail_reason VARCHAR(255) DEFAULT NULL COMMENT '失败原因(result>=3时)',
    MODIFY COLUMN group_link_id BIGINT DEFAULT NULL COMMENT '成功/收编时关联group_link.id';

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

ALTER TABLE group_link_import_batch
    MODIFY COLUMN inserted_rows INT NOT NULL DEFAULT 0 COMMENT '新增行数',
    MODIFY COLUMN adopted_rows INT NOT NULL DEFAULT 0 COMMENT '收编行数',
    MODIFY COLUMN failed_rows INT NOT NULL DEFAULT 0 COMMENT '格式不合格行数';

ALTER TABLE group_link COMMENT = '群链接(跨业务共享群组表-import身份段)';

ALTER TABLE group_link
    MODIFY COLUMN group_name VARCHAR(128) DEFAULT NULL COMMENT '业务群名(导入时填,可空)';

ALTER TABLE group_link
    DROP INDEX idx_group_link_membership,
    DROP INDEX idx_group_link_origin,
    DROP COLUMN membership_state,
    DROP COLUMN origin;
