# 通讯录营销复刻详细设计

- 日期：2026-08-28
- 状态：**设计草案，待评审**
- 范围：`通讯录营销` 一级菜单及其下两个页面（`通讯录超链任务`、`通讯录剧本任务`）
- 基线（2026-08-28 三仓均已与 origin 齐平）：
  | 仓库 | 分支 | commit |
  |---|---|---|
  | `armada` | `1.0.3-snapshot` | `e1f5d195`（Flyway 已到 `V156`，本期从 `V157` 起编号） |
  | `wheel-saas-pure-web` | `1.0.3-snapshot` | `a9f039e` |
  | `armada-protocol` | `1.0.3-snapshot` | `60f40d9` |
  | `whatsapp-server` | `1.0.3-snapshot` | `f1faa36` |
- 工作分支：四个仓库均已从上述基线开出 `feat/contact-marketing`
- 复刻来源：`hylbuiaxykfrontendsource/readable/assets/`（仅构建产物，无 sourcemap、无后端源码）
- 上游文档：
  - 总设计 `docs/superpowers/specs/2026-08-27-hyperlink-marketing-replication-design.md`
  - 超链一期 `docs/superpowers/specs/2026-08-27-hyperlink-data-template-phase1-design.md`
  - 超链任务期 `docs/superpowers/specs/2026-08-27-hyperlink-task-strategy-asset-analysis-design.md`
  - 超链任务对齐 `docs/superpowers/specs/2026-08-27-hyperlink-task-competitor-parity-detailed-design.md`
  - 数据模型 `docs/business/hyperlink-marketing-data-model.md`

口径不变：**前端功能一致、接口仿照、后端按 armada 现有能力适配实现。**

---

## 0. 一句话结论

通讯录营销的消息形态比超链任务简单一档（只有链接消息和图文消息，**没有按钮**），
但它引入了一个 armada 从零开始的能力：**读取账号自己的通讯录**。

「发给谁」这一环没有任何现成实现可接——Web 协议不留联系人、armada 无表无字段。
因此本方案的关键路径是 `协议层读通讯录 → armada 落快照与计数 → 任务发送引擎 → 前端`，
而不是「照着超链任务再抄一遍」。

同时这条链路是**超链任务的硬依赖**：超链任务对齐设计已把「好友数、注册天数、允许拉群三项全部实现，
缺数据采集能力时菜单不算完成」写成结论，其中「好友数」筛选吃的正是本期采集的数据。
两个菜单共用一份账号通讯录资产，不各建一套。

---

## 1. 实施前必须遵守的既有结论

一期已冻结、本期**不得推翻**的前提：

| # | 结论 | 出处 |
|---|---|---|
| 1 | 接口前缀 `/api/<resource>`、字段 camelCase、返回 `ApiResponse<T>` / `PageResult<T>` | 超链一期 §7 |
| 2 | 任务事实表保存**快照**，不外键会变动的主数据 | 超链一期 §6.6 |
| 3 | 图片沿用 `marketing_template_file` 的字节存储与稳定 ID，不改表名、不复制字节 | 数据模型 §6.1 |
| 4 | 不做计费（armada 无计费体系）、不做国家风险拦截 | 数据模型 §10.1 |
| 5 | 数据库结构只走 Flyway，新列必须带 `COMMENT`，落地后重跑 `.harness/wiki/gen_datamodel.py` | AGENTS.md |
| 6 | 账号筛选条件以 JSON 存库前必须按白名单归一化，不能原样落前端 JSON | 超链任务期 §4.2 |
| 7 | 不机械复制竞品的往返缺陷（编辑回填丢字段等），armada 必须完整往返 | 超链任务对齐 §0 |
| 8 | 协议缺的能力全部补齐，不做能力降级；Web 按 Baileys 接，Android 照搬 Web 逻辑 | 用户决策，超链任务期 §4.3 |

---

## 2. 从静态前端确证的事实

以下均可在存档中复核，是本方案的输入，不是推断。

### 2.1 菜单只有两个页面，其中一个是空占位

`router-CPQmbuR9.js:49226-49255`：

| 路由 | 名称 | 组件 |
|---|---|---|
| `/contact/hyperlink` | 通讯录超链任务 | `hyperlink-BVNnqLDE.js`（2596 行，真实页面） |
| `/contact/script` | 通讯录剧本任务 | `script-DcoFUVM0.js`（**全文 28 行**） |

