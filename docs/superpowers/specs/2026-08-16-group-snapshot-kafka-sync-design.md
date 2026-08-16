# WhatsApp 群快照与邀请码 Kafka 同步设计

日期：2026-08-16  
状态：方案待评审，尚未实施  
涉及仓库：`armada`、`armada-protocol`、`whatsapp-server-feature-android-zhuan`

## 1. 结论先行

首次建档、人工刷新、异常修复等确实需要主动查询群详情时，Armada 不再在任务线程内批量同步调用
Web/Android HTTP 接口，而是通过现有协议命令 Outbox 和现有 Kafka topic 异步派发；协议端必须完成
WhatsApp 响应解析，再把结构化群快照和邀请码查询结果通过现有群事件 topic 回报给 Armada 落库。

本方案确认：

- 不新建 Kafka topic；
- 不新建 Outbox 表，复用 `protocol_command_outbox`；
- 新增命令类型 `group.snapshot_sync.requested`；
- 新增结果事件 `group.snapshot_sync_result_reported`；
- Web 命令复用当前 master command topic；
- Android 命令复用当前 group-action command topic；
- 查询结果复用当前 group event topic；
- Kafka key 固定使用 `protocolAccountId`，保证单账号命令有序；
- 100 个账号发现 500 个唯一群时，按 `tenantId + groupJid` 去重，只产生约 500 个首次同步任务，
  不能按 5 万条账号群关系派发；
- metadata 与邀请码分别结算。metadata 成功、邀请码无权限时先落完整群资料，再换受控账号只重试邀请码；
- 普通群变更事件继续执行“原事件直接投影”，不得因为本方案重新在每条群事件后查询 metadata。

本文是 `2026-08-16-group-event-direct-projection-design.md` 中“完整 metadata 查询旁路”的传输与执行设计。

## 2. 已确认需求

1. 账号首次上线或首次补充账号群关系后，控端需要获得群最新详细资料，而不仅是 ACK 或群 JID 列表。
2. 协议端必须解析 WhatsApp 响应，控端消费结构化结果后更新数据库；只确认 Kafka 或 WhatsApp ACK 不算成功。
3. 邀请码只读当前 code，不执行 revoke/reset。
4. 管理员默认可以读取当前邀请码；普通成员在群设置允许成员访问邀请链接时也可能读取成功。
5. 当前 Web/Android 都没有可靠暴露“普通成员可访问邀请链接”的独立权限字段，因此候选策略暂定为管理员优先、
   普通成员兜底，并以 WhatsApp 服务端结果为准。
6. 大批量账号/群同步使用 Kafka 解耦，不由 Armada 同步发起数百次 HTTP 请求。
7. Topic 复用现有配置，通过消息中的 `commandType` / `event` 区分业务，不为该能力增加新 topic。

## 3. 当前事实与缺口

### 3.1 已有 Kafka 基础设施

- Armada 已有通用 `protocol_command_outbox`，每行保存 `command_type`、`kafka_topic`、`kafka_key`、
  `protocol_account_id` 和 payload；事务提交后由 dispatcher 异步投递。
- Web master 命令信封已支持 `commandId/commandType/protocolAccountId/payload`，并按协议账号路由到 owner worker。
- Android 已按生命周期、消息、进群、群动作拆分命令消费者；群成员查询已复用 group-action topic。
- 协议端事件已经按事件类型路由到 account/group/message 等现有 topic。
- 当前查询类命令至少包括账号当前群同步、群健康检查和群成员查询；本方案不是引入第二套命令总线。

### 3.2 当前主动查询仍是 HTTP

- Web 邀请码读取由 Armada 调用 `GET /v1/groups/{groupJid}/invite-code`；
- Android 邀请码读取由 Armada 调用 `/ws/v1/groups/qrcode/{wsPhone}`；
- `GroupMetadataSyncJob`、批量刷新 worker 和历史群人工刷新当前会在 Armada 进程内直接等待协议 HTTP；
- `GroupMetadataSnapshotServiceImpl` 在 metadata 返回后再次选择管理员并同步读取邀请码；
- 批量刷新链接、首次快照和主动兜底存在多处“只选管理员”的重复实现。

这种实现对单群操作简单，但在首次导入大量账号时会把查询等待、并发、超时和重试压力集中在 Armada
应用线程池及协议 HTTP 网关，不适合作为批量群建档主链。

