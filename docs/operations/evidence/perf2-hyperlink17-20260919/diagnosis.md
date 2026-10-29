# 第二套超链任务 17（9-19）发送排查

2026-09-19；目标 perf2 / armada_perf / tenant_id=1。根据截图定位任务，查询数据库、任务审计和 Android 持久化日志。只读排查，未改业务代码、数据、配置，未重发、重登、重启或部署。

## 结论

故障已定位到 Android 私聊消息写出后的 ACK 确认阶段：18 个收件人完成构包，脱敏流量确认全部实际写入 socket，随后等待服务器 ACK 8 秒超时；另 2 个收件人在密钥准备阶段失败。同一连接持续收到 IQ 响应，未见本批 ACK/receipt 或解码错误。网络写出不证明 WhatsApp 接受，尚不能确定远端对本账号/消息静默的具体条件，也不能认定 18 条消息明确未送达。详见末尾 SSM 深入取证补充。

任务于北京时间 20:06:59.811 被用户 11 操作 STOP，早于首批消息的两分钟业务结果确认期限。后台随后将未知记录超时收口，不再换号重试。停止解释了最终收口行为，不是前面 ACK 超时的原因。

## 数量与事实

| 分类 | 数量 | 证据 |
|---|---:|---|
| ACK 未确认 | 18 | recipient=FAILED/SEND_RESULT_TIMEOUT，均有 command_id/message_id；18 条 ACK timeout 和 unresolved 日志；sent_at、delivered_at 均为空 |
| 发送准备失败后停止 | 2 | recipient 23629、23638；各有两次 LID_PRIMARY_KEY_MISSING 日志，dispatch_attempt=3 表示下一次尝试序号，不是发了三次 |
| 尚未提交便停止 | 3448 | TASK_STOPPED、submitted_at=NULL、dispatch_attempt=1 |
| 成功/送达 | 0 | runtime 与 recipient 事实一致，无对应成功回执 |

页面的 fail_num=3468 混合上述三类，不能解释为 3468 条消息被 WhatsApp 拒绝。

发信账号只有 #2660（19107208629），protocol_backend=ANDROID；每条间隔配置 1000–1200ms，单账号在途上限 20。task usage_status=1，无 invalid_code，当前 in_flight_count=0。查询时全局账号状态正常在线，没有消息受限或封禁标记。任务拒绝配对表无记录，相关日志无本批消息的 accepted/rejected ACK，未见 463 拒绝证据。

## 时间线（北京时间）

- 20:03:11：发信账号完成 Noise 握手。
- 20:05:14.862–20:05:36.597：20 个唯一收件人提交到协议通道。
- 20:05:19.364–20:05:44.496：18 个消息完成加密构包。
- 20:05:27.367–20:05:52.547：上述消息全部 ACK 等待超时，timeoutMs=8000。
- 20:05:36.680、20:05:46.217：另两条第一次 LID_PRIMARY_KEY_MISSING。
- 20:06:53.654、20:07:00.671：另两条第二次同类准备失败。
- 20:06:59.811：审计 STOP，actor_user_id=11。
- 20:07:16 起：在途未知记录陆续变为 SEND_RESULT_TIMEOUT。截图中的“结果确认中”23628 后续于 20:07:56.521 超时收口。
- 20:18:11：账号断连并快速重连；这次事件晚于发送异常，不能倒推为 20:05 超时原因。

## 协议与后台的证据链

18 条消息都有原 commandId 和 messageId。逐条持久化日志包含 peer message prepared、message ACK timeout、contact message ACK unresolved；后台 recipient 保存同一 messageId，随后查询原命令结果。由此可排除“整批命令未抵达 Android 协议层”及“后台完全没有收到未知结果事件”。当前 outbox 的 18 条命令类型已变为 message.send_result.query，其 sent_at 是最近对账查询投递时间，不能误当首次发送延迟。

18 条构包均 toDomain=lid / device=0 / primaryCipherPresent=true。17 条为根节点 pkmsg，1 条为两个 participant pkmsg；密文长度约 21.5KB。deviceIdentityPresent=false 本身不能被判定错误：当前源码仅在身份材料非空时附加，且注释区分分身设备场景。结构日志发生在 socket 写出前，因此“构包成功”不等于“已经写入网络”。

LID_PRIMARY_KEY_MISSING 来自 refreshLIDPeerSessions：目标主设备未成功建立所需密钥会话，准备失败发生在实际消息派发前。这不是未注册、封号或 WhatsApp ACK 拒绝的证明。

