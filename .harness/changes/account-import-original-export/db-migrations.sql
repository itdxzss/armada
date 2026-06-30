-- V020__account_import_original_payload.sql
SET @schema_name = DATABASE();
SET @column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'account_import_batch'
      AND COLUMN_NAME = 'source_file_type'
);
SET @ddl = IF(
    @column_exists = 0,
    'ALTER TABLE account_import_batch ADD COLUMN source_file_type VARCHAR(16) DEFAULT NULL COMMENT ''原始导入容器:ZIP/TXT'' AFTER source_file_name',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'account_import_detail'
      AND COLUMN_NAME = 'raw_payload'
);
SET @ddl = IF(
    @column_exists = 0,
    'ALTER TABLE account_import_detail ADD COLUMN raw_payload MEDIUMTEXT DEFAULT NULL COMMENT ''单条原始导入内容,敏感,不得进入日志或列表响应'' AFTER ws_phone',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'account_import_detail'
      AND COLUMN_NAME = 'source_entry_name'
);
SET @ddl = IF(
    @column_exists = 0,
    'ALTER TABLE account_import_detail ADD COLUMN source_entry_name VARCHAR(512) DEFAULT NULL COMMENT ''原始条目名:zip内路径或line-N'' AFTER raw_payload',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
