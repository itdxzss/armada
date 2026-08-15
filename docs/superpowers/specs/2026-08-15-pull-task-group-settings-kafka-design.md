# 普通群链接拉群群设置异步化与关闭进群审核设计

日期：2026-08-15
状态：待评审

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

1. 任务管理员提权确认后、占用拉手之前，由该管理员账号一次性把目标群设置为「全体成员可加人」且「关闭进群审核」。
2. 群设置从同步 HTTP 改为 outbox + Kafka 异步命令，与 promote、邀请、批量 add 使用同一条通道和同一套收敛模型。
3. 去掉群元数据回读确认，改以协议动作结果事件为准。
4. 补齐 Android 协议后端的 join-approval 能力。
5. 加人权限失败仍阻断并退避重试；关闭审核失败不阻断。

### 2.2 非目标

- 不改群详情页手工修改群设置（`GroupDetailServiceImpl`）。那是同步交互，异步化需要改前端，收益为零。
- 不改新建普群链路。它已经走 Kafka 的 `GROUP_SETTINGS_APPLY`，不使用同步 `GroupSettingsPort`。
- 不删除 `GroupSettingsPort` 及其 HTTP 适配器，群详情页仍在使用。
- 不新增 Kafka topic。
- 不新增任务级或全局级配置开关，关闭进群审核是固定行为。
- 不改动群资料设置（`pull_task_standard_group_setting`）的应用时机与语义。

## 3. 业务流程

### 3.1 阶段位置

阶段枚举不变，仍是八段。改造发生在 `MANAGER_PULLER_CONTACT` 阶段内部：

```text
MANAGER_ADMIN 提权确认成功
  -> 阶段推进到 MANAGER_PULLER_CONTACT
  -> 有补充拉手指令则优先处理，本段不执行
  -> 短事务：复核租约、选定管理员账号、取 groupJid
  -> 短事务：写群设置动作行(SUBMITTED) + 写 outbox 行(PENDING)，原子提交
  -> 释放租约，等待协议结果事件
  -> 结果事件到达，收敛动作行
       加人权限成功 -> 唤醒执行行，继续本阶段后续（占拉手、建联系人）
                       其中关审核若失败，只写原因码留痕，不影响推进
       加人权限失败 -> 保留阶段，按原因码退避重试
  -> 短事务：占用拉手、创建管理—拉手联系人动作、推进到 PULLER_INVITE
```

改造前该段是「事务外同步 HTTP 设置 + 回读确认 + 未确认则 defer」，改造后是「写命令 + 等回调」。

### 3.2 为什么合成一条命令

两项设置合成一条命令，一条 outbox、一条结果事件、一个动作行。`GROUP_SETTINGS_APPLY` 在两端本来就是批量语义。
拆成两条会产生两倍的 outbox 行、两次回调、两次执行行唤醒，还要处理两次回调乱序到达时的推进条件。

两项失败语义不同（加人权限阻断、关审核不阻断），这一点由结果事件的 `requiredSettings` / `failedSettings`
表达，不需要拆成两条命令，见 4.5。

### 3.3 为什么去掉回读

同步 HTTP 时代回读是防御「HTTP 200 只代表协议层收到了命令」。异步命令的 `group.action_result_reported`
是协议层真正把 IQ 发完之后才发出的执行结果，不是提交回执。判据从「回读 metadata 的 `memberAddMode`」
换成「结果事件的 outcome 与 reasonCode」。

Android 的群元数据本来也不返回 `joinApprovalMode`，回读对关审核这一项从来就不可用。

### 3.4 顺序保证

Outbox 行的 `kafka_key` 是 `protocolAccountId`。同一管理员账号的 promote 命令与群设置命令落在同一分区，
严格保序，不会出现「设置命令先于提权命令被消费、账号还不是管理员因而被拒」的竞态。同步 HTTP 版本没有这个保证。

## 4. 命令与事件契约

### 4.1 Topic

不新增 topic，复用现有两条。

| 方向 | 账号 backend | Topic |
| --- | --- | --- |
| 命令 | WEB | `protocol.master.commands.v1` |
| 命令 | ANDROID | `protocol.android.group-action.commands.v1` |
| 结果 | 两者 | `protocol.group.events.v1` |

结果事件由 `ProtocolGroupEventConsumer` 消费，消费组 `armada-api-group-events`。

避开新建普群的独立通道（`protocol.android.normal-group.commands.v1` / `protocol.normal-group.events.v1`）。

### 4.2 命令标识

