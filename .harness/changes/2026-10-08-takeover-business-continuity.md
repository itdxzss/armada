# 变更记录：抢登账号业务连续性

- 日期 / 分支 / worktree: 2026-10-08 / `1.0.3-snapshot` / 主仓库 `/Users/daishuaishuai/IdeaProjects/armada`；开发不创建 worktree、不切分支。2026-10-09 用户另授权两个指定 detached worktree 仅跑测试，已用完删除。
- 需求来源: 用户本轮实施指令及 `docs/superpowers/specs/2026-10-08-takeover-account-business-continuity-design.md` r2。
- 状态: 四阶段实现和本任务验证完成；阶段 1–3 已提交，阶段 4 随本记录提交。用户于 2026-10-09 确认的独立 last_kicked_at 去重、全部角色恢复终态检查均已实现。全量保留基线、环境和其他会话在途改动导致的失败，详见最后的分类结果。

## 目标（一句话）

按四阶段实现自动抢登、固定窗口熔断、建群人恢复与角色离线等待，并保证两个开关关闭时保留原行为。

## 缺口拆解 / 任务清单

- [x] 阶段 1：V215 数据结构、两个配置类及全 profile 装配、离线起点维护、真实 Mapper H2 测试、设计文档提交。
- [x] 阶段 2：自动抢登、熔断、补偿扫描、状态与意图保护、提交后续上线。
- [x] 阶段 3：角色可用性、建群人/预留建群人恢复、拉手超时与自动补号、上线唤醒。
- [x] 阶段 4：管理宽限、两个开关回归、去重与恢复终态补充、全量测试及失败归因。

## 关键设计决策

- 开工基线 HEAD：`ebef5d92134eadee2e883e6b571387b43dcaae97`；暂存区为空。
- 完整 `git status --porcelain=v1 -uall`、既有 API diff 与 8 个指定文件快照已保存在 `/private/tmp/armada-takeover-20261008-baseline/`，用于核对他人改动未被覆盖；不提交该临时目录。
- 既有修改：`AccountBatchLifecycleServiceImpl.java`、`AccountOnlineCommandServiceImpl.java`、`ProtocolCommandOutboxServiceImpl.java`、`AccountBatchLifecycleServiceImplTest.java`、`ProtocolCommandOutboxServiceImplTest.java`；另有 `.codegraph/daemon.pid` 和两个既有 worktree 的状态变化。
- 既有未跟踪代码：`ProtocolAccountCommandRejectedException.java`、`AccountBatchLifecycleTransactionH2Test.java`。既有未跟踪文档、证据及设计文档均已列入基线清单；本任务仅按用户授权提交设计文档。
- 对混合所有权的文件仅暂存本任务补丁，逐次核对 `git diff --cached --stat`；不使用 stash、reset、checkout、全量 add。
- 迁移编号核对输出为 V212、V213、V214，暂定下一个编号 V215，写入和提交前再次核对。
- `offline_since` 属账号登录态事实，已有同步时间与角色更新时间会持续刷新，不能表达连续离线起点；熔断计数独立存储，避免扩大账号状态聚合。
- 所有验证使用本地 H2 和真实 Mapper XML；不访问真实数据库，不 push、部署、SSH，不修改其他仓库。

### 必须核实项

- [x] 5.2 已列 SQL 参数：三个登录态写入使用 `lastStateSyncTime`；三个待上线 statement 使用 `updatedAt`；注销 `markOffline` 使用 `now`。全 mapper 写入点复核留待实现后执行。
- [x] 5.3 代理分配 `IpProxyServiceImpl:280`、outbox 入队 `ProtocolCommandOutboxServiceImpl:240` 均为事务 Bean；无空闲代理 `IpProxyOptimisticAllocator:161` 与 outbox 拒绝 `ProtocolCommandOutboxServiceImpl:834` 均抛运行时业务异常，命中设计的提交后续上线分支。回调必须捕获并恢复事件租户，在新事务中写入，避免复用已提交事务资源；需实现 F29。
- [x] 5.4 `AccountStateMapper:291` 使用 `@InterceptorIgnore(tenantLine = "true")`；dispatcher 逐账号设置并恢复 TenantContext，符合设计。
- [x] 5.6 Zhuan `internal/armada/command.go:52,228` 均用标准 `json.Unmarshal`，没有严格未知字段检查或自定义反序列化；上线调用点 `internal/armada/lifecycle_scheduler.go:253`。`DisallowUnknownFields` 仅在独立探针中，归属字段兼容。仅只读检查，没有修改 Android 仓库。
- [x] 7.1(a) `PullTaskGroupExecutionFailureServiceImpl:53` 重读最新 version；`transitionTerminal` 不要求空租约，持调度租约可以命中。
- [x] 7.1(b) 通用终止/GROUP_BANNED 只取消计划调用、波次和释放拉手，没有解除 RESERVED；命中设计“没有则补齐”分支，后续补安全释放与测试。
- [x] 7.2 人工补充的 `activateResourceSupplement` 保留活动波次、粘性指针和调用；后续波次 prepare 复用活动波次，bindCall 重绑 PLANNED 调用和尝试。新增超时清理的版本变化无法直接复用这一流程，见下方暂停项。
- [x] 7.3 管理离线拒绝会产生 action FAILED、membership JOIN_FAILED，资格查询与 prepareExisting 均排除该事实；两个结果入口都需按 7.3/7.4 修复为可重试事实，符合设计要求。

