# 账号动态营销新群即时发送设计

> 状态：已确认
> 日期：2026-07-20
> 范围：`armada/armada-api`
> 协议：Web、Android Zhuan 复用现有群快照上报和消息发送链路，无协议层改动

## 1. 目标与确认口径

营销任务处于发送中时，账号新加入群组后，不等待任务下一轮时间，先由该账号向该群发送一次当前任务内容。首次即时发送完成后，不建立独立周期；该群在后续轮次继续由现有 `ACCOUNT_DYNAMIC` 目标解析逻辑读取并发送。

本次确认口径：

- 只适用于 `ACCOUNT_DYNAMIC`（按账号动态读取当前群）目标，`GROUP_FIXED` 不处理。
- 幂等维度为“租户 + 营销任务 + 账号 + 群 JID”。同一群若被多个已选账号加入，每个账号都分别触发一次。
- 同一任务、账号、群只允许一次首次即时触发；重复群回报、服务重启、退群后重进均不再次触发。
- 即时发送不修改任务的 `current_round_no`、`next_round_at`、`last_round_started_at` 和发送间隔。
- 即时发送后即使很快到达正常下一轮，也允许该群继续随正常轮次发送。
- 多个新群沿用任务的 `account_group_send_interval_ms` 依次排期，不同时无间隔下发。
- 消息内容继续使用任务当前模板和现有组装规则，不增加内容策略配置。
- 任务开启自动重试时，首次即时发送的协议失败最多允许一次业务重试；这仍属于同一次即时触发。

## 2. 当前事实

Web 和 Android Zhuan 都会在账号上线或群关系变化后拉取账号当前完整群列表，并统一发布 `account.groups_reported`。Armada 当前由 `AccountGroupMembershipReportServiceImpl` 消费事件，再通过 `AccountGroupMembershipSnapshotServiceImpl` 刷新 `account_group_membership`。

普通营销每个正常轮次由 `MarketingRoundWorker` 解析目标。`ACCOUNT_DYNAMIC` 会读取账号当前活跃 membership，因此新群已经可以在下一正常轮次自然加入任务；当前缺口只是没有在 membership 首次出现时即时生成一次发送。

普通营销消息通过 `MessageSendPort` 写入 `protocol_command_outbox`。事务提交后，`ProtocolCommandDispatchTrigger` 直接异步发送本次插入的 outbox 行；默认十秒扫描仅用于漏触发、服务重启、锁恢复和 Kafka 失败重试兜底。

## 3. 方案比较与选择

### 3.1 定时扫描新 membership

周期扫描全部发送中动态任务，再按 `joined_at` 查新增群。实现直观，但会持续扫描任务和群关系，还需要维护扫描游标，重复判断与并发幂等都更复杂，不采用。

### 3.2 新增即时发送任务表

为新群建立独立待执行队列表，由专用 worker 扫描。隔离性强，但增加表、状态机、扫描器和恢复逻辑；现有 attempt、outbox 已能承担这些职责，本期不采用。

### 3.3 群快照差量触发并复用 attempt/outbox

在现有 `account.groups_reported` 事务内计算新增群，只对真新增群查询当前账号的发送中动态任务，抢占首次即时 attempt 并写入现有 outbox。该方案没有全局轮询、没有每账号定时器、没有新执行表，选为本期方案。

## 4. 总体流程

```text
Web / Zhuan 群变化
  -> account.groups_reported（当前完整群列表）
  -> 查询账号同步前的活跃 membership JID
  -> 用本次完整群列表刷新 membership
  -> 计算 addedGroups = reportedGroups - previousActiveGroups
  -> 查询该账号当前拥有使用权的发送中 ACCOUNT_DYNAMIC 任务
  -> 以 round_no=0、attempt_no=1 抢占首次即时 attempt
  -> 仅为抢占成功的 attempt 写 message.send.requested outbox
  -> 事务提交
  -> afterCommit dispatcher 按 notBeforeAt 推送 Kafka
  -> Web / Zhuan 按账号协议后端发送
```

正常营销调度器继续按原 `next_round_at` 生成 `round_no >= 1` 的轮次，不感知即时发送。

## 5. 新增群差量

### 5.1 差量计算

`AccountGroupMembershipSnapshotService` 在刷新前一次性查询该账号当前活跃群 JID，归一化本次协议全量群列表后在内存中求差集。额外成本是一条账号范围查询和一个有界集合差运算，不按营销任务或全库扫描。

快照写入结果调整为变化集，至少包含：

- `currentGroups`：本次刷新后仍活跃的群快照。
- `addedGroups`：本次回报存在、刷新前不存在的群快照。

