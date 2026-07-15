# Armada 群详情抽屉现有入口补齐设计

> 状态：已确认
>
> 日期：2026-07-15
>
> 目标仓库：`armada`、`armada-protocol`、`wheel-saas-pure-web`
>
> 产品边界：只补齐当前群详情抽屉已经存在的入口，不新增按钮、页面或业务能力

## 1. 背景

`wheel-saas-pure-web` 的群组列表已经有群详情抽屉，但当前页面存在“入口已经显示、真实能力尚未接通”的情况：权限状态以固定值初始化，限时消息和三项权限只提示接口待接入，成员升降管理员和踢人调用了 Armada 尚不存在的接口，头像上传路径也与现有 Armada 群资料接口不一致。

Armada 已具备群链接本地资料维护、真实群名称/描述/头像修改和实时成员读取的部分能力；`armada-protocol` 已具备大部分群元数据、群设置和参与者操作接口。三仓契约没有收口，导致前端抽屉无法真实回显和修改 WhatsApp 群状态。

本设计采用纵向逐项打通方式：每个现有入口同时补齐协议层、Armada 后端、前端和测试，完成一项再验收下一项。

需求事实以本次用户逐项确认的口径为最高优先级。`docs/business/requirements/一期需求.xlsx` 在本次会话中未能通过规定的工作簿运行时复核；该限制不影响本设计，因为最新用户口径已经明确限定为“只补齐现有抽屉入口”。

## 2. 当前事实与缺口

### 2.1 前端

事实入口位于：

- `wheel-saas-pure-web/src/views/group/list/components/GroupMemberDrawer.vue`
- `wheel-saas-pure-web/src/api/group.ts`

当前抽屉只有以下功能：

1. 更换群头像。
2. 编辑群名称和群备注，并通过一个“保存群资料”按钮提交。
3. 限时消息：24 小时、7 天、90 天、关闭。
4. 五项群组权限：编辑群组设置、发送新消息、添加其他成员、通过链接邀请、管理员可以批准新成员。
5. 群成员列表、搜索、刷新和多选。
6. 批量设置管理员、取消管理员、踢出。

已确认的前端缺口：

- 五项权限使用固定初始值，未完整回显 WhatsApp 真实状态。
- 限时消息只有告警提示，没有后端调用。
- “添加其他成员”只是权限开关，不是添加成员操作。
- “通过链接邀请”只是权限开关，不是复制或重置邀请链接操作。
- 设置/取消管理员和踢出已有按钮，但对应 Armada API 尚不存在。
- 前端上传头像使用 multipart `/avatar`，现有 Armada 真实头像接口使用 JSON `/picture`，契约不一致。

### 2.2 Armada 后端

`GroupLinkController` 当前已有：

- `GET /api/group-links`
- `PATCH /api/group-links/{id}`：本地群名称、备注、头像 URL
- `POST /api/group-links/{id}/subject`：真实群名称
- `POST /api/group-links/{id}/description`
- `POST /api/group-links/{id}/announcement-text`
- `POST /api/group-links/{id}/picture`
- `GET /api/group-links/{id}/members`：实时成员列表

当前缺口：

- 真实群资料接口要求前端传 `accountId`，与“Armada 自动选择执行账号”的最新口径不符。
- 没有抽屉详情聚合接口，无法一次返回真实权限、限时消息和成员角色。
- 没有限时消息、群设置、成员升降管理员和踢出接口。
- 现有成员读取已经会选择在线且仍在群内的账号，并按管理员优先排序，可提取为统一执行账号选择器。

### 2.3 armada-protocol

`protocol-layer/src/routes/groups.ts` 当前已有：

- 群元数据和成员查询。
- 真实群名称、描述和头像修改。
- `announcement`、`locked`、`member-add-mode`、`join-approval` 设置。
- 参与者 `promote`、`demote`、`remove`。

当前缺口：

