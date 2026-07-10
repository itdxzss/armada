# Android Zhuan 批量账号生命周期接入设计

> 状态：已确认，待书面复核与实施计划
> 日期：2026-07-10
> 目标分支：`armada/1.0.1-snapshot`
> 协议实现：`whatsapp-server-feature-android-zhuan`

## 1. 背景

Armada 已具备 Android 协议后端枚举、批量账号上线/下线接口、协议命令 outbox、`protocol.android.commands.v1` 路由和统一账号状态事件消费能力。此前接入的 `whatsapp-server-feature-android` 协议实现无法使用，因此该协议源码、运行服务和其特有接入假设全部退役。

`whatsapp-server-feature-android-zhuan` 已通过真实账号六段凭证登录验证。本期以它作为唯一 Android 协议实现，只接通账号批量上线、批量下线和逐账号状态回写，不扩大到群组、消息或营销能力。

`1.0.1-android-snapshot` 已于提交 `8a3a9d9` 合并进 `1.0.1-snapshot`，因此 Armada 现有 Android 路由和批量生命周期能力作为本设计的基础，不重复开发。

## 2. 目标与范围

本期目标：

1. Armada 按 `-zhuan` 实测成功的六段字段顺序导入并保存凭据。
2. 批量上线时，每个账号通过 outbox 独立投递到 `-zhuan`，最终回写在线或明确失败状态。
3. 批量下线时，每个账号独立执行，重复下线保持幂等，最终回写离线状态。
4. 批次内不同账号无序并发；同一账号的先后命令保持顺序。
5. 旧 Android 账号不做凭据迁移，切换前删除，切换后按新格式重新导入。
6. 全部行为变更严格采用 TDD：先写测试并确认按预期失败，再写最小实现使其通过，最后重构。

本期不包含：

- 群同步、建群、加人、移除成员或群设置。
- 消息发送、营销任务、朋友圈或频道能力。
- 旧 `whatsapp-server-feature-android` 的兼容、修复或双跑。
- 旧六段凭据的自动识别、转换或迁移。
- 扫码、配对码、Baileys JSON 或 PARAMS 登录。

## 3. 核心决策

采用“在 `whatsapp-server-feature-android-zhuan` 内重新实现轻量 Armada 生命周期适配模块”的方案：

```text
Armada 批量上线/下线接口
  -> 每账号一条 protocol_command_outbox，共享 batchId
  -> protocol.android.commands.v1
  -> zhuan Armada lifecycle adapter
  -> SixLoginService / LogOutService
  -> account.state_changed
  -> Armada 账号状态收敛
```

不新增独立 bridge 服务，也不让 Armada Java 请求线程直接批量调用 Go HTTP API。这样可以保留现有 outbox 的事务后投递、失败重试、批次排查和最终一致状态语义，同时避免增加一套部署单元。

旧协议中的 `internal/armada` 只能作为 Armada 事件契约的参考，不能整体复制到 `-zhuan`。新适配模块必须以 `-zhuan` 的登录、回调和运行时行为为准重新实现和测试。

## 4. Armada 侧设计

### 4.1 六段导入契约

新六段输入顺序固定为：

```text
phone,staticPub,staticPri,identityPub,identityPri,phoneId
```

`AccountImportParser` 将每一行规范化为语义明确的 JSON：

```json
{
  "phone": "...",
  "static_pub_key": "...",
  "static_pri_key": "...",
  "id_pub_key": "...",
  "id_pri_key": "...",
  "phone_id": "..."
}
```

校验规则：

- 必须正好六列。
- `phone` 必须是 7 至 15 位纯数字。
- 后五列去除首尾空白后均不能为空。
- 单行失败只记录该行错误，不阻塞同一导入批次中的其他行。
- 错误信息只指出行号、列号和错误类型，不输出字段原值。
- `rawPayload` 仅用于受控原格式导出，不进入普通日志或列表响应。

`account_credential.cred_format` 继续使用 `SIX_SEGMENT` 对应编码。`creds_json` 保存规范化 JSON，不保存由逗号拼接的 `sixdata`，以免协议传输格式污染业务存储。

### 4.2 账号类型与代理

导入元数据中的 `account_type` 继续保持“导入即冻结”：

- `1` 映射为 `isBusiness=false`。
- `2` 映射为 `isBusiness=true`。

上线命令需要把该布尔值作为安全元数据透传给协议适配模块。在 `ProtocolOnlineCommandRequest` 和 outbox 安全 payload 中增加 `isBusiness`，由账号编排服务根据已查询到的 `Account.accountType` 生成，避免协议 publisher 再次反查账号表。

代理仍由 Armada 分配并在 Kafka 投递前解析为完整 `ProxyDescriptor`。`-zhuan` 适配模块只使用 `proxy.url` 生成 `SixLoginDto.socks5`，不参与代理选择和绑定。

### 4.3 批量命令

继续使用现有批量接口和 outbox 语义：

