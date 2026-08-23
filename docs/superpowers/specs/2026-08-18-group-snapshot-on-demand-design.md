# 群快照按需查询与邀请码同步设计（重写版）

日期：2026-08-18
状态：方案待实施
涉及仓库：`armada`、`armada-protocol`、`whatsapp-server-feature-android-zhuan`

本文取代 `2026-08-16-group-snapshot-kafka-sync-design.md`。那一版的现状基线已过时，
提出的多数"新建"能力在两端协议层均已存在。本文按 2026-08-18 实际代码重新设计，
**只补真实缺口，不重建已有链路**。

---

## 1. 结论先行

控端需要主动获取群资料/邀请码时（人工刷新、异常修复、邀请码换号重试），通过 Kafka 下发
**单群命令**，协议端复用**已有的单群查询能力**执行，结果走**已有的事实事件**回流，
另有一条**只带结算语义的新事件**用于把命令收口。

七条决定：

1. **首次建档不新增任何命令**——直接复用现有 `account.groups_sync.requested`（账号级全量对账，
   Web/安卓两端都已实现）。100 个账号发 100 条命令即可覆盖全部群，比按群派发 500 条更省。
2. 新增**单群**命令 `group.snapshot.requested`，只服务"指定账号查指定群"的场景。
3. **群资料事实仍走 `group.profile_reported`**，只在协议端给它补一个可选 `commandId`。
   不新建资料事件，避免第二个写入口。
4. **邀请码事实仍走 `group.invite_link_changed`**，同样补可选 `commandId`。
5. 新增 `group.snapshot_result_reported`，**只承载 commandId / scope / outcome / errorCode**，
   不重复携带任何群业务字段。事实与结算彻底分离。
6. 不新建 topic、不新建 Outbox 表、**不做持久化的同群折叠**（去重只在派发时内存内做，先观测再决定）；
7. 安卓本期补两件事：`group.profile_reported` 发布（数据已在手，只是事件契约丢弃了）、
   邀请链接读取改用只读 MEX，HTTP 与 Kafka 命令共用同一实现；资料解析层不改动。

### 修订记录

- **2026-08-18 修订一**：删除 `peer_task_id` + `WAITING_PEER` + 折叠派发 + 广播结算的整套机制
  （为未量化的问题引入的复杂度，见 §9.2）；安卓资料上报从第 9 刀提到第 1 刀；
  补 §13.1 成员 `owner`/`role` 映射（原稿遗漏，会导致安卓来源群主恒为 0）；
  补 §12 校验失败必须发结算、错误归一禁止匹配英文文案两条约束。
- **2026-08-23 修订二**：test2 真实日志确认 Android 旧 `w:g2 <invite/>` 查询收到
  WhatsApp `410 gone`，而同群 metadata 与管理员身份均正常。Android 邀请码读取改用只读
  MEX `fetchMexGroupInviteCode`；`400/410` 只归一为 `GROUP_INVITE_LINK_UNAVAILABLE`，
  不再误报整群 `GROUP_UNAVAILABLE`，也不自动创建、撤销或重置邀请链接。

---

## 2. 为什么重写 0816 版

逐条核实 0816 版的现状描述，与 2026-08-18 代码对照：

| 0816 版说法 | 实际代码 | 结论 |
|---|---|---|
| §3.2「主动查询仍是同步 HTTP，控端无法异步发起」 | `worker-consumer.ts:604` → `account-manager.ts:729 reconcileParticipatingGroups()`，由 Kafka 命令 `account.groups_sync.requested` 触发，强制读完整 metadata | **已存在，说法作废** |
| §8.1 新建 metadata 结果块（subject/description/announceOnly/成员等） | `account-manager.ts:3113 groupProfileReported()` 已发布全部同名字段，另有 `fieldMask`、`membersComplete` | **重复造轮子** |
| §8.2 `snapshotComplete` | 已有同义字段 `membersComplete` | 改用既有命名 |
| §12.1 Web 新建 executor 做"查询+完整解析" | `readGroupMetadata()`（单群，含读后抑制窗口）+ `groupFetchAllParticipating()`（全量）+ 完整解析映射均已存在 | **只缺命令入口** |
| §12.2 安卓新建 executor 做"完整解析" | `GetAllGroup(true)` / `GetGroupMember(jid)` 均已实现，`GroupInfo` 含完整 `Participants` 与全部群设置 | **只缺事件带成员** |
| §19「安卓可复用现有 commandId 结果状态存储」 | `internal/armada/join_state.go`、`group_action_state.go`、`message_state.go`、`lifecycle_inbox.go` 均在 | **说法正确，予以保留** |
| §20.1「安卓群成员查询复用 group-action topic」 | 安卓 group-action 仅 4 个命令类型，无成员查询 | **事实错误** |
| §20.3「安卓是否返回完整成员待确认」 | 能取到（`entity.go:37 GroupInfo.Participants`），但 `event.go:37 ReportedGroup` 注释明写"不携带 participant 列表" | **是契约选择，非能力缺失** |
| §6「安卓与 Web 的 group event topic 命名不一致」 | 两端默认值同为 `protocol.group.events.v1` | 代码默认一致；差异仅在部署文件 |
| §629「禁止覆盖 V121/V122」 | 设计时最新为 **V127**（`V127__group_profile_field_versions.sql`） | 集成后迁移使用 **V129** |
| §1「按 `tenantId + groupJid` 去重」/ §9.1「表唯一键为 `tenantId + groupLinkId`」 | 表唯一键确为 `(tenant_id, group_link_id)`（`V098:263`）；而 `group_link` 唯一键是 `(tenant_id, link_url)`（`V003:31`），`group_link_preview.group_jid` 上只有**非唯一**索引（`V010:63`） | **同一 group_jid 可对应多条 group_link，去重口径必须显式定义（见 §9）** |

另有两处 0816 版未察觉的既有能力：

- **分档查询**（`account-manager.ts:2117`，提交 `b709993`）：账号上线时按 `groupBaselineReady` 决定走
  轻量群清单还是完整 metadata；`dirty` 与控端显式对账用 `forceFullMetadata` 绕过分档。
  批量新号首次上线的流量已有一层防护。
- **群变更直接投影**（提交 `32e2232` / `de192af5` / `6b550f5f` / `02ffc7cc`）：Web 侧 `groups.update`
  与成员变更已直接投影为 `group.metadata_updated` / `group.participant_changed`，不再回查 metadata。

---

## 3. 已核实的代码现状

### 3.1 控端 armada

