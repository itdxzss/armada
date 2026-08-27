-- V140-V151 开始承接新版本流量后，禁止直接回退到不识别 owner/scope 的旧应用。
-- 首选方案是在 owner-aware schema 上前向修复，或部署仍兼容 owner 的上一构建。
-- 只有持续停止所有分组和模板写入、确认没有同租户同名活跃数据，并执行本文件恢复
-- account_group / marketing_template 旧名称索引，以及 V147 的旧 URL/名称/request_id
-- 索引后，才允许切回旧应用。
-- 本脚本不会删除 owner_user_id 或改写归属数据，也不会修改 flyway_schema_history。
-- V142/V143/V144/V145 没有替换唯一键，无额外结构逆操作；其任务 owner 列和查询索引刻意保留，
-- 以支持审计、前向修复和重新上线。旧应用会继续写 NULL owner，因此不允许恢复旧写流量。
-- V146 新增的头像元数据表也刻意保留；删除它会丢失新头像归属并使普通用户无法访问已上传文件。
-- V147 的 owner 列、生成列和 owner 索引同样保留；本文件只在无冲突时恢复旧唯一键。
-- V148 的新建普群任务 owner 结构也保留；旧幂等键只在无跨 owner 重复时恢复。
-- V149 的历史群 owner/幂等结构刻意保留；恢复旧幂等键前同样必须确认无跨 owner 冲突。
-- V150/V151 的 CAPI owner 与导出 scope 快照刻意保留；历史 NULL 记录不能回填猜测值。

-- V141：不同 owner 已产生同名活跃模板时，旧应用无法安全解释；先列出并 fail-fast。
SELECT tenant_id,
       template_name,
       COUNT(*) AS active_template_count
FROM marketing_template
WHERE deleted_at IS NULL
GROUP BY tenant_id, template_name
HAVING COUNT(*) > 1
ORDER BY tenant_id, template_name;

DROP TEMPORARY TABLE IF EXISTS tmp_v141_template_name_rollback_guard;
CREATE TEMPORARY TABLE tmp_v141_template_name_rollback_guard (
    guard_key TINYINT NOT NULL PRIMARY KEY
);
INSERT INTO tmp_v141_template_name_rollback_guard (guard_key) VALUES (1);
INSERT INTO tmp_v141_template_name_rollback_guard (guard_key)
SELECT 1
FROM marketing_template
WHERE deleted_at IS NULL
GROUP BY tenant_id, template_name
HAVING COUNT(*) > 1
LIMIT 1;
DROP TEMPORARY TABLE tmp_v141_template_name_rollback_guard;

SET @marketing_template_legacy_unique_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'marketing_template'
       AND index_name = 'uq_tenant_name') = 0,
    'ALTER TABLE marketing_template ADD UNIQUE KEY uq_tenant_name (tenant_id, template_name, deleted_at)',
    'SELECT 1'
);
PREPARE marketing_template_legacy_unique_stmt FROM @marketing_template_legacy_unique_ddl;
EXECUTE marketing_template_legacy_unique_stmt;
DEALLOCATE PREPARE marketing_template_legacy_unique_stmt;

-- V147：owner 级唯一键已允许同租户不同 owner 出现相同 URL/名称/request_id。
-- 以下查询先输出冲突；任一冲突存在时，守卫会 fail-fast，禁止恢复旧唯一键。
SELECT 'group_link' AS conflict_table, tenant_id, link_url AS conflict_key, COUNT(*) AS row_count
FROM group_link
GROUP BY tenant_id, link_url
HAVING COUNT(*) > 1
UNION ALL
SELECT 'group_folder', tenant_id, name, COUNT(*)
FROM group_folder
GROUP BY tenant_id, name
HAVING COUNT(*) > 1
UNION ALL
SELECT 'group_link_label', tenant_id, name, COUNT(*)
FROM group_link_label
GROUP BY tenant_id, name
HAVING COUNT(*) > 1
UNION ALL
SELECT 'group_batch_task', tenant_id, request_id, COUNT(*)
FROM group_batch_task
GROUP BY tenant_id, request_id
HAVING COUNT(*) > 1;

DROP TEMPORARY TABLE IF EXISTS tmp_v147_legacy_unique_rollback_guard;
CREATE TEMPORARY TABLE tmp_v147_legacy_unique_rollback_guard (
    guard_key TINYINT NOT NULL PRIMARY KEY
);
INSERT INTO tmp_v147_legacy_unique_rollback_guard (guard_key) VALUES (1);
INSERT INTO tmp_v147_legacy_unique_rollback_guard (guard_key)
SELECT 1
FROM (
    SELECT tenant_id, link_url AS conflict_key
    FROM group_link
    GROUP BY tenant_id, link_url
    HAVING COUNT(*) > 1
    UNION ALL
    SELECT tenant_id, name
    FROM group_folder
    GROUP BY tenant_id, name
    HAVING COUNT(*) > 1
    UNION ALL
    SELECT tenant_id, name
    FROM group_link_label
    GROUP BY tenant_id, name
    HAVING COUNT(*) > 1
    UNION ALL
    SELECT tenant_id, request_id
    FROM group_batch_task
    GROUP BY tenant_id, request_id
    HAVING COUNT(*) > 1
) AS conflicts
LIMIT 1;
DROP TEMPORARY TABLE tmp_v147_legacy_unique_rollback_guard;

SET @group_link_legacy_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_link'
       AND index_name = 'uq_url') = 0,
    'ALTER TABLE group_link ADD UNIQUE KEY uq_url (tenant_id, link_url)',
    'SELECT 1'
);
PREPARE group_link_legacy_unique_stmt FROM @group_link_legacy_unique_ddl;
EXECUTE group_link_legacy_unique_stmt;
DEALLOCATE PREPARE group_link_legacy_unique_stmt;

