# 变更记录：群组列表批量刷新群链接与批量获取最新群信息

- 日期 / 分支 / worktree: 2026-08-12 / `feat/group-batch-refresh` / `/home/yanwenchao/ideaProject/armada`
- 需求来源: `群组列表批量刷新与获取最新信息_产品需求文档_PRD_V1.1.docx` + `轻量需求卡_V1.0` + 独立交互原型 V1.0
- 状态: 已完成（待部署验证）

## 目标（一句话）

群组列表勾选一个或多个群后，可批量刷新所选群的邀请链接、或重新拉取并回填所选群的最新快照；未勾选时两个按钮均不可用。

## 缺口拆解 / 任务清单

- [x] 执行层：两个按钮都实时直调协议，明细级并发 + 按账号闸门限流
- [x] 选号：`GroupExecutionAccountSelector.findAdmin` 强制群管理员角色
- [x] Flyway V112：`group_batch_task` / `group_batch_task_item`
- [x] 批量任务 entity / enums / Mapper + XML
- [x] `GroupBatchTaskService`：提交校验与进度聚合
- [x] 执行器：`GroupBatchTaskSettlement` + `GroupBatchLinkRefreshWorker` + `GroupBatchInfoRefreshWorker` + `GroupBatchTaskJob`
- [x] Controller 三个端点
- [x] 前端：两个按钮 + 任务弹窗 + 轮询

## 关键设计决策

### 1. 「刷新群链接」= 重新拉取当前链接，不是 revoke 重置

PRD 内部矛盾：7.4 把它归为写操作、封禁群禁止（指向 revoke），但背景描述是「链接可能过期或被重置」（指向重新拉取）。**业务确认取重新拉取**。

- 采用：复用现有 `GroupInvitePort.getInvite`，WEB / ANDROID 双后端都已实现，协议层零改动。
- 否决 revoke 方案的原因：`armada-protocol` 虽有 `POST /v1/groups/{groupJid}/invite/revoke`，但 Android 后端只有 `/ws/v1/groups/qrcode/` 读取端点，**没有 revoke**，Go 侧要新开发，一期范围会失控。
- 遗留：PRD 7.4 / AC-28 的「封禁群后端拒绝写操作」措辞需产品同步修正为只读语义。

### 2. 封禁群禁用刷新链接是产品口径，与读写语义无关

业务确认：**只要勾选里存在 banned 群，刷新链接按钮整体置灰**（把 PRD P-08 的含糊表述定死为「整体禁用」，不是过滤后部分失败）。

列表 `status` 实际有 5 个值（`GroupLinkMapper.xml:318-330`）：`UNCHECKED` / `AVAILABLE` / `BANNED` / `LINK_INVALID` / `UNAVAILABLE`。
业务确认的拦截口径：**`BANNED`（is_banned=1）和 `UNAVAILABLE`（health_status=3）都置灰**；
`LINK_INVALID`（health_status=2）**必须放行** —— 链接失效恰恰是最需要刷新链接的群，
若按 PRD「封禁/异常」字面全禁会让功能自相矛盾；`UNCHECKED` 同样放行。

落地为 `GroupLinkHealthMapper.selectLinkRefreshBlockedIds`（`is_banned = 1 OR health_status = 3`），
由 `GroupLinkHealthRefreshBlockMapperDbTest` 用 H2 真跑 SQL 锁住四种状态的分流。
前端按钮禁用条件必须与之一致：`selectedCount === 0 || 选中项存在 (banned || status === 'UNAVAILABLE')`。

### 3. 批量获取最新群信息实时直调协议，不进耐久队列

**业务确认：两个按钮都要实时调协议。** 一期曾把它排进 `group_metadata_sync_task` 的批量档
（`BATCH_REFRESH(8)` trigger + 独立配额），只让 worker 观察 `last_success_at`；已撤回，理由三条：

1. **口径相反**：队列会 `RETRY_WAIT` 退避重试，而 PRD P-05 明确失败项不重试。排队期间前端只能
   看着进度长时间不动，无从区分"还在等"和"已经失败"。
2. **失败原因隔了一层**：worker 只能转抄同步任务表的 `last_error_code`，拿不到本次调用的异常。
3. **吞吐反而更差**：配额 10 项/5s ≈ 2 项/s。直调 + 并发 6 后是 6 项/(1~2s) ≈ 3~6 项/s。

保留的部分：协议读取、字段级空值保护（`xxxObserved` 标记）、成员快照落库仍然复用
`GroupMetadataSnapshotService`，新增 `refresh(GroupMetadataSnapshotRequest, account)` 作为共同入口，
耐久队列的 `execute(task, account)` 改为映射成同一个请求后委派 —— 不存在第二套快照解析逻辑。