- 一个请求生成一个 `batchId`。
- 每个账号生成独立 `commandId` 和独立 outbox 行。
- Kafka key 继续使用 `protocolAccountId`。
- 同一账号的上线、下线命令进入同一 Kafka 分区并保持发送顺序。
- 不同账号之间不保证顺序，也不等待前一账号完成。
- 批量接口返回“已受理”只表示命令已进入 outbox，不代表账号已经在线或离线。

上线 Kafka payload 保持结构化，不在 Armada 内提前拼接 `sixdata`：

```json
{
  "tenantId": 1,
  "accountId": 100,
  "protocolAccountId": "acc_919000000000",
  "format": "six",
  "credential": {
    "phone": "...",
    "static_pub_key": "...",
    "static_pri_key": "...",
    "id_pub_key": "...",
    "id_pri_key": "...",
    "phone_id": "..."
  },
  "proxy": {
    "protocol": "SOCKS5",
    "url": "..."
  },
  "isBusiness": false,
  "source": "batch_online",
  "onlineAttemptId": "...",
  "previousOnlineAttemptId": null
}
```

outbox 数据库行只保存账号、凭据格式和代理 ID 等引用；完整凭据和代理地址仍在 dispatch 时加载并写入 Kafka envelope，不把密钥明文长期复制到 outbox 表。

## 5. Zhuan 协议侧设计

### 5.1 组件边界

在 `whatsapp-server-feature-android-zhuan` 新增独立 Armada 生命周期适配包，职责拆分如下：

- `CommandDecoder`：解析并校验 Armada command envelope，只接受 `account.online.requested` 和 `account.offline.requested`。
- `SixCredentialMapper`：按固定顺序把结构化凭据组装成 `sixdata`。
- `LifecycleExecutor`：分别调用 `service.SixLoginService` 和 `service.LogOutService`。
- `CommandContextStore`：在 Redis 中按 `protocolAccountId` 和手机号保存租户、账号、批次、命令和上线尝试上下文。
- `CallbackObserver`：观察 `-zhuan` 登录/离线事件并转换为 Armada 统一状态事件。
- `AccountEventPublisher`：发布 `account.state_changed`。
- `PublishOnceGuard`：使用 Redis `SET NX` 对同一 `commandId + targetState` 去重，防止同步返回和异步 callback 重复触发业务副作用。
- `CommandConsumer`：消费 Kafka 命令，控制并发、提交和重试。

适配包可以依赖 `api/service`、`internal/external`、Redis 和 Kafka 客户端；协议核心不得反向依赖 Armada Java 模型或业务数据库。

### 5.2 上线执行

上线流程：

1. 校验 `format` 必须为 `six`，六个语义字段、代理 URL 和上下文字段必须完整。
2. 在调用协议服务前保存 `CommandContext`，同时按 `protocolAccountId` 和 `phone` 建索引。
3. 严格按以下顺序拼接 `sixdata`：

   ```text
   phone,static_pub_key,static_pri_key,id_pub_key,id_pri_key,phone_id
   ```

4. 构造 `SixLoginDto{SixData, Socks5, IsBusiness}` 并调用 `SixLoginService`。
5. 最终 ONLINE 或失败状态以统一事件回写；同步返回只用于识别“请求已进入登录流程”或“立即失败”，不能把受理当成在线。

`SixLoginService` 内部把 `QrId` 设为手机号，因此 callback 关联必须同时支持按手机号查找 `CommandContext`；发布到 Armada 的 envelope 仍使用存储的 `protocolAccountId`。

### 5.3 下线执行

下线流程：

1. 根据 `protocolAccountId` 找到已保存的手机号上下文。
2. 用手机号调用 `LogOutService(phone, "")`。
3. 正常 callback 映射为 `OFFLINE`。
4. 若运行时实例不存在或已经离线，按幂等成功处理并主动发布一次 `OFFLINE`，不得让消息无限重试。

### 5.4 并发与顺序

批次内不同账号允许无序并发。实现使用 Kafka 分区并行和可配置的 consumer 并发数，不使用可能导致同一分区 offset 乱序提交的无约束 worker pool。

- 同一个 `protocolAccountId` 的命令使用相同 Kafka key，保持分区内顺序。
- 不同账号可被不同分区/consumer 同时执行，完成顺序不固定。
- consumer 并发数配置项默认值为 `4`，允许部署环境按机器容量和 topic 分区数调整，防止短时间创建过多 WhatsApp 连接。
- consumer 并发数超过 topic 分区数不会增加实际并行度；部署前需要确认 topic 分区满足目标并发。
- `batchId` 只用于归组和排查，不参与排序或调度。

## 6. 状态与错误映射

统一状态映射：

| Zhuan 事件/结果 | Armada `to` | `semantic` | 原始码 |
| --- | --- | --- | --- |
| `loginSuccess` / `200` | `ONLINE` | 空 | `200` |
| 主动下线 / `101` | `OFFLINE` | 空 | `101` |
| 网络、心跳或代理失败 / `302` | `PROXY_FAILED` | `PROXY_FAILED` | `302` |
| 抢登 / `303` | `LOGIN_REPLACED` | `LOGIN_REPLACED` | `303` |
| 设备移除 / `-407` | `DEVICE_REMOVED` | `DEVICE_REMOVED` | `-407` |
| 登录凭据失效 | `NEED_REAUTH` | `LOGIN_FAILED` | WhatsApp 原始原因码 |
| WhatsApp reason `403` | `NEED_REAUTH` | `LOGIN_FAILED` | `403` |

