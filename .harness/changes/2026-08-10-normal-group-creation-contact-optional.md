# 变更记录：新建普群加好友改为可选前置动作

- 日期 / 分支 / worktree: 2026-08-10 / `1.0.3-snapshot` / `/home/yanwenchao/ideaProject/armada`
- 需求来源: 用户原话「现在建群没加成好友就没法建群了，这个逻辑我想去掉，加好友是可选项，加成了就加，加不成就不加，不要影响后边的建群」；追加要求「失败的明细要保留」
- 状态: 已完成（待部署）

## 目标（一句话）

新建普群的 `CONTACT_PREPARE`（双向加好友）从建群硬前置条件降级为尽力而为的可选动作：
加成功记成功、加失败记失败，两个方向都拿到最终回执后照常下发 `GROUP_CREATE`；
失败明细逐方向完整保留并在任务详情接口可查。

## 缺口拆解 / 任务清单

### 一、加好友不再阻断建群

- [x] `countIncompleteContactDirections` → `countPendingContactDirections`：从「统计未成功方向」改为「统计未回执方向」，`FAILED`/`UNKNOWN` 视为已落定。
- [x] `startGroupCreate` 的 `NOT EXISTS` 守卫从 `status <> 'SUCCESS'` 改为 `status = 'PENDING'`。
- [x] `NormalGroupCreationProtocolResultService`：`CONTACT_PREPARE` 结果不再走 `applyFailure`，改走新的 `contactSettled`，成功与失败共用一条落定路径。
- [x] 删除 `failProtocolAction` 中已不可达的 `PREPARING_CONTACTS` 分支，并从 `applyFailure` 去掉 `member` 参数与成员分支。
- [x] 同步 `docs/business/group-list-create-normal-group-development-design.md` 与 `normal-group-creation/summary.md` 中「任一联系人方向未成功不得建群」的旧口径。

### 二、失败明细保留（V110）

- [x] **修数据丢失 bug**：两个联系人方向原先共用 `normal_group_creation_item_member.last_error_code/last_error_message`，`applyContactResult` 无条件覆写。加好友失败不再终止明细后，对向方向的成功回执会把已记录的失败原因写成 NULL。V110 按方向拆出 `creator_save_error_code/message` 与 `member_save_error_code/message`，`applyContactResult` 只写自己方向那一对。
- [x] `normal_group_creation_item.contact_prepare_failed`：在 `startGroupCreate` 里按成员方向状态一次性算出，标记「本群存在未成功的加好友方向」，透出到 `NormalGroupCreationItemVO.contactPrepareFailed`。
- [x] 详情接口新增 `contactFailures`：新 VO `NormalGroupCreationContactFailureVO` + mapper `selectContactFailures`，只返回存在 `FAILED`/`UNKNOWN` 方向的成员，错误消息复用 `NormalGroupCreationErrorMessage.resolve` 本地化。
- [x] `replaceMember` 改为清理四个新错误列。
- [x] V110 回填：历史 `last_error_*` 按方向状态复制到新列，并给已过 `PREPARING_CONTACTS` 阶段的明细补 `contact_prepare_failed`。

## 关键设计决策

- **成员名单不受加好友结果影响**：`GROUP_CREATE` 的 `participants` 仍是全部冻结成员。
  被否决方案：只把加好友成功的成员放进 participants —— 会让实际群人数与任务计划人数不一致，
  且用户明确确认「照常拉进群」。
- **`UNKNOWN` 与 `FAILED` 同等对待**：都算已落定、都不重试、都不阻断建群。理由是加好友已不是
  正确性前置条件，为它保留一个「结果未知」的悬挂终态只会卡住整条计划群。
- **失败仍写回成员行并保留账号离线收敛**：方向级失败原因记录在按方向拆分的
  `creator_save_error_*` / `member_save_error_*` 上；`ACCOUNT_NOT_ONLINE` 仍触发
  `AccountStateEventService.applyStateChanged` 把实际 actor 收敛为 `OFFLINE`。
  计划群的 `status` / `last_error_code` 不再被加好友失败污染，改用独立的
  `contact_prepare_failed` 标记表达「群建成功但加好友没全成」。
- **`contact_prepare_failed` 在 `startGroupCreate` 里算，而不是每次方向落定都写一遍**：
  该语句是 `PREPARING_CONTACTS -> CREATING_GROUP` 的唯一入口且带条件更新保护，
  在这里按成员方向状态一次算出既省一次往返，也保证标记与最终方向状态一致。
- **`contactFailures` 只返回有失败方向的成员，不返回全量成员**：单任务成员快照上限 1 万行，
  详情接口是 2～3 秒轮询的，全量返回会把正常任务的响应也撑大；运营要的是「哪些成员没加上」。
- **不 DROP 旧的 `last_error_code/last_error_message`**：这两列已无写入方，但在共享库上做
  不可逆的列删除只为消除两个空列不值当，且会破坏回滚到旧 jar 的能力。留到后续清理迁移。
- **成员手机号不进出参**：`contactFailures` 只给 `memberAccountId`，与 `items` 只给
  `creatorAccountId` 的口径一致，不把 WhatsApp 号码复制进 API 响应。
