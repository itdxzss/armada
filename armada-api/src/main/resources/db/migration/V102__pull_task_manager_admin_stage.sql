-- 普通群链接执行链路补充任务管理员提权阶段与可重试动作事实。
-- MySQL DDL 会隐式提交，因此用持久化检查点保护后续 DML，保证 repair 后重跑不会重复迁移阶段。

CREATE TABLE IF NOT EXISTS armada_schema_migration_checkpoint (
    migration_key VARCHAR(128) NOT NULL,
    stage_renumbered TINYINT(1) NOT NULL DEFAULT 0,
    manager_rewound TINYINT(1) NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (migration_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需要跨DDL保证重试安全的迁移检查点';

INSERT IGNORE INTO armada_schema_migration_checkpoint (
    migration_key, stage_renumbered, manager_rewound, updated_at
) VALUES ('V102_pull_task_manager_admin_stage', 0, 0, 0);

SET @pull_task_action_table_exists := (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'pull_task_account_action'
);

SET @pull_task_action_attempt_no_sql := IF(
    @pull_task_action_table_exists = 1
        AND (SELECT COUNT(*)
             FROM information_schema.columns
             WHERE table_schema = DATABASE()
               AND table_name = 'pull_task_account_action'
               AND column_name = 'attempt_no') = 0,
    'ALTER TABLE pull_task_account_action ADD COLUMN attempt_no INT NOT NULL DEFAULT 0 COMMENT ''当前命令尝试序号;每次提交新commandId递增'' AFTER command_id',
    'SELECT 1'
);
PREPARE pull_task_action_attempt_no_stmt FROM @pull_task_action_attempt_no_sql;
EXECUTE pull_task_action_attempt_no_stmt;
DEALLOCATE PREPARE pull_task_action_attempt_no_stmt;

SET @pull_task_action_retryable_sql := IF(
    @pull_task_action_table_exists = 1
        AND (SELECT COUNT(*)
             FROM information_schema.columns
             WHERE table_schema = DATABASE()
               AND table_name = 'pull_task_account_action'
               AND column_name = 'retryable') = 0,
    'ALTER TABLE pull_task_account_action ADD COLUMN retryable TINYINT(1) DEFAULT NULL COMMENT ''最近结果是否允许业务重试'' AFTER reason_message',
    'SELECT 1'
);
PREPARE pull_task_action_retryable_stmt FROM @pull_task_action_retryable_sql;
EXECUTE pull_task_action_retryable_stmt;
DEALLOCATE PREPARE pull_task_action_retryable_stmt;

SET @pull_task_execution_stage_comment_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_execution'
       AND column_name = 'stage') = 1,
    'ALTER TABLE pull_task_group_execution MODIFY COLUMN stage TINYINT NOT NULL DEFAULT 1 COMMENT ''业务阶段:1=链接校验 2=管理入群 3=管理员设置 4=管理拉手联系人 5=管理邀请拉手 6=拉人执行 7=料子提权 8=收口''',
    'SELECT 1'
);
PREPARE pull_task_execution_stage_comment_stmt FROM @pull_task_execution_stage_comment_sql;
EXECUTE pull_task_execution_stage_comment_stmt;
DEALLOCATE PREPARE pull_task_execution_stage_comment_stmt;

SET @pull_task_role_type_comment_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_account'
       AND column_name = 'role_type') = 1,
    'ALTER TABLE pull_task_group_account MODIFY COLUMN role_type TINYINT NOT NULL COMMENT ''角色:1=管理 2=拉手 3=站台 4=提权管理员''',
    'SELECT 1'
);
PREPARE pull_task_role_type_comment_stmt FROM @pull_task_role_type_comment_sql;
EXECUTE pull_task_role_type_comment_stmt;
DEALLOCATE PREPARE pull_task_role_type_comment_stmt;