`script-DcoFUVM0.js` 整个组件就是一个 `Result` 空态，`status="info"`、`title=$t('common.lookForward')`。
竞品自己就是「敬请期待」。**逐字复刻这个占位，不编造功能。**

### 2.2 接口只有 6 个

`router-CPQmbuR9.js:46322-46377`：

```
GET    /api/admin/friend-tasks           列表
GET    /api/admin/friend-tasks/{id}      详情
POST   /api/admin/friend-tasks           新建（multipart/form-data）
PUT    /api/admin/friend-tasks/{id}      编辑（multipart/form-data）
POST   /api/admin/friend-tasks/{id}/action   { action }
GET    /api/admin/friend-tasks/{id}/data     账号维度发送数据
```

**没有删除接口**，行操作里也没有删除按钮。armada 同样不提供。

### 2.3 消息形态只有两种，且没有按钮

`message_type`：`0` 链接消息 / `1` 图文消息。整页搜不到任何按钮编辑器引用。

| 形态 | 字段 | 图片 |
|---|---|---|
| `0` 链接消息 | `title` 消息标题（卡片加粗大字）、`description` 链接描述（标题下小字）、`promotion_link` 推广链接、`content` 正文内容 | 链接预览图。**校验规则里没有它**（必填项只有 title / description / promotion_link / content），但 UI 把上传区放在最显眼处 |
| `1` 图文消息 | 仅 `content` 图文文案 | 图文配图，页面明示**可选**：「不传则仅发文字」 |

图片限制：`accept=".jpg,.jpeg"`、`≤ 500KB`、建议 16:9。

### 2.4 提交体逐字段（`hyperlink-BVNnqLDE.js:805-829`）

```
name                  必填，maxlength 128，trim
account_filter        JSON 字符串（注意：是字符串，不是对象）
message_type          0 | 1
title                 message_type=0 时取值，否则恒为 ''，maxlength 512
description           message_type=0 时取 link_description，否则恒为 ''，maxlength 2048
content               必填，maxlength 2000
promotion_link        message_type=0 时取值，否则恒为 ''，maxlength 2048
task_delay_minutes    start_mode=now 时恒为 0
status                0 已停用 | 1 启用
msg_interval_min_sec  DECIMAL，Math.round(x*10)/10
msg_interval_max_sec  DECIMAL，且被裁剪为 >= min
concurrency           默认 10，范围 1~200
max_sends_per_account 默认 50，min 0 step 10，0 = 全部好友
retry_max             默认 3，范围 0~10
link_preview_image    File，仅当用户选了新图才 append
```

注意前端表单字段 `link_description` 对应后端字段 `description`（回填时 `link_description: e.description`）。

### 2.5 发送间隔是**秒带一位小数**

默认 `[0.5, 1]`，滑杆范围 `0.1 ~ 30`，最小值输入下限 `0.1`、最大值输入上限 `60`。四档预设：

| 预设 | 区间 |
|---|---|
| 最快 | 0.1 ~ 0.1 秒 |
| 平台推荐 | 0.5 ~ 1 秒 |
| 稳健 | 1 ~ 3 秒 |
| 防风控 | 3 ~ 5 秒 |

页面文案定义了语义：「间隔 = 同一个号给两个好友发消息之间至少等几秒」，实际发送时**在区间内随机取值**。

### 2.6 任务有两个独立状态字段

与超链任务同一套口径：

| 字段 | 取值 |
|---|---|
| `status` | `0` 已停用（仅保存不发送） / `1` 启用 |
| `task_status` | `0` 未开始 / `1` 进行中 / `2` 已完成 / `3` 已暂停 / `4` 已停止 |

展示优先级：`status=0` 一律显示「已停用」，否则按 `task_status` 显示。

行操作按 `task_status` 分支：`0`→启动+编辑；`1`→暂停+停止+查看；`3`→恢复+停止+查看；`2|4`→仅查看。
**「账号数据」按钮在任何状态都有。** 停止的确认文案明写「停止后任务将被终止，且无法恢复」。

### 2.7 账号范围

任务页在提交时对筛选条件做归一化，透传的键是完整弹窗字段的一个子集
（`hyperlink-BVNnqLDE.js:762-786`）：

```
country_iso2s / exclude_country_iso2s / continent
channel_ids / group_ids / protocol_id
online_status / account_type / platform / wid_type / group_invite_allowed
phone / error_code / error_desc
friend_count_min|max / retention_days_min|max / register_days_min|max
created_at_from|to / logged_in_from|to
```

