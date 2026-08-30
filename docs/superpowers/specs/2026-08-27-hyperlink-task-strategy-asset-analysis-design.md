# 超链任务 / 超链策略 / 图片素材 / 超链市场分析 详细方案

- 日期：2026-08-27
- 状态：**超链任务已按查询/运行效率冻结；策略 / 素材 / 市场分析按各菜单实施时复核**
- 超链任务公共合同：`docs/superpowers/specs/2026-08-28-hyperlink-task-shared-contract.md` v1.1；六份后续方案不得重定义其 HTTP、共享 DTO、枚举、指标和权限
- 范围：超链营销一级菜单下除「超链数据包」「超链营销模板」之外的**四个模块**
- 基准分支：`1.0.3-snapshot`（当前已使用到 `V156`；后续版本实施前动态分配）
- 上游文档：
  - 总设计 `docs/superpowers/specs/2026-08-27-hyperlink-marketing-replication-design.md`
  - 一期详细方案 `docs/superpowers/specs/2026-08-27-hyperlink-data-template-phase1-design.md`
  - 数据模型 `docs/business/hyperlink-marketing-data-model.md`
- 复刻来源：`hylbuiaxykfrontendsource/readable/assets/`（仅构建产物，无 sourcemap、无后端源码）

> **2026-08-27 竞品复核纠偏**：超链任务已经按主分块、账号筛选、按钮编辑器、素材弹框、
> WhatsApp 预览、访问趋势和路由/API 分块重新逐项取证。任务菜单的实施口径以
> `docs/superpowers/specs/2026-08-27-hyperlink-task-competitor-parity-detailed-design.md` 为准，
> 证据见 `docs/superpowers/reviews/2026-08-27-hylb-hyperlink-task-reverse-evidence.md`。
> 本文仍负责四菜单总览；与新任务详设冲突的旧任务结论均作废。

口径不变：**前端功能一致、接口仿照、后端按 armada 现有能力适配实现**。

---

## 0. 一句话结论

四个模块里，**超链任务是唯一的重活**——它是发送引擎；策略是它的参数预设，素材库是它的图片来源，市场分析是它的读模型。
因此本方案按「协议补齐 → 策略 → 素材 → 任务 → 回流 → 深度追踪 → 市场分析」的依赖顺序分七期落地。

协议层缺的任务实际能力（CTA URL 单按钮、私聊目标）**全部补齐，不做降级**（§4.3）。这决定了 P0 是协议层，
任务期在协议真机验证通过之前不开工——否则会写出一套发不出去的发送链路。

---

## 1. 实施前必须遵守的既有结论

一期已冻结、本期**不得推翻**的前提：

| # | 结论 | 出处 |
|---|---|---|
| 1 | 接口前缀 `/api/<resource>`、字段 camelCase、返回 `ApiResponse<T>` / `PageResult<T>` | 一期 §7 |
| 2 | 号码池状态机 `1未使用 2已领取 3当前单钩 4已送达 5可重试失败 6未注册`，写在 `data_package_phone.pool_status`；状态 5 只表示可被后续其他任务重新领取，不在本任务重发 | `V153` / 数据模型 §3.2 |
| 3 | 任务领取号码：锁 `data_package` 读 `current_generation`，按 ID 升序、条件 `generation=current AND pool_status=UNUSED` 批量更新为 CLAIMED | 一期 §13.1 |
| 4 | 消息内容契约（`HyperlinkMessageContent` + `HyperlinkMessageContentValidator`）模板与任务共用，一套字段长度、一套校验器 | `HyperlinkMessageContentValidator.java` |
| 5 | recipient 保存包 ID/代次/导入批次/号码/国家**快照**，不保存 `data_package_phone_id` | 一期 §6.6 |
| 6 | 2026-08-28 收口：同任务同号码最多发送一次，协议消息 ID、唯一 command、结果和短码都归 recipient；超时只恢复同一 command，不另建 attempt | 数据模型 §4.5 |
| 7 | 图片素材沿用 `marketing_template_file` 的字节存储与稳定 ID，不改表名、不复制字节 | 数据模型 §6.1 |
| 8 | 不做国家风险拦截、不做批量号码预探测；**计费结论被本次竞品复核推翻**，余额/报价/冻结/结算是任务完整上线的硬依赖 | 任务详设 §8 |
| 9 | 数据库结构只走 Flyway，新列必须带 `COMMENT`，落地后重跑 `.harness/wiki/gen_datamodel.py` | AGENTS.md / 数据模型规范 |

---

## 2. 从静态前端确证的事实（本期新增）

以下均可在存档中复核，是本方案的输入，不是推断。

### 2.1 消息类型：新建时只有三种

`task-0vbZUOmq.js:1732-1747` 的消息类型选项**只有 `3 普通按钮` / `4 卡片按钮` / `1 单图文`**。
`2 双图文` 只在渲染历史数据时出现，竞品自己不允许新建。

> 这与一期「双图文提交返回 `40001 一期暂不支持双图文`」**完全一致**。
> **本期同样不开放双图文**，且这不再是"暂不支持"，而是与竞品一致的最终形态。
> 收益很实在：新任务的一位 recipient 恒等于一个物理消息和一个协议 command，不需要消息分片或尝试序号；
> 历史双图文只做内容读取兼容，不反向污染新任务发送模型。

### 2.2 按钮：最多 1 个，任务锁定 CTA URL

