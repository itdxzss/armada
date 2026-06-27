# Account Online Kafka Refactor Slices

## Goal

将账号单条上线、批量上线从“Armada HTTP 调协议并等待协议处理结果”逐步改为“Armada 投递 Kafka 命令，协议层异步消费，最终状态通过 Kafka 事件回填 Armada”。

这份文档只记录切片边界和审核点。每个切片开工前，再单独写该切片的实现计划。

## Current Baseline

- Armada 当前已经支持单条上线和批量上线。
- 批量上线当前通过 HTTP 一次调用协议层 `/v1/accounts/online/batch`。
- Armada 批量代理分配已经改成批量 DB 分配，不再 500 个账号循环 500 次分配代理。
- 协议层当前 HTTP batch online 会等待本地上线处理完成后才 `reply.send(...)`，不是“收到请求立即返回”。
- 协议层当前没有账号上线命令 Kafka consumer，只有事件 publisher。
- Armada 当前没有 Kafka producer/consumer 基础设施。

## Hard Rules

- Kafka 上线入口不能在回填闭环完成前正式打开。
- 每个切片都要能单独审核，避免一次性同时改 Armada、协议层、状态回填、代理释放。
- Outbox 不存完整账号凭据和代理密码，只存 command、account、proxy 等引用信息；真正发送 Kafka 前再从本地表读取最新必要数据。
- HTTP 上线路径保留到 Kafka 路径稳定后，作为回滚开关。
- 用户点击上线仍然是“先释放旧 IP，再分配空闲 IP，再投递上线命令”的语义。

## Outbox Model

Armada 侧新增 `protocol_command_outbox`，一行代表一个账号命令。批量 500 个账号时写 500 行，用同一个 `batch_id` 归组。

核心字段：

| Field | Meaning |
| --- | --- |
| `id` | 本地自增主键 |
| `tenant_id` | 租户 |
| `command_id` | 全局唯一命令 ID，Kafka 幂等和排查使用 |
| `batch_id` | 批量命令归组 ID |
| `command_type` | 初始使用 `account.online.requested` |
| `aggregate_type` | 初始使用 `ACCOUNT` |
| `aggregate_id` | Armada account id |
| `kafka_topic` | 初始使用 `protocol.account.commands.v1` |
| `kafka_key` | 推荐使用 `protocol_account_id`，保证同账号命令有序 |
| `protocol_account_id` | 协议层账号 ID |
| `payload_json` | 轻量 payload，保存账号、代理、凭据格式等引用信息 |
| `status` | `PENDING` / `LOCKED` / `SENT` / `DEAD` / `CANCELED` |
| `retry_count` | 发布重试次数 |
| `next_retry_at` | 下次可重试时间 |
| `locked_by` / `locked_at` | publisher 抢占发送权 |
| `sent_at` | Kafka producer ack 成功时间 |
| `last_error` | 最近一次失败原因 |
| `created_at` / `updated_at` / `deleted_at` | 通用时间字段 |

关键索引：

- `uk_command_id(command_id)`
- `idx_dispatch(status, next_retry_at, id)`
- `idx_batch(tenant_id, batch_id)`
- `idx_account(tenant_id, aggregate_id, created_at)`

## Slice Order

### Slice 1: Armada Outbox Table

范围：

- 新增 `protocol_command_outbox` migration。
- 新增 schema DbTest，验证字段、唯一键、核心索引存在。
- 不新增 Kafka 依赖。
- 不接入上线流程。

审核点：

- 表结构是否能支撑批量 500 条账号命令。
- 索引是否覆盖 publisher 扫描、批次查询、账号排查。
- payload 是否避免复制敏感明文。

### Slice 2: Armada Outbox Mapper And Domain Model

范围：

- 新增 outbox entity / status enum / mapper。
- 支持批量 insert pending command。
- 支持按 `status + next_retry_at` 查询待发送命令。
- 支持锁定、标记 sent、标记 retry、标记 dead。
- 只写 mapper/unit/db tests。
- 不接入上线流程。

审核点：

- 状态流转是否简单、可恢复。
- 并发 publisher 是否不会重复抢同一批命令。
- 批量 insert 是否适合 500 条规模。

### Slice 3: Armada Online Command Enqueue Service

范围：

- 新增 `ProtocolCommandOutboxService`。
- 提供 `enqueueOnlineCommands(...)`，把上线计划转换成 outbox rows。
- payload 只保存 `accountId`、`protocolAccountId`、`credentialFormat`、`proxyId`、`source` 等引用信息。
- 单元测试覆盖单条、批量、重复 command id 防护。
- 暂不从 Controller/online service 调用。

审核点：

- command envelope 是否稳定。
- batchId/commandId 是否便于查日志。
- 不把凭据和代理密码提前复制进 outbox。