**强制注入**（仅当筛选条件非空时）：`account_status: 'normal'`、`is_exported: false`。
筛选条件为空时提交 `{}`，语义为「未限制（全部有效账号）」。

> 与超链任务的差异，两条都是**真实差异不是笔误**：
> ① 通讯录任务**不注入** `stranger_muted: false`（超链任务注入）；
> ② 通讯录任务的 `group_invite_allowed` 是自由筛选项，不锁定。

页面会用 `account_filter + account_status:'normal' + is_exported:false` 实时试算命中账号数，
命中 0 且 `status=1` 时把「账号范围」区块标红并阻止启用。

### 2.8 「好友」在竞品里是两个不同的数

这条是本方案的口径基础，必须分清：

| 字段 | 页面标题 | 竞品注释 |
|---|---|---|
| `name_num` | 好友数 | 通讯录里**有名字**的联系人数 |
| `friend_count` | 双向好友 | 互加的好友数 |

出处：账号管理列表 `manage-MZBfmYTV.js:2138-2148`。

账号筛选弹窗的控件叫「**双向好友数** ≥ / ≤」，提交字段是 `friend_count_min|max`
（`account-filter-modal-BXDIvipG.js:1325,1344`）——即筛选用的是**双向好友**口径。

而通讯录任务页的说明文案是「每个账号会向自己的**好友列表**中的所有好友发送同一条消息」、
「本任务面向账号**好友**群发」，页面 badge 是 `好友群发`。结合菜单叫「通讯录营销」，
**发送目标集用通讯录联系人口径（`name_num`），筛选控件用双向好友口径（`friend_count`）。**

### 2.9 通讯录是在**上线验号**时采集的

四条独立证据：

| 证据 | 出处 |
|---|---|
| 账号管理列表把 `name_num` / `friend_count` 作为常驻列渲染 | `manage-MZBfmYTV.js:2138-2148` |
| 账号筛选弹窗有「双向好友数 ≥ / ≤」——可下推的 SQL 条件必须落列 | `account-filter-modal-BXDIvipG.js:1325,1344` |
| 导入批次统计「0好友 / 率」注释：**导入成功且上线有效的账号中**，通讯录有名字联系人的数量 | `import-C5wK2Ml_.js:2081` |
| 同页评分栏注释：**验号没跑完时显示「验证中」，跑完才出分**（头像/群组同批采集） | `import-C5wK2Ml_.js:2094` |
| 导入批次可按「账号无好友」「账号无双向好友」导出 zip | `import-C5wK2Ml_.js:1257` |

结论：竞品在账号上线验号阶段采集通讯录，把计数落成账号持久属性。
完整联系人列表是否落库前端看不出来（无任何展示单账号通讯录的页面），
但任务详情的 `need_send_num`（每账号计划发送数）在任务启动时即有值。

### 2.10 列表页与账号数据抽屉

**列表 7 列**（`hyperlink-BVNnqLDE.js:2036-2252`）：

| 列 | 内容 |
|---|---|
| 消息类型 / 内容 | 链接消息显示「标题 / 推广链接」；图文消息显示「图文消息 / 文案预览」 |
| 状态 | `status` + `task_status` 组合口径 |
| 进度（成功 / 计划） | `success_message_num / total_send_num`，绿色条为成功率 |
| 账号统计 | `used_account_count` 使用号数、`invalid_account_num` 封号数、`avg_send_per_account` 号均发量 |
| 账号范围 | 国家标签（含排除，带国旗）+ 文本标签，超 3 个折叠 `+N` |
| 计划开始时间 | `task_start_at` |
| 操作 | 见 §2.6 |

顶部搜索：任务名模糊、状态下拉（未开始/进行中/已完成/已暂停/已停止）、创建时间区间。
分页可选 `10/20/50/100/200`。导出 CSV 走前端本页数据，12 列。

**账号数据抽屉**（`GET /{id}/data`，宽 960，右侧）：
`account_id`、`account_phone`（带有效/无效 tag）、`need_send_num`、`sent_num`、`fail_num`、`progress`。
后三个数值列**支持服务端排序**（`sort_by` + `sort_order=asc|desc`）。分页 `10/20/50/100/200`。

### 2.11 两条不复刻的竞品行为

