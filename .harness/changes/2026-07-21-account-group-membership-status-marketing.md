# 变更记录：账号群关系状态保留与营销跳过

- 日期 / 分支 / 工作区: 2026-07-21 / `1.0.1-snapshot` / 当前工作区
- 需求来源: 用户要求保留被踢/主动退出群状态，不发送但在创建任务和营销明细中展示；设计见 `docs/superpowers/specs/2026-07-21-account-group-membership-status-marketing-design.md`
- 状态: 方案已确认，分项目实施计划已编写，尚未开始业务代码实现

## 目标（一句话）

把账号群关系从存在/软删改成显式当前状态，并让营销执行对退出群生成 SKIPPED 明细而不调用协议。

## 缺口拆解 / 任务清单

- [x] 完成现状代码、性能环境日志和 Android 群事件链路排查。
- [x] 与用户确认 IN_GROUP、UNCONFIRMED、KICKED_OUT、LEFT、NOT_IN_GROUP 状态口径。
- [x] 与用户确认所有状态均在创建任务时展示并允许勾选。
- [x] 与用户确认执行前读本地表，不可发送状态写 SKIPPED，跳过不计失败。
- [x] 完成跨 Android 协议、Armada 后端和 Vue 前端的设计文档。
- [x] 用户复核书面设计。
- [x] 编写 Android、Armada、Vue 三个分项目实施计划和总发布计划。
- [ ] 按 TDD 实现与验证。
- [ ] 确认测试环境后完成端到端联调。

## 关键设计决策

- 当前关系保留在 `account_group_membership`，状态代替退出时软删；全局 `group_link` 不变。
- UNCONFIRMED 允许发送；KICKED_OUT、LEFT、NOT_IN_GROUP 生成 SKIPPED 且不调用协议。
- 创建任务展示并允许选择所有状态，发送拦截只在运行时执行。
- 精确 remove/leave 事件独立发布，完整群快照负责未知原因退出和重新入群校准。
- 快照缺失不覆盖已确认的被踢/主动退出原因；更新的快照重新出现才恢复在群。
- 不完整快照不更新缺失关系，查询失败不伪造空快照。
- 快照完整性字段缺失时按账号 `protocol_id` 兼容：Web/Baileys 沿用旧完整快照语义，Android
  按不完整处理；显式 false 或有跳过群始终按不完整处理。
- 营销明细以已解析目标群和 attempt 的并集保留群，分离当前关系状态和最后执行结果，SKIPPED
  单独计数且不计失败。
- 当前范围不包含 Baileys `armada-protocol`。

被否决方案：

- 继续只软删：不能区分退出原因，也无法在选择列表和明细保留当前状态。
- 软删加 exit_reason：重新进群后的当前行选择和乱序处理更复杂。
- 独立完整状态历史表：当前需求已有发送尝试历史，只需可靠当前关系，双写成本过高。

## 验证（evidence-before-done）

- 当前完成方案设计和实施计划；实现验证尚未开始。
- 实施计划见 `docs/superpowers/plans/2026-07-22-account-group-membership-marketing-rollout.md`。
- 数据实现阶段必须执行真库 DbTest；Android 协议必须执行 Go 静态检查、编译、单测和定向 race；前端必须执行类型检查、测试和构建。

## 部署

- commit / 环境 / 部署后验证结果: 仅方案文档，未部署。

## 遗留 / 跟进

- 按 Android 协议 → Armada 后端 → Vue 前端的子计划顺序执行；每个仓库独立 worktree 和提交。
- 远程数据库、部署和真实端到端测试前必须再次确认目标环境。
