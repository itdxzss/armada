# 账号状态与群同步 Kafka Topic 隔离设计

## 目标

解决 `account.groups_reported` 重事务与账号状态事件共用 Kafka Topic、consumer group 和单线程 listener，导致 `account.state_changed` 长时间积压、Armada 页面账号停留在离线或待上线的问题。

本次统一修改 Armada、Web/Baileys 协议和 Android Zhuan 协议。只改变协议事件的 Kafka 路由和消费并发，不改变 WhatsApp 连接、Presence、passive 模式、群通知采集或账号状态业务语义。

## 当前事实

- Armada 当前由 `ProtocolAccountEventConsumer` 的一个 `@KafkaListener` 同时处理状态、离线诊断、完整群快照和群成员关系变化。
- `account.groups_reported` 会在事务中逐群查询和写库，耗时显著高于状态更新。
- Kafka key 为协议账号 ID，同账号事件在分区内有序；但单个 consumer 处理 12 个分区，任一重事件都会延迟其后面的状态事件。
- Web 协议旧账号 Topic 还承载 heartbeat、owner、proxy 等 Armada 当前不消费的遥测事件，不能把这些高频事件迁入新状态 Topic。
- `protocol.group.events.v1` 已用于群健康和进群结果，不与账号群快照复用。

## Topic 与事件路由

新增两个 Topic，均为 12 分区：

| Topic | 事件 | Armada consumer group | 总并发 |
| --- | --- | --- | --- |
| `protocol.account.state.events.v1` | `account.state_changed`、`account.offline_diagnosed` | `armada-api-account-state-events` | 4 |
| `protocol.account.group-sync.events.v1` | `account.groups_reported`、`account.group_membership_changed` | `armada-api-account-group-sync-events` | 4 |

所有事件继续使用协议账号 ID 作为 Kafka key，同一账号在单个 Topic 内保持分区顺序。两个 Topic 之间不保证顺序；群同步写库不得依赖 Armada 已先消费 ONLINE。

Web 协议的其他 `account.*` 遥测事件继续写入 `protocol.account.events.v1`，Armada 不再订阅该 Topic。原有 `protocol.group.events.v1`、消息事件和 owner 事件路由保持不变。

## 协议层实现

### Web/Baileys

- 配置新增 `KAFKA_TOPIC_ACCOUNT_STATE` 和 `KAFKA_TOPIC_ACCOUNT_GROUP_SYNC`。
- 事件路由器只把两个状态事件发往状态 Topic，把两个账号群同步事件发往群同步 Topic。
- 其他账号遥测事件继续使用现有 `KAFKA_TOPIC_ACCOUNT`。
- 仍复用同一个 KafkaJS producer，避免增加连接数；只按事件类型选择 send 的目标 Topic。

### Android Zhuan

- TOML 配置将 `accounteventtopic` 一次性替换为 `accountstateeventtopic` 与 `accountgroupsynceventtopic`。
- `AccountEventPublisher` 继续复用一个 kafka-go writer，根据 envelope 的事件类型选择目标 Topic。
- 状态事件和离线诊断进入状态 Topic，群快照和本人群关系变化进入群同步 Topic；未知类型返回错误，不静默投递。
- 本地 JSONL DLQ、同步 broker ACK 和三次发送尝试保持不变。

## Armada 实现

- 原账号事件 consumer 改为两个独立 `@KafkaListener` 入口，分别订阅新 Topic 和独立 group。
- 两个 listener 的 `concurrency` 均由环境变量配置，默认 4；perf2 单实例时总并发即 4。多实例部署时按实例数拆分，避免总并发无意放大。
- 状态 listener 只处理状态和离线诊断；群同步 listener 只处理群快照与本人群关系变化。跨域投错的事件抛 `BusinessException`，直接进入原 Topic 对应的 `.DLT`。
- 沿用现有固定 1 秒、最多 3 次的数据库异常重试；`DeadLetterPublishingRecoverer` 按原 Topic 路由到各自 `.DLT`。
- 新 Topic 和两个 DLT 都预建 12 分区。
- Hikari 最大连接数改为环境变量控制，默认 20，为 4+4 Kafka 消费并发保留 API 和调度连接余量。

## 一次性切换

perf2 采用停机一次性切换，不做双写、双消费：

1. 创建两个新 Topic 和两个 DLT，各 12 分区。
2. 停止 Android、Web 协议和 Armada。
3. 将旧账号事件 consumer group offset 移到末尾，废弃旧积压。
4. 账号展示状态统一置为 OFFLINE，并把历史账号上下线 outbox 指令置为终态。
5. 一次性部署三端代码和新配置。
6. 先启动 Armada，确认两个 group 各有 4 个活跃消费者且 lag 为 0。
7. 再启动协议，让账号重连并通过新 Topic 重建状态和群快照。

passive 配置全程保持不变。系统不得根据 EOF、lag、状态数量或其他观测结果自动切回 Active 或旧 Topic。

## 人工回退

只有收到用户明确命令才执行回退：

1. 停止三个服务。
2. 恢复旧版本和旧 Topic 配置。
3. 将旧 consumer group offset 再移到末尾，避免重放切换前的过期事件。
4. 账号展示状态置为 OFFLINE，先启动 Armada，再启动协议，由协议向旧 Topic 重新上报真实状态。
5. 新 Topic 保留用于排障和再次切换，不删除、不自动清理。

## 错误处理与可观测性

- 非法 JSON、缺少必填字段和投错 Topic 的事件不重试，直接进入对应 DLT。
- 数据库等可恢复异常按现有错误处理器重试 3 次后进入 DLT。
- 分别观察两个 group 的总 lag、分区 lag、消费异常和 DLT 数量。
- 额外观察 Hikari active/pending、状态事件 occurredAt 到数据库更新时间延迟，以及 Armada ONLINE 数与协议实际在线数的差异。

## 验证边界

代码交付包含三端路由、配置和消费者单元测试，以及与改动相称的编译/静态检查。perf2 环境切换、全量账号重连、lag 清零和业务验收由用户执行。

## 非目标

- 不改变 WhatsApp Presence 或 passive 行为。
- 不减少或屏蔽 WhatsApp 群通知。
- 不修改群快照、动态营销、账号状态落库的业务规则。
- 不部署、不执行 offset 重置、不批量更新测试库。
