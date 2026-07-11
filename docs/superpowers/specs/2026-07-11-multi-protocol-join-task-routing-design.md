# Web / Android 双协议进群任务路由与适配设计

> 状态：已完成口头方案确认，待书面复核
> 日期：2026-07-11
> 目标仓库：`armada/1.0.1-snapshot`
> 协议后端：Web/Baileys `armada-protocol`、Android `whatsapp-server-feature-android-zhuan`

## 1. 背景

Armada 需要长期同时支持 Web/Baileys 和 Android Zhuan 两种 WhatsApp 协议后端。账号导入时已经通过 `account.protocol_id` 区分 `WEB` 与 `ANDROID`，账号上下线也已经为 Android 使用独立 Kafka topic，但同步 HTTP 能力仍共用单个 `armada.protocol.base-url`。

进群任务已经具备建任务、账号与链接分配、启动、逐行执行、结果计数和协议调用能力。当前 Worker 固定使用 Web 协议契约：

1. 调用 `GET /v1/accounts/{protocolAccountId}/status` 判断账号是否在线。
2. 调用 `POST /v1/groups/join` 执行进群。
3. 按 `{groupJid, joined}` 把明细收敛为成功或待管理员审核。

Android Zhuan 已有原生账号状态、邀请码进群和群成员查询接口，但请求字段、账号定位、响应 envelope、错误状态和进群结果语义都与 Web 协议不同。两套协议后端将长期并存，因此不能通过替换全局 base URL 完成切换。

## 2. 设计约束

本设计遵守以下硬约束：

1. Web/Baileys 与 Android Zhuan 都视为既有外部系统，不要求为了 Armada 统一接口而大改协议代码。
2. 首期进群接入不要求协议仓库新增 Armada facade、统一路由或统一响应。
3. 请求参数转换、原生响应解析、原始错误归一和结果确认全部在 Armada 防腐层完成。
4. 业务 Worker 只依赖统一端口和统一领域结果，不解析 HTTP、Kafka、原生 JSON、中文消息或协议原始码。
5. 业务代码不散落 `if (ANDROID)`；后端选择只出现在协议路由层。
6. 进群首期继续使用同步 HTTP。账号上下线继续使用现有 outbox + Kafka，不把传输方式强行统一。
7. Android 原生进群返回成功后，Armada 允许再调用一次群成员查询，确认账号已经真实入群还是仅进入管理员审核队列。
8. 本设计只实现进群任务所需的双协议路由、状态查询和进群能力。营销发送的双协议投递另立设计，不在本期顺带实现。

## 3. 目标与非目标

### 3.1 目标

1. Web 和 Android 账号可以出现在同一套 Armada 进群任务中，并按账号自身 `protocol_id` 调用正确协议。
2. 保持 Web 现有进群行为和契约不回退。
3. Armada 能调用 Zhuan 现有原生 HTTP 接口完成在线检查、邀请码进群和进群结果确认。
4. 两套协议的原始成功、待审核、账号离线、超时、限流和未知错误统一转换成 Armada 领域语义。
5. 新增一个协议后端时，只新增对应能力 adapter，不修改进群 Worker 的业务分支。
6. 为后续建群、群成员、联系人和营销适配建立可复用但不过度抽象的路由模式。

### 3.2 非目标

- 不修改 Web/Baileys 协议接口。
- 不要求 Android Zhuan 修改现有 `/ws/v1` 接口或 Kafka consumer。
- 不在本期接入 Android 建群、加人、联系人保存或营销消息。
- 不在本期重构所有现存协议端口。
- 不在本期解决 Android 多实例 owner gateway；首期由配置的 Android HTTP endpoint 承载已上线 Android 账号。
- 不承诺从 Android 模糊的原始 `403` 中猜出协议未提供的精细业务原因。
- 不在本期完成进群任务的跨进程恢复、暂停、停止或完整持久化重试引擎。

## 4. 方案比较

### 4.1 方案 A：Worker 内直接按后端分支

`JoinTaskWorker` 根据 `account.protocol_id` 分别拼 Web 与 Android URL、请求体和响应解析。

优点是改动最少。缺点是业务编排与协议细节耦合，后续建群、营销等能力会重复相同分支，错误映射也会散落到多个 Worker。长期不可维护，不采用。

