-- 第一阶段账号域用户归属：只给独立权限根增加 owner，不根据 created_by 猜测历史归属。
-- 历史行继续保持 owner_user_id=NULL，由租户管理员运营；新版本应用负责写入可信 owner。

SET @account_owner_column_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'account'
       AND column_name = 'owner_user_id') = 0,
    'ALTER TABLE account ADD COLUMN owner_user_id BIGINT DEFAULT NULL COMMENT ''归属用户ID;NULL为待管理员显式分配的历史数据'' AFTER tenant_id',
    'SELECT 1'
);
PREPARE account_owner_column_stmt FROM @account_owner_column_ddl;
EXECUTE account_owner_column_stmt;
DEALLOCATE PREPARE account_owner_column_stmt;

SET @account_group_owner_column_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'account_group'
       AND column_name = 'owner_user_id') = 0,
    'ALTER TABLE account_group ADD COLUMN owner_user_id BIGINT DEFAULT NULL COMMENT ''归属用户ID;NULL为待管理员显式分配的历史数据'' AFTER tenant_id',
    'SELECT 1'
);
PREPARE account_group_owner_column_stmt FROM @account_group_owner_column_ddl;
EXECUTE account_group_owner_column_stmt;
DEALLOCATE PREPARE account_group_owner_column_stmt;

SET @account_import_batch_owner_column_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'account_import_batch'
       AND column_name = 'owner_user_id') = 0,
    'ALTER TABLE account_import_batch ADD COLUMN owner_user_id BIGINT DEFAULT NULL COMMENT ''归属用户ID;NULL为待管理员显式分配的历史数据'' AFTER tenant_id',
    'SELECT 1'
);
PREPARE account_import_batch_owner_column_stmt
    FROM @account_import_batch_owner_column_ddl;
EXECUTE account_import_batch_owner_column_stmt;
DEALLOCATE PREPARE account_import_batch_owner_column_stmt;

-- MySQL 唯一索引不判定两个 NULL 相等。该辅助列只约束无 owner 的活跃分组，
-- 保留历史 NULL owner 数据的租户级名称唯一；有 owner 的行不受它限制。
SET @account_group_unowned_name_column_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'account_group'
       AND column_name = 'unowned_name_key') = 0,
    'ALTER TABLE account_group ADD COLUMN unowned_name_key VARCHAR(100) GENERATED ALWAYS AS (IF(owner_user_id IS NULL, name, NULL)) VIRTUAL COMMENT ''无归属活跃分组名称唯一键辅助;有归属时为空'' AFTER name',
    'SELECT 1'
);
PREPARE account_group_unowned_name_column_stmt
    FROM @account_group_unowned_name_column_ddl;
EXECUTE account_group_unowned_name_column_stmt;
DEALLOCATE PREPARE account_group_unowned_name_column_stmt;

SET @account_owner_index_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'account'
       AND index_name = 'idx_account_owner') = 0,
    'ALTER TABLE account ADD KEY idx_account_owner (tenant_id, owner_user_id, deleted_at, id)',
    'SELECT 1'
);
PREPARE account_owner_index_stmt FROM @account_owner_index_ddl;
EXECUTE account_owner_index_stmt;
DEALLOCATE PREPARE account_owner_index_stmt;

SET @account_group_owner_index_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'account_group'
       AND index_name = 'idx_account_group_owner') = 0,
    'ALTER TABLE account_group ADD KEY idx_account_group_owner (tenant_id, owner_user_id, deleted_at, id)',
    'SELECT 1'
);
PREPARE account_group_owner_index_stmt FROM @account_group_owner_index_ddl;
EXECUTE account_group_owner_index_stmt;
DEALLOCATE PREPARE account_group_owner_index_stmt;

SET @account_import_batch_owner_index_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'account_import_batch'
       AND index_name = 'idx_account_import_batch_owner') = 0,
    'ALTER TABLE account_import_batch ADD KEY idx_account_import_batch_owner (tenant_id, owner_user_id, deleted_at, created_at, id)',
    'SELECT 1'
);
PREPARE account_import_batch_owner_index_stmt
    FROM @account_import_batch_owner_index_ddl;
EXECUTE account_import_batch_owner_index_stmt;
DEALLOCATE PREPARE account_import_batch_owner_index_stmt;