- Baileys 7.0.0-rc11 已有 `groupToggleEphemeral`，但协议层尚未暴露限时消息路由。
- Baileys 元数据已经返回 `restrict`、`announce`、`memberAddMode`、`joinApprovalMode` 和 `ephemeralDuration`，协议层 OpenAPI/Armada 模型尚未完整收口。
- 当前 Baileys 公共 socket API 和 `GroupMetadata` 类型没有“通过链接或二维码邀请”权限字段或设置方法。该能力不能与 `memberAddMode`、`joinApprovalMode` 混用，也不能猜测底层二进制标签后直接上线。

## 3. 范围

### 3.1 本期目标

只补齐当前群详情抽屉已有入口：

| 抽屉入口 | 最终语义 |
|---|---|
| 群头像 | 修改真实 WhatsApp 群头像，成功后同步 Armada 本地头像镜像 |
| 群名称 | 修改真实 WhatsApp 群名称，成功后同步 Armada 本地群名称镜像 |
| 群备注 | 只更新 Armada 本地备注，不发往 WhatsApp |
| 限时消息 | 关闭、24 小时、7 天、90 天 |
| 编辑群组设置 | 开启时普通成员可编辑；关闭时仅管理员可编辑 |
| 发送新消息 | 开启时全员可发言；关闭时仅管理员可发言 |
| 添加其他成员 | 控制普通成员是否可以添加成员 |
| 通过链接邀请 | 控制普通成员是否可以访问、分享邀请链接或二维码 |
| 管理员可以批准新成员 | 控制通过邀请申请加入时是否启用管理员审批 |
| 群成员列表 | 实时展示群主、管理员和普通成员 |
| 设置/取消管理员、踢出 | 对当前勾选成员执行批量操作，并返回逐项结果 |

### 3.2 明确非目标

本期不新增：

- 群描述或公告文本输入框。
- 真正的“添加成员”入口。
- 复制邀请链接、重置邀请链接或二维码入口。
- 待审批成员列表及批准/拒绝操作。
- 退群操作。
- 建群、进群任务、营销任务相关能力。
- 前端执行账号选择器。
- 群成员持久化、权限历史或操作审计页面。

协议层即使已经存在上述非目标能力，也不因此扩展前端抽屉。

## 4. 方案比较

### 4.1 方案 A：纵向逐项打通

按“详情读取 → 群资料 → 限时消息 → 五项权限 → 成员管理”的顺序，每项同时完成三仓代码和测试。

优点是每项都可独立验收，能持续消除假入口，问题定位范围小。缺点是三仓会发生多轮小改动。采用本方案。

### 4.2 方案 B：协议层和 Armada 一次性完成，再接前端

优点是后端契约可以集中建设。缺点是前端很晚才能看到真实效果，阶段性验收困难，不符合“一个一个梳理”的要求。不采用。

### 4.3 方案 C：前端继续使用临时状态，最后统一联调

优点是短期页面改动少。缺点是继续保留“看起来可用、实际未接通”的状态，容易造成契约漂移。不采用。

## 5. 总体架构

```text
GroupMemberDrawer
  -> Armada /api/group-links/{id}/...
     -> GroupExecutionAccountSelector
        -> account_group_membership + account_state
     -> GroupDetail / GroupSettings / GroupParticipant ports
        -> armada-protocol owner 路由
           -> Baileys socket
              -> WhatsApp
```

前端只调用 Armada，不直接调用协议层，也不传执行账号。

Armada 为每次读取或修改选择一个执行账号：

1. 账号状态为 `ONLINE`。
2. `account_group_membership` 表明账号仍在目标群。
3. 优先 `is_admin = true`。
4. 同级按最近群同步时间和稳定主键排序。

一次请求只选择一次账号。协议返回权限不足时，Armada 直接返回明确错误，不自动换账号重试；批量成员操作中的全部成员使用同一个执行账号。这样可以避免前一账号已经部分执行后再换号造成重复或不可判断状态。

## 6. 详情读取设计

新增聚合查询：

```text
GET /api/group-links/{id}/detail
```

建议响应结构：

