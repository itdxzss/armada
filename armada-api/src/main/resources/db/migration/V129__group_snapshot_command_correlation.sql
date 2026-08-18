-- V129：群快照从同步 HTTP 切换到 Kafka 命令后，任务过程聚合需要保存当前命令、scope 完成度、
-- 候选游标和结果期限。这里只扩展过程表，不复制群资料或邀请码事实。

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_metadata_sync_task'
       AND column_name = 'current_command_id') = 0,
    'ALTER TABLE group_metadata_sync_task ADD COLUMN current_command_id VARCHAR(64) DEFAULT NULL COMMENT ''当前等待结算的群快照命令ID''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_metadata_sync_task'
       AND column_name = 'requested_scope_mask') = 0,
    'ALTER TABLE group_metadata_sync_task ADD COLUMN requested_scope_mask TINYINT NOT NULL DEFAULT 0 COMMENT ''当前命令请求scope位掩码:1=METADATA 2=INVITE_CODE''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_metadata_sync_task'
       AND column_name = 'completed_scope_mask') = 0,
    'ALTER TABLE group_metadata_sync_task ADD COLUMN completed_scope_mask TINYINT NOT NULL DEFAULT 0 COMMENT ''当前任务已成功落库scope位掩码:1=METADATA 2=INVITE_CODE''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_metadata_sync_task'
       AND column_name = 'candidate_cursor') = 0,
    'ALTER TABLE group_metadata_sync_task ADD COLUMN candidate_cursor INT NOT NULL DEFAULT 0 COMMENT ''已消费执行账号候选位置''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_metadata_sync_task'
       AND column_name = 'result_deadline_at') = 0,
    'ALTER TABLE group_metadata_sync_task ADD COLUMN result_deadline_at BIGINT DEFAULT NULL COMMENT ''当前命令结果超时水位(epoch毫秒)''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_metadata_sync_task'
       AND index_name = 'idx_gmst_command') = 0,
    'ALTER TABLE group_metadata_sync_task ADD KEY idx_gmst_command (tenant_id, current_command_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_metadata_sync_task'
       AND index_name = 'idx_gmst_deadline') = 0,
    'ALTER TABLE group_metadata_sync_task ADD KEY idx_gmst_deadline (tenant_id, result_deadline_at)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_batch_task_item'
       AND column_name = 'current_command_id') = 0,
    'ALTER TABLE group_batch_task_item ADD COLUMN current_command_id VARCHAR(64) DEFAULT NULL COMMENT ''当前等待结算的群快照命令ID''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_batch_task_item'
       AND column_name = 'attempt_count') = 0,
    'ALTER TABLE group_batch_task_item ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 COMMENT ''已派发群快照命令次数''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_batch_task_item'
       AND column_name = 'candidate_cursor') = 0,
    'ALTER TABLE group_batch_task_item ADD COLUMN candidate_cursor INT NOT NULL DEFAULT 0 COMMENT ''已消费执行账号候选位置''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_batch_task_item'
       AND column_name = 'result_deadline_at') = 0,
    'ALTER TABLE group_batch_task_item ADD COLUMN result_deadline_at BIGINT DEFAULT NULL COMMENT ''当前命令结果超时水位(epoch毫秒)''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_batch_task_item'
       AND column_name = 'completed_scope_mask') = 0,
    'ALTER TABLE group_batch_task_item ADD COLUMN completed_scope_mask TINYINT NOT NULL DEFAULT 0 COMMENT ''已成功落库scope位掩码:1=METADATA 2=INVITE_CODE''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_batch_task_item'
       AND index_name = 'idx_gbti_command') = 0,
    'ALTER TABLE group_batch_task_item ADD KEY idx_gbti_command (tenant_id, current_command_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_batch_task_item'
       AND index_name = 'idx_gbti_deadline') = 0,
    'ALTER TABLE group_batch_task_item ADD KEY idx_gbti_deadline (tenant_id, result_deadline_at)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
