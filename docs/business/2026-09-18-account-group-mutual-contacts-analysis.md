# 账号列表：两个账号分组互相保存联系人（需求分析与实施）

- 日期：2026-09-18。
- 范围：四仓主工作区已实现；未连接远程环境、未执行真实联系人操作。当前实施口径和验证见 `.harness/changes/account-group-mutual-contacts/summary.md`。
- 四个仓库当前分支均为 `1.0.3-snapshot`；后端、前端已有在途修改，本分析基于当前工作区，不代表已部署版本。
- 用户原文：“我想在账号列表增加个按钮，互相添加好友功能。选择两个账号分组，这两个账号分组进行互相保存联系人操作，你先分析”。

## 1. 需求理解与建议口径

按两个不同账号分组的跨组全量互存理解：A 中每个参与账号保存 B 中所有参与账号，B 中每个参与账号保存 A 中所有参与账号；不包含 A 内部或 B 内部互存。

例如 A 有 10 个参与账号、B 有 20 个，产生 200 对关系、400 次定向保存。两个方向分别记录，仅两边都收到成功结果时才计为一对“双向保存成功”。

这里的成功是本任务两个方向的保存操作成功，不等于 WhatsApp 提供了一个长期有效的双向好友认证，也不推导消息送达、动态可见或永久好友数。

用户已确认：允许保存时覆盖已有联系人备注名称，不需要增加保留旧备注的处理。这里指执行账号通讯录中的联系人名称，不是对方的 WhatsApp 昵称；建议统一使用对方 WS 号码作为名称。

用户后续授权开始编码，首版按以下默认范围实现：

- 第一版只纳入状态正常、在线、协议身份完整且有操作权限的账号；预览明确展示排除数量与原因。
- 不同分组必须不同；按账号 ID 和规范化 WhatsApp 身份去重，排除自己保存自己。
- 任务创建时冻结参与账号及号码快照，后续移组、新增账号不扩展该任务。执行前重新检查删除、授权、身份变化、在线状态和操作限制。
- 执行中离线或受限的账号停止派发，相关项显示明确的未完成原因；恢复后的继续/重试应是可见操作，不隐式自动登录。

## 2. 已核实的现有能力与缺口

| 范围 | 当前事实 | 本功能所需变化 |
|---|---|---|
| 账号列表 | `wheel-saas-pure-web/src/views/account/index/components/AccountListTable.vue` 的 `PureTableBar` 已有批量操作入口，未提供分组互存 | 增加独立“互相添加好友”按钮及抽屉，不依赖勾选表格行 |
| 分组选择 | `src/api/account-group.ts` 提供分页、关键词查询；`useAccountListPage.ts` 当前只取前 200 组 | 复用 API，新增选择器应支持远程搜索/分页，不能漏掉第 201 组以后的分组 |
| 账号后端 | `AccountController` 有批量上下线、迁移等接口，未有本功能端点 | 增加预览、创建、查询、停止、重试等任务 API |
| 协议路由 | `RoutingContactPort` 按执行账号 backend 选择 Web/Android，已有保存能力 | 每个方向按发起方协议路由，允许跨协议互存；不按对方协议路由 |
| Web HTTP | `armada-protocol/protocol-layer/src/routes/contacts.ts` 等待 App-State 准备和 `addOrEditContact` 完成才返回 | 可复用底层保存操作 |
| Android HTTP | `whatsapp-server-feature-android-zhuan/api/service/sync.go:SyncAddContactsService` 等待 usync，但 `go waapp.SendCreateContact(numbers, "")` 不等待保存结果 | HTTP 200、`Code=0` 不能作为实际通讯录写入成功的完整证据 |
| Android 异步执行器 | `internal/armada/contact_save_sender.go` 等待真正创建联系人结果，检查 App-State、IQ 和 patch 错误；超时为 UNKNOWN | 复用真实保存逻辑和结果分类 |
| 异步命令链路 | Web `commands/contact-save-executor.ts`、Android `group_action_command.go`、后端 `PullTaskContactSavePayloadHydrator` / `ProtocolGroupEventConsumer` 均有业务来源/关联字段校验 | 目前绑定拉群等既有业务，需新增互存任务来源和关联，不能拿虚假的拉群 ID 复用 |
| 运维批量脚本 | `armada-deploy/tools/account-contact-matrix.sh` 已有并发、间隔、重试和结果账本机制，但仍调用上述 HTTP 接口 | 可参考调度经验，不能直接包装成页面执行器或沿用旧 Android 成功口径 |

