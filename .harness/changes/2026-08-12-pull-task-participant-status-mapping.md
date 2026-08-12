# 变更记录：普通链接拉群逐成员状态映射

- 日期 / 分支 / worktree: 2026-08-12 / 1.0.3-snapshot / 主工作区
- 需求来源: 用户要求将 WhatsApp 200/403/408/409/419 逐成员状态传给 Armada，展示真实入群原因
- 状态: 已完成

## 目标（一句话）

协议层完整归一化逐成员状态，Armada 持久化原因并对群满及时终止后续拉人调用。

## 缺口拆解 / 任务清单

- [x] 协议层补齐 200/403/408/409/419 稳定原因码和展示文案
- [x] 409 在 ADD 动作中按幂等成功收口
- [x] Armada 沿用既有原因字段持久化逐成员结果
- [x] Armada 收到 GROUP_FULL 时终止该群后续拉人调用
- [x] 完成协议层和后端回归验证

## 关键设计决策

- 不新增数据库字段；现有 `pull_reason_code/pull_reason_message` 和逐成员查询接口已覆盖展示需求。
- 408 是逐成员超时，不再误映射为拉手账号主动触达受限。
- 419 保留 `GROUP_FULL` 逐成员原因，执行行仍复用 `GROUP_UNAVAILABLE` 群级终止语义。

## 验证（evidence-before-done）

- `npm test -- --runInBand`: 65 suites、604 tests 全部通过。
- `npm run lint`: TypeScript `tsc --noEmit` 通过。
- `mvn -Dtest=ProtocolGroupEventConsumerTest,ProtocolPullTaskBatchParticipantResultAdapterTest,PullTaskPullCallParticipantResultServiceTest,PullTaskGroupExecutionFailureServiceTest,PullTaskMaterialMemberMapperInMemoryTest test`: 70 tests 全部通过，BUILD SUCCESS。
- `git diff --check`: 协议层与 Armada 仓库均通过。

## 部署

- commit / 环境 / 部署后验证结果: 未提交、未部署

## 遗留 / 跟进

- 部署 test1 后用新任务验证逐成员结果列表中的五种原因展示。
