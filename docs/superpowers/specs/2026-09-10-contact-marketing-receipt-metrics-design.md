# 通讯录营销执行进度与单勾、双勾统计设计

日期：2026-09-10。状态：已在主仓实施，并按追加授权提交、推送、部署到 test1。

用户要求：先查看超链任务的单勾、双勾统计，再设计通讯录营销任务的统计和展示。

本次依据本机当前主仓：Armada `a690ed13`、前端 `eaf2a50`、Android 协议 `b1759da`。未查询任务 5 数据库明细，示意数据均为虚构。代码事实不等同于 test1 当前制品或真实设备回执验收。

## 1. 已核实的超链统计

| 指标 | 当前口径 | 依据 |
| --- | --- | --- |
| `sendTotal` | 已提交协议发送的逻辑 recipient 数，同一 recipient 最多 1 次 | shared-contract；recipient 的 `submitted_at` 投影 |
| `successNum`（单勾） | 当前状态 SUCCESS、DELIVERED、READ 的数量，即至少达到发送确认 | `HyperlinkRecipientStatus`；`HyperlinkMetricsProjectionService.delta` |
| `deliveredNum`（双勾） | 当前状态 DELIVERED、READ 的数量 | 同上 |
| `readNum` | 当前状态 READ 的数量 | 同上 |
| 单勾率 | `successNum / sendTotal` | shared-contract；市场分析 |
| 双勾率 | `deliveredNum / successNum` | 列表、详情、市场分析 |
| 列表页汇总 | 当前页各任务数量相加，再用合计分子 / 合计分母计算率 | `list-display.currentPageMetrics` |

三个回执指标为包含关系：已读 ⊆ 送达 ⊆ 发送确认。不能把单勾、双勾、已读相加当作发送总数。

超链 recipient 结果经状态等级单调归并，重复回执不会重复增加；投影器比较已投影状态和当前状态，批量更新任务、轮次、账号统计。默认调度间隔为 60 秒，支持从 recipient 事实重建聚合；这不是回执保证在 60 秒内到达。

存在需要避免继承的展示问题：

1. `HyperlinkTaskMetrics.vue` 和 `recipient-stats.ts` 仍把单勾描述为“消息已发送到对方手机”，应为“WhatsApp 服务器已确认接收”；单勾不能证明设备送达。
2. 同处“对方 WhatsApp 在线、设备 100% 收到”不应扩展为对方当前在线；展示收到的送达回执即可。
3. 页面存在“落地率 ≈ 双勾率 + 20%”经验文案，不能当作真实回执统计继承到通讯录营销。
4. 数据包 `sent_count` 的“当前停留单勾”与任务 `successNum` 的“累计至少单勾”不是同一口径，不能直接复用前者作为任务单勾数。

## 2. 通讯录现状与缺口

已具备：

- `ContactTaskSendResultSink` 用发送结果确认 SUCCESS，按实际更新行数增加任务成功数和账号已发送数；送达/已读回执还可将 UNKNOWN 修正为 SUCCESS。
- recipient 已有 `delivered_at`、`read_at`、`first_sent_at`、`last_attempt_at`、`attempt_count`、稳定 `command_id` 和 `protocol_message_id`。
- `markAck` 收到 READ 时同时补齐 delivered/read，重复事件保留已有时间。不会把每个设备回执当成一条新消息。
- 前端“联系人发送明细”已有发送确认、送达、已读时间，但任务列表和账号表没有送达、已读汇总。
- 任务在没有 PENDING/SENDING 且名单准备结束后收尾，不等待所有消息送达或已读。单条 UNKNOWN 不自动重发，仍可继续其他联系人。

缺口：

- 任务和账号进度使用“成功 / 计划”，因此处理全部结束仍可能为 0%。这表示确认率，不是执行进度。
- `invalid_account_num` 收尾时统计任务账号 `state=FAILED`，前端却叫“封号数”；没有证据说明这些账号均被 WhatsApp 封禁。
- `used_account_count` 当前按 `need_send_num > 0` 统计，应称“有收件人的账号数”，不能直接解释为实际发生发送尝试的账号数。
- 当前账号 `valid/invalid` 快照混合任务结果与账号状态，不能据此新增“真实封号数”。
- 当前只能按账号打开联系人明细；不能在任务层直接筛选全部未知、仅单勾或未送达记录。
- 现有回执时间不保证是对方设备精确发生时间；先到 READ 可补齐前序时间，界面应称“首次确认时间”，不能伪装独立测得的三个事件时刻。

