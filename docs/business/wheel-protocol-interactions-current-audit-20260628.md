# wheel 当前协议层交互接口梳理

> 日期：2026-06-28
> 范围：`wheel` 仓库静态代码审计
> 目的：给 armada 重做协议相关能力时，对齐 wheel 侧除账号上线/下线以外仍在使用或仍被代码保留的协议层接口。

## 1. 结论先行

当前 wheel 和协议层的交互不只账号 `online/offline`。按代码实况看，仍需要关注四大块：

1. **群操作**：这是除账号上下线外最大的热路径，包括进群、拉人、踢人、升/降管理员、退群、查群详情、建群、取邀请链接、群设置、入群审批。
2. **群营销消息**：群营销任务会通过协议层发文本、图片、普通链接卡片、按钮链接卡片。
3. **Kafka 回调事件**：账号状态、风控、封禁、登录态、账号类型、群健康、群成员变更、pairing/QR 结果等都靠协议层事件异步落库。
4. **辅助能力**：联系人保存、账号可用性查询、代理失败重绑、账号绑定/pairing/QR/导入相关 command path 仍在代码中。

需要特别注意：`docs/协议层接口契约-在用.md` 和当前代码不是完全一致。

- 文档说“导入不调协议层”，但代码仍保留 `pairing-code`、`qrcode`、`import/baileys-json` 三个后端 command 端点。
- 文档保留 `/v1/groups/preview`，但主代码当前没搜到业务调用；链接识别实际走抓 WhatsApp invite 页面，群健康走 `metadata`。
- 文档精简版剔除了 owner topic，但代码仍有 `protocol.owner.events.v1` consumer。
- 代码里的 account/group 事件 handler 比精简契约更宽，仍兼容一些历史事件。

所以 armada 对接时建议分成两层：

- **必须兼容的当前热路径**：群操作、消息发送、Kafka 核心事件、联系人保存、账号可用性、代理重绑。
- **需要产品/架构确认的残留接口**：preview、batch online、import batch、proxy bind/get、risk restriction/message-cap、owner topic、若干历史事件。

## 2. 审计依据

主要代码入口：

- 协议 HTTP facade：`wheel-api-v2/wheel-api-infra/src/main/java/com/wheel/infra/protocol/facade/`
- 协议 HTTP 实现：`wheel-api-v2/wheel-api-infra/src/main/java/com/wheel/infra/protocol/impl/`
- 账号生命周期业务：`wheel-api-v2/wheel-api-tenant/src/main/java/com/wheel/tenant/service/TenantAccountService.java`
- 账号导入/绑定业务：`wheel-api-v2/wheel-api-tenant/src/main/java/com/wheel/tenant/service/TenantAccountImportService.java`
- 拉群调度引擎：`wheel-api-v2/wheel-api-internal/src/main/java/com/wheel/internal_/scheduler/DispatchEngineService.java`
- 进群任务 worker：`wheel-api-v2/wheel-api-internal/src/main/java/com/wheel/internal_/scheduler/JoinTaskWorker.java`
- 群营销发送：`wheel-api-v2/wheel-api-tenant/src/main/java/com/wheel/tenant/service/GroupMarketingSendJob.java`
- Kafka 消费：`wheel-api-v2/wheel-api-internal/src/main/java/com/wheel/internal_/kafka/`
- 当前契约文档：`docs/协议层接口契约-在用.md`

本文里的“当前调用”以 `src/main/java` 静态调用点为准；测试、mock、注释不算业务调用。

## 3. 出站 HTTP 总览

### 3.1 账号绑定 / 导入

实现类：`HttpAccountImportClient`

| 协议接口 | wheel 方法 | 当前业务入口 | 状态 | 说明 |
|---|---|---|---|---|
| `POST /v1/auth/pairing-code` | `requestPairingCode` | `TenantAccountImportService.requestPairingCode` | 后端入口存在 | 同步只拿受理回执；真实 code 走 `pairing.code_generated` Kafka |
| `POST /v1/auth/qrcode` | `requestQrCode` | `TenantAccountImportService.requestQrCode` | 后端入口存在 | 同步只拿 QR session；QR 本体走 `qr.code_generated` Kafka |
| `POST /v1/accounts/import/baileys-json` | `importBaileysJson` | `TenantAccountImportService.importBaileysJson` | 后端入口存在 | 与“wheel 自托管 creds，导入不调协议层”的新模型冲突，需要确认是否废弃 |
| `POST /v1/accounts/import/batch` | `importBatch` | 未发现主代码调用 | 残留/备用 | facade + HTTP 实现存在，业务未接入 |

