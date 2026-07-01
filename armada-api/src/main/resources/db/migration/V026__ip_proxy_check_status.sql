SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ip_proxy'
    AND column_name = 'check_status'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN check_status TINYINT NOT NULL DEFAULT 0 COMMENT ''检测生命周期:0=检测中 1=成功 2=失败'' AFTER last_check_error',
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
    AND column_name = 'whatsapp_check_status'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN whatsapp_check_status TINYINT NOT NULL DEFAULT 0 COMMENT ''WhatsApp连通性检测生命周期:0=检测中 1=成功 2=失败'' AFTER check_status',
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
    AND column_name = 'whatsapp_http_status'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN whatsapp_http_status INT DEFAULT NULL COMMENT ''WhatsApp探测HTTP状态码'' AFTER whatsapp_check_status',
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
    AND column_name = 'whatsapp_check_error'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN whatsapp_check_error VARCHAR(512) DEFAULT NULL COMMENT ''WhatsApp连通性检测失败原因'' AFTER whatsapp_http_status',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
