# 变更记录：一次性建群账号终态预留释放 + 批量抢登按账号隔离

- 日期 / 分支 / worktree: 2026-10-08 / `1.0.3-snapshot` / 主仓 `/Users/daishuaishuai/IdeaProjects/armada`（用户 2026-10-09 明确指定）
- 需求来源: 第二套环境（perf2，库 `armada_perf`）账号列表对 3 个「被抢登」账号一键抢登，提示「账号已被一次性建群任务预留或进入永久注销流程」，整批失败；列表显示「未受限」、无占用任务。
- 关联分析: [2026-10-07 影响审计](./2026-10-07-creator-deletion-impact-audit.md) → [impact-analysis.md](../../docs/operations/evidence/creator-deletion-impact-perf2-20261007/impact-analysis.md) 的 **F01**（结束任务后预留没有释放）、**F15**（掉线 creator 无恢复入口）、第 7 节安全释放判据、第 8 节 P1-A。本方案就是 P1-A 中「终态 R 收口」的落地，范围更窄。
- 状态: Part A + B + Part C 进度标签已实施；定向 342 项通过；全量本地 5534 项仍有 11 failures / 24 errors（详见验证）；未部署

## 目标（一句话）

拉群执行进入终态、且注销从未提交的一次性建群账号，自动释放预留，账号恢复成普通账号；批量一键抢登遇到受限账号时只让该账号失败，其余继续。

## 现场事实（2026-10-08 只读查询 armada_perf）

本次报错只由账号 **2794**（918943736217）触发；2792/2793 未被预留，是被 `takeoverBatch` 单事务一起回滚的。

| 项 | 值 |
|---|---|
| account_creator_deletion | account_id=2794, task 82, execution 324, `lifecycle=RESERVED`, `operation_id=NULL` |
| pull_task 82 | `ENDED`, `is_creator_delete_after_takeover=1` |
| execution 324 | `execution_status=6`(ABANDONED), stage 9 GROUP_CREATE, `group_jid` 已有，reason `GROUP_CREATOR_UNAVAILABLE` |
| pull_task_creator_deletion | status 0(RESERVED), submitted_at NULL, attempts 0 |

全库共 5 条 RESERVED，**全部挂在已终态任务上**，全部满足「未提交」：

| account_id | task / execution | 任务状态 | 执行 reason | ptcd | in-flight outbox | 未释放 role4 | 备注 |
|---|---|---|---|---|---|---|---|
| 2755 | 64 / 303 | ENDED | GROUP_CREATE_FAILED | 0 | 0 | 0 | 账号已软删 |
| 2757 | 65 / 305 | ENDED | GROUP_PROFILE_VERIFICATION_FAILED | 0 | 0 | 1 | |
| 2770 | 71 / 313 | COMPLETED | — | 0 | 0 | 1 | 父任务 COMPLETED、执行 ABANDONED |
| 2776 | 80 / 322 | ENDED | MANAGER_ADMIN_ACTOR_UNAVAILABLE | 0 | 0 | 2 | 只释放本执行的 role4 行，其他行先核对再说 |
| 2794 | 82 / 324 | ENDED | GROUP_CREATOR_UNAVAILABLE | 0 | 0 | 1 | 本次报错账号，当前「被抢登」 |

查询 SQL 见文末附录。DB 连接信息由用户另行提供，**不得写入仓库、日志或 change 记录**。

## 根因

1. `account_creator_deletion` 只有 `RESERVED → DELETING → DELETED`，没有释放入口（[AccountCreatorDeletionMapper.xml](../../armada-api/src/main/resources/mapper/account/AccountCreatorDeletionMapper.xml)）。
2. 任务结束（`PullTaskStandardLifecycleServiceImpl#endTask`）、单执行结束（`PullTaskStandardExecutionLifecycleServiceImpl#end`）、失败终态等路径只释放拉手，不收口 creator 预留。
3. 公共门禁 `creatorDeletionCommandBlocked`（[ProtocolCommandOutboxMapper.xml:419](../../armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml)，`AccountMapper.xml` 同名、`ProtocolCommandPublisher` 派发前复检）对「有行」一律拦截，只放行同 task+execution 的命令。账号列表的上线/抢登不带 `pullTaskId`，因此永久被拒。
4. `takeoverBatch` 是单个 `@Transactional`，一个账号被拒就整批回滚；返回的错误也不带账号标识。

