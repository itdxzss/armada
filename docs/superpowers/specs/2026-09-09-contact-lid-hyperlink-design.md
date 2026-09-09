# 通讯录 LID 超链任务：现状分析与设计建议

日期：2026-09-09。状态：用户已授权代码实现。当前实施决策、验证与待办见 `.harness/changes/contact-lid-hyperlink/summary.md`，下文保留分析阶段的现状与建议。

## 1. 分析边界与结论

用户本次要求是“先分析”。所附《2026-09-09-通讯录超链任务-agent提示词.md》作为需求和交接材料阅读，其中的开发、部署、登录及真号发送指令没有在本次执行。

用户后续明确的设计决策：

- 发送继续使用 Armada outbox → Kafka → 协调器 → 协议节点；Armada 不直接 HTTP 调用协议层。本任务的通讯录刷新同样通过正常命令链路触发。
- 发送间隔由页面配置，60 秒/条不作为固定值或强制下限。具体默认值与可配置范围尚未确定；原附件的 60 秒建议不再作为实现约束。
- 实际发送间隔由协议层独立控制，基于同账号上一条消息的实际网络派发时刻计时。Armada、outbox、Kafka 和协调器均不通过定时投递、延迟消费或逐条放行来模拟发送间隔。

用户已确认首版只围绕以下五项实现：

1. 现有通讯录模型支持 LID-only。
2. 现有 Kafka 命令链路接通 LID 超链发送。
3. 页面配置间隔，协议层按实际发送时刻控制。
4. 接通通讯录任务的送达、已读回执。
5. 异常能停住，结果不明不自动重发。

本范围替代原附件和前轮建议中的扩展项：不建设跨日额度凭据/统一配额体系、通讯录多版本平台、通用任务控制框架，不默认新增模板按钮、联系人分组或运行中配置热更新。已有任务内每号上限可以保留，账号日上限不列为本次新增要求。历史审查意见用于识别风险，不等于新增功能清单。

结论：应在现有通讯录任务上补齐 LID-only 能力。当前已有任务、账号、收件人三层模型、轮次调度、outbox 发送、发送结果回写、权限及前端页面；主要缺口是联系人身份模型、LID 生产发送管线、送达/已读关联和账号级发送约束。

本次证据来自本地静态阅读：

- Armada：`1.0.3-snapshot`，HEAD `f27a7a1d`，已有其他工作区修改。
- ws-go 实际目录：`whatsapp-server-feature-android-zhuan`，`1.0.3-snapshot`，HEAD `155fc10`，已有其他工作区修改。
- 前端：`wheel-saas-pure-web`，`1.0.3-snapshot`，HEAD `19694c9`，检查时工作区干净。
- 附件所述 F7 解码、1091 条结果、真号送达、账号 1714 被封及 noise 测试基线，是交接材料报告的事实，本次未重新实测。引用的 `/private/tmp/wa-contacts-send-probe-20260908` 本次未找到；远端证据没有访问。
- 未连接远程环境、数据库或 WhatsApp，未运行测试，不能据此声称线上已验证。

以下仓库证据链接均指向本次读取的本地文件；代码存在不代表部署环境已具有相同版本。

## 2. 当前功能及真实缺口

### 2.1 任务与页面已经存在

现有 `/api/contact-tasks` 提供列表、详情、创建、编辑、启停和账号数据。`contact_friend_task`、`contact_friend_task_account`、`contact_friend_task_recipient` 已承载任务、账号执行数据、收件人快照，并有 `current_round_no`、`round_no`、`command_id` 支持轮次关联。

前端已有 `/contact/hyperlink` 对应的页面、创建抽屉、账号筛选、账号数据抽屉及测试文件。因此无需直接复制营销页，也不应再建同义的任务三表。

证据：[Controller](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/contact/task/controller/ContactTaskController.java:35)、[V163](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/db/migration/V163__contact_friend_task.sql:6)、[V165](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/db/migration/V165__contact_task_engine.sql:1)、[现有页面](/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/contact/hyperlink/index.vue)。

现有形态与附件也有产品差异：任务直接保存链接/正文/图片配置，命令工厂明确不带按钮；展开时只选择“有名字”的联系人。账号筛选中的分组是发信账号分组，不能直接解释为联系人分组。

