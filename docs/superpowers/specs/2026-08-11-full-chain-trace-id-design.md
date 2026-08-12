# Armada 全链路 Trace ID 设计

日期：2026-08-11  
状态：本地实现完成，待测试环境验证
涉及仓库：`armada`、`armada-protocol`

## 1. 背景与目标

当前排障已经能够依靠 `accountId`、`protocolAccountId`、`taskId`、`itemId`、`commandId`、`eventId`、`groupJid` 等业务标识精准检索，但同一次执行会跨越后端 HTTP、事务 Outbox、Kafka、协议层 Master、Redis Stream、Worker、事件 Kafka 和后端消费者。排障人员仍需手工拼接不同阶段的业务标识。

本次增加轻量级 `traceId`，使一次执行链路上的日志可以通过一个值关联，同时保留现有业务 ID 作为长期业务生命周期的权威检索键。

目标链路：

```text
HTTP/后台调度
  -> Armada 业务处理
  -> protocol_command_outbox
  -> Kafka command
  -> Protocol Master
  -> Redis Stream
  -> Protocol Worker
  -> Kafka event
  -> Armada event consumer
```

本次不是完整的 OpenTelemetry 分布式追踪：不引入 span、采样、Collector、Trace UI 或 Exporter。

## 2. 核心约定

### 2.1 格式和命名

- `traceId`：32 位小写十六进制字符串，不接受全零值。
- HTTP：`X-Trace-Id`。
- JSON 消息：`traceId`。
- Kafka Header：`traceId`，作为消息体字段的镜像。
- 数据库列：`trace_id`。
- 日志字段：`traceId`。

收到缺失或非法的外部 Trace ID 时，系统生成新值，不回显、不记录非法原值，也不因此阻断业务。

### 2.2 Trace 与业务 ID 的职责

- `traceId` 回答“这一次执行经过了哪些组件”。
- 业务 ID 回答“这是哪个账号、任务、命令或事件”。
- `traceId` 不参与幂等、鉴权、租户隔离、状态机判断或数据库业务关联。
- 排障时优先用已知业务 ID 定位入口，再用 `traceId` 展开本次链路；也可从任一错误日志直接反查同一 Trace。

## 3. Trace 生命周期

### 3.1 创建边界

- 外部 HTTP 请求携带合法 `X-Trace-Id` 时沿用，否则在入口创建。
- 一个用户动作产生多条下游命令时，默认共享入口 Trace。
- 后台调度以“单个待处理项”为边界创建 Trace，不让一次批量扫描中的所有任务共用一个 Trace。
- 重试、Outbox 重发和进程重启后继续发送同一条命令时，必须保留原 Trace。
- 独立补偿动作创建新 Trace，并通过原有业务 ID 与旧执行关联。

### 3.2 账号长生命周期

一次上线命令从 `VERIFYING` 到 `ONLINE` 或失败使用同一 Trace。上线完成后的心跳超时、断线、自动重连等独立事件创建新 Trace；跨生命周期关联继续使用 `accountId`、`protocolAccountId` 和已有尝试标识，避免一个 Trace 无限延长。

## 4. 数据模型

数据库只修改事务 Outbox 表：

```sql
ALTER TABLE protocol_command_outbox
    ADD COLUMN trace_id VARCHAR(32) NULL COMMENT '全链路追踪标识';
```

约束如下：

- 不新增 Trace 表。
- 不修改账号、任务、营销、命令结果等业务表。
- 不回填历史数据；迁移前产生的行允许 `trace_id` 为空。
- 新代码创建 Outbox 行时应写入非空 Trace。
- 第一版不增加 `trace_id` 索引。当前检索入口是日志，数据库按 Trace 查询尚无明确需求；若后续出现稳定的 DB 检索场景，再基于查询频率和执行计划补索引。
- Flyway 迁移使用项目既有的 `information_schema` 防重复模式，并同步生成的数据模型文档。
- 回滚应用版本时保留该可空列，避免破坏新旧版本兼容；如确认所有相关版本均已退出，可通过独立回滚脚本删除该列。

Outbox 必须持久化 Trace，因为事务提交后的异步发送、失败重试和应用重启都无法依赖原线程 MDC。仅将 Trace 放在内存或日志里不能保证全链路。

