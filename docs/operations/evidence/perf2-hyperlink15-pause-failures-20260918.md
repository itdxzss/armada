# 第二套任务15暂停及高失败率排查

2026-09-18，只读排查 perf2（3.110.124.52）及第二套 Android 协议（3.111.245.182）。未恢复、重发、修改业务数据或部署。

## 已核实事实

- 后端运行 SHA256 874186af06db2d0e137ed0d91e8a918cb72086d23142adad88467202833f4c3a，启动09:53:38 UTC，RestartCount=0，确为本任务上一轮发布包。
- 任务15，租户1，18:06:55北京时间创建，并发2、最大使用10、每号发送上限0，分组616。收件人3934。
- 审计仅CREATE（actor11）和BILLING_RESERVE，无PAUSE/RESUME操作。
- 当前round已依次选入9个账号；第3至9个确实持续补入，非只使用首次2个。任务usage有7个WA_403封禁、1个ACCOUNT_OFFLINE退出、1个有效账号。
- 后续account_state检查发现退出的2532也已BANNED，实际9个已选账号中8个最终封禁，2533在线；任务invalid_account_count仍是7，该计数与账号最终状态存在滞后/口径差异，不能把页面7视为当前全局封禁总数。

## 暂停原因

18:14:45.977北京时间，明细16185的第3次命令 hl:1:15:16185:3 返回 ACCOUNT_BANNED，messageId为空。HyperlinkSendFailurePolicy.nextRetryAt 对非463错误在 attempt%3==0 时返回RECOVERY_HOLD。随后round advance发现任意hold便把整个任务从RUNNING改为PAUSED，18:14:46.622释放hold，明细保留PENDING并递增到attempt4。

这是资源错误扩大成整任务暂停：仍有在线账号2533，选入9个也未达到10个上限。上轮补位修复未覆盖此分支。部署前后 HyperlinkSendFailurePolicy.class 的SHA相同（c8704794a47d18fd3fcdbff924928ad944c43c143c875d414b17ac3a21bc2bfe），属于遗留规则，补位能继续后暴露；不是两分钟超时分支直接暂停。

使用运行制品类做本地只读探针确认：ACCOUNT_BANNED attempt3 globalHold=true；ACCOUNT_OFFLINE attempt3 globalHold=true。

## 失败分解与提前耗尽

截图36条 = 30条WA_ACK_REJECTED_463 + 6条BANNED_SEND_RESULT_TIMEOUT。排查期间其余在途继续收口，最新40条 = 30条463 + 10条封禁结果超时；成功196，待处理3698，共3934。没有未注册失败。

30条463终态中：20条有4个明确拒绝；9条仅3个明确拒绝；1条仅2个明确拒绝。数据库sender_rejection与Android原始日志中不同commandId的拒绝次数完全吻合。

提前耗尽的10条ID：16011、16042、16051、16053、16093、16109、16111、16123、16124、16126；其中16042只有2次463，其余3次。所有dispatch_attempt均为4。原因是所有错误共享dispatch_attempt，封禁/离线/准备失败等未发送错误也递增；463耗尽判断只看attempt>=4，没有只计算463实际拒绝次数。

Android原始任务日志18:06:50至18:17:30窗口有416次463拒绝事件，真实拒绝确实大量存在，不能通过改统计消除；本次证据尚不能判定WhatsApp内部为何针对这些账号/消息拒绝。两分钟超时只是把无确定结果归入超时失败，不应描述为已经证明未送达或号码无效。

## 最小修正方向

1. 明确未发送的封禁/离线错误退出失效账号，目标等待其他可用账号；不由单条此类错误暂停整任务，遵守最大使用账号数。
2. 463预算按本目标已持久化的明确拒绝事实计算，账号不可用和发送前错误不消耗463预算。
3. 增加封禁叠加多次重试/仍有账号时继续派发的组合验证，并记录可追溯的自动暂停原因。
4. 已失败的10条不可在原因未修复前直接批量重发；真实拒绝和业务超时保留原始分类。

上轮制品69项回归缺少上述组合场景，不能作为这些路径已正确的证据。

原始脱敏只读输出与探针：/private/tmp/hyperlink15-diag/（facts.txt、deep.txt、final.txt、budget.txt、backend.log、protocol-budget.json、PausePolicyProbe.java）。