## 开工前置（必须）

- armada 主 checkout 当前有**其他会话的未提交改动**，正好覆盖相关文件：`AccountOnlineCommandServiceImpl`、`AccountBatchLifecycleServiceImpl`、`ProtocolCommandOutboxServiceImpl`（`ProtocolAccountCommandRejectedException` 按账号收集）、`AccountTakeoverAutoReonlineSideEffect`、`AccountStateMapper` 等；前端 `account-batch-operation.ts` 和 `useAccountListPage.ts` 也有。**不得 stash、reset 或覆盖这些改动。**
- 第一步先跑 `git status` 和 `git diff --stat`。如果上述 diff 仍未提交，**停下来问用户**：是等它合入后再开 worktree，还是在同一工作区叠加。Part B 依赖其中的 `ProtocolAccountCommandRejectedException` 按账号收集逻辑。
- 先读 `armada/AGENTS.md`、`.harness/rules/编码规范.md`、`工程结构.md`、`数据模型规范.md`、`.harness/wiki/数据模型.md`。

---

## Part A（P1，核心）：终态未提交预留释放

### A1. 释放判据（全部在锁内二次读取后成立才释放）

- 执行行 `execution_status IN (COMPLETED 4, FAILED 5, ABANDONED 6)`。暂停、WAIT_RESOURCE 等可恢复状态**不释放**。
- `account_creator_deletion`：`lifecycle='RESERVED'`、`operation_id IS NULL`，且 tenant/task/execution 与执行行一致。
- `pull_task_creator_deletion`：同执行行存在，且 `status=0`(RESERVED)、`submitted_at IS NULL`。行缺失视为数据不一致：不释放，打 warn。
- 该 creator 的 `protocol_account_id` 在本 task/execution 下没有处于「派发中/结果未知」的 outbox 行（状态码以 `ProtocolCommandOutbox` 枚举为准）。有的话本轮跳过，等补扫重试。
- 任一条不满足（DELETING/DELETED，ptcd 为 SUBMITTED/ACCEPTED/UNKNOWN/FAILED/COMPLETE）就**永久保留保护**，不得 TTL 解锁。这一条与审计报告第 7 节一致。

> 安全性依据：注销 POST 之前必须先经过 `claimSubmission`，它是 `status=0 → 1` 的 CAS（`PullTaskCreatorDeletionMapper.xml` `claimSubmission`）。所以 `ptcd.status=0` 说明从没有发出过注销请求，Android 端也不会有 taskBinding 记录。释放事务先把 ptcd 从 0 改走，之后并发的 claim 一定失败。

### A2. 存储设计（推荐：迁出到历史表，主表删除）

- 新建 Flyway（取实施时下一个空闲版本号，当前最大是 V215）：`account_creator_deletion_release`。列与 `account_creator_deletion` 一致，外加 `released_at BIGINT NOT NULL`、`release_reason VARCHAR(64) NOT NULL`、`execution_status_at_release TINYINT`。不设唯一键（同一账号可以多次预留、多次释放），加索引 `(account_id)`、`(tenant_id, group_execution_id)`。迁移脚本写成幂等（`CREATE TABLE IF NOT EXISTS`），风格对齐 V211。
- 释放 = 在同一事务里 `INSERT ... SELECT` 写入历史表，然后 `DELETE FROM account_creator_deletion WHERE account_id=? AND lifecycle='RESERVED' AND operation_id IS NULL AND tenant_id=? AND group_execution_id=?`，影响行数必须为 1，否则抛错回滚。
- 选这个方案的理由：
  - 主表现有约 20 处「有行即拦截」的读点完全不用改（AccountMapper、AccountStateMapper、AccountMutualContactMapper、PullTaskGroupAccountMapper、ProtocolCommandOutboxMapper、AccountCreatorDeletionMapper），回归面最小。
  - `account_id` 主键和 `identity_hash` 唯一键自然空出来，释放后的账号可以被新任务重新预留，`reserve()` 不用改。
  - 回滚到旧版本后，已释放账号仍然可用，这是正确的，因为它们是按判据安全释放的；仍在 R/D 的账号旧版本继续保护。
