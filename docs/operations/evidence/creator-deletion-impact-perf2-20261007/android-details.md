# Android 注销保护影响审计（只读源码与隔离复现）

审计仓库 `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan`。以下路径均相对此根（行号为当前工作区）。2026-10-07 北京时间审计。未操作远程、未改业务代码或原测试、未发起真实协议行为。已读本项目 AGENTS.md。

## 基线与部署边界

- 分支 `1.0.3-snapshot`，HEAD `8d05bb173d9b32e025d1cca1fc45ed16c969fccb`，主 checkout。
- 已有修改保持：`.codegraph/daemon.pid`、[group_profile_metadata_test.go](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/api/service/group_profile_metadata_test.go)、[login.go](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/api/service/login.go)、[login_start_test.go](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/api/service/login_start_test.go)、[vo.go](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/api/vo/vo.go)、[executor.go](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/executor.go)、[executor_test.go](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/executor_test.go)、[iq.go](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/nodes/iq.go)、[iq_group_result_test.go](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/nodes/iq_group_result_test.go)。
- 其中未提交 login/vo/executor 补丁将登录准备注销错误分离为 1011，并不再把一般准备失败发布为 302；HEAD 不含这些修复。iq.go 等是审批节点缺省 off 修复，不能归为注销变化。
- 根代理单独取得 perf2 运行二进制及 662 个 api/internal Go 文件哈希，告知当前部署源码均匹配本工作区；本子审计未自行读取部署，不以历史报告替代当前核查。
- 用户指定历史报告已读，仅作为线索；下述缺口均重新读源码或本地复现。

## 最关键边界

**Java 只有 RESERVED、尚未调用 Android POST 时，本协议层没有 RESERVED 状态，也没有同步预留事件。不能把执行 305 前置审批解析失败归因于 Android 冻结。** Android 冻结从持久认领/Begin/Quarantine 开始；Java RESERVED 提前拦查询或派单的问题由后端审计确认。

## 状态模型

1. POST 授权匹配租户、任务、执行、账号、建群 operation 后，`ClaimTask` 写 `STARTED` 到 `prefix + account-delete:SHA256(trim(phone))`，SET NX、无 TTL。一次账号键只对应一个 operation，不能换 taskBinding。
2. `BeginAccountDeletion` 验证同一在线 Android 主设备、连接稳定与身份相同；进程状态 Idle -> Prepared，设置 expectedLogout=true，冻结 sender；仍保留当前连接发送注销。
3. Begin 失败：持久结果 NOT_SENT。任务绑定路径尝试 Quarantine 当前真实实例，进程 Idle -> NotSent、冻结业务；没有实例也保留持久 taskBinding，禁止以后创建/登录。
4. Prepared -> Sending；注销 IQ **编码前**取消/超时为 NOT_SENT；编码开始后失败为 UNKNOWN（编码不等于一定写入，故保守）。匹配来源/id/type 的回复才是 ACCEPTED/REJECTED。
5. Save 同 operation 结果后：ACCEPTED 尝试 StopNetwork、Offline、清本地密钥/通讯录/AppState/特定缓存、移除 appCache 与事件 handler、释放 fleet 归属，然后进程 Completed。Redis 仍 ACCEPTED 永久阻断。
6. 非任务 REJECTED/NOT_SENT：持久记录允许新业务，若有匹配 IQ 的同进程状态则 Release -> Idle。**任务 taskBinding 非空：所有状态永久 Blocked，进程 Release 明确拒绝，Save 禁止移除/改绑。**
7. STARTED/PENDING/UNKNOWN 持久重启后仍阻断；GET 只读结果，不重发、也不主动核验；未知不能当作“没发送”释放。

证据：[store.go:55,107-132,187-218,222-238](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/accountdelete/store.go:55)；[authorization.go:18-27,41-89](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/accountdelete/authorization.go:18)；[account_delete.go:84-158](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/api/service/account_delete.go:84)；[account_delete.go:35-104,114-213](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/app/account_delete.go:35)；[account_delete.go:64-90,105-159,176-229](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/nodes/account_delete.go:64)。

## 业务 × 阶段矩阵（Android 范围）

