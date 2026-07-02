# 账号上线/离线可观测性设计

## 目标

让 Armada 的每一次账号上线都有可追溯记录。账号卡在 `VERIFYING`、短暂 `ONLINE` 后又离线、进入 `PROXY_FAILED`，或者被协议层自动救援时，我们需要能从持久化数据回答三个问题：

1. 这是哪一次上线尝试。
2. Armada 和协议层分别发生了哪些状态变化。
3. 最后离线或失败的证据是什么。

第一期重点服务测试环境的账号上线排查，但设计要能平滑推广到生产环境。

## 当前现状

Armada 现在已经有一条完整的命令和状态事件链路：

- Armada 创建协议命令 outbox，里面有 `commandId`、`batchId`、账号身份、凭据格式、代理 id、来源等信息。
- Armada 把命令发布到 Kafka。
- 协议层消费账号上线命令，并通过 Baileys 驱动 WhatsApp。
- 协议层发布 `account.state_changed`。
- Armada 消费 `account.state_changed`，同步账号登录状态。

这条链路能看到粗粒度状态变化，比如：

- `VERIFYING -> ONLINE`
- `ONLINE -> PROXY_FAILED`
- `VERIFYING -> PROXY_FAILED`

但它还不能完整解释每一种失败原因，主要缺口是：

- 没有一个持久化的 `onlineAttemptId` 把命令 outbox、协议 worker 日志、协议事件、Armada 账号状态和重试串起来。
- 协议 worker 虽然能从命令 envelope 拿到 `commandId` 和 `batchId`，但当前上线执行路径没有把它们保留到账号运行时上下文。
- `account.state_changed` 只适合表达生命周期状态变化，里面没有结构化诊断信息。
- `VERIFYING` 超时只能说明 Baileys 没有抛出 `connection.update open/close`，不能进一步区分是代理连接、DNS、TLS、HTTP CONNECT，还是 WA 握手阶段失败。
- `PROXY_FAILED` 后自动重新上线会释放代理再重新分配，可能又选回同一个代理，导致重复失败看起来像几次独立尝试。

## 不做什么

这次设计不重写协议状态机，不替换 Baileys，也不追踪每个底层网络包。账号上线仍然是异步命令流程，不改成同步等待成功或失败。

## 总体方案

新增一条“每次上线尝试”的观测链路：

- Armada 为每次上线或重新上线生成 `onlineAttemptId`。
- `onlineAttemptId` 写入协议命令 outbox，并通过 Kafka 传到协议 worker。
- 协议 worker 把本次上线尝试的上下文保存到 `AccountManager`。
- 协议层在失败、离线、心跳失效、救援失败等关键节点发布诊断事件。
- Armada 消费诊断事件，写入一张只用于排查的持久化时间线表。
- 现有 `account.state_changed` 继续负责账号状态同步，不承载大块诊断证据。

核心原则：状态同步和问题诊断分开。

`account.state_changed` 保持轻量、稳定、关键。新增诊断事件可以携带更丰富证据，不影响状态同步链路。

## 标识模型

新增一个字符串标识：

```text
onlineAttemptId
```

推荐格式：

```text
oa_<yyyyMMddHHmmss>_<shortRandom>
```

示例：

```text
oa_20260702101716_x7k9m2
```

这个 id 必须由 Armada 在创建协议命令 outbox 之前生成。这样它会同时存在于：

- `protocol_command_outbox.payload`
- Kafka 命令 payload
- 协议 worker 运行时日志
- 协议诊断事件 payload
- Armada 诊断时间线表

几个 id 的边界：

- `commandId`：一次命令消息的投递 id。
- `batchId`：一次批量操作的分组 id。
- `onlineAttemptId`：某个账号使用某份凭据和某个代理发起的一次逻辑上线尝试。

自动重新上线时，每次重试都生成新的 `onlineAttemptId`。如果能拿到上一轮 attempt，就带上 `previousOnlineAttemptId`，方便把多次重试串成链。

## 命令 Payload 调整

账号上线命令 payload 增加 `onlineAttemptId` 和 `previousOnlineAttemptId`：

```json
{
  "accountId": 9,
  "protocolAccountId": "acc_252625852450",
  "credentialFormat": "BAILEYS_AUTH_INFO",
  "proxyId": 4035,
  "source": "batch_online",
  "onlineAttemptId": "oa_20260702101716_x7k9m2",
  "previousOnlineAttemptId": null
}
```