`group_metadata_sync_task` 保持只服务实时事件与新群首次同步，本次不再有 trigger 8、不再有配额，
`ORDER BY CASE` 也回到三档。

### 3.1 并发：明细池封顶 + 按账号串行

单条明细约 1~2 秒（`getMetadata`，必要时再补一次 `getInvite`），串行推进 1000 个群要 17~35 分钟。
两层限流：

| 层 | 配置 | 默认 | 作用 |
|---|---|---|---|
| 明细线程池 `groupBatchItemExecutor` | `armada.group-batch-task.item-concurrency`（`@Value` 读取） | 6 | 总在飞协议调用数上限 |
| 账号闸门 `GroupBatchAccountThrottle` | `armada.group-batch-task.account-concurrency` | 1 | 单账号同时在飞数，口径与队列侧 `group-metadata-sync.account-concurrency` 一致 |

两个并发量都用 `@Value("${...:默认值}")` 读，不放进 `GroupBatchTaskJobProperties`：`application.yml` 里
没有 `armada.group-batch-task` 段，而那个 record 同时有 4 参规范构造和公开无参构造，Boot 走哪种绑定
（进而空环境下拿到默认值还是 0）不确定；并发被静默绑成 0 → `max(1, 0)` → 退回串行是最不能接受的失败模式。

账号闸门是必须的：一个租户的群往往集中在少数管理员账号上，并发会退化成"同一条 WhatsApp 连接
上并发发 IQ"，那是账号被限流的直接来源。许可按账号发放，因此并发只在账号之间放开 —— 20 个群
分布在 6 个账号上能真正 6 路并行，全挤在 1 个账号上自动退回串行（此时 1000 群仍约 17~35 分钟，
需要更快只能提 `account-concurrency`，代价是账号风险）。

`advance()` 用 `CompletableFuture.allOf(...).join()` 等本轮明细全部结束才返回：提前返回会释放
`inFlight`，下一轮就会对还在飞的明细重复发协议调用。明细线程池用 `CallerRunsPolicy`，队列满时由
投递线程自己跑完这一条，不丢明细也不无限堆积。

### 3.2 关闭弹窗即取消剩余项

新增 `POST /api/group-links/batch-tasks/{taskId}/cancel`，前端 `close()` 时调用（已终结的任务跳过，
取消失败静默忽略——最坏只是后台多跑一会儿）。

- 明细：`cancelPending` 只把 **PENDING** 改成 `CANCELED(4)`，已成功/已失败的是既有结果，不覆盖
- 任务：`cancelIfRunnable` 带 `status IN (PENDING, RUNNING)` 白名单，重复取消返回 0 行，
  也不会把已完成的批次改写成已取消；`CANCELED(5)` 是终态，前端轮询据此停
- 本轮已投递的明细：`advanceItem` 在发协议之前读一次任务状态（`selectStatusById`，绕租户拦截器，
  取消可能来自另一个实例），已取消就直接返回。所以停止是近实时的，只有已越过这个检查的
  ≤`item-concurrency` 条会把当前协议调用跑完
- 计数不漂移：被取消的项不计入成功数也不计入失败数；万一某条在飞明细在取消之后才结算，
  `finishItem` 的 `status = PENDING` 守卫返回 0 行，汇总直接跳过

已回填的数据不回滚 —— 取消前跑完的群，最新群信息/邀请链接照旧留在 `group_link_preview` 里。

### 4. 强制管理员选号：以 `account_group_membership.is_admin` 为准

`GroupExecutionAccountSelector.find()` 的 SQL 里 `is_admin` **只在 `ORDER BY`，不在 `WHERE`**，且 `candidateAt()` 会按重试次数轮换候选 → 重试时可能选中普通成员。而列表「可用管理员」列是强制 `is_admin = 1` 的，两边口径不一致会出现「显示可用却报权限错误」。

新增 `selectGroupAdminExecutionAccounts`（共享 `<sql>` 片段 + `AND m.is_admin = 1`），口径与列表列对齐。取不到即判该项失败并给出 PRD 要求的「系统内没有可用管理员账号」，**不发注定被拒的协议调用**。

局限（已与业务确认接受）：`is_admin` 是快照值，非 PRD 字面的「实时角色」。真实时需每项先 `getMetadata` 再 `getInvite`，协议调用翻倍。采用 **DB 预筛 + 协议层兜底裁决**。

### 5. 其他已确认口径

