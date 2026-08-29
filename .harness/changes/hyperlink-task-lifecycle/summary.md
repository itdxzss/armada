# 变更记录：超链任务发布与运行生命周期（H3）

- 日期 / 分支 / worktree: 2026-08-28 / `codex/hyperlink-task-h3` / `/Users/daishuaishuai/IdeaProjects/.codex-worktrees/hyperlink-task/armada-h3`
- 需求来源: `docs/superpowers/specs/2026-08-28-hyperlink-task-shared-contract.md` v1.1、`2026-08-28-hyperlink-task-lifecycle-design.md`、`2026-08-28-hyperlink-task-editor-design.md` 与 `docs/business/hyperlink-marketing-data-model.md` §4
- 状态: 已完成本分支实现与本地验证

## 目标（一句话）

在 Armada 现有 Controller → Service → Mapper 和协议 Outbox 基础上实现超链任务报价、保存、准备、运行生命周期、幂等发送与统计投影的可恢复后端基础。

## 缺口拆解 / 任务清单

- [x] 建立 10 张超链任务表、全部约束/索引及回滚脚本，并把任务/模板标题能力统一为 1024。
- [x] 实现 task/content/runtime/recipient/claim/billing/round/usage/round_account/account_stat 实体、Mapper 和 XML。
- [x] 实现 quote、create、update、provision-status、action HTTP 合同与稳定业务错误。
- [x] 实现 enabled=false 仅保存、启用准备、recipient 分批领取和本地 billing Saga；真实钱包仅保留可插拔端口。
- [x] 实现三种模式、账号容量/分派、START/PAUSE/RESUME/STOP 状态机和稳定 commandId/outbox。
- [x] 实现 recipient 状态单调推进、发送指标投影和 reconciliation 标记。
- [x] 扩展 Java 通用 MessageTarget、HyperlinkCorrelation 与 send-result/ACK 唯一路由，兼容现有群营销关联。
- [x] 完成状态非法转移、recipient/command 幂等、暂停/继续/停止、ACK 乱序和草稿无执行事实测试。

## 关键设计决策

- 沿用现有 `com.armada.hyperlink` 域，并新增 `task` 功能子包；不新建 Repository，Service 直接调用 Mapper。
- 不修改公共契约和冻结设计；设计中的 application/domain/repository 是概念分层，代码按 Armada 工程规则收敛。
- 同任务同号码只保留一行 recipient；协议未知结果保持 SENDING 并登记原 commandId 对账时间，不建 attempt 表、不换账号重发。
- UPDATE 对所有未开始编辑先确认没有 commandId；冻结范围变化进入 PROCESSING，后台每批 500 行释放旧事实后按当前配置重建。
- STOP 同步进入终态并异步分批失败无 command 的 PENDING；已提交 recipient、ACK、点击与其数据包来源事实均保留。
- `HyperlinkBillingGateway` 是外部账务端口；本次只提供默认关闭的不可用实现，严禁模拟余额或调用真实钱包。
- PRIVATE 发送能力必须由门禁显式允许；默认关闭，测试通过注入测试态能力实现覆盖。
- 任务运行事实以 MySQL/MyBatis 为准，worker 状态、投影和恢复均不使用生产内存兜底。

## 验证（evidence-before-done）

- `mvn -Dtest='com.armada.hyperlink.task.*Test,com.armada.hyperlink.template.service.HyperlinkMessageContentValidatorTest,com.armada.platform.protocol.backend.web.WebMessageSendBackendTest,com.armada.platform.protocol.backend.android.AndroidMessageSendBackendTest' test`：48 tests，0 failure/error。
- `mvn -Dtest='ProtocolMessageEventConsumerTest,MarketingSendResultServiceImplTest,HistoricalGroupSendResultServiceImplTest,ProtocolKafkaConfigurationTest,ProtocolKafkaListenerConfigurationTest' test`：30 tests，0 failure/error。
- `mvn -Dtest='HyperlinkTaskApiShapeTest,HyperlinkTaskCleanupFlowTest,HyperlinkTaskStateMachineTest,HyperlinkRecipientStateMachineTest,HyperlinkProtocolUnknownResultTest,HyperlinkProtocolAckRoutingTest,HyperlinkTaskLifecycleMigrationSqlTest' test`：17 tests，0 failure/error；含真实 recipient Mapper XML + H2 MySQL 模式故障窗口验证。
- `find armada-api/src/main/resources/mapper/hyperlink -name '*.xml' ... xmllint --noout`：通过。
- `mvn -DskipTests compile`：BUILD SUCCESS。
- `.harness/wiki/gen_datamodel.py` 依赖 `/tmp/wheel_tables.tsv` 的真库导出；本任务禁止连接真库，未生成数据模型 wiki。