-- 先建立 NULL 兼容唯一键，再建立 owner 范围唯一键，最后移除旧租户范围唯一键；
-- 任一步失败时旧约束仍在，避免迁移中途出现名称无约束窗口。
SET @account_group_unowned_unique_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'account_group'
       AND index_name = 'uq_account_group_unowned_name') = 0,
    'ALTER TABLE account_group ADD UNIQUE KEY uq_account_group_unowned_name (tenant_id, unowned_name_key, is_active)',
    'SELECT 1'
);
PREPARE account_group_unowned_unique_stmt
    FROM @account_group_unowned_unique_ddl;
EXECUTE account_group_unowned_unique_stmt;
DEALLOCATE PREPARE account_group_unowned_unique_stmt;

SET @account_group_owner_unique_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'account_group'
       AND index_name = 'uq_account_group_owner_name') = 0,
    'ALTER TABLE account_group ADD UNIQUE KEY uq_account_group_owner_name (tenant_id, owner_user_id, name, is_active)',
    'SELECT 1'
);
PREPARE account_group_owner_unique_stmt
    FROM @account_group_owner_unique_ddl;
EXECUTE account_group_owner_unique_stmt;
DEALLOCATE PREPARE account_group_owner_unique_stmt;

SET @account_group_legacy_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group'
      AND index_name = 'uq_tenant_name'
);
SET @account_group_legacy_unique_non_unique := (
    SELECT MAX(non_unique)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group'
      AND index_name = 'uq_tenant_name'
);
SET @account_group_owner_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group'
      AND index_name = 'uq_account_group_owner_name'
);
SET @account_group_owner_unique_non_unique := (
    SELECT MAX(non_unique)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group'
      AND index_name = 'uq_account_group_owner_name'
);
SET @account_group_unowned_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group'
      AND index_name = 'uq_account_group_unowned_name'
);
SET @account_group_unowned_unique_non_unique := (
    SELECT MAX(non_unique)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group'
      AND index_name = 'uq_account_group_unowned_name'
);

-- 同名错误索引不能被“已存在”判断掩盖。先用主键冲突 fail-fast，避免删除旧约束后
-- 才发现 owner 唯一键并非预期列序或根本不是 UNIQUE。
DROP TEMPORARY TABLE IF EXISTS tmp_v140_account_group_index_guard;
CREATE TEMPORARY TABLE tmp_v140_account_group_index_guard (
    guard_key TINYINT NOT NULL PRIMARY KEY
);
INSERT INTO tmp_v140_account_group_index_guard (guard_key) VALUES (1);
INSERT INTO tmp_v140_account_group_index_guard (guard_key)
SELECT 1
WHERE COALESCE(@account_group_owner_unique_columns, '')
          <> 'tenant_id,owner_user_id,name,is_active'
   OR COALESCE(@account_group_owner_unique_non_unique, 1) <> 0
   OR COALESCE(@account_group_unowned_unique_columns, '')
          <> 'tenant_id,unowned_name_key,is_active'
   OR COALESCE(@account_group_unowned_unique_non_unique, 1) <> 0
   OR (@account_group_legacy_unique_columns IS NOT NULL
       AND (@account_group_legacy_unique_columns <> 'tenant_id,name,is_active'
            OR COALESCE(@account_group_legacy_unique_non_unique, 1) <> 0));
DROP TEMPORARY TABLE tmp_v140_account_group_index_guard;

SET @account_group_drop_legacy_unique_ddl := IF(
    @account_group_legacy_unique_columns = 'tenant_id,name,is_active'
        AND @account_group_legacy_unique_non_unique = 0
        AND @account_group_owner_unique_columns = 'tenant_id,owner_user_id,name,is_active'
        AND @account_group_owner_unique_non_unique = 0
        AND @account_group_unowned_unique_columns = 'tenant_id,unowned_name_key,is_active'
        AND @account_group_unowned_unique_non_unique = 0,
    'ALTER TABLE account_group DROP INDEX uq_tenant_name',
    'SELECT 1'
);
PREPARE account_group_drop_legacy_unique_stmt
    FROM @account_group_drop_legacy_unique_ddl;
EXECUTE account_group_drop_legacy_unique_stmt;
DEALLOCATE PREPARE account_group_drop_legacy_unique_stmt;