通用 `HyperlinkButtonEditor` 内部确实实现了四种类型，但任务页调用时传入
`locked-type="cta_url"`（`task-0vbZUOmq.js:2246-2275`）。因此超链任务的真实能力是：

- 普通按钮、卡片按钮均恰好 1 个按钮；
- 类型固定 `cta_url`，按钮文案最多 30 字，URL 必填；
- 深度追踪是这个 CTA URL 按钮上的开关；
- 不为任务新增 `cta_call`、`cta_copy`、`quick_reply`。

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

竞品请求参数为 snake_case；Armada 接口固定 camelCase：`dateFrom`、`dateTo`、
`granularity`(`day`|`hour`)、`taskType`、`senderCountryIso2`、`recipientCountryIso2`、
`accountType`、`deviceOs`(`android`|`iphone`)、`shortLinkEnabled`。设备筛选来自发送时
`account.device_os` 快照，不使用 `protocolBackend`，也不复用任务账号筛选的六值 `platform`。

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

比率一律按原始计数现算：单钩率 `=单钩÷发送`、双钩率 `=双钩÷单钩`、点击率 `=点击UV÷单钩`、
封号率 `=封号÷使用号数`、号均 `=单钩÷使用号数`。Armada 后端返回固定指标中的三个率和号均原值，
前端只做百分比/小数格式化；点击率仍由页面计算。预计落地率是页面明示的经验估算，精确公式为
`min(99%, 双钩率 + 20 个百分点)`，不是后端字段。

### 2.10 账号筛选弹窗的完整字段

```
country_iso2s[] / exclude_country_iso2s[] / continent
group_ids[] / channel_ids[] / protocol_id
online_status / rotation_status / account_type / platform / wid_type / import_mode
group_invite_allowed / stranger_muted / account_status / is_exported
phone / import_no / source
friend_count_min|max / retention_days_min|max / register_days_min|max
created_at_from|to
```

任务实际打开的是完整账号筛选抽屉，轮号状态、号码来源、允许拉群、好友数、留存天数和注册天数
都是真实可见控件。`hyperlink_task_count_*` 只属于筛选组件的其他业务场景，不在任务场景显示。

