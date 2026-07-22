-- 账号群关系改为保留当前状态，不再用软删除表达“已退出群”。

SET @membership_status_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group_membership'
      AND column_name = 'membership_status'
);
SET @sql := IF(
    @membership_status_col_exists = 0,
    'ALTER TABLE account_group_membership
       ADD COLUMN membership_status TINYINT NOT NULL DEFAULT 1
       COMMENT ''当前账号群关系:1在群 2未确认 3被踢 4主动退出 5不在群''
       AFTER is_admin',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @status_source_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group_membership'
      AND column_name = 'status_source'
);
SET @sql := IF(
    @status_source_col_exists = 0,
    'ALTER TABLE account_group_membership
       ADD COLUMN status_source VARCHAR(64) NULL
       COMMENT ''当前关系状态来源''
       AFTER membership_status',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @status_updated_at_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group_membership'
      AND column_name = 'status_updated_at'
);
SET @sql := IF(
    @status_updated_at_col_exists = 0,
    'ALTER TABLE account_group_membership
       ADD COLUMN status_updated_at BIGINT NULL
       COMMENT ''当前关系状态事实时间(epoch毫秒)''
       AFTER status_source',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE account_group_membership
SET membership_status = 1,
    status_source = CASE WHEN deleted_at IS NULL THEN 'LEGACY_ACTIVE' ELSE 'LEGACY_ARCHIVED' END,
    status_updated_at = COALESCE(deleted_at, updated_at, created_at)
WHERE status_source IS NULL
   OR status_updated_at IS NULL;

CREATE TEMPORARY TABLE tmp_membership_revival AS
SELECT archived.id,
       COALESCE(archived.deleted_at, archived.updated_at, archived.created_at) AS exited_at
FROM account_group_membership archived
LEFT JOIN account_group_membership active
  ON active.tenant_id = archived.tenant_id
 AND active.account_id = archived.account_id
 AND active.group_jid = archived.group_jid
 AND active.deleted_at IS NULL
WHERE archived.deleted_at IS NOT NULL
  AND active.id IS NULL
  AND archived.id = (
    SELECT newer.id
    FROM account_group_membership newer
    WHERE newer.tenant_id = archived.tenant_id
      AND newer.account_id = archived.account_id
      AND newer.group_jid = archived.group_jid
      AND newer.deleted_at IS NOT NULL
    ORDER BY newer.deleted_at DESC, newer.id DESC
    LIMIT 1
  );

UPDATE account_group_membership membership
JOIN tmp_membership_revival revival ON revival.id = membership.id
SET membership.membership_status = 5,
    membership.status_source = 'LEGACY_MIGRATION',
    membership.status_updated_at = revival.exited_at,
    membership.deleted_at = NULL,
    membership.updated_at = revival.exited_at;

DROP TEMPORARY TABLE tmp_membership_revival;

ALTER TABLE account_group_membership
    MODIFY COLUMN status_updated_at BIGINT NOT NULL
    COMMENT '当前关系状态事实时间(epoch毫秒)',
    MODIFY COLUMN last_seen_at BIGINT NULL
    COMMENT '最近一次快照或精确add确认仍在群的时间(epoch毫秒);从未确认可为NULL',
    MODIFY COLUMN deleted_at BIGINT NULL
    COMMENT '旧重复历史或真正废弃记录的软删时间(epoch毫秒)';

SET @membership_status_idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group_membership'
      AND index_name = 'idx_account_group_membership_status'
);
SET @sql := IF(
    @membership_status_idx_exists = 0,
    'ALTER TABLE account_group_membership
       ADD KEY idx_account_group_membership_status
       (tenant_id, account_id, membership_status, deleted_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