| 操作 | Java RESERVED、无协议 POST | Prepared/Sending/UNKNOWN/STARTED 恢复 | taskBinding NOT_SENT/REJECTED | ACCEPTED/Completed | 范围 |
|---|---|---|---|---|---|
| 群创建/加入/拉人/设置/提权/退群 | Android 无该预留门禁；上游可能拦 | 拒绝 | 拒绝且无解除 | 拒绝 | 同号全部实例/任务；不识别本任务例外 |
| 群列表/成员/资料/邀请码、关系同步 | 同上 | 拒绝，返回离线/网络类错误 | 同左，长期 | 拒绝 | 同号本任务和其他任务 |
| 联系人新增/互加/查询、通讯录实时同步 | 同上 | 拒绝 | 拒绝，长期 | 拒绝 | 同号 |
| 普通/群/超链/剧本/feed 等复用发送 | 同上 | 新准备/派发/已排队未写均拦；已写等待结果不直接拦 | 同左 | 拒绝 | 同号 |
| 账号资料/头像/状态读取及修改 | 同上 | 拒绝 | 拒绝 | 拒绝 | 同号 |
| 消息媒体下载（只需 URL/key/proxy） | 同上 | 也被在线 getter 拦 | 同左 | 拦 | 同号，属于过宽只读门禁 |
| 本地 wa_contacts 查询 | 可 | 可（不发协议） | 可 | 入口可，但成功清理已删除该账号数据 | `ListContactsService` |
| 独立 GET 注销结果 | 若无记录返回不存在 | 可 | 可 | 可；无 owner 可挑健康节点读共享 Redis | taskBinding 必须完全匹配 |
| 管理号独立核验注销 | 管理号可用则可 | 创号冻结不阻断管理号 | 同左 | 同左 | 使用 manager 身份；若 manager 自身冻结则拒绝 |
| 入站消息 ACK/receipt、原 IQ 回复/群事件 | 可 | 无总门禁；ACK/receipt/ping/result 出站允许 | 同左（若连接仍活） | 关闭连接后不能继续收；此前 durable inbox 不被此 gate 删除 | 后端是否接收另审 |
| 手动停网络 | 普通路径可 | HTTP getter 返回“不存在/已下线”，未 Stop；executor 报成功 | 同左 | 已清理则不存在合理 | 确定协议缺口 |
| 无关不同号码账号 | 正常 | 门禁状态独立；但全局锁争用有风险 | 同左 | 同左 | 同号别名/新凭据不能独立绕过 |

## 发现及最小修复

### A1 P1：确定未发/明确拒绝的任务账号永久隔离，无安全解除/重试收口

- 触发：合法 taskBinding Claim 成功，但账号不在线、主设备检查失败、编码前取消，或服务端明确拒绝。
- 预期：保留一次性发送证据；明确未发/拒绝可在后台终态、无依赖、同绑定确证后释放普通业务，不能当 UNKNOWN；是否重试注销应另立显式受控操作。
- 实际：`store.go:197` 因 Binding!=nil 永远 true；无 TTL（55,121）；`account_delete.go:169` Release 拒 Binding；`store.go:230-235` 禁止去 Binding；没有解除 API/worker。
- 因果：任务 Claim -> NOT_SENT/REJECTED -> 重启/迁移仍禁止 CreateWSApp/SetWSApp/WALogin -> 所有本任务与其他任务 live 业务拒绝，重复同 op 仅重放旧结果，新 op 冲突。
- 本地证据：TestAuditTaskNotSentRejectedPersistAndRejectNewGeneration 用真实 Store+miniredis，一年时间推进后仍 blocked，同号码新 accountId/op 无法 claim，无关号码不 blocked；现有 TestTaskBindingImmutableAndLifecycleRemainsBlocked、TestTaskDeletionRejectionCannotReleaseGate 也证明此行为是有意的底层策略。
- 最小修复：新增单调 RELEASED/SAFE_ABORTED 决策及签名解除命令，绑定原 tenant/task/execution/op/account generation，必须确证 NOT_SENT 或匹配 REJECTED 并结束相关派单。保留原记录与永久已注销 tombstone，不能删 key/TTL 自动解锁。Java RESERVED 未发送释放走更早的独立路径。
- 现场：根代理现场 reserved2、attempts0 尚未 POST，不应宣称这两例已经命中 Android 永久隔离。

### A2 P1：冻结 getter 同时屏蔽实时查询和清理，错误分类掩盖真实原因

