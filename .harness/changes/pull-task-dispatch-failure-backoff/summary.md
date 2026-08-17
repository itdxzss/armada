# 变更概述：普通拉群执行行调度异常后退避

- 日期 / 工作目录：2026-08-17 / `/Users/daishuaishuai/IdeaProjects/armada`
- 分支：`1.0.3-group`
- 需求来源：test1 现场观察到同一执行行以每秒一次的频率反复失败刷屏，30 分钟 1595 条
- 状态：已实现、已测试、待部署观察

## 问题

`PullTaskExecutionDispatchCoordinator.process()` 捕获 `RuntimeException` 后调用 `releaseLock`，
只清 `lock_owner` / `lock_expires_at`，**不改 `next_run_at`**。

抢占条件是 `next_run_at <= now`，排序是 `ORDER BY next_run_at ASC, id ASC`。因此失败行保留着
已经过期的旧 `next_run_at`，下一轮不但立刻重新到期，还会排在队首。配合 `fixedDelayMs=1000`，
不可恢复的行就以每秒一次的频率永久重试，并且每一轮都占掉一个 claim 名额。

test1 实例：`pull_task_group_execution` id=244（task 155，`group_jid` 为 NULL）在 30 分钟内
产生 1595 条 `普通拉群执行行调度异常`。该行最终停止不是被修复，而是有人把父任务改成 `PAUSED`，
使其不再满足抢占条件中的父任务 `EXECUTING` 判断。

原日志只记录 `errorType`，不带异常 message，因此现场无法判断失败原因，只能反推。

## 解法

异常路径改用新增的 `releaseLockWithBackoff`：释放租约的同时把 `next_run_at` 推到
`now + retryDelayMs`（现有配置，默认 30000ms，`resourceRecovery.recover` 已在用同一个值）。
错误日志补上 `nextRunAt` 与异常 message。

`releaseLock` 保持原样。它另有 10 个调用方，都是"本轮没活、正常释放"的路径，
推后 `next_run_at` 会拖慢健康行，不能一起改。

**本期不加尝试计数，也不自动置 FAILED。** 该项目里 `IllegalStateException` 绝大多数是
CAS 抢输、租约已变、写入行数不符一类的瞬时并发竞争（"拉手邀请提交状态已变化"、
"群设置执行行唤醒 CAS 失败"、"群级失败终止执行行发生并发变化"等），按次数判死会把
只是抢输一次的行误杀成 `FAILED`（其语义是"链接失效等不可恢复原因"）。
真正的永久失败当前只有 244 一例，且根因未证实。等日志里出现真实 message 后再判断
是否存在永久失败类别，以及是该修成因还是补终态。人工兜底 `ABANDONED` 一直可用。

## 影响模块

- `PullTaskGroupExecutionMapper` / `PullTaskGroupExecutionMapper.xml`：新增 `releaseLockWithBackoff`
- `PullTaskExecutionDispatchCoordinator.process()`：异常分支改用退避释放并补日志

## 数据库变更

无。`next_run_at` 是既有列，未加表、列、索引或迁移。

## API / 前端 / Redis / Kafka 变更

无。

## 关键约束

- 只改异常分支，正常调度、阶段路由、租约、批量和积压统计口径不变
- 退避不改变执行状态，行仍是原状态，只是下次抢占时间推后
- 非租约持有者不得写入：`WHERE id = ? AND lock_owner = ?` 保持与 `releaseLock` 一致

## 验证记录

- `PullTaskExecutionDispatchCoordinatorTest` 10 个通过。新增用例断言异常后调用
  `releaseLockWithBackoff(id, owner, now, now+retryDelayMs)`，且不再调用 `releaseLock`
- `PullTaskGroupExecutionMapperInMemoryTest` 27 个通过。新增用例在真跑的 H2 上钉死：
  非持有者返回 0；持有者释放后 `lock_owner`/`lock_expires_at` 清空、`updated_at` 写入、
  `next_run_at` 推到 30650；`claimDue` 在 700 抢不到、在 30650 能抢回
- `xmllint` 通过
- `PullTask*Test` 全量：除 6 个既有失败外全部通过。这 6 个已用 `git stash` 回到 HEAD 复跑确认
  同方法、同行号、同消息一致复现，属既有问题，不由本次改动引入：
  `PullTaskMapperBusinessConditionTest`（`PullTaskAccountActionMapper.xml` 硬编码状态条件）、
  `PullTaskClosingTransactionServiceTest`、`PullTaskStationSupplementServiceTest` 3 个、
  `PullTaskNormalLinkCollationDbTest`（本机缺外部数据库，环境阻断）

## 回滚方案

改动集中在一个 catch 分支和一条新增 SQL，没有 schema 变更和数据写入语义变化，
直接回滚上一版本镜像即可，不需要数据补偿。
