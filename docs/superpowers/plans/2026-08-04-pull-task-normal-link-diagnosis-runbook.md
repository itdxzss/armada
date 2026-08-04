# 普通群链接拉群任务测试排查手册 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付一份普通群链接拉群任务快速排查手册和一份以 `taskId` 为入口的只读 MySQL 查询附录。

**Architecture:** 手册负责阶段映射、卡点判断、日志边界和标准结论格式；SQL 附录负责从父任务逐层读取执行行、角色账号、业务动作、拉人调用、料子结果和 Outbox。两份材料只服务 `STANDARD + NORMAL_LINK`，不引入产品功能或运行时依赖。

**Tech Stack:** Markdown、MySQL 8 只读 SQL、Armada Java 状态枚举、MyBatis Mapper、`protocol_command_outbox`

---

## File Structure

- Create: `docs/operations/pull-task-normal-link-diagnosis.md`
  - 快速使用说明、状态与阶段映射、故障判断树、后端/协议层边界、日志检索方法、标准结论模板。
- Create: `docs/operations/pull-task-normal-link-diagnosis.sql`
  - 只读取普通群链接任务事实的查询集合；除注释中的 `SET @task_id` 使用示例外，可执行语句全部为 `SELECT`。
- Reference: `docs/superpowers/specs/2026-08-04-pull-task-normal-link-diagnosis-runbook-design.md`
  - 已确认范围、边界和验收标准，不在实施中改变产品口径。

## Source-of-Truth Anchors

- `armada-api/src/main/java/com/armada/task/model/enums/PullTaskStandardStatus.java`
- `armada-api/src/main/java/com/armada/task/model/enums/PullTaskExecutionStatus.java`
- `armada-api/src/main/java/com/armada/task/model/enums/PullTaskExecutionStage.java`
- `armada-api/src/main/java/com/armada/task/model/enums/PullTaskActionStatus.java`
- `armada-api/src/main/java/com/armada/task/model/enums/PullTaskPullCallStatus.java`
- `armada-api/src/main/java/com/armada/task/model/enums/PullTaskMaterialPullStatus.java`
- `armada-api/src/main/java/com/armada/task/model/enums/PullTaskMaterialAdminStatus.java`
- `armada-api/src/main/java/com/armada/platform/protocol/model/enums/ProtocolCommandOutboxStatus.java`
- `armada-api/src/main/resources/mapper/task/PullTaskMapper.xml`
- `armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml`
- `armada-api/src/main/resources/mapper/task/PullTaskGroupAccountMapper.xml`
- `armada-api/src/main/resources/mapper/task/PullTaskAccountActionMapper.xml`
- `armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml`
- `armada-api/src/main/resources/mapper/task/PullTaskMaterialMemberMapper.xml`
- `armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml`

### Task 1: 编写快速排查手册正文

**Files:**
- Create: `docs/operations/pull-task-normal-link-diagnosis.md`
- Reference: `docs/superpowers/specs/2026-08-04-pull-task-normal-link-diagnosis-runbook-design.md`

- [ ] **Step 1: 写入范围和最少信息模板**

文件开头必须直接说明：

````markdown
# 普通群链接拉群任务测试排查手册

适用范围：`pull_task.task_type='STANDARD'` 且 `pull_task.mode='NORMAL_LINK'`。

本手册只做只读排查，不自动重试、恢复、释放资源、修改状态或重启服务。

## 测试时请保留

```text
环境：测试环境名称
测试时间：精确到约 5 分钟范围
任务 ID：pull_task.id
操作：创建 / 启动 / 暂停 / 恢复 / 结束 / 补充资源
现象：页面可见结果
截图：可选
```
````

- [ ] **Step 2: 写入状态与七阶段映射**

任务状态必须覆盖：`DRAFT`、`WAIT_START`、`EXECUTING`、`PAUSED`、`INTERRUPTED`、`COMPLETED`、`ENDED`。

执行行状态必须覆盖：`DRAFT(0)`、`WAIT_START(1)`、`EXECUTING(2)`、`WAIT_RESOURCE(3)`、`COMPLETED(4)`、`FAILED(5)`、`ABANDONED(6)`。

七阶段必须按源码顺序记录：

```text
1 LINK_VALIDATION
2 MANAGER_JOIN
3 MANAGER_PULLER_CONTACT
4 PULLER_INVITE
5 PULL_EXECUTION
6 MATERIAL_ADMIN
7 CLOSING
```

