DROP TABLE IF EXISTS marketing_task_send_attempt;
DROP TABLE IF EXISTS marketing_task_target;
DROP TABLE IF EXISTS marketing_task;
DROP TABLE IF EXISTS account_group_baseline;

SET @baseline_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'account' AND column_name = 'group_baseline_state'
);
SET @sql := IF(
    @baseline_col_exists = 1,
    'ALTER TABLE account DROP COLUMN group_baseline_state',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