| 字段 | 取值 |
| --- | --- |
| `commandType` | `group.settings.requested`（新增） |
| `source` | `pull_task_group_settings`（新增） |
| `action` | `GROUP_SETTINGS_APPLY`（复用两端已有常量） |
| `aggregateType` | `PULL_TASK_ACCOUNT_ACTION` |
| `aggregateId` | 群设置动作行 ID |
| `kafkaKey` | 管理员的 `protocolAccountId` |

### 4.3 Outbox 引用 payload

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

### 4.4 发布时补全的 wire payload

新增 `PullTaskGroupSettingsPayloadHydrator`，`supports` 判据为
`commandType = group.settings.requested` 且 `aggregateType = PULL_TASK_ACCOUNT_ACTION`：

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
  "action": "GROUP_SETTINGS_APPLY",
  "addMembersAllowed": true,
  "joinApprovalEnabled": false,
  "requiredSettings": ["addMembers"],
  "timeoutMs": 30000,
  "attemptNo": 1,
  "source": "pull_task_group_settings"
}
```

**字段缺省即不设置。** 五项群设置字段（`sendMessagesAllowed`、`editGroupSettingsAllowed`、
`addMembersAllowed`、`joinApprovalEnabled`、`ephemeralDurationSeconds`）全部可选，两端只对
payload 中实际出现的字段发 IQ。拉群只带两项，其余三项属于任务群资料设置，不得被顺手覆盖。

`requiredSettings` 声明哪些项失败会导致命令失败，取值为设置项名称：`sendMessages`、
`editGroupSettings`、`addMembers`、`joinApproval`、`ephemeral`。已传但不在该数组中的项为尽力项。

这是对现有 `GROUP_SETTINGS_APPLY` 语义的扩展：`normal_group_creation` 五项全带且
`requiredSettings` 含全部五项，行为不变。

### 4.5 结果事件

沿用 `group.action_result_reported`，`data.source = pull_task_group_settings`，
`data.operation = group_settings_apply`，携带 `commandId`、`attemptNo`、`outcome`、`reasonCode`、
`reasonMessage`、`occurredAt`。

一条命令承载两项语义不同的设置，因此结果事件必须区分二者，否则「关审核失败不阻断」无法表达。
payload 中每项设置分为必需项与尽力项：

| 字段 | 语义 | 失败影响 |
| --- | --- | --- |
| `requiredSettings` | 必需项名称数组，拉群传 `["addMembers"]` | 任一失败即命令 `outcome = FAILED` |
| 其余已传字段 | 尽力项，拉群的 `joinApproval` 属此类 | 失败不影响 `outcome` |

结果事件新增 `data.failedSettings`：字符串数组，列出实际失败的设置项名称，成功时为空数组。

因此「加人成功、关审核失败」的事件是 `outcome = SUCCESS` 且 `failedSettings = ["joinApproval"]`。
协议侧不吞异常，失败项被显式上报；是否阻断由控端按 `requiredSettings` 判定。

`normal_group_creation` 保持现状：五项全部为必需项，任一失败即整体失败，行为不变。

## 5. 数据模型

### 5.1 动作类型扩展

`pull_task_account_action.action_type` 新增取值 `5 = 应用群设置`。

`PullTaskAccountActionType` 新增枚举项：

```java
/** 应用群设置：任务管理员放开加人权限并关闭进群审核。 */
APPLY_GROUP_SETTINGS(5);
```

### 5.2 唯一键处理

现有唯一键 `uq_pull_task_action_pair (tenant_id, group_execution_id, action_type, actor_group_account_id,
target_group_account_id)`，且两个账号列都是 `NOT NULL`。

群设置动作没有「对象账号」。沿用踩链接入群那一行已有的约定（表注释：踩链接入群时 actor 为目标账号自身 ID），
把 `target_group_account_id` 写成与 `actor_group_account_id` 相同的管理员角色行 ID。这样唯一键天然表达
「每个执行行只有一条群设置动作」，重复调度不会插出第二行。

### 5.3 Flyway

新增 `V119__pull_task_group_settings_action.sql`，只做一件事：修改 `action_type` 列注释，
补上 `5=应用群设置`。列类型和索引不变，无数据迁移。

```sql
ALTER TABLE pull_task_account_action
    MODIFY COLUMN action_type TINYINT NOT NULL
    COMMENT '动作类型:1=保存联系人 2=邀请入群 3=踩链接入群 4=设置任务管理员 5=应用群设置';