### 已确认补充：7.2 超时清理使后续推进的 version 过期

- 设计要求 `pullerCheck` 内复用 REMOVED 分支，并调用 `stickyPullers.invalidateCurrentRole`。
- 当前 `PullTaskStickyPullerTransactionService:42-49,175-178` 的原因白名单不包含 `PULLER_OFFLINE_TIMEOUT`，照写会直接跳过清理。
- 把新原因加入白名单后，若过期角色恰好是当前粘性拉手，`PullTaskGroupExecutionMapper.xml:683-691` 清空指针并令执行行 `version + 1`。
- `PullTaskResourceRecoveryTransactionService:91-100,482-487` 随后的 defer/resume 仍用原 candidate.version。`transitionClaimed` 在 XML:480 检查 version，失败后 service:476-478 标记整个事务 rollback-only，连超时移出都会回滚。
- 这与 7.2 预期“超时移出后继续资源恢复”的事务结果不符，按用户指令暂停全部后续实现和新测试；尚未更改生产代码或提交。
- 用户已确认：加入超时原因；仅任务侧开启且本轮实际超时移出后，在同事务重读执行行。复核 WAIT_RESOURCE、stage 不变、manual_paused=0、lock_owner 为本调度实例、lock_expires_at>now；失败时 setRollbackOnly 并返回 LOST。后续 defer/deferForSlot/resume/recoveryStage 全部使用重读行。
- 用户另要求：managerAdminCheck 终止执行行后，recover 必须直接返回终止结果，不能再 defer/resume。
- 上述内容已补入设计 7.1、7.2 和第 8 节 G30–G34，覆盖当前粘性拉手、丢租约回滚、非粘性角色版本仅加一次、开关关闭无重读及终止直返；阶段 3 落同组 H2 测试。

## 验证（evidence-before-done）

- 已执行工作区、分支、暂存区和迁移版本检查；阶段 1 已完成。
- 新增测试：`AccountAutoTakeoverPropertiesTest`（14）、`PullTaskOfflineRoleWaitPropertiesTest`（8）、`AccountTakeoverBreakerMigrationTest`（4）、`AccountOfflineSinceMapperH2Test`（25），合计 51。
- RED 命令：`cd armada-api && mvn -Dtest='AccountAutoTakeoverPropertiesTest,PullTaskOfflineRoleWaitPropertiesTest' test`。
- 真实结果：退出码 1，`BUILD FAILURE`，`testCompile` 两处找不到对应配置类，耗时 8.111 s；测试方法未执行。这是编译红阶段，尚无绿测试结果。
- 完整日志：`/private/tmp/armada-takeover-phase1-properties-red.log`。
- 后续 RED：配置装配 22 tests / 2 errors（缺少装配类）；迁移 4 tests / 4 errors（缺少 V215）；离线起点 25 tests / 11 failures / 1 error。日志分别为 `/private/tmp/armada-takeover-phase1-configuration-red.log`、`/private/tmp/armada-takeover-phase1-migration-red.log`、`/private/tmp/armada-takeover-phase1-offline-red.log`。
- 初次扩展回归默认 JDK 26 无法启动 Mockito agent：135 tests / 50 errors；新测试 51 项全部通过。保留日志 `/private/tmp/armada-takeover-phase1-green.log`，未把环境错误视为通过。
- 最终 GREEN：使用已安装 Java 17 和现有 Byte Buddy agent，命令如下，135 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS（日志 `/private/tmp/armada-takeover-phase1-java17-green.log`）。

```bash
cd armada-api
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest='AccountAutoTakeoverPropertiesTest,PullTaskOfflineRoleWaitPropertiesTest,AccountTakeoverBreakerMigrationTest,AccountOfflineSinceMapperH2Test,AccountStateEventServiceConcurrencyH2Test,AccountCreatorDeletionServiceInMemoryTest,AccountExportServiceH2Test,AccountPullerRestrictionServiceH2Test,DeviceImportTransactionTest,AccountBatchLifecycleTransactionH2Test' test
```

- 两份 Mapper XML 的 `xmllint --noout` 通过；`git diff --check` 通过；生成器离线运行且重跑 SHA-256 一致。5 份既有测试 fixture 只增加新列，未改业务断言。
- 已检查所有 Mapper 登录态写入：现有 7 个写入口均覆盖；5.6 的新 claim 在阶段 3 增加。普通默认状态 insert 仍保持未上报且 offlineSince=NULL，等待策略按设计视为无有效恢复起点。
- 受保护的 7 份既有 API 文件（含两份未跟踪文件）与开工快照逐字节一致。

## 部署

- 阶段 1 commit: `7b294fa91dd448714101a57b74f9a4d8164dfbcb`（22 files changed, 1659 insertions, 4 deletions）。暂存路径逐项核对，无受保护 API 文件；设计文档依用户授权一并提交。
- 环境 / 部署后验证结果: 用户禁止 push、部署与真实环境访问，均未执行。

### 阶段 2：自动抢登（完成）

