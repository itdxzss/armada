# 普通群链接拉群任务测试排查手册

适用范围：`pull_task.task_type='STANDARD'` 且 `pull_task.mode='NORMAL_LINK'`。

本手册只做只读排查，不自动重试、恢复、释放资源、修改状态或重启服务。旧建群营销、拉群营销、历史群拉人和其他拉群模式不使用本手册。

## 测试时请保留

```text
环境：测试环境名称
测试时间：精确到约 5 分钟范围
任务 ID：pull_task.id
操作：创建 / 启动 / 暂停 / 恢复 / 结束 / 补充资源
现象：页面可见结果
截图：可选
```

后续排查优先使用 `taskId` 找到 `executionId`，再使用 `commandId` 串联 Armada、Outbox 和协议层。不要在聊天或排查记录中粘贴完整号码、完整群链接、私钥、密码或命令载荷。

## 快速判断顺序

```text
父任务
→ 冻结执行配置（并发、间隔、人数、提权时点）
→ 执行行状态、stage、next_run_at、租约
→ 角色账号和业务事实
→ commandId
→ protocol_command_outbox
→ Armada 日志
→ 协议 master/worker 日志
→ 回调与状态收口
```

不要跳过业务事实和 Outbox，直接根据页面“执行中”猜测协议层异常。

也不要跳过冻结执行配置：并发上限、拉人间隔、要求管理员人数和提权时点决定了哪些“不动”本来就是正常的。见“先排除的正常现象”。

## 任务状态

| 状态 | 含义 | 首要检查 |
| --- | --- | --- |
| `DRAFT` | 创建页内部草稿，不进入正式调度 | 草稿计划、链接/TXT 解析和提交冻结 |
| `WAIT_START` | 已提交，等待启动 | 是否调用启动接口、启动校验是否通过 |
| `EXECUTING` | 父任务执行中 | 执行行状态、阶段、排期和租约 |
| `PAUSED` | 父任务已暂停 | 是否为人工暂停，已提交动作是否正在安全收口 |
| `INTERRUPTED` | 父任务已中断 | 中断原因、执行行和资源释放状态 |
| `COMPLETED` | 自然完成 | 是否仍有非终态执行行或未释放拉手 |
| `ENDED` | 人工结束 | 未开始事实是否已取消、已提交事实是否已收口 |

父任务为 `EXECUTING` 只说明任务生命周期正在运行，不能证明每条群执行行都在主动执行。

## 群执行行状态

| 值 | 状态 | 含义 |
| --- | --- | --- |
| `0` | `DRAFT` | 创建页未提交的计划行 |
| `1` | `WAIT_START` | 已冻结，等待调度领取 |
| `2` | `EXECUTING` | 正在推进某个业务阶段 |
| `3` | `WAIT_RESOURCE` | 等待管理、拉手或站台资源 |
| `4` | `COMPLETED` | 本行正常收口 |
| `5` | `FAILED` | 不可恢复失败终态 |
| `6` | `ABANDONED` | 人工放弃终态 |

`WAIT_RESOURCE` 是明确的业务等待，不直接判定为程序卡死。需要结合 `wait_resource_type`、`reason_code` 和对应账号池确认缺少什么资源。

但 `WAIT_RESOURCE` 并不是"挂起不动"：调度器每轮都会重新领取阶段 2 到 6 的 `WAIT_RESOURCE` 行去复查资源。所以已经到期（`next_run_at` 已过）却长时间没有 `updated_at` 变化的 `WAIT_RESOURCE` 是真异常，异常摘要会在 `WAIT_RESOURCE` 候选里标成 `STALLED`；标成 `RETRYING` 的才是正常的资源等待。

调度器只领取下面这些状态与阶段的组合，其他组合永远不会被捞起：

| 执行状态 | 可被领取的阶段 |
| --- | --- |
| `WAIT_START`（1） | 仅阶段 1 |
| `EXECUTING`（2） | 阶段 1 到 7 |
| `WAIT_RESOURCE`（3） | 仅阶段 2 到 6 |

异常摘要的 `UNCLAIMABLE_STATE_COMBO` 就是检查这张表之外的组合。

## 七个执行阶段