### 3.3 当前权限与错误缺口

- Web `groupInviteCode` 本身不做本地角色校验，权限由 WhatsApp 服务端判断；当前 HTTP route 未把
  `not-authorized` 稳定映射成业务错误。
- Android 已把 401/403 类响应映射为 `GROUP_PERMISSION_DENIED`，但当前不能可靠区分“无邀请码权限”与
  “已经不在群”。
- 当前控端 selector 只选管理员，会漏掉已经被群设置授权读取邀请链接的普通成员。
- 当前 `group.invite_link_changed` 只能表达成功观察到邀请码，不能表达命令关联、部分成功、权限失败和候选轮换，
  因此不能直接替代查询结果事件。

## 4. 目标与非目标

### 4.1 目标

- 大批量群详情与邀请码查询通过 Kafka 异步执行；
- 每个唯一群一次首次快照任务，重复账号群关系不重复派发；
- Web/Android 使用统一命令和结果语义；
- 协议端返回已经解析、校验的结构化结果，不把原始 WhatsApp node 交给控端二次猜测；
- metadata、成员快照、邀请码分别判断成功和落库；
- 支持管理员优先、普通成员兜底和按失败原因轮换账号；
- 命令至少一次投递、结果重复/迟到时仍幂等收敛；
- 手工批量刷新、首次建档和异常修复复用同一协议执行器；
- 保留可观测的排队、执行、结果、落库四段状态。

### 4.2 非目标

- 不在普通 `add/remove/promote/demote/groups.update` 后查询完整 metadata；
- 不重置群邀请链接；
- 不把 500 个群打包成一条超大命令或一条超大结果；一群一条命令、一群一条结果；
- 不按每条账号群关系查询邀请码；
- 不新增前端页面 API；页面继续读取控端数据库；
- 不在本期猜测 WhatsApp“成员可访问邀请链接”设置的原始节点名，拿到真实报文后再补独立字段解析；
- 不立即删除现有 HTTP 查询适配器，滚动发布期间保留为受控回滚路径，稳定后再清理批量主链调用。

## 5. 总体架构

```text
账号首次/定时群列表同步
  -> Outbox + Kafka: account.groups_sync.requested
  -> Web/Android 查询当前参与群
  -> Kafka: account.groups_reported
  -> Armada 按账号更新群关系
  -> 按 tenantId + groupJid upsert 群快照任务（重复群合并）
  -> 选择在线、正常、仍在群内的执行账号
  -> protocol_command_outbox
  -> Kafka: group.snapshot_sync.requested
  -> 对应协议账号 worker
       -> 查询并解析完整 metadata
       -> 按 scope 查询并解析当前邀请码
       -> Kafka: group.snapshot_sync_result_reported
  -> Armada 校验命令关联和当前协议绑定
       -> metadata reducer / 完整成员快照 reducer
       -> current invite reducer
       -> 任务结算或选择下一候选账号
  -> 页面通过现有 API 读取最新数据库事实
```

普通群变更事件走另一条链：

```text
WhatsApp 增量事件
  -> group.participant_changed / group.metadata_updated / group.invite_link_changed
  -> Armada 直接投影
```

两条链只在领域 reducer 汇合，增量事件不能触发本方案的常规完整查询。

## 6. Topic 与路由

本期不创建新 topic，使用部署环境当前已经存在的逻辑通道：

| 数据流 | 复用配置 | 当前默认值 | 路由字段 |
|---|---|---|---|
| Web 查询命令 | `armada.protocol.kafka.master-commands.topic` | `protocol.master.commands.v1` | `commandType` |
| Android 查询命令 | `armada.protocol.kafka.android-commands.group-action-topic` | `protocol.android.group-action.commands.v1` | `commandType` |
| 群查询结果 | 现有 group event topic 配置 | Armada/Web 默认 `protocol.group.events.v1` | `event` |

注意：Android 代码默认的 group event topic 名称与 Armada/Web 默认值存在命名差异。实施前必须以目标环境配置
核对当前 Android 群事件实际落点和 Armada consumer 订阅范围；本方案要求复用并对齐现有物理 topic，不以新增
topic 掩盖配置不一致。

所有命令和结果 Kafka key 均为 `protocolAccountId`：

