# 变更记录：协议命令 Publisher 有界异步窗口

- 日期 / 分支 / worktree：2026-07-17 / `1.0.1-snapshot` / 当前主 worktree
- 需求来源：用户确认；`docs/superpowers/specs/2026-07-17-protocol-command-publisher-window-design.md`
- 状态：设计完成，待实施

## 目标（一句话）

把 Armada 协议命令发布从逐条等待 Kafka ACK 改为默认最多 100 条在途的可配置异步窗口，同时保持每条 outbox
独立结果和现有状态机。

## 缺口拆解 / 任务清单

- [x] 对账现有 Publisher、Dispatcher、配置和单测。
- [x] 确认保留一条 outbox 一条 Kafka Record。
- [x] 确认窗口可配置，默认 100。
- [x] 完成并确认设计。
- [x] 编写实施计划。
- [ ] TDD 实现配置与有界异步窗口。
- [ ] 完成聚焦回归和全量验证。

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

实施计划已写入 `docs/superpowers/plans/2026-07-17-protocol-command-publisher-window.md`。尚未实施，未运行本任务
测试；实施后记录命令、测试数和真实结果。

## 部署

- commit / 环境 / 部署后验证结果：尚未部署；本次不执行远程或共享环境操作。

## 遗留 / 跟进

- 初始窗口 100 需要在目标测试环境通过 outbox 吞吐、Kafka producer latency 和错误率继续观察。
