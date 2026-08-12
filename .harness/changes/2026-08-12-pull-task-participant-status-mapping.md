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

## 2026-08-12 未知结果不卡波次补充

- 协议层新增 `GROUP_PARTICIPANTS_TIMEOUT_MS`，默认 12000，只作用于
  `groupParticipantsUpdate`；建群、保存联系人、邀请链接进群等继续使用原有超时。
- Baileys 原生调用超时后继续持有账号群操作锁直到真实 Promise 完成，避免下一波重试与旧调用重叠。
- Armada 明确收到 `UNKNOWN/UNCERTAIN` 时立即释放参与者，不再发起群成员名单查询。
- Armada 对完全未回传的逐成员结果保留 15000ms 窗口，窗口结束后标记结果未知并释放；
  未知不增加明确失败次数。
- 所有已释放的 UNKNOWN（NOT_STARTED/UNCERTAIN）都进入后继 RETRY 波次，迟到成功仍可提升事实。
- 重试波首批提交时间不早于当前时间及上一批实际提交时间加页面冻结的拉人间隔。