后端 controller 暴露的 command path：

- `POST /api/tenant/accounts/import/pairing-code`
- `POST /api/tenant/accounts/import/qrcode`
- `POST /api/tenant/accounts/import/baileys-json`

风险点：

- `docs/协议层接口契约-在用.md` 的 0.0 写的是“导入不调协议层”，但上述三个 command path 仍在。
- 如果 armada 要严格执行“协议层纯瞬态，不托管 creds”，这三个入口要么停用，要么重定向为 wheel 本地落库流程。
- pairing/QR 如果仍要支持新号绑定，则 HTTP command + Kafka pairing 事件仍是完整闭环。

### 3.2 账号生命周期 / 诊断

实现类：`HttpAccountLifecycleClient`

账号上线/下线已经单独梳理过，这里只列其他仍要注意的接口。

| 协议接口 | wheel 方法 | 当前业务入口 | 状态 | 说明 |
|---|---|---|---|---|
| `POST /v1/accounts/{id}/logout` | `logout` | `TenantAccountService.logoutAccount` | 当前可用 | 不可逆退出设备；状态靠 Kafka 后续落库 |
| `GET /v1/accounts/{id}/status` | `status` | `TenantAccountService.refreshStatus` | 当前可用 | 手动刷新/诊断，不是热路径派单前置 |
| `POST /v1/accounts/{id}/probe` | `probe` | `TenantAccountService.probeAccount` | 当前可用 | 主动 WA ping，真发包；用于人工探活/关键操作前诊断 |
| `POST /v1/accounts/{id}/type/refresh` | `refreshType` | `TenantAccountService.refreshAccountType` | 当前可用 | 手动强制重识别账号类型 |
| `GET /v1/accounts/{id}/type` | `type` | 未发现主代码调用 | 残留/备用 | HTTP 实现存在；业务只看到 `refreshType` 调用 |
| `POST /v1/accounts/online/batch` | `batchOnline` | 未发现主代码调用 | 残留/备用 | `TenantProtocolService.batchOnline` 当前是循环单号 `onlineAccount`，没走这个接口 |

注意：

- `refreshType` 在精简契约里被剔除过，但代码仍有入口。
- `status/probe/refreshType` 都是 tenant 账号页的人工动作，不应和调度热路径混在一起。

### 3.3 代理绑定 / 重绑

实现类：`HttpProxyClient`

| 协议接口 | wheel 方法 | 当前业务入口 | 状态 | 说明 |
|---|---|---|---|---|
| `POST /v1/accounts/{id}/proxy/rebind` | `rebind` | `ProxyFailureRecoveryService` | 当前可用 | 收到 `PROXY_FAILED` 类状态后，重新分配代理并通知协议层重建 socket |
| `POST /v1/accounts/{id}/proxy/bind` | `bind` | 未发现主代码调用 | 残留/备用 | 当前上线直接在 `/online` body 里带 proxy |
| `GET /v1/accounts/{id}/proxy` | `get` | 未发现主代码调用 | 残留/备用 | HTTP 实现存在 |

风险点：

- 现在 wheel 的主模型是上线时 body 带 `ProxyBinding`。
- `rebind` 仍有实际业务入口，armada 至少需要决定是否保留“在线会话换代理”能力。

### 3.4 账号风控 / 可用性查询

实现类：`HttpRiskClient`

| 协议接口 | wheel 方法 | 当前业务入口 | 状态 | 说明 |
|---|---|---|---|---|
| `GET /v1/accounts/{id}/usability` | `usability` | `DispatchEngineService` 自建群选号 | 当前可用 | 用 `canCreateGroup` 等字段判断管理号能不能建群 |
| `GET /v1/accounts/{id}/restriction` | `restriction` | 未发现主代码调用 | 残留/备用 | 受限详情查询实现存在 |
| `GET /v1/accounts/{id}/message-cap` | `messageCap` | 未发现主代码调用 | 残留/备用 | 新会话配额查询实现存在 |

注意：

- 派单常规风控主要读 wheel 本地 `account` 表，由 Kafka 事件更新。
- `usability` 当前只在自建群场景看到实际调用。

