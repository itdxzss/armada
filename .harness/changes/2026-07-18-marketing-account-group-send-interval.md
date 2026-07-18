# 变更记录：普通营销任务单账号下群组发送间隔

- 日期 / 分支 / worktree: 2026-07-18 / `1.0.1-snapshot` / 主工作区
- 需求来源: 用户要求新增营销任务页面增加“单账号下群组发送间隔”，默认 0.5 秒、最大 3 秒，避免同一账号瞬间向全部群推送营销命令
- 设计文档: `docs/superpowers/specs/2026-07-18-marketing-account-group-send-interval-design.md`
- 状态: 设计已确认，待实施计划

## 目标（一句话）

让普通营销任务在 Armada Kafka 投递前按协议账号执行 0.5～3.0 秒的持久化逐群推送间隔，同时保持不同账号并行且不修改 Web/Android 协议端。

## 缺口拆解 / 任务清单

- [x] 核对普通营销任务前端、任务聚合、轮次 worker、outbox dispatcher、Web/Android 消费现状。
- [x] 确认页面名称、范围、精度、双协议范围和固定推送间隔口径。
- [x] 完成 Armada-only 设计。
- [ ] 编写实施计划。
- [ ] 前端按 TDD 增加字段、校验和请求映射。
- [ ] 后端按 TDD 增加任务配置、Flyway、Mapper、DTO/VO 和创建校验。
- [ ] 按 TDD 增加普通营销 outbox 初始排期和投递快照。
- [ ] 按 TDD 增加 dispatcher 持久化账号节流、延期和精确唤醒。
- [ ] 运行聚焦测试、真库 DbTest、前端 typecheck/test/build 和后端全量测试。

## 关键设计决策

- 页面字段名称固定为“单账号下群组发送间隔”，默认 0.5 秒，范围 0.5～3.0 秒，步长 0.1 秒。
- 采用简单固定推送间隔；不等待上一条 WhatsApp 发送结果。
- 仅修改 `wheel-saas-pure-web` 和 `armada`，不修改 `armada-protocol` 或 Android Zhuan。
- 任务配置按整数毫秒保存，Kafka payload 不增加字段；outbox 使用内部投递快照和持久化协议账号水位。
- 不在营销事务或轮次 worker 中长时间 sleep。
- 不同账号并行；同一账号跨轮次、多实例和重启后仍受同一水位约束。
- 旧任务回填 500ms；非普通营销命令保持不节流。

## 验证（evidence-before-done）

待实施。数据相关改动必须运行 Flyway/Mapper 真库 DbTest；没有真实输出不得标记完成。

## 部署

- commit / 环境 / 部署后验证结果: 未部署。实施后必须先后端、后前端；任何测试或生产部署需再次确认目标环境。

## 遗留 / 跟进

- 本次不处理“上一条发送完成后再等待”的严格协议执行间隔。
- 本次不扩展建群营销和历史群营销。
