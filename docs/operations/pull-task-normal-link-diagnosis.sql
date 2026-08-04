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

-- 结果 4：账号动作及对应 Outbox；覆盖保存联系人、邀请和踩链接。
SELECT
    a.id AS action_id,
    a.group_execution_id AS execution_id,
    a.action_type,
    a.actor_group_account_id,
    a.target_group_account_id,
    a.action_status,
    a.command_id,
    a.reason_code,
    a.reason_message,
    a.submitted_at,
    a.result_at,
    a.updated_at AS action_updated_at,
    o.command_type,
    o.aggregate_type,
    o.aggregate_id,
    o.protocol_account_id,
    o.protocol_backend,
    o.status AS outbox_status,
    o.retry_count,
    o.next_retry_at,
    o.locked_by,
    o.locked_at,
    o.sent_at,
    o.last_error
FROM pull_task_account_action a
JOIN pull_task_group_execution e
  ON e.id = a.group_execution_id
 AND e.tenant_id = a.tenant_id
JOIN pull_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
LEFT JOIN protocol_command_outbox o
  ON o.command_id = a.command_id
 AND o.tenant_id = a.tenant_id
 AND o.deleted_at IS NULL
WHERE t.id = @task_id
  AND t.task_type = 'STANDARD'
  AND t.mode = 'NORMAL_LINK'
  AND t.deleted_at IS NULL
ORDER BY e.seq, a.id;

-- 结果 5：真实批量拉人调用及对应 Outbox。
SELECT
    c.id AS call_id,
    c.group_execution_id AS execution_id,
    c.call_seq,
    c.puller_group_account_id,
    c.puller_account_id,
    c.planned_material_count,
    c.planned_station_count,
    c.call_status,
    c.command_id,
    c.reason_code,
    c.reason_message,
    c.submitted_at,
    c.result_at,
    c.updated_at AS call_updated_at,
    o.command_type,
    o.aggregate_type,
    o.aggregate_id,
    o.protocol_account_id,
    o.protocol_backend,
    o.status AS outbox_status,
    o.retry_count,
    o.next_retry_at,
    o.locked_by,
    o.locked_at,
    o.sent_at,
    o.last_error
FROM pull_task_pull_call c
JOIN pull_task_group_execution e
  ON e.id = c.group_execution_id
 AND e.tenant_id = c.tenant_id
JOIN pull_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
LEFT JOIN protocol_command_outbox o
  ON o.command_id = c.command_id
 AND o.tenant_id = c.tenant_id
 AND o.deleted_at IS NULL
WHERE t.id = @task_id
  AND t.task_type = 'STANDARD'
  AND t.mode = 'NORMAL_LINK'
  AND t.deleted_at IS NULL
ORDER BY e.seq, c.call_seq, c.id;

-- 结果 6a：料子入群和提权状态聚合。
SELECT
    m.group_execution_id AS execution_id,
    m.pull_status,
    m.admin_status,
    COUNT(*) AS member_count
FROM pull_task_material_member m
JOIN pull_task_group_execution e
  ON e.id = m.group_execution_id
 AND e.tenant_id = m.tenant_id
JOIN pull_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
WHERE t.id = @task_id
  AND t.task_type = 'STANDARD'
  AND t.mode = 'NORMAL_LINK'
  AND t.deleted_at IS NULL
GROUP BY m.group_execution_id, m.pull_status, m.admin_status
ORDER BY m.group_execution_id, m.pull_status, m.admin_status;

-- 结果 6b：处理中或异常料子；不输出号码和 WhatsApp JID。
SELECT
    m.id AS member_id,
    m.group_execution_id AS execution_id,
    m.member_seq,
    m.pull_call_id,
    m.pull_status,
    m.pull_reason_code,
    m.pull_reason_message,
    m.pull_result_at,
    m.admin_required,
    m.admin_status,
    m.admin_command_id,
    m.admin_reason_code,
    m.admin_result_at,
    o.status AS admin_outbox_status,
    o.retry_count AS admin_outbox_retry_count,
    o.sent_at AS admin_outbox_sent_at,
    o.last_error AS admin_outbox_last_error,
    m.updated_at
