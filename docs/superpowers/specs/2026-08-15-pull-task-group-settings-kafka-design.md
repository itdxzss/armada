# 普通群链接拉群群设置异步化与关闭进群审核设计

日期：2026-08-15
状态：已实现

## 1. 背景与问题

普通群链接拉群在 `MANAGER_ADMIN` 阶段确认任务管理员已获得群管理员权限后，进入 `MANAGER_PULLER_CONTACT`
阶段。该阶段开头有一段事务外的群设置逻辑 `PullTaskManagerPullerContactProcessor.ensureMemberAddPermission`，
作用是把目标群的「谁可以添加成员」从 `admin_add` 放开为 `all_member_add`。这是硬前置：拉手进群后是普通成员，
`PULL_EXECUTION` 阶段靠拉手 add 料子，群若仍是 `admin_add`，拉手一加人就失败。

现状有两个问题。

### 1.1 群设置走同步 HTTP，与拉群其余协议动作不同构

拉群的 promote、单人邀请、批量 add 全部走 outbox + Kafka 命令，有 `commandId + attemptNo` 收敛状态机。
只有群设置是同步 HTTP：

- Web 账号：`HttpGroupSettingsAdapter` → `POST /v1/groups/{jid}/settings/member-add-mode`
- Android 账号：`AndroidNativeGroupSettingsAdapter` → `POST /ws/v1/groups/settings/join-mode/{wsPhone}`

同步调用在调度线程内阻塞到超时，且因为 HTTP 200 只代表协议层收到，代码必须再回读一次群元数据确认，
一次设置最多产生三次同步 HTTP 往返。

### 1.2 缺少「关闭管理员审核进入」

目标群若开启了入群审批（`membership_approval_mode`），通过邀请链接自主进群的账号会卡在待审核。
拉群链路里补充管理员是踩链接进群的（`PullTaskSupplementManagerProcessor` 会返回 `entryPendingApproval`），
提权完成后若不关闭审批，补充管理员补位会持续失败。

## 2. 目标与非目标

### 2.1 目标

1. 任务管理员提权确认后、占用拉手之前，由该管理员账号放开加人权限并关闭进群审核。
2. 群设置从同步 HTTP 改为 outbox + Kafka 异步命令，与 promote、邀请、批量 add 使用同一条通道和同一套收敛模型。
3. 去掉群元数据回读确认，改以协议动作结果事件为准。
4. 补齐 Android 协议后端的 join-approval 能力。
5. 加人权限失败仍阻断并退避重试；关闭审核失败不阻断。

### 2.2 非目标

- 不改群详情页手工修改群设置（`GroupDetailServiceImpl`）。那是同步交互，异步化需要改前端，收益为零。
- 不改新建普群链路。它已经走 Kafka 的 `GROUP_SETTINGS_APPLY`，本次完全不触碰。
- 不删除 `GroupSettingsPort` 及其 HTTP 适配器，群详情页仍在使用。
- 不新增 Kafka topic。
- 不新增任务级或全局级配置开关，关闭进群审核是固定行为。
- 不改动群资料设置（`pull_task_standard_group_setting`）的应用时机与语义。

## 3. 设计原则

### 3.1 一条命令一个动作

拉群现有的协议命令全部是单动作粒度：`pull_task_manager_admin` 一条只 promote 一个人，
`pull_task_puller_invite` 一条只邀请一个拉手，Android spec 上标着 `singleTarget: true`。
一条命令对应一个 `commandId`、一行 `pull_task_account_action`、一个结果事件、一个 `reasonCode`。

本次沿用该粒度：**放开加人权限和关闭进群审核是两条独立命令**，不合并。

合并的唯一动机本是省一次 Kafka 往返，代价却是要在结果事件里发明「逐项结果数组」这种新契约，
并让协议层承担「哪项失败算数」的业务判断。拆开之后这两样都不需要：命令级的 `outcome` 与
`reasonCode` 就是该设置的结果，用的是现成格式。

拆开常见的顾虑是「两次回调乱序，不知道何时该推进」。这里不存在该问题，原因见 3.2。