```

## 6. armada 侧改造清单

### 6.1 新增

| 文件 | 职责 |
| --- | --- |
| `ProtocolPullTaskGroupSettingsCommandRequest` | 命令请求记录，`SOURCE = pull_task_group_settings`，含 actor 与两个布尔 |
| `PullTaskGroupSettingsPayloadHydrator` | 从动作行、角色快照、执行行补全 wire payload |
| `PullTaskGroupSettingsResultService` + `Impl` | 以 `commandId + attemptNo` CAS 收敛结果并唤醒执行行 |
| `PullTaskGroupSettingsCallback`（DTO） | 结果事件的领域入参 |
| `V119__pull_task_group_settings_action.sql` | 动作类型注释 |

### 6.2 修改

| 文件 | 改动 |
| --- | --- |
| `PullTaskAccountActionType` | 新增 `APPLY_GROUP_SETTINGS(5)` |
| `ProtocolCommandOutboxServiceImpl` | 新增 `COMMAND_TYPE_GROUP_SETTINGS_REQUESTED` 常量与 `enqueuePullTaskGroupSettingsCommands`，topic 路由复用 `backend == ANDROID ? groupActionTopic : masterTopic` |
| `ProtocolCommandOutboxService` | 新增接口方法 |
| `ProtocolGroupEventConsumer` | `handleActionResultReported` 增加 `pull_task_group_settings` 分支 |
| `PullTaskManagerPullerContactProcessor` | 删除 `ensureMemberAddPermission` 与 `memberAddAllowed`，改为「准备 → 提交命令 → 返回等待」 |
| `PullTaskManagerPullerContactTransactionService` | `prepareMemberAddPermission` 改名为 `prepareGroupSettings`，返回值增加两个布尔；`deferMemberAddPermission` 改名为 `deferGroupSettings`；新增 `submitGroupSettingsCommand`（写动作行 + outbox，同事务） |
| `PullTaskMemberAddPermissionWork` | 改名 `PullTaskGroupSettingsWork`，字段增加 `addMembersAllowed`、`joinApprovalEnabled` |
| `PullTaskExecutionReasonCode` | 保留 `GROUP_MEMBER_ADD_PERMISSION_DENIED` / `_UNCONFIRMED`；新增 `GROUP_JOIN_APPROVAL_CLOSE_FAILED`，仅写入动作行 `reason_code` 留痕，不阻断执行行 |
| `AndroidNativeClient` | 新增 `setGroupJoinApproval(wsPhone, groupJid, enabled)` |
| `HttpAndroidNativeClient` | 实现之，`GROUP_JOIN_APPROVAL_URI_PREFIX = "/ws/v1/groups/settings/approval/"`，复用 `GroupPermissionRequest` |
| `AndroidNativeGroupSettingsAdapter` | `setJoinApprovalEnabled` 由 `throw unsupported` 改为真实调用 |
| `PullTaskUnknownResultReconciliationService` | 纳入群设置动作的未知结果兜底 |
| `ProtocolCommandOutboxServiceImpl.cancelPendingPullTaskCommands` | 覆盖群设置命令类型 |

`AndroidNativeGroupSettingsAdapter` 的补齐虽然拉群链路改异步后不再直接用它，但群详情页手工设置仍走该端口，
Android 账号目前在那里是不可用的，一并补上。

### 6.3 结果收敛语义

`PullTaskGroupSettingsResultService.apply` 与 `PullTaskManagerAdminResultServiceImpl` 同构：

1. 按 `commandId` 定位动作行，校验 `attemptNo`、租户、执行行、动作类型一致
2. 目标状态已到达则幂等返回 true
3. CAS 从 `{SUBMITTED, UNKNOWN}` 迁移到 `{SUCCESS, FAILED, UNKNOWN}`
4. CAS 唤醒执行行：期望 `EXECUTING + MANAGER_PULLER_CONTACT`

结果分支：

| 结果 | 动作行 | 执行行 |
| --- | --- | --- |
| `SUCCESS` 且 `failedSettings` 为空 | SUCCESS | 保留 `MANAGER_PULLER_CONTACT`，`next_run_at` 置为立即，下轮走占拉手 |
| `SUCCESS` 且 `failedSettings = ["joinApproval"]` | SUCCESS，`reasonCode = GROUP_JOIN_APPROVAL_CLOSE_FAILED` | 同上，照常推进，不重试 |
| 失败且 `GROUP_PERMISSION_DENIED` | FAILED | 保留阶段，`reasonCode = GROUP_MEMBER_ADD_PERMISSION_DENIED`，按统一退避重试 |
| 其它失败 | FAILED | 保留阶段，`reasonCode = GROUP_MEMBER_ADD_PERMISSION_UNCONFIRMED`，按统一退避重试 |
| 未知 | UNKNOWN | 保留阶段，交由未知结果兜底调度 |

失败重试时重新生成 `commandId` 并递增 `attemptNo`，与提权动作一致。

第二行是「关审核失败不阻断」的落点：动作行仍是 SUCCESS，只在 `reason_code` 留痕供排查和统计，
执行行照常推进到占拉手。加人权限失败才阻断，因为它是拉手 add 料子的硬前置。

加人权限失败时命令整体重试，重试会连带重发关审核。两项 IQ 均幂等，无副作用。

## 7. protocol-layer 侧改造

### 7.1 命令类型注册

- `types.ts`：`MasterCommandType` 与 `SUPPORTED_COMMAND_TYPES` 增加 `group.settings.requested`
- `master-router.ts`：按 `protocolAccountId` 路由到 worker，与 `group.participants.requested` 同路径
- `pull-task-action.ts`：新增 `GROUP_SETTINGS_SOURCE_SPECS` 白名单，收录 `pull_task_group_settings`，
  校验 source 与 operation 严格配对
- `worker-consumer.ts`：新增 executor 分派

### 7.2 执行器

新增 `group-settings-executor.ts`，或复用 `normal-group-creation-executor.ts` 中
`GROUP_SETTINGS_APPLY` 分支并抽出共享函数。执行语义改为按字段存在性逐项设置，
并按 `requiredSettings` 区分失败处理：

```ts
const failed: string[] = []
for (const item of ORDERED_SETTINGS) {
  if (payload[item.field] === undefined) continue
  try {
    await item.apply(socket, payload)
  } catch (error) {
    if (payload.requiredSettings?.includes(item.name)) throw error   // 必需项失败立即中止
    failed.push(item.name)                                           // 尽力项失败记录后继续
    logSettingFailure(item.name, error)
  }
}
return { failedSettings: failed }
```

`ORDERED_SETTINGS` 顺序固定为：发言权限、编辑权限、加人权限、进群审批、限时消息。拉群只命中中间两项，
即「先加人权限、后进群审核」。加人权限是必需项，失败直接抛出，进群审批不再执行；
进群审批是尽力项，失败进入 `failedSettings` 并继续。

`normal_group_creation` 的 `requiredSettings` 含全部五项，任一失败即抛出，与现状逐字一致。

### 7.3 结果发布

复用现有 pull_task 动作结果发布路径，`source = pull_task_group_settings`，
`operation = group_settings_apply`。

## 8. Android 侧改造

### 8.1 命令契约

`internal/armada/group_action_command.go`：

- 新增 `SourcePullTaskGroupSettings = "pull_task_group_settings"`
- 新增 `CommandTypeGroupSettingsRequested = "group.settings.requested"`
- `groupActionSpecs` 增加一条：

```go
{
    commandType: CommandTypeGroupSettingsRequested, source: SourcePullTaskGroupSettings,
    operation: operationGroupSettingsApply,
    aggregateType: aggregateTypePullTaskAccountActionGroupAction,
    key: correlationKeyActionID, action: ActionGroupSettingsApply,
    validateExecution: validatePullTaskGroupSettingsExecution,
},
```

- `GroupActionCommandPayload` 的五个设置字段改为指针类型，以区分「未传」与「传了 false」
- `GroupActionCommandPayload` 新增 `RequiredSettings []string`
- 现有 `validateNormalGroupCreationExecution` 对 `GROUP_SETTINGS_APPLY` 的必填校验保持不变，
  新增的 `validatePullTaskGroupSettingsExecution` 要求 `groupJid` 非空、至少带一项设置，
  且 `requiredSettings` 中的每一项都必须在 payload 中实际出现

### 8.2 执行

`internal/armada/group_action_executor.go` 按字段存在性逐项调用，失败处理与 7.2 一致
（必需项失败中止，尽力项失败进 `failedSettings` 后继续）：

- `addMembersAllowed` → `SendGroupPermission(groupJID, "member_add_mode", v)`
- `joinApprovalEnabled` → `SendApproveNewMembers(groupJID, v)`

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

Go 服务端无需新增能力，也无需新增 HTTP 路由。

### 8.3 结果发布

`internal/armada/group_action_event.go` 复用现有 `group.action_result_reported` 发布路径。

## 9. 存量兼容与灰度

### 9.1 存量执行行

改造前停留在 `MANAGER_PULLER_CONTACT` 阶段的执行行，其加人权限可能已经通过同步 HTTP 设置成功，
但没有任何动作行记录。部署后这些执行行会新建一条群设置动作并重新提交命令。重复设置
`all_member_add` 与 `off` 都是幂等 IQ，无副作用。

不需要数据回补脚本。

### 9.2 版本依赖

armada 的新命令类型必须在 protocol-layer 与 Android 都已支持之后才能发布，否则命令会被
`UNSUPPORTED_COMMAND_TYPE` 拒绝消费，执行行会卡在等待。

发布顺序：protocol-layer 与 Android 先发（新增分支对存量命令无影响），确认两端就绪后再发 armada。

### 9.3 回滚

armada 回滚到旧版本后，同步 HTTP 路径恢复，已写入但未消费的群设置命令会在协议侧被拒绝并进入失败，
对应执行行由未知结果兜底调度回收。不需要清理 outbox。

## 10. 可观测性

- 命令提交、结果收敛各记一条 INFO，字段限于 `taskId`、`executionId`、`actionId`、`commandId`、
  `attemptNo`、`outcome`、`reasonCode`，不记录号码、群 JID 和协议账号句柄
- 关闭审核失败单独记 WARN，便于统计目标群开启审批的比例
- 拉群任务详情沿用现有原因码展示，`GROUP_MEMBER_ADD_PERMISSION_DENIED` 与
  `GROUP_MEMBER_ADD_PERMISSION_UNCONFIRMED` 的展示文案不变

## 11. 测试计划

### 11.1 armada 单元测试

- `PullTaskGroupSettingsPayloadHydratorTest`：payload 只含两项设置字段；动作行与冻结事实不一致时抛校验异常；租户上下文正确恢复
- `PullTaskGroupSettingsResultServiceTest`：成功唤醒执行行；`failedSettings = ["joinApproval"]` 时仍推进且写入 `GROUP_JOIN_APPROVAL_CLOSE_FAILED`；`GROUP_PERMISSION_DENIED` 映射到 DENIED 原因码并退避；其它失败映射到 UNCONFIRMED；重复回调幂等；`attemptNo` 不匹配时拒绝
- `PullTaskManagerPullerContactProcessorTest`：补充拉手指令优先，群设置命令不提交；命令提交后返回等待且不占用拉手；动作行与 outbox 行同事务写入
- `ProtocolCommandOutboxServiceImplTest`：WEB 账号进 master topic，ANDROID 账号进 group-action topic；`kafkaKey` 为 protocolAccountId；批量上限与 commandId 冲突
- `PullTaskGroupSettingsActionMigrationSqlTest`：V119 幂等且只改注释
- `AndroidNativeGroupSettingsAdapterTest`：`setJoinApprovalEnabled` 打到
  `/ws/v1/groups/settings/approval/{wsPhone}`；协议失败映射为 ProtocolException

### 11.2 protocol-layer 测试

- 字段缺省时不调用对应 socket 方法
- 两项都传时按「先加人、后审批」顺序调用
- 加人（必需项）失败时审批不执行且整体失败
- 审批（尽力项）失败时 `outcome = SUCCESS` 且 `failedSettings = ["joinApproval"]`
- `normal_group_creation` 五项全传、五项全必需的行为与改造前逐字一致（回归）
- source 白名单外的命令被拒绝

### 11.3 Android 测试

- 指针字段区分未传与 false
- `pull_task_group_settings` spec 的必填校验，含 `requiredSettings` 与实际字段的一致性校验
- 结果事件字段完整，含 `failedSettings`

### 11.4 集成验证

在测试环境跑一条真实拉群任务，确认：目标群加人权限被放开、进群审批被关闭、执行行正常推进到
`PULLER_INVITE`、补充管理员踩链接不再返回待审核。Web 与 Android 两种管理员账号各验一次。

## 12. 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 三仓联动发布顺序错误 | 执行行卡在等待群设置结果 | 按 9.2 顺序发布；armada 侧命令被拒后由未知结果兜底回收，不会永久卡死 |
| 异步化后阶段耗时增加 | 每条执行行多一次 Kafka 往返 | 同分区保序，实测往返在百毫秒级；换来的是不再占用调度线程做三次同步 HTTP |
| `GROUP_SETTINGS_APPLY` payload 语义收窄改错 | 建普群的群设置被漏设 | 7.2 的回归测试逐字比对五项全传的调用序列 |
| 关闭审核失败被静默 | 补充管理员持续待审核 | 单独 WARN 日志 + 原因码，可统计 |
| 目标群管理员权限在命令消费前丢失 | 命令失败 | 与提权失败同路径，退避重试并轮换候选，行为不变 |