| 项 | 位置 | 事实 |
|---|---|---|
| Outbox | `V013__protocol_command_outbox.sql` + `V046`（补 `protocol_backend`） | 列含 `command_type/aggregate_type/aggregate_id/kafka_topic/kafka_key/protocol_account_id/protocol_backend/payload_json/status/retry_count` |
| Outbox 派发器 | `platform/kafka/dispatch/ProtocolCommandDispatcher.java:28` | 事务提交后异步投递 |
| 自动快照任务表 | `V098__group_list_history_metadata.sql:245-267` | `UNIQUE KEY uq_group_metadata_sync_task (tenant_id, group_link_id)`；已有 `execution_account_id/attempt_count/lease_until/rerun_requested` |
| 人工批量任务 | `V112__group_batch_refresh_task.sql` | `group_batch_task`（`task_type` 1=刷新群链接 2=获取最新群信息；`status` 1..5）+ `group_batch_task_item` |
| 群事件消费者 | `platform/kafka/consumer/group/ProtocolGroupEventConsumer.java:60,193` | 已消费 `group.profile_reported`（`handleProfileReported`）、`group.metadata_updated`、`group.participant_changed`、`group.invite_link_changed` 等 |
| 候选选择器 | `group/service/GroupExecutionAccountSelector.java:17` | `MAX_RETRY_CANDIDATES = 4`；另有 `MAX_ADMIN_DISCOVERY_CANDIDATES = 500` |
| 字段版本 | `V127__group_profile_field_versions.sql` | 群资料字段级版本控制已上 |
| Topic 配置 | `ProtocolMasterCommandProperties:15` / `ProtocolAndroidCommandProperties:24` / `ProtocolGroupEventConsumerProperties:15` | `protocol.master.commands.v1` / `protocol.android.group-action.commands.v1` / `protocol.group.events.v1` |

### 3.2 Web / armada-protocol

| 项 | 位置 | 事实 |
|---|---|---|
| 命令类型全集 | `src/commands/types.ts:9-58` | 12 个：`account.offline`、`account.offline.requested`、`account.online.requested`、`account.groups_sync.requested`、`group.health_check.requested`、`group.join.requested`、`contact.save.requested`、`group.participants.requested`、`group.settings.requested`、`group.members.query.requested`、`group.normal_creation.requested`、`message.send.requested` |
| 账号级对账 | `commands/worker-consumer.ts:601-604` → `worker/account-manager.ts:729` | `reconcileParticipatingGroups(accountId, reportSource)`，`forceFullMetadata: true`，source 记为 `armada_explicit_reconcile` |
| 单群查询 | `worker/account-manager.ts:764 readGroupMetadata(accountId, groupJid)` | 内部走 `readGroupMetadataWithMemberLinkMode`；带 `GROUP_METADATA_READ_SUPPRESSION_MS` 抑制窗口，避免自查回灌的 `groups.update` 被误当实时变更 |
| 分档 | `worker/account-manager.ts:2117` | `forceFullMetadata === true || !ctx.groupBaselineReady` 才走 `groupFetchAllParticipating` |
| 资料事件映射 | `worker/account-manager.ts:3113 groupProfileReported()` | 输出 `groupJid/subject/description/announceOnly/adminOnlyEditInfo/memberAddMode/joinApprovalMode/ephemeralDurationSeconds/members/membersComplete/fieldMask` |
| 成员映射 | `worker/account-manager.ts:3180+ groupProfileMembers()` | 每成员 `jid/lid/phone/admin`，三种身份至少有一个才收 |
| 邀请码 | `src/routes/groups.ts:693` | `groupInviteCode(groupJid)`，**仅 HTTP，未接 Kafka** |
| 群事件名 | `src/events/subjects.ts:7-49` | 含 `group.profile_reported`、`group.invite_link_changed`、`group.metadata_updated`、`group.participant_changed` 等 |
| commandId 幂等 | `commands/group-join-state.ts`、`commands/pull-task-action-state.ts` | Redis + Lua 原子 claim / storeResult / markPublished |

### 3.3 安卓 / whatsapp-server-feature-android-zhuan

| 项 | 位置 | 事实 |
|---|---|---|
| 生命周期命令 | `internal/armada/command.go:20` | 已支持 `account.groups_sync.requested` |
| group-action 命令 | `internal/armada/group_action_command.go:8-19` | 仅 4 个：`contact.save.requested`、`group.participants.requested`、`group.settings.requested`、`group.normal_creation.requested` |
| 全量群查询 | `internal/armada/groups_fetcher.go:63,270` | 固定调 `GetAllGroup(true)`，**participants 已取回** |
| 单群查询 | `internal/service/app/group.go:56 GetGroupMember(groupId)` | 单群 metadata IQ，已存在 |
| 邀请码 | `internal/service/app/safemex.go SendGroupInviteCodeQuery(groupId)` | HTTP 与 Kafka 共用只读 MEX 查询，不重置链接 |
| 群资料结构 | `internal/service/entity/entity.go:37 GroupInfo` | `Creator/Creation/Subject/Participants/GroupJoinState/MemberAddMode/MemberLinkMode/AddressingMode/Announce/Locked/Suspended/Terminated`；**无 `description`、无 `ephemeralDuration`** |
| 成员结构 | `internal/service/entity/entity.go:25 ParticipantAttr` | `Jid/Type/Err/PhoneNumber`；**无独立 LID 字段** |
| 上报契约 | `internal/armada/event.go:33-52 ReportedGroup` | 仅 `groupJid/subject/admin/announceOnly/adminOnlyEditInfo/memberAddMode/joinApprovalMode`；注释明写**"不携带 participant 列表或原始 IQ 数据"** |
| 已发事件 | `internal/armada/event.go:18-31` | `account.groups_reported`、`group.health_reported`、`group.invite_link_changed`、`group.participant_changed`；**无 `group.profile_reported`** |
| commandId 幂等 | `internal/armada/join_state.go:65 RedisGroupJoinCommandStateStore`、`group_action_state.go`、`message_state.go` | Claim/StoreResult/MarkPublished 齐备 |
| 节点隔离 | `internal/armada/config.go:81 nodeTopicOrDefault()` | 配了 `[fleet].nodeid` 时命令 topic 改写为 `{sourceTopic}.node-{nodeID}.v1` |

### 3.4 能力差矩阵

| 能力 | Web | 安卓 | 本期动作 |
|---|---|---|---|
| 账号级全量对账命令 | ✅ | ✅ | 复用，不动 |
| 轻量群清单（群名+四设置） | ✅ | ✅ | 复用，不动 |
| 群简介 description | ✅ | ❌ IQ 不含 | 安卓留空，`fieldMask` 不含该键 |
| 限时消息 ephemeralDurationSeconds | ✅ | ❌ IQ 不含 | 同上 |
| 逐群完整资料+成员事件 | ✅ `group.profile_reported` | ❌ 事件契约丢弃 | **安卓补发布** |
| 单群按 jid 查询 | ✅ `readGroupMetadata` | ✅ `GetGroupMember` | 两端接命令入口 |
| 成员 LID | ✅ | ❌ 无字段 | 安卓 `lid` 留空 |
| 邀请码读取 | ✅ 仅 HTTP | ✅ 仅 HTTP | **两端接 Kafka** |
| 资料变化即时投影 | ✅ | ❌ 缺 `w:gp2` 样本 | **不在本期范围** |
| 成员变化即时投影 | ✅ | ✅ | 复用，不动 |
| commandId 幂等存储 | ✅ | ✅ | 复用，不动 |

