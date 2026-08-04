-- 普通群链接拉群任务只读诊断查询。
-- 目标必须满足 task_type='STANDARD' AND mode='NORMAL_LINK'。
-- 本文件不得输出 normalized_link、invite_code、account_phone、normalized_phone、
-- wa_jid、group_jid 或 payload_json。
--
-- 使用前先在当前 MySQL 会话设置任务 ID：
--   SET @task_id := 123;
-- 需要只看某一条执行行时，把下面参数块里的 @execution_id 改成具体 executionId。

-- ---------------------------------------------------------------------------
-- 参数块：每次排查前整块执行一次，保证结果 0 到结果 9 使用同一时间基准。
-- ---------------------------------------------------------------------------

-- 统一时间基准；用 SIGNED 保证与未来时间(next_run_at)相减不会触发无符号溢出。
SET @now := CAST(FLOOR(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000) AS SIGNED);

-- NULL = 看整个任务；填具体 executionId = 只看这一条执行行。
SET @execution_id := NULL;

-- 判定阈值，全部来自 PullTaskExecutionDispatchProperties 默认值：
--   fixedDelayMs=1000、leaseMs=30000、
--   resultReconciliationDelayMs=60000、resultReconciliationIntervalMs=30000。
-- 环境改过配置时同步调整这里，否则会误报或漏报。

-- 调度停滞宽限：取 2 倍租约。小于这个值的"到期未持锁"属于正常轮询间隙。
SET @stall_grace_ms := 60000;

-- 结果收敛超期：保护期 60s + 扫描间隔 30s = 90s 内属正常；这里留 2 倍余量。
-- 超过说明未知结果收敛调度没跑，或候选执行行数超过单轮 100 上限。
SET @reconcile_overdue_ms := 180000;

-- Outbox 滞留判定：publisher 抢占后正常应在秒级流转。
SET @outbox_stuck_ms := 60000;

-- 结果 8b 用字面量 LIMIT 200 控制输出量；MySQL 的 LIMIT 不接受用户变量，
-- 需要放宽时直接改那一行的数字。

-- 结果 0：参数与时间基准回显；排查记录直接抄这一行。
SELECT
    @task_id AS task_id,
    @execution_id AS execution_id_filter,
    @now AS now_epoch_ms,
    FROM_UNIXTIME(@now / 1000) AS now_text,
    @stall_grace_ms AS stall_grace_ms,
    @reconcile_overdue_ms AS reconcile_overdue_ms,
    @outbox_stuck_ms AS outbox_stuck_ms;

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
    FROM_UNIXTIME(started_at / 1000) AS started_at_text,
    finished_at,
    FROM_UNIXTIME(finished_at / 1000) AS finished_at_text,
    last_business_executed_at,
    FROM_UNIXTIME(last_business_executed_at / 1000) AS last_business_at_text,
    (@now - last_business_executed_at) DIV 1000 AS last_business_age_seconds,
    version,
    (@now - updated_at) DIV 1000 AS updated_age_seconds,
    created_at,
    updated_at
FROM pull_task
WHERE id = @task_id
  AND task_type = 'STANDARD'
  AND mode = 'NORMAL_LINK'
  AND deleted_at IS NULL;

-- 结果 2：启动时冻结的执行配置。
-- 判断"正常等待"还是"卡死"必须先看这一组：
--   concurrent_group_count  解释为什么只有 N 条执行行在跑；
--   pull_interval_seconds   解释拉手为什么在冷却而不是卡住；
--   required_manager_count  解释 MANAGER_UNAVAILABLE 的门槛；
--   material_admin_timing=2 解释阶段 6 为什么等本群料子全部终态；
--   puller_risk_minutes=0   说明风控冷却不会自动恢复。
SELECT
    s.task_id,
    s.auto_start,
    s.material_admin_timing,
    s.pull_count_min,
    s.pull_count_max,
    s.pull_interval_seconds,
    s.puller_count_per_group,
    s.station_count_per_call,
    s.concurrent_group_count,
    s.puller_risk_minutes,
    s.required_manager_count,
    s.manager_group_id,
    s.manager_group_name,
    s.puller_group_id,
    s.puller_group_name,
    s.station_group_id,
    s.station_group_name,
    s.updated_at
