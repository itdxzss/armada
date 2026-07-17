# 变更记录：协议命令 Publisher 有界异步窗口

- 日期 / 分支 / worktree：2026-07-17 / `1.0.1-snapshot` / 当前主 worktree
- 需求来源：用户确认；`docs/superpowers/specs/2026-07-17-protocol-command-publisher-window-design.md`
- 状态：本地实现完成，未提交、未部署

## 目标（一句话）

把 Armada 协议命令发布从逐条等待 Kafka ACK 改为默认最多 100 条在途的可配置异步窗口，同时保持每条 outbox
独立结果和现有状态机。

## 缺口拆解 / 任务清单

- [x] 对账现有 Publisher、Dispatcher、配置和单测。
- [x] 确认保留一条 outbox 一条 Kafka Record。
- [x] 确认窗口可配置，默认 100。
- [x] 完成并确认设计。
- [x] 编写实施计划。
- [x] TDD 实现配置与有界异步窗口。
- [x] 完成聚焦回归并尝试全量验证；全量测试受本机数据库环境阻塞。

## 关键设计决策

- 推荐窗口式异步提交，不使用全批次无界异步，避免 Producer 缓冲和内存压力失控。
- 不把 100 条命令手工封装成一条 Kafka 消息，保留独立分区、重试、幂等和 outbox 状态。
- 返回结果继续与输入顺序一致；这不影响窗口内 ACK 并发，只用于保持现有方法契约。
- 配置小于 1 时启动失败，不静默修正。

### 被否决方案

- 全部 rows 一次性异步发送：批次上限和内存压力依赖调用方，缺少明确背压。
- 复合 Kafka 消息：破坏逐条重试、分区路由、监控和 DLQ 语义。
- 新建应用发送线程池：KafkaTemplate 已提供异步能力，本次没有额外线程池的必要。

## 验证（evidence-before-done）

实施计划已写入 `docs/superpowers/plans/2026-07-17-protocol-command-publisher-window.md`。

- 改动前基线：
  `mvn -Dtest='ProtocolCommandPublisherPropertiesTest,ProtocolCommandPublisherTest,ProtocolCommandDispatcherTest' test`，
  21 tests，0 failure，0 error，0 skipped，BUILD SUCCESS。
- 配置 RED：`mvn -Dtest=ProtocolCommandPublisherPropertiesTest test` 因 `getMaxInFlight` 和
  `DEFAULT_MAX_IN_FLIGHT` 尚不存在而编译失败，符合预期。
- 配置 GREEN：同一命令 7 tests，0 failure，0 error，0 skipped，BUILD SUCCESS。
- 窗口 RED：
  `mvn -Dtest=ProtocolCommandPublisherTest#publishBatch_submitsOneWindowBeforeWaitingAndKeepsInputOrder test`，
  旧实现只提交第一条后等待 ACK，`submittedFullWindow` 断言失败，符合预期。
- 窗口 GREEN：同一命令 1 test，0 failure，0 error，0 skipped，BUILD SUCCESS。
- Publisher 回归：`mvn -Dtest=ProtocolCommandPublisherTest test`，11 tests，0 failure，0 error，0 skipped，
  BUILD SUCCESS。
- 聚焦联合回归：
  `mvn -Dtest='ProtocolCommandPublisherPropertiesTest,ProtocolCommandPublisherTest,ProtocolCommandDispatcherTest' test`，
  25 tests，0 failure，0 error，0 skipped，BUILD SUCCESS。
- 全量：`mvn test` 执行到结果汇总时为 154 tests，0 failure，47 errors，0 skipped；共同根因是本机 MySQL
  `root@localhost` 无密码访问被拒，Flyway/Spring ApplicationContext 无法启动。为避免其继续逐个耗尽连接超时，
  在确认相同环境错误后中止进程，退出码 130；不得声称全量通过。
- 任务范围 `git diff --check` 通过。本次没有数据库、API、Redis 或跨仓改动，不需要真库 DbTest。

## 部署

- commit / 环境 / 部署后验证结果：按用户要求保持本地未提交；尚未部署，本次不执行远程或共享环境操作。

## 遗留 / 跟进

- 初始窗口 100 需要在目标测试环境通过 outbox 吞吐、Kafka producer latency 和错误率继续观察。
