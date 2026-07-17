# Android Zhuan 命令 Topic 隔离设计

> 状态：已确认，待实施计划
> 日期：2026-07-17
> 范围：`armada/armada-api`、`whatsapp-server-feature-android-zhuan`

## 1. 背景

Armada 当前把全部 Android Zhuan 下行命令写入同一个 Kafka topic：
`protocol.android.commands.v1`。Zhuan 使用同一个 consumer group 和一组并发 consumer，按
`commandType` 分发以下四类命令：

- `account.online.requested`
- `account.offline.requested`
- `message.send.requested`
- `group.join.requested`

该结构能复用统一 outbox、Kafka reader 和幂等链路，但把低吞吐、高优先级的账号生命周期命令与高吞吐、
可能长时间执行的营销和进群命令放进了同一组分区与消费槽。

2026-07-17 dev-1 排查已观察到实际隔离需求：同一批 19 个 Android 账号的 outbox 在约 2 秒内全部发送到
Kafka，但协议端完成全部 ONLINE 用时约 118 秒；同一窗口内 Zhuan 还处理了 73 条营销消息。服务器负载和
Armada outbox 发送耗时均正常，延迟来自共享 command topic 的消费竞争。

早期营销设计已经为该情况预留拆分方向：第一版复用现有 command topic，若营销消息挤压上线、下线或群同步，
再切换到独立消息 topic。本设计执行该拆分，并同时隔离进群命令。

## 2. 目标

1. 账号上线、下线不再与营销发送、进群命令竞争 Kafka 分区和 consumer。
2. 营销发送积压只影响营销消费池，进群积压只影响进群消费池。
3. 同一账号的上线、下线仍在同一 topic 内按 `protocolAccountId` key 保序。
4. 保留现有 outbox、幂等、结果事件和 Armada 状态收敛语义。
5. 允许三个命令族独立配置 topic、consumer group、分区数和进程内并发数。
6. 使用停机切换，不引入双写、双读、旧 topic 排空或兼容开关。

## 3. 非目标

- 不实现 Kafka 分区内的异步 worker pool、连续 offset 提交器或账号级 keyed executor。
- 不增加等待账号 ONLINE 的 `AccountOnlineGate`。
- 不改变营销任务、进群任务或账号生命周期的业务重试策略。
- 不修改 Zhuan 向 Armada 回传账号、营销和进群结果的 event topic。
- 不迁移或补偿旧 `protocol.android.commands.v1` 中尚未消费的命令。
- 不删除旧 Kafka topic；删除属于后续独立运维动作。
- 不调整 Web/master 命令路由。

## 4. Topic 与命令映射

Armada 向 Android Zhuan 下发命令时使用三个 topic：

| 命令族 | Topic | Command type |
|---|---|---|
| 生命周期 | `protocol.android.lifecycle.commands.v1` | `account.online.requested`、`account.offline.requested` |
| 营销消息 | `protocol.android.message.commands.v1` | `message.send.requested` |
| 进群 | `protocol.android.group-join.commands.v1` | `group.join.requested` |

三个 topic 都使用 `protocolAccountId` 作为 Kafka key。同一 topic 内同一账号继续保序；不同 topic 之间不承诺
总顺序。该跨 topic 非保序是本次隔离的明确取舍。

默认每个 topic 创建 4 个分区。Zhuan 每组 consumer 默认 `concurrency=4`，实际有效并行度不超过 topic
分区数。配置必须允许三个命令族后续独立扩容。

## 5. Armada 路由设计

### 5.1 配置

现有 `armada.protocol.kafka.android-commands.topic` 单值配置改为三个明确字段：

```yaml
armada:
  protocol:
    kafka:
      android-commands:
        lifecycle-topic: ${PROTOCOL_ANDROID_LIFECYCLE_COMMANDS_TOPIC:protocol.android.lifecycle.commands.v1}
        message-topic: ${PROTOCOL_ANDROID_MESSAGE_COMMANDS_TOPIC:protocol.android.message.commands.v1}
        group-join-topic: ${PROTOCOL_ANDROID_GROUP_JOIN_COMMANDS_TOPIC:protocol.android.group-join.commands.v1}
```

`ProtocolAndroidCommandProperties` 继续作为 Android 下行命令配置边界，但提供三个强类型属性。启动时必须校验
三个 topic 非空且互不重复；不允许静默回退旧 topic。

