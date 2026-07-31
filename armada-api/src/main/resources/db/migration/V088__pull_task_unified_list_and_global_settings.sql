-- 拉群任务公共索引、拉群营销列表聚合与租户全局设置。
-- 该迁移不接入执行器，也不迁移独立 marketing_task/group_pull_marketing_task 数据。

SET @pull_task_type_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'task_type') = 0,
    'ALTER TABLE pull_task ADD COLUMN task_type VARCHAR(32) NOT NULL DEFAULT ''STANDARD'' COMMENT ''任务类型:STANDARD普通拉群 GROUP_MARKETING拉群营销'' AFTER id',
    'SELECT 1'
);
PREPARE pull_task_type_stmt FROM @pull_task_type_sql;
EXECUTE pull_task_type_stmt;
DEALLOCATE PREPARE pull_task_type_stmt;

SET @pull_task_group_source_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'group_source') = 0,
    'ALTER TABLE pull_task ADD COLUMN group_source VARCHAR(32) DEFAULT NULL COMMENT ''群组来源:HISTORICAL历史群 SELF_COLLECTED自收群 MIXED混合;普通任务为空'' AFTER task_type',
    'SELECT 1'
);
PREPARE pull_task_group_source_stmt FROM @pull_task_group_source_sql;
EXECUTE pull_task_group_source_stmt;
DEALLOCATE PREPARE pull_task_group_source_stmt;

SET @pull_task_primary_stage_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'primary_stage') = 0,
    'ALTER TABLE pull_task ADD COLUMN primary_stage VARCHAR(64) DEFAULT NULL COMMENT ''当前主要业务阶段'' AFTER status',
    'SELECT 1'
);
PREPARE pull_task_primary_stage_stmt FROM @pull_task_primary_stage_sql;
EXECUTE pull_task_primary_stage_stmt;
DEALLOCATE PREPARE pull_task_primary_stage_stmt;

SET @pull_task_blocking_reason_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'blocking_reason') = 0,
    'ALTER TABLE pull_task ADD COLUMN blocking_reason VARCHAR(255) DEFAULT NULL COMMENT ''当前阻塞、暂停或停止原因'' AFTER primary_stage',
    'SELECT 1'
);
PREPARE pull_task_blocking_reason_stmt FROM @pull_task_blocking_reason_sql;
EXECUTE pull_task_blocking_reason_stmt;
DEALLOCATE PREPARE pull_task_blocking_reason_stmt;

SET @pull_task_last_executed_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'last_business_executed_at') = 0,
    'ALTER TABLE pull_task ADD COLUMN last_business_executed_at BIGINT DEFAULT NULL COMMENT ''最近一次真实业务步骤完成或提交时间(epoch毫秒)'' AFTER updated_at',
    'SELECT 1'
);
PREPARE pull_task_last_executed_stmt FROM @pull_task_last_executed_sql;
EXECUTE pull_task_last_executed_stmt;
DEALLOCATE PREPARE pull_task_last_executed_stmt;

SET @pull_task_type_status_index_sql := IF(
    (SELECT COUNT(DISTINCT index_name)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND index_name = 'idx_pull_task_type_status') = 0,
    'ALTER TABLE pull_task ADD INDEX idx_pull_task_type_status (tenant_id, task_type, status, deleted_at, id)',
    'SELECT 1'
);
PREPARE pull_task_type_status_index_stmt FROM @pull_task_type_status_index_sql;
EXECUTE pull_task_type_status_index_stmt;
DEALLOCATE PREPARE pull_task_type_status_index_stmt;

SET @pull_task_source_index_sql := IF(
    (SELECT COUNT(DISTINCT index_name)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND index_name = 'idx_pull_task_source') = 0,
    'ALTER TABLE pull_task ADD INDEX idx_pull_task_source (tenant_id, group_source, deleted_at, id)',
    'SELECT 1'
);
PREPARE pull_task_source_index_stmt FROM @pull_task_source_index_sql;
EXECUTE pull_task_source_index_stmt;
DEALLOCATE PREPARE pull_task_source_index_stmt;

