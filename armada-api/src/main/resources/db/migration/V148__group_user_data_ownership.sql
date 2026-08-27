-- 群域用户归属：group_link 是用户操作句柄，wa_group/wa_group_invite 继续承载租户共享的协议事实。
-- 历史行不根据 created_by 猜测归属，owner_user_id 保持 NULL，仅租户管理员可见。

SET @group_link_owner_column_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_link'
       AND column_name = 'owner_user_id') = 0,
    'ALTER TABLE group_link ADD COLUMN owner_user_id BIGINT DEFAULT NULL COMMENT ''归属用户ID;NULL为待管理员显式分配的历史数据'' AFTER tenant_id',
    'SELECT 1'
);
PREPARE group_link_owner_column_stmt FROM @group_link_owner_column_ddl;
EXECUTE group_link_owner_column_stmt;
DEALLOCATE PREPARE group_link_owner_column_stmt;

SET @group_folder_owner_column_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_folder'
       AND column_name = 'owner_user_id') = 0,
    'ALTER TABLE group_folder ADD COLUMN owner_user_id BIGINT DEFAULT NULL COMMENT ''归属用户ID;NULL为待管理员显式分配的历史数据'' AFTER tenant_id',
    'SELECT 1'
);
PREPARE group_folder_owner_column_stmt FROM @group_folder_owner_column_ddl;
EXECUTE group_folder_owner_column_stmt;
DEALLOCATE PREPARE group_folder_owner_column_stmt;

SET @group_link_label_owner_column_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_link_label'
       AND column_name = 'owner_user_id') = 0,
    'ALTER TABLE group_link_label ADD COLUMN owner_user_id BIGINT DEFAULT NULL COMMENT ''归属用户ID;NULL为待管理员显式分配的历史数据'' AFTER tenant_id',
    'SELECT 1'
);
PREPARE group_link_label_owner_column_stmt FROM @group_link_label_owner_column_ddl;
EXECUTE group_link_label_owner_column_stmt;
DEALLOCATE PREPARE group_link_label_owner_column_stmt;

SET @group_link_import_batch_owner_column_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_link_import_batch'
       AND column_name = 'owner_user_id') = 0,
    'ALTER TABLE group_link_import_batch ADD COLUMN owner_user_id BIGINT DEFAULT NULL COMMENT ''归属用户ID;NULL为待管理员显式分配的历史数据'' AFTER tenant_id',
    'SELECT 1'
);
PREPARE group_link_import_batch_owner_column_stmt
    FROM @group_link_import_batch_owner_column_ddl;
EXECUTE group_link_import_batch_owner_column_stmt;
DEALLOCATE PREPARE group_link_import_batch_owner_column_stmt;

SET @group_batch_task_owner_column_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_batch_task'
       AND column_name = 'owner_user_id') = 0,
    'ALTER TABLE group_batch_task ADD COLUMN owner_user_id BIGINT DEFAULT NULL COMMENT ''归属用户ID;NULL为待管理员显式分配的历史数据'' AFTER tenant_id',
    'SELECT 1'
);
PREPARE group_batch_task_owner_column_stmt FROM @group_batch_task_owner_column_ddl;
EXECUTE group_batch_task_owner_column_stmt;
DEALLOCATE PREPARE group_batch_task_owner_column_stmt;

-- MySQL UNIQUE 不判定两个 NULL 相等；生成列继续约束历史 NULL owner 行的租户级唯一性。
SET @group_link_unowned_url_column_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_link'
       AND column_name = 'unowned_url_key') = 0,
    'ALTER TABLE group_link ADD COLUMN unowned_url_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS (IF(owner_user_id IS NULL, link_url, NULL)) VIRTUAL COMMENT ''无归属群入口URL唯一键辅助'' AFTER link_url',
    'SELECT 1'
);
PREPARE group_link_unowned_url_column_stmt FROM @group_link_unowned_url_column_ddl;
EXECUTE group_link_unowned_url_column_stmt;
DEALLOCATE PREPARE group_link_unowned_url_column_stmt;

