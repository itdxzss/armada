-- 第二阶段营销模板归属：模板和图片文件是用户私有资源；历史行不猜测归属。
-- owner_user_id=NULL 的历史数据仅租户管理员可见，新版本应用写入可信登录用户 ID。

SET @marketing_template_owner_column_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'marketing_template'
       AND column_name = 'owner_user_id') = 0,
    'ALTER TABLE marketing_template ADD COLUMN owner_user_id BIGINT DEFAULT NULL COMMENT ''归属用户ID;NULL为待管理员显式分配的历史数据'' AFTER tenant_id',
    'SELECT 1'
);
PREPARE marketing_template_owner_column_stmt FROM @marketing_template_owner_column_ddl;
EXECUTE marketing_template_owner_column_stmt;
DEALLOCATE PREPARE marketing_template_owner_column_stmt;

SET @marketing_template_file_owner_column_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'marketing_template_file'
       AND column_name = 'owner_user_id') = 0,
    'ALTER TABLE marketing_template_file ADD COLUMN owner_user_id BIGINT DEFAULT NULL COMMENT ''归属用户ID;NULL为待管理员显式分配的历史数据'' AFTER tenant_id',
    'SELECT 1'
);
PREPARE marketing_template_file_owner_column_stmt FROM @marketing_template_file_owner_column_ddl;
EXECUTE marketing_template_file_owner_column_stmt;
DEALLOCATE PREPARE marketing_template_file_owner_column_stmt;

-- 仅活跃模板参与名称唯一性；软删除行生成 NULL，可继续保留历史同名记录。
SET @marketing_template_active_name_column_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'marketing_template'
       AND column_name = 'active_name_key') = 0,
    'ALTER TABLE marketing_template ADD COLUMN active_name_key VARCHAR(128) GENERATED ALWAYS AS (IF(deleted_at IS NULL, template_name, NULL)) VIRTUAL COMMENT ''活跃模板名称唯一键辅助'' AFTER template_name',
    'SELECT 1'
);
PREPARE marketing_template_active_name_column_stmt FROM @marketing_template_active_name_column_ddl;
EXECUTE marketing_template_active_name_column_stmt;
DEALLOCATE PREPARE marketing_template_active_name_column_stmt;

-- MySQL 唯一索引不判定两个 NULL 相等，单独约束历史 NULL owner 的活跃模板名称。
SET @marketing_template_unowned_name_column_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'marketing_template'
       AND column_name = 'unowned_name_key') = 0,
    'ALTER TABLE marketing_template ADD COLUMN unowned_name_key VARCHAR(128) GENERATED ALWAYS AS (IF(deleted_at IS NULL AND owner_user_id IS NULL, template_name, NULL)) VIRTUAL COMMENT ''无归属活跃模板名称唯一键辅助'' AFTER active_name_key',
    'SELECT 1'
);
PREPARE marketing_template_unowned_name_column_stmt FROM @marketing_template_unowned_name_column_ddl;
EXECUTE marketing_template_unowned_name_column_stmt;
DEALLOCATE PREPARE marketing_template_unowned_name_column_stmt;

SET @marketing_template_owner_index_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'marketing_template'
       AND index_name = 'idx_marketing_template_owner') = 0,
    'ALTER TABLE marketing_template ADD KEY idx_marketing_template_owner (tenant_id, owner_user_id, deleted_at, id)',
    'SELECT 1'
);
PREPARE marketing_template_owner_index_stmt FROM @marketing_template_owner_index_ddl;
EXECUTE marketing_template_owner_index_stmt;
DEALLOCATE PREPARE marketing_template_owner_index_stmt;

SET @marketing_template_file_owner_index_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'marketing_template_file'
       AND index_name = 'idx_marketing_template_file_owner') = 0,
    'ALTER TABLE marketing_template_file ADD KEY idx_marketing_template_file_owner (tenant_id, owner_user_id, deleted_at, id)',
    'SELECT 1'
);
PREPARE marketing_template_file_owner_index_stmt FROM @marketing_template_file_owner_index_ddl;
EXECUTE marketing_template_file_owner_index_stmt;
DEALLOCATE PREPARE marketing_template_file_owner_index_stmt;

