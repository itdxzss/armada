# 通讯录快照推送 详细设计

- 日期：2026-08-29
- 状态：**设计草案**
- 范围：把账号通讯录从「armada 按需 HTTP 拉」改为「协议层定期推全量快照」，并修复现有投影的三处漏采
- 上游文档：
  - 通讯录营销总设计 `docs/superpowers/specs/2026-08-28-contact-marketing-replication-design.md`
  - 交接状态 `docs/superpowers/plans/2026-08-29-contact-marketing-handoff.md`

---

## 0. 一句话结论

现在这条链路有两个独立的病：**取数是漏的**（Web 投影漏了批量来源、丢了 LID 号），**时间是假的**（`last_synced_at` 记的是"armada 什么时候拉的"，不是"数据有多新"）。

两个病同一个药：**协议层强制一次全量 app-state 快照，攒成一份带真实截止时间的完整联系人集，推给 armada**。armada 沿用现有的整批替换落库，删除随之收敛。

---

## 1. 为什么现在这套不行

### 1.1 Web 投影漏采（三处，均已在代码中确证）

`protocol-layer/src/worker/contact-store-bridge.ts:12` 订阅三个事件：

```ts
const CONTACT_EVENTS = ['contacts.set', 'contacts.upsert', 'contacts.update']
```

对照 Baileys `7.0.0-rc11` 的 `lib/Types/Events.d.ts`：

| 订阅项 | 实际情况 |
|---|---|
| `contacts.set` | **v7 中不存在该事件**，空订阅 |
| `contacts.upsert` | 存在，app-state 的 `contactAction` 走这里 |
| `contacts.update` | 存在，但只携带 `notify` / `verifiedName` |
| **未订阅** | **`messaging-history.set` 带 `contacts: Contact[]`**，history sync 的批量联系人从这里来 |

**LID 号被静默丢弃**：v7 的 `lidContactAction`（`lib/Utils/chat-utils.js:833`）发出的 `id` 是 `@lid` 而非 `@s.whatsapp.net`，而 `contact-store.ts` 的 `normalizeContactJid` 对非 `@s.whatsapp.net` 一律 `return null`。payload 里其实带 `phoneNumber` 字段可用。WhatsApp 正在向 LID 迁移，这部分号目前全丢。

### 1.2 时间字段是假的

`protocol-layer/src/routes/contacts.ts:20` 回的是 `syncedAt: Date.now()` —— 响应时刻，不是数据新鲜度。armada 的 `WebContactListAdapter` 解析了它，然后 `AccountContactSyncServiceImpl` **从未读取**，落库用的是自己的 `clock`。安卓 adapter 直接硬写 `null`。

后果：一个号连着 3 天没收到任何联系人事件，armada 去拉，拿到 3 天前的数据，盖上 `now` 的戳，然后认为它 24 小时内新鲜。**TTL 在量错误的东西。**

### 1.3 增量事件表达不了删除

`chat-utils.js:206` 解码 app-state mutation 时：

```js
onMutation({ syncAction, index })   // operation 没有传下去
```

`operation`（SET / REMOVE）只喂给了 LTHash 校验器，`processSyncAction` 收不到。`processContactAction` 注释写着 "Always emit contacts.upsert"，事件类型里也没有 `contacts.delete`。

**结论：光靠增量事件，数据只增不减。** 号主删掉的联系人会永久留在 armada，任务照发。

---

## 2. 关键机制：强制全量快照（已验证到代码级）

### 2.1 联系人所在的 collection

`chat-utils.js:500-508`：`contact` 类 patch 的 `type` 是 **`critical_unblock_low`**，index 为 `['contact', jid]`。

反向核对 Baileys 的出站映射表，**只有 `contact` 一种 action 写入该 collection**。因此重放这个 collection 不会误触其它业务的 mutation。

### 2.2 强制全量的触发条件

`Socket/chats.js:454`：

```js
return_snapshot: (shouldForceSnapshot || !state.version).toString()
```

**存储的 version 为 0/空时，服务端返回全量快照而非增量 patch。**

