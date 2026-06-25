# wheel ↔ laqunxitong 协议层集成契约(as-is 实证基线)

> 状态:已核 · 2026-06-24 产出。
> 本文 = **从真代码逆向核出**的 wheel(Java/Spring)↔ laqunxitong(协议层,NodeJS/TS)集成契约,**非凭记忆**。
> 产出方式:10-agent 工作流(`wf_1aab2227-54d`)并行读两侧源码 → 交叉对账 → 对争议点独立证伪;所有结论带 `file:line`。
> 用途:armada `platform/protocol` + `platform/kafka` **防腐层的设计输入**(目标设计是下一轮,不在本文)。
> 它**取代/订正**记忆里自相矛盾的旧认知(同步HTTP命令 vs Kafka异步、批量上线端点存不存在)。

---

## 0. 一句话总览

两条通道,**严格单向、各司其职**:

| 通道 | 方向 | 机制 | 用途 | 谁是主动方 |
|---|---|---|---|---|
| **命令通道** | wheel → lqxt | **100% 同步阻塞 HTTP**(Spring RestClient) | 下命令(上下线/发消息/群操作/换IP) | wheel |
| **事件通道** | lqxt → wheel | **Kafka**(lqxt 是 **publisher-only**,无 consumer) | 回写状态(state_changed/owner/group/pairing) | lqxt |

- wheel **从不**用 Kafka 下命令;lqxt **从不**消费 Kafka。两条通道不交叉。
- **命令的"回执"在哪 = 分命令类型**(详见 §2.2):查询/消息/群操作命令结果在 HTTP 响应体里;**账号生命周期(上线/下线/退出)命令的 HTTP 只回"已受理",真正终态靠事件通道异步回写**。这正是旧认知里"同步 vs 异步"打架的根因——两个半句各对一半。

---

## 1. 拓扑

```
                    命令通道(同步 HTTP, X-Api-Key)
   ┌─────────┐  ──────────────────────────────────────▶  ┌──────────────┐
   │  wheel  │   POST/GET /v1/accounts|groups|messages…    │ laqunxitong  │
   │ (Spring)│                                             │ protocol-layer│
   │         │  ◀──────────────────────────────────────   │  (Fastify+    │
   └─────────┘     事件通道(Kafka, 5 topics, key=accountId) │   Baileys)   │
        │            protocol.{account,owner,group,         └──────────────┘
        │            pairing,message}.events.v1                    │
   4 个 @KafkaListener                                      Kafka publisher-only
   (account/owner/group/pairing;message 不消费)            (acks=-1, 幂等 producer)
```

- 连接配置:`ProtocolHttpClientConfig`(`base-url` + `X-Api-Key` 头 + `SimpleClientHttpRequestFactory`,connect-timeout **3s** / read-timeout **60s**)。
- lqxt 路由层:Fastify,两个 preHandler——`addApiKeyGuard`(x-api-key/Bearer)+ `addOwnerGuard`(`requireLocalOwner`,否则 `NotOwnerError`;白名单见 §4.4)。

---

## 2. 命令通道(wheel → lqxt)

### 2.1 传输事实(统一根因)

所有出站命令都过 `com.wheel.infra.protocol.ProtocolHttpExecutor` 的三个方法,底层都是 Spring **RestClient `.exchange(handler, false)` 同步阻塞**(无 WebClient/reactive、无 `@Async`、无出站消息队列):
- `postTyped` — 阻塞反序列化为 DTO 返回(`ProtocolHttpExecutor.java:71,80`)
- `postVoid` — 只 `ensureSuccess` 校验 2xx、不读响应体(`:89`)
- `getTyped` — 阻塞反序列化返回(`:64`)
- 失败统一映射 `ProtocolLayerException`(errorCode 取 body.code,网络/超时 → `TIMEOUT/NETWORK`,`:112-126`);429 `ACCOUNT_BUSY/WORKER_BUSY` 也走它,上层按 `retryAfterMs` 退避。

### 2.2 三种"回执"语义

| 语义 | 含义 | 命令 |
|---|---|---|
| **sync-blocking** | HTTP 响应体即结果,就地消费,**不碰 Kafka** | status / probe / type / refreshType;**全部** message / group / proxy 命令 |
| **async-callback** | HTTP 同步返回"**已受理/已开始**"(online 回 202+`OnlineResult{accepted}`,offline/logout 回 2xx),**真正终态由 Kafka `account.state_changed` 异步回写落库** | online / offline / logout |
| **fire-and-forget** | HTTP 同步发出但 void、best-effort,失败吞掉不阻断 | saveContact |

> ⚠ 关键:**传输全程同步**,但生命周期命令的**业务终态是异步的**。wheel 自己的 javadoc 写明:`OnlineResult.accepted()=true 仅表示协议层已开始处理,真正 ONLINE 状态通过 webhook account.state_changed 推送`(`AccountLifecycleClient.java:26-27`)。lqxt 侧 `/online` handler 只 `await openSocket`(发起建连)就回 202(`lifecycle.ts:143`),不等到 ONLINE。

### 2.3 出站命令全表(39 条,按 facade 分组)

**`AccountLifecycleClient` / `HttpAccountLifecycleClient`(账号生命周期 · 7)**