- **保留联系人重试与成员替换通道**：`NormalGroupCreationServiceImpl.retry` 在
  `PREPARING_CONTACTS` 阶段的 `enqueueFailedContactPrepare` / `replaceMember` 不删除，
  用于恢复本次变更前已卡在 `FAILED + PREPARING_CONTACTS` 的历史明细。
  `NormalGroupCreationErrorMessage` 里 `PREPARING_CONTACTS` 的中文提示同理保留。

## 验证（evidence-before-done）

```
cd armada-api && mvn -o -Dtest='NormalGroupCreation*,ProtocolNormalGroupCreationEventConsumerTest' test
Tests run: 69, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

先红后绿：改产品代码前先改测试，第一轮报 `cannot find symbol:
countPendingContactDirections`；第二轮报 `NormalGroupCreationItemVO` 构造参数数量不匹配。

H2 内存库覆盖（真实 Mapper XML + MyBatis-Plus 租户插件 + Spring 事务）：

- `protocolFlowAdvancesAfterBothContactDirectionsSettleAndMatchingCommandIds`：仍有 `PENDING`
  方向时 `startGroupCreate` 返回 0，方向落定后返回 1。
- `failedOrUnknownContactDirectionsNoLongerBlockGroupCreate`：`FAILED` + `UNKNOWN` 方向不再
  阻断 `startGroupCreate`，`current_step` 推进到 `CREATING_GROUP`，`contact_prepare_failed=1`。
- `everyDirectionSucceedingLeavesTheContactFailureFlagClear`：全成功时标记为 0、
  `selectContactFailures` 为空。
- `contactFailureReasonsAreKeptPerDirectionAndNotClearedByTheOtherDirection`：
  creator 方向失败后 member 方向成功，creator 的原因码/摘要仍在，`selectContactFailures`
  能读回该条并带上本地化中文原因。**这条直接锁住本次修掉的覆写 bug。**
- `failedContactRetryCanReplaceUnavailableMemberAndFenceOldResults`：`replaceMember`
  清空四个新错误列。

服务层覆盖：`contactPrepare_lastDirectionFailureStillEnqueuesCreate`、
`contactPrepare_unknownOutcomeIsSettledAndNeverBlocksCreate`、
`contactPrepare_memberOfflinePersistsMemberFailureWithoutFailingTheItem`、
`contactPrepare_creatorOfflinePersistsCreatorFailureWithoutFailingTheItem`、
`detailExposesRetainedContactFailuresAlongsideItems`。

Flyway 脚本契约：`NormalGroupCreationContactFailureMigrationSqlTest` 校验五个 ADD COLUMN
都带 `information_schema` 幂等判定，以及三段回填不覆盖已有新列。

## 数据库变更

`V110__normal_group_creation_contact_failure_detail.sql`：

- `normal_group_creation_item_member` 新增 `creator_save_error_code(64)` /
  `creator_save_error_message(512)` / `member_save_error_code(64)` /
  `member_save_error_message(512)`。
- `normal_group_creation_item` 新增 `contact_prepare_failed TINYINT NOT NULL DEFAULT 0`。
- 回填：历史 `last_error_*` 按方向状态复制到新列；已过 `PREPARING_CONTACTS` 的明细补标记。
  回填只能按方向状态推断历史共用列属于哪个方向，无法百分之百还原「两个方向都失败」的场景，
  这类历史行两列会拿到同一个原因，可接受。
- 全部 ADD COLUMN 走 `information_schema` 幂等判定，可重复执行。
- 未删除任何列；`last_error_code/last_error_message` 保留但已无写入方。

## API 变更

无新增或删除接口，`GET /api/normal-group-creation-tasks/{taskId}` 出参扩展：

- 新增 `contactFailures[]`：`itemId` / `itemNo` / `memberAccountId` /
  `memberProtocolBackend` / `creatorSavedMemberStatus` / `creatorSaveErrorCode` /
  `creatorSaveErrorMessage` / `memberSavedCreatorStatus` / `memberSaveErrorCode` /
  `memberSaveErrorMessage`，只含有失败方向的成员，全成功时为空数组。
- `items[]` 新增 `contactPrepareFailed`。
- 行为差异：因加好友失败而出现的 `FAILED + current_step=PREPARING_CONTACTS` 明细不再产生；
  新明细会继续走到 `CREATING_GROUP` 并按建群自身结果收敛，加好友失败改由上面两个字段表达。
- 前端需要跟：详情抽屉里把 `contactPrepareFailed` 显示成一个提示，并用 `contactFailures`
  渲染「哪些成员没加上好友」。

## 部署

- commit / 环境 / 部署后验证结果: 待填

## 遗留 / 跟进

- `normal_group_creation_item_member.last_error_code/last_error_message` 已无写入方，
  等新代码在生产稳定、确认不需要回滚到旧 jar 后，用一个独立清理迁移 DROP 掉。
- 历史卡在 `FAILED + PREPARING_CONTACTS` 的明细仍需人工点重试恢复；这批数据清空后，
  `retryInTransaction` 的 `PREPARING_CONTACTS` 分支和 `NormalGroupCreationErrorMessage`
  对应提示可以一并删除。
- `contactFailures` 目前不分页。单任务成员快照上限 1 万行，极端情况下全部方向失败会返回
  1 万条；若真出现这种量级，说明账号池整体不可用，应该先看账号在线率而不是翻这个列表。
- 加好友失败率没有单独打点。`docs/business/...design.md` 第 12 节列过「双向联系人准备失败率
  及按协议类型分布」，现在这个指标比改动前更重要（失败不再体现为任务失败），值得补监控。
