# 超链任务 / 超链策略 / 图片素材 / 超链市场分析 详细方案

- 日期：2026-08-27
- 状态：**设计草案，待评审**
- 范围：超链营销一级菜单下除「超链数据包」「超链营销模板」之外的**四个模块**
- 基准分支：`1.0.3-snapshot`（一期已合入 `V153`~`V155`，本期从 `V156` 起编号）
- 上游文档：
  - 总设计 `docs/superpowers/specs/2026-08-27-hyperlink-marketing-replication-design.md`
  - 一期详细方案 `docs/superpowers/specs/2026-08-27-hyperlink-data-template-phase1-design.md`
  - 数据模型 `docs/business/hyperlink-marketing-data-model.md`
- 复刻来源：`hylbuiaxykfrontendsource/readable/assets/`（仅构建产物，无 sourcemap、无后端源码）

口径不变：**前端功能一致、接口仿照、后端按 armada 现有能力适配实现**。

---

## 0. 一句话结论

四个模块里，**超链任务是唯一的重活**——它是发送引擎；策略是它的参数预设，素材库是它的图片来源，市场分析是它的读模型。
因此本方案按「协议补齐 → 策略 → 素材 → 任务 → 回流 → 深度追踪 → 市场分析」的依赖顺序分七期落地。

协议层缺的能力（按钮类型、私聊目标）**全部补齐，不做降级**（§4.3）。这决定了 P0 是协议层，
任务期在协议真机验证通过之前不开工——否则会写出一套发不出去的发送链路。

---

## 1. 实施前必须遵守的既有结论

一期已冻结、本期**不得推翻**的前提：

| # | 结论 | 出处 |
|---|---|---|
| 1 | 接口前缀 `/api/<resource>`、字段 camelCase、返回 `ApiResponse<T>` / `PageResult<T>` | 一期 §7 |
| 2 | 号码池状态机 `1未使用 2已领取 3当前单钩 4已送达 5可重试失败 6未注册`，写在 `data_package_phone.pool_status` | `V153` |
| 3 | 任务领取号码：锁 `data_package` 读 `current_generation`，按 ID 升序、条件 `generation=current AND pool_status=UNUSED` 批量更新为 CLAIMED | 一期 §13.1 |
| 4 | 消息内容契约（`HyperlinkMessageContent` + `HyperlinkMessageContentValidator`）模板与任务共用，一套字段长度、一套校验器 | `HyperlinkMessageContentValidator.java` |
| 5 | recipient 保存包 ID/代次/导入批次/号码/国家**快照**，不保存 `data_package_phone_id` | 一期 §6.6 |
| 6 | 协议消息 ID、消息分片、重试结果属于 `hyperlink_delivery_attempt`，不塞进 recipient | 一期 §6.6 |
| 7 | 图片素材沿用 `marketing_template_file` 的字节存储与稳定 ID，不改表名、不复制字节 | 数据模型 §6.1 |
| 8 | 不做计费（armada 无计费体系）、不做国家风险拦截、不做批量号码预探测 | 数据模型 §10.1 |
| 9 | 数据库结构只走 Flyway，新列必须带 `COMMENT`，落地后重跑 `.harness/wiki/gen_datamodel.py` | AGENTS.md / 数据模型规范 |

---

## 2. 从静态前端确证的事实（本期新增）

以下均可在存档中复核，是本方案的输入，不是推断。

### 2.1 消息类型：新建时只有三种

`task-0vbZUOmq.js:1732-1747` 的消息类型选项**只有 `3 普通按钮` / `4 卡片按钮` / `1 单图文`**。
`2 双图文` 只在渲染历史数据时出现，竞品自己不允许新建。

> 这与一期「双图文提交返回 `40001 一期暂不支持双图文`」**完全一致**。
> **本期同样不开放双图文**，且这不再是"暂不支持"，而是与竞品一致的最终形态。
> 收益很实在：一次发送恒等于一个物理消息，`hyperlink_delivery_attempt.message_part_no` 只在重试时增长。

### 2.2 按钮：最多 1 个，四种类型

- 数量上限 **1**（`最多 1 个 · 文案建议带 emoji…`）。
- 类型与必填值：`cta_url`→`url`、`cta_call`→`phone_number`、`cta_copy`→`copy_code`、`quick_reply`→仅文案。
- 默认按钮：`{type:'cta_url', display_text:'🔥 立即抢购', url:'…'}`。

### 2.3 深度追踪是**按钮级**，不是任务级

`task-0vbZUOmq.js:1677-1680`：开关落在 `buttons[].use_short_link`，页面展示
`已开启（r/n 个按钮）`；链接卡片类型（单图文）直接显示
`—（链接卡片类型不支持按钮级深度追踪）`。

结论：**单图文没有点击数据**，只有按钮消息的 `cta_url` 按钮能开深度追踪。
任务级"是否开启深度追踪"是从按钮派生的展示值。

### 2.4 消息间隔是**秒带一位小数**

`Re = [.5, .7]`，范围 `0 ~ 10`，`Math.round(t*10)/10`；三档预设：

| 预设 | 区间 |
|---|---|
| 激进模式 | 0 ~ 0.3 秒 |
| 普通模式 | 0.5 ~ 0.7 秒 |
| 稳健模式 | 1.0 ~ 1.2 秒 |

提交字段仍叫 `msg_interval_min_sec` / `msg_interval_max_sec`，但值是小数。

### 2.5 任务有**两个**独立状态字段

`task-0vbZUOmq.js:5094-5119`：

| 字段 | 取值 |
|---|---|
| `status` | `0` 已停用（仅保存不发送） / `1` 启用 |
| `task_status` | `0` 未开始 / `1` 进行中 / `2` 已完成 / `3` 已暂停 / `4` 已停止 |

展示优先级：`status=0` 一律显示「已停用」，否则按 `task_status` 显示。

### 2.6 并发校验的三条硬规则

```
concurrent_num ≤ 协议台数 × 15                             （提示："您当前拥有 N 台协议，最多支持 N*15 个最大执行账号"）
concurrent_num ≤ max_use_account                          （max_use_account > 0 时）
concurrent_num × account_send_concurrency ≤ 10000          （常量 mr = 1e4）
1 ≤ account_send_concurrency ≤ 100
周期模式：task_interval ≥ 1 分钟，max_use_account ≥ 1
预发布模式：task_planned_end_at 晚于当前至少 1 分钟
延后执行：task_delay_minutes > 0
```

默认值：`concurrent_num=10`、`account_send_concurrency=20`、`max_use_account=0`、`account_max_send_num=0`、`task_interval=60`。

### 2.7 策略只承载 **6 个**参数

`strategy-D2fnr_pX.js:657-671` 的提交体里，`account_send_concurrency` 恒为 `20`、
`msg_interval_min_sec` / `msg_interval_max_sec` 恒为 `0`——**这三个字段是硬编码常量，策略页没有对应控件**。

策略真正承载的是：`type`、`task_interval`、`account_filter`、`concurrent_num`、`max_use_account`、`account_max_send_num`（外加 `name` / `status`）。
任务页的提示文案也印证了这一点：「带入任务模式 / 账号范围 / 并发 / 限号 / 周期间隔，不影响消息内容 / 数据包 / 启动时机」。

> 另一处差异：策略的周期间隔下限是 **30 分钟**，任务的是 **1 分钟**。逐字复刻，两边都保留。

### 2.8 素材库

- 列表：网格，`pageSize` 24（可选 12/24/48/96），筛选 = 名称模糊 + 标签（任意匹配）。
- 卡片字段：缩略图、名称、标签（超 3 个折叠 `+N`）、尺寸 `W x H`、大小、引用数。
- 编辑：只改 `name` + `tags[]`。
- 删除：有引用时拒绝，文案 `该素材仍被 N 处模板引用，不能删除`。
- 批量上传：单次最多 **100** 张，仅 JPG/JPEG，单张 ≤ 500KB，本次标签统一应用到全部图片。

### 2.9 市场分析

请求参数：`date_from`、`date_to`、`granularity`(`day`|`hour`)、`type`(任务模式)、
`sender_country_iso2`、`recipient_country_iso2`、`account_type`、`platform`、`use_short_link`。

窗口限制：**按日 ≤ 90 天，按小时 ≤ 7 天**（超出前端直接拦，不发请求）。

响应结构（`analysis-DA45fcKJ.js:1176-1205` 反推）：

```
items[] = {
  senderCountryIso2, recipientCountryIso2,
  summary: <指标对象>,
  series: [ { statTime, <指标对象> } ]
}
```