1. **新建按钮在竞品当前构建里是禁用的**（`hyperlink-BVNnqLDE.js` 页头 `disabled: ''`）。
   整套新建/编辑抽屉代码完整存在，只是入口被关。判断为竞品灰度中，**armada 开放该入口**。
2. **单价 badge**：页面会调 `resource` 价格接口取 `friend_task` 单价并显示「好友任务单价：x USDT/条」。
   armada 无计费体系（既有结论 #4），**不复刻该 badge**。

---

## 3. 与 armada 现有能力的对账

### 3.1 可直接复用

| 能力 | 位置 | 用途 |
|---|---|---|
| 轮次调度范式 | `marketing/scheduler/MarketingRoundScheduler` + `MarketingRoundWorker` + `MarketingTaskLifecycleWorker` | 任务轮次派发与生命周期收敛 |
| 协议无关发送命令 | `platform/protocol/model/command/MessageSendCommand` + `WebMessageSendBackend` / `AndroidMessageSendBackend` | 链接卡片与图文发送 |
| 协议分流 | `ProtocolBackend.fromProtocolId(account.protocolId)` → `WEB` / `ANDROID` | 通讯录读取与发送的后端选择 |
| Kafka 协议事件消费骨架 | `platform/kafka/consumer/message/ProtocolMessageEventConsumer` | 发送结果回流 |
| 图片存储 | `marketing_template_file` + `MarketingTemplateFileService`（JPEG + 500KB 校验） | 预览图 / 配图字节层 |
| 菜单与权限迁移范式 | `V155__hyperlink_marketing_menu_rbac.sql` | 本期菜单节点 |
| 账号筛选圈号服务 | `HyperlinkAccountSelector`（超链任务期设计，尚未实现） | 本期与超链任务**共建共用** |

### 3.2 必须新建或扩展

| # | 缺口 | 处理 |
|---|---|---|
| 1 | **无任何读取账号通讯录的能力**：Web 协议丢弃 app-state 联系人；armada 无表无字段 | 新建协议接口 + `account_contact` 快照链路（§5、§6） |
| 2 | `MessageSendCommand.MessageTarget` 只有 `groupJid` | 改为语义中立的 `MessageTarget(String jid)`；私聊目标 `<phone>@s.whatsapp.net`。**跨业务共享 record，与超链任务期 §3.2-① 是同一处改动，合并成一次做** |
| 3 | `MessageCorrelation` 无通讯录分支 | 增 `ContactTaskCorrelation(taskId, taskAccountId, recipientId, roundNo)` |
| 4 | 三层发送链路都是群语义，无私聊路由 | 与超链任务期 §4.3-B 同一处改动，合并（§5.2） |
| 5 | `account` / `account_state` 无好友数字段 | `account_state` 增两列冗余计数，仅供筛选下推（§4.1） |
| 6 | `ProtocolMessageEventConsumer` 无通讯录任务分支 | 增分支回写三级计数（§7.3） |
| 7 | 账号占用模型是**分组级**（`account_group.marketing_occupancy_type`） | 通讯录任务按筛选跨分组圈号，**不套用分组占用锁**，与超链任务期 §4.4 同一结论 |

### 3.3 为什么不复用 `marketing_task` 加 `business_type`

两条硬理由：

1. **占用模型不兼容**。`marketing_task` 的账号占用是分组级的，
   由 `MarketingAccountOccupancyService` 按 `account_group.marketing_occupancy_type` 抢占。
   通讯录任务按账号字段筛选，命中的账号跨分组，套不上这把锁。
2. **目标语义不同**。`marketing_task_target` 的目标是群，通讯录任务的目标是
   「账号 × 该账号自己的联系人」——这是一个二级展开结构，塞进现有单级 target 表
   会让 `need_send_num`（每账号计划发送数）这种账号维读模型无处安放。

---

## 4. 模块边界

两块东西**故意拆开**：

| 归属 | 内容 | 理由 |
|---|---|---|
| `com.armada.account.contact` | 通讯录采集、`account_contact` 快照、计数回流 | 这是**账号资产**不是通讯录营销专属。超链任务对齐设计已把「好友数筛选必须实现」写成结论，吃的是同一份数据。放进营销包等于把超链任务的依赖埋进另一个业务域 |
| `com.armada.contact` | 任务 CRUD、轮次发送引擎、账号维度统计 | 与 `com.armada.hyperlink` 平级 |