FROM pull_task_standard_setting s
JOIN pull_task t
  ON t.id = s.task_id
 AND t.tenant_id = s.tenant_id
WHERE s.task_id = @task_id
  AND t.task_type = 'STANDARD'
  AND t.mode = 'NORMAL_LINK'
  AND t.deleted_at IS NULL;

-- 结果 3：群执行行概况、排期和调度租约。
-- schedule_state / lease_state 对终态和人工暂停行输出专用值，
-- 避免把永远不会被调度的行标成"已到期"。
SELECT
    e.id AS execution_id,
    e.seq,
    e.source_file_index,
    e.execution_status,
    e.stage,
    e.manual_paused,
    e.group_jid IS NOT NULL AS has_group_jid,
    e.valid_member_count,
    e.invalid_line_count,
    e.duplicate_line_count,
    e.wait_resource_type,
    e.reason_code,
    e.reason_message,
    e.next_run_at,
    FROM_UNIXTIME(NULLIF(e.next_run_at, 0) / 1000) AS next_run_at_text,
    (e.next_run_at - @now) DIV 1000 AS next_run_in_seconds,
    CASE
        WHEN e.execution_status IN (0, 4, 5, 6) THEN 'TERMINAL_OR_DRAFT'
        WHEN e.manual_paused = 1 THEN 'MANUAL_PAUSED'
        WHEN e.next_run_at > @now THEN 'NOT_DUE'
        ELSE 'DUE'
    END AS schedule_state,
    e.lock_owner,
    e.lock_expires_at,
    CASE
        WHEN e.execution_status IN (0, 4, 5, 6) THEN 'TERMINAL_OR_DRAFT'
        WHEN e.lock_owner IS NULL THEN 'UNLOCKED'
        WHEN e.lock_expires_at IS NULL THEN 'LOCK_WITHOUT_EXPIRY'
        WHEN e.lock_expires_at <= @now THEN 'EXPIRED'
        ELSE 'HELD'
    END AS lease_state,
    e.started_at,
    e.finished_at,
    e.last_business_executed_at,
    (@now - e.last_business_executed_at) DIV 1000 AS last_business_age_seconds,
    (@now - e.updated_at) DIV 1000 AS idle_seconds,
    FROM_UNIXTIME(e.updated_at / 1000) AS updated_at_text,
    e.updated_at
FROM pull_task_group_execution e
JOIN pull_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
WHERE e.task_id = @task_id
  AND (@execution_id IS NULL OR e.id = @execution_id)
  AND t.task_type = 'STANDARD'
  AND t.mode = 'NORMAL_LINK'
  AND t.deleted_at IS NULL
ORDER BY e.seq, e.id;

-- 结果 4：管理、拉手、站台角色账号事实；不输出账号号码。
-- membership_reason_* 是进群结果原因，与 unavailable_reason_code(账号可用性原因)不同源。
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
    a.membership_reason_code,
    a.membership_reason_message,
    a.membership_result_at,
    a.joined_at,
    a.pull_call_id,
    a.admin_status,
    a.availability_status,
    a.unavailable_reason_code,
    a.cooldown_until,
    (a.cooldown_until - @now) DIV 1000 AS cooldown_remaining_seconds,
    a.occupied_at,
    a.released_at,
    (@now - a.updated_at) DIV 1000 AS idle_seconds,
    a.updated_at
FROM pull_task_group_account a
JOIN pull_task_group_execution e
  ON e.id = a.group_execution_id
 AND e.tenant_id = a.tenant_id
JOIN pull_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
WHERE t.id = @task_id
  AND (@execution_id IS NULL OR e.id = @execution_id)
  AND t.task_type = 'STANDARD'
  AND t.mode = 'NORMAL_LINK'
  AND t.deleted_at IS NULL
ORDER BY e.seq, a.role_type, a.role_seq, a.id;

