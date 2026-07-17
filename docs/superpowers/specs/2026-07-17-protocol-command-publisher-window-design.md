# 协议命令 Publisher 有界异步窗口设计

> 状态：设计已确认，待实施
> 日期：2026-07-17
> 范围：`armada/armada-api`

## 1. 背景

`ProtocolCommandPublisher.publishBatch` 当前先批量准备命令 envelope，但发送阶段仍逐行执行
`KafkaTemplate.send(...).get(...)`。应用线程必须等上一条 producer ACK 返回后才能提交下一条，因此同一批
outbox 无法利用 Kafka Producer 已有的异步缓冲和原生 RecordBatch。

本次保持“一条 outbox 对应一条 Kafka Record”，只把发送阶段改成可配置的有界异步窗口。用户确认初始窗口为
100，后续根据压测结果通过配置调整。

## 2. 目标

1. 同一发送窗口内先异步提交最多 100 条独立 Kafka Record，再统一等待结果。
2. 保留每条 outbox 独立的成功、失败、超时和后续 `SENT/RETRY/DEAD` 状态处理。
3. 限制同时在途的发送数量，避免把整个 outbox 批次无上限压入 Producer 缓冲区。
4. 保持 `publishBatch` 返回结果与输入 rows 顺序一致，但不按输入顺序等待 ACK。
5. 保持单条 `publish`、命令 envelope、Topic、Kafka key 和 Dispatcher 调用契约不变。

## 3. 非目标

- 不把多条业务命令封装成一条复合 Kafka 消息。
- 不新增应用业务线程池；继续使用 `KafkaTemplate` 和 Kafka Producer 自身异步能力。
- 不改变 outbox 扫描、锁定、重试次数、`SENT/RETRY/DEAD` 状态机或数据库结构。
- 不修改 Zhuan/Web 消费端、Kafka Topic、分区数或消费者并发。
- 不在本次处理营销 attempt 与 outbox `DEAD` 的失败闭环。

## 4. 配置设计

在现有 `armada.protocol.command-publisher` 下新增：

```yaml
armada:
  protocol:
    command-publisher:
      send-timeout-ms: ${PROTOCOL_COMMAND_SEND_TIMEOUT_MS:10000}
      max-in-flight: ${PROTOCOL_COMMAND_MAX_IN_FLIGHT:100}
```

`ProtocolCommandPublisherProperties` 新增 `maxInFlight`，默认值为 100。值必须大于等于 1；非法配置必须在
应用启动绑定配置时失败，不允许静默改成 1 或退化为无限发送。

## 5. 发送流程

`publishBatch` 继续先调用现有 envelope 准备逻辑。payload 校验失败或补全失败的行直接生成失败 outcome，不占用
Kafka 在途名额。

可发送行按输入顺序分成最多 `maxInFlight` 条的窗口。每个窗口执行以下流程：

1. 连续调用 `KafkaTemplate.send(topic, key, envelope)`，保存 row、输入位置和 Future。
2. 单次 `send` 同步抛错时，只记录该行失败，继续提交窗口内其它有效行。
3. 每条 Future 从调用 `send` 时开始应用现有 `sendTimeoutMs` 超时。
4. 当前窗口全部 Future 收敛后，将每条成功或失败 outcome 写回对应输入位置。
5. 当前窗口完成后才提交下一窗口，保证在途数量不超过配置值。
6. 最终按输入位置返回 outcome 列表。

保持返回顺序不代表按顺序等待 ACK。窗口内所有 Record 已先提交；某条 Future 较早完成时，其结果直接保存到自身
位置，不阻塞其它 Future 的完成。

## 6. 错误与中断语义

- Future 正常完成：生成该 row 的成功 outcome。
- Producer 异常、序列化异常或 Future 异常完成：沿用现有 `ProtocolException` 映射并生成该 row 的失败 outcome。
- Future 超过 `sendTimeoutMs`：沿用现有 Kafka 超时失败语义，交由 Dispatcher 标记重试或终态失败。
- 调用线程被中断：恢复线程中断标记；未获得成功确认的行不得返回成功。
- 日志继续只记录 commandId、batchId、accountId、protocolAccountId 和 topic 等现有安全字段，不新增 payload 输出。

## 7. 兼容性

- `publish(row)` 继续调用 `publishBatch(List.of(row))`，成功时返回原结果，失败时抛原异常。
- `ProtocolCommandPublishOutcome` 继续携带具体 outbox row，Dispatcher 不需要改变状态回写逻辑。
- Kafka key、同分区发送调用顺序和单条消息协议均不改变。
- 不新增数据库、API、Redis 或跨仓契约变更。

## 8. 测试设计

使用纯 Java 单测和 Mockito 验证，不涉及 Mapper、事务或数据库，因此不需要真库 DbTest。

1. `maxInFlight` 默认值为 100，并可通过配置及 application.yml 环境占位覆盖。
2. `maxInFlight < 1` 时配置绑定失败。
3. 第一条 Future 未完成时，窗口内后续 Record 已调用 `send`。
4. 使用较小测试窗口时，达到上限后不会提交下一条；窗口完成后才继续。
5. 混合成功、同步发送失败、异步失败和超时时，每行 outcome 独立且输入顺序不变。
6. envelope 准备失败的行不占用窗口名额，也不阻止其它有效行发送。
7. 单条 `publish` 的成功和失败行为保持兼容。

## 9. 验证与回滚

实施阶段至少运行：

```bash
cd armada-api
mvn -Dtest='ProtocolCommandPublisherPropertiesTest,ProtocolCommandPublisherTest,ProtocolCommandDispatcherTest' test
mvn test
```

若全量测试受既有本地数据库环境阻塞，必须单独报告，不能以聚焦测试替代全量结果。回滚只需回退本次 Java、配置和
测试改动；不存在数据库或 Kafka 数据迁移。