## 3. 推荐统计口径

### 3.1 统计单位

基础单位为通讯录任务的一条 recipient 发送记录，唯一性沿用 `(tenant_id, task_id, task_account_id, contact_jid)`。

同一联系人分别出现在两个发信账号的名单中，当前属于两条逻辑发送记录。页面单位用“条”，不称“独立触达人数”；本次不改变跨账号去重或发信范围。LID 不转换成推测手机号。

### 3.2 执行指标与效果指标

| 记号 / 字段建议 | 计算规则 | 展示 |
| --- | --- | --- |
| N / `plannedNum` | 本任务已固化 recipient 数 | 计划条数 |
| A / `attemptedNum` | `attempt_count > 0` 的 recipient 数，每条只计一次 | 已尝试条数 |
| S / `confirmedNum` | SUCCESS，或已有送达/已读事实的 recipient 数 | ✓ 已发送（累计发送确认） |
| D / `deliveredNum` | `delivered_at IS NOT NULL OR read_at IS NOT NULL` | ✓✓ 已送达（累计双勾） |
| R / `readNum` | `read_at IS NOT NULL` | 蓝色 ✓✓ 已读 |
| F / `failedNum` | 当前 FAILED | 明确失败 |
| U / `unknownNum` | 当前 UNKNOWN | 结果未知 |
| K / `skippedNum` | 当前 SKIPPED | 已跳过 |
| P / `processedNum` | 当前 SUCCESS / FAILED / UNKNOWN / SKIPPED 数 | 已处理 |
| `pendingNum` / `sendingNum` | 当前 PENDING / SENDING 数 | 待处理 / 处理中 |

其中 A 只说明调度器已发起过一次处理，包含本地入队被拒绝的记录，不能叫“服务器已收到”或“已投递到网络”。通讯录首版采用“发送确认率 = S / A”，不能把它无注释地混称超链的 `S / sendTotal`，后者分母为已提交协议数。

界面主要比率：

- 执行进度：P / N；名单全部准备完成后分母固定。准备期间显示“名单准备 x/y 个账号，已确认计划 N 条，已处理 P 条”，不把尚在增长的 N 当最终分母。
- 送达率（双勾率）：D / S，与超链一致。
- 已读率：R / D，明确标记“送达后的已读比例”；如果要看总计划覆盖，另称“已读覆盖率 R/N”，不混用。
- 分母为零显示 `—`，未知或未支持字段不能被前端兜底成 0。

数据不变量：`N = pendingNum + sendingNum + S + F + U + K`，`0 ≤ R ≤ D ≤ S ≤ N`。现有合法数据满足 SUCCESS 与回执事实一致；若出现 FAILED 带送达事实等矛盾行，应作为数据一致性问题核查，不能静默同时计入两组或将比例截成 100%。

### 3.3 累计指标与互斥明细

概览沿用超链的累计口径；点击进入列表后应明确“至少发送确认”“至少送达”等筛选名称。

需要看当前停留在哪个勾时，使用互斥分类：

- 仅单勾：S − D。
- 已送达未读：D − R。
- 已读：R。

如果演示 S=80、D=60、R=25，则仅单勾=20、已送达未读=35、已读=25，三项合计 80；不是发送了 165 条。

## 4. 页面与交互

### 4.1 任务列表

保留现有任务信息、账号范围和任务操作，主要调整三组列：

1. **执行进度**：`已处理 P / 计划 N` 加进度条；次行显示待处理、处理中。结束后仍有未知时显示“已结束 · 有结果待确认”，不显示“全部发送成功”。零收件人显示“无可发送联系人”，不显示 0/0 的红绿进度。
2. **消息效果**：`✓ 已发送 S · ✓✓ 已送达 D · 已读 R`，次行显示送达率 D/S。每个数量可点击查看相应任务明细。
3. **异常 / 账号**：明确失败 F、结果未知 U、跳过 K；账号摘要显示参与名单准备数、有收件人数、执行异常数。原“封号数”改为“执行异常账号数”，不改变底层账号生命周期。

状态、执行进度、效果各自独立。已停止任务保留停止标签，即使后来所有记录终结也不能自动改成正常完成。

列表不再堆第二套全局指标卡；如保留当前页汇总，显式标明“当前页”，比例使用合计分子 / 合计分母，不取各行百分比平均。