- 固定窗口熔断使用持久账号行锁保护首次插入和人工清零；窗口期满只能重开未熔断窗口，已熔断记录必须人工清零。
- 状态事件开启时保留终态、期望离线和禁言防线；预留建群人计数但不走全局上线。关闭时保留原同步事件分支。
- 开启时提交后在 REQUIRES_NEW 中续上线，捕获并恢复事件租户；代理失败不影响已提交状态。扫描逐账号隔离异常并恢复调用方租户。
- 自动入口和启用状态下的续上线统一先锁 account、后锁 account_state，与熔断事件、outbox 的锁顺序一致。人工入口复用原抢登体，只有开启时清零熔断，失败整笔回滚。
- `AccountOnlineCommandServiceImpl.java` 仅新增策略依赖、自动入口、续上线防线及原抢登体提取；既有 `loadAccounts`、`updateDesiredLoginStateOrThrow` 等他人方法体未改。
- 既有未跟踪 `AccountBatchLifecycleTransactionH2Test.java` 因新增构造依赖只增加一个 disabled Policy mock 参数；仍不暂存、不提交该文件，其他内容保持不变。
- RED：`/private/tmp/armada-takeover-phase2-red.log`，testCompile 缺少 Breaker/Policy/Lookup 等 12 errors，退出 1，BUILD FAILURE（3.437 s）。
- 首轮执行：`/private/tmp/armada-takeover-phase2-green1.log`，120 tests / 4 failures / 24 errors。真实租户插件暴露 FOR UPDATE 被错误移至 ORDER BY/LIMIT 前；熔断排序锁改为显式 tenantId 加方法级忽略，状态锁删除冗余 LIMIT，保留真实行锁/租户并发验证。
- 第二轮：`/private/tmp/armada-takeover-phase2-green2.log`，123 tests / 4 failures / 6 errors；策略 20 例和扫描 11 例全部通过，剩余均为存量代理快照 `UPDATE ... JOIN` 的 H2 方言错误。
- 对该存量语句只在 test support 做精确方言转换，仍加载真实 Mapper XML 并保留租户插件及事务；新增原 SQL 解析、批量跨租户更新和回滚测试。原 SQL 结构检查通过，另两项在第二轮真实报错，作为适配的 RED。生产 SQL 不因测试库方言而改变。
- 最终 GREEN：`/private/tmp/armada-takeover-phase2-green3.log`，123 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS（19.410 s）。新增 60 项（策略 20、扫描 11、事件 20、上线 6、方言 3），既有回归 63 项。

```bash
cd armada-api
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest='AccountTakeoverPolicyH2Test,AccountAutoTakeoverDispatcherH2Test,AccountTakeoverStateEventH2Test,AccountAutoTakeoverOnlineH2Test,AccountTakeoverProxySnapshotDialectTest,AccountOnlineCommandServiceImplTest,AccountStateEventServiceImplTest,AccountStateEventServiceConcurrencyH2Test,AccountBatchLifecycleTransactionH2Test' test
```

- 必须核实 5.3 的 F29 已通过：真实 REQUIRED 代理池事务抛错，账号状态和熔断仍提交，保持 7/离线，无 outbox；后续扫描可补偿。关闭账号开关时保留旧同步回滚行为。
- 残余验证边界：H2 不代表 MySQL InnoDB 间隙锁与优化器；没有连接真实库。两个功能开关均关闭的完整矩阵继续在阶段 3、4 补齐。

## 遗留 / 跟进

- 设计“必须核实”项如与代码不符，按用户要求暂停并报告差异与建议，获得确认后再继续。

### 阶段 3：建群人、拉手与预留恢复（完成）

- 角色可用性批量查询真实 account/state/breaker/reservation，以终态、用户下线、熔断优先；预留查询匹配账号和身份手机别名。
- 预留恢复锁定账号/状态并原子抢占待上线，只透传所属 task/execution；普通上线 payload 的逐字节一致性 H2 验证通过。
- 建群及新群提权共用固定建群人闸门；等待成功提交后用新事务发起预留恢复，异常保留等待事实；终止后直返并同事务释放原 RESERVED。
- 拉手实际超时移出后，先重读角色避免恢复 REMOVED，再重读并锁定执行行，复核状态/阶段/暂停/租约，后续 CAS 使用新 version。任一复核失败整笔回滚。
- `PULLER_OFFLINE_TIMEOUT` 已加入粘性失效原因；真实 H2 同组覆盖 G30–G34。自动补号验证新角色插入及原 wave/call/attempt 的重绑定，排除历史账号。
- ONLINE 事件在拉手资格判断之前唤醒建群人/管理员等待；任务开关关闭完全不发新增 wake SQL。
- 7.4 审查：管理/拉手进群、管理员提权和迟到料子提权明确离线拒绝保留待执行事实；建群拒绝不加 attempt。标准拉人 UNKNOWN/NOT_STARTED 原实现已扣除未执行尝试、不计失败，无需改动；FAILED/STARTED 的执行事实不被原因字符串覆盖。通用 handleAccountAction 在生产无调用，未扩大遗留路径改动。
- E27 文案与旧实现核对：原 failCreate 的 ACCOUNT_NOT_ONLINE 属 DEFINITELY_NOT_CREATED，实际 reason 为 GROUP_CREATE_FAILED 且 attempt+1；GROUP_CREATOR_UNAVAILABLE 是旧建群后闸门。遵循用户最高约束“关闭与改动前一致”，关闭回归保留真实原值，设计 E27 同步澄清。
- 类长度边界：存量 GroupCreateTransactionService 已超过 800 行，本次新判断集中在独立 Gate，只在既有入口增加门控，没有无关拆分或重排。
- RED：账号快照缺类 3 errors（3.021 s）；任务 Gate/Policy 缺类 4 errors（9.708 s）；离线回调新增依赖缺构造 3 errors（18.774 s）；迟到料子结果新增依赖缺构造 1 error（21.358 s）。完整日志位于 `/private/tmp/armada-takeover-phase3-{account-red,task-red,offline-result-red,late-red}.log`。
- 中间失败全部保留：green1 缺原因枚举导致 compile 失败；green2 遗漏既有手动构造导致 testCompile 失败；green3 113 tests / 1 failure / 18 errors（fixture 缺必填列，及迟到进群阶段回退）；green4 60 tests / 3 failures / 0 errors（建群拒绝计数及在线终态拉手恢复顺序），对应真实行为已修复。
- 扩展回归 green5：540 tests / 9 failures / 0 errors，失败均来自既有 EndToEnd 参数化闭环；新测试均通过，仍在定位，不将本轮报告为通过。日志 `/private/tmp/armada-takeover-phase3-green5.log`。