| 阶段 | 枚举 | 主要事实 | 正常推进条件 | 典型故障层 |
| --- | --- | --- | --- | --- |
| 1 | `LINK_VALIDATION` | 执行行 `group_jid`、`reason_code`、`next_run_at` | 链接有效并解析出群 JID | 链接失效、公开页网络、后端校验 |
| 2 | `MANAGER_JOIN` | 管理角色、`JOIN_BY_LINK` 动作、在群状态 | 管理账号确认在群 | 账号选择、协议进群、入群待审批 |
| 3 | `MANAGER_PULLER_CONTACT` | 双向 `SAVE_CONTACT` 动作 | 联系人动作进入终态 | 命令准备、Outbox、协议联系人操作 |
| 4 | `PULLER_INVITE` | `INVITE_TO_GROUP`、拉手在群状态 | 计划拉手确认在群 | 管理权限、拉手可用性、协议邀请 |
| 5 | `PULL_EXECUTION` | 调用计划、双向 `SAVE_CONTACT`（拉手↔站台）、`pull_task_pull_call`、站台和料子结果 | 当前调用逐参与者结果回写并继续取料或进入下一阶段 | 拉手分配、拉手站台联系人、批量拉人、协议回调 |
| 6 | `MATERIAL_ADMIN` | `admin_status`、`admin_command_id` | 需要提权的成功入群料子全部终态 | 管理员设置、Outbox、协议结果 |
| 7 | `CLOSING` | 拉手释放、执行行终态、父任务聚合 | 本行收口并触发父任务完成检查 | 资源释放、状态汇总 |

保存联系人动作出现在两个阶段，不要只按阶段 3 定位：阶段 3 是管理↔拉手，阶段 5 是拉手↔站台（`PullTaskPullExecutionProcessor` 把"调用计划 → 拉手站台联系人 → 整批拉人"串在同一阶段内）。诊断 SQL 结果 5 会输出 `actor_role_type` 和 `target_role_type`，据此区分是哪一组联系人。

保存联系人失败通常不阻断后续邀请或拉人。看到联系人动作 `FAILED` 时，先检查阶段是否仍能推进，不要直接把整个任务判成失败。

## 业务事实状态

### 角色账号

角色：`1` 管理、`2` 拉手、`3` 站台。来源：`1` 初始选择、`2` 人工补充。选号方式：`1` 自动、`2` 手动。

进群方式 `entry_mode`：`1` 踩链接、`2` 管理员邀请、`3` 拉手拉入；站台补充为 `NULL`。

在群状态 `membership_status`：

| 值 | 状态 | 含义 |
| --- | --- | --- |
| `0` | `NOT_JOINED` | 未入群 |
| `1` | `JOINING` | 入群中，命令已发出等待结果 |
| `2` | `IN_GROUP` | 已确认在群 |
| `3` | `JOIN_FAILED` | 入群明确失败 |
| `4` | `UNKNOWN` | 入群结果无法确认 |

失败或不确定原因看 `membership_reason_code` 和 `membership_reason_message`。这两列与 `unavailable_reason_code`（账号可用性原因）不同源，不要混用。

角色账号的群管理员权限 `admin_status`：`0` 不适用、`1` 待设置、`2` 已提交、`3` 成功、`4` 失败、`5` 结果未知。注意这一组取值与料子提权 `admin_status` 不同，不要互相套用。

可用性 `availability_status`：

| 值 | 状态 | 含义 |
| --- | --- | --- |
| `1` | `AVAILABLE` | 可用 |
| `2` | `RISK_COOLDOWN` | 风控冷却，看 `cooldown_until` |
| `3` | `OFFLINE` | 离线或不可用 |
| `4` | `REMOVED` | 已移出本执行行 |

`puller_risk_minutes` 为 `0` 时不会建立定时恢复，风控冷却的拉手不会自动回到可用。

拉手占用看 `released_at`：为 `NULL` 表示仍在占用中，跨任务互斥。

### 账号动作

动作类型：

| 值 | 类型 |
| --- | --- |
| `1` | `SAVE_CONTACT`，单向保存联系人 |
| `2` | `INVITE_TO_GROUP`，邀请账号入群 |
| `3` | `JOIN_BY_LINK`，账号踩链接入群 |

动作状态：

