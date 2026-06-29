SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND index_name = 'idx_tenant_batch_online_phase') > 0,
    'ALTER TABLE account_import_detail DROP INDEX idx_tenant_batch_online_phase',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND index_name = 'idx_tenant_batch_login_result') > 0,
    'ALTER TABLE account_import_detail DROP INDEX idx_tenant_batch_login_result',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND index_name = 'idx_import_detail_online_phase') > 0,
    'ALTER TABLE account_import_detail DROP INDEX idx_import_detail_online_phase',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND column_name = 'login_reason') > 0,
    'ALTER TABLE account_import_detail DROP COLUMN login_reason',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND column_name = 'dispatch_attempts') > 0,
    'ALTER TABLE account_import_detail DROP COLUMN dispatch_attempts',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND column_name = 'login_settled_at') > 0,
    'ALTER TABLE account_import_detail DROP COLUMN login_settled_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND column_name = 'online_dispatched_at') > 0,
    'ALTER TABLE account_import_detail DROP COLUMN online_dispatched_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND column_name = 'online_phase') > 0,
    'ALTER TABLE account_import_detail DROP COLUMN online_phase',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
