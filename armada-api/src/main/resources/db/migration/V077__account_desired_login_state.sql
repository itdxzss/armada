-- 账号控制面期望登录状态：显式下线阻断旧 PROXY_FAILED 自动恢复，显式上线重新放行。

SET @desired_login_state_col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_state'
      AND column_name = 'desired_login_state'
);
SET @sql := IF(
    @desired_login_state_col_exists = 0,
    'ALTER TABLE account_state
       ADD COLUMN desired_login_state TINYINT DEFAULT NULL
       COMMENT ''期望登录状态:1在线 2离线;NULL=历史未建立显式意图''
       AFTER login_state',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