### 4.2 任务详情

沿用现有抽屉和 Element Plus 组件，增加“概览 / 账号数据 / 联系人明细”三个视图，避免抽屉层层叠加。

概览包含三组信息：执行（准备与处理进度）、效果（发送确认、送达、已读）、异常（失败、未知、跳过及原因分组）。任务指标针对整条任务，不随联系人分页改变。原始任务配置仍可在“查看配置”中查看。

账号数据：账号 ID、执行状态、计划、已处理、发送确认、送达、已读、失败、未知、跳过、原因。首次打开保留原服务端排序语义；支持按送达数/未知数服务端排序。真实账号在线/封禁状态如需展示，单独命名并注明是当前状态，不从任务成功率推导。

联系人明细：收件人标识、发信账号、处理状态、最高回执状态、首次发送确认/送达确认/已读确认时间、原因。UNKNOWN 独立显示“结果未知”，不显示单勾；SKIPPED 标为“未执行”，不算已发送失败。

筛选支持全部、处理中、仅单勾、至少送达、已读、失败、结果未知、跳过。明确按同一逻辑发送记录统计，点击数字后的总数必须与该筛选定义一致。

### 4.3 刷新和迟到回执

- 默认打开任务时读一次，任务进行中或存在 UNKNOWN/未送达记录时每 10 秒刷新可见视图；切到后台或关闭抽屉停止轮询。完成页面可由用户关闭自动刷新，始终保留手动刷新。
- 展示“最近刷新时间”，如以后采用异步聚合再单独展示“指标更新时间”。HTTP 请求时间不能冒充回执更新时间。
- 发送结束不冻结 D/R；迟到的送达、已读继续修正。UNKNOWN 被可靠回执确认后 U 减 1、S 增 1，D/R 按实际级别增加，P 不变。
- 已读为空表示尚无已读回执，不能断言对方未读；双勾为空也不能单独断言设备永远未收到。
- 不因本次统计优化自动重发 UNKNOWN、仅单勾或重启历史任务。

## 5. 后端/API 与数据方案

推荐首版复用现有 recipient 事实和回执处理，先增加读模型，暂不新建统计表、不增加重复状态列。

1. 列表查询分页取得任务 ID 后，在同一租户和同一读快照内按这些 ID 批量聚合 recipient、task_account，返回 `metrics`；不做每行 N+1 查询，不从浏览器当前页明细求总数。
2. 新增 `GET /api/contact-tasks/{id}/stats`，提供任务级执行、回执、异常和准备摘要。可用同一统计 VO 复用列表/详情逻辑。
3. 扩展现有 `GET /api/contact-tasks/{id}/data` 的账号返回和排序白名单，按筛选全集计算排序后分页，不能先分页再排序统计值。
4. 新增任务级 `GET /api/contact-tasks/{id}/recipients`，允许按 `taskAccountId`、`sendStatus`、`receiptStatus`、`errorCode` 筛选；现有账号级接口委托同一 service 查询，保持原调用兼容。
5. 保留原 `successMessageNum` 等 API 字段语义以支持发布先后顺序；新页面各项统计使用同一批事实聚合，避免旧计数和新 D/R 来自不同时间点。列表 CSV 同步增加指标，注明累计/互斥口径。
6. 现有索引覆盖 tenant/task/JID 和 task/status；上线前使用代表性数据确认聚合与排序开销。批量限定任务仍可能扫描大量明细，不能宣称天然解决大任务性能。若超出项目查询预算，再参考超链的幂等增量投影与全量校准模式，提出明确列/表与迁移方案，不提前复制整个超链 runtime/round 架构。

租户权限：沿用 `tenant:contact_task:view`，先校验 task 归属；账号、recipient 均校验 tenant/task/account 关联；所有聚合、子查询和计数按租户限定。API 不接受客户端 tenantId 越权切换，不新增未授权手机号导出或明文权限。数据模型变更若确有需要，走独立 Flyway，不手工改共享库。

## 6. 回执可靠性边界

Android 当前 `message_ack.go` 已同时支持 `hyperlink_task`、`contact_task` 的关联；DELIVERED/READ 经 `message.ack` 交给业务 sink。关联索引保存在 Redis，`MessageAckRetention` 代码默认 30 天，但本次未核对部署配置，不能向运营承诺任意久的历史回执都能追回。

需要在实施验收中确认：