### 3.2 阻断规则只表达为「哪个动作是推进条件」

| 动作 | 失败后果 | 是否推进条件 |
| --- | --- | --- |
| 放开加人权限 | 阻断，退避重试 | 是 |
| 关闭进群审核 | 只记录 `reason_code`，不重试 | 否 |

执行行的推进只等加人权限那条命令的结果。关闭审核的结果到达时只更新自己的动作行，不触碰执行行，
因此它何时到达、成功与否都不影响阶段推进，没有乱序问题。

这条规则写在 armada 的结果收敛器里。将来若某场景要求关审核也必须成功，改控端一处即可，
协议层不需要发布。

### 3.3 协议层不做业务判断

协议层只做协议映射：收到一条命令，发一条 IQ，如实上报成败与原因码。
哪条失败要阻断、哪条可以放过，全部由控端决定。

这与既有分层一致。`HttpGroupSettingsAdapter` 的类注释已写明「执行账号选择、管理员权限判断、
超时回读确认和业务异常转换均由上层 Service 负责」。

### 3.4 不回读

同步 HTTP 时代回读是防御「HTTP 200 只代表协议层收到了命令」。异步命令的
`group.action_result_reported` 是协议层真正把 IQ 发完之后才发出的执行结果，不是提交回执。
判据从「回读 metadata 的 `memberAddMode`」换成「结果事件的 outcome 与 reasonCode」。

Android 的群元数据本来也不返回 `joinApprovalMode`，回读对关审核这一项从来就不可用。

### 3.5 顺序保证

Outbox 行的 `kafka_key` 是 `protocolAccountId`。同一管理员账号的 promote 命令与群设置命令落在同一分区，
严格保序，不会出现「设置命令先于提权命令被消费、账号还不是管理员因而被拒」的竞态。
同步 HTTP 版本没有这个保证。

## 4. 业务流程

阶段枚举不变，仍是八段。改造发生在 `MANAGER_PULLER_CONTACT` 阶段内部。

```text
MANAGER_ADMIN 提权确认成功
  -> 阶段推进到 MANAGER_PULLER_CONTACT
  -> 有补充拉手指令则优先处理，本段不执行
  -> 短事务：复核租约、选定管理员账号、取 groupJid
  -> 短事务：写「放开加人权限」动作行(SUBMITTED) + outbox 行(PENDING)，原子提交
  -> 释放租约，等待结果事件

  -> 加人权限结果到达
       失败 -> 保留阶段，按原因码退避重试，重新提交命令
       成功 -> 同一事务内：
                 写「关闭进群审核」动作行 + outbox 行
                 唤醒执行行，next_run_at 置为立即
  -> 短事务：占用拉手、创建管理—拉手联系人动作、推进到 PULLER_INVITE
       （不等待关闭审核的结果）

  -> 关闭进群审核结果到达（时点不定，可能在推进之后）
       成功 -> 动作行 SUCCESS
       失败 -> 动作行 FAILED + reason_code，不重试，不影响任何阶段
```

改造前该段是「事务外同步 HTTP 设置 + 回读确认 + 未确认则 defer」，改造后是「写命令 + 等回调」。

### 4.1 为什么关闭审核在加人权限成功之后才发

两条命令若同时发出，加人权限因账号无管理员权限而失败时，关闭审核必然同样失败；
之后加人权限重试成功，关闭审核却因为不重试而永远停在失败态，目标群审批一直开着。

改为串行发出后，关闭审核只在管理员权限已被协议层实际验证可用之后发出一次，
它的一次性不重试才是安全的。

代价是关闭审核晚一个 Kafka 往返，但它不阻断任何阶段，晚到无影响。

## 5. 命令与事件契约

### 5.1 Topic

不新增 topic，复用现有两条。

| 方向 | 账号 backend | Topic |
| --- | --- | --- |
| 命令 | WEB | `protocol.master.commands.v1` |
| 命令 | ANDROID | `protocol.android.group-action.commands.v1` |
| 结果 | 两者 | `protocol.group.events.v1` |