---

## 4. 目标与非目标

### 4.1 目标

- 控端能指定"用账号 A 查群 G"，异步拿到结构化结果；
- 邀请码读取从 HTTP 搬到 Kafka，支持权限失败后换号重试；
- 安卓的逐群完整资料（含成员）能进控端库；
- 命令至少一次投递，结果重复/迟到仍幂等收敛；
- 人工批量刷新不再占用控端应用线程等待 HTTP；
- 群资料与邀请码的写入口数量**不增加**。

### 4.2 非目标

- 不新建 Kafka topic、不新建 Outbox 表；
- 不新建群资料事件、不新建邀请码事件；
- 不改动首次建档链路（继续用 `account.groups_sync.requested`）；
- 不做安卓 `w:gp2` 即时投影（缺真实报文样本，另案）；
- 不重置/撤销群邀请链接，只读当前 code；
- 不在普通群变更事件后回查 metadata；
- 不猜测"成员可访问邀请链接"的独立权限字段，以服务端结果为准；
- 不立即删除现有 HTTP 查询路由（保留低频兼容调用）。

---

## 5. 总体架构

### 5.1 首次建档（**本期零开发**）

```text
账号上线 / 控端触发
  -> 现有 Outbox: account.groups_sync.requested
  -> Web  reconcileParticipatingGroups(forceFullMetadata)
     安卓 GetAllGroup(true)
  -> account.groups_reported（账号级轻量全集，承载退群判定）
  -> group.profile_reported × N（逐群完整资料+成员）   [安卓本期补齐]
  -> 控端按群落库
```

### 5.2 按需单群查询（**本期主体**）

```text
人工刷新 / 异常修复 / 邀请码换号重试
  -> group_metadata_sync_task 或 group_batch_task_item 认领
  -> 选执行账号（在线、在群、角色优先）
  -> protocol_command_outbox（与任务状态同事务）
  -> Kafka: group.snapshot.requested   [Web 走 master topic / 安卓走 group-action topic]
  -> 协议端单群查询
       METADATA     -> group.profile_reported（带 commandId）
       INVITE_CODE  -> group.invite_link_changed（带 commandId）
  -> 无论成败都发 group.snapshot_result_reported（只带结算）
  -> 控端：事实 reducer 落库；结算 CAS 收口任务 / 选下一候选
```

### 5.3 三事件分工（本设计的核心）

| 事件 | 承载 | 是否新增 |
|---|---|---|
| `group.profile_reported` | 群资料与成员**事实** | 已有，补可选 `commandId` |
| `group.invite_link_changed` | 邀请码**事实** | 已有，补可选 `commandId` |
| `group.snapshot_result_reported` | 命令**结算**（成/败、原因），**不含任何群业务字段** | 新增 |

这样做的理由：`2026-08-16-group-event-direct-projection-design.md` 明确要求"避免一个事实形成
两条写入口"。事实继续从既有事件单口径进入，新事件只回答"这条命令算完了没、为什么失败"，
不产生第二个事实来源，也不需要控端做跨事件时间戳仲裁。

---

## 6. Topic 与路由

不新建 topic。

| 数据流 | 配置 key | 默认值 | 区分字段 |
|---|---|---|---|
| Web 命令 | `armada.protocol.kafka.master-commands.topic` | `protocol.master.commands.v1` | `commandType` |
| 安卓命令 | `armada.protocol.kafka.android-commands.group-action-topic` | `protocol.android.group-action.commands.v1` | `commandType` |
| 全部结果 | `armada.protocol.kafka.group-events.topic` | `protocol.group.events.v1` | `event` |

- Kafka key 一律 `protocolAccountId`，保证单账号命令有序；
- 不要求同群跨账号全局有序，乱序由 §15 的版本规则收敛；
- 安卓 `nodeTopicOrDefault()`（`config.go:81`）会把命令 topic 改写成 `.node-{id}.v1`，
  由 coordinator 转发。控端仍写源 topic，**不感知节点后缀**；
- 实施前用目标环境配置核对部署文件里的 group event topic 实际取值
  （代码默认两端一致，`deploy/node/` 下曾出现 `armada.` 前缀变体）。

---

## 7. 命令契约 `group.snapshot.requested`

```json
{
  "commandId": "cmd_01J...",
  "commandType": "group.snapshot.requested",
  "version": "v1",
  "protocolAccountId": "acc_918233195112",
  "traceId": "trace-...",
  "createdAt": "2026-08-18T04:30:00.000Z",
  "payload": {
    "tenantId": 1,
    "accountId": 100,
    "groupLinkId": 5001,
    "groupJid": "120363xxx@g.us",
    "scopes": ["METADATA", "INVITE_CODE"],
    "source": "MANUAL_INFO_REFRESH",
    "taskType": "GROUP_METADATA_SYNC",
    "taskId": 9001,
    "attemptNo": 1
  }
}
```

字段规则：

- `commandId` 全局唯一，Outbox 幂等、结果关联、协议端结果缓存都用它；
- envelope 的 `protocolAccountId` 必须与执行账号当前协议绑定一致，协议端校验不符即
  `ACCOUNT_BINDING_MISMATCH` 失败，不执行查询；
- `groupLinkId` 是控端内部关联，协议端**原样回传**，不得当作 WhatsApp 标识使用；
- `groupJid` 必须是规范化 `@g.us`；协议端不做链接解析，不接受邀请 URL；
- `scopes` 非空、去重，只允许 `METADATA`、`INVITE_CODE`；
- `source` 允许 `MANUAL_INFO_REFRESH`、`MANUAL_INVITE_REFRESH`、`REPAIR`、`BACKFILL`、
  `INVITE_CANDIDATE_ROTATION`。**不含首次建档**——那条走 `account.groups_sync.requested`；
- `taskType` 允许 `GROUP_METADATA_SYNC`、`GROUP_BATCH_TASK_ITEM`；`taskId` 为对应主键；
- 邀请码换号重试时使用**新 `commandId`**，`scopes` 只带 `INVITE_CODE`，`attemptNo` 递增；
- payload 不携带旧邀请码；邀请 code 不得进入 Kafka key、header 或任何日志。

### 7.1 为什么不复用 `group.members.query.requested`

该命令的 payload 与 correlation 绑定拉群任务状态机（`pullTaskId/groupExecutionId/queryId/purpose`），
且安卓侧根本不支持它。复用会把群建档与拉群耦合。本方案复用命令总线与 topic，使用独立命令类型。

### 7.2 为什么不扩展 `account.groups_sync.requested`