SET @group_folder_unowned_name_column_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_folder'
       AND column_name = 'unowned_name_key') = 0,
    'ALTER TABLE group_folder ADD COLUMN unowned_name_key VARCHAR(64) GENERATED ALWAYS AS (IF(owner_user_id IS NULL, name, NULL)) VIRTUAL COMMENT ''无归属群文件夹名称唯一键辅助'' AFTER name',
    'SELECT 1'
);
PREPARE group_folder_unowned_name_column_stmt FROM @group_folder_unowned_name_column_ddl;
EXECUTE group_folder_unowned_name_column_stmt;
DEALLOCATE PREPARE group_folder_unowned_name_column_stmt;

SET @group_link_label_unowned_name_column_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_link_label'
       AND column_name = 'unowned_name_key') = 0,
    'ALTER TABLE group_link_label ADD COLUMN unowned_name_key VARCHAR(100) GENERATED ALWAYS AS (IF(owner_user_id IS NULL, name, NULL)) VIRTUAL COMMENT ''无归属WS链接分组名称唯一键辅助'' AFTER name',
    'SELECT 1'
);
PREPARE group_link_label_unowned_name_column_stmt
    FROM @group_link_label_unowned_name_column_ddl;
EXECUTE group_link_label_unowned_name_column_stmt;
DEALLOCATE PREPARE group_link_label_unowned_name_column_stmt;

SET @group_batch_task_unowned_request_column_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_batch_task'
       AND column_name = 'unowned_request_key') = 0,
    'ALTER TABLE group_batch_task ADD COLUMN unowned_request_key VARCHAR(64) GENERATED ALWAYS AS (IF(owner_user_id IS NULL, request_id, NULL)) VIRTUAL COMMENT ''无归属批量任务幂等键辅助'' AFTER request_id',
    'SELECT 1'
);
PREPARE group_batch_task_unowned_request_column_stmt
    FROM @group_batch_task_unowned_request_column_ddl;
EXECUTE group_batch_task_unowned_request_column_stmt;
DEALLOCATE PREPARE group_batch_task_unowned_request_column_stmt;

SET @group_link_owner_index_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_link'
       AND index_name = 'idx_group_link_owner') = 0,
    'ALTER TABLE group_link ADD KEY idx_group_link_owner (tenant_id, owner_user_id, deleted_at, id)',
    'SELECT 1'
);
PREPARE group_link_owner_index_stmt FROM @group_link_owner_index_ddl;
EXECUTE group_link_owner_index_stmt;
DEALLOCATE PREPARE group_link_owner_index_stmt;

SET @group_folder_owner_index_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_folder'
       AND index_name = 'idx_group_folder_owner') = 0,
    'ALTER TABLE group_folder ADD KEY idx_group_folder_owner (tenant_id, owner_user_id, deleted_at, id)',
    'SELECT 1'
);
PREPARE group_folder_owner_index_stmt FROM @group_folder_owner_index_ddl;
EXECUTE group_folder_owner_index_stmt;
DEALLOCATE PREPARE group_folder_owner_index_stmt;

SET @group_link_label_owner_index_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_link_label'
       AND index_name = 'idx_group_link_label_owner') = 0,
    'ALTER TABLE group_link_label ADD KEY idx_group_link_label_owner (tenant_id, owner_user_id, deleted_at, id)',
    'SELECT 1'
);
PREPARE group_link_label_owner_index_stmt FROM @group_link_label_owner_index_ddl;
EXECUTE group_link_label_owner_index_stmt;
DEALLOCATE PREPARE group_link_label_owner_index_stmt;

SET @group_link_import_batch_owner_index_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_link_import_batch'
       AND index_name = 'idx_group_link_import_batch_owner') = 0,
    'ALTER TABLE group_link_import_batch ADD KEY idx_group_link_import_batch_owner (tenant_id, owner_user_id, deleted_at, created_at, id)',
    'SELECT 1'
);
PREPARE group_link_import_batch_owner_index_stmt
    FROM @group_link_import_batch_owner_index_ddl;
EXECUTE group_link_import_batch_owner_index_stmt;
DEALLOCATE PREPARE group_link_import_batch_owner_index_stmt;