### 2026-08-29 计费恢复加固

- [x] 把本地预约状态补全为可恢复的 reserve / adjust / settle / release Saga；所有外部动作先持久化意图并以原 operation key 重放。
- [x] STOP 清理在 recipient 状态收敛后，按唯一实际发送 recipient 及冻结国别单价结算，再释放未消费金额；重复收口不重复扣款。
- [x] FAILED 的准备作业由 START 恢复原 claim / billing 作业，不再伪装为新一轮调度；领取尚未发起钱包调用时可本地补偿结束零金额预约。
- [x] 编辑重建使用包含外部预约号、任务 ID 与任务版本的新 operation key，旧 reserve key 不复用。
- [x] 未新增数据表或字段；真实钱包适配器缺失时仍 fail-closed，不创建本地余额或模拟账本。
- 验证：`mvn -Dtest='com.armada.hyperlink.task.*Test,com.armada.hyperlink.task.service.HyperlinkBillingOperationKeysTest' test` 共 30 tests，0 failure/error；`mvn -DskipTests compile` 与 3 个改动 Mapper XML 的 `xmllint --noout` 均通过。

### 2026-08-29 短链发送、审计门禁与迁移验收修复

- [x] `shortCode` 改为 `SecureRandom` 生成的 16 位 Base64URL 字符串；仅在启用短链时生成，并依靠 V158 的全局唯一键在同一 recipient / command 上重试碰撞。
- [x] LINK_CARD 推广 URL 与 BUTTON_CARD 唯一 CTA 仅在对应短链开关启用时改写为 `{publicBaseUrl}/api/public/hl/{shortCode}`；关闭时保留冻结原 URL。H6 的公网落点和五个分析端点只做合同锁定，本变更不实现点击记录或归因。
- [x] 仓库没有可复用的通用持久审计落点，因此新增最小 `HyperlinkTaskAuditPort` 合同并默认 fail-closed；create / update、START / PAUSE / RESUME / STOP 与 reserve / adjust / settle / release 均已接入，普通日志不计作审计。计费审计复用钱包 operation key 作为 eventId，已完成幂等重放不重复登记。
- [x] V158 结构测试固定为用户确认的 10 张任务表，并排除 recipient_round、attempt、ban、click、30 分钟桶等已否决表；recipient Mapper 通过 H2 MySQL mode 实际验证 `short_code` 唯一约束，MySQL 专有生成列/JSON DDL 仅做结构门禁，未宣称运行真 MySQL。
- [x] 四个任务扫描器按候选隔离异常，单任务失败不再阻断同批次后续任务，并恢复扫描前租户上下文。
- 验证：超链 task/service/scheduler 定向测试 69 tests、0 failure/error；`mvn -DskipTests compile` 与全部 hyperlink Mapper XML 的 `xmllint --noout` 均通过。未连接或运行真实 MySQL。

V158 中两组未逐字段列入业务数据模型表格、但属于现有运行合同的列予以保留，读写证据如下：

| 合同字段 | 写入方 | 读取方 / 必要性 |
|---|---|---|
| `hyperlink_task_runtime.failure_code/failure_reason` | `HyperlinkProvisioningService` 失败路径经 `HyperlinkTaskRuntimeMapper.markProvisionFailed` 写入；恢复/就绪/重建 Mapper 路径清空 | `HyperlinkTaskStoreService.receipt` 返回异步准备失败的稳定业务码与脱敏原因，供 create/update/START 后的 `provision-status` 合同读取 |
| `hyperlink_task_account_usage.protocol_id_snapshot/protocol_account_id_snapshot/protocol_backend` | `HyperlinkFirstRoundService.usage` 在账号首次进入任务时冻结 | `HyperlinkDispatchService` 做协议能力门禁并复制 recipient 路由快照，`HyperlinkMessageCommandFactory` 构造稳定 `ProtocolAccountRef`；账号资料变化或进程重启后仍需使用任务冻结时的协议路由 |

门禁说明：真实 `HyperlinkTaskAuditPort` 适配器和 `armada.hyperlink.public-base-url` 是启用相应能力的外部依赖；缺失时写操作/短链发送失败关闭。`.harness/wiki/数据模型.md` 只能由真实 MySQL `information_schema` 导出后按 `gen_datamodel.py` 生成，本 worktree 未获真库导出，未手工伪更新 wiki。

#### 短链配置审查修复