- **否决方案**：主表新增 `lifecycle='RELEASED'`。每个读点都得补 `lifecycle<>'RELEASED'`，漏一处就仍然拦截；而且 PK 和身份唯一键被占住，`reserve()` 得改成复用行的 UPDATE。改动面大，容易漏。

### A3. 释放时的联动

1. `pull_task_creator_deletion`：条件更新 `status 0 → 6`（新增枚举 `RELEASED(6)`，注释写「执行终态且注销未提交，预留已释放；不代表账号曾被注销」），`reason_code='CREATOR_RESERVATION_RELEASED'`，`reason_message='执行已结束且未提交注销，建群账号预留已释放'`，并写 `updated_at`。WHERE 带 `status=0`。
2. `pull_task_group_account`：只处理本 tenant/task/execution、本 account、`role_type=4`、`released_at IS NULL` 的那一行。设置 `released_at=now`、`availability_status=REMOVED(4)`、`unavailable_reason_code='CREATOR_RESERVATION_RELEASED'`。新增一个 mapper 语句，**不要复用** `releaseCreator`，它的语义是「已注销」。
3. **不改** `account_state`（登录态、期望态、账号状态都不动），不自动上线。账号原来是什么状态就保持什么状态，比如 2794 仍是「被抢登」，由运营自己点抢登/上线。软删账号（2755）照样释放，仍保持软删。
4. 打一条 info 日志：tenantId/taskId/executionId/accountId/reason，**不打号码明文**。

### A4. 代码组织

- 账号域：`AccountCreatorDeletionService#releaseUnsubmitted(CreatorReleaseRequest)`，在 `AccountCreatorDeletionServiceImpl` 里做 A1 中账号侧的锁和校验，再执行 A2。锁顺序沿用 `beginDeletion`：`lockIdentityAliases(creatorPhone)` → `lockAccount` → 重读预留行。
- 任务域：`PullTaskCreatorDeletionTransactionService#releaseIfTerminalUnsubmitted(tenantId, executionId, reason, now)`，幂等，不满足条件直接返回 false。锁顺序与 `PullTaskCreatorDeletionTransactionService` 现有的认领/提交路径保持一致：先执行行，再 `selectByExecutionIdForUpdate` 锁 ptcd 行，再调用账号域方法。实施前先读 `current(...)`，确认它的锁方式，不得引入反向锁序。
- 跨域只走 Service，保持 Controller → Service → Mapper。

### A5. 触发点

1. **同步钩子**，在已持有执行锁的事务里、终态更新之后调用：
   - `PullTaskStandardLifecycleServiceImpl#endTask`：放在 `abandonByTask` 之后，对本任务所有带 ptcd 行的执行逐个调用。
   - `PullTaskStandardExecutionLifecycleServiceImpl#end`：放在 `transitionTerminal` 之后。
   - 其余进入 FAILED/ABANDONED/COMPLETED 的汇合点（`PullTaskGroupExecutionFailureServiceImpl`、`terminateBannedGroup`、建群失败分支等）要先 grep `ABANDONED|FAILED` 的终态写入点，逐个确认。挂不上钩子的，由下面的补扫兜底，不强求全覆盖。
