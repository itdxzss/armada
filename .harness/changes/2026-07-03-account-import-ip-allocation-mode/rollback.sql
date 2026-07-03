SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'account_import_batch'
      AND COLUMN_NAME = 'ip_allocation_mode'
);

SET @ddl := IF(@col_exists > 0,
    'ALTER TABLE account_import_batch DROP COLUMN ip_allocation_mode',
    'SELECT 1'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