后台 HyperlinkUnknownResultRecoveryService：RUNNING/PAUSED 超过两分钟会重新排队换发信人；STOPPED 则将记录收口为 SEND_RESULT_TIMEOUT。该任务先收到人工 STOP，所以没有走超时换号。这里只用当前源码解释已观察行为，未把源码视为运行二进制完整校验。

## 尚未确定与后续检查

1. 18 条无 ACK 的更深原因：需要核对同 messageId 的实际 socket 写出、入站 ACK 分类及同账号成功样本；当前证据不能在网络、服务端静默和协议处理异常之间作确定选择。
2. 2 条缺主设备密钥：需要比对原设备查询与密钥查询的返回结构，区分远端未返回主设备 key、返回身份不匹配等情况；本轮未额外向 WhatsApp 发起查询。
3. 排查后段协议服务器公网 SSH 两次关闭连接，经 perf2 后端至内网 172.31.40.84 的 SSH banner 也超时，成功样本全日对比未执行成功。这是后续取证阻碍，不是原发送故障证据。
4. 读取原始流量片段曾被自动审批拦截；改为服务器侧白名单统计后获准。未导出原始流量或消息正文。该分钟脱敏 samples 无记录，不能据此断定没有网络流量。

## 证据文件

本机只读诊断脚本和已脱敏结果保存在 `/private/tmp/hyperlink17-diag-20260919/`：query.tsv、finalquery.tsv、filelogs.json、acklogs.json、structure.json、traffic.jsonl。compare.json 仅含 SSH 连接失败提示，不是比较结果。数据库工具校验 armada_perf，只允许 SELECT，连接 readOnly，结束 rollback。

## 用户追问：此前是否有过，以及本次为何集中（同日补充实查）

再次只读查询 perf2 历史任务 10–17，并按状态核对，避免把成功记录残留的 fail_code 算作当前失败。

- 任务 10：3999 个提交、3049 成功；66 条 FAILED/SEND_RESULT_UNKNOWN。这是结果未知，不能仅靠该码证明均为本次相同的原生 8 秒 ACK 超时。
- 任务 11：950 个提交、845 成功；8 条 FAILED/SEND_RESULT_UNKNOWN，同样不能混同底层原因。
- 任务 14：171 个提交、63 成功；2 条 FAILED/BANNED_SEND_RESULT_TIMEOUT。
- 任务 15：329 个提交、196 成功；10 条 FAILED/BANNED_SEND_RESULT_TIMEOUT。
- 任务 16：365 个提交、230 成功。6 条仍留 BANNED_SEND_RESULT_TIMEOUT 错误码的记录当前实际状态为 DELIVERED（4），必须计入成功/送达，不能把这 6 条列作当前超时失败。
- 任务 17：20 个提交、0 成功，18 个原生 ACK 超时、2 个准备失败。只使用 #2660；该账号在历史超链 usage 中仅有本次任务，没有可比的旧任务发信成功基线。

任务 10、11、13–17 的 message_type=4、主图片 asset=177，标题、正文、card_text 和 buttons 与任务 17 逐字段相同；单账号在途上限均 20，间隔均 1000–1200ms。任务 12 图片 asset=169。因此没有“任务 17 突然增大单账号并发或更换卡片正文/按钮/图片”的证据。

任务 17 全部 3468 个号码精确匹配任务 16 的停止失败集合；这是旧剩余集合，不是独立抽取的新样本。本次 18 个 ACK 超时号码在任务 16 中都曾被 2–5 个发信账号明确拒绝（sender_rejection 事实），之后停在待重试状态。不过这 18 个号码在更早任务 10/11 中全部有成功记录（17 个在任务 10，另 1 个在任务 11）；不能解释为这些号码一直无效或从来不可达。另两个 LID_PRIMARY_KEY_MISSING 号码在任务 10/11 中一直是 RECIPIENT_SESSION_UNAVAILABLE。

据此可以确认本次集中失败与过去“有大量成功、夹杂未知或封禁超时”的形态不同；优先排查此次发信账号 #2660 和 Android 发送/回执链路，同时保留目标与发信账号组合、部署版本变化的可能性。尚不能认定账号被隐性封禁、模板被拦截、或某次代码更新导致。对比旧任务并不能替代同账号/同版本的受控对照。