## 3. 页面交互建议

账号列表工具栏“互相添加好友”打开抽屉，提供两个可搜索分组选择框和执行间隔设置；并发由系统限制，避免把复杂参数全部暴露给用户。

针对用户提出由业务人员选择间隔，建议提供一个数值输入框“单账号保存间隔（秒）”，默认 0，接受非负整数。说明文案：“同一账号每次保存完成后的等待时间，0 表示不额外等待。”例如设置 3 秒，则当前保存完成后等待至少 3 秒再派发该账号的下一次保存；不同账号独立计时、允许并行。该值随任务保存，后端负责校验与执行，关闭页面不受影响。第一版固定单值，不增加随机范围；限流退避和未确认操作的阻塞仍优先于正常间隔，0 不代表同账号并发。

预览展示两组总数、可参与数、排除原因、预计关系对数和定向保存次数。预览只查本地账号事实，不执行 WhatsApp 写操作；提交时复核快照，参与范围变化时刷新预览。

开始后显示后台任务进度：已处理定向操作数、双向成功对数、仅单向成功对数、失败、结果待确认和未执行数。提供双方账号、A→B/B→A 状态与错误原因的分页明细。抽屉关闭不终止任务，重新打开可查看最近任务。

停止只阻止尚未派发的操作；已提交命令仍接收结果，已保存联系人不回删。仅重试明确允许重试的未成功方向，成功方向不重复执行。

## 4. 后端与数据设计建议

建议在账号域建立独立互存任务，通过既有 Outbox、协议路由、执行器幂等与结果事件基础设施执行。任务业务生命周期不挂到拉群任务。

### 4.1 Kafka 与 HTTP 的选择（代码复核结论）

Web 和 Android 当前代码均支持 HTTP 保存入口，也均接入 `contact.save.requested` Kafka 命令。推荐本功能采用“前端 HTTP 创建/查询任务，后端 Outbox + Kafka 下发协议动作，协议 Kafka 回传结果”。Kafka 是后端与协议层之间的传递方式，实际 WhatsApp 保存仍由协议层执行。

现有路径证据：

- Armada `ProtocolCommandOutboxServiceImpl.toPullTaskContactSaveOutboxRow` 已按执行方 backend 选择 Web master command topic 或 Android group-action command topic，Kafka key 为执行账号 `protocolAccountId`。
- Web `commands/master-consumer.ts` 经账号 owner 路由写入 worker Redis Stream；`worker-consumer.ts:executeContactSaveCommand` 调用真实联系人执行器。`contact-save-executor.ts` 等待保存完成，存储结果后发布 `group.action_result_reported`，获得 broker 确认后才确认 worker inbox 消息。
- Android `internal/armada/start.go` 已装配 `CommandTypeContactSaveRequested -> NewZhuanContactSaveSender`。`group_action_executor.go` 负责账号互斥、commandId 幂等、结果存储与 Kafka 发布；`contact_save_sender.go` 等待通讯录写入回执并识别 App-State/IQ/patch 错误。
- 后端 `ProtocolGroupEventConsumer.handleActionResultReported` 已消费对应结果，但目前按拉群来源读取关联字段。

因此底层能力可复用，新增任务仍需扩展两协议的来源/字段校验、Web 无 owner 时的失败回执、后端命令 payload 补全及结果分发。建议沿用命令类型、增加独立 source 和业务关联，不伪造拉群 ID。

选择 Kafka 的原因是现有链路已经具备持久命令、结果回传、重复投递处理和账号路由，适合跨组大量动作及页面关闭后的执行。HTTP 也能实现后台任务，但需要补齐同样的恢复/对账能力；当前 Android HTTP 还存在提前返回成功的问题，不宜直接复用为最终成功依据。

Kafka 投递成功只表示命令已送达队列，不能标记联系人保存成功；必须等每条方向操作的协议结果。Kafka 本身也不自动保证副作用仅执行一次，仍需沿用命令幂等、结果存储和 UNKNOWN 对账。前端设置的间隔由任务调度器控制，0 秒表示收到本次结果后不额外等待，不代表没有 Kafka/协议往返耗时。

上述为当前本地代码支持情况，未确认指定测试环境的部署版本、消费者配置或某个账号的 App-State 完整性，真实验收需另行核验。