## 4. 群组操作接口

实现类：`HttpGroupClient`

这是当前除账号上下线外最重要的协议交互面。拉群任务、进群任务、群营销、WS 群管理都会用到。

### 4.1 群基础 / 解析 / 建群

| 协议接口 | wheel 方法 | 当前业务入口 | 状态 | 说明 |
|---|---|---|---|---|
| `POST /v1/groups/create` | `createGroup` | `DispatchEngineService` 自建群任务 | 当前可用 | `group_source=CREATE_NEW` 时由管理号建群 |
| `GET /v1/groups/{jid}/invite-code?accountId=...` | `groupInviteCode` | `DispatchEngineService` 自建群回填邀请链接 | 当前可用 | 建群后取 invite code/link |
| `POST /v1/groups/preview` | `preview` | 未发现主代码调用 | 残留/文档不一致 | 契约写链接入池会调，但代码里没有业务调用 |
| `GET /v1/groups/{jid}/metadata?accountId=...` | `metadata` | 多处 | 当前热路径 | 查群详情、成员、人数、群健康、WS 群管理 |
| `GET /v1/accounts/{accountId}/groups` | `listParticipating` | 账号群同步、群营销账号树 | 当前可用 | 查询账号参与的群，和 `group_link.group_jid` 取交集 |

`metadata` 当前调用场景：

- `DefaultGroupLinkProbeClient`：群健康巡检。
- `TenantWsGroupService`：WS 群详情/成员查询。
- `JoinTaskWorker`：进群后校验群人数等。
- `DispatchEngineService`：确认 groupJid 真相、群人数/成员状态、异常分类。

`listParticipating` 当前调用场景：

- `AccountGroupSyncJob`：在线账号自动同步其所在群到 `group_link`。
- `TenantBusinessBasicsService`：群营销建任务账号-群树，只返回协议层参与群和本地群组列表的交集。

`preview` 的现状：

- `GroupClient.preview()` 和 `HttpGroupClient.preview()` 存在。
- `MockGroupClient.preview()` 存在。
- 但 `src/main/java` 未发现主业务调用。
- 代码里还有 `GroupLinkPreviewBackfillJob` 的注释引用，但未发现该类。
- 当前群链接识别走 `GroupLinkRecognizeService` + `GroupInvitePageFetcher` 抓 WhatsApp invite 页面，不走协议层。

### 4.2 进群 / 拉人 / 成员管理

| 协议接口 | wheel 方法 | 当前业务入口 | 状态 | 说明 |
|---|---|---|---|---|
| `POST /v1/groups/join` | `join` | `JoinTaskWorker`、`DispatchEngineService`、手动介入 | 当前热路径 | 账号通过 invite link/code 入群 |
| `POST /v1/groups/{jid}/participants/add` | `addParticipants` | `DispatchEngineService`、手动介入 | 当前核心热路径 | 拉人入群；wheel 出口会把裸手机号归一为 jid |
| `POST /v1/groups/{jid}/participants/remove` | `removeParticipants` | `DispatchEngineService`、`TenantWsGroupService` | 当前可用 | 收尾踢人 / WS 群管理 |
| `POST /v1/groups/{jid}/participants/promote` | `promoteParticipants` | `DispatchEngineService`、`TenantWsGroupService` | 当前可用 | 添加管理员 / WS 群管理 |
| `POST /v1/groups/{jid}/participants/demote` | `demoteParticipants` | `TenantWsGroupService` | 当前可用 | 取消管理员 |
| `POST /v1/groups/{jid}/leave` | `leave` | `DispatchEngineService`、`TenantWsGroupService` | 当前可用 | 账号退群 |

重要业务路径：

- 拉群主动作：`DispatchEngineService.executeRow` 调 `addParticipants`。
- 派单前确保拉手在群：`DispatchEngineService` 会按场景调 `join`。
- 新增管理员：先 `join`，再 `promoteParticipants`。
- 备用拉手/水军/补号：可能调 `join`、`addParticipants`、`promoteParticipants`。
- 任务收尾：可能调 `leave` 或 `removeParticipants`。

协议兼容注意：