指标对象固定 12 个字段：`statTime`、`sendTotal`、`successNum`、`sendSuccessRate`、
`deliveredNum`、`deliveryRate`、`usedAccountCount`、`bannedAccountCount`、
`marketingBanRate`、`avgSendPerAccount`、`clickUvNum`、`updatedAt`。

**关键口径事实**：页面顶部 KPI 卡是把各国家对的 `summary` **逐行相加**得到的
（`used_account_count`、`banned_account_count`、`click_uv_num` 都在相加）。
也就是说**竞品的「使用号数」本身就是各国家对去重后相加的近似值，同一账号跨国家对会被重复计数**。

> 这条事实极大地简化了我们的实现：不需要为汇总口径做全局 `COUNT(DISTINCT)` 回源。
> 预聚合表按行给出、前端相加即与竞品一致。这是**有意保留的竞品口径**，写进接口注释，防止后人当 bug 修。

比率一律由前端现算：单钩率 `=单钩÷发送`、双钩率 `=双钩÷单钩`、点击率 `=点击UV÷单钩`、
封号率 `=封号÷使用号数`、号均 `=单钩÷使用号数`。落地率 `≈双钩率+20%` 是页面明示的经验估算。

### 2.10 账号筛选弹窗的完整字段

```
country_iso2s[] / exclude_country_iso2s[] / continent
group_ids[] / channel_ids[] / protocol_id
online_status / rotation_status / account_type / platform / wid_type / import_mode
group_invite_allowed / stranger_muted / account_status / is_exported
phone / import_no / error_code / error_desc
friend_count_min|max / retention_days_min|max / register_days_min|max
hyperlink_task_count_min|max          （"超链寿命"，条/账号）
created_at_from|to / logged_in_from|to
```

但**任务页的归一化函数只透传其中一个子集**（`task-0vbZUOmq.js:1512-1529`）：
`rotation_status` 与 `hyperlink_task_count_*` 不在其中，说明超链任务实际不使用这两项。
另外任务页提交时会**强制注入** `account_status:'normal'`、`is_exported:false`、`stranger_muted:false`
（对应弹窗底部的说明：账号状态固定"有效"、禁言固定"未禁言"、允许拉群固定"允许"）。

---

## 3. 本期与 armada 能力的对账

### 3.1 可直接复用

| 能力 | 位置 | 用途 |
|---|---|---|
| 协议无关发送命令 | `platform/protocol/model/command/MessageSendCommand`（`MessageType.LINK_CARD` / `BUTTON_CARD`）+ `WebMessageSendBackend` / `AndroidMessageSendBackend` | 超链消息发送，不新增协议接口 |
| 协议分流 | `ProtocolBackend.fromProtocolId(account.protocol_id)` → `WEB`/`ANDROID` | 设备类型筛选、按钮能力判定 |
| 轮次调度范式 | `marketing/scheduler/MarketingRoundScheduler` + `MarketingRoundWorker` + `MarketingTaskLifecycleWorker` | 周期/预发布模式的轮次派发与生命周期收敛 |
| 异步导出任务框架 | `marketing/export/*`（`marketing_export_job`，`V152`） | 本期 4 个导出接口 |
| 短码与公网入口范式 | `promotion/channel/support/ChannelCodeGenerator`（8 位无歧义字符）、`PromotionChannelPublicController`、`promotion_domain` | 深度追踪短链 |
| Kafka 协议事件消费骨架 | `platform/kafka/consumer/message/ProtocolMessageEventConsumer` | 接 `message.ack` |
| 图片存储 | `marketing_template_file` + `MarketingTemplateFileService`（JPEG + 500KB 校验） | 素材库字节层 |
| 国家主数据 | `admin.CountryController` / `country` / `country_phone_prefix_mapping` | 国家、大洲、号码国别识别 |

### 3.2 必须新建或扩展

| # | 缺口 | 处理 |
|---|---|---|
| 1 | `MessageSendCommand.MessageTarget` 只有 `groupJid` | 改为语义中立的 `MessageTarget(String jid)`；私聊目标 `<phone>@s.whatsapp.net`。**跨业务共享类，改动走全局评审**（§4.3-B） |
| 2 | `MessageCorrelation` 无超链分支 | 增 `HyperlinkCorrelation(taskId, recipientId, attemptId, roundNo)` |
| 3 | `AccountQuery` 只有 14 个维度 | 新增筛选服务 `HyperlinkAccountSelector`，不硬塞进 `AccountQuery`（§4.2） |
| 4 | `ProtocolMessageEventConsumer` 只处理 `message.send_result_reported` | 新增 `message.ack` 分支（`server_ack`/`delivery_ack`/`read`） |
| 5 | 账号占用模型是**分组级**（`account_group.marketing_occupancy_type`） | 超链按筛选圈号、跨分组，**不套用分组占用锁**（§4.4） |
| 6 | Android 协议只支持 1 个 `cta_url` 按钮；Web 缺 `cta_call` | **全部补齐**，四层门同步改（§4.3-A） |
| 7 | 三层发送链路都是群语义，无私聊路由 | Web 加"私聊跳过 sendability"，Android 新增 `preparePeerLinkSend`（§4.3-B） |
| 8 | 素材管理面（命名/标签/引用/批量上传） | 扩 `marketing_template_file` + 2 张标签表（§6.5） |

---

## 4. 业务语义设计

### 4.1 任务生命周期

两个正交字段，与竞品一致：

```
isEnabled   0=已停用（仅保存）  1=启用
runStatus   0=未开始  1=进行中  2=已完成  3=已暂停  4=已停止
```

状态迁移：

```
创建(isEnabled=0)                        → runStatus=0，不入队，dataPackageId 可空
创建(isEnabled=1)                        → 校验数据包 + 可用账号 → 领号 → runStatus=0，入队
                                            start_mode=scheduled 时 notBeforeAt = now + delay
action=start   runStatus 0 → 1
action=pause   runStatus 1 → 3           在途 attempt 跑完即止，不撤回已入队命令
action=resume  runStatus 3 → 1
action=stop    runStatus 1|3 → 4         终态，不可恢复；释放未发送 recipient
自动完成       runStatus 1 → 2           即时：号发完；预发布：到期或号发完（先到为准）；周期：只能手动停止
```

编辑限制（竞品原文）：`任务已开始/进行中/已完成/已暂停/已停止，仅可查看，不能修改`。
即 `isEnabled=1` 后即只读，`isEnabled=0` 的任务可自由编辑。
`message_type` 在编辑模式下一律不可改（前端禁用 + 后端拒绝）。

三种模式的执行差异：

| 模式 | 结束条件 | 账号范围刷新 | 轮次 |
|---|---|---|---|
| 1 即时 | 数据包领取的号发完 | 启动时定格一次 | 单轮 |
| 2 预发布 | `taskPlannedEndAt` 到期 **或** 号发完（先到为准） | 每轮重新匹配，新号自动加入 | 连续 |
| 3 周期 | 仅手动 `stop` | 每轮重新匹配 | 每 `taskIntervalMinutes` 一轮，每轮至多 `maxUseAccount` 个号 |

### 4.2 账号筛选：本期落地范围

新建 `HyperlinkAccountSelector`（超链域内的账号圈选服务），**不扩 `AccountQuery`**——
`AccountQuery` 服务的是账号菜单列表，塞入 12 个营销专用维度会把它变成大杂烩，
而且 `account` 是跨业务共享主表，随业务加筛选维度必然失控。选择器只依赖 `account`、
`account_state`、`account_group`、`country_phone_prefix_mapping` 现有列做下推 SQL。

| 筛选项 | 本期 | 数据来源 |
|---|---|---|
| 国家包含 / 排除 / 大洲 | ✅ | `account.ws_phone` 区号 → `country_phone_prefix_mapping` → `country.continent` |
| 分组 / 渠道 / 协议 | ✅ | `account.account_group_id` / `promotion_channel_id` / `protocol_id` |
| 在线状态 / 封号码 / 封号原因 | ✅ | `account_state.login_state` / `block_error_code` / `block_reason` |
| 账号类型（个人/商业） | ✅ | `account.account_type` |
| 设备类型（主设备/分身） | ✅ | 由 `protocol_id` 派生 `ProtocolBackend`，**不落 `wid_type` 列** |
| 账号性质（买量/自登） | ✅ | `account.number_source` |
| 手机号 / 批次号 | ✅ | `account.ws_phone` / `account_import_batch` |
| 入库时间 / 最近登录时间 | ✅ | `account.created_at` / `account_online_attempt_log` |
| 存活天数 | ✅ | `now - account.created_at` 派生，**不落列** |
| 导入方式（六段/全参） | ⚠️ | `account_import_batch.import_format`，需经 `account_import_detail` 关联；SQL 成本要实测 |
| 好友数 | ❌ 本期隐藏 | 需协议层主动查，两侧口径未统一（数据模型 §8.2） |
| 允许拉群 | ❌ 本期隐藏 | 同上，Android 侧能力待确认 |
| 注册天数 | ❌ 本期隐藏 | WhatsApp 不暴露，产品定义未澄清（数据模型 §8.3） |
| 轮换状态 / 超链寿命 | ❌ 不做 | 竞品任务页自己也不透传这两项（§2.10） |