| 命令 | lqxt 路由 | 语义 | 备注 |
|---|---|---|---|
| online | `POST /v1/accounts/{id}/online` | async-callback | 调用前 wheel 自托管取 creds + `ProxyAllocator` 分配 1账号1IP;入参 `LoadConnectRequest{format, credential, proxy}` |
| offline | `POST /v1/accounts/{id}/offline` | async-callback | 保留 creds 可重上线;404 `ACCOUNT_NOT_FOUND` 幂等当成功 |
| logout | `POST /v1/accounts/{id}/logout` | async-callback | 不可逆(退出+移除设备),需前端二次确认 |
| status | `GET /v1/accounts/{id}/status` | sync | 状态快照,只读不落库(= "query status") |
| probe | `POST /v1/accounts/{id}/probe` | sync | 主动探活,**唯一真发包联系 WA** 的命令 |
| type | `GET /v1/accounts/{id}/type` | sync | PERSONAL/BUSINESS/VERIFIED |
| refreshType | `POST /v1/accounts/{id}/type/refresh` | sync | 结果同步返回;但 `account_type` 落库被冻结(见 §3.4) |

**`MessageClient` / `HttpMessageClient`(发消息 · 4)** — 全 sync

| sendText `POST /v1/messages/text` · sendImage `/image` · sendLinkCard `/link-card` · sendButtonCard `/button-card` |
|---|

> 营销发送分流优先级(`GroupMarketingSendJob.sendRound:182-197`):`isButton→button-card > isCard→link-card > hasImage→image > 兜底 text`。

**`ContactClient` / `HttpContactClient`(联系人 · 1)**

| saveContact `POST /v1/contacts/{jid}/save` | **fire-and-forget** | 两入口:进群前 `ensureRoleAccountsMutualContacts`(角色号全网状双向互加)+ 拉料子前 `addContactsBeforePull`;均 best-effort |

**`GroupClient` / `HttpGroupClient`(群 · ~20)** — 全 sync

| 群操作 | 路由 | 业务 |
|---|---|---|
| preview | `POST /v1/groups/preview` | **踩链接①**(解析邀请链接拿 groupJid,不加入)+ **群状态检测**(`isBanned`/memberCount) |
| join | `POST /v1/groups/join` | **踩链接②**(真加入) |
| createGroup | `POST /v1/groups/create` | 建群 |
| addParticipants | `POST /v1/groups/{jid}/participants/add` | 拉人 |
| promote/demote/remove | `…/participants/promote|demote|remove` | **设管理员**/降权/踢 |
| leave | `POST /v1/groups/{jid}/leave` | 退群 |
| setLocked/setAnnouncementOnly/setMemberAddMode/setJoinApproval | `…/settings/{locked|announcement|member-add-mode|join-approval}` | 锁群/禁言/加人模式/入群审批 |
| listPendingJoinRequests/approveJoinRequests | `…/pending` · `…/pending/approve` | 待审 |
| metadata/groupInviteCode | `GET …/metadata` · `…/invite-code` | 群元数据/邀请码 |
| updateSubject/Description/Picture/AnnouncementText | `POST …/{subject|description|picture|announcement-text}` | 改群信息(announcement-text 会发 `group.metadata_updated` 事件) |

**`ProxyClient` / `HttpProxyClient`(代理 · 3)** — 全 sync

| bind `POST /v1/accounts/{id}/proxy/bind` | rebind `…/proxy/rebind` | get `GET …/proxy` |
|---|---|---|
| **无生产调用方**(上线时 `LoadConnectRequest.proxy` 内联首绑) | 换 IP(不重新 pairing,仅 socket 重连);**生产唯一真实触发=删 IP 路径**(见 §4.1) | 查当前代理 |

> 本地代理设施 `ProxyAllocator`(`@Transactional FOR UPDATE` 行锁分配/复用同IP/池空抛 `BusinessException`)、`ProxyReaper`(`@Scheduled` 回收过期+孤儿绑定→IDLE)是**纯本地 DB,不发协议**。

### 2.4 "批量上线"真相(订正旧矛盾 ②)

- **lqxt 批量端点存在**:`POST /v1/accounts/online/batch`(`lifecycle.ts:169`)+ `POST /v1/accounts/offline/batch`(`:338`)。owner 分桶 + `OnlineGate.waitOnline` 节流(默认 ~50/s)+ 返回 `{requested,local,remote,accepted,timeout}` 汇总。两者被 owner-guard 白名单放行(`owner.ts:54-55`)。
- **但 wheel 从不调用它**。wheel 的"批量上线" = `TenantProtocolService.batchOnline` / `TenantAccountService.batchPerAccount`(`:502`)**Java for 循环逐个**调单账号 `onlineAccount → HttpAccountLifecycleClient.online → POST /v1/accounts/{id}/online`。**N 个账号 = N 次单账号 HTTP 往返**(前端只 POST 一次 `/api/tenant/accounts/batch-command`,伪批量在 wheel 后端展开)。单账号失败不阻断其余(skipped+原因)。
- ⇒ **lqxt 的 batch 端点对 wheel 是死代码**。

---

## 3. 事件通道(lqxt → wheel,Kafka)

### 3.1 Producer 机制(lqxt 侧)