```json
{
  "groupLinkId": 1,
  "groupJid": "120363...@g.us",
  "groupName": "WhatsApp 真实群名",
  "remark": "Armada 本地备注",
  "avatarUrl": "https://...",
  "liveStateAvailable": true,
  "liveStateUnavailableReason": null,
  "timedMessageMode": "7d",
  "permissions": {
    "editGroupSettings": true,
    "sendMessages": true,
    "addMembers": false,
    "inviteViaLink": null,
    "adminApproveNewMembers": true
  },
  "capabilities": {
    "inviteViaLink": {
      "supported": false,
      "reason": "当前协议版本未暴露该设置"
    }
  },
  "membersAvailable": true,
  "membersUnavailableReason": null,
  "members": []
}
```

数据规则：

- Armada 先读取本地群名称镜像、备注和头像镜像，保证协议不可用时仍能打开抽屉。
- 再用自动选中的账号读取一次 WhatsApp 群元数据；同一份元数据同时映射权限、限时消息和成员角色，避免重复调用 `groupMetadata`。
- WhatsApp 实时 subject 覆盖响应中的本地群名称镜像；本地备注始终来自 `group_link.remark`。
- 群主和管理员根据参与者的 owner/admin 标记映射；LID 场景优先返回可用的 PN/phoneNumber，保留原始 JID 作为操作标识。
- 现有 `GET /api/group-links/{id}/members` 保留，抽屉“刷新”按钮可只刷新成员列表。
- 协议读取失败时，聚合接口仍返回本地资料，并通过 `liveStateAvailable=false` 和原因标记权限、限时消息、成员不可用；前端不得用固定默认值冒充真实状态。

字段映射：

| 前端语义 | Baileys 元数据 | 映射 |
|---|---|---|
| 编辑群组设置 | `restrict` | `!restrict` |
| 发送新消息 | `announce` | `!announce` |
| 添加其他成员 | `memberAddMode` | 原值 |
| 管理员可以批准新成员 | `joinApprovalMode` | 原值 |
| 限时消息 | `ephemeralDuration` | `0/off`、`86400/24h`、`604800/7d`、`7776000/90d` |

## 7. 修改接口设计

### 7.1 群名称和备注

保留抽屉现有“保存群资料”按钮，但按字段分别执行：

- 群名称变化时调用 `POST /api/group-links/{id}/subject`，请求只包含 `subject`，不再要求 `accountId`。
- 群备注变化时调用现有 `PATCH /api/group-links/{id}`，只提交 `remark`。
- 两个字段独立返回结果；一个成功、一个失败时不回滚成功项，前端明确展示失败字段。
- 群名称只有在 WhatsApp 修改成功后才更新 `group_link.group_name` 镜像。
- 群备注从不调用协议层。

现有 `description` 和 `announcement-text` 后端接口保留兼容，但本抽屉不调用，也不新增对应前端控件。

### 7.2 群头像

补齐前端已有 multipart 契约：

```text
POST /api/group-links/{id}/avatar
Content-Type: multipart/form-data
file=<image>
```

Armada 校验文件类型和大小，转换为协议需要的字节/base64 后调用真实头像接口。协议层更新成功后读取新的 WhatsApp 群头像 URL，并返回 `avatarUrl`；Armada 再写入 `group_link_preview.avatar_url`。

外部调用和本地镜像更新不放在同一个数据库事务中。若 WhatsApp 已成功但头像 URL 回读或本地镜像写入失败，返回 `applied=true, mirrorSynced=false`，前端提示“头像已更新，本地列表待刷新”，不能把已经发生的 WhatsApp 修改误报成完全失败。

### 7.3 限时消息

新增：

```text
POST /api/group-links/{id}/timed-message
{ "mode": "off" | "24h" | "7d" | "90d" }
```

协议层新增对应路由并调用 `groupToggleEphemeral`：

- `off` -> `0`
- `24h` -> `86400`
- `7d` -> `604800`
- `90d` -> `7776000`

不支持自定义时长。成功后重新读取群元数据确认真实值。

### 7.4 五项群权限

Armada 使用单项命令接口，避免一个请求同时修改多个权限：

```text
POST /api/group-links/{id}/settings
{
  "key": "EDIT_GROUP_SETTINGS" | "SEND_MESSAGES" | "ADD_MEMBERS" |
         "INVITE_VIA_LINK" | "ADMIN_APPROVE_NEW_MEMBERS",
  "enabled": true
}
```