- wheel 侧 `HttpGroupClient.normalizeParticipants` 会把裸手机号补成 `<phone>@s.whatsapp.net`。
- `participants/add/remove/promote/demote/approve` 都统一传 `timeoutMs=30000`。
- `JoinResult.joined=false` 表示入 pending 队列，后续需要审批接口处理。

### 4.3 群设置 / 群资料

| 协议接口 | wheel 方法 | 当前业务入口 | 状态 | 说明 |
|---|---|---|---|---|
| `POST /v1/groups/{jid}/subject` | `updateSubject` | `DispatchEngineService` | 当前可用 | 任务配置确认改群信息时触发 |
| `POST /v1/groups/{jid}/description` | `updateDescription` | `DispatchEngineService` | 当前可用 | 任务配置确认改群信息时触发 |
| `POST /v1/groups/{jid}/picture` | `updatePicture` | 未发现主代码调用 | 残留/备用 | 实现存在，业务未接入 |
| `POST /v1/groups/{jid}/settings/announcement` | `setAnnouncementOnly` | `DispatchEngineService`、`TenantWsGroupService` | 当前可用 | 全员禁言/解除禁言 |
| `POST /v1/groups/{jid}/announcement-text` | `updateAnnouncementText` | 未发现主代码调用 | 残留/备用 | 实现存在，facade 返回 void |
| `POST /v1/groups/{jid}/settings/locked` | `setLocked` | `DispatchEngineService`、`TenantWsGroupService` | 当前可用 | 锁群/解锁 |
| `POST /v1/groups/{jid}/settings/member-add-mode` | `setMemberAddMode` | `DispatchEngineService` 收尾 | 当前可用 | 关闭普通成员加人入口 |
| `POST /v1/groups/{jid}/settings/join-approval` | `setJoinApproval` | `DispatchEngineService` 审批模式 | 当前可用 | 入群审批开关 |

`TenantWsGroupService.updateWsGroupSettings` 只支持：

- `setLocked`
- `setAnnouncementOnly`

`DispatchEngineService` 任务模板/收尾会用到更多：

- 改群名/描述
- 开/关全员禁言
- 锁群
- 关闭成员加人
- 开/关入群审批

### 4.4 入群审批

| 协议接口 | wheel 方法 | 当前业务入口 | 状态 | 说明 |
|---|---|---|---|---|
| `GET /v1/groups/{jid}/pending?accountId=...` | `listPendingJoinRequests` | `DispatchEngineService` | 当前可用 | 查询 pending 入群申请 |
| `POST /v1/groups/{jid}/pending/approve` | `approveJoinRequests` | `DispatchEngineService` | 当前可用 | 批准入群申请 |

调用场景：

- 拉群任务 `audit_mode=true` 时，先打开 `join-approval`。
- 拉人后按 `audit_join_mode` 从 pending 列表批量或逐个批准。
- 完成后按配置决定是否关闭审批。

## 5. 群营销消息接口

实现类：`HttpMessageClient`

调用方：`GroupMarketingSendJob`

| 协议接口 | wheel 方法 | 当前业务入口 | 状态 | 说明 |
|---|---|---|---|---|
| `POST /v1/messages/text` | `sendText` | 群营销发送任务 | 当前可用 | 纯文本 |
| `POST /v1/messages/image` | `sendImage` | 群营销发送任务 | 当前可用 | 图片字节 base64 内联，可带 caption |
| `POST /v1/messages/link-card` | `sendLinkCard` | 群营销发送任务 | 当前可用 | 普通超链卡片，带缩略图/标题/描述 |
| `POST /v1/messages/button-card` | `sendButtonCard` | 群营销发送任务 | 当前可用 | 1-3 个按钮，支持 link/phone/copy/quick |

调用逻辑：

- 群营销任务 `SENDING` 状态下，`GroupMarketingSendJob` 扫描待发明细。
- 根据模板内容选择按钮卡片、链接卡片、图片消息或纯文本。
- 发送目标 `jid` 是 `group_marketing_detail.group_jid`。
- 发言账号是 `acc_<wsPhone>` 风格的协议账号 handle。

协议兼容注意：

- 图片/缩略图由 wheel 读本地 file blob 后 base64 内联，不要求公网 URL。
- `caption/footer/title/thumbnail/mimetype/value` 等可选字段，HTTP 实现通过 `@JsonInclude(NON_NULL)` 避免显式 null 触发协议层 zod 校验失败。

## 6. 联系人接口

