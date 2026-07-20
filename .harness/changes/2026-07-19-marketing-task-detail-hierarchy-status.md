# 变更记录：营销任务明细层级与群组状态统一

- 日期 / 分支 / worktree: 2026-07-19 / `1.0.1-snapshot` / 主工作树（按用户要求不建 worktree、不提交）
- 需求来源: 用户补充需求第 4、5 条；设计文档 `docs/superpowers/specs/2026-07-19-marketing-task-detail-hierarchy-status-design.md`；实施计划 `docs/superpowers/plans/2026-07-19-marketing-task-detail-hierarchy-status.md`
- 状态: 协议、后端纯单测、前端实现完成；待后端真库验证与本地复核

## 目标（一句话）

统一普通营销任务详情的账号—群组两级字段、实际发送次数、群组状态和最后有效发送结果。

## 缺口拆解 / 任务清单

- [x] 对账前端现有账号—群组明细结构。
- [x] 对账后端发送记录聚合、次数与最后发送时间口径。
- [x] 对账协议群可发送状态和发送失败回执。
- [x] 确认最后发送成功无条件显示群组状态“正常”。
- [x] 完成跨仓方案设计。
- [x] 用户评审方案设计。
- [x] 编写实施计划并拆分协议层、后端、前端任务。
- [x] 按 TDD 完成协议稳定失败原因码实现与验证。
- [x] 按 TDD 完成后端实时登录态、同源有效尝试归一化和详情 API 纯单测。
- [x] 按 TDD 完成前端固定两级字段与六态展示。
- [ ] 确认 `armada-api/.env` 目标后完成真库 DbTest 与最终跨仓复核。

## 关键设计决策

- 采用“协议层输出稳定原因码、后端统一归一化、前端只展示”的方案。
- 实际发送次数只累计成功回执，失败、跳过和待回执不计数。
- 最后有效发送记录按 `round_no DESC, attempt_no DESC, id DESC` 从成功/失败记录中选择。
- `executionResult`、`executionReason` 和 `groupStatus` 必须从同一发送记录派生。
- 最后有效发送成功时群组状态无条件为 `NORMAL`。
- 统一页面状态为 `NORMAL / ACCOUNT_BANNED / GROUP_BANNED / NO_PERMISSION / KICKED_OUT / UNCONFIRMED`。
- 保留 `UNCONFIRMED` 处理无法可靠判断的协议异常。
- 不新增数据库表或列。
- 当前分支已有后端提交 `7dcf4ca` 和前端提交 `35ae2b6d` 的执行结果第一阶段实现；本次设计提交不改写其代码，实施时在其基础上按新方案修订。
- 账号登录态通过 Account 域 `AccountService` 一次批量读取；营销域不依赖 Account Mapper 或实体。
- 未新增登录态 VO，Account 域 Mapper 复用同域 `AccountState` 做最小投影，避免违反新 VO 必须使用 record 的规范。

## 验证（evidence-before-done）

- 协议层聚焦 Jest：3 个 suite、39 个 test 全部通过；`npm run lint`、`npm run build` 通过。
- 后端聚焦单测：41 个 test 全部通过，0 失败、0 跳过。
- 前端聚焦 Node 测试：8 个 test 全部通过；定向 ESLint/Prettier、`tsc`、`vue-tsc`、Vite 生产构建通过。
- 三仓 `git diff --check` 已通过。
- 真库 DbTest 尚未运行：已确认 `.env` 文件存在，等待用户确认其目标为获准的本地/专用测试库。

## 部署

- commit: 按用户要求不提交，保留三仓未提交差异供本地复核。
- 环境 / 部署后验证结果: 未部署。

## 遗留 / 跟进

- 保留前端提交 `35ae2b6d` 已有的详情轮询行为，本需求不调整其交互策略。
- 待真库覆盖状态 0/3 不覆盖最后有效结果、成功计数/最后成功时间、已软删账号历史和租户隔离。