- [cache.go:100-140](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/app/cache.go:100) 对任意非 Idle 返回 nil，无操作类型/任务身份；[account_delete_gate.go:23-38](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/account_delete_gate.go:23) 再次拦所有非 ACK/receipt/ping/result/delete 出站 IQ。
- 全链路：live 业务 service/resolver -> GetValidWSApp nil -> “离线/请重登/ACCOUNT_OFFLINE/ACCOUNT_NOT_ONLINE”，而不是注销保护；保存现有 WaApp 引用绕 getter 也会在 sender 入队和写 socket 前拦截。
- 群资料+邀请码：[group_snapshot_client.go:49-58,127-144](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/group_snapshot_client.go:49)；群列表：[groups_fetcher.go:360-374](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/groups_fetcher.go:360)；群成员：[group_member_query_client.go:23-27](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/group_member_query_client.go:23)；联系人：[contact_save_sender.go:175-181](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/contact_save_sender.go:175)、[contact_snapshot_coordinator.go:143-154](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/contact_snapshot_coordinator.go:143)；进群：[join_sender.go:182-188](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/join_sender.go:182)；独立普通建群：[normal_group_creation_sender.go:258-265](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/normal_group_creation_sender.go:258)；消息：[message_sender.go:139-145,690-715](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/message_sender.go:139)。
- HTTP 全量：[group.go](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/api/service/group.go) create/read/invite/profile/admin/join/leave/approval/permissions/remove；`profile.go:17,48,72,228,262,296,320`；`sync.go:17,50,90,124,140,173,205,237`；`login.go:123,713,723`。媒体下载 `message.go:197-221` 先走 `base.go:10-16`，虽然真正下载只需要媒体 URL/key 和代理也被拦。
- 已确定业务限制，不等于每条都是缺陷：注销已发后禁止写业务合理；NOT_SENT 安全终止后长久拦全部业务不合理；停止连接、离线结果读取、必要对账应单独允许。不要统一开放受注销账号所有 live IQ。
- 最小修复：按业务派单/实时只读/控制停机/结果回收分入口；typed `ACCOUNT_DELETION_BLOCKED` 携阶段和可重试性，不建议重登；本任务前置核验在 RESERVED 用绑定授权（后端负责）；已发后的验证改独立管理员，媒体/已落库查询无需业务有效实例。

### A3 P1：下线假成功、缓存假在线、归属与连接不一致（历史线索现仍在源码）

- [login.go:91-95](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/api/service/login.go:91) 下线 GetValid=nil 时返回“账号不存在或已下线”；未运行 105 StopNetwork；[executor.go:315-324](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/executor.go:315) 按字符串认作幂等成功发布 OFFLINE。
- `cache.go:38-52` RemoveWSApp 对 frozen 直接 return，不停网络、不删除、不释放 OnAccountOffline；[waapp.go:306-311](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/app/waapp.go:306) Exit 则会 StopNetwork 后保留缓存；`StopNetwork:262-296` 仅 Reconnecting 自动改 Offline，Online 的外部状态更依赖调用方。
- `login.go:165-176` 登录快捷返回只看缓存 Online，在注销检查之前发布成功回调；`login.go:698-710` 状态查询也只看缓存，不看连接/注销。
- 本地 TestAuditAccountDeletionHidesConnectedInstanceFromBusinessLookup：UNKNOWN/NOT_SENT/REJECTED 均保持 Online+connected，GetValid=nil，Remove 不清理也未 Stop。执行实际停机已被禁，本测试使用内存连接无真实网络。
- 最小修复：控制停机用 GetWSApp + 身份核验，显式 StopNetwork 可幂等、保留 tombstone/未知证据与必要结果上下文；状态响应区分 physical connection/usable/deletion；禁止 frozen 走旧 Online 快捷回调。

### A4 P1：UNKNOWN/STARTED 只有查询，没有可靠结果推进；晚到成功证据被丢弃

- 注销专属 15 秒计时器 [account_delete.go:10,17-40](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/processor/account_delete.go:10)，超时/取消从 `_nodeList` 摘除；已结算的 [account_delete.go:105-110](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/nodes/account_delete.go:105) 直接忽略迟到回复；GET [account_delete_task.go:60-72](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/api/service/account_delete_task.go:60) 只读 Redis。
- 导致：编码后客户端取消/超时 -> UNKNOWN -> 正确关联 ACCEPTED 即使后来到达也不会写回；后台轮询只重复 UNKNOWN。进程刚 Claim 后崩溃的 STARTED 更无法知道是否曾发送。
- 本地 TestAuditLateDeletionAcceptedReplyCannotPromoteUnknown 在同 id/from/type 正确 ACCEPTED 到达时仍 UNKNOWN，测试通过。当前线上发生数量须根核对，不能用普通消息 ACK 的恢复能力类推注销 IQ。
- 最小修复：保持禁止自动重发；保留 bounded 专属 late-reply correlation（操作+连接代次+IQ）并 CAS 推进持久结果；独立 manager 注册/群关系证明可做复核，但“查不到/不在线”不是成功证据。Crash-before-send 只有可靠未发送标志才可转 SAFE_ABORTED，其余继续人工/自动只读对账。

