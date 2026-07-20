# 变更记录：账号动态营销新群即时发送

- 日期 / 分支 / worktree: 2026-07-20 / `1.0.1-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户本次需求；设计文档 `docs/superpowers/specs/2026-07-20-account-dynamic-new-group-immediate-marketing-design.md`
- 状态: 进行中（设计已确认，等待书面规格复核）

## 目标（一句话）

发送中的 `ACCOUNT_DYNAMIC` 普通营销任务在账号新加入群组时立即补发一次，并保持任务原有轮次、时间和协议路由不变。

## 缺口拆解 / 任务清单

- [x] 对账 Web、Android Zhuan 群变化上报链路。
- [x] 对账 Armada membership、动态目标解析、attempt 和 outbox 链路。
- [x] 确认按任务 + 账号 + 群 JID 幂等以及多账号分别触发。
- [x] 完成轻量方案设计。
- [ ] 用户复核书面设计。
- [ ] 编写实施计划。
- [ ] TDD 实现群快照差量、即时 attempt/outbox 和一次业务重试。
- [ ] 运行单元测试、真库 DbTest 和 Web/Zhuan 端到端验收。
- [ ] 完成后端专家评审和部署验证。

## 关键设计决策

- 不扫描全部任务或 membership，不新增即时发送任务表和专用调度器。
- 在现有 `account.groups_reported` 事务中计算账号群差量，只处理真新增群。
- 首次 baseline 不触发即时营销，避免把上控前已有群当成新增群。
- 使用现有 `marketing_task_send_attempt`，保留 `round_no=0` 表示新群即时发送；正常轮次保持 `round_no>=1`。
- 复用现有 target + round + group 唯一键承担任务 + 账号 + 群 JID 幂等，不修改唯一索引。
- 业务重试复用同一 attempt 行，将 `attempt_no` 从 1 更新到 2，并以 commandId 条件拦截迟到结果。
- 复用 `MessageSendPort`、`protocol_command_outbox` 和 afterCommit dispatcher；Web、Android Zhuan 协议层均不改。
- 即时发送不更新任务 `current_round_no`、`next_round_at` 等正常轮次字段。

否决方案：

- 周期扫描 `joined_at`：持续全局扫描、需要游标且并发幂等更复杂。
- 独立即时任务表：增加表、状态机、worker 和恢复链路，本期现有 attempt/outbox 足够。

## 验证（evidence-before-done）

- 设计自检：`git diff --cached --check` 通过，无空白错误。
- 占位符扫描：设计文档无 `TBD`、`TODO`、`FIXME` 或待定项。
- 代码和 DbTest 尚未开始；不得声明功能已完成。

## 部署

- commit / 环境 / 部署后验证结果: 尚未实施、尚未部署。

## 遗留 / 跟进

- 书面设计经用户复核后，进入 implementation plan；未获得复核前不修改业务代码。