固定注入（与竞品一致，不作为可选项暴露）：账号状态=有效、禁言=未禁言。

> **有意保留的竞品差异**：三个 ❌ 隐藏项。前端**不渲染控件**，而不是渲染一个永远无效的控件——
> 灰置控件比没有控件更让人误解。数据源就位后单独一期补齐。

`accountFilter` 以 JSON 存库，空对象 = 不限定（全部有效账号）。
**入库前必须按白名单归一化**：未知键丢弃、国家码大写、ID 去重、数值下界裁剪。
不能把前端 JSON 原样落库——那是后续任何筛选语义变更都无法收口的伏笔。

### 4.3 协议能力补齐（**本期硬前置，不做能力降级**）

**已定（用户决策）**：缺的能力全部补齐。Web 侧按 Baileys 接，Android 侧照搬 Web 逻辑。
不做"限制超链任务只能选 Web 协议账号"这类降级方案。

两块缺口互相独立，都必须补：**A 按钮能力**、**B 私聊目标**。

| 形态 | 协议命令 | 补齐后 Web | 补齐后 Android |
|---|---|---|---|
| 1 单图文 | `MessageType.LINK_CARD` | ✅ 已有 | ✅ 已有 |
| 3 普通按钮 | `MessageType.BUTTON_CARD`（无 thumbnail） | ✅ 加 `cta_call` | ✅ 改按钮数组 |
| 4 卡片按钮 | `MessageType.BUTTON_CARD`（带 thumbnail） | ✅ 加 `cta_call` | ✅ 改按钮数组 |

#### A. 按钮能力

现状里有**四层各自独立的门**，只改一层会变成"上层放行、下层拒绝"的静默失败：

| 层 | 现状 | 出处 |
|---|---|---|
| armada Android backend | `buttons().size() != 1` 拒绝；`!"link".equalsIgnoreCase(type)` 拒绝 | `AndroidMessageSendBackend.java:126,130` |
| armada Web backend | 纯透传，无按钮校验 | `WebMessageSendBackend.java:104-115` |
| Web 协议 | `ButtonCardButtonType = 'link' \| 'copy' \| 'quick'`，1~3 个；`nativeFlowButton()` 无 call 分支 | `card-content.ts:19,37,161-176` |
| Android 协议 | `HyperLinkMessage` 只有单按钮的 `ButtonText`+`Url`；`case "2"` 硬编码一个 `cta_url` | `entity/message.go:391-403`、`message_payload.go:123-152` |

**Web 侧改动（约 15 行）**

1. `ButtonCardButtonType` 加 `'call'`。
2. `nativeFlowButton()` 加分支：

```ts
if (btn.type === 'call') {
  const phone = btn.value ?? ''
  return {
    name: 'cta_call',
    buttonParamsJson: JSON.stringify({ display_text: displayText, phone_number: phone })
  }
}
```

3. `validateButtonCard()` 加 call 分支：`value` 必须是 E.164 格式（`+` 加 8~15 位数字）。
4. **Baileys 侧零改动**——`NativeFlowButton` 只有 `{name, buttonParamsJson}` 两个字段、
   无按钮名白名单，Baileys 是纯透传（`node_modules/baileys/WAProto/WAProto.proto:2734-2737`）。

> ⚠️ **`cta_call` 的 `buttonParamsJson` 字段名无法从仓库确证，必须真机验证。**
> proto 里的 `CallButton` / `HydratedCallButton` 用的是 `displayText` / `phoneNumber`
> （`WAWebProtobufsE2E.proto:1509,2232`），但那是**模板消息（HSM）机制，不是 native flow**。
> native flow 的 payload 一律 snake_case（现有 `cta_url` 用 `display_text` / `url` / `merchant_url`），
> 因此推断为 `display_text` / `phone_number`。
> **真机 A/B 通过前，前端不放开该按钮类型**——发出去不渲染的按钮比没有按钮更糟。
> 若真机验证发现还需要 `id` 字段，按验证结果补，不要凭猜测多塞字段。

**Android 侧改动（照搬 Web 语义）**

1. `entity.HyperLinkMessage` 增 `Buttons []HyperLinkButton{Type, DisplayText, Value}`。
   保留 `Url` / `ButtonText` 旧字段供存量 HTTP 调用方（`Template=1` 也在用 `Url`）：
   `Template=2` 优先读 `Buttons`，为空时回落旧字段。**不删旧字段**，否则现网 HTTP 调用方直接挂。
2. `BuildLinkGroupPayload` 的 `case "2"`：遍历 `Buttons` 生成 `[]NativeFlowButton`，
   JSON 字段名与 Web 的 `nativeFlowButton()` **逐字一致**。
3. `internal/armada/message_sender.go:562` 的 `PrepareButtonCard`：删掉
   `len(card.Buttons) != 1` 断言，透传全部按钮**及其 `Type`**（现在 `Type` 被直接丢弃）。
4. armada `AndroidMessageSendBackend.validateButtonCard`：放开数量与类型限制，
   改用与 Web 完全相同的一套校验。

> **两条协议必须共用同一份按钮约定**。JSON 字段名、数量上限、类型白名单三者只要有一处不一致，
> 同一个模板在两条协议上就会渲染出不同结果，而这种问题只有真机才能发现。
> 因此校验收口到 armada 侧一个 `HyperlinkButtonValidator`，两条协议内部的校验只做 wire 层兜底。
> 现在恰好是反例：类型门在 armada（Java）、数量门在 Go，两处独立演进。

#### B. 私聊目标

超链是**私聊**发送，而现有三层全是群语义：

| 层 | 现状 | 补齐方式 |
|---|---|---|
| armada | `MessageTarget(String groupJid)`，两个 backend 都写进 payload 的 `groupJid` | 改为语义中立的 `MessageTarget(String jid)`。**跨业务共享类，走全局评审** |
| Web 协议 | `sock.sendMessage(jid,…)` / `relayMessage(jid,…)` **本身 jid 无关，私聊直接可用**；但 `resolveGroupSendability()` 对每条消息都调 `groupMetadata(jid)` | 增加"私聊跳过"分支，照 `historical_group_pull` 已有的 `skippedGroupSendability()` 做法 |
| Android 协议 | `PrepareGroupLinkMessageContext(groupJid)` → `prepareGroupLinkSend()` 走群成员路由；非 `g.us` 分支走 `cachedGroupParticipants()`，对用户 JID 拿不到成员 | 新增 `preparePeerLinkSend(ctx, peerJid)`：`SendGetUserDevices(peerPhone)` + 自身设备，`AddressingMode=PN`，不查群成员、不跑 `runGroupTyping`（换私聊 chat state） |

Web 侧那个跳过不是优化，是必需项：不跳的代价是**每条超链消息多一次 `groupMetadata` 协议调用**，
在数十万条量级上既慢，又是白送的风控暴露——查一个不存在的群，这个请求本身就很可疑。

> **Android 的私聊路由是本期协议层工作量最大的一块，"照搬 Web"在这里省不掉。**
> Web 的私聊能力来自 Baileys 现成实现，Android 是自研协议栈，路由、设备解析、加密会话
> 都得自己走一遍私聊分支。这一项必须单独排期、单独真机回归。

### 4.4 发送调度与账号并发

沿用 `MarketingRoundScheduler` 的轮次范式，一轮的执行步骤：

```
1. 选号：HyperlinkAccountSelector 按 accountFilter 圈出候选，排除本任务已达
        accountMaxSendNum 的号、已封号、已达 concurrent 上限的号
2. 限号：即时/预发布——首轮定格至多 maxUseAccount 个（0=不限）
        周期——每轮至多 maxUseAccount 个
3. 领号：从任务已领取的 recipient 里取 sendStatus=待发送 的行
4. 配对：账号 × recipient，按 accountSendConcurrency 控制单账号在途数
5. 派发：写 hyperlink_delivery_attempt(status=发送中) → 入协议命令 outbox
        sendIntervalMs 在 [msgIntervalMinMs, msgIntervalMaxMs] 内随机取值
6. 收敛：MarketingTaskLifecycleWorker 同类 worker 判定完成/到期
```

