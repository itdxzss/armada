-- 使用前先回滚依赖新字段的 Armada 应用版本。

SET @account_group_send_interval_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task'
      AND column_name = 'account_group_send_interval_ms'
);
SET @sql := IF(
    @account_group_send_interval_col_exists > 0,
    'ALTER TABLE marketing_task DROP COLUMN account_group_send_interval_ms',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
