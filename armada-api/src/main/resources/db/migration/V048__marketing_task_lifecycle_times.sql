-- 营销任务生命周期时间:
-- account_group_send_at 固定本任务账号动态维度的群加入时间筛选下界;
-- task_start_at / task_end_at 控制任务自动开始和自动结束。

SET @account_group_send_at_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task'
      AND column_name = 'account_group_send_at'
);
SET @sql := IF(
    @account_group_send_at_col_exists = 0,
    'ALTER TABLE marketing_task
       ADD COLUMN account_group_send_at BIGINT DEFAULT NULL
       COMMENT ''账号群组发送时间(epoch毫秒);仅发送该时间之后加入的账号动态群''
       AFTER remark',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @task_start_at_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task'
      AND column_name = 'task_start_at'
);
SET @sql := IF(
    @task_start_at_col_exists = 0,
    'ALTER TABLE marketing_task
       ADD COLUMN task_start_at BIGINT DEFAULT NULL
       COMMENT ''任务计划开始时间(epoch毫秒)''
       AFTER account_group_send_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @task_end_at_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task'
      AND column_name = 'task_end_at'
);
SET @sql := IF(
    @task_end_at_col_exists = 0,
    'ALTER TABLE marketing_task
       ADD COLUMN task_end_at BIGINT DEFAULT NULL
       COMMENT ''任务计划结束时间(epoch毫秒)''
       AFTER task_start_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE marketing_task
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1
    COMMENT '任务状态:1=等待开始/未发送 2=发送中 3=发送成功 4=发送失败 5=已停止 6=部分失败 7=已结束';

SET @start_due_idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task'
      AND index_name = 'idx_marketing_task_start_due'
);
SET @sql := IF(
    @start_due_idx_exists = 0,
    'ALTER TABLE marketing_task
       ADD KEY idx_marketing_task_start_due
       (tenant_id, status, task_start_at, id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @end_due_idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task'
      AND index_name = 'idx_marketing_task_end_due'
);
SET @sql := IF(
    @end_due_idx_exists = 0,
    'ALTER TABLE marketing_task
       ADD KEY idx_marketing_task_end_due
       (tenant_id, status, task_end_at, id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
