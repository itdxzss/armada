-- 拉群执行行调度补索引：并发槽位计数与调度租约回读当前都没有可用索引。
-- 纯加索引，不改列、不动业务数据。

-- 并发槽位闸门 acquireExecutionSlot 的子查询按 (task_id, execution_status) 计数。
-- 现有 idx_pull_task_execution_page(tenant_id, task_id, id) 不含状态列，
-- idx_pull_task_execution_dispatch 又不以 task_id 打头，
-- 万级群的任务每次启动都要扫该任务的全部执行行。
SET @pull_task_execution_task_status_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_execution'
       AND index_name = 'idx_pull_task_execution_task_status') = 0,
    'ALTER TABLE pull_task_group_execution ADD KEY idx_pull_task_execution_task_status (tenant_id, task_id, execution_status)',
    'SELECT 1'
);
PREPARE pull_task_execution_task_status_stmt FROM @pull_task_execution_task_status_ddl;
EXECUTE pull_task_execution_task_status_stmt;
DEALLOCATE PREPARE pull_task_execution_task_status_stmt;

-- selectClaimed 每个调度轮次执行一次：WHERE lock_owner = ? AND lock_expires_at > ?。
-- 该表现有四个索引都不以 lock_owner 打头，当前是全表扫描。
SET @pull_task_execution_lock_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_execution'
       AND index_name = 'idx_pull_task_execution_lock') = 0,
    'ALTER TABLE pull_task_group_execution ADD KEY idx_pull_task_execution_lock (lock_owner, lock_expires_at)',
    'SELECT 1'
);
PREPARE pull_task_execution_lock_stmt FROM @pull_task_execution_lock_ddl;
EXECUTE pull_task_execution_lock_stmt;
DEALLOCATE PREPARE pull_task_execution_lock_stmt;
