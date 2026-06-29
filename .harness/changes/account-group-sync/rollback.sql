DROP TABLE IF EXISTS account_group_membership;

SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group_baseline'
      AND index_name = 'idx_account_baseline_group_sync_requested'
);
SET @sql := IF(
    @idx_exists > 0,
    'ALTER TABLE account_group_baseline DROP INDEX idx_account_baseline_group_sync_requested',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group_baseline'
      AND column_name = 'last_group_sync_requested_at'
);
SET @sql := IF(
    @column_exists > 0,
    'ALTER TABLE account_group_baseline DROP COLUMN last_group_sync_requested_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE group_link
    MODIFY COLUMN origin TINYINT NOT NULL DEFAULT 1
    COMMENT '首次进入群组池来源:1=导入链接 2=进群任务 3=拉群任务 4=自建群';