-- 结果 5：账号动作及对应 Outbox；覆盖保存联系人、邀请和踩链接。
-- actor_role_type / target_role_type 直接读出动作双方角色，
-- 例如阶段 3 是 1→2(管理→拉手)，阶段 5 是 2→3(拉手→站台)。
SELECT
    a.id AS action_id,
    a.group_execution_id AS execution_id,
    e.stage AS execution_stage,
    a.action_type,
    actor.role_type AS actor_role_type,
    a.actor_group_account_id,
    target.role_type AS target_role_type,
    a.target_group_account_id,
    a.action_status,
    a.command_id,
    a.reason_code,
    a.reason_message,
    a.submitted_at,
    (@now - a.submitted_at) DIV 1000 AS submitted_age_seconds,
    a.result_at,
    (@now - a.updated_at) DIV 1000 AS idle_seconds,
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
    (@now - o.sent_at) DIV 1000 AS sent_age_seconds,
    o.last_error
FROM pull_task_account_action a
JOIN pull_task_group_execution e
  ON e.id = a.group_execution_id
 AND e.tenant_id = a.tenant_id
JOIN pull_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
LEFT JOIN pull_task_group_account actor
  ON actor.id = a.actor_group_account_id
 AND actor.tenant_id = a.tenant_id
LEFT JOIN pull_task_group_account target
  ON target.id = a.target_group_account_id
 AND target.tenant_id = a.tenant_id
LEFT JOIN protocol_command_outbox o
  ON o.command_id = a.command_id
 AND o.tenant_id = a.tenant_id
 AND o.deleted_at IS NULL
WHERE t.id = @task_id
  AND (@execution_id IS NULL OR e.id = @execution_id)
  AND t.task_type = 'STANDARD'
  AND t.mode = 'NORMAL_LINK'
  AND t.deleted_at IS NULL
ORDER BY e.seq, a.id;

-- 结果 6：真实批量拉人调用及对应 Outbox。
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
    FROM_UNIXTIME(c.submitted_at / 1000) AS submitted_at_text,
    (@now - c.submitted_at) DIV 1000 AS submitted_age_seconds,
    c.result_at,
    (@now - c.updated_at) DIV 1000 AS idle_seconds,
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
    (@now - o.sent_at) DIV 1000 AS sent_age_seconds,
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
  AND (@execution_id IS NULL OR e.id = @execution_id)
  AND t.task_type = 'STANDARD'
  AND t.mode = 'NORMAL_LINK'
  AND t.deleted_at IS NULL
ORDER BY e.seq, c.call_seq, c.id;

-- 结果 7：同一拉手的连续拉人间隔核对。
-- last_submitted_age_seconds 小于 pull_interval_seconds 时，
-- 该拉手"不动"是正常冷却，不是卡住。
SELECT
    c.group_execution_id AS execution_id,
    c.puller_account_id,
    COUNT(*) AS call_count,
    MAX(c.submitted_at) AS last_submitted_at,
    FROM_UNIXTIME(MAX(c.submitted_at) / 1000) AS last_submitted_at_text,
    (@now - MAX(c.submitted_at)) DIV 1000 AS last_submitted_age_seconds
FROM pull_task_pull_call c
JOIN pull_task_group_execution e
  ON e.id = c.group_execution_id
 AND e.tenant_id = c.tenant_id
JOIN pull_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
WHERE t.id = @task_id
  AND (@execution_id IS NULL OR e.id = @execution_id)
  AND t.task_type = 'STANDARD'
  AND t.mode = 'NORMAL_LINK'
  AND t.deleted_at IS NULL
  AND c.submitted_at IS NOT NULL
GROUP BY c.group_execution_id, c.puller_account_id
ORDER BY c.group_execution_id, c.puller_account_id;

-- 结果 8a：料子入群和提权状态聚合。
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
  AND (@execution_id IS NULL OR e.id = @execution_id)
  AND t.task_type = 'STANDARD'
  AND t.mode = 'NORMAL_LINK'
  AND t.deleted_at IS NULL
GROUP BY m.group_execution_id, m.pull_status, m.admin_status
ORDER BY m.group_execution_id, m.pull_status, m.admin_status;