| 值 | 状态 | 含义 |
| --- | --- | --- |
| `1` | `PENDING` | 动作行已生成，命令尚未发出 |
| `2` | `SUBMITTED` | 已生成 `commandId`，等待结果 |
| `3` | `SUCCESS` | 协议确认成功 |
| `4` | `FAILED` | 协议明确失败 |
| `5` | `UNKNOWN` | 结果无法确认，等待核对 |
| `6` | `CANCELED` | 任务结束时取消未完成动作 |

### 拉人调用

| 值 | 状态 | 含义 |
| --- | --- | --- |
| `1` | `PLANNED` | 调用和成员绑定已生成，命令尚未发出 |
| `2` | `SUBMITTED` | 批量加成员命令已发出 |
| `3` | `WRITTEN_BACK` | 逐参与者结果已回写 |
| `4` | `UNKNOWN` | 调用结果无法确认 |
| `5` | `CANCELED` | 未发出的调用已取消 |

### 料子入群与提权

料子入群 `pull_status`：`0` 未消费、`1` 已提交、`2` 成功、`3` 失败、`4` 结果未知、`5` 取消。

料子提权 `admin_status`：`0` 不需要、`1` 待执行、`2` 已提交、`3` 成功、`4` 失败、`5` 结果未知、`6` 取消。

料子入群明确失败后不换拉手重试；提权失败不反向修改该号码已经确认的入群成功结果。

### 执行行原因码

`pull_task_group_execution.reason_code` 只有下面这几个取值，出现其他值先怀疑代码新增未同步本手册。

| 原因码 | 含义 | 首要检查 |
| --- | --- | --- |
| `LINK_INVALID` | 群链接已失效 | 阶段 1；链接本身，不是程序问题 |
| `LINK_PROBE_INCOMPLETE` | 群链接校验暂不可用 | 公开页网络或探测服务；会按重试延迟自动复查 |
| `MANAGER_UNAVAILABLE` | 当前没有可用管理员 | 管理分组可用账号数与 `required_manager_count` |
| `MANAGER_JOIN_PENDING_APPROVAL` | 管理员入群等待审批 | 群本身要求审批，属业务等待 |
| `MANAGER_MEMBERSHIP_UNCONFIRMED` | 管理员在群结果无法确认 | 阶段 2 的踩链接动作与实时群成员查询 |
| `PULLER_UNAVAILABLE` | 当前没有可用拉手 | 拉手分组、跨任务占用、风控冷却 |
| `STATION_UNAVAILABLE` | 当前可用站台不足 | 站台分组与 `station_count_per_call` |

`PULLER_UNAVAILABLE` 要同时排除占用泄漏：别的任务遗留的未释放拉手会让本任务一直缺拉手。见异常摘要的 `PULLER_OCCUPANCY_LEAK`。

## Outbox 状态

| 值 | 状态 | 判断 |
| --- | --- | --- |
| `0` | `PENDING` | 待 publisher 扫描或重试时间尚未到达 |
| `1` | `LOCKED` | 已被 publisher 抢占 |
| `2` | `SENT` | Kafka producer 已确认发送，不代表 worker 已执行成功 |
| `3` | `DEAD` | 发布重试耗尽或不可恢复失败，读取 `last_error` |
| `4` | `CANCELED` | 业务取消，命令不再发送 |
| `5` | `DISPATCHING` | 发送权已提交，等待 Kafka 结果 |
| `6` | `CANCEL_REQUESTED` | 业务已结束，当前发送只允许收口 |

## 卡点判断表