它是**账号级**语义（"把这个号的所有群对一遍"），承载退群判定，粒度与本命令不同。
两者并存：账号级用于建档与对账，单群级用于定点刷新与邀请码轮换。

---

## 8. 结果契约

### 8.1 `group.profile_reported`（已有事件，唯一改动是补 `commandId`）

协议端在**命令触发**的查询里，于现有 payload 顶层追加：

```json
{
  "commandId": "cmd_01J...",
  "source": "MANUAL_INFO_REFRESH"
}
```

- 非命令触发（上线同步、dirty 对账）时**不带 `commandId`**，行为与今天完全一致；
- 其余字段（`groupJid/subject/description/announceOnly/adminOnlyEditInfo/memberAddMode/
  joinApprovalMode/ephemeralDurationSeconds/members/membersComplete/fieldMask`）**一个不改**；
- `fieldMask` 语义保持："键在 mask 里"=本次确实观察到，"不在"=未观察，控端保留旧值。
  安卓因 IQ 不含 `description`/`ephemeralDurationSeconds`，这两个键**永远不进 fieldMask**；
- `membersComplete=true` 表示 members 是该群全集，控端据此把缺失成员判为已退群。
  拿不到完整成员时**不得置该标记**，且不得截断后仍声明为 true。

### 8.2 `group.invite_link_changed`（已有事件，补 `commandId`）

命令触发的邀请码读取成功时，用该事件上报 code，顶层追加 `commandId` 与 `source`。
被动观察到的邀请码变化仍不带 `commandId`。

- 只传 `inviteCode`，完整 URL 由控端按固定域名派生；
- `inviteCode` 保留原始大小写，不得转小写。

### 8.3 `group.snapshot_result_reported`（新增，只结算）

```json
{
  "eventId": "acc_918233195112:group.snapshot_result_reported:cmd_01J...",
  "event": "group.snapshot_result_reported",
  "version": "v1",
  "accountId": "acc_918233195112",
  "occurredAt": "2026-08-18T04:30:02.000Z",
  "data": {
    "commandId": "cmd_01J...",
    "tenantId": 1,
    "accountId": 100,
    "protocolAccountId": "acc_918233195112",
    "protocolBackend": "WEB",
    "groupLinkId": 5001,
    "groupJid": "120363xxx@g.us",
    "taskType": "GROUP_METADATA_SYNC",
    "taskId": 9001,
    "attemptNo": 1,
    "scopes": {
      "METADATA":    { "outcome": "SUCCESS", "completedAt": 1786854600000 },
      "INVITE_CODE": { "outcome": "FAILED",  "completedAt": 1786854600100,
                       "errorCode": "GROUP_PERMISSION_DENIED" }
    }
  }
}
```

约束：

- `scopes` 的键必须与命令请求的 scope **完全一致**，多、少、伪造都视为非法结果；
- `outcome` 只允许 `SUCCESS` / `FAILED`；`FAILED` 必须带 `errorCode`；
- **不含任何群业务字段**（无 subject、无 members、无 inviteCode）。事实一律在 §8.1/§8.2 里；
- `SUCCESS` 表示"对应事实事件已成功发布并获 broker ACK"。事实发布失败则该 scope 记
  `FAILED` + `RESULT_PUBLISH_FAILED`，不得报成功；
- `completedAt` 为**协议端拿到响应的时刻**（epoch 毫秒），**不是请求发起时刻**。
  这是 0816 版 §8.2 的错误：用发起时刻做版本戳会让"先发起、后返回"的新结果被误判为旧结果；
- 协议端不得回传原始 stanza/node、凭据或错误堆栈。

### 8.4 错误码（第一期）

| errorCode | 含义 | 控端动作 |
|---|---|---|
| `GROUP_PERMISSION_DENIED` | 当前账号无权读取该 scope | 保留已成功 scope，换下一候选，只重试失败 scope |
| `GROUP_NOT_JOINED` | 当前账号不在群 | 校准该账号群关系，换候选 |
| `GROUP_UNAVAILABLE` | 群被封禁/终止/不可访问 | 更新群健康，停止普通重试 |
| `GROUP_INVITE_LINK_UNAVAILABLE` | 当前群没有返回有效邀请链接 | 保留群资料和群健康状态，友好提示；不自动重置链接 |
| `ACCOUNT_NOT_ONLINE` | 协议账号当前不可执行 | 换在线候选；无候选则置 DEFERRED |
| `ACCOUNT_BUSY` | 账号正在执行互斥动作 | 短退避后重试同一账号 |
| `ACCOUNT_BINDING_MISMATCH` | envelope 绑定与当前实际绑定不符 | 不重试，重新选号后新建命令 |
| `INVALID_PAYLOAD` | 命令字段非法（scope/groupJid/taskType 等） | 不重试，告警；属控端派发缺陷 |
| `TIMEOUT` / `NETWORK` | 瞬时链路失败 | 同账号有限重试，再换候选 |
| `PAYLOAD_TOO_LARGE` | 结果超过安全阈值 | 不发超大成功事件，转人工/分片 |
| `RESULT_PUBLISH_FAILED` | 查询成功但事实事件发布失败 | 按瞬时失败重试 |
| `UNKNOWN` | 未识别错误 | 有限重试并告警，不得猜成功 |

两端必须在协议边界完成语义归一（Web 的 `not-authorized`、安卓的 401/403），
**不允许控端解析错误文案**。

---

## 9. 去重与派发口径

### 9.1 事实前提

- `group_link` 唯一键 `(tenant_id, link_url)`（`V003:31`）；
- `group_link_preview.group_jid` 上只有**非唯一**索引 `(tenant_id, group_jid)`（`V010:63`）；
- `group_metadata_sync_task` 唯一键 `(tenant_id, group_link_id)`（`V098:263`）。

**推论：同一个 `group_jid` 理论上可以对应多条 `group_link`**（同群不同邀请链接、多批次导入）。

### 9.2 本期口径：不做持久化折叠

上一版本文曾设计 `peer_task_id` + `WAITING_PEER` 状态 + 派发折叠 + 结算广播的完整机制。
**本期明确取消该设计**，理由：

- 重复的实际代价只是"同一个群多查一次 WhatsApp"，不会造成数据错乱、循环或状态不一致；
- 同 jid 多 link 的真实占比**从未测量**，为一个未量化的问题引入新列、新状态和两套逻辑，
  收益与复杂度不成比例；
- 落库侧本就有 `fieldMask` + 字段版本（V127）兜底，重复上报同一事实是幂等的。

因此本期规则：

1. 任务表唯一键**维持** `(tenant_id, group_link_id)`，不改；
2. 派发时**在内存内按 `tenantId + groupJid` 去重**：同一批 claim 出来的任务，若 `group_jid` 相同，
   只写一条 Outbox，其余保持 PENDING 等下一轮（下一轮时前一条通常已结算，自然不再重复）；
