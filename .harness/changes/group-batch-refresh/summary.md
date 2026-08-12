# 变更记录：群组列表批量刷新群链接与批量获取最新群信息

- 日期 / 分支 / worktree: 2026-08-12 / `feat/group-batch-refresh` / `/home/yanwenchao/ideaProject/armada`
- 需求来源: `群组列表批量刷新与获取最新信息_产品需求文档_PRD_V1.1.docx` + `轻量需求卡_V1.0` + 独立交互原型 V1.0
- 状态: 进行中

## 目标（一句话）

群组列表勾选一个或多个群后，可批量刷新所选群的邀请链接、或重新拉取并回填所选群的最新快照；未勾选时两个按钮均不可用。

## 缺口拆解 / 任务清单

- [x] 调度层：新增 `BATCH_REFRESH(8)` trigger，走独立配额，不挤占实时链路
- [x] 选号：`GroupExecutionAccountSelector.findAdmin` 强制群管理员角色
- [x] Flyway V112：`group_batch_task` / `group_batch_task_item`
- [x] 批量任务 entity / enums / Mapper + XML
- [x] `GroupBatchTaskService`：提交校验与进度聚合
- [ ] `GroupBatchTaskJob` + `GroupBatchTaskWorker` + `GroupBatchTaskSettlement`：两类任务执行器
- [ ] Controller 三个端点
- [ ] 前端：两个按钮 + 任务弹窗 + 轮询

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

### 3. 批量获取最新群信息复用耐久队列，但必须走独立配额

复用 `group_metadata_sync_task` 可白拿字段级空值保护（preview 的 `xxxObserved` 标记）、账号选择、退避重试、租户/账号并发约束，不必维护第二套同步逻辑。

**但直接用 `MANUAL_REFRESH` 会堵实时链路**，且坑比预想的深：

- `findDue` 的分档不只在 Java，真正的优先级在 SQL 的 `ORDER BY CASE`（`GroupMetadataSyncTaskMapper.xml`）。
- 批量项大多是从未同步过的群 → 命中 `last_success_at IS NULL THEN 0` 排最前 → 在 `LIMIT 20` 里就把候选占满，**实时事件刷新连候选列表都进不去**。
- 因此批量档必须放在 `last_success_at` 判定**之前**求值，Java 侧再加配额上限。

最终形态（rank 越小越优先）：

```
0 = 新群首次同步
1 = 实时刷新（trigger 3/4/6：成员变更、metadata 变更、手动单群刷新）
2 = 批量刷新（trigger 8）           ← 新增，先于 last_success_at 判定
3 = 重试 / 账号上线恢复（每轮 1 个）
```

配额 `armada.group-metadata-sync.batch-quota-per-run` 默认 10。系统空闲时批量填满前台用剩的名额；实时事件涌入时排序天然让路。

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
| 进度刷新 | 必须动态。执行器逐项独立事务提交 + `success_count = success_count + 1` 原子递增；整批一个事务会让进度 0% 卡到 100% |
| 明细展示 | 运行中即实时追加已终结项，不等任务完成 |

## 验证（evidence-before-done）