2. **补扫**（必做，也负责清理存量 5 条）：新建 `PullTaskCreatorReservationReleaseJob`，`@Scheduled(fixedDelayString="${armada.pull-task.creator-release.fixed-delay-ms:60000}")`，风格对齐 `PullTaskGroupAvatarCleanupJob`。查询条件是 `account_creator_deletion.lifecycle='RESERVED' AND operation_id IS NULL` JOIN 终态执行行 JOIN `ptcd.status=0`，按 id 游标分批（每批 ≤50），每条在独立事务里设置好 TenantContext 后调用 A4 的任务域方法。单条失败只记日志，不影响其他条。开关配置项 `armada.pull-task.creator-release.enabled`，默认 true。
3. 存量数据**不写手工 UPDATE/DELETE SQL**，部署后由补扫按同一套判据处理，保证线上走的就是被测试过的代码路径。

## Part B（P1）：批量一键抢登按账号隔离

- 现状：`POST /api/accounts/batch-takeover` → `AccountOnlineCommandServiceImpl#takeoverBatch` 是单事务，任何账号被拒就整批 CONFLICT。
- 改法：仿照未提交 diff 中 `AccountBatchLifecycleServiceImpl#submitChunk` 的「捕获 `ProtocolAccountCommandRejectedException` → 剔除被拒账号 → 新事务重试剩余账号」模式，给抢登加一个非事务的编排入口。Controller 改调这个入口，内部通过 Spring 代理调用事务方法 `takeoverBatch(remaining)`。
  - 被拒账号计入失败，错误文案为 `账号 {id}：账号已被一次性建群任务预留或进入永久注销流程`，与前端 `account-batch-operation.test.ts` 已有断言格式一致。
  - 重试前确认被拒集合是本批的子集且非空，否则整批计失败、停止重试（防死循环），与 `submitChunk` 一致。
  - 被拒账号的事务已回滚，所以不会被标成「抢登中」，也不会被 `takeoverPolicy.reset`。测试里要断言这一点。
  - 只隔离 `ProtocolAccountCommandRejectedException`；其他异常保持整批失败语义，不盲目重试。
- 返回结构：如果 `AccountBatchOnlineVO` 没有承载失败明细的字段，就按未提交 diff 里批量上线的返回方式对齐（前端 `account-batch-operation.ts` 已消费 `batchErrors`）。先看清再决定，**不另造一套结构**。
- 前端：确认一键抢登的结果提示复用 `account-batch-operation.ts` 的反馈逻辑，部分成功时提示「成功 N，失败 M」并列出账号错误。

## Part C（P2，可选，单独提交）：可见性

- 拉群任务进度：前端 `src/views/task/pull-task/creator-deletion-display.ts` 的 `deletionLabels` 增加 `RELEASED` → 「预留已释放（未注销）」（后端如何把 code 6 映射成字符串，以现有 progress 接口为准）。否则会显示成「注销状态待核实」。**这一项随 Part A 一起做，不算可选。**
- 账号列表：「业务风控 / 占用任务」展示「一次性建群预留（任务 #82）」和「永久注销中/已注销」，对应审计 F10。工作量较大，单独排期，本次不做。

## 不在范围

审计报告 F02–F14（候选/占用漏防、混批回滚、直接端口绕过、身份查询隐去、Android NOT_SENT/REJECTED 解除、UNKNOWN 收敛、身份代次等）本次都不处理，也不修改 Android/Web 协议层。

## 任务清单