3. **不新增数据库列、不新增任务状态、不做广播结算**；
4. `group_jid` 为空的 `group_link` 不参与去重，按 link 单独处理；
5. 埋点 `group_snapshot_duplicate_jid_total`，**先观测真实重复率**。若上线后该指标显著偏高
   （建议阈值：重复派发占比 > 5%），再评估是否需要持久化折叠，届时作为独立设计另案处理。

### 9.3 触发时机

派发单群命令的条件：

- 用户在页面显式点刷新（`MANUAL_INFO_REFRESH` / `MANUAL_INVITE_REFRESH`）；
- 自建群或历史群缺当前邀请码，且已有 metadata；
- 事实矛盾或解析失败进入低频修复（`REPAIR`）；
- 运维 backfill（`BACKFILL`）；
- 邀请码权限失败后换号（`INVITE_CANDIDATE_ROTATION`）。

**不派发**的条件：

- 账号首次上线/重连——走 `account.groups_sync.requested`；
- 群已有成功快照且无 dirty/修复需求；
- 同一群刚被另一个账号的群列表看见（那是账号级链路的职责）；
- 普通增量事件（改名、成员进出、邀请码变化）已能解释的变化。

### 9.4 背压

- 每轮 claim 有界任务批量写 Outbox，不等结果；
- 控端限制单账号同时在途的群查询数，默认沿用现有账号并发上限；
- 结果 consumer 与命令 dispatcher 分开扩缩容；
- 禁止用无界线程池换速度；峰值由 Kafka lag 吸收。

---

## 10. 执行账号选择与邀请码轮换

### 10.1 候选条件与排序

候选必须同时满足：同租户、账号状态正常、协议在线、当前关系为在群、协议绑定有效。
稳定排序：

1. 已确认群主/管理员；
2. 已确认普通成员；
3. 同档内按最近在群时间倒序，再按 `account_id` 升序。

**最多 4 个候选**，沿用 `GroupExecutionAccountSelector.MAX_RETRY_CANDIDATES = 4`。

### 10.2 首次查询

选第一个可用账号，请求 `METADATA + INVITE_CODE`（即使它是普通成员）：

- metadata 成功 → 立即落库完整成员与角色；
- invite 成功 → 落 code，任务完成；
- invite `GROUP_PERMISSION_DENIED` → 用刚落库的角色重新挑**受控管理员**，只重试 `INVITE_CODE`；
- 无受控管理员 → 继续试其他受控普通成员（群设置可能允许成员读邀请链接）。

### 10.3 后续重试

- metadata 已成功后，后续命令 `scopes` **只能是** `["INVITE_CODE"]`，不得重复拉成员；
- `GROUP_PERMISSION_DENIED` 消耗一个候选，**不回滚** metadata 的成功；
- `GROUP_NOT_JOINED` 同时校准该账号的群关系，**不得清空其他账号的关系**；
- 已尝试账号集合必须持久化（`candidate_cursor`），应用重启后从游标续，不得从头循环；
- 全部候选失败 → 邀请码状态记"暂未取得/权限不足"，**保留旧 code，不写空值覆盖**。

未来若协议端能解析出"成员可访问邀请链接"的独立字段，selector 可直接跳过明确无权限的普通成员；
在此之前一律以服务端结果为准。

---

## 11. 数据模型（Flyway **V129**）

设计时最新为 V127；集成时 V128 已被群创建者手机号地区功能占用，因此本功能使用 **V129**：
`V128__group_snapshot_command_correlation.sql`。

### 11.1 `group_metadata_sync_task` 增列

| 字段 | 类型 | 用途 |
|---|---|---|
| `current_command_id` | `VARCHAR(64) DEFAULT NULL` | 当前等待结果的命令 ID，结果 CAS 用 |
| `requested_scope_mask` | `TINYINT NOT NULL DEFAULT 0` | 本次请求 scope；1=METADATA 2=INVITE_CODE 位掩码 |
| `completed_scope_mask` | `TINYINT NOT NULL DEFAULT 0` | 已成功落库 scope，避免重复拉 metadata |
| `candidate_cursor` | `INT NOT NULL DEFAULT 0` | 已消费候选位置 |
| `result_deadline_at` | `BIGINT DEFAULT NULL` | 等待结果超时水位（epoch 毫秒） |

索引：`KEY idx_gmst_command (tenant_id, current_command_id)`、
`KEY idx_gmst_deadline (tenant_id, result_deadline_at)`。

状态语义调整：原同步 HTTP 的 `RUNNING` 改为"命令已派发，等待 Kafka 结果"。
**不新增 `WAITING_PEER` 状态**（见 §9.2）。

> 已实施提示：V129 若已写入 `peer_task_id` 列与 `WAITING_PEER` 枚举，本期应移除。
> 该迁移尚未部署，直接改迁移文件即可，不需要补偿脚本。

### 11.2 `group_batch_task_item` 增列

同样补 `current_command_id` / `attempt_count` / `candidate_cursor` / `result_deadline_at` /
`completed_scope_mask`，状态补 `DISPATCHED` / `WAITING_RESULT`。前端轮询接口形状不变。

### 11.3 Outbox 行构造

不加表，只加构造方法：

```text
command_type       = group.snapshot.requested
aggregate_type     = GROUP_METADATA_SYNC_TASK | GROUP_BATCH_TASK_ITEM
aggregate_id       = 对应 task/item id
kafka_topic        = WEB 用 master-commands.topic；ANDROID 用 android-commands.group-action-topic
kafka_key          = protocolAccountId
protocol_account_id= protocolAccountId
protocol_backend   = WEB | ANDROID
```

**任务状态变更与 Outbox 行必须同一数据库事务写入**，禁止先标已派发再另事务写 Outbox。

### 11.4 编码约束（沿用项目既有铁律）

- 所有时间列用 `BIGINT` 存 epoch 毫秒，**不用 DATETIME**；
- API wire 出入参 camelCase，MyBatis 保留 map-underscore；
- 分页/计数/筛选一律 SQL 下推，**禁止 Java 内存分页或 load-all**（含测试）；
- 任何 `FOR UPDATE` + `LIMIT` 的 mapper 必须加 `@InterceptorIgnore(tenantLine)`，否则租户拦截器
  会拼出 MySQL 语法错误（mock 测不出，必须真库验证）；
- mapper XML 内裸 `<` `>` 必须转义，改完用 `xmllint` 或 DbTest 验证，否则运行时 crash-loop；
- 新增/修改的类与字段写中文注释；字段多的 record 用属性上方内联 `/** */`。

---

## 12. Web 实施（armada-protocol）

1. `src/commands/types.ts` 的 `MasterCommandType` 与 `SUPPORTED_COMMAND_TYPES` 加
   `group.snapshot.requested`；