映射规则：

| `key` | 开启 | 关闭 | 协议调用 |
|---|---|---|---|
| `EDIT_GROUP_SETTINGS` | `unlocked` | `locked` | `groupSettingUpdate` |
| `SEND_MESSAGES` | `not_announcement` | `announcement` | `groupSettingUpdate` |
| `ADD_MEMBERS` | `all_member_add` | `admin_add` | `groupMemberAddMode` |
| `ADMIN_APPROVE_NEW_MEMBERS` | `on` | `off` | `groupJoinApprovalMode` |
| `INVITE_VIA_LINK` | 待能力验证 | 待能力验证 | 独立能力，不复用其它设置 |

`INVITE_VIA_LINK` 的实现分两道门：

1. 在已确认的测试账号和测试群中验证当前 WhatsApp/Baileys 是否能够读取、修改该权限，并确定真实 wire 契约。
2. 只有读写都验证成功后，才在协议层增加 `/settings/invite-link-access` 路由和元数据字段。

如果当前版本无法可靠读写，详情接口返回 `supported=false`，前端禁用该开关并显示原因。禁止把它映射成“添加其他成员”或“管理员审批”，也禁止协议层无操作却返回成功。

每次权限修改成功后重新读取对应真实状态；失败时前端恢复修改前状态。

### 7.5 成员管理

补齐前端已使用的 Armada API：

```text
POST /api/group-links/{id}/members/promote-batch
POST /api/group-links/{id}/members/demote-batch
POST /api/group-links/{id}/members/kick-batch
{ "jids": ["..."] }
```

规则：

- 一次请求使用一个自动选择的执行账号。
- 不允许对群主执行降级或踢出；前端继续锁定群主行，后端必须再次校验。
- 协议层使用现有 `groupParticipantsUpdate` 的 `promote`、`demote`、`remove`。
- 逐个映射协议返回状态，不因一名成员失败而回滚成功成员。
- 返回 `ok`、`partial`、汇总消息和逐项 `jid/status/reason`。
- 成功或部分成功后重新加载成员列表。

## 8. 错误与状态模型

Armada 统一输出稳定错误码，协议原始消息只用于诊断日志：

| 错误码 | 前端提示语义 |
|---|---|
| `GROUP_EXECUTOR_UNAVAILABLE` | 没有在线且仍在该群内的账号 |
| `GROUP_PERMISSION_DENIED` | 执行账号没有管理员权限 |
| `GROUP_CAPABILITY_UNSUPPORTED` | 当前 WhatsApp/协议版本不支持该设置 |
| `GROUP_MEMBER_NOT_FOUND` | 目标成员已不在群内 |
| `GROUP_OWNER_PROTECTED` | 群主不能被降级或踢出 |
| `GROUP_PROTOCOL_TIMEOUT` | 协议调用超时，操作结果待确认 |
| `GROUP_OPERATION_PARTIAL` | 部分成员操作成功，部分失败 |

处理规则：

- 权限不足时不自动换号重试。
- 修改群名称、头像、权限或限时消息失败时，不伪造本地成功状态。
- 协议超时后立即重新读取实际状态；仍无法确认时提示“操作结果待确认，请刷新”。
- 批量操作的成功项不回滚，失败项逐条展示原因。
- 控件提交期间禁用重复点击；成功后以重新读取结果覆盖本地临时状态。

## 9. 数据与租户边界

本期不新增数据库表或字段：

- 群名称镜像继续使用 `group_link.group_name`。
- 群备注继续使用 `group_link.remark`。
- 群头像镜像继续使用 `group_link_preview.avatar_url`。
- 权限、限时消息和成员列表均为 WhatsApp 实时状态，不持久化。

所有通过群链接 ID 的查询、选号和更新必须受当前租户约束。`GroupExecutionAccountSelector` 只能从同租户、未删除、在线、仍在该群的账号中选择。日志记录 Armada 账号 ID、群链接 ID、脱敏 group JID 和操作类型，不记录图片 base64、完整手机号或凭据。

## 10. 纵向实施顺序

### Slice 1：详情读取基础