- [x] 开工前置：已核对 git status / diff --stat / 分支 / worktree；按用户“在主仓库做”授权叠加，保留其他会话改动
- [x] Flyway：`account_creator_deletion_release` 历史表 + 迁移测试（参照 `PullTaskCreatorDeletionMigrationTest`）
- [x] `PullTaskCreatorDeletionStatus.RELEASED(6)` 及所有 switch/映射点
- [x] 账号域 `releaseUnsubmitted` + Mapper（INSERT…SELECT / 条件 DELETE）
- [x] 任务域 `releaseIfTerminalUnsubmitted` + ptcd 条件更新 + role4 释放 Mapper
- [x] `endTask`、执行 `end` 及其他已确认终态点挂同步钩子
- [x] 补扫 Job + 配置项（含 `application*.yml` 默认值）
- [x] Part B：抢登编排入口 + Controller 切换
- [x] 前端：`creator-deletion-display.ts` 标签；抢登部分成功提示
- [x] 本次需求定向测试通过（342 项），前端 21 项、typecheck / lint / build 通过；真实输出已贴入「验证」
- [ ] 全量本地测试全绿：5534 项，11 failures / 24 errors，非本次修改范围的旧断言/fixture及环境失败，未修复或隐瞒
- [ ] 部署 perf2（须用户确认）+ 部署后验证

## 测试要求（H2 + 真实 Mapper XML + Spring 事务，按 `unit-test-write` 技能）

释放判据：
1. 执行 ABANDONED + R + `operation_id` NULL + ptcd 0 → 主表行删除、历史表 1 行、ptcd=6、role4 已释放、`account_state` 不变。
2. 释放后同账号的普通上线 outbox 能写入（`creatorDeletionCommandBlocked=false`）。
3. 以下情况**不释放**：执行处于可恢复状态（EXECUTING/WAIT_RESOURCE/暂停）、ptcd ∈ {1,2,3,4,5}、`lifecycle` ∈ {DELETING, DELETED}、`operation_id` 非空、ptcd 行缺失、有派发中的 outbox。
4. 幂等：连续调用两次，第二次返回 false，无副作用。
5. 竞态：先释放再 `claimSubmission` → claim 返回 0 行；先 claim 再释放 → 不释放。
6. 释放后新任务能对同账号、同号码的别名账号重新 `reserve()` 成功。
7. 跨租户同号：只释放本租户执行对应的行，不误动其他租户。
8. 补扫：父任务 COMPLETED/ENDED 下的遗留都能处理；单条异常不影响其他条；开关关闭时不执行。

触发点：
9. `endTask` 结束带 R 的任务 → 同一事务内完成释放；执行 `end` 同理。

批量抢登：
10. 3 个被抢登账号中 1 个 R → 2 个受理、1 个失败，错误含 `账号 {id}：`；被拒账号的 `account_state` 仍为被抢登，未被 reset。
11. 全部被拒 → 0 受理，不死循环。
12. 非生命周期异常 → 整批失败，不重试。

回归：`mvn -Dtest='*CreatorDeletion*,*AccountBatchLifecycle*,*AccountOnlineCommand*,*ProtocolCommandOutbox*,*PullTaskStandard*Lifecycle*' test`，然后 `cd armada-api && mvn test`。前端 `account-batch-operation.test.ts`、`creator-deletion-display.test.ts`。

## 部署与验收（须用户确认后执行，按 `deploy-verify` 技能）

- 目标：第二套环境 perf2（库 `armada_perf`）。只发布后端（含 Flyway）和前端；不动协议层，不扩大到其他环境。
- 部署后 2 分钟内，用附录 SQL 只读确认：`account_creator_deletion` 中 RESERVED 条数为 0（如果期间有新的进行中任务产生 R，这些不应被释放）；历史表新增 5 行；对应 ptcd=6；DELETED 的 5 条不变。
- 业务验收：账号列表对 2794 点一键抢登，命令能受理；再选一个 R 账号（新建测试任务造一个）加普通账号混合抢登，确认部分成功提示。
- 回滚：回退代码即可。历史表和已释放数据不需要回滚；回退后未释放的 R/D 继续受保护。

## 附录：只读核查 SQL

