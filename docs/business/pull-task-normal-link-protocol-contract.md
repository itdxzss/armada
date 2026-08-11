# 普通群链接拉群任务 —— 协议层对接契约

> 状态：**协议端 5 条命令全部已实现**（批次 1/2/3 完成）· 2026-08-04
> 余下不属于协议端的两件事见 §13 末尾。
> 批次 1 采用 §4.1.3 的备选方案 B（`source` 分支），未采用 opaque 透传——保守优先，
> 对现有 join_task 的序列化路径零改动。实施详情见 §11、§12。
> 本文 = **从 armada / armada-protocol / whatsapp-server 三侧真代码逆向核出**的契约，所有结论带 `file:line`，非凭记忆。
> 用途：给 Web（`armada-protocol/protocol-layer`，TS/Baileys）与 Android（`whatsapp-server`，Go）两端作为实现输入。
> armada 侧已全部实现并在发命令。协议端原本 5 条命令一条都没对接，现已全部打通。
> §1 记录的是**改造前**的阻塞实证，保留作为设计依据。

---

## 0. 一句话结论

普通群链接拉群任务共下发 **5 条协议命令**、需要 **2 类结果事件**。armada 侧命令生产、payload 补全、结果消费、状态机、UNKNOWN 对账全部就绪；协议两端：

| 命令 | Web 现状 | Android 现状 |
|---|---|---|
| `group.join.requested`（管理员踩链接） | ✅ **批次 1 已实现**（原：payload 契约硬绑 `join_task`） | ✅ **批次 1 已实现**（同） |
| `contact.save.requested`（加好友） | ✅ **批次 2 已实现** | ✅ **批次 2 已实现**（新增 group-action 命令族） |
| `group.participants.requested`（邀请拉手 / 批量拉人 / 料子提权） | ✅ **批次 3 已实现** | ✅ **批次 3 已实现** |
| `group.action_result_reported`（结果事件） | ✅ **批次 2 已实现** | ✅ **批次 2 已实现** |

**好消息**：三种底层能力两端都已经有同步 HTTP 实现（见 §6），本次工作量集中在「Kafka 异步命令通道 + 结果事件发布」，不需要重新实现 WhatsApp 侧动作。

**这是纯协议端单边工作**，控端命令生产 / topic 配置 / 结果消费 / 状态机全部已就位且已逐一实证（见 §7.2）。

> 怎么读这篇：先看 §7.1 的端到端串联表建立整体印象 → 按 §8 的三个批次分工 → 实现时查 §4（命令 payload）和 §5（结果事件）的逐字段契约 → 动手前务必读 §7.5 两个坑和 §7.6 共同约定。

---

## 1. 阻塞点实证

### 1.1 Web（`armada-protocol/protocol-layer`）

- `src/commands/types.ts:37-45` — `SUPPORTED_COMMAND_TYPES` 只有 7 个类型，**不含** `contact.save.requested`、`group.participants.requested`。`parseMasterCommand` 对未知类型返回 `UNSUPPORTED_COMMAND_TYPE`（`types.ts:60-66`），master 不会投给 worker。
- `src/commands/group-join-executor.ts:219-246` — `parseGroupJoinPayload` 硬校验：
  - `joinTaskId`、`joinTaskResultId` 必须为正整数（`:222-223`）
  - `source` 必须 `=== 'join_task'`（`:230`），否则 `invalidField('source')`

  拉群发的是 `pullTaskId` / `groupExecutionId` / `actionId` + `source = 'pull_task_manager_join'` → **在第一个字段就抛错**。
- `src/events/subjects.ts:7-46` — `EVENT_TYPES` 无 `group.action_result_reported`。

### 1.2 Android（`whatsapp-server`）

- 全仓库只定义 4 个命令类型：`internal/armada/command.go:15,17`（account online/offline）、`internal/armada/message_command.go:12`（message send）、`internal/armada/join_command.go:9`（group join）。**无** contact save、**无** group participants。
- `internal/armada/join_command.go:125-165` — `validateGroupJoinCorrelation` 硬要求：
  - `joinTaskId > 0`、`joinTaskResultId > 0`（`:134,137`）
  - `source == "join_task"`（`:158`）
  - `aggregateType == "JOIN_TASK_RESULT"`（`:161`）
  - `aggregateId == joinTaskResultId`（`:164`）
- `internal/armada/doc.go:5-8` — 只有 lifecycle / message / group-join 三族 consumer。armada 已在往 `protocol.android.group-action.commands.v1` 写命令（`ProtocolAndroidCommandProperties.java:24`），**该 topic 在 Android 端无任何 consumer**。
- `internal/armada/event.go:13-23` — 事件常量无 `group.action_result_reported`。

### 1.3 armada 侧（已就绪，等生产者）

- `ProtocolGroupEventConsumer.java:38` 已声明 `group.action_result_reported`，`:98-104` 已接入 switch。
- `:112-124` 已按 `source` 分流 `pull_task_batch_add` / `pull_task_contact_save` / `pull_task_puller_invite` / `pull_task_material_admin`。
- `:286-297` `joinCorrelation` 已支持 `source = pull_task_manager_join` 的三元关联。
- 结果状态机：`ProtocolGroupActionResultAdapter.java:42-67`、`ProtocolPullTaskBatchParticipantResultAdapter.java:29-38`。

### 1.4 实际表现

任务 START 后卡在 `MANAGER_JOIN`：命令 enqueue → 动作置 `SUBMITTED` → 协议端拒绝或静默丢弃 → 永远收不到结果 → 只能靠 `PullTaskUnknownResultReconciliationService` 兜成 `UNKNOWN`。后续 4 个阶段（`MANAGER_PULLER_CONTACT` / `PULLER_INVITE` / `PULL_EXECUTION` / `MATERIAL_ADMIN`，见 `PullTaskExecutionStageRouter.java:47-67`）根本走不到。

---

## 2. 通道与 topic

```
命令方向（armada → 协议）                        结果方向（协议 → armada）

WEB 后端    → protocol.master.commands.v1  ┐
                                            ├→  protocol.group.events.v1
ANDROID 后端 → protocol.android.group-join.commands.v1      （两端共用）
             → protocol.android.group-action.commands.v1  ┘
```

| 用途 | topic | 配置项 | 来源 |
|---|---|---|---|
| Web 全部命令 | `protocol.master.commands.v1` | `PROTOCOL_MASTER_COMMANDS_TOPIC` | `application.yml:150` |
| Android 踩链接 | `protocol.android.group-join.commands.v1` | `PROTOCOL_ANDROID_GROUP_JOIN_COMMANDS_TOPIC` | `application.yml:147` |
| **Android 群动作（新）** | `protocol.android.group-action.commands.v1` | `PROTOCOL_ANDROID_GROUP_ACTION_COMMANDS_TOPIC` | `application.yml:148` |
| 结果事件（两端） | `protocol.group.events.v1` | `PROTOCOL_GROUP_EVENTS_TOPIC` | `application.yml:167` |

- 后端路由由账号冻结的 `protocolBackend` 决定：`ProtocolCommandOutboxServiceImpl.java:678,704,730,756,782,808`。
- **Kafka key 一律为 `protocolAccountId`**，保证单账号命令分区内有序。
- Android 分布式模式下 group-action topic 同样需要 node 专属改写与 Coordinator 转发（对齐 `internal/armada/config.go:53-54` 对 group-join 的处理）。

---

## 3. 命令信封（所有 5 条命令共用）

`ProtocolCommandEnvelope.java:20-28`：

```json
{
  "commandId":         "cmd_xxxxxxxx",
  "batchId":           "batch_xxxxxxxx",
  "commandType":       "group.participants.requested",
  "aggregateType":     "PULL_TASK_PULL_CALL",
  "aggregateId":       12345,
  "protocolAccountId": "acc_xxx",
  "payload":           { }
}
```

- `commandId` 全局唯一，**是协议端幂等键**。同一 `commandId` 只允许产生一次 WhatsApp 副作用；结果发布失败重放时必须回放缓存结果，不能重复执行（照抄 Web `group-join-executor.ts:78-97` 的 `claim / storeResult / markPublished` 三段式，或 Android `internal/armada/executor.go` 的同款状态机）。
- 业务重试由 armada 生成**新的 `commandId`**，协议端不需要自己重试计数。
- Web 侧 `parseMasterCommand` 已同时接受 `commandType`+`protocolAccountId` 和旧版 `type`+`accountId`（`types.ts:87-96`），无需改动。