- **publisher-only**:整个 laqunxitong 只有 `protocol-layer` 子目录是 Kafka 生产者,**无任何 consumer**。
- 单个共享 **幂等 producer**(`publisher.ts:163`,acks=-1,maxInFlight=1)。topic 由 `topicKindFor()` 前缀路由(`subjects.ts:84`):`account.owner_*→owner`、`message.*→message`、`group.*→group`、`pairing.*/qr.*→pairing`、其余→`account`。
- **Kafka key 恒 = accountId**(同账号分区内有序)。
- ⚠ **背压会丢事件**:inflight 超限或连发 3 次失败 → 写本地 DLQ jsonl 而非 Kafka。Broker 故障下"活"事件也可能被静默泄到 DLQ。

### 3.2 Consumer 机制(wheel 侧)

4 个消费者,各 1 个 `@KafkaListener onMessage(EventEnvelope, Acknowledgment)`,`@Profile("kafka")`,`containerFactory=protocolEventListenerContainerFactory`(`AckMode=MANUAL_IMMEDIATE`,concurrency=3)。

**统一管线**(4 个消费者一致):
```
null-envelope guard → EventIdempotencyChecker.markIfAbsent(eventId) 去重
  → handlersByType.get(type) 分发
      → handler==null ⇒ warn 'unregistered_event' + ack(静默丢弃,不重投)
      → 否则 TenantContext.runIgnoringTenant(handler.handle)  ← 跨租户(避免 tenant_id=-1 注入打空反查)
  → 手动 ack.acknowledge()
  → 抛异常:不 ack + rethrow ⇒ spring-kafka 重投,幂等兜底
```
⚠ **幂等是内存 LRU(`InMemoryEventIdempotencyChecker`)**,Redis/DB 实现是 TODO 桩 ⇒ **去重仅单实例,不跨实例**。

### 3.3 事件 → 消费者 → 落表(25 个实际发出的事件)

| topic / 消费者 | 事件(handler → 落表) |
|---|---|
| **account** `protocol.account.events.v1`<br>`AccountEventConsumer` | `state_changed`(全状态转换唯一漏斗,携带 semantic=PROXY_FAILED/NEED_REAUTH)、`online_changed`、`heartbeat`(默认关,见下)、`need_reauth`、`restricted`(风控禁言6h/24h)、`new_chat_capping`、`logout`(仅手动路由)、`type_detected`(**log-only**,见 §3.4)、`proxy_changed` → 落 `wheel_tenant.account` |
| **owner** `protocol.owner.events.v1`<br>`OwnerEventConsumer` | `owner_assigned`/`owner_changed`/`owner_unassigned` → `account`(worker_id/owner_endpoint;unassigned 写 NULL) |
| **group** `protocol.group.events.v1`<br>`GroupEventConsumer` | `participant_changed`(promote 路径写 `join_task_result.markAdmin`)、`metadata_updated`(仅 timestamp/source,subject 归 preview)、`health_reported`(权威 member_count + is_banned) → `group_link` / `join_task_result` |
| **pairing** `protocol.pairing.events.v1`<br>`PairingEventConsumer` | `pairing.code_generated`、`qr.code_generated`、`pairing.completed`(非空 jid 时 markStateSynced account)、`pairing.failed` → `wa_login_session` / `account` |
| **message** `protocol.message.events.v1` | **无消费者**(`package-info.java:19` 明写"目前不消费,留空") |

### 3.4 注意点

- `account.heartbeat`:lqxt 仅当 `config.worker.heartbeatEventEnabled=true`(**默认 OFF**)才发 ⇒ 监听器默认被饿着。
- `account.type_detected`:会发,但 wheel handler **故意 log-only 不落库**(`account_type` 导入即冻结,`MyBatisAccountStateUpdater.java:490`)。
- 群封禁不可靠:lqxt `groups` preview/metadata **硬编码 `isBanned:false`**;权威封禁只来自 `group.health_reported`。
- 发消息路由**无 ONLINE 闸**(lqxt 不校验账号是否在线就发)。

---

## 4. 缺口与幽灵清单(本文最核心)

### 4.1 ★头号缺口:`account.proxy_failed` 双向断 ⇒ "IP 失败自动换IP"功能未接通

这是全链路最严重的一处,**生产者、消费者两侧同时断**:

1. **生产侧(lqxt)永不发**:`account.proxy_failed` 登记进 `EVENT_TYPES` 且被列为 `CRITICAL_EVENTS`(必须可靠投递,`subjects.ts:14,49`),但**全仓零 `publish()` 调用点**。真正的代理失败路径(看门狗 `handleConnectStall` `account-manager.ts:769`、close 的 class-B 分支 `:703`)走的是 `publishStateChange(...,'PROXY_FAILED')` ⇒ 发 `account.state_changed`(semantic=PROXY_FAILED)+ `proxyFailedTotal` 指标,**从不发 `account.proxy_failed`**。
2. **消费侧(wheel)无 handler**:`MyBatisAccountStateUpdater.onProxyFailed`(`:549-573`)+ `AccountProxyFailedPayload` **完整实现了**,但**没有任何 `AccountEventHandler` 返回 `eventType()="account.proxy_failed"`**。即便上游发了(它不发),运行时也会命中 `handler==null` → warn+ack → 丢弃。
3. **后果**:`ProxyFailureRecoveryService.recover`(IP 失败3次 → `rotateSession` 换 `_session` token / `switchIp` 换行 → `rebind`)这条**自动换IP链路在生产无事件入口**;`recover()` 全部调用方都在测试。前端"IP异常·自动换IP中"语义背后**没有真实自愈**。`rebind` 生产唯一真实触发 = 删IP时 `TenantResourceService.deleteIp → reassignForDeletedProxies → switchIp → safeRebind`(异常吞 warn,fire-and-forget)。