```sql
SELECT d.account_id, d.task_id, d.group_execution_id, d.lifecycle, d.operation_id,
       t.status task_status, e.execution_status, e.reason_code,
       p.status ptcd_status, p.submitted_at,
       (SELECT COUNT(*) FROM protocol_command_outbox o
          WHERE o.protocol_account_id = d.protocol_account_id
            AND o.deleted_at IS NULL AND o.status IN (0,1,5,6)) inflight_outbox,
       (SELECT COUNT(*) FROM pull_task_group_account r
          WHERE r.account_id = d.account_id AND r.role_type = 4 AND r.released_at IS NULL) open_creator_roles
FROM account_creator_deletion d
JOIN pull_task t ON t.id = d.task_id
JOIN pull_task_group_execution e ON e.id = d.group_execution_id
LEFT JOIN pull_task_creator_deletion p
  ON p.tenant_id = d.tenant_id AND p.group_execution_id = d.group_execution_id
WHERE d.lifecycle = 'RESERVED';
```

## 关键设计决策

- 释放后账号回到普通池，**即使它仍是已建群的群主**。这是有意取舍：任务已经终态，不会再走注销，与其永久锁死，不如让账号可用。如果后续需要「群主身份的账号不回池」，另开需求。
- 只释放「从未提交注销」的预留。任何已提交或结果未知的状态继续永久保护，这条底线与 2026-10-07 审计一致。
- 存量数据由补扫按同一判据处理，不写手工数据修复 SQL。

## 实施事实（2026-10-09）

- V216 已由其他会话占用，本次使用 V217；历史表属账号生命周期聚合，保存释放快照，不复制原业务唯一键。状态列注释同步补充 code 6。
- 当前代码已有 `releaseReservation` 直接删除入口（方案编写后的变化）；本次删除此入口，失败释放统一通过任务终态判据与历史表，不保留旁路。
- `current(...)` 完整锁序为父任务 → 执行行 → ptcd → 身份别名 → 账号。本次释放无需父任务锁，使用其子序列“执行行 → ptcd → 身份别名 → 账号”，避免在终态写入持有执行锁后反向补父任务锁。账号预留和 outbox 采用 `FOR UPDATE` 当前读，支持软删账号。
- ptcd 锁一直持有到事务提交；账号历史写入、条件删除、ptcd 0→6 CAS、role4 释放在同一事务。CAS 或后续失败全部回滚，并发 claim 必须等待 ptcd 锁后重新检查，不能发出注销。
- outbox 的任务归属在 `payload_json`，没有独立 task/execution 列。锁定同租户、同路由的 0/1/5/6 命令后核对两个正整数归属；缺失、损坏、非法或溢出的归属保守阻断；其他明确执行的命令不误挡。
- `endTask` 在 abandon 和未发送命令取消后同步收口；单执行 end、群封禁、群级失败、管理号入群失败、拉手邀请失败、正常 closing 完成通过 `PullTaskParentCompletionService` 公共终态汇合点收口。建群失败重试及资料核验失败暂停仍为 EXECUTING，不释放；未命中钩子的终态由每 60 秒、每页 50 条补扫覆盖。写点核对见 [terminal-write-points.txt](./2026-10-08-creator-reservation-release/terminal-write-points.txt)。
- 抢登非事务入口为 `AccountBatchLifecycleService#takeoverByIds`，共用既有拒绝隔离循环与结果聚合器，通过注入的 Spring 代理调用原 `takeoverBatch`。Controller/前端契约统一为已有 `AccountBatchCommandResultVO`，不新增另一套结果结构。
- 前端显示“预留已释放（未注销）”；部分抢登显示已受理数、失败数及账号错误，不把命令受理写成账号已在线。两个配置默认值放在公共 `application.yml`，各环境 profile 继承；无需复制多份默认值。
- 数据模型通过离线 `generate-model.py` 调用既有生成器更新；不连接共享库。子代理按 `expert-reviewer` 评审发现的非法 JSON 归属和普通任务误 warn 均已修正并补测。

## 验证（evidence-before-done）

### 验证环境与范围