前端：`src/views/contact/hyperlink/` 与 `src/views/contact/script/`，与 `src/views/hyperlink/` 平级。

### 4.1 账号侧字段落位

`account_state` 增两列（**只为筛选下推**，`好友数 ≥/≤` 必须能进 SQL 不能回表算）：

```sql
contact_named_num   INT NOT NULL DEFAULT 0 COMMENT '通讯录中有名字的联系人数（竞品「好友数」口径）'
contact_mutual_num  INT NOT NULL DEFAULT 0 COMMENT '双向好友数（竞品「双向好友」口径）'
```

写入点**唯一**：通讯录同步完成时由 `AccountContactSyncService` 与 `account_contact_sync` 一并更新。
任何其他地方不得直写这两列——否则筛选口径会分裂。

---

## 5. 协议能力补齐（本期硬前置）

两块缺口互相独立，都必须补：**A 读通讯录**、**B 私聊目标**。

### 5.1 A. 读取账号通讯录

#### Web（`armada-protocol`）

现状：`routes/contacts.ts` 全文 80 行，只有 `save` / `delete` / `block` / `unblock` 与 6 个 chat 操作，
**没有 list**；`worker/event-bridge.ts` 也不消费 `contacts.upsert` / `contacts.set`，app-state 同步出来的联系人直接丢弃。

改动：

1. 在 account 运行时加一个 per-account 内存 contact store，随 socket 生命周期存续；
   订阅 Baileys 的 `contacts.set` / `contacts.upsert` / `contacts.update` 三个事件写入。
2. 新增 `GET /v1/accounts/{accountId}/contacts`，返回：

```jsonc
{
  "accountId": "…",
  "contacts": [
    { "jid": "8613800000000@s.whatsapp.net",
      "phone": "8613800000000",
      "name": "…",        // Baileys Contact.name
      "notify": "…",      // pushName
      "verifiedName": "…" // 商业号名称
    }
  ],
  "syncedAt": 1756345678901
}
```

3. **持久化交给 armada**，协议层只做当前会话的投影，不落库。

#### Android（`whatsapp-server`）

现状：联系人**已经在 MySQL 里**。`internal/service/appstate/contact.go:FilterContacts` 从 app-state
mutation 里过滤出 `ContactEntry{JID, LID, FirstName, FullName}`，
`internal/service/axolotl/store/contacts.go:StoreAllContacts` 批量落 `contacts` 表，
`LoadContacts(ownerId)` 已实现。**只缺 HTTP 出口。**

改动：新增 `POST /ws/v1/contacts/list/{key}`，照搬 `api/service/sync.go` 现有返回壳，
字段名对齐 Web 侧契约后返回。

#### armada 侧

新增 `ContactListPort` + `WebContactListBackend` / `AndroidContactListBackend`，
走现有 `ProtocolBackend.fromProtocolId()` 分流，返回统一的 `AccountContactSnapshot`。

#### ⚠️ 两处必须真机验证的口径（仓库里确证不了）

| # | 问题 | 影响 | 验证方式 |
|---|---|---|---|
| V1 | Baileys 的 contact store 在**冷启动 / 重连**后是否仍为全量。app-state 是增量 patch 机制，重连后可能只收到本次会话的变更，而非完整通讯录 | 决定 Web 侧能否只靠事件投影，还是必须主动触发一次全量 app-state resync | 真机：同一账号冷启动 → 拉 list → 与手机端通讯录条数比对 |
| V2 | **双向好友**如何判定。Android `ContactEntry` 只有 `JID / LID / FirstName / FullName`，没有双向标记；Baileys `Contact` 同样没有 | 决定 `contact_mutual_num` 能否落地。若两侧都拿不到，本期只交付 `contact_named_num`，`双向好友数 ≥/≤` 筛选项**不渲染控件**（灰置控件比没有控件更让人误解） | 真机：抓一个已知双向/单向混合的号，比对可得字段 |

**V1 / V2 在真机验证通过前，P2 及之后不开工。** 否则会写出一套拿不到人的发送链路。

### 5.2 B. 私聊目标路由

`MessageSendCommand.MessageTarget(String groupJid)` → `MessageTarget(String jid)`，
私聊填 `<phone>@s.whatsapp.net`。这是**跨业务共享 record，影响所有营销发送链路**。

超链任务期设计 §3.2-① 与 §4.3-B 已经做了完全相同的决定。
**两边合并成一次改动，谁先落地谁做，另一方直接消费，不各改一遍。**

