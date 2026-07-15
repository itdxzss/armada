# Web / Android 营销消息 Kafka 适配设计

> 状态：待用户书面评审
> 日期：2026-07-15
> 范围：`armada/armada-api`、`whatsapp-server-feature-android-zhuan`

## 1. 背景

Armada 已经为 Web/Baileys 与 Android Zhuan 建立协议防腐层：业务使用统一 port 和
`ProtocolAccountRef`，routing port 根据 `ProtocolBackend` 选择具体 backend，请求字段、响应解析和
错误映射留在对应实现内部。账号运行态和进群能力已经按此模式实现。

当前营销发送尚未完成同样的双协议适配：

- `MarketingRoundWorker` 直接构造 `ProtocolMarketingMessageCommandRequest` 并调用 outbox service。
- 营销 outbox 行固定写入 Web master topic，`protocol_backend` 固定为 `WEB`。
- Android Zhuan 已有文本、图片和链接卡片原生发送能力，但没有消费营销消息命令。
- Android 现有图片 HTTP 入口没有透传说明文字；按钮仅有固定单跳转按钮雏形；现有消息入口没有真正的
  “提醒所有人”能力。

本设计把营销消息补齐为与账号状态、进群相同的“统一业务端口 + 后端路由 + 独立适配器”结构，并保持
业务 outbox/Kafka 可靠投递模型。

## 2. 已确认需求

1. Web 和 Android 账号都可以执行 Armada 营销发送。
2. 业务层不关注 Web/Android 的请求字段、响应格式或传输实现差异。
3. Android 发送命令使用现有 `protocol.android.commands.v1` Kafka 通道，不由 Armada 同步调用 Zhuan HTTP。
4. 支持以下消息能力：
   - `TEXT`：纯文本消息。
   - `LINK`：包含普通链接的文本消息。
   - `IMAGE`：图片消息，支持同一条消息内的说明文字。
   - `LINK_CARD`：可点击的链接预览卡片。
   - `BUTTON_CARD`：按钮卡片；Android 只支持一个跳转链接按钮。
   - `mentionAll=true`：真正提醒群内所有人，不是只在正文前拼一个无通知语义的 `@all`。
5. Android 按钮规则由 Armada 校验：
   - 按钮数量必须恰好为 1。
   - 按钮类型必须为跳转链接。
   - 按钮显示文字不能为空。
   - 按钮值必须是合法的 `http://` 或 `https://` URL。
   - 不满足规则时只失败对应 Android 发送目标，不下发 Android 命令。
6. Web 保持现有 1–3 个 `link/copy/quick` 按钮能力，不因 Android 限制而降级。
7. 不修改前端按钮数量和表单逻辑。
8. 普通营销与建群后的营销发送共用同一消息协议能力和结果闭环。

## 3. 非目标

- 不修改营销任务创建、轮次间隔、账号占用或任务生命周期业务规则。
- 不修改 Web/Baileys 已有消息形态和发送结果语义。
- 不为 Android 支持复制按钮或快捷回复按钮。
- 不把 Android 限制扩散到前端或 Web 模板保存规则。
- 不在本设计内实现建群、加群、群设置或群快照；`mentionAll` 只复用 Android 进程内群成员读取能力。
- 不承诺 WhatsApp 副作用与本地 Kafka/Redis 状态之间的 exactly-once。

## 4. 方案比较

### 4.1 方案 A：Armada 直接调用 Zhuan 多个 HTTP 接口

Armada outbox dispatcher 根据消息类型调用 Zhuan 的文本、图片、链接和按钮 HTTP 接口。

优点是能复用现有 HTTP 路由。缺点是 Armada adapter 需要维护多套 HTTP DTO；远端已发送但 HTTP 响应丢失时
重试可能重复发送；还需要单独实现 HTTP 结果到统一事件的转换。不采用。

### 4.2 方案 B：新增一个 Zhuan 统一营销 HTTP 接口

Armada outbox dispatcher 统一调用 `/ws/v1/messages/marketing/{wsPhone}`。

相比方案 A，契约更集中，但仍存在同步 HTTP 的不确定结果与额外 delivery router；同时 Android 已经具备独立
Kafka consumer 和事件 publisher 基础设施。作为兼容接口没有当前业务价值，不采用。

### 4.3 方案 C：统一 port，按 backend 写入不同 Kafka topic

