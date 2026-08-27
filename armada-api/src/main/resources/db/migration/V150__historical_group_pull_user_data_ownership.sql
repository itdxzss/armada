-- 历史群一次性拉人执行用户归属。历史行不根据 created_by 猜测 owner，NULL 仅管理员可见。

SET @hgpe_owner_column_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'historical_group_pull_execution'
       AND column_name = 'owner_user_id') = 0,
    'ALTER TABLE historical_group_pull_execution ADD COLUMN owner_user_id BIGINT DEFAULT NULL COMMENT ''归属用户ID;NULL为待管理员显式分配的历史数据'' AFTER tenant_id',
    'SELECT 1'
);
PREPARE hgpe_owner_column_stmt FROM @hgpe_owner_column_ddl;
EXECUTE hgpe_owner_column_stmt;
DEALLOCATE PREPARE hgpe_owner_column_stmt;

-- MySQL UNIQUE 不判定两个 NULL 相等；生成列保证历史 NULL owner 幂等键仍租户内唯一。
SET @hgpe_unowned_idem_column_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'historical_group_pull_execution'
       AND column_name = 'unowned_idempotency_key') = 0,
    'ALTER TABLE historical_group_pull_execution ADD COLUMN unowned_idempotency_key VARCHAR(128) GENERATED ALWAYS AS (IF(owner_user_id IS NULL, idempotency_key, NULL)) VIRTUAL COMMENT ''无归属执行幂等键唯一辅助'' AFTER idempotency_key',
    'SELECT 1'
);
PREPARE hgpe_unowned_idem_column_stmt FROM @hgpe_unowned_idem_column_ddl;
EXECUTE hgpe_unowned_idem_column_stmt;
DEALLOCATE PREPARE hgpe_unowned_idem_column_stmt;

SET @hgpe_owner_index_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'historical_group_pull_execution'
       AND index_name = 'idx_hgpe_owner_time') = 0,
    'ALTER TABLE historical_group_pull_execution ADD KEY idx_hgpe_owner_time (tenant_id, owner_user_id, created_at, id)',
    'SELECT 1'
);
PREPARE hgpe_owner_index_stmt FROM @hgpe_owner_index_ddl;
EXECUTE hgpe_owner_index_stmt;
DEALLOCATE PREPARE hgpe_owner_index_stmt;

SET @hgpe_owner_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'historical_group_pull_execution'
       AND index_name = 'uq_hgpe_owner_idempotency') = 0,
    'ALTER TABLE historical_group_pull_execution ADD UNIQUE KEY uq_hgpe_owner_idempotency (tenant_id, owner_user_id, idempotency_key)',
    'SELECT 1'
);
PREPARE hgpe_owner_unique_stmt FROM @hgpe_owner_unique_ddl;
EXECUTE hgpe_owner_unique_stmt;
DEALLOCATE PREPARE hgpe_owner_unique_stmt;

SET @hgpe_unowned_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'historical_group_pull_execution'
       AND index_name = 'uq_hgpe_unowned_idempotency') = 0,
    'ALTER TABLE historical_group_pull_execution ADD UNIQUE KEY uq_hgpe_unowned_idempotency (tenant_id, unowned_idempotency_key)',
    'SELECT 1'
);
PREPARE hgpe_unowned_unique_stmt FROM @hgpe_unowned_unique_ddl;
EXECUTE hgpe_unowned_unique_stmt;
DEALLOCATE PREPARE hgpe_unowned_unique_stmt;

SET @hgpe_legacy_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'historical_group_pull_execution'
      AND index_name = 'uq_hgpe_tenant_idempotency'
);
SET @hgpe_legacy_unique_non_unique := (
    SELECT MAX(non_unique)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'historical_group_pull_execution'
      AND index_name = 'uq_hgpe_tenant_idempotency'
);
SET @hgpe_owner_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'historical_group_pull_execution'
      AND index_name = 'uq_hgpe_owner_idempotency'
);
SET @hgpe_owner_unique_non_unique := (
    SELECT MAX(non_unique)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'historical_group_pull_execution'
      AND index_name = 'uq_hgpe_owner_idempotency'
);
SET @hgpe_unowned_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'historical_group_pull_execution'
      AND index_name = 'uq_hgpe_unowned_idempotency'
);
SET @hgpe_unowned_unique_non_unique := (
    SELECT MAX(non_unique)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'historical_group_pull_execution'
      AND index_name = 'uq_hgpe_unowned_idempotency'
);

DROP TEMPORARY TABLE IF EXISTS tmp_v149_hgpe_index_guard;
CREATE TEMPORARY TABLE tmp_v149_hgpe_index_guard (
    guard_key TINYINT NOT NULL PRIMARY KEY
);
INSERT INTO tmp_v149_hgpe_index_guard (guard_key) VALUES (1);
INSERT INTO tmp_v149_hgpe_index_guard (guard_key)
SELECT 1
WHERE COALESCE(@hgpe_owner_unique_columns, '')
          <> 'tenant_id,owner_user_id,idempotency_key'
   OR COALESCE(@hgpe_owner_unique_non_unique, 1) <> 0
   OR COALESCE(@hgpe_unowned_unique_columns, '')
          <> 'tenant_id,unowned_idempotency_key'
   OR COALESCE(@hgpe_unowned_unique_non_unique, 1) <> 0
   OR (@hgpe_legacy_unique_columns IS NOT NULL
       AND (@hgpe_legacy_unique_columns <> 'tenant_id,idempotency_key'
            OR COALESCE(@hgpe_legacy_unique_non_unique, 1) <> 0));
DROP TEMPORARY TABLE tmp_v149_hgpe_index_guard;

SET @hgpe_drop_legacy_unique_ddl := IF(
    @hgpe_owner_unique_columns = 'tenant_id,owner_user_id,idempotency_key'
      AND @hgpe_owner_unique_non_unique = 0
      AND @hgpe_unowned_unique_columns = 'tenant_id,unowned_idempotency_key'
      AND @hgpe_unowned_unique_non_unique = 0
      AND (@hgpe_legacy_unique_columns IS NULL
           OR (@hgpe_legacy_unique_columns = 'tenant_id,idempotency_key'
               AND @hgpe_legacy_unique_non_unique = 0)),
    IF(@hgpe_legacy_unique_columns IS NULL, 'SELECT 1',
       'ALTER TABLE historical_group_pull_execution DROP INDEX uq_hgpe_tenant_idempotency'),
    'SELECT 1'
);
PREPARE hgpe_drop_legacy_unique_stmt FROM @hgpe_drop_legacy_unique_ddl;
EXECUTE hgpe_drop_legacy_unique_stmt;
DEALLOCATE PREPARE hgpe_drop_legacy_unique_stmt;