| 已确认事实 | 初步定位 | 下一步证据 |
| --- | --- | --- |
| 没有生成当前阶段应有的业务事实 | 后端调度或阶段状态机 | `next_run_at`、租约、调度日志 |
| 业务事实为待执行且没有 `commandId` | 事务编排或 Outbox 入库前 | 业务事实更新时间、Armada 异常日志 |
| Outbox 为 `PENDING` | 尚未到重试时间或 publisher 未推进 | `next_retry_at`、`retry_count`、publisher 日志 |
| Outbox 为 `LOCKED` 或 `DISPATCHING` | Armada 发布链路处理中 | `locked_at`、`locked_by`、Kafka producer 日志 |
| Outbox 为 `DEAD` | Kafka 发布失败 | `last_error` |
| Outbox 为 `SENT`，业务事实仍为已提交 | 协议 worker 执行或结果回传 | 相同 `commandId` 的协议日志 |
| 协议日志已有结果，业务事实未变化 | Armada 回调消费、关联或落库 | 回调消费日志、`commandId` 关联结果 |
| 业务事实为 `UNKNOWN` | 结果无法确认 | 未知结果核对调度与实时状态查询 |
| `next_run_at` 还没到 | 正常排期 | 等到排期时间后复查 |
| `next_run_at` 已到，租约为空或过期 | 应具备调度资格 | 调度器是否运行、领取 SQL 是否命中 |
| `lock_owner` 非空但 `lock_expires_at` 为空 | 租约数据不一致 | 写入租约的事务和相关日志 |
| 执行行为 `WAIT_RESOURCE` | 资源等待 | `wait_resource_type`、原因码和账号可用性 |
| 角色账号在群状态为 `JOINING` 或 `UNKNOWN` | 进群结果未收口 | `membership_reason_code`、对应动作的 `commandId` |
| 业务事实有 `commandId`，Outbox 查不到该行 | 命令链路断在入库 | 业务事实更新时间、Armada 事务异常日志 |
| 父任务已终态但拉手 `released_at` 为空 | 占用泄漏 | `PullTaskClosingTransactionService`、资源释放日志 |
| 父任务已终态但执行行仍非终态 | 收口未完成，群链接仍被占用 | 执行行状态与父任务收口日志 |
| 子执行行全部终态，父任务仍在运行态 | 收口或父任务聚合 | `PullTaskParentCompletionService` 日志和状态更新 |

异常摘要 SQL 输出的是“排查候选”，不是自动故障结论。

## 先排除的正常现象

下面这些情况看起来像卡住，实际是设计行为。判断前先按这一节排除，再进入命令链路。

阈值全部来自 `PullTaskExecutionDispatchProperties` 默认值。环境改过配置时，本节和 SQL 参数块要同步调整。

| 现象 | 为什么正常 | 判据 |
| --- | --- | --- |
| 到期但没有持锁 | 调度线程固定延迟 1 秒一轮，租约 30 秒，两轮之间必然存在到期未持锁的瞬间 | 停留小于 60 秒不算异常，异常摘要已按此过滤 |
| 已提交但没有结果 | 未知结果收敛的保护期是 60 秒，扫描间隔 30 秒，90 秒内属正常 | 超过 180 秒仍是已提交才算 `RECONCILIATION_OVERDUE` |
| 只有 N 条执行行在跑 | `concurrent_group_count` 冻结了同任务并发上限 | 结果 2 的配置值 |
| 拉手长时间不发起新调用 | `pull_interval_seconds` 是同一拉手连续调用的最小间隔 | 结果 7 的 `last_submitted_age_seconds` 与配置值比较 |
| 阶段 6 迟迟不提权 | `material_admin_timing=2` 表示等本群料子全部终态后再提权 | 结果 2 的配置值与结果 8a 的料子聚合 |
| 料子 `admin_status=1` 大量存在 | 阶段 5 尚未提权，待执行是正常起点 | 结果 8b 已排除该状态 |
| `LINK_PROBE_INCOMPLETE` | 链接探测暂不可用，会按 30 秒重试延迟自动复查 | `next_run_at` 是否在未来 |
| 联系人动作 `FAILED` | 保存联系人失败通常不阻断邀请和拉人 | 阶段是否仍在推进 |

反过来，这几个不属于正常现象，看到就要继续查：`UNCLAIMABLE_STATE_COMBO`、`LEASE_INCONSISTENT`、`COMMAND_WITHOUT_OUTBOX`、`PULLER_OCCUPANCY_LEAK`、`LINK_OCCUPANCY_LEAK`。

## SQL 查询使用方法

在已确认的目标测试数据库会话中先设置任务 ID：

```sql
SET @task_id := 123;
```

然后执行 `docs/operations/pull-task-normal-link-diagnosis.sql`。文件开头的参数块必须整块执行：它统一时间基准 `@now`，保证各组结果可以互相比较，并集中定义判定阈值。

需要只看某一条执行行时，把参数块里的 `@execution_id` 改成具体 `executionId`，结果 3 之后的所有查询都会收窄。大任务必须这样做，否则料子明细会返回上万行。

按下面顺序阅读：