### 4.2 方案 B：要求协议端统一 Armada HTTP 契约

让 Web 与 Android 都实现相同路径、请求和响应，Armada 只保留一个 HTTP adapter。

调用侧最简单，但需要持续修改两套协议服务，协议原生行为会被 Armada 契约反向侵入，不符合“协议零/小改、Armada 适配”的约束，不采用。

### 4.3 方案 C：能力端口 + 后端路由 + 原生 adapter

业务侧保留统一 `GroupJoinPort` 和 `AccountRuntimeStatusPort`。路由实现根据 `ProtocolBackend` 选择 Web 或 Android 原生 adapter，每个 adapter 独立负责请求和响应翻译。

该方案把变化限制在 Armada 防腐层，业务代码稳定，协议代码保持原样；同时避免一个包含全部协议能力的巨型 client。采用本方案。

## 5. 总体架构

```text
JoinTaskWorker
  -> AccountRuntimeStatusPort
     -> RoutingAccountRuntimeStatusPort
        -> WebAccountRuntimeStatusAdapter
        -> AndroidAccountRuntimeStatusAdapter

  -> GroupJoinPort
     -> RoutingGroupJoinPort
        -> WebNativeGroupJoinAdapter
        -> AndroidNativeGroupJoinAdapter
             -> AndroidNativeClient
             -> AndroidResponseDecoder
             -> AndroidGroupJoinErrorMapper
             -> AndroidGroupMembershipVerifier
```

路由层只做一件事：根据 `ProtocolBackend` 选择 adapter。adapter 只做一件事：把 Armada 统一命令翻译成某个协议的原生调用，再把原生结果翻译回统一结果。

## 6. 统一模型与端口

### 6.1 协议账号引用

```java
public record ProtocolAccountRef(
        Long armadaAccountId,
        ProtocolBackend backend,
        String protocolAccountId,
        String wsPhone
) {
}
```

- Web adapter 使用 `protocolAccountId`，例如 `acc_919000000001`。
- Android adapter 使用 `wsPhone`，例如 `919000000001`。
- Android adapter 不通过截取 `acc_` 前缀恢复手机号，避免协议句柄格式变化造成隐式耦合。
- 日志中的 `wsPhone` 必须脱敏。

`ProtocolAccountRef` 由 Armada 账号实体在进入协议边界前一次性转换，Worker 后续不再分别读取协议字段。

### 6.2 进群命令与结果

```java
public record GroupJoinCommand(
        ProtocolAccountRef account,
        String inviteLinkOrCode,
        String operationId
) {
}

public enum GroupJoinOutcome {
    JOINED,
    ALREADY_JOINED,
    PENDING_APPROVAL
}

public record GroupJoinResult(
        String groupJid,
        GroupJoinOutcome outcome
) {
}
```

`operationId` 首期使用 `join-task-result:{resultId}`，只用于日志关联，不要求协议端支持幂等键。

### 6.3 业务端口

```java
public interface GroupJoinPort {
    GroupJoinResult join(GroupJoinCommand command);
}

public interface AccountRuntimeStatusPort {
    ProtocolAccountRuntimeStatus status(ProtocolAccountRef account);
}
```

进群 Worker 不再直接依赖同时包含 online、batch online、status 和 probe 的 `AccountLifecyclePort`。运行态查询使用窄端口，避免异步生命周期命令与同步热路径查询混在一个接口中。

### 6.4 后端 SPI

```java
public interface GroupJoinBackend {
    ProtocolBackend backend();
    GroupJoinResult join(GroupJoinCommand command);
}

public interface AccountRuntimeStatusBackend {
    ProtocolBackend backend();
    ProtocolAccountRuntimeStatus status(ProtocolAccountRef account);
}
```

`RoutingGroupJoinPort` 和 `RoutingAccountRuntimeStatusPort` 在构造时把实现收集为 `EnumMap<ProtocolBackend, ...>`，启动时拒绝重复 backend，调用时拒绝未注册 backend。Spring 对外只暴露一个对应业务端口 Bean。

不建立包含 online、join、create、send、contact 等所有方法的巨型 `ProtocolBackendClient`。不同能力继续使用独立端口和独立 backend SPI。

## 7. HTTP 客户端与配置

当前单一配置改为后端配置映射：