| 项 | 结论 |
|---|---|
| 单次批量上限 | 不设额外上限，按当页 pageSize（前端最大 1000） |
| 失败项重试 | 不做（PRD P-05） |
| 任务弹窗重开 | 不做，关闭即销毁轮询并丢弃 taskId（PRD P-06）。**AC-18 与之冲突，需产品修正** |
| 关闭弹窗后的剩余项 | **业务确认取消**。下次开弹窗一定是新任务，旧明细再也不会展示，继续把剩余上千个群跑完只是白花协议流量（决策 6） |
| 进度刷新 | 必须动态。执行器逐项独立事务提交 + `success_count = success_count + 1` 原子递增；整批一个事务会让进度 0% 卡到 100% |
| 明细展示 | 运行中即实时追加已终结项，不等任务完成 |

## 验证（evidence-before-done）

```
mvn -o -f armada-api/pom.xml -Dtest='GroupBatchAccountThrottleTest,GroupBatchInfoRefreshWorkerTest,\
GroupBatchLinkRefreshWorkerTest,GroupBatchTaskJobTest,GroupBatchTaskServiceImplTest,\
GroupBatchTaskExecutorConfigTest,GroupBatchTaskItemMapperDbTest,GroupBatchTaskMapperDbTest,\
GroupBatchRefreshMigrationSqlTest,GroupMetadataSyncTaskServiceImplTest,GroupMetadataSyncTaskMapperDbTest,\
GroupMetadataSnapshotServiceImplTest,GroupMetadataSnapshotPersistenceImplTest,GroupMetadataSyncJobTest,\
GroupExecutionAccountSelectorTest,GroupMetadataSyncRequestedSinkAdapterTest,GroupDetailServiceImplTest' test
→ Tests run: 115, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS（含取消链路）

注：仓库里 `@SpringBootTest(classes = Application.class)` 那批 DbTest 需要真库（`armada-api/.env` 未提供），
本机跑不了；全上下文装配只由新增的 `GroupBatchTaskExecutorConfigTest`（切片上下文）覆盖。
```

### 数据层两个易踩的坑（已由测试锁住）

1. **`applyItemOutcome` 的 SET 求值顺序**：MySQL 的 `SET` 按从左到右求值，`status`/`completed_at`
   的 CASE 若排在计数自增之后，会读到已经加过 1 的计数，终态提前一项落下，最后一项再无人聚合。
   XML 中 status 必须写在计数列之前（该写法在 MySQL 与标准 SQL 两种求值语义下都正确）。
2. **`finishItem` 带 `AND status = #{pendingStatus}`**：执行器重入或多实例竞争时第二次返回 0 行，
   调用方据此跳过汇总递增，否则同一项计入两次、进度会超过总数。

TDD 循环（均先观察到预期失败再实现）：

| 测试 | RED 时的失败原因 |
|---|---|
| `findAdminReturnsEmptyWhenGroupHasNoOnlineAdminSoCallerCanSkipTheProtocolCall` | `findAdmin` / `selectGroupAdminExecutionAccounts` 不存在 |
| `selectGroupAdminExecutionAccountsEnforcesAdminRoleInsteadOfOnlyPreferringIt` | XML 中无强制 `is_admin` 过滤的 select |
| `GroupBatchRefreshMigrationSqlTest`（4 个） | V112 迁移文件不存在 |
| `GroupBatchAccountThrottleTest`、`GroupBatchInfoRefreshWorkerTest`（改直调后重写） | `GroupBatchAccountThrottle` / `GroupMetadataSnapshotRequest` 尚不存在，testCompile 失败 |
| `itemsAdvanceConcurrentlyAndTheRoundWaitsForAllOfThem` | 把 `advance()` 临时改回串行 for 循环复验：4 条明细的 `CyclicBarrier` 3 秒超时，`TimeoutException` |

## 执行器设计（已落码）

拆三个类，职责边界是「协议 I/O 绝不在事务里」+「逐项独立事务」：

| 类 | 职责 | 事务 |
|---|---|---|
| `GroupBatchTaskJob` | `@Scheduled` 捞 PENDING/RUNNING 任务 → 投递到任务线程池 → 明细并发投递到明细线程池并等齐 | 无 |
| `GroupBatchLinkRefreshWorker` / `GroupBatchInfoRefreshWorker` | 执行单项，产出已填好状态的 `GroupBatchTaskItem` | 无（含协议调用） |
| `GroupBatchRefreshSupport` / `GroupBatchTaskOutcomes` | 两个执行器共用的选号、groupJid、账号闸门、结算与结算行构造 | 无 |
| `GroupBatchTaskSettlement` | `finishItem` + `applyItemOutcome` | `@Transactional`，一项一事务 |

