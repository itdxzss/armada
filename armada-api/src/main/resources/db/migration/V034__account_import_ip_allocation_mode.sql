SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'account_import_batch'
      AND COLUMN_NAME = 'ip_allocation_mode'
);

SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE account_import_batch ADD COLUMN ip_allocation_mode VARCHAR(16) DEFAULT NULL COMMENT ''账号导入IP分配方式:smart=按账号区号匹配国家 mixed=混合国家;NULL=历史批次按ip_region'' AFTER ip_region',
    'SELECT 1'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
