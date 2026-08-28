# 超链营销模块复刻设计（hylb → armada 控端）

- 日期：2026-08-27
- 状态：**历史初稿，仅保留早期全景取证，不得直接实施超链任务**
- 来源系统：`hylb.uiaxyk.com`（极量乌云 CRMS），存档于 `hylbuiaxykfrontendsource/`
- 目标系统：`armada`（后端）+ `wheel-saas-pure-web`（控端前端）+ `armada-protocol`（协议层）

> **失效声明（2026-08-28）**
>
> 本文形成于超链任务逐块复核之前，其中任务表结构、通用多按钮、短码归属、计费删除、账号筛选和
> Android 能力判断均含已被推翻的早期结论。**超链任务禁止按本文 §4.3、§6、§7、§9 开发。**
> 唯一实施入口是：
>
> 1. `2026-08-27-hyperlink-task-competitor-parity-detailed-design.md`：任务页面、接口与业务行为；
> 2. `2026-08-27-hyperlink-task-strategy-asset-analysis-design.md`：四菜单总览与跨菜单契约；
> 3. `2026-08-28-hyperlink-task-shared-contract.md` v1.1：六份任务方案共用的 HTTP、DTO、枚举、指标、权限和错误合同；
> 4. `docs/business/hyperlink-marketing-data-model.md` §4/§8：最终按工作负载拆分的 10 张任务表与共享账号画像。
>
> 2026-08-28 对这 10 张表逐表复核后，表数仍为 10；新增的最终约束包括 runtime 当前运行段、round worker
> 租约、计费待操作恢复字段、claim 在 OWNED 后释放代次操作锁、停止未提交 recipient 记
> `TASK_STOPPED` 失败，以及 account_stat 不重复保存 account_usage 已有的账号展示快照。以上仍只以三份
> 当前设计文档为准，不回写下方历史草案结构。
>
> 保留本文旧正文仅用于追溯“为什么发生纠偏”，旧结论没有实施效力。

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
  click_count, first_visit_at, last_visit_at,
  first_visit_ip/user_agent/browser/os/device/language/country
  UNIQUE(tenant_id, hyperlink_task_id, recipient_phone)
  UNIQUE(short_code)                              -- 深度追踪时才生成

# 已废弃：原拟建 hyperlink_task_ban；最终将首次封号/失效字段并入 hyperlink_task_account_usage，
# /ban-stats 按任务账号用量表分组。
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

> **已定（2026-08-27）**：采纳上表口径——`/api/hyperlink-tasks` + camelCase，与现有 Controller
> 一致，不引入第二套 API 规范。字段映射统一落在前端 `src/api/*.ts`。

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

**上表只对 Web 协议成立。** armada 有两条协议链路，由 `account.protocol_id` 经
`ProtocolBackend.fromProtocolId()` 分流，二者消息能力差距很大：

| | Web（`armada-protocol`，Node/Baileys） | Android（`whatsapp-server-feature-android-zhuan`，Go） |
|---|---|---|
| 现有按钮能力 | `link` / `copy` / `quick`，最多 3 个（`messages/card-content.ts`） | **只有 1 个 `cta_url`** |
| 出处 | `card-content.ts:37 nativeFlowButton()` | `internal/service/node/message_payload.go:123` 注释原文："Android 营销按钮只生成一个 cta_url native-flow button" |
| 支持的超链消息形态 | 1/2/3/4 全部 | 仅 1（单图文）与退化的 3 |

因此**真正的缺口不是 `cta_call`，而是 Android 协议的按钮能力整体落后于超链模板的四种形态**：
走 Android 协议的账号跑超链任务时，形态 2/3/4 发不出来。

扩展可行性（已核实）：

- **wire format 不是障碍**。`internal/service/waproto/WAWebProtobufsE2E.proto` 已含
  `CallButton`(2232)、`HydratedCallButton`(1509)、`nativeFlowCallButtonPayload`(2079)；
  `NativeFlowMessage.Buttons` 本身就是 slice。
- **Web 侧扩展成本极低**：`nativeFlowButton()` 直接产出 `{name, buttonParamsJson}`，
  Baileys 纯透传，加 `cta_call` 是加一个 case（约 10 行）+ 类型联合加一个 `'call'`。
- **Android 侧要先改入参模型**：现在是 `link.ButtonText` / `link.Url` 的单按钮结构，
  需改成按钮数组才能支持多按钮与 copy/quick/call。

**决策项**（见 §9）：本期是补齐 Android 协议按钮能力，还是限制超链任务只能选 Web 协议账号。

### 6.2 单钩 / 双钩

1. 协议层已发布 `message.ack`（`worker/event-bridge.ts:144`），无需改动。
2. armada 侧扩展 `ProtocolMessageEventConsumer`，新增 `message.ack` 分支。
3. 映射：`server_ack` → 单钩（`success_num`），`delivery_ack` → 双钩（`delivered_num`），`read` → 已读。
4. 通过 `message.key.id` 关联回 `hyperlink_task_recipient`（发送时落库协议消息 ID）。
5. 页面已明示"双钩有延迟"，因此**最终一致即可**，不做同步等待。