## 5. Armada 后端设计

### 5.1 HTTP 与日志上下文

增加统一 Trace 工具和 `OncePerRequestFilter`：

1. 校验请求头中的 `X-Trace-Id`，缺失或非法则生成。
2. 将 Trace 放入 SLF4J MDC 的 `traceId`。
3. 在响应头返回 `X-Trace-Id`，方便测试人员从接口响应直接检索日志。
4. 在 `finally` 中恢复或清理 MDC，防止线程池复用造成串链。

当前日志配置未输出 MDC，因此同步调整日志格式，使每条应用日志包含 `traceId=%X{traceId:-}`。没有 Trace 的启动日志和框架日志显示 `-`。

所有到协议层的 HTTP 调用由中心 `RestClient` 拦截器注入 `X-Trace-Id`。如果调用发生在没有上下文的后台线程，调用链入口必须先建立 Trace。

### 5.2 Outbox 写入与发布

- Outbox 实体、Mapper 和插入语句增加 `traceId/trace_id`。
- Outbox 服务优先读取当前 MDC Trace；后台入口尚未创建上下文时生成新 Trace。
- 发布器从 Outbox 行恢复 Trace，并写入命令消息体与 Kafka Header。
- 同一 Outbox 行重试时复用持久化的 Trace。
- 对迁移前 `trace_id IS NULL` 的旧行，发布器使用 `commandId` 计算稳定的 32 位十六进制兼容 Trace；这样多次重试和重启不会改变。兼容逻辑只用于追踪，不改变 `commandId` 语义。

命令 Envelope 新增字段；协议层解析保持可选输入，以兼容滚动发布期间的旧消息：

```json
{
  "traceId": "0123456789abcdef0123456789abcdef"
}
```

### 5.3 Kafka 事件消费

后端事件消费者按单条消息建立 MDC Scope：

- 优先使用事件 Envelope 中合法的 `traceId`。
- 消息体缺失时读取 Kafka Header。
- 两者不一致时记录告警并以 Envelope 为准。
- 旧消息两处都缺失时生成新 Trace，业务仍正常执行。
- 每条消息处理结束后在 `finally` 中清理或恢复 MDC。

消费者的业务日志、错误日志和后续副作用因此自动携带 Trace。

## 6. Armada Protocol 设计

### 6.1 Node Trace Context

增加基于 `AsyncLocalStorage` 的 Trace Context，提供以下最小能力：

- 校验或生成 Trace。
- 在指定异步回调内运行 Trace Scope。
- 获取当前 Trace。
- Pino `mixin` 自动为 Scope 内日志附加 `traceId`。

Fastify Hook 接收或生成 `X-Trace-Id`，写入响应头并建立请求 Scope。现有 `reqId` 保留，用于协议层单次 HTTP 请求内部定位。

### 6.2 Command 链路

Kafka Master 消费命令时，以单条消息为 Scope，不能让整个批次共享上下文：

1. 从命令 Envelope 读取 `traceId`，Header 作为兼容回退。
2. Envelope 与 Header 不一致时告警并采用 Envelope。
3. 旧命令无 Trace 时生成新值。
4. Master 转发到 Redis Stream 时显式携带 `traceId`。
5. Worker 取出 Stream 消息后重新建立 Trace Scope。

Master 到 Worker 的 HTTP 转发同样携带 `X-Trace-Id`。

### 6.3 Event 链路

`EventEnvelope` 新发布消息必须包含 `traceId`，Kafka Header 同步写入镜像值；消费端仍兼容字段缺失的旧消息：

- 命令直接产生的结果事件继承命令 Trace。
- Worker 在命令 Scope 中产生的关联事件继承当前 Trace。
- 心跳超时、断线通知等没有当前命令上下文的自发事件创建新 Trace。
- 旧调用方未传 Trace 时，事件发布器从当前 Scope 获取；仍不存在则生成。

该字段保持可选，确保协议层与后端可以滚动发布。

## 7. 一致性与兼容策略

消息体字段是跨中间件传播的权威值，Kafka Header 是便于消费者和运维工具读取的镜像值：