任务提交时强制注入 `account_status:'normal'`、`is_exported:false`、`stranger_muted:false`；
**没有**锁定 `group_invite_allowed`，允许拉群仍由用户筛选。竞品编辑回填漏掉 `rotation_status`、
`source` 等字段是前端缺陷，不代表功能不存在；Armada 必须保证所有可见筛选字段完整往返。

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
| 2 | `MessageCorrelation` 无超链分支 | 增 `HyperlinkCorrelation(taskId, recipientId)`；轮次从 recipient 读取，不创建 attempt 模型 |
| 3 | `AccountQuery` 只有 14 个维度 | 新增筛选服务 `HyperlinkAccountSelector`，不硬塞进 `AccountQuery`（§4.2） |
| 4 | `ProtocolMessageEventConsumer` 只处理 `message.send_result_reported` | 新增 `message.ack` 分支（`server_ack`/`delivery_ack`/`read`） |
| 5 | 账号占用模型是**分组级**（`account_group.marketing_occupancy_type`） | 超链按筛选圈号、跨分组，**不套用分组占用锁**（§4.4） |
| 6 | 任务实际只允许 1 个 `cta_url`；Android 现状正好匹配，Web 需统一校验 | 不扩四类按钮，只验证两条协议的 CTA URL 单按钮结构一致（§4.3-A） |
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
action=pause   runStatus 1 → 3           在途 command 跑完即止，不撤回已入队命令
action=resume  runStatus 3 → 1
action=stop    runStatus 1|3 → 4         终态，不可恢复；未提交 recipient 记 TASK_STOPPED 失败，释放对应 CLAIMED 号码与未消费冻结，历史行保留
自动完成       runStatus 1 → 2           即时：号发完；预发布：到期或号发完（先到为准）；周期：剩余 recipient 发完
```

runtime 用 `execution_duration_sec` 保存已结束运行段累计秒数、`active_since_at` 保存当前运行段起点：开始/继续
写起点，暂停/停止/完成先累加再清空。`metrics_updated_at` 只代表分钟级发送指标投影完成时间，生命周期与点击
更新都不能把它刷新成“看起来已同步”。

编辑限制以竞品实际分支为准：`runStatus=0` 显示编辑，其余状态显示查看。
因此已启用但尚未开始（例如延后执行）的任务仍可编辑；开始后只读。
`message_type` 在编辑模式下一律不可改（前端禁用 + 后端拒绝）。

三种模式的执行差异：

| 模式 | 结束条件 | 账号范围刷新 | 轮次 |
|---|---|---|---|
| 1 即时 | 数据包领取的号发完 | 启动时定格一次 | 单轮 |
| 2 预发布 | `taskPlannedEndAt` 到期 **或** 号发完（先到为准） | round 1 运行期间持续重匹配，新号自动加入 | 单个长轮次 |
| 3 周期 | 剩余 recipient 发完或手动 `stop` | 每轮重新匹配 | 每 `taskIntervalMinutes` 一轮，每轮至多 `maxUseAccount` 个发信号，只分配未发送收信人 |

### 4.2 账号筛选：本期落地范围

新建 `HyperlinkAccountSelector`（超链域内的账号圈选服务），**不扩 `AccountQuery`**——
`AccountQuery` 服务的是账号菜单列表，塞入 12 个营销专用维度会把它变成大杂烩，
而且 `account` 是跨业务共享主表，随业务加筛选维度必然失控。选择器依赖 `account`、
`account_state`、账号组/渠道/协议关系，并通过一对一 `account_profile` 承载营销画像缺口。

| 筛选项 | 本期 | 数据来源 |
|---|---|---|
| 国家包含 / 排除 / 大洲 | ✅ | `account.ws_phone` 区号 → `country_phone_prefix_mapping` → `country.continent` |
| 分组 / 渠道 / 协议 | ✅ | `account.account_group_id` / `promotion_channel_id` / `protocol_id` |
| 在线状态 | ✅ | `account_state.login_state` |
| 轮号状态 | ✅ | 新增 `account_profile.rotation_status` |
| 账号类型（个人/商业） | ✅ | `account.account_type` |
| 类型（主设备/分身，`wid_type`） | ✅ | 由 `protocol_id` 派生 `ProtocolBackend`，**不落 `wid_type` 列** |
| 设备类型（`platform` 六值） | ✅ | 由 `device_os + account_type + ProtocolBackend` 组合，**不落复合列** |
| 账号性质（五类，`source`） | ✅ | `account_profile.marketing_source`：买量/自登/买入/转入/群扫码 |
| 手机号 / 批次号 | ✅ | `account.ws_phone` / `account_import_batch.id`；经 `account_import_detail.batch_id` 圈号，不新增 `import_no` |
| 入库时间 | ✅ | `account.created_at`；竞品任务抽屉没有最近登录时间筛选 |
| 存活天数 | ✅ | `now - account.created_at` 派生，**不落列** |
| 导入方式（六段/全参） | ✅ | `account_credential.cred_format` 直接按账号关联，不给 account 冗余第二份事实 |
| 好友数 | ✅ | `account_profile.friend_count`，协议侧定期同步 |
| 允许拉群 | ✅ | `account_profile.is_group_invite_allowed` |
| 注册天数 | ✅ | `registered_at` 或注册天数快照，带画像同步时间 |
| 轮号状态 | ✅ | `account_profile.rotation_status` |
| 超链寿命 | ❌ 任务不显示 | 只出现在筛选组件其他业务模式，不属于任务场景 |

固定注入（与竞品一致，不作为可选项暴露）：账号状态=有效、未导出、禁言=未禁言。
允许拉群不是固定条件，仍提供全部/允许/不允许三态筛选。

好友数、允许拉群、注册天数、轮号状态的数据源是菜单完整复刻的硬依赖。数据源未就位时不能通过隐藏控件
宣称任务菜单完成；页面可在画像尚未同步时提示数据时效，但字段、筛选和完整回填必须存在。

`accountFilter` 以 JSON 存库，空对象 = 不限定（全部有效账号）。
**入库前必须按白名单归一化**：未知键丢弃、国家码大写、ID 去重、数值下界裁剪。
不能把前端 JSON 原样落库——那是后续任何筛选语义变更都无法收口的伏笔。

### 4.3 协议能力补齐（**本期硬前置，不做能力降级**）

任务真实协议范围只有 **CTA URL 单按钮** 和 **私聊目标**。不做“只允许 Web 协议账号”的降级，
但也不再为竞品任务没有开放的电话、复制、快捷回复和多按钮做额外协议开发。

| 形态 | 协议命令 | 补齐后 Web | 补齐后 Android |
|---|---|---|---|
| 1 单图文 | `MessageType.LINK_CARD` | ✅ 已有 | ✅ 已有 |
| 3 普通按钮 | `MessageType.BUTTON_CARD`（无 thumbnail） | ✅ 验证 CTA URL 单按钮 | ✅ 已有结构，真机验证 |
| 4 卡片按钮 | `MessageType.BUTTON_CARD`（带 thumbnail） | ✅ 验证 CTA URL 单按钮 | ✅ 已有结构，真机验证 |

#### A. 按钮能力

四层契约统一收口为：按钮数量恰好 1、类型 `link/cta_url`、文案非空且最多 30、URL 合法，
`useShortLink` 只控制发送前是否替换为任务短码。Web、Android 和 armada Java 侧都做一致校验；
已有通用模型的其他按钮类型不删除，但超链任务 DTO 不接受。本期协议验收只做普通按钮、卡片按钮
两种消息形态在 Web/Android 私聊中的 CTA URL 真机渲染与点击。

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

沿用 `MarketingRoundScheduler` 的轮次范式，但不从 recipient 大表反推调度进度。一轮的执行步骤：

```
0. 首次领号：recipient_claim 按 phone.id 游标分批写 recipient；每批短事务；进入 OWNED 后释放代次操作锁，
        号码归属继续由 phone.claimed_by_hyperlink_task_id 隔离，完成后才进入首轮
1. 建轮次：写 hyperlink_task_round；即时/预发布只用 round 1，周期到点新建一轮；worker 条件认领时写独立租约
2. 选号：HyperlinkAccountSelector 按 accountFilter 圈候选，排除 account_usage 已达任务成功上限/封号/失效账号
3. 固化：锁 round，UPSERT task_account_usage 并 INSERT round_account；即时/预发布 round 1 至多
        maxUseAccount 个（0=不限），周期每轮至多 maxUseAccount 个，唯一键保证重放只补缺口
4. 空轮次：周期账号为 0 时记“无账号跳过”；尚有待发 recipient 时下轮继续选号，预发布则保留 round 1 等新号
5. 分配：按任务内 `send_status=待发 AND round_id IS NULL` 的索引使用 SKIP LOCKED 分批认领 recipient，
        一次写入不可变的 round/account/command/shortCode，并同步增加 account_usage 的在途/成功槽预占
