# 拉群任务「群信息设置」命令契约 —— group.profile.apply

> 状态：**armada 侧只落了契约定义、载荷补全与头像转码，尚未下发**（2026-08-19）。
> 读者：协议层 Web（`armada-protocol/protocol-layer`，TS/Baileys）与 Android（`whatsapp-server`，Go）。
> 同域姊妹篇：`pull-task-normal-link-protocol-contract.md`（拉群已有 5 条命令）。

---

## 0. 一句话

拉群任务要给**老群**批量设置群资料。执行能力复用建群链路已实现的那一套群设置动作，
但**另起一条命令**：`group.profile.apply`。所有设置项可选，**留空 = 这一项别动**。

---

## 1. 为什么不蹭建群那条命令

建群链路的群设置命令字段全必填——新建的群没有「现有设置」，发什么就是什么。
拉群进的是客户**已经在用的老群**：运营只想改群名，就绝不能顺手把禁言、加人权限、
限时消息按默认值覆写掉。两者口径互斥，因此另起命令，建群那条**一字不改**。

同样也不与拉群自己的旧命令 `pull_task_group_settings` 混用：那条是一条命令一个设置项
（放开加人权限、关闭进群审核），仍在用，不要动它。

---

## 2. 命令头

| 项 | 值 |
|---|---|
| 命令类型 `commandType` | `group.profile.apply` |
| 来源 `source` | `pull_task_group_profile` |
| Outbox 聚合类型 `aggregateType` | `PULL_TASK_ACCOUNT_ACTION` |
| Outbox 关联键 `aggregateId` | 动作行 ID（`pull_task_account_action.id`），与拉群其它命令一致 |
| 动作类型 `action_type` | `7 = APPLY_GROUP_SETTINGS` |
| 执行账号 | 该执行行的任务管理员；其 backend 决定命令进 Web 还是 Android Topic |

---

## 3. 载荷字段

### 3.1 路由与定位（恒定出现）

| 字段 | 类型 | 说明 |
|---|---|---|
| `tenantId` / `pullTaskId` / `groupExecutionId` / `actionId` | number | 业务定位 |
| `accountId` / `protocolAccountId` | number / string | 执行账号 |
| `wsPhone` | string | **字段名不许改**：coordinator 的 ExtractPhone 用它做 group-action 族路由，安卓节点用它 Resolve 会话。改名不报错，只会让命令被判 "phone unresolvable" 静默丢弃 |
| `protocolBackend` | string | `WEB` / `ANDROID` |
| `groupJid` | string | **必填**，目标老群 |
| `attemptNo` / `timeoutMs` / `source` | number / number / string | 重试序号、超时、来源 |

### 3.2 设置项（全部可选）

| 字段 | 类型 | 说明 |
|---|---|---|
| `subject` | string | 群名称。字段名跟随 WhatsApp 与协议两侧（Baileys `groupUpdateSubject`、安卓 `SetGroupName`）叫 `subject`，**不叫 `groupName`**；armada 库里的来源列仍是 `group_name`，只是列名不是线上字段名 |
| `avatar` | object | 群头像，形如 `{ "base64": "...", "mimetype": "image/jpeg" }`，见 §3.4 |
| `description` | string | 群描述 |
| `sendMessagesAllowed` | boolean | 是否允许全体成员发言（禁言的反面） |
| `editGroupSettingsAllowed` | boolean | 是否允许全体成员编辑群资料，**同时决定谁能取群邀请链接**，见 §3.6 |
| `addMembersAllowed` | boolean | 是否允许全体成员加人。armada 侧暂不下发，见 §6 |
| `joinApprovalEnabled` | boolean | 是否开启入群审批。armada 侧暂不下发，见 §6 |
| `ephemeralDurationSeconds` | number | 限时消息秒数，`0` = 关闭 |

### 3.3 「留空」的线上表达（最关键一条）

**字段整个不出现在 JSON 里，不发 `null`。**

协议端拿到显式 `null` 无从判断是「别动」还是「清空」，按清空执行就把客户老群里
自己配的群资料抹了。armada 侧由载荷类的 `@JsonInclude(NON_NULL)` 保证，并有
`ProtocolPullTaskGroupProfilePayloadSerializationTest` 钉死。

协议端对应要求：**只处理出现了的字段**，未出现的项一个 IQ 都不要发。

### 3.4 头像：armada 转好，协议两侧纯透传

WhatsApp 群头像要 **640×640 方形 JPEG**。

- **armada 侧已经转好**：读出运营上传的原图 → 居中裁切成方形 → 缩放到 640×640 →
  合成白底（PNG 透明区不铺白转 JPEG 会变黑）→ JPEG 编码 → base64。