### A5 P1/P2：ACCEPTED 本地清理失败后，重启不能恢复清理

- [account_delete.go:195-203](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/app/account_delete.go:195) 要求 Redis ACCEPTED 且进程内 state==Accepted 且 IQID 相同；重启丢掉 state/IQID。`cache.go:150-151` 又禁止从 tombstone 重建正常实例；API replay `account_delete.go:93-99` 只在 account!=nil 时补清理；没有按 durable record 构建的只清理 worker。
- [account_delete_cleanup.go:16-47](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/app/account_delete_cleanup.go:16) 清理可以在 MySQL不可用、AppState ownership不匹配或缓存删除失败时返回错误；之前已 StopNetwork+Offline 但未 finishCacheCleanup，旧归属会直到其它机制过期；重启后 key/material残留无补做路径。
- 本地 TestAuditAcceptedCleanupCannotResumeFromPersistedRecordAlone 用 Store 真正持久 ACCEPTED，再全新对象调用 Complete -> “no accepted result”；CreateWSApp -> ErrAccountDeletionBlocked。未模拟真实密钥删除或连接。
- 最小修复：持久化 cleanup phases 与凭据 fingerprint/identity generation，单独幂等 cleanup worker 从 durable evidence恢复，绝不重新登录或再发删除 IQ；完成后幂等移除 fleet owner/缓存/对应代次密钥；不允许按号码盲删新注册凭据。

### A6 P2：同号码就是永久身份，缺少凭据/注册代次

- Redis key `store.go:66-69,210-211` 只由 phone hash 决定，不含 tenant/backend/credential generation。taskBinding包含 accountId 但不是 key；新任务/新账号 id/new credential 均返回原 record。
- 当前必要保护：同一真实 WhatsApp 身份的别名和跨租户不能借新 ID 重登旧已注销身份；binding严格相等防跨任务读取结果（task service 69-70）。未发现该接口能向其他租户返回原绑定内容，不能称信息泄漏。
- 风险：同号码用户确实重新注册新身份后仍永久不可用（本地新AccountID测试证实 ID变化不能绕过，但未做真实新注册）；iOS原生/Android原生共用 phone key 时也会被同号 tombstone 阻断。Web若独立Redis并无Android gate，是否由后端覆盖由Web/后端审计确认。
- 最小修复：账号身份代次应结合 registration/device identity公钥指纹与后台稳定generation，经有证据的新注册/新授权动作切换；旧代次永久保留禁派单。仅“导入新字符串”不足以认定新身份。

### A7 P2 风险：新增锁会拖慢无关号码，死锁未证实

- [account_delete.go:37-43,71](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/app/account_delete.go:37) 在全进程 `appCacheLifecycleMu` 持有期间，等待 account `networkLifecycleMu` 再同步获取 sender gate 写锁。
- sender 写 socket 全程持有 gate RLock（[sender.go:383-405](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/sender.go:383)）；底层为 `net.Conn.Write`（[noise-socket.go:71-72](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/network/noise-socket.go:71)）。慢写期间 Begin 等待也继续占着全局缓存锁。
- 无关号码的 Create/Set/Remove 同样需要该全局锁（cache.go:39,72,153）。因此一个账号注销等慢写可能阻塞其他账号生命周期。未在现场测到持续时长，未证明锁环死锁；不要报已发生死锁。
- 最小修复：全局锁仅做实例 identity检查/冻结意图 CAS；等 sender drain 或网络I/O须退出全局锁，以账号级现有生命周期锁/代次二次校验保一致；避免新增全局或分布式锁。

### A8 P2 风险：异步登录拒绝仍成功受理、验证超时可重回代理失败分类

- 当前未提交补丁修复准备错误1011及旧1001→302，但 [waapp.go:367-390](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/app/waapp.go:367) 可以在准备后因新门禁返回拒绝 Promise；`login.go:366-369` 异步分支不看拒绝，返回“登录已发起”；[lifecycle_scheduler.go:412](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/lifecycle_scheduler.go:412) 验证超时仍可302。
- 并发触发条件：CreateWSApp初次检查通过，注销Claim/Begin随后生效，再 WALogin 二次检查拒绝；同步路径 GetResult错误仍1001（login.go:372-375）。后台DELETING过滤事件是否阻断连锁由后端确认。
- 这是代码竞态风险，尚未现场复现；本子审计没有新增整链并发测试，不声称当前仍有误失效代理。
- 最小修复：WALogin立即拒绝返回typed准备结果或异步可靠发布reason=ACCOUNT_DELETION_BLOCKED；timeout只表连接状态未知，不能无证据当代理损坏。

