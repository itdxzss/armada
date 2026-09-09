# 变更记录：WhatsApp 超链任务六方案跨仓代码评审

- 日期 / 分支 / worktree：2026-08-28 / `1.0.3-snapshot` / 主工作区
- 需求来源：用户要求更新 Armada 代码基线，审阅新提交的六个超链任务设计，并完整回答双协议交互、Kafka 分区/消费者、并发、WhatsApp 报文、测试验收、Runner、压测和监控
- 状态：`VERIFIED`

## 目标（一句话）

基于 Armada、Web/Baileys、Android/Zhuan 和 staging Runner 的真实代码，形成可执行的跨仓评审、验收和压测放行方案。

## 缺口拆解 / 任务清单

- [x] 确认 Armada 当前本地设计提交和工作区状态
- [x] 通读共享契约与 H1-H6 六份设计
- [x] 核对 Armada Outbox/Kafka 路由、事件 consumer 和运行配置
- [x] 核对 Web master/Redis Stream/worker、消息发送、ACK 和恢复语义
- [x] 核对 Android 多 consumer、并行 prepare、串行 dispatch、私聊底层能力和状态机
- [x] 核对 staging Runner 和现有 perf2 压测工具的能力边界
- [x] 输出 P0/P1 findings、修订契约、分 PR 审核方案、测试验收、压测与监控方案
- [x] 明确本次未部署、未运行远程验收、未触达真实 WhatsApp

## 关键设计决策

- 控制面和短链走 HTTP；消息发送统一走业务事务 Outbox + Kafka，禁止新功能同步 HTTP 旁路协议状态机。
- “并发”拆成 consumer、prepare、socket dispatch、ACK in-flight 四个维度；Android 的 20 只能解释为每账号 prepare concurrency。
- 每账号 WhatsApp socket dispatch 默认保持 1，多账号并行；容量由模拟协议压测后冻结。
- Web 与 Android 都需要 commandId 幂等状态和结果 outbox；外部 WhatsApp 副作用不宣称 exactly-once，崩溃不确定窗口进入 UNKNOWN。
- 真实 WhatsApp 仅用于白名单低速 canary，容量压测使用协议模拟器。
- Runner 保持编排/证据职责，不充当协议 worker 或负载生成器。
- Armada 新业务遵守 `Controller -> Service -> Mapper`，否决方案中的 Repository 和自定义 DDD 分层。

## 验证（evidence-before-done）

- 静态核对三个仓库的当前 commit、工作区状态和六份设计文件。
- 使用 `rg`/源码阅读核对 Armada 的 `MessageSendCommand`、Web/Android backend、Kafka listener 与 dispatch executor。
- 使用源码阅读核对 Web master consumer、worker stream、message sender、event bridge/publisher 和 PM2 配置。
- 使用源码阅读核对 Android message command parser、dispatcher、executor/state、私聊 HTTP sender 和生产配置。
- 使用源码阅读核对 staging Runner 的 safety、单 worker/lock、恢复与证据能力，以及现有 perf2 工具边界。
- `git diff --check`：待文档写入后执行。

## 部署

- commit / 环境 / 部署后验证结果：未提交、未部署；本次仅产生评审文档和变更记录。

## 遗留 / 跟进

- 用户/产品需确认 Android 送达/已读范围、失败重发语义、Web 是否拆消息专用 topic、并发 20 的正式定义。
- 修订 H3/共享契约并关闭五个 P0 后，按协议基础能力、Armada 生命周期、查询统计、前端联调分 PR 实施。
- test1 部署和真实 WhatsApp canary 需要另行确认目标 revision、环境和安全授权。