```
mvn -f armada-api/pom.xml -Dtest='AccountGroupMembershipMapperSqlTest,GroupClassificationServiceImplTest,\
GroupClassificationBackfillJobTest,GroupMetadataSyncTaskMapperDbTest,NormalGroupCreationProtocolResultServiceTest,\
GroupExecutionAccountSelectorTest,GroupMetadataSyncTaskServiceImplTest,GroupMetadataSyncRequestedSinkAdapterTest,\
GroupDetailServiceImplTest,GroupMetadataSyncJobTest,JoinTaskDispatchCoordinatorTest' test
→ Tests run: 93, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS

mvn -f armada-api/pom.xml -Dtest='GroupBatchTaskItemMapperDbTest,GroupBatchTaskMapperDbTest,\
GroupBatchRefreshMigrationSqlTest' test
→ Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS
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
| `findDueCapsBatchRefreshEvenWhenTheGroupsHaveNeverSynchronized` | 3 条批量任务全被当前台任务放行，期望受配额限制只放 2 条 |
| `dueCandidatesRankBatchRefreshBehindRealtimeRefreshEvenWithoutSnapshot` | 批量项因 `last_success_at IS NULL` 排到实时项之前 |
| `findAdminReturnsEmptyWhenGroupHasNoOnlineAdminSoCallerCanSkipTheProtocolCall` | `findAdmin` / `selectGroupAdminExecutionAccounts` 不存在 |
| `selectGroupAdminExecutionAccountsEnforcesAdminRoleInsteadOfOnlyPreferringIt` | XML 中无强制 `is_admin` 过滤的 select |
| `GroupBatchRefreshMigrationSqlTest`（4 个） | V112 迁移文件不存在 |

## 下一步：执行器设计（已定，未落码）

拆三个类，职责边界是「协议 I/O 绝不在事务里」+「逐项独立事务」：

| 类 | 职责 | 事务 |
|---|---|---|
| `GroupBatchTaskJob` | `@Scheduled` 捞 PENDING/RUNNING 任务 → 逐个 `itemMapper.selectPending` → 交给 worker | 无 |
| `GroupBatchTaskWorker` | 执行单项，产出一个已填好状态的 `GroupBatchTaskItem` | 无（含协议调用） |
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

### REFRESH_INFO 单项流程（只观察，不自己拉协议）

提交阶段已 `enqueue(BATCH_REFRESH)` 并把 `baseline_synced_at` 冻结为提交时刻。worker 只做判定：

```java
GroupMetadataSyncTask sync = metadataSyncTaskMapper.selectByGroupLinkId(item.getGroupLinkId());
if (sync != null && sync.getLastSuccessAt() != null
        && sync.getLastSuccessAt() > item.getBaselineSyncedAt())  → SUCCESS
else if (sync != null && FAILED.code() == sync.getStatus())        → FAILED（带 lastErrorCode/lastErrorMessage）
else                                                                → 保持 PENDING，下一轮再看
```

判定基线用「提交时刻」而非「提交时的 last_success_at」：提交后任一次同步成功都说明快照已刷新，
语义等价但省掉提交阶段的 N 次查询。

### 待写测试（TDD 入口）

- `refreshLinkWithoutAvailableAdminFailsTheItemWithoutCallingTheProtocol`（验证 `verify(invitePort, never())`）
- `refreshLinkPersistsFetchedInviteAndSettlesItemAsSuccess`
- `refreshInfoSucceedsOnlyAfterSyncSucceededPastTheSubmittedBaseline`
- `refreshInfoFailsWhenMetadataSyncReachedTerminalFailure`
- `settlementSkipsSummaryWhenItemWasAlreadyFinished`（`finishItem` 返回 0 行）

### 调度线程池（全局审查发现，已修）

应用内 17 个 `@Scheduled` 共用 Spring 默认**单线程**调度器（`spring.task.scheduling.pool.size` 未配置）。
`GroupBatchTaskJob` 若在调度线程里同步发协议调用，一轮最多 50 项 × ~1s 会把群详情同步等
全部定时任务堵住。注意这不是本次引入的新问题——`GroupMetadataSyncJob` 本来就这么干（batchSize=20），
本次是在已有坏模式上加码，把阻塞窗口拉大约 2.5 倍。

修法沿用仓库既有的 `HistoricalGroupPullExecutorConfig` 模式：新增 `GroupBatchTaskExecutorConfig`
（core 1 / max 2 / queue 50），`runOnce()` 只扫描并投递，推进动作全部在自有线程池执行；
`inFlight` 集合防止上一轮未跑完时重复投递同一任务、对同一批明细重复发协议调用。

遗留：`GroupMetadataSyncJob` 自身仍在调度线程内同步发协议，建议另开任务同样迁到独立线程池。

## 部署

- commit / 环境 / 部署后验证结果: 待补

## 遗留 / 跟进

1. ~~未在真实 MySQL 上验证过新 SQL~~ 业务确认 H2 覆盖即可；新增 SQL 均由 H2 MySQL 模式加载真实 XML 执行。
2. `V112` 版本号已核对全分支无冲突（`git log --all` 扫描至 V111）。
3. PRD 需产品同步修正两处：7.4 / AC-28 的写操作措辞（决策 1）、AC-18 与 P-06 的冲突（决策 5）。
4. ~~`UNAVAILABLE` 状态是否等同封禁~~ 已确认：等同，一并置灰（决策 2）。
5. `batch-quota-per-run` 默认 10 是估算值，勾满 1000 个群约 8 分钟；实际值待压测后定（PRD 10 亦要求轮询间隔压测后确认）。