```yaml
armada:
  protocol:
    backends:
      WEB:
        base-url: http://protocol-web-master:3000
        api-key: ${WEB_PROTOCOL_API_KEY:}
        connect-timeout-ms: 3000
        read-timeout-ms: 60000
      ANDROID:
        base-url: http://android-zhuan:8000
        api-key: ${ANDROID_PROTOCOL_API_KEY:}
        connect-timeout-ms: 3000
        read-timeout-ms: 60000
```

Armada 新增 `ProtocolHttpExecutorRegistry`，按 `ProtocolBackend` 保存独立 executor。Web 与 Android adapter 必须显式获取自己的 executor，禁止共享一个可被全局切换的 base URL。

为降低切换风险，旧 `armada.protocol.base-url` 可在过渡期只作为 Web fallback；当所有部署配置完成后再删除兼容入口。Android base URL 必须显式配置，不允许静默回退到 Web。

## 8. Web 原生适配

### 8.1 在线状态

继续调用：

```text
GET /v1/accounts/{protocolAccountId}/status
```

继续使用现有 Web 状态响应映射。该逻辑从 `HttpAccountLifecycleAdapter.status` 提取到 `WebAccountRuntimeStatusAdapter`，其他生命周期方法暂不重构。

### 8.2 进群

继续调用：

```text
POST /v1/groups/join
```

请求：

```json
{
  "accountId": "acc_919000000001",
  "inviteLink": "https://chat.whatsapp.com/ABC123"
}
```

或纯 code：

```json
{
  "accountId": "acc_919000000001",
  "inviteCode": "ABC123"
}
```

响应 `{groupJid, joined}` 映射规则：

- `joined=true` -> `JOINED`
- `joined=false` -> `PENDING_APPROVAL`

现有 Web `ProtocolException` 错误映射保持不变，只补齐 backend、operation 和 operationId 诊断上下文。

## 9. Android 原生适配

### 9.1 邀请码规范化

Android adapter 在 Armada 内提取邀请码：

- 接受纯 code。
- 接受严格 `https://chat.whatsapp.com/{code}` 链接。
- 去除首尾空白。
- 空 code 或非法 host 在发请求前直接映射 `INVALID_GROUP_LINK`。

### 9.2 在线状态

调用 Zhuan 现有接口：

```text
GET /ws/v1/auth/status/{wsPhone}
```

映射规则：

- envelope `Code=0` -> `ONLINE`
- 明确“账号不存在或已下线” -> `OFFLINE`
- 结构不合法 -> `ANDROID_RESPONSE_UNRECOGNIZED`
- 网络异常/超时 -> `NETWORK/TIMEOUT`，不得直接把 Armada 本地账号改成 OFFLINE

### 9.3 进群请求

调用 Zhuan 现有接口：

```text
POST /ws/v1/groups/invite/{wsPhone}
```

请求按 Zhuan 原生字段发送：

```json
{
  "Code": "ABC123"
}
```

Armada 使用 Android 专用 DTO，字段通过 `@JsonProperty("Code")` 固定，禁止复用 Web 请求 record。

### 9.4 Android 响应 envelope

Android 原生响应使用大写字段且业务失败通常仍为 HTTP 200。Armada 定义专用响应：

```java
public record AndroidResponseEnvelope(
        @JsonProperty("Code") Integer code,
        @JsonProperty("Data") JsonNode data,
        @JsonProperty("Msg") JsonNode message,
        @JsonProperty("error") String validationError
) {
}
```

`AndroidResponseDecoder` 负责结构解析：

1. `error` 非空：Gin 参数绑定失败，映射 `BAD_REQUEST`。
2. `Code` 缺失：映射 `ANDROID_RESPONSE_UNRECOGNIZED`。
3. `Code=0`：交给操作级 mapper 解析 `Data`。
4. `Code!=0`：提取安全 message 和其中可识别的 `Code: <raw>` 原始 IQ code。

所有中文和正则解析只允许存在于 Android adapter 包，不能进入 Worker、Service、Mapper 或前端 VO。

### 9.5 Android 成功结果解析

Zhuan 当前成功 `Data` 为类似文本：

```text
通过邀请码进群成功, 群聊ID: 120363xxxxxxxxx
```

