SET @baseline_group_subjects_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group_baseline'
      AND column_name = 'baseline_group_subjects'
);

SET @baseline_group_subjects_rollback_sql := IF(
    @baseline_group_subjects_exists = 1,
    'ALTER TABLE account_group_baseline DROP COLUMN baseline_group_subjects',
    'SELECT 1'
);

PREPARE baseline_group_subjects_rollback_stmt FROM @baseline_group_subjects_rollback_sql;
EXECUTE baseline_group_subjects_rollback_stmt;
DEALLOCATE PREPARE baseline_group_subjects_rollback_stmt;

-- Task 8：先删明细，再删执行；不触碰 baseline_group_subjects。
DROP TABLE IF EXISTS historical_group_pull_member;
DROP TABLE IF EXISTS historical_group_pull_execution;