- 两者合法且一致：正常使用。
- Envelope 合法、Header 缺失或不同：使用 Envelope；不一致时告警。
- Envelope 缺失或非法、Header 合法：使用 Header。
- 两者都缺失或非法：生成新 Trace，不中断业务。

消费端对新增消息字段保持兼容，数据库列可空，不要求停机发布；新版本生产者发出的 Envelope 始终包含合法 `traceId`。

推荐发布顺序：

1. 执行 Outbox 数据库迁移。
2. 发布可接收、传播可选 Trace 的 `armada-protocol`。
3. 发布持久化并发送 Trace 的 `armada`。
4. 观察日志后，再决定是否让前端主动生成或展示 Trace；本次不包含前端修改。

回滚时先回滚生产新消息的后端，再回滚协议层；数据库列保留。

## 8. 并发、错误与安全

- Java MDC 和 Node `AsyncLocalStorage` 都必须在 Scope 结束时清理，避免线程或异步任务串链。
- Trace 解析失败只影响可观测性，不影响业务成功、重试和幂等。
- 不把异常、请求体、令牌、手机号等数据编码进 Trace。
- 对外部 Trace 只接受严格格式，防止日志注入和超长字段。
- Trace 不是可信身份，不作为租户过滤条件；所有数据访问继续使用现有租户上下文。
- 传播成本仅为 32 字符字段、少量消息 Header 和日志字段，不引入网络 Exporter。

## 9. 测试设计

### 9.1 Armada

- HTTP：合法、缺失、非法 Header；响应 Header；请求结束后 MDC 清理。
- 并发：不同请求和并行消息不串 Trace。
- Outbox：创建时持久化 Trace；重试和重启后 Trace 不变；历史空值按 `commandId` 稳定派生。
- Mapper/Flyway：列映射、插入和迁移可重复执行。
- Kafka Command：Envelope 与 Header 一致。
- Kafka Event：Envelope/Header 优先级、不一致告警、旧消息兼容、消费结束后 MDC 清理。
- 日志配置：有 Scope 时输出 Trace，无 Scope 时输出 `-`。

### 9.2 Armada Protocol

- Fastify：接收、生成、返回 Trace，异步日志携带 Trace。
- Kafka Master：逐消息建立 Scope，Envelope/Header 兼容规则正确。
- Redis Stream：Master 写入、Worker 读取并恢复同一 Trace。
- Worker：并发命令互不污染。
- Event：命令结果继承 Trace，自发事件创建新 Trace，Envelope/Header 一致。
- Legacy：没有 Trace 的旧命令和旧事件不影响业务处理。

### 9.3 跨仓库契约

使用固定 Trace 样例验证 Java 与 TypeScript 对格式、字段名、Header 名和优先级的理解一致。至少覆盖：

```text
0123456789abcdef0123456789abcdef
```

## 10. 验收标准

在测试环境触发一次真实业务操作后，用响应中的 Trace ID 搜索日志，应能看到：

- Armada HTTP 入口或后台任务入口。
- Armada 业务处理、Outbox 落库和 Kafka 命令发布。
- Protocol Master 收到命令并转发。
- Protocol Worker 执行命令。
- Protocol 发布结果事件。
- Armada 消费事件并更新结果。

每个关键日志同时保留对应的业务 ID。Outbox 重试后仍能使用最初的 Trace 找到发送记录。并发执行不同操作时不能出现 Trace 串链。

## 11. 非目标

- 不实现 `spanId`、父子 Span、耗时瀑布图或采样。
- 不接入 OpenTelemetry Collector、Tempo、Jaeger 或 X-Ray。
- 不新增 Trace 查询接口、Trace 数据表、仪表盘或告警规则。
- 不改前端页面。
- 不替换现有业务 ID、Fastify `reqId`、Kafka `commandId/eventId`。

## 12. 后续演进

第一阶段验证 Trace 对测试环境排障的实际收益。若日志仍无法快速回答耗时和调用拓扑问题，可在保持当前 `traceId` 兼容的前提下引入 OpenTelemetry，并将该值映射为标准 Trace ID。若出现高频的数据库 Trace 检索需求，再单独评估 Outbox 索引。
