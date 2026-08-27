# 超链营销模块复刻设计（hylb → armada 控端）

- 日期：2026-08-27
- 状态：**设计草案，待评审**（尚未进入实施计划）
- 来源系统：`hylb.uiaxyk.com`（极量乌云 CRMS），存档于 `hylbuiaxykfrontendsource/`
- 目标系统：`armada`（后端）+ `wheel-saas-pure-web`（控端前端）+ `armada-protocol`（协议层）

---

## 1. 口径与边界

用户已明确三条口径，本设计据此展开：

| 层 | 口径 |
|---|---|
| 前端 | **精准复刻**——信息架构、字段、校验、交互流程、文案、指标口径逐屏对齐 |
| 接口 | **仿照**——资源划分、路径语义、查询参数、分页与聚合口径照抄 hylb |
| 后端 | **按 armada 已有能力适配实现**，不照搬 hylb 未知的服务端实现 |

已定：前端在现有控端 `wheel-saas-pure-web` 内用 **Element Plus 同构复刻**。

> hylb 前端栈为 **Naive UI + soybean-admin**（`n-data-table` / `n-dynamic-tags` / `useThemeVars`
> / `layout.base$view.xxx` 路由约定均可在存档产物中确证）；控端为 **Element Plus + pure-admin-thin**。
> 因此"精准"指**语义与行为精准**，不指 DOM 与像素一致。

### 1.1 存档的能力边界

存档只有构建产物，无 sourcemap、无 `.vue` 源文件、无后端源码（见 `hylbuiaxykfrontendsource/README.md`）。
因此本设计中：

- **可确证的**：路由表、菜单标题、接口路径与方法、请求字段名、前端校验规则、文案、指标计算公式、枚举取值。以上均从 `readable/assets/router-CPQmbuR9.js` 与各页面 chunk 反推得到，可复核。
- **不可确证的**：响应体的完整字段、后端表结构、后端算法（配额、限速、归因窗口）。这些一律按 armada 现有能力重新设计，并在本文标注为「适配设计」。

---

## 2. 复刻对象清单

### 2.1 路由与菜单（`router-CPQmbuR9.js:49546`）

一级菜单 `超链营销`（`/hyperlink`，icon `solar:link-bold-duotone`，order 1），六个子页：

| order | name | path | 标题 | icon |
|---|---|---|---|---|
| 0 | `hyperlink_task` | `/hyperlink/task` | 超链任务 | `solar:link-circle-bold-duotone` |
| 1 | `hyperlink_data` | `/hyperlink/data` | 数据包 | `solar:database-bold-duotone` |
| 2 | `hyperlink_templates` | `/hyperlink/templates` | 超链模板 | `solar:link-circle-bold-duotone` |
| 3 | `hyperlink_strategy` | `/hyperlink/strategy` | 超链策略 | `solar:tuning-2-bold-duotone` |
| 4 | `hyperlink_library` | `/hyperlink/library` | 图片素材 | `solar:gallery-wide-bold-duotone` |
| 5 | `hyperlink_analysis` | `/hyperlink/analysis` | 超链市场分析 | `solar:link-bold-duotone` |

> 存档中另有 `contact_hyperlink`（通讯录超链任务，挂在「通讯录营销」下，复用 chunk `hyperlink-BVNnqLDE.js`）。
> **不在本次范围内**，本设计只覆盖 `/hyperlink/*` 六页。

### 2.2 接口面（从 `router-CPQmbuR9.js:45960-46160, 46739-46892` 反推，完整）