每个阶段写明主要事实表、预期推进条件和典型故障层。阶段 3 必须注明保存联系人失败通常不阻断主流程；`WAIT_RESOURCE` 必须与程序卡死分开说明。

- [ ] **Step 3: 写入跨层判断树**

正文使用以下固定顺序，不能跳过 Outbox 直接猜协议问题：

```text
父任务
→ 执行行状态、stage、next_run_at、租约
→ 角色账号和业务事实
→ commandId
→ protocol_command_outbox
→ Armada 日志
→ 协议 master/worker 日志
→ 回调与状态收口
```

明确记录以下判定：

- 没有业务事实：调度或阶段编排。
- 事实为待执行且没有 `commandId`：命令准备或 Outbox 入库前。
- Outbox 为 `PENDING/LOCKED/DISPATCHING`：Armada 发布链路。
- Outbox 为 `DEAD`：以 `last_error` 定位发布失败。
- Outbox 为 `SENT` 且事实仍为已提交：协议执行或结果回传。
- 协议已有结果但事实未变化：Armada 回调关联或落库。
- `UNKNOWN`：检查未知结果核对流程。
- `next_run_at` 尚未到达：正常排期。
- `WAIT_RESOURCE`：资源等待。

- [ ] **Step 4: 写入日志检索与标准结论模板**

日志章节要求先限定测试时间窗口，再按 `taskId`、`executionId`、`commandId` 逐步收窄。不得建议无条件扫描完整日志，不得在示例中出现私钥内容、完整号码、完整群链接或命令 `payload_json`。

排查回复模板固定为：

```text
结论：
证据：
影响：
建议：
确定性：已确认 / 高概率 / 信息不足
```

- [ ] **Step 5: 验证正文覆盖设计要求**

Run:

```bash
rg -n "STANDARD|NORMAL_LINK|LINK_VALIDATION|MANAGER_JOIN|MANAGER_PULLER_CONTACT|PULLER_INVITE|PULL_EXECUTION|MATERIAL_ADMIN|CLOSING|WAIT_RESOURCE|commandId|Outbox|确定性" docs/operations/pull-task-normal-link-diagnosis.md
```

Expected: 每个关键词至少命中一次，七阶段顺序与 `PullTaskExecutionStage` 一致。

Run:

```bash
git diff --check -- docs/operations/pull-task-normal-link-diagnosis.md
```

Expected: 无输出，退出码 `0`。

- [ ] **Step 6: 提交手册正文**

```bash
git add docs/operations/pull-task-normal-link-diagnosis.md
git commit -m "docs: add normal link pull task diagnosis runbook"
```

### Task 2: 编写任务、执行行和角色账号基础查询

**Files:**
- Create: `docs/operations/pull-task-normal-link-diagnosis.sql`
- Reference: `armada-api/src/main/resources/mapper/task/PullTaskMapper.xml`
- Reference: `armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml`
- Reference: `armada-api/src/main/resources/mapper/task/PullTaskGroupAccountMapper.xml`

- [ ] **Step 1: 写入只读约束和任务 ID 使用说明**

SQL 文件开头使用注释提供会话变量示例，不把示例写成默认可执行行：

```sql
-- 普通群链接拉群任务只读诊断查询。
-- 使用前在当前 MySQL 会话设置任务 ID，例如：SET @task_id := 123;
-- 目标必须满足 task_type='STANDARD' AND mode='NORMAL_LINK'。
-- 本文件不得输出 normalized_link、invite_code、account_phone、normalized_phone 或 payload_json。
```

- [ ] **Step 2: 添加父任务概况查询**

查询必须从 `pull_task` 返回：

```sql
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
```

手册说明：结果为空时先判定任务 ID 错误、任务已软删或查到了其他模式，不继续拼接后续结论。

- [ ] **Step 3: 添加群执行行概况查询**

只返回非敏感字段，并增加排期和租约判读列：

```sql
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
```

- [ ] **Step 4: 添加角色账号查询**

使用以下查询；不得增加 `account_phone`：

```sql
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
```

- [ ] **Step 5: 静态验证基础查询**

Run:

```bash
rg -n "FROM pull_task$|FROM pull_task_group_execution|FROM pull_task_group_account|task_type = 'STANDARD'|mode = 'NORMAL_LINK'|schedule_state|lease_state" docs/operations/pull-task-normal-link-diagnosis.sql
```

Expected: 三组基础查询和任务模式守卫均有命中。

Run:

```bash
rg -n "^[[:space:]]*(INSERT|UPDATE|DELETE|ALTER|DROP|TRUNCATE|CREATE|REPLACE|CALL|LOCK)[[:space:]]" -i docs/operations/pull-task-normal-link-diagnosis.sql
```

