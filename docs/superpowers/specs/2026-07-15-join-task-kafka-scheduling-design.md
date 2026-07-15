# Web / Android 进群任务 Kafka 调度设计

> 状态：交互方案已确认，待书面复核
> 日期：2026-07-15
> 目标仓库：`armada`、`armada-protocol`、`whatsapp-server-feature-android-zhuan`

## 1. 背景

当前进群任务在 Armada 进程内由 `JoinTaskWorker` 执行。任务启动后，Worker 把计划明细按账号分成 lane，
通过固定大小的账号线程池调用同步 HTTP 进群，并在相邻操作之间 `Thread.sleep` 随机间隔。

这套实现有两个直接问题：

1. 账号 lane 默认最多 16 个。任务包含上百个账号时，前 16 个账号 lane 在随机间隔内持续占用线程，后续账号
   必须排队，不能满足“多个账号同时推进”的业务要求。
2. Web/Baileys 与 Android Zhuan 的进群任务都依赖 Armada 到协议层的同步 HTTP。部署地址缺失、容器网络地址
   不一致或协议响应较慢时，任务直接收敛成协议网络失败；现有 Kafka outbox 的可靠投递能力没有被复用。

一个进群任务可以包含多条 `join_task_result` 计划明细。“同一账号后续记录”指同一个任务内该账号的后续
账号×群链接计划行，不是另一个任务。例如一个任务包含账号 A、B 和链接 L1、L2 时，可以产生 A→L1、A→L2、
B→L1、B→L2 四条计划明细。

本设计把进群任务改为“MySQL 到期时间调度 + Armada outbox + Kafka 命令 + 协议结果事件”。调度器只做短事务
抢占和入队，不执行 WhatsApp 调用，也不通过休眠占用账号线程。

## 2. 已确认需求

1. 一个任务内，不同账号独立推进；同一时刻可以有上百个账号到期并进入 Kafka。
2. 同一个任务、同一个账号的计划明细必须串行，前一条得到结果后才能安排后一条。
3. 相邻进群之间的等待时间，从任务记录的区间内重新随机取值，包含区间上下限。
4. 随机间隔的基准时间是前一个结果在 Armada 成功落库的时间，不使用协议层时钟，避免跨服务时钟偏差。
5. 任务启动时，每个账号的第一条待执行明细立即到期；只有同一账号的后续明细等待前一条结果和随机间隔。
6. 可重试失败必须先重试当前明细。每次重试同样等待新生成的随机间隔，并受任务 `retryLimit` 限制。
7. 当前明细成功、不可重试失败或重试耗尽后，才安排该账号下一条明细。
8. Web 和 Android 进群任务都改用 Kafka；命令使用各自既有 Topic，结果共用既有群事件 Topic。
9. 新进群调度器保持独立，不合并进现有其它定时任务调度器。
10. 页面继续只展示 `PENDING/SUCCESS/FAILED`；内部异步状态不增加前端状态。
11. 串行控制键是 `tenantId + joinTaskId + accountId`。不同任务即使使用同一账号，也不新增跨任务互斥。
12. 本期不设计进群任务的服务重启恢复。

## 3. 非目标

- 不修改前端页面、表单或接口返回结构。
- 不新建 Kafka Topic。
- 不新增跨任务账号锁。
- 不新增暂停、停止或取消任务能力。
- 不实现进群任务的服务重启专项恢复、启动补偿或在途命令接管。
- 不删除 Web 或 Android 现有 HTTP 进群接口；仅让批量进群任务不再调用这些接口。
- 不改造与进群任务无关的群管理 HTTP 能力。
- 不承诺 WhatsApp 外部副作用与本地状态之间的 exactly-once。

## 4. 方案选择

### 4.1 固定线程 lane + 休眠

扩大 lane 线程池可以暂时容纳更多账号，但随机间隔仍会占用线程。账号规模继续增长时，需要继续扩大线程池，
线程数与业务账号数耦合，不采用。