**超链任务**
```
GET    /api/admin/hyperlink-tasks                      列表(params)
GET    /api/admin/hyperlink-tasks/{id}                 详情
POST   /api/admin/hyperlink-tasks                      新建 (multipart/form-data)
PUT    /api/admin/hyperlink-tasks/{id}                 编辑 (multipart/form-data)
POST   /api/admin/hyperlink-tasks/{id}/action          启动/暂停/恢复/停止  body:{action}
GET    /api/admin/hyperlink-tasks/{id}/recipients      收信人明细
GET    /api/admin/hyperlink-tasks/{id}/clicks          点击明细
GET    /api/admin/hyperlink-tasks/{id}/visit-trend     访问趋势
GET    /api/admin/hyperlink-tasks/{id}/ban-stats       封号原因分布
GET    /api/admin/hyperlink-tasks/{id}/recipients/export
GET    /api/admin/hyperlink-tasks/{id}/visit-trend/export
GET    /api/admin/hyperlink-tasks/{id}/click-attribution/export   深度归因
```
`multipart` 两个二进制字段：`link_preview_image`、`body_main_image`；其余字段以 `String(v)` 追加。

**超链策略**
```
GET/POST         /api/admin/hyperlink-strategies
PUT/DELETE       /api/admin/hyperlink-strategies/{id}
```

**超链模板**
```
GET/POST         /api/admin/hyperlink-templates
GET/PUT/DELETE   /api/admin/hyperlink-templates/{id}
POST             /api/admin/hyperlink-templates/{id}/copy
```

**数据包**
```
GET/POST         /api/admin/data-packages
GET/DELETE       /api/admin/data-packages/{id}
POST             /api/admin/data-packages/{id}/import
PUT              /api/admin/data-packages/{id}/name
GET              /api/admin/data-packages/{id}/phones
POST             /api/admin/data-packages/{id}/reset-failed
GET              /api/admin/data-packages/{id}/visit-trend
GET              /api/admin/data-packages/countries
```

**图片素材**
```
GET/POST         /api/admin/resource-assets
PUT/DELETE       /api/admin/resource-assets/{id}
GET              /api/admin/resource-assets/tags
```

**超链市场分析**
```
GET  /api/admin/hyperlink-tasks/marketing-stats
GET  /api/admin/hyperlink-tasks/marketing-stats/countries
GET  /api/admin/hyperlink-tasks/marketing-stats/accounts
GET  /api/admin/hyperlink-tasks/marketing-stats/accounts/export
GET  /api/admin/hyperlink-tasks/click-analysis/never-click
GET  /api/admin/hyperlink-tasks/click-analysis/never-click/export
GET  /api/admin/hyperlink-tasks/click-analysis/uv-ratio
GET  /api/admin/hyperlink-tasks/click-analysis/uv-ratio/export
```

### 2.3 关键枚举（已确证）

| 字段 | 取值 |
|---|---|
| `message_type` | 1=单图文 2=双图文 3=普通按钮 4=卡片按钮 |
| `type`（任务模式） | 1=即时 `instant` 2=持续运营 `rolling` 3=周期循环 `cycle` |
| `start_mode` | `now` / `scheduled`（配 `task_delay_minutes`） |
| 按钮 `name` | `cta_url` / `cta_copy` / `cta_call` / `quick_reply` |
| `wid_type` | `web5`=分身设备 / `native6`=主设备 |
| `import_mode` | `six_segment`=六段 / `full_param`=全参 |
| `source` | 0=买量 / 1=自登 |

### 2.4 `account_filter` 结构（已确证，`task-0vbZUOmq.js:1505-1528`）

```
country_iso2s[] | exclude_country_iso2s[] | continent
channel_ids[]   | group_ids[]
online_status   | account_type | platform | wid_type | import_mode
group_invite_allowed(bool)
friend_count_min/max | retention_days_min/max | register_days_min/max
created_at_from/to | logged_in_from/to
phone | import_no | protocol_id | error_code | error_desc
```
空对象 = 不限定（全部有效账号）。

### 2.5 指标口径（已确证，来自页面提示文案）