- `armada.hyperlink.public-base-url` 统一由 `HyperlinkShortLinkGuard` 解析；除 HTTP(S) 与有效 host 外，明确拒绝 URI user-info、query 和 fragment，避免凭据泄漏或端点拼接歧义。
- 启用创建、启用更新及已有短链任务 START 都在 task/version/runtime/round 本地写入前检查公网基址；缺配置返回稳定 `HYPERLINK_DISPATCH_GUARD_UNAVAILABLE`，disabled draft 仍可保存，派发期 URL 构造继续保留最后防线。
- 运维门禁：已有 RUNNING 短链任务期间不得移除公网基址配置，部署前必须校验该属性。若应用重启后违规移除，消息构造会失败关闭且事务回滚，但现有扫描器仍会重试该候选；本修复不为此扩建 scheduler 状态机。

### 2026-08-29 派发批量与无账号重查门控

- [x] 派发扫描对每个到期任务单轮最多提交 50 个 recipient；一旦账号容量或待发 recipient 耗尽立即停止，且每次 `dispatchOne` 仍保持独立短事务。
- [x] `NO_ACCOUNT` 与未到期 `PLANNED` 轮次统一遵守 `next_dispatch_at`，不再被 1 秒生命周期扫描提前反复选号；生命周期全局索引同步加入该到期列。
- [x] 未新增表、列或业务状态；`50` 仍是冻结设计中的调度批量切片，不进入业务字段或计费口径。
- 验证：`HyperlinkSchedulerCandidateIsolationTest`、`HyperlinkRuntimeRoundMapperH2Test`、`HyperlinkTaskLifecycleMigrationSqlTest` 共 17 tests，0 failure/error；`mvn -DskipTests compile` 通过。Mapper XML 已单独执行 `xmllint --noout`。

### 2026-08-29 报价过期清理收敛

- [x] OWNED claim 因本地人数校验进入 `40911` 时，编辑重建、停用或合法 STOP 清理会先原子结束从未调用钱包的旧 RESERVE，再释放 recipient/claim，避免后台永久重放旧报价。
- [x] 本地结束仅接受 FAILED + pending RESERVE + `40911` + 无外部预约号，且冻结、结算、释放金额和结算发送数全部为零，`reserved_at`、`settled_at`、`released_at` 全为空；任一外部计费事实存在时失败关闭。
- [x] 普通未知 RESERVE 不走本地结束，继续使用原 operation key 恢复；未新增表、列或业务状态。
- 验证：`HyperlinkTaskCleanupFlowTest`、`HyperlinkBillingSagaH2Test`、`HyperlinkQuoteStaleRecoveryH2Test` 共 34 tests，0 failure/error；真实 Billing Mapper XML 在 H2 MySQL mode 执行，改动 XML 已通过 `xmllint --noout`。

### 2026-08-29 recipient 指标增量投影

- [x] 分钟级主路径改为每事务 `FOR UPDATE SKIP LOCKED` 领取最多 500 条变化 recipient，按任务、轮次、任务账号桶合并状态净增量后做列级原子更新；只回写本批 projected 状态。
- [x] 每次调度最多执行 8 个短事务，即最多处理 4000 条变化；不再因一条 ACK 对最多 10 万 recipient 的任务做全量重扫。
- [x] `submitted_at` 为空的本地拒绝和 STOP 只增加失败数，不增加 `send_total`；NULL account 进入未分配桶，SUCCESS → DELIVERED → READ 保持包含指标单调推进。
- [x] runtime 的使用号数从小基数 account_stat 去重桶刷新，封号数从 account_usage 首次失效事实刷新；均限定当前任务，不退回 recipient 全量扫描。
- [x] 保留按任务 `reconcile(taskId)` 全量事实校准入口，但分钟调度不再调用；未新增表、列或状态。
- 验证：`HyperlinkMetricsProjectionH2Test`、`HyperlinkTaskLifecycleMigrationSqlTest`、`HyperlinkSchedulerCandidateIsolationTest`、`HyperlinkRuntimeFenceSqlShapeTest` 共 16 tests，0 failure/error；`mvn -DskipTests compile` 与 4 个改动 Mapper XML 的 `xmllint --noout` 均通过。未连接真实 MySQL。

#### 指标投影锁序审查修复

- 投影器先无锁读取最多 500 个候选主键，按 `tenant_id + task_id` 稳定顺序锁 runtime、相关 round，再按精确主键和 `needs_metrics_projection=1` 领取实际 recipient；重叠 worker 不重复投影。
- dispatch 在 runtime shared fence 后立即显式锁 active round，再进入 usage、recipient；显式 reconciliation 同样先锁 runtime 再更新 round，统一锁序为 `runtime → round → usage → recipient`。
- 未改变批次上限、调度上限和任何统计口径，未新增表、列或状态。
- 验证：锁序、两 worker/H2、dispatch 与生命周期相关的 8 个定向测试类共 35 tests，0 failure/error；JDK 17 `mvn -DskipTests compile`、2 个改动 Mapper XML 的 `xmllint --noout` 与 `git diff --check` 均通过。未连接真实 MySQL。