- 同账号命令在一个 partition 内有序；
- 不要求同一群跨账号全局有序，跨账号乱序由 `observedAt + eventId/commandId` 版本规则收敛；
- Android node-suffixed topic 继续使用现有路由规则，本方案不改变节点隔离方式。

## 7. 命令契约

### 7.1 `group.snapshot_sync.requested`

```json
{
  "commandId": "cmd_01...",
  "commandType": "group.snapshot_sync.requested",
  "version": "v1",
  "protocolAccountId": "protocol-account-100",
  "traceId": "trace-...",
  "createdAt": "2026-08-16T04:30:00.000Z",
  "payload": {
    "tenantId": 1,
    "accountId": 100,
    "groupLinkId": 5001,
    "groupJid": "120363xxx@g.us",
    "scopes": ["METADATA", "INVITE_CODE"],
    "source": "ACCOUNT_GROUP_INITIAL_SYNC",
    "taskType": "GROUP_METADATA_SYNC",
    "taskId": 9001,
    "attemptNo": 1
  }
}
```

字段规则：

- `commandId` 全局唯一，Outbox 幂等与结果关联都使用它；
- envelope `protocolAccountId` 必须与执行账号当前协议绑定完全一致；
- `groupLinkId` 是 Armada 内部关联，协议端只回传，不能把它当 WhatsApp 标识；
- `groupJid` 必须是规范化 `@g.us`；
- `scopes` 非空、去重，只允许 `METADATA`、`INVITE_CODE`；
- `source` 第一阶段允许 `ACCOUNT_GROUP_INITIAL_SYNC`、`MANUAL_INFO_REFRESH`、
  `MANUAL_INVITE_REFRESH`、`REPAIR`、`BACKFILL`；
- `taskType/taskId` 用于结果落回原任务，协议端不得修改；
- 重试邀请码时使用新 `commandId`，`scopes` 只带 `INVITE_CODE`，`attemptNo` 递增；
- payload 不传旧邀请码，不把敏感 code 放进 Kafka key、header 或日志。

### 7.2 为什么不是复用 `group.members.query.requested`

现有 `group.members.query.requested` 的 payload 和 correlation 专用于普通拉群任务成员确认，关联
`pullTaskId/groupExecutionId/queryId/purpose`。强行扩展会把群建档与拉群状态机耦合。本方案复用命令总线和 topic，
但使用独立且明确的群快照命令类型。

## 8. 结果事件契约

### 8.1 `group.snapshot_sync_result_reported`

```json
{
  "eventId": "protocol-account-100:group.snapshot_sync_result_reported:cmd_01...",
  "event": "group.snapshot_sync_result_reported",
  "version": "v1",
  "accountId": "protocol-account-100",
  "occurredAt": "2026-08-16T04:30:02.000Z",
  "data": {
    "commandId": "cmd_01...",
    "tenantId": 1,
    "accountId": 100,
    "protocolAccountId": "protocol-account-100",
    "protocolBackend": "WEB",
    "groupLinkId": 5001,
    "groupJid": "120363xxx@g.us",
    "source": "ACCOUNT_GROUP_INITIAL_SYNC",
    "taskType": "GROUP_METADATA_SYNC",
    "taskId": 9001,
    "attemptNo": 1,
    "metadata": {
      "outcome": "SUCCESS",
      "observedAt": 1786854600000,
      "snapshotComplete": true,
      "subject": "群名称",
      "description": "群描述",
      "avatarUrl": null,
      "memberCount": 3,
      "ownerJid": "8613800000000@s.whatsapp.net",
      "createdAtSeconds": 1700000000,
      "announceOnly": false,
      "adminOnlyEditInfo": true,
      "memberAddMode": false,
      "joinApprovalMode": false,
      "ephemeralDurationSeconds": 0,
      "participants": [
        {
          "jid": "8613800000000@s.whatsapp.net",
          "phone": "8613800000000",
          "lid": null,
          "role": "ADMIN",
          "owner": true
        }
      ]
    },
    "invite": {
      "outcome": "FAILED",
      "observedAt": 1786854600100,
      "inviteCode": null,
      "errorCode": "GROUP_PERMISSION_DENIED"
    }
  }
}
```

### 8.2 独立 scope 结算