| 结果 | 内容 | 读法 |
| --- | --- | --- |
| 0 | 参数与时间基准回显 | 抄进排查记录，后面所有"距今多少秒"都以此为准 |
| 1 | 父任务概况 | 无结果时停止，先确认任务 ID、软删状态或业务模式 |
| 2 | 冻结执行配置 | 先看这一组，它决定后面哪些等待属于正常 |
| 3 | 群执行行概况、排期和租约 | 选择页面异常对应的 `executionId`；看 `schedule_state`、`lease_state`、`idle_seconds` |
| 4 | 角色账号 | 管理、拉手、站台的在群、权限、可用性和占用 |
| 5 | 账号动作及 Outbox | 联系人、邀请和踩链接；用 `actor_role_type`/`target_role_type` 区分阶段 3 和阶段 5 的联系人 |
| 6 | 拉人调用及 Outbox | 每次真实批量拉人 |
| 7 | 拉手拉人间隔 | 判断拉手是冷却中还是卡住 |
| 8a / 8b | 料子结果 | 先看聚合，再看处理中或异常明细 |
| 9 | 异常摘要 | 把候选异常与前面各组事实交叉确认 |

先排除未到排期、人工暂停、并发上限、拉手冷却和资源等待，再进入命令链路。只有拿到 `commandId` 后，才继续查询 Outbox 和协议日志。

### 异常摘要候选类别

| 类别 | 含义 |
| --- | --- |
| `DUE_EXECUTION_STALLED` | 到期、未持锁且超过宽限期没有更新 |
| `UNCLAIMABLE_STATE_COMBO` | 状态与阶段的组合不在调度可领取集合内 |
| `LEASE_INCONSISTENT` | `lock_owner` 非空但 `lock_expires_at` 为空 |
| `WAIT_RESOURCE` | 资源等待；诊断串里区分 `STALLED` 和 `RETRYING` |
| `PENDING_ACTION_WITHOUT_COMMAND` | 动作行超过宽限期仍没有 `commandId` |
| `PENDING_CALL_WITHOUT_COMMAND` | 拉人调用超过宽限期仍没有 `commandId` |
| `COMMAND_WITHOUT_OUTBOX` | 有 `commandId` 但 Outbox 查不到该行 |
| `OUTBOX_DEAD` | 发布重试耗尽或不可恢复失败 |
| `OUTBOX_STUCK` | 长时间停在 `LOCKED`、`DISPATCHING` 或 `CANCEL_REQUESTED` |
| `OUTBOX_PENDING_OVERDUE` | 仍是 `PENDING`，重试时间已到却没被推进 |
| `RECONCILIATION_OVERDUE` | 已提交超过保护期仍未转 `UNKNOWN`，说明收敛调度没跑 |
| `UNKNOWN_RESULT` | 动作、拉人调用、料子入群或料子提权结果无法确认 |
| `ROLE_MEMBERSHIP_OPEN` | 角色账号在群状态停在 `JOINING` 或 `UNKNOWN` |
| `ROLE_ADMIN_OPEN` | 角色账号群管理员权限停在已提交或结果未知 |
| `PULLER_OCCUPANCY_LEAK` | 父任务已终态但拉手占用未释放 |
| `LINK_OCCUPANCY_LEAK` | 父任务已终态但执行行仍非终态，群链接仍被占用 |
| `TERMINAL_CHILD_NON_TERMINAL_PARENT` | 子执行行全部终态但父任务仍在运行态 |

`ROLE_MEMBERSHIP_OPEN` 的诊断串会标注是否在收敛范围内。站台由拉人调用兜底收敛；管理和拉手（`pull_call_id` 为空）标为 `NOT_COVERED_BY_RECONCILIATION`，没有任何机制会自动推进它们，长时间停留一定要查。

`RECONCILIATION_OVERDUE` 大批量出现时，先确认是不是候选执行行数超过单轮扫描上限（`resultReconciliationBatchSize` 默认 100），而不是调度线程死了。调度和收敛跑在同一条单线程上，任一环节阻塞会让两者一起停。

## 日志检索原则

连接远程环境前必须先确认目标环境。日志检索保持只读，并遵循以下顺序：

1. 先限定测试时间前后约 5 分钟，避免扫描无关历史日志。
2. Armada 先搜 `taskId` 和 `executionId`，确认阶段是否生成业务事实。
3. 业务事实已经有 `commandId` 时，Armada 和协议层都使用同一个 `commandId` 搜索。
4. Outbox `SENT` 但协议层没有相同 `commandId` 时，再检查 Kafka topic、账号所属 backend 和 worker 路由。
5. 协议层已有明确结果时，回到 Armada 搜结果事件消费、关联和状态更新。