**账号占用：本期不引入互斥锁。** 现有占用模型是分组级
（`account_group.marketing_occupancy_type`，见 `AccountMarketingOccupancyType`），
而超链按筛选条件跨分组圈号，两者粒度不兼容。硬套会出现"一个超链任务锁掉整个分组、
群营销全线停摆"的事故。

替代方案：单账号并发由**在途 attempt 计数**控制（`accountSendConcurrency`），
跨任务不做抢号互斥——这与竞品一致（竞品同样是按筛选圈号，多任务可共用同一账号）。

> **登记的风险**：多个超链任务同时跑同一批号时，单号实际并发是各任务之和，
> 可能超出单任务的 `accountSendConcurrency`。竞品也有这个问题。
> 若实测出号损，再引入账号级令牌桶（Redis，复用协议层 `operation-gate` 范式），
> 不要退回分组锁。

### 4.5 单钩 / 双钩 / 失败回流

```
协议 message.ack ──┐
                   ├─→ ProtocolMessageEventConsumer（新增 ack 分支）
协议 send_result ──┘         │
                             ├─→ hyperlink_delivery_attempt   按 (tenantId, accountId, protocolId, protocolMessageId) 幂等更新
                             ├─→ hyperlink_task_recipient     聚合状态取"最优"推进，不回退
                             ├─→ hyperlink_task_stat          计数增量
                             └─→ data_package_phone.pool_status + data_package_stat   可靠事件幂等更新
```

映射：`server_ack` → 单钩、`delivery_ack` → 双钩、`read` → 已读。
协议明确返回未注册时才置 `未开通WS`，其余失败置 `发送失败`（一期已冻结：不做批量预探测）。

三条硬规则：

1. **状态只前进不后退**。ack 乱序到达是常态，`sendStatus` 用 `CASE` 比较后再写，不做无条件覆盖。
2. **不在高频回执里锁 `data_package` 主行**。只更新 `data_package_phone` 行与 `data_package_stat` 单行（一期已冻结）。
3. **失败原因落库前按列宽截断**，且日志不打完整手机号与推广链接参数。

### 4.6 深度追踪

按 §2.3，短码归属于「recipient × 开启了 `useShortLink` 的 `cta_url` 按钮」。
由于按钮数量上限是 1，**每个 recipient 至多一个短码**，因此：

- `hyperlink_task_recipient.short_code` 单列即可，不需要独立的短链表。
- 发送时把该按钮的 `url` 替换为 `https://{域名}/hl/{shortCode}`。
- 公网 `GET /api/public/hl/{shortCode}` 记录 `hyperlink_click` 后 302 跳原始 URL。
- 单图文任务不生成短码，点击类指标真实为 0（与竞品一致）。

> **约束写进注释**：单列 `short_code` 的前提是"按钮上限 1"。若将来放开多按钮，
> 必须新建 `hyperlink_recipient_short_link(recipientId, buttonIndex, shortCode)`，
> 不允许把多个短码拼进一个字符串列。

点击 UV 从 `hyperlink_task_recipient` 用 `COUNT(*) WHERE click_count > 0` 算，
**不从 `hyperlink_click` 做 `COUNT(DISTINCT)`**（数据模型 §4.5 已冻结）。

公网写入路径无租户上下文，Mapper 用 `@InterceptorIgnore(tenantLine = "true")` 并显式带
`tenant_id`，与 `PromotionPairingSessionMapper` 现有做法一致。

### 4.7 封号归因

`hyperlink_task_ban` 一行 = 本任务期间一个账号的封号事实，唯一键
`(tenantId, hyperlinkTaskId, accountId)` 保证 `bannedCount` 天然去重。

触发时机：消费账号状态变更事件时，若该账号在**任一进行中的超链任务**里有过 attempt，
就为这些任务各记一行。原因码取 `account_state.block_error_code`。

`ban-stats` 接口返回按原因码分组的占比。竞品的原因文案（可直接复用）：

```
中途禁言，马上封号 / 中途强制被掐掉，封号 / 从主设备登录出，被强制下线 / 主设备直接掉了/封了 / 未知原因
```

> **为什么不直接查 `account_state`**：它只有当前状态、没有历史。账号解封或被别的任务再封，
> 本任务的封号事实就丢了（数据模型 §4.8 已论证）。

### 4.8 市场分析的读取路径

| 场景 | 数据源 |
|---|---|
| `granularity=day`（≤90 天） | `hyperlink_stat_daily` 预聚合表，按筛选维度过滤后分组 |
| `granularity=hour`（≤7 天） | 实时聚合 `hyperlink_delivery_attempt`（含冗余的收件人国家快照）+ `hyperlink_task_ban` |
| `marketing-stats/countries` | 区间内出现过的发信国 / 被营销国去重清单，供筛选下拉 |
| `marketing-stats/accounts` | 按账号维度聚合 attempt，任务详情「发信账号统计」Tab 与全局账号统计共用 |

日聚合任务：每小时回填**昨天与今天**两天（跨天与迟到 ack 都能被修正），
按唯一键 `ON DUPLICATE KEY UPDATE` 幂等。

小时粒度不预聚合的理由（数据模型 §7.2 已算过）：维度组合 × 24 × 90 天 ≈ 6480 万行，不可接受。
7 天窗口的实时聚合走 `idx_hyperlink_attempt_stat` 可控。

---

## 5. 数据模型

### 5.1 表清单与 Flyway 编排

| 版本 | 内容 |
|---|---|
| `V156` | `hyperlink_strategy` |
| `V157` | `hyperlink_task`、`hyperlink_task_content`、`hyperlink_task_stat` |
| `V158` | `hyperlink_task_recipient`、`hyperlink_delivery_attempt` |
| `V159` | `hyperlink_task_ban` |
| `V160` | `hyperlink_click` |
| `V161` | `hyperlink_stat_daily` |
| `V162` | `marketing_template_file` 素材管理列 + `resource_asset_tag` + `resource_asset_tag_ref` |
| `V163` | 四个菜单 + RBAC 权限 |

版本号实施前**重新扫描全局最高版本**再定（一期就踩过 `V117` 撞号）。
`ADD COLUMN` 一律用 `information_schema` 守卫保证幂等。

### 5.2 对现有数据模型文档的修订

`docs/business/hyperlink-marketing-data-model.md` 的任务族设计写在超链任务还没做的时候，
本期用静态前端事实校正 **7 处**：

| # | 原设计 | 修订 | 依据 |
|---|---|---|---|
| 1 | `status TINYINT` 单字段 7 态 | 拆为 `is_enabled TINYINT(1)` + `run_status TINYINT` | §2.5，竞品就是两个字段；合成一个字段会丢掉"已停用但曾经跑过"的信息 |
| 2 | `msg_interval_min_sec/max_sec INT` | `msg_interval_min_ms/max_ms INT` | §2.4 需要 0.1 秒精度，INT 秒存不下；`marketing_task.account_group_send_interval_ms` 已是毫秒先例 |
| 3 | `is_short_link_enabled` 为任务级开关 | 改为**派生冗余列**，事实源是 `hyperlink_task_content.buttons[].useShortLink` | §2.3 深度追踪是按钮级 |
| 4 | `hyperlink_strategy` 含 `msg_interval_*`、`account_send_concurrency` | **删除这三列** | §2.7 策略页无对应控件，落列即死列（规范一.4） |
| 5 | 未提及 `default_sub_task_num` | **不落列** | 前端硬编码 50，无 UI 控件，是竞品遗留字段 |
| 6 | `hyperlink_delivery_attempt` 无收件人国家 | 增 `recipient_country_iso2_snapshot` | §4.8 小时粒度实时聚合免 join recipient |
| 7 | `hyperlink_stat_daily` 无协议维度 | 增 `protocol_backend TINYINT` | §2.9 分析页有「设备平台」筛选 |

另外补充：任务表需要 `current_round_no` / `next_round_at` / `last_round_started_at` 三列支撑周期调度
（命名沿用 `marketing_task`）。

### 5.3 `hyperlink_strategy`（V156）

