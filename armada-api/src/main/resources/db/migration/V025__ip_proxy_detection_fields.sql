SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ip_proxy'
    AND column_name = 'detected_country_code'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN detected_country_code VARCHAR(8) DEFAULT NULL COMMENT ''检测出的ISO2国家码'' AFTER last_sample_check_at',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ip_proxy'
    AND column_name = 'outbound_ip'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN outbound_ip VARCHAR(64) DEFAULT NULL COMMENT ''真实出口公网IP'' AFTER detected_country_code',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ip_proxy'
    AND column_name = 'detected_location'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN detected_location VARCHAR(255) DEFAULT NULL COMMENT ''检测出的地理位置'' AFTER outbound_ip',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ip_proxy'
    AND column_name = 'detected_isp'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN detected_isp VARCHAR(255) DEFAULT NULL COMMENT ''检测出的ISP'' AFTER detected_location',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ip_proxy'
    AND column_name = 'detected_latitude'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN detected_latitude DECIMAL(10,7) DEFAULT NULL COMMENT ''检测纬度'' AFTER detected_isp',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ip_proxy'
    AND column_name = 'detected_longitude'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN detected_longitude DECIMAL(10,7) DEFAULT NULL COMMENT ''检测经度'' AFTER detected_latitude',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ip_proxy'
    AND column_name = 'check_fail_count'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN check_fail_count INT NOT NULL DEFAULT 0 COMMENT ''检测失败次数'' AFTER detected_longitude',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ip_proxy'
    AND column_name = 'last_check_error'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN last_check_error VARCHAR(512) DEFAULT NULL COMMENT ''最近一次检测失败原因'' AFTER check_fail_count',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