2. master 按现有 owner 路由把命令投给持有该账号 socket 的 worker（`master-router.ts` 无需特殊处理）；
3. 新建 `src/commands/group-snapshot-executor.ts`：
   - 校验 payload（scopes 合法、groupJid 规范、绑定一致）与当前 socket generation；
   - **校验失败也必须发出结算事件**：`protocolAccountId` 与信封不符发
     `ACCOUNT_BINDING_MISMATCH`，其余字段非法发 `INVALID_PAYLOAD`，把请求的每个 scope 都标
     `FAILED`。**不允许直接抛异常了事**——控端收不到结算就只能干等到 `result_deadline_at`，
     任务白挂一个超时周期。仅当 payload 残缺到连 `commandId`/`taskId` 都取不到时才走 DLT；
   - `METADATA` → 复用 `accounts.readGroupMetadata(accountId, groupJid)`
     （**必须走它，不要另写查询**，因为它带 `groups.update` 回灌抑制窗口）；
     再用**现有** `groupProfileReported()` 映射，附加 `commandId`/`source` 后
     `publisher.publish('group.profile_reported', ...)`；
   - `INVITE_CODE` → 调 `groupInviteCode(groupJid)`，成功则发
     `group.invite_link_changed`（附 `commandId`）；
   - 各 scope 独立捕获，互不阻断；
   - 最后统一发一条 `group.snapshot_result_reported`；
4. 错误归一：`not-authorized` → `GROUP_PERMISSION_DENIED`；明确不在群 → `GROUP_NOT_JOINED`；
   群被封/终止 → `GROUP_UNAVAILABLE`；

   **实现约束**：归一必须基于 Baileys 的**结构化错误**（`Boom` 的 `output.statusCode`、
   `data`、stanza 的 `error` 子节点 code/text），**不得靠匹配英文错误文案子串**。
   `not participant`、`not joined` 这类串在 Baileys 实际响应里基本不出现——真实的"不在群"
   多为 `not-authorized` 或 `item-not-found`，与"无权限"共用同一个 code。
   两者的控端动作不同（换号 vs 校准群关系），**分不开时必须回 `UNKNOWN` 并打日志，不许猜**。
   上线前用 test1 真实报文把这张映射表核一遍，核完把实际 code 补进本节；
5. 幂等：复用 `group-join-state.ts` 的 Redis claim/storeResult/markPublished 模式新建
   `group-snapshot-state.ts`。同 `commandId` 重放优先补发已缓存结果，**不重复请求 WhatsApp**；
6. **事实事件与结算事件都获 broker ACK 后**才确认输入命令 offset；发布失败保留命令待重试；
7. `routes/groups.ts` 现有 HTTP 路由保留，不改，供低频兼容调用；
8. Kafka worker **禁止**反向 HTTP 调自己，必须直接复用 socket 执行器。

---

## 13. 安卓实施（whatsapp-server-feature-android-zhuan）

### 13.1 补 `group.profile_reported` 发布（本期最大的一块）

数据已在手：`groups_fetcher.go:270` 调 `GetAllGroup(true)`，`GroupInfo.Participants` 完整。
现在只是 `event.go:33 ReportedGroup` 按契约丢弃了。要做的是：

1. 新增 `ReportedGroupProfile` 结构与 `BuildGroupProfileReportedEvent`，字段与 Web 的
   `groupProfileReported()` 输出**逐字对齐**：
   `groupJid/subject/announceOnly/adminOnlyEditInfo/memberAddMode/joinApprovalMode/
   members/membersComplete/fieldMask`；
2. `description` 与 `ephemeralDurationSeconds` 安卓 IQ 不返回，**不写入 payload，也不进 fieldMask**
   （控端据此保留旧值，不会被误判为"观察到空"）；
3. 成员映射必须与 Web 的 `groupProfileMembers()` **字段齐平**，控端
   `ProtocolGroupEventConsumer:476` 会读 `admin`、`owner`、`role` 三个字段：

   | wire 字段 | 安卓来源 | 说明 |
   |---|---|---|
   | `jid` | `ParticipantAttr.Jid` | |
   | `phone` | `ParticipantAttr.PhoneNumber` | |
   | `lid` | —— | 安卓无该字段，**留空** |
   | `admin` | `Type == "admin" \|\| Type == "superadmin"` | 布尔 |
   | `owner` | `Type == "superadmin"`，或 `jid/phone` 命中 `GroupInfo.Creator` | 布尔，**不可省略** |
   | `role` | `ParticipantAttr.Type` 原值 | 字符串，`admin`/`superadmin`/空 |

   **`owner` 与 `role` 必须上报**。`GroupInfo.Creator` 已有群主身份
   （`groups_fetcher.go:120` 的 `creatorGroupAdmin` 正在用它），漏映射会导致安卓来源的群
   **群主恒为 0**——该症状在 `GroupParticipantRolePrecedenceSqlTest` 的注释里已被 test1 实测记录，
   不要重蹈。三种身份全空的条目跳过；
4. `membersComplete` 只在确实读到完整 participants 时置 true；
5. 账号级 `account.groups_reported` 的现有形状**保持不变**（它承载退群判定，改了会波及既有消费）；
   逐群资料走新事件，与 Web 对齐。

### 13.2 接入单群命令

1. `group_action_command.go` 加 `CommandTypeGroupSnapshotRequested = "group.snapshot.requested"`
   与对应 source 常量；
2. 按 `protocolAccountId` 定位账号运行实例；
3. `METADATA` → 调 `GetGroupMember(groupJid)`（单群 metadata IQ），映射同 §13.1；
4. `INVITE_CODE` → 调只读 MEX 邀请码查询，成功发 `group.invite_link_changed`（附 `commandId`）；
5. 错误归一：403 → `GROUP_PERMISSION_DENIED`；邀请链接查询 400/410 或空 code →
   `GROUP_INVITE_LINK_UNAVAILABLE`；不在群 → `GROUP_NOT_JOINED`；离线 →
   `ACCOUNT_NOT_ONLINE`；超时 → `TIMEOUT`。**尽量区分"无权限"与"已不在群"**，
   区分不了时用 `UNKNOWN` 并打日志，不要猜；
6. 幂等：复用 `join_state.go` 的 `RedisGroupJoinCommandStateStore` 模式新建
   `group_snapshot_state.go`；
7. Kafka publish 或本地可靠失败队列确认前，**不提交输入命令 offset**；
8. WhatsApp 的 IQ/notification ACK **只代表协议收包**，不得据此把业务任务标成功；
9. 节点隔离沿用 `nodeTopicOrDefault()`，本方案不改路由规则。

---

## 14. 控端消费与落库（armada）

`ProtocolGroupEventConsumer` 增加 `group.snapshot_result_reported` 分支。三个事件的处理分工：

### 14.1 `group.profile_reported`（已有 handler，做增量改造）

