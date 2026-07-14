-- Rollback for V054__group_link_import_invalid_reason.sql.
-- Run only after confirming target environment and maintenance window.

ALTER TABLE group_link_import_batch
    MODIFY COLUMN failed_rows INT NOT NULL DEFAULT 0
        COMMENT '失败总数(重复 + 格式错误)';

ALTER TABLE group_link_import_detail
    MODIFY COLUMN fail_reason VARCHAR(255) DEFAULT NULL
        COMMENT '失败原因:重复/格式错误;成功时为空';
