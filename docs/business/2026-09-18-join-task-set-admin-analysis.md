# 进群任务「设置管理员」开关需求分析

- 日期：2026-09-18。
- 范围：独立进群任务 `/task/join`，不是普通拉群任务的管理员补充。
- 阶段：用户已授权在主工作区实施，代码与本地测试已完成；数据库与远程环境未修改。下文保留前期分析，最终行为以第 10 节为准。
- 事实基线：Armada 主工作区 `1.0.3-snapshot`，HEAD `6aa319cd`，含其他会话在途修改；前端、Web 和 Android 协议均以本地当前源码为依据，不能据此断言线上版本已有相同能力。

## 1. 需求与推荐口径

用户后续截图与文字明确：创建进群任务在“分配方式/执行间隔”之后、“失败处理”之前，新增独立的「群管理设置」区域，区域内本期增加「设置管理员」开关。这是任务配置入口；账号选择表格中已有的“管理员设置状态”列不能代替此开关。

建议开关默认关闭，旧请求未传时按关闭处理。开关随任务保存、详情回填，仅允许草稿编辑；作用于计划中实际分配的每个“账号 × 群链接”明细。

开启后，每条明细顺序执行：确认目标号码进群成功 → 找到我们原先就在本群中、有管理员权限且当前可执行的受控账号 → 由该原有管理员设置本次进群号码为管理员 → 确认成员级设置结果 → 本步骤成功。群主与普通群管理员均可作为执行者，不要求必须群主，也不局限于本次选中的进群账号分组。执行者须是另一名具备权限的账号，不能让新进群普通账号给自己设置管理员。

进群账号和设置管理员的执行账号是两个角色。第二阶段按执行管理员的协议后端路由，不能沿用目标号码的协议后端。仅提权本条目标，不能误操作执行者、其他群成员或整个账号分组。

用户还明确后续将在此区域增加「踢出其他管理员」。本期仅实现设置管理员，页面分区为后续功能留出组织位置；不提前增加可执行开关、数据库死列或踢人逻辑，也不将后续“踢出”擅自解释成仅取消管理权限。

进群任务详情必须分别体现进群与设置管理员的结果，包括失败原因。以上页面位置、分区名称、当前开关、操作账号角色和结果展示已由用户明确；异常等待时长等最终默认行为见第 10 节。

## 2. 实施前确认的事实

| 项目 | 当前源码事实 | 缺口 |
| --- | --- | --- |
| 配置 | `CreateJoinTaskDTO`、`JoinTask` 和前端 `CreateJoinTaskRequest` 有重试配置，没有设置管理员开关 | 新增配置持久化、创建/编辑/详情/列表契约与前端控件 |
| 进群确认 | `JoinTaskResultServiceImpl.apply` 接到 `JOINED` 或 `ALREADY_JOINED` 后立即 `markTerminalSuccess`，再 `advanceAfterTerminal` | 开启开关时必须继续进入设置管理员阶段，不能此时推进下一条 |
| 审批 | 当前 `PENDING_APPROVAL` 记为失败原因 `JOIN_PENDING_APPROVAL` | 审批中不算进群成功，不发提权；本次不扩展自动审批功能 |
| 串行与汇总 | `JoinTaskResultMapper` 按账号激活下一条；`JoinTaskMapper.refreshCounters/markDoneWhenNoPending` 只检查进群 `status` | 必须把管理员阶段纳入账号顺序、汇总和任务结束判定 |
| 管理员展示 | 明细已存 `is_admin/promoted_at`；前端显示两态。当前 `JoinTaskServiceImpl` 直接读该字段，检索当前生产源码未找到对应主动提权或更新这两个列的写入 SQL | 字段和旧注释不能证明回执闭环存在，不能直接依赖该布尔值判定新功能成功 |
| 管理员候选 | `GroupExecutionAccountSelector` 和 `AccountGroupMembershipMapper` 已支持按群 JID、当前在群及管理员角色挑选在线账号 | 需提炼为群域能力，并核实进群任务授权范围；不直接耦合 PullTask 业务状态机 |
| 协议能力 | Web `group-participants-executor.ts`、Android `group_participants_sender.go` 支持单目标 PROMOTE 和成员级结果 | 现有异步 source/关联字段主要绑定 PullTask，尚无进群提权契约 |
| 回执入口 | `ProtocolGroupEventConsumer.handleActionResultReported` 要求 `pullTaskId/groupExecutionId/actionId` 并校验来源白名单 | 新 source 必须在这些字段校验之前分流，不能伪造拉群任务 ID |