需要保留的日志证据只包含时间、动作名、`taskId`、`executionId`、`commandId`、脱敏原因码和必要的账号内部 ID。不要复制完整命令载荷。

## 故障归类

最终结论归入以下一类：

1. 创建链路：草稿、链接解析、TXT 解析、匹配或提交冻结。
2. 生命周期：启动、暂停、恢复、结束或父任务状态。
3. 后端调度：候选未领取、租约未回收或阶段未推进。
4. 资源分配：管理、拉手、站台不足或账号不可用。
5. 业务编排：动作或拉人调用没有正确生成。
6. Outbox/Kafka：命令待发送、发送中、取消或死信。
7. 协议路由：命令没有到达账号所属 worker。
8. 协议执行：worker 收到命令，但 WhatsApp 操作失败或结果无法确认。
9. 回调处理：协议已有结果，Armada 未关联或落库。
10. 结果核对：未知结果没有按预期收敛。
11. 执行收口：单群终态、资源释放或父任务汇总异常。
12. 占用泄漏：父任务已终态但拉手或群链接仍被占用，影响后续任务。

## 标准排查回复

```text
结论：确认卡在什么阶段、哪一层。

证据：列出数据库事实、commandId、Outbox 状态和相关日志。

影响：说明是单个群执行行、整个任务还是某个账号。

建议：指出应检查或修改的模块，不直接执行修复。

确定性：已确认 / 高概率 / 信息不足。
```

“已确认”必须有数据库或日志直接证据；只有状态组合推断时使用“高概率”；缺少环境、时间或任务 ID 时使用“信息不足”。

## 代码事实锚点

- `PullTaskStandardStatus`：父任务状态。
- `PullTaskExecutionStatus`：群执行行状态。
- `PullTaskExecutionStage`：七阶段顺序。
- `PullTaskExecutionReasonCode`：执行行原因码全集。
- `PullTaskActionStatus`、`PullTaskPullCallStatus`：动作和拉人调用状态。
- `PullTaskMaterialPullStatus`、`PullTaskMaterialAdminStatus`：料子状态。
- `PullTaskGroupAccountMembershipStatus`、`PullTaskGroupAccountAdminStatus`、`PullTaskGroupAccountAvailability`：角色账号状态。
- `ProtocolCommandOutboxStatus`：命令传输状态。
- `PullTaskExecutionDispatchProperties`：轮询间隔、租约时长、重试延迟、未知结果保护期与扫描间隔；本手册所有时间阈值的唯一来源。
- `pull_task_standard_setting`（V090）：启动时冻结的并发、间隔、人数和提权时点。
- `PullTaskExecutionDispatchCoordinator`：调度可领取的状态与阶段组合。
- `PullTaskPullExecutionProcessor`：阶段 5 内部的调用计划、拉手站台联系人和批量拉人顺序。
- `PullTaskUnknownResultReconciliationService`：哪些事实会被自动收敛，以及按 `submitted_at` 还是 `updated_at` 判定超时。
- `PullTaskGroupExecutionMapper.xml`：调度资格、排期和租约条件。
- `ProtocolCommandOutboxMapper.xml`：Outbox 抢占、发布、重试和死信流转。

代码调整后应先更新状态映射和 SQL，再使用本手册判断新任务。改动 `PullTaskExecutionDispatchProperties` 的默认值时，必须同步“先排除的正常现象”一节和 SQL 参数块里的阈值，否则异常摘要会开始误报或漏报。

## 验证边界

在没有连接用户确认的测试数据库前，只能完成源码、Mapper、Flyway 和 SQL 的静态核对，不能声称查询已经在真实 MySQL 数据上执行通过。首次实际排查时，应在确认的测试环境执行只读 SQL，并根据真实输出修正文档或查询错误。

阈值类判定还需要一次真实标定：`@stall_grace_ms`、`@reconcile_overdue_ms` 和 `@outbox_stuck_ms` 目前按配置默认值推导，实际环境的数据库延迟和负载可能需要放宽。首次排查时先在健康任务上跑一遍异常摘要，确认它对正常任务输出为空或只有可解释的 `WAIT_RESOURCE`，再用它判断故障任务。
