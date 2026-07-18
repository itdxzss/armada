-- 对应 Flyway V058：普通营销任务按账号控制相邻群组命令发送间隔。

SET @account_group_send_interval_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task'
      AND column_name = 'account_group_send_interval_ms'
);
SET @sql := IF(
    @account_group_send_interval_col_exists = 0,
    'ALTER TABLE marketing_task
       ADD COLUMN account_group_send_interval_ms INT NOT NULL DEFAULT 500
       COMMENT ''单账号下相邻群组命令发送间隔(毫秒)''
       AFTER send_per_round',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