6. 派发：唯一 command 入协议命令 outbox；结果未知时查询或重放同一个 command_id，不创建第二次业务发送
7. 节流：按 accountSendConcurrency 控制单账号在途数，间隔在 msgIntervalMinMs~maxMs 内随机
8. 收敛：本轮已分配 recipient 无待发/发送中后完成；仍有未分配 recipient 才原子创建下个周期 round，
        runtime 由分钟级投影器汇总
```

recipient 在任务领取阶段已经一次性生成，round 不再复制 50 万行。派发不使用单一递增游标：并发认领、
节流等待和超时恢复会产生空洞，用游标跳过空洞有漏发风险，`send_status + round_id + next_dispatch_at` 索引才是事实。
round.`next_dispatch_at` 只表示业务可执行时间；`lease_owner/lease_expires_at` 表示 worker 存活。选号或派发 worker
崩溃后必须等租约过期再按状态/version 接管，暂停、终态或主动释放执行权时清空租约。

**账号占用：本期不引入互斥锁。** 现有占用模型是分组级
（`account_group.marketing_occupancy_type`，见 `AccountMarketingOccupancyType`），
而超链按筛选条件跨分组圈号，两者粒度不兼容。硬套会出现"一个超链任务锁掉整个分组、
群营销全线停摆"的事故。

替代方案分两层：任务内由 `hyperlink_task_account_usage.in_flight_count` 条件占槽，限制
`accountSendConcurrency`；跨任务由 Redis 账号级 TTL 信号量限制总在途数，holder 是 recipient.`command_id`。
全局上限用 `armada.hyperlink.account.max-in-flight` 配置，默认 20；Redis 不可用或续租失败时 fail closed、
延后派发。这样不锁整个账号分组，也不会让两个任务各自 20 并发叠加成单号 40。

### 4.5 单钩 / 双钩 / 失败回流

```
协议 message.ack ──┐
                   ├─→ ProtocolMessageEventConsumer（新增 ack 分支）
协议 send_result ──┘         │
                             ├─→ hyperlink_task_recipient     按 command/message 唯一键幂等、单调更新
                             ├─→ hyperlink_task_account_usage 同步兑现/释放成功槽与在途计数
                             ├─→ 账号累计投影                  recipient 投影器批量幂等更新
                             ├─→ round/runtime                 recipient 投影器按任务分钟级合并
                             └─→ data_package_phone.pool_status + data_package_stat   可靠事件幂等更新
```

映射：`server_ack` → 单钩、`delivery_ack` → 双钩、`read` → 已读。
协议明确返回未注册时才置 `未开通WS`，其余失败置 `发送失败`（一期已冻结：不做批量预探测）。
任务停止前尚未提交协议且没有 command 的待发行，统一按失败写
`fail_code=TASK_STOPPED`、`fail_reason=任务已停止`，不增加独立“跳过”状态。

四条硬规则：

1. **recipient 状态只前进不后退**。ACK 乱序到达是常态，只能通过 command/message 唯一键推进对应行。
   同任务同号码没有第二轮发送或第二个业务 command。
2. **不在高频回执里锁 `data_package` 主行**。只更新 `data_package_phone` 行与 `data_package_stat` 单行（一期已冻结）。
3. **不逐条更新 runtime 热行**。逻辑指标以 recipient 为单位异步合并，同 command 恢复不增加发送数。
4. **失败原因落库前按列宽截断**，且日志不打完整手机号与推广链接参数。

### 4.6 深度追踪

按 §2.3，短码归属于「唯一 recipient × 开启了 `useShortLink` 的 `cta_url` 按钮」。按钮数量上限是 1，
因此每个 recipient 至多一个短码：

- `hyperlink_task_recipient.short_code` 单列即可，不需要独立短链表。
- 发送时把该按钮的 `url` 替换为 `https://{域名}/hl/{shortCode}`。
- 公网 `GET /api/public/hl/{shortCode}` 反查并锁定 recipient，更新访问次数、首末时间、首次 IP/UA/设备环境，
  同事务原子增加 runtime 的任务 UV/PV 后 302 跳原始 URL；只更新普通 `updated_at`，不推进发送指标的
  `metrics_updated_at`。
- 单图文任务不生成短码，点击类指标真实为 0（与竞品一致）。
- 访问趋势以第一个 UV 为起点；同一 recipient 的全部 PV 按其 `first_visit_at` 近似归入首访时间段，
  不能按后续 click 的真实时间分桶。页面直接按 recipient.`first_visit_at` 和 `click_count` 聚合，当前业务
  单任务发送量不超过 10 万，不建 30 分钟聚合表。

同一任务内一个收件人只发送一次，实际发信账号也冻结在 recipient，因此 recipient 级短码可以精确归因。
若未来明确放开同人多次发送或多按钮，再按真实需求重新评审映射关系；当前不预建死表。

点击 UV 从 `hyperlink_task_recipient` 用 `COUNT(*) WHERE click_count > 0` 算；PV 为 `SUM(click_count)`。
竞品没有逐次点击明细入口，因此不建逐次点击流水（数据模型 §4.5 已冻结）。

公网写入路径无租户上下文，Mapper 用 `@InterceptorIgnore(tenantLine = "true")` 并显式带
`tenant_id`，与 `PromotionPairingSessionMapper` 现有做法一致。

### 4.7 封号归因