`settle(GroupBatchTaskItem outcome)` 单参数即可——成功与否从 `outcome.getStatus()` 推导，避免超 5 参数限制：

```java
int updated = itemMapper.finishItem(outcome, PENDING.code());
if (updated == 0) return;   // 重入/多实例竞争，跳过汇总，否则同一项计两次
taskMapper.applyItemOutcome(outcome.getTaskId(), success, COMPLETED.code(), RUNNING.code(), outcome.getOperatedAt());
```

### REFRESH_LINK 单项流程

1. `selector.findAdmin(groupLinkId)` → 空则直接 FAILED，errorCode `NO_AVAILABLE_ADMIN`，
   description「系统内没有可用管理员账号」，**不发协议调用**（PRD 9）。
2. groupJid 取 `GroupLinkPreviewMapper.selectByGroupLinkId(groupLinkId).getGroupJid()`
   （提交阶段不写 group_jid，执行时才解析）。
3. `GroupInvitePort.getInvite(account.protocolRef(), groupJid)`。
4. 成功 → `GroupInviteLinkService.applyCurrentInvite(new GroupInviteLinkObservation(...))` 写回新链接，
   `ProtocolBackend.fromProtocolId(account.protocolId())` 取 backend；明细落 SUCCESS + accountId + groupJid。
5. 协议异常 → FAILED，errorCode 取 `ProtocolErrorCode`，description 脱敏后写入（禁止只返回通用失败，PRD 6.3）。

### REFRESH_INFO 单项流程（实时直调）

与 REFRESH_LINK 完全对称，只差调哪个协议：

1. `selector.find(groupLinkId, 0)` → 空则直接 FAILED，errorCode `NO_AVAILABLE_ACCOUNT`，
   description「系统内没有在线且仍在该群内的账号」，**不发协议调用**。只读 metadata 不需要管理员，
   因此这里用 `find` 而不是 `findAdmin`。
2. groupJid 取 `GroupBatchRefreshSupport.groupJid(groupLinkId)`（读 `GroupLinkPreviewMapper`）。
3. 在该账号的闸门内调 `snapshotService.refresh(request, account)`，
   `request.inviteRequired = false`：取不到邀请码不该把本项判失败，那是另一个按钮的职责。
4. 成功 → 明细落 SUCCESS + accountId + groupJid，description「群信息已刷新」。
5. 协议异常 → FAILED，errorCode `METADATA_FETCH_FAILED`，description 取异常自带说明并截断到 512。

两个执行器共用的选号、groupJid 解析、账号闸门与结算收进 `GroupBatchRefreshSupport`，
结算行构造与失败原因脱敏收进 `GroupBatchTaskOutcomes`，避免两处漂移。

### 已写测试

刷新群链接 / 获取最新群信息两侧对称覆盖：

- `missingAdminFailsTheItemWithoutEverCallingTheProtocol` /
  `missingExecutionAccountFailsTheItemWithoutCallingTheProtocol`（`verify(port, never())`）
- `missingGroupJidFailsTheItemBeforeTheProtocolCall`（两侧各一条）
- `successfulFetchPersistsTheInviteAndSettlesTheItemWithExecutingAccount` /
  `successfulRefreshReadsTheSnapshotInRealTimeAndSettlesWithExecutingAccount`
- `protocolCallRunsInsideTheThrottleOfTheExecutingAccount`（两侧各一条）
- `protocolFailureKeepsTheOldLinkAndRecordsAConcreteReason` /
  `protocolFailureRecordsAConcreteReasonAndKeepsTheOldSnapshot`
- `GroupBatchAccountThrottleTest` 5 条：同账号峰值并发恒为 1、不同账号真并行、
  动作抛异常仍归还许可、accountId 为空不限流、许可数可配
- `itemsAdvanceConcurrentlyAndTheRoundWaitsForAllOfThem`、`oneFailingItemDoesNotAbortTheRestOfTheRound`
- `runOnceOnlyDispatchesSoTheSharedSchedulerThreadIsNeverBlockedByProtocolCalls`、
  `inFlightTaskIsNotDispatchedAgainByTheNextRound`