### 4.2 每条明细创建 JVM 延迟任务

可以避免主动休眠，但大量延迟任务只保存在内存中，调度状态与数据库结果分离，多实例抢占和故障排查也更复杂，
不采用。

### 4.3 MySQL 到期调度 + Outbox/Kafka

把每个账号下一次可执行时间保存在明细表。独立调度器扫描到期记录，在同一事务中抢占记录并写入现有协议命令
outbox；协议层异步执行后发布结果事件。等待中的账号不占线程，多个账号可以一起入队，同一账号的后续明细由
状态机串行放行。

采用该方案。

## 5. 总体架构

```text
JoinTaskService.start
  -> 每个 task-account 的第一条 PENDING 明细 next_execute_at = now

独立 JoinTaskDispatchScheduler（单线程、无 sleep）
  -> 扫描 WAITING 且到期的明细
  -> 按 tenant 恢复 TenantContext
  -> 短事务原子抢占明细 + 写 protocol_command_outbox
     -> WEB     -> protocol.master.commands.v1
     -> ANDROID -> protocol.android.commands.v1

Web/Baileys worker 或 Android Zhuan consumer
  -> 执行 group.join.requested
  -> 发布 group.join_result_reported
  -> protocol.group.events.v1

Armada ProtocolGroupEventConsumer
  -> JoinTaskResultService
  -> 幂等应用结果
  -> 重试当前明细，或按随机间隔放行同账号下一条明细
  -> 刷新任务计数与 DONE 状态
```

调度器使用独立的单线程 `ScheduledExecutorService`，默认每 1 秒执行一轮，默认单轮扫描 500 条，可通过配置调整：

```yaml
armada:
  task:
    join-dispatcher:
      enabled: true
      fixed-delay-ms: 1000
      batch-size: 500
```

调度线程只执行候选查询、短事务抢占和 outbox 入队，不调用协议 HTTP/Kafka consumer，不等待结果，也不按账号创建
线程。超过单批上限的到期明细由后续 tick 继续处理。

## 6. 数据模型

### 6.1 `join_task_result` 新增字段

当前表有 13 个字段。本期只增加 4 个核心字段，完成后共 17 个字段：

| 字段 | 类型 | 含义 |
|---|---|---|
| `dispatch_state` | `VARCHAR(16) NOT NULL DEFAULT 'WAITING'` | 内部调度状态：`WAITING/SUBMITTED/TERMINAL` |
| `next_execute_at` | `BIGINT NULL` | 下次可执行时间，epoch 毫秒；空表示尚未放行 |
| `command_id` | `VARCHAR(64) NULL` | 当前尝试对应的 outbox/Kafka 命令 ID |
| `attempt_no` | `INT NOT NULL DEFAULT 0` | 已发起的业务尝试次数；首次发送后为 1 |

不新增 `submitted_at` 和 `result_deadline_at`。Kafka 发送时间使用现有 `protocol_command_outbox.sent_at`；本设计不增加
Armada 等待回执超时后再造命令的 watchdog。

### 6.2 索引

新增两个索引：

```sql
KEY idx_jtr_dispatch (dispatch_state, next_execute_at, id),
KEY idx_jtr_task_account (tenant_id, join_task_id, account_id, status, id)
```

第一个服务跨租户到期候选扫描，第二个服务同任务同账号的在途校验和下一条明细选择。

### 6.3 业务状态与内部状态

`status` 继续表示页面可见的业务结果；`dispatch_state` 只描述异步执行阶段：

| `status` | `dispatch_state` | 含义 |
|---|---|---|
| `PENDING` | `WAITING` | 未放行、等待随机间隔或等待重试 |
| `PENDING` | `SUBMITTED` | 当前尝试已经写入 outbox，等待协议结果 |
| `SUCCESS` | `TERMINAL` | 已进群或已经在群内 |
| `FAILED` | `TERMINAL` | 不可重试或重试耗尽 |