- 日期：2026-10-09（Asia/Shanghai）；主仓 `1.0.3-snapshot`，在用户及其他会话未提交改动上叠加；无 stash/reset，未提交或部署。
- Java 17.0.19、Maven、真实 MyBatis XML、生产租户插件、H2 MySQL 模式、Spring 声明式事务；Byte Buddy 使用本地 javaagent，测试 JVM 开启 headless。首次默认 JDK 26 导致 Mockito 初始化失败，切换项目 JDK 后重跑，不将环境失败计作业务通过。
- 先红后绿证据：[迁移 RED](./2026-10-08-creator-reservation-release/migration-red.txt) 为 `1 test / 0 failures / 1 error`（V217 尚不存在）；[前端标签 RED](./2026-10-08-creator-reservation-release/frontend-red.txt) 为期望“预留已释放（未注销）”、实际“注销状态待核实（RELEASED）”。
- 宽名称匹配首轮意外选入 `ProtocolCommandOutboxSchemaDbTest` 的数据源初始化，已中止该轮；该轮不算真库验证。随后按[已核实的 88 个真库/容器测试排除名单](./2026-10-08-creator-reservation-release/real-database-tests-excluded.txt)生成明确的本地测试类白名单并重跑。未执行部署、SSH、线上数据修复或真库验收。

### 后端定向验证：通过

```bash
cd armada
release_test_selection=$(cat .harness/changes/2026-10-08-creator-reservation-release/focused-tests.txt)
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home \
  mvn -f armada-api/pom.xml \
  -DargLine='-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Djava.awt.headless=true' \
  -Dtest="$release_test_selection,PullTaskOfflineResourceRecoveryH2Test,PullTaskGroupSettingsApplyTimingIntegrationTest,PullTaskManagerOfflineGraceH2Test,GroupDataPackageTaskResourceH2Test" test
```

[真实完整输出](./2026-10-08-creator-reservation-release/regression-final.txt)，退出码 **0**：

```text
[INFO] Tests run: 342, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  29.874 s
[INFO] Finished at: 2026-10-09T10:07:55+08:00
```

覆盖需求 1–12：三种执行终态；暂停/可恢复态保护；已提交/未知/失败注销永久保护；软删账号释放；主表删除、历史落库、ptcd=6、role4时间/状态收口且 account_state 完整快照不变；普通上线 outbox 可写；重复释放无副作用；两个独立事务的 claim/释放竞争（先证明等待）；原账号及跨租户同号别名重新 reserve；仅原租户/任务/执行角色释放；52 条跨租户翻页；补扫关闭不执行、单条真实 DB 约束异常回滚且下一条继续；实际任务 end/执行 end 同事务释放及外层失败回滚；混合抢登 2 受理/1 拒绝，受限账号 account_state 和 breaker reset 全部回滚；全拒绝退出；非生命周期异常不重试；拒绝集合非法不死循环；7 种非法 JSON 归属保守阻断。

### 后端完整本地套件：未通过，未宣称全绿

```bash
cd armada
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home \
  mvn -f armada-api/pom.xml test \
  -DargLine='-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Djava.awt.headless=true' \
  -Dsurefire.excludesFile=/Users/daishuaishuai/IdeaProjects/armada/.harness/changes/2026-10-08-creator-reservation-release/real-database-tests-excluded.txt
```

[真实完整输出](./2026-10-08-creator-reservation-release/full-local-final.txt)，退出码 **1**：

```text
[ERROR] Tests run: 5534, Failures: 11, Errors: 24, Skipped: 0
[INFO] BUILD FAILURE
[INFO] Total time:  01:32 min
```

首轮出现的 59 个新增依赖装配错误已通过更新测试配置修复，定向复测覆盖这些场景。最终剩余 18 个失败类不在本次修改范围；[逐用例失败明细](./2026-10-08-creator-reservation-release/full-local-failures.json)保留真实根因，未把“未改动”冒充逐项基线复跑证明：