结果事件由 `ProtocolGroupEventConsumer` 消费，消费组 `armada-api-group-events`。

避开新建普群的独立通道（`protocol.android.normal-group.commands.v1` / `protocol.normal-group.events.v1`）。

### 5.2 命令标识

两条命令共用命令类型与 source，靠 payload 的 `setting` 字段区分。

| 字段 | 取值 |
| --- | --- |
| `commandType` | `group.settings.requested`（新增） |
| `source` | `pull_task_group_settings`（新增） |
| `setting` | `memberAdd` 或 `joinApproval` |
| `aggregateType` | `PULL_TASK_ACCOUNT_ACTION` |
| `aggregateId` | 对应动作行 ID |
| `kafkaKey` | 管理员的 `protocolAccountId` |

`normal_group_creation` 的 `GROUP_SETTINGS_APPLY` 是另一个命令类型，与本契约无交集。

### 5.3 Outbox 引用 payload

Outbox 只持久化轻量业务引用，不含号码、群 JID 和协议账号句柄，与现有 pull_task 命令族一致：

```json
{
  "tenantId": 1,
  "pullTaskId": 100,
  "groupExecutionId": 2000,
  "actionId": 30000,
  "source": "pull_task_group_settings"
}
```

设置项由动作行的 `action_type` 推出，不必冗余存进引用。

### 5.4 发布时补全的 wire payload

新增 `PullTaskGroupSettingsPayloadHydrator`，`supports` 判据为
`commandType = group.settings.requested` 且 `aggregateType = PULL_TASK_ACCOUNT_ACTION`。
按动作行的 `action_type` 决定 `setting` 与 `enabled`：

```json
{
  "tenantId": 1,
  "pullTaskId": 100,
  "groupExecutionId": 2000,
  "actionId": 30000,
  "accountId": 15,
  "protocolAccountId": "acc_xxx",
  "accountPhone": "8613900000000",
  "protocolBackend": "WEB",
  "groupJid": "1203xxxx@g.us",
  "setting": "memberAdd",
  "enabled": true,
  "timeoutMs": 30000,
  "attemptNo": 1,
  "source": "pull_task_group_settings"
}
```

| `action_type` | `setting` | `enabled` |
| --- | --- | --- |
| `OPEN_MEMBER_ADD` | `memberAdd` | `true`（全体成员可加人） |
| `CLOSE_JOIN_APPROVAL` | `joinApproval` | `false`（关闭进群审核） |

### 5.5 结果事件

沿用 `group.action_result_reported`，不新增字段。

| 字段 | 取值 |
| --- | --- |
| `data.source` | `pull_task_group_settings` |
| `data.operation` | `group_settings_apply` |
| `data.setting` | `memberAdd` 或 `joinApproval`，原样回显 |
| `commandId` / `attemptNo` | 与命令一致 |
| `outcome` | `SUCCESS` / `FAILED` |
| `reasonCode` / `reasonMessage` | 失败时的协议错误码与脱敏描述 |

一条命令一个设置，因此命令级 `outcome` 就是该设置的结果，不需要逐项结果数组。

## 6. 数据模型

### 6.1 动作类型扩展

`pull_task_account_action.action_type` 新增两个取值。两条命令是两个不同的动作，
不能靠同一个类型加判别字段区分——唯一键 `uq_pull_task_action_pair` 包含 `action_type`，
两行的 `actor` 与 `target` 相同，必须靠 `action_type` 才能共存。

`PullTaskAccountActionType` 新增：

```java
/** 放开加人权限：任务管理员把群设置为全体成员可添加成员。 */
OPEN_MEMBER_ADD(5),
/** 关闭进群审核：任务管理员关闭群的管理员入群审批。 */
CLOSE_JOIN_APPROVAL(6);
```

### 6.2 唯一键处理

现有唯一键 `uq_pull_task_action_pair (tenant_id, group_execution_id, action_type,
actor_group_account_id, target_group_account_id)`，两个账号列都是 `NOT NULL`。