- green5 的 9 个 EndToEnd 失败定位为既有 fixture：HEAD 中 `findOnlineProtocolRefs(anyList())` 永远仅返回 manager，而未修改的 PullerInvite 会校验拉手在线。fixture 改为按请求 ID 返回已设定的 manager/puller/station，原业务断言不变；green6 EndToEnd 17 项全部通过。
- 重发闭环新增 RED：13 tests / 4 failures / 0 errors（`phase3-retry-red.log`，18.194 s），分别复现同步结果未标 retryable、新旧动作未递增代次及未清元数据。开启路径复用原 action，独立 `submitOfflineRetryAttempt` 更新命令/代次并清理由，原 `submitAttempt` SQL 逐字保留；关闭路径仍用 `markSubmitted`，覆盖开关回滚时遗留离线 PENDING 数据。
- green6：30 tests / 0 failures / 0 errors / 0 skipped（23.846 s），包含原动作重发与端到端回归；随后补充关闭路径及专用 SQL 隔离，纳入最终阶段回归。

- 阶段 2 commit：`67f88972d1a66427750143d08b667979a0b4bf6a`。
- 阶段 3 最终 GREEN：543 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS（30.143 s）。新 9 个测试类合计 123 项；完整日志 `/private/tmp/armada-takeover-phase3-green7.log`。全部 5 份新增/修改 Mapper XML 经 xmllint，git diff --check 通过。

```bash
cd armada-api
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest='AccountRoleAvailabilityH2Test,AccountCreatorReservationReleaseH2Test,AccountReservedCreatorReonlineH2Test,PullTaskRoleReconnectWakeH2Test,PullTaskOfflineRoleWaitPolicyTest,PullTaskCreatorOfflineGateH2Test,PullTaskOfflineResourceRecoveryH2Test,PullTaskManagerJoinOfflineH2Test,PullTaskLateMaterialAdminOfflineH2Test,AccountProtocolLookupServiceTest,AccountCreatorDeletionServiceInMemoryTest,PullTaskGroupCreateTransactionIntegrationTest,PullTaskResourceRecoveryTransactionIntegrationTest,PullTaskExecutionEndToEndIntegrationTest,PullTaskStickyPullerTransactionServiceTest,PullTaskPullerAccountStateServiceImplTest,PullTaskPullerOnlineWindowIntegrationTest,PullTaskManagerJoinResultServiceImplTest,PullTaskManagerJoinTransactionIntegrationTest,PullTaskManagerJoinTransactionServiceTest,PullTaskManagerAdminResultServiceImplTest,PullTaskProtocolResultCallbackServiceImplTest,PullTaskStandardExecutionLifecycleServiceTest,ProtocolCommandOutboxServiceImplTest,AccountTakeoverPolicyH2Test,AccountAutoTakeoverDispatcherH2Test,AccountTakeoverStateEventH2Test,AccountAutoTakeoverOnlineH2Test,AccountTakeoverProxySnapshotDialectTest,AccountOnlineCommandServiceImplTest,AccountStateEventServiceImplTest,AccountStateEventServiceConcurrencyH2Test,AccountBatchLifecycleTransactionH2Test' test
```


### 阶段 4：管理与组合回归（完成）

- 阶段 3 commit：`a4416a94b7395e23ae1b598fd2b4cefbf3a3beb5`；暂存统计 63 files / 3201 insertions / 71 deletions。两个混合所有权文件用独立补丁暂存，其余 61 个文件逐路径添加；索引不含他人的生命周期受限异常及方法修改。
- 提交后逐字节验证：共享文件工作区与 HEAD 的差异仅为最初他人修改；`loadAccounts`、`updateDesiredLoginStateOrThrow`、`insertPendingRows` 方法体与开工快照完全一致。其他受保护文件 hash 一致；既有未跟踪 H2 测试仅保留已告知的构造参数适配，没有提交。
- 全量本地测试约束：项目有 88 个可选真库/容器测试类，不能直接无筛选运行，否则违反本任务“只用 H2，不连接真实数据库”。基于源码的 DbTestBase、SpringBootTest 和 Testcontainers 扫描生成 `2026-10-08-takeover-business-continuity/real-database-tests-excluded.txt`；包括两个名称不带 DbTest 的真库测试。最终运行 mvn test 时使用 Surefire excludesFile，保留其余全部本地测试；不会将结果宣称为包含真库验收。