### 2.3 全量快照会重放每一条

`chat-utils.js:301`：

```js
const areMutationsRequired = typeof minimumVersionNumber === 'undefined' || newState.version > minimumVersionNumber
```

`minimumVersionNumber` 来自 `initialVersionMap[name]`，而它**只在存量 state 存在时才赋值**（`chats.js:433-438`）。清掉版本号后该值保持 `undefined` → `areMutationsRequired` 恒真 → 快照中每条记录都进 `mutationMap` → 每个联系人 emit 一次 `contacts.upsert`。

### 2.4 状态可干净重建

快照恢复后 `chats.js:484` 立即 `authState.keys.set({'app-state-sync-version': {[name]: newState}})`，版本号从快照重新建立。中途失败最坏结果是下次再全量一遍，**不会损坏 LTHash 状态**。

### 2.5 安卓侧同样可行

`internal/service/node/processor/iq.go:1442`：

```go
isSnapshot := false
if version == 0 { isSnapshot = true }
...
collectionNode.Attributes.AddAttr("return_snapshot", isSnapshot)
```

且已为 `critical_unblock_low` 特判 `order=0`。安卓的 `appstate.Mutation` 结构体**还带 `Operation` 字段**（SET/REMOVE），能力强于 Baileys —— 但本方案统一走快照，不依赖该差异。

---

## 3. 目标架构

```
WhatsApp 服务端
   │  强制全量 app-state 快照（critical_unblock_low, version=0）
   ▼
协议层（Web / Android 各自实现）
   │  ① 清版本号 → 强制 resync
   │  ② 在一份干净的 store 里收集本轮 contacts
   │  ③ 攒成完整快照，分片
   ▼  Kafka: account.contacts_reported
armada
   │  按 snapshotId 整批替换 account_contact
   │  synced_at = 协议给的 snapshotCutoff（真实时间）
   ▼
通讯录营销任务启用时直接读快照，不再同步拉取
```

### 3.1 触发时机

**只自动推，无命令通道。** 协议层自行决定：

1. 账号上线且 history sync 完成后推一次
2. 之后按周期强制 resync 并推（默认 24h，配置项）

armada 不下命令、不拉取。任务启用时读库里已有的快照。

### 3.2 为什么不转发增量事件

强制快照本来就把新增、改名、删除**一次全带上**。单独再维护一套增量转发，买到的只是"新增和改名早几小时到"，代价是多一套写入路径加乱序去重。**不做。**

---

## 4. 事件契约

### 4.1 `account.contacts_reported`

照搬 `account.groups_reported` 的快照契约（`account-manager.ts:236` 的 `GroupSnapshotContract`）：

```
snapshotId        string   同一逻辑快照的稳定标识
queryStartedAt    string   开始拉取时间（ISO8601）
snapshotCutoff    string   快照截止时间（ISO8601）← 这就是真实的 synced_at
snapshotComplete  boolean  本快照是否完整
chunkSeq          int      分片序号，0 起
chunkCount        int      分片总数
totalCount        int      本快照联系人总条数（跨全部分片）
contacts          array    本分片的联系人
```

单个联系人：

```
phone         string  不带加号的纯数字
jid           string  规范用户 JID
fullName      string?
firstName     string?  Web 恒为 null（Baileys 无此概念）
pushName      string?
businessName  string?
```

### 4.2 分片

联系人量可能到数千条，整账号一条消息会撞 Kafka 单消息上限 —— 这个教训 `account-manager.ts:2529` 已经用群成员踩过一次，注释在那儿。

**分片规则**：每片最多 500 条。全部分片共享同一个 `snapshotId` 与 `snapshotCutoff`。

### 4.3 Topic 与消费

**独立 topic**：`protocol.account.contact-sync.events.v1`，协议层 `subjects.ts` 新增 topic kind `accountContactSync`。