群设置动作没有「对象账号」。沿用踩链接入群那一行已有的约定（表注释：踩链接入群时 actor 为目标账号自身 ID），
把 `target_group_account_id` 写成与 `actor_group_account_id` 相同的管理员角色行 ID。
于是每个执行行每种群设置动作至多一行，重复调度不会插出第二行。

### 6.3 Flyway

新增 `V119__pull_task_group_settings_action.sql`，只修改 `action_type` 列注释。
列类型与索引不变，无数据迁移。

```sql
ALTER TABLE pull_task_account_action
    MODIFY COLUMN action_type TINYINT NOT NULL
    COMMENT '动作类型:1=保存联系人 2=邀请入群 3=踩链接入群 4=设置任务管理员 5=放开加人权限 6=关闭进群审核';
```

## 7. armada 侧改造清单

### 7.1 新增

| 文件 | 职责 |
| --- | --- |
| `ProtocolPullTaskGroupSettingsCommandRequest` | 命令请求记录，`SOURCE = pull_task_group_settings`，含 actor 与动作行 ID |
| `PullTaskGroupSettingsPayloadHydrator` | 按动作行 `action_type` 补全 `setting` 与 `enabled` |
| `PullTaskGroupSettingsResultService` + `Impl` | 以 `commandId + attemptNo` CAS 收敛结果；仅加人权限那条唤醒执行行 |
| `PullTaskGroupSettingsCallback`（DTO） | 结果事件的领域入参 |
| `V119__pull_task_group_settings_action.sql` | 动作类型注释 |

### 7.2 修改

| 文件 | 改动 |
| --- | --- |
| `PullTaskAccountActionType` | 新增 `OPEN_MEMBER_ADD(5)`、`CLOSE_JOIN_APPROVAL(6)` |
| `ProtocolCommandOutboxServiceImpl` | 新增 `COMMAND_TYPE_GROUP_SETTINGS_REQUESTED` 常量与 `enqueuePullTaskGroupSettingsCommands`，topic 路由复用 `backend == ANDROID ? groupActionTopic : masterTopic` |
| `ProtocolCommandOutboxService` | 新增接口方法 |
| `ProtocolGroupEventConsumer` | `handleActionResultReported` 增加 `pull_task_group_settings` 分支 |
| `PullTaskManagerPullerContactProcessor` | 删除 `ensureMemberAddPermission` 与 `memberAddAllowed`，并去掉 `FixedAccountGroupMetadataPort`、`GroupSettingsPort` 两个构造依赖——本阶段不再有任何事务外协议调用 |
| `PullTaskManagerPullerContactTransactionService` | 新增 `ensureGroupSettings`（查动作事实 → 未确认则写动作行 + outbox → 返回等待，全在一个短事务内）；`deferMemberAddPermission` 改名 `deferGroupSettings`；删除 `prepareMemberAddPermission` |
| `PullTaskGroupSettingsGate`（新增） | 门控结果；`open()` 表示加人权限已确认可继续占拉手，`waiting(result)` 表示本轮到此为止 |
| `PullTaskMemberAddPermissionWork` / `PullTaskMemberAddPermissionPreparation` | 随同步路径一并删除，不再有调用方 |
| `PullTaskExecutionReasonCode` | 保留 `GROUP_MEMBER_ADD_PERMISSION_DENIED` / `_UNCONFIRMED`；新增 `GROUP_JOIN_APPROVAL_CLOSE_FAILED`，仅写动作行 `reason_code`，不进执行行 |
| `AndroidNativeClient` | 新增 `setGroupJoinApproval(wsPhone, groupJid, enabled)` |
| `HttpAndroidNativeClient` | 实现之，`GROUP_JOIN_APPROVAL_URI_PREFIX = "/ws/v1/groups/settings/approval/"`，复用 `GroupPermissionRequest` |
| `AndroidNativeGroupSettingsAdapter` | `setJoinApprovalEnabled` 由 `throw unsupported` 改为真实调用 |

