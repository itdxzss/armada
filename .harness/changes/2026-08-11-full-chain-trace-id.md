# 变更记录：全链路 Trace ID

- 日期 / 分支 / worktree: 2026-08-11 / `1.0.3-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户要求先增加全链路 Trace ID，并确认数据库只给 `protocol_command_outbox` 增加 `trace_id` 列；详细设计见 `docs/superpowers/specs/2026-08-11-full-chain-trace-id-design.md`
- 状态: 本地实现与验证完成，待确认测试环境后执行迁移和端到端验证

## 目标（一句话）

用一个轻量级 `traceId` 串联 Armada HTTP/后台任务、事务 Outbox、Kafka、Protocol Master、Redis Stream、Worker 和回传事件，同时保留现有业务 ID 作为长期业务关联依据。

## 缺口拆解 / 任务清单

- [x] 盘点现有业务 ID、HTTP、Outbox、Kafka、Redis Stream 和日志上下文。
- [x] 明确 Trace 格式、生命周期、兼容规则、发布顺序和验收标准。
- [x] 确认数据库范围：只修改 `protocol_command_outbox`，不改业务表。
- [x] 增加 Flyway `V111__add_trace_id_to_protocol_command_outbox.sql` 和本地 SQL/H2 验证。
- [ ] 在确认的测试库执行迁移，并重新生成数据模型文档。
- [x] 在 Armada 增加 HTTP/MDC Trace Context、日志格式和协议 HTTP Header 传播。
- [x] 在 Armada Outbox 持久化 Trace，并通过 Command Envelope/Kafka Header 发布。
- [x] 在 Armada 全部协议 Event Consumer 恢复并清理 Trace Context。
- [x] 在 Armada Protocol 增加 `AsyncLocalStorage`、Fastify/Pino Trace 支持。
- [x] 在 Kafka Command、Redis Stream、Worker、Event Envelope/Kafka Header 之间传播 Trace。
- [x] 补齐 Java、TypeScript、并发隔离、兼容和跨仓库契约测试。
- [ ] 在测试环境验证一次真实业务操作的完整日志链路。

## 关键设计决策

- Trace 使用 32 位小写十六进制，HTTP Header 为 `X-Trace-Id`，JSON/Kafka/日志字段为 `traceId`，数据库列为 `trace_id`。
- `traceId` 只用于一次执行链路；账号、任务、命令和事件的长期关联继续使用现有业务 ID。
- 同一 Outbox 行的重试和重启恢复复用持久化 Trace；历史空值按 `commandId` 稳定派生兼容 Trace。
- 消息 Envelope 是权威值，Kafka Header 是镜像；不一致时使用 Envelope 并告警。
- 数据库只新增 `protocol_command_outbox.trace_id VARCHAR(32) NULL COMMENT '全链路追踪标识'`，不回填历史数据，第一版不建索引。
- 当前 Flyway 最新版本已核实为 `V110`，本变更预留 `V111`；实施落盘前仍按仓库规则检查版本没有被并行变更占用。
- 采用轻量级 MDC/`AsyncLocalStorage` 传播，不引入 OpenTelemetry Span、Collector、采样和 Trace UI。
- 滚动发布顺序为数据库、Protocol、Armada；应用回滚时保留可空列。

被否决的方案：

- 只把 Trace 放在 MDC：事务提交后的异步 Outbox、失败重试和进程重启会丢失，不满足全链路。
- 把 Trace 塞进 `payload_json`：无需迁移，但查询和约束弱，并污染业务消息结构。
- 给所有业务表加 Trace：扩大迁移和写入范围，而且会把一次执行标识误当成长期业务属性。
- 首期直接接入完整 OpenTelemetry：当前核心问题是日志关联，Span 基础设施的投入和风险高于首期收益。

## 验证（evidence-before-done）

设计阶段已执行：

```text
$ 占位标记扫描 <设计文档> <变更记录>
无未决占位标记

$ git diff --check -- <设计文档> <变更记录>
通过（exit 0）

$ find armada-api/src/main/resources/db/migration -maxdepth 1 -type f -name 'V*.sql' | sort -V | tail
最新文件：V110__normal_group_creation_contact_failure_detail.sql
```

本地实施验证已执行：

```text
$ mvn -q -Dtest=<17 个 Trace/Outbox/Publisher/Consumer 测试类> test
suites=17 tests=142 failures=0 errors=0 skipped=0

$ npm test -- --runInBand
Test Suites: 66 passed, 66 total
Tests:       609 passed, 609 total

$ npm run lint
tsc --noEmit（exit 0）

$ xmllint --noout armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml
$ git diff --check  # armada、armada-protocol
全部 exit 0

$ find armada-api/src/main/resources/db/migration -maxdepth 1 -type f -name 'V111__*.sql' -print
V111__add_trace_id_to_protocol_command_outbox.sql（唯一）
```

未执行：真实 MySQL Flyway、数据模型重新生成、测试环境端到端日志检索。原因是尚未确认目标测试库和部署环境。

## 部署

- commit / 环境 / 部署后验证结果: 按用户要求未提交、未部署；后续按“数据库迁移 → `armada-protocol` → `armada`”发布，并从接口响应取得 Trace 搜索完整链路。

## 遗留 / 跟进

- 首期不包含前端展示、Trace 查询接口、数据库索引、仪表盘和告警。
- 首期效果验证后，再决定是否接入 OpenTelemetry，或根据实际 SQL 查询需求增加 Outbox Trace 索引。
- 应用回滚不删除数据库列；如所有相关版本均退出，再使用独立人工回滚脚本清理。
