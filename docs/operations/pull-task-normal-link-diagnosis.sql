-- 普通群链接拉群任务只读诊断查询。
-- 使用前在当前 MySQL 会话设置任务 ID，例如：SET @task_id := 123;
-- 目标必须满足 task_type='STANDARD' AND mode='NORMAL_LINK'。
-- 本文件不得输出 normalized_link、invite_code、account_phone、normalized_phone 或 payload_json。

-- 结果 1：父任务概况。
-- 无结果时停止，先确认任务 ID、软删状态或业务模式。
SELECT
    id AS task_id,
    tenant_id,
    task_type,
    mode,
    status,
    primary_stage,
    blocking_reason,
    group_count,
    expected_pull_count,
    started_at,
    finished_at,
    last_business_executed_at,
    version,
    created_at,
    updated_at
FROM pull_task
WHERE id = @task_id
  AND task_type = 'STANDARD'
  AND mode = 'NORMAL_LINK'
  AND deleted_at IS NULL;

-- 结果 2：群执行行概况、排期和调度租约。
SELECT
    e.id AS execution_id,
    e.seq,
    e.execution_status,
    e.stage,
    e.manual_paused,
    e.wait_resource_type,
    e.reason_code,
    e.reason_message,
    e.next_run_at,
    CASE
        WHEN e.next_run_at > CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
            THEN 'NOT_DUE'
        ELSE 'DUE'
    END AS schedule_state,
    e.lock_owner,
    e.lock_expires_at,
    CASE
        WHEN e.lock_owner IS NULL THEN 'UNLOCKED'
        WHEN e.lock_expires_at IS NULL THEN 'LOCK_WITHOUT_EXPIRY'
        WHEN e.lock_expires_at <= CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
            THEN 'EXPIRED'
        ELSE 'HELD'
    END AS lease_state,
    e.started_at,
    e.finished_at,
    e.last_business_executed_at,
    e.updated_at
FROM pull_task_group_execution e
JOIN pull_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
WHERE e.task_id = @task_id
  AND t.task_type = 'STANDARD'
  AND t.mode = 'NORMAL_LINK'
  AND t.deleted_at IS NULL
ORDER BY e.seq, e.id;

-- 结果 3：管理、拉手、站台角色账号事实；不输出账号号码。
SELECT
    a.group_execution_id AS execution_id,
    a.id AS role_row_id,
    a.account_id,
    a.role_type,
    a.role_seq,
    a.source_type,
    a.selection_mode,
    a.entry_mode,
    a.membership_status,
    a.admin_status,
    a.availability_status,
    a.unavailable_reason_code,
    a.cooldown_until,
    a.occupied_at,
    a.released_at,
    a.updated_at
FROM pull_task_group_account a
JOIN pull_task_group_execution e
  ON e.id = a.group_execution_id
 AND e.tenant_id = a.tenant_id
JOIN pull_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
WHERE t.id = @task_id
  AND t.task_type = 'STANDARD'
  AND t.mode = 'NORMAL_LINK'
  AND t.deleted_at IS NULL
ORDER BY e.seq, a.role_type, a.role_seq, a.id;