`AndroidNativeGroupSettingsAdapter` 的补齐虽然拉群改异步后不再走它，但群详情页手工设置仍用该端口，
Android 账号目前在那里不可用，一并补上。

### 7.2.1 两处经核对后确认不需要改动

**`ProtocolCommandOutboxServiceImpl.cancelPendingPullTaskCommands`。**
它按 `aggregate_type` 过滤而非 `command_type`，群设置命令用的就是 `PULL_TASK_ACCOUNT_ACTION`，
任务结束时自动被一并取消，无需登记新命令类型。

**`PullTaskUnknownResultReconciliationService`。**
该服务靠实时成员快照观察动作效果：邀请和踩链接看目标是否在群内，提权看目标是否成为管理员。
群设置改的是群属性，效果在成员列表里根本观察不到，纳入它没有任何可用判据。

群设置的 `UNKNOWN` 由重发处理，见 7.4，比兜底扫描更直接。

### 7.3 结果收敛语义

`PullTaskGroupSettingsResultService.apply` 先按动作行的 `action_type` 分流。

公共前置与 `PullTaskManagerAdminResultServiceImpl` 同构：

1. 按 `commandId` 定位动作行，校验 `attemptNo`、租户、执行行一致
2. 目标状态已到达则幂等返回 true
3. CAS 从 `{SUBMITTED, UNKNOWN}` 迁移到 `{SUCCESS, FAILED, UNKNOWN}`

**`OPEN_MEMBER_ADD` 分支**，额外 CAS 唤醒执行行（期望 `EXECUTING + MANAGER_PULLER_CONTACT`）：

| 结果 | 动作行 | 执行行 |
| --- | --- | --- |
| 成功 | SUCCESS | 同事务内追加 `CLOSE_JOIN_APPROVAL` 动作行与 outbox 行；`next_run_at` 置为立即 |
| 失败且 `GROUP_PERMISSION_DENIED` | FAILED | 保留阶段，`reasonCode = GROUP_MEMBER_ADD_PERMISSION_DENIED`，退避重试 |
| 其它失败 | FAILED | 保留阶段，`reasonCode = GROUP_MEMBER_ADD_PERMISSION_UNCONFIRMED`，退避重试 |
| 未知 | UNKNOWN | 保留阶段，交由未知结果兜底调度 |

失败重试时重新生成 `commandId` 并递增 `attemptNo`，与提权动作一致。重复设置
`all_member_add` 是幂等 IQ，无副作用。

**`CLOSE_JOIN_APPROVAL` 分支**，只写动作行，一律不触碰执行行：

| 结果 | 动作行 |
| --- | --- |
| 成功 | SUCCESS |
| 失败 | FAILED，`reasonCode = GROUP_JOIN_APPROVAL_CLOSE_FAILED`，不重试 |
| 未知 | UNKNOWN，由兜底调度收口为终态，不重试 |

该分支必须对「执行行已推进到 `PULLER_INVITE` 甚至更后阶段」保持可写。它不读也不 CAS 执行行，
因此结果何时到达都能正常落库。

### 7.4 UNKNOWN 与 FAILED 的重发

`UNKNOWN` 表示协议层无法确认结果。群设置没有可观察的快照可兜底，因此它和 `FAILED` 走同一条路：
下一轮调度时重发命令。两条 IQ（`member_add_mode`、`membership_approval_mode`）都是幂等的，
重复设置无副作用。

`ensureGroupSettings` 只把 `SUBMITTED` 视为命令在途。把 `UNKNOWN` 也算作在途会让执行行永远
退避空转，永不重发。

重发复用同一行动作，通过 `PullTaskAccountActionMapper.submitAttempt` 从
`{PENDING, FAILED, UNKNOWN}` CAS 到 `SUBMITTED`，同时递增 `attempt_no` 并清空上一轮原因。
不可使用 `markSubmitted`——它只接受 `PENDING` 前置态，重发时会写入失败。

`attempt_no` 递增使新旧尝试在动作行上可区分；迟到的旧结果按 `commandId` 已经定位不到该行，
会被收敛器直接拒绝。

