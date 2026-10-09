-- 去重水位属于抢登计数事实，跨窗口与人工清零保留，避免旧被挤事件重投再次累计。
SET @last_kicked_at_col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_takeover_breaker'
      AND column_name = 'last_kicked_at'
);
SET @sql := IF(
    @last_kicked_at_col_exists = 0,
    'ALTER TABLE account_takeover_breaker
       ADD COLUMN last_kicked_at BIGINT NULL
       COMMENT ''最近一次已计数被挤事件的发生时间(epoch毫秒),用于重投去重''
       AFTER window_started_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
