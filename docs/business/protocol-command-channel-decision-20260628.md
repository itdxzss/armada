# 协议命令与事件通道分流决策

> 日期：2026-06-28
> 范围：armada 重构协议交互时，账号生命周期、群操作、消息发送、协议事件的通道选择。
> 结论状态：设计决策记录，用于后续实现 `GroupPort`、`MessagePort`、Kafka event consumer、任务 worker。

## 1. 结论

armada 协议层交互按“命令下发”和“结果/状态回流”分开设计：

- **命令下发**：业务主动要求协议层做一件事，例如上线、进群、拉人、发消息。
- **事件回流**：协议层异步告知业务世界发生了什么，例如账号上线成功、群健康变化、成员身份变化、消息送达回执。

首期推荐：

| 能力 | 推荐通道 | 说明 |
|---|---|---|
| 账号 `online/offline/logout` | Kafka 可行，HTTP 保留回滚 | 长耗时，终态本来靠 `account.state_changed` 等事件回填。 |
| 进群 `groups/join` | HTTP | 结果要立刻落任务行，例如成功、pending、链接失效、群满、账号不可用。 |
| 拉人/踢人/升降管理 | HTTP | 需要 per-participant 结果，直接驱动成功数、失败数、补号、重试。 |
| 群 `metadata/listParticipating` | HTTP | 查询型能力，HTTP 最直接，调用方需要立即拿快照。 |
| 发消息四接口 | HTTP | 先拿 send 结果；后续 delivery/read 可用 Kafka ack 异步更新。 |
| `group.health_reported` | Kafka event | 群健康异步修正，不是命令入口。 |
| `group.participant_changed` | Kafka event | 管理员/成员变化异步回填，例如 promote 后确认 `is_admin`。 |
| `message.ack` | 可选 Kafka event | 二期做送达/已读回执再接，首期不依赖它判断“已提交发送”。 |

一句话：**上下线可以走 Kafka 命令；进群、群成员操作、发消息首期仍走 HTTP 命令；Kafka 主要负责终态和事件回流。**

## 2. 决策背景

`account-online-kafka-slices` 分支把账号上线/下线改成了 outbox + Kafka 命令模型：

1. Armada 先在本地事务写 `protocol_command_outbox`。
2. 事务提交后 dispatcher 抢占 outbox 行。
3. publisher 把命令发到 Kafka。
4. 协议层消费命令并执行上线/下线。
5. 协议层再通过 Kafka 事件把最终账号状态回填 Armada。

这个模型对账号生命周期成立，因为账号上线/下线本身就是异步终态：

- HTTP accepted 或 Kafka producer ack 都不能说明账号已经 ONLINE/OFFLINE。
- 真正终态要等协议层连接 WhatsApp 后通过事件回填。
- 批量上线还涉及握手 CPU、代理分配、协议层节流，适合把提交和执行解耦。

但不能把这个结论直接外推到所有协议命令。进群、拉人、发消息的核心问题不是“怎么把命令投出去”，而是“每一行业务结果如何快速、准确、可重试地落库”。

## 3. 为什么账号生命周期可以 Kafka 化

账号 `online/offline/logout` 有几个特点：

1. **终态天然异步**：上线命令被受理后，还要等连接建立、登录态恢复、WhatsApp socket 状态稳定。
2. **批量压力集中在协议层**：握手 CPU、worker event loop、代理链路由协议层最清楚，节奏应该靠协议层 gate 控制。
3. **重复命令副作用相对可控**：同账号重复 online/offline 可以通过 `commandId`、账号级幂等、当前状态判断来抑制。
4. **用户可接受 accepted 语义**：账号页可以展示“上线中/下线中”，等事件回填最终状态。
5. **失败处理可以事件化**：凭据缺失、代理不可用、登录失效、协议异常都可以通过账号事件驱动状态和代理释放。

因此，账号生命周期可以采用：

- Kafka command：`account.online.requested`、`account.offline.requested`、后续可扩展 `account.logout.requested`。
- Kafka event：`account.state_changed`、`account.logout`、`account.need_reauth`、`account.restricted`、`account.heartbeat` 等。
- HTTP fallback：保留 `command-mode=http|kafka`，Kafka 模式不稳定时可以回滚。

## 4. 为什么进群和群成员操作首期走 HTTP

进群、拉人、踢人、升降管理员不是单纯“提交一条命令”就结束，它们会直接驱动任务明细行状态。

### 4.1 进群 `groups/join`

进群结果至少会影响：

- `join_task_result.status`
- `join_task_result.reason`
- `join_task_result.group_jid`
- 任务的 `executed/success/failed/pending`
- 是否需要重试、换号、换链接、等待审批

