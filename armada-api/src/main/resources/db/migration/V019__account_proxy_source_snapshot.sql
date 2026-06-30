-- 账号列表代理来源展示快照:释放 ip_proxy 绑定后仍可展示上线分配时的来源。
SET @schema_name = DATABASE();
SET @column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'account_state'
      AND COLUMN_NAME = 'proxy_source'
);
SET @ddl = IF(
    @column_exists = 0,
    'ALTER TABLE account_state ADD COLUMN proxy_source VARCHAR(64) DEFAULT NULL COMMENT ''代理来源展示快照'' AFTER proxy_country',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