```
单钩率   = 单钩 ÷ 发送
双钩率   = 双钩 ÷ 单钩
点击率   = 点击 UV ÷ 单钩数
封号率   = 封号数 ÷ 使用号数
号均发量 = 单钩条数 ÷ 使用号数
落地率  ≈ 双钩率 + 20%     （页面明示为经验估算，非精确值）
```
页面同时明示：**双钩状态有延迟，仅作参考**；**点击 UV / 点击率仅统计开启了「深度追踪」的任务**。

---

## 3. 三边能力对账

### 3.1 armada 已有、可直接复用

| 能力 | 位置 | 用于 |
|---|---|---|
| link-card / button-card 发送 | `armada-protocol` `routes/messages.ts:152,181`，`messages/card-content.ts` 已实现 nativeFlow `cta_url`/`cta_copy`/`quick_reply` | 四种消息形态的底层发送 |
| 按 `jid` 发送（不区分群/私聊） | 同上 | 私聊发超链（`<phone>@s.whatsapp.net`） |
| `onWhatsApp` 号码探测 | `armada-protocol` `routes/status.ts:179` | 「未开通 WS」判定、`fail_404` |
| `message.ack` 事件 | `armada-protocol` `worker/event-bridge.ts:144`，状态映射 `server_ack`/`delivery_ack`/`read` | **单钩=server_ack、双钩=delivery_ack** |
| `MarketingTemplate` | `com.armada.marketing.model.entity.MarketingTemplate` + `LinkMode` + `MessageButton` + `promotionLink` + `imageFileId` | 超链模板的八成模型 |
| `marketing_template_file` | `V035`，`MarketingTemplateFileController` 上传 + 取内容 | 图片素材的存储底座 |
| promotion 短码/域名/公网入口基建 | `promotion_domain` / `promotion_channel` / `ChannelCodeGenerator`(8 位无歧义字符) / `PromotionChannelLinkBuilder` / `PromotionChannelPublicController` | 点击追踪跳转服务的现成范式 |
| 账号分组、协议管理、国家字典、IP 代理 | `account` / `platform.protocol` / `admin.CountryController` / `resource` | 账号筛选与任务执行的依赖 |
| Kafka 协议事件消费框架 | `com.armada.platform.kafka.consumer.*` | 接入 `message.ack` 的现成骨架 |

### 3.2 缺口（需新建）

| # | 缺口 | 说明 |
|---|---|---|
| 1 | **数据包** `data_package` | 号码包：TXT 导入、去重、覆盖/追加、国家分布、未使用统计、巴西号码风险拦截、单次上传条数上限 |
| 2 | **手机号维度的任务目标** | 现有 `marketing_task_target` 是**群维度**（`group_jid`/`group_link_id`）。超链是**手机号私聊**维度，是另一条目标链路，不可复用 |
| 3 | **点击追踪** | 公网跳转 + click 日志（UA / OS / 浏览器 / 设备 / 语言 / 访问次数 / 首末访问时间 / UV 去重） |
| 4 | **超链策略** `hyperlink_strategy` | 发送策略预设，供新建任务「引用策略」一键带入 |
| 5 | **素材库管理面** | 现有只有上传+取内容，缺 列表/命名/标签/引用计数/删除保护/批量上传 |
| 6 | **市场分析聚合** | 国家对（发信→被营销）、按时/按日粒度、never-click、uv-ratio |
| 7 | **账号筛选维度** | `AccountQuery` 缺 `friend_count` / `retention_days` / `register_days` / `wid_type` / `import_mode` / `continent` / `group_invite_allowed` |
| 8 | **`message.ack` 消费** | `ProtocolMessageEventConsumer` 目前只处理 `message.send_result_reported`（见其类注释），未消费 ack |

---

## 4. 数据模型方案（适配设计）

遵循 armada 红线：**只走 Flyway**；当前最高版本 `V139`，本模块从 **`V140`** 起。
所有表带 `tenant_id`、`created_at`/`updated_at`（epoch 毫秒）、软删 `deleted_at`。

### 4.1 数据包