### 5.2 Outbox 路由

- `account.online.requested` 和 `account.offline.requested` 的 Android outbox 行写入
  `lifecycle-topic`。
- `AndroidMessageSendBackend` 产生的 `message.send.requested` outbox 行写入 `message-topic`。
- Android `group.join.requested` outbox 行写入 `group-join-topic`。
- Web 命令仍写入现有 `protocol.master.commands.v1`。
- `group.health_check.requested` 和 `account.groups_sync.requested` 继续保持 Web-only，不纳入 Zhuan 三 topic。

outbox 的事务、状态机、dispatcher、重试和 Kafka envelope 均保持不变，只改变 Android 行的
`kafka_topic` 选择。

## 6. Zhuan 消费设计

### 6.1 配置

Zhuan `[kafka]` 配置把原 `commandtopic`、`consumergroup`、`concurrency` 拆为三组：

```toml
[kafka]
enabled = true

lifecyclecommandtopic = "protocol.android.lifecycle.commands.v1"
lifecycleconsumergroup = "whatsapp-server-feature-android-armada-lifecycle"
lifecycleconcurrency = 4

messagecommandtopic = "protocol.android.message.commands.v1"
messageconsumergroup = "whatsapp-server-feature-android-armada-message"
messageconcurrency = 4

groupjoincommandtopic = "protocol.android.group-join.commands.v1"
groupjoinconsumergroup = "whatsapp-server-feature-android-armada-group-join"
groupjoinconcurrency = 4
```

三个 topic、三个 group ID 必须分别非空且互不重复，并发必须至少为 1。旧单 topic 配置不再读取，避免部署时
误把三组 consumer 重新指向共享通道。

### 6.2 Consumer pool

Zhuan 启动三个独立 consumer pool，共享 Kafka broker、安全配置、Redis 状态存储和事件 publisher，但不共享
source reader 或 consumer group：

- lifecycle pool 只调用生命周期 handler，并只接受上线、下线命令；
- message pool 只调用消息 handler，并只接受营销消息命令；
- group-join pool 只调用进群 handler，并只接受进群命令。

复用统一的 pool 构造与停止编排，pool 参数包含 `commandFamily`、topic、group ID、concurrency 和 handler，避免
复制三套 reader 生命周期代码。启动过程中任一 pool 配置或构造失败时，已经创建的 reader 和 writer 必须全部
关闭，适配器整体启动失败。

收到不属于当前 topic 的 `commandType` 时，记录不含 payload 的永久路由错误并提交 offset，避免错误消息永久
阻塞分区。日志必须包含 `commandFamily`、`commandTopic` 和 `consumerIndex`。

### 6.3 停止语义

停止时先取消共享 adapter context，再关闭三个 pool 的 reader 解除 `FetchMessage` 阻塞，等待 runner 退出，
最后关闭账号、消息和进群事件 writer。停止操作继续保持幂等。

## 7. 业务执行语义

### 7.1 生命周期

上线、下线继续使用现有同步 executor、Redis 上下文、状态事件发布和 Kafka offset 提交规则。同一账号的两类
命令位于 lifecycle topic 且 key 相同，因此保持分区内顺序。

### 7.2 营销消息

不在 Armada 写 outbox 前增加账号在线检查，不在 Zhuan 增加等待 ONLINE、延迟队列或协议级自动重试。

Zhuan 在实际解析当前 WaApp 时沿用现有行为：如果账号实例不可用，发布
`message.send_result_reported` 失败事件，`reasonCode=ACCOUNT_OFFLINE`，随后提交 source command offset。
发送过程中的其它确定性业务失败也沿用当前结果事件语义。

### 7.3 进群

进群保持现有行为。账号不在线时立即回报 `ACCOUNT_NOT_ONLINE`；网络、限流、超时等结果继续携带当前
`retryable` 语义，由 Armada 的进群任务状态机决定后续动作。Zhuan 不等待同账号 lifecycle topic 中可能存在的
上线命令。

## 8. 错误与隔离语义

