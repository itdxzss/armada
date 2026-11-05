# 两个账号分组互聊：简版分析方案

- 日期：2026-11-05。
- 状态：**方案已定，可交付开发**（已按复核意见修订，无待确认项）；本方案的实现与验证现见 [交付记录](../../.harness/changes/account-group-mutual-chat/summary.md)，未部署或发送真实消息。开发任务书见 [账号分组互聊开发任务书](../handoff/2026-11-05-账号分组互聊开发任务书.md)。
- 用户要求：在现有互加好友之外增加互聊，两组分别配置发送内容，由控端安排协议发送，避免过度设计。
- 用户已确认：
  1. 沿用全量交叉配对，每对账号双方各发一条。
  2. 发送失败、结果不确定（待确认）的方向可手动重试，口径与互加好友一致：先手动停止任务，再重试。**与互加的一处差异**：互聊的重试同时包含“已取消”的方向，所以“停止后重试”等于“停止后继续”。
  3. 当前只面向测试环境，后端与协议层的上线先后不限。
  4. 单账号发送间隔默认 10 秒，由使用人员在页面上创建任务时自行填写；**不新增 yml 或环境变量配置**。预计规模一般几十到几百，不按 20000 条设计。
  5. 消息文本较短（一两百个词以内），长度设一个上限即可。
- 依据：四仓 `1.0.3-snapshot` 当前本地源码，不代表任何远程环境已部署该能力。

## 1. 已确认的业务口径

A 组每个参与账号与 B 组每个参与账号互聊一次：A→B 发 A 文案，B→A 发 B 文案。只涉及跨组关系。

若实际参与数分别为 M、N，则有 M×N 对账号、2×M×N 条计划消息。例如 A=10、B=20，共 200 对、400 条消息。这里的“每对各一条”不是“每个账号总共只发一条”。

双方文案分别配置，执行由控端组织，协议执行指定账号向指定号码发消息。

## 2. 建议的最小使用流程

账号列表增加“互聊”按钮，打开简单抽屉，包含两个分组、A 发出的文本、B 发出的文本、单账号发送间隔（默认 10 秒，创建任务时在页面上自己改）。首版建议只支持固定纯文本。

先预览实际参与账号数和预计总消息数，再创建后台任务。任务创建时固定账号、号码和双方文案；之后增号、移组不扩大本次任务。分组选择、预览及进度展示沿用现有互加功能的交互模式。

控端为每对账号建立 A→B、B→A 两条发送记录，分别派发并接收结果。双方发送都由任务调度，不增加收到消息后自动回复的监听流程；首版不要求送达或已读后再触发反向发送。

页面显示待发送、已提交、发送成功、失败、待确认、已取消，以及双向成功对数。关闭页面不影响后台执行。

**停止与重试（与互加好友一致）：**

- 停止：阻止尚未派发的方向（转“已取消”）；已提交的方向继续接收结果；已发出的消息保留。
- 重试：把“失败”“待确认”和“已取消”的方向重新排队，以**新命令 ID** 再发一次；成功方向不重发。因为包含“已取消”，先停止、再重试就是“停止后继续”。接口不强制先停止，与互加一致。
- 重试“待确认”项前界面要提示：该消息可能已经发出，重试可能让对方收到重复消息。保存联系人按号码覆盖，重复无害；发消息重复会被对方看到，所以互聊要提示。“已取消”的方向从未发出，重试没有重复风险，不需要提示。
- 重试后，原命令晚到的回执因命令 ID 已更换而被忽略，不会覆盖新结果。

## 3. 实现范围

| 范围 | 最小改动 |
|---|---|
| 前端 | 账号列表入口、互聊抽屉及 API；复用现有分组选择组件和任务展示方式；增加停止、重试按钮；间隔输入框默认 10 秒（前端表单初始值，和互加的默认值做法一致） |
| Armada 后端 | 互聊任务、方向明细、后台派发、结果回填、停止与重试；复用现有账号权限和协议路由。详见第 5 节 |
| Web / Android 协议 | 复用普通私聊文本发送；各补一个互聊来源及两个关联字段，并让该来源“不确定不由协议层自动重投”。详见第 5 节 |

发送链路沿用 `MessageSendPort → 现有 Outbox/Kafka → Web/Android → 发送结果`，命令使用已有的 `PRIVATE + TEXT`，目标为 `对方 WS 号码@s.whatsapp.net`。按发送方账号的协议路由，双方可以分别使用不同协议。

