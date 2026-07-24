# 变更记录：批量上线防重与代理乐观抢占

- 日期 / 分支 / worktree: 2026-07-24 / 当前分支 / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户针对 perf2 约 1000 账号批量上线超时与重复请求的确认；设计见 `docs/superpowers/specs/2026-07-24-batch-online-optimistic-proxy-allocation-design.md`
- 状态: 进行中

## 目标（一句话）

通过前端超时语义和 30 秒冷却、账号 PENDING/ONLINE 防重、代理 CASE 条件批量抢占及快照批量更新，缩短大批量上线同步耗时并阻止重复协议命令。

## 缺口拆解 / 任务清单

- [x] 对齐 perf2 超时时间线和同步慢路径。
- [x] 确认代理采用 `CASE WHEN + WHERE status=IDLE` 的批量乐观抢占，每次 100 条。
- [x] 固化跨前后端设计与事务失败语义。
- [ ] 用户审阅并确认设计文档。
- [ ] 编写 TDD 实施计划。
- [ ] 实现 Web 超时提示和批量上线 30 秒冷却。
- [ ] 实现 PENDING_ONLINE/ONLINE 预估、执行和命令服务防重。
- [ ] 实现代理 CASE CAS、冲突重试、批量快照和批次日志。
- [ ] 完成前端测试、后端单测和真库 DbTest。
- [ ] 经用户确认后部署 perf2 并执行约 1000 账号性能验收。

## 关键设计决策

- 代理候选查询取消逐账号 `FOR UPDATE`，UPDATE 自身用 `status=IDLE` 作 CAS。
- 批量代理绑定使用单表 `CASE WHEN`；当前只有 `bound_account_id` 一个逐行变化字段，不引入派生表 JOIN。
- CAS SQL 默认每次 100 条；500 账号外部分片语义暂不改变。
- 正常批量上线不维护随账号数增长的 `NOT IN`；删除代理重登只保留固定排除集合。
- 用户手动单账号和批量上线跳过 PENDING_ONLINE、ONLINE；系统恢复来源不受该用户幂等规则阻断。
- 前端不延长全局 10 秒 timeout；批量上线 timeout 改为“正在上线，请稍后”并刷新列表。
- 不把本次范围扩大为异步 operationId API、协议并发或 outbox dispatcher 调整。

## 验证（evidence-before-done）

尚未进入实现。计划要求 Web 聚焦测试、Armada 聚焦单测、Mapper 真库 DbTest，以及 perf2 同范围批量上线耗时和冲突指标对比。

## 部署

- commit / 环境 / 部署后验证结果: 尚未部署；任何 perf2 部署必须再次按部署流程确认。

## 遗留 / 跟进

- 异步批任务 `202 + operationId` 可作为后续架构演进，本次不实现。
- worker 级 PROXY_FAILED 根因分析不属于本次数据库慢路径优化。