SET @group_folder_legacy_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_folder'
       AND index_name = 'uq_group_folder_name') = 0,
    'ALTER TABLE group_folder ADD UNIQUE KEY uq_group_folder_name (tenant_id, name)',
    'SELECT 1'
);
PREPARE group_folder_legacy_unique_stmt FROM @group_folder_legacy_unique_ddl;
EXECUTE group_folder_legacy_unique_stmt;
DEALLOCATE PREPARE group_folder_legacy_unique_stmt;

SET @group_link_label_legacy_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_link_label'
       AND index_name = 'uq_name') = 0,
    'ALTER TABLE group_link_label ADD UNIQUE KEY uq_name (tenant_id, name)',
    'SELECT 1'
);
PREPARE group_link_label_legacy_unique_stmt FROM @group_link_label_legacy_unique_ddl;
EXECUTE group_link_label_legacy_unique_stmt;
DEALLOCATE PREPARE group_link_label_legacy_unique_stmt;

SET @group_batch_task_legacy_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_batch_task'
       AND index_name = 'uq_group_batch_task_request') = 0,
    'ALTER TABLE group_batch_task ADD UNIQUE KEY uq_group_batch_task_request (tenant_id, request_id)',
    'SELECT 1'
);
PREPARE group_batch_task_legacy_unique_stmt FROM @group_batch_task_legacy_unique_ddl;
EXECUTE group_batch_task_legacy_unique_stmt;
DEALLOCATE PREPARE group_batch_task_legacy_unique_stmt;

-- V148：不同 owner 可使用相同新建普群幂等键；回退前必须先解决同租户重复。
SELECT tenant_id, idempotency_key, COUNT(*) AS task_count
FROM normal_group_creation_task
GROUP BY tenant_id, idempotency_key
HAVING COUNT(*) > 1
ORDER BY tenant_id, idempotency_key;

DROP TEMPORARY TABLE IF EXISTS tmp_v148_normal_group_creation_rollback_guard;
CREATE TEMPORARY TABLE tmp_v148_normal_group_creation_rollback_guard (
    guard_key TINYINT NOT NULL PRIMARY KEY
);
INSERT INTO tmp_v148_normal_group_creation_rollback_guard (guard_key) VALUES (1);
INSERT INTO tmp_v148_normal_group_creation_rollback_guard (guard_key)
SELECT 1
FROM normal_group_creation_task
GROUP BY tenant_id, idempotency_key
HAVING COUNT(*) > 1
LIMIT 1;
DROP TEMPORARY TABLE tmp_v148_normal_group_creation_rollback_guard;

SET @normal_group_creation_legacy_unique_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'normal_group_creation_task'
       AND index_name = 'uq_normal_group_creation_task_idem') = 0,
    'ALTER TABLE normal_group_creation_task ADD UNIQUE KEY uq_normal_group_creation_task_idem (tenant_id, idempotency_key)',
    'SELECT 1'
);
PREPARE normal_group_creation_legacy_unique_stmt
    FROM @normal_group_creation_legacy_unique_ddl;
EXECUTE normal_group_creation_legacy_unique_stmt;
DEALLOCATE PREPARE normal_group_creation_legacy_unique_stmt;

-- 不同 owner 已产生同名活跃分组时无法无损恢复旧唯一键；先列出冲突供业务处理。
SELECT tenant_id,
       name,
       COUNT(*) AS active_group_count
FROM account_group
WHERE deleted_at IS NULL
GROUP BY tenant_id, name
HAVING COUNT(*) > 1
ORDER BY tenant_id, name;

-- 用主键冲突 fail-fast，避免在仍有同名活跃分组时继续执行结构回滚。
DROP TEMPORARY TABLE IF EXISTS tmp_v140_group_name_rollback_guard;
CREATE TEMPORARY TABLE tmp_v140_group_name_rollback_guard (
    guard_key TINYINT NOT NULL PRIMARY KEY
);
INSERT INTO tmp_v140_group_name_rollback_guard (guard_key) VALUES (1);
INSERT INTO tmp_v140_group_name_rollback_guard (guard_key)
SELECT 1
FROM account_group
WHERE deleted_at IS NULL
GROUP BY tenant_id, name
HAVING COUNT(*) > 1
LIMIT 1;
DROP TEMPORARY TABLE tmp_v140_group_name_rollback_guard;

SET @account_group_legacy_unique_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'account_group'
       AND index_name = 'uq_tenant_name') = 0,
    'ALTER TABLE account_group ADD UNIQUE KEY uq_tenant_name (tenant_id, name, is_active)',
    'SELECT 1'
);
PREPARE account_group_legacy_unique_stmt
    FROM @account_group_legacy_unique_ddl;
EXECUTE account_group_legacy_unique_stmt;
DEALLOCATE PREPARE account_group_legacy_unique_stmt;

-- 验收旧唯一键和账号手机号租户唯一键仍存在；owner 结构刻意保留以支持数据审计和重新前滚。
SELECT table_name,
       index_name,
       non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS index_columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND (
    (table_name = 'account_group' AND index_name = 'uq_tenant_name')
    OR (table_name = 'account' AND index_name = 'uq_tenant_phone')
    OR (table_name = 'group_link' AND index_name = 'uq_url')
    OR (table_name = 'group_folder' AND index_name = 'uq_group_folder_name')
    OR (table_name = 'group_link_label' AND index_name = 'uq_name')
    OR (table_name = 'group_batch_task' AND index_name = 'uq_group_batch_task_request')
    OR (table_name = 'normal_group_creation_task'
        AND index_name = 'uq_normal_group_creation_task_idem')
  )
GROUP BY table_name, index_name, non_unique
ORDER BY table_name, index_name;