封号/失效事实并入 `hyperlink_task_account_usage`：任务×账号本来就是唯一一行，增加首次
`invalid_code/invalid_reason/invalid_at` 即可天然去重，不再复制 task/account/手机号/国家建单独封号表。

触发时机：消费账号状态变更事件时，若该账号在**任一进行中的超链任务**里已有 account_usage，首次把
`invalid_at` 从 NULL 更新为事件时间，并保存 `account_state.block_error_code`；重复事件只补原因、不重复计数。

`ban-stats` 接口返回按原因码分组的占比。竞品的原因文案（可直接复用）：

```
中途禁言，马上封号 / 中途强制被掐掉，封号 / 从主设备登录出，被强制下线 / 主设备直接掉了/封了 / 未知原因
```

> **为什么不直接查 `account_state`**：它只有当前状态、没有历史。account_usage 保存任务期首次失效快照，
> 账号后来解封也不会丢掉本任务的封号事实（数据模型 §4.9）。

### 4.8 市场分析的读取路径

| 场景 | 数据源 |
|---|---|
| `granularity=day`（≤90 天） | `hyperlink_stat_daily` 预聚合表，按筛选维度过滤后分组 |
| `granularity=hour`（≤7 天） | `hyperlink_stat_hourly` 滚动 8 天预聚合，页面不扫 recipient |
| 顶部 `overview` | 相同筛选窗口内按 recipient 受控回源，账号和封号全局 `COUNT(DISTINCT)` |
| `marketing-stats/countries` | 与主查询相同时间窗口；从对应日/小时投影取发信国 / 被营销国去重清单 |
账号明细与导出继续由任务详情页既有接口负责；市场页不重复提供 `accounts` 或 `accounts/export`。

日聚合每小时回填昨天/今天；小时聚合每 5 分钟回填当前/上一小时、每日低峰重算最近 8 天，均按唯一键
幂等 UPSERT。小时表若保留 90 天理论量过大，但页面只允许 7 天，因此滚动 8 天是容量与查询效率的平衡；
`idx_hyperlink_recipient_market_stat` 服务后台回填/校准及顶部 `overview` 的一次受控精确聚合；列表和趋势不回源。

---

## 5. 数据模型

### 5.1 表清单与 Flyway 编排

| 顺序 | 内容 |
|---|---|
| 1 | `hyperlink_strategy` |
| 2 | `marketing_template_file` 素材管理列 + `resource_asset_tag` + `resource_asset_tag_ref` |
| 3 | `account_profile` + `hyperlink_template.title` 扩到 1024 |
| 4 | 10 张任务表：3 张业务事实 + 6 张执行/计费状态 + 1 张专用查询投影 |
| 5 | `hyperlink_stat_daily` + `hyperlink_stat_hourly`（滚动 8 天） |
| 6 | 四个菜单 + RBAC 权限 |

`V156` 已被数据包纠偏迁移使用，本文早期写死的 `V156~V163` 全部作废。版本号实施前重新扫描
全局最高版本再定。
`ADD COLUMN` 一律用 `information_schema` 守卫保证幂等。

### 5.2 对现有数据模型文档的修订

`docs/business/hyperlink-marketing-data-model.md` 的任务族设计写在超链任务还没做的时候，
本期先用静态前端事实校正 7 处，再于 2026-08-28 完成物理模型收口：

| # | 原设计 | 修订 | 依据 |
|---|---|---|---|
| 1 | `status TINYINT` 单字段 7 态 | 拆为 `is_enabled TINYINT(1)` + `run_status TINYINT` | §2.5，竞品就是两个字段；合成一个字段会丢掉"已停用但曾经跑过"的信息 |
| 2 | `msg_interval_min_sec/max_sec INT` | `msg_interval_min_ms/max_ms INT` | §2.4 需要 0.1 秒精度，INT 秒存不下；`marketing_task.account_group_send_interval_ms` 已是毫秒先例 |
| 3 | `is_short_link_enabled` 为任务级开关 | 改为**派生冗余列**，事实源是 `hyperlink_task_content.buttons[].useShortLink` | §2.3 深度追踪是按钮级 |
| 4 | `hyperlink_strategy` 含 `msg_interval_*`、`account_send_concurrency` | **删除这三列** | §2.7 策略页无对应控件，落列即死列（规范一.4） |
| 5 | 未提及 `default_sub_task_num` | **不落列** | 前端硬编码 50，无 UI 控件，是竞品遗留字段 |
| 6 | recipient 原本只保存受众快照 | 同行增加实际账号/协议、唯一 command/ACK、发送结果、短码、点击累计与首触归因字段 | §4.4～§4.6；一位收信人一行，避免 50 万级 1:1 拆表和逐次点击流水 |
| 7 | `hyperlink_stat_daily` 无设备维度 | 增 `sender_device_os TINYINT` 与 recipient 发送时快照 | §2.9 的 android/iphone 是设备 OS，不是协议后端 |
| 8 | 策略模板另表、六字段同时复制进 task | 统一 `hyperlink_strategy` 保存 TEMPLATE/TASK_SNAPSHOT，task 只强关联快照 ID | 2026-08-30 单一事实源决策；详见聚焦设计 §7、§10 |

最终收口同时确定：双状态与列表投影落 `hyperlink_task_runtime`，调度边界落 `hyperlink_task_round`；任务无删除列；
目标国家使用 JSON 数组快照；recipient 承载唯一发送命令、结果和短码；计费预约与任务 1:1；首次封号/失效
事实并入 account_usage；账号累计使用可重建投影且账号展示快照只保留在 account_usage，访问趋势直接聚合
recipient；recipient 首触敏感环境保留 90 天。轮次业务时间与 worker 租约分列；计费行以待操作类型、幂等键、
重试时间恢复外部调用；claim 进入 OWNED 后不继续占代次操作锁。唯一字段口径
见数据模型 §4，本文不再维护第二份表结构。

