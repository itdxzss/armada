-- 拉群任务统一列表与全局设置回滚脚本。
-- 先停止应用读写并确认统计与设置数据无需保留；本脚本只供审阅，不在本地执行。

DELETE FROM sys_menu
WHERE menu_key = 'TaskPullSettings'
  AND perm_key = 'tenant:pull_task:settings';

DROP TABLE IF EXISTS pull_task_group_marketing_setting;
DROP TABLE IF EXISTS pull_task_group_marketing_summary;

SET @pull_task_source_index_drop_sql := IF(
    (SELECT COUNT(DISTINCT index_name)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND index_name = 'idx_pull_task_source') > 0,
    'ALTER TABLE pull_task DROP INDEX idx_pull_task_source',
    'SELECT 1'
);
PREPARE pull_task_source_index_drop_stmt FROM @pull_task_source_index_drop_sql;
EXECUTE pull_task_source_index_drop_stmt;
DEALLOCATE PREPARE pull_task_source_index_drop_stmt;

SET @pull_task_type_status_index_drop_sql := IF(
    (SELECT COUNT(DISTINCT index_name)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND index_name = 'idx_pull_task_type_status') > 0,
    'ALTER TABLE pull_task DROP INDEX idx_pull_task_type_status',
    'SELECT 1'
);
PREPARE pull_task_type_status_index_drop_stmt FROM @pull_task_type_status_index_drop_sql;
EXECUTE pull_task_type_status_index_drop_stmt;
DEALLOCATE PREPARE pull_task_type_status_index_drop_stmt;

SET @pull_task_last_executed_drop_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'last_business_executed_at') > 0,
    'ALTER TABLE pull_task DROP COLUMN last_business_executed_at',
    'SELECT 1'
);
PREPARE pull_task_last_executed_drop_stmt FROM @pull_task_last_executed_drop_sql;
EXECUTE pull_task_last_executed_drop_stmt;
DEALLOCATE PREPARE pull_task_last_executed_drop_stmt;

SET @pull_task_blocking_reason_drop_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'blocking_reason') > 0,
    'ALTER TABLE pull_task DROP COLUMN blocking_reason',
    'SELECT 1'
);
PREPARE pull_task_blocking_reason_drop_stmt FROM @pull_task_blocking_reason_drop_sql;
EXECUTE pull_task_blocking_reason_drop_stmt;
DEALLOCATE PREPARE pull_task_blocking_reason_drop_stmt;

SET @pull_task_primary_stage_drop_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'primary_stage') > 0,
    'ALTER TABLE pull_task DROP COLUMN primary_stage',
    'SELECT 1'
);
PREPARE pull_task_primary_stage_drop_stmt FROM @pull_task_primary_stage_drop_sql;
EXECUTE pull_task_primary_stage_drop_stmt;
DEALLOCATE PREPARE pull_task_primary_stage_drop_stmt;

SET @pull_task_group_source_drop_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'group_source') > 0,
    'ALTER TABLE pull_task DROP COLUMN group_source',
    'SELECT 1'
);
PREPARE pull_task_group_source_drop_stmt FROM @pull_task_group_source_drop_sql;
EXECUTE pull_task_group_source_drop_stmt;
DEALLOCATE PREPARE pull_task_group_source_drop_stmt;

SET @pull_task_type_drop_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'task_type') > 0,
    'ALTER TABLE pull_task DROP COLUMN task_type',
    'SELECT 1'
);
PREPARE pull_task_type_drop_stmt FROM @pull_task_type_drop_sql;
EXECUTE pull_task_type_drop_stmt;
DEALLOCATE PREPARE pull_task_type_drop_stmt;