FROM pull_task_material_member m
JOIN pull_task_group_execution e
  ON e.id = m.group_execution_id
 AND e.tenant_id = m.tenant_id
JOIN pull_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
LEFT JOIN protocol_command_outbox o
  ON o.command_id = m.admin_command_id
 AND o.tenant_id = m.tenant_id
 AND o.deleted_at IS NULL
WHERE t.id = @task_id
  AND t.task_type = 'STANDARD'
  AND t.mode = 'NORMAL_LINK'
  AND t.deleted_at IS NULL
  AND (
    m.pull_status IN (1, 3, 4)
    OR m.admin_status IN (1, 2, 4, 5)
  )
ORDER BY e.seq, m.member_seq, m.id;

-- 结果 7：异常摘要候选。
-- “候选”不等于故障结论；必须结合排期、测试时间和前六组事实确认。
WITH target_task AS (
    SELECT id, tenant_id, status, updated_at
    FROM pull_task
    WHERE id = @task_id
      AND task_type = 'STANDARD'
      AND mode = 'NORMAL_LINK'
      AND deleted_at IS NULL
), task_commands AS (
    SELECT
        'ACTION' AS fact_type,
        a.id AS fact_id,
        a.task_id,
        a.group_execution_id AS execution_id,
        a.tenant_id,
        a.command_id,
        a.action_status AS fact_status,
        a.updated_at
    FROM pull_task_account_action a
    JOIN target_task t
      ON t.id = a.task_id
     AND t.tenant_id = a.tenant_id
    UNION ALL
    SELECT
        'PULL_CALL',
        c.id,
        c.task_id,
        c.group_execution_id,
        c.tenant_id,
        c.command_id,
        c.call_status,
        c.updated_at
    FROM pull_task_pull_call c
    JOIN target_task t
      ON t.id = c.task_id
     AND t.tenant_id = c.tenant_id
    UNION ALL
    SELECT
        'MATERIAL_ADMIN',
        m.id,
        e.task_id,
        m.group_execution_id,
        m.tenant_id,
        m.admin_command_id,
        m.admin_status,
        m.updated_at
    FROM pull_task_material_member m
    JOIN pull_task_group_execution e
      ON e.id = m.group_execution_id
     AND e.tenant_id = m.tenant_id
    JOIN target_task t
      ON t.id = e.task_id
     AND t.tenant_id = e.tenant_id
)
SELECT
    'DUE_EXECUTION_NOT_PROGRESSING' AS category,
    e.task_id,
    e.id AS execution_id,
    e.id AS fact_id,
    NULL AS command_id,
    CONCAT(
        'stage=', e.stage,
        ', lease=', CASE WHEN e.lock_owner IS NULL THEN 'UNLOCKED' ELSE 'EXPIRED' END
    ) AS diagnosis,
    e.updated_at
FROM pull_task_group_execution e
JOIN target_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
WHERE t.status = 'EXECUTING'
  AND e.execution_status IN (1, 2)
  AND e.manual_paused = 0
  AND e.next_run_at <= CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
  AND (
    e.lock_owner IS NULL
    OR e.lock_expires_at <= CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
  )

UNION ALL

SELECT
    'PENDING_ACTION_WITHOUT_COMMAND',
    a.task_id,
    a.group_execution_id,
    a.id,
    a.command_id,
    CONCAT('action_type=', a.action_type, ', action_status=', a.action_status),
    a.updated_at
FROM pull_task_account_action a
JOIN target_task t
  ON t.id = a.task_id
 AND t.tenant_id = a.tenant_id
WHERE a.action_status = 1
  AND a.command_id IS NULL

UNION ALL

SELECT
    'PENDING_CALL_WITHOUT_COMMAND',
    c.task_id,
    c.group_execution_id,
    c.id,
    c.command_id,
    CONCAT('call_seq=', c.call_seq, ', call_status=', c.call_status),
    c.updated_at
