SET @sql = IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'historical_group_pull_execution'
       AND column_name = 'source_account_group_id') = 0,
    'ALTER TABLE historical_group_pull_execution ADD COLUMN source_account_group_id BIGINT DEFAULT NULL COMMENT ''来源历史群账号组ID'' AFTER operation_account_id',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE historical_group_pull_execution execution_row
INNER JOIN account operation_account
  ON operation_account.tenant_id = execution_row.tenant_id
 AND operation_account.id = execution_row.operation_account_id
SET execution_row.source_account_group_id = operation_account.account_group_id
WHERE execution_row.source_account_group_id IS NULL
  AND operation_account.account_group_id IS NOT NULL;

SET @sql = IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'historical_group_pull_execution'
       AND index_name = 'idx_historical_pull_source_group') = 0,
    'ALTER TABLE historical_group_pull_execution ADD KEY idx_historical_pull_source_group (tenant_id, source_account_group_id, group_jid, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