-- 结果 8b：处理中或异常料子；不输出号码和 WhatsApp JID。
-- 不含 admin_status=1(待执行)：阶段 5 尚未提权属正常，先看 8a 聚合。
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
    (@now - o.sent_at) DIV 1000 AS admin_sent_age_seconds,
    o.last_error AS admin_outbox_last_error,
    (@now - m.updated_at) DIV 1000 AS idle_seconds,
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
  AND (@execution_id IS NULL OR e.id = @execution_id)
  AND t.task_type = 'STANDARD'
  AND t.mode = 'NORMAL_LINK'
  AND t.deleted_at IS NULL
  AND (
    m.pull_status IN (1, 3, 4)
    OR m.admin_status IN (2, 4, 5)
  )
ORDER BY e.seq, m.member_seq, m.id
LIMIT 200;

-- 结果 9：异常摘要候选。
-- "候选"不等于故障结论；必须结合排期、测试时间和前面各组事实确认。
-- 所有时间类判定都带宽限期，宽限期内的在途状态刻意不输出：
--   到期未持锁 < @stall_grace_ms          → 正常轮询间隙
--   已提交无结果 < @reconcile_overdue_ms  → 未知结果收敛的保护期内
WITH target_task AS (
    SELECT id, tenant_id, status, started_at, finished_at, updated_at
    FROM pull_task
    WHERE id = @task_id
      AND task_type = 'STANDARD'
      AND mode = 'NORMAL_LINK'
      AND deleted_at IS NULL
), scoped_execution AS (
    SELECT e.*
    FROM pull_task_group_execution e
    JOIN target_task t
      ON t.id = e.task_id
     AND t.tenant_id = e.tenant_id
    WHERE @execution_id IS NULL OR e.id = @execution_id
), task_commands AS (
    SELECT
        'ACTION' AS fact_type,
        a.id AS fact_id,
        a.task_id,
        a.group_execution_id AS execution_id,
        a.tenant_id,
        a.command_id,
        a.action_status AS fact_status,
        a.submitted_at,
        a.updated_at
    FROM pull_task_account_action a
    JOIN scoped_execution e
      ON e.id = a.group_execution_id
     AND e.tenant_id = a.tenant_id
    UNION ALL
    SELECT
        'PULL_CALL',
        c.id,
        c.task_id,
        c.group_execution_id,
        c.tenant_id,
        c.command_id,
        c.call_status,
        c.submitted_at,
        c.updated_at
    FROM pull_task_pull_call c
    JOIN scoped_execution e
      ON e.id = c.group_execution_id
     AND e.tenant_id = c.tenant_id
    UNION ALL
    -- 料子提权没有 submitted_at 列；收敛服务同样按 updated_at 判定超时。
    SELECT
        'MATERIAL_ADMIN',
        m.id,
        e.task_id,
        m.group_execution_id,
        m.tenant_id,
        m.admin_command_id,
        m.admin_status,
        NULL,
        m.updated_at
    FROM pull_task_material_member m
    JOIN scoped_execution e
      ON e.id = m.group_execution_id
     AND e.tenant_id = m.tenant_id
)

-- 到期、未持锁且长时间无更新：应具备调度资格却没有推进。
SELECT
    'DUE_EXECUTION_STALLED' AS category,
    e.task_id,
    e.id AS execution_id,
    e.id AS fact_id,
    NULL AS command_id,
    CONCAT(
        'execution_status=', e.execution_status,
        ', stage=', e.stage,
        ', lease=', CASE WHEN e.lock_owner IS NULL THEN 'UNLOCKED' ELSE 'EXPIRED' END
    ) AS diagnosis,
    (@now - e.updated_at) DIV 1000 AS stall_seconds,
    e.updated_at AS fact_updated_at
FROM scoped_execution e
JOIN target_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
WHERE t.status = 'EXECUTING'
  AND e.execution_status IN (1, 2)
  AND e.manual_paused = 0
  AND e.next_run_at <= @now
  AND (
    e.lock_owner IS NULL
    OR e.lock_expires_at <= @now
  )
  AND e.updated_at <= @now - @stall_grace_ms

UNION ALL