迁移时，历史 `SUCCESS/FAILED` 行回填为 `TERMINAL`；历史 `PENDING` 行回填为 `WAITING` 且
`next_execute_at = NULL`，不会被迁移自动启动。上线切换前必须确保没有旧版 `RUNNING` 进群任务，因为本期明确不做
旧 Worker 内存状态的重启恢复。已有 `DRAFT` 任务可以在新版本中正常启动。

## 7. 调度状态机

### 7.1 创建任务

- 合法计划明细：`status=PENDING`、`dispatch_state=WAITING`、`next_execute_at=NULL`、`attempt_no=0`。
- 无效链接等创建时已经失败的明细：`status=FAILED`、`dispatch_state=TERMINAL`。
- 计划明细仍按现有分配模式生成；本设计不把所有任务强制改成账号与链接的笛卡尔积。

### 7.2 启动任务

启动事务完成以下操作：

1. 把任务从 `DRAFT` 条件更新为 `RUNNING`。
2. 对每个有效 `account_id`，只把 ID 最小的第一条 `PENDING/WAITING` 明细设置为 `next_execute_at=now`。
3. 同一账号其它明细保持 `next_execute_at=NULL`。

如果一个任务只有一个链接和 100 个账号，每个账号只有一条明细，100 条都会立即到期。

### 7.3 抢占和入队

候选必须同时满足：

- 所属任务为 `RUNNING`。
- `status=PENDING`。
- `dispatch_state=WAITING`。
- `next_execute_at <= now`。
- 同一 `tenant_id + join_task_id + account_id` 没有 `SUBMITTED` 明细。

跨租户候选查询只返回 ID 和 `tenant_id`，随后按租户分组恢复 `TenantContext`。每个租户批次在短事务中使用
`SELECT ... FOR UPDATE SKIP LOCKED` 重新校验候选，避免多实例重复抢占。

对每条成功抢占的明细，在同一事务中：

1. 校验账号仍有效，并读取 `protocol_id/protocol_account_id/ws_phone`。
2. 把链接规范化为邀请码；非法值直接终态失败，不写 outbox。
3. 生成新 `command_id`。
4. `attempt_no + 1`。
5. 更新为 `dispatch_state=SUBMITTED`、`next_execute_at=NULL`。
6. 写入一条 `protocol_command_outbox`。

明细状态更新与 outbox 插入必须同事务成功或同事务回滚。账号域数据通过账号 Service 查询，不从任务 Service
直接调用账号 Mapper。调度时发现账号不存在、协议标识缺失或邀请码非法时，不写 outbox；当前明细按不可重试失败
进入终态，并继续使用第 7.5 节的随机间隔规则安排该账号下一条明细。批量进群任务不再同步查询协议运行态，账号
是否在线由实际消费命令的 Web/Android 协议端判断并回报。

### 7.4 随机间隔

根据任务现有分配模式读取对应区间：

- `FIXED_ACCOUNTS_PER_LINK` 使用 `fixed_interval_min_sec/fixed_interval_max_sec`。
- `FIXED_ACCOUNT_MULTI_LINK` 使用 `multi_interval_min_sec/multi_interval_max_sec`。

每次需要安排下一次执行时，重新生成闭区间 `[min,max]` 内的随机秒数。若上下限相等则使用固定值；保存前对非法
负数或颠倒区间执行与任务创建校验一致的拒绝逻辑，不静默交换上下限。

`next_execute_at = Armada 结果应用时间 + randomInterval`。协议事件中的 `occurredAt/timestamp` 只用于诊断，
不作为调度基准。

### 7.5 结果后的流转