通讯录任务只需要 `MessageType.LINK_CARD` 与图片+文案两种形态，**不碰按钮**，
因此完全绕开了超链任务期 §4.3-A 那套 `cta_call` / 按钮数组的四层门改造。

---

## 6. 数据模型

### 6.1 `V157__account_contact_sync.sql`

```
account_contact                          账号通讯录快照
  id BIGINT PK
  tenant_id            租户
  account_id           所属账号
  contact_phone        联系人号码（纯数字，无 + 号）
  contact_jid          联系人 JID
  full_name            通讯录全名
  first_name           通讯录名
  push_name            对方设置的展示名
  business_name        商业号名称
  is_named    TINYINT  full_name 或 first_name 非空 → 竞品「好友数」口径
  is_mutual   TINYINT  双向好友（V2 验证通过后才有真值，否则恒 0）
  synced_at            本行所属同步批次时间
  created_at / updated_at
  UNIQUE KEY (tenant_id, account_id, contact_phone)
  KEY (tenant_id, account_id, is_named)

account_contact_sync                     每账号一行的同步状态
  tenant_id / account_id      UNIQUE KEY (tenant_id, account_id)
  last_synced_at
  last_sync_source     ONLINE_PROBE | TASK_START | MANUAL
  contact_num          本次同步到的联系人总数
  named_num            其中有名字的
  mutual_num           其中双向的
  sync_status          SUCCESS | FAILED | SYNCING
  fail_reason
  created_at / updated_at
```

同时给 `account_state` 加 §4.1 的两列。

**同步是整批替换语义**：一次成功同步内，先按 `(tenant_id, account_id)` 批量 upsert 本批号码，
再删除 `synced_at < 本批 synced_at` 的残留行。失败时**不动任何已有数据**，只写 `sync_status=FAILED`。

### 6.2 `V158__contact_friend_task.sql`

```
contact_friend_task                      任务主表
  id / tenant_id / created_by
  name                 VARCHAR(128)
  message_type         TINYINT  0 链接消息 / 1 图文消息
  title                VARCHAR(512)    仅 message_type=0
  description          VARCHAR(2048)   仅 message_type=0（前端叫「链接描述」）
  promotion_link       VARCHAR(2048)   仅 message_type=0
  content              VARCHAR(2000)
  preview_image_file_id BIGINT          → marketing_template_file.id，可空
  account_filter       JSON            白名单归一化后落库
  msg_interval_min_sec DECIMAL(4,1)
  msg_interval_max_sec DECIMAL(4,1)
  concurrency          INT   最大执行账号数
  max_sends_per_account INT  0 = 全部联系人
  retry_max            INT
  start_mode           VARCHAR(16)  now | scheduled
  task_delay_minutes   INT
  task_start_at        BIGINT       计划开始时间
  is_enabled           TINYINT  0 已停用 / 1 启用
  run_status           TINYINT  0 未开始 1 进行中 2 已完成 3 已暂停 4 已停止
  next_round_at        BIGINT
  -- 列表页直接读的汇总，不实时聚合
  total_send_num / success_message_num / used_account_count
  invalid_account_num / avg_send_per_account
  created_at / updated_at / deleted_at

contact_friend_task_account              任务 × 账号（GET /{id}/data 的读模型）
  id / tenant_id / task_id / account_id
  account_phone_snapshot                快照，账号改号不影响历史
  account_status_snapshot               valid | invalid
  need_send_num / sent_num / fail_num
  state                PENDING | RUNNING | DONE | FAILED | SKIPPED
  contact_synced_at    本任务用的通讯录快照时间
  created_at / updated_at
  UNIQUE KEY (task_id, account_id)
  KEY (task_id, need_send_num) / (task_id, sent_num) / (task_id, fail_num)   -- 支撑服务端排序

contact_friend_task_recipient            任务 × 账号 × 联系人
  id / tenant_id / task_id / task_account_id
  contact_phone / contact_jid           快照，不外键 account_contact
  contact_named TINYINT                 快照当时是否有名字
  send_status          PENDING | SENDING | SUCCESS | FAILED
  attempt_count / protocol_message_id / error_code / error_desc
  first_sent_at / last_attempt_at
  created_at / updated_at
  UNIQUE KEY (task_id, task_account_id, contact_phone)    -- 幂等键
  KEY (task_id, send_status)
```

