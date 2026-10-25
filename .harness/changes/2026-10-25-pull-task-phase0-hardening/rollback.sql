-- 回滚 V218：删除拉群角色账号按任务的索引（仅在确认需要回滚时执行，并同步处理 flyway_schema_history）。
SET @pull_task_group_account_task_drop := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_account'
       AND index_name = 'idx_pull_task_group_account_task') > 0,
    'ALTER TABLE pull_task_group_account DROP KEY idx_pull_task_group_account_task',
    'SELECT 1'
);
PREPARE pull_task_group_account_task_drop_stmt FROM @pull_task_group_account_task_drop;
EXECUTE pull_task_group_account_task_drop_stmt;
DEALLOCATE PREPARE pull_task_group_account_task_drop_stmt;
