-- 账号上控后群加入时间:
-- joined_at 记录 Armada 首次探测到该账号在上控后进入该群的时间(epoch毫秒)。
-- 历史 active membership 无法还原真实探测时间,回填为 created_at。

SET @joined_at_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group_membership'
      AND column_name = 'joined_at'
);
SET @sql := IF(
    @joined_at_col_exists = 0,
    'ALTER TABLE account_group_membership
       ADD COLUMN joined_at BIGINT DEFAULT NULL
       COMMENT ''账号上控后首次探测到进入该群的时间(epoch毫秒)''
       AFTER is_admin',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE account_group_membership
SET joined_at = created_at
WHERE joined_at IS NULL
  AND deleted_at IS NULL;

SET @joined_at_idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group_membership'
      AND index_name = 'idx_account_group_membership_account_joined'
);
SET @sql := IF(
    @joined_at_idx_exists = 0,
    'ALTER TABLE account_group_membership
       ADD KEY idx_account_group_membership_account_joined
       (tenant_id, account_id, deleted_at, joined_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