```
data_package                号码包主表
  name, phone_count, used_num, unused_num, success_num, delivered_num,
  fail_num, fail_404_num, click_uv_num, created_by
data_package_phone          号码明细
  data_package_id, phone, country_iso2, used_at, send_status,
  fail_code, hyperlink_task_id, delivered_at, read_at
  UNIQUE(tenant_id, data_package_id, phone)      -- 包内去重
data_package_import         导入批次
  data_package_id, import_mode(overwrite/append), total_rows, valid_rows,
  invalid_rows, duplicated_rows, blocked_country_iso2s
```

### 4.2 超链模板 / 策略 / 素材

```
hyperlink_template
  name, message_type, title, content, link_description, promotion_link,
  buttons(JSON), card_text, link_preview_asset_id, body_main_asset_id,
  task_ref_count(冗余，删除保护用)
hyperlink_strategy
  name, type(1/2/3), task_interval, max_use_account, concurrent_num,
  account_max_send_num, account_send_concurrency,
  msg_interval_min_sec, msg_interval_max_sec,
  account_filter(JSON), enabled, remark
resource_asset
  name, file_id(→marketing_template_file.id), size_bytes, width, height,
  mime_type, ref_count
resource_asset_tag / resource_asset_tag_ref   标签与关联
```

> 素材沿用 `marketing_template_file` 做字节存储，`resource_asset` 只做**管理面**（命名、标签、引用计数），
> 避免再造一套文件存储。

### 4.3 超链任务

```
hyperlink_task
  name, type(1即时/2持续/3周期), status, start_mode, task_delay_minutes,
  task_planned_end_at, task_interval,
  data_package_id, data_package_name(快照),
  hyperlink_template_id, hyperlink_strategy_id,
  message_type, title, content, link_description, promotion_link,
  buttons(JSON), card_text, link_preview_asset_id, body_main_asset_id,
  account_filter(JSON), max_use_account, concurrent_num,
  account_max_send_num, account_send_concurrency,
  msg_interval_min_sec, msg_interval_max_sec,
  use_short_link(深度追踪开关),
  -- 统计冗余
  recipient_total, send_total, success_num, delivered_num, fail_num,
  fail_404_num, banned_count, click_uv_num, used_account_count,
  started_at, last_send_at, finished_at, execution_duration_sec

hyperlink_task_recipient        手机号维度目标（对应 /recipients）
  hyperlink_task_id, data_package_phone_id, recipient_phone,
  recipient_country_iso2, account_id, sender_phone, sender_country_iso2,
  protocol_id, send_status, fail_code, fail_reason,
  short_code, success_at, delivered_at, failed_at,
  click_count, first_visit_at, last_visit_at
  UNIQUE(tenant_id, hyperlink_task_id, recipient_phone)
  UNIQUE(short_code)                              -- 深度追踪时才生成

hyperlink_click                 点击流水（对应 /clicks、visit-trend、深度归因）
  hyperlink_task_id, recipient_id(可空), recipient_phone,
  recipient_country_iso2, short_code,
  user_agent, browser, os, device, language, ip_country_iso2,
  visit_at, visit_count_order
  KEY(tenant_id, hyperlink_task_id, visit_at)

hyperlink_task_ban              封号记录（对应 /ban-stats）
  hyperlink_task_id, account_id, ban_reason_code, ban_reason, banned_at
```

### 4.4 分析聚合

按日/按小时预聚合两张表，供「超链市场分析」在 90 天 × 国家对维度下秒回：

```
hyperlink_stat_hourly / hyperlink_stat_daily
  stat_time, sender_country_iso2, recipient_country_iso2,
  account_type, task_type, use_short_link,
  send_total, success_num, delivered_num, click_uv_num,
  banned_account_count, used_account_count
```

---

## 5. 接口方案

**采纳口径：资源划分与查询语义照抄 hylb，命名规范服从 armada 现有约定。**