-- 状态与阶段的组合不在 claimDue 的可领取集合内，调度器永远不会捞起这一行。
-- 可领取集合：WAIT_START 仅阶段 1；EXECUTING 阶段 1-7；WAIT_RESOURCE 仅阶段 2-6。
SELECT
    'UNCLAIMABLE_STATE_COMBO',
    e.task_id,
    e.id,
    e.id,
    NULL,
    CONCAT('execution_status=', e.execution_status, ', stage=', e.stage),
    (@now - e.updated_at) DIV 1000,
    e.updated_at
FROM scoped_execution e
WHERE (e.execution_status = 1 AND e.stage <> 1)
   OR (e.execution_status = 3 AND e.stage NOT IN (2, 3, 4, 5, 6))

UNION ALL

-- 租约数据不一致：持有者非空但没有过期时间，既不会被续约也不会被回收。
SELECT
    'LEASE_INCONSISTENT',
    e.task_id,
    e.id,
    e.id,
    NULL,
    CONCAT('lock_owner=', e.lock_owner, ', lock_expires_at IS NULL'),
    (@now - e.updated_at) DIV 1000,
    e.updated_at
FROM scoped_execution e
WHERE e.lock_owner IS NOT NULL
  AND e.lock_expires_at IS NULL

UNION ALL

-- 资源等待。WAIT_RESOURCE 每轮都会被重新领取，
-- 所以 STALLED(到期且长时间无更新)是真异常，RETRYING 才是正常业务等待。
SELECT
    'WAIT_RESOURCE',
    e.task_id,
    e.id,
    e.id,
    NULL,
    CONCAT(
        'wait_resource_type=', COALESCE(e.wait_resource_type, -1),
        ', reason_code=', COALESCE(e.reason_code, 'NULL'),
        ', ', CASE
            WHEN e.next_run_at <= @now AND e.updated_at <= @now - @stall_grace_ms
                THEN 'STALLED'
            ELSE 'RETRYING'
        END
    ),
    (@now - e.updated_at) DIV 1000,
    e.updated_at
FROM scoped_execution e
WHERE e.execution_status = 3

UNION ALL

-- 动作行已生成但迟迟没有 commandId：事务编排或 Outbox 入库前。
SELECT
    'PENDING_ACTION_WITHOUT_COMMAND',
    a.task_id,
    a.group_execution_id,
    a.id,
    a.command_id,
    CONCAT('action_type=', a.action_type, ', action_status=', a.action_status),
    (@now - a.updated_at) DIV 1000,
    a.updated_at
FROM pull_task_account_action a
JOIN scoped_execution e
  ON e.id = a.group_execution_id
 AND e.tenant_id = a.tenant_id
WHERE a.action_status = 1
  AND a.command_id IS NULL
  AND a.updated_at <= @now - @stall_grace_ms

UNION ALL

SELECT
    'PENDING_CALL_WITHOUT_COMMAND',
    c.task_id,
    c.group_execution_id,
    c.id,
    c.command_id,
    CONCAT('call_seq=', c.call_seq, ', call_status=', c.call_status),
    (@now - c.updated_at) DIV 1000,
    c.updated_at
FROM pull_task_pull_call c
JOIN scoped_execution e
  ON e.id = c.group_execution_id
 AND e.tenant_id = c.tenant_id
WHERE c.call_status = 1
  AND c.command_id IS NULL
  AND c.updated_at <= @now - @stall_grace_ms

UNION ALL

-- 业务事实已有 commandId，但 Outbox 没有对应行(或已软删)：命令链路断在入库。
SELECT
    'COMMAND_WITHOUT_OUTBOX',
    tc.task_id,
    tc.execution_id,
    tc.fact_id,
    tc.command_id,
    CONCAT(tc.fact_type, ': fact_status=', tc.fact_status, ', outbox row missing'),
    (@now - tc.updated_at) DIV 1000,
    tc.updated_at
FROM task_commands tc
LEFT JOIN protocol_command_outbox o
  ON o.command_id = tc.command_id
 AND o.tenant_id = tc.tenant_id
 AND o.deleted_at IS NULL
WHERE tc.command_id IS NOT NULL
  AND o.id IS NULL
  AND tc.updated_at <= @now - @stall_grace_ms

UNION ALL

