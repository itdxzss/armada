# 变更记录：普通群链接逐号码重试与异常接管

- 日期 / 分支 / worktree: 2026-08-08 / `1.0.2-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户本次确认口径；`docs/superpowers/specs/2026-08-08-normal-link-pull-retry-takeover-design.md`
- 实施计划: `docs/superpowers/plans/2026-08-08-normal-link-pull-retry-takeover.md`
- 状态: 本地实施完成，待用户审阅；未提交、未发布

## 目标（一句话）

普通群链接批量拉人按站台号和料子号逐号码收口，明确失败额外重试三次，拉手异常导致的未知结果经每批一次群成员名单核实后释放接管，并统一持久化 3～5 秒 WhatsApp 副作用静默。

## 缺口拆解 / 任务清单

- [x] Web 协议端报告 `outcome + executionState`。
- [x] Android 协议端使用相同逐号码合同。
- [x] Armada Kafka 消费端校验并传递 `executionState`。
- [x] Flyway V106 和逐号码 attempt Mapper。
- [x] 料子号与站台号聚合 CAS 状态转换。
- [x] 新批次规划、载荷水合与下一拉手选择。
- [x] 当前回调、迟到回调、失败三次重试和批次关闭。
- [x] 未知批次一次名单核实与释放。
- [x] 3～5 秒统一随机静默及原拉人间隔取最大值。
- [x] 生命周期、统计、端到端与并发恢复验证。

## 关键设计决策

- 初次执行不算重试；单号码最多执行四次明确失败，第 4 次明确失败进入最终失败。
- `UNKNOWN` 不增加失败次数；`NOT_STARTED` 直接释放，`UNCERTAIN` 或缺失回调沿用 60 秒结果窗口后核实。
- 每个异常批次先以批次级 CAS 持久化名单核实认领，再调用一次只读名单查询；服务重启不得再次查询。
- 重试和接管永远新建批次、命令 ID 和逐号码 attempt，不重置或重发已提交批次。
- 成功是不可降级事实；旧 attempt 迟到失败只记审计，迟到成功可提升聚合状态。
- 下一可用拉手优先；没有其他拉手时复用仍可用的原拉手，否则进入现有拉手资源等待。
- 批量拉 10 个号码仍是一条 WhatsApp 副作用；随机静默在批次收口时只抽取并持久化一次。
- 不处理历史数据，不新增前端配置，不做发布、远程、真库或数据回填。
- 用户明确要求在现有本地项目工作，所有改动保持未提交状态。

## 验证（evidence-before-done）

### 开工基线

- Web：`npm test -- --runInBand src/commands/group-participants-executor.test.ts`，28 tests 通过。
- Armada：`mvn -q -Dtest=ProtocolGroupEventConsumerTest,ProtocolPullTaskBatchParticipantResultAdapterTest,PullTaskProtocolResultCallbackServiceImplTest,PullTaskUnknownResultReconciliationServiceTest,PullTaskPullCallPlanningIntegrationTest,PullTaskBatchAddPayloadHydratorTest test`，退出码 0。
- Android：沙箱内因 miniredis 无权绑定本地端口失败；授权本地执行 `go test ./internal/armada` 后通过。
- 计划命令修正：Armada 是单 Maven 工程，命令改为在 `armada-api/` 直接运行，不使用 `-pl armada-api`；全量测试排除真库 `DbTest`。

### Web executionState 合同

- RED：executor/master 聚焦测试 43 个用例中 8 个按预期失败，证明缺少 `executionState`、调用前失败仍被映射为整批 `FAILED`、过期 targetless 缓存只发布一条。
- GREEN：`npm test -- --runInBand src/commands/pull-task-action-state.test.ts src/commands/group-participants-executor.test.ts src/commands/master-consumer.test.ts`，3 suites / 44 tests 通过。
- 静态门禁：`npm run lint` 与 `npm run build` 均退出码 0。
- 补充边界：无 owner worker 属于调用前未开始，批量结果逐号码发布 `UNKNOWN + NOT_STARTED`；单目标邀请/提权保持既有失败语义。

### Android executionState 合同

- RED：`go test ./internal/armada` 编译失败，明确暴露 `ExecutionState` 字段和三种枚举尚不存在。
- GREEN：`go test ./internal/armada` 通过；覆盖调用前离线、明确逐成员回执、缺失回执、原生调用异常、操作门禁失败和陈旧整批结果逐号码展开。
- 竞态验证：`go test -race ./internal/armada` 通过。
- 静态与构建门禁：设置沙箱临时 `GOCACHE` 后，`go vet ./...`、`go build ./...` 均退出码 0；`git diff --check` 通过。
- 向后兼容：非批次结果仍允许省略 `executionState`；升级前批次缓存会在发布前保守补成明确结果 `STARTED`、未知结果 `UNCERTAIN`，不会重放 WhatsApp 操作。

### Armada executionState 接入

- RED：聚焦测试编译失败，证明任务域尚无逐号码执行阶段枚举。
- GREEN：`mvn -q -Dtest=ProtocolGroupEventConsumerTest,ProtocolPullTaskBatchParticipantResultAdapterTest test` 通过。
- 消费门禁：批次事件必须显式携带大小写敏感的执行阶段，并只接受 `SUCCESS/FAILED + STARTED`、`UNKNOWN + NOT_STARTED/UNCERTAIN`；重试决策仍未放入 consumer。

### 逐号码台账与聚合 CAS

- RED：迁移/台账测试首先因 V106、实体、枚举和 Mapper 均不存在而编译失败；聚合测试随后因 attempt 绑定与转换 DTO 不存在而失败。
- GREEN：`mvn -q -Dtest=PullTaskParticipantAttemptMigrationSqlTest,PullTaskPullCallMemberAttemptMapperInMemoryTest,PullTaskMaterialMemberMapperInMemoryTest,PullTaskGroupAccountMapperInMemoryTest test` 通过。
- 台账约束：同批参与者唯一、同参与者只有一个活动 attempt、执行序号唯一；释放在同一次 CAS 中清空 `active_slot`，随后才可创建新 attempt。
- 聚合规则：料子与站台都要求状态、活动 attempt 和失败计数同时匹配；前三次明确失败回池，第 4 次终态；未知释放不计次；成功使用独立单调提升 SQL，旧成功不会清除更新 attempt 指针。
- V106 只新增结构，无 DML 回填；未创建 attempt 的历史批次不进入新状态机。

### 规划、重试、接管与迟到回调

- 新批次从待处理料子与待重试站台生成不可变 attempt；载荷只从 attempt 水合，不会把旧批次剩余成员重新拼回原命令。
- 明确失败统一计数：第 1～3 次失败释放回池，第 4 次进入最终失败；协议 `retryable` 不改变该规则。
- 精确覆盖 10 号码场景：1～4 成功保留，5 明确失败计数一次，6～10 未回调经名单核实后释放；下一拉手的新批次只包含 5～10，顺序保持稳定。
- 新拉手按现有轮转顺序优先选择；只有没有其他可用拉手时才复用原拉手，否则保持 `WAIT_RESOURCE`。
- 当前 attempt 成功/失败/未知按 CAS 收口；旧 attempt 迟到失败只留审计，迟到成功可单调提升聚合成功事实。
- 料子全部完成但站台仍需重试时，会生成只含站台的新批次，不会提前关闭执行行。

### 未知结果与生命周期

- `NOT_STARTED` 无协议副作用，直接释放，不调用群成员名单协议。
- `UNCERTAIN` 和缺失结果等待既有 60 秒窗口；每个批次通过持久化 CAS 最多认领一次名单核实。
- 名单中存在的号码收口成功；名单中不存在或名单查询失败的号码释放回池且不增加失败次数。
- 任务停止、取消和执行行终止会取消尚未发布的计划/提交 attempt；已经发布且结果未知的 attempt 保留给回调或核实流程收口。

### 3～5 秒副作用静默

- Web 与 Android 的批量参与者操作仍各只调用一次 WhatsApp 批量协议，不在同一批号码之间睡眠。
- 后端使用 `PullTaskOperationDelayPolicy` 生成 3,000～5,000 ms，并持久化到 `next_run_at`；没有引入 `Thread.sleep`。
- 拉人批次下一执行时间取既有拉人间隔与本次随机静默约束的最大值；只读名单查询和纯数据库状态切换不增加静默。

### 最终本地回归

- Web：`npm test -- --runInBand`，64 suites / 583 tests 通过；`npm run lint`、`npm run build`、`git diff --check` 通过。
- Android：`go test -race ./internal/armada`、`go vet ./...`、`go build ./...`、`git diff --check` 通过；`go test ./internal/armada` 亦通过。
- Armada：本次修改/新增的 28 个测试类在 Java 17 下全部通过，覆盖协议接入、V106、Mapper CAS、计划、回调、核实、静默、生命周期、读模型和端到端流程。
- Armada：`mvn -q -DskipTests package` 通过；本次精确 10 号码接管场景和站台独立重试场景均包含在通过的计划集成测试中。
- Armada：排除真库/环境类及下述已确认基线失败类后的近全量回归为 425 个测试类 / 2202 tests / 0 failure / 0 error / 11 skipped。
- 三个项目均执行 `git diff --check`，无空白错误。

## 部署

- 不在本任务范围内；用户自行发布。
- 本地实现期间不 commit、不连接远程或真实数据库。

## 遗留 / 跟进

- Android 全量 `go test ./...` 在本机 Go 1.26.5 下仍有未修改包的基线失败：`internal/service/utils/promise` 存在测试结束后 goroutine 写日志 panic，`pkg/noise` 的固定向量测试失败；项目 `go.mod` 声明 Go 1.25.1。本次变更包、竞态、vet 和 build 均通过。
- Armada 初次全量回归为 2218 tests / 9 failures / 5 errors / 11 skipped；隔离后修复了本次新增表断言及两条 4 秒静默测试时间线。剩余失败均稳定复现在未修改的历史群、营销 SQL 形状、Mapper 业务条件、Closing 字面量、补拉手和补站台测试类，未扩展本需求处理。
- 发布、迁移执行和历史数据处理仍由用户自行安排；本地改动保持未提交。