协议命令 envelope 里已有 `commandId` 和 `batchId`。协议 worker 消费上线命令时，要把 envelope 字段和 payload 字段一起放进账号运行时上下文。

运行时上下文至少包含：

- `tenantId`：租户 id。
- `accountId`：Armada 账号 id。
- `protocolAccountId`：协议层账号 id。
- `onlineAttemptId`：本次上线尝试 id。
- `previousOnlineAttemptId`：上一轮上线尝试 id，可为空。
- `commandId`：协议命令 id。
- `batchId`：批量操作 id。
- `proxyId`：本次使用的代理 id。
- `source`：上线来源，比如 `batch_online` 或 `proxy_failed_reonline`。
- `workerId`：处理账号的协议 worker。
- `startedAt`：协议层开始处理这次上线的时间。

当前协议上线执行路径里有把 source 固定成 `api` 的地方。这里要改成保留 Armada 命令 payload 里的真实 source。

## 新增诊断事件

协议层新增事件：

```text
account.offline_diagnosed
```

这个事件只负责诊断，不替代 `account.state_changed`。

协议层在以下节点发布诊断事件：

- `VERIFYING` 超时，且没有收到任何 `connection.update open/close`。
- Baileys 发出 `connection.close`，并带有 raw code 或错误信息。
- 心跳判断连接已失活。
- 快速救援失败。
- 手动离线关闭账号。
- 登录状态不可恢复，例如被登出、设备被移除、需要重新认证。
- worker 因失败释放运行时 slot。

事件数据结构：

```json
{
  "tenantId": 1,
  "accountId": 9,
  "protocolAccountId": "acc_252625852450",
  "onlineAttemptId": "oa_20260702101716_x7k9m2",
  "previousOnlineAttemptId": null,
  "commandId": "cmd_xxx",
  "batchId": "batch_xxx",
  "proxyId": 4035,
  "source": "batch_online",
  "from": "VERIFYING",
  "to": "PROXY_FAILED",
  "diagnosisCode": "VERIFY_TIMEOUT_NO_CONNECTION_UPDATE",
  "diagnosisClass": "PROXY_OR_WA_CONNECTIVITY",
  "rawCode": 408,
  "rawReason": "no connection.update open/close before verify timeout",
  "recoverability": "RETRYABLE",
  "actionTaken": "MARK_PROXY_FAILED_RELEASE_SLOT",
  "occurredAt": "2026-07-02T10:18:00.123Z",
  "workerId": "w3",
  "evidence": {
    "connectionField": "connecting",
    "wsOpen": false,
    "lastConnectionUpdateAt": null,
    "verifyTimeoutMs": 30000,
    "lastHeartbeatAt": null,
    "lastProbeRttMs": null
  }
}
```

字段解释：

- `diagnosisCode`：具体诊断码，排查时主要看它。
- `diagnosisClass`：诊断分类，用于统计和告警。
- `rawCode`：协议层或 Baileys 看到的原始错误码。
- `rawReason`：原始错误说明，长度要限制。
- `recoverability`：是否可重试。
- `actionTaken`：协议层当时做了什么动作。
- `evidence`：辅助证据，必须是小而安全的 JSON。

证据必须做边界控制和脱敏：

- 不存凭据。
- 不存二维码。
- 不存 cookie。
- 不存完整代理密码。
- 不存大段堆栈。
- 长错误信息发布前截断。

## 诊断码

第一期先定义固定枚举，覆盖已知问题，同时保留未知兜底。