Expected: 无输出，退出码 `1`，证明没有数据或结构写语句。

- [ ] **Step 6: 提交基础 SQL**

```bash
git add docs/operations/pull-task-normal-link-diagnosis.sql
git commit -m "docs: add normal link task diagnosis queries"
```

### Task 3: 补齐业务事实、Outbox 和异常摘要查询

**Files:**
- Modify: `docs/operations/pull-task-normal-link-diagnosis.sql`
- Modify: `docs/operations/pull-task-normal-link-diagnosis.md`
- Reference: `armada-api/src/main/resources/mapper/task/PullTaskAccountActionMapper.xml`
- Reference: `armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml`
- Reference: `armada-api/src/main/resources/mapper/task/PullTaskMaterialMemberMapper.xml`
- Reference: `armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml`

- [ ] **Step 1: 添加账号动作与 Outbox 查询**

使用以下查询，连接必须同时匹配 `tenant_id`。不得增加 `payload_json`、`kafka_key` 或账号号码：

```sql
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
```

- [ ] **Step 2: 添加拉人调用与 Outbox 查询**

使用以下查询，不得增加 `idempotency_key`：

```sql
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
```

- [ ] **Step 3: 添加料子状态汇总与提权命令查询**

第一条查询按 `group_execution_id + pull_status + admin_status` 聚合数量：

```sql
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
```

第二条只列异常或处理中行，并按提权命令关联 Outbox：

```sql
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
```

不得输出 `normalized_phone` 或 `wa_jid`。

- [ ] **Step 4: 添加异常摘要查询**

用 `UNION ALL` 输出统一列：

```text
category, task_id, execution_id, fact_id, command_id, diagnosis, updated_at
```

至少覆盖：

1. `DUE_EXECUTION_NOT_PROGRESSING`：执行行可调度、未暂停、租约为空或过期。
2. `PENDING_ACTION_WITHOUT_COMMAND`：动作 `PENDING(1)` 且无 `command_id`。
3. `PENDING_CALL_WITHOUT_COMMAND`：调用 `PLANNED(1)` 且无 `command_id`。
4. `OUTBOX_DEAD`：本任务动作、调用或提权命令对应 Outbox 为 `DEAD(3)`。
5. `SENT_WITHOUT_BUSINESS_RESULT`：Outbox 为 `SENT(2)`，事实仍为已提交。
6. `UNKNOWN_RESULT`：动作 `UNKNOWN(5)`、调用 `UNKNOWN(4)`、料子入群 `UNKNOWN(4)` 或提权 `UNKNOWN(5)`。
7. `WAIT_RESOURCE`：执行行 `execution_status=3`，输出 `wait_resource_type` 和原因码。
8. `TERMINAL_CHILD_NON_TERMINAL_PARENT`：全部执行行终态而父任务仍为运行态。

“长期”不在 SQL 中硬编码固定分钟数；输出事实时间，由手册结合任务配置、`next_run_at` 和测试时间判断。

使用以下完整查询作为实现骨架；只允许调整诊断中文，不得删减类别或租户关联：

```sql
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
    CONCAT('stage=', e.stage, ', lease=',
           CASE WHEN e.lock_owner IS NULL THEN 'UNLOCKED' ELSE 'EXPIRED' END) AS diagnosis,
    e.updated_at
FROM pull_task_group_execution e
JOIN target_task t
  ON t.id = e.task_id
 AND t.tenant_id = e.tenant_id
WHERE t.status = 'EXECUTING'
  AND e.execution_status IN (1, 2)
  AND e.manual_paused = 0
  AND e.next_run_at <= CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
  AND (e.lock_owner IS NULL
       OR e.lock_expires_at <= CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED))

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
  AND ((tc.fact_type = 'ACTION' AND tc.fact_status = 2)
    OR (tc.fact_type = 'PULL_CALL' AND tc.fact_status = 2)
    OR (tc.fact_type = 'MATERIAL_ADMIN' AND tc.fact_status = 2))

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
    CONCAT('wait_resource_type=', COALESCE(e.wait_resource_type, -1),
           ', reason_code=', COALESCE(e.reason_code, 'NULL')),
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
```

- [ ] **Step 5: 在手册中加入 SQL 结果阅读顺序**

添加一节说明七组结果的阅读顺序，并明确：

