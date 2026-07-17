-- 运维审查副本；正式环境由 Flyway V055__join_task_kafka_dispatch.sql 执行。
-- 每个对象独立检查，兼容测试库曾手工创建部分字段或索引的情况。

SET @dispatch_state_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND column_name = 'dispatch_state'
);
SET @sql := IF(
    @dispatch_state_col_exists = 0,
    'ALTER TABLE join_task_result ADD COLUMN dispatch_state VARCHAR(16) NOT NULL DEFAULT ''WAITING'' COMMENT ''派发状态:WAITING/SUBMITTED/TERMINAL'' AFTER status',
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
    @next_execute_at_col_exists = 0,
    'ALTER TABLE join_task_result ADD COLUMN next_execute_at BIGINT DEFAULT NULL COMMENT ''下一次允许写入协议outbox的时间(epoch毫秒);未激活为NULL'' AFTER dispatch_state',
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
    @command_id_col_exists = 0,
    'ALTER TABLE join_task_result ADD COLUMN command_id VARCHAR(64) DEFAULT NULL COMMENT ''当前业务尝试关联的协议outbox命令ID;业务重试时清空'' AFTER next_execute_at',
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
    @attempt_no_col_exists = 0,
    'ALTER TABLE join_task_result ADD COLUMN attempt_no INT NOT NULL DEFAULT 0 COMMENT ''已派发业务尝试序号;首次派发为1'' AFTER command_id',
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
    @dispatch_idx_exists = 0,
    'ALTER TABLE join_task_result ADD KEY idx_jtr_dispatch (dispatch_state, next_execute_at, id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @task_account_idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND index_name = 'idx_jtr_task_account'
);
SET @sql := IF(
    @task_account_idx_exists = 0,
    'ALTER TABLE join_task_result ADD KEY idx_jtr_task_account (tenant_id, join_task_id, account_id, status, id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE join_task_result
SET dispatch_state = 'TERMINAL', next_execute_at = NULL
WHERE status IN ('SUCCESS', 'FAILED');

-- 历史 PENDING 不自动激活；本阶段明确不做服务重启/存量 RUNNING 恢复。
UPDATE join_task_result
SET dispatch_state = 'WAITING', next_execute_at = NULL, command_id = NULL
WHERE status = 'PENDING'
  AND attempt_no = 0
  AND command_id IS NULL;