证据：[命令工厂](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/contact/task/service/ContactTaskMessageCommandFactory.java:16)、[收件人展开](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/contact/task/service/ContactTaskExpansionService.java:150)、[联系人 SQL](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountContactMapper.xml)。

### 2.2 LID-only 无法可靠进入现有通讯录模型

存在连续几层阻塞：

1. Go 快照转换以手机号去重，最终强制生成 `phone@s.whatsapp.net`。它的 `contactPhoneDigits` 会去掉域再取数字，因此输入标准 `数字@lid` 时存在把 LID 数字误当手机号的路径；这不是单纯增加一个非空判断能修复的问题。
2. Armada 的通讯录事件解析要求 `contacts[].phone` 非空；后续 normalizer 再次丢弃无手机号条目，并以手机号去重。
3. `account_contact.contact_phone` 和收件人快照 `contact_phone` 都是 NOT NULL，两个唯一键都以手机号作为联系人身份的一部分。
4. 即使补齐入库，当前“仅有名字”筛选仍不等于附件要求的云端通讯录全量。

证据：[Go 快照转换](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/contact_snapshot.go:110)、[事件解析](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/kafka/consumer/contact/ProtocolAccountContactEventConsumer.java:113)、[归一化器](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/contact/service/AccountContactNormalizer.java:32)、[V162](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/db/migration/V162__account_contact_sync.sql:6)。

### 2.3 通讯录获取接口不能只按名称选用

现有 Android 正式快照来自账号 ONLINE 后的 `ContactSnapshotCoordinator`：调用 `ForceContactSnapshot()` 强制 app-state 全量拉取，分片发布 `account.contacts_reported`，Armada 消费后落入账号通讯录。部分/未收齐快照保留旧行，完整快照才清理残留。

`contacts/sync` 和 `contacts/query` 接受号码列表并执行 usync；`contacts/list` 读取节点本地 `wa_contacts`。这些路径不能直接等同于交接材料中取得 1091 条 Base64 LID 的云端查询探针。尚需核对探针的数据源和正式 app-state 数据源是否一致，避免用错误的“同步”接口接入。

证据：[快照协调器](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/contact_snapshot_coordinator.go:58)、[sync/query 实现](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/api/service/sync.go:17)、[快照落库](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/contact/service/impl/AccountContactSnapshotSink.java:80)。

### 2.4 正式发送链路走 Kafka（用户已确认）

本地代码可确认的发送链路：

```text
ContactTaskRoundWorker
  → ContactTaskMessageCommandFactory（source=contact_task，targetKind=PRIVATE）
  → RoutingMessageSendPort → AndroidMessageSendBackend
  → protocol_command_outbox（事务提交后投递）
  → protocol.android.message.commands.v1
  → coordinator.CommandForwarder（按账号路由）
  → 节点专属 message topic
  → ws-go 消息消费/账号调度/MessageCommandExecutor
  → ZhuanMessageSender.preparePrivate
  → PreparePrivateLinkCard → WaApp.PreparePeerLinkMessageContext
  → node 准备及派发 → 服务器 ACK → 发送结果事件
  → ProtocolMessageEventConsumer → ContactTaskSendResultSink
```

现有三个入口层都限制 PN：命令目标校验、`ZhuanMessageSender` 的 target 校验、`preparePeerSendRoute`。因此只让 HTTP 路由接受 `@lid` 仍会在 Kafka 业务链路被拒绝。

按已确认决策扩展现有消息命令及内部 private sender，以明确的 JID 域选择 PN/LID 发送准备逻辑。Armada 通过 outbox/Kafka 发起发送与通讯录刷新，不直接 HTTP 调用协议层；本功能不要求新增协议 HTTP 路由。

证据：[命令工厂](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/contact/task/service/ContactTaskMessageCommandFactory.java:108)、[Android 编码](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java:216)、[协调器转发](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/coordinator/command_forwarder.go:219)、[PN 命令限制](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/message_command.go:494)、[sender 限制](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/message_sender.go:116)、[PN 路由准备](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/app/peer_prepared_send.go:40)。

### 2.5 当前成功统计不能作为送达统计

Go 的发送结果 success 来自 `ServerAck != nil`。通讯录结果 sink 收到 success 后直接标记 SUCCESS，并累加 `success_message_num`。但表注释称其为“成功送达”，口径不一致。