## 3. 结果与状态设计

建议保留现有 `join_task_result.status` 的“进群结果”含义，另加管理员阶段状态；接口派生整体步骤结果，不再持久化第三套重复状态。

原因：`MarketingTaskExportMapper.xml` 根据 `jtr.status = 'SUCCESS'` 读取进群事实；`PullTaskGroupMarketingCandidateMapper.xml` 用成功进群及 `is_admin` 判断自收群来源。不能将提权失败伪装为未进群。

| 进群结果 | 管理员阶段 | 整体步骤 | 能否推进下一条 |
| --- | --- | --- | --- |
| 待处理 | 未开始 | 执行中 | 否 |
| 失败 | 不执行 | 失败终态 | 按现有失败流程推进 |
| 成功 | 开关关闭，不要求设置 | 成功 | 是 |
| 成功 | 等待可用管理员/待派发 | 执行中 | 否 |
| 成功 | 已提交/核实结果中 | 执行中 | 否 |
| 成功 | 已确认管理员 | 成功 | 是 |
| 成功 | 明确失败且不再重试 | 失败终态，保留进群成功 | 是，建议口径 |
| 成功 | 查询仍无法确认 | 继续核实；到期显示“设置结果未确认” | 未到期不推进；到期如何终结需统一策略 |

“步骤成功”和“失败后结束本条”应区分。设置失败不能进入成功计数；任务 DONE 目前表示全部明细已结束，不保证全部成功。界面应显示成功/失败数量，避免将 DONE 展示成“全部成功”。

进群事实确认后，应照常更新群关系及既有进群相关事件；本需求控制该进群步骤的完成，不隐式增加其他独立营销任务必须等待提权的规则。

### 3.1 成功证据

1. 进群：可信 `JOINED`；或 `ALREADY_JOINED` 且能确认准确群 JID。当前 ALREADY_JOINED 分支允许空 JID，开关开启时必须补查询，不能拿空群身份发提权。
2. 设置管理员：当前命令对应目标成员的明确成功回执；或新鲜成员查询明确目标仍在群中且角色为 admin/superadmin。
3. Outbox 入库、Kafka 发布成功、HTTP 接收、无异常返回、查询缺少目标成员，均不能独立证明设置成功。
4. 目标本来已是管理员：确认其当前在群和角色后可幂等完成，无须重复发 PROMOTE。
5. 目标已离群、成员身份未知、审批中：不能计为成功。PN/LID 匹配复用协议身份解析，不能把 LID 当手机号。
6. 成功后未来被降权不倒退已完成任务；当前权限由群域事实维护。任务保存本次完成证据，不能成为挑选当前管理员的权威来源。

### 3.2 回执、重试和故障恢复

- 分别保存进群与提权命令、尝试次数、错误原因。不得覆盖原进群命令来承载提权。
- 提权回执关联至少包含 tenantId、joinTaskId、joinTaskResultId、commandId、attemptNo、执行管理员 accountId/protocolAccountId、groupJid、目标账号/targetJid。
- 用当前尝试匹配和 CAS/短事务保证重复结果不重复推进；旧命令失败不能覆盖新尝试成功。旧成功或无关联的成员事件只能触发当前事实核实，不直接越过状态闸门。
- 命令 Outbox 与提权状态变为 SUBMITTED 同事务提交；外部成员查询放事务外，查询返回后重新校验版本和任务有效性。
- 单条明细两阶段整体终结后，才按现有间隔激活该账号下一条；其他账号可继续并行。同一群/执行管理员的权限操作应有并发约束，避免集中突发。
- 提权失败仅重试提权，不重复进群；建议沿用任务现有自动重试开关与次数上限，但管理员阶段独立计次。
- 管理员掉线或权限失效：刷新事实，在授权候选中换号。群失效/目标离群/明确永久拒绝不盲目重复操作。
- 超时或 UNKNOWN：先查询目标角色；已生效则收敛成功，明确未生效才按策略重试；仍未知则有界核实，保留“未确认”而非伪造 WhatsApp 拒绝。
- 必须覆盖 Outbox DEAD、已发布但无业务回执、进程重启、重复消费和任务软删除。当前 JoinTask 的 DEAD 扫描只跟踪进群 commandId，新阶段要有自己的收敛扫描。
- 删除或停止后不再派新命令。已发送操作不可假装撤销，迟到事实可更新群状态，但不得启动后续明细。现有页面实际可用的任务控制入口需以实现为准，不仅看枚举名字。

