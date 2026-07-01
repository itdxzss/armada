SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'country'
    AND column_name = 'last_ip_sample_check_at'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE country ADD COLUMN last_ip_sample_check_at BIGINT DEFAULT NULL COMMENT ''国家级最近 IP 抽检时间(epoch毫秒)'' AFTER is_ip_supported',
  'SELECT 1'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