| 类别 | 测试类 / 现象 |
|---|---|
| 沙箱本地端口限制（9 errors） | GrizzlySmsConfigurationTest（5）、HttpFacebookCapiClientTest（2）、HttpProtocolReadyProbeTest（2），均为 `Operation not permitted` |
| 既有 H2 fixture / 方言不一致 | MysqlModeMapperInMemoryTest：缺 system_builtin / 不支持 FORCE INDEX；GroupMembershipCountSemanticsMapperH2Test：缺 declared_account_type / account_creator_deletion；PullTaskGroupMarketingGroupMapperInMemoryTest：缺 group_classification |
| 既有 SQL 文本 / schema 数量断言 | HistoricalGroupPreviewSchemaSqlTest、GroupParticipantRolePrecedenceSqlTest、PullTaskMapperBusinessConditionTest、PullTaskNormalLinkSchemaSelfTest（期望 11 条 DDL，实际 13）、PullTaskLifecycleMapperInMemoryTest、GroupCreationMarketingTaskMapperSqlShapeTest |
| 既有业务 / 交互断言与 fixture | BusinessControllerAuthorizationContractTest、HistoricalGroupPullWorkerImplTest、PullTaskStandardCreateServiceTest、PullTaskStandardSettingWriterTest、GroupPullMarketingMaterialEntryServiceTest |
| 本地外部 DTD 解析失败 | MarketingGroupBanMapperH2Test：`UnknownHostException: mybatis.org` |

上述失败未扩大范围修复。真库与部署后 MySQL InnoDB 锁行为、存量五条及 UI 业务验收仍待部署授权，不以 H2 代替。

### 前端及静态检查：通过

```bash
cd wheel-saas-pure-web
node --import tsx --test src/views/account/index/account-batch-operation.test.ts src/views/task/pull-task/creator-deletion-display.test.ts
pnpm typecheck
pnpm exec eslint src/api/account.ts src/views/task/pull-task/creator-deletion-display.ts src/views/task/pull-task/creator-deletion-display.test.ts src/views/account/index/account-batch-operation.ts src/views/account/index/account-batch-operation.test.ts src/views/account/index/composables/useAccountListPage.ts
pnpm build
```

全部退出码 **0**。真实输出节选：

```text
ℹ tests 21
ℹ pass 21
ℹ fail 0
ℹ skipped 0
$ tsc --noEmit && vue-tsc --noEmit --skipLibCheck
✓ built in 29.80s
```

日志：[前端测试](./2026-10-08-creator-reservation-release/frontend-focused.txt)、[类型检查](./2026-10-08-creator-reservation-release/frontend-typecheck.txt)、[ESLint](./2026-10-08-creator-reservation-release/frontend-lint.txt)、[构建](./2026-10-08-creator-reservation-release/frontend-build.txt)。ESLint 首轮仅格式错误，修复后重跑通过。

- `xmllint --noout`：本次三个修改 Mapper XML，退出码 0、无错误输出。
- `git diff --check`：后端及前端退出码 0、无错误输出。
- `python3 .harness/wiki/test_api_docs.py`：[输出](./2026-10-08-creator-reservation-release/api-docs.txt)为 `Ran 1 test ... OK`，退出码 0；API 文档由现有生成器刷新 batch-takeover 响应类型。
- 专家复核：非法 outbox 归属、正常无预留任务误告警已修复；第二次只读复核无新的阻断项。保留 H2 与 MySQL 间隙锁/死锁检测差异及全量失败清单。


## 部署

- commit / 环境 / 部署后验证结果: 尚未提交、未部署；目标 perf2，等待用户明确确认。全量本地存在上述失败，不能按全绿发布记录处理。

## 遗留 / 跟进

- 账号列表展示预留/注销状态（审计 F10）。
- F15：仍在进行中的任务，creator 掉线后无法恢复连接（带 owner 的恢复入口）。本方案只解决终态。
- 审计 F02–F14。