数据库 upsert 的影响行数不用于判断新增，因为 MySQL 驱动的 affected rows 语义可能受配置和更新内容影响；显式前置集合更稳定。

### 5.2 首次 baseline 保护

如果账号在本次事件开始时仍是 `BASELINE_PENDING`，本次完整群列表用于首次 baseline 和 membership 建立，但 `addedGroups` 不触发即时营销，避免把账号上控前已有群批量当成新群。

只有 baseline 已建立或明确不启用 baseline 过滤的账号，后续完整群列表相对现有活跃 membership 的新增差量才进入即时发送。

### 5.3 重复与乱序回报

群差量是降低无效处理的第一层；数据库唯一键是最终幂等边界。即使重复事件、旧快照导致关系先软删后恢复，或者多实例并发计算出同一个新增群，也只能抢占一次首次即时 attempt。

## 6. 任务匹配

对存在 `addedGroups` 的账号，只执行一次账号范围查询，匹配：

- 任务未删除且状态为 `SENDING`。
- 当前时间位于任务开始、结束窗口内。
- `marketing_task_target.target_scope = ACCOUNT_DYNAMIC`。
- target 的 `account_id` 等于群回报账号。
- 账号当前营销占用关系仍归该任务所有。

查询使用现有账号目标索引和任务主键关联。正常情况下一个账号只会有一个实际占用中的普通营销任务；历史异常数据出现多个发送中任务时，以账号占用事实为准，不向非占用任务即时发送。

## 7. 幂等与 attempt 语义

### 7.1 保留轮次

约定：

- `round_no = 0`：新群首次即时发送保留轮次，不递增任务主表轮次。
- `round_no >= 1`：现有正常营销轮次。

`ACCOUNT_DYNAMIC` 在同一任务、账号下只有一条 target，因此首次 attempt 的幂等键为：

```text
tenant_id + target_id + round_no(0) + group_jid
```

它等价于“租户 + 任务 + 账号 + 群 JID”。重复检测永远只尝试创建 `attempt_no=1`，现有 `uq_marketing_task_attempt_group_round` 唯一键冲突即跳过，不再次写 outbox。

### 7.2 一次业务重试

现有唯一键保持不变，一次即时触发始终只占用一条 attempt。发送结果处理器确认任务开启自动重试、当前为即时 `attempt_no=1`、任务仍发送中且账号仍在群时，原子更新同一行：

- `attempt_no` 从 1 更新为 2。
- `is_retry` 更新为 1。
- 换用新的 `command_id`，清理上一次结果字段。
- 在同一事务写入新的 outbox 命令。

重试结果再次失败后终结该 attempt，不再重试。第一次协议失败属于同一逻辑发送的中间状态，只记录有界日志和 target 重试次数；任务最终成功/失败计数在重试完成或不再重试时累计。

结果回写 SQL 同时校验 `attempt_id + command_id + SUBMITTED`。重试提交后迟到的第一次命令结果因 `command_id` 已变化而被幂等跳过，不能覆盖重试结果。

Kafka 发布失败由现有 outbox retry 处理，不创建新的营销 attempt；协议已经执行并回报失败才属于上述业务重试。

## 8. 消息生成与 outbox

即时发送复用普通营销现有能力：

- `MarketingMessageComposer` 读取并组装任务模板、图片、链接卡片或按钮消息。
- 同一任务在一次群回报中只组装一次消息，多个新增群复用组装结果，避免重复读取模板和媒体。
- `ProtocolAccountRef` 和 `MessageSendPort` 按账号的 `ProtocolBackend` 自动路由到 Web 或 Android Zhuan。
- correlation 继续使用 `source=marketing_task`，携带任务、target、attempt 和 `roundNo=0`。
- `MarketingSendResultServiceImpl` 继续回写 attempt、target、任务计数与成功群事实。

应抽取普通轮次与即时发送共同需要的“单目标消息命令构造”能力，避免复制协议 payload 和模板组装规则；不改 Kafka 契约。

### 8.1 多新群排期

同一账号本次新增群按协议回报稳定顺序处理，第 `n` 个成功抢占的即时 attempt 使用：

```text
notBeforeAt = immediateStartedAt + n * accountGroupSendIntervalMs
```

`n` 从 0 开始。写入 outbox 后，已到期行在事务提交后立即 dispatch；未来行由现有 `TaskScheduler` 到点 dispatch，失败或重启由现有低频扫描兜底。

### 8.2 事务边界

membership 刷新、首次 attempt 抢占和 outbox 插入处于同一数据库事务：

- 事务回滚时不发布 Kafka，也不留下已占用但未入队的 attempt。
- 事务提交后才由现有 afterCommit trigger 推送 Kafka。
- 协议发送失败发生在事务外，只回写对应 attempt，不反向回滚群快照。