---

## 4. 命令契约（5 条）

字段全部来自 armada 的 payload hydrator，即协议端实际收到的 wire payload。

### 4.1 管理员踩链接

| 项 | 值 |
|---|---|
| commandType | `group.join.requested` |
| aggregateType | `PULL_TASK_ACCOUNT_ACTION` |
| aggregateId | = payload.`actionId` |
| topic | Web master / Android group-join |
| 来源 | `PullTaskGroupJoinPayloadHydrator.java:181-194` |

```json
{
  "tenantId": 1, "pullTaskId": 100, "groupExecutionId": 200, "actionId": 300,
  "accountId": 400, "protocolAccountId": "acc_x", "wsPhone": "8613800000000",
  "protocolBackend": "WEB",
  "inviteCode": "AbCdEf123456",
  "attemptNo": 1,
  "source": "pull_task_manager_join"
}
```

动作：`groupAcceptInvite(inviteCode)`（Web）/ 等价原生接口（Android）。与现有 `join_task` 的执行动作**完全相同**，差别只在关联字段与 `source`。

#### 4.1.1 这不是新命令：已复用的部分与唯一的耦合点

踩链接**没有新增命令类型**。真正的"新加一个"是另一种选择——造 `pull_task.group.join.requested`，设计上明确避开了。

| 维度 | 进群任务 | 拉群踩链接 | 复用 |
|---|---|---|---|
| commandType | `group.join.requested` | 同 | ✅ `ProtocolCommandOutboxServiceImpl.java:674` vs `:700` |
| topic | master / android group-join | 同 | ✅ |
| 执行动作 | `groupAcceptInvite(inviteCode)` | 同 | ✅ |
| 结果事件类型 | `group.join_result_reported` | 同 | ✅ |
| **关联字段** | `joinTaskId` / `joinTaskResultId` | `pullTaskId` / `groupExecutionId` / `actionId` | ❌ **唯一不兼容处** |

卡住的根因：协议端把业务关联写成了**强类型字段**，硬编码在 **6 处**（实施时逐一核出，前 3 处是静态阅读发现的，后 3 处是改造过程中发现的）——

| # | 位置 | 问题 |
|---|---|---|
| 1 | Web `group-join-executor.ts:222-223` | 强制 `joinTaskId`/`joinTaskResultId` 为正整数 |
| 2 | Web `group-join-executor.ts:200-201` | `publishResult` 硬编码回显这两个字段名 |
| 3 | Android `join_command.go:134,137` | `validateGroupJoinCorrelation` 强制两个 ID 为正 |
| 4 | Android `join_event.go:62-65` | `BuildGroupJoinResultEvent` **另有一处**独立硬校验 `JoinTaskID > 0 && JoinTaskResultID > 0` |
| 5 | **两端都不发 `source`** | 今天能跑是因为控端 `joinCorrelation():286-290` 把 `source == null` 视作 `join_task`。拉群链路**必须新发 `source`**，否则结果会被路由进进群任务状态机 → 数据损坏 |
| 6 | Web `master-consumer.ts:246` | `OWNER_NOT_FOUND` 兜底的 `groupJoinFallbackPayload` 要求 `source === 'join_task'`，否则返回 null → **拉群命令在无 owner 时收不到任何结果，动作永久停在 `SUBMITTED`**。这条是隐性最深的一处 |

**关键观察：协议端从不解释这两个 ID 的含义。** `publishResult:199-206` 就是把 payload 的值原样搬进 event data，Android `BuildGroupJoinResultEvent` 同理。这是「不透明回执上下文被写成强类型字段」造成的耦合，不是业务语义冲突。

> 第 6 处给出的教训已固化为实现约束：**worker 执行器支持的每一种 `source`，master 兜底路径都必须能兜底**。两条发布路径必须共用同一份关联契约（Web 侧已抽成 `src/commands/group-join-correlation.ts`），否则漏一处就是一个静默挂起的业务动作。批次 2、3 新增 `contact.save.requested` 与 `group.participants.requested` 时同样适用。

#### 4.1.2 为什么做不到协议端零改动

**路 A：拉群塞假的 `joinTaskId`/`joinTaskResultId`，`source` 仍填 `join_task`**

→ **数据损坏。** `ProtocolGroupJoinResultRouter.java:37-40` 按 `correlation` 类型分派，`source=join_task` 会命中 `ProtocolJoinTaskGroupJoinCorrelation` → `joinTaskService.apply()` → 拿 `pullTaskId` 当 `joinTaskId` 去更新 `join_task` 的 `executed/success/failed/pending` 计数（`V007__join_task.sql:26-30`）。

**路 B：拉群真的写一行 `join_task_result`，整套复用**

三个硬障碍：
1. `join_task_result.join_task_id` 是 NOT NULL（`V007__join_task.sql:43`），得凭空造假 `join_task` 行，污染进群任务列表与看板。
2. 该表只有 `PENDING/SUCCESS/FAILED` 三态（`:47`），承载不了拉群踩链接要驱动的阶段推进 + `pull_task_group_account.membership_status` + 拉手跨任务占用判定。
3. 失败语义不同：拉群要区分「链接永久失效 → 整条执行行终止」与「管理员不可用 → 换号补位」（`PullTaskManagerJoinResultServiceImpl.java:39-43` 两套独立 code 集合），`join_task` 只有通用 `retry_enabled`/`retry_limit`。

#### 4.1.3 推荐改法：关联字段改为不透明透传（opaque）

既然协议端只是搬运，正确形态是：协议端**只校验自己执行需要的字段**（`inviteCode` / `wsPhone` / `protocolAccountId` / `protocolBackend` / `attemptNo`），业务关联字段**整块原样回显**。

| 做法 | 改动量 | 下一个业务复用踩链接 |
|---|---|---|
| **A. opaque 透传（推荐）** | Web ~30 行 + Android ~40 行 | **零改动** |
| B. 加 `source` 分支（备选） | Web ~20 行 + Android ~15 行 | 还要再改一次协议端 |

Android 贵一点是因为 `GroupJoinResultEventData`（`join_event.go:34-48`）是强类型 struct，透传要改结构，`groupJoinCommandLogFields`（`start.go:554-564`）的日志字段也要改成通用形态。

**控端两种做法都不用改**：`joinCorrelation()`（`ProtocolGroupEventConsumer.java:286-297`）本来就是先读 `data.source` 再取对应 ID，只要 opaque 块里含 `source` 就照常工作。

B 是 A 的子集，协议端想先小步验证可以先做 B，后续再收敛到 A，两者不冲突。

#### 4.1.4 硬约束：不得影响现有进群任务链路

改这块动的是**生产在跑**的代码路径。以下每条都是可测条款，缺一条不许合并。

**1. 控端侧零改动，进群任务 payload 一个字节不变**
`ProtocolGroupJoinCommandRequest` 与 `toGroupJoinOutboxRow`（`ProtocolCommandOutboxServiceImpl.java:667-689`）不得改动。协议端改造必须在「输入完全不变」的前提下做。

**2. Go 侧必须用 `json.RawMessage` 透传，禁用 `map[string]any`**
`map[string]any` 会把 JSON 数字解成 `float64`，ID 是 BIGINT，超过 2^53 时精度漂移、且可能序列化成科学计数法。控端 `longValue()`（`ProtocolGroupEventConsumer.java:366-382`）只接受 `isLong()/isInt()` 或数字字符串，**浮点形态会被直接拒绝**，表现为结果事件消费失败、动作永久挂起。用 `json.RawMessage` 原样搬字节可完全规避。

**3. join_task 场景的 event data 必须逐字段等价**
字段名、JSON 类型、存在性（含 `omitempty` 行为）与改动前完全一致。**要求加 golden JSON 断言**：同一条 `join_task` 命令，改动前后产出的 event data 序列化结果必须相同。

**4. `aggregateType` / `aggregateId` 校验只放宽、不移除**
Android `join_command.go:161,164` 现在是单值 `JOIN_TASK_RESULT` + `aggregateId == joinTaskResultId`。改成**按 `source` 查表**：`join_task` → (`JOIN_TASK_RESULT`, `joinTaskResultId`)、`pull_task_manager_join` → (`PULL_TASK_ACCOUNT_ACTION`, `actionId`)。白名单之外的 `source` 一律拒绝——**不许退化成"不校验"**。