`AndroidNativeGroupJoinAdapter` 使用受测试保护的正则提取群 ID，并统一补全 `@g.us`。解析不到群 ID 时返回 `ANDROID_RESPONSE_UNRECOGNIZED`，不得返回空 JID 成功。

### 9.6 真实入群确认

Android 原生进群成功响应没有明确区分真实入群与待管理员审核。成功解析到 `groupJid` 后，Armada 再调用：

```text
POST /ws/v1/groups/members/{wsPhone}
```

请求：

```json
{
  "group_id": "120363xxxxxxxxx@g.us"
}
```

`AndroidGroupMembershipVerifier` 归一 participant 的 `jid`、`phone_number` 等字段，并与当前账号手机号比较：

- 查询成功且存在当前账号 -> `JOINED`
- 查询成功且不存在当前账号 -> `PENDING_APPROVAL`
- 原生结果明确表示已在群 -> `ALREADY_JOINED`
- 查询超时、网络错误或结构无法解析 -> `JOIN_RESULT_UNCONFIRMED`

`JOIN_RESULT_UNCONFIRMED` 不得被记为成功。本期先按统一失败原因落明细；是否自动重试由后续可靠性切片结合现有 `retryEnabled/retryLimit` 统一实现，避免本次路由接入顺带重写任务引擎。

## 10. 统一错误模型

Armada 业务侧继续使用 `ProtocolException`，补充以下结构化上下文：

```java
ProtocolBackend backend;
String operation;
String operationId;
ProtocolErrorCode errorCode;
String rawProtocolCode;
boolean retryable;
Integer retryAfterMs;
```

统一错误码至少包括：

| 统一码 | 是否可重试 | 含义 |
| --- | --- | --- |
| `ACCOUNT_NOT_FOUND` | 否 | Armada 或协议账号不存在 |
| `ACCOUNT_NOT_ONLINE` | 否 | 协议运行态明确不在线 |
| `BAD_REQUEST` | 否 | 原生接口参数或请求形状不合法 |
| `INVALID_GROUP_LINK` | 否 | 链接或邀请码非法 |
| `GROUP_JOIN_REJECTED` | 否 | 协议拒绝进群但无法进一步细分 |
| `JOIN_PENDING_APPROVAL` | 否 | 已提交审核但未真实入群 |
| `JOIN_RESULT_UNCONFIRMED` | 后续可重试 | 已提交进群但确认调用失败 |
| `ACCOUNT_BUSY` | 是 | 同账号正在执行互斥操作 |
| `WORKER_BUSY` | 是 | 协议 worker 繁忙 |
| `ACCOUNT_REACHOUT_RESTRICTED` | 否 | 协议明确报告账号触达受限 |
| `TIMEOUT` | 是 | HTTP 或协议请求超时 |
| `NETWORK` | 是 | 网络不可达 |
| `ANDROID_RESPONSE_UNRECOGNIZED` | 否 | Android 响应不符合已知结构 |
| `UNSUPPORTED_BACKEND` | 否 | 未注册后端 adapter |
| `UNKNOWN` | 否 | 无法安全细分的错误 |

### 10.1 Android 错误映射原则

1. 优先使用 envelope `Code`、原始 IQ code 和稳定的结构字段。
2. 只有 Zhuan 未提供结构字段时，才在 Android adapter 内使用精确文本模式兜底。
3. 精确模式未命中时返回通用错误，不根据模糊 `403` 猜测“群满、链接失效、账号被封”等具体原因。
4. 原始 message 只保存安全截断摘要，普通日志不打印完整手机号、邀请 code 或可能含敏感数据的整个响应。

初始映射：

| Android 原生信息 | Armada 统一码 |
| --- | --- |
| `Code=0` 且成功文本含群 ID | 进入成员确认步骤 |
| `error` 参数绑定失败 | `BAD_REQUEST` |
| “账号不存在或已下线”/“账号...不在线” | `ACCOUNT_NOT_ONLINE` |
| “邀请码为空” | `INVALID_GROUP_LINK` |
| 原始 `429` / `rate-overlimit` | `ACCOUNT_BUSY` |
| timeout 文本或 HTTP read timeout | `TIMEOUT` |
| 原始 `401/403` 且无稳定细分 | `GROUP_JOIN_REJECTED` |
| 其它未知非零 code | `UNKNOWN` |

## 11. Worker 数据流