- 命令没有请求某 scope 时，对应字段必须缺失，不能伪造 `FAILED`；
- metadata `SUCCESS` 必须包含 `snapshotComplete=true` 才能替换完整成员快照；
- metadata 失败不能阻止 invite 成功结果落库；
- invite 失败不能阻止 metadata 和成员快照落库；
- invite 成功只传 `inviteCode`，完整 URL 由控端按固定域名派生；
- `inviteCode` 保留原始大小写，不得转小写；
- `observedAt` 是协议请求开始观察时间，用于拒绝晚到旧结果；
- 协议端不得回传原始 stanza/node、凭据或完整错误堆栈。

### 8.3 稳定 outcome 与错误码

scope outcome 只允许：

- `SUCCESS`：已经取得并解析合法结果；
- `FAILED`：明确失败，带稳定 `errorCode`。

第一期错误码：

| errorCode | 含义 | 控端动作 |
|---|---|---|
| `GROUP_PERMISSION_DENIED` | 当前账号没有读取邀请码权限 | 保留 metadata，换下一候选，只查邀请码 |
| `GROUP_NOT_JOINED` | 当前账号不在群或关系已过期 | 更新该账号关系，换候选 |
| `ACCOUNT_NOT_ONLINE` | 协议账号当前不可执行 | 换在线候选；无候选则 DEFERRED |
| `ACCOUNT_BUSY` | 单账号正在执行互斥动作 | 短退避后重试 |
| `TIMEOUT` / `NETWORK` | 瞬时链路失败 | 当前账号有限重试，再换候选 |
| `GROUP_UNAVAILABLE` | 群终止、封禁或不可访问 | 更新群健康，停止普通重试 |
| `PAYLOAD_TOO_LARGE` | 单群结果超过事件安全阈值 | 不发布超大成功体，进入分片/人工修复 |
| `UNKNOWN` | 未识别错误 | 有限重试并告警，不猜成功 |

Web 和 Android 必须在协议边界完成 401/403/不在群语义归一，不能让 Armada 解析错误文案。

## 9. 批量去重与派发

### 9.1 去重单位

首次关系回报按账号落库，但群快照任务按 `tenantId + groupLinkId` 唯一。现有
`group_metadata_sync_task` 已有该唯一键，可以继续承担自动首次建档和修复任务。

100 个账号都看见同一批 500 个群时：

- 账号群关系最多可以形成 50,000 条；
- `group_metadata_sync_task` 只保留 500 行；
- 运行中再次收到同群触发只设置 `rerun_requested`，不并发派发第二条首次快照命令；
- 手工刷新属于用户显式新鲜度要求，可以保留独立 `group_batch_task_item`，但仍复用同一个命令执行器。

### 9.2 触发时机

自动创建/唤醒快照任务的条件：

- 群首次被账号完整快照发现且尚无完整 metadata；
- 自建群缺少当前邀请码；
- 事件解析失败或状态矛盾进入低频修复；
- 运维 backfill。

不触发条件：

- 普通账号重连且群已有成功快照、没有 dirty/修复需求；
- 已经能够由 `group.participant_changed` 或 `group.metadata_updated` 直接解释的正常变更；
- 仅仅因为同一群又被另一个账号的群列表看见。

### 9.3 背压

- Armada 每轮只 claim 有界任务并批量写 Outbox，不等待协议结果；
- Kafka key 按账号分区，协议端继续使用单账号 operation gate；
- 控端限制单账号同时等待的群查询数，默认沿用现有账号并发上限；
- 新账号批量导入时，查询压力由 Kafka lag 吸收，不能用无界线程池换取表面速度；
- result consumer 与命令 dispatcher 分开扩缩容。

## 10. 执行账号选择与邀请码轮换

### 10.1 候选顺序

候选必须满足：当前租户、账号正常、协议在线、当前关系为在群、协议绑定有效。稳定排序：

1. 已确认群主/管理员；
2. 已确认普通成员；
3. 同角色内按最近在群时间和账号 ID 稳定排序。

默认最多尝试 4 个候选。该上限沿用当前群查询 selector 的有界候选口径，防止一个群无限试探。

### 10.2 首次查询

首次任务选择第一个可用账号，请求 `METADATA + INVITE_CODE`。即使它是普通成员：