- `JOINED/ALREADY_JOINED`：当前行更新为 `SUCCESS/TERMINAL`，清空 `reason`，保存 `group_jid`。
- `PENDING_APPROVAL`：当前行更新为 `FAILED/TERMINAL`，`reason=JOIN_PENDING_APPROVAL`，不重试。
- 可重试失败且仍有重试额度：保持 `status=PENDING`，更新为 `WAITING`，保留最近 `reason`，为当前行安排随机时间。
- 不可重试失败或重试耗尽：当前行更新为 `FAILED/TERMINAL`，保存最终 `reason`。

`retryLimit` 表示首次尝试之外允许的额外重试次数，因此最大业务尝试数为 `1 + retryLimit`。若
`attempt_no <= retryLimit`，当前失败后仍可再安排一次；否则终态失败。

当前行进入终态后，才查找同一 `tenant_id + join_task_id + account_id` 下 ID 更大的下一条
`PENDING/WAITING/next_execute_at=NULL` 明细，并从当前结果应用时间开始安排新的随机间隔。

## 8. Kafka 命令契约

### 8.1 Topic 路由

发送命令不是同一个 Topic：

| 方向 | Topic |
|---|---|
| Armada → Web/Baileys | `protocol.master.commands.v1` |
| Armada → Android Zhuan | `protocol.android.commands.v1` |
| Web/Android → Armada 结果 | `protocol.group.events.v1` |

命令 Topic 按协议后端拆分；结果 Topic 按群事件领域统一。三个 Topic 均为现有 Topic，不新建 Topic。Android Zhuan
需要补齐群事件 Topic 的配置项和 publisher 路由。

### 8.2 命令 envelope

统一命令类型为 `group.join.requested`：

```json
{
  "commandId": "cmd_xxx",
  "batchId": "join-task:9",
  "commandType": "group.join.requested",
  "aggregateType": "JOIN_TASK_RESULT",
  "aggregateId": 26,
  "protocolAccountId": "acc_xxx",
  "payload": {
    "tenantId": 1,
    "joinTaskId": 9,
    "joinTaskResultId": 26,
    "accountId": 382,
    "protocolAccountId": "acc_xxx",
    "wsPhone": "919000000001",
    "protocolBackend": "ANDROID",
    "inviteCode": "ABC123",
    "attemptNo": 1,
    "source": "join_task"
  }
}
```

- Kafka key 固定为 `protocolAccountId`。
- Armada 负责从完整 URL 或纯邀请码中提取并校验 `inviteCode`，协议层不再分别兼容多种 URL 格式。
- Web 可以忽略 `wsPhone`；Android 必须使用显式 `wsPhone`，禁止从协议账号句柄截取手机号。
- `aggregateId` 与 `joinTaskResultId` 都指向当前明细，前者用于通用 outbox 关联，后者用于结果契约显式表达。

## 9. 协议执行与结果契约

### 9.1 Web/Baileys

Web master command parser 和 worker inbox 增加 `group.join.requested`：

1. master 根据 `protocolAccountId` 路由到 owner worker。
2. worker 校验账号运行态，复用账号 operation gate 执行进群。
3. 调用现有 Baileys `groupAcceptInvite` 能力。
4. 已在群内的已知响应归一为 `ALREADY_JOINED`；成功返回群 JID 归一为 `JOINED`；需要审核归一为
   `PENDING_APPROVAL`。
5. 发布结果成功后才 XACK worker Redis Stream 明细。发布失败时保留 pending，由 worker inbox 重投。

现有 `/v1/groups/join` HTTP 路由保留，但进群任务不再经过该路由。

### 9.2 Android Zhuan

Android command consumer 增加 `group.join.requested` decoder 和 executor：

1. 使用 payload 的 `wsPhone` 定位本地账号。
2. 调用现有原生邀请码进群能力。
3. 保留现有“进群后查询成员关系”的确认语义，区分真实入群和待管理员审核。
4. 发布结果成功后才提交 Kafka offset；发布失败时由 Kafka 重投原命令。

