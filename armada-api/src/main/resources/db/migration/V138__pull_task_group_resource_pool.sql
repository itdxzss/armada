-- 拉人任务群组资源池：系统分组标识、TXT 重试次数、全局群组占用约束。

SET @ddl = IF(
    (SELECT COUNT(*)
       FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'group_folder'
        AND column_name = 'system_builtin') = 0,
    'ALTER TABLE group_folder ADD COLUMN system_builtin TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否系统内置分组'' AFTER name',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 已有租户补齐系统“已使用群组”；若同名自定义分组已存在，则直接提升为系统分组。
INSERT INTO group_folder (
    tenant_id, name, system_builtin, created_at, updated_at, created_by, deleted_at
)
SELECT tenant_row.id,
       '已使用群组',
       1,
       CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED),
       CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED),
       NULL,
       NULL
  FROM tenant tenant_row
ON DUPLICATE KEY UPDATE
    system_builtin = 1,
    deleted_at = NULL,
    updated_at = VALUES(updated_at);

SET @ddl = IF(
    (SELECT COUNT(*)
       FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'pull_task_group_execution'
        AND column_name = 'attempt_no') = 0,
    'ALTER TABLE pull_task_group_execution ADD COLUMN attempt_no INT NOT NULL DEFAULT 1 COMMENT ''同一 TXT 的执行次数'' AFTER source_file_index',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @seq_index_columns = (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_execution'
       AND index_name = 'uq_pull_task_execution_seq'
);
SET @ddl = IF(
    @seq_index_columns = 'tenant_id,task_id,seq',
    'ALTER TABLE pull_task_group_execution DROP INDEX uq_pull_task_execution_seq, ADD UNIQUE KEY uq_pull_task_execution_seq (tenant_id, task_id, seq, attempt_no)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @file_index_columns = (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_execution'
       AND index_name = 'uq_pull_task_execution_file'
);
SET @ddl = IF(
    @file_index_columns = 'tenant_id,task_id,source_file_index',
    'ALTER TABLE pull_task_group_execution DROP INDEX uq_pull_task_execution_file, ADD UNIQUE KEY uq_pull_task_execution_file (tenant_id, task_id, source_file_index, attempt_no)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @occupancy_expression = (
    SELECT LOWER(generation_expression)
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_execution'
       AND column_name = 'link_occupancy_key'
);
SET @occupancy_index_exists = (
    SELECT COUNT(*)
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_execution'
       AND index_name = 'uq_pull_task_execution_link_occupancy'
);
SET @ddl = IF(
    @occupancy_index_exists > 0
        AND (@occupancy_expression NOT LIKE '%group_jid%'
          OR @occupancy_expression NOT LIKE '%normalized_link%'),
    'ALTER TABLE pull_task_group_execution DROP INDEX uq_pull_task_execution_link_occupancy',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @occupancy_expression = (
    SELECT LOWER(generation_expression)
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_execution'
       AND column_name = 'link_occupancy_key'
);
SET @ddl = IF(
    @occupancy_expression NOT LIKE '%group_jid%'
        OR @occupancy_expression NOT LIKE '%normalized_link%',
    'ALTER TABLE pull_task_group_execution MODIFY COLUMN link_occupancy_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (CASE WHEN execution_status IN (1, 2, 3) THEN CASE WHEN NULLIF(group_jid, '''') IS NOT NULL THEN CONCAT(''jid:'', group_jid) WHEN NULLIF(normalized_link, '''') IS NOT NULL THEN CONCAT(''link:'', normalized_link) ELSE NULL END ELSE NULL END) STORED COMMENT ''活动任务占用群组键''',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @occupancy_index_exists = (
    SELECT COUNT(*)
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_execution'
       AND index_name = 'uq_pull_task_execution_link_occupancy'
);
SET @ddl = IF(
    @occupancy_index_exists = 0,
    'ALTER TABLE pull_task_group_execution ADD UNIQUE KEY uq_pull_task_execution_link_occupancy (tenant_id, link_occupancy_key)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