送达/已读是另一条 `message.ack` 链路。Go 关联存储和发送端关联登记目前限于 `source=hyperlink_task`，关联结构只有普通超链任务/收件人字段；Armada ACK DTO 也缺通讯录任务关联。不能假定 `external` 有回执就会自动回写通讯录任务。Go 当前 forwarder 仅处理 received/read，附件提到的失败状态也需要单独接通。

证据：[服务器 ACK 映射](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/message_sender.go:839)、[通讯录成功回写](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/contact/task/service/ContactTaskSendResultSink.java:85)、[ACK 关联限制](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/message_ack.go:63)、[Armada ACK DTO](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageAckEvent.java:4)。

### 2.6 发送限制存在跨层差距

- 前端默认间隔 0.5–1 秒、并发账号数 10、retryMax=3；后端允许 0.1–60 秒，无法表达大于 60 秒的间隔。数据库旧默认值也相同，不能仅改页面默认值。
- `max_sends_per_account=50` 是每任务截取联系人数量，不是跨任务共享的账号日上限。
- worker 默认每账号每轮准备 20 条，发现账号不可用时保留 PENDING 等下一轮；这不满足遇断线/封禁后停止且不自动恢复的口径。
- 当前 `ContactTaskRoundWorker` 按配置间隔计算轮次时间和逐条 `notBeforeAt`。目标设计需解除本功能对这些上游定时投递的依赖。ws-go 已有账号级 `AccountMessageDispatcher` 和派发时钟，应复用并核对计时点；现有代码在 dispatch 回调返回后记时，不能仅凭有计时器就认定测量点等于实际网络派发点。
- Armada 失败回写会按预算重新置 PENDING；Go `MessageCommandExecutor` 还有独立的 `RetryWarranted` 重投逻辑。只设置 retryMax=0 不足以证明不会再次派发。
- `hyperlink_task_account_usage` 的唯一键含 `hyperlink_task_id`，存的是任务内配额/在途状态，不能直接拿来实现账号跨任务的每日额度。

证据：[表单默认值](/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/contact/hyperlink/domain/task-form.ts:68)、[后端限制](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/contact/task/service/ContactTaskFormValidator.java:34)、[轮次配置](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/contact/task/scheduler/ContactTaskSchedulerProperties.java:11)、[失败重排](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/contact/task/service/ContactTaskSendResultSink.java:106)、[Go 重投分支](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/message_executor.go:244)、[现有超链配额表](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/db/migration/V158__hyperlink_task_lifecycle.sql:351)。

## 3. 设计方案（已确认项见第 1 节，其余为建议）

### 身份与数据归属

按已确认的五项范围，建议复用五张现有表，不新增业务表：

| 表 | 用途 | 首版最小调整 |
| --- | --- | --- |
| `account_contact` | 每个账号的联系人 | `contact_phone` 允许 NULL；现有 `contact_jid` 保存完整 PN/LID 主设备地址；唯一键按 `(tenant_id, account_id, contact_jid)` 调整 |
| `account_contact_sync` | 同步状态及时间 | 复用；同步与任务展开通过账号级数据库互斥、状态检查保证不混读，暂不建设历史多版本存储 |
| `contact_friend_task` | 任务与消息配置 | 复用已有间隔字段和启停状态，落实禁止自动重发的业务规则；不加另一套配置表 |
| `contact_friend_task_account` | 任务账号执行情况 | 复用执行状态，按异常停发的展示需求补停止原因字段 |
| `contact_friend_task_recipient` | 收件人快照与发送结果 | 手机号允许 NULL；完整 JID 去重；补 `delivered_at`、`read_at`；现有 `send_status` 增加结果不明及需要的取消/跳过取值 |

复用 `contact_jid` 的依据是当前需要一个实际收件地址且已有字段可承载，不能把它解释成 PN/LID 所有身份事实都只能保留一列。`contact_phone` 仅保存可确认的真实号码；输入同时提供 PN 与 LID 时按可信协议映射归一地址并保留号码，禁止按数字相似性合并。不为本期另建联系人映射平台。