| 诊断码 | 分类 | 含义 |
| --- | --- | --- |
| `VERIFY_TIMEOUT_NO_CONNECTION_UPDATE` | `PROXY_OR_WA_CONNECTIVITY` | 账号停在 `VERIFYING`，超时前 Baileys 没有发出 open 或 close。 |
| `WA_CONNECTION_TERMINATED_428` | `WA_TRANSIENT` | WA 或 Baileys 返回 raw code 428。通常可重试，但如果同一个代理反复出现，要重点怀疑代理或链路。 |
| `HEARTBEAT_TIMEOUT` | `STALE_CONNECTION` | 协议心跳没有及时拿到存活证明。 |
| `QUICK_RESCUE_FAILED` | `STALE_CONNECTION` | 快速救援尝试过，但没有恢复账号。 |
| `STALE_HALF_OPEN_WS` | `STALE_CONNECTION` | WebSocket 看起来还开着，但实际已经不像活连接。 |
| `NEED_REAUTH` | `AUTH` | 当前凭据无法恢复会话，需要重新认证。 |
| `LOGGED_OUT` | `AUTH` | WA 报告账号已登出或会话失效。 |
| `DEVICE_REMOVED` | `AUTH` | 关联设备被移除。 |
| `MANUAL_OFFLINE` | `OPERATOR_ACTION` | 用户或系统主动把账号下线。 |
| `PROXY_CONNECT_ERROR` | `PROXY_NETWORK` | 代理连接明确失败。 |
| `PROXY_CONNECT_TIMEOUT` | `PROXY_NETWORK` | 代理连接在进入 WA 握手前超时。 |
| `PROXY_AUTH_FAILED` | `PROXY_NETWORK` | 代理认证失败。 |
| `UNKNOWN_OFFLINE` | `UNKNOWN` | 账号离线，但当前证据不足以归类。 |

协议层优先使用更具体的诊断码。如果只能拿到 raw code 或错误字符串，就使用最接近的诊断码，同时保留 raw 字段。

## Armada 持久化

新增一张只追加写的诊断时间线表：

```sql
CREATE TABLE account_online_attempt_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  protocol_account_id VARCHAR(128) NOT NULL,
  online_attempt_id VARCHAR(64) NOT NULL,
  previous_online_attempt_id VARCHAR(64) NULL,
  command_id VARCHAR(64) NULL,
  batch_id VARCHAR(64) NULL,
  proxy_id BIGINT NULL,
  source VARCHAR(64) NULL,
  from_state VARCHAR(32) NULL,
  to_state VARCHAR(32) NULL,
  diagnosis_code VARCHAR(64) NOT NULL,
  diagnosis_class VARCHAR(64) NOT NULL,
  raw_code INT NULL,
  raw_reason VARCHAR(512) NULL,
  recoverability VARCHAR(32) NULL,
  action_taken VARCHAR(64) NULL,
  worker_id VARCHAR(64) NULL,
  evidence_json JSON NULL,
  occurred_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  KEY idx_attempt (tenant_id, online_attempt_id),
  KEY idx_account_time (tenant_id, account_id, occurred_at),
  KEY idx_proxy_time (tenant_id, proxy_id, occurred_at),
  KEY idx_code_time (tenant_id, diagnosis_code, occurred_at)
);
```

这张表不参与账号状态机，只做审计和排查。

保留策略：

- 测试环境保留 30 天。
- 生产默认保留 7 到 14 天详细证据，之后归档或删除。
- 单行数据要小，不能让账号上线性能依赖这张表。

## Armada 消费逻辑

协议事件消费者新增对 `account.offline_diagnosed` 的处理：

1. 解析事件 envelope。
2. 校验 `tenantId`、`accountId`、`protocolAccountId`、`onlineAttemptId`。
3. 写入一条 `account_online_attempt_log`。
4. 格式不合法的诊断事件只记录日志并跳过，不能影响 `account.state_changed` 消费。

状态同步和诊断落库必须互相独立。诊断落库失败时，账号状态仍然要正常同步。

## 查询面

第一期提供小而够用的排查查询：

- 按账号查最近上线尝试。
- 按 `onlineAttemptId` 查一次尝试的完整时间线。
- 按代理查最近失败。
- 按诊断码查最近重复失败。

当前排查最有用的是这种紧凑时间线：

```text
账号 252625852450
attempt oa_20260702101716_x7k9m2
代理 4035

10:17:16 VERIFYING，由 batch_online 发起
10:17:26 ONLINE
10:17:30 PROXY_FAILED，WA_CONNECTION_TERMINATED_428，rawCode=428
10:18:00 PROXY_FAILED，VERIFY_TIMEOUT_NO_CONNECTION_UPDATE，rawCode=408
10:18:11 ONLINE
10:22:15 STALE，HEARTBEAT_TIMEOUT
10:22:15 QUICK_RESCUE_FAILED
10:22:45 PROXY_FAILED，VERIFY_TIMEOUT_NO_CONNECTION_UPDATE
10:23:01 ONLINE
```

第一期可以先做 DbTest 友好的 mapper 查询，后面再补内部管理 API。Codex skill 或命令行脚本也可以复用同一套查询逻辑。

## 性能控制

这个机制不能给账号上线增加同步等待。

控制点：