### 4.2 生产侧 6 个幽灵事件(声明了但永不发)

| 幽灵事件 | 级别 | 实际用什么替代 |
|---|---|---|
| `account.proxy_failed` | CRITICAL | `state_changed(PROXY_FAILED)` + `proxyFailedTotal` 指标 |
| `account.banned` | CRITICAL | 封号类断开译成 `need_reauth` + `state_changed(NEED_REAUTH)` |
| `account.risk_triggered` | CRITICAL | `account.restricted`(reachoutTimeLock) |
| `account.stale_detected` | best-effort | `staleDetectedTotal` 指标 + reconnect→`state_changed` |
| `account.proxy_rotated` | best-effort | 只有 `proxyRotatedTotal` 指标;rebind 发 `account.proxy_changed` |
| `account.rate_limited` | best-effort | `group_busy`/`worker_busy`/`reconnect_limited` |

> **反向镜像**:wheel 的 `AccountEventConsumer` **给其中 5 个建了 handler**(`banned/stale_detected/risk_triggered/rate_limited/proxy_rotated`),但这些事件永不到达 ⇒ **5 个 handler 是接不到货的死代码**。契约在两个方向上都漂移了:有的 handler 没事件,有的事件(proxy_failed 的 sink)没 handler。

### 4.3 wheel 侧丢弃的 6 个事件(lqxt 发了,wheel 没接)

| 事件 | 为什么没接 |
|---|---|
| `message.received` / `message.ack` | 整个 `message.*` topic 无消费者(留空) |
| `account.group_busy` / `account.worker_busy` | busy 已由 HTTP **429 同步**告知(`ProtocolLayerException`),Kafka 事件冗余,丢弃 |
| `account.reconnect_requested` / `account.reconnect_limited` | wheel 从不驱动 `/reconnect` 路由,自然不接 |

### 4.4 死路由(lqxt 有,wheel 不调)

- `POST /v1/accounts/online/batch` + `offline/batch`(§2.4)
- `POST /v1/accounts/{id}/proxy/bind`(online 内联首绑)
- `/v1/accounts/{id}/reconnect`(wheel 用 relogin=offline+online 替代)
- `/v1/accounts/{id}/alive`(facade javadoc 注明已删,Kafka 心跳替代)

> lqxt 共 **107 个 HTTP 端点**(102 个 `/v1/*` + 5 个 infra),wheel 只消费其中一个子集。完整路由分布:accounts 31 / groups 25 / messages 17 / contacts 10 / profile 5 / business 4 / channels 4 / chats 6 / admin 5 / auth 2 / import 5 / export 4 / owner 2。**wheel 远未用满**(消息细操作、contacts、chats、channels、business、profile 等大片未接)。

### 4.5 入站 `/api/internal` 回调(HTTP 通道的另一半,待外部确认)

wheel 还有 4 个**入站** HTTP 回调控制器(由 lqxt/H5/scheduler 反向调,**非 Kafka**):`H5WorkerEventController`(`/h5/worker-event`)、`InternalDispatchEngineController`(`/scheduler`)、`HeartbeatController`(`/heartbeat/batch`)、`GroupLinkHealthController`(`/grouplink/health/report`)。**wheel 仓内无调用方**(只有测试引用)⇒ 是否真有流量只能从 lqxt/H5 侧或运行日志确认。`GroupLinkHealthController` 是 Kafka `group.health_reported` 的 HTTP 重复路径。

### 4.6 基础设施弱点(随防腐层一并处理)

- 幂等内存 LRU,**不跨实例**(§3.2);
- 背压会把事件静默泄到 DLQ jsonl(§3.1);
- 发消息无 ONLINE 闸;群封禁 preview 硬编码 false(§3.4)。

---

## 5. 两个旧矛盾的最终裁决

**① wheel→lqxt 是同步 HTTP 还是 Kafka 异步?** → **混合,但传输 100% 同步 HTTP**。命令通道全部走 RestClient `.exchange(...,false)` 阻塞;Kafka **只**做反向事件回写,**从不**承载命令。差异在"结果落在哪":非生命周期命令结果在 HTTP 响应体(同步);生命周期 online/offline/logout 的 HTTP 只回"已受理",**业务终态靠 Kafka `account.state_changed` 异步收敛**。旧记忆"同步命令通道"对了传输、漏了生命周期终态的异步性;今晚"全异步 Option C"则把生命周期的异步终态错误外推到了所有命令。

**② 批量上线端点存不存在?** → **lqxt 端点存在(`lifecycle.ts:169`),但 wheel 不用**;wheel 的批量 = N 次单账号 HTTP 循环(§2.4)。两条对打的旧记录都只说对了一半。

---

## 6. 给 armada `platform/protocol` 防腐层的设计输入(待下一轮决策)