### 6.3 号码有效性：建议不做预探测

协议层**已有**批量接口 `POST /v1/accounts/check-whatsapp`（`routes/status.ts:175`），
body `{accountId, phones[]}`，内部 `sock.onWhatsApp(...phones)` 一次查一批。

**但它目前完全没有限速**：协议层的 `operation-gate`（Redis 锁 + TokenBucket）只挂在
建群链路上（`commands/normal-group-creation-executor.ts`），`messages` 与 `check-whatsapp`
都未接入。

风险是具体的：`onWhatsApp` 走 USync 查询，是 WhatsApp 明确的风控点。短时间大批量查询
陌生号码是典型扫号特征，会直接触发封号。安全阈值无法凭代码推断，**只能实测**，需要定三个量：
单账号每分钟查询上限、单次 batch 大小、多账号之间如何摊配额。

**本设计采用：不做全量预探测，改为「发送即探测」。**
直接发送，失败时由协议层返回的错误码判定「未开通 WS」，落 `fail_404`。
理由：

1. 失败码处理路径本来就必须实现，不增加代码面。
2. 零额外风控暴露——不产生任何本不该有的 USync 查询。
3. 代价仅为失败那条消息浪费一次发送配额，可接受。

若产品坚持要发送前预探测，前置条件是：`check-whatsapp` 先接入 `operation-gate`，
再灰度实测出阈值。**在阈值实测出来之前不得启用批量预探测。**

### 6.4 点击追踪（深度追踪）

- 任务开启 `use_short_link` 时，**为每个收件人生成独立 `short_code`**（复用 `ChannelCodeGenerator`，8 位无歧义字符集），落 `hyperlink_task_recipient.short_code`。
- 发送时把 `promotion_link` 替换为 `http://{domain}/hl/{shortCode}`。
- 公网接口锁定 recipient，累计次数并在首访时保存 IP/UA/browser/os/device/language/country，同事务原子更新
  runtime 的任务 UV/PV，再 302 跳原始链接；趋势页按 recipient 首访时间直接聚合。
- `click_uv_num` = 去重 `recipient_id` 计数；`visit_count` = 该收件人点击次数。
- 未开启深度追踪的任务，直接发原始 `promotion_link`，无点击数据（与 hylb 行为一致）。

竞品没有逐次点击历史入口，因此不保存每一次访问；首触敏感环境保留 90 天，累计次数与首末时间长期保留。

### 6.5 账号筛选补齐

逐项对账结果见数据模型文档 §8。要点：

- **设备类型（主设备/分身）不需要新字段**——`ProtocolBackend.fromProtocolId(account.protocol_id)`
  已经区分 `ANDROID`（主设备 native6）与 `WEB`（分身 web5）。
- **存活天数**由 `now - account.created_at` 派生，不落列。
- **好友数 / 是否允许拉群**需协议层**主动查**（WhatsApp 不主动推），且两条协议口径不同：
  Android 侧联系人已落 MySQL 可直接 COUNT；Web 侧靠 app-state 被动同步，需新增落库计数。
  拉群隐私 Web 侧有 `sock.fetchPrivacySettings()`，Android 侧能力待确认。
- **注册天数 WhatsApp 不暴露**，两条协议都拿不到，需产品重新定义该字段含义。

`AccountQuery` 新增：`friendCountMin/Max`、`retentionDaysMin/Max`、`protocolBackend`、
`importMode`、`continent`、`groupInviteAllowed`、`excludeCountryIso2s`。

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
| P1 | 超链策略 + 超链任务（创建/编辑/列表/action）+ 私聊发送链路 | P0；**Android 协议按钮能力决策（§6.1）——这是 P1 的硬前置** |
| P2 | 单钩双钩回流（`message.ack` 消费）+ 收信人/发信账号/封号 Tab | P1 |
| P3 | 深度追踪（短码 + 公网跳转 + 点击明细 + 访问趋势 + 深度归因导出） | P1；需确定公网域名归属 |
| P4 | 超链市场分析（预聚合表 + 6 个统计接口 + 图表页） | P2、P3 |

P0 三页彼此独立、无协议层依赖，是最稳的起手。

> 本文是**总设计**，不是实施计划。整个模块体量超出单份实施计划能覆盖的范围，
> 因此每一期（P0…P4）在开工前各自出一份实施计划，逐期评审、逐期落地。

---

## 9. 待确认项

### 9.1 已决（2026-08-27）

| # | 决策 |
|---|---|
| 1 | **接口命名**：`/api/hyperlink-tasks` + camelCase，与现有 Controller 一致（§5） |
| 6 | **协议缺失能力全部补齐，不做降级**：Web 按 Baileys 加 `cta_call`，Android 照搬 Web 逻辑改按钮数组；私聊目标两条协议都补。详见 `2026-08-27-hyperlink-task-strategy-asset-analysis-design.md` §4.3 |
| 2 | **数据包单次导入上限 5000 行**；总量不限；覆盖模式清空+导入同事务 |
| 3 | **不做国家风险拦截**，对应两列不落 |
| 4 | **计费/单价整块不做**——armada 无计费体系（见 9.3 勘误） |
| 5 | **不做批量预探测**，改「发送即探测」（§6.3） |

