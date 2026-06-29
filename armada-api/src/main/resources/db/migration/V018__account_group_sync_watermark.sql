-- 账号当前群同步调度水位:
-- 只记录定时任务最近一次把账号群同步命令写入 outbox 的时间,不覆盖 baseline_group_jids。

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'account_group_baseline'
       AND column_name = 'last_group_sync_requested_at') = 0,
    'ALTER TABLE account_group_baseline
       ADD COLUMN last_group_sync_requested_at BIGINT DEFAULT NULL
       COMMENT ''最近一次账号当前群同步命令入队时间(epoch毫秒);不覆盖baseline快照''
       AFTER captured_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'account_group_baseline'
       AND index_name = 'idx_account_baseline_group_sync_requested') = 0,
    'ALTER TABLE account_group_baseline
       ADD KEY idx_account_baseline_group_sync_requested (tenant_id, last_group_sync_requested_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