不复用账号状态 topic 的原因：快照是大消息。仓库既有做法就是把大消息事件隔离 —— `account.groups_reported` 走独立的 `protocol.account.group-sync.events.v1`，且消费端带 `max.poll.records=1`（`ProtocolAccountEventConsumer:159`）；`NormalGroupCreationKafkaProperties` 的注释也写了"各使用独立 topic，避免与其它协议业务共享消费容量"。联系人快照同理。

armada 侧新建 `ProtocolAccountContactEventConsumer`，同样 `max.poll.records=1`，分发给 `AccountContactsReportedSink`。

安卓侧 Go 已在推 `account.state_changed` / `account.group_membership_changed`，加这个事件是现成范式。

---

## 5. armada 侧落库

### 5.1 整批替换，但删除要有防护

沿用现有语义（P2 已实现）：分批 upsert → 扫掉 `synced_at` 更早的残留行。变化有两点：

1. **`synced_at` 用协议给的 `snapshotCutoff`**，不再用 armada 的 `now`
2. **`deleteStale` 由"收齐"触发，不由"最后一片"触发**

**收齐判据（每一片处理完都执行一次）**：

```
upsert 本片 → 统计 account_contact 中 synced_at = snapshotCutoff 的行数 n
n == totalCount → 快照收齐：执行 deleteStale(synced_at < snapshotCutoff)，sync_status = SUCCESS
n <  totalCount → 还没收齐：只 upsert 不删，sync_status = SYNCING
```

**为什么不用"最后一片"触发**：Kafka 不保证分片顺序。若末片先到，按"末片触发"的写法会做一次计数不足的判定，之后再无触发点，`deleteStale` 永远不会执行，陈数据永久滞留。改成每片都判"是否收齐"后，无论到达顺序如何，收齐的那一刻自然触发，且天然幂等——重复投递同一片不会让计数虚高（唯一键 upsert）。

**丢片的结果**：`n` 永远达不到 `totalCount`，`deleteStale` 不执行，状态停在 `SYNCING`，下一轮快照（新的 `snapshotCutoff`）重来。宁可留几条脏数据，也不能因为丢片把号主的通讯录删掉一半。

### 5.2 `snapshotComplete = false` 时

协议自己判定快照不完整（例如强制 resync 中途超时）时，**只 upsert 不删除**，`sync_status = PARTIAL`。

### 5.3 `sync_status` 取值扩充

V157 建表时的取值是 `NEVER / SYNCING / SUCCESS / FAILED`，本期新增 `PARTIAL`。列类型是 `VARCHAR(16)` 无需改类型，但**列注释要跟着改**，因此仍需一条 Flyway 迁移（只改 COMMENT）。

### 5.4 计数回写

不变：`account_contact_sync` 的 `contact_num/named_num/mutual_num`，以及 `account_state` 的 `contact_named_num/contact_mutual_num`。

`is_mutual` 仍恒为 0（两套协议都不暴露双向好友标记，见总设计 §5.3），归一化器里那处注释保留。

---

## 6. 协议层改造

### 6.1 Web / Baileys

**投影修复**（`contact-store.ts` / `contact-store-bridge.ts`）：

1. 去掉不存在的 `contacts.set` 订阅
2. 增订 `messaging-history.set`，取其 `contacts` 字段
3. `normalizeContactJid` 支持 LID：`@lid` 的 id 用 payload 的 `phoneNumber` / `pnJid` 求号码；两者都没有才丢弃
4. `AccountContactStore` 增加 `rebuild()` 语义：强制 resync 期间写入一份干净 store，完成后整体替换

**强制快照流程**：

```
1. 记 queryStartedAt
2. 清 authState.keys 中 critical_unblock_low 的 app-state-sync-version
3. 开一份干净 store，把 contacts 事件临时导向它
4. await sock.resyncAppState(['critical_unblock_low'], true)
5. 等事件 flush（见 6.3）
6. 记 snapshotCutoff，替换主 store，分片推送
```

### 6.2 Android / Go

1. `FilterContacts` 之外新增全量快照采集：`BuildIqGetAppStatePatch('critical_unblock_low', 0)`
2. 收集本轮 `ContactEntry`，攒成快照
3. 复用现有 Kafka 生产者推 `account.contacts_reported`