- metadata 成功后立即保存完整成员和角色；
- invite 成功则保存 code，任务完成；
- invite 权限不足则利用刚落库的成员角色重新选择受控管理员；
- 没有受控管理员时继续尝试其他受控普通成员，因为群设置可能允许普通成员读取。

### 10.3 后续重试

- metadata 已成功后，后续命令 `scopes` 只能是 `INVITE_CODE`，不得重复拉完整成员；
- `GROUP_PERMISSION_DENIED` 消耗一个候选，不消耗 metadata 成功；
- `GROUP_NOT_JOINED` 同时校准当前账号群关系；
- 已尝试账号集合必须持久化或能由任务 attempt 记录恢复，应用重启后不能从第一个账号无限循环；
- 全部候选失败时邀请码状态为“暂未取得/权限不足”，保留旧 code，不写空值覆盖。

未来协议解析出独立“成员可访问邀请链接”权限后，selector 可直接跳过明确无权限的普通成员；在此之前以服务端
查询结果为准。

## 11. 任务状态与数据模型

### 11.1 自动快照任务

复用 `group_metadata_sync_task`，把当前同步 HTTP 的 `RUNNING` 语义调整为“命令已 claim，可能正在等待 Kafka 结果”。
建议通过下一 Flyway 增加：

| 字段 | 用途 |
|---|---|
| `current_command_id` | 当前等待结果的命令 ID，结果 CAS 关联 |
| `requested_scope_mask` | 本次请求 scope，1=METADATA、2=INVITE_CODE |
| `completed_scope_mask` | 已成功落库 scope，避免邀请码重试重复 metadata |
| `candidate_cursor` | 已消费候选位置，应用重启后继续轮换 |
| `result_deadline_at` | 等待结果超时水位 |

现有 `execution_account_id/attempt_count/lease_until/rerun_requested` 继续复用。`current_command_id` 必须唯一或至少有
索引，结果 consumer 使用 `tenantId + taskId + commandId` CAS，旧结果只能确认消费，不能结算新 attempt。

### 11.2 人工批量任务

复用 `group_batch_task/group_batch_task_item` 展示用户进度。明细增加或等价保存：

- `DISPATCHED/WAITING_RESULT` 状态；
- `current_command_id`；
- `attempt_count/candidate_cursor/result_deadline_at`；
- 已完成 scope。

原 `GroupBatchLinkRefreshWorker` 和 `GroupBatchInfoRefreshWorker` 不再等待 HTTP，而是选择账号、写 Outbox 并把明细
转为等待结果。结果 consumer 在独立事务逐项结算成功/失败，前端轮询 API 不变。

### 11.3 Outbox

不增加表，只增加构造 `group.snapshot_sync.requested` 行的 service 方法：

```text
command_type       = group.snapshot_sync.requested
aggregate_type     = GROUP_METADATA_SYNC_TASK 或 GROUP_BATCH_TASK_ITEM
aggregate_id       = 对应 task/item id
kafka_topic        = 按 Web/Android 使用现有配置
kafka_key          = protocolAccountId
protocol_account_id= protocolAccountId
protocol_backend   = WEB/ANDROID
```

业务任务状态和 Outbox 行必须在同一数据库事务写入，禁止先标记已派发再单独写 Outbox。

## 12. 协议端执行

### 12.1 Web/Baileys

1. 在 master command type 白名单加入 `group.snapshot_sync.requested`；
2. master 按现有 owner 路由将命令交给持有账号 socket 的 worker；
3. worker 校验 payload、当前 socket generation 和账号可用状态；
4. `METADATA` 调用 `groupMetadata(groupJid)` 并完整解析成员 PN/LID、角色和群设置；
5. `INVITE_CODE` 调用 `groupInviteCode(groupJid)`；
6. 把 `not-authorized` 规范为 `GROUP_PERMISSION_DENIED`，把明确不在群规范为 `GROUP_NOT_JOINED`；
7. 各 scope 独立捕获结果并生成一个统一结果事件；
8. 结果成功获得 broker ACK 后才确认输入命令；发布失败则保留/重试输入命令；
9. commandId 重放优先补发已缓存结果，避免重复 WhatsApp 查询。

现有 HTTP route 可以继续服务低频兼容调用，但 Kafka worker 不应反向 HTTP 调自己，必须直接复用 socket 执行器和解析器。