本文是 as-is,不是 to-be。下一轮 brainstorming 要拍的开放问题(由上面缺口直接引出):

1. **命令/事件二分**:armada 防腐层是否照搬"同步 HTTP 命令 + Kafka 事件回写"二分?生命周期命令的"已受理→终态异步收敛"是否显式建模成一种 `AsyncCommand`/`pending→settled` 状态,而非藏在 service 注释里?
2. **幽灵事件收敛**:6 个幽灵事件 + 5 个空转 handler,是**砍**(承认 `state_changed(semantic)` 是唯一真相、删死 handler/死 payload)还是**补**(让 lqxt 真发具名事件)?**`account.proxy_failed` 自动换IP链路**要不要在 armada 真正接通(它属 `resource/proxy` + `platform/proxy`,是 §4.1 头号债)?
3. **批量**:armada 要不要真用 lqxt 的 batch 端点(省 N 次往返 + 用上 `OnlineGate` 节流),还是保留应用层循环?
4. **message.* topic**:armada 是否要消费(目前整 topic 丢弃)?取决于一期是否有消息回执/会话需求。
5. **`/api/internal` 入站回调**:这 4 个回调在 armada 归 `platform/protocol`(入站防腐)还是各自业务域?需先确认它们是否真在被调。
6. **可靠性**:幂等改跨实例(Redis/DB)、DLQ 重投、发消息 ONLINE 闸——哪些进一期防腐层基线。
7. **目标参考**:是否把干净的 `protocol-channel-hub`(NestJS/TDD)作为 lqxt 的替换目标 / 防腐层接口形状的参考。

---

## 7. 吞吐分类模型 + fan-out 全台账(0624 实证)

> 来源:10-agent 工作流 `wf_526e50a3`(穷尽 wheel 所有批量/定时协议调用点,带 file:line)。
> 用于:防腐层不能"一刀切限流",要按**约束轴**分类,每类的 pacing 放在不同位置。

### 7.1 两根真实约束轴(决定"能不能拉满")

| 类别 | 约束 | 拉满后果 | pacing 该放哪 |
|---|---|---|---|
| **cpu-handshake** | 协议层 worker 单线程 Node 的 libsignal Noise 握手(~20-50ms CPU/个),并发握手 = 单核串行烧 CPU、event loop 冻结 | 冻 worker → 误杀健康号(StaleDetector)→ 重连风暴 | **协议层**(它知道自己 CPU,已有 `OnlineGate` 三层令牌桶;随 worker 数水平扩) |
| **wa-rate-social** | WhatsApp 每号反垃圾限速(**真封号**) | **封号** | **wheel + 协议层双层**:必须**按发言号**令牌桶,不是全局/按群 |
| **io-probe** | 几乎不受限(纯 I/O 等待,Node 强项);弱约束=探针号被 WA 频控 + 共享 event loop | 基本无害 | wheel 侧**有界并发**即可,可激进 |
| **local-only** | 只动本地 DB | 无 | 本地 SQL cap |

### 7.2 定时任务台账(14 个 @Scheduled)

> ⚠ **头号系统级缺陷**:全仓**无** `TaskScheduler`/`spring.task.scheduling.pool.size` 配置 ⇒ Spring 默认调度器**单线程**,这 14 个 job **全串在一条线程上**。任一慢 job(`AutoOnlineSupervisor` 一拍 200 握手 / `DailyStatJob` 全租户回填 / `GroupLinkHealthJob` 200 preview)**阻塞所有其它 job**。扩容前必须先把调度器改多线程池或拆独立 executor。(印证前一轮对 GroupLinkHealthJob 的"堵死定时线"判断,且量化了。)

| Job | 周期 | 类别 | 单轮 cap | 备注 |
|---|---|---|---|---|
| `AutoOnlineSupervisor` | 30s | **cpu-handshake** | 200/拍 | **拍内 200 次握手无间隔**,最高风险扇出 |
| `ImportOnlineJob`(dispatch) | 10s | cpu-handshake | 5/拍 | 温和;与 AutoOnline 打同一 `online` 端点,可叠加 |
| `GroupMarketingSendJob` | 10s | **wa-rate-social/BAN** | 全局 20 条/拍 | **唯一定时发消息**;按群不按号节流(封号缺口) |
| `GroupLinkHealthJob` | 300s | io-probe | 200/轮 | preview,全局探针号;`probeTimeoutMs` 未透传=形同虚设 |
| `GroupLinkPreviewBackfillJob` | 120s | io-probe | 100/轮 | preview,同探针号(候选互斥) |
| `GroupMemberCountRefreshJob` | 180s | io-probe | 50/轮 | metadata,各群管理员号(分散) |
| `AccountGroupSyncJob` | 180s | io-probe | 50/轮 | listParticipating,各在线号 |
| `GroupLinkImportJob` | 3s | local-only | batchSize | 解析入库,最快轮询 |
| `ProxyReaper` | 60s | local-only | 批量 SQL | IP 绑定回收 |
| `HeartbeatFlushSnapshotJob` | 30s | local-only | 全量刷盘 | 心跳态落库 |
| `MaterialPhoneRescanJob` | cron 1min | local-only | 500/次 | 料子超时回标 |
| `WaLoginSessionPurgeJob` | cron 1min | local-only | 500/次 | 敏感字段清理 |
| `DailyStatJob` | 每天 01:10 | local-only | 全租户×回填窗口 | 日聚合 |