### 5.3 `hyperlink_strategy`

> **2026-08-30 修订**：本节早期“模板表 + task 内嵌六字段”的 SQL 草案作废，不再维护第二份表结构。
> `hyperlink_strategy` 是统一事实源，通过 `strategy_scope` 区分 `TEMPLATE` 与 `TASK_SNAPSHOT`；
> `hyperlink_task.hyperlink_strategy_id` 强关联任务独占快照，快照的 `source_strategy_id` 弱追溯模板。
> 存量 task 六字段采用 expand/contract 迁移后删除。唯一详细口径为
> `docs/superpowers/specs/2026-08-30-hyperlink-strategy-template-competitor-parity-design.md` §7、§10。

### 5.4 超链任务最终物理模型

任务表结构不在四菜单总览中复制维护，唯一口径为
`docs/business/hyperlink-marketing-data-model.md` §4。结论按工作负载固定为 10 张任务表：

1. `hyperlink_task`：低频配置和数据包/国家/筛选快照，无任务删除列。
2. `hyperlink_task_content`：1:1 消息快照；标题与现有模板同步扩到 1024；素材引用可按索引反查。
3. `hyperlink_task_runtime`：1:1 双状态、累计运行时长/当前运行段和分钟级列表计数。
4. `hyperlink_task_round`：每轮 due scan、独立 worker 租约、账号选择、剩余 recipient 分配和恢复状态。
5. `hyperlink_task_account_usage`：任务×账号跨轮成功上限、预占槽、在途并发、节奏与首次封号/失效状态。
6. `hyperlink_task_round_account`：轮次×账号稳定选号集合，防重启/并发重选超限。
7. `hyperlink_task_recipient_claim`：大包按批领号/释放的代次操作互斥、游标、租约与补偿状态；OWNED 后靠号码 owner 隔离。
8. `hyperlink_task_recipient`：任务内唯一收件人、实际轮次/账号、唯一 command/ACK、最终状态、短码、点击累计和首触归因；停止前未提交行按 TASK_STOPPED 失败。
9. `hyperlink_task_account_stat`：任务×账号累计指标投影（含未分配桶），展示快照 JOIN account_usage；任意时间范围直接按 recipient 精确聚合。
10. `hyperlink_billing_reservation`：任务 1:1 报价、待操作类型/幂等键/重试时间及冻结/结算/释放状态，不复制外部钱包总账。

表数不是目标。recipient_claim 解决 50 万号码的短事务批处理，round/account_usage/round_account 是选号限额、
调度正确性和恢复边界，账号累计投影是默认排序分页的查询边界且可从事实重建，不是第二套真值。访问趋势按
recipient 首访索引直接聚合。recipient 已完整表达
“一个收信人一次发送及其首触归因”，不再建 recipient_round、attempt、逐次点击、独立短链和封号表。周期只分配剩余 recipient，
任务按冻结受众一次计费。比率一律现算，不落会漂移的冗余列。

### 5.5 `hyperlink_stat_daily` / `hyperlink_stat_hourly`

沿用数据模型 §7.1，设备维度固定为 **`sender_device_os TINYINT NOT NULL`**
（0 未知 / 1 安卓 / 2 苹果），取 recipient 的发送时快照。

唯一键：`(tenant_id, stat_date, sender_country_iso2, recipient_country_iso2, account_type,
task_type, sender_device_os, is_short_link_enabled)`。

未知国家落 `ZZ`。维度基数估算（加了协议维度后）：

```
发信国(~50) × 被营销国(~50) × 账号类型(2) × 任务模式(3) × 深度追踪(2) × 设备(2) ≈ 6 万组合
理论上限 6 万行/天 × 90 天 ≈ 540 万行
实际国家对高度稀疏（一个租户通常只跑 3~10 个国家对），真实量级低两个数量级
```

`used_account_count` / `banned_account_count` / `click_uv_num` 是**行内去重、跨行相加**的口径，
与竞品一致（§2.9）。这一点必须写进列注释与接口注释。
小时表使用同一组维度/指标，主时间键改为 `stat_hour_start_at`，滚动保留 8 天；页面日/小时粒度分别只读
对应投影，禁止小时模式回退到在线扫描 recipient。

