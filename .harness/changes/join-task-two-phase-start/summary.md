# 变更：进群任务「启动」两段式（从 wt2/2.0.0-snapshot 同步到 wt3）

## 变更概述
对齐菜单需求「启动 P0：点击后任务启动执行，针对待启动任务」。该功能在 wt2/2.0.0-snapshot 已全栈实现，
而 wt3 此前是**相反的自动启动**（create 直接 RUNNING + 自动起 worker，且测试锁死"无启动按钮"）。
用户拍板：**把 wt2 两段式同步到 wt3**（而非保留自动启动）。本次以 wt2 实现为蓝本手工移植到 wt3
当前文件（不能 cherry-pick——wt3 这几个文件本会话已改过批量删除/列设置/详情，会冲突）。

两段式语义：保存只建「待启动」(DRAFT) 任务、不执行 → 用户点行内「启动」→ `start()` 转 RUNNING + 触发 worker。

## 影响模块
- 后端：`wheel-api-tenant`（TenantJoinTaskService.create 改 DRAFT + 去自动起 worker、新增 start()；Controller 新增 /start）
- 前端：`wheel-saas-web`（constants.ts DRAFT 标签、join-task.ts startJoinTask、JoinTaskList.vue 启动按钮+状态筛选+startTask）

## 数据库变更
无。`join_task.status` 既有列，DRAFT/RUNNING 均既有取值。

## API 变更
- 新增 `POST /api/tenant/join-tasks/{id}/start`（权限 `tenant:pull_task:edit`），把待启动任务转 RUNNING + 触发执行。
- `POST /api/tenant/join-tasks`（create）**行为变更**：建任务状态由 RUNNING → **DRAFT（待启动），不再自动执行**。

## 关键约束
- 仅 DRAFT 可启动；非 DRAFT（已启动/已结束）抛 VALIDATION；任务不存在抛 RESOURCE_NOT_FOUND。
- worker 触发经 `AfterCommitExecutor` 等事务提交后再起（worker 独立连接，事务内直起读不到 RUNNING → NOT_RUNNING 空跑）。
- 计划行在 create 时已落库，start 只翻状态 + 起 worker。
- 前端：DRAFT 行显「启动」按钮（其余状态无）；状态筛选加「待启动」；DRAFT 标签由「草稿」改「待启动」。

## 测试
- 后端 `TenantJoinTaskServiceTest`：30（create_doesNotTriggerWorker 替代旧"create 触发 worker"、create_setsStatusDraft、+3 start：转态+触发/非DRAFT拒/不存在）；tenant 全模块 560 绿 BUILD SUCCESS
- 前端 `JoinTaskList.test.ts`：23（+2：待启动显示启动按钮并点击调 startJoinTask+重拉、非待启动无按钮）；前端全量 475 绿；vue-tsc clean

## 回滚方案
纯逻辑改动无 DB 迁移。`git revert` 即可。注意：与 wt2 是同一功能，最终并入 2.0.0-snapshot 时
create 的 DRAFT vs（若回滚）RUNNING 会与 wt2 冲突，须以两段式（DRAFT）为准收口。