当前链路会校验消息的业务来源，不能仅添加页面便直接传一个未知来源。建议增加 `account_group_mutual_chat` 来源（与现有 `account_group_mutual_contact` 命名对齐），关联字段为任务 ID 和方向明细 ID。后端以命令 ID 对应方向明细；回填时核对租户、发送账号与目标。协议只识别来源和关联，不承担两组配对或文案配置。

建议接口族为 `/api/accounts/mutual-chat-tasks`，提供预览、创建、任务查询、明细查询、停止、重试。首版不增加自动循环、多轮剧本、随机文案。

**不新增 yml 或环境变量配置。** 发送间隔是页面上的任务参数：创建任务时填写，随任务保存（和互加一致）。后端只用代码常量做校验兜底，集中在一个 `AccountMutualChatPolicy` 里：

- 发送间隔 1~3600 秒（不允许 0，避免连发）。
- 单任务最多 5000 条消息（2×M×N）。
- 单条文本最多 1000 字符。

这三个数是我按“几十到几百、一两百个词”定的，要调改常量即可。

**“重试”与“协议层自动重发”是两件事。** 重试是用户在控端点按钮、使用新命令 ID 的手动动作，本方案要做。自动重发是协议层对同一条命令自行再投一次，本方案不要：协议层负责如实上报“失败/待确认”，是否再发由用户决定，否则协议层自动重投与人工重试会叠加成重复发送。这与互加好友的分工相同。

持久化建议只新增互聊任务主表与方向明细表，字段对齐 `account_mutual_contact_task/item`：

- 主表：租户、创建人、请求幂等键、两个分组及名称快照、两组参与数、**A 文案、B 文案**、发送间隔、任务状态、创建/更新时间。
- 明细：租户、任务、发送账号与目标账号 ID、双方号码快照、协议账号与协议后端快照、**方向**（1=A→B 发 A 文案，2=B→A 发 B 文案；调度时据此从主表取文案，不在明细里重复存文本。账号创建后可能移组，不能靠当前分组反推）、方向状态、尝试序号、命令 ID、**协议消息 ID**（成功时写入，用于排查）、原因码、提交/结果时间、下次可派发时间。**不设 `retryable` 列**：消息结果事件没有该字段，重试规则是“失败 + 待确认 + 已取消”全部可重试。
- 唯一约束保证同一任务同一方向只有一条；成功数、双向对数现场聚合，不另存镜像。

为什么不直接扩展 `account_mutual_contact_*`：两者结构相近，但互聊多文案字段，且回执语义不同（消息有 messageId，没有 `retryable`）；合并会改动已上线的互加。因此建新表，同时**复用而不复制**互加的规则代码（权限与账号资格 `AccountMutualContactAccess`，分组选择、去重与预览逻辑抽取共用）。间隔和规模上限**不沿用** `AccountMutualContactPolicy` 里的常量（0–3600 秒、20000 条），互聊另用自己的 `AccountMutualChatPolicy`（见上）。现有互存表、通讯录营销表、Outbox 均不能直接充当互聊业务事实，复用其机制即可。DDL 留到开发时确定，走 Flyway（当前最新为 V224，开发时再核对撞号），随后重跑 `gen_datamodel.py`，不改共享账号表。

## 4. 必要的执行约束

- 两组不同，参与账号按 WhatsApp 身份去重，排除自己给自己发送；两组都有的账号归 A 组，预览明确参与和排除数量。
- 账号资格沿用 `AccountMutualContactAccess.eligible`：在线，状态为正常、被抢登、抢登中，风控正常，未被静音，协议身份完整。派发前复核权限、身份与资格，变化则该方向记为失败。
- 同账号串行、不同账号有限并行。调度器按“该账号上一条的结果时间 + 任务保存的发送间隔（页面默认 10 秒）”计算下次可派发时间。间隔只由控端控制，不传给协议层：Android 协议层只有通讯录来源会使用命令里的间隔，互聊不用，命令里填 0 即可。
- 任务规模：预览和创建都校验消息数不超过 5000 条，超出时明确提示缩小分组；读取单组候选的上限随之推算。单任务同时在途数沿用互存的 100 条常量。
- 文本校验：去除首尾空白后不得为空，不超过 1000 字符；创建时校验，A、B 两段文案都要填。
- 耗时估算：不同账号并行，所以耗时取决于“对方分组人数 × (间隔 + 单条耗时)”，不取决于总条数。例如对方组 20 个，每个账号约 3~5 分钟发完；对方组 100 个，约 17 分钟以上。
- 同一次创建请求幂等，每个任务方向唯一；调度重跑和重复结果不新增发送事实。
- 入队成功只记“已提交”；协议确认发送成功并返回消息 ID 后记“发送成功”，不推断送达或已读。
- 超时（沿用互存的 300 秒）或协议上报结果不确定时记“待确认”。待确认**不阻止**该账号继续派发后续方向（与互加一致）；同一命令晚到的明确结果可把待确认升级为成功或失败；重试换新命令后，旧命令的回执忽略。
- 失败方向（含账号不可用、WhatsApp 拒绝等）、待确认方向和已取消方向都可重试；重试后任务回到“运行中”。已取消方向从未发出，重试不会重复；这是与互加不同的一处（互加的重试不含已取消）。