### 9.2 未决

| # | 问题 | 影响 |
|---|---|---|
| ~~1~~ | ~~**Android 协议按钮能力**（§6.1）~~ | **已决（见 9.1-6）：补齐。** 唯一遗留的是 `cta_call` 的 native flow 字段名需真机验证 |
| 2 | **`hyperlink_template` 独立 vs 归一进 `marketing_template`** | 决定 P0 是否要动生产中的群组营销 |
| 3 | **`marketing_template_file` 改名为 `resource_asset` 的影响面**是否接受 | 不接受则退化为两张图片表，违反数据模型规范一.2 |
| 4 | **「注册天数」的产品定义** | WhatsApp 不暴露注册时间；含义未定则该筛选项不做 |
| 5 | **账号画像同步策略**——触发时机与刷新频率 | 主动查协议本身有风控暴露，高频刷新伤号 |
| 6 | **点击追踪域名隔离**（见 9.4） | 共用域名时超链被封会连带买量落地页一起挂 |
| ~~7~~ | ~~逐次点击流水的归档/分区策略~~ | **已决：不建逐次点击表；recipient 首触敏感环境保留 90 天** |

### 9.3 勘误

本文早先写的「涉及 armada 的 `balances` / `consume-stats` 体系」**是错的**。
`/api/admin/balances`、`/api/admin/consume-stats`、`/api/admin/recharges` 是 **hylb 的接口**。

已核实：armada 的 Java 代码与全部 Flyway 迁移中**没有任何 balance / recharge 相关的表或类，
armada 无计费体系**。因此超链任务页的「当前余额 / 超链单价 / 普通模式单价 / 超级模式单价 /
估算落地率」在 armada 侧没有可对接的依赖，本期整块不做。若要做，须先独立立项建计费体系。

### 9.4 点击追踪域名隔离（问题 9.2-6 的展开）

深度追踪要把 `promotion_link` 替换为 `http://{域名}/hl/{短码}`，用户点击后先落
recipient 点击累计/首触归因并原子更新 runtime 点击计数，再 302 跳转。这个域名必须是公网可达的自有域名。

armada 已有 `promotion_domain` 表管理买量落地页域名池。问题是超链短链复用这批域名还是单开。

**风险**：超链短链会被大批量发进 WhatsApp 私聊，域名极易被 WhatsApp 判定为 spam 并拉黑。
若与买量落地页共用域名，**超链把域名跑挂会连带买量落地页一起不可用**。

**建议**：单开一批域名做物理隔离，但**不新建域名表**——给 `promotion_domain` 加一个用途
判别列即可，避免出现第二张域名表（数据模型规范一.2）。

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
| Web 协议卡片能力 | `armada-protocol/protocol-layer/src/messages/card-content.ts:37` |
| Web 协议消息路由 | `armada-protocol/protocol-layer/src/routes/messages.ts:141-221` |
| Android 协议只支持单个 cta_url | `whatsapp-server-feature-android-zhuan/internal/service/node/message_payload.go:123-152` |
| WhatsApp 电话按钮 wire format | `whatsapp-server-feature-android-zhuan/internal/service/waproto/WAWebProtobufsE2E.proto:1509, 2079, 2232` |
| 两条协议链路分流 | `com/armada/platform/protocol/model/enums/ProtocolBackend.java:23,43` |
| 批量号码探测接口 | `armada-protocol/protocol-layer/src/routes/status.ts:175-187` |
| 限速门只挂建群 | `armada-protocol/protocol-layer/src/rate-limit/operation-gate.ts`；引用方仅 `commands/normal-group-creation-executor.ts` |
| Baileys 隐私设置可读 | `protocol-layer/node_modules/baileys/lib/Socket/chats.d.ts:33 fetchPrivacySettings` |
| Android 侧联系人已落库 | `whatsapp-server-feature-android-zhuan/internal/service/axolotl/store/contacts.go:59 LoadContacts` |
| `message.ack` 发布与映射 | `armada-protocol/protocol-layer/src/worker/event-bridge.ts:134-153, 263-277` |
| armada 现有 ack 消费缺口 | `com/armada/platform/kafka/consumer/message/ProtocolMessageEventConsumer.java:24,32` |
| 群维度目标（不可复用） | `com/armada/marketing/model/entity/MarketingTaskTarget.java:17-20` |
| 模板既有模型 | `com/armada/marketing/model/entity/MarketingTemplate.java` |
| 短码生成器 | `com/armada/promotion/channel/support/ChannelCodeGenerator.java` |
| Flyway 当前最高版本 | `armada-api/src/main/resources/db/migration/V139__*.sql` |
| 控端技术栈与红线 | `wheel-saas-pure-web/AGENTS.md` |
