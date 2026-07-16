-- 前置条件：先停用 JOIN_TASK_DISPATCHER_ENABLED，等待 outbox/协议消费者静止并回退应用代码。
-- 已发布的 Kafka 命令无法通过数据库 DDL 撤回。

SET @task_account_idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND index_name = 'idx_jtr_task_account'
);
SET @sql := IF(
    @task_account_idx_exists > 0,
    'ALTER TABLE join_task_result DROP INDEX idx_jtr_task_account',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @dispatch_idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND index_name = 'idx_jtr_dispatch'
);
SET @sql := IF(
    @dispatch_idx_exists > 0,
    'ALTER TABLE join_task_result DROP INDEX idx_jtr_dispatch',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @attempt_no_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND column_name = 'attempt_no'
);
SET @sql := IF(
    @attempt_no_col_exists > 0,
    'ALTER TABLE join_task_result DROP COLUMN attempt_no',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @command_id_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND column_name = 'command_id'
);
SET @sql := IF(
    @command_id_col_exists > 0,
    'ALTER TABLE join_task_result DROP COLUMN command_id',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @next_execute_at_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND column_name = 'next_execute_at'
);
SET @sql := IF(
    @next_execute_at_col_exists > 0,
    'ALTER TABLE join_task_result DROP COLUMN next_execute_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @dispatch_state_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND column_name = 'dispatch_state'
);
SET @sql := IF(
    @dispatch_state_col_exists > 0,
    'ALTER TABLE join_task_result DROP COLUMN dispatch_state',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