安卓的联系人已落 `wa_contacts` 表，快照可直接从表里读（表有 gorm 的 `UpdatedAt`），也可从本轮 mutation 收集 —— **以本轮 mutation 为准**，因为表是累积的、同样只增不减。

### 6.3 完成时机（Web 侧的已知毛刺）

`resyncAppState` 被 `ev.createBufferedFunction` 包装（`event-buffer.js:141`），事件在其 resolve **之后约 100ms** 才 flush。因此不能 await 完立即读 store。

**处理**：await 之后等待事件静默 —— 记录最后一次 `contacts.upsert` 的时刻，连续 500ms 无新事件即认为重放结束。上限 60s 兜底，超时则标 `snapshotComplete = false`。

---

## 7. 退役的代码

改造完成后以下路径不再有调用方，**本期一并删除**，不留死代码：

| 位置 | 说明 |
|---|---|
| `AccountContactSyncService#syncNow` / `#syncIfStale` | 拉取入口，无调用方 |
| `AccountContactSyncServiceImpl` | 整体重写为事件驱动的落库服务 |
| `AccountContactOnlineHook` + `AccountStateChangedSinkAdapter:83` 的调用 | 上线后拉取，协议自己会推 |
| `ContactListPort` + `WebContactListAdapter` + `AndroidNativeContactListAdapter` | 拉取端口与两个 adapter |
| `AccountContactSnapshot.syncedAt` | 死字段（协议给的是 `Date.now()`，从未被消费） |

**保留**：协议层的 `GET /v1/accounts/{id}/contacts` 与 Go 的 `POST /ws/v1/contacts/list/{key}` —— 排查用，不删。

---

## 8. 任务启用时的行为变化

`ContactTaskExpansionService` 当前逐账号调 `syncIfStale`。改造后：

1. 不再同步拉取，直接读 `account_contact_sync` 与 `account_contact`
2. 快照缺失（从未推过）→ 该号写 `SKIPPED`，`need_send_num = 0`
3. 快照过期（`snapshotCutoff` 早于 TTL）→ 同样 `SKIPPED`
4. `sync_status = PARTIAL` 的快照**可以用**（数据是全的，只是可能多几条已删的）

宁可少发，也不拿陈数据发。号下次上线协议会自动推，下一个任务就能用。

---

## 9. 分期

| 期 | 内容 | 可独立验证 |
|---|---|---|
| **S1** | Web 投影修复（LID、`messaging-history.set`、去掉死订阅） | 是，纯投影单测 |
| **S2** | 事件契约 + Web 强制快照与推送 | 是，协议层单测 |
| **S3** | armada 消费落库（含丢片防护）+ 退役拉取路径 | 是，Mockito + XML 契约测试 |
| **S4** | Android 强制快照与推送 | 是，Go 单测 |

S1 → S2 → S3 有依赖；S4 可与 S2/S3 并行。

---

## 10. 明确不做

- **不转发增量联系人事件**（理由见 §3.2）
- **不做命令通道**：armada 不能主动要快照，只能等推
- **不动 `is_mutual`**：两套协议仍不暴露双向好友标记
- **不删协议层的联系人 HTTP 接口**：留作排查

---

## 11. 待真机验证

| # | 项 | 影响 |
|---|---|---|
| R1 | 服务端是否真的对 `critical_unblock_low` 返回全量快照，返回量多大 | 决定分片大小是否够用；若量极大需要调整 |
| R2 | 频繁强制 resync 是否触发风控 | 决定 TTL 下限；24h 是保守猜测，无实测依据 |
| R3 | 事件静默 500ms 的判据在真实网络下是否够 | 太短会截断快照（标记为不完整，不会误删，但会浪费一轮） |
| R4 | LID 联系人在真机上的占比 | 衡量本次修复实际挽回了多少号 |

R1、R2 是**上线前必须验的**：前者决定方案可行性，后者决定频率。R3、R4 可上线后观察。

验证结论回填到本文 §11 与总设计的对应位置。