- **协议两侧不要再动它**：不缩放、不裁切、不改格式，`mimetype` 固定 `image/jpeg`。
- 为什么不交给协议层：Web 那条路能吃非方形 PNG，是因为它底层库替它缩放了；安卓是自研协议
  没有这一层。转码留在协议侧就是两端各写一套、行为还不一致。
- 非方形按**居中裁切**而非补白：客户端把群头像按圆形显示，补白会让白边落进圆形可视区并把
  主体等比缩小；头像主体几乎都在画面中央，裁掉两侧的损失远小于主体缩水。

走 base64 内嵌而不是 URL 或 Redis 资源引用：协议层进程读不到 armada 的本地盘，
群头像限 500KB 以内，内嵌一次走完，不值得为它多引一条资源生命周期。

### 3.5 示例

只改群名和描述、其余一律别动：

```json
{
  "tenantId": 7, "pullTaskId": 100, "groupExecutionId": 11, "actionId": 811,
  "accountId": 901, "protocolAccountId": "manager-901",
  "wsPhone": "8613800000901", "protocolBackend": "WEB",
  "groupJid": "120363group@g.us", "attemptNo": 1, "timeoutMs": 30000,
  "source": "pull_task_group_profile",
  "subject": "客户群",
  "description": "本群仅发布客户通知"
}
```

### 3.6 群链接权限已并入编辑群资料权限

WhatsApp 底层**没有**独立的「谁能拿群邀请链接」开关。能设的群权限只有两个：
「谁能发消息」和「谁能编辑群资料」，取邀请链接的权限绑在后者上。

因此表单上原来并排的「群链接权限」和「编辑群资料权限」其实是同一个开关，
并排放着必然有一项不生效。现已合并：载荷只保留 `editGroupSettingsAllowed`，
由 `edit_permission_mode` 驱动。

> `pull_task_standard_group_setting.link_permission_mode` 列**保留不删**（存量数据、
> 表单历史），但 hydrator 不再读它，**已废弃**。不要把它接到 `addMembersAllowed`：
> 加人权限 ≠ 取链接权限，接上等于替运营下发一个他没表达过的权限变更。

---

## 4. 结果与失败原因码

失败**不阻断执行行**，只把原因留在动作行上：群资料是运营展示需求，拉不拉得到人与它无关。

一条命令要改最多 8 项，只回一个笼统的「设置失败」运营没法排查，因此结果需要指明
**是哪一项没设上**。armada 侧按项分派到各自原因码：

| 设置项 | 原因码 |
|---|---|
| 群名称 | `GROUP_NAME_SET_FAILED` |
| 群头像 | `GROUP_AVATAR_SET_FAILED` |
| 群描述 | `GROUP_DESCRIPTION_SET_FAILED` |
| 群禁言 | `GROUP_MUTE_SET_FAILED` |
| 群资料编辑权限 | `GROUP_EDIT_PERMISSION_SET_FAILED` |
| 加人权限 | `GROUP_MEMBER_ADD_PERMISSION_SET_FAILED` |
| 入群审批 | `GROUP_JOIN_APPROVAL_SET_FAILED` |
| 限时消息 | `GROUP_DISAPPEARING_MESSAGE_SET_FAILED` |

**多项失败只回报第一个失败项**（业务确认 2026-08-19），不需要明细列表：
动作行只有一个原因码字段，运营要的也只是「先去看哪一项」。
协议端据此只需回一个失败项标识即可。

> `GROUP_MEMBER_ADD_PERMISSION_SET_FAILED` 与拉手前置门控的
> `GROUP_MEMBER_ADD_PERMISSION_UNCONFIRMED` 不是一回事；
> `GROUP_JOIN_APPROVAL_SET_FAILED` 与独立命令的
> `GROUP_JOIN_APPROVAL_CLOSE_FAILED` 也不是一回事。
>
> 原因码字面里的 `GROUP_NAME_*` 与载荷字段名 `subject` 不一致是**有意保留**的：
> 三边（armada / Web / Android）已按这些码对齐，改字面要三个仓一起动，不值当。

### 4.1 八项的执行顺序 —— 「第一个」的定义依据

```
群名 → 头像 → 群描述 → 禁言 → 编辑权限 → 加人权限 → 入群审批 → 限时消息
```

先设看得见的资料（群名/头像/描述），再设权限类。「多项失败只回第一个」这条规则必须有一个
确定顺序才有定义，就是这个顺序。协议层两侧已按此钉断言；armada 侧
`PullTaskGroupSettingItem` 的**声明顺序与之一致**，两边都不要重排。

### 4.2 设置项失败一律按 UNKNOWN 回报；armada 只留痕不重发