**5. 现有执行前校验一条都不能少**
`protocolAccountId == command.accountId`（Web `group-join-executor.ts:231-233`、Android `join_command.go:142-143`）、`protocolBackend` 匹配（Web `:227` 要求 `WEB`、Android `:148` 要求 `ANDROID`）、`attemptNo > 0`（Android `:154-155`）、`wsPhone` 非空（`:145-146`）、`inviteCode` 非空（`:151-152`）。opaque 化只影响业务关联字段，执行字段的校验强度不变。

**6. 空值语义（已确认对现有链路无影响，但新链路不能依赖）**
控端 `text()`（`:415-421`）对缺失返回 `null`、对 `""` 返回 `""`；而 join_task 侧 `JoinTaskResultServiceImpl.java:208-210` 的 `safe()` 把两者都归一成 `""`。所以即使 `omitempty` 行为变化，**现有进群任务不受影响**。但拉群链路的 `requiredText` 会拒绝 `""`，协议端不得用空字符串代替字段缺失。

**7. 双解析必须保留**
Android `ParseGroupJoinCommandReference`（`join_command.go:70-108`）的「执行字段非法但任务关联可信 → 仍回写永久失败」能力对两条链路都必要。opaque 化后这个降级路径要同时覆盖两种 `source`。

**8. 回归测试门槛**
- 进群任务全链路 e2e 在改动后重跑通过
- 新增 golden 事件对比测试（约束 3）
- 新增「未知 `source` 被拒绝」测试（约束 4）
- Android 新增「BIGINT 边界值（> 2^53）透传后仍是整数形态」测试（约束 2）

### 4.2 加好友（管理↔拉手、拉手↔站台双向）

| 项 | 值 |
|---|---|
| commandType | `contact.save.requested` |
| aggregateType | `PULL_TASK_ACCOUNT_ACTION` |
| aggregateId | = payload.`actionId` |
| topic | Web master / **Android group-action** |
| 来源 | `PullTaskContactSavePayloadHydrator.java:69-73`, 记录定义 `:150-164` |

```json
{
  "tenantId": 1, "pullTaskId": 100, "groupExecutionId": 200, "actionId": 301,
  "accountId": 400, "protocolAccountId": "acc_x", "wsPhone": "8613800000000",
  "protocolBackend": "WEB",
  "contact": "8613900000000",
  "name":    "8613900000000",
  "attemptNo": 1,
  "source": "pull_task_contact_save"
}
```

- ⚠️ `contact` / `name` 当前都填**裸手机号**（无 `@s.whatsapp.net` 后缀），值为对方账号的 `account_phone`，两个字段目前同值。协议端需自行归一为 JID。
- `wsPhone` = 执行方（actor）手机号，`contact` = 被保存方（target）。

### 4.3 邀请拉手入群（管理员单人邀请）

| 项 | 值 |
|---|---|
| commandType | `group.participants.requested` |
| aggregateType | `PULL_TASK_ACCOUNT_ACTION` |
| aggregateId | = payload.`actionId` |
| topic | Web master / **Android group-action** |
| 来源 | `PullTaskPullerInvitePayloadHydrator.java:79-84`, 记录定义 `:171-186` |

```json
{
  "tenantId": 1, "pullTaskId": 100, "groupExecutionId": 200, "actionId": 302,
  "accountId": 400, "protocolAccountId": "acc_x", "wsPhone": "8613800000000",
  "protocolBackend": "WEB",
  "groupJid": "1234567890-1234567890@g.us",
  "participants": ["8613900000000@s.whatsapp.net"],
  "action": "ADD",
  "timeoutMs": 30000,
  "attemptNo": 1,
  "source": "pull_task_puller_invite"
}
```

- `participants` 恒为 **1 个元素**。
- JID 由 armada 归一好（`WhatsappJids.userJid`，`WhatsappJids.java:27-41`），协议端直接用。

### 4.4 批量拉人（站台 + 料子同批入群）

| 项 | 值 |
|---|---|
| commandType | `group.participants.requested` |
| aggregateType | `PULL_TASK_PULL_CALL` |
| aggregateId | = payload.`pullCallId` |
| topic | Web master / **Android group-action** |
| 来源 | `PullTaskBatchAddPayloadHydrator.java:89-94`, 记录定义 `:216-232` |

```json
{
  "tenantId": 1, "pullTaskId": 100, "groupExecutionId": 200,
  "pullCallId": 500,
  "accountId": 400, "protocolAccountId": "acc_x", "wsPhone": "8613800000000",
  "protocolBackend": "WEB",
  "groupJid": "1234567890-1234567890@g.us",
  "participants": ["86139...@s.whatsapp.net", "86138...@s.whatsapp.net", "..."],
  "action": "ADD",
  "timeoutMs": 30000,
  "attemptNo": 1,
  "source": "pull_task_batch_add"
}
```

- **关联字段是 `pullCallId`，不是 `actionId`**（唯一一条用 `pullCallId` 的命令）。
- `participants` 为 N 个（站台号 + 料子号混合，批量大小由 armada 的 `PullTaskBatchSizeSelector` 决定）。
- ⚠️ **必须逐成员回报结果**，详见 §5.3。

### 4.5 A/a 料子提权

| 项 | 值 |
|---|---|
| commandType | `group.participants.requested` |
| aggregateType | `PULL_TASK_MATERIAL_MEMBER` |
| aggregateId | = 料子成员 ID |
| topic | Web master / **Android group-action** |
| 来源 | `PullTaskMaterialAdminPayloadHydrator.java:80-85`, 记录定义 `:180-196` |

```json
{
  "tenantId": 1, "pullTaskId": 100, "groupExecutionId": 200,
  "actionId": 600,
  "accountId": 400, "protocolAccountId": "acc_x", "wsPhone": "8613800000000",
  "protocolBackend": "WEB",
  "groupJid": "1234567890-1234567890@g.us",
  "participants": ["8613900000000@s.whatsapp.net"],
  "action": "PROMOTE",
  "timeoutMs": 30000,
  "attemptNo": 1,
  "source": "pull_task_material_admin"
}
```

- ⚠️ **字段名叫 `actionId`，实际值是「料子成员 ID」**（`hydrate` 传入 `reference.materialId()`，`PullTaskMaterialAdminPayloadHydrator.java:82`；armada 回读时映射为 `PullTaskMaterialAdminCallback.materialId`，`ProtocolGroupActionResultAdapter.java:59-65`）。协议端只需原样回显，不要重命名。
- 执行方 `wsPhone` 是本群管理员，`participants[0]` 是被提权的料子号。

### 4.6 `action` 字段取值约定

Kafka 契约用 **大写**：`"ADD"` / `"PROMOTE"`。
注意与现有同步 HTTP 通道的小写 wire value（`GroupParticipantAction.java:7,10` → `add` / `promote`）不同，不要混用。本期只用到 `ADD` 和 `PROMOTE`。

---

## 5. 结果事件契约（2 类）

统一发到 `protocol.group.events.v1`，事件信封：

```json
{
  "eventId":  "acc_x:group.action_result_reported:cmd_xxx",
  "event":    "group.action_result_reported",
  "accountId": "acc_x",
  "workerId": "worker-1",
  "data": { }
}
```

- `data` 缺失时 armada 会回退到用信封根节点当 data（`ProtocolGroupEventConsumer.java:329-331`），但**请始终带 `data`**。
- 信封 `accountId` 必须等于 `data.protocolAccountId`，否则 armada 抛「账号关联不一致」拒绝消费（`:133-137`, `:180-184`）。
- `eventId` 建议沿用现有格式 `{protocolAccountId}:{event}:{commandId}`（`group-join-executor.ts:212`、`internal/armada/join_event.go:20`）；批量拉人逐成员事件需再拼上 `targetJid` 以保证唯一。
- **关联字段不完整时 armada 会抛异常拒绝消费**（不是静默跳过），所以必填字段一个都不能少。

### 5.1 踩链接结果 —— 复用 `group.join_result_reported`

只需把关联字段从 `joinTaskId`/`joinTaskResultId` 换成拉群三元组，其余与现有实现一致。校验见 `ProtocolGroupEventConsumer.java:237-297`。