CREATE TABLE IF NOT EXISTS pull_task_group_marketing_summary (
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '拉群营销任务ID(→pull_task.id)',
    target_group_count INT NOT NULL DEFAULT 0 COMMENT '目标群组数',
    transfer_success_count INT NOT NULL DEFAULT 0 COMMENT '转移成功群组数',
    transfer_pending_close_count INT NOT NULL DEFAULT 0 COMMENT '转移待收口群组数',
    transfer_partial_count INT NOT NULL DEFAULT 0 COMMENT '转移部分完成群组数',
    transfer_failed_count INT NOT NULL DEFAULT 0 COMMENT '转移失败群组数',
    transfer_running_count INT NOT NULL DEFAULT 0 COMMENT '转移执行中群组数',
    transfer_waiting_count INT NOT NULL DEFAULT 0 COMMENT '转移等待执行群组数',
    planned_target_count INT NOT NULL DEFAULT 0 COMMENT '计划目标人数',
    effective_target_count INT NOT NULL DEFAULT 0 COMMENT '有效目标人数',
    joined_success_count INT NOT NULL DEFAULT 0 COMMENT '新增成功人数',
    already_in_group_count INT NOT NULL DEFAULT 0 COMMENT '已在群内人数',
    privacy_restricted_count INT NOT NULL DEFAULT 0 COMMENT '隐私限制人数',
    invalid_number_count INT NOT NULL DEFAULT 0 COMMENT '无效号码数',
    unregistered_count INT NOT NULL DEFAULT 0 COMMENT '未注册号码数',
    pull_result_unknown_count INT NOT NULL DEFAULT 0 COMMENT '拉人结果未知数',
    remaining_target_count INT NOT NULL DEFAULT 0 COMMENT '剩余有效目标人数',
    marketing_waiting_count INT NOT NULL DEFAULT 0 COMMENT '营销待开始群组数',
    marketing_running_count INT NOT NULL DEFAULT 0 COMMENT '营销进行中群组数',
    marketing_paused_count INT NOT NULL DEFAULT 0 COMMENT '营销已暂停群组数',
    marketing_completed_count INT NOT NULL DEFAULT 0 COMMENT '营销已完成群组数',
    marketing_abnormal_stopped_count INT NOT NULL DEFAULT 0 COMMENT '营销异常停止群组数',
    message_success_count INT NOT NULL DEFAULT 0 COMMENT '最终发送成功消息数',
    message_failed_count INT NOT NULL DEFAULT 0 COMMENT '最终发送失败消息数',
    message_unknown_count INT NOT NULL DEFAULT 0 COMMENT '发送结果未知消息数',
    abnormal_group_count INT NOT NULL DEFAULT 0 COMMENT '去重异常群组数',
    puller_shortage_group_count INT NOT NULL DEFAULT 0 COMMENT '缺少拉手群组数(异常群组子集)',
    banned_account_count INT NOT NULL DEFAULT 0 COMMENT '去重封禁账号数',
    available_puller_count INT NOT NULL DEFAULT 0 COMMENT '当前可用拉手数',
    is_target_data_shortage TINYINT(1) NOT NULL DEFAULT 0 COMMENT '目标数据是否不足:0否 1是',
    is_puller_shortage TINYINT(1) NOT NULL DEFAULT 0 COMMENT '拉手是否不足:0否 1是',
    is_water_army_shortage TINYINT(1) NOT NULL DEFAULT 0 COMMENT '水军是否不足:0否 1是',
    is_admin_shortage TINYINT(1) NOT NULL DEFAULT 0 COMMENT '管理员是否不足:0否 1是',
    is_marketing_admin_shortage TINYINT(1) NOT NULL DEFAULT 0 COMMENT '营销账号是否不足:0否 1是',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (tenant_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='拉群营销任务列表聚合统计';

CREATE TABLE IF NOT EXISTS pull_task_group_marketing_setting (
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    marketing_silence_minutes INT NOT NULL COMMENT '营销静默时间(分钟,允许0)',
    group_lockdown_minutes INT NOT NULL COMMENT '群组封控时间(分钟,允许0)',
    max_marketing_accounts_per_group INT NOT NULL COMMENT '单群营销账号上限数量(正整数)',
    created_by BIGINT DEFAULT NULL COMMENT '创建人用户ID',
    updated_by BIGINT DEFAULT NULL COMMENT '最近修改人用户ID',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='租户拉群营销全局设置';

SET @pull_task_setting_permission_now :=
    CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);
INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT tenant.id, parent.id, '全局设置', 'TaskPullSettings', 'B', NULL, NULL,
       'tenant:pull_task:settings', NULL, 50, 1,
       @pull_task_setting_permission_now, NULL,
       @pull_task_setting_permission_now, NULL
FROM tenant
INNER JOIN sys_menu parent
    ON parent.tenant_id = tenant.id AND parent.menu_key = 'TaskPull'
WHERE tenant.status = 1;
