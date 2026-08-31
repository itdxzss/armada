# 变更记录：四仓平台缺口与执行路线固化

- 日期 / 分支 / worktree: 2026-08-30 / `1.0.3-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户要求梳理非超链竞品缺口、Kafka/topic 治理、日志/健康/Runner 与需求到交付闭环，并将多任务报告收敛为可执行路线。
- 状态: 已完成（限分析、路线与文档固化）

## 目标（一句话）

把四仓当前缺口、Kafka/Runner/交付风险和未来工作拆成可追溯、可验证、单项不超过 4 小时的执行路线。

## 缺口拆解 / 任务清单

- [x] 收敛 K1～K4 为 Kafka/topic/Redis Stream/outbox/Runner 治理设计 S1。
- [x] 收敛 C1～C4 为非超链 38 能力簇、7 P0、4 P1 差距矩阵 S2。
- [x] 将 D1、S1、S2 重建为 62 个 work item 的 S3 执行路线。
- [x] 关闭依赖环、无效前端命令、可变审计自引用、M27 语义丢失、通讯录 `roundNo` 遗漏和 PA-02 悬空 validator。
- [x] 完成独立 V1 复核，最终 verdict 为 `ACCEPT`。
- [x] 将最终 S1/S2/D1/S3/V1 和索引固化到 `docs/plans/2026-08-30-platform-gap/`。

## 关键设计决策

- 阶段一不合并任何 Kafka topic；不改 topic、payload、key、consumer group、ACK/commit/retry 语义。
- replay 默认拒绝；先固化 canonical manifest、全量 inventory、DLT/失败出口和人工授权门。
- 路线最多同时两条实现流；任一 P0 未达到本地出口，P1 不启动。
- 用 `R0-B1/R0-B2` 先建 acceptance/messaging bootstrap linter，避免前置任务倒向依赖后继验证器。
- 四仓 dirty 只能作为 `R0-03` candidate 冻结硬门；不把当前工作树写成可交付候选。
- 全部超链赶超任务排除；只保留共享基础的既有超链回归。

## 验证（evidence-before-done）

- `shasum -a 256` 已确认五份临时最终报告与仓库副本内容一致。
- S3 本地静态检查：62 个 work item，依赖环 0，悬空 validator 0，超 4h 切片 0。
- 前端 `package.json` 实际存在 `test`、`typecheck`、`build` 入口；PA-02 绑定现存 `src/views/account/index/account-display.test.ts`。
- V1 冻结 S3 SHA-256 `ab099c8d05585c0fc60d6c1082cb804e9a3792563536a212e3f2172775f93ae6`，最终 verdict `ACCEPT`。
- 本次没有运行业务构建/测试，没有产生本地实现通过结论。

## 部署

- commit / 环境 / 部署后验证结果: 未 commit，未部署，未访问 test1/远程/真库/真实 WhatsApp 资源。

## 遗留 / 跟进

- 执行 S3 `R0-03`，将四仓 HEAD、dirty diff、命令入口和隔离 worktree 固定为实施 candidate。
- 获得 L1 后按 Flow A/Flow B 实施七个 P0 与 Kafka/Runner/交付证据链，不超过两条实现流。
- 当前未证明本地测试通过、test1 环境通过或业务可用；环境与真实协议验证继续按 L2/L3/L4 分级授权。