### 12.2 Android/WGP2

1. 在现有 group-action 命令消费者加入 `group.snapshot_sync.requested`；
2. 按 `wsPhone/protocolAccountId` 定位当前账号运行实例；
3. `METADATA` 调用当前详细群信息能力，并解析完整设置和成员 PN/LID/角色；
4. `INVITE_CODE` 调用现有 `GetGroupCode` 对应能力；
5. 修正 401/403 语义映射，尽量区分权限不足与不在群；
6. 使用现有 group event publisher 发送统一结果事件；
7. Kafka publish 或本地可靠失败队列确认前，不提交输入命令 offset；
8. WhatsApp notification/IQ ACK 只代表协议收包，不推进 Armada 任务成功状态。

## 13. Armada 结果消费与落库

`ProtocolGroupEventConsumer` 增加 `group.snapshot_sync_result_reported` 分支，先做信封校验，再进入领域 sink：

1. envelope `accountId == data.protocolAccountId`；
2. tenant、Armada account、backend、当前协议绑定有效；
3. `groupLinkId/groupJid` 指向同一当前群；
4. `taskType/taskId/currentCommandId` CAS 匹配；
5. scope 与原命令请求一致；
6. metadata 完整成功时调用结构化快照 reducer，禁止再次调用协议；
7. 成员快照更新成员事实、管理员角色和受控账号群关系；
8. invite 成功调用当前邀请码 reducer，并按观察时间防止旧 code 复活；
9. invite 权限失败时选择下一候选并写下一条 Outbox；
10. 所有需要 scope 结算后完成任务；存在 `rerun_requested` 时重新进入 PENDING。

结果事件至少一次投递：

- `eventId/commandId` 重复不重复写邀请历史、不重复增加任务计数；
- 旧 commandId 不覆盖新 attempt；
- 较旧 observedAt 不覆盖更新 metadata 或邀请码；
- 数据库提交成功后才能提交 Kafka offset；
- 非法结果进入现有重试/DLT，不能静默跳过。

## 14. 消息大小与隐私

一群一条完整结果，500 个群对应约 500 条独立事件，不把 500 群合并成一个 Kafka 消息。群详情的主要体积来自
participants；正常单群完整响应预期远低于 Kafka 常见单消息上限，但实现不能依赖猜测：

- 协议端序列化后记录脱敏的 `payload_bytes` 指标；
- 第一阶段设置 800 KiB 安全阈值，超过阈值不尝试发送超大成功事件，回报/记录 `PAYLOAD_TOO_LARGE`；
- 上线前用最大成员群的 Web/Android 实际脱敏 fixture 测量序列化大小；
- 若实际可能超过阈值，再引入 `snapshotId + participantChunks` 分片，这是独立契约升级，不在 v1 静默截断成员；
- 禁止只截前 N 个成员后仍声明 `snapshotComplete=true`；
- Kafka producer 使用现有压缩和最大消息配置，不在本文硬编码 broker 参数；
- 日志不得记录完整 participants、邀请码、原始 node 或手机号列表，只允许数量、稳定 ID 和邀请码后缀。

## 15. 配置、监控与告警

建议新增业务开关和调度参数，但不新增 topic 配置：

- `armada.group-snapshot-kafka.enabled`：Kafka 查询主链总开关；
- `armada.group-snapshot-kafka.dispatch-batch-size`；
- `armada.group-snapshot-kafka.account-concurrency`；
- `armada.group-snapshot-kafka.result-timeout-ms`；
- `armada.group-snapshot-kafka.max-candidates=4`；
- Web/Android 对应命令执行开关；
- `armada.group-snapshot-kafka.http-fallback-enabled`：滚动发布紧急回滚开关，正常关闭。

指标：

- `group_snapshot_task_total{source,status}`；
- `group_snapshot_command_total{backend,scope,result}`；
- `group_snapshot_result_total{backend,metadataOutcome,inviteOutcome}`；
- `group_snapshot_candidate_switch_total{reason}`；
- `group_snapshot_payload_bytes{backend}`；
- `group_snapshot_queue_lag_seconds{backend}`；
- `group_snapshot_end_to_end_seconds{source}`；
- `group_snapshot_stale_result_total{reason}`；
- `group_snapshot_permission_denied_total{backend,role}`；
- Outbox pending/retry、命令 topic lag、group event consumer lag 和 DLT 数量。