Armada 业务调用统一消息端口；Web 和 Android backend 分别校验、编码并把命令写入对应 outbox/topic。
Android Zhuan 扩展现有 Kafka consumer，执行本地原生发送并发布统一结果事件。

该方案保持 Armada 现有事务 outbox、协议路由与结果收敛模型，Web 无需迁移，Android 不经过网络回环调用自身
HTTP。采用此方案。

## 5. 总体架构

```text
MarketingRoundWorker / GroupCreationMarketingWorker
  -> MessageSendPort（统一异步消息端口）
     -> RoutingMessageSendPort
        -> WebMessageSendBackend
           -> protocol_command_outbox(protocol_backend=WEB)
           -> protocol.master.commands.v1
        -> AndroidMessageSendBackend
           -> protocol_command_outbox(protocol_backend=ANDROID)
           -> protocol.android.commands.v1

Android Zhuan
  -> CommandConsumer
  -> MessageCommandDecoder
  -> MessageCommandExecutor
  -> Zhuan 原生群消息发送
  -> MessageResultPublisher
  -> protocol.message.events.v1

Armada ProtocolMessageEventConsumer
  -> MarketingSendResultServiceImpl
  -> marketing_task_send_attempt / 任务与目标统计
```

业务层只认识统一命令和统一结果。routing port 只读取 `command.account().backend()`；各 backend 独立负责能力
校验、wire payload 与 topic。Android Zhuan 内部再把 Android 消息 payload 转为自身 protobuf 和发送函数调用。

## 6. Armada 统一消息端口

### 6.1 账号引用

继续复用现有 `ProtocolAccountRef`：

```java
public record ProtocolAccountRef(
        Long armadaAccountId,
        ProtocolBackend backend,
        String protocolAccountId,
        String wsPhone
) {}
```

Android 命令显式携带 `wsPhone`。禁止从 `acc_` 前缀或其它协议句柄格式推导手机号。

营销目标查询需要从 `account` 实时读取：

- `protocol_id`，经 `ProtocolBackend.fromProtocolId` 归一为 backend。
- `protocol_account_id`，作为 Kafka key、事件关联和 Web owner 路由标识。
- `ws_phone`，作为 Android Zhuan 进程内账号定位键。

### 6.2 Port 与 backend

新增按能力拆分的接口：

```java
public interface MessageSendPort {
    MessageSendEnqueueResult enqueue(List<MessageSendCommand> commands);
}

public interface MessageSendBackend {
    ProtocolBackend backend();
    MessageSendEnqueueResult enqueue(List<MessageSendCommand> commands);
}
```

`RoutingMessageSendPort` 按每条命令中的 `ProtocolAccountRef.backend()` 分组后调用对应 backend。同一 backend
重复注册时启动失败；缺少实现时返回统一 `UNSUPPORTED_BACKEND` 错误。调用方不得另外传一份 backend，避免
两处路由信息不一致。

### 6.3 统一命令

`MessageSendCommand` 包含：

- `tenantId`。
- `ProtocolAccountRef account`。
- `groupJid`。
- `messageType` 与统一消息内容。
- `mentionAll`。
- `commandId`。
- 普通营销的 `marketingTaskId/targetId/attemptId/roundNo`。
- 建群营销的 `groupCreationTaskId/groupCreationItemId`。
- `source`。

统一命令不携带 Web route URL、Android DTO 字段名或 Android 原始响应码。

### 6.4 批量结果

消息端口返回逐命令结果，而不是因一条 Android 配置错误回滚整个混合批次：

```java
public record MessageSendEnqueueItem(
        String commandId,
        boolean accepted,
        String reasonCode,
        String reasonMessage
) {}
```

- `accepted=true`：对应 outbox 已在当前业务事务中写入。
- `accepted=false`：没有写 outbox；调用方把对应 attempt 直接记为本地失败。
- 基础设施、数据库或整体契约异常仍抛异常并回滚事务。

这样混合 Web/Android 任务中，Android 按钮配置不兼容只影响 Android 目标；合法 Web 目标仍正常入队。

## 7. Armada backend 适配

### 7.1 WebMessageSendBackend

- 接受现有 `TEXT/LINK/IMAGE/LINK_CARD/BUTTON_CARD` 和 `mentionAll`。
- 保留当前 Web payload 字段和 `protocol.master.commands.v1` topic。
- outbox `protocol_backend=WEB`。
- 不改变 link card、1–3 个按钮或 Web 结果事件。