- `batchInsertKeepsTheSubmitStageFailureReasonSoBlockedGroupsExplainThemselves`（SQL 层）
- 取消:`closingTheDialogCancelsRemainingItemsSoTheyStopSendingProtocolCalls`、
  `cancelingAnAlreadyFinishedTaskChangesNothing`、`cancelRejectsATaskOutsideTheCurrentTenant`、
  `canceledTaskStopsMidRoundWithoutSendingAnyMoreProtocolCalls`、
  `cancelPendingLeavesAlreadySettledItemsUntouched`（SQL 层）、
  `cancelIfRunnableOnlyTouchesTasksThatCanStillBeStopped`（SQL 层）、
  `selectStatusByIdReadsAcrossTenantsSoSchedulerThreadsCanSeeCancellation`（SQL 层）
- `settlementSkipsSummaryWhenItemWasAlreadyFinished`（`finishItem` 返回 0 行；已由
  `GroupBatchTaskItemMapperDbTest.finishItemIsRejectedOnSecondCall...` 在 SQL 层覆盖）

### 两个只会在部署时才暴露的缺陷（本分支自己引入，已修）

1. **`GroupBatchTaskJobProperties` 从未被注册**：全仓 26 个 `@ConfigurationProperties` 里唯一
   既没有 `@EnableConfigurationProperties`、也不在任何 `@ConfigurationPropertiesScan` 覆盖范围内的一个
   （`Application` 只有 `@SpringBootApplication` + `@MapperScan`）。`GroupBatchTaskJob` 构造需要它，
   一部署就 `NoSuchBeanDefinitionException` 起不来；单测手工 `new` 所以全绿。
   修法：`GroupBatchTaskExecutorConfig` 上加 `@EnableConfigurationProperties`（明细线程池也要读它）。
2. **`batchInsert` 漏三列**：`error_code` / `description` / `operated_at` 没进 INSERT 语句，
   提交阶段被拦截的封禁群落库后原因是 NULL，前端弹窗只能显示空原因（PRD 6.3 要求具体原因）。
   原测试只断言了内存里的 `capturedItems()`，断不到 SQL，故未发现。

### 调度线程池（全局审查发现，已修）

应用内 17 个 `@Scheduled` 共用 Spring 默认**单线程**调度器（`spring.task.scheduling.pool.size` 未配置）。
`GroupBatchTaskJob` 若在调度线程里同步发协议调用，一轮最多 50 项 × ~1s 会把群详情同步等
全部定时任务堵住。注意这不是本次引入的新问题——`GroupMetadataSyncJob` 本来就这么干（batchSize=20），
本次是在已有坏模式上加码，把阻塞窗口拉大约 2.5 倍。

修法沿用仓库既有的 `HistoricalGroupPullExecutorConfig` 模式：新增 `GroupBatchTaskExecutorConfig`，
任务层 core 1 / max 2 / queue 50 只扫描分派，明细层另开 `groupBatchItemExecutor`
（core = max = `item-concurrency`，queue = `item-batch-size`，`CallerRunsPolicy`）承载协议调用；
`inFlight` 集合防止上一轮未跑完时重复投递同一任务、对同一批明细重复发协议调用。

遗留：`GroupMetadataSyncJob` 自身仍在调度线程内同步发协议，建议另开任务同样迁到独立线程池。

## 部署

- commit / 环境 / 部署后验证结果: 待补

## 遗留 / 跟进

1. ~~未在真实 MySQL 上验证过新 SQL~~ 业务确认 H2 覆盖即可；新增 SQL 均由 H2 MySQL 模式加载真实 XML 执行。
2. `V112` 版本号已核对全分支无冲突（`git log --all` 扫描至 V111）。
3. PRD 需产品同步修正两处：7.4 / AC-28 的写操作措辞（决策 1）、AC-18 与 P-06 的冲突（决策 5）。
4. ~~`UNAVAILABLE` 状态是否等同封禁~~ 已确认：等同，一并置灰（决策 2）。
5. `item-concurrency` 默认 6 / `account-concurrency` 默认 1 都是估算值。勾满 1000 个群的耗时取决于这些群分布在
   多少个账号上：分散时约 3~6 分钟，全挤在一个账号上仍是 17~35 分钟。实际值待压测后定（PRD 10 亦要求轮询间隔压测后确认）。
6. 每项成功后仍会顺带读一次邀请码（`GroupMetadataSnapshotServiceImpl.safeInviteCode`，仅在有管理员账号时触发），
   与原队列路径行为一致。若后续要压批量流量，可在 `GroupMetadataSnapshotRequest` 上加"跳过邀请码"开关。
7. 多实例部署时两个实例会同时扫到同一个批量任务并各自投递（`group_batch_task` 没有租约），
   `finishItem` 的 `status = PENDING` 守卫保证不会重复计数，但会重复发协议调用。这是原有设计，
   本次并发只是放大了窗口；要彻底解决需给任务加租约，另开任务处理。
