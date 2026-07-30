SET @sql = IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'historical_group_pull_execution'
       AND index_name = 'idx_historical_pull_source_group') > 0,
    'ALTER TABLE historical_group_pull_execution DROP INDEX idx_historical_pull_source_group',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'historical_group_pull_execution'
       AND column_name = 'source_account_group_id') > 0,
    'ALTER TABLE historical_group_pull_execution DROP COLUMN source_account_group_id',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link_preview'
       AND column_name = 'group_created_at') > 0,
    'ALTER TABLE group_link_preview DROP COLUMN group_created_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