-- Outbox 死信：发布重试耗尽或不可恢复失败。
SELECT
    'OUTBOX_DEAD',
    tc.task_id,
    tc.execution_id,
    tc.fact_id,
    tc.command_id,
    CONCAT(tc.fact_type, ': ', COALESCE(o.last_error, 'OUTBOX_DEAD')),
    (@now - o.updated_at) DIV 1000,
    o.updated_at
FROM task_commands tc
JOIN protocol_command_outbox o
  ON o.command_id = tc.command_id
 AND o.tenant_id = tc.tenant_id
 AND o.deleted_at IS NULL
WHERE o.status = 3

UNION ALL

-- Outbox 长时间停在 LOCKED / DISPATCHING / CANCEL_REQUESTED：
-- publisher 抢占后崩溃或结束流程收不了口，这些行不会自己流转。
SELECT
    'OUTBOX_STUCK',
    tc.task_id,
    tc.execution_id,
    tc.fact_id,
    tc.command_id,
    CONCAT(
        tc.fact_type, ': outbox_status=', o.status,
        ', locked_by=', COALESCE(o.locked_by, 'NULL'),
        ', retry_count=', o.retry_count
    ),
    (@now - o.updated_at) DIV 1000,
    o.updated_at
FROM task_commands tc
JOIN protocol_command_outbox o
  ON o.command_id = tc.command_id
 AND o.tenant_id = tc.tenant_id
 AND o.deleted_at IS NULL
WHERE o.status IN (1, 5, 6)
  AND o.updated_at <= @now - @outbox_stuck_ms

UNION ALL

-- Outbox 仍是 PENDING，重试时间已到却长时间没被 publisher 推进。
SELECT
    'OUTBOX_PENDING_OVERDUE',
    tc.task_id,
    tc.execution_id,
    tc.fact_id,
    tc.command_id,
    CONCAT(
        tc.fact_type, ': retry_count=', o.retry_count,
        ', next_retry_at=', o.next_retry_at,
        ', last_error=', COALESCE(o.last_error, 'NULL')
    ),
    (@now - o.updated_at) DIV 1000,
    o.updated_at
FROM task_commands tc
JOIN protocol_command_outbox o
  ON o.command_id = tc.command_id
 AND o.tenant_id = tc.tenant_id
 AND o.deleted_at IS NULL
WHERE o.status = 0
  AND o.next_retry_at <= @now
  AND o.updated_at <= @now - @outbox_stuck_ms

UNION ALL

-- 已提交超过保护期仍未转 UNKNOWN：未知结果收敛调度没跑，
-- 或本轮候选执行行数超过 resultReconciliationBatchSize(默认 100)。
-- 这取代了原来的 SENT_WITHOUT_BUSINESS_RESULT：
-- 命令刚发送时短暂没有结果属于正常，保护期内刻意不输出。
SELECT
    'RECONCILIATION_OVERDUE',
    tc.task_id,
    tc.execution_id,
    tc.fact_id,
    tc.command_id,
    CONCAT(
        tc.fact_type, ': fact_status=', tc.fact_status,
        ', outbox_status=', COALESCE(CAST(o.status AS CHAR), 'NONE')
    ),
    (@now - COALESCE(tc.submitted_at, tc.updated_at)) DIV 1000,
    tc.updated_at
FROM task_commands tc
LEFT JOIN protocol_command_outbox o
  ON o.command_id = tc.command_id
 AND o.tenant_id = tc.tenant_id
 AND o.deleted_at IS NULL
WHERE tc.fact_status = 2
  AND tc.fact_type IN ('ACTION', 'PULL_CALL', 'MATERIAL_ADMIN')
  AND COALESCE(tc.submitted_at, tc.updated_at) <= @now - @reconcile_overdue_ms

UNION ALL

-- 结果无法确认，等待未知结果核对调度与实时状态查询收敛。
SELECT
    'UNKNOWN_RESULT',
    tc.task_id,
    tc.execution_id,
    tc.fact_id,
    tc.command_id,
    CONCAT(tc.fact_type, ': fact_status=', tc.fact_status),
    (@now - tc.updated_at) DIV 1000,
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
    (@now - m.updated_at) DIV 1000,
    m.updated_at