## 4. 管理员候选和权限边界

以当前 `wa_group`、`wa_account_group_binding`、`wa_group_participant` 的群身份/在群/角色事实，结合 `account_state` 在线及可执行状态选候选。不得根据“该账号曾创建过群”或旧 `join_task_result.is_admin` 推定其当前有权限。

候选限于任务租户及任务有权使用的账号数据范围。已有 PullTask 专用 SQL 显式约束 tenantId，并过滤在线、账号可执行、风险和禁言状态；它本身没有显式 owner_user_id 条件。复用前必须核实后台调度上下文的数据权限，不能将“同租户”自动解释为“可操作该租户所有用户的账号”。如果业务要求跨用户共享管理员，需要明确共享规则。

本地角色可能滞后，派发前可复用定点成员查询核验；查不到不能当普通成员，更不能当管理员。已进群的目标账号或其他允许使用的在线在群账号可作为读取群信息的账号，真正权限写入仍必须由确认有权限的管理员执行。

## 5. API、数据和前端影响

### 5.1 API

- 创建/编辑：建议 `setAdminEnabled?: boolean`，缺失为 false；继续复用原任务接口。
- 任务列表/详情：返回开关配置，成功计数表示满足该任务配置的完整步骤数；若保留“进群成功数”标签，则须另设整体成功数，不能同名混义。
- 明细：保留 `status` 表示进群结果，增加 `adminStatus`、派生 `stepStatus`、管理员执行账号及阶段原因。为稳定展示与定位，返回明细 ID、群身份及确认时间。
- 开关关闭显示“无需设置”；开启展示“等待管理员/设置中/核实中/成功/失败”，替换无法表达过程的两态文案。

### 5.2 数据模型建议（字段名在实现阶段定稿）

- `join_task` 聚合增加一个布尔配置列。属于现有任务配置，同一关注点，不为一个开关拆新表。
- `join_task_result` 聚合增加管理员阶段状态、执行账号 ID、当前命令 ID、尝试次数、下一次处理时间、阶段截止时间和失败/未确认原因。均用于调度、回执锁定、恢复或页面展示，现有进群字段不能承载两阶段独立结果。
- 保留 `group_jid/joined_at` 和进群结果；复用 `promoted_at` 记录已确认管理员时间。`is_admin` 若继续兼容旧读者，应与本次确认同事务维护，不能另起第三套当前群角色真相。
- 不新增总体 step_status 存储列；总体结果从开关、进群结果与管理员阶段派生。
- 增加到期调度所需索引；所有关联显式校验租户。迁移走 Flyway，版本号在实施时核对，旧任务默认关闭，不补发历史提权。
- 修改 `refreshCounters`、`markDoneWhenNoPending`、next-row 激活、并发闸门；原来仅查 `status = PENDING` 的逻辑不足以识别“已进群、待提权”。原进群调度条件必须排除只剩提权的行。

### 5.3 前端

`src/api/join-task.ts`、`useJoinTaskPage.ts`、`JoinTaskEditorDrawer.vue`、`JoinTaskDetailDrawer.vue`、列表列定义及 `JoinTaskTable.vue` 接入配置、回填、阶段状态与计数。

创建/编辑区域顺序：选择账号 → 进群链接 → 分配方式（含执行间隔）→ 群管理设置（设置管理员开关）→ 失败处理。沿用现有分区与 el-switch 样式。

建议提示文案：“进群成功后，由本群原有的受控管理员将该账号设置为管理员，确认设置成功后完成该步骤。”

详情按“账号 × 群”展示：账号、群链接/群身份、进群结果、设置管理员结果、执行管理员账号、失败原因及整体步骤结果。典型展示：

