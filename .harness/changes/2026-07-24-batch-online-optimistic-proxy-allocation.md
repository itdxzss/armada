# 变更记录：批量上线防重与代理乐观抢占

- 日期 / 分支 / worktree: 2026-07-24 / 当前分支 / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户针对 perf2 约 1000 账号批量上线超时与重复请求的确认；设计见 `docs/superpowers/specs/2026-07-24-batch-online-optimistic-proxy-allocation-design.md`
- 状态: 代码与离线验证完成，后端与前端已部署 perf2，待真库 DbTest 和约 1000 账号性能验收

## 目标（一句话）

通过前端超时语义和 30 秒冷却、账号 PENDING/ONLINE 防重、代理 CASE 条件批量抢占及快照批量更新，缩短大批量上线同步耗时并阻止重复协议命令。

## 缺口拆解 / 任务清单

- [x] 对齐 perf2 超时时间线和同步慢路径。
- [x] 确认代理采用 `CASE WHEN + WHERE status=IDLE` 的批量乐观抢占，每次 100 条。
- [x] 固化跨前后端设计与事务失败语义。
- [x] 用户审阅并确认设计文档。
- [x] 编写 Outbox 批量 SENT 回写 TDD 实施计划。
- [x] 实现 Kafka ACK 窗口完成后批量回写 Outbox SENT。
- [x] 实现 Web 超时提示和批量上线 30 秒冷却。
- [x] 实现 PENDING_ONLINE/ONLINE 预估、执行和命令服务防重。
- [x] 实现代理 CASE CAS、冲突重试、批量快照和批次日志。
- [x] 完成前端测试和后端单测；真库 DbTest 已补齐但待确认目标库后执行。
- [x] 经用户确认后将当前工作区的后端与前端部署到 perf2。
- [ ] 在 perf2 执行约 1000 账号性能验收。

## 关键设计决策

- 代理候选查询取消逐账号 `FOR UPDATE`，UPDATE 自身用 `status=IDLE` 作 CAS。
- 批量代理绑定使用单表 `CASE WHEN`；当前只有 `bound_account_id` 一个逐行变化字段，不引入派生表 JOIN。
- CAS SQL 默认每次 100 条；500 账号外部分片语义暂不改变。
- 正常批量上线不维护随账号数增长的 `NOT IN`；删除代理重登只保留固定排除集合。
- 用户手动单账号和批量上线跳过 PENDING_ONLINE、ONLINE；系统恢复来源不受该用户幂等规则阻断。
- 前端不延长全局 10 秒 timeout；批量上线 timeout 改为“正在上线，请稍后”并刷新列表。
- 不把本次范围扩大为异步 operationId API 或协议并发；Outbox 仅优化单线程 Dispatcher
  内部的 ACK 窗口与 SENT 批量回写，不增加 Dispatcher 线程数。

## 验证（evidence-before-done）

- 后端聚焦测试：116 个通过，0 失败、0 错误；覆盖 Outbox ACK 窗口、状态防重、
  100 条 CASE/快照分片、冲突换 IP 重试和批量跳过统计。
- Java 17 执行 `mvn -DskipTests package`：通过。
- 4 个受影响 Mapper XML 经 `xmllint --noout` 校验：通过。
- 前端全量 Node 测试：388 个通过，0 失败；`pnpm typecheck`、定向 ESLint、
  `pnpm build` 全部通过。
- `git diff --check`：通过。
- Mapper 真库 DbTest：尚未执行。需先确认
  `.env` 指向允许测试写入的本地或测试库。

## 部署

- 环境 / 范围: `perf2`（第二套环境）/ 当前未提交工作区的后端与前端；未部署协议层。
- 基线: Armada `2fd09ca`、前端 `a473b5aa`，两边均包含本记录对应的未提交改动。
- 结果: `armada-backend`、`armada-nginx` 已原地重建，均为 `running`、`RestartCount=0`；
  公网首页 200、环境标识、API 代理和协议 `readyz` 验证通过，后端启动日志未命中致命错误。
- 环境遗留: perf2 档案配置的 Compose project 是 `armada-perf`，远端既有容器实际属于
  `armada-deploy`。本次使用既有 project 原地重建；后续应单独统一档案和远端 project，
  否则标准脚本会因固定容器名冲突而失败。

## 遗留 / 跟进

- 异步批任务 `202 + operationId` 可作为后续架构演进，本次不实现。
- worker 级 PROXY_FAILED 根因分析不属于本次数据库慢路径优化。

## 后续修正：默认事务与 PROXY_FAILED 恢复

- 状态: 仅在本地 `1.0.1-snapshot` 修改，未 commit、未部署；由用户负责测试环境验收。
- 删除上线和代理分配入口显式 `READ_COMMITTED`，继续使用数据库默认事务隔离级别。
- 代理上线分配不再使用 CASE 批量抢占，改为候选普通 SELECT + 单行
  `UPDATE ... WHERE id = ? AND status = IDLE`；返回 0 立即尝试下一候选。
- PROXY_FAILED 拆成独立 A/B/C：A 提交 OFFLINE/PROXY_FAILED；B 按事件的
  `accountId + proxyId` 精确释放旧代理回 IDLE；C 条件抢占恢复资格后换 IP、更新快照并写 outbox。
- 协议 PROXY_FAILED 不再把 IP 标记为 UNAVAILABLE。B/C 异常不向 Kafka 状态消费冒泡；
  C 回滚后由 5 秒周期扫描持续补偿，账号上线、人工停止或进入终态后自动退出扫描。
- 安卓协议自救逻辑不在本次修改范围。