- 现有 `handleProfileReported` 逻辑**保持不变**（无 `commandId` 时行为完全不变）；
- 有 `commandId` 时，落库成功后额外记录该 scope 已完成，供结算比对；
- 落库范围与今天一致，按事件自身的 `groupJid` / `groupLinkId` 归位，**不做跨 link 广播**（见 §9.2）；
- `fieldMask` 语义严格执行：不在 mask 的字段保留旧值，**不得写 NULL 覆盖**；
- `membersComplete=true` 才允许把缺失成员判为退群；false 时只做增量合并。

### 14.2 `group.invite_link_changed`（已有 handler，做增量改造）

- 有 `commandId` 时记录 scope 完成；
- 按观察时间防旧 code 复活；
- 失败/无权限时**不写空值**，保留旧 code。

### 14.3 `group.snapshot_result_reported`（新 handler）

信封校验顺序：

1. envelope `accountId == data.protocolAccountId`；
2. tenant、armada account、backend、当前协议绑定有效；
3. `groupLinkId` 与 `groupJid` 指向同一当前群；
4. `taskType` / `taskId` / `commandId` 与任务的 `current_command_id` **CAS 匹配**；
5. `scopes` 键集与任务的 `requested_scope_mask` 一致；

然后：

6. 全部请求 scope 都 `SUCCESS` → 任务完成；有 `rerun_requested` 则回 PENDING；
7. `INVITE_CODE` 失败且属可换号错误 → 推进 `candidate_cursor`，选下一候选，**同事务**写下一条 Outbox；
8. 候选耗尽 → 任务置终态"暂未取得"，保留旧 code。

### 14.4 幂等与乱序

- `eventId` / `commandId` 重复：不重复写邀请历史、不重复累加任务计数；
- 旧 `commandId` 的结果**只确认消费，不结算新 attempt**；
- 较旧 `completedAt` 不得覆盖更新的 metadata 或 code；
- **数据库提交成功后才提交 Kafka offset**；
- 非法结果进现有重试/DLT，**不得静默跳过**。

### 14.5 超时恢复

独立定时任务扫 `result_deadline_at` 到期且仍在等待的任务：
推进候选或置 DEFERRED，并清空 `current_command_id`，防止任务永久悬挂。

---

## 15. 消息大小与隐私

- 一群一条资料事件，不合并多群；
- 体积主要来自 `members`。协议端序列化后记录脱敏的 `payload_bytes` 指标；
- **阈值先测后定**：上线前用最大成员群的 Web/安卓真实脱敏 fixture 测量序列化大小，
  再据实测结果设阈值并写回本文。在此之前**不得**把 800 KiB 之类的数字硬编码进代码
  （这是 0816 版 §14 的顺序错误）；
- 超阈值时不发超大成功事件，回报 `PAYLOAD_TOO_LARGE`；分片（`snapshotId + participantChunks`）
  是独立契约升级，v1 不做，**更不允许截断后仍标 `membersComplete=true`**；
- Kafka producer 沿用现有压缩与消息上限配置，不在本文硬编码 broker 参数；
- 日志禁止出现完整 participants、邀请码明文、原始 node 或手机号列表；
  只允许数量、稳定 ID 与邀请码后缀。

---

## 16. 配置与监控

不新增 topic 配置。新增业务开关：

- `armada.group-snapshot.enabled`：单群命令主链总开关（默认关，灰度打开）；
- `armada.group-snapshot.dispatch-batch-size`；
- `armada.group-snapshot.account-concurrency`；
- `armada.group-snapshot.result-timeout-ms`；
- `armada.group-snapshot.max-candidates`（默认 4，与 selector 常量一致）；
- `armada.group-snapshot.http-fallback-enabled`：滚动期紧急回滚，默认关。
  **注意**：§11.2 改造后批量 Worker 已不走 HTTP，该开关只对"人工单群刷新"生效，
  不是全链路回滚路径，文档与代码注释都要写清这个边界；
- Web / 安卓各自的命令执行开关。

指标：

- `group_snapshot_command_total{backend,scope,result}`；
- `group_snapshot_result_total{backend,taskType}`；
- `group_snapshot_candidate_switch_total{reason}`；
- `group_snapshot_payload_bytes{backend}`；
- `group_snapshot_queue_lag_seconds{backend}`；
- `group_snapshot_end_to_end_seconds{source}`；
- `group_snapshot_stale_result_total{reason}`；
- `group_snapshot_permission_denied_total{backend,role}`；
- `group_snapshot_duplicate_jid_total`（§9.2 内存去重命中的重复派发数，**用于判断是否真需要持久化折叠**）；
- Outbox pending/retry、命令 topic lag、group event consumer lag、DLT 数量。

告警覆盖：结果超时、持续权限失败、payload 超限、DLT 非零、同账号队列异常积压。

---

## 17. 发布与回滚

### 17.1 顺序

1. 核对目标环境三个 topic 的实际配置（尤其安卓部署文件里的 group event topic 取值）；
2. armada 部署 V129 与结果 consumer，**开关关闭**，此时不派发新命令；
3. Web 部署新 commandType 与 executor，开关关闭；
4. 安卓部署 `group.profile_reported` 发布 + 新 commandType，开关关闭；
5. test1 打开开关，先单账号单群，再 10 群；
6. 分别验证 Web/安卓的 metadata 成功、邀请码成功、权限失败换号；
7. 打开人工批量刷新走新链路；
8. 稳定后再评估清理不再使用的 HTTP 批量调用。

### 17.2 回滚

- 关 `armada.group-snapshot.enabled`，停止产生新命令；
- 已进入 Kafka 的命令允许跑完，按 `commandId` 幂等落库，或由超时任务终止；
- V129 新增列**保留**，不做破坏性回滚；
- consumer 继续接受滚动期晚到的结果；
- 回滚**不得**清空已确认的群资料、成员或邀请码。

---

## 18. 任务拆分

排序原则：**能独立见效的先做**。第 1 刀不依赖后续任何一刀，做完即可单独验证、单独上线。

1. **安卓 `group.profile_reported` 发布**（含成员 `jid/phone/admin/owner/role` 映射与 `fieldMask`）。
   这一刀独立闭环：做完安卓的群资料与成员就能进控端库，不必等按需查询链路。
   控端 `handleProfileReported` 已存在，无需改动即可消费；
2. 固定命令与三个结果事件的 JSON fixture、scope 枚举、错误码表（先落文件，两端共用）；
3. armada：V129 迁移 + 任务表新列（**不含 `peer_task_id`**）+ 状态语义调整（含真库 DbTest）；
4. armada：Outbox 构造方法 + Web/安卓 topic 路由 + 同事务派发 + §9.2 的内存去重与埋点；
5. armada：`group.snapshot_result_reported` consumer + CAS 幂等 + 超时恢复；
6. armada：候选选择器扩展（管理员优先、普通成员兜底、`candidate_cursor` 持久化）；
7. armada：`group_batch_task_item` 从 HTTP Worker 改为 Outbox + 等结果；
8. Web：`group-snapshot-executor` + `group-snapshot-state` + `commandId` 透传
   + 校验失败结算 + 结构化错误归一；