## 5. Java 与协议层统筹改动清单

链路上有三层都按“来源”做硬校验，必须同步改，缺一层该来源的命令会被丢弃或回执解析失败。

**Java（armada-api）**

| 位置 | 改动 |
|---|---|
| 账号域 `AccountMutualChat*` | Controller、Service、调度器、事务派发、Mapper 及 XML、DTO/VO/实体/枚举，骨架对齐 `AccountMutualContact*`，另有 `AccountMutualChatPolicy` 放间隔、规模、文本长度三个校验常量；重试的 SQL 条件为失败、待确认、已取消三种状态；派发时调用 `MessageSendPort.enqueue`，每次一条、换新命令 ID；返回 `accepted=false`（如协议后端未注册）则该方向记失败并写原因码，整批抛异常则事务回滚、下个调度周期重试；调度线程要像互加那样先设置 `TenantContext` |
| Flyway | 新增任务表与方向明细表，更新数据模型文档 |
| `MessageSendCommand` | 新增互聊关联（任务 ID、方向明细 ID），`MessageCorrelation` 增加对应组件；record 内的兼容构造器和直接调用 9 参构造的位置补 null（构造调用点共主代码 8 处、测试 16 处，开发时逐个核对参数个数） |
| `ProtocolCommandOutboxServiceImpl` | 新增聚合类型常量；`toMessageOutboxRow` 增加分支（否则落到营销分支，读 `marketing().attemptId()` 空指针）；`validateMessageCommand` 增加分支（必须是私聊、来源与关联匹配）；现有各分支及营销兜底补“互聊关联必须为空” |
| `WebMessageSendBackend`、`AndroidMessageSendBackend` | 命令 payload 增加两个关联字段 |
| `ProtocolMessageSendResultReportedEvent`、`ProtocolMessageEventConsumer` | 事件增加两个字段（含兼容构造器）；解析时互聊来源免除营销必填字段（`marketingTaskId`、`targetId`、`attemptId`、`roundNo`、`groupJid`），必填互聊两字段；`businessId`/`businessItemId` 增加分支，保证风控元数据正确 |
| 新增 `AccountMutualChatResultSink` | 只支持互聊来源，默认营销 Sink 不会误吞；按命令 ID 找方向并核对任务、方向明细、发送账号、目标 JID。有消息 ID 的成功记成功；`outcome=UNKNOWN` 或原因码为 `SEND_RESULT_UNKNOWN`、`UNKNOWN`、`EMPTY_MESSAGE_ID` 记待确认，其余记失败；只接收“已提交/待确认”的方向；风控由消费者统一处理，Sink 里不用再接。**同一个类还要实现 `ProtocolMessageAckSink`，`handleAck` 空实现**：Web 对所有已记录关联的消息都会发 `message.ack`（`event-bridge.ts:329-376` 不按来源过滤），后端 `selectAckSink` 没有匹配处理器会抛 CONFLICT 触发 Kafka 重试，所以必须有一个 ACK 处理器接住；首版不记送达/已读 |

**Web 协议层（armada-protocol/protocol-layer）**

| 位置 | 改动 |
|---|---|
| `commands/worker-consumer.ts` | payload 类型加两字段；`messageSendPayload` 增加互聊分支（必须私聊、两字段必填、不得混入其它业务字段，否则落到兜底分支被判 `invalid message send payload`）；`messageCorrelation` 与日志字段回带两字段；指标来源白名单加入新来源 |
| `commands/message-send-state.ts` | 关联类型加两字段；`validateCorrelation` 增加校验；`hasAnyBusinessCorrelation` 列表加两字段 |
| 重发处理 | **无需改**。Web 命令状态机不按来源区分，处理中超时统一转 UNKNOWN 且不自动重发 |