告警至少覆盖：结果超时、持续权限失败、payload 超限、DLT、新账号批次长时间未完成、同账号队列异常积压。

## 16. 发布与回滚

### 16.1 发布顺序

1. 确认目标环境 Web/Android 命令 topic 与群结果 topic 实际配置，不创建新 topic；
2. Armada 部署结果 consumer、任务关联字段和兼容 reducer，此时不派发新命令；
3. Web 部署新 commandType 解析和结果 publisher，保持开关关闭；
4. Android 部署新 commandType 解析和结果 publisher，保持开关关闭；
5. test1 先启用单账号、单群，再启用 10 群；
6. 验证 Web/Android 各自 metadata、邀请码成功和权限失败轮换；
7. 以 100 账号、500 唯一群规模压测去重、lag、消息大小和落库；
8. 打开 Kafka 主链，关闭批量 HTTP 主链；
9. 稳定后再评估删除不再使用的 HTTP 批量执行代码。

### 16.2 回滚

- 关闭 `armada.group-snapshot-kafka.enabled`，停止产生新查询命令；
- 已进入 Kafka 的命令允许完成并按 commandId 幂等落库，或由结果超时任务终止；
- 必要时临时打开受控 HTTP fallback，仅用于首次建档/人工刷新，不恢复普通事件后查询；
- 新增任务关联列保留，不做破坏性回滚；
- consumer 可继续接受滚动期间的晚到结果；
- 回滚不能清空已经确认的群资料、成员或邀请码。

## 17. 任务拆分

每项控制在 4 小时以内，按依赖顺序执行：

1. 固定命令/结果 JSON fixture、scope 和错误码；
2. Armada Flyway：任务 commandId、scope、候选游标和结果截止时间；
3. Armada Outbox：新 commandType、Web/Android topic 路由和同事务派发；
4. Armada consumer：结果校验、部分 scope 结算和幂等 CAS；
5. Armada reducer：把当前“读取+落库”拆成纯结构化结果落库，复用新群模型兼容双写；
6. Armada selector：管理员优先、普通成员兜底、持久候选轮换；
7. Web executor：metadata/invite 查询、错误归一、结果缓存与发布；
8. Android executor：metadata/invite 查询、错误归一、结果缓存与发布；
9. 自动首次建档接入：账号群回报去重 upsert 快照任务；
10. 手工刷新接入：现有 batch item 从 HTTP worker 改为 Outbox/等待结果；
11. 监控、开关、超时恢复和 DLT；
12. test1 联调与 100 账号/500 群压测。

## 18. 测试与验收

### 18.1 Armada 单测/数据测试

- 100 个账号各回报同一批 500 群，只产生 500 条自动快照任务；
- 任务 claim 与 Outbox 行在同一事务，任一失败都不出现“已派发但无命令”；
- Web/Android 命令分别写入当前配置 topic，key 均为 protocolAccountId；
- metadata 成功、invite 权限失败时 metadata 和成员先落库，随后只派发 invite scope；
- 管理员失败后按稳定顺序尝试普通成员，最多 4 个候选；
- 重复结果、旧 commandId、迟到 observedAt 不覆盖新事实；
- 完整成员快照更新受控账号群关系和管理员角色；
- `GROUP_NOT_JOINED` 校准单账号关系，不错误清空其他账号关系；
- 全部候选失败保留旧邀请码；
- 手工批量任务能实时看到等待、成功、失败和取消；
- H2 加载真实 Mapper XML 验证租户隔离和事务；MySQL 8.4 验证唯一键/CAS/并发。

### 18.2 Web 协议测试

- master 接受并路由新 commandType，未知 scope 拒绝；
- metadata 和 invite 都成功时发布一条组合结果；
- metadata 成功、invite 401 时发布部分成功，不丢 metadata；
- `not-authorized`、不在群、离线、超时映射稳定错误码；
- 结果 Kafka ACK 前不确认输入命令；
- commandId 重放不重复请求 WhatsApp；
- payload 超阈值不发布截断成功快照。

### 18.3 Android 协议测试