| 维度 | hylb | armada 本模块 |
|---|---|---|
| 路径前缀 | `/api/admin/xxx` | `/api/hyperlink-tasks` 等（对齐 `/api/marketing-tasks`、`/api/promotion-channels`） |
| 字段命名 | `snake_case` | `camelCase`（对齐现有全部 Controller） |
| 响应包络 | 未知 | `ApiResponse<T>`（现有约定） |
| 资源、方法、参数、分页、聚合口径 | — | **完全照抄** |

理由：控端 `src/api/*.ts` 本来就承担后端↔前端字段映射（`marketing-template.ts` 即为范例：
`BackendMarketingTemplate` → 前端类型）。让后端服从 armada 一套命名，映射成本落在前端 api 层一处，
比引入第二套 API 命名规范便宜得多。

> **待确认（不阻塞设计，阻塞实施）**：若你要求连路径与字段名也逐字复刻（`/api/admin/hyperlink-tasks` +
> snake_case），需在 armada 引入独立的 snake_case 序列化配置与 `/api/admin` 前缀分组。技术可行，
> 但会在同一后端里长期并存两套 API 规范。见 §9。

公网点击入口（新增，仿 `PromotionChannelPublicController` 模式）：
```
GET /api/public/hl/{shortCode}     记录点击 → 302 跳转到 promotion_link
```

---

## 6. 后端适配方案

### 6.1 消息发送

复用协议层现成能力，**不新增协议层消息接口**：

| message_type | 协议层接口 | 说明 |
|---|---|---|
| 1 单图文 | `POST /v1/messages/link-card` | 标题 + 描述 + 缩略图 + URL |
| 2 双图文 | `POST /v1/messages/image` + `link-card` | 正文主图 + 链接卡片，两条消息 |
| 3 普通按钮 | `POST /v1/messages/button-card` | nativeFlow buttons，无 header 图 |
| 4 卡片按钮 | `POST /v1/messages/button-card` | 带 `thumbnail` header 图 |

`jid` 取 `<phone>@s.whatsapp.net`。按钮映射：
`cta_url`→`{type:'link'}`、`cta_copy`→`{type:'copy'}`、`quick_reply`→`{type:'quick'}`。

> `cta_call`（电话按钮）在 hylb 前端有校验（「请输入电话号码」），但协议层 `card-content.ts`
> 当前 `ButtonCardButtonType` 只有 `link|copy|quick`。**需协议层扩展**，或本期在前端禁用该按钮类型。见 §9。

### 6.2 单钩 / 双钩

1. 协议层已发布 `message.ack`（`worker/event-bridge.ts:144`），无需改动。
2. armada 侧扩展 `ProtocolMessageEventConsumer`，新增 `message.ack` 分支。
3. 映射：`server_ack` → 单钩（`success_num`），`delivery_ack` → 双钩（`delivered_num`），`read` → 已读。
4. 通过 `message.key.id` 关联回 `hyperlink_task_recipient`（发送时落库协议消息 ID）。
5. 页面已明示"双钩有延迟"，因此**最终一致即可**，不做同步等待。

### 6.3 号码有效性

发送前批量调 `POST /v1/status`（`onWhatsApp`）判定「未开通 WS」，落 `fail_404`。
批量粒度与频率需按协议层限速能力定，避免探测本身触发风控。

### 6.4 点击追踪（深度追踪）

- 任务开启 `use_short_link` 时，**为每个收件人生成独立 `short_code`**（复用 `ChannelCodeGenerator`，8 位无歧义字符集），落 `hyperlink_task_recipient.short_code`。
- 发送时把 `promotion_link` 替换为 `http://{domain}/hl/{shortCode}`。
- 公网接口记录 `hyperlink_click`（UA 解析出 browser/os/device、`Accept-Language` 取 language），再 302 跳原始链接。
- `click_uv_num` = 去重 `recipient_id` 计数；`visit_count` = 该收件人点击次数。
- 未开启深度追踪的任务，直接发原始 `promotion_link`，无点击数据（与 hylb 行为一致）。