### Slice 4: Armada Kafka Producer Infrastructure

范围：

- 增加 Kafka producer 依赖和配置。
- 新增 `ProtocolCommandPublisher`，负责把 outbox row 转成 Kafka message。
- publisher 发送前按 `accountId/proxyId` 批量查询凭据和代理详情。
- 定时调度默认关闭，只保留可测试的 publisher 方法。
- 使用 mock producer 或可替代接口做单元测试。

审核点：

- 不因加 Kafka 依赖影响现有 HTTP 上线。
- 发送失败时是否正确 retry/dead。
- 日志是否能按 batchId/commandId/accountId 排查。

### Slice 5: Armada Publisher Scheduler

范围：

- 新增可配置 scheduler。
- 扫描 `PENDING` outbox，短事务抢占为 `LOCKED`。
- 事务外发送 Kafka。
- 成功标记 `SENT`，失败标记 retry/dead。
- 默认关闭，测试环境手动打开。

审核点：

- DB 事务不包住网络调用。
- 应用重启后 `LOCKED` 卡住命令能恢复。
- 发送速率、每批大小、最大重试次数都可配置。

### Slice 6: Protocol Kafka Consumer Skeleton

范围：

- 在 armada-protocol 增加 Kafka consumer 配置。
- 订阅 `protocol.account.commands.v1`。
- 只解析 envelope、打结构化日志、提交 offset。
- 不真正执行上线。

审核点：

- consumer group 命名是否符合环境隔离。
- 日志是否包含 commandId/batchId/accountId/protocolAccountId。
- 单 worker 下不会引入并发不可控行为。

### Slice 7: Protocol Command Idempotency

范围：

- 增加 command idempotency guard。
- 初始建议用 Redis `SETNX protocol:cmd:{commandId}` 加 TTL。
- 重复 command 只打日志并跳过业务执行。
- 不真正执行上线。

审核点：

- 重复投递不会重复上线。
- TTL 是否足够覆盖 producer 重试和 Kafka 重平衡。
- 幂等失败时是否保守处理。

### Slice 8: Protocol Online Command Handler

范围：

- 把 Kafka `account.online.requested` 接到协议层现有 online 能力。
- 复用 HTTP online 的核心逻辑，避免复制两套上线实现。
- 支持批量命令一个个处理，但从 Kafka consumer 入口进入。
- 增加协议层关键日志。

审核点：

- Kafka handler 和 HTTP handler 是否共享核心上线逻辑。
- 协议失败时是否能发出明确事件。
- 不让 HTTP response 生命周期继续绑定上线处理时长。

### Slice 9: Protocol Failure Events

范围：

- 明确并实现命令处理失败事件。
- 至少覆盖：凭据缺失/格式错误、代理不可用、rate limit、协议上线异常。
- 事件里带 commandId、batchId、accountId/protocolAccountId、reason。

审核点：

- Armada 能靠事件释放 IP 或标记失败。
- 失败原因足够排查生产问题。
- 不把敏感信息写入事件。

### Slice 10: Armada Protocol Event Consumer

范围：

- Armada 增加 Kafka consumer，消费协议账号事件。
- 回填账号在线状态。
- 对失败、离线、proxy_failed 等事件释放 IP。
- 测试覆盖状态更新和代理释放。

审核点：

- Kafka 命令路径的状态闭环是否完整。
- 失败场景 IP 不会长期占用。
- 重复事件不会导致状态异常。

### Slice 11: Armada Online Entry Kafka Mode

范围：

- 增加配置：`armada.protocol.command-mode=http|kafka`。
- 默认仍为 `http`。
- Kafka 模式下，单条/批量上线只做本地代理分配和 outbox enqueue，然后立即返回 accepted。
- HTTP 模式保持现有行为，方便回滚。

审核点：

- 用户接口返回语义从“协议已处理”变成“命令已受理”是否清晰。
- 单条和批量都不会重复发送 HTTP 和 Kafka。
- 模式切换不影响已有测试。

### Slice 12: Load Test And Cutover

范围：

- 在测试环境开启 Kafka mode。
- 压测一次最多 500 个账号的批量上线。
- 观察单 worker 协议层处理速率、错误率、日志完整性。
- 根据实际日志决定是否提高 worker 数或调整消费速率。

审核点：

- 是否达到当前目标吞吐。
- 是否出现代理释放不及时、状态不回填、重复上线。
- 是否可以在生产灰度打开。

## First Slice Recommendation

第一刀只做 Slice 1：`protocol_command_outbox` 表和 schema DbTest。

理由：

- 不改业务行为。
- 不引入 Kafka 依赖。
- 能先把数据模型固定下来。
- 审核成本最低，发现字段问题也最好改。