## 8. protocol-layer 侧改造

### 8.1 命令类型注册

- `types.ts`：`MasterCommandType` 与 `SUPPORTED_COMMAND_TYPES` 增加 `group.settings.requested`
- `master-router.ts`：按 `protocolAccountId` 路由到 worker，与 `group.participants.requested` 同路径
- `pull-task-action.ts`：新增 `GROUP_SETTINGS_SOURCE_SPECS` 白名单，收录 `pull_task_group_settings`，
  校验 source 与 operation 严格配对，并校验 `setting` 取值在 `memberAdd` / `joinApproval` 之内
- `worker-consumer.ts`：新增 executor 分派

### 8.2 执行器

新增独立的 `group-settings-executor.ts`，**不复用也不改动 `normal-group-creation-executor.ts`**。
建普群那条分支保持原样，本次不触碰，回归面为零。

一条命令只发一条 IQ：

```ts
switch (payload.setting) {
  case 'memberAdd':
    if (!socket.groupMemberAddMode) unsupported('groupMemberAddMode')
    await socket.groupMemberAddMode.call(
      socket, payload.groupJid, payload.enabled ? 'all_member_add' : 'admin_add')
    return
  case 'joinApproval':
    if (!socket.groupJoinApprovalMode) unsupported('groupJoinApprovalMode')
    await socket.groupJoinApprovalMode.call(
      socket, payload.groupJid, payload.enabled ? 'on' : 'off')
    return
}
```

抛出的异常按现有 pull_task 动作结果路径转成 `outcome = FAILED` 与 `reasonCode`。

### 8.3 结果发布

复用现有 pull_task 动作结果发布路径，`source = pull_task_group_settings`，
`operation = group_settings_apply`，回显 `setting`。

## 9. Android 侧改造

### 9.0 现状盘点：已有什么、缺什么

**IQ 协议能力已完整实现，且与 baileys 报文逐字一致，无需重写。** 调用链：

```text
WaApp.SendApproveNewMembers          (internal/service/app/group.go:103)
  -> MainNodeProcessor.CreateApproveNewMembers  (internal/service/node/node_processor.go:841)
  -> IqProcessor.BuildIqApproveNewMembers       (internal/service/node/processor/iq.go:479)
  -> createIqApproveNewMembers                  (internal/service/node/processor/iq.go:2099)
```

该链路已被建普群（`internal/armada/normal_group_creation_sender.go:234`）在生产使用，
HTTP 路由 `POST /ws/v1/groups/settings/approval/:key` 也已注册（`api/router/router.go:144`）。
加人权限走 `SendGroupPermission(groupJID, "member_add_mode", state)`，同样已实现。

| 报文 | baileys 对应 | 状态 |
| --- | --- | --- |
| `<membership_approval_mode><group_join state='on'\|'off'/></membership_approval_mode>` | `groupJoinApprovalMode(jid, 'on'\|'off')` | 已实现，一致 |
| `<member_add_mode>all_member_add\|admin_add</member_add_mode>` | `groupMemberAddMode(jid, ...)` | 已实现，一致 |

**缺的是接线，共两处**，都在本次范围内：

1. Go `internal/armada` 没有独立的群设置命令分发分支。群设置目前只能从建普群那个复合动作内部触发，
   新的 `group.settings.requested` 命令到达后会因 spec 表无匹配项被拒。补 9.1 的 spec 与 9.2 的 executor 分支。
2. armada Java 的 `AndroidNativeGroupSettingsAdapter.setJoinApprovalEnabled` 仍是
   `throw unsupported("join-approval")`，导致 Android 账号在群详情页改该设置直接报错。补 7.2 表中的三个文件。

第 2 项不在拉群异步链路上（拉群改异步后不再走同步端口），但属于同一能力缺口，一并补齐。

### 9.1 命令契约

`internal/armada/group_action_command.go`：

