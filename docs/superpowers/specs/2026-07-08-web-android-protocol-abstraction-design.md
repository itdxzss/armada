# Web / Android 双协议接入核心设计

> 状态: 设计草案
> 日期: 2026-07-08
> 范围: Armada 后端如何同时接入现有 Web 协议层和 `whatsapp-server-feature-android` Android 协议服务。

## 背景

Armada 当前已接入 `armada-protocol` 的 Web 协议能力。现有主链路是:

1. 账号、群组、营销等业务服务写入 `protocol_command_outbox`。
2. dispatcher 把 outbox 命令发送到协议层 Kafka topic。
3. 协议层执行命令。
4. 协议层通过 Kafka 事件回写 `account.state_changed`、`account.groups_reported`、`message.send_result_reported` 等事件。
5. Armada 消费统一事件并收敛本地账号、群组、营销状态。

Android 协议服务 `whatsapp-server-feature-android` 是 Go + Gin 服务，暴露 `/ws/v1/...` HTTP API，并通过 HTTP callback 上报登录、离线、消息、消息状态等事件。它与现有 Web 协议层的传输方式、路由模型和回调格式不同。

目标不是让业务层同时理解两套协议，而是把 Web / Android 差异压在 `platform/protocol` 防腐层和协议适配层内。

## 设计结论

需要抽象，而且抽象应放在协议平台层:

- 业务域只依赖 `platform/protocol/port` 的能力接口。
- Web / Android 是同一组协议能力的两个后端实现。
- 命令可以按协议后端路由到不同执行通道。
- 事件必须统一回写为 Armada 已有协议事件模型。
- 禁止在账号、群组、营销、任务等业务服务内散落 `if web / if android`。

不做一个大而全的 `WhatsappProtocolService`。继续按能力拆分端口:

- `AccountLifecyclePort`: 上线、下线、状态、探活。
- `GroupCreatePort`: 建群。
- `GroupJoinPort`: 邀请链接入群。
- `GroupParticipantPort`: 添加、移除、提升、降级、查询群成员。
- `GroupProfilePort`: 群名称、群描述、群头像、发言模式、锁群等设置。
- 后续需要消息能力时再接 `MessagePort`，不提前扩大范围。

## 核心模型

账号需要明确绑定协议后端。导入时按凭据类型确定协议归属:

- JSON / Baileys JSON: `WEB`
- 六段号: `ANDROID`

```text
account
  protocol_backend: WEB | ANDROID
  protocol_account_id: 协议账号句柄
  protocol_id / protocol_address: 可继续作为实例或集群路由信息
  device_os / account_credential.cred_format: 用于判断凭据和设备能力
```

如果短期不新增列，可以先复用 `protocol_id` 表达后端，但长期应收敛成明确枚举，避免把实例 ID、协议类型、服务地址混在一个字段里。

账号列表查询应返回协议后端给前端。前端可以不展示该字段，但批量上下线时要把它随账号 ID 带回:

```json
{
  "accounts": [
    { "id": 100, "protocolBackend": "WEB" },
    { "id": 101, "protocolBackend": "ANDROID" }
  ]
}
```

账号生命周期命令使用请求项里的 `protocolBackend` 做路由，不再为了判断 Web/Android 额外回表。后端仍会读取账号、凭据、状态和代理信息完成上线前置校验。

其它内部协议能力调用不应只传 `protocolAccountId`，而应传一个协议账号引用:

```java
public record ProtocolAccountRef(
        Long accountId,
        String protocolAccountId,
        ProtocolBackend backend
) {
}
```

这样 `platform/protocol` 不需要反向依赖 `account` mapper，也不会破坏现有架构规则。

## Port 与路由

业务域看到的是统一 Port:

```text
AccountOnlineCommandService
  -> AccountLifecyclePort

GroupCreationMarketingWorker / GroupService
  -> GroupCreatePort / GroupParticipantPort / GroupProfilePort
```

Spring 中注册的是 routing port:

```text
RoutingAccountLifecyclePort
  -> WebAccountLifecycleAdapter
  -> AndroidAccountLifecycleAdapter

RoutingGroupCreatePort
  -> WebGroupCreateAdapter
  -> AndroidGroupCreateAdapter
```

Routing port 只负责根据 `ProtocolAccountRef.backend` 选择后端。具体 HTTP body、Kafka payload、callback code 映射都在后端 adapter 内完成。

## 命令通道

账号上线、下线、批量任务、营销发送这类需要可靠投递和重试的操作继续走 outbox。账号生命周期批量接口一次最多 1000 个账号，单账号上下线也使用同一个批量接口传 1 个账号:

```text
批量上下线请求(accounts[].protocolBackend)
  -> 业务服务
  -> protocol_command_outbox(protocol_backend)
  -> dispatcher
  -> Web command topic / Android command topic
  -> 对应协议后端执行
```

