# 变更记录：普通群链接拉人波次调度

- 日期：2026-08-09
- 状态：本地实施完成（广泛回归保留已确认的基线失败）
- 设计：`docs/superpowers/specs/2026-08-09-normal-link-pull-wave-dispatch-design.md`
- 计划：`docs/superpowers/plans/2026-08-09-normal-link-pull-wave-dispatch.md`
- 工作方式：当前本地检出直接修改，不提交、不部署、不连接真实数据库。

## 验收台账

- [x] Schema：V107、波次表、执行/调用/参与者关联字段及无历史数据回填
- [x] Planning：一次事务冻结完整初始波次和按上一波结果筛选的重试波次
- [x] Dispatch：每个调用按持久化间隔独立派发，不等待上一调用回执
- [x] Callback：回执只写事实台账，不推进派发游标或重写派发时间
- [x] Reconciliation：60 秒保护后通过 HTTP 群成员名单一次性收敛未知结果
- [x] Sticky puller：同一拉手持续执行，账号级不可用时按代际安全切换
- [x] Lifecycle：暂停、恢复、结束及历史未完成调用兼容
- [x] Protocol audit：只读核对既有协议回执契约，不修改协议代码
- [x] Verification：聚焦测试、打包、广泛本地回归及静态检查

## 测试证据

### 基线

```text
cd armada-api
mvn -q -Dtest=PullTaskNormalLinkSchemaSelfTest,PullTaskPullCallMapperInMemoryTest,PullTaskPullCallMemberAttemptMapperInMemoryTest test
结果：EXIT_CODE=0
```

### 实施记录

#### Task 1：持久化与 Mapper 基础

```text
RED 1: mvn -q -Dtest=PullTaskPullWaveMigrationSqlTest,PullTaskNormalLinkSchemaSelfTest test
结果：EXIT_CODE=1；新增 2 个迁移测试因 V107 不存在而报 NoSuchFileException，原有 7 个基座测试通过。

RED 2: mvn -q -Dtest=PullTaskPullWaveMapperInMemoryTest test
结果：EXIT_CODE=1；测试编译期找不到 PullTaskPullWaveMapper。

GREEN: mvn -q -Dtest=PullTaskPullWaveMigrationSqlTest,PullTaskNormalLinkSchemaSelfTest,PullTaskPullWaveMapperInMemoryTest,PullTaskGroupExecutionMapperInMemoryTest,PullTaskPullCallMapperInMemoryTest,PullTaskPullCallMemberAttemptMapperInMemoryTest test
结果：EXIT_CODE=0；Tests run: 51, Failures: 0, Errors: 0, Skipped: 0。

XML: xmllint --noout PullTaskPullWaveMapper.xml PullTaskGroupExecutionMapper.xml PullTaskPullCallMapper.xml PullTaskPullCallMemberAttemptMapper.xml
结果：EXIT_CODE=0。
```

关键断言：一个执行行只能有一个活动波次；状态/版本 CAS 生效；派发态不被收集唤醒；
结算后可创建下一活动波次；租户隔离生效；冻结调用和 attempt 可在拉手为空时持久化波次身份。

扩展回归曾暴露既有 Map fixture 未传 `waitResourceTypes` 时 XML 直接调用 `.isEmpty()` 的空指针；
通过 blame/diff 确认与本次列清单改动无关后，将条件改为 null-safe，并由原失败测试验证通过。

#### Task 2：完整波次冻结

```text
RED: mvn -q -Dtest=PullTaskPullWavePlanningIntegrationTest test
结果：EXIT_CODE=1；编译期缺少候选 DTO、完整波次规划服务和规划资源。

GREEN: mvn -q -Dtest=PullTaskPullWavePlanningIntegrationTest,PullTaskMaterialMemberMapperInMemoryTest,PullTaskGroupAccountMapperInMemoryTest,PullTaskPullCallMemberAttemptMapperInMemoryTest,PullTaskPullCallPlanningIntegrationTest test
结果：EXIT_CODE=0；Tests run: 68, Failures: 0, Errors: 0, Skipped: 0。
```

关键断言：21 个料子按 5/5/5/5/1 一次冻结；5 个调用和 21 个 attempt 在返回首调用前全部存在；
活动波次重复 prepare 不重分区；重试波次仅包含明确失败未达 4 次、NOT_STARTED、名单确认不存在；
成功、第四次明确失败和最终 UNKNOWN 被排除；材料与站台顺序稳定；五个调用只得到四个站台时零半成品并进入站台等待。

#### Task 3：粘性拉手与代次防护