### 7.2 AndroidMessageSendBackend

- 接受 `TEXT/LINK/IMAGE/LINK_CARD/BUTTON_CARD` 和 `mentionAll`。
- `BUTTON_CARD` 必须恰好一个按钮，且类型为 `link`、显示文字非空、value 为 HTTP(S) URL。
- 失败时返回：
  - `reasonCode=INVALID_ANDROID_BUTTON_CONFIG`。
  - `reasonMessage` 明确说明数量不支持、类型不支持、按钮文字为空或跳转链接格式错误。
- 校验失败不写 outbox，不允许降级为纯文本、普通链接或丢弃多余按钮。
- outbox `protocol_backend=ANDROID`，topic 为 `protocol.android.commands.v1`。
- Android wire payload 显式包含 `wsPhone` 和统一结果回写字段，不复用 Web 专用的 owner worker 路由假设。

## 8. Android Kafka 消息契约

命令 envelope 保持现有字段：

```json
{
  "commandId": "cmd_xxx",
  "batchId": "batch_xxx",
  "commandType": "message.send.requested",
  "aggregateType": "MARKETING_SEND_ATTEMPT",
  "aggregateId": 9001,
  "protocolAccountId": "acc_919000000001",
  "payload": {}
}
```

Android payload 使用专用 decoder，字段包括：

```json
{
  "tenantId": 7,
  "accountId": 100,
  "protocolAccountId": "acc_919000000001",
  "wsPhone": "919000000001",
  "groupJid": "120363000000000000@g.us",
  "messageType": "IMAGE",
  "text": "图片说明文字",
  "image": {
    "base64": "...",
    "mimetype": "image/png"
  },
  "mentionAll": true,
  "marketingTaskId": 10,
  "targetId": 20,
  "attemptId": 30,
  "roundNo": 1,
  "source": "marketing_task"
}
```

`ParseCommand` 先解析通用 envelope，再按 `commandType` 交给生命周期 decoder 或消息 decoder。消息命令不得经过
六段凭据和代理字段校验。无法构造结果关联字段的非法信封按永久命令错误提交；可以确定 attempt/item 的非法
消息必须发布 `message.send_result_reported` 失败事件后再提交，避免 Armada attempt 永久停在 SUBMITTED。

## 9. Android 消息执行映射

| 统一消息类型 | Android 原生执行 |
| --- | --- |
| `TEXT` | 群文本消息，正文为 `text` |
| `LINK` | 群文本消息，正文保留普通 URL |
| `IMAGE` | 群图片消息，`text` 作为同一条图片消息的 caption |
| `LINK_CARD` | Android 链接模板 1，映射 title、description、url、text 和 thumbnail |
| `BUTTON_CARD` | Android 原生 interactive message；只构造一个 `cta_url` 按钮 |

按钮的 `displayText` 和 URL 必须来自 Armada 已校验的唯一按钮，禁止继续使用 Zhuan 当前硬编码的
`click here` 文案。

所有发送方法以 Zhuan 收到 WhatsApp server ACK 为同步成功口径，成功结果返回原生 `messageId`。对方送达和
已读 receipt 不参与营销首次发送成功结算。

## 10. 真正提醒所有人

当 `mentionAll=true` 时，Android 执行器必须：

1. 确认目标为 `@g.us` 群 JID；非群目标按非法消息失败。
2. 使用当前 Android 账号的进程内群成员查询能力读取目标群成员。
3. 过滤空 JID、当前发送账号和重复成员。
4. 正文或图片 caption 前增加一个可见的 `@all`；已有前缀时不重复添加。
5. 把实际成员 JID 写入 WhatsApp message context 的 mention 字段，并沿用群消息加密发送所需的 participant
   设备列表，使成员收到真实提及通知。
6. 文本、普通链接、图片、链接卡片和按钮卡片都执行同一套 mention enrichment，不在各发送分支复制群成员
   解析逻辑。

群成员查询失败时本次发送失败并回传 `MENTION_ALL_RESOLUTION_FAILED`，不得悄悄退化为没有通知语义的
`@all` 文本。

## 11. 结果事件

Android Zhuan 新增 message event topic 配置和 publisher，输出与 Web 相同的：

```text
event = message.send_result_reported
topic = protocol.message.events.v1
key = protocolAccountId
```

事件 `data` 至少包含：