现有 Android HTTP 进群接口保留。

### 9.3 执行超时

Web 和 Android 单次协议执行默认超时 60 秒，可在各协议服务配置。执行超时必须形成一个正常失败结果：

```text
outcome=FAILED
reasonCode=TIMEOUT
retryable=true
```

Armada 收到后按任务随机间隔重试当前明细，生成新 `command_id` 并增加 `attempt_no`，受 `retryLimit` 限制。

### 9.4 结果事件

统一结果事件类型为 `group.join_result_reported`：

```json
{
  "eventId": "acc_xxx:group.join_result_reported:cmd_xxx",
  "event": "group.join_result_reported",
  "version": "v1",
  "accountId": "acc_xxx",
  "occurredAt": "2026-07-15T08:00:00Z",
  "workerId": "worker-1",
  "data": {
    "tenantId": 1,
    "joinTaskId": 9,
    "joinTaskResultId": 26,
    "accountId": 382,
    "protocolAccountId": "acc_xxx",
    "commandId": "cmd_xxx",
    "attemptNo": 1,
    "outcome": "JOINED",
    "groupJid": "120363000000000000@g.us",
    "reasonCode": "",
    "reasonMessage": "",
    "retryable": false,
    "timestamp": 1784102400000
  }
}
```

允许的 `outcome`：

- `JOINED`
- `ALREADY_JOINED`
- `PENDING_APPROVAL`
- `FAILED`

`eventId` 由 `protocolAccountId + event type + commandId` 稳定生成。同一命令重发结果时保持相同 eventId。

## 10. 超时重投与幂等

### 10.1 Armada 到 Kafka 发送超时

复用现有 `protocol_command_outbox` dispatcher：

- Kafka producer 发送失败或 10 秒内未收到 ack 时，outbox 回到 `PENDING`。
- 默认 30 秒后重发，最多失败 3 次。
- 重发使用同一 Topic、key、payload 和 `command_id`，不增加 `attempt_no`，因为这只是同一次业务尝试的传输重投。
- outbox 最终进入 `DEAD` 时，进群调度器把对应当前明细按 `KAFKA_PUBLISH_FAILED` 作为可重试失败处理；仍有
  业务重试额度则按随机间隔生成下一次业务尝试，否则终态失败。

### 10.2 协议结果发布失败

协议层必须遵守“保存执行结果 → 发布结果事件 → 提交输入消息”的顺序：

- Web：Kafka master 只负责把命令可靠转入 owner worker Redis Stream；worker 结果事件发布成功后才 XACK。
- Android：结果事件发布成功后才提交 Kafka offset。

因此结果发布失败时不会由 Armada 再造命令，而是 Web worker inbox 或 Android Kafka consumer 重投原命令。

### 10.3 协议命令幂等

两套协议都按 `commandId` 保存最小命令状态：

```text
PROCESSING -> RESULT_STORED -> PUBLISHED
```

- Web 新增 Redis 命令结果状态；Android 复用现有消息命令 `message_state` 的 Redis CAS 模式实现独立进群状态。
- 重投命中 `RESULT_STORED` 时只重发相同结果事件，不再次调用 WhatsApp。
- 重投命中 `PUBLISHED` 时直接确认输入消息。
- 同一命令处于有效 `PROCESSING` 窗口时，第二个 consumer 不并发执行。
- `PROCESSING` 超过协议执行窗口仍没有结果时，固定为 `JOIN_RESULT_UNCONFIRMED/retryable=true` 后发布；不使用同一
  `commandId` 直接再次执行 WhatsApp。Armada 是否产生下一次业务尝试仍由任务 `retryLimit` 和随机间隔决定。

这能覆盖正常的消息重投和结果发布重试。外部 WhatsApp 动作成功后、协议状态保存前进程立即崩溃仍存在不确定
窗口，因此本设计不声明 exactly-once。

### 10.4 Armada 结果幂等