- 第一组无结果时停止。
- 优先选择页面异常对应的 `executionId`。
- 先判断排期、暂停和资源等待，再看业务事实。
- 只有拿到 `commandId` 后才进入 Outbox 和协议日志。
- Outbox `SENT` 只证明 Kafka producer 已确认发送，不证明 worker 已执行成功。
- 联系人动作失败不应自动解释为整个任务失败。

- [ ] **Step 6: 验证跨层关联和敏感字段边界**

Run:

```bash
rg -n "pull_task_account_action|pull_task_pull_call|pull_task_material_member|protocol_command_outbox|OUTBOX_DEAD|SENT_WITHOUT_BUSINESS_RESULT|UNKNOWN_RESULT|WAIT_RESOURCE" docs/operations/pull-task-normal-link-diagnosis.sql
```

Expected: 业务事实、Outbox 和四类关键异常均有命中。

Run:

```bash
rg -n "(^|[^a-z_])(normalized_link|invite_code|account_phone|normalized_phone|payload_json|idempotency_key)([^a-z_]|$)" docs/operations/pull-task-normal-link-diagnosis.sql
```

Expected: 只命中开头的禁止输出注释，不命中任何 `SELECT` 投影。

- [ ] **Step 7: 提交跨层诊断查询**

```bash
git add docs/operations/pull-task-normal-link-diagnosis.md docs/operations/pull-task-normal-link-diagnosis.sql
git commit -m "docs: complete normal link task diagnosis guide"
```

### Task 4: 对照源码完成只读安全与一致性验收

**Files:**
- Verify: `docs/operations/pull-task-normal-link-diagnosis.md`
- Verify: `docs/operations/pull-task-normal-link-diagnosis.sql`
- Verify: `docs/superpowers/specs/2026-08-04-pull-task-normal-link-diagnosis-runbook-design.md`

- [ ] **Step 1: 对照枚举核对所有状态值**

逐项对照 Source-of-Truth Anchors，确认：

```text
execution_status: 0..6
stage: 1..7
action_status: 1..6
call_status: 1..5
material pull_status: 0..5
material admin_status: 0..6
outbox status: 0..6
```

发现不一致时只修正文档和 SQL 映射，不修改生产枚举。

- [ ] **Step 2: 对照 Mapper 核对表、列和关联键**

必须确认：

- 所有业务表通过 `task_id` 或 `group_execution_id` 回到目标任务。
- 跨表连接同时匹配 `tenant_id`。
- Outbox 通过 `command_id + tenant_id` 关联。
- `deleted_at IS NULL` 应用于 `pull_task` 和 Outbox。
- SQL 不引用迁移中不存在的列。

- [ ] **Step 3: 运行只读安全扫描**

Run:

```bash
rg -n "^[[:space:]]*(INSERT|UPDATE|DELETE|ALTER|DROP|TRUNCATE|CREATE|REPLACE|CALL|LOCK|START[[:space:]]+TRANSACTION|COMMIT|ROLLBACK)[[:space:]]" -i docs/operations/pull-task-normal-link-diagnosis.sql
```

Expected: 无输出，退出码 `1`。

Run:

```bash
rg -n "dev-1\.pem|xieyi\.pem|BEGIN (RSA |OPENSSH )?PRIVATE KEY|password|secret" -i docs/operations/pull-task-normal-link-diagnosis.md docs/operations/pull-task-normal-link-diagnosis.sql
```

Expected: 无凭据、私钥或密码命中；若正文安全说明出现普通单词 `secret`，改成中文避免误报。

- [ ] **Step 4: 运行文档和 SQL 静态验证**

Run:

```bash
git diff --check -- docs/operations/pull-task-normal-link-diagnosis.md docs/operations/pull-task-normal-link-diagnosis.sql
```

Expected: 无输出，退出码 `0`。

Run:

```bash
rg -n "TBD|TODO|待定|以后补|暂不确定" docs/operations/pull-task-normal-link-diagnosis.md docs/operations/pull-task-normal-link-diagnosis.sql
```

Expected: 无输出，退出码 `1`。

- [ ] **Step 5: 记录无法本地完成的验证边界**

在手册末尾注明：未连接用户确认的测试数据库前，只能完成源码与 SQL 静态核对，不能声称查询已在真实 MySQL 数据上执行通过。首次实际排查时再按用户确认的测试环境运行只读 SQL，并根据真实输出修正文档错误。

- [ ] **Step 6: 提交最终校验修正**

仅当 Task 4 产生修正时执行：

```bash
git add docs/operations/pull-task-normal-link-diagnosis.md docs/operations/pull-task-normal-link-diagnosis.sql
git commit -m "docs: verify normal link diagnosis materials"
```

若没有修正，不创建空提交。