```text
读取 join_task_result
  -> 读取 Account
  -> 组装 ProtocolAccountRef
  -> AccountRuntimeStatusPort.status(ref)
     -> 非 ONLINE：明细 ACCOUNT_NOT_ONLINE
  -> GroupJoinPort.join(command)
     -> Web：直接得到 JOINED / PENDING_APPROVAL
     -> Android：原生 join -> 解析 JID -> 查询群成员 -> 统一 outcome
  -> JOINED / ALREADY_JOINED：明细 SUCCESS + groupJid
  -> PENDING_APPROVAL：明细 FAILED + JOIN_PENDING_APPROVAL
  -> ProtocolException：明细 FAILED + 统一错误码
  -> refreshCounters
```

本期保持当前“待审核记失败”的页面与统计语义，不新增明细状态。后续如果产品需要单独统计待审核，再独立评审状态机与数据库迁移。

## 12. Kafka 与后续营销扩展原则

本期不修改进群为 Kafka，也不要求 Android 消费 Web 形状的营销 Kafka 命令。

后续营销接入时遵循同一边界：

```text
业务写统一 outbox 命令
  -> Armada delivery router 按 backend + command family 选择投递器
     -> WebMarketingKafkaDelivery
     -> AndroidMarketingHttpDelivery（调用 Zhuan 现有消息 HTTP API）
```

`ProtocolMarketingMessageCommandRequest` 届时必须补 `protocolBackend`，outbox 行继续持久化 `protocol_backend`。Kafka 或 HTTP 是 Armada 投递策略，不由营销 Worker 分支决定。

Android HTTP 营销存在“远端发送成功但本地确认落库失败可能重复发送”的幂等限制，必须在单独营销设计中明确，不在本期隐式承诺 exactly-once。

## 13. 代码组织

建议 Armada 新增或调整：

```text
platform/protocol/
├── model/
│   ├── ProtocolAccountRef.java
│   ├── ProtocolAccountRuntimeStatus.java
│   ├── GroupJoinCommand.java
│   ├── GroupJoinOutcome.java
│   └── GroupJoinResult.java
├── port/
│   ├── AccountRuntimeStatusPort.java
│   └── GroupJoinPort.java
├── routing/
│   ├── AccountRuntimeStatusBackend.java
│   ├── GroupJoinBackend.java
│   ├── RoutingAccountRuntimeStatusPort.java
│   └── RoutingGroupJoinPort.java
├── backend/web/
│   ├── WebAccountRuntimeStatusAdapter.java
│   └── WebNativeGroupJoinAdapter.java
├── backend/android/
│   ├── AndroidNativeClient.java
│   ├── AndroidResponseEnvelope.java
│   ├── AndroidResponseDecoder.java
│   ├── AndroidAccountRuntimeStatusAdapter.java
│   ├── AndroidNativeGroupJoinAdapter.java
│   ├── AndroidGroupMembershipVerifier.java
│   └── AndroidGroupJoinErrorMapper.java
└── http/
    ├── ProtocolBackendHttpProperties.java
    └── ProtocolHttpExecutorRegistry.java
```

具体类名可以在实施计划中按现有包结构微调，但职责边界不得合并回 Worker。

## 14. 测试策略

全部行为变更使用 TDD。每一刀先写最小失败测试，再实现通过。

### 14.1 路由测试

- `WEB` 只调用 Web backend。
- `ANDROID` 只调用 Android backend。
- 重复注册同一 backend 启动失败。
- 未注册 backend 返回 `UNSUPPORTED_BACKEND`。
- Worker 不包含 Android URL、字段名或响应字符串。

### 14.2 Web 回归测试

- 完整链接只序列化 `inviteLink`。
- 纯 code 只序列化 `inviteCode`。
- `joined=true/false` 映射保持现状。
- 原 Web HTTP 错误映射不回退。

### 14.3 Android 契约 fixture 测试

用固定 JSON fixture 锁定当前 Zhuan 原生响应：

- `Code=0` + 成功中文 + 裸 group ID。
- `Code=1003` + 账号离线。
- `Code=1003` + `Code: 403`。
- Gin `{"error":"..."}` 参数错误。
- 缺 `Code`、缺 `Data`、群 ID 无法提取。
- 未知新增字段不影响解析。

### 14.4 Android 成员确认测试