-- 先建立 owner/NULL-owner 两套新约束，验收索引列序后才移除旧租户级名称索引。
SET @marketing_template_owner_unique_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'marketing_template'
       AND index_name = 'uq_marketing_template_owner_name') = 0,
    'ALTER TABLE marketing_template ADD UNIQUE KEY uq_marketing_template_owner_name (tenant_id, owner_user_id, active_name_key)',
    'SELECT 1'
);
PREPARE marketing_template_owner_unique_stmt FROM @marketing_template_owner_unique_ddl;
EXECUTE marketing_template_owner_unique_stmt;
DEALLOCATE PREPARE marketing_template_owner_unique_stmt;

SET @marketing_template_unowned_unique_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'marketing_template'
       AND index_name = 'uq_marketing_template_unowned_name') = 0,
    'ALTER TABLE marketing_template ADD UNIQUE KEY uq_marketing_template_unowned_name (tenant_id, unowned_name_key)',
    'SELECT 1'
);
PREPARE marketing_template_unowned_unique_stmt FROM @marketing_template_unowned_unique_ddl;
EXECUTE marketing_template_unowned_unique_stmt;
DEALLOCATE PREPARE marketing_template_unowned_unique_stmt;

SET @marketing_template_legacy_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_template'
      AND index_name = 'uq_tenant_name'
);
SET @marketing_template_legacy_unique_non_unique := (
    SELECT MAX(non_unique)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_template'
      AND index_name = 'uq_tenant_name'
);
SET @marketing_template_owner_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_template'
      AND index_name = 'uq_marketing_template_owner_name'
);
SET @marketing_template_owner_unique_non_unique := (
    SELECT MAX(non_unique)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_template'
      AND index_name = 'uq_marketing_template_owner_name'
);
SET @marketing_template_unowned_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_template'
      AND index_name = 'uq_marketing_template_unowned_name'
);
SET @marketing_template_unowned_unique_non_unique := (
    SELECT MAX(non_unique)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_template'
      AND index_name = 'uq_marketing_template_unowned_name'
);

DROP TEMPORARY TABLE IF EXISTS tmp_v141_marketing_template_index_guard;
CREATE TEMPORARY TABLE tmp_v141_marketing_template_index_guard (
    guard_key TINYINT NOT NULL PRIMARY KEY
);
INSERT INTO tmp_v141_marketing_template_index_guard (guard_key) VALUES (1);
INSERT INTO tmp_v141_marketing_template_index_guard (guard_key)
SELECT 1
WHERE COALESCE(@marketing_template_owner_unique_columns, '')
          <> 'tenant_id,owner_user_id,active_name_key'
   OR COALESCE(@marketing_template_owner_unique_non_unique, 1) <> 0
   OR COALESCE(@marketing_template_unowned_unique_columns, '')
          <> 'tenant_id,unowned_name_key'
   OR COALESCE(@marketing_template_unowned_unique_non_unique, 1) <> 0
   OR (@marketing_template_legacy_unique_columns IS NOT NULL
       AND (@marketing_template_legacy_unique_columns <> 'tenant_id,template_name,deleted_at'
            OR COALESCE(@marketing_template_legacy_unique_non_unique, 1) <> 0));
DROP TEMPORARY TABLE tmp_v141_marketing_template_index_guard;

SET @marketing_template_drop_legacy_unique_ddl := IF(
    @marketing_template_legacy_unique_columns = 'tenant_id,template_name,deleted_at'
        AND @marketing_template_legacy_unique_non_unique = 0
        AND @marketing_template_owner_unique_columns = 'tenant_id,owner_user_id,active_name_key'
        AND @marketing_template_owner_unique_non_unique = 0
        AND @marketing_template_unowned_unique_columns = 'tenant_id,unowned_name_key'
        AND @marketing_template_unowned_unique_non_unique = 0,
    'ALTER TABLE marketing_template DROP INDEX uq_tenant_name',
    'SELECT 1'
);
PREPARE marketing_template_drop_legacy_unique_stmt FROM @marketing_template_drop_legacy_unique_ddl;
EXECUTE marketing_template_drop_legacy_unique_stmt;
DEALLOCATE PREPARE marketing_template_drop_legacy_unique_stmt;
