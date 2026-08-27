-- 异步营销导出必须持久化创建时的可信数据范围，后台 Worker 不重新猜测用户角色。

SET @marketing_export_scope_column_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'marketing_task_export_job'
       AND column_name = 'data_scope_mode') = 0,
    'ALTER TABLE marketing_task_export_job ADD COLUMN data_scope_mode VARCHAR(8) DEFAULT NULL COMMENT ''创建时数据范围:SELF/ALL;NULL历史作业禁止执行'' AFTER created_by',
    'SELECT 1'
);
PREPARE marketing_export_scope_column_stmt FROM @marketing_export_scope_column_ddl;
EXECUTE marketing_export_scope_column_stmt;
DEALLOCATE PREPARE marketing_export_scope_column_stmt;