- Armada 在内存里生成 `onlineAttemptId`，不增加远程调用。
- 协议诊断事件走现有异步事件发布器。
- 诊断落库只服务可观测性，和账号状态同步分开。
- Prometheus 指标只使用低基数字段，比如诊断码、诊断分类、worker id、动作，不把 account id 或 attempt id 作为 label。
- `evidence_json` 必须有大小边界，不存大日志或堆栈。
- 索引只围绕排查查询建立，避免过宽组合索引拖慢写入。
- 详细诊断行有限期保留。

预期影响：

- Armada 创建上线命令时，多生成一个字符串 id，并多写几个 payload 字段。
- 协议运行时多保存一个小上下文对象。
- 只有状态边界和失败节点才发诊断事件，不按心跳频率写库。
- 数据库每个诊断点写一行，不是每次心跳写一行。

在这个边界下，正常上线吞吐不应该受到明显影响。

## 分阶段落地

按下面顺序拆实现：

1. Armada 增加 `account_online_attempt_log` 迁移和 mapper。
2. Armada 生成 `onlineAttemptId`，并写入协议命令 outbox payload。
3. 协议层解析命令时保留 `commandId`、`batchId`、`onlineAttemptId`、`proxyId`、`source` 等上下文。
4. 协议层新增诊断 helper，把 close、timeout、heartbeat、rescue、auth、manual offline 等情况映射成诊断码。
5. 协议层在关键诊断点发布 `account.offline_diagnosed`。
6. Armada 消费 `account.offline_diagnosed` 并写入诊断时间线表。
7. 增加账号 attempt 时间线查询。
8. 如果 `VERIFYING` 超时仍然太粗，再补代理底层阶段观测。

底层代理阶段观测放在后面做。等 attempt 时间线稳定后，再看当前库能不能暴露这些阶段：

- 代理 TCP 连接。
- 代理认证。
- HTTP CONNECT。
- TLS。
- WA 握手。

## 测试策略

Armada 侧测试：

- 迁移和 mapper DbTest，验证诊断行插入和查询。
- outbox payload 单测，验证 `onlineAttemptId` 和 `previousOnlineAttemptId` 被写入。
- 事件消费者单测，覆盖合法事件、格式错误事件、重复事件、未知诊断码。
- 查询测试，覆盖账号最近时间线和代理失败查询。

协议层测试：

- 命令解析测试，验证 envelope 里的 `commandId`、`batchId` 和 payload 里的 attempt 字段进入运行时上下文。
- `AccountManager` 的 `VERIFYING` 超时诊断测试。
- `AccountManager` 的 428 连接终止诊断测试。
- 心跳失活和快速救援失败诊断测试。
- 手动离线和认证类诊断测试。
- 事件发布测试，验证 evidence 已脱敏且有长度边界。

集成检查：

- 上线一个测试账号，确认同一个 `onlineAttemptId` 能在 Armada outbox、协议日志、协议事件 payload、Armada 诊断表里看到。
- 强制制造 `VERIFYING` 超时，确认诊断码是 `VERIFY_TIMEOUT_NO_CONNECTION_UPDATE`。
- 模拟诊断落库失败，确认 `account.state_changed` 仍然能正常更新账号登录状态。

## 排查用法

以后排查账号 `252625852450` 时，流程应该变成：

1. 找到 Armada 账号行和协议账号 id。
2. 按账号 id 查询最近的 `account_online_attempt_log`。
3. 选最新的 `onlineAttemptId`。
4. 看这次 attempt 的时间线和诊断码。
5. 如果多次失败都使用同一个代理 id，检查代理分配和释放逻辑。
6. 如果诊断码是 `VERIFY_TIMEOUT_NO_CONNECTION_UPDATE`，先按“代理或 WA 连接阶段未返回明确结果”处理；如果后续已有底层代理阶段观测，再继续细分到 DNS、TCP、认证、CONNECT、TLS 或 WA 握手。

这样即使 PM2 日志滚动了、worker 重启了，也能从 Armada 持久化数据里还原问题。

## 第一期固定决策

第一期按这些决策实现：

- 新增 `account.offline_diagnosed`，不把诊断信息塞进 `account.state_changed`。
- 诊断落库和账号状态同步分离。
- `onlineAttemptId` 由 Armada 生成，不由协议层生成。
- Prometheus label 不包含 account id 或 attempt id。
- 代理底层阶段观测放到第二阶段。