SET @pull_task_action_type_comment_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_account_action'
       AND column_name = 'action_type') = 1,
    'ALTER TABLE pull_task_account_action MODIFY COLUMN action_type TINYINT NOT NULL COMMENT ''动作类型:1=保存联系人 2=邀请入群 3=踩链接入群 4=设置任务管理员''',
    'SELECT 1'
);
PREPARE pull_task_action_type_comment_stmt FROM @pull_task_action_type_comment_sql;
EXECUTE pull_task_action_type_comment_stmt;
DEALLOCATE PREPARE pull_task_action_type_comment_stmt;

SET @pull_task_stage_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'pull_task_group_execution'
      AND column_name = 'stage'
);
SET @pull_task_stage_renumber_needed := (
    SELECT IF(@pull_task_stage_exists = 1 AND stage_renumbered = 0, 1, 0)
    FROM armada_schema_migration_checkpoint
    WHERE migration_key = 'V102_pull_task_manager_admin_stage'
);

START TRANSACTION;
SET @pull_task_stage_renumber_sql := IF(
    @pull_task_stage_renumber_needed = 1,
    'UPDATE pull_task_group_execution SET stage = CASE WHEN stage BETWEEN 3 AND 7 THEN stage + 1 ELSE stage END',
    'SELECT 1'
);
PREPARE pull_task_stage_renumber_stmt FROM @pull_task_stage_renumber_sql;
EXECUTE pull_task_stage_renumber_stmt;
DEALLOCATE PREPARE pull_task_stage_renumber_stmt;
UPDATE armada_schema_migration_checkpoint
SET stage_renumbered = 1,
    updated_at = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
WHERE migration_key = 'V102_pull_task_manager_admin_stage'
  AND @pull_task_stage_renumber_needed = 1;
COMMIT;

SET @pull_task_manager_admin_tables_exist := (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name IN ('pull_task', 'pull_task_group_execution', 'pull_task_group_account')
);
SET @pull_task_manager_admin_rewind_needed := (
    SELECT IF(@pull_task_manager_admin_tables_exist = 3
                  AND stage_renumbered = 1
                  AND manager_rewound = 0, 1, 0)
    FROM armada_schema_migration_checkpoint
    WHERE migration_key = 'V102_pull_task_manager_admin_stage'
);

START TRANSACTION;
SET @pull_task_manager_admin_rewind_sql := IF(
    @pull_task_manager_admin_rewind_needed = 1,
    'UPDATE pull_task_group_execution execution_row
     JOIN pull_task task_row
       ON task_row.tenant_id = execution_row.tenant_id
      AND task_row.id = execution_row.task_id
     SET execution_row.execution_status = 2,
         execution_row.stage = 3,
         execution_row.wait_resource_type = NULL,
         execution_row.reason_code = NULL,
         execution_row.reason_message = NULL,
         execution_row.next_run_at = 0,
         execution_row.lock_owner = NULL,
         execution_row.lock_expires_at = NULL,
         execution_row.updated_at = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
     WHERE execution_row.execution_status IN (1, 2, 3)
       AND task_row.task_type = ''STANDARD''
       AND task_row.mode = ''NORMAL_LINK''
       AND task_row.status NOT IN (''COMPLETED'', ''ENDED'')
       AND EXISTS (
         SELECT 1
         FROM pull_task_group_account manager_row
         WHERE manager_row.tenant_id = execution_row.tenant_id
           AND manager_row.group_execution_id = execution_row.id
           AND manager_row.role_type = 1
           AND manager_row.membership_status = 2
           AND COALESCE(manager_row.admin_status, 0) <> 3
       )',
    'SELECT 1'
);
PREPARE pull_task_manager_admin_rewind_stmt FROM @pull_task_manager_admin_rewind_sql;
EXECUTE pull_task_manager_admin_rewind_stmt;
DEALLOCATE PREPARE pull_task_manager_admin_rewind_stmt;
UPDATE armada_schema_migration_checkpoint
SET manager_rewound = 1,
    updated_at = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
WHERE migration_key = 'V102_pull_task_manager_admin_stage'
  AND @pull_task_manager_admin_rewind_needed = 1;
COMMIT;