## 9. 正常轮次隔离

即时发送路径禁止更新：

- `marketing_task.current_round_no`
- `marketing_task.next_round_at`
- `marketing_task.last_round_started_at`
- `marketing_task.started_at`
- 其他账号和群组的正常轮次状态

下一个正常轮次仍由 `MarketingRoundWorker.selectDynamicTargetGroups` 从活跃 membership 读取该新群，并生成下一正常轮次 attempt。即时 `round_no=0` 与正常 `round_no>=1` 的唯一键互不冲突。

即时发送和正常轮次在时间边界并发时，允许分别生成各自 attempt；这是已确认的“即时后仍直接跟随整体下一轮”语义。

## 10. 异常隔离

- 群已退出、解散或账号已不在群：即时 worker/重试前再次确认活跃 membership；无效时记录跳过或失败，不发送。
- 账号离线、异常、无发言权限、群封禁、协议发送失败：沿用现有协议结果码和 attempt 回写。
- 模板配置错误或本地入队拒绝：只终结对应任务/群 attempt，其他新群继续处理。
- 单条协议命令失败：只影响该 attempt；同批其他 outbox 命令独立发送和回写。
- 数据库或基础设施级事务失败：整次群回报回滚并由 Kafka 消费重试；唯一键保证重放安全。
- 任务在即时 attempt 提交后暂停或关闭：已进入 outbox 的命令沿用现有“已提交命令继续完成”语义，暂停或关闭只阻止后续新生成。

## 11. 数据、接口与跨仓影响

### 11.1 数据库

通过 Flyway：

- 更新 `round_no` 注释，明确 `0=新群首次即时发送，1+=正常轮次`。
- 不调整现有 attempt 唯一键，不新增业务表，不修改 membership 表，不增加扫描游标。

### 11.2 API 与前端

本期不增加创建配置和 API 字段，前端无需调整。任务详情继续复用现有 attempt 聚合；`round_no=0` 是内部保留值。

### 11.3 协议层

Web 和 Android Zhuan 都继续发布现有 `account.groups_reported`，并消费现有 `message.send.requested`。不新增事件、不修改 payload、不要求协议层理解营销任务。

## 12. 可观测性

新增有界日志字段：

- `eventId/source/tenantId/accountId`
- 本次回报群数、旧活跃群数、新增群数
- 匹配动态任务数、首次抢占数、重复跳过数、outbox 接受/拒绝数
- `taskId/targetId/groupJid/attemptId/attemptNo/roundNo`

群 JID 样本保持有界，不打印模板正文、媒体内容、凭据或代理信息。

## 13. 测试与验收

### 13.1 单元测试

- 完整群快照正确计算新增、保持和退出集合。
- 首次 baseline 不触发即时发送。
- `GROUP_FIXED`、非发送中、未开始、已结束和非占用任务不触发。
- 多个新增群按账号间隔生成 `notBeforeAt`。
- 即时路径不调用任务轮次 claim，不修改任何正常轮次时间。
- Web/Android 账号都经现有 routing port 生成正确 backend 命令。

### 13.2 真库 DbTest

- 重复群事件只能插入一条 `round_no=0, attempt_no=1`。
- 多实例并发抢占同一任务、账号、群时唯一键只允许一个成功。
- 同一群由两个已选账号加入时，各自 target 都能生成一次即时发送。
- 即时 attempt 后，下一正常轮次仍能为该群插入 `round_no>=1`。
- 自动重试关闭时失败直接终结；开启时同一 attempt 最多更新到 `attempt_no=2`，总行数保持一条。
- 重试换 commandId 后，第一次命令的迟到结果不能覆盖重试状态。
- attempt 与 outbox 同事务提交或回滚，不出现孤儿 attempt。

### 13.3 端到端验收

- Web 与 Zhuan 各验证一个账号运行 `ACCOUNT_DYNAMIC` 任务时加入新群。
- 新群无需等待完整营销间隔即可收到一次当前任务消息。
- 多新群按现有单账号群间隔依次发送。
- 新群后续跟随任务统一下一轮，原群时间与内容不受影响。
- 重复群上报、页面刷新和服务重启不重复首次发送。
- 单群异常不阻塞同批其他新群和正常轮次。

## 14. 发布与回滚

直接部署包含 `round_no` 注释更新和即时发送逻辑的 Armada；Web 和 Zhuan 不要求同步部署。

应用回滚后不会再生成 `round_no=0` attempt，历史即时 attempt 和 outbox 审计数据可保留。现有唯一键未改变，无需为了应用回滚删除历史数据或回退索引。
