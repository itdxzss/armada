-- 第三阶段第四切片：拉群任务公共聚合根增加用户归属。
-- 历史任务保持 owner_user_id=NULL，仅租户管理员可见；不根据 created_by 猜测归属。

SET @pull_task_owner_column_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'owner_user_id') = 0,
    'ALTER TABLE pull_task ADD COLUMN owner_user_id BIGINT DEFAULT NULL COMMENT ''归属用户ID;NULL为待管理员显式分配的历史数据'' AFTER tenant_id',
    'SELECT 1'
);
PREPARE pull_task_owner_column_stmt FROM @pull_task_owner_column_ddl;
EXECUTE pull_task_owner_column_stmt;
DEALLOCATE PREPARE pull_task_owner_column_stmt;

SET @pull_task_owner_index_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND index_name = 'idx_pull_task_owner') = 0,
    'ALTER TABLE pull_task ADD KEY idx_pull_task_owner (tenant_id, owner_user_id, deleted_at, id)',
    'SELECT 1'
);
PREPARE pull_task_owner_index_stmt FROM @pull_task_owner_index_ddl;
EXECUTE pull_task_owner_index_stmt;
DEALLOCATE PREPARE pull_task_owner_index_stmt;