recipient 存**快照**不外键 `account_contact`——沿用超链一期 §6.6 的既有结论：
通讯录会变，任务事实不能跟着漂。

### 6.3 `V159__contact_marketing_menu_rbac.sql`

照抄 `V155` 写法：目录节点 `ContactMarketing`（`/contact`，`sort_no` 排在 `HyperlinkMarketing` 之后），
两个页面节点 `ContactHyperlinkTask`（`/contact/hyperlink`）与 `ContactScriptTask`（`/contact/script`）。

权限节点四个：`tenant:contact_task:{view,create,edit,operate}`。
迁移只创建节点，不自动给普通角色授权，部署后由管理员按角色显式配置。

---

## 7. 后端设计

### 7.1 接口契约

竞品是 `/api/admin/friend-tasks` + snake_case；armada 一期已冻结 `/api/<resource>` + camelCase。
**沿用 armada 规范**，前端做字段映射（与超链一期同一处理方式）。

```
GET    /api/contact-tasks              tenant:contact_task:view
POST   /api/contact-tasks              tenant:contact_task:create   multipart
GET    /api/contact-tasks/{id}         tenant:contact_task:view
PUT    /api/contact-tasks/{id}         tenant:contact_task:edit     multipart
POST   /api/contact-tasks/{id}/action  tenant:contact_task:operate
GET    /api/contact-tasks/{id}/data    tenant:contact_task:view
```

列表查询参数：`name`、`runStatus`、`createdAtStart`、`createdAtEnd`、`page`、`pageSize`。
账号数据查询参数：`page`、`pageSize`、`sortBy`（`needSendNum|sentNum|failNum`）、`sortOrder`（`asc|desc`）。

**不提供删除接口。**

编辑限制：`run_status != 0` 时拒绝编辑（`409`）；`messageType` 在编辑态一律不可改（前端禁用 + 后端拒绝）。

### 7.2 生命周期

```
创建 is_enabled=0          → run_status=0，不入队，可自由编辑
创建 is_enabled=1          → 校验可用账号 → 圈号 → 展开 recipient → run_status=0，入队
                              start_mode=scheduled 时 task_start_at = now + delay*60000
action=start   run_status 0 → 1
action=pause   run_status 1 → 3    在途 attempt 跑完即止，不撤回已入队命令
action=resume  run_status 3 → 1
action=stop    run_status 1|3 → 4  终态，不可恢复
自动完成       run_status 1 → 2    全部 recipient 落终态
```

### 7.3 发送引擎

复刻 `MarketingRoundScheduler` 三件套范式，新建
`ContactTaskRoundScheduler` / `ContactTaskRoundWorker` / `ContactTaskLifecycleWorker`（均 `@Profile("kafka")`）。

启用流程：

```
1. HyperlinkAccountSelector 按 account_filter 圈号（强制注入 accountStatus=normal、isExported=false）
2. 逐账号检查 account_contact_sync.last_synced_at
     超过 TTL（默认 24h，配置项 armada.contact.snapshot-ttl-hours）→ 发 ContactListPort 重拉并落快照
     未超过 → 直接用现有快照
3. 写 contact_friend_task_account.need_send_num
     = 该账号 account_contact 中 is_named=1 的条数，受 max_sends_per_account 截断（0 表示不截断）
4. 展开 contact_friend_task_recipient
5. 汇总写 task.total_send_num / used_account_count
```

轮次流程：

```
每轮取一批 PENDING recipient（批量大小受 concurrency 约束：同时在跑的账号数上限）
  → 组装 MessageSendCommand
      target = MessageTarget(contactJid)
      payload = LINK_CARD（message_type=0）或 IMAGE+caption / TEXT（message_type=1）
      sendIntervalMs = [minSec, maxSec] 区间内随机取值 × 1000，逐条计算，不是固定值
      correlation = ContactTaskCorrelation(taskId, taskAccountId, recipientId, roundNo)
  → MessageSendPort 写 outbox → Kafka → 协议层实发
```

回执流程：`ProtocolMessageEventConsumer` 增 contact 分支，按 correlation 回写三级计数
（recipient → task_account → task）。失败且 `attempt_count < retry_max` 时置回 `PENDING` 重排。

### 7.4 `account_filter` 归一化

**入库前按白名单归一化**（既有结论 #6）：未知键丢弃、国家码大写、ID 去重、数值下界裁剪、空值剔除。
白名单即 §2.7 列出的键集。归一化后为空对象则语义为「不限定」。