成功消息结构对比仍未完成：本轮再次读取 Android 当日日志被服务器关闭 SSH。未发起新消息测试。新增证据：`history.tsv`、`recipienthistory.tsv`；`historyconfirm.py` 的只读结果确认当前 18 个超时收件人全部曾成功、两个准备失败收件人均未在任务 10/11 成功。


## 深入取证：实际网络写出与历史 ACK 对比

本轮通过既有 AWS SSM 通道恢复只读取证，未修改 SSH、网络、服务或业务配置。此前 SSH 不可用的取证障碍已绕过，不能继续以其为无法查日志的理由。目标实例 i-09bfc4466f34dca63，公网 3.111.245.182、内网 172.31.40.84；EC2 system/instance 均 ok，SSM Online。20:05 五分钟 CPU 平均 10.83%、最大 12.30%，无 CPU 饱和证据。

### 18 条实际网络行为

服务器内部以已有脱敏映射材料关联已知 messageId，仅导出白名单元数据，不输出密钥、正文或原始协议内容。events-20260919-12.jsonl 中 18 个 messageId 各有一个 direction=up / kind=message 记录，总写出 412192 字节；首条 43300 字节（两个收件设备密文），其余约 21686–21703 字节。构包日志到实际写出相隔 2.05–278.94ms，中位 2.82ms；不是等待 8 秒后才出队。

采集源码 WASegment.WriteSegmentOutputDataWithMeta 在 conn.Write 返回后按 written 字节记录该事件，不能把其解释为仅构包或入队。该时段没有 sending node data failed、runSendQueue panic、慢写出、XMPP节点解析错误/为空、OnRecvData 异常日志。20:03–20:18 该账号持续存在双向流量，包含下行 129 条 background_iq、2 条 marketing_iq；18 条消息均无下行 ACK/receipt 记录。入站分类在 XMPP 解析之后、ACK processor 之前发生，故没有“ACK 已进入分类但后台处理丢失”的证据。

这里能排除队列未出队、连接整体断开、明显 CPU 饱和、已记录的 XMPP 解析报错；不能凭没有日志完全证明远端收到或排除所有未记录的内部错误。没有主动重发这些未知消息。

### 前一天对比

2026-09-18 持久化日志共 1608 条个人消息构包：650 条 ACK accepted、944 条 ACK rejected 463、14 条只见 ACK timeout。只见超时占比约 0.87%；本次构包的 18 条全部 ACK timeout（100%）。这些是协议日志中消息 ID 的统计，不等于任务唯一收件人数；旧任务可能多次换号。

上述 650 个成功样本与本次均具有 toDomain=lid、primaryCipherPresent=true、peerRecipientPNPresent=true、deviceIdentityPresent=false，因而不能把缺少 device-identity 单独判作原因；这里只比较有限构包特征，不声称消息所有字节相同。

### 发信账号差异与风险事实

历史任务 10–16 有账号归属的发送快照均为个人号（account_type=1）/iOS（device_os=2）；本次 #2660 为申报商业号（account_type=2，declared_account_type=2），类型校验 status=0/source=NULL，尚不能把申报值视为协议确认。都是 cred_format=4。#2660 于 20:03:01 入库，20:03:11 建连，约两分钟后开始本任务，没有旧超链成功基线。

protocol_risk_event 有两条 account.restricted 事件，时间 20:03:14.830、20:18:15.762；两条 is_active=0、enforcement_type/restricted_until 均为空。不能因 signal_code 名为 ACCOUNT_REACHOUT_RESTRICTED 就宣称当时被限制。这也说明先前“没有受限记录”的口径应理解为没有生效的受限事实，而不是该事件表完全无行。

本地 6019251..e406294 差异中，个人消息底层构包、node sender 和 Noise 写出文件无变更，ACK 变更主要新增迟到成功的业务观察路径；这不能替代历史部署二进制核验，也不能排除其他链路回归。

### 下一步需要的新证据

被动证据到此仍不能区分账号/会话与卡片发送条件。已向用户请求指定其控制的测试收件号，并确认仅由 #2660 新发一条短文本、一条本次卡片。尚未取得接收人及发送许可，未执行新消息测试、未重放历史 UNKNOWN。该对照应同时核对 socket、ACK 与接收手机，不能只看 HTTP 成功。

新增证据：ssm-deep-result.json、ssm-parsecheck-result.json、account.tsv、risk.tsv、sendercompare.tsv。SSM 查询 ID：af6aa81c-2a1c-41dc-8747-92fbac340a07、5dc00290-6ff2-4815-9392-c4cd6a203296。