```text
RED: mvn -q -Dtest=PullTaskStickyPullerTransactionServiceTest test
结果：EXIT_CODE=1；编译期缺少粘性拉手选择、切换和代次绑定服务。

GREEN: mvn -q -Dtest=PullTaskStickyPullerTransactionServiceTest,PullTaskGroupExecutionMapperInMemoryTest,PullTaskPullCallMapperInMemoryTest,PullTaskPullCallMemberAttemptMapperInMemoryTest test
结果：EXIT_CODE=0；Tests run: 45, Failures: 0, Errors: 0, Skipped: 0。

XML: xmllint --noout PullTaskGroupExecutionMapper.xml PullTaskPullCallMapper.xml PullTaskPullCallMemberAttemptMapper.xml
结果：EXIT_CODE=0。
```

关键断言：首次选择拉手时 generation 从 0 增至 1；账号仍可用时跨调用复用且不递增 generation；
账号级离线/封控/解绑/限流/触达受限/群权限异常可触发换号，传输超时不触发；无替代账号时进入拉手资源等待；
调用与 PLANNED attempt 同步绑定拉手及 generation；A→B→A 后旧 generation 回执不能清除当前 A。

#### Task 4：波内定时派发与统一结算

```text
RED 1: mvn -q -Dtest=PullTaskPullWaveMapperInMemoryTest,PullTaskGroupExecutionMapperInMemoryTest test
结果：EXIT_CODE=1；编译期缺少共享的波次/执行行派发推进 DTO 与 Mapper API。

RED 2: mvn -q -Dtest=PullTaskPullExecutionProcessorTest test
结果：EXIT_CODE=1；编译期缺少波次结算路由服务。

GREEN: mvn -q -Dtest=PullTaskPullWaveDispatchIntegrationTest,PullTaskPullWaveSettlementIntegrationTest,PullTaskBatchAddProcessorTest,PullTaskBatchAddPayloadHydratorTest,PullTaskOperationDelayPolicyTest,ProtocolCommandOutboxServiceImplTest test
结果：EXIT_CODE=0；Tests run: 38, Failures: 0, Errors: 0, Skipped: 0。

纵向回归: mvn -q -Dtest=PullTaskExecutionEndToEndIntegrationTest test
结果：EXIT_CODE=0；Tests run: 1, Failures: 0, Errors: 0, Skipped: 0。

XML: xmllint --noout PullTaskPullWaveMapper.xml PullTaskGroupExecutionMapper.xml PullTaskPullCallMemberAttemptMapper.xml PullTaskPullCallMapper.xml
结果：EXIT_CODE=0。
```

关键断言：五个调用在 1/11/21/31/41 秒分别提交，前四个保持 SUBMITTED 也不阻塞后续调用；
第五个提交后波次转为 COLLECTING，派发完成时间和执行行唤醒时间均为 41 秒；一次 claim 最多提交一个调用；
开放 attempt 按最早 submitted_at + 60 秒等待；全部关闭后只结算一次，并原子创建重试波或进入 CLOSING；
重试波保留执行行级粘性拉手和 assignment generation。旧单调用规划器及其过期集成测试已删除，主纵向闭环改接波次路由并通过。

#### Task 5：回执台账、账号错误分类与波次安全唤醒

```text
RED 1: mvn -q -Dtest=PullTaskPullCallParticipantResultServiceTest,PullTaskGroupExecutionFailureServiceTest test
结果：EXIT_CODE=1；端到端测试仍向回执服务传入已删除的延迟策略，证明旧调度依赖尚未清除。

RED 2: mvn -q -Dtest=PullTaskPullCallMapperInMemoryTest,PullTaskPullCallParticipantResultServiceTest,PullTaskGroupExecutionFailureServiceTest test
结果：EXIT_CODE=1；真实 MyBatis XML 证明 record 的普通 getter 不参与属性绑定，改为显式嵌套记录组件后通过。

RED 3: mvn -q -Dtest=PullTaskUnknownResultReconciliationServiceTest test
结果：EXIT_CODE=1；未知结果服务尚未接入波次安全唤醒协调器。

GREEN: mvn -q -Dtest=PullTaskPullWaveDispatchIntegrationTest,PullTaskPullCallMapperInMemoryTest,PullTaskPullCallParticipantResultServiceTest,PullTaskGroupExecutionFailureServiceTest,PullTaskUnknownResultReconciliationServiceTest test
结果：EXIT_CODE=0；Tests run: 45, Failures: 0, Errors: 0, Skipped: 0。
```