```sql
CREATE TABLE IF NOT EXISTS hyperlink_strategy (
    id                     BIGINT NOT NULL AUTO_INCREMENT COMMENT '超链策略主键',
    tenant_id              BIGINT NOT NULL COMMENT '租户ID',
    strategy_name          VARCHAR(128) NOT NULL COMMENT '策略名称;仅后台展示便于识别',
    task_type              TINYINT NOT NULL COMMENT '任务模式:1即时 2预发布 3周期',
    task_interval_minutes  INT NOT NULL DEFAULT 0 COMMENT '周期轮次间隔(分钟);仅周期模式有效,下限30',
    account_filter         JSON DEFAULT NULL COMMENT '账号筛选条件白名单归一化后JSON;NULL或{}=不限定',
    concurrent_num         INT NOT NULL DEFAULT 10 COMMENT '最大执行账号数;0=按号数均分',
    max_use_account        INT NOT NULL DEFAULT 0 COMMENT '最大使用账号数;0=不限号数;周期模式为每轮上限且必须>=1',
    account_max_send_num   INT NOT NULL DEFAULT 0 COMMENT '每账号最大发送条数;0=打死或封号为止',
    is_enabled             TINYINT(1) NOT NULL DEFAULT 1 COMMENT '0=停用(不出现在新建任务选项) 1=启用',
    remark                 VARCHAR(255) DEFAULT NULL COMMENT '备注',
    version                INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by             BIGINT DEFAULT NULL COMMENT '创建人user_id',
    created_at             BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at             BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    deleted_at             BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);NULL=未删',
    is_active              TINYINT GENERATED ALWAYS AS (
                               CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END
                           ) STORED COMMENT '软删唯一键辅助;有效行为1,已删行为NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uq_hyperlink_strategy_name (tenant_id, strategy_name, is_active),
    KEY idx_hyperlink_strategy_enabled (tenant_id, is_enabled, deleted_at, id),
    KEY idx_hyperlink_strategy_created (tenant_id, created_at, id),
    CONSTRAINT ck_hyperlink_strategy_type CHECK (task_type IN (1, 2, 3)),
    CONSTRAINT ck_hyperlink_strategy_counts CHECK (
        task_interval_minutes >= 0 AND concurrent_num >= 0
        AND max_use_account >= 0 AND account_max_send_num >= 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链营销发送策略预设';
```

策略与任务是**弱引用**：引用即复制参数进任务，改策略不影响在跑任务，因此不维护引用计数、
删除不做保护（与竞品的"确认删除策略「X」？此操作不可恢复"一致）。

### 5.4 `hyperlink_task`（V157，26 列）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` / `tenant_id` | `BIGINT` | 主键 / 租户 |
| `task_name` | `VARCHAR(128) NOT NULL` | 任务名称 |
| `task_type` | `TINYINT NOT NULL` | 1 即时 2 预发布 3 周期 |
| `is_enabled` | `TINYINT(1) NOT NULL DEFAULT 0` | 0 已停用（仅保存） 1 启用 |
| `run_status` | `TINYINT NOT NULL DEFAULT 0` | 0 未开始 1 进行中 2 已完成 3 已暂停 4 已停止 |
| `start_mode` | `TINYINT NOT NULL DEFAULT 1` | 1 立即执行 2 延后执行 |
| `task_delay_minutes` | `INT NOT NULL DEFAULT 0` | 延后分钟；`start_mode=1` 时恒 0 |
| `task_planned_end_at` | `BIGINT` | 计划结束（epoch 毫秒）；仅预发布必填 |
| `task_interval_minutes` | `INT NOT NULL DEFAULT 0` | 周期间隔；仅周期必填，≥1 |
| `data_package_id` | `BIGINT` | 受众包；`is_enabled=0` 时可空 |
| `data_package_generation` | `INT` | 领取时冻结的包代次 |
| `data_package_name_snapshot` | `VARCHAR(128)` | 包名快照 |
| `source_template_id` / `source_template_version` | `BIGINT` / `INT` | 内容来源模板（弱引用） |
| `hyperlink_strategy_id` | `BIGINT` | 引用的策略（弱引用） |
| `account_filter` | `JSON` | 归一化后的筛选条件 |
| `concurrent_num` | `INT NOT NULL DEFAULT 10` | 最大执行账号数 |
| `max_use_account` | `INT NOT NULL DEFAULT 0` | 最大使用账号数；0 不限 |
| `account_max_send_num` | `INT NOT NULL DEFAULT 0` | 每号最大发送；0 不限 |
| `account_send_concurrency` | `INT NOT NULL DEFAULT 20` | 单账号并发，1~100 |
| `msg_interval_min_ms` / `msg_interval_max_ms` | `INT NOT NULL` | 消息间隔毫秒，`max ≥ min`，0~10000 |
| `is_short_link_enabled` | `TINYINT(1) NOT NULL DEFAULT 0` | 派生冗余：内容里存在开启深度追踪的按钮 |
| `current_round_no` / `next_round_at` / `last_round_started_at` | `BIGINT` | 轮次调度 |
| `started_at` / `last_send_at` / `finished_at` | `BIGINT` | 生命周期时间点 |
| `remark` | `VARCHAR(512)` | 备注 |
| `version` | `INT NOT NULL DEFAULT 1` | 乐观锁 |
| `created_by` / `created_at` / `updated_at` / `deleted_at` / `is_active` | — | 同其他表约定 |

索引：

```
UNIQUE uq_hyperlink_task_name    (tenant_id, task_name, is_active)
KEY idx_hyperlink_task_tenant    (tenant_id, deleted_at, id)
KEY idx_hyperlink_task_status    (tenant_id, is_enabled, run_status, last_send_at)
KEY idx_hyperlink_task_due       (tenant_id, run_status, task_planned_end_at, id)
KEY idx_hyperlink_task_round     (tenant_id, run_status, next_round_at, id)
KEY idx_hyperlink_task_package   (tenant_id, data_package_id)
```

> 任务名唯一键是**本期新增的约束**，竞品未确证有此约束。理由：`hyperlink_template` /
> `data_package` 都做了同租户名称唯一，任务不做会在运维排障时出现同名任务无法区分。
> 如果评审认为运营需要同名任务（例如按天重复创建），删掉这个唯一键即可，其余设计不变。

### 5.5 `hyperlink_task_content`（V157，1:1，主键即任务 ID）

字段与 `hyperlink_template` 的内容字段**逐一对齐**（`message_schema_version`、`message_type`、
`title`、`content`、`link_description`、`promotion_link`、`buttons` JSON、`card_text`、
`link_preview_asset_id`、`body_main_asset_id`），复用同一个 `HyperlinkMessageContent` DTO 与校验器。

`buttons` JSON 元素结构（沿用一期 §5.2 的版本化数组，本期补 `useShortLink`）：

```json
{ "type": "CTA_URL", "displayText": "🔥 立即抢购", "url": "https://…", "useShortLink": true }
```

拆表理由（数据模型 §4.1 已论证）：列表分页不必带长文本与 JSON；ack 高频回写不与配置行争锁。

### 5.6 `hyperlink_task_stat`（V157，1:1）

`recipient_total`、`send_total`、`success_num`、`delivered_num`、`read_num`、`fail_num`、
`fail_404_num`、`banned_count`、`click_uv_num`、`click_total`、`used_account_count`、
`execution_duration_sec`、`updated_at`。

**比率一律不落列**——分子分母异步回流，落列必然出现自相矛盾的快照。

### 5.7 `hyperlink_task_recipient`（V158）

沿用数据模型 §4.5，`send_status` 值域按竞品对齐为
`1 待发送 2 发送中 3 单钩 4 双钩 5 已读 6 发送失败 7 未开通WS 8 已跳过`。

索引：

```
UNIQUE uq_hyperlink_recipient            (tenant_id, hyperlink_task_id, recipient_phone_snapshot)
UNIQUE uq_hyperlink_recipient_short_code (short_code)          -- NULL 不参与
KEY    idx_hyperlink_recipient_task      (tenant_id, hyperlink_task_id, send_status, id)
KEY    idx_hyperlink_recipient_source    (tenant_id, data_package_id, data_package_generation, id)
KEY    idx_hyperlink_recipient_click     (tenant_id, hyperlink_task_id, click_count)
KEY    idx_hyperlink_recipient_country   (tenant_id, hyperlink_task_id, recipient_country_iso2_snapshot)
```

### 5.8 `hyperlink_delivery_attempt`（V158）

沿用数据模型 §4.6，**新增 `recipient_country_iso2_snapshot CHAR(2)`**（§5.2 修订 6）。

索引：

```
UNIQUE uq_hyperlink_attempt          (tenant_id, recipient_id, attempt_no, message_part_no)
UNIQUE uq_hyperlink_attempt_ack      (tenant_id, account_id, protocol_id, protocol_message_id)
KEY    idx_hyperlink_attempt_task    (tenant_id, hyperlink_task_id, status, id)
KEY    idx_hyperlink_attempt_stat    (tenant_id, sent_at, sender_country_iso2_snapshot,
                                      recipient_country_iso2_snapshot)
KEY    idx_hyperlink_attempt_account (tenant_id, hyperlink_task_id, account_id, id)
```