SET @group_batch_task_owner_index_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_batch_task'
       AND index_name = 'idx_group_batch_task_owner') = 0,
    'ALTER TABLE group_batch_task ADD KEY idx_group_batch_task_owner (tenant_id, owner_user_id, status, id)',
    'SELECT 1'
);
PREPARE group_batch_task_owner_index_stmt FROM @group_batch_task_owner_index_ddl;
EXECUTE group_batch_task_owner_index_stmt;
DEALLOCATE PREPARE group_batch_task_owner_index_stmt;

-- 先建立新唯一键，再验证列序和 UNIQUE 属性，最后才移除旧租户级唯一键。
SET @group_link_owner_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_link'
       AND index_name = 'uq_group_link_owner_url') = 0,
    'ALTER TABLE group_link ADD UNIQUE KEY uq_group_link_owner_url (tenant_id, owner_user_id, link_url)',
    'SELECT 1'
);
PREPARE group_link_owner_unique_stmt FROM @group_link_owner_unique_ddl;
EXECUTE group_link_owner_unique_stmt;
DEALLOCATE PREPARE group_link_owner_unique_stmt;

SET @group_link_unowned_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_link'
       AND index_name = 'uq_group_link_unowned_url') = 0,
    'ALTER TABLE group_link ADD UNIQUE KEY uq_group_link_unowned_url (tenant_id, unowned_url_key)',
    'SELECT 1'
);
PREPARE group_link_unowned_unique_stmt FROM @group_link_unowned_unique_ddl;
EXECUTE group_link_unowned_unique_stmt;
DEALLOCATE PREPARE group_link_unowned_unique_stmt;

SET @group_link_legacy_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_link' AND index_name = 'uq_url'
);
SET @group_link_legacy_unique_non_unique := (
    SELECT MAX(non_unique) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_link' AND index_name = 'uq_url'
);
SET @group_link_owner_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_link'
      AND index_name = 'uq_group_link_owner_url'
);
SET @group_link_owner_unique_non_unique := (
    SELECT MAX(non_unique) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_link'
      AND index_name = 'uq_group_link_owner_url'
);
SET @group_link_unowned_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_link'
      AND index_name = 'uq_group_link_unowned_url'
);
SET @group_link_unowned_unique_non_unique := (
    SELECT MAX(non_unique) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_link'
      AND index_name = 'uq_group_link_unowned_url'
);
DROP TEMPORARY TABLE IF EXISTS tmp_v147_group_link_index_guard;
CREATE TEMPORARY TABLE tmp_v147_group_link_index_guard (guard_key TINYINT NOT NULL PRIMARY KEY);
INSERT INTO tmp_v147_group_link_index_guard (guard_key) VALUES (1);
INSERT INTO tmp_v147_group_link_index_guard (guard_key)
SELECT 1
WHERE COALESCE(@group_link_owner_unique_columns, '') <> 'tenant_id,owner_user_id,link_url'
   OR COALESCE(@group_link_owner_unique_non_unique, 1) <> 0
   OR COALESCE(@group_link_unowned_unique_columns, '') <> 'tenant_id,unowned_url_key'
   OR COALESCE(@group_link_unowned_unique_non_unique, 1) <> 0
   OR (@group_link_legacy_unique_columns IS NOT NULL
       AND (@group_link_legacy_unique_columns <> 'tenant_id,link_url'
            OR COALESCE(@group_link_legacy_unique_non_unique, 1) <> 0));
DROP TEMPORARY TABLE tmp_v147_group_link_index_guard;
SET @group_link_drop_legacy_unique_ddl := IF(
    @group_link_owner_unique_columns = 'tenant_id,owner_user_id,link_url'
      AND @group_link_owner_unique_non_unique = 0
      AND @group_link_unowned_unique_columns = 'tenant_id,unowned_url_key'
      AND @group_link_unowned_unique_non_unique = 0
      AND (@group_link_legacy_unique_columns IS NULL
           OR (@group_link_legacy_unique_columns = 'tenant_id,link_url'
               AND @group_link_legacy_unique_non_unique = 0)),
    IF(@group_link_legacy_unique_columns IS NULL, 'SELECT 1',
       'ALTER TABLE group_link DROP INDEX uq_url'),
    'SELECT 1'
);
PREPARE group_link_drop_legacy_unique_stmt FROM @group_link_drop_legacy_unique_ddl;
EXECUTE group_link_drop_legacy_unique_stmt;
DEALLOCATE PREPARE group_link_drop_legacy_unique_stmt;