- lifecycle handler 的临时基础设施错误继续保留当前消息并重试；永久参数错误回报状态后提交。
- message handler 无论发送成功或确定性失败，都必须先发布结果并持久化幂等状态，再提交 source offset。
- group-join handler 保持“先持久化结果、再发布事件、最后提交 source offset”的现有顺序。
- 任一命令族的业务积压只增加对应 consumer group lag，不消耗其它 pool 的 consumer。
- event publisher 仍可能成为跨命令族共享的下游依赖；本次不拆 event topic，也不改变 publisher 实现。
- 日志不得输出六段凭据、代理密码、消息正文、图片 base64、邀请码或完整手机号。

## 9. 停机切换

测试环境允许停机，旧 topic 中未消费命令允许丢弃，因此不设计兼容期。

切换顺序固定为：

1. 停止 Armada 和 Android Zhuan。
2. 创建三个新 topic，每个 4 个分区；保持 `AllowAutoTopicCreation=false`。
3. 更新 Armada 和 Zhuan 配置并部署新版本。
4. 先启动 Zhuan，确认三个 consumer group 均完成分区分配且没有配置错误。
5. 再启动 Armada，开始向三个新 topic 写 outbox。
6. 保留旧 `protocol.android.commands.v1`，但不再生产、不再消费。
7. 分别执行生命周期、营销和进群验收。

回滚同样采用停机方式：停止双方，恢复旧版本和旧配置后再启动。回滚窗口中新 topic 内未消费命令不自动迁回旧
topic。

## 10. 测试设计

### 10.1 Armada

- Android 上线、下线 outbox 精确写入 lifecycle topic。
- Android 营销 outbox 精确写入 message topic。
- Android 进群 outbox 精确写入 group-join topic。
- Web/master 的上线、下线、营销和进群路由保持不变。
- 三个 Android topic 的空值、重复值配置校验失败。
- 回归测试确认不再生成写入旧 Android topic 的新 outbox。

### 10.2 Zhuan

- TOML 配置能解析三个 topic、group ID 和 concurrency。
- 空 topic、重复 topic、空 group ID、重复 group ID、非正并发均启动失败。
- 三个 pool 使用各自 reader config 并同时启动。
- lifecycle pool 只接受上线、下线；message pool 只接受消息；group-join pool 只接受进群。
- 错路由命令按永久错误提交，不触发任何协议副作用。
- 三个 pool 全部被停止且 reader 只关闭一次。
- 同一账号的生命周期命令仍按顺序执行。
- 离线营销发布 `ACCOUNT_OFFLINE` 结果并提交 offset。
- 离线进群发布 `ACCOUNT_NOT_ONLINE` 结果。

### 10.3 隔离回归

- 阻塞 message handler 时，lifecycle 和 group-join pool 仍能消费并提交。
- 阻塞 group-join handler 时，lifecycle 和 message pool 仍能消费并提交。
- 制造 message topic 积压时，lifecycle consumer group lag 不随营销积压增长。

## 11. dev-1 验收标准

1. 三个新 topic 均存在且各有 4 个分区。
2. 三个 Zhuan consumer group 均分配到预期 topic，成员数和有效并发符合配置。
3. `protocol_command_outbox` 中四种 Android command type 映射到本设计规定的三个 topic。
4. 新部署后不再出现写入 `protocol.android.commands.v1` 的 outbox 行。
5. 离线账号营销命令产生 Armada 可消费的 `ACCOUNT_OFFLINE` 失败结果，不阻塞 message 分区。
6. 离线账号进群产生 `ACCOUNT_NOT_ONLINE` 结果，Armada 任务状态正常收敛。
7. 同时制造营销积压并发送批量上线命令，确认积压只出现在 message consumer group，lifecycle group 能独立推进。
8. 账号最终登录态、营销 attempt 和进群任务结果分别由现有 event consumer 正常落库。

## 12. 风险与约束

- 拆分后跨 topic 不再有同账号总顺序。营销或进群可能在账号上线前到达，并按本设计直接失败回报；这是已确认
  的业务取舍。
- 提高 Zhuan 配置并发前必须先提高对应 topic 分区数，否则新增 consumer 不会带来有效并行度。
- 三组 consumer 会增加 Kafka group member 和连接数；默认 4/4/4 需要在 dev-1 验收连接数和资源占用。
- 本次只隔离 source command topic。若未来 event publisher 或 Redis 成为共享瓶颈，需要基于新证据单独设计，
  不在本次预先拆分。