`idx_hyperlink_attempt_stat` 是小时粒度实时聚合的唯一支撑，不能省。
`idx_hyperlink_attempt_account` 支撑「发信账号统计」Tab。

### 5.9 `hyperlink_task_ban`（V159）/ `hyperlink_click`（V160）

均沿用数据模型 §4.8 / §4.7，不做改动。

`hyperlink_click` 是本模块唯一线性膨胀的表，**实施时必须同时定归档策略**：
按 `visit_at` 保留 N 天，超期分批删除（每批 ≤2000 行、独立提交，沿用一期旧代号码清理的做法）。
保留期是待决项（§11-4）；**没有保留期就不上这张表**。

### 5.10 `hyperlink_stat_daily`（V161）

沿用数据模型 §7.1，**新增 `protocol_backend TINYINT NOT NULL`**（1 WEB / 2 ANDROID）。

唯一键：`(tenant_id, stat_date, sender_country_iso2, recipient_country_iso2, account_type,
task_type, is_short_link_enabled, protocol_backend)`。

未知国家落 `ZZ`。维度基数估算（加了协议维度后）：

```
发信国(~50) × 被营销国(~50) × 账号类型(2) × 任务模式(3) × 深度追踪(2) × 协议(2) ≈ 6 万组合
理论上限 6 万行/天 × 90 天 ≈ 540 万行
实际国家对高度稀疏（一个租户通常只跑 3~10 个国家对），真实量级低两个数量级
```

`used_account_count` / `banned_account_count` / `click_uv_num` 是**行内去重、跨行相加**的口径，
与竞品一致（§2.9）。这一点必须写进列注释与接口注释。

### 5.11 素材管理列（V162）

**不新建 `resource_asset` 表**，给 `marketing_template_file` 加列（幂等守卫）：

| 新列 | 类型 | 说明 |
|---|---|---|
| `asset_name` | `VARCHAR(128)` | 素材名称；存量行用 `original_filename` 回填 |
| `width` / `height` | `INT` | 像素尺寸；解析失败为 NULL |
| `created_by` | `BIGINT` | 上传人 user_id；存量行 NULL |
| `updated_at` | `BIGINT` | epoch 毫秒；存量行取 `created_at` 回填 |

新增索引 `idx_marketing_template_file_name (tenant_id, deleted_at, asset_name)`。

标签是多对多，必须独立成表（JSON 列做不了「按标签任意匹配」的索引化反查）：

```
resource_asset_tag      (id, tenant_id, tag_name, created_at)
                        UNIQUE (tenant_id, tag_name)
resource_asset_tag_ref  (id, tenant_id, file_id, tag_id, created_at)
                        UNIQUE (tenant_id, file_id, tag_id)
                        KEY    (tenant_id, tag_id, file_id)
```

**为什么不建第二张素材表**：`marketing_template_file` 已经是素材的事实源（字节 + 租户 +
原名 + 类型 + 大小）。再建一张 `resource_asset` 做"管理面"，就有两行描述同一个素材，
正是数据模型规范一.2 禁止的分歧。加列 + 标签表既满足页面需求，又只有一个事实源。

代价与登记项：

1. `marketing_template_file` 是**群组营销在用的共享表**，加列必须走全局评审（规范五）。
   新列全部可空、全部不改变现有读写路径，评审风险可控。
2. 物理表名与新的 `resource-assets` 资源名不一致。**改名不在本期做**——
   滚动发布中旧 Mapper 会直接报表不存在。改名登记为独立技术债，先上兼容 Service。
3. `content` 是 MySQL 里的 `MEDIUMBLOB`。素材库支持批量上传（单次 100 张 × 500KB = 50MB/次）后，
   主库体积会随素材量线性增长。**本期沿用现状，不引入对象存储**，但这条技术债必须登记在案。
4. `ref_count` **不落列**——引用方是模板与任务两张表，实时 `COUNT` 即可；
   落冗余列必然出现与真实引用不一致的时刻，而删除保护恰恰不能容忍这种不一致。

---

## 6. 接口方案

资源划分、查询语义、分页与聚合口径照抄 hylb；路径前缀与字段命名服从 armada 约定。

### 6.1 超链任务

```
GET    /api/hyperlink-tasks                          列表
GET    /api/hyperlink-tasks/{id}                     详情
POST   /api/hyperlink-tasks                          新建（multipart/form-data）
PUT    /api/hyperlink-tasks/{id}                     编辑（multipart/form-data）
POST   /api/hyperlink-tasks/{id}/action              {action: START|PAUSE|RESUME|STOP}
DELETE /api/hyperlink-tasks/{id}                     删除（仅 isEnabled=0 或终态）
GET    /api/hyperlink-tasks/{id}/recipients          收信人流水
GET    /api/hyperlink-tasks/{id}/account-stats       发信账号维度统计
GET    /api/hyperlink-tasks/{id}/clicks              点击明细（深度归因）
GET    /api/hyperlink-tasks/{id}/visit-trend         访问趋势
GET    /api/hyperlink-tasks/{id}/ban-stats           封号原因分布
POST   /api/hyperlink-tasks/{id}/recipients/export         异步导出
POST   /api/hyperlink-tasks/{id}/visit-trend/export        异步导出
POST   /api/hyperlink-tasks/{id}/click-attribution/export  异步导出
GET    /api/hyperlink-tasks/available-account-count  按 accountFilter 试算可用账号数
```

列表查询参数：`page`、`pageSize`、`taskName`、`isEnabled`、`runStatus`、`taskType`、
`recipientCountryIso2`、`createdFrom`、`createdTo`。

`multipart` 两个二进制字段 `linkPreviewImage` / `bodyMainImage`（可选，未传则用
`linkPreviewAssetId` / `bodyMainAssetId` 引用素材库）；`accountFilter` 与 `buttons` 以 JSON 字符串传输。

> **与竞品的接口差异（有意）**：
> 1. 导出改为 `POST` + 异步任务，复用 `marketing_export_job` 框架。竞品的同步
>    `GET .../export` 在数十万行收信人上必然超时，同步导出是**不可复刻的错误设计**。
> 2. 新增 `available-account-count`。竞品在新建抽屉里实时显示"N 个可用"，
>    走的是账号列表接口的 `total`；armada 的账号筛选维度与之不同，必须有专用试算端点。

`available-account-count` 同时返回协议台数上限，供前端做 `concurrent_num ≤ 协议台数 × 15` 校验：

```json
{ "code": 0, "data": { "availableAccountCount": 1832, "protocolCount": 24, "maxConcurrentNum": 360 } }
```

### 6.2 超链策略

```
GET    /api/hyperlink-strategies            列表（page/pageSize/strategyName/isEnabled/taskType）
GET    /api/hyperlink-strategies/{id}       详情
POST   /api/hyperlink-strategies            新建
PUT    /api/hyperlink-strategies/{id}       编辑（带 version）
DELETE /api/hyperlink-strategies/{id}       删除
GET    /api/hyperlink-strategies/options    新建任务的"引用策略"下拉（仅 isEnabled=1）
```

竞品没有独立的详情与 options 端点（列表里带全量、前端自己过滤）。
armada 加这两个端点，避免新建任务抽屉为了一个下拉去拉全量策略列表。

### 6.3 图片素材

```
GET    /api/resource-assets                 列表（page/pageSize/assetName/tags[]）
POST   /api/resource-assets                 上传（multipart：file + tags JSON），单次单文件
PUT    /api/resource-assets/{id}             改名 + 改标签
DELETE /api/resource-assets/{id}             删除（有引用返回 40901）
GET    /api/resource-assets/tags             全部标签候选
GET    /api/resource-assets/{id}/content     取字节（沿用现有 MarketingTemplateFileController 能力）
```

批量上传由前端**并发调用单文件上传**实现（竞品也是逐文件上传、逐文件显示进度与重试），
后端不做批量接口——批量接口的部分失败语义比逐文件重试复杂得多，收益为零。

列表项：`id`、`assetName`、`contentUrl`、`tags[]`、`sizeBytes`、`width`、`height`、
`referenceCount`、`createdBy`、`createdAt`。

### 6.4 超链市场分析

```
GET /api/hyperlink-tasks/marketing-stats             主查询
GET /api/hyperlink-tasks/marketing-stats/countries    筛选下拉的国家清单
GET /api/hyperlink-tasks/marketing-stats/accounts     账号维度统计
POST /api/hyperlink-tasks/marketing-stats/accounts/export
```

