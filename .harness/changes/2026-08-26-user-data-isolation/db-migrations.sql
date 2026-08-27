-- 正式迁移文件：
--   armada-api/src/main/resources/db/migration/V140__account_user_data_ownership.sql
--   armada-api/src/main/resources/db/migration/V141__marketing_template_user_data_ownership.sql
--   armada-api/src/main/resources/db/migration/V142__marketing_task_user_data_ownership.sql
--   armada-api/src/main/resources/db/migration/V143__group_creation_marketing_task_user_data_ownership.sql
--   armada-api/src/main/resources/db/migration/V144__join_task_user_data_ownership.sql
--   armada-api/src/main/resources/db/migration/V145__pull_task_user_data_ownership.sql
--   armada-api/src/main/resources/db/migration/V146__pull_task_group_avatar_user_data_ownership.sql
--   armada-api/src/main/resources/db/migration/V147__group_user_data_ownership.sql
--   armada-api/src/main/resources/db/migration/V148__normal_group_creation_user_data_ownership.sql
--   armada-api/src/main/resources/db/migration/V149__historical_group_pull_user_data_ownership.sql
--   armada-api/src/main/resources/db/migration/V150__promotion_capi_outbox_user_data_ownership.sql
--   armada-api/src/main/resources/db/migration/V151__marketing_export_job_data_scope.sql
-- 本文件保留 V140 的完整审计副本；V141-V151 以同目录正式 Flyway 文件为完整审计源。
-- 共享数据库结构变更仍只允许由 Flyway 执行。

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

-- V141 审计摘要：
-- 1. marketing_template / marketing_template_file 增加 nullable owner_user_id，历史数据不回填。
-- 2. 两表增加 (tenant_id, owner_user_id, deleted_at, id) 查询索引。
-- 3. marketing_template 增加 active_name_key / unowned_name_key 生成列。
-- 4. 新建 uq_marketing_template_owner_name 与 uq_marketing_template_unowned_name，
--    校验索引列序和 UNIQUE 属性后才删除旧 uq_tenant_name。
-- 5. 完整、可执行 SQL 仅位于 V141 Flyway 文件；禁止人工拼接本摘要执行。

-- V142 审计摘要：
-- 1. marketing_task 增加 nullable owner_user_id，历史数据不回填。
-- 2. 增加 (tenant_id, owner_user_id, business_type, deleted_at, id) 查询索引。
-- 3. 普通营销与拉群营销共用此聚合根；target、attempt 和导出事实通过父任务继承。

-- V143 审计摘要：
-- 1. group_creation_marketing_task 增加 nullable owner_user_id，历史数据不回填。
-- 2. 增加 (tenant_id, owner_user_id, deleted_at, id) 查询索引。
-- 3. group_creation_marketing_item 通过 task_id 继承归属，不重复保存 owner。
-- 4. 完整、可执行 SQL 仅位于 V142/V143 Flyway 文件；禁止人工拼接本摘要执行。

-- V144 审计摘要：
-- 1. join_task 增加 nullable owner_user_id，历史数据不回填。
-- 2. 增加 (tenant_id, owner_user_id, deleted_at, id) 查询索引。
-- 3. join_task_result 通过 join_task_id 继承归属，不重复保存 owner。
-- 4. 完整、可执行 SQL 仅位于 V144 Flyway 文件；禁止人工拼接本摘要执行。

-- V145 审计摘要：
-- 1. pull_task 增加 nullable owner_user_id，历史数据不回填。
-- 2. 增加 (tenant_id, owner_user_id, deleted_at, id) 查询索引。
-- 3. 草稿、设置、执行行、账号行、动作和结果均通过 task_id 继承归属，不重复保存 owner。
-- 4. 完整、可执行 SQL 仅位于 V145 Flyway 文件；禁止人工拼接本摘要执行。

-- V146 审计摘要：
-- 1. 新建 pull_task_group_avatar_file，保存本地随机文件 key 的可信 owner 元数据。
-- 2. owner_user_id 对新元数据强制 NOT NULL；历史磁盘文件不猜测、不回填。
-- 3. 文件 key 保持租户内唯一，并增加 (tenant_id, owner_user_id, id) 查询索引。
-- 4. 二进制仍在原租户目录，不复制进数据库；完整 SQL 仅位于 V146 Flyway 文件。

-- V147 审计摘要：
-- 1. group_link / group_folder / group_link_label / group_link_import_batch /
--    group_batch_task 增加 nullable owner_user_id，历史数据不回填。
-- 2. group_link 是用户运营句柄；wa_group / wa_group_invite 继续保持租户级 canonical 协议事实。
-- 3. URL、文件夹名、WS 链接分组名和批处理 request_id 改为 owner 内唯一；
--    NULL owner 通过生成列仍保持租户内唯一。
-- 4. 新唯一键会校验列序和 UNIQUE 属性，通过后才删除旧租户级唯一键。
-- 5. 完整、可执行 SQL 仅位于 V147 Flyway 文件；禁止人工拼接本摘要执行。

-- V148 审计摘要：
-- 1. normal_group_creation_task 增加 nullable owner_user_id，历史数据不回填。
-- 2. 任务项、普通成员和次管理员冻结快照通过 task_id 继承 owner。
-- 3. 幂等键改为 owner 内唯一；NULL owner 通过 unowned_idempotency_key 仍租户内唯一。
-- 4. 新唯一键会校验列序和 UNIQUE 属性，通过后才删除旧租户级唯一键。
-- 5. 完整、可执行 SQL 仅位于 V148 Flyway 文件；禁止人工拼接本摘要执行。