HTTP 返回可以在当前 worker 执行链路里直接分类：

- 成功加入：回写 `SUCCESS` 和 `groupJid`。
- 进入 pending：回写 pending/待审批语义，后续审批流程继续处理。
- 链接无效：标记链接失败，避免重复打同一个坏链接。
- 群满/群封/群不可加入：标记对应失败原因。
- 账号离线/限流/协议忙：按可恢复错误进入退避或重试。

如果进群命令走 Kafka，则 worker 只能把任务行标成 `SUBMITTED`，然后等待协议层再发一个 `join.command_succeeded/failed` 事件。这样会额外引入：

- 命令结果 topic。
- 命令超时 sweeper。
- 任务行 pending 状态机。
- 重复事件幂等。
- 取消任务时如何处理已投递但未执行的命令。
- 命令积压后是否还允许执行过期进群。

首期没有必要承担这套复杂度。

### 4.2 拉人/踢人/升降管理员

成员操作通常是批量参与者结果，不是单个布尔值。拉人尤其需要 per-participant 结果：

- 某些手机号成功。
- 某些手机号隐私限制。
- 某些已在群内。
- 某些不存在或不可拉。
- 群满、账号无权限、管理员身份丢失。
- 协议层限流或 WhatsApp 临时失败。

这些结果要直接决定：

- 本轮成功数。
- 本轮失败原因。
- 是否补号。
- 是否重试。
- 是否触发管理员补位。
- 是否暂停任务。

HTTP 更适合作为“执行一批，立即返回这一批结果”的命令接口。Kafka event 适合做后续事实校正，例如 `group.participant_changed` 确认某账号真的被 promote。

## 5. 为什么发消息首期走 HTTP

发消息是强副作用动作，重复执行会造成真实重复消息。这里最需要谨慎。

HTTP 命令的好处：

1. worker 拿到一个发送目标。
2. 通过 per-account 发送限速。
3. 调协议层 `sendText/sendImage/sendLinkCard/sendButtonCard`。
4. 立即拿到提交结果或失败原因。
5. 落 `marketing_task_target` / `marketing_task_send_attempt`。

Kafka 命令的风险：

- producer ack 只代表进 Kafka，不代表消息发给 WhatsApp。
- Kafka retry 如果没有协议侧幂等，会重复发送真实消息。
- backlog 积压后可能发送过期营销内容。
- 任务停止/删除后，已在 Kafka 里的消息如何取消很复杂。
- 如果只靠异步 ack，页面会长期看到“发送中”，运营难判断真实进度。

因此首期建议：

- 发送命令：HTTP。
- 发送尝试落库：由 Armada worker 根据 HTTP 结果直接写。
- delivery/read：二期如果协议层能稳定发布 `message.ack`，再消费 Kafka 更新送达/已读等二级状态。

注意：HTTP 命令不等于前端请求同步等待。营销任务仍然应该是后台 worker 异步执行，只是 worker 到协议层这一跳用 HTTP。

## 6. Kafka event 的定位

Kafka 在 group/message 方向仍然有价值，但它应该承载“事实回流”，不是首期承载“命令入口”。

### 6.1 群健康事件

`group.health_reported` 用来异步修正群健康：

- 当前人数。
- 群是否异常。
- 群链接是否仍可用。
- 巡检错误码。

它不应该替代 `metadata` 查询。`metadata` 是调用方当场要快照，`group.health_reported` 是后台事实收敛。

### 6.2 群成员变化事件

`group.participant_changed` 用来异步确认成员事实：

- promote 后确认是否已是管理员。
- remove/leave 后确认成员变化。
- 外部变化导致本地状态修正。

它可以补偿 HTTP 命令之后的事实漂移，但首期不要让进群/拉人任务依赖它作为唯一命令结果。

### 6.3 消息 ack 事件

`message.ack` 适合二期做：

- server accepted。
- delivered。
- read。
- failed after submit。

首期不要把它作为“发送命令成功”的唯一依据。首期只需要知道 HTTP send 是否成功提交，后续 ack 是增强状态。

## 7. 推荐的 Armada 内部执行模型

外部看，进群任务和营销任务仍然是异步任务；内部到协议层的命令调用保持 HTTP。

推荐链路：

```text
用户启动任务
  -> Armada 写任务状态 RUNNING
  -> 后台 worker 扫描任务行
  -> 按账号/动作限速
  -> HTTP 调协议层 GroupPort/MessagePort
  -> 根据 HTTP 结果回写任务行
  -> Kafka event 做异步修正/补偿
```

这个设计的关键点：