`marketing-stats` 参数（camelCase 化）：`dateFrom`、`dateTo`、`granularity`、`taskType`、
`senderCountryIso2`、`recipientCountryIso2`、`accountType`、`protocolBackend`、`isShortLinkEnabled`。

窗口校验后端也要做一遍：`day ≤ 90 天`、`hour ≤ 7 天`，超出返回 `40001`。
前端拦截不能替代后端校验——直接调接口就能绕过。

响应即 §2.9 的 `items[]` 结构（`granularity` + `items`），字段 camelCase。

### 6.5 公网点击入口

```
GET /api/public/hl/{shortCode}     记录 hyperlink_click → 302 跳转原始 URL
```

仿 `PromotionChannelPublicController`：无租户上下文、无鉴权、短码无效时返回 404 页而不是 500。

### 6.6 错误码

沿用一期 §9：`40001 VALIDATION` / `40401 NOT_FOUND` / `40901 CONFLICT`，HTTP 200 + 非零业务码。
本期新增场景：

| 场景 | 错误 | 提示 |
|---|---|---|
| 启用任务未选数据包 | `40001` | 启用任务时必须选择「受众数据包」 |
| 即时任务可用账号为 0 | `40001` | 即时任务需要至少 1 个可用账号；如需 0 个也能启用，请切换为「预发布」模式 |
| 数据包可用号码为 0 | `40001` | 该数据包已无未使用号码 |
| 按钮类型非法或数量超限 | `40001` | 按钮类型不支持 / 按钮数量超出上限 |
| `cta_call` 真机验证未通过前提交 | `40001` | 电话按钮暂未开放（§4.3-A 的临时开关，验证通过即移除） |
| 提交双图文 | `40001` | 不支持双图文 |
| 已启用任务被编辑 | `40901` | 任务已启用，仅可查看 |
| action 与当前状态不匹配 | `40901` | 任务当前状态不支持该操作 |
| 素材有引用时删除 | `40901` | 该素材仍被 N 处引用，不能删除 |
| 分析窗口超限 | `40001` | 日粒度最多 90 天 / 小时粒度最多 7 天 |

---

## 7. 后端落位

沿用一期已落地的 `com.armada.hyperlink.<子域>` 结构（`data` / `template` 已存在）：

```text
com/armada/hyperlink/
  strategy/     controller | service(+impl) | mapper | converter | model{entity,dto,vo}
  asset/        controller | service(+impl) | mapper | converter | model
  task/
    controller/   HyperlinkTaskController
    service/      HyperlinkTaskService            任务 CRUD 与 action
                  HyperlinkTaskLaunchService      启用：领号 + 建 recipient
                  HyperlinkAccountSelector        账号圈选（下推 SQL）
                  HyperlinkSendDispatchService    轮次派发
                  HyperlinkAckProjectionService   ack/结果回流投影
                  HyperlinkShortLinkService       短码生成与解析
    scheduler/    HyperlinkTaskRoundScheduler / HyperlinkTaskLifecycleWorker
    mapper/ converter/ model/
  click/        HyperlinkClickPublicController | HyperlinkClickService | mapper
  analysis/     controller | service(+impl) | mapper（含日聚合回填 scheduler）
```

实现规则（一期 §10 的延续）：

- 严格 `Controller → Service → Mapper`，不引入 Repository；跨业务域只调对方 Service。
- 分页、筛选、聚合全部下推 SQL，**禁止内存分页**。
- 领号、建 recipient、更新池状态在**同一事务**内完成；ack 回流用独立短事务。
- `accountFilter` 与 `buttons` 的序列化、反序列化、白名单归一化集中在 converter/validator。
- 所有关联 ID（数据包、模板、策略、素材）按当前租户重新查询，不信任前端。
- 日志只记 ID 与计数，**不记完整手机号、推广链接参数、短码明文**。
- 素材字节读写继续走 `MarketingTemplateFileService`，超链域不直接碰 BLOB。

---

## 8. 前端落位

控端 `wheel-saas-pure-web`，Element Plus 同构复刻（语义与行为精准，非 DOM 像素一致）。

```text
src/api/
  hyperlink-task.ts | hyperlink-strategy.ts | resource-asset.ts | hyperlink-analysis.ts

src/views/hyperlink/
  task/
    index.vue
    components/  HyperlinkTaskEditDrawer.vue      左预览 / 右分段表单
                 HyperlinkTaskConfirmStep.vue     提交前"最后核对"
                 HyperlinkAccountFilterDialog.vue 账号筛选弹窗（可与策略页共用）
                 HyperlinkTaskDetailDrawer.vue    5 个 Tab
    composables/ useHyperlinkTaskPage.ts | useHyperlinkTaskForm.ts
                 useHyperlinkTaskValidation.ts | useAvailableAccountCount.ts
  strategy/  index.vue + HyperlinkStrategyDialog.vue
  library/   index.vue + ResourceAssetUploadDialog.vue + ResourceAssetEditDialog.vue
  analysis/  index.vue + MarketingStatsTable.vue + MarketingStatsChart.vue
components/hyperlink/
  HyperlinkMessagePreview.vue   一期已有，本期复用（任务与模板共用）
  HyperlinkButtonEditor.vue     一期已有，本期增 useShortLink 开关
```

页面要点：

**超链任务列表** — 汇总卡（任务数/发送总数/单钩/双钩/点击UV/点击率，基于当前页前端汇总，
按任务名搜索时分页自动放大到 200，与竞品一致）+ 表格 + 行操作（启动/暂停/恢复/停止/编辑/查看/详情/复制）。
数据 1 分钟自动刷新一次。

**新建/编辑抽屉** — 左侧 WhatsApp 实时预览、右侧四段表单（基础信息 / 消息内容 / 发送策略 / 受众与发布），
提交前弹「最后核对」二次确认（含 N 秒阅读倒计时）。计费相关字段（余额/单价/预计冻结/估算落地率）**整块不做**。

**任务详情** — 5 个 Tab：收信人流水统计 / 发信账号维度统计 / 深度归因 / 访问趋势 / 封号原因分布。
未开启深度追踪的任务，点击类 Tab 显示「该任务未开启深度追踪，无点击 UV 数据」而不是空表。

**策略页** — 列表 + 编辑弹窗，字段只有 §2.7 的 6 个参数。

**素材库** — 网格 + 名称/标签筛选 + 批量上传（前端并发单文件上传、逐文件进度与重试）+ 编辑 + 删除保护。

**市场分析** — 筛选栏（粒度 / 时间范围 / 快捷区间 / 账号性质 / 设备平台 / 深度追踪 / 营销国 / 被营销国）
+ 8 张 KPI 卡 + 表格/图表切换（表格按国家对分组、可展开看每日或每小时明细）。

控端红线：`.vue` 单文件 ≤ 600 行（超 400 行拆 composable 或子组件）、页面禁止直接 axios、
禁止自绘表格与弹窗、菜单以 `/api/tenant/me/menus` 为最终来源。

任务编辑抽屉是本模块最大的组件，必须从一开始就按上面的拆法写——先写成一个大文件再拆，
一定会拆不干净。

---

## 9. 菜单与权限（V163）

```text
超链营销            /hyperlink
  超链数据包        /hyperlink/data          （一期已有）
  超链营销模板      /hyperlink/templates     （一期已有）
  超链任务          /hyperlink/task
  超链策略          /hyperlink/strategy
  图片素材          /hyperlink/library
  超链市场分析      /hyperlink/analysis
```

组件路径由 `/api/tenant/me/menus` 返回（`hyperlink/task/index` 等），不依赖前端 mock 路由。
菜单顺序按竞品：任务 → 数据包 → 模板 → 策略 → 素材 → 市场分析。

权限（沿用 `tenant:<module>:<action>`）：

```
tenant:hyperlink_task:view|create|edit|copy|delete|action|export
tenant:hyperlink_strategy:view|create|edit|delete
tenant:resource_asset:view|upload|edit|delete
tenant:hyperlink_analysis:view|export
```

要点：

- 后端用 `@PreAuthorize`，前端按钮权限只管交互展示。
- `action`（启动/暂停/停止）与 `edit` **分开**：运营可以启停但不一定能改配置。
- 收信人导出含完整手机号，`export` 独立成权限点并记操作审计，不复用 `view`。
- 素材的 `content` 读取权限扩展现有方法级表达式，**不删除任何现有读取权限**
  （群组营销模板仍在用）。

---

## 10. 分期与验证