- 新增 `SourcePullTaskGroupSettings = "pull_task_group_settings"`
- 新增 `CommandTypeGroupSettingsRequested = "group.settings.requested"`
- 新增 `operationGroupSettingsApply = "group_settings_apply"`
- `GroupActionCommandPayload` 新增 `Setting string` 与 `Enabled *bool`（指针以区分未传与 false）
- `groupActionSpecs` 增加一条：

```go
{
    commandType: CommandTypeGroupSettingsRequested, source: SourcePullTaskGroupSettings,
    operation: operationGroupSettingsApply,
    aggregateType: aggregateTypePullTaskAccountActionGroupAction,
    key: correlationKeyActionID,
    validateExecution: validatePullTaskGroupSettingsExecution,
},
```

`validatePullTaskGroupSettingsExecution` 要求 `groupJid` 非空、`setting` 在白名单内、`enabled` 非 nil。

建普群的 `GROUP_SETTINGS_APPLY` 契约与校验保持原样，不触碰。

### 9.2 执行

`internal/armada/group_action_executor.go` 新增分支，一条命令发一条 IQ：

- `setting = memberAdd` → `SendGroupPermission(groupJID, "member_add_mode", enabled)`
- `setting = joinApproval` → `SendApproveNewMembers(groupJID, enabled)`

两个方法都已存在（`internal/service/app/group.go:103` 与 `:108`），底层分别落到
`createIqApproveNewMembers` 与 `createIqSetGroupPermission`，报文与 baileys
`groupJoinApprovalMode` / `groupMemberAddMode` 一致：

```xml
<iq xmlns='w:g2' type='set' to='xxx@g.us'>
  <membership_approval_mode><group_join state='off'/></membership_approval_mode>
</iq>
<iq xmlns='w:g2' type='set' to='xxx@g.us'>
  <member_add_mode>all_member_add</member_add_mode>
</iq>
```

底层 IQ 能力与 HTTP 路由都已存在，本节新增的只是 Kafka 命令到这两个方法的分发分支。

### 9.3 结果发布

`internal/armada/group_action_event.go` 复用现有 `group.action_result_reported` 发布路径，回显 `setting`。

## 10. 存量兼容与灰度

### 10.1 存量执行行

改造前停留在 `MANAGER_PULLER_CONTACT` 阶段的执行行，其加人权限可能已经通过同步 HTTP 设置成功，
但没有任何动作行记录。部署后这些执行行会新建加人权限动作并重新提交命令。重复设置
`all_member_add` 是幂等 IQ，无副作用。

不需要数据回补脚本。

### 10.2 版本依赖

armada 的新命令类型必须在 protocol-layer 与 Android 都已支持之后才能发布，否则命令会被
`UNSUPPORTED_COMMAND_TYPE` 拒绝消费，执行行会卡在等待。

发布顺序：protocol-layer 与 Android 先发（新增分支对存量命令无影响），确认两端就绪后再发 armada。

### 10.3 回滚

armada 回滚到旧版本后，同步 HTTP 路径恢复，已写入但未消费的群设置命令会在协议侧被拒绝并进入失败，
对应执行行由未知结果兜底调度回收。不需要清理 outbox。

## 11. 可观测性

- 两条命令的提交与结果收敛各记一条 INFO，字段限于 `taskId`、`executionId`、`actionId`、
  `commandId`、`attemptNo`、`setting`、`outcome`、`reasonCode`，不记录号码、群 JID 和协议账号句柄
- 关闭审核失败单独记 WARN，便于统计目标群开启审批的比例
- 拉群任务详情沿用现有原因码展示，`GROUP_MEMBER_ADD_PERMISSION_DENIED` 与
  `GROUP_MEMBER_ADD_PERMISSION_UNCONFIRMED` 的展示文案不变

## 12. 测试计划

### 12.1 armada 单元测试

- `PullTaskGroupSettingsPayloadHydratorTest`：`OPEN_MEMBER_ADD` 补出 `memberAdd/true`，
  `CLOSE_JOIN_APPROVAL` 补出 `joinApproval/false`；动作行与冻结事实不一致时抛校验异常；租户上下文正确恢复