关键断言：提前回执关闭 attempt/call 后仅尝试唤醒 COLLECTING 波次；DISPATCHING 波次的
`next_call_seq`、`next_dispatch_at` 和执行行 `next_run_at` 均不变；迟到成功只裁剪对应的
PLANNED attempt，调用仅在人数归零时取消；离线/重登、限流、群权限异常按当前拉手代次失效，
群不可用终止执行但保留已发布调用继续写事实；未知结果的波次调用不再推进拉手游标。

#### Task 6：60 秒保护后的 HTTP 名单三态收口

```text
RED: mvn -q -Dtest=PullTaskPullCallReconciliationServiceTest,PullTaskPullCallParticipantResultServiceTest test
结果：EXIT_CODE=1；测试编译期缺少名单观察三态与统一收口 DTO，原接口仍只有 boolean present。

GREEN: mvn -q -Dtest=PullTaskPullCallReconciliationServiceTest,PullTaskPullCallParticipantResultServiceTest,PullTaskUnknownResultReconciliationServiceTest,PullTaskUnknownResultReconciliationCoordinatorTest,PullTaskExecutionDispatchPropertiesTest,PullTaskPullWaveSettlementIntegrationTest test
结果：EXIT_CODE=0；Tests run: 45, Failures: 0, Errors: 0, Skipped: 0。
```

关键断言：保护时间未满不认领、不发 HTTP；一次 `GroupMemberListPort` 查询本地收口同调用全部未知号码；
名单成功时区分 PRESENT/ABSENT，只有 ABSENT 释放为重试候选；HTTP 失败、跳过、缺少查询账号统一
关闭为最终 UNKNOWN，真实 retry-candidate XML 返回空；名单认领 CAS 防止并发重复查询；迟到成功可提升最终
UNKNOWN，但迟到失败只更新旧 attempt 事实且两者都不唤醒已结算波次。默认保护/扫描配置为 60,000/30,000 ms。

#### Task 7：生命周期与历史开放调用兼容

```text
RED 1: mvn -q -Dtest=PullTaskStandardLifecycleServiceTest,PullTaskStandardExecutionLifecycleServiceTest test
结果：EXIT_CODE=1；任务结束、单群结束和群封禁后活动波次仍停留在 DISPATCHING。

RED 2: mvn -q -Dtest=PullTaskPullWaveLegacyBootstrapIntegrationTest test
结果：EXIT_CODE=1；新初始波次只包含剩余未消费号码，两个升级前开放调用未被挂接。

GREEN: mvn -q -Dtest=PullTaskStandardLifecycleServiceTest,PullTaskStandardExecutionLifecycleServiceTest,PullTaskGroupBanTerminationServiceTest,PullTaskPullWaveLegacyBootstrapIntegrationTest,PullTaskPullWavePlanningIntegrationTest,PullTaskPullWaveDispatchIntegrationTest,PullTaskPullWaveSettlementIntegrationTest test
结果：EXIT_CODE=0；Tests run: 38, Failures: 0, Errors: 0, Skipped: 0。
```

关键断言：暂停保留波次状态、游标和派发时钟；任务结束、单群结束及群封禁取消活动波次；
已发布调用/attempt 不被生命周期取消扫描覆盖；升级前 PLANNED/SUBMITTED/UNKNOWN 调用按 call_seq 挂接，
命令 ID、幂等键、提交时间、状态和参与者不变；已完成历史调用保持无 wave_id；剩余未消费号码追加到
同一初始波次；最近历史提交未满足间隔时只持久化下一 deadline 并释放租约，不立即补发。

#### Task 8：读模型、结构化日志与纵向验收

```text
RED 1: mvn -q -Dtest=PullTaskStandardReadServiceTest,PullTaskStandardReadMapperInMemoryTest test
结果：EXIT_CODE=1；历史调用没有拉手时详情读取对 primitive long 自动拆箱产生空指针。

RED 2: mvn -q -Dtest=PullTaskPullWaveEndToEndIntegrationTest test
结果：EXIT_CODE=1；纵向测试复用了派发测试的 mock 结算器，收集阶段返回 null，暴露测试装配未覆盖真实结算。

GREEN: mvn -q -Dtest=PullTaskStandardReadMapperInMemoryTest,PullTaskStandardReadServiceTest,PullTaskPullWaveEndToEndIntegrationTest test
结果：EXIT_CODE=0；Tests run: 5, Failures: 0, Errors: 0, Skipped: 0。

COMPILE: mvn -q -DskipTests compile
结果：EXIT_CODE=0。
```