- 协议元数据响应和 OpenAPI 补齐真实字段。
- Armada 自动选号组件和聚合详情接口。
- 前端删除权限固定默认值，真实回显详情和成员。

### Slice 2：群资料

- 群名称改为真实 WhatsApp 修改，备注保持本地。
- multipart 头像上传与本地头像镜像同步。

### Slice 3：限时消息

- 协议层暴露 `groupToggleEphemeral`。
- Armada 和前端接通四档设置。

### Slice 4：五项群权限

- 先完成已有四项稳定能力。
- 单独验证并接入“通过链接邀请”；不支持时完成能力禁用展示。

### Slice 5：成员管理

- 升管理员、降管理员、踢出三种批量操作。
- 逐项结果和刷新后的真实成员角色。

每个 Slice 必须三仓测试和测试群验收通过后再进入下一项。

## 11. 测试与验收

### 11.1 协议层

- 群元数据正确返回 `restrict`、`announce`、`memberAddMode`、`joinApprovalMode`、`ephemeralDuration` 和参与者角色。
- 四档限时消息正确映射到秒数。
- 四项已有权限设置调用正确 Baileys 方法和 mode。
- 参与者批量操作保留逐项结果。
- “通过链接邀请”只有在真实能力验证后增加成功测试；否则验证稳定返回 unsupported。
- master gateway 正确把新增请求转发到执行账号所属 owner worker。

### 11.2 Armada 后端

- 自动选号只选择当前租户下在线且仍在群内的账号，并优先管理员。
- 权限不足不选择第二个账号。
- 聚合详情在协议不可用时仍返回本地资料和不可用原因，不填假权限。
- 群名称和头像只有 WhatsApp 成功后才更新本地镜像。
- 群备注只更新本地数据库。
- 权限和限时消息请求体映射正确，修改后重新读取确认。
- 成员批量操作覆盖全成功、部分成功、全部失败、群主保护和成员已离群。
- DbTest 验证自动选号顺序、软删关系过滤和租户隔离；没有数据库迁移。

### 11.3 前端

- 打开抽屉时没有固定权限默认值闪烁或假回显。
- 协议不可用时保留本地群资料，实时控件禁用并展示原因。
- 群名称、备注联合保存时按字段展示结果。
- 限时消息和权限失败后恢复真实状态。
- unsupported 的“通过链接邀请”不可点击。
- 群主不可勾选执行降级或踢出；后端失败仍能逐项展示。
- 页面没有新增范围外入口。
- 运行项目可用的组件测试、typecheck、lint 和 build。

### 11.4 测试环境验收

远程调用前必须确认目标测试环境、测试账号和测试群。逐项在 WhatsApp 客户端中核对：

1. 读取状态与客户端一致。
2. 修改后客户端真实生效。
3. 页面重新打开后仍显示真实状态。
4. 无权限账号得到明确提示且没有换号执行。
5. 批量成员操作的逐项结果与群成员实际角色一致。

## 12. 回滚

- 前端可逐 Slice 回退 API 接入，但不能恢复固定假状态；未接通入口应禁用并显示原因。
- Armada 新接口可独立回退，不涉及数据库迁移和数据回滚。
- 协议层新增路由可独立回退；现有路由保持兼容。
- 已经成功写入 WhatsApp 的群设置、角色或头像属于外部事实，代码回滚不会自动反向修改 WhatsApp。

## 13. 自检

- 范围只包含当前抽屉已有入口，没有新增添加成员、邀请链接操作、审批列表或退群。
- “添加其他成员”和“通过链接邀请”均定义为权限设置，没有误写成实际成员操作。
- 前端不选择账号；Armada 自动选择在线、仍在群内、优先管理员的账号。
- 权限不足不换号重试；批量成员操作允许部分成功。
- 群名称和头像修改真实 WhatsApp 后再同步本地镜像；备注只在本地保存。
- 实时权限、限时消息和成员不新增数据库持久化。
- “通过链接邀请”明确要求真实能力验证，不猜测协议、不返回假成功。
- 文档没有待定占位符；测试环境信息属于执行前安全确认，不影响设计完整性。