### 7.3 用户/引擎驱动 fan-out

| 点 | 触发 | 类别 | 扇出 | pacing 现状 |
|---|---|---|---|---|
| `batchPerAccount`(批量上线/下线) | 用户 batch-command | **cpu-handshake** | **=勾选号数 N,无任何 cap**;online 走 relogin 最坏 **2N** 次握手 | **零节流**,纯串行 for |
| `reassignForDeletedProxies`(删IP批量重登) | 删IP | cpu-handshake | =被删IP数,**无cap** | **零节流** |
| `ProxyFailureRecoveryService.recover`(自动换IP) | Kafka `proxy_failed` | cpu-handshake | 事件级 1:1 | 阈值≥3 门禁=天然节流 ⚠ **但生产永不触发**(§4.1:lqxt 不发该事件;且 handler 只在 worktree 分支,不在主 src) |
| **`DispatchEngineService` 拉群派单** | 引擎(BatchDispatchWorker) | **wa-rate-social + io-probe 混合** | **单任务最大扇出面**(见下) | pass 间 sleep(≥1s)+ 4 线程池;**行内/角色循环零节流** |

**拉群派单单任务最大扇出(全程无内部 sleep 的热点):**
- `ensureRoleAccountsMutualContacts`:**O(N²) saveContact**(N=拉手∪管理∪站台,N=20→**380 次**),裸双重 for,**零节流** ← 最该上限流
- `executeWaterForRow`:`ceil(水军数/10)` 次 addParticipants 在一个 executeRow 内连发,**无 sleep**(绕开了 pass 间隔)
- 每轮:addParticipants(≤10 号)+ addContactsBeforePull(≤10 saveContact)+ approve;轮数上限 5000
- 唯一全局节流 = `BatchDispatchWorker` pass 间 `human_interval_sec`(下限 1s)+ 固定 4 线程池;**行内全靠协议层 429 反压兜底**

### 7.4 "零节流热点"清单(armada 重构优先上限流的点)

1. **cpu-handshake**:`batchPerAccount`(无cap)、`reassignForDeletedProxies`(无cap)、`AutoOnlineSupervisor`(拍内 200 无间隔)→ 改为**提交协议层 batch 端点 + 协议层 `OnlineGate` 节奏**,wheel 不自己 fan-out。
2. **wa-rate-social**:`ensureRoleAccountsMutualContacts` O(N²)、`executeWaterForRow` 同步 drain、`GroupMarketingSendJob`(全局预算→**改按发言号令牌桶**)→ 真封号轴,必须 per-account 限速。
3. **系统级**:先把单线程调度器改多线程/独立 executor,否则任何限流都和"互相阻塞"耦合,难定位。

---

## 8. 可观测性基线(回答"重构后要能观察线上")

### 8.1 现状:两套割裂的体系

- **lqxt = Prometheus,已有 35 个 `unsea_*` 指标**(`/metrics` 端点):核心扩容信号齐全——`unsea_event_loop_lag_seconds`、`unsea_online_inflight`、`unsea_online_duration_seconds{source,result}`、`unsea_group_op_inflight`、`unsea_accounts_by_state`、`unsea_reconnect_total`、`unsea_stale_detected_total`、`unsea_disconnect_total{semantic,code}`、`unsea_libsignal_error_total`、`unsea_kafka_producer_inflight` 等。
- **wheel = 0 个指标**(pom+code 双证实:无 Micrometer/Prometheus/Actuator)。3836 条 SLF4J 日志是唯一可观测 substrate,要算量只能 grep 日志或查 DB。

### 8.2 关键缺口(直接卡住"该不该限速/扩容"的决策)

1. **★限流命中率两侧都不可数**:lqxt 的 `OnlineGate`/`OperationGate` 拒绝(429/`WORKER_BUSY`/`ACCOUNT_BUSY`)**不 inc 任何 Counter**;wheel 吃到 429 只进日志。⇒ **"令牌桶被打满的频率"完全看不到 → 无法判断该不该调 rate**。
2. **wheel 不知道自己正被限流**:lqxt 把 gate 限流作为 `account.worker_busy`/`account.group_busy` 发 Kafka,但 **wheel 无订阅者**(§4.3)→ backpressure 信号在 wheel 完全丢失。
3. **lqxt 的扩容信号困在 Prometheus 里**:`event_loop_lag`/`online_inflight` 这些"该不该加 worker"的核心 gauge,wheel 取不到(不 scrape、Kafka 里也没有)。
4. **wheel 派单吞吐无指标**:`BatchDispatchWorker` 的 pass 吞吐、4 线程池饱和度、队列深度全靠日志。
5. **死指标**:`unsea_proxy_rotated_total`/`unsea_proxy_bytes_*` 定义了从不 inc。

### 8.3 armada 观测计划(从一开始内建)