- `PullTaskManagerPullerContactTransactionIntegrationTest`：加人权限命令提交后动作行为 SUBMITTED
  且带真实 commandId；`UNKNOWN` 与 `FAILED` 下一轮重发新命令并递增 `attempt_no`，且复用同一行动作
- `PullTaskExecutionEndToEndIntegrationTest`：整条链路穿过异步群设置步骤跑到 `COMPLETED`
- `PullTaskGroupSettingsResultServiceTest`
  - 加人权限成功：动作行 SUCCESS、追加关闭审核动作行与 outbox 行、执行行被唤醒
  - 加人权限 `GROUP_PERMISSION_DENIED`：映射 DENIED 原因码、退避、不追加关闭审核动作
  - 加人权限其它失败：映射 UNCONFIRMED 原因码、退避
  - 关闭审核失败：动作行 FAILED + `GROUP_JOIN_APPROVAL_CLOSE_FAILED`，执行行完全不被触碰
  - 关闭审核结果在执行行已推进到 `PULLER_INVITE` 之后到达：仍能正常落库
  - 重复回调幂等；`attemptNo` 不匹配时拒绝
- `PullTaskManagerPullerContactProcessorTest`：补充拉手指令优先，不提交群设置命令；
  提交加人权限命令后返回等待且不占用拉手；动作行与 outbox 行同事务写入
- `ProtocolCommandOutboxServiceImplTest`：WEB 进 master topic，ANDROID 进 group-action topic；
  `kafkaKey` 为 protocolAccountId；批量上限与 commandId 冲突；取消覆盖新命令类型
- `PullTaskGroupSettingsActionMigrationSqlTest`：V119 幂等且只改注释
- `AndroidNativeGroupSettingsAdapterTest`：`setJoinApprovalEnabled` 打到
  `/ws/v1/groups/settings/approval/{wsPhone}`；协议失败映射为 ProtocolException

### 12.2 protocol-layer 测试

- `setting = memberAdd` 只调 `groupMemberAddMode`，不碰其它 socket 方法
- `setting = joinApproval` 只调 `groupJoinApprovalMode`
- `setting` 取值非法时命令被拒绝
- socket 能力缺失时抛 `unsupported`
- source 白名单外的命令被拒绝
- `normal-group-creation-executor.ts` 未被本次改动触及（文件级 diff 为空）

### 12.3 Android 测试

- `pull_task_group_settings` spec 的字段校验，含 `setting` 白名单与 `enabled` 非 nil
- 两种 `setting` 各自落到正确的 `Send*` 方法
- 结果事件字段完整并回显 `setting`
- 建普群 `GROUP_SETTINGS_APPLY` 校验与执行未受影响（回归）

### 12.4 集成验证

在测试环境跑一条真实拉群任务，确认：目标群加人权限被放开、进群审批被关闭、执行行正常推进到
`PULLER_INVITE`、补充管理员踩链接不再返回待审核。Web 与 Android 两种管理员账号各验一次。

补充一条负向验证：人为让管理员失去群管理员权限，确认加人权限命令失败并退避重试，
且关闭审核命令没有被发出。

## 13. 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 三仓联动发布顺序错误 | 执行行卡在等待加人权限结果 | 按 10.2 顺序发布；命令被拒后由未知结果兜底回收，不会永久卡死 |
| 异步化后阶段耗时增加 | 每条执行行多一次 Kafka 往返 | 同分区保序，往返在百毫秒级；换来不再占用调度线程做三次同步 HTTP |
| 关闭审核失败被静默 | 补充管理员持续待审核 | 单独 WARN 日志 + 动作行 `reason_code`，可统计 |
| 关闭审核动作行长期停留非终态 | 观测噪声 | 纳入未知结果兜底调度收口，但不阻断执行行 |
| 目标群管理员权限在命令消费前丢失 | 加人权限命令失败 | 与提权失败同路径，退避重试并轮换候选，行为不变 |
| `GroupActionCommandPayload` 新增字段影响建普群 | 建普群校验或执行异常 | 新字段仅被新 spec 使用，建普群校验分支不变；12.3 有回归用例 |