### 5.6 素材管理列

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
POST   /api/hyperlink-tasks                          新建（application/json）
PUT    /api/hyperlink-tasks/{id}                     编辑（application/json）
GET    /api/hyperlink-tasks/{id}/provision-status    启用任务准备状态
POST   /api/hyperlink-tasks/{id}/action              {action, version, quoteToken}
GET    /api/hyperlink-tasks/export                   列表导出
GET    /api/hyperlink-tasks/{id}/summary             详情顶部公共摘要
GET    /api/hyperlink-tasks/{id}/recipients          收信人流水
GET    /api/hyperlink-tasks/{id}/account-stats       发信账号维度统计
GET    /api/hyperlink-tasks/{id}/clicks              点击明细（深度归因）
GET    /api/hyperlink-tasks/{id}/visit-trend         访问趋势
GET    /api/hyperlink-tasks/{id}/ban-stats           封号原因分布
POST   /api/hyperlink-tasks/{id}/recipients/export         异步导出
POST   /api/hyperlink-tasks/{id}/account-stats/export      异步导出
POST   /api/hyperlink-tasks/{id}/visit-trend/export        异步导出
POST   /api/hyperlink-tasks/{id}/click-attribution/export  异步导出
GET    /api/hyperlink-task-exports/{jobId}                 导出作业状态
GET    /api/hyperlink-task-exports/{jobId}/download        导出文件
GET    /api/hyperlink-tasks/create-context           模式、价码、余额、协议数
POST   /api/hyperlink-tasks/quote                    CREATE/START 报价、数据包人数、预计冻结、quoteToken
POST   /api/hyperlink-tasks/account-match-count      按完整 accountFilter 试算可用账号数
```

列表查询参数：`page`、`pageSize`、`taskName`、`runStatus`、`taskType`、
`recipientCountryIso2`、`createdFrom`、`createdTo`。

任务保存只提交 JSON 和稳定的 `linkPreviewAssetId` / `bodyMainAssetId`；新图片先走素材上传接口，任务接口不再
同时维护 multipart 与 JSON 两种合同。`accountFilter` 与 `buttons` 是 JSON 对象/数组，不二次字符串化。

> **与竞品的接口差异（有意）**：
> 1. 导出改为 `POST` + 异步任务，复用 `marketing_export_job` 框架。竞品的同步
>    `GET .../export` 在数十万行收信人上必然超时，同步导出是**不可复刻的错误设计**。
> 2. 新增 `account-match-count`。竞品在新建抽屉里实时显示"N 个可用"，
>    走的是账号列表接口的 `total`；armada 的账号筛选维度与之不同，必须有专用试算端点。

`account-match-count` 同时返回协议台数上限，供前端做 `concurrent_num ≤ 协议台数 × 15` 校验：

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
GET /api/hyperlink-tasks/marketing-stats/countries    同时间范围的国家清单
```

`marketing-stats` 参数（camelCase 化）：`dateFrom`、`dateTo`、`granularity`、`taskType`、
`senderCountryIso2`、`recipientCountryIso2`、`accountType`、`deviceOs`、`shortLinkEnabled`。
其中 `deviceOs` 只接受 `android|iphone`，按发送时设备 OS 快照过滤。
`countries` 接收 `dateFrom`、`dateTo`、`granularity`，国家候选必须与当前可见窗口一致。

窗口校验后端也要做一遍：`day ≤ 90 天`、`hour ≤ 7 天`，超出返回 `40001`。
前端拦截不能替代后端校验——直接调接口就能绕过。

响应即 §2.9 的 `overview + items[]` 结构（`granularity` + 全局精确 `overview` + 国家对 `items`），字段 camelCase。

### 6.5 公网点击入口

