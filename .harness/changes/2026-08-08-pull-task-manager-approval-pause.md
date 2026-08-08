# 变更记录：普通拉群管理员等待审批时暂停单群

- 日期 / 分支 / worktree：2026-08-08 / `1.0.2-snapshot` / `armada` 主工作树
- 需求来源：用户确认“管理员等待审批时停止该群拉群并反馈明确信息；链接/群明确失败时终止该群；Android 协议成员查询不改”。
- 设计：`docs/superpowers/specs/2026-08-08-pull-task-manager-approval-pause-design.md`
- 实施计划：`docs/superpowers/plans/2026-08-08-pull-task-manager-approval-pause-implementation.md`
- 状态：已实施，保留本地工作区等待复核；未提交、未部署。

## 目标（一句话）

将管理员踩链接返回的 `PENDING_APPROVAL` 收敛为单群暂停，而不是未知结果重试；将原因明确展示给操作者。

## 缺口拆解 / 任务清单

- [x] 后端把 `PENDING_APPROVAL` 映射为 `WAIT_RESOURCE + APPROVAL + next_run_at=0`。
- [x] 管理员动作和在群事实新增等待审批状态；调度 Mapper 只领取三种可恢复资源等待，跳过该行。
- [x] 新鲜 `JOINED` 不再由 Armada 重复查询群成员；仅结果不确定且有已知 JID 时查询。
- [x] 补充管理员踩链接的等待审批也收敛为同一单群暂停状态。
- [x] 前端执行列表显示“等待入群审批”，详情显示稳定原因文案。
- [x] 验证 Web/Android 协议事件无需改动，运行聚焦回归。

## 关键设计决策

- Android 的 `InviteCode` 成功不等于已入群；其 `GroupParticipants` 查询保留，用于区分已入群与等待审批。
- Web 的 `JOINED + groupJid` 是正常成功反馈，不再被后端完整成员查询降级为未知。
- `PENDING_APPROVAL` 不是账号资源不足，因此使用新的 `wait_resource_type=APPROVAL` 与既有原因码，而不更换管理员。
- 群链接无效、撤销或群不可用才终止该群；账号离线、重新认证、触达限制、限流和超时不终止该群。
- 本次不做获批后的自动唤醒或后台轮询：Web 待审批结果可能没有群 JID，自动重试会重复提交申请。
- 不新增表、列、Flyway、Kafka Topic 或 API 字段；任务详情已有原因字段可复用。

## 验证（evidence-before-done）

- 通过：`mvn -q -Dtest=PullTaskManagerJoinResultServiceImplTest,PullTaskManagerJoinProcessorTest,PullTaskManagerJoinTransactionServiceTest,PullTaskResourceRecoveryTransactionIntegrationTest,PullTaskExecutionDispatchCoordinatorTest,PullTaskSupplementManagerProcessorTest,PullTaskSupplementManagerTransactionIntegrationTest test`。
- 通过：Web 协议 `group-join-executor.test.ts`，41 项测试通过；Android
  `TestZhuanGroupJoinSender(UsesExactWSPhoneAndConfirmsMembership|ReturnsPendingWhenSelfIsAbsent)` 通过。
- 通过：前端状态函数先红后绿的编译模块断言，以及前端/后端/协议层 `git diff --check`。
- 已知项目基线问题：`pnpm typecheck` 被既有 `CommonGroupCreate.test.ts` 的
  `allowImportingTsExtensions` 配置错误阻断；增加该编译选项后，多个既有测试文件出现
  未使用 `@ts-expect-error`。本次前端文件未出现在这些错误中。
- 数据库行为变更仅为既有 `TINYINT` 枚举值与现有 Mapper 状态转换；使用 H2 MySQL 模式与真实 Mapper XML 验证，不连接真实数据库。

## 部署

- 未提交、未部署、未连接远程或真实数据库。

## 遗留 / 跟进

- 若业务要求审批通过后自动继续，需要单独设计跨 Web/Android 的审批获批证据和安全唤醒机制。