Armada 扩展现有 `ProtocolGroupEventConsumer`，把 join 事件委托给任务域 Service。结果更新必须同时匹配：

- `tenant_id`
- `join_task_result.id`
- `status=PENDING`
- `dispatch_state=SUBMITTED`
- 当前 `command_id`
- 当前 `attempt_no`

匹配成功的事务才允许更新结果、刷新计数或安排下一条明细。重复事件条件更新为 0，直接视为幂等成功；旧尝试的
延迟事件因 commandId 或 attemptNo 不匹配被记录并忽略。

Armada 不增加“等待结果超过 N 秒就主动再发一条命令”的 watchdog，避免协议已经执行但事件延迟时重复进群。

## 11. 失败分类

协议层返回的显式 `retryable` 是第一判断依据。没有显式值时，沿用现有进群错误分类：

- 永久失败：邀请码无效/撤销、群不可用、触达受限、账号不存在或未在线、参数错误、协议拒绝、后端不支持、
  需要重新认证、缺少代理、Android 响应不可识别，以及 `PENDING_APPROVAL`。
- 默认可重试：网络异常、执行超时、协议临时不可用、owner/worker 繁忙、限流和未细分内部错误。

等待重试时 `status` 保持 `PENDING`，`reason` 保存最近一次原因；最终成功时清空，最终失败时保留最终原因。

## 12. 计数与任务完成

- 业务重试期间不增加 `executed/success/failed`，该明细仍计入 `pending`。
- 只有明细第一次从 `PENDING` 条件更新为 `SUCCESS` 或 `FAILED` 时才改变任务计数。
- 计数使用数据库聚合刷新或等价的条件增量，必须保证重复事件不会重复累计。
- 当任务下所有有效明细均为终态、`pending=0` 时，把 `join_task.status` 更新为 `DONE`。
- 一个账号当前明细终态只影响该账号下一条明细，不阻塞其它账号。

## 13. 线程与性能边界

- 删除进群任务对固定 16 lane 线程池和 `Thread.sleep` 的依赖。
- 独立调度器固定占用一个调度线程；没有到期数据时只执行一次有索引的空扫描。
- 100 个账号各一条明细时，100 条可以在同一调度批次写入 outbox，不再等待 16 个 lane 释放。
- 真正的 WhatsApp 并发上限由 Web worker 和 Android consumer 自身容量控制；Armada 不为每个账号创建线程。
- Kafka key 使用 `protocolAccountId` 提供同协议账号的消息顺序；任务域的数据库条件仍是同任务同账号串行的权威
  约束。
- 不同任务使用同一账号时不加互斥；这是已确认的业务边界，不依赖 Kafka key 形成新的业务锁语义。

## 14. 仓库改造边界

### 14.1 `armada`

- Flyway 迁移和 `JoinTaskResult` 四字段映射。
- 任务启动时放行每账号第一条明细。
- 独立 `JoinTaskDispatchScheduler`、候选查询、事务抢占和随机时间计算。
- 新增统一进群 Kafka command request/outbox 入队方法，按 `ProtocolBackend` 选择 Web/Android Topic。
- 扩展 `ProtocolGroupEventConsumer` 和任务域结果 Service。
- 删除批量进群任务对 `JoinTaskWorker`、账号 lane pool、同步在线状态 HTTP 和同步 `GroupJoinPort` 的调用。
- 仅在确认无其它调用方后删除变成无引用的 Worker 配置或 Bean；HTTP adapter 和端口本身暂时保留。

### 14.2 `armada-protocol`

- master/worker command 类型增加 `group.join.requested`。
- owner 路由、worker executor、payload 校验和错误归一。
- 进群结果 Redis 状态机和群事件 publisher。
- 结果发布成功后再 XACK worker stream。
- 现有 HTTP 路由保持兼容。

### 14.3 `whatsapp-server-feature-android-zhuan`