SET @group_folder_owner_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_folder'
       AND index_name = 'uq_group_folder_owner_name') = 0,
    'ALTER TABLE group_folder ADD UNIQUE KEY uq_group_folder_owner_name (tenant_id, owner_user_id, name)',
    'SELECT 1'
);
PREPARE group_folder_owner_unique_stmt FROM @group_folder_owner_unique_ddl;
EXECUTE group_folder_owner_unique_stmt;
DEALLOCATE PREPARE group_folder_owner_unique_stmt;
SET @group_folder_unowned_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_folder'
       AND index_name = 'uq_group_folder_unowned_name') = 0,
    'ALTER TABLE group_folder ADD UNIQUE KEY uq_group_folder_unowned_name (tenant_id, unowned_name_key)',
    'SELECT 1'
);
PREPARE group_folder_unowned_unique_stmt FROM @group_folder_unowned_unique_ddl;
EXECUTE group_folder_unowned_unique_stmt;
DEALLOCATE PREPARE group_folder_unowned_unique_stmt;
SET @group_folder_legacy_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_folder'
      AND index_name = 'uq_group_folder_name'
);
SET @group_folder_legacy_unique_non_unique := (
    SELECT MAX(non_unique) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_folder'
      AND index_name = 'uq_group_folder_name'
);
SET @group_folder_owner_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_folder'
      AND index_name = 'uq_group_folder_owner_name'
);
SET @group_folder_owner_unique_non_unique := (
    SELECT MAX(non_unique) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_folder'
      AND index_name = 'uq_group_folder_owner_name'
);
SET @group_folder_unowned_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_folder'
      AND index_name = 'uq_group_folder_unowned_name'
);
SET @group_folder_unowned_unique_non_unique := (
    SELECT MAX(non_unique) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_folder'
      AND index_name = 'uq_group_folder_unowned_name'
);
DROP TEMPORARY TABLE IF EXISTS tmp_v147_group_folder_index_guard;
CREATE TEMPORARY TABLE tmp_v147_group_folder_index_guard (guard_key TINYINT NOT NULL PRIMARY KEY);
INSERT INTO tmp_v147_group_folder_index_guard (guard_key) VALUES (1);
INSERT INTO tmp_v147_group_folder_index_guard (guard_key)
SELECT 1
WHERE COALESCE(@group_folder_owner_unique_columns, '') <> 'tenant_id,owner_user_id,name'
   OR COALESCE(@group_folder_owner_unique_non_unique, 1) <> 0
   OR COALESCE(@group_folder_unowned_unique_columns, '') <> 'tenant_id,unowned_name_key'
   OR COALESCE(@group_folder_unowned_unique_non_unique, 1) <> 0
   OR (@group_folder_legacy_unique_columns IS NOT NULL
       AND (@group_folder_legacy_unique_columns <> 'tenant_id,name'
            OR COALESCE(@group_folder_legacy_unique_non_unique, 1) <> 0));
DROP TEMPORARY TABLE tmp_v147_group_folder_index_guard;
SET @group_folder_drop_legacy_unique_ddl := IF(
    @group_folder_owner_unique_columns = 'tenant_id,owner_user_id,name'
      AND @group_folder_owner_unique_non_unique = 0
      AND @group_folder_unowned_unique_columns = 'tenant_id,unowned_name_key'
      AND @group_folder_unowned_unique_non_unique = 0
      AND (@group_folder_legacy_unique_columns IS NULL
           OR (@group_folder_legacy_unique_columns = 'tenant_id,name'
               AND @group_folder_legacy_unique_non_unique = 0)),
    IF(@group_folder_legacy_unique_columns IS NULL, 'SELECT 1',
       'ALTER TABLE group_folder DROP INDEX uq_group_folder_name'),
    'SELECT 1'
);
PREPARE group_folder_drop_legacy_unique_stmt FROM @group_folder_drop_legacy_unique_ddl;
EXECUTE group_folder_drop_legacy_unique_stmt;
DEALLOCATE PREPARE group_folder_drop_legacy_unique_stmt;