- 任务异步由 Armada 自己的 DB 状态机负责。
- 协议命令结果由 HTTP 直接驱动当前任务行。
- Kafka event 不阻塞主执行链路。
- 后续如果某类命令确实需要 Kafka 化，可以在 port 层替换 adapter，而不污染业务域。

## 8. 如果未来要把 group/message 命令 Kafka 化，必须补齐什么

不排除未来某些高吞吐场景需要 Kafka command，但需要先满足下面条件。

### 8.1 命令状态机

不能只有 outbox `SENT`，还要有业务命令状态：

- `PENDING`
- `SUBMITTED`
- `ACCEPTED_BY_PROTOCOL`
- `SUCCEEDED`
- `FAILED`
- `TIMED_OUT`
- `CANCELED`

`SENT` 只代表发到 Kafka 成功，不代表协议动作完成。

### 8.2 端到端幂等

每条真实副作用命令必须有业务幂等键：

- 进群：`join_task_result_id` 或 `join_command_id`。
- 拉人：`dispatch_row_id + participant_jid`。
- 发消息：`marketing_send_attempt_id`。

协议层必须持久化或缓存命令处理记录。重复消费同一命令时，只能返回旧结果，不能重复执行 WhatsApp 动作。

### 8.3 结果事件

协议层必须发布明确的命令结果事件：

- `group.join.succeeded/failed`
- `group.participants_add.succeeded/failed`
- `message.send.succeeded/failed`

事件里必须包含：

- `commandId`
- `businessId`
- `accountId/protocolAccountId`
- `groupJid/link`
- 失败 code
- 可重试标记
- 协议层执行时间

### 8.4 超时和取消

必须明确：

- 命令多久没结果算超时。
- 超时后能否重试。
- 任务停止后 Kafka 中已排队命令如何取消。
- 已开始执行的命令是否允许取消。
- 过期营销消息是否直接丢弃。

### 8.5 背压和限速

Kafka 会隐藏压力。必须有：

- 按账号限速。
- 按动作限速。
- 按协议 worker 限速。
- command age 指标。
- consumer lag 指标。
- backlog 超阈值后停止继续入队。

否则 backlog 一旦恢复消费，协议层可能集中执行大量过期命令，尤其容易导致发消息和拉人风控风险。

## 9. 首期落地建议

### 9.1 Port 划分

建议业务域只依赖 port，不直接依赖 HTTP/Kafka：

- `AccountLifecyclePort`
- `GroupCommandPort`
- `MessageCommandPort`
- `ContactCommandPort`
- `RiskQueryPort`

首期 adapter：

- `AccountLifecyclePort`：支持 HTTP 和 Kafka mode，Kafka mode 只用于 online/offline/logout。
- `GroupCommandPort`：HTTP adapter。
- `MessageCommandPort`：HTTP adapter。
- `ContactCommandPort`：HTTP adapter，按 best-effort 处理。
- `RiskQueryPort`：HTTP adapter。

### 9.2 任务侧状态

进群任务：

- worker 逐行处理 `join_task_result`。
- 调 `groups/join` 后立即回写行结果。
- `group.participant_changed` 只补充管理员/成员事实。

营销任务：

- worker 逐目标处理 `marketing_task_target`。
- 每次发送写 `marketing_task_send_attempt`。
- HTTP send 成功即认为“提交发送成功”。
- 二期 `message.ack` 再更新 delivery/read。

### 9.3 限速位置

必须在 Armada 任务 worker 前置 per-account 限速：

- 发消息：按发言账号限速。
- 加联系人：按操作账号限速。
- 进群：按进群账号限速。
- 拉人/审批/升降管理员：按执行账号限速。

协议层仍可保留 worker 级 gate，作为兜底背压。

## 10. 本决策不包含

以下内容不在本文拍板：

- 具体 HTTP DTO 字段。
- Kafka topic 最终命名。
- 协议层 command consumer 实现细节。
- 进群任务 worker 的调度算法。
- 营销任务 worker 的发送节奏参数。
- `message.ack` 是否一期接入。

这些可以在各自实现切片里再单独写计划。

## 11. 最终决策

首期采用以下原则：

1. **账号生命周期**：允许 Kafka command，保留 HTTP fallback。
2. **群操作命令**：HTTP。
3. **消息发送命令**：HTTP。
4. **查询类协议能力**：HTTP。
5. **状态/事实回流**：Kafka event。
6. **任务异步**：由 Armada 自己的任务表、worker、限速器负责，不通过 Kafka 命令强行异步化所有协议动作。

这样可以先跑通业务闭环，降低重复副作用和状态机复杂度，同时为以后按单个高吞吐场景升级 Kafka command 留出 port 层空间。
