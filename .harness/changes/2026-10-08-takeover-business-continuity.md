# 变更记录：抢登账号业务连续性

- 日期 / 分支 / worktree: 2026-10-08 / `1.0.3-snapshot` / 主仓库 `/Users/daishuaishuai/IdeaProjects/armada`；用户明确要求不创建 worktree、不切分支。
- 需求来源: 用户本轮实施指令及 `docs/superpowers/specs/2026-10-08-takeover-account-business-continuity-design.md` r2。
- 状态: 进行中；阶段 1–3 完成，准备阶段 4。用户确认的 7.2 重读门禁及终止直返约束已写入设计。

## 目标（一句话）

按四阶段实现自动抢登、固定窗口熔断、建群人恢复与角色离线等待，并保证两个开关关闭时保留原行为。

## 缺口拆解 / 任务清单

- [x] 阶段 1：V215 数据结构、两个配置类及全 profile 装配、离线起点维护、真实 Mapper H2 测试、设计文档提交。
- [x] 阶段 2：自动抢登、熔断、补偿扫描、状态与意图保护、提交后续上线。
- [x] 阶段 3：角色可用性、建群人/预留建群人恢复、拉手超时与自动补号、上线唤醒。
- [ ] 阶段 4：管理宽限、两个开关回归、全量测试。

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