SET @group_link_label_owner_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_link_label'
       AND index_name = 'uq_group_link_label_owner_name') = 0,
    'ALTER TABLE group_link_label ADD UNIQUE KEY uq_group_link_label_owner_name (tenant_id, owner_user_id, name)',
    'SELECT 1'
);
PREPARE group_link_label_owner_unique_stmt FROM @group_link_label_owner_unique_ddl;
EXECUTE group_link_label_owner_unique_stmt;
DEALLOCATE PREPARE group_link_label_owner_unique_stmt;
SET @group_link_label_unowned_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_link_label'
       AND index_name = 'uq_group_link_label_unowned_name') = 0,
    'ALTER TABLE group_link_label ADD UNIQUE KEY uq_group_link_label_unowned_name (tenant_id, unowned_name_key)',
    'SELECT 1'
);
PREPARE group_link_label_unowned_unique_stmt FROM @group_link_label_unowned_unique_ddl;
EXECUTE group_link_label_unowned_unique_stmt;
DEALLOCATE PREPARE group_link_label_unowned_unique_stmt;
SET @group_link_label_legacy_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_link_label' AND index_name = 'uq_name'
);
SET @group_link_label_legacy_unique_non_unique := (
    SELECT MAX(non_unique) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_link_label' AND index_name = 'uq_name'
);
SET @group_link_label_owner_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_link_label'
      AND index_name = 'uq_group_link_label_owner_name'
);
SET @group_link_label_owner_unique_non_unique := (
    SELECT MAX(non_unique) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_link_label'
      AND index_name = 'uq_group_link_label_owner_name'
);
SET @group_link_label_unowned_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_link_label'
      AND index_name = 'uq_group_link_label_unowned_name'
);
SET @group_link_label_unowned_unique_non_unique := (
    SELECT MAX(non_unique) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_link_label'
      AND index_name = 'uq_group_link_label_unowned_name'
);
DROP TEMPORARY TABLE IF EXISTS tmp_v147_group_link_label_index_guard;
CREATE TEMPORARY TABLE tmp_v147_group_link_label_index_guard (guard_key TINYINT NOT NULL PRIMARY KEY);
INSERT INTO tmp_v147_group_link_label_index_guard (guard_key) VALUES (1);
INSERT INTO tmp_v147_group_link_label_index_guard (guard_key)
SELECT 1
WHERE COALESCE(@group_link_label_owner_unique_columns, '') <> 'tenant_id,owner_user_id,name'
   OR COALESCE(@group_link_label_owner_unique_non_unique, 1) <> 0
   OR COALESCE(@group_link_label_unowned_unique_columns, '') <> 'tenant_id,unowned_name_key'
   OR COALESCE(@group_link_label_unowned_unique_non_unique, 1) <> 0
   OR (@group_link_label_legacy_unique_columns IS NOT NULL
       AND (@group_link_label_legacy_unique_columns <> 'tenant_id,name'
            OR COALESCE(@group_link_label_legacy_unique_non_unique, 1) <> 0));
DROP TEMPORARY TABLE tmp_v147_group_link_label_index_guard;
SET @group_link_label_drop_legacy_unique_ddl := IF(
    @group_link_label_owner_unique_columns = 'tenant_id,owner_user_id,name'
      AND @group_link_label_owner_unique_non_unique = 0
      AND @group_link_label_unowned_unique_columns = 'tenant_id,unowned_name_key'
      AND @group_link_label_unowned_unique_non_unique = 0
      AND (@group_link_label_legacy_unique_columns IS NULL
           OR (@group_link_label_legacy_unique_columns = 'tenant_id,name'
               AND @group_link_label_legacy_unique_non_unique = 0)),
    IF(@group_link_label_legacy_unique_columns IS NULL, 'SELECT 1',
       'ALTER TABLE group_link_label DROP INDEX uq_name'),
    'SELECT 1'
);
PREPARE group_link_label_drop_legacy_unique_stmt FROM @group_link_label_drop_legacy_unique_ddl;
EXECUTE group_link_label_drop_legacy_unique_stmt;
DEALLOCATE PREPARE group_link_label_drop_legacy_unique_stmt;

