SET @gcm_retry_history_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'group_creation_marketing_item'
      AND column_name = 'retry_history_json'
);

SET @gcm_retry_history_sql := IF(
    @gcm_retry_history_exists = 0,
    'ALTER TABLE group_creation_marketing_item ADD COLUMN retry_history_json JSON DEFAULT NULL COMMENT ''换号重试历史'' AFTER participant_result_json',
    'SELECT 1'
);

PREPARE gcm_retry_history_stmt FROM @gcm_retry_history_sql;
EXECUTE gcm_retry_history_stmt;
DEALLOCATE PREPARE gcm_retry_history_stmt;