- 阶段 4 RED：`phase4-red.log`，47 tests / 5 failures / 0 errors，BUILD FAILURE（14.729 s）；管理宽限 11 项中的 4 个等待用例按预期失败；双关闭任务 1 例因 fixture 误用 RISK_COOLDOWN 数值，改用 OFFLINE 枚举，不改旧生产逻辑。
- 7.3 新增逻辑仅在 usable 为空且任务开关开启时查询保留管理员的可用性，排除 REMOVED/admin FAILED；有 WAIT 取固定最早截止，否则沿用原 replaceManager。
- 阶段 4 聚焦 GREEN：`phase4-green1.log`，195 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS（23.090 s）。新增 20 项（管理 11、同库双关闭任务 5、同库双关闭账号 4）；D24 原参数组进一步严格验证计划 2、两旧角色都移出、唯一新号插入 seq3，并保留原 wave/call/attempt 重绑定断言。
- E26 覆盖：StateEvent 的 disabled 2/3/7 三类旧转换、Dispatcher disabled 零查询、AutoTakeoverOnline disabled 人工抢登不清 breaker、ReservedCreator disabled 跳过。
- E27 覆盖：CreatorGate disabled 仍发命令/旧计数/无限 profile defer；OfflineResourceRecovery disabled 不移出且不重读；RoleReconnectWake disabled 零新 SQL；双关闭任务验证存在候选也不自动补号、管理员立即替换。
- E28 使用真实同库账号事件、AccountProtocolLookupService、账号与任务 Mapper、两组真实 Properties 同时 false，并让账号和任务都实际走入关闭路径；不是只声明一个未参与执行的配置对象。

```bash
cd armada-api
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest='PullTaskManagerOfflineGraceH2Test,PullTaskBothSwitchesOffH2Test,AccountTakeoverBothSwitchesOffH2Test,PullTaskOfflineResourceRecoveryH2Test,PullTaskResourceRecoveryTransactionIntegrationTest,PullTaskManagerJoinOfflineH2Test,PullTaskExecutionEndToEndIntegrationTest,AccountTakeoverStateEventH2Test,AccountAutoTakeoverDispatcherH2Test,AccountAutoTakeoverOnlineH2Test,AccountReservedCreatorReonlineH2Test,PullTaskCreatorOfflineGateH2Test,PullTaskRoleReconnectWakeH2Test' test
```


### 最终边界复核：已获用户确认（2026-10-09）

- 原计划阶段 4 的 195 项聚焦验证已通过，但新增复核发现以下两项；遵循用户“核实结果与设计不符时停下报告”的要求，不自行扩写生产处理，不提交第四阶段。
- **事件计数幂等缺口**：AccountStateChangedEvent 未保留 envelope eventId；StateEvent 仅拒绝早于水位的事件，同时间会再次进入 recordKick。LOGIN_REPLACED 不在现有 ProtocolRiskSignal 内，因此不能假设风控表已经做过此事件的去重。RESERVED 账号同一事件投递 10 次，真实结果 kick_count=10；应为 1。新增 `AccountTakeoverStateEventH2Test#replayingSameReservedCreatorKickTenTimesCountsOnceWithoutTripping` 已复现。
- 初次提出复用风控表/eventId/摘要的方案，用户明确否决，未实施。最终采用新迁移 V216 添加 last_kicked_at，不能污染固定风控信号语义。真实被挤需先重连，用户提供 perf2 间隔至少 2 秒的依据，发生时间可作为本业务去重水位；设计 5.3 已写明该前提和摘要的同样限制。
- **管理员终态准入缺口**：7.3 只要求 usable 为空后才查新可用性；旧管理资格 SQL 不排除 desired_login_state=2 或 breaker。原 OFFLINE 管理员若仍被账号状态报为 ONLINE，先恢复 AVAILABLE，再成为 usable，绕过 TERMINAL。
- 建议补 7.3：任务开关开启时，在恢复原离线管理员前批量复核 AccountRoleAvailability，排除 TERMINAL 后再走旧恢复/资格逻辑；不可恢复时走现有替换/资源等待。关闭开关保留原顺序及 SQL 路径。
- 两项共新增 3 个真实 Mapper H2 边界回归：`/private/tmp/armada-takeover-final-boundary-red.log`，3 tests / 3 failures / 0 errors，BUILD FAILURE（14.103 s）。熔断断言 expected 1 / actual 10；两个管理员断言实际 availability 仍为 AVAILABLE(1)。测试与阶段 4 改动暂未提交。

```bash
cd armada-api
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest='AccountTakeoverStateEventH2Test#replayingSameReservedCreatorKickTenTimesCountsOnceWithoutTripping,PullTaskManagerOfflineGraceH2Test#onlineTerminalOriginalManagerCannotBeRestoredByLegacyEligibility' test
```

### 全量本地测试真实结果（未通过）

```bash
cd armada-api
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn test -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dsurefire.excludesFile=/Users/daishuaishuai/IdeaProjects/armada/.harness/changes/2026-10-08-takeover-business-continuity/real-database-tests-excluded.txt
```