- 普通营销：`tenantId/marketingTaskId/targetId/attemptId/roundNo`。
- 建群营销：`tenantId/groupCreationTaskId/groupCreationItemId`。
- 公共字段：`accountId/protocolAccountId/groupJid/commandId/source`。
- 结果字段：`success/messageId/reasonCode/reasonMessage/timestamp`。
- 群状态字段可按当前能力返回；无法确认时使用
  `groupStatus=UNCONFIRMED`、`groupStatusReason=STATUS_RESOLUTION_UNAVAILABLE` 和检查时间，不伪造正常状态。

Armada 继续使用现有 `ProtocolMessageEventConsumer` 和 `MarketingSendResultServiceImpl`，不增加 Android 专用结果
Controller 或业务 Service。

## 12. 幂等与崩溃窗口

Kafka offset 只能保证命令至少一次交付，WhatsApp 发送和 Kafka 结果发布不在同一事务内。Android 使用 Redis
保存 command result 状态：

```text
NEW -> PROCESSING -> RESULT_STORED -> PUBLISHED
```

- `PUBLISHED`：重投直接成功，不再次发送。
- `RESULT_STORED`：只重发结果事件，不再次发送 WhatsApp 消息。
- 首次抢占成功：执行一次 WhatsApp 发送，先保存完整结果，再发布事件。
- 在调用 WhatsApp 发送前明确得到的校验、离线或群成员解析错误，直接保存失败结果，不进入不确定重试。
- 进程在 `PROCESSING` 阶段崩溃后，恢复端无法可靠区分“尚未调用发送”与“已收到 ACK 但未保存结果”。因此过期
  `PROCESSING` 一律不自动再次发送，发布 `SEND_RESULT_UNKNOWN` 失败结果，避免营销消息重复触达。

该策略选择“未知时不重复发送”的 at-most-once 副作用偏好。它可能把极小崩溃窗口内实际已发送的消息记为
未知失败，但不会宣称 exactly-once。Redis key 只保存命令链路和结果，不保存图片正文或完整消息内容。

## 13. 错误映射

| 场景 | reasonCode | 是否调用 WhatsApp 发送 |
| --- | --- | --- |
| Android 按钮数量、类型、文案或 URL 不合法 | `INVALID_ANDROID_BUTTON_CONFIG` | 否 |
| Android 命令字段不合法但关联字段完整 | `INVALID_MESSAGE_PAYLOAD` | 否 |
| `mentionAll` 群成员解析失败 | `MENTION_ALL_RESOLUTION_FAILED` | 否 |
| Android 账号不在线 | `ACCOUNT_OFFLINE` | 否 |
| WhatsApp 发送或 server ACK 失败 | `SEND_FAILED` | 是 |
| 崩溃恢复后发送结果不确定 | `SEND_RESULT_UNKNOWN` | 可能已经执行 |
| Kafka/Redis 临时故障 | 不发布业务失败，保持命令重试 | 依幂等状态决定 |

错误日志只记录 commandId、Armada accountId、attempt/item ID、消息类型和低基数错误分类；不记录图片 Base64、
营销正文、完整手机号或完整群 JID。

## 14. 数据、租户与配置影响

- 不新增业务表或 Flyway 迁移。
- `marketing_task_target` 不复制保存 protocol backend；发送时通过 account 关联读取当前事实。
- outbox 继续使用现有 `protocol_backend` 列。
- 所有普通营销 attempt、目标和任务更新继续受现有 tenant 条件与 `TenantContext` 约束。
- Android 增加 message event topic 配置，默认 `protocol.message.events.v1`。
- Android Redis 增加带 TTL 的消息 command result key；key 不包含正文、媒体或凭据。

## 15. 代码边界

Armada 侧预期新增或调整：

```text
platform/protocol/port/MessageSendPort.java
platform/protocol/routing/MessageSendBackend.java
platform/protocol/routing/RoutingMessageSendPort.java
platform/protocol/backend/web/WebMessageSendBackend.java
platform/protocol/backend/android/AndroidMessageSendBackend.java
platform/protocol/model/command/MessageSendCommand.java
platform/protocol/model/result/MessageSendEnqueueResult.java
marketing/scheduler/MarketingRoundWorker.java
marketing/service/impl/GroupCreationMarketingWorker.java（只替换统一消息端口调用）
marketing/model/entity/MarketingTaskTarget.java
resources/mapper/marketing/MarketingTaskMapper.xml
platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java
```

