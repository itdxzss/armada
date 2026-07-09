-- Rollback for V047__account_group_membership_joined_at.sql.
-- Run only after confirming target environment and maintenance window.

SET @joined_at_idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group_membership'
      AND index_name = 'idx_account_group_membership_account_joined'
);
SET @sql := IF(
    @joined_at_idx_exists > 0,
    'ALTER TABLE account_group_membership DROP INDEX idx_account_group_membership_account_joined',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @joined_at_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group_membership'
      AND column_name = 'joined_at'
);
SET @sql := IF(
    @joined_at_col_exists > 0,
    'ALTER TABLE account_group_membership DROP COLUMN joined_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