实现类：`HttpContactClient`

| 协议接口 | wheel 方法 | 当前业务入口 | 状态 | 说明 |
|---|---|---|---|---|
| `POST /v1/contacts/{jid}/save` | `saveContact` | `DispatchEngineService` | 当前可用 | 拉人前把目标加为联系人；角色号之间互加 |

调用场景：

- 拉人前 `contact_before_pull`：拉手先保存待拉手机号为联系人。
- 角色号互加：管理号/拉手等角色之间按任务配置互加联系人。

协议兼容注意：

- wheel 入参可以是裸手机号，`HttpContactClient` 会补 `@s.whatsapp.net`。
- body 当前传 `{accountId, contact:{name:<phone>}}`。
- 联系人保存失败当前不一定阻断所有后续流程，具体看调用处容错。

## 7. 入站 Kafka 事件

Kafka 仅在 `spring.profiles.active=kafka` 时启用。

配置类：`KafkaConsumerConfig`

默认 topic：

- `protocol.account.events.v1`
- `protocol.group.events.v1`
- `protocol.pairing.events.v1`
- `protocol.owner.events.v1`

统一 envelope：

```json
{
  "eventId": "evt_xxx",
  "event": "account.state_changed",
  "version": "v1",
  "accountId": "acc_xxx",
  "occurredAt": "2026-06-02T10:00:00Z",
  "workerId": "worker-1",
  "evidence": {},
  "data": {}
}
```

消费共性：

- 按 `eventId` 幂等。
- 未注册事件 warn 后 ack，不阻塞 partition。
- handler 正常完成才 ack；异常不 ack，等 Kafka 重投。
- 消费线程绕过 tenant 拦截器，因为事件按协议账号 handle 定位账号。

### 7.1 Pairing topic

Consumer：`PairingEventConsumer`

代码当前注册事件：

| 事件 | handler | 状态 | 业务作用 |
|---|---|---|---|
| `pairing.code_generated` | `PairingCodeGeneratedHandler` | 当前可用 | 落配对码、过期时间 |
| `qr.code_generated` | `QrCodeGeneratedHandler` | 当前可用 | 落 QR base64、过期时间 |
| `pairing.completed` | `PairingCompletedHandler` | 当前可用 | 绑定完成，落账号/会话 |
| `pairing.failed` | `PairingFailedHandler` | 当前可用 | 绑定失败，写失败原因 |

如果保留 pairing/QR 新号绑定，这四个事件是必需的。

### 7.2 Account topic

Consumer：`AccountEventConsumer`

代码当前注册事件：

| 事件 | 状态 | 业务作用 |
|---|---|---|
| `account.state_changed` | 当前核心 | 上线真正结果、登录态迁移、状态源 |
| `account.heartbeat` | 当前核心 | 稳态心跳，刷新本地登录态/同步时间 |
| `account.banned` | 当前核心 | 账号封禁/设备移除，写封禁状态 |
| `account.logout` | 当前核心 | 远端/手动 logout，写离线/解绑语义 |
| `account.rate_limited` | 当前核心 | 写风控、cooldown |
| `account.restricted` | 当前核心 | reachoutTimelock 进入/解除，写禁言/风控 |
| `account.risk_triggered` | 当前核心 | 泛化风险触发，暂停外发 |
| `account.type_detected` | 当前核心 | 写 `account_type` |
| `account.online_changed` | 历史兼容 | 旧在线变化事件 |
| `account.need_reauth` | 历史兼容 | 需要重新授权 |
| `account.stale_detected` | 历史兼容 | 连接陈旧 |
| `account.new_chat_capping` | 历史兼容 | 新会话配额/触达限制 |
| `account.proxy_changed` | 历史兼容 | 代理变化 |
| `account.proxy_rotated` | 历史兼容 | 代理轮换 |

和精简契约差异：

- `docs/协议层接口契约-在用.md` account topic 只列了 8 个事件。
- 代码仍兼容 14 个 account 事件。
- armada 可以选择只发精简契约事件，但要确认旧 handler 是否需要保留一段兼容期。

### 7.3 Group topic

Consumer：`GroupEventConsumer`

代码当前注册事件：