| 期 | 内容 | 依赖 | 交付判定 |
|---|---|---|---|
| **P0** | **协议能力补齐**（§4.3）：Web 加 `cta_call` + 私聊跳过 sendability；Android 改按钮数组 + 新增私聊路由；armada 三处门统一 | `MessageTarget` 全局评审通过 | 真机验证：四种按钮在两条协议上渲染一致；私聊单图文与按钮消息能真实发到手机 |
| P1 | 超链策略（表 + API + 页面） | 无 | 策略可增删改查，`options` 能被任务页消费 |
| P2 | 图片素材（加列 + 标签表 + API + 页面 + 存量回填） | 无 | 素材可上传/命名/打标签/删除保护；模板页图片字段切到素材库选择 |
| P3 | 超链任务（表 + 创建/编辑/action + 领号 + 发送链路） | **P0**、P1、P2 | 单图文与按钮任务能真实发出并落 attempt |
| P4 | 回流（`message.ack` 消费 + 收信人/账号/封号 Tab） | P3 | 单钩双钩计数与号码池状态最终一致 |
| P5 | 深度追踪（短码 + 公网跳转 + 点击明细 + 访问趋势 + 深度归因导出） | P3、域名归属确定 | 点击 UV 与点击率可核对 |
| P6 | 市场分析（日聚合表 + 回填任务 + 3 个统计接口 + 页面） | P4、P5 | 日/小时两种粒度口径与任务明细可对账 |

每期开工前各自出一份实施计划（一期已确立的做法），逐期评审、逐期落地。

测试要求（沿用 AGENTS.md 与一期 §15）：

- **Flyway 迁移 SQL 测试**：每个新表/新列一个 `*MigrationSqlTest`，H2 内存库执行迁移并断言列、索引、CHECK。
- **Mapper H2 测试**：分页、筛选、聚合、幂等 upsert、唯一键冲突各一条；
  重点覆盖 ack 乱序（先 `delivery_ack` 后 `server_ack`）不回退状态。
- **Service 单测**：状态机全迁移矩阵、并发校验三条规则的边界值、
  `accountFilter` 白名单归一化（未知键、脏国家码、负数）、按钮协议准入。
- **契约测试**：每个 Controller 一个 `*ContractTest`，锁定路径、字段名、错误码。
- **聚合口径对账测试**：同一批 attempt 数据，日聚合表求和结果与实时聚合结果必须一致
  （去重计数除外，去重计数按§2.9 的相加口径断言）。
- 覆盖率不低于 80%；没有真实输出不得声称通过。

---

## 11. 待决问题

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| 1 | `hyperlink_click` 的保留期与归档策略 | 唯一线性膨胀的表，不定就是埋雷 | 建议 90 天，与分析页最大窗口对齐 |
| 2 | 深度追踪短链域名是否与买量 `promotion_domain` 隔离 | 共用域名时超链被 WhatsApp 拉黑会连带买量落地页一起挂 | 建议给 `promotion_domain` 加用途判别列做物理隔离，**不新建域名表** |
| 3 | `MessageTarget` 改为语义中立 `jid` 的全局评审 | 跨业务共享类，**P0 的硬前置** | 评审通过前 P0 不能开工 |
| 4 | `marketing_template_file` 加列的全局评审 | P2 的硬前置 | 新列全可空、不改现有读写，风险可控 |
| 5 | 任务名是否同租户唯一（§5.4） | 决定运营能否按天创建同名任务 | 倾向加唯一键，请产品确认 |
| 6 | 「导入方式」筛选的 SQL 成本 | 需经 `account_import_detail` 关联，可能拖慢圈号 | 实测；若慢则在 `account` 冗余一列（走全局评审） |
| 7 | `cta_call` 的 `buttonParamsJson` 字段名（§4.3-A） | 仓库无法确证，猜错就是发出去不渲染 | **真机 A/B 验证**，这是 P0 的验收项之一，不是待决的设计选择 |
| 8 | 账号画像三项（好友数/允许拉群/注册天数） | 决定筛选项能否补齐 | 沿用数据模型 §8 的硬约束，三者齐备前不落列 |
| 9 | 多任务共用同一账号的号损 | 无跨任务抢号互斥（§4.4） | 灰度实测；若有号损再上账号级令牌桶 |
| 10 | Android 私聊路由的排期（§4.3-B） | 自研栈，工作量最大且必须真机回归 | 建议单独拆一个 PR，不与按钮改动混在一起提 |

---

## 附：证据索引

| 结论 | 出处 |
|---|---|
| 新建只有 3 种消息类型 | `readable/assets/task-0vbZUOmq.js:1732-1747` |
| 按钮上限 1 与四种类型校验 | `task-0vbZUOmq.js:1077-1092`，`最多 1 个` 文案 |
| 深度追踪按钮级 | `task-0vbZUOmq.js:1677-1680` |
| 消息间隔小数与三档预设 | `task-0vbZUOmq.js:895-911` |
| 并发上限常量 `mr=1e4` | `task-0vbZUOmq.js:762, 1047-1066` |
| 协议台数 × 15 | `task-0vbZUOmq.js:991, 1051` |
| 两个状态字段 | `task-0vbZUOmq.js:5094-5119` |
| 任务提交字段全集 | `task-0vbZUOmq.js:1577-1607` |
| 任务详情回填字段全集 | `task-0vbZUOmq.js:1495-1569` |
| 策略只 6 个参数 | `strategy-D2fnr_pX.js:443-454, 657-671` |
| 策略周期间隔下限 30 分钟 | `strategy-D2fnr_pX.js:463-470` |
| 素材库列表与删除保护 | `library-C1_C9S_k.js:142-172, 245`，`该素材仍被 N 处模板引用` |
| 批量上传上限 100 | `resource-asset-upload-modal-Cns3ms7s.js:157` |
| 分析窗口 90 天 / 7 天 | `analysis-DA45fcKJ.js:991-998, 1113-1119` |
| 分析请求参数 | `analysis-DA45fcKJ.js:1121-1126` |
| 分析响应结构与相加口径 | `analysis-DA45fcKJ.js:1148-1205` |
| 账号筛选字段全集 | `account-filter-modal-BXDIvipG.js` |
| 任务页只透传筛选子集 | `task-0vbZUOmq.js:1512-1529` |
| 接口面 | `router-CPQmbuR9.js:45960-46160, 46268-46320, 46739-46760, 46878-46895` |
| Web 协议按钮能力与 1~3 个上限 | `armada-protocol/protocol-layer/src/messages/card-content.ts:19,37-60,161-176` |
| Baileys 对 native flow 按钮纯透传、无名称白名单 | `protocol-layer/node_modules/baileys/WAProto/WAProto.proto:2731-2737` |
| Android 只支持单个 `cta_url` | `whatsapp-server-feature-android-zhuan/internal/service/node/message_payload.go:123-152` |
| Android 单按钮入参模型 | `internal/service/entity/message.go:391-403` |
| Android 侧 `len(buttons)!=1` 断言与 Type 被丢弃 | `internal/armada/message_sender.go:562-576` |
| Android 发送路由是群语义 | `internal/service/app/group.go:258-291, 317` |
| 模板消息 CallButton 用 camelCase（**不是** native flow） | `internal/service/waproto/WAWebProtobufsE2E.proto:1509, 2232` |
| armada Android backend 的按钮门 | `com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java:126,130` |
| armada Web backend 无按钮校验 | `com/armada/platform/protocol/backend/web/WebMessageSendBackend.java:104-115` |
| Web 每条消息都查 group sendability，历史群链路已有跳过先例 | `protocol-layer/src/commands/worker-consumer.ts:764-766, 866-874` |
| armada 发送命令抽象（目标只有 groupJid） | `com/armada/platform/protocol/model/command/MessageSendCommand.java:36` |
| 协议分流 | `com/armada/platform/protocol/model/enums/ProtocolBackend.java` |
| 轮次调度范式 | `com/armada/marketing/scheduler/MarketingRoundScheduler.java` |
| 异步导出框架 | `com/armada/marketing/export/*`，`V152__marketing_export_job_data_scope.sql` |
| 占用模型是分组级 | `com/armada/account/model/enums/AccountMarketingOccupancyType.java:6` |
| 毫秒间隔先例 | `com/armada/marketing/model/entity/MarketingTask.java:57` |
| 号码池状态与一期表结构 | `armada-api/src/main/resources/db/migration/V153__hyperlink_data_package.sql` |
| 模板表与内容契约 | `V154__hyperlink_template.sql`、`HyperlinkMessageContentValidator.java` |
| Flyway 当前最高版本 | `V155__hyperlink_marketing_menu_rbac.sql` |