Armada 已能依据 `to/semantic` 识别 `LOGIN_REPLACED`，因此 Zhuan 的原始抢登码 `303` 不伪装成 Web/Baileys 的 `440`。

`-zhuan` 当前登录失败 callback 使用通用 code `-401`，真实的 `401/403/503` 仅存在于消息文本。为避免字符串解析，给 `WhatsAppEventPayload` 增加向后兼容的可选 `ReasonCode` 字段，并由 `onLoginFailure` 写入真实数字原因。原 HTTP callback 消费者可以忽略新增字段，Armada observer 优先使用 `ReasonCode` 作为 `rawCode`。

错误分类和 Kafka 处理规则：

- 凭据字段缺失、格式错误、账号不存在、已离线下线：确定性结果，发布对应状态后提交消息。
- `SixLoginService` 明确返回参数或认证失败：发布失败状态后提交消息。
- Kafka、Redis 或事件发布临时失败：不提交当前消息，等待重试。
- callback 已发布状态后，同步执行器再次得到同一结果：由 `PublishOnceGuard` 去重。
- 日志只记录 `commandId`、`batchId`、脱敏手机号、状态和错误分类，不记录完整 credential、`sixdata`、代理密码或 Kafka payload。

## 7. 存量删除与切换

历史 Android 账号不迁移，切换步骤如下：

1. 停止旧 `whatsapp-server-feature-android` 服务，确保它不再消费 Android topic。
2. 在明确确认的目标环境中预览历史 Android 账号数量和 ID，形成固定删除清单。
3. 删除清单以切换时已存在的 Android 账号 ID 为准，不能使用会误删后续新导入账号的长期动态条件。
4. 在事务和既有业务删除规则下清理账号、凭据、状态及关联业务数据，并释放代理绑定。
5. 清理或终结这些账号尚未发送的 Android outbox 命令，避免删除后继续投递。
6. 启动 `-zhuan` Armada adapter，确认旧服务和新服务不会同时消费。
7. 按新六段顺序重新导入账号，再进行小批量上线/下线验证。

删除属于批量数据修改，不放入 Flyway migration，不随应用启动自动执行。执行前必须再次确认测试/生产环境、租户范围、账号数量和回滚保留方式。

## 8. TDD 实施约束

实施计划必须把每个行为拆成独立 RED-GREEN-REFACTOR 循环：

1. 先写一个描述目标行为的最小测试。
2. 单独运行并确认测试因目标行为尚未实现而失败，不接受编译错误或测试本身错误。
3. 写最小生产代码使该测试通过。
4. 运行相关测试和回归测试，确认全部通过。
5. 只在全绿后重构，然后再次运行测试。

Armada 侧至少覆盖：

- 新六段字段顺序和规范化 JSON。
- 空列、错误列数和非法手机号逐条失败。
- `account_type` 到 `isBusiness` 的映射。
- Android 上线 payload 包含结构化六段凭据、代理和上下文，但 outbox 安全 payload 不含密钥。
- 批量上线/下线一账号一行、共享 `batchId`。

Zhuan 侧至少覆盖：

- command envelope 解析和只接受生命周期命令。
- 结构化字段到 `sixdata` 的严格顺序。
- 上线调用 `SixLoginService`，下线调用 `LogOutService`。
- 已离线下线的幂等行为。
- callback 到统一状态的全部映射，包括 `302/303/-407/403`。
- 同一命令状态去重。
- 临时基础设施失败不提交，确定性业务失败发布状态后提交。
- 不同账号可并发、同一账号保持 Kafka 分区内顺序。

## 9. 验收标准

本期完成必须同时满足：

1. 旧协议服务已停止，Armada 只把 Android 生命周期命令交给 `-zhuan`。
2. 按新顺序导入的六段账号可以被正确保存和重新导出，不发生密钥错位。
3. 批量上线产生一账号一命令，同批账号无序并发，一个坏凭据不阻塞其他账号。
4. 成功账号最终回写 `ONLINE`；凭据失败、403、代理失败、抢登、设备移除均回写明确状态。
5. 批量下线最终回写 `OFFLINE`，重复下线幂等。
6. 同一个账号先上线后下线时，命令顺序不会反转。
7. 账号状态事件包含正确 `tenantId/accountId/protocolAccountId/batchId/commandId/onlineAttemptId`。
8. 日志、普通接口、测试输出和 outbox 数据库行不暴露六段密钥或代理密码。
9. 所有新增行为都有先失败后通过的自动化测试，相关 Armada Java 测试和 Zhuan Go 测试全部通过。
10. 历史 Android 账号只在明确确认的目标环境和固定 ID 清单下删除，新导入账号不受清理操作影响。