- 当前手机号出现在 `phone_number`。
- 当前手机号出现在 `jid`。
- 当前账号不存在于成功返回的成员列表。
- 成员查询返回离线、超时、未知 envelope。
- `JOIN_RESULT_UNCONFIRMED` 不会被 Worker 记成功。

### 14.5 Worker 测试

- Web 与 Android 账号都通过统一 port 执行。
- `JOINED/ALREADY_JOINED` 成功回填 group JID。
- `PENDING_APPROVAL` 保持现有失败统计语义。
- Android 状态网络异常不把本地账号强制写成 OFFLINE。
- 统一错误码正确写入 `join_task_result.reason`。

## 15. 分片实施顺序

每个切片独立提交、独立测试，前一片通过后再进入下一片。

### Slice 1：双后端 HTTP 配置地基

- 新增 backend properties 和两个 executor。
- 保持旧 Web base URL fallback。
- 不改任何业务调用。
- 验收：配置测试和 executor 选择测试通过，现有测试全绿。

### Slice 2：统一模型与进群路由

- 新增 `ProtocolAccountRef`、`GroupJoinCommand`、outcome。
- 新增 `GroupJoinBackend` 和 `RoutingGroupJoinPort`。
- 把现有 Web adapter 接入路由。
- Worker 仍只跑 Web 行为。
- 验收：Web 进群回归完全一致，路由测试通过。

### Slice 3：运行态查询拆分

- 新增 `AccountRuntimeStatusPort` 与 backend router。
- 提取 Web status adapter。
- 新增 Android status adapter，调用 Zhuan 原生 status。
- Worker 切换到窄端口。
- 验收：两种账号在线/离线/网络异常语义正确。

### Slice 4：Android 原生 join 与响应解码

- 新增 Android native client、request DTO、envelope decoder。
- 提取邀请 code、调用 Zhuan 原生 join。
- 解析成功群 ID和基础错误。
- 暂不接 Worker。
- 验收：所有 Android fixture 测试通过。

### Slice 5：Android 群成员确认

- 调用 Zhuan 原生群成员接口。
- 归一 participant 并判断当前账号是否真实在群。
- 产出 `JOINED/PENDING_APPROVAL/JOIN_RESULT_UNCONFIRMED`。
- 验收：确认逻辑单测与 HTTP adapter 测试通过。

### Slice 6：进群 Worker 接入 Android

- Worker 组装 backend-aware command。
- Android 账号走 Android adapter，Web 账号保持 Web adapter。
- 统一落明细状态、reason 和 group JID。
- 验收：混合 Web/Android 任务测试通过，完整相关测试全绿。

### Slice 7：任务可靠性加固（后续独立设计/计划）

- 落实现有 `retryEnabled/retryLimit`。
- 区分确定性失败和可重试失败。
- 评审 `JOIN_RESULT_UNCONFIRMED` 的持久化重试。
- 评审应用重启后的 RUNNING 任务恢复。

### Slice 8：营销双协议投递（另立设计）

- 给营销命令补 `protocolBackend`。
- 设计 Web Kafka 与 Android HTTP delivery adapter。
- 单独解决 Android HTTP 消息发送幂等和不确定结果。

## 16. 验收标准

首期 Slice 1-6 完成必须满足：

1. 不修改两套协议仓库即可完成 Armada 双协议进群调用。
2. 同一租户的 Web 与 Android 账号可执行同一套进群任务业务流程。
3. Web 请求和响应行为无回归。
4. Android 使用现有 status、invite、members 原生接口。
5. Android 原生响应和中文/原始码解析只存在于 Android adapter 包。
6. 业务 Worker 不包含 backend 分支、URL、原生字段或原始错误文本。
7. Android 成功后必须经过成员确认，未确认结果不得记成功。
8. 网络异常不得被误写成账号明确离线。
9. 所有错误以统一 `ProtocolErrorCode` 落任务明细，原始码仅作诊断上下文。
10. Web 回归、Android fixture、路由、成员确认和 Worker 混合任务测试全部通过。

## 17. 后续演进边界

本设计建立的是“Armada 适配外部协议”的能力路由模式，不要求一次迁移所有端口。后续每接一项能力，只新增该能力的 backend SPI 和对应 native adapter；不为了复用而提前创建巨型协议客户端，也不把营销、建群或群管理塞进本次进群实现。