- **wheel/armada 侧**:引入 Micrometer + Prometheus(Actuator `/actuator/prometheus`)。每协议命令 RED(速率/错误/延迟)、**429/限流命中率**、线程池+连接池饱和度、Kafka 消费 lag、批量 `submitted/accepted/timeout`、巡检 sweep 时长 vs 周期、按吞吐类别打 tag。
- **补 backpressure 回路**:wheel 订阅 `worker_busy`/`group_busy`(或读 429),让派单端能感知协议层在限流并自适应退避。
- **统一看板**:把 wheel 派单速率 与 lqxt `online_inflight`/`event_loop_lag`/限流命中 放一起 —— 限速/扩容需要这条端到端因果链。
- **决策指标对**:`online_duration_p99` + `event_loop_lag` → 调上线闸/加 worker;`每号 send_rate` + 封号率 → 调营销节奏;`sweep 时长` vs 周期 → 调巡检并发。
- **核心理念**:**有了数据,"拉满 vs 限速"用测的,不用吵** —— 把闸调到指标允许的最高,撞顶就加 worker。

---

## 9. 防腐层设计约束(前几轮已收敛的结论)

1. **路由**:协议侧路由(网关 / 集群内转发),**不让 wheel 指定 worker** —— 拓扑不泄进业务。
2. **传输**:命令通道继续 HTTP,但做成**传输无关 port**(`ProtocolCommandPort`),HTTP 是 adapter;NATS 仅作"动态归属寻址成头号痛点时"的备选。Kafka 做命令=排除。
3. **批量 = 异步提交 + 事件回终态**:`submitBatchOnline(ids)` 交协议层 batch 端点,**不在 wheel fan-out**;终态走 Kafka。
4. **按吞吐类别分治限流**(§7.1):cpu-handshake 交协议层 gate;wa-rate-social 必须 per-account 令牌桶;io-probe 用 wheel 有界并发。
5. **bulkhead**:后台/批量与前台命令各用独立有界线程池 + 连接池;每操作独立短超时 + 熔断;HTTP 客户端换带连接池实现(弃 `SimpleClientHttpRequestFactory`)。
6. **调度器**:多线程调度池 / 独立 executor,杜绝单线程互相阻塞。
7. **观测先行**(§8):Micrometer 内建,限流命中率 + 派单吞吐 + backpressure 回路全部可见。

---

## 10. fan-out 点业务语义对照(大白话,重构定限速标准用)

> 这是 WhatsApp **拉群(把人拉进群)+ 群营销** 系统。每个 fan-out loop 在业务上"对 WhatsApp 做了什么动作",决定它该按哪根轴限速。

**角色术语**:拉手=干活把人拉进群的号 · 料子=要被拉进群的目标客户手机号 · 站台=撑场面的小号(进群装活跃)· 水军=充人数/造氛围的小号 · 管理员=能提权/踢人/审批入群的号。
**动作黑话**:背靠背=一条接一条不停顿连发 · drain=把待办列表循环做到空 · pass=派单引擎一"趟",趟间 sleep≥1s 节流 · O(N²)=N 个号两两组合。

| loop | 业务=干啥 | 轴 |
|---|---|---|
| `GroupMarketingSendJob.sendRound` | 给一批群**群发营销消息**;每群每轮几条**背靠背连发** | ②封号 |
| `ensureRoleAccountsMutualContacts` | 进群前所有角色号(拉手+管理+站台)**两两互加好友**(像真人社交圈,不易被判异常);N²,N=20→380 次 | ②封号 |
| `addContactsBeforePull` | 拉手把料子拉进群**前先逐个加为好友**(加了再拉更易成功) | ②封号 |
| `executeWaterForRow` | 群拉够人后往群里**塞水军**充人数;while-drain 每批 10 连塞,绕开 pass 间隔 | ②封号 |
| `joinStandbys` | 安排小号**进群站台**撑场面;SLOW 每个隔 3s,FAST 无间隔 | ②封号 |
| `approvePendingJoinRequestsIfNeeded` | 群开了审批时管理员**批准入群申请**放料子/水军进来;逐个批 | ②封号 |
| `batchPerAccount` | 账号列表勾一批**批量上线/下线**;无上限串行握手 | ①CPU |
| `AutoOnlineSupervisor` | 掉线的"自动上线号"每 30s **自动重上线**;一拍≤200 无间隔 | ①CPU |
| `reassignForDeletedProxies` | 删 IP 时把受影响账号**换新 IP 重连** | ①CPU |
| Health/Preview/MemberCount/Sync 巡检 | 查群**还在不在/解析群标识/刷人数/同步账号所在群**(只读) | ③IO |
| `quitRoleByLeave` | 任务收尾**角色号退群** | ③IO |

> ②的每一条现在**都缺 per-发言号限速**(只有全局预算/按群间隔/pass-sleep)。armada 重构铁律:**每个 ② loop 必须穿过 per-号令牌桶,且不同动作不同阈值**(发消息≠加好友≠拉人≠审批,WhatsApp 各有单号安全速率)。

---

## 11. 三轴测试观察策略 + 重构取向(0624 决策)