明细已有 `command_id`、`protocol_message_id`、`error_code`、`error_desc`、`first_sent_at`，继续复用。SUCCESS 明确为服务器确认；送达/已读用各自时间表达，结果不明通过状态和原因展示，无需单独回执表。接到 READ 时如何推导送达事实需明确；首次发送时间由实际协议事实写入，不能直接取 Armada 收到成功事件的本地时间。

状态与字段的具体 DDL 仍属建议；通过新的 Flyway 迁移调整可空性、索引、必要列和注释，不修改已执行迁移。事件校验、normalizer、upsert、展开 SQL、VO 及相关测试一起修改；默认不新增准备中等纯过程状态的持久化字段。

每个账号只使用自己的通讯录快照；租户隔离、账号归属校验及启动时快照固化继续沿用。不能因为历史账号有 1091 条联系人，就把该名单自动划给新的测试账号。

任务主模型继续使用 `contact_friend_task*`。现有轮次号继续表达内部处理批次，本期不新增轮次表。

### 同步与目标集

建议增加受控的显式刷新入口，经正常命令链路触发协议采集，再复用 `account.contacts_reported` 落库。具体采集器需在恢复探针源码后确认。开始任务前仅使用已完整完成且未过期的快照，SYNCING/PARTIAL 不作为全量目标依据；不能单靠 lastSyncedAt 新鲜就放行。

附件所称“全部/分组”需明确。建议首版允许选择该账号完整通讯录中的 LID 联系人，联系人分组待来源和模型明确后实现；这是一项产品建议，未改变现有“有名字”规则。

### 协议发送

根据交接材料，LID 分支应包含：LID 与本机 PN 的设备查询、刷新 prekey 并重建会话、缺 key 的伴随设备可跳过但目标主设备 key 必须存在、无 peer_recipient_pn 的 LID 信封、子串误判守卫、正常派发和回执。

不得将“目标主设备 key 必须存在”写成“主设备必达”；设备可加密不等于最终送达。超链卡片还需验证实际内容包装，文本探针成功不能直接证明按钮、缩略图或所有卡片形式已验证。

保留 PN 路由行为；LID 的会话重建与加密准备需要纳入同账号串行控制，评估与该账号其他 PN/群消息共享会话存储的并发影响。继续使用既有 commandId 幂等和结果持久化；LID 路径禁止因结果不明而重新执行真实派发。

### 节奏与停止

发送间隔已确定由页面配置，不固定为 60 秒，也不设 60 秒强制下限。建议复用页面已有的最小/最大间隔控件及 `msgIntervalMinSec`、`msgIntervalMaxSec` 字段：单位为秒，两者相同表示固定间隔，不同表示每条从区间取值。间隔语义是同一发信账号相邻两次实际派发的时间间隔。

前端负责输入，Armada 负责校验、保存任务配置，将间隔参数随消息命令下发。outbox/Kafka/协调器只负责可靠传递和路由；本功能不按发送间隔计算逐条 `notBeforeAt`，不以延迟投递或延迟消费控制节奏。任务计划开始时间是独立的业务约束，不作为逐条间隔的计时依据。

协议层按账号维护发送队列和实际派发时钟，在完成必要准备后、真正提交消息至网络发送入口前执行间隔控制。固定间隔使用配置值，随机区间由协议层按配置取值；对每个相邻消息间隙只确定一次，循环等待时不重新取值。下一条实际派发必须满足：`本条实际派发时刻 >= 上一条实际派发时刻 + 本次间隔`。每次实际派发后，以这次真实时刻建立后续间隔，不能按任务启动时刻预排时间表后追赶补发。计时不以 Kafka 到达、开始准备或收到服务器 ACK 为起点；进程内等待使用单调时钟。

Kafka 积压、准备耗时或网络阻塞造成晚发时整体顺延，不能缩短后续间隔补发。优先复用现有账号调度器，不另建跨任务策略合并或迁移时钟平台；正常队列空后再次入队不能绕过已有间隔。节点重启或账号迁移按异常停止和结果不明规则收敛，不要求首版跨节点无缝续发。能约束的是本地协议网络派发间隔，不能保证远端收到消息的时间间隔相同。

前后端数值范围必须一致，非法配置显式拒绝。默认值和数值上下限待确定，不自行沿用旧版 0.5–1 秒默认值，也不引入新的默认秒数。