- Android/Web 各自单勾、送达、已读的真实回传，并覆盖 LID 收件人；本次没有核实 Web 适配器部署及端到端回执。
- 先 READ 后发送结果、重复多设备回执、延迟 DELIVERED、任务结束后的回执，均不重复计数或回退状态。
- 同名 messageId 不跨账号、租户、任务归并。通讯录 ACK 当前按 tenant/command/JID/messageId 关联，实施时补齐携带的 task/account/recipient 身份一致性校验测试。
- 没有 messageId 且关联索引不存在的 UNKNOWN 不能凭空归因；关联过期/失败须可从现有日志监控定位，不能“猜一个双勾”。
- 时间列按“首次获得该层级确认的时间”展示；先到 READ 推断前序阶段已达到，不宣称恢复了真实发送或送达时刻。

## 7. 建议验收与发布范围

首版交付：任务进度纠正、S/D/R 汇总、失败/未知/跳过可见、账号统计、任务级明细筛选、准确文案和刷新。超链两处误导文案可作为后续同口径的小修复列出，但本次设计不直接修改它们。

核心验收用例：

1. 准备中分母增加；准备完成后固定；零收件人不显示误导百分比。
2. 100 条演示记录：S=80、D=60、R=25、F=10、U=5、K=5；P=100，进度 100%，送达率 75%，已读率 41.67%；互斥回执桶合计 80。
3. 全 UNKNOWN：进度可为 100%，发送确认=0、送达=0，双勾率显示 `—`。
4. UNKNOWN 收到迟到 READ：未知减少、S/D/R 各正确补齐、已处理不变；重复事件不加数。
5. 失败/跳过与账号真实封禁分开，0 成功不自动显示封号。
6. 任务摘要、账号合计、联系人筛选数量、CSV 一致；跨页不丢数，跨租户不串数。
7. H2 真实 Mapper/事务测试涵盖聚合、过滤分页、幂等、乱序和租户隔离；前端验证口径与交互；已有 receipt 回归保留。
8. test1 授权发布后，用真实设备核对服务器确认、送达、已读三个阶段，不能用测试通过或任务“已完成”替代。

预期改动前后端，首版无协议契约变化；只有真实验收发现适配器回执缺口时再扩大协议范围。回滚前后端制品即可，保留 receipt 历史事实；若后续需要新增投影，另行设计校准和回滚。

2026-09-10 用户追加授权在主仓实施且禁止 commit/push。已实现统计接口、列表/账号指标、统一结果抽屉与筛选刷新，验证证据见[变更记录](../../../.harness/changes/2026-09-10-contact-marketing-receipt-metrics.md)。追加发布已完成，详情见变更记录。

## 8. 证据入口

- [超链共享契约统计定义](2026-08-28-hyperlink-task-shared-contract.md)
- [超链数据模型](../../business/hyperlink-marketing-data-model.md)
- [超链指标投影](../../../armada-api/src/main/java/com/armada/hyperlink/task/service/HyperlinkMetricsProjectionService.java)
- [超链回执处理](../../../armada-api/src/main/java/com/armada/hyperlink/task/service/HyperlinkProtocolResultService.java)
- [超链回执状态机](../../../armada-api/src/main/java/com/armada/hyperlink/task/service/HyperlinkRecipientStateMachine.java)
- [通讯录回执处理](../../../armada-api/src/main/java/com/armada/contact/task/service/ContactTaskSendResultSink.java)
- [通讯录 recipient SQL](../../../armada-api/src/main/resources/mapper/contact/ContactFriendTaskRecipientMapper.xml)
- [通讯录任务 SQL](../../../armada-api/src/main/resources/mapper/contact/ContactFriendTaskMapper.xml)
- [通讯录轮次调度](../../../armada-api/src/main/java/com/armada/contact/task/scheduler/ContactTaskRoundWorker.java)
- [超链列表统计](../../../../wheel-saas-pure-web/src/views/hyperlink/task/domain/list-display.ts)
- [超链指标文案](../../../../wheel-saas-pure-web/src/views/hyperlink/task/components/HyperlinkTaskMetrics.vue)
- [通讯录任务页面](../../../../wheel-saas-pure-web/src/views/contact/hyperlink/index.vue)
- [通讯录联系人明细](../../../../wheel-saas-pure-web/src/views/contact/hyperlink/components/ContactTaskRecipientTable.vue)
- [Android 回执关联](../../../../whatsapp-server-feature-android-zhuan/internal/armada/message_ack.go)