- group-action consumer 接受新命令并调用正确账号；
- 详细群信息与 GetGroupCode 都被完整解析；
- 普通成员权限开/关的真实脱敏响应分别得到成功/`GROUP_PERMISSION_DENIED`；
- 401/403/不在群、离线、超时映射稳定；
- Kafka/DLQ 与输入 offset 提交顺序正确；
- payload 超阈值不伪造完整快照。

### 18.4 test1 验收

1. 单个 Web 管理员群首次同步；
2. 单个 Android 管理员群首次同步；
3. 普通成员关闭邀请访问权限，metadata 成功、invite 权限失败；
4. 打开成员邀请访问权限，同一普通成员取得邀请码；
5. 无受控管理员但有获权普通成员时补齐群链接；
6. 一个账号退出群后换另一在群账号；
7. 100 个账号、500 个唯一群批量同步；
8. 重复投递、应用重启、结果迟到、Kafka 短暂不可用和回滚演练。

验收标准：

- Armada 批量主链不再同步发起 metadata/invite HTTP；
- 500 个唯一群首次任务数为 500，不随账号群关系数膨胀；
- 每个成功项都能串起 `task -> outbox -> command -> parsed result -> Armada applied`；
- metadata、成员、管理员、账号群关系和邀请码在现有页面/API 可读；
- 权限失败不丢已成功 metadata，不清空旧邀请码；
- Broker ACK、WhatsApp ACK 均不能单独把业务任务标成功；
- Kafka/DLT 无持续失败，消息大小和队列 lag 在压测报告中有真实数据；
- 普通群增量事件仍保持零次 metadata 后置查询。

## 19. 影响清单

| 领域 | 影响 |
|---|---|
| API/前端 | 页面 API 不变；批量任务继续轮询现有接口 |
| Kafka topic | 不新增，复用 Web master、Android group-action、group event |
| Kafka contract | 新增一个 commandType 和一个 result event |
| Outbox | 复用现有表，新增构造/校验/路由方法 |
| 数据库 | 为自动任务和人工明细补异步 command/result 关联字段与状态 |
| Web 协议 | 新增 Kafka executor、完整解析、错误归一和结果可靠发布 |
| Android 协议 | 新增 group-action executor、完整解析、错误归一和结果可靠发布 |
| Armada group 域 | 查询与落库解耦、候选轮换、结果 reducer、超时恢复 |
| Redis | Web/Android 可复用现有 commandId 结果状态存储；具体 key 遵循现有命令幂等组件 |
| 隐私 | 邀请 code 不进日志；成员只传落库必需的规范身份和角色 |

## 20. 事实、推断与未确认项

### 20.1 已确认事实

- 当前 Armada 已有通用 Outbox，topic 由每行决定；
- 当前 Web master topic 承载多个 commandType；
- 当前 Android 群成员查询复用 group-action command topic；
- 当前群结果已存在 group event 消费通道；
- 当前 metadata/invite 主动读取仍由 Armada 同步 HTTP 调用；
- 当前自动 metadata 任务已按租户和群唯一；
- 当前 Web/Android 邀请码协议请求都由 WhatsApp 服务端鉴权；
- 当前普通成员是否可以读取邀请码不能由现有 metadata 字段可靠判断。

### 20.2 设计推断

- 500 个独立小命令适合现有 Kafka/Outbox，不需要新增 topic；
- 按群去重、按账号 key 分区和有界候选轮换可以避免 5 万关系放大；
- 组合查询结果按 scope 独立结算，可以避免重复 metadata 查询；
- 任务状态持久化 commandId、scope 和候选游标，能够在至少一次投递和进程重启下稳定恢复。

### 20.3 实施前必须确认

- test1/生产当前 Android group event topic 与 Armada consumer 的真实配置是否已经对齐；
- Web/Android 最大成员群的实际序列化 payload 大小，确认 800 KiB 阈值是否合适；
- Android 详细群接口返回是否包含本文 metadata 全部字段及完整成员 PN/LID；缺字段必须在协议端补解析，不能由控端猜；
- 普通成员邀请访问权限开/关的真实脱敏报文和错误码；
- 在途群数据模型 Flyway 版本与本功能下一 migration 编号，禁止覆盖现有 V121/V122；
- 人工批量任务取消后，已经进入 Kafka 的只读查询结果是允许幂等落库群事实，还是只丢弃任务结算；建议允许落库事实、
  不再增加已取消任务的成功数。