- Android Kafka command decoder/handler 增加 `group.join.requested`。
- 复用原生进群与成员关系确认能力。
- 增加进群命令 Redis 状态机。
- 增加 `group.join_result_reported` 构造与发布。
- Kafka 配置增加 group event topic，默认 `protocol.group.events.v1`。
- 现有 HTTP 路由保持兼容。

## 15. 测试策略

### 15.1 Armada

- Flyway + 真实 MySQL `DbTest` 验证新增字段、默认值、回填和索引。
- Mapper `DbTest` 验证到期扫描、`SKIP LOCKED` 抢占、同任务同账号只允许一条在途、下一条选择。
- Service 测试覆盖首次放行、随机区间边界、重试额度、永久失败、成功、待审核和任务 DONE。
- Outbox 测试覆盖 Web/Android Topic、Kafka key、payload 和同事务回滚。
- Consumer 测试覆盖重复事件、旧 commandId、旧 attemptNo、非法事件和跨租户隔离。
- 容量行为测试至少构造 100 个账号，证明同一批次可以入队超过 16 条且不存在休眠线程。

### 15.2 Web/Baileys

- command parser/master router/worker inbox 测试。
- `JOINED/ALREADY_JOINED/PENDING_APPROVAL/FAILED/TIMEOUT` 结果映射测试。
- 结果发布失败不 XACK、重投只发布缓存结果、不重复调用 `groupAcceptInvite` 测试。
- 运行现有 Jest/TypeScript 全量回归。

### 15.3 Android Zhuan

- command decoder、字段校验和手机号定位测试。
- 原生成功、待审核、成员确认失败、超时和错误映射测试。
- Redis 状态机 CAS、结果发布失败不提交 offset、重投不重复进群测试。
- group event topic 默认值与配置覆盖测试。
- 运行相关包测试及 `go test ./...`。

## 16. 可观测性

结构化日志至少携带：`tenantId`、`joinTaskId`、`joinTaskResultId`、`accountId`、`protocolAccountId`、
`protocolBackend`、`commandId`、`attemptNo`、`outcome`、`reasonCode`。邀请码只记录后缀，不记录完整链接；
手机号按现有规则脱敏。

调度日志记录每轮 `scanned/claimed/enqueued/skipped`，结果日志区分 `applied/duplicate/stale/retried/terminal`。
本期不新增监控平台或持久化审计表。

## 17. 发布与回滚

发布顺序：

1. 确认测试/生产目标环境没有 `RUNNING` 的旧进群任务。
2. 先发布 Web 和 Android 协议消费者，使两端能够识别新命令，但此时 Armada 尚不生产新命令。
3. 再发布 Armada Flyway 和调度器，开启 `group.join.requested` 生产。

回滚时先通过配置关闭独立进群调度器，防止继续生成命令，再回滚 Armada 应用；现有 HTTP 进群接口保留，因此旧版
Worker 镜像仍可工作。已进入 Kafka 的命令按发布时协议版本完成结果闭环。

本设计文档不授权实际部署、SSH 修改、Topic 创建或远程数据修改；这些操作必须另行确认目标环境。

## 18. 验收标准

1. 单任务包含 100 个以上账号时，不受 16 lane 限制；首条明细可在同一调度批次进入 outbox。
2. 同一任务同一账号始终最多一条 `SUBMITTED`，相邻业务尝试符合配置的随机区间。
3. Web 与 Android 命令分别进入正确 Topic，两个协议都能执行并回报统一结果。
4. Kafka 发送超时、协议执行超时、结果发布失败、重复命令、重复结果和旧结果均按本设计收敛。
5. 重试期间页面仍显示 `PENDING`，终态计数准确，全部完成后任务为 `DONE`。
6. 进群调度不再使用账号 lane 线程池或 `Thread.sleep`。
7. 现有 Web/Android HTTP 进群接口和非进群 Kafka 能力无回归。
