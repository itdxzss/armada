-- 拉群角色账号表补按任务的索引。纯加索引，不改列、不动业务数据。

-- 任务列表的可用拉手统计按 (task_id IN ..., role_type, availability_status) 聚合，
-- 按任务读取角色账号也只带 task_id。现有索引都以 group_execution_id / account_id / occupancy_key
-- 跟在 tenant_id 之后，这些查询只能扫描租户下全部角色行；列表页自动刷新后会被频繁触发。
SET @pull_task_group_account_task_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_account'
       AND index_name = 'idx_pull_task_group_account_task') = 0,
    'ALTER TABLE pull_task_group_account ADD KEY idx_pull_task_group_account_task (tenant_id, task_id, role_type, availability_status)',
    'SELECT 1'
);
PREPARE pull_task_group_account_task_stmt FROM @pull_task_group_account_task_ddl;
EXECUTE pull_task_group_account_task_stmt;
DEALLOCATE PREPARE pull_task_group_account_task_stmt;