**Android 协议层（whatsapp-server-feature-android-zhuan/internal/armada）**

| 位置 | 改动 |
|---|---|
| `message_command.go` | payload 结构与解析加两字段；`validateMessageCorrelation` 增加互聊分支（私聊、两字段必填），并给现有互斥检查补上新字段 |
| `message_event.go` | 结果事件带回两字段 |
| `message_sender.go:371`、`:467`，`message_executor.go:256` | 把互聊来源并入 `contact_task`/`hyperlink_task` 现有的“不确定上报 `SEND_RESULT_UNKNOWN`、不由协议层重投”条件。**这是 Android 最容易漏的一处**：漏掉 `message_executor.go:256` 会让协议层把同一条命令再投一次，叠加人工重试造成重复发送 |
| 不改的地方 | ACK 落盘（`message_sender.go:348`、`message_executor.go:293`、`message_ack.go`）：互聊不消费送达/已读；`WithContactSendInterval`（`message_sender.go:360`）：间隔由控端调度控制 |

**测试要点**

- Java：A=2、B=3 正好 12 条方向、双方文案正确；重复创建/调度/回执不重复；失败和待确认重试换命令、旧回执忽略；待确认不阻塞同账号后续方向，且可被同命令晚到结果升级；停止后重试；租户与归属隔离；间隔超出 1~3600 秒、消息数超过 5000、文本为空或超过 1000 字符被拒绝；停止后重试能把已取消方向重新派发；出站校验对缺关联、混入其它关联的命令拒绝且不空指针；结果事件解析与 Sink 路由唯一；互聊来源的 `message.ack` 事件到达时不抛错、不改状态。
- Web：新来源的有效、缺字段、混入其它业务字段三类 payload；状态机关联校验。
- Android：校验与事件字段；针对 `message_executor.go:256`，补“互聊来源不被协议层重投”的用例。
- 联调：A=2、B=3，覆盖纯 Web、纯 Android、混合协议；再覆盖一次失败后重试、一次待确认后重试。真实消息验收需在明确环境和账号范围内另行执行。

## 6. 当前源码证据与验证边界

- 后端 `AccountMutualContactServiceImpl.java:87`：现有两组双重循环生成双向明细；`:138` 起处理预览、参与范围和身份去重。
- 后端互加重试：`AccountMutualContactMapper.xml` 的 `retryFailed` 为 `(status=4 AND retryable=1) OR status=5`，即**待确认也会被重试**；`AccountMutualContactH2Test` 的 `unknownIsManuallyRetriedWithNewCommandAndOldReceiptIgnored`、`unknownResultDoesNotBlockActorFromNextDirection` 覆盖了换命令、旧回执忽略、待确认不阻塞后续方向。`.harness/changes/account-group-mutual-contacts/summary.md` 中“没有……人工强制覆盖 UNKNOWN 的入口”“该账号后续动作阻塞”与代码和测试不符，以代码为准。
- 前端 `AccountMutualContactDrawer.vue:71`：现有全量跨组互存说明；`MutualContactGroupSelect.vue:17` 起已有搜索与分页分组选择。
- 后端 `MessageSendCommand.java:18`、`RoutingMessageSendPort.java:44`：已有私聊目标、文本、命令 ID、间隔及按发送账号协议路由。
- 后端 `ProtocolCommandOutboxServiceImpl.java:1391`、`:2109`，`ProtocolMessageEventConsumer.java:173`、`:210`：新业务需接入来源、关联校验和结果处理。
- Web `protocol-layer/src/commands/worker-consumer.ts:1668`、Android `internal/armada/message_command.go:417`：两端都存在业务来源校验。
- Android `message_sender.go:371`、`:467`，`message_executor.go:256`：不确定私聊消息不上报失败、不由协议层重投的现有处理，按来源硬编码。
- Web `protocol-layer/src/commands/message-send-state.ts:186`：处理中超时统一转 UNKNOWN，不按来源区分。

上述为源码阅读结论。本次未运行测试，未连接远程环境。

## 7. 待确认项

暂无。发送间隔、规模上限、重试范围（含已取消）、文本长度均已定，见开头和第 3、4 节。