Android Zhuan 侧预期新增或调整：

```text
internal/armada/command.go（拆出通用 envelope 路由）
internal/armada/message_command.go
internal/armada/message_executor.go
internal/armada/message_result.go
internal/armada/message_once.go
internal/armada/start.go
api/dto/dto.go（复用内部 DTO 时增加 caption/mention 字段；不要求新增外部路由）
api/service/message.go
internal/service/app/group.go
internal/service/node/node_processor.go
internal/service/node/nodes/message.go
```

若群组 agent 同时修改 Android 群成员或通知代码，消息实现只调用其稳定内部查询能力；重叠文件在编码前重新
检查 worktree 状态，不覆盖其它会话改动。

## 16. 测试设计

### 16.1 Armada

- routing port：Web/Android 分组正确，重复 backend 注册失败，缺失 backend 返回统一错误。
- Web backend：现有 payload、topic 和按钮能力不回退。
- Android backend：五种消息类型入队到 Android topic。
- Android 按钮：恰好一个合法 link 接受；0 个、2 个、copy、quick、空文案、非 HTTP(S) URL 拒绝且不写 outbox。
- 混合任务：合法 Web 目标入队，非法 Android 目标本地失败，两者不互相回滚。
- `ProtocolAccountRef`：Android payload 使用 `wsPhone`，不从 protocolAccountId 派生。
- 普通营销与建群营销结果字段完整。
- 现有 `ProtocolMessageEventConsumer` 可消费 Android 统一结果。

### 16.2 Android Zhuan

- 通用命令 parser 不让消息命令经过 lifecycle credential/proxy 校验。
- 文本、普通链接、图片 caption、链接卡片、单 CTA URL 按钮分别生成正确原生消息。
- 按钮文案不再硬编码为 `click here`。
- `mentionAll` 为所有有效群成员生成真实 mention context；已有 `@all` 不重复前缀。
- 群成员读取失败时不发送降级消息，发布 `MENTION_ALL_RESOLUTION_FAILED`。
- server ACK 成功发布统一成功结果；离线、ACK 失败和非法 payload 发布稳定失败码。
- command 结果 `RESULT_STORED` 重投只重发事件；`PUBLISHED` 重投不发送也不重发事件。
- stale `PROCESSING` 发布 `SEND_RESULT_UNKNOWN`，不再次发送。
- 日志和 Redis 数据不包含正文、图片 Base64、完整手机号或凭据。

### 16.3 回归

- Armada 相关 Java 单测与营销 DbTest。
- Android `go test ./internal/armada/... ./api/service/... ./internal/service/app/... ./internal/service/node/...`。
- Web 协议 `message.send.requested` 相关测试保持通过。
- 端到端 fixture 覆盖一个 Web 账号和一个 Android 账号执行同一营销任务并分别回写结果。

## 17. 部署与回滚

部署顺序：

1. 先部署能识别 `message.send.requested` 且默认不开启消息消费的 Android Zhuan。
2. 部署 Armada 消息 routing port 和 Android backend。
3. 配置并确认 Android command topic、message event topic、consumer group 和 Redis。
4. 开启 Android 消息消费，以单账号纯文本灰度，再依次验证图片、链接卡片、按钮和提醒所有人。

回滚时先停止 Armada 向 Android backend 生成新命令，再关闭 Android 消息消费；Web topic 和 Web backend 不受影响。
已存在的 Android outbox/命令保留用于审计，不删除、不改写业务数据。

## 18. 验收标准

1. 营销 Worker 和建群营销发送交接代码不出现 Web/Android 请求分支。
2. 所有路由只依据 `ProtocolAccountRef.backend()`，Android 账号定位只使用显式 `wsPhone`。
3. Web 账号的五种消息、1–3 个按钮和提醒所有人行为不回退。
4. Android 账号可发送纯文本、普通链接、带说明文字图片、链接卡片和单跳转按钮卡片。
5. Android 按钮非法时 Armada 本地失败且不写 Android outbox。
6. Android `mentionAll` 对群成员产生真实提醒；成员解析失败不降级发送。
7. Android 成功、失败和不确定结果都能通过统一事件幂等收敛到正确 attempt/item。
8. Kafka 重投不会在已保存结果或已发布结果场景重复发送 WhatsApp 消息。
9. 不新增 Android 专用业务 Controller，不修改前端，不把协议差异泄漏到营销业务域。