## 合理保护及明确不受该 gate 影响

- 账号 Claim/唯一 operation/持久未知禁重发、taskBinding签名校验、旧凭据/旧代次不能重登、发送前末端二次门禁均应保留。
- 新入队和已排队未写业务均有 sender 两次检查：[sender.go:119-125,168-174,383-387](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/sender.go:119)。已开始写入会完成后冻结；不能把其结果当 NOT_SENT。
- 入站普通 ACK：[ack.go:88-100](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/processor/ack.go:88) 无注销过滤，[node_processor.go:174-175](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/node_processor.go:174) -> [art.go:300-304](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/external/art.go:300) 可靠回执观察器；receipt：[node_processor.go:181-224](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/node_processor.go:181) -> [art.go:312-315](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/external/art.go:312) inbox观察器不被 getter 拦。HTTP可丢队列和后端消费是否有各自限制另审。
- 出站 ACK/receipt/ping/IQ result 白名单已用本地测试验证；不能写“所有回调都被 Android gate 丢弃”。
- 群事件 [group_snapshot_coordinator.go:275-310](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/group_snapshot_coordinator.go:275) 有事件自带完整资料可直接发增量；需要fallback live群同步时 GetValid会拒；因此“已有完整事件”与“补拉查询”不同。
- 注销后独立管理员 proof [account_deletion_proof.go:20-67](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/api/service/account_deletion_proof.go:20) 读取 manager，creator被冻结不影响其基本入口；manager自己被另一任务冻结则拒。此处无本任务绕creator读取的设计需求。
- GET状态跨节点恢复路径已存在：[http_gateway.go:128-146](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/coordinator/http_gateway.go:128) 无owner的GET deletion挑健康节点读共享Redis，不新分配账号；POST没有这种重发fallback。
- [sync.go:300-309](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/api/service/sync.go:300) 本地通讯录历史查询可用；清理会删除数据不是读取门禁。

## 本地验证、限制、证据路径

- `/private/tmp/creator-deletion-android-tests/overlay.json` 指向只在 /private/tmp 的追加测试副本，仓库未改。
- `/private/tmp/creator-deletion-android-tests/results.log`：4包20个顶层测试通过（含4个新增审计测试），针对 taskBinding、持久化、缓存、sender、late reply。
- `/private/tmp/creator-deletion-android-tests/cleanup-results.log`：第5个新增审计测试通过。
- 命令：`GOPROXY=off go test -overlay=... ./internal/accountdelete ./internal/service/node ./internal/service/node/nodes ./internal/service/app -run 'TestAudit|TestAccountDeletion|TestTaskDeletion|TestTaskBinding|TestRecordPersists|TestPersistedDeletion' -count=1 -v`，cleanup test另跑。
- 首轮沙箱因本地 miniredis bind 和编译缓存权限失败，是环境错误；按已授权隔离测试范围自动审批提权后通过。无联网下载、无远端访问。
- miniredis用于执行真实Go Store与Lua脚本；没有涉及SQL。连接为内存实现，不证明真实WhatsApp成功/失败。

## 建议修复顺序（仅方案）

1. P1：后端 RESERVED可撤销预留收口（根报告详述）；任务NOT_SENT/REJECTED受控解除；UNKNOWN/ACCEPTED不得清key。
2. P1：typed gate+真实控制停机+不报假在线；区分本地查询/结果收口/协议业务；保持底层业务禁派单。
3. P1：late-result CAS推进及ACCEPTED持久cleanup worker；后台账号状态/群关系/占用最终一致。
4. P2：身份代次与短化全局锁；修登录异步拒绝分类。
5. 部署影响：Android node 与 coordinator 按接口变化成套升级；后端签名释放/代次契约同步；前端只展示真实阶段不可凭按钮绕过。Web不应无必要部署。
6. 回滚：保留持久 ledger/tombstone 新状态兼容读取；旧版本不认识 RELEASED不得假定自动可用，需发布前设计compat；回滚代码不回滚不可逆WhatsApp事实；不得恢复已删凭据来重新登录。
7. 验收：无真实注销先用真实mapper/miniredis离线组合；实际验收另授权最小专用测试号：pre-send safe abort、late ACK、restart cleanup、混批无关账号、manager proof、旧新代次隔离；协议HTTP成功和cache在线不算业务验收。