- 日志 `/private/tmp/armada-takeover-full-test.log`：1571 tests / 4 failures / 11 errors / 0 skipped，BUILD FAILURE（14.874 s），forked JVM 最后 Abort trap 6，退出码 134，未跑完整个剩余测试集。
- 7 个 errors 是本地 HTTP 测试绑定端口被沙箱拒绝（GrizzlySmsConfigurationTest 5、HttpFacebookCapiClientTest 2）。其余为旧 H2 fixture 缺 account_creator_deletion、declared_account_type、system_builtin 和不支持 FORCE INDEX；这四项对应 SQL 未由本次改动引入。
- 4 个 failures 分别是既有权限注解期望、群成员角色优先级 SQL 文本断言、HistoricalGroupPullWorker 的 2 项交互期望。本次未修改这些生产方法或断言；不把它们当作本特性的绿色验证，也未扩大范围修复。
- 名称带 DbTest 的 GroupMetadataSyncTask/GroupBatchTask/GroupBatchTaskItem/GroupLinkHealthRefreshBlock 等类源码实际使用 H2，正常保留；88 个真实外部数据库类按清单排除。全程未连接真实数据库、未启动 MySQL 容器。
- 当时等待确认期间暂存区为空；未执行第四次提交、push、部署或 SSH。既有受保护方法和文件仍保留。

### 2026-10-09 用户确认补充的实施与验证

- 新迁移前执行 `ls armada-api/src/main/resources/db/migration | sort -V | tail -3`，输出 V213/V214/V215，使用 V216；V215 未改。新增 last_kicked_at 只表达最近一次已计数事件，不使用风控表、eventId 或载荷摘要；窗口重开与人工 reset 保留水位。
- 原始 occurredAt 为 NULL 时警告并返回 CONTINUE，不写 breaker；状态 SQL 仍用原有归一化时间。锁行后 `occurredAt <= last_kicked_at` 直接沿用当前 tripped 状态，实际计数才更新水位。
- rg 全量核实 OFFLINE→AVAILABLE：实际写入语句为 restoreValidatedAvailability、restoreOccupiedOfflinePuller，调用分布于 ResourceRecovery、markOnline、contact refresh。restoreExpiredPullerCooldowns 无生产调用且只处理风险冷却；其他 AVAILABLE 赋值是新角色初始化。
- ResourceRecovery 的管理员恢复、拉手恢复、拉手重新占用均在任务开关开启时批量查快照并排除 TERMINAL；关闭时不加查询。contact 的过滤集合同时用于后续重新占用。新选号 findOnlineEligible*ByGroupId SQL 不改，人工普通上线后熔断账号可能再被选中的限制记入设计 11。
- 新增 RED：`armada-takeover-dedup-red.log` 52 tests / 4 failures / 1 error（V216 尚不存在），3.911 s；`armada-takeover-puller-terminal-red.log` 4 tests / 4 failures / 0 errors，14.993 s；`armada-takeover-role-restore-red2.log` 6 tests / 4 failures / 0 errors，5.619 s。第一轮 role restore 的 3 errors 为测试 fixture 的 window_start_at 拼写错误，改为真实 window_started_at 后才取业务红输出。
- 首轮补充 GREEN：`armada-takeover-boundary-green1.log`，169 tests / 0 failures / 0 errors / 0 skipped，35.324 s，覆盖去重、迁移、账号上线、角色快照和资源恢复。全部日志位于 `/private/tmp/`。

### 基线对照与 Abort 定位

- 按用户指定建立 `/private/tmp/armada-baseline`（ebef5d92）与 `/private/tmp/armada-head`（a4416a94），没有修改两处源码。两处用同一 Java 17/Byte Buddy 参数运行下面五类，各为 **45 tests / 4 failures / 4 errors / 0 skipped**；耗时分别 32.487 s / 32.734 s。报告复制到 `/private/tmp/armada-takeover-baseline-audit/{baseline,head}/` 后用 `git worktree remove` 删除两处。

| 测试类 | 基线 | 本任务阶段 3 HEAD | 主目录原全量 | 归类 |
|---|---|---|---|---|
| MysqlModeMapperInMemoryTest | 19 / 0F / 2E | 相同 | 相同 | 基线已失败：system_builtin 缺列、H2 不支持 FORCE INDEX |
| BusinessControllerAuthorizationContractTest | 3 / 1F / 0E | 相同 | 相同 | 基线已失败：script_marketing 权限期望缺项 |
| GroupMembershipCountSemanticsMapperH2Test | 15 / 0F / 2E | 相同 | 相同 | 基线已失败：creator_deletion 缺表、declared_account_type 缺列 |
| GroupParticipantRolePrecedenceSqlTest | 3 / 1F / 0E | 相同 | 相同 | 基线已失败：SQL 文本断言 |
| HistoricalGroupPullWorkerImplTest | 5 / 2F / 0E | 相同 | 相同 | 基线已失败：两个交互期望 |