9. 安卓：单群命令接入 + 只读 MEX 邀请码查询接 Kafka + `group_snapshot_state`；
10. 监控、开关、DLT；
11. test1 联调 + 真实 fixture 测 payload 大小并回填 §15 阈值 + 核对 §12 错误码映射表。

第 1 刀可与其余全部并行；第 11 刀必须在打开生产开关前完成。

> 与上一版的差异：删除了原第 4 刀「`group_jid` 折叠派发与广播结算」（见 §9.2 取消理由），
> 并把安卓资料上报从原第 9 位提到第 1 位。

---

## 19. 测试与验收

### 19.1 armada

- 同一 `group_jid` 对应 3 条 `group_link` 时，**同一批派发只写 1 条 Outbox**，其余保持 PENDING，
  且 `group_snapshot_duplicate_jid_total` 计数 +2；
- 任务状态与 Outbox 行同事务，任一失败都不出现"已派发但无命令"；
- Web/安卓命令分别落到各自配置 topic，key 均为 `protocolAccountId`；
- metadata 成功、invite 权限失败时，成员先落库，随后只派发 `INVITE_CODE` scope；
- 管理员失败后按稳定顺序试普通成员，最多 4 个候选；
- 重复 `eventId`、旧 `commandId`、迟到 `completedAt` 均不覆盖新事实；
- `GROUP_NOT_JOINED` 只校准当前账号关系，不影响其他账号；
- 全部候选失败保留旧邀请码；
- `fieldMask` 不含的字段保留旧值，不被写空；
- 超时任务能被恢复任务清理并推进候选；
- H2 加载真实 Mapper XML 验租户隔离与事务；MySQL 8.4 验唯一键/CAS/并发。

### 19.2 Web

- master 接受并路由新 commandType；未知 scope 拒绝；
- 两个 scope 都成功时，发出 `profile_reported` + `invite_link_changed` + 一条结算；
- metadata 成功、invite 401 时，`profile_reported` 照常发，结算里 invite 记 `FAILED`；
- 非命令触发的 `profile_reported` **不带 `commandId`**，形状与今天完全一致（回归保护）；
- 事实事件发布失败时结算记 `RESULT_PUBLISH_FAILED`，不报成功；
- `commandId` 重放不重复请求 WhatsApp；
- **payload 校验失败（绑定不符、scope 非法）仍发出结算事件**，errorCode 为
  `ACCOUNT_BINDING_MISMATCH` / `INVALID_PAYLOAD`，不静默抛异常；
- 单群查询走 `readGroupMetadata`，其抑制窗口仍能压住自查回灌的 `groups.update`。

### 19.3 安卓

- `GetAllGroup(true)` 结果能完整映射成 `group.profile_reported`，成员含 jid/phone/admin；
- **群主能被识别**：`GroupInfo.Creator` 对应的成员 `owner=true`，`role` 原值透传；
  一个群至少有一个 `owner=true`（除非 IQ 确实没返回 Creator）；
- `description` / `ephemeralDurationSeconds` **不出现在 payload 也不出现在 fieldMask**；
- `account.groups_reported` 形状未变（回归保护）；
- group-action consumer 接受新命令并调用正确账号；
- 只读 MEX 邀请码查询成功发 `invite_link_changed`，403 归一为
  `GROUP_PERMISSION_DENIED`，400/410 或空邀请码归一为 `GROUP_INVITE_LINK_UNAVAILABLE`；
- Kafka/DLQ 与输入 offset 提交顺序正确；
- 节点后缀 topic 下命令仍能被正确节点消费。

### 19.4 test1 验收

1. Web 管理员群单群刷新；
2. 安卓管理员群单群刷新；
3. 普通成员关闭邀请访问权限：metadata 成功、invite 权限失败；
4. 打开成员邀请访问权限：同一普通成员取得邀请码；
5. 无受控管理员但有获权普通成员时补齐群链接；
6. 一个账号退群后自动换另一在群账号；
7. 同一群三条 link 的派发去重（只查一次 WhatsApp）；
8. 重复投递、应用重启、结果迟到、Kafka 短暂不可用、回滚演练。

验收标准：

- 人工刷新不再同步等 HTTP；
- 同 `group_jid` 不产生重复 WhatsApp 查询；
- 安卓来源的群**能识别出群主**（`owner=true` 不恒为 0）；
- 每个成功项可串起 `task → outbox → command → 事实事件 → 结算事件 → 落库`；
- 权限失败不丢已成功 metadata，不清空旧邀请码；
- broker ACK 与 WhatsApp ACK 均不能单独把任务标成功；
- 安卓群资料（含成员）能进控端库并在现有页面可读；
- 普通增量事件仍保持零次 metadata 后置查询；
- payload 大小与队列 lag 有真实压测数据。

---

## 20. 未决项（实施中确认，不阻断开工）

1. 安卓部署文件里 group event topic 的实际取值是否与代码默认一致
   （代码两端均为 `protocol.group.events.v1`，`deploy/node/` 下曾见 `armada.` 前缀变体）；
2. `payload_bytes` 实测值与最终阈值（§15，第 12 项任务产出后回填本文）；
3. 安卓 `GetGroupMember` 单群 IQ 返回的群设置字段是否与 `GetAllGroup` 完全一致；
   若有缺失，单群命令的 `fieldMask` 需按实际返回裁剪；
4. 同 `group_jid` 多 `group_link` 的真实重复率（由 §9.2 的 `group_snapshot_duplicate_jid_total`
   观测）。**超过 5% 再考虑持久化折叠，否则维持内存去重**；
   `group_link_preview.group_jid` 为空的历史行占比一并观测；
5. 提交 V129 前重新确认 `db/migration/` 下最大版本号，避免与在途分支撞号；
6. 人工批量任务被取消后，已进 Kafka 的只读结果是否仍允许落库群事实。
   **建议：允许落事实，但不再累加已取消任务的成功计数**。

---

## 21. 与其他设计文档的关系

- `2026-08-16-group-event-direct-projection-design.md`：定义增量事件直接投影。本文是其
  "完整 metadata 查询旁路"的按需执行设计，**严格遵守其"一个事实一条写入口"约束**
  （见 §5.3 三事件分工）；
- `2026-08-15-group-data-model-rebuild-design.md`：规划六张权威表并占用 V126。本文只在
  过程表 `group_metadata_sync_task` / `group_batch_task_item` 上加列，与权威表改造互不冲突；
  两者上线顺序无强依赖；
- `2026-08-15-pull-task-group-settings-kafka-design.md`（已实现）：本文的命令/结果风格与之对齐
  （`group.*.requested` 命名、`protocolAccountId` 做 key、`commandId + attemptNo` 幂等）；
- `2026-08-16-group-snapshot-kafka-sync-design.md`：**本文取代之，该文档不再作为实施依据**。

END