| 进群结果 | 设置管理员结果 | 整体步骤 |
| --- | --- | --- |
| 成功 | 成功 | 成功 |
| 成功 | 设置中/结果核实中 | 执行中 |
| 成功 | 失败，并展示具体原因 | 失败（不可展示为全部成功） |
| 失败，并展示具体原因 | 未执行 | 失败 |
| 成功 | 未开启 | 成功 |

创建页账号选择表中 isAdmin 固定为 false（`toAccountOption`），也不具备逐群维度；它不能用于表达本次任务的设置管理员结果。本期新增配置开关，执行结果统一在任务详情按账号与群展示。

## 6. 跨协议方案

推荐复用 `group.participants.requested` + `PROMOTE` 的动作能力，新增明确的 `source=join_task_admin`，并为它增加进群任务关联分支；结果可沿用 `group.action_result_reported`，但不能套用 PullTask 必填字段。

Web 需覆盖来源白名单、命令解析、master 无 owner 的失败回报、worker 执行、结果持久化与重放。Android 需覆盖 command specs、payload/reference 校验、aggregateType、coordinator/fleet 路由、拒绝/失败/成功回报、事件与去重。共享风险事件也须使用进群任务关联，不能继续硬编码 pull_task。

两端已有成员级结果解析，不代表新 source 已可直接发送。任务关联与主从异常路径必须一起改，否则可能出现正常路径可用、无 owner 时永久等待。

群详情同步 HTTP 提权接口可作为底层能力参考，但不建议直接塞进 Kafka 进群回调并在长事务内调用；这样缺少可恢复的独立命令关联，也不符合现有进群 Outbox 编排方式。

## 7. 验收和交付顺序

必须覆盖：关闭开关旧行为；两阶段成功；进群失败零提权；已在群普通成员；已在群且已经是管理员；缺失群 JID；审批中；无管理员；候选掉线/被降权后换号；目标离群；提权失败仅重试提权；UNKNOWN 查询成功和持续未知；重复/乱序/旧尝试回执；重启和 DEAD；多个账号同群；跨租户及同租户不同用户隔离；软删期间迟到结果；PN/LID；Web、Android 及执行者与目标协议不同的组合。

验证层次：状态机单测；真实 Mapper + H2 的两阶段汇总/并发闸门/隔离验证，MySQL 特有语法单独验证；两端命令契约与成员结果解析测试；前端回填/展示检查；指定环境分别进行真实进群与真实管理员角色验收。

交付顺序建议：确认异常策略 → 数据/API/状态机 → 双协议契约 → 前端 → 自动验证 → 指定环境验收。发布时先让所用协议端具备兼容新 source 的能力，再让后端及界面产生新命令；不能仅执行 backend/frontend 发布便声称全链路完成。

回滚前停止产生新提权命令并处理在途任务；不能把尚在提权的任务直接交给旧版调度器，否则旧版可能按进群 SUCCESS 提前结单。增量列先保留，不在紧急回滚中删事实；关闭新任务开关不能替代处理已经开启的在途任务。

## 8. 前期分析边界（实施结果见第 10 节）

已向用户询问：进群成功后无可用管理员时，选择有界等待、立即失败还是持续等待。建议有界等待；等待时长与到期后终结策略尚未定稿。

管理员仅使用现有授权范围是本方案边界；若“控端账号”另指跨用户共享池，需要补充明确的共享权限口径。无需为此扩大到所有租户或默认越过账号归属。

本次执行了本地源码、Mapper、现有测试文件和前端/协议契约的只读核查。未运行业务测试、未查真库、未调用真实 WhatsApp 变更接口，未验证线上版本和实际管理员角色。

## 9. 主要证据入口