协议两侧对设置项失败**一律回 `UNKNOWN`（带 `retryable`）**，不回 `FAILED`。

理由：原生请求发出去撤不回，协议侧并不知道这一项到底生没生效；而这些设置重复执行无副作用
（改群名、传头像、设权限都是幂等的），宁可多试一次，也别让运营以为设上了。

**armada 侧的最终口径（业务确认 2026-08-19）：收到 UNKNOWN 只落库留痕，不重发。
一条 `group.profile.apply` 命令只发一次。**

为什么不重试——不是漏了，是二选一：这个仓里的重试驱动来自「执行行被卡在那一步」，
调度器反复回到该步才有人重新发命令（「放开加人权限」就是这么重试的）。而群资料按业务口径
**不阻断执行行**，执行行会径直往后走，没有任何东西会回头再发一次。
「失败不阻断执行行」与「靠卡住执行行驱动重试」在现有结构下互斥，只能取一个，
本轮取「不阻断」。群资料没设上是个看得见的问题，运营在执行明细里能发现并手动重来；
先让主链路通、跑出真实失败率，再决定值不值得做自动补发。

> ⚠️ 协议侧回的 `retryable` 标志 **armada 目前不消费**，它不会触发任何重发。
> 保留它是为了将来真加补发任务时能直接用——别看到这个字段就以为重试已经生效了。

---

## 5. armada 侧落点

| 内容 | 位置 |
|---|---|
| 命令请求与常量 | `platform/protocol/model/command/ProtocolPullTaskGroupProfileCommandRequest.java` |
| 载荷定义（NON_NULL） | `platform/protocol/model/command/ProtocolPullTaskGroupProfilePayload.java` |
| 载荷补全 | `task/service/impl/PullTaskGroupProfilePayloadHydrator.java` |
| 头像转码 | `task/service/impl/PullTaskGroupAvatarJpegTranscoder.java` |
| 任务配置来源表 | `pull_task_standard_group_setting`（总开关 `is_group_setting_enabled`） |
| 失败原因码 | `task/model/enums/PullTaskExecutionReasonCode.java` |
| 失败项枚举 | `task/model/enums/PullTaskGroupSettingItem.java` |

配置项 → 载荷字段映射：表单选「不操作」(`UNCHANGED`) 的项一律不出现。

| 配置列 | 载荷字段 |
|---|---|
| `group_name` / `is_material_filename_as_group_name` | `subject`（勾选后逐行取该执行行的料子文件名） |
| `avatar_file_key` | `avatar`（读本地盘原图，转 640×640 方形 JPEG 后 base64） |
| `group_description` | `description` |
| `mute_mode` | `sendMessagesAllowed`（禁言 → `false`） |
| `edit_permission_mode` | `editGroupSettingsAllowed`（同时管取链接权限） |
| `link_permission_mode` | **已废弃，不再读**，见 §3.6 |
| —— | `addMembersAllowed`（表单无此项，恒不下发） |
| —— | `joinApprovalEnabled`（表单无此项，恒不下发） |
| `disappearing_message_mode` | `ephemeralDurationSeconds` |

---

## 6. 尚未打通（下一刀）

1. **没有人产生这条命令**：动作行的产生时机（`BEFORE_PULL` / `AFTER_PULL`）与
   Outbox 入队尚未实现，本轮只落契约。
2. **`addMembersAllowed` 与 `joinApprovalEnabled` 取不到值**：
   `pull_task_standard_group_setting` 没有加人权限列，也没有入群审批列，拉群表单同样没有
   这两项，因此两个字段目前恒不出现。契约保留它们，表单补上对应列后在 hydrator 里接上即可，
   协议侧不用改。
3. **结果回路未开**：`ProtocolGroupEventConsumer` 的 source 白名单尚未加
   `pull_task_group_profile`，协议事件也还没有透出「第一个失败项」的字段。
4. **自动补发：已决「不做」，只发一次**（业务确认 2026-08-19）。UNKNOWN 只留痕不重发，口径与
   理由见 §4.2。这**不是**遗留缺口，是明确取舍：本刀已经很大，补发是另一摊活；群资料没设上
   运营在执行明细里看得见，可以手动重来。
   *后续项（别丢）*：真要做自动补发，需要一个按动作行扫 `APPLY_GROUP_SETTINGS` + UNKNOWN 的
   对账任务，并定好**重试间隔**与**次数上限**（协议侧对这类失败恒回 UNKNOWN，没有上限就会
   变成无限重发），再消费协议侧那个目前闲置的 `retryable` 标志。做之前先看主链路跑出来的
   真实失败率，值不值得做。