| 事件 | 状态 | 业务作用 |
|---|---|---|
| `group.health_reported` | 当前核心 | 群健康权威回报，更新 `group_link.status/current_count/errorCode` |
| `group.participant_changed` | 当前可用 | 群成员/管理员变化，进群任务 promote 等可能依赖 |
| `group.metadata_updated` | 历史/扩展 | 群资料变化回填 |

和精简契约差异：

- 精简契约只保留 `group.health_reported`。
- 代码仍有 `participant_changed` 和 `metadata_updated`。

### 7.4 Owner topic

Consumer：`OwnerEventConsumer`

代码当前注册事件：

| 事件 | 状态 | 业务作用 |
|---|---|---|
| `account.owner_assigned` | 残留/需确认 | owner worker 分配 |
| `account.owner_changed` | 残留/需确认 | owner worker 变化 |
| `account.owner_unassigned` | 残留/需确认 | owner worker 解绑 |

和精简契约差异：

- `docs/协议层接口契约-在用.md` 明确说剔除 owner 路由事件，wheel 无需感知。
- 但当前代码仍配置并消费 `protocol.owner.events.v1`。
- 这里建议 armada 和 wheel 先做一个决策：要么协议层继续发布并保持兼容；要么 wheel 删除 owner consumer/handler/配置，避免无意义 topic。

## 8. 当前“实现存在但业务未调用”的接口

这些接口不建议 armada 第一批按热路径优先实现，除非产品确认还要保留。

| 接口 | 代码位置 | 未调用判断 | 建议 |
|---|---|---|---|
| `POST /v1/groups/preview` | `HttpGroupClient.preview` | 未发现 `groupClient.preview(...)` 主代码调用 | 确认是否废弃；如果保留，补真实调用方或更新文档 |
| `POST /v1/accounts/online/batch` | `HttpAccountLifecycleClient.batchOnline` | 业务批量上线当前循环单号 online | 可暂缓；或改 `TenantProtocolService.batchOnline` 真正使用 |
| `POST /v1/accounts/import/batch` | `HttpAccountImportClient.importBatch` | 未发现主代码调用 | 和“导入不调协议层”一起确认 |
| `GET /v1/accounts/{id}/type` | `HttpAccountLifecycleClient.type` | 未发现主代码调用 | `refreshType` 有调用，普通 `type` 可暂缓 |
| `POST /v1/accounts/{id}/proxy/bind` | `HttpProxyClient.bind` | 未发现主代码调用 | 上线 body 带 proxy 后可能不需要 |
| `GET /v1/accounts/{id}/proxy` | `HttpProxyClient.get` | 未发现主代码调用 | 可暂缓 |
| `GET /v1/accounts/{id}/restriction` | `HttpRiskClient.restriction` | 未发现主代码调用 | 可暂缓 |
| `GET /v1/accounts/{id}/message-cap` | `HttpRiskClient.messageCap` | 未发现主代码调用 | 可暂缓 |
| `POST /v1/groups/{jid}/picture` | `HttpGroupClient.updatePicture` | 未发现主代码调用 | 可暂缓 |
| `POST /v1/groups/{jid}/announcement-text` | `HttpGroupClient.updateAnnouncementText` | 未发现主代码调用 | 可暂缓 |

## 9. armada 对接优先级建议

### P0：必须先通

这些不通，拉群/营销/账号状态就会直接断。

- `POST /v1/accounts/{id}/online`
- `POST /v1/accounts/{id}/offline`
- `POST /v1/groups/join`
- `POST /v1/groups/{jid}/participants/add`
- `POST /v1/groups/{jid}/participants/remove`
- `POST /v1/groups/{jid}/participants/promote`
- `POST /v1/groups/{jid}/participants/demote`
- `POST /v1/groups/{jid}/leave`
- `GET /v1/groups/{jid}/metadata?accountId=...`
- `GET /v1/accounts/{accountId}/groups`
- `POST /v1/messages/text`
- `POST /v1/messages/image`
- `POST /v1/messages/link-card`
- `POST /v1/messages/button-card`
- `POST /v1/contacts/{jid}/save`
- Kafka：`account.state_changed`
- Kafka：`account.heartbeat`
- Kafka：`account.banned`
- Kafka：`account.logout`
- Kafka：`account.rate_limited`
- Kafka：`account.restricted`
- Kafka：`account.risk_triggered`
- Kafka：`account.type_detected`
- Kafka：`group.health_reported`

### P1：当前功能路径会用

这些和特定功能相关，但不是每条任务都会用。