- 后端：`armada-api/src/main/java/com/armada/task/service/impl/JoinTaskResultServiceImpl.java`、`JoinTaskServiceImpl.java`；`task/scheduler/JoinTaskDispatchTransactionService.java`。
- 模型与 SQL：`task/model/dto/CreateJoinTaskDTO.java`、`task/model/entity/JoinTaskResult.java`；`src/main/resources/mapper/task/JoinTaskResultMapper.xml`、`JoinTaskMapper.xml`、`PullTaskGroupMarketingCandidateMapper.xml`；`mapper/marketing/MarketingTaskExportMapper.xml`。
- 群权限：`group/service/GroupExecutionAccountSelector.java`、`group/service/impl/GroupDetailServiceImpl.java`、`src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`；`PullTaskManagerAdminTransactionService.java` 为模式参考，当前有在途修改。
- 消费入口：`platform/kafka/consumer/group/ProtocolGroupEventConsumer.java`。
- 前端：同级 `wheel-saas-pure-web/src/api/join-task.ts` 及 `src/views/task/join-task/`。
- Web：同级 `armada-protocol/protocol-layer/src/commands/pull-task-action.ts`、`group-participants-executor.ts`。
- Android：同级 `whatsapp-server-feature-android-zhuan/internal/armada/group_action_command.go`、`group_action_event.go`、`group_participants_sender.go`。


## 10. 主工作区实施结果（2026-09-18）

### 最终行为

- 创建/编辑抽屉在执行间隔后新增“群管理设置 → 设置管理员”开关，默认关闭；详情回填配置。
- 进群命令和设置管理员命令均通过 Kafka Outbox 下发、Kafka 成员级结果结算；角色核实复用现有定点群元数据查询接口（HTTP 读查询）。
- `status` 保持进群事实，`admin_status` 单独记录无需设置、等待、已提交、成功、失败、待核实。整步结果由两者派生；开启时只有两阶段都成功才计成功。提权失败保留进群成功，计整步失败。
- 进群成功且管理员阶段未终结时，不激活该账号的下一条进群明细、不把任务提前置 DONE。命令提交成功不等于管理员设置成功。
- 操作账号按当前同群角色、在线/可执行状态筛选，使用操作账号自己的 WEB/ANDROID 后端；只能 PROMOTE 单个本条目标，不能给自己发自提权命令。
- 新开关启用时从 Spring Security 可信 AuthPrincipal 保存任务 `owner_user_id`（原创建路径没有写该字段）。历史草稿首次开启也记录身份，已归属任务不能被普通用户改换归属。候选范围为同租户、发起人名下或未分配归属的共享账号，不扩展到其他用户已归属账号。即使本地记录是管理员，也需新鲜成员查询确认。
- 优先匹配 PN JID/已确认的 PN 映射，禁止从 LID 数字猜手机号码；完整快照确认目标不在群才失败，不完整快照继续核实。
- 无可用管理员/查询异常默认有界等待 5 分钟，可用 `armada.join-task-admin.timeout-ms` 调整（最低 30 秒）。这是未收到可选问题答复后的实现默认。到期未确认成功记管理员阶段失败，并保留未确认原因。
- 管理员重试使用原任务“失败自动重试/次数”配置，但与进群尝试独立。UNKNOWN 先查角色；旧 Outbox 未发布时不生成下一次命令，也不因关闭重试提前判失败。命令 ID、明细、尝试、执行账号、群和目标必须匹配当前尝试；重复/旧回执不推进。
- 新鲜角色已是管理员可直接确认；明确成员级 SUCCESS 可确认。详情分别展示进群结果、管理员状态/原因、操作管理员 ID、整步结果；列表统计改为任务步骤统计。
- 本期没有“踢出其他管理员”的开关或副作用；群管理设置分区可后续扩展。

### 数据与交付

- Flyway：`V201__join_task_set_admin.sql`，增加任务开关、管理员阶段 7 列及到期索引；复用已有 `owner_user_id`、`is_admin`、`promoted_at`。历史任务默认关闭，不补发提权。
- 配套迁移/回滚说明与脚本见 `.harness/changes/join-task-set-admin/`。紧急代码回滚先停止新任务并排空在途管理员命令，保留新增列和结果；不可直接交给旧终结逻辑。
- 数据模型 wiki 生成器读取真实库导出的 TSV；本次未访问或迁移环境数据库，不手写生成 wiki。指定环境迁移后再导出并生成真实数据模型。
- 改动在后端、前端、Web 协议、Android 协议四个主工作区；未重置其他 agent 的改动。共享文件已有并行变更，因此提交前还需按本次内容选择变更，不能提交整份 dirty 工作区。
- 本地验证详见变更记录；未提交、推送或部署，也未进行真实 WhatsApp 进群/管理员角色验收。
