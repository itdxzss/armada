-- V083.1：保留受控账号最近一次精确退群事实，账号重新进群后仍可用于业务导出。

SET @last_exit_type_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group_membership'
      AND column_name = 'last_exit_type'
);
SET @sql := IF(
    @last_exit_type_col_exists = 0,
    'ALTER TABLE account_group_membership
       ADD COLUMN last_exit_type TINYINT NULL
       COMMENT ''最近一次精确退群方式:3被踢 4主动退出''
       AFTER status_updated_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @last_exited_at_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group_membership'
      AND column_name = 'last_exited_at'
);
SET @sql := IF(
    @last_exited_at_col_exists = 0,
    'ALTER TABLE account_group_membership
       ADD COLUMN last_exited_at BIGINT NULL
       COMMENT ''最近一次精确退群事件时间(epoch毫秒)''
       AFTER last_exit_type',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE account_group_membership
SET last_exit_type = COALESCE(last_exit_type, membership_status),
    last_exited_at = COALESCE(last_exited_at, status_updated_at)
WHERE membership_status IN (3, 4)
  AND (last_exit_type IS NULL OR last_exited_at IS NULL);