竞品编辑回填时会丢掉 `source` 等字段（显示函数认得，归一化函数不透传），
按既有结论 #7，**armada 完整往返，不复刻这个缺陷**。

---

## 8. 前端设计

`src/views/contact/`：

```
contact/
  hyperlink/
    index.vue                            页面壳：page-hero + 搜索卡 + 列表卡
    components/
      ContactTaskSearchCard.vue          任务名 / 状态 / 创建时间
      ContactTaskDrawer.vue              新建 / 编辑 / 只读三态共用
      ContactTaskPreview.vue             WhatsApp 实时预览
      ContactTaskAccountDrawer.vue       账号发送数据（GET /{id}/data）
    composables/
      useContactTaskPage.ts
      useContactTaskForm.ts
    domain/
      task-form.ts                       表单默认值、校验规则、提交体组装
      interval-preset.ts                 四档预设与区间匹配
  script/
    index.vue                            Result 空占位，逐字复刻
```

抽屉四段式与竞品一致：`1 基础信息`（消息类型 + 任务名 + 账号范围）、
`2 消息内容`、`3 发送策略`、`4 发布`。左侧 WhatsApp 实时预览，右侧表单，`readonly` 时整块 `inert`。

复用现有组件：账号筛选弹窗、国家标签、`table-header-operation`、`use-export-csv`、`page-hero`。

菜单与路由按 `src/router/` 现有范式接入，补 `contact-route.test.ts`（对齐 `hyperlink-route.test.ts`）。

**不复刻**：单价 badge（§2.11-2）。**开放**：新建按钮（§2.11-1）。

---

## 9. 分期与依赖

| 期 | 内容 | 仓库 | 前置 |
|---|---|---|---|
| P0 | 协议层读通讯录：Web contact store + `GET /v1/accounts/{id}/contacts`；Android `POST /ws/v1/contacts/list/{key}` | `armada-protocol`、`whatsapp-server` | **真机验证 V1 / V2** |
| P1 | `MessageTarget` 中立化 + 私聊发送路由 | `armada` + 两个协议仓 | 与超链任务期共用，需协调先后 |
| P2 | `V157` + `AccountContactSyncService` + 计数回流 + 上线验号挂钩 + `ContactListPort` | `armada` | P0 |
| P3 | `V158` + 6 个接口 + 轮次引擎 + 回执回流 | `armada` | P1、P2 |
| P4 | `V159` + 前端菜单、任务页、占位页、RBAC | `armada`、`wheel-saas-pure-web` | P3 |

### 9.1 与超链任务的协调点

| 共用物 | 说明 |
|---|---|
| `MessageTarget` 中立化 + 私聊路由 | 同一处改动，只做一次 |
| `HyperlinkAccountSelector` | 同一个圈号服务，通讯录任务复用；本期不再单造一个 |
| 账号通讯录数据 | 超链任务的「好友数 ≥/≤」筛选吃本期 P2 的产出 |

---

## 10. 明确不做

| 项 | 理由 |
|---|---|
| 任务删除 | 竞品无接口无按钮（§2.2） |
| 计费 / 单价 / 冻结 | armada 无计费体系（既有结论 #4） |
| 通讯录剧本任务的实际功能 | 竞品自己就是空占位（§2.1） |
| 按钮消息 | 通讯录任务只有链接消息与图文消息，无按钮（§2.3） |
| 前端定时自动刷新 | 与超链任务对齐设计同口径，只保留手动刷新 |
| `双向好友数 ≥/≤` 筛选（**条件性**） | 仅当真机验证 V2 判定两侧都拿不到双向标记时才不做；届时**不渲染控件**而非灰置 |

---

## 11. 待办 / 未决

| # | 事项 | 阻塞谁 |
|---|---|---|
| 1 | 真机验证 V1：Baileys contact store 冷启动后的全量性 | P0 → 全部 |
| 2 | 真机验证 V2：双向好友标记可得性 | `contact_mutual_num`、筛选控件 |
| 3 | 真机验证：私聊群发的风控表现（间隔下限是否够用） | P1 默认参数取值 |
| 4 | 与超链任务期协调 `MessageTarget` 改动的落地顺序 | P1 |
| 5 | ~~两个协议仓无法 fetch~~ 已解决：2026-08-28 由用户手动拉取，两仓均与 origin 齐平 | — |