SET @group_batch_task_owner_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_batch_task'
       AND index_name = 'uq_group_batch_task_owner_request') = 0,
    'ALTER TABLE group_batch_task ADD UNIQUE KEY uq_group_batch_task_owner_request (tenant_id, owner_user_id, request_id)',
    'SELECT 1'
);
PREPARE group_batch_task_owner_unique_stmt FROM @group_batch_task_owner_unique_ddl;
EXECUTE group_batch_task_owner_unique_stmt;
DEALLOCATE PREPARE group_batch_task_owner_unique_stmt;
SET @group_batch_task_unowned_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_batch_task'
       AND index_name = 'uq_group_batch_task_unowned_request') = 0,
    'ALTER TABLE group_batch_task ADD UNIQUE KEY uq_group_batch_task_unowned_request (tenant_id, unowned_request_key)',
    'SELECT 1'
);
PREPARE group_batch_task_unowned_unique_stmt FROM @group_batch_task_unowned_unique_ddl;
EXECUTE group_batch_task_unowned_unique_stmt;
DEALLOCATE PREPARE group_batch_task_unowned_unique_stmt;
SET @group_batch_task_legacy_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_batch_task'
      AND index_name = 'uq_group_batch_task_request'
);
SET @group_batch_task_legacy_unique_non_unique := (
    SELECT MAX(non_unique) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_batch_task'
      AND index_name = 'uq_group_batch_task_request'
);
SET @group_batch_task_owner_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_batch_task'
      AND index_name = 'uq_group_batch_task_owner_request'
);
SET @group_batch_task_owner_unique_non_unique := (
    SELECT MAX(non_unique) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_batch_task'
      AND index_name = 'uq_group_batch_task_owner_request'
);
SET @group_batch_task_unowned_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_batch_task'
      AND index_name = 'uq_group_batch_task_unowned_request'
);
SET @group_batch_task_unowned_unique_non_unique := (
    SELECT MAX(non_unique) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'group_batch_task'
      AND index_name = 'uq_group_batch_task_unowned_request'
);
DROP TEMPORARY TABLE IF EXISTS tmp_v147_group_batch_task_index_guard;
CREATE TEMPORARY TABLE tmp_v147_group_batch_task_index_guard (guard_key TINYINT NOT NULL PRIMARY KEY);
INSERT INTO tmp_v147_group_batch_task_index_guard (guard_key) VALUES (1);
INSERT INTO tmp_v147_group_batch_task_index_guard (guard_key)
SELECT 1
WHERE COALESCE(@group_batch_task_owner_unique_columns, '') <> 'tenant_id,owner_user_id,request_id'
   OR COALESCE(@group_batch_task_owner_unique_non_unique, 1) <> 0
   OR COALESCE(@group_batch_task_unowned_unique_columns, '') <> 'tenant_id,unowned_request_key'
   OR COALESCE(@group_batch_task_unowned_unique_non_unique, 1) <> 0
   OR (@group_batch_task_legacy_unique_columns IS NOT NULL
       AND (@group_batch_task_legacy_unique_columns <> 'tenant_id,request_id'
            OR COALESCE(@group_batch_task_legacy_unique_non_unique, 1) <> 0));
DROP TEMPORARY TABLE tmp_v147_group_batch_task_index_guard;
SET @group_batch_task_drop_legacy_unique_ddl := IF(
    @group_batch_task_owner_unique_columns = 'tenant_id,owner_user_id,request_id'
      AND @group_batch_task_owner_unique_non_unique = 0
      AND @group_batch_task_unowned_unique_columns = 'tenant_id,unowned_request_key'
      AND @group_batch_task_unowned_unique_non_unique = 0
      AND (@group_batch_task_legacy_unique_columns IS NULL
           OR (@group_batch_task_legacy_unique_columns = 'tenant_id,request_id'
               AND @group_batch_task_legacy_unique_non_unique = 0)),
    IF(@group_batch_task_legacy_unique_columns IS NULL, 'SELECT 1',
       'ALTER TABLE group_batch_task DROP INDEX uq_group_batch_task_request'),
    'SELECT 1'
);
PREPARE group_batch_task_drop_legacy_unique_stmt FROM @group_batch_task_drop_legacy_unique_ddl;
EXECUTE group_batch_task_drop_legacy_unique_stmt;
DEALLOCATE PREPARE group_batch_task_drop_legacy_unique_stmt;