### 2026-08-29 跨任务账号全局并发保护

- [x] 复用 Armada 现有 Redis 连接，为每个 `accountId` 建独立 ZSET；holder 固定为 recipient 的稳定 `commandId`，Lua 原子清理过期 holder、幂等续租并按冻结上限 20 获取容量。租期使用消费端当前时钟，不能使用可能滞后的协议事件时间。
- [x] Redis 异常或续租失败统一抛 `50311` 并失败关闭；容量已满只回退任务内 usage 槽并延后该账号候选，不把 recipient 写成失败。
- [x] 派发在任务 usage 占槽后锁全局 `account` 行，并对同租户同账号最多 20 条 `send_status=2` recipient 做当前读；数据库是跨任务硬容量边界，Redis holder 过期或丢失也不能放行第 21 条。
- [x] V158 增加 `(tenant_id, account_id, send_status, id)` 索引；容量满、账号消失或 Redis 容量满时完成一次退避即结束当前事务，不继续累积第二个账号锁。
- [x] DB 回滚和本地 adapter 拒绝在事务完成后释放；outbox 接受且 DB 提交后保留，UNKNOWN/原命令重放续租，SUCCESS/DELIVERED/READ/最终失败仅在终态事实提交后释放。
- [x] 未新增表、列或业务状态；reconciliation 查询只补回既有 recipient.`account_id`，确保重放续租同一账号 holder。
- [x] holder TTL 固定 600 秒作为运维续租窗口，不再承担容量正确性证明；容量正确性由账号行锁和数据库 SENDING 当前读保证。
- 验证：账号 guard、派发事务、账号并发行锁、跨任务/租户 SENDING 当前读、滞后 UNKNOWN、结果恢复及真实 Mapper H2 共 34 tests，0 failure/error；未连接真实 Redis、MySQL 或远程环境。

### 2026-08-29 审定分支容量重检与历史双图文发送门禁

- 分支 / worktree：`codex/hyperlink-task-h3-reviewed` / `/Users/daishuaishuai/IdeaProjects/.codex-worktrees/hyperlink-task/armada-h3-reviewed`。
- [x] 启用创建、disabled → enabled 编辑、保持 enabled 编辑和 START 均在 quote、版本、runtime、计费及准备事实写入前重检 `maxExecutingAccounts <= protocolCount * 15`；等于边界允许，零协议拒绝，disabled 草稿不查询容量。
- [x] 协议数复用 H2 create-context/account-match-count 的 `HyperlinkAccountCandidateSelector.protocolCount()` 事实链：PRIVATE 能力门禁后，由账号域按 `protocol_backend + protocol_address` 去重统计；`×15` 只在 `HyperlinkProtocolCapacityService` 计算。
- [x] 超限固定返回 `42211 HYPERLINK_PROTOCOL_CAPACITY_INSUFFICIENT`，未新增表、列、发送事实或隐藏状态。
- [x] 竞品静态前端观察到历史双图文为链接预览卡加正文主图；现有 Web/Android 私聊合同均只能表达单一 LINK_CARD 或 BUTTON_CARD，无法真实表达该复合语义。因此新建继续拒绝 type=2，历史任务在 START 和命令构造双层以 `40001` 明确失败关闭，不再落入按钮 serializer。
- [x] Web/Android 协议 worktree 无需变更，integration worktree 未修改；未连接真实钱包、数据库、Redis 或远程协议。

验证记录：

- H3 定向轮：主代码 1701 个源文件、测试 693 个源文件编译通过；真实 `AccountMapper.xml` H2 模式 6 项通过。其余 Mockito 用例共 32 项在执行断言前因本机 macOS 26 禁止 Byte Buddy 自附加而报环境错误，无业务断言 failure。
- 相关广域轮：194 项中 81 通过、0 assertion failure、109 项在执行断言前因同一 Mockito attach 环境错误、4 skipped；主/测试源码仍编译通过。按用户要求未运行第三轮。

## 部署

- commit / 环境 / 部署后验证结果: 不部署；仅提交当前 H3 分支。

## 遗留 / 跟进

- Web/Android 私聊 serializer、真实账号真机消息与 ACK 能力需跨仓授权测试，未通过前 PRIVATE 能力门禁保持关闭。
- 真实钱包提供方、价格目录与结算规则是上线硬依赖，本分支不得以测试实现冒充。
- 仓库当前没有跨域统一持久审计落点；已提供 fail-closed 的最小审计 Port，待真实审计系统接入前任务变更与钱包动作保持关闭。
- 任务指标对账后的自动完成触发由后续运行收口变更负责；本修复只提供可幂等调用的计费最终收口服务。