- `POST /v1/accounts/{id}/logout`
- `GET /v1/accounts/{id}/status`
- `POST /v1/accounts/{id}/probe`
- `POST /v1/accounts/{id}/type/refresh`
- `POST /v1/accounts/{id}/proxy/rebind`
- `GET /v1/accounts/{id}/usability`
- `POST /v1/groups/create`
- `GET /v1/groups/{jid}/invite-code?accountId=...`
- `POST /v1/groups/{jid}/subject`
- `POST /v1/groups/{jid}/description`
- `POST /v1/groups/{jid}/settings/announcement`
- `POST /v1/groups/{jid}/settings/locked`
- `POST /v1/groups/{jid}/settings/member-add-mode`
- `POST /v1/groups/{jid}/settings/join-approval`
- `GET /v1/groups/{jid}/pending?accountId=...`
- `POST /v1/groups/{jid}/pending/approve`
- Kafka：`group.participant_changed`
- Kafka：pairing 四事件（如果保留 pairing/QR 绑定）

### P2：先确认再做

这些目前更像残留、备用或文档/代码冲突点。

- `POST /v1/groups/preview`
- `POST /v1/accounts/online/batch`
- `POST /v1/accounts/import/batch`
- `POST /v1/accounts/import/baileys-json`
- `POST /v1/auth/pairing-code`
- `POST /v1/auth/qrcode`
- `GET /v1/accounts/{id}/type`
- `POST /v1/accounts/{id}/proxy/bind`
- `GET /v1/accounts/{id}/proxy`
- `GET /v1/accounts/{id}/restriction`
- `GET /v1/accounts/{id}/message-cap`
- `POST /v1/groups/{jid}/picture`
- `POST /v1/groups/{jid}/announcement-text`
- `protocol.owner.events.v1`
- 历史 account 事件：`online_changed`、`need_reauth`、`stale_detected`、`new_chat_capping`、`proxy_changed`、`proxy_rotated`
- 历史 group 事件：`metadata_updated`

## 10. 需要拉齐的决策清单

1. **账号导入到底还调不调协议层**
   - 新模型：wheel 自托管 creds，导入只落库，上线才喂协议层。
   - 代码现状：pairing/QR/baileys-json import 仍调协议层。
   - 建议：确认是否保留 pairing/QR；`import/baileys-json` 是否改成本地落库。

2. **`/v1/groups/preview` 是否废弃**
   - 文档说在用。
   - 代码没看到业务调用。
   - 当前群健康更依赖 `metadata`，群链接识别走网页抓取。

3. **owner topic 是否还要 wheel 消费**
   - 精简契约已剔除。
   - 代码仍有 consumer。
   - 建议：删除 wheel 侧 owner consumer，或 armada 保留兼容发布。

4. **历史 account/group 事件保留多久**
   - 新契约可以只发核心事件。
   - wheel 代码现在仍支持更多事件。
   - 建议：给一个兼容窗口，之后清理 handler 和文档。

5. **批量上线接口要不要真正接入**
   - `HttpAccountLifecycleClient.batchOnline` 存在。
   - 当前批量上线按账号循环调用单号 `online`。
   - 如果 armada 的 OnlineGate/限速在 batch 里更强，应改 wheel 调用方；否则删接口减少误解。

6. **代理 bind/get 是否还需要**
   - 当前上线 body 带 proxy，失败恢复只用 `rebind`。
   - `bind/get` 无主调用。

7. **风险查询是否只保留 usability**
   - 当前 `usability` 有自建群选号调用。
   - `restriction/message-cap` 没看到主调用，风控状态主要靠 Kafka 落本地表。

## 11. 最小兼容建议

如果 armada 要先做一个可替换现役协议层的最小闭环，建议先按下面顺序验收：

1. 账号 `online/offline` + Kafka `account.state_changed/heartbeat`。
2. `metadata`、`join`、`participants/add`，跑通一条真实拉群任务。
3. `participants/promote/remove/demote/leave` + 群设置接口，跑通完整收尾。
4. `listParticipating` + 消息发送四接口，跑通群营销建任务树和发送。
5. `contact.save` + `usability` + `proxy.rebind`，补齐稳定性和风控辅助能力。
6. Pairing/QR/导入、owner、preview、batch online 另开决策，不要混在第一批热路径里。
