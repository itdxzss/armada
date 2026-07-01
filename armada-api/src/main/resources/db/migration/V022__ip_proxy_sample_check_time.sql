SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ip_proxy'
    AND column_name = 'last_sample_check_at'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN last_sample_check_at BIGINT DEFAULT NULL COMMENT ''最近抽检时间(epoch毫秒)'' AFTER bound_at',
  'SELECT 1'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