### 4.2 API 与持久化

建议接口族（命名待实施设计确定）：

- `POST /api/accounts/mutual-contact-tasks/preview`：校验两组、计算参与范围及操作规模。
- `POST /api/accounts/mutual-contact-tasks`：携带请求幂等键创建任务、冻结参与范围。
- `GET /api/accounts/mutual-contact-tasks`、`GET /{id}`、`GET /{id}/items`：最近任务、进度、分页方向明细。
- `POST /{id}/stop`、`POST /{id}/retry-failed`：停止新派发、按原因重试失败方向。

数据聚合建议为任务主表与定向操作明细，必要时按实际规模另设参与账号快照；具体 DDL 留待实施设计。

- 主表保存租户、操作者/数据归属、两个分组快照、请求幂等键、任务状态和调度配置。
- 明细保存执行方/目标方身份快照、方向状态、commandId、尝试序号、错误码、派发/结果时间；唯一约束保证同一任务同一方向不重复生成。
- 双向对数由两条方向事实归并；汇总字段如持久化只能作为可重算投影，不能成为另一份成功事实。
- `account_contact` 是通讯录采集快照，不适合保存任务执行状态；不能把任务结果直接伪装成全量联系人采集或增加 `friend_count`。
- `pull_task_account_action` 强制关联拉群任务及执行行；`group_batch_task_item` 关联群组且按群去重，均不适合存账号对。复用机制，不强行复用业务表。
- 复用 `protocol_command_outbox` 的可靠投递，不再建同用途的投递队列；Redis 仅用于已有命令执行幂等/互斥等技术状态，任务事实存 MySQL。
- 迁移走 Flyway；任务、预览、查询、操作与回执同时落实租户隔离、用户数据归属和写权限，不能只隐藏按钮。权限键按现有 account 的 module_key/perm_key 规范设计。

## 5. 执行与失败处理

- 不同执行账号有限并发，同一个执行账号串行，并与已有账号操作闸门协调；不一次提交全部 N×M 命令。
- 正常额外间隔按任务的 `intervalSeconds` 执行，建议默认 0 秒；后端记录账号下一次可派发时间，调度等待不占住工作线程。每次确认结果后计算下一次派发水位，异常退避时间取更晚者。
- 预览和后端均校验规模上限，分批生成/派发明细。例如 1000×1000 是 100 万对、200 万次保存，需要明确上限和分页策略。
- 新增独立来源（例如 `account_group_mutual_contact`），关联 taskId/itemId/attemptNo；前后端、Web、Android 对字段与回执处理同步扩展。
- 创建请求、命令重投、重复/乱序回执均需幂等。超时后副作用可能已经发生，保留“待确认”，优先对账原 commandId，不能直接算确定失败并立即新发。
- 限流按账号退避；账号离线、凭据/App-State 缺失、身份异常分别给出原因，不无限重试，不用重新登录掩盖密钥缺失。
- 部分成功可保留；停止或发布回滚均不会撤销 WhatsApp 已发生的通讯录保存。

## 6. 影响、发布与验证边界

建议方案涉及前端、Armada 后端及 Flyway、Web 协议、Android 协议四个仓库。核心保存动作已有，主要工作是独立任务管理、权限、节流、可靠回执及页面。

发布时先让协议层和后端识别新来源，再开放页面入口；旧任务来源行为保持兼容。回滚优先关闭创建与派发入口，保留查询及回执收尾能力，不能删掉仍有在途命令的来源处理。

实施验证至少覆盖：跨组数量/去重/排除自身；分组超过 200 条；跨租户和用户越权；重复提交；单方向失败；超时与晚到结果；停止和重试；同账号并发；重启恢复；Web/Android/混合协议回执。

真实验收应在明确指定的测试环境用 A=2、B=3 的小样本，核对 6 对关系与 12 条方向结果，覆盖一个单向失败及失败方向重试。测试环境部署、协议成功回执和联系人可读取验证分别报告。

分析后的实现与测试已经完成；尚未部署、未执行真实联系人保存，详见变更记录。

## 7. 首版实施口径

1. 使用跨组全量互存，最多 20000 条定向操作，不做抽样。
2. 只纳入当前在线正常账号；预览展示排除总数及规则。

联系人备注覆盖已获用户确认。第一版采用跨组全量、仅在线正常账号、每任务最多 8 条在途，以及前端 0–3600 秒可配置间隔（默认 0 秒）。
