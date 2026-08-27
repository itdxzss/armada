-- 新建普群任务用户归属。历史行不根据 created_by 猜测 owner，NULL 仅管理员可见。

SET @normal_group_creation_owner_column_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'normal_group_creation_task'
       AND column_name = 'owner_user_id') = 0,
    'ALTER TABLE normal_group_creation_task ADD COLUMN owner_user_id BIGINT DEFAULT NULL COMMENT ''归属用户ID;NULL为待管理员显式分配的历史数据'' AFTER tenant_id',
    'SELECT 1'
);
PREPARE normal_group_creation_owner_column_stmt
    FROM @normal_group_creation_owner_column_ddl;
EXECUTE normal_group_creation_owner_column_stmt;
DEALLOCATE PREPARE normal_group_creation_owner_column_stmt;

-- MySQL UNIQUE 不判定两个 NULL 相等；生成列保证历史 NULL owner 幂等键仍租户内唯一。
SET @normal_group_creation_unowned_idem_column_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'normal_group_creation_task'
       AND column_name = 'unowned_idempotency_key') = 0,
    'ALTER TABLE normal_group_creation_task ADD COLUMN unowned_idempotency_key VARCHAR(64) GENERATED ALWAYS AS (IF(owner_user_id IS NULL, idempotency_key, NULL)) VIRTUAL COMMENT ''无归属任务幂等键唯一辅助'' AFTER idempotency_key',
    'SELECT 1'
);
PREPARE normal_group_creation_unowned_idem_column_stmt
    FROM @normal_group_creation_unowned_idem_column_ddl;
EXECUTE normal_group_creation_unowned_idem_column_stmt;
DEALLOCATE PREPARE normal_group_creation_unowned_idem_column_stmt;

SET @normal_group_creation_owner_index_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'normal_group_creation_task'
       AND index_name = 'idx_normal_group_creation_task_owner') = 0,
    'ALTER TABLE normal_group_creation_task ADD KEY idx_normal_group_creation_task_owner (tenant_id, owner_user_id, deleted_at, status, created_at, id)',
    'SELECT 1'
);
PREPARE normal_group_creation_owner_index_stmt
    FROM @normal_group_creation_owner_index_ddl;
EXECUTE normal_group_creation_owner_index_stmt;
DEALLOCATE PREPARE normal_group_creation_owner_index_stmt;

-- 先建新唯一键，验证列序与 UNIQUE 属性后再移除旧租户级唯一键。
SET @normal_group_creation_owner_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'normal_group_creation_task'
       AND index_name = 'uq_normal_group_creation_task_owner_idem') = 0,
    'ALTER TABLE normal_group_creation_task ADD UNIQUE KEY uq_normal_group_creation_task_owner_idem (tenant_id, owner_user_id, idempotency_key)',
    'SELECT 1'
);
PREPARE normal_group_creation_owner_unique_stmt
    FROM @normal_group_creation_owner_unique_ddl;
EXECUTE normal_group_creation_owner_unique_stmt;
DEALLOCATE PREPARE normal_group_creation_owner_unique_stmt;

SET @normal_group_creation_unowned_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'normal_group_creation_task'
       AND index_name = 'uq_normal_group_creation_task_unowned_idem') = 0,
    'ALTER TABLE normal_group_creation_task ADD UNIQUE KEY uq_normal_group_creation_task_unowned_idem (tenant_id, unowned_idempotency_key)',
    'SELECT 1'
);
PREPARE normal_group_creation_unowned_unique_stmt
    FROM @normal_group_creation_unowned_unique_ddl;
EXECUTE normal_group_creation_unowned_unique_stmt;
DEALLOCATE PREPARE normal_group_creation_unowned_unique_stmt;

SET @normal_group_creation_legacy_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'normal_group_creation_task'
      AND index_name = 'uq_normal_group_creation_task_idem'
);
SET @normal_group_creation_legacy_unique_non_unique := (
    SELECT MAX(non_unique)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'normal_group_creation_task'
      AND index_name = 'uq_normal_group_creation_task_idem'
);
SET @normal_group_creation_owner_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'normal_group_creation_task'
      AND index_name = 'uq_normal_group_creation_task_owner_idem'
);
SET @normal_group_creation_owner_unique_non_unique := (
    SELECT MAX(non_unique)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'normal_group_creation_task'
      AND index_name = 'uq_normal_group_creation_task_owner_idem'
);
SET @normal_group_creation_unowned_unique_columns := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'normal_group_creation_task'
      AND index_name = 'uq_normal_group_creation_task_unowned_idem'
);
SET @normal_group_creation_unowned_unique_non_unique := (
    SELECT MAX(non_unique)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'normal_group_creation_task'
      AND index_name = 'uq_normal_group_creation_task_unowned_idem'
);

DROP TEMPORARY TABLE IF EXISTS tmp_v148_normal_group_creation_index_guard;
CREATE TEMPORARY TABLE tmp_v148_normal_group_creation_index_guard (
    guard_key TINYINT NOT NULL PRIMARY KEY
);
INSERT INTO tmp_v148_normal_group_creation_index_guard (guard_key) VALUES (1);
INSERT INTO tmp_v148_normal_group_creation_index_guard (guard_key)
SELECT 1
WHERE COALESCE(@normal_group_creation_owner_unique_columns, '')
          <> 'tenant_id,owner_user_id,idempotency_key'
   OR COALESCE(@normal_group_creation_owner_unique_non_unique, 1) <> 0
   OR COALESCE(@normal_group_creation_unowned_unique_columns, '')
          <> 'tenant_id,unowned_idempotency_key'
   OR COALESCE(@normal_group_creation_unowned_unique_non_unique, 1) <> 0
   OR (@normal_group_creation_legacy_unique_columns IS NOT NULL
       AND (@normal_group_creation_legacy_unique_columns <> 'tenant_id,idempotency_key'
            OR COALESCE(@normal_group_creation_legacy_unique_non_unique, 1) <> 0));
DROP TEMPORARY TABLE tmp_v148_normal_group_creation_index_guard;

SET @normal_group_creation_drop_legacy_unique_ddl := IF(
    @normal_group_creation_owner_unique_columns = 'tenant_id,owner_user_id,idempotency_key'
      AND @normal_group_creation_owner_unique_non_unique = 0
      AND @normal_group_creation_unowned_unique_columns = 'tenant_id,unowned_idempotency_key'
      AND @normal_group_creation_unowned_unique_non_unique = 0
      AND (@normal_group_creation_legacy_unique_columns IS NULL
           OR (@normal_group_creation_legacy_unique_columns = 'tenant_id,idempotency_key'
               AND @normal_group_creation_legacy_unique_non_unique = 0)),
    IF(@normal_group_creation_legacy_unique_columns IS NULL, 'SELECT 1',
       'ALTER TABLE normal_group_creation_task DROP INDEX uq_normal_group_creation_task_idem'),
    'SELECT 1'
);
PREPARE normal_group_creation_drop_legacy_unique_stmt
    FROM @normal_group_creation_drop_legacy_unique_ddl;
EXECUTE normal_group_creation_drop_legacy_unique_stmt;
DEALLOCATE PREPARE normal_group_creation_drop_legacy_unique_stmt;
