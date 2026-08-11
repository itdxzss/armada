# 全链路 Trace ID

## 目标

用 32 位小写十六进制 `traceId` 串联 Armada HTTP、事务 Outbox、Kafka、Protocol Master、Redis Stream、Worker 和回传事件，同时保留业务 ID 作为长期关联依据。

## 数据范围

- 只新增 `protocol_command_outbox.trace_id VARCHAR(32) NULL COMMENT '全链路追踪标识'`。
- 不修改业务表，不建 Trace 表，不回填历史行，首期不建索引。
- 该列是跨事务提交、重试和重启边界保留的一次执行元数据，不是业务事实。

## 状态

- [x] 设计和跨仓库实施计划。
- [x] Armada HTTP/MDC Trace 上下文。
- [x] V111 迁移、回滚证据和 Outbox 持久化映射。
- [x] Armada Kafka 命令发布和全部协议事件消费入口。
- [x] Protocol HTTP、Kafka、Redis Stream、Worker、异步上线生命周期和事件传播。
- [x] 本地 Java/TypeScript 单测、类型检查、Mapper XML 和 diff 格式验证。
- [ ] 确认测试环境后执行 Flyway、真库验证并重新生成数据模型文档。

## 数据模型文档

`.harness/wiki/数据模型.md` 是从真实 MySQL `information_schema` 生成的文档。当前未确认可迁移的测试库，因此不手工修改；待 V111 在确认环境执行后，运行 `.harness/wiki/gen_datamodel.py` 重新生成。