- 对照范围内没有“本任务提交引入”或“只在主目录失败”的差异。遵循用户要求，不修复上述无关失败。日志为 `armada-takeover-{baseline,head}-comparison.log`。
- 两个 socket 测试 GrizzlySmsConfigurationTest、HttpFacebookCapiClientTest 不再运行；先前 7 个错误来自沙箱 bind 端口被拒绝，明确记为环境原因。
- 检查原 `target/surefire-reports`、目标目录和显式 ErrorFile 路径，未产生 `.dumpstream`、`.dump` 或 `hs_err_pid*.log`；用户与系统 DiagnosticReports 也没有 Java 崩溃记录。不能据缺失日志编造 native 堆栈。
- 在测试专用 HEAD worktree 单跑 HistoricalGroupMaterialParserTest 可稳定复现 exit 134 / Abort trap 6。类初始化日志最后为 `java/awt/event/NativeLibLoader`、`sun/lwawt/macosx/LWCToolkit` 初始化，测试 Excel 生成触发 macOS headful AWT 路径。相同 Java 17/agent/test 仅增加 `-Djava.awt.headless=true` 后 **5 tests / 0 failures / 0 errors**，BUILD SUCCESS 2.280 s，足以定位触发路径并规避；没有 JVM 致命日志，无法进一步断言具体 native 栈帧。
- 证据：`armada-takeover-crash-reproduce.log`、`armada-takeover-crash-class-trace.log`、`armada-takeover-crash-class-init.log`、`armada-takeover-crash-headless.log`。后续全量/补跑仅通过测试 JVM argLine 启用 headless，不修改业务或 Maven 项目配置。

### 补跑、扩展对照与最终第四阶段验证

- 从首次中断日志的实际完成类清单减去源码中的本地测试清单，得出 571 个待补跑类，分为 191/190/190 三批（includesFile 存于 `/private/tmp/armada-takeover-completion-batch{1,2,3}.txt`）。第一批 1165 / 2F / 19E，53.152 s；第二批 1342 / 3F / 1E，20.043 s；第三批两次受其他会话的接口/构造器在途修改阻断，最终实际执行 1411 / 2F / 35E，40.313 s。三批均无 JVM Abort。
- 本任务新增 contact Resources 依赖导致 GroupSettingsApplyTiming fixture 缺配置 Bean（8E），已补最小 @Bean，后续独立阶段验证该类 8 项全通过。这是本任务引入并已修复的失败。
- 其他会话同期新增 V217、替换 releaseReservation 接口及终态处理依赖，先造成主目录 compile/testCompile 失败，随后造成 OfflineResourceRecovery 的上下文缺 CreatorDeletionTransactionService。只记录，不修改它们。V217 及其迁移测试、相关 Mapper/Service/SQL 都不纳入本次提交。
- 为确认新增失败归属，再次建立同名、同 ref 的两个测试 worktree。以下 8 类基线与阶段 3 HEAD 都为 **47 / 5F / 9E**（28.840 s / 28.843 s），失败方法完全一致；其中 GroupSettingsApplyTiming 为 8/0/0。随后在两处跑第三批已有类，基线 **1293 / 2F / 2E**，HEAD **1395 / 2F / 2E**，失败也一致，新增阶段 1–3 类全部通过。基线日志与 XML 保存在 `/private/tmp/armada-takeover-baseline-audit/`。

| 追加测试类 | 基线与阶段 3 HEAD 结果 | 归类 |
|---|---|---|
| HistoricalGroupPreviewSchemaSqlTest | 2 / 1F / 0E | 基线已失败 |
| PullTaskMapperBusinessConditionTest | 1 / 1F / 0E | 基线已失败 |
| PullTaskLifecycleMapperInMemoryTest | 7 / 1F / 0E | 基线已失败 |
| PullTaskGroupMarketingGroupMapperInMemoryTest | 11 / 1F / 7E | 基线已失败，fixture 缺 group_classification |
| PullTaskStandardSettingWriterTest | 8 / 0F / 1E | 基线已失败 |
| GroupPullMarketingMaterialEntryServiceTest | 8 / 0F / 1E | 基线已失败 |
| GroupCreationMarketingTaskMapperSqlShapeTest | 2 / 1F / 0E | 基线已失败 |
| PullTaskNormalLinkSchemaSelfTest | 9 / 1F / 0E | 基线已失败 |
| PullTaskStandardCreateServiceTest | 30 / 0F / 2E | 基线已失败 |
| MarketingGroupBanMapperH2Test | 3 / 1F / 0E | 基线已失败 |

- HttpProtocolReadyProbeTest 两例也需要绑定端口，真实执行结果均为 SocketException Operation not permitted，列为环境错误；未擅自修改或删除测试。
- 完成固定 ref 对照并保存证据后，复用测试专用 HEAD worktree，仅装入本任务已保存的第四阶段补丁验证，未在其中开发或带入他人的 WIP。首次复制时并发出现的 EndToEnd.parentCompletion 构造适配 hunk 被核对识别并从临时测试补丁剔除，主目录中该他人 hunk 保留。最终提交亦用只含己方改动的 patch 暂存该混合文件与数据模型文档。
- **独立全量实际结果：5467 tests / 11 failures / 17 errors / 0 skipped，BUILD FAILURE，2:00 min**；846 个 Surefire 类报告（含嵌套类），全部 28 个失败/错误均对应已实跑对照的基线问题（11F/15E）或端口环境（2E），没有本任务未修复失败。88 个真库/容器类、2 个用户指定端口类未运行；没有连接真库、启动容器或再次 Abort。完整日志 `/private/tmp/armada-takeover-own-full-test2.log`。
- **第四阶段最终聚焦 GREEN：313 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS，17.524 s**，共 21 个相关测试类，包含新增去重、全部恢复入口、管理宽限、双开关、事务及端到端回归。日志 `/private/tmp/armada-takeover-phase4-final-green.log`。