建议为 outbox 增加协议后端字段:

```text
protocol_command_outbox.protocol_backend = WEB | ANDROID
```

dispatcher 根据 `protocol_backend` 选择目标 topic 或目标 publisher。Web 保持现有 topic；Android 可以新增:

```text
protocol.android.commands.v1
```

Android 命令不建议由 Armada Java 直接同步调用 Go 服务。推荐新增 Android bridge:

```text
protocol.android.commands.v1
  -> android bridge
  -> whatsapp-server-feature-android HTTP API
  -> HTTP callback
  -> unified Kafka events
```

这样 Java 业务层仍保持 outbox 语义，不因为 Android 是 HTTP 服务就退化成请求线程直连。

## 事件回写

Web / Android 最终都必须回到同一套事件:

```text
account.state_changed
account.groups_reported
account.offline_diagnosed
message.send_result_reported
group.health_reported
```

Android callback 到 Armada 时，不直接让业务服务处理原始 callback。应由 Android bridge 或 `platform/protocol/android` callback adapter 转换成统一 Kafka event。

Android callback 映射示例:

| Android 事件 | Armada 统一事件 |
| --- | --- |
| 登录成功 | `account.state_changed` -> `ONLINE` |
| 主动下线 | `account.state_changed` -> `OFFLINE`, source=manual/batch |
| 心跳/网络异常离线 | `account.state_changed` -> `OFFLINE` 或 `PROXY_FAILED`, 按错误分类 |
| 被抢登 | `account.state_changed` -> `LOGIN_REPLACED` |
| 设备移除/解绑 | `account.state_changed` -> `DEVICE_REMOVED` 或 `NEED_REAUTH` |
| 403 / banned 类失败 | `account.state_changed` -> `NEED_REAUTH`, rawCode=403 |
| 群列表回报 | `account.groups_reported` |
| 消息发送结果 | `message.send_result_reported` |

统一事件的好处是账号状态收敛、抢登续上线、代理失败重上线、导入上线结算、营销发送结算都不需要复制两套逻辑。

## 同步接口与异步任务

群组能力分两类:

1. 页面/API 手动操作: 可以通过 `Group*Port` 同步调用 routing adapter。
2. 批量任务/营销/建群: 应继续走 outbox + 事件回写，保证可重试、可追踪、不会阻塞请求线程。

因此 `GroupCreatePort` 等接口可以存在，但调用方要按业务场景选择同步还是 outbox。不要因为抽象了 Port，就把所有批量任务改成同步 HTTP 调用。

## 首期范围

建议第一期只接账号生命周期:

- 账号协议后端枚举。
- 上线命令按后端路由。
- Android bridge 消费上线/下线命令并调用 Go 服务。
- Android callback 转 `account.state_changed`。
- 支持状态、探活、主动下线。
- 保留现有 Web 协议行为不变。

群组接口作为第二期:

- 建群。
- 加人、移除、管理员。
- 群成员查询。
- 群名称、描述、头像。
- 群发言模式、入群审批、锁群。

消息和营销发送作为第三期，等生命周期和群组状态稳定后再接。

## 风险与待确认点

1. 凭据格式不等价。现有 Web / Baileys JSON 凭据未必能直接映射成 Android `/auth/login` 所需字段。首期建议只支持 Android 凭据账号，不承诺已有 Web 凭据可直接切 Android。
2. Android 服务当前按内存实例查 `:key`，多实例部署需要明确账号 owner 路由，否则 `status/logout/send/group` 可能打到错误节点。
3. Android callback 是 HTTP 回调且原始 code 语义不同，必须先稳定映射到 Armada 状态机。
4. 配置和凭据需要环境变量化，不能把数据库、Redis、callback 地址硬编码在版本库配置中。
5. Android HTTP API 的同步返回不等价于账号最终在线，Armada 对外仍应保持“命令已受理，最终状态等事件”的语义。

## 非目标

- 不在业务域内直接调用 Android HTTP API。
- 不把 Web / Android 差异暴露到账号、群组、营销、任务服务。
- 不一次性接完所有消息、群组、频道能力。
- 不承诺 Web 凭据自动转换为 Android 凭据。
- 不改变现有 Web 协议的 outbox/Kafka 主链路。

## 验收口径

第一期完成后应满足:

1. 同一个账号根据协议后端选择 Web 或 Android 上线通道。
2. 业务服务不出现分散的 `if WEB / if ANDROID` 协议调用逻辑。
3. Android 登录成功、离线、抢登、解绑等事件能统一落到 `account_state`。
4. Web 协议现有上线、下线、状态回写行为不回退。
5. 失败命令可在 outbox 中重试或进入 dead 状态，排查字段包含协议后端。