FROM pull_task_pull_call c
JOIN target_task t
  ON t.id = c.task_id
 AND t.tenant_id = c.tenant_id
WHERE c.call_status = 1
  AND c.command_id IS NULL

UNION ALL

SELECT
    'OUTBOX_DEAD',
    tc.task_id,
    tc.execution_id,
    tc.fact_id,
    tc.command_id,
    CONCAT(tc.fact_type, ': ', COALESCE(o.last_error, 'OUTBOX_DEAD')),
    o.updated_at
FROM task_commands tc
JOIN protocol_command_outbox o
  ON o.command_id = tc.command_id
 AND o.tenant_id = tc.tenant_id
 AND o.deleted_at IS NULL
WHERE o.status = 3

UNION ALL

SELECT
    'SENT_WITHOUT_BUSINESS_RESULT',
    tc.task_id,
    tc.execution_id,
    tc.fact_id,
    tc.command_id,
    CONCAT(tc.fact_type, ': fact_status=', tc.fact_status, ', outbox_status=2'),
    tc.updated_at
FROM task_commands tc
JOIN protocol_command_outbox o
  ON o.command_id = tc.command_id
 AND o.tenant_id = tc.tenant_id
 AND o.deleted_at IS NULL
WHERE o.status = 2
  AND (
    (tc.fact_type = 'ACTION' AND tc.fact_status = 2)
    OR (tc.fact_type = 'PULL_CALL' AND tc.fact_status = 2)
    OR (tc.fact_type = 'MATERIAL_ADMIN' AND tc.fact_status = 2)
  )

UNION ALL

SELECT
    'UNKNOWN_RESULT',
    tc.task_id,
    tc.execution_id,
    tc.fact_id,
    tc.command_id,
    CONCAT(tc.fact_type, ': fact_status=', tc.fact_status),
    tc.updated_at
FROM task_commands tc
WHERE (tc.fact_type = 'ACTION' AND tc.fact_status = 5)
   OR (tc.fact_type = 'PULL_CALL' AND tc.fact_status = 4)
   OR (tc.fact_type = 'MATERIAL_ADMIN' AND tc.fact_status = 5)

UNION ALL

SELECT
    'UNKNOWN_RESULT',
    e.task_id,
    m.group_execution_id,
    m.id,
    NULL,
    CONCAT('MATERIAL_PULL: pull_status=', m.pull_status),
    m.updated_at
FROM pull_task_material_member m
JOIN pull_task_group_execution e
  ON e.id = m.group_execution_id
 AND e.tenant_id = m.tenant_id
JOIN target_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
WHERE m.pull_status = 4

UNION ALL

SELECT
    'WAIT_RESOURCE',
    e.task_id,
    e.id,
    e.id,
    NULL,
    CONCAT(
        'wait_resource_type=', COALESCE(e.wait_resource_type, -1),
        ', reason_code=', COALESCE(e.reason_code, 'NULL')
    ),
    e.updated_at
FROM pull_task_group_execution e
JOIN target_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
WHERE e.execution_status = 3

UNION ALL

SELECT
    'TERMINAL_CHILD_NON_TERMINAL_PARENT',
    t.id,
    NULL,
    NULL,
    NULL,
    CONCAT('parent_status=', t.status, ', all executions terminal'),
    t.updated_at
FROM target_task t
WHERE t.status IN ('EXECUTING', 'PAUSED', 'INTERRUPTED')
  AND EXISTS (
    SELECT 1
    FROM pull_task_group_execution e
    WHERE e.task_id = t.id
      AND e.tenant_id = t.tenant_id
  )
  AND NOT EXISTS (
    SELECT 1
    FROM pull_task_group_execution e
    WHERE e.task_id = t.id
      AND e.tenant_id = t.tenant_id
      AND e.execution_status NOT IN (4, 5, 6)
  )
ORDER BY category, execution_id, fact_id;