关键断言：调用详情允许计划态/历史数据的拉手账号为空；聚合按号码终态统计，不因多次 attempt 膨胀；
27 个号码冻结为 6/6/6/6/3 五个调用并在 1/11/21/31/41 秒提交；前两次复用拉手 A，
确认限流后后三次及重试波复用拉手 B；20 成功、3 明确失败、2 NOT_STARTED、1 名单不存在和
1 名单查询不可用统一结算后，重试波只包含前六个可重试号码。已增加波次冻结、调用提交、
收集延期/结算、粘性拉手分配/失效及名单核实完成日志；日志不记录手机号、名单或凭据。

#### Task 9：协议层回执契约只读审计

```text
npm test -- --runInBand src/commands/group-participants-executor.test.ts src/commands/pull-task-action-state.test.ts src/commands/master-consumer.test.ts
结果：EXIT_CODE=0；Test Suites: 3 passed；Tests: 48 passed。

npm run lint
结果：EXIT_CODE=0；tsc --noEmit 通过。

按计划逐项 rg ACCOUNT_NOT_ONLINE、NEED_REAUTH、GROUP_PERMISSION_DENIED、
ACCOUNT_REACHOUT_RESTRICTED、RATE_LIMITED、GROUP_UNAVAILABLE、NOT_STARTED、UNCERTAIN、
401、408、409、412
结果：EXIT_CODE=0；所有归一化值均存在于执行器或契约测试。
```

审计结论：协议层现有 `pull_task_batch_add` 已按成员发布
`group.action_result_reported`，包含 `pullCallId + targetJid + outcome + executionState + reasonCode`；
事件 ID 由账号、commandId 和 targetJid 稳定派生并要求 broker ack；调用前账号失败为
`UNKNOWN + NOT_STARTED`，明确成员回执为 `SUCCESS/FAILED + STARTED`，缺失回执及调用后不确定结果为
`UNKNOWN + UNCERTAIN`。本次未修改 `armada-protocol`。

#### 最终验证

```text
mvn -q -Dtest=PullTaskPullWaveMigrationSqlTest,PullTaskPullWaveMapperInMemoryTest,PullTaskPullWavePlanningIntegrationTest,PullTaskStickyPullerTransactionServiceTest,PullTaskPullWaveDispatchIntegrationTest,PullTaskPullCallParticipantResultServiceTest,PullTaskPullWaveSettlementIntegrationTest,PullTaskPullCallReconciliationServiceTest,PullTaskPullWaveLegacyBootstrapIntegrationTest,PullTaskPullWaveEndToEndIntegrationTest,PullTaskStandardLifecycleServiceTest,PullTaskStandardExecutionLifecycleServiceTest test
结果：EXIT_CODE=0。

mvn -q -DskipTests package
结果：EXIT_CODE=0。

xmllint --noout PullTaskPullWaveMapper.xml PullTaskGroupExecutionMapper.xml
PullTaskPullCallMapper.xml PullTaskPullCallMemberAttemptMapper.xml PullTaskMaterialMemberMapper.xml
结果：EXIT_CODE=0。

git diff --check
结果：EXIT_CODE=0。

mvn -q test -Dtest='!*DbTest,!GroupLinkRegistryServiceImplTest,!GroupCreationMarketingTaskServiceImplTest' -DfailIfNoTests=false
结果：EXIT_CODE=1；Tests run: 2257, Failures: 8, Errors: 3, Skipped: 11。

在临时干净 HEAD(ecf8b477) 中单独运行上述全部六个失败类
结果：EXIT_CODE=1；Tests run: 16, Failures: 8, Errors: 3, Skipped: 0；失败项和错误信息逐项一致。
```

广泛回归的 11 个非绿项均已在本次修改前的干净 HEAD 复现，涉及历史群端口旧参数断言、营销 SQL
形状旧断言、业务状态条件既有硬编码、旧阶段号测试数据与旧收口断言；本次没有新增广泛回归失败，
也没有为追求全绿而扩改这些无关模块。新增/重点类物理行数最大为 760，未超过 800 行限制。

## 数据库变更

- 迁移版本：V107（已实现；版本唯一性待最终迁移契约回归）
- 数据回填：禁止；迁移不得包含 INSERT/UPDATE/DELETE 历史数据语句
- 正向与回滚 SQL：同步维护在 `.harness/changes/pull-task-normal-link/`
- 数据模型文档：不得手工编辑；本地无真实库元数据时记录生成器未执行原因

## 部署

未部署；未连接远程环境或真实数据库。