```json
{
  "source": "pull_task_manager_join",
  "tenantId": 1, "pullTaskId": 100, "groupExecutionId": 200, "actionId": 300,
  "accountId": 400, "protocolAccountId": "acc_x",
  "commandId": "cmd_xxx", "attemptNo": 1,
  "outcome": "JOINED",
  "groupJid": "1234567890-1234567890@g.us",
  "reasonCode": null, "reasonMessage": null,
  "retryable": false,
  "timestamp": 1754300000000
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `source` | 是 | 必须 `pull_task_manager_join`；缺省或 `join_task` 走旧进群任务分支（`:286-297`） |
| `pullTaskId` / `groupExecutionId` / `actionId` | 是 | 原样回显命令 payload（`:292-295`） |
| `outcome` | 是 | `JOINED` / `ALREADY_JOINED` / `PENDING_APPROVAL` / `FAILED`，其它值拒绝（`:40-41`, `:255-258`） |
| `attemptNo` | 是 | 必须 > 0（`:249-252`） |
| `retryable` | 是 | 布尔，缺失直接拒绝（`:260-263`） |
| `groupJid` | 否 | 成功时应带上 |
| `timestamp` | 否 | epoch 毫秒，缺失按 0 处理 |

### 5.2 群动作结果 —— 新增 `group.action_result_reported`

用于 §4.2 加好友、§4.3 邀请拉手、§4.5 料子提权。校验见 `ProtocolGroupEventConsumer.java:108-165`。

```json
{
  "source": "pull_task_puller_invite",
  "operation": "PARTICIPANT_ADD",
  "tenantId": 1, "pullTaskId": 100, "groupExecutionId": 200, "actionId": 302,
  "accountId": 400, "protocolAccountId": "acc_x",
  "commandId": "cmd_xxx", "attemptNo": 1,
  "outcome": "SUCCESS",
  "targetJid": "8613900000000@s.whatsapp.net",
  "reasonCode": null, "reasonMessage": null,
  "retryable": false,
  "timestamp": 1754300000000
}
```

**`source` × `operation` 必须严格配对**，不匹配一律拒绝（`:120-127`）：

| 命令 | `source` | `operation` | `targetJid` |
|---|---|---|---|
| 加好友 | `pull_task_contact_save` | `CONTACT_SAVE` | 可空 |
| 邀请拉手 | `pull_task_puller_invite` | `PARTICIPANT_ADD` | **必填**（`:147-150`） |
| 料子提权 | `pull_task_material_admin` | `PARTICIPANT_PROMOTE` | **必填**（`:147-150`） |

| 字段 | 必填 | 说明 |
|---|---|---|
| `tenantId` / `pullTaskId` / `groupExecutionId` / `actionId` | 是 | 原样回显；料子提权的 `actionId` 即料子成员 ID |
| `outcome` | 是 | `SUCCESS` / `FAILED` / `UNKNOWN`，其它值拒绝（`:45`, `:141-145`） |
| `attemptNo` | 是 | 必须 > 0 |
| `retryable` | 是 | 布尔，缺失拒绝 |

`UNKNOWN` 的语义：动作已发出但**无法确认是否生效**。armada 收到后不会重发命令，转由 `PullTaskUnknownResultReconciliationService` 靠真实群成员事实收敛。**宁可报 `UNKNOWN` 也不要猜成 `FAILED`** —— 猜错会导致重复拉人触发风控。

### 5.3 批量拉人结果 —— `group.action_result_reported` 的逐成员形态

同一事件类型，但 `source = pull_task_batch_add`，关联字段用 `pullCallId`。armada 走独立分支（`ProtocolGroupEventConsumer.java:112-115`, `:166-210`）。

```json
{
  "source": "pull_task_batch_add",
  "operation": "PARTICIPANT_ADD",
  "tenantId": 1, "pullTaskId": 100, "groupExecutionId": 200,
  "pullCallId": 500,
  "accountId": 400, "protocolAccountId": "acc_x",
  "commandId": "cmd_xxx", "attemptNo": 1,
  "targetJid": "8613900000000@s.whatsapp.net",
  "outcome": "SUCCESS",
  "executionState": "STARTED",
  "reasonCode": null, "reasonMessage": null,
  "retryable": false,
  "timestamp": 1754300000000
}
```

- `operation` 必须 `PARTICIPANT_ADD`，否则拒绝（`:170-172`）。
- 用 `pullCallId`，**没有** `actionId`（`:176`）。
- `targetJid` **必填**（`:184`）。
- `executionState` **必填且大小写敏感**：明确 `SUCCESS/FAILED` 只能配 `STARTED`；未知结果只能配 `NOT_STARTED/UNCERTAIN`。
- ⚠️ **一个成员一条事件**。缺失事件不会再被当作整批成功或失败：通常在 60 秒结果窗口结束后，armada 对该批次最多查询一次群成员名单，逐号码确认成功或释放回待拉池；若同一拉手已收到账号级不可用结果，则立即提前核实其仍在途的批次。
- 不需要额外的「调用级完成」事件，收口由 armada 自己算。

### 5.4 `reasonCode` 语义（影响 armada 状态机分支）

`reasonCode` 请用 `ProtocolErrorCode` 的枚举名（`ProtocolErrorCode.java`），armada 对以下值有特殊处理：

| reasonCode | armada 行为 |
|---|---|
| `ACCOUNT_NOT_FOUND`、`ACCOUNT_NOT_ONLINE`、`NEED_REAUTH` | 批量拉人：标记当前拉手离线并立即触发其在途调用名单核实；已回执号码逐个收口，未开始直接释放，结果不明或缺失回调经核实后交给下一拉手 |
| `RATE_LIMITED`、`ACCOUNT_REACHOUT_RESTRICTED` | 把该拉手置为 `RISK_COOLDOWN` 冷却并立即触发其在途调用名单核实；号码仍按各自的 `outcome + executionState` 收口 |
| `INVITE_INVALID`、`INVITE_REVOKED`、`INVALID_GROUP_LINK`、`GROUP_UNAVAILABLE` | 踩链接：判定**该群链接永久失效**，整条执行行终止（`PullTaskManagerJoinResultServiceImpl.java:39-40`） |
| `ACCOUNT_NOT_FOUND`、`ACCOUNT_NOT_ONLINE`、`NEED_REAUTH`、`ACCOUNT_REACHOUT_RESTRICTED`、`GROUP_JOIN_REJECTED` | 踩链接：判定**该管理员账号不可用**，换号补位（`:41-43`） |
| 其它 / null | 批量拉人的明确失败累计次数；未知结果不累计失败次数 |

批量拉人参与者不再使用 `retryable` 决定终态：明确失败固定最多额外重试 3 次，第 4 次明确失败终态；`retryable=false` 也不能跳过前三次重试。此规则只针对批量拉人的料子号和站台号，不改变踩链接、邀请、联系人或提权动作的既有判断。

---

## 6. 两端已有的底层能力（可直接复用，不用重写 WhatsApp 动作）

### Web（`armada-protocol/protocol-layer`）

| 能力 | 现有实现 |
|---|---|
| 加好友 | `src/routes/contacts.ts:13` `POST /v1/contacts/:jid/save` |
| 拉人 / 邀请 | `src/routes/groups.ts:428` `sock.groupParticipantsUpdate(jid, participants, 'add')` |
| 提权 | `src/routes/groups.ts:464` `…'promote'` |
| 踩链接 | `src/commands/group-join-executor.ts` 全套（含幂等状态机、operation gate、超时） |

### Android（`whatsapp-server`）

| 能力 | 现有实现 |
|---|---|
| 加好友 | `api/router/router.go:108` `POST /ws/v1/contacts/add/:key` → `SyncAddContactsController` |
| 拉人 / 邀请 | `api/router/router.go:136` `POST /ws/v1/groups/members/add/:key` → `AddGroupMemberController` |
| 提权 | `api/router/router.go:138` `POST /ws/v1/groups/admin/set/:key` → `SetGroupAdminController` |
| 踩链接 | `internal/armada/join_command.go` + `join_event.go` + `join_publisher.go` + `executor.go` 全套 |

所以两端的工作都是**把已有的 service 层接到新的 Kafka 命令消费路径上，再发结果事件**。

---

## 7. 端到端串联（控端 ↔ 协议端）

### 7.0 工作量分布：topic 接线不是主要成本

参照 Android 现成的 `group.join.requested` 一族（非测试代码）：

| 文件 | 行数 | 职责 |
|---|---|---|
| `internal/armada/join_state.go` | 279 | 幂等状态机 |
| `internal/armada/join_sender.go` | 233 | 执行 + 错误码映射 |
| `internal/armada/join_command.go` | 167 | 命令解析 + 双解析 |
| `internal/armada/join_executor.go` | 129 | 编排 |
| `internal/armada/join_event.go` | 86 | 结果事件构造 |
| `internal/armada/join_publisher.go` | 62 | 发布 |
| **合计** | **956** | |

而 topic 接线只有 `config.go:36-37` 两行 + `topics.go:17` families 加一项 + `cmd/coordinator/main.go:52` 转发映射加一条。

**Web 侧甚至不需要新 topic** —— 拉群命令走的就是 master 早已消费的 `protocol.master.commands.v1`（`ProtocolCommandOutboxServiceImpl.java:730,756,782,808` 的 WEB 分支）。白名单加两行命令就能进来，然后没有任何东西能处理它。

真正的成本在四件事：**幂等状态机**、**错误码映射**、**`UNKNOWN` 判定**、**批量拉人的 1:N 扇出**。前两件有先例可抄，后两件没有。

### 7.1 五个阶段的端到端链路

每一行必须整条打通才算这个阶段可用；任何一格断开，控端对应的动作行就永久挂在 `SUBMITTED` 等 UNKNOWN 对账。

| 阶段 | 控端发起 | topic | 协议端落点 | 结果事件 | 控端消费 | 控端落库 |
|---|---|---|---|---|---|---|
| `MANAGER_JOIN` | `PullTaskManagerJoinTransactionService` → `PullTaskGroupJoinPayloadHydrator` | master / android group-join | Web `group-join-executor.ts`（改 source 分支）· Android `join_command.go`（改校验） | `group.join_result_reported` + `source=pull_task_manager_join` | `ProtocolGroupEventConsumer:237-297` → `ProtocolGroupJoinResultRouter` → `PullTaskManagerJoinResultServiceImpl` | `pull_task_account_action.action_status`、`pull_task_group_account.membership_status` |
| `MANAGER_PULLER_CONTACT` | `PullTaskManagerPullerContactTransactionService` → `PullTaskContactSavePayloadHydrator` | master / **android group-action** | Web `contact-save-executor.ts`（新）· Android contact save executor（新） | `group.action_result_reported` + `CONTACT_SAVE` | `:108-165` → `ProtocolGroupActionResultAdapter:43-50` → `PullTaskContactSaveResultServiceImpl` | `pull_task_account_action.action_status` |
| `PULLER_INVITE` | `PullTaskPullerInviteTransactionService` → `PullTaskPullerInvitePayloadHydrator` | master / **android group-action** | Web `group-participants-executor.ts`（新）· Android participants executor（新） | `group.action_result_reported` + `PARTICIPANT_ADD` + `targetJid` | `:108-165` → `ProtocolGroupActionResultAdapter:51-58` → `PullTaskPullerInviteResultServiceImpl` | 拉手 `membership_status` |
| `PULL_EXECUTION` | `PullTaskBatchAddTransactionService` → `PullTaskBatchAddPayloadHydrator` | master / **android group-action** | 同上，**1 次调用 → N 条事件** | `group.action_result_reported` + `source=pull_task_batch_add`（逐成员） | `:112-115` → `:166-210` → `ProtocolPullTaskBatchParticipantResultAdapter` → `PullTaskProtocolResultCallbackServiceImpl:135-180` | `pull_task_pull_call.call_status`、`pull_task_material_member.pull_status` |
| `MATERIAL_ADMIN` | `PullTaskMaterialAdminTransactionService` → `PullTaskMaterialAdminPayloadHydrator` | master / **android group-action** | 同上，`action=PROMOTE` | `group.action_result_reported` + `PARTICIPANT_PROMOTE` | `:108-165` → `ProtocolGroupActionResultAdapter:59-66` → `handleMaterialAdmin` | `pull_task_material_member.admin_status` |

### 7.2 控端侧：无需改动（已实证）

四个接缝逐一核过，控端不需要为本次对接改任何代码：

1. **命令能发出去** —— `ProtocolCommandPublisher.java:349-358` 的 `hydratePayload` 按 `supports(row)` 选补全器，5 个 hydrator 都是 `@Component` 已自动注册；匹配到多个抛异常、匹配不到直通引用 payload。
2. **topic 已配好** —— `application.yml:147,148,150` 三个命令 topic + `:167` 结果 topic 均已就位，`group-action-topic` 默认值与 Android `DefaultSourceTopic(FamilyGroupAction)` 拼出的名字**完全一致**。
3. **结果通道两端对齐** —— Web `src/config.ts:62` `topicGroup` 默认 `protocol.group.events.v1`；Android `configs/prod_configs_example.toml:60` `groupeventtopic` 同值；控端 `application.yml:167` 消费同一个。**三方已经对上，不用改配置。**
4. **结果消费与状态机齐全** —— 见 §1.3 与上表最后两列。

> 换句话说：**这次是纯协议端单边工作**。控端唯一可能需要动的是联调期临时调低 `PullTaskExecutionDispatchProperties` 的轮询间隔，属于调试便利，非功能缺失。

### 7.3 Web 逐文件清单（`armada-protocol/protocol-layer`）

**改（7 个文件）**

| 文件 | 改什么 |
|---|---|
| `src/commands/types.ts:7-14, 37-45` | `MasterCommandType` 与 `SUPPORTED_COMMAND_TYPES` 各加 `contact.save.requested`、`group.participants.requested` |
| `src/events/subjects.ts:7-46, 48-78` | `EVENT_TYPES` 加 `group.action_result_reported`；**同时加入 `CRITICAL_EVENTS`**（结果丢了控端动作永久挂）。`topicKindFor:108` 的 `group.*` 规则已自动路由到 group topic，无需改动 |
| `src/commands/group-join-executor.ts:219-246, 197-215` | **关联字段改 opaque 透传**（推荐，见 §4.1.3）：`parseGroupJoinPayload` 只校验执行字段，业务关联整块透传；`publishResult` 原样回显。备选是按 `source` 分支。⚠️ 必须满足 §4.1.4 全部 8 条硬约束 |
| `src/commands/worker-consumer.ts:79, 105-121` | `WorkerCommandExecutorDeps` 加 2 个 state store 字段；分发链加 2 个 `command.type` 分支 |
| `src/commands/worker-stream-consumer.ts:17, 255, 325-332` | deps 透传；`isSafePendingCommand` 加新类型（幂等状态机接管后 pending 重放安全）。优先级建议与 `group.join.requested` 同为 normal，不抢占 P0 建群 / P1 生命周期 |
| `src/commands/master-consumer.ts:134-190` | `OWNER_NOT_FOUND` 兜底：新类型也要发一条 `FAILED / ACCOUNT_NOT_ONLINE / retryable=false` 的 `group.action_result_reported`，不能静默丢 |
| `src/server.ts:153, 170, 428-441` | 装配新 Redis state store 并注入 deps（照 `RedisGroupJoinCommandStateStore` 的写法） |

**新增（4 个文件）**

| 文件 | 内容 |
|---|---|
| `src/commands/contact-save-executor.ts` | payload 解析 → `claim/storeResult/markPublished` → 调 `routes/contacts.ts:13` 已有能力 → 发结果事件 |
| `src/commands/group-participants-executor.ts` | 同上；内部按 `source` 分 3 种语义（`puller_invite` 单人 / `batch_add` **逐成员扇出** / `material_admin` promote），复用 `routes/groups.ts:428`（add）与 `:464`（promote），套 `operationGate.runGroup` |
| `src/commands/contact-save-state.ts`、`group-participants-state.ts` | 幂等状态机。建议把 `group-join-state.ts` 泛化成带 namespace 的通用实现，避免三份重复 |

**不用改**：`master-router.ts` —— 只有 `account.online.requested` 走 `assign`（`:47`），新类型天然走现有 owner 路由分支。

### 7.4 Android 逐文件清单（`whatsapp-server`）

**改（10 个代码文件 + 6 个部署模板）**

| 文件 | 改什么 |
|---|---|
| `internal/coordinator/route_key.go:15-17` | 加 `FamilyGroupAction CommandFamily = "group-action"`。`DefaultSourceTopic:24-26` 模板是 `protocol.android.%s.commands.v1`，拼出来正好等于控端默认 topic |
| `internal/coordinator/route_key.go:109` | `ExtractPhone` 的 `case FamilyMessage, FamilyGroupJoin` 加上 `FamilyGroupAction`（4 条 payload 都带 `wsPhone`） |
| `internal/coordinator/topics.go:17` | `families` 列表加第 4 个 |
| `cmd/coordinator/main.go:49-60` | `sourceTopics` map 与 `sourceTopicList` 各加一项 |
| `cmd/server/main.go:149-157` | `EnsureNodeTopics` 多传一个源 topic |
| `internal/configs/configs.go:102-104` | Kafka 结构体加 `GroupActionCommandTopic` / `GroupActionConsumerGroup` / `GroupActionConcurrency` |
| `internal/armada/config.go:36-40` | 加 `GroupActionCommands: CommandConsumerOptions{...}`，走同款 `nodeTopicOrDefault` / `nodeGroupOrDefault` |
| `internal/armada/start.go:318-323, 507-521, 396-401` | 第 4 个 consumer 注册 + `commandFamilyGroupAction` 常量 + `newGroupActionCommandHandler`（族内按 `commandType` 分发两个 executor）+ handler + 脱敏 logFields + 启动日志 |
| `internal/armada/join_command.go:125-165` | **关联字段改 opaque 透传**（`json.RawMessage`，**禁用 `map[string]any`**，见 §4.1.4 约束 2）；`aggregateType`/`aggregateId` 校验改为按 `source` 查表、只放宽不移除（约束 4）。备选是按 `source` 分支 |
| `internal/armada/join_event.go:34-48` | `GroupJoinResultEventData` 的关联字段改为透传块；join_task 场景序列化结果必须逐字段等价（约束 3，需 golden 断言） |
| `internal/armada/start.go:554-564` | `groupJoinCommandLogFields` 改成通用形态（现在硬编码 `joinTaskId`/`joinTaskResultId`），保持脱敏不回退 |
| `internal/armada/event.go:13-23, 89-95` | 加 `EventGroupActionResultReported = "group.action_result_reported"`，`EventData` 补 `source` / `operation` / `pullTaskId` / `groupExecutionId` / `actionId` / `pullCallId` / `targetJid` / `outcome` / `reasonCode` / `reasonMessage` / `retryable` |
| 6 个 toml 模板 | `configs/prod_configs_example.toml`、`deploy/configs/prod_configs.example.toml`、`deploy/node/config.template.toml`、`deploy/multinode/config.coordinator.template.toml`、`deploy/multinode/config.node.template.toml`、`deploy/coordinator/config.template.toml` |

**新增（照 `join_*` 六件套；两个 commandType 同属一族，建议共用 state 与 publisher）**

| 文件 | 内容 |
|---|---|
| `internal/armada/group_action_command.go` | 两个 commandType 的 payload 强类型 + `validateXxxCorrelation` + **双解析**（`ParseXxxCommand` / `ParseXxxCommandReference`）——执行字段非法但任务关联可信时仍要能回写永久失败 |
| `internal/armada/group_action_state.go` | commandId 幂等状态机（照 `join_state.go`） |
| `internal/armada/contact_save_sender.go` | 复用 `SyncAddContactsController` 的 service 层 + 错误映射 |
| `internal/armada/group_participants_sender.go` | 复用 `AddGroupMemberController` / `SetGroupAdminController` + 错误映射 + **批量逐成员结果拆分** |
| `internal/armada/group_action_executor.go` | `Execute` / `ExecuteRejected` 编排 |
| `internal/armada/group_action_event.go`、`group_action_publisher.go` | 事件构造与发布 |

### 7.5 两个坑

**坑 1：`topics.go` 的 families 是按下标匹配可变参数的**

`NodeTopics(nodeID string, sourceTopics ...string)`（`internal/coordinator/topics.go:15-27`）用 `families[index]` 对齐 `sourceTopics[index]`。加第 4 个 family 后，`cmd/server/main.go:149-157` 传入的 topic **顺序必须与 families 完全一致**，否则节点会去消费错的专属 topic，**且不报任何错**。这两处必须同一个提交改，并加一条断言下标对齐的单测。

**坑 2：`decision.go` 与 `IsOnlineCommand` 不要动**

`internal/coordinator/decision.go:56` 是 `if family == FamilyLifecycle {...} else { ResolveExisting }`，新 family 自动走 else 分支，语义正确（要求账号在线且有归属节点）。`route_key.go:73` 的 `IsOnlineCommand` 对非 lifecycle 直接返回 false，也正确。这两处改了反而会出问题。

### 7.6 两端共同约定

- 错误映射到 `ProtocolErrorCode` 枚举名（见 §5.4）。尤其 `ACCOUNT_NOT_ONLINE` / `RATE_LIMITED` / `ACCOUNT_REACHOUT_RESTRICTED` 三个会改变控端调度行为——映射错不会报错，只会让调度器做错决定。
- 无法确认动作是否生效时报 `outcome = UNKNOWN`，**禁止猜成 `FAILED`**。猜错会导致控端重发 → 重复拉人 → 触发风控。
- 结果事件发布失败必须回放缓存结果，不得重复执行 WhatsApp 动作。
- 批量拉人**每个成员一条事件**，一条不能少（控端靠 `hasPendingParticipants` 收口）。

---

## 8. 分批实施顺序

三批，每批可独立联调、独立上线、独立回滚。

### 批次 1：踩链接（最小改动，先验证关联字段链路）

- **不碰新 topic、不碰新命令族**，也**不新增命令类型**（见 §4.1.1），只把现有进群逻辑的关联字段改成 opaque 透传。
- Web：`group-join-executor.ts` + `subjects.ts`。Android：`join_command.go` + `join_event.go` + `start.go` + `event.go`。
- **最高风险批次**：动的是**已在生产跑的**进群任务代码路径。开工前先读 §4.1.4 的 8 条硬约束，其中约束 2（`json.RawMessage` 而非 `map[string]any`）和约束 3（golden JSON 等价断言）是最容易漏且后果最严重的两条。
- 建议做法：先只加 golden 断言把**现有行为固化**成测试，再动实现——这样"没影响现有逻辑"是测出来的，不是看出来的。
- 打通标志：`MANAGER_JOIN` 阶段推进到 `MANAGER_PULLER_CONTACT`，且进群任务 e2e 全绿。

### 批次 2：加好友（第一次引入新 topic 与新命令族）

- Web：新增 contact-save 三件（executor / state / 装配）+ `types.ts` 白名单 + `worker-consumer` 分发。
- Android：完整的 group-action 命令族骨架（topic 配置 + coordinator family + consumer + handler + state + executor + publisher），只接 contact save 一个 commandType。
- 风险点：坑 1 的下标对齐；分布式模式下 coordinator 转发要实测。
- 打通标志：阶段推进到 `PULLER_INVITE`。

### 批次 3：participants 三件（风险最高，放最后）

- 顺序：**邀请拉手（1:1）→ 料子提权（1:1）→ 批量拉人（1:N）**。前两个先把 `group.participants.requested` 的 payload 与 `action` 语义验证过，再上唯一的 1:N 扇出。
- 风险点：批量拉人的逐成员事件完整性 + `UNKNOWN` 判定。这两个错了会重复拉人触发风控，是本次对接最贵的失败模式。
- 打通标志：阶段推进到 `CLOSING`，任务能正常终态。

---

## 9. 联调验收清单

按阶段顺序逐个打通，每步都能在控端看到状态推进：

| # | 批次 | 验收点 | 控端可观测 |
|---|---|---|---|
| 1 | 1 | 踩链接：`source=pull_task_manager_join` 不再被拒，`group.join_result_reported` 带三元组回来 | `pull_task_account_action.action_status` `SUBMITTED → SUCCESS`；`pull_task_group_account.membership_status` 变 `IN_GROUP`；阶段推进到 `MANAGER_PULLER_CONTACT` |
| 2 | 1 | **老链路回归（§4.1.4 门槛）**：golden JSON 断言通过 · 进群任务 e2e 全绿 · 未知 `source` 被拒 · BIGINT > 2^53 透传后仍是整数形态 | `join_task_result.status`/`group_jid` 与改动前逐行一致；`join_task` 的 `executed/success/failed/pending` 计数无偏差 |
| 3 | 2 | 加好友：`contact.save.requested` 被消费并回 `CONTACT_SAVE` 结果 | 动作行 `SUCCESS`；阶段推进到 `PULLER_INVITE` |
| 4 | 2 | 分布式模式：配了 `[fleet].nodeid` 后 coordinator 能把 group-action 命令转发到正确节点 | 命令被目标节点消费，非归属节点不处理 |
| 5 | 3 | 邀请拉手：`PARTICIPANT_ADD` 单人结果带 `targetJid` | 拉手 `membership_status` 变 `IN_GROUP`；阶段推进到 `PULL_EXECUTION` |
| 6 | 3 | 料子提权：`PARTICIPANT_PROMOTE` 结果，`actionId` = 料子 ID | `pull_task_material_member.admin_status` 变 `ADMIN` |
| 7 | 3 | 批量拉人：N 个成员 → N 条事件全部回来 | `pull_task_pull_call.call_status` 收口；`pull_task_material_member.pull_status` 逐号码落定；阶段推进到 `CLOSING` |
| 8 | 全 | 幂等：同 `commandId` 重投 | 不产生第二次 WhatsApp 动作；控端计数不翻倍 |
| 9 | 全 | 离线场景：拉手掉线回 `ACCOUNT_NOT_ONLINE` | pull call 退回 `PLANNED` 并换拉手重排，不计失败 |
| 10 | 全 | 风控场景：回 `RATE_LIMITED` | 该拉手进 `RISK_COOLDOWN`，冷却期内不再被选中 |
| 11 | 全 | `UNKNOWN` 场景：协议端报 `UNKNOWN` | 控端**不重发命令**，转由 `PullTaskUnknownResultReconciliationService` 靠群成员事实收敛 |

排查手册与现成 SQL：`docs/operations/pull-task-normal-link-diagnosis.md`、`docs/operations/pull-task-normal-link-diagnosis.sql`。

---

## 10. 参考

- armada 设计与决策：`docs/superpowers/specs/2026-08-02-pull-task-normal-link-data-model-design.md`、`docs/adr/0001-limit-pull-task-v2-to-group-link-mode.md` ~ `0009-defer-manual-operation-audit-log.md`
- 现有协议契约基线：`docs/business/platform-protocol-contract.md`
- 命令通道决策：`docs/business/protocol-command-channel-decision-20260628.md`
- 踩链接 Kafka 命令的原始实现计划（可直接照抄结构）：`armada-protocol/docs/superpowers/plans/2026-07-16-group-join-kafka-command.md`

---

## 11. 批次 1 实施记录（管理员踩链接）

**方案**：§4.1.3 的备选 B（`source` 分支）。未采用 opaque 透传，理由是对现有 join_task 的
序列化路径零改动，风险最低；opaque 仍可作为后续收敛方向。

**验证结果**

| 仓库 | 类型检查 | 测试 |
|---|---|---|
| Web `armada-protocol/protocol-layer` | `npm run lint`（tsc --noEmit）通过 | 57 suites / **479** tests 全过（改造前 477，新增 15 减去 13 个合并进既有 describe 的计数差异） |
| Android `whatsapp-server` | `go build ./...` + `go vet ./internal/armada/` 通过 | `internal/armada` 全包通过 |

> Android 全仓库另有 `pkg/noise` 的 8 个失败，已用 `git stash` 在**干净代码**上复现，确认是
> 既有失败（Noise 握手加密），与本次改动无关。

**Web 改动**

| 文件 | 说明 |
|---|---|
| `src/commands/group-join-correlation.ts` | **新增**。关联契约单一来源：类型、`readGroupJoinCorrelation`（按 source 校验互斥字段组）、`groupJoinCorrelationEventFields`（按 source 产出事件字段，join_task 不发 source） |
| `src/commands/group-join-executor.ts` | `GroupJoinPayload` 的关联字段收进 `correlation` 判别联合；`parseGroupJoinPayload` 与 `publishResult` 改走共享契约 |
| `src/commands/master-consumer.ts` | `groupJoinFallbackPayload` 改走共享契约（修掉耦合点 6） |
| `src/commands/group-join-executor.test.ts` | 新增 `describe('executeGroupJoin with pull task correlation')`：正常发布、10 个字段的非法值拒绝、未知 source 拒绝、串台拒绝、执行动作一致 |
| `src/commands/master-consumer.test.ts` | 新增无 owner 兜底的拉群用例 + 未知 source 仍按路由失败处理 |

**Android 改动**

| 文件 | 说明 |
|---|---|
| `internal/armada/join_command.go` | 新增 `SourceJoinTask`/`SourcePullTaskManagerJoin` 常量与 `groupJoinCorrelationOf`（按 source 返回 aggregateType + 业务主键）；payload 与 reference 双解析补 3 个拉群字段；`validateGroupJoinCorrelation` 的 source/aggregateType/aggregateId 三段校验改为查表 |
| `internal/armada/join_event.go` | `GroupJoinResultEventData` 补 `Source`/`PullTaskID`/`GroupExecutionID`/`ActionID`，关联字段加 `omitempty`；`BuildGroupJoinResultEvent` 的关联校验改用 `groupJoinCorrelationOf`（修掉耦合点 4）；新增 `applyGroupJoinCorrelation` 按 source 只填一组 |
| `internal/armada/start.go` | `groupJoinCommandLogFields` 的关联字段按 source 选取，并补 `source` 便于排查 |
| `internal/armada/join_backward_compat_test.go` | **新增回归护栏**：join_task 事件 JSON 的 golden 逐字节断言、不得出现 `source`、命令解析关联落位不变、日志字段不丢 |
| `internal/armada/join_pull_task_test.go` | 新增拉群用例：命令解析、11 个字段的非法值拒绝且不泄漏取值、未知 source 拒绝、串台拒绝、事件携带 source 且不含 joinTaskId、reference 降级路径、日志字段 |

**§4.1.4 硬约束落实情况**

| 约束 | 状态 |
|---|---|
| 1 控端零改动 | ✅ 未改控端任何文件 |
| 2 禁用 `map[string]any` | ✅ 未引入透传，仍是强类型 int64 字段，不存在 float64 精度问题 |
| 3 join_task event data 逐字段等价 | ✅ `joinTaskGoldenEventJSON` 逐字节断言；`omitempty` 对 join_task 不触发（两个 ID 恒 > 0） |
| 4 aggregateType/aggregateId 只放宽不移除 | ✅ 改为按 source 查表，白名单外拒绝（有专测） |
| 5 现有执行前校验不减 | ✅ protocolAccountId 一致性、protocolBackend、attemptNo、wsPhone、inviteCode 全部保留 |
| 6 不用空串代替字段缺失 | ✅ 关联字段是数值，未使用空串语义 |
| 7 双解析保留并覆盖两种 source | ✅ `ParseGroupJoinCommandReference` 有拉群专测 |
| 8 回归测试门槛 | ✅ golden 断言、进群任务既有测试全过、未知 source 被拒；BIGINT > 2^53 一项因方案 B 不涉及透传而不适用 |

**遗留**：批次 2（加好友）、批次 3（participants 三件）未开始。Web 侧 `types.ts` 白名单与
`subjects.ts` 的 `group.action_result_reported` 均属批次 2，本次未改动。

---

## 12. 批次 2 实施记录（加好友）

首次引入 `group.action_result_reported` 事件与 Android 第 4 个命令族。

**验证结果**

| 仓库 | 类型/编译 | 测试 |
|---|---|---|
| Web `protocol-layer` | `tsc --noEmit` 通过 | 58 suites / **503** tests 全过（批次 1 后为 479） |
| Android `whatsapp-server` | `go build ./...` + `go vet ./...` 通过 | 除既有 `pkg/noise` 失败外全过 |

**Web 改动**

| 文件 | 说明 |
|---|---|
| `src/commands/pull-task-action.ts` | **新增**。账号动作共享契约：outcome/operation 类型、三元关联、结果类型、`pullTaskActionEventData`（事件 data 组装）、`pullTaskActionEventId`、`userJid`（裸手机号归一）。批次 3 的 participants 三件直接复用 |
| `src/commands/pull-task-action-state.ts` | **新增**。commandId 级 Redis 幂等状态机；过期 PROCESSING 收敛为 **UNKNOWN**（不是 FAILED） |
| `src/commands/contact-save-executor.ts` | **新增**。payload 校验 → claim/store/publish → 调 `sock.addOrEditContact` |
| `src/commands/types.ts` | 白名单加 `contact.save.requested` |
| `src/events/subjects.ts` | `EVENT_TYPES` + `CRITICAL_EVENTS` 加 `group.action_result_reported`（`topicKindFor` 的 `group.*` 规则自动路由，无需改） |
| `src/commands/worker-consumer.ts` | socket 接口加 `addOrEditContact`、deps 加状态存储与超时、分发链加分支、装配函数 |
| `src/commands/worker-stream-consumer.ts` | deps 透传 + `isSafePendingCommand` 加新类型 |
| `src/commands/master-consumer.ts` | **`OWNER_NOT_FOUND` 兜底加 contact.save 分支**（批次 1 教训的直接应用） |
| `src/server.ts` | 装配 `RedisPullTaskActionCommandStateStore` |

**Android 改动**

| 文件 | 说明 |
|---|---|
| `internal/armada/group_action_command.go` | **新增**。命令族解析：按 commandType 查 spec（source / aggregateType / 执行字段校验），双解析，关联与执行字段分离 |
| `internal/armada/group_action_state.go` | **新增**。Redis 幂等状态机；过期 PROCESSING 收敛为 UNKNOWN |
| `internal/armada/group_action_event.go` | **新增**。`group.action_result_reported` 构造，source × operation 配对，outcome 白名单 |
| `internal/armada/group_action_publisher.go` | **新增**。独立 Kafka writer 发到 `protocol.group.events.v1` |
| `internal/armada/contact_save_sender.go` | **新增**。原生 `SyncAddContacts` + `SendCreateContact` 两步，错误映射 |
| `internal/armada/group_action_executor.go` | **新增**。按 commandType 分派 sender；缺 sender 报错而非静默丢弃 |
| `internal/coordinator/route_key.go` | 加 `FamilyGroupAction`；`ExtractPhone` 纳入该族 |
| `internal/coordinator/topics.go` | 抽出 `commandFamilyOrder()` 作为可变参数的位置契约唯一定义处 |
| `cmd/coordinator/main.go`、`cmd/server/main.go` | 源 topic 映射与 `EnsureNodeTopics` 顺序 |
| `internal/configs/configs.go`、`internal/armada/config.go`、`options.go`、`consumer_pool.go`、`start.go` | 配置字段、consumer 注册、handler、脱敏日志 |
| 6 个 toml 模板 | 新增 topic / consumer group / concurrency |

**两个实现决策**

1. **新配置项可缺省**。`internal/armada/config.go` 为 group-action 的 topic / consumer group / concurrency 提供默认值（topic 回落到与控端一致的 `coordinator.DefaultSourceTopic`），**升级到本版本的现有部署无需先改配置就能启动并消费**。模板已更新供显式配置。
2. **坑 1 已加断言守护**。`internal/coordinator/topics_order_test.go` 固定 `commandFamilyOrder()` 与 `NodeTopics` 的下标对应关系，并断言 group-action 默认 topic 与控端 `application.yml` 一致。

**待控端确认的一个缺口**

`PullTaskUnknownResultReconciliationService:106-109` 把 `membershipAction` 定义为 `!SAVE_CONTACT`，因此**加好友动作拿不到群成员事实来收敛**；而对账只从 `SUBMITTED` 转出（`:117-122`），不处理 `UNKNOWN`。
协议端按契约在结果不可确认时上报 `UNKNOWN`（超时、原生报错、PROCESSING 过期均如此），但控端目前没有把 `SAVE_CONTACT` 的 `UNKNOWN` 收敛回终态的路径。
协议端未擅自改成 FAILED——那会让控端按"确定失败"换号重做。**建议控端补一条 SAVE_CONTACT 的 UNKNOWN 收敛逻辑**，或确认由人工介入处理。

---

## 13. 批次 3 实施记录（participants 三件）

一个 `group.participants.requested` 命令类型服务三种 source，含唯一的 1:N 扇出。

**验证结果**

| 仓库 | 类型/编译 | 测试 |
|---|---|---|
| Web `protocol-layer` | `tsc --noEmit` 通过 | 59 suites / **527** tests 全过（批次 2 后为 503） |
| Android `whatsapp-server` | `go build ./...` + `go vet ./...` 通过 | 除既有 `pkg/noise` 失败外全过 |

**三个结构性差异及处理**

1. **批量拉人用 `pullCallId`，其余用 `actionId`。** 两端都把关联主键做成按 source 查表：Web 的
   `PullTaskActionCorrelation` 两个键可选、`correlationKeyField` 只输出实际使用的那个；Android 用
   `correlationKey` 类型 + `correlationValue()`。事件里另一个键靠 `omitempty` 完全不出现，
   避免控端读到 0 值关联。**并且互斥校验是双向的**——批量拉人带了 `actionId` 会被拒绝，反之亦然。
2. **1 次调用要发 N 条事件。** 状态机从"存 1 个结果"改成"存结果列表"（Web
   `storeResults`、Android `StoreResults`），否则发布中途失败后的重放会漏发成员事件，
   控端那次 pull call 永久停在 `SUBMITTED`。批次 2 的加好友同步改为长度 1 的列表，保持一套代码。
3. **料子提权的 payload 字段叫 `actionId`、值是料子 ID。** 协议端只原样回显，
   两侧代码注释都写明了这点，避免后人"修正"成 materialId。

**逐成员结果的判定规则（两端一致）**

| 情形 | 结论 |
|---|---|
| 回执 `Err`/`status` 为空 | `SUCCESS` |
| 回执带 403 / 409 / 408 / 401 / 412 / 404 / 429 | `FAILED` + 对应 `ProtocolErrorCode` |
| **该成员没有回执** | `UNKNOWN`——不默认成功，否则控端会把号码记成已入群 |
| 整体超时 | 每个成员各一条 `UNKNOWN`，保证结果数与成员数一致 |
| 账号离线 / 限流等整体失败 | 每个成员同一结论 |

单目标动作若只回一条不带标识的回执，按位置取用（Baileys / 原生回执的标识字段跨版本不稳定）；
批量拉人则必须精确匹配，宁可报 `UNKNOWN` 也不能把回执错配到另一个号码上。

**Web 改动**

| 文件 | 说明 |
|---|---|
| `src/commands/group-participants-executor.ts` | **新增**。三种 source 的解析、执行、逐成员结果映射与发布 |
| `src/commands/pull-task-action.ts` | 关联支持双主键；`pullTaskActionEventId` 支持 targetJid 后缀；**新增 `participantsSourceSpec`** 作为 source 契约唯一定义处（executor 与 master 兜底共用） |
| `src/commands/pull-task-action-state.ts` | 单结果 → 结果列表 |
| `src/commands/contact-save-executor.ts` | 适配结果列表；eventId 统一带 targetJid |
| `src/commands/types.ts` | 白名单加 `group.participants.requested` |
| `src/commands/worker-consumer.ts` | socket 加 `groupParticipantsUpdate`、分发链加分支、装配函数 |
| `src/commands/worker-stream-consumer.ts` | pending 恢复白名单加新类型 |
| `src/commands/master-consumer.ts` | **`OWNER_NOT_FOUND` 兜底加 participants 分支，批量拉人逐成员回写** |

**Android 改动**

| 文件 | 说明 |
|---|---|
| `internal/armada/group_participants_sender.go` | **新增**。原生 `AddGroupMember` / `CreateGroupAdmin`，逐成员回执映射 |
| `internal/armada/group_action_command.go` | spec 从"按 commandType"改成"按 (commandType, source)"查表；payload 补 `pullCallId`/`groupJid`/`participants`/`action`；关联主键双向互斥校验 |
| `internal/armada/group_action_event.go` | operation 由 spec 决定；关联主键二选一 + `omitempty`；**新增 `BuildGroupActionResultEvents`** 强制结果数与成员数一致 |
| `internal/armada/group_action_state.go` | 单结果 → 结果列表 |
| `internal/armada/group_action_executor.go` | 多结果 + 逐条发布；超时按成员数展开 UNKNOWN |
| `internal/armada/contact_save_sender.go` | sender 接口统一为 `SendAll`（多结果） |
| `internal/armada/start.go` | 注册 participants sender；handler 接受新命令类型 |

**协议端至此完成。余下两件不属于协议端：**

1. **控端 `SAVE_CONTACT` 的 `UNKNOWN` 收敛缺口**（见 §12 末尾）。批次 3 没有引入新的同类缺口——
   participants 三件都是 membership 动作，`PullTaskUnknownResultReconciliationService` 能靠群成员
   事实收敛它们的 `UNKNOWN`。只有加好友这一种动作缺路径。
2. **§9 的联调验收清单**需要真实 Kafka + WhatsApp 账号环境，无法在开发环境完成。
   建议按 §8 的批次顺序分批联调，每批用 §9 对应行的控端可观测项作为通过标准。