```bash
cd armada-api
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -DargLine='-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Djava.awt.headless=true' -Dtest='AccountTakeoverStateEventH2Test,AccountTakeoverPolicyH2Test,AccountTakeoverBreakerMigrationTest,AccountAutoTakeoverDispatcherH2Test,AccountAutoTakeoverOnlineH2Test,AccountReservedCreatorReonlineH2Test,AccountRoleAvailabilityH2Test,AccountTakeoverBothSwitchesOffH2Test,PullTaskManagerOfflineGraceH2Test,PullTaskOfflineResourceRecoveryH2Test,PullTaskOfflineRestoreTerminalH2Test,PullTaskBothSwitchesOffH2Test,PullTaskResourceRecoveryTransactionIntegrationTest,PullTaskPullerAccountStateServiceImplTest,PullTaskPullerOnlineWindowIntegrationTest,PullTaskRoleReconnectWakeH2Test,PullTaskManagerPullerContactTransactionIntegrationTest,PullTaskExecutionEndToEndIntegrationTest,PullTaskGroupSettingsApplyTimingIntegrationTest,PullTaskCreatorOfflineGateH2Test,PullTaskManagerJoinOfflineH2Test' test

JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn test -DargLine='-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Djava.awt.headless=true' -Dsurefire.excludesFile=../.harness/changes/2026-10-08-takeover-business-continuity/real-database-tests-excluded.txt '-Dsurefire.excludes=**/GrizzlySmsConfigurationTest.java,**/HttpFacebookCapiClientTest.java'
```

- 主目录直接 `mvn test` 亦已实际执行完：**5512 / 10F / 928E**，1:34 min，含大量 NoClassDefFoundError；相关源未改而 class 文件稍后重新出现，记录为共享 target 在途构建干扰，不把这次结果当成业务归因依据。进一步使用临时审计 POM 将输出单独放 `/private/tmp/armada-takeover-main-build` 后复跑主目录，以隔离其他会话写入 target；真实 `pom.xml` 不变，临时文件测试后删除。

- **主目录隔离构建输出的最终全量：5523 tests / 11 failures / 76 errors / 0 skipped，BUILD FAILURE，1:46 min**。850 个测试类全部实际由 Surefire 运行；源码默认 Surefire 命名清单共 940 类，减去 88 个真库/容器类和用户指定 2 个端口类，缺跑类为 0。独立本任务快照为 846 类 / 5467 例；其余会话新增的 4 类及既有类新增实例合计 56 例也都实际执行通过。
- 与独立本任务结果逐项比较，新增失败仅为下表 59 个上下文 errors；11F 与另外 17E 完全一致，无新增 assertion failure。59 个实例在主目录因上下文未初始化而没进入业务断言，在本任务独立快照中均已正常执行通过。没有将这些 setup errors 宣称为主目录测试通过，也没有替其他会话修复。

| 只在主目录失败的类 | errors | 已确认原因 |
|---|---:|---|
| PullTaskOfflineResourceRecoveryH2Test | 33 | 其他会话新增 ParentCompletionService 构造依赖，测试上下文缺 CreatorDeletionTransactionService |
| PullTaskGroupSettingsApplyTimingIntegrationTest | 8 | 同上；本任务新增的 Properties Bean 已正确补齐，当前是另一项依赖 |
| PullTaskManagerOfflineGraceH2Test | 13 | 同上 |
| GroupDataPackageTaskResourceH2Test | 5 | 同上 |

- 最终全量日志 `/private/tmp/armada-takeover-main-isolated-full-test.log`；逐类两套结果与分类保存为本目录附件 `2026-10-08-takeover-business-continuity/local-test-results.tsv`（850 行类结果），不重复累计多轮测试次数。临时审计 POM 已删除；两个测试 worktree 已清理。
- 第四阶段新增 43 个 H2/配置迁移测试实例（新增 4 个测试类共 28 例，既有类新增 15 例），另强化 D24 现有 3 个实例。313 项阶段验证、5467 项独立全量与5523项主目录全量的口径如上。新测试不 mock Mapper，全部 SQL 走真实 XML；XML 校验与己方 diff whitespace 检查通过。
- 最终“必须核实”结论：原有 5.3/5.4/5.6/7.1/7.2/7.3 核实均有对应 H2 覆盖；本次额外全文核查恢复入口无遗漏；开关关闭不计熔断、不执行新增快照/超时重读，E26–E28 全部通过。两个开关与所有业务阈值沿用配置。
- 提交所有权：第四阶段 8 个实现/测试/排除清单新文件及 1 个结果台账逐个 add；现有文件使用预先审阅的己方补丁暂存。数据模型文档的 V217 部分和 EndToEnd 的 ParentCompletion 构造适配属于他人，明确剔除。本轮早期再次验证三个指定方法体及原保护文件与快照一致；最终检测到其他会话又给 AccountBatchLifecycleServiceImpl 新增 takeoverByIds 和 executeChunk 适配，已保留并排除，不能再宣称其整文件与最初快照相同。两份原保护测试和 ProtocolAccountCommandRejectedException 仍逐字节一致；既有未跟踪 H2 的已告知参数适配也未提交。提交前检查 `git diff --cached --stat` 和每个索引文件，索引不包含他人新迁移、接口、SQL、测试或原在途改动。
- 遗留：15 类基线失败、1 类端口环境错误、4 类主目录在途上下文错误按用户指令仅记录；人工普通上线后的新选号限制见设计 11。没有 push、部署、SSH、真实数据库或跨仓修改。
