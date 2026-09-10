-- 养群任务逐群绑定与资格检查；保留存量任务执行语义。
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'script_marketing_send_record' AND column_name = 'result_deadline_at') = 0,
    'ALTER TABLE script_marketing_send_record ADD COLUMN result_deadline_at BIGINT NULL COMMENT ''结果等待截止毫秒；恢复不改写原提交时间''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'script_marketing_task' AND column_name = 'account_group_id') = 0,
    'ALTER TABLE script_marketing_task ADD COLUMN account_group_id BIGINT NULL COMMENT ''推手账号分组；NULL 为存量固定账号任务''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'script_marketing_task' AND column_name = 'pause_reason') = 0,
    'ALTER TABLE script_marketing_task ADD COLUMN pause_reason VARCHAR(500) NULL COMMENT ''启动复核或运行中自动暂停原因''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'script_marketing_group' AND column_name = 'bindings_json') = 0,
    'ALTER TABLE script_marketing_group ADD COLUMN bindings_json JSON NULL COMMENT ''启动时固定的角色账号映射；恢复不重抽''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'script_marketing_group' AND column_name = 'paused') = 0,
    'ALTER TABLE script_marketing_group ADD COLUMN paused TINYINT NOT NULL DEFAULT 0 COMMENT ''单群暂停：0否 1是''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'script_marketing_group' AND column_name = 'pause_reason') = 0,
    'ALTER TABLE script_marketing_group ADD COLUMN pause_reason VARCHAR(500) NULL COMMENT ''单群暂停原因''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