```
GET /api/public/hl/{shortCode}     更新 recipient 点击累计/首触归因及 runtime 点击计数 → 302 跳转原始 URL
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
| 提交双图文 | `40001` | 不支持双图文 |
| 已开始任务被编辑 | `40901` | 任务已开始，仅可查看 |
| action 与当前状态不匹配 | `40901` | 任务当前状态不支持该操作 |
| 报价已过期 | `40901` | 报价已更新，请重新核对后提交 |
| 可用余额不足 | `40901` | 可用余额不足，无法冻结本次任务费用 |
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

**超链任务列表** — 汇总卡（任务数/发送总数/单钩/双钩/点击UV/点击率，基于当前页前端汇总）
+ 表格 + 行操作（启动/暂停/恢复/停止/编辑/查看/详情/复制）。名称使用服务端模糊搜索，不复制竞品
把分页临时放大到 200 的实现缺陷。页面仅手动刷新，并提示后端聚合数据约 1 分钟同步一次。

**新建/编辑抽屉** — 左侧 WhatsApp 实时预览、右侧四段表单（基础信息 / 消息内容 / 发送策略 / 受众与发布），
纯新建提交前弹「最后核对」二次确认（7 秒阅读倒计时）。余额、单价、运行模式、预计冻结金额、
数据包剩余数、匹配账号数、推广链接和深度追踪状态必须完整展示；计费 Gateway 是任务完整上线硬依赖。

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

## 9. 菜单与权限

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
tenant:hyperlink_task:view|create|edit|copy|action|export|attribution_sensitive
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
| **P0** | **协议能力补齐**（§4.3）：两条协议统一 CTA URL 单按钮校验和私聊路由 | `MessageTarget` 全局评审通过 | 真机验证：私聊单图文、普通 CTA URL 按钮、卡片 CTA URL 按钮在两条协议上真实送达并可点击 |
| P1 | 超链策略（表 + API + 页面） | 无 | 策略可增删改查，`options` 能被任务页消费 |
| P2 | 图片素材（加列 + 标签表 + API + 页面 + 存量回填） | 无 | 素材可上传/命名/打标签/删除保护；模板页图片字段切到素材库选择 |
| P3 | 超链任务（核心执行表 + 创建/编辑/action + recipient_claim 领号 + round/recipient 唯一发送链路） | **P0**、P1、P2 | 50 万号码可恢复领取，单图文与按钮任务真实发出，调度重入不重发/漏发 |
| P4 | 回流（`message.ack` 消费 + runtime/账号累计投影 + 收信人/账号/封号 Tab） | P3 | 单钩双钩计数与号码池状态最终一致，账号默认查询不全扫 recipient |
| P5 | 深度追踪（短码 + 公网跳转 + recipient 首触归因/趋势查询 + 深度归因导出） | P3、域名归属确定 | 点击 UV 与点击率可核对，10 万 recipient 的 72 小时趋势命中首访索引并满足延迟目标 |
| P6 | 市场分析（日/小时聚合表 + 回填/保留任务 + 3 个统计接口 + 页面） | P4、P5 | 两种粒度不扫事实大表且与任务明细可对账 |

每期开工前各自出一份实施计划（一期已确立的做法），逐期评审、逐期落地。

测试要求（沿用 AGENTS.md 与一期 §15）：

- **Flyway 迁移 SQL 测试**：每个新表/新列一个 `*MigrationSqlTest`，H2 内存库执行迁移并断言列、索引、CHECK。
- **Mapper H2 测试**：分页、筛选、聚合、幂等 upsert、唯一键冲突各一条；
  重点覆盖 ack 乱序（先 `delivery_ack` 后 `server_ack`）不回退状态。
- **Service 单测**：状态机全迁移矩阵、并发校验三条规则的边界值、
  `accountFilter` 白名单归一化（未知键、脏国家码、负数）、按钮协议准入；覆盖暂停时长冻结/继续续算、
  停止未提交 recipient 落 `TASK_STOPPED` 失败。
- **契约测试**：每个 Controller 一个 `*ContractTest`，锁定路径、字段名、错误码。
- **聚合口径对账测试**：同一批 recipient 数据，日聚合表求和结果与实时聚合结果必须一致
  （去重计数除外，去重计数按§2.9 的相加口径断言）。
- **容量与查询计划测试**：执行链构造 50 万 recipient 跨 3 个调度轮；访问趋势按当前业务上界构造 10 万
  recipient 并产生点击。用 `EXPLAIN ANALYZE` 断言 round due scan、recipient 认领、账号默认/时间范围排序
  和 72 小时趋势分别命中目标索引。
- **投影恢复测试**：模拟投影器在“事实已提交、projected 状态未回写”时宕机并重放，断言 runtime/round、
  账号累计不重复计数，reconciliation 能从事实重建；公网点击事务重放不得重复增加 recipient/runtime 点击数，
  且不得推进 `metrics_updated_at`。
- **作业恢复测试**：模拟 round 选号/派发 worker 租约过期接管、claim OWNED 后另一任务领取同代剩余号码，
  以及冻结/调整/结算/释放四种计费操作“远端成功、本地提交前宕机”，断言均按 owner/version/幂等键收敛。
- 覆盖率不低于 80%；没有真实输出不得声称通过。

---

## 11. 已决事项与外部交付依赖

已决：recipient 首触敏感环境保留 90 天，累计次数与首末访问时间长期保留；短链域名与买量用途物理隔离；任务名允许重名；导入方式直接查询
`account_credential.cred_format`，不向 account 加冗余列。这些不再进入产品确认。

| # | 外部交付依赖 | 影响 | 执行要求 |
|---|---|---|---|
| 1 | `MessageTarget` 语义中立化全局评审 | P0 硬前置 | 通过后再实施私聊路由 |
| 2 | `account_profile` 与 `account` 组合筛选索引全局评审 | P3 硬前置 | 共享画像和索引通过评审后再迁移，不向 account 复制业务字段 |
| 3 | `marketing_template_file` 加列全局评审 | P2 硬前置 | 新列全可空，先兼容再回填 |
| 4 | 真实计费提供方、价码和结算规则 | 决定任务能否启用 | 未接真实账务前不得宣称任务完成 |
| 5 | Android 拉群隐私、好友数与私聊路由 | 决定筛选覆盖率和双协议发送 | 单独实施并做真机回归，不隐藏控件 |
| 6 | 号源提供注册日期或号龄 | 决定注册天数覆盖率 | 未知保存 NULL，带条件时不匹配 |
| 7 | 多任务共用账号的真机压测 | 决定是否需要账号级令牌桶 | 先按已冻结并发上限灰度，出现叠加号损再立项 |

---

## 附：证据索引

| 结论 | 出处 |
|---|---|
| 新建只有 3 种消息类型 | `readable/assets/task-0vbZUOmq.js:1732-1747` |
| 按钮上限 1 且任务锁 CTA URL | `task-0vbZUOmq.js:2246-2275`，`locked-type="cta_url"` |
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
| 任务账号筛选提交完整、编辑回填漏字段缺陷 | `account-filter-modal-BXDIvipG.js:731-739`；`task-0vbZUOmq.js:1495-1529` |
| 接口面 | `router-CPQmbuR9.js:45960-46160, 46268-46320, 46739-46760, 46878-46895` |
| Web 协议通用按钮模型（任务仍锁 CTA URL 单按钮） | `armada-protocol/protocol-layer/src/messages/card-content.ts:19,37-60,161-176` |
| Baileys 对 native flow 按钮纯透传、无名称白名单 | `protocol-layer/node_modules/baileys/WAProto/WAProto.proto:2731-2737` |
| Android 只支持单个 `cta_url` | `whatsapp-server-feature-android-zhuan/internal/service/node/message_payload.go:123-152` |
| Android 单按钮入参模型 | `internal/service/entity/message.go:391-403` |
| Android 侧 `len(buttons)!=1` 断言与 Type 被丢弃 | `internal/armada/message_sender.go:562-576` |
| Android 发送路由是群语义 | `internal/service/app/group.go:258-291, 317` |
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
| Flyway 当前最高版本（本文冻结时） | `V156__hyperlink_data_package_full_replication.sql` |
