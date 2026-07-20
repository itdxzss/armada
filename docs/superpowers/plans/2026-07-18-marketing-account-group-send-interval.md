# Marketing Account Group Send Interval Implementation Plan

**Goal:** 在普通营销任务中新增“单账号下群组发送间隔”，默认 0.5 秒、范围 0.5～3.0 秒、步长 0.1 秒；由 Armada 在 Kafka 投递前按账号做简单固定排期。

**Architecture:** 前端提交秒数，任务表保存整数毫秒。`MarketingRoundWorker` 按账号计算每条群消息的
`notBeforeAt`，outbox 复用 `next_retry_at`。事务提交后，到期行立即投递，未来行由本机调度器到点交给原有
dispatcher。Kafka payload 和协议层不增加字段。

**Execution boundary:** 直接修改本地 `1.0.1-snapshot`，不建新 worktree，不提交，不部署。

## Task 1: 前端字段

- [x] 请求类型和任务行类型增加 `accountGroupSendIntervalSeconds`。
- [x] 创建表单默认值设为 `0.5`。
- [x] 在“单轮发送数量”下增加 `ElInputNumber`，`min=0.5`、`max=3`、`step=0.1`、`precision=1`。
- [x] 提交前校验有限数字、范围和最多一位小数。
- [x] 请求映射携带字段，重开抽屉恢复默认值。
- [x] 增加前端测试。

## Task 2: 营销任务配置

- [x] DTO 增加 `BigDecimal accountGroupSendIntervalSeconds`。
- [x] 实体增加 `Integer accountGroupSendIntervalMs`。
- [x] 创建时将空值归一为 500ms，校验 500～3000ms 和 100ms 步长。
- [x] 列表和详情转换回秒数。
- [x] `MarketingTaskMapper.xml` 增加字段映射、查询列和插入列。
- [x] V058 仅向 `marketing_task` 增加一列，默认 500ms。
- [x] 增加 service、SQL shape 和 DbTest 覆盖。

## Task 3: 按账号生成最早投递时间

- [x] `MessageSendCommand` 增加内部字段 `long notBeforeAt`。
- [x] `MarketingRoundWorker` 记录一轮内每个账号的位置。
- [x] 使用 `roundStartedAt + position * intervalMs` 计算时间。
- [x] 账号位置跨同一轮的 outbox 批次连续。
- [x] 建群营销和历史群营销使用 `0L`，保持原行为。
- [x] Web/Android backend 不把内部字段写入 payload。

## Task 4: 复用 outbox 简单调度

- [x] `ProtocolCommandOutboxServiceImpl` 把 `notBeforeAt` 写到现有 `next_retry_at`。
- [x] `ProtocolCommandDispatchTrigger` 按 `next_retry_at` 分组。
- [x] 到期分组提交给现有 executor。
- [x] 未来分组复用应用现有 `TaskScheduler` 到点提交，不新增调度器 Bean。
- [x] 保留原有周期扫描兜底。
- [x] dispatcher 保持原锁定、发布、重试逻辑。
- [x] 删除 pace 表、outbox 间隔列、水位服务、延期回写和复杂唤醒器。

## Task 5: 验证和交付

- [x] 聚焦后端单元测试确认 RED 后转 GREEN。
- [x] 运行最终后端相关单元测试。
- [x] 运行 `mvn -DskipTests package`。
- [x] 校验 MyBatis XML 与 `git diff --check`。
- [x] 运行前端测试、类型检查和构建。
- [x] 确认协议仓库无改动、两个代码仓库都没有新增 commit。
- [x] 向用户汇总本地文件差异；不提交。

真库 DbTest 仅在展示非敏感目标并获得用户确认后运行。当前识别目标为 `localhost:3306 / armada`，尚未授权。