FROM pull_task_material_member m
JOIN scoped_execution e
  ON e.id = m.group_execution_id
 AND e.tenant_id = m.tenant_id
WHERE m.pull_status = 4

UNION ALL

-- 角色账号在群状态停在"入群中"或"结果未知"。
-- 站台(pull_call_id 非空)由未知结果收敛按拉人调用兜底；
-- 管理和拉手(pull_call_id 为空)不在收敛范围内，长时间停留没有任何机制会推进。
SELECT
    'ROLE_MEMBERSHIP_OPEN',
    e.task_id,
    a.group_execution_id,
    a.id,
    NULL,
    CONCAT(
        'role_type=', a.role_type,
        ', membership_status=', a.membership_status,
        ', reason_code=', COALESCE(a.membership_reason_code, 'NULL'),
        ', ', CASE WHEN a.pull_call_id IS NULL
            THEN 'NOT_COVERED_BY_RECONCILIATION'
            ELSE 'CALL_BOUND' END
    ),
    (@now - a.updated_at) DIV 1000,
    a.updated_at
FROM pull_task_group_account a
JOIN scoped_execution e
  ON e.id = a.group_execution_id
 AND e.tenant_id = a.tenant_id
WHERE a.membership_status IN (1, 4)
  AND a.updated_at <= @now - @stall_grace_ms

UNION ALL

-- 角色账号群管理员权限停在"已提交"或"结果未知"。
SELECT
    'ROLE_ADMIN_OPEN',
    e.task_id,
    a.group_execution_id,
    a.id,
    NULL,
    CONCAT('role_type=', a.role_type, ', admin_status=', a.admin_status),
    (@now - a.updated_at) DIV 1000,
    a.updated_at
FROM pull_task_group_account a
JOIN scoped_execution e
  ON e.id = a.group_execution_id
 AND e.tenant_id = a.tenant_id
WHERE a.admin_status IN (2, 5)
  AND a.updated_at <= @now - @stall_grace_ms

UNION ALL

-- 拉手占用泄漏：父任务已终态但 released_at 仍为空。
-- occupancy_key 唯一键会让这个账号在本租户内永远无法被新任务占用，
-- 症状会出现在别的任务上(WAIT_RESOURCE / PULLER_UNAVAILABLE)。
SELECT
    'PULLER_OCCUPANCY_LEAK',
    t.id,
    a.group_execution_id,
    a.id,
    NULL,
    CONCAT(
        'parent_status=', t.status,
        ', account_id=', a.account_id,
        ', occupied_at=', COALESCE(CAST(a.occupied_at AS CHAR), 'NULL')
    ),
    (@now - a.updated_at) DIV 1000,
    a.updated_at
FROM pull_task_group_account a
JOIN scoped_execution e
  ON e.id = a.group_execution_id
 AND e.tenant_id = a.tenant_id
JOIN target_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
WHERE t.status IN ('COMPLETED', 'ENDED')
  AND a.role_type = 2
  AND a.released_at IS NULL

UNION ALL

-- 群链接占用泄漏：父任务已终态但执行行仍停在非终态。
-- link_occupancy_key 会让同一条群链接无法被新任务创建。
SELECT
    'LINK_OCCUPANCY_LEAK',
    t.id,
    e.id,
    e.id,
    NULL,
    CONCAT('parent_status=', t.status, ', execution_status=', e.execution_status),
    (@now - e.updated_at) DIV 1000,
    e.updated_at
FROM scoped_execution e
JOIN target_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
WHERE t.status IN ('COMPLETED', 'ENDED')
  AND e.execution_status IN (1, 2, 3)

UNION ALL

-- 子执行行全部终态但父任务仍在运行态：收口或父任务聚合没有触发。
SELECT
    'TERMINAL_CHILD_NON_TERMINAL_PARENT',
    t.id,
    NULL,
    NULL,
    NULL,
    CONCAT('parent_status=', t.status, ', all executions terminal'),
    (@now - t.updated_at) DIV 1000,
    t.updated_at
FROM target_task t
WHERE @execution_id IS NULL
  AND t.status IN ('EXECUTING', 'PAUSED', 'INTERRUPTED')
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