| 轴 | 测试环境策略(现在) | armada 重构取向 |
|---|---|---|
| **②封号** wa-rate-social | **保持现状,不放开**。现有节流(营销全局预算/按群间隔/pass-sleep/SLOW)维持,**不拿真号试封号底线**。 | 每个发送/拉人/加好友/进群/审批 loop **必须穿过 per-发言号限速**(现状只有全局/按群,缺 per-号);**节流不弱化**,只补粒度。 |
| **①CPU** cpu-handshake | **放开 cap 压测**,用 `observe-load.sh` 盯 `evlag_ms` / `reconn/s` / `stale/s` / lqxt CPU,**经验测出单 worker 握手天花板**。 | 不在 wheel 硬编 cap;pacing 归协议层 `OnlineGate`(它知 CPU)+ **横向加 worker**(随 worker 数线性扩);批量=异步提交 batch 端点 + Kafka 回终态。 |
| **③IO** io-probe | **放开,可激进并发**(探活/退群不封号不烧握手 CPU)。 | wheel 侧**有界但宽松**并发池即可;留意 health+preview **共用单探针号**的集中受压点,可分散到多号。 |

**测试观察判据**(①③ 放开压时):
- `evlag_ms` 持续 >50-100ms = 协议层单核饱和、握手在排队 → 这就是 ① 的天花板;
- `reconn/s` / `stale/s` **突增** = 已在雪崩(健康号被误杀)→ **立即回退,记下临界并发数**;
- lqxt CPU 近 100%(单核)= 该横向加 worker,不是该限速。
- 出来的数 → 定"单 worker 安全握手并发"基线 → armada 据此设 `OnlineGate` 节奏 + 算扩容比例。

**观测工具**:`observe-load.sh` 已部署 xieyi(协议层,裸 node,CPU/RSS 取 `/metrics` 的 `unsea_process_*` 计数器)+ wheel-test(后端 docker `wheel-backend`)。压测时先开它,再放开 ①③。

**前置**:放开前先把 Spring 调度器配多线程(`spring.task.scheduling.pool.size`),否则 14 个 job 互相阻塞会污染观察数据(分不清是协议层慢还是调度被堵)。

---

## 12. 死代码清单(armada 不移植 / 砍或补 · 0624 实证)

> workflow `wf_b50cf163`:逐 facade 方法查生产调用方(排除 test/mock/target/worktree)+ 逐事件查 handler。三类"无用集成点"。

### 12.1 死出站 HTTP —— wheel 定义了 facade 方法但**零生产调用**(8)

| facade 方法 | lqxt 端点 | 现状 |
|---|---|---|
| `AccountLifecycleClient.type` | GET /v1/accounts/{id}/type | 零调用(连测试都没);生产只用 `refreshType` |
| `GroupClient.updatePicture` | POST /v1/groups/{jid}/picture | 仅 test/mock;改群头像未接线(对齐"群头像富上传待产品定") |
| `GroupClient.updateAnnouncementText` | POST /v1/groups/{jid}/announcement-text | 仅 test/mock;业务侧未接线 |
| `ProxyClient.bind` | POST /v1/accounts/{id}/proxy/bind | 零调用;首绑走 `ProxyResolver`/online 内联,非此 facade |
| `ProxyClient.get` | GET /v1/accounts/{id}/proxy | 零调用 |
| `RiskClient.restriction` | GET /v1/accounts/{id}/restriction | 零调用 |
| `RiskClient.messageCap` | GET /v1/accounts/{id}/message-cap | 零调用 |
| `AccountImportClient.importBatch` | POST /v1/accounts/import/batch | 仅测试 `verify(never())`;批量导入走本地建号、不调协议 |

> RiskClient 3 方法仅 `usability` 活(建群门控);ProxyClient 3 方法仅 `rebind` 活——两个 facade 各 **2/3 是死方法**。
> ⚠ 删前确认这些 lqxt 端点有无**非 wheel** 的消费者(本次只在 wheel 侧判定)。

### 12.2 无人读的入站事件 —— lqxt 发、wheel 丢(6,已穷尽:25 真发事件中 19 有 handler、这 6 个无)

| 事件 | topic | 丢在哪 |
|---|---|---|
| `account.reconnect_requested` / `account.reconnect_limited` | account | `AccountEventConsumer` handler==null → warn+ack 丢弃;wheel 不驱动 reconnect |
| `account.group_busy` / `account.worker_busy` | account | 同上;busy 已由 HTTP **429 同步**告知,Kafka 冗余 |
| `message.received` / `message.ack` | **message(整 topic 不听)** | 无 `@KafkaListener`;`package-info` 写"留空" |

### 12.3 反向死 handler —— wheel 建了 handler、lqxt 永不发(6)

`account.{stale_detected, proxy_failed, proxy_rotated, risk_triggered, banned, rate_limited}` —— 这 6 个在 lqxt 是**声明但零 publish** 的幽灵事件(被 `account.state_changed` 的 semantic / Prometheus 指标取代),但 wheel **都注册了 handler** → handler 永远等不到货 = 死代码(`proxy_failed` 的 sink `onProxyFailed` 见 §4.1)。

### 12.4 armada 取向
- **死出站(12.1)**:不移植这 8 个 facade 方法(用到再加)。
- **无人读事件(12.2)**:`message.*` 要不要消费取决于一期有无"消息回执/会话"需求;busy/reconnect 事件可不接(已有 429 / wheel 不驱动 reconnect)。
- **反向死 handler(12.3)**:不移植这 6 个空 handler;若要 `proxy_failed` 自愈,得先让 lqxt 真发该事件(§4.1)。