**容量提醒**：数据包可达数十万号码，每号一个短码 + 点击流水，`hyperlink_click` 是本模块最大的表，
需要按 `hyperlink_task_id` + 时间做索引与归档策略。

### 6.5 账号筛选补齐

`AccountQuery` 新增：`friendCountMin/Max`、`retentionDaysMin/Max`、`registerDaysMin/Max`、
`widType`、`importMode`、`continent`、`groupInviteAllowed`、`excludeCountryIso2s`。
需先确认 `account` 表是否已有好友数、存活天数、注册天数的落库字段——**若无，需先补采集链路，否则这几个筛选是空壳**。见 §9。

### 6.6 调度

沿用 `marketing` 模块的轮次调度范式（`MarketingTaskScheduler` + Kafka 轮次派发），
三种任务模式映射：

- **即时**：一次性按计划发完整个数据包，速度最快
- **持续运营**：到指定时间或数据包发完结束（先到为准），期间符合筛选的新号自动加入
- **周期循环**：每隔 `task_interval` 分钟跑一轮，每轮用 `max_use_account` 个账号，手动停止

---

## 7. 前端复刻方案

目录（对齐控端现有 `src/views/<域>/<页>` 约定）：

```
src/views/hyperlink/
  task/        列表 + 新建/编辑抽屉（左 WhatsApp 实时预览 / 右表单）+ 详情 4 Tab
               （收信人 / 发信账号统计 / 点击明细 / 封号统计）
  data/        数据包列表 + TXT 导入向导 + 号码明细抽屉 + 点击分析
  templates/   模板列表 + 左预览右表单编辑
  strategy/    策略列表 + 编辑弹窗
  library/     素材库网格 + 批量上传 + 标签编辑
  analysis/    市场分析（筛选 + 表格/图表切换 + 国家对汇总）
src/api/hyperlink-task.ts | hyperlink-template.ts | hyperlink-strategy.ts
        | data-package.ts | resource-asset.ts | hyperlink-analysis.ts
src/components/hyperlink/WaMessagePreview.vue    WhatsApp 真机预览（四种形态）
```

复刻要点：

1. **WhatsApp 实时预览**是本模块最强的产品特征（任务页与模板页都有），需按 `message_type` 四态渲染，含气泡、缩略图、按钮组、底部小字。存档中对应 `wa-message-preview-*.js` / `wa-doodle-*.js` / `wa-tick-*.js`，可作视觉参考。
2. **文案逐字复刻**——所有提示、校验错误、指标说明 tooltip 均已从存档提取，直接使用。
3. **校验规则逐条复刻**（已确证，例）：
   - 周期模式：`task_interval ≥ 1` 分钟；`max_use_account ≥ 1`；`concurrent_num ≤ max_use_account`
   - 持续运营：`task_planned_end_at` 需晚于当前至少 1 分钟
   - 延后执行：`task_delay_minutes > 0`
   - 按钮消息：至少 1 个按钮
   - 即时任务：需至少 1 个可用账号才能启用（预发布模式可为 0）
4. 遵守控端红线：`.vue` 单文件 ≤ 600 行（超 400 行拆 composable/子组件）、页面禁止直接 axios、
   禁止自绘表格/弹窗、菜单以 `/api/tenant/me/menus` 为最终来源。
5. 菜单与权限：新增 `hyperlink` 一级菜单 + 6 个子菜单，走 `sys_menu` 与 RBAC（`V071`）。

---

## 8. 分期建议