Armada 负责任务计划开始时间、在途背压与业务停止状态；协议层负责实际派发时间，在途窗口不作为间隔计时器。账号首次断线/限流/封禁后同时阻止新 outbox、已排队命令和已准备消息继续派发；已在网络中的消息继续收回执。优先利用现有协议队列的取消能力，并在派发前检查停止状态；具体停发契约仍须落实，但不为此建设通用控制框架。停止范围是账号还是整个任务，需要明确。

禁重发必须覆盖 Armada 失败重排、Go 重投及崩溃恢复；Kafka/outbox 传输重试可保留，但依靠同一 commandId 的持久化状态，不能转化为再次真实发送。

### 状态及统计

业务状态复用现有待发送、发送中、服务器确认、失败，并补结果不明及必要的取消/跳过状态；送达、已读通过明细时间字段表达。准备耗时等内部过程记录日志，不为每个阶段新增业务状态或表。任务按可继续执行的工作及发送结果收敛，不等待全部送达或已读才完成；结果不明结束自动执行但保留待核对标识。

以 tenant、发送账号、commandId、protocolMessageId 和通讯录任务/明细关联定位消息；关联先于可能到达的回执落位，或使用现有 inbox 缓存早到回执。回执重复消费不重复计数，READ 早于 DELIVERED 时不回退，结果不明允许迟到回执澄清但禁止自动重发。任务停止后仍消费历史消息回执。

前端沿用现有页面补充 LID 显示、同步状态、收件人分页明细、发送结果及送达/已读统计、结果不明和停止原因。首版沿用现有链接卡片配置，不新增模板选择或按钮编辑。

## 4. 实施顺序与验证建议

1. 以现有通讯录任务承载五项范围，明确联系人范围和停止范围；恢复探针源码，核对采集数据源与卡片证据，确定跨仓命令/事件契约。
2. 在独立 worktree 补协议命令及 LID sender、通讯录事件身份契约、通讯录 ACK 关联，先做协议局部验证。
3. Armada 进行最小迁移及 Mapper/事件/展开改造，接通停发与结果不明处理；最小跨仓链路就绪后，再用用户指定的新账号做正式单条卡片送达验收。旧任务不因部署自动扩大目标集或自动启动。
4. 补现有前端的间隔配置和结果展示；再做小批量端到端验收。

本地验证应覆盖：PN/LID 同数字但不同域、空 PN、重复/乱序/缺片快照、无名字联系人口径、LID 主设备缺 key/伴随缺 key、子串守卫、会话准备并发、真实卡片包装、两层禁重投、暂停时已有排队消息、ACK 先到/重复/乱序、崩溃结果不明、跨租户访问。

间隔验证在协议网络派发入口记录时间：覆盖 Kafka 批量到达、延迟后突发到达、准备耗时波动、ACK 延迟和队列空后再次入队。断言相邻实际派发间隔符合配置，在准备和网络可控时测量延迟误差，且超时后不追赶补发；不能用 Kafka 投递时间差代替验收。节点重启/迁移验证停止及结果不明收敛。

Armada 当前 AGENTS 与测试规范将 H2 执行真实 Mapper/事务作为默认门禁，真库测试为可选补充；附件“真库测试”的表述需与当前规范对齐。Go 改动按其 AGENTS 跑 gofmt/vet/build/test，涉及并发加 race；历史 noise 失败必须用本次基线确认后再归类。

部署顺序需使接收端先支持可空手机号和新回执关联，再由协议端开始发布新数据。Flyway 只增新迁移，核对版本冲突；不改已执行的 V162/V163。回滚优先关闭新派发、保留事实与迟到回执处理，不通过删联系人/明细或重置 SENDING 恢复旧行为。因新数据包含空 PN，应用二进制直接回退旧版本是否可行必须专项验证。

## 5. 仍需明确的口径

- 无名字的 LID 联系人是否包含在首版目标范围；新账号使用自己的通讯录同步结果，联系人分组不纳入首版。
- 页面发送间隔的默认值及数值上下限，以及首次异常停止单账号还是整任务。Kafka 入口、页面配置间隔、协议层控速和五项范围已确认。
- 真机阶段的新测试账号及允许联系的收件人范围。分析阶段不需要提前提供凭据。

本次仅完成以上分析与文档记录，未创建实现分支、修改业务代码、执行部署或发送测试消息。
