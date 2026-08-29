-- 回滚前必须停止账号画像写入和超链任务画像筛选；DROP TABLE 会永久删除已采集画像。
SET @account_profile_rollback_schema := DATABASE();
SET @account_profile_rollback_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA=@account_profile_rollback_schema AND TABLE_NAME='account'
             AND INDEX_NAME='idx_account_hyperlink_platform'),
    'ALTER TABLE account DROP INDEX idx_account_hyperlink_platform',
    'SELECT 1');
PREPARE account_profile_rollback_stmt FROM @account_profile_rollback_sql;
EXECUTE account_profile_rollback_stmt;
DEALLOCATE PREPARE account_profile_rollback_stmt;

DROP TABLE IF EXISTS account_profile;