| 期 | 内容 | 依赖 |
|---|---|---|
| P0 | 数据包 + 图片素材 + 超链模板（三个"物料"页） | 无外部依赖，可立即开工 |
| P1 | 超链策略 + 超链任务（创建/编辑/列表/action）+ 私聊发送链路 | P0；协议层 `cta_call` 决策 |
| P2 | 单钩双钩回流（`message.ack` 消费）+ 收信人/发信账号/封号 Tab | P1 |
| P3 | 深度追踪（短码 + 公网跳转 + 点击明细 + 访问趋势 + 深度归因导出） | P1；需确定公网域名归属 |
| P4 | 超链市场分析（预聚合表 + 6 个统计接口 + 图表页） | P2、P3 |

P0 三页彼此独立、无协议层依赖，是最稳的起手。

> 本文是**总设计**，不是实施计划。整个模块体量超出单份实施计划能覆盖的范围，
> 因此每一期（P0…P4）在开工前各自出一份实施计划，逐期评审、逐期落地。

---

## 9. 待确认项

以下问题会改变实施方案，需在进入实施计划前定：

1. **接口命名口径**——`/api/hyperlink-tasks` + camelCase（推荐，§5），还是逐字复刻 `/api/admin/hyperlink-tasks` + snake_case？
2. **`cta_call` 电话按钮**——协议层 `card-content.ts` 当前不支持。扩展协议层，还是本期前端禁用该类型？
3. **账号筛选字段落库**——`account` 表是否已有好友数 / 存活天数 / 注册天数？若无，这三组筛选需要先补采集链路。
4. **点击追踪域名**——复用 `promotion_domain` 已有域名池，还是超链单独一套域名？（涉及被封风险隔离）
5. **数据包上传上限**——hylb 有"单次最大 N 条"限制但存档中未固化具体数值，需按 armada 实际承载定。
6. **巴西号码拦截**——hylb 有硬编码的国家风险拦截（"某某的数据包禁止上传号码"），是否复刻这条业务规则？
7. **计费/单价**——任务页有"超链单价 / 普通模式单价 / 超级模式单价 / 当前余额 / 估算落地率"，涉及 armada 的 `balances` / `consume-stats` 体系。本期是否纳入？
8. **`未开通 WS` 探测频率**——批量 `onWhatsApp` 的粒度与限速，需协议层给出安全阈值。

---

## 附：证据索引

| 结论 | 出处 |
|---|---|
| 路由与菜单 | `readable/assets/router-CPQmbuR9.js:49546-49615`、`:10690-10696` |
| 接口面 | `readable/assets/router-CPQmbuR9.js:45960-46160, 46739-46892` |
| `account_filter` 结构 | `readable/assets/task-0vbZUOmq.js:1505-1528` |
| `message_type` 枚举 | `readable/assets/task-0vbZUOmq.js:1168-1176` |
| 任务模式枚举 | `readable/assets/task-0vbZUOmq.js:1537` |
| 指标口径文案 | `readable/assets/analysis-DA45fcKJ.js`、`task-0vbZUOmq.js` |
| 协议层卡片能力 | `armada-protocol/protocol-layer/src/messages/card-content.ts` |
| 协议层消息路由 | `armada-protocol/protocol-layer/src/routes/messages.ts:141-221` |
| `onWhatsApp` | `armada-protocol/protocol-layer/src/routes/status.ts:179` |
| `message.ack` 发布与映射 | `armada-protocol/protocol-layer/src/worker/event-bridge.ts:134-153, 263-277` |
| armada 现有 ack 消费缺口 | `com/armada/platform/kafka/consumer/message/ProtocolMessageEventConsumer.java:24,32` |
| 群维度目标（不可复用） | `com/armada/marketing/model/entity/MarketingTaskTarget.java:17-20` |
| 模板既有模型 | `com/armada/marketing/model/entity/MarketingTemplate.java` |
| 短码生成器 | `com/armada/promotion/channel/support/ChannelCodeGenerator.java` |
| Flyway 当前最高版本 | `armada-api/src/main/resources/db/migration/V139__*.sql` |
| 控端技术栈与红线 | `wheel-saas-pure-web/AGENTS.md` |
