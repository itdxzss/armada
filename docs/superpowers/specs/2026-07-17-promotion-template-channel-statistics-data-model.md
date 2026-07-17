# 推广模板、渠道管理与渠道统计数据模型设计

> 状态：已 brainstorm 定稿，待用户复核书面规格后进入 writing-plans（2026-07-17）  
> 需求来源：`D:/documents/买号上量系统_模版管理_渠道管理_渠道统计需求文档_V1.1_H5抽奖拉群合并版 - 副本.docx`  
> 规范来源：`.harness/rules/数据模型规范.md`、`.harness/wiki/数据模型.md`  
> 实施范围：落地页模板管理、推广渠道管理、渠道访问/登录/成功/解绑事件、渠道日统计和广告补录；不建设抽奖、奖励、OTP、分享达标流程表。

---

## 1. 验收摘要

### 1.1 目标

- 用真实 MySQL/Flyway 表替代模板、渠道和渠道统计页面的静态数据。
- 为渠道访问、登录请求、登录成功和账号解绑建立可幂等、可重算的原始事实。
- 为渠道统计建立日级查询投影，避免每次页面查询扫描全部事件。
- 为广告消耗、展示、点击、手续费率和其他费用建立不可覆盖历史的版本记录。
- 兼容现有 `account.number_source` 和 `account.channel_name`，同时补充稳定的渠道 ID 关联。
- 将 Pixel/CAPI Token 与渠道主数据垂直拆分，只保存密文并保留探测审计。
- 交付一张包含全部表、全部字段、字段含义和示例值的 Mermaid ER 图，并提供逐表字段字典。

### 1.2 非目标

- 不复用或改造现有 `marketing_template`。该表是群营销消息素材，不是落地页模板。
- 不建设 H5 抽奖、奖品库存、奖励领取、OTP/配对码、分享达标和推广人员结算表。
- 不在本次建设渠道统计导入、导出和第三方广告平台自动拉数任务。
- 不强制为历史 `account.channel_name` 回填渠道 ID。
- 不增加数据库物理外键；沿用 Armada 的逻辑关联、Service 校验和租户拦截器。

### 1.3 硬性验收条件

- Flyway 版本使用当前最新 V057 之后的唯一版本号。
- 所有新业务表包含 `tenant_id`，由租户拦截器隔离。
- 每个 `CREATE TABLE` 字段都必须有中文 `COMMENT`，且同时说明业务含义和至少一个示例值；例如：`渠道公开短码,例如 A8K2M9QX`。
- 枚举字段的 `COMMENT` 必须列出全部当前枚举值并给出示例。
- 所有时间字段除自然日 `DATE` 外使用 `BIGINT` epoch 毫秒，并在注释中给出示例。
- 所有金额和费率使用 `DECIMAL`，禁止 `FLOAT/DOUBLE`。
- 所有软删除唯一性使用 `is_active` 生成列，不能依赖含 `NULL deleted_at` 的普通唯一索引。
- 每个关键查询都有满足最左匹配原则的索引，并有 DbTest 校验索引列顺序。
- 原始事件通过 `tenant_id + event_key` 唯一约束保证幂等。
- 补录版本通过“业务粒度 + revision_no”和“业务粒度 + 当前版本生成列”两组唯一约束防止覆盖和双当前版本。
- Token 不得以明文列存储，不得通过列表、详情、审计或错误字段返回原文。
- 迁移同时提供 `.harness/changes/<主题>/db-migrations.sql` 和 `rollback.sql`。
- 更新 `.harness/wiki/数据模型.md`，并新增业务数据模型文档和全字段 ER 图。

---

## 2. 现状与兼容约束

### 2.1 现有营销模板不能复用

当前 `marketing_template` 承载群营销消息内容，字段包括消息类型、正文、按钮和图片。新需求中的模板是系统预置落地页程序，核心字段是模板编码、预览资源和支持参数。两者生命周期、消费者和数据内容不同，复用会造成一个表同时承载两个聚合，因此新增 `promotion_landing_template`。

### 2.2 账号渠道字段兼容

现有 `account` 已有：

- `number_source`：1=买量，2=裂变，3=自购。
- `channel_name`：推广渠道名称快照，目前为 `VARCHAR(64)`。

本次增加 `promotion_channel_id` 作为稳定关联，并把 `channel_name` 扩展为 `VARCHAR(128)`。成功入库时双写：

- 基础推广：`number_source=1`。
- 裂变推广：`number_source=2`。
- `promotion_channel_id` 写渠道主键。
- `channel_name` 写入库时名称快照。

历史数据允许 `promotion_channel_id IS NULL`，原筛选和展示继续可用。

### 2.3 国家主数据复用

目标国家和预选区号复用现有平台表 `country`：

- 渠道目标国家使用 `target_country_id`。
- 预选区号使用 `preselected_country_id`，区号从 `country.phone_prefix` 读取。
- `target_country_id=NULL` 表示“混合（不限国家）”。
- 统计和事件保存 `country_code` 快照，使用 ISO2；无法识别时保存 `ZZ`，避免国家主数据后续停用改变历史维度。

---

## 3. 方案选择

### 3.1 方案 A：模板、渠道、日统计三张宽表

优点是实现快。缺点是 Token 与主数据混存、域名跨模板约束无法由唯一键保证、补录覆盖历史、统计口径变更后不能重算。拒绝。

### 3.2 方案 B：主数据 + 事件事实 + 日投影 + 版本补录（采用）

使用八张表分离模板、域名、渠道、敏感配置、原始事件、日统计、广告补录版本和审计。当前查询路径清晰，也为口径调整保留原始事实。采用。

### 3.3 方案 C：只存原始事件，所有统计实时计算

灵活，但管理页每次区间查询都需要扫描事件和去重，MySQL 单体下性能和实现复杂度不可控。拒绝。

---

## 4. 表与聚合设计

### 4.1 `promotion_landing_template`（推广落地页模板）

职责：保存租户可使用的系统预置落地页模板、预览资源、支持参数和运营备注。

字段：

| 字段 | 类型 | 可空/默认 | 含义与示例 |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | 模板主键，例如 `1001` |
| `tenant_id` | BIGINT | NOT NULL | 租户 ID，例如 `1` |
| `template_code` | VARCHAR(64) binary collation | NOT NULL | 稳定程序编码，例如 `base_sex` |
| `template_name` | VARCHAR(128) | NOT NULL | 运营展示名称，例如 `基础约会-投男粉` |
| `preview_uri` | VARCHAR(512) | NULL | 预览资源 URI，例如 `/preview/base_sex.png` |
| `supported_params` | JSON | NULL | 支持参数数组，例如 `["themeColor","showAppDownload"]` |
| `status` | TINYINT | DEFAULT 1 | 1=启用、0=禁用，例如 `1` |
| `remark` | VARCHAR(500) | NULL | 运营备注，例如 `巴西渠道默认模板` |
| `revision` | INT | DEFAULT 0 | 乐观锁版本，例如 `3` |
| `created_by` | BIGINT | NULL | 创建人用户 ID，例如 `20001` |
| `updated_by` | BIGINT | NULL | 最近修改人用户 ID，例如 `20002` |
| `created_at` | BIGINT | NOT NULL | 创建时间 epoch 毫秒，例如 `1784217600000` |
| `updated_at` | BIGINT | NOT NULL | 更新时间 epoch 毫秒，例如 `1784217660000` |
| `deleted_at` | BIGINT | NULL | 软删时间；未删除为 NULL，例如 `NULL` |
| `is_active` | TINYINT generated | generated | 活行唯一键辅助；活行=1、删除行=NULL，例如 `1` |

索引：

- `PRIMARY KEY (id)`。
- `UNIQUE (tenant_id, template_code, is_active)`：租户内活跃编码唯一。
- `(tenant_id, status, deleted_at, id)`：可用模板列表。

模板名称不作为关联键，也不强制唯一。

### 4.2 `promotion_domain`（推广访问域名）

职责：把规范化域名唯一绑定到一个落地页模板，解决“同域名同模板可多渠道、跨模板不可复用”的并发约束。

| 字段 | 类型 | 可空/默认 | 含义与示例 |
|---|---|---|---|
| `id` | BIGINT | PK | 域名记录主键，例如 `3001` |
| `tenant_id` | BIGINT | NOT NULL | 域名所属租户，例如 `1` |
| `domain_host` | VARCHAR(253) ASCII binary | NOT NULL | 小写/Punycode 规范化主机名，例如 `go.example.com` |
| `landing_template_id` | BIGINT | NOT NULL | 绑定模板 ID，例如 `1001` |
| `created_by` | BIGINT | NULL | 创建人，例如 `20001` |
| `updated_by` | BIGINT | NULL | 最近修改人，例如 `20002` |
| `created_at` | BIGINT | NOT NULL | 创建时间，例如 `1784217600000` |
| `updated_at` | BIGINT | NOT NULL | 更新时间，例如 `1784217660000` |
| `deleted_at` | BIGINT | NULL | 软删时间，例如 `NULL` |
| `is_active` | TINYINT generated | generated | 活行唯一键辅助，例如 `1` |

索引：

- 全局唯一 `(domain_host, is_active)`：同一真实域名不能被多个租户或模板同时占用。
- `(tenant_id, landing_template_id, deleted_at, id)`：按模板反查域名。

域名保存前必须去协议、去端口、去尾点、转小写并将 IDN 转 Punycode。

### 4.3 `promotion_channel`（推广渠道）

职责：保存渠道主数据和生命周期状态。

| 字段 | 类型 | 可空/默认 | 含义与示例 |
|---|---|---|---|
| `id` | BIGINT | PK | 渠道主键，例如 `5001` |
| `tenant_id` | BIGINT | NOT NULL | 租户 ID，例如 `1` |
| `channel_code` | VARCHAR(32) binary collation | NOT NULL | 稳定公开短码，例如 `A8K2M9QX` |
| `channel_name` | VARCHAR(128) | NOT NULL | 渠道名称，例如 `KK-代投印度-抽奖` |
| `owner_user_id` | BIGINT | NOT NULL | 归属用户 ID，例如 `20001` |
| `promotion_domain_id` | BIGINT | NOT NULL | 域名记录 ID，例如 `3001` |
| `target_country_id` | BIGINT | NULL | 目标国家 ID；NULL=混合，例如 `102` |
| `preselected_country_id` | BIGINT | NULL | 落地页预选区号国家 ID，例如 `102` |
| `platform` | TINYINT | NOT NULL | 1=Facebook、2=TikTok、3=快手、4=MGSKY，例如 `1` |
| `theme_color` | CHAR(7) | DEFAULT `#fe4e60` | `#RRGGBB` 主题色，例如 `#fe4e60` |
| `is_in_app_open_allowed` | TINYINT(1) | DEFAULT 1 | 是否允许平台内置浏览器打开，例如 `1` |
| `status` | TINYINT | DEFAULT 1 | 1=启用、0=禁用，例如 `1` |
| `status_reason` | VARCHAR(255) | NULL | 禁用原因，例如 `CAPI配置失效` |
| `revision` | INT | DEFAULT 0 | 乐观锁版本，例如 `4` |
| `created_by` | BIGINT | NULL | 创建人 ID，例如 `20001` |
| `updated_by` | BIGINT | NULL | 最近修改人 ID，例如 `20002` |
| `created_at` | BIGINT | NOT NULL | 创建时间，例如 `1784217600000` |
| `updated_at` | BIGINT | NOT NULL | 更新时间，例如 `1784217660000` |
| `deleted_at` | BIGINT | NULL | 软删时间，例如 `NULL` |
| `is_active` | TINYINT generated | generated | 活行唯一键辅助，例如 `1` |

链接不重复存完整 URL。接口按 `promotion_domain.domain_host + channel_code + source_type` 生成推广链接和裂变链接。

索引：

- 唯一 `(tenant_id, channel_code, is_active)`。
- 列表 `(tenant_id, deleted_at, id)`。
- 国家筛选 `(tenant_id, target_country_id, deleted_at, id)`。
- 归属用户筛选 `(tenant_id, owner_user_id, deleted_at, id)`。
- 域名关联 `(tenant_id, promotion_domain_id, deleted_at, id)`。

### 4.4 `promotion_channel_tracking_config`（渠道追踪/CAPI配置）

职责：隔离敏感 Token，保存当前配置和最近探测投影。

| 字段 | 类型 | 可空/默认 | 含义与示例 |
|---|---|---|---|
| `id` | BIGINT | PK | 配置主键，例如 `6001` |
| `tenant_id` | BIGINT | NOT NULL | 租户 ID，例如 `1` |
| `channel_id` | BIGINT | NOT NULL | 渠道 ID，例如 `5001` |
| `provider_type` | TINYINT | NOT NULL | 1=Facebook、2=TikTok、3=快手、4=MGSKY，例如 `1` |
| `tracking_id` | VARCHAR(128) | NULL | Pixel/追踪标识，例如 `123456789012345` |
| `access_token_ciphertext` | VARBINARY(4096) | NULL | Token 密文，例如 AES-GCM 密文字节；绝不保存明文 |
| `encryption_key_id` | VARCHAR(64) | NULL | 密钥版本，例如 `kms-key-v3` |
| `token_fingerprint` | BINARY(32) | NULL | Token 指纹，例如 SHA-256 32字节摘要 |
| `token_expires_at` | BIGINT | NULL | Token 到期时间，例如 `1786813200000` |
| `last_probe_status` | TINYINT | NULL | NULL=未探测、0=探测中、1=成功、2=失败，例如 `1` |
| `last_probe_event_name` | VARCHAR(64) | NULL | 探测事件名，例如 `PageView` |
| `last_probe_event_id` | VARCHAR(128) | NULL | 平台探测事件 ID，例如 `evt_20260717_001` |
| `last_probe_error_code` | VARCHAR(64) | NULL | 脱敏错误码，例如 `TOKEN_EXPIRED` |
| `last_probe_error_message` | VARCHAR(255) | NULL | 脱敏错误摘要，例如 `访问令牌已过期` |
| `last_probed_at` | BIGINT | NULL | 最近探测时间，例如 `1784217660000` |
| `created_by` | BIGINT | NULL | 创建人，例如 `20001` |
| `updated_by` | BIGINT | NULL | 最近修改人，例如 `20002` |
| `created_at` | BIGINT | NOT NULL | 创建时间，例如 `1784217600000` |
| `updated_at` | BIGINT | NOT NULL | 更新时间，例如 `1784217660000` |
| `deleted_at` | BIGINT | NULL | 软删时间，例如 `NULL` |
| `is_active` | TINYINT generated | generated | 活行唯一键辅助，例如 `1` |

索引：唯一 `(tenant_id, channel_id, is_active)`；探测查询 `(tenant_id, last_probe_status, last_probed_at, id)`。

### 4.5 `promotion_channel_event`（渠道原始事件）

职责：保存可幂等、可追溯、可重算的访问和账号转化事实。

| 字段 | 类型 | 可空/默认 | 含义与示例 |
|---|---|---|---|
| `id` | BIGINT | PK | 事件主键，例如 `9000001` |
| `tenant_id` | BIGINT | NOT NULL | 租户 ID，例如 `1` |
| `channel_id` | BIGINT | NOT NULL | 渠道 ID，例如 `5001` |
| `country_code` | CHAR(2) | NOT NULL | ISO2 或未知 `ZZ`，例如 `IN` |
| `source_type` | TINYINT | NOT NULL | 1=基础推广、2=裂变推广，例如 `1` |
| `event_type` | TINYINT | NOT NULL | 1=访问、2=登录请求、3=登录成功、4=解绑，例如 `3` |
| `event_key` | VARCHAR(128) binary collation | NOT NULL | 上游幂等键，例如 `login-success:req-8f31` |
| `visitor_key_hash` | BINARY(32) | NULL | 访客去重键的不可逆摘要，例如 SHA-256 32字节值 |
| `account_id` | BIGINT | NULL | 登录成功/解绑关联账号 ID，例如 `7001` |
| `request_id` | VARCHAR(64) | NULL | 链路排查 ID，例如 `req-8f31` |
| `stat_date` | DATE | NOT NULL | 统计自然日，例如 `2026-07-17` |
| `occurred_at` | BIGINT | NOT NULL | 业务事件时间，例如 `1784217660000` |
| `created_at` | BIGINT | NOT NULL | Armada 落库时间，例如 `1784217660123` |

索引：

- 唯一 `(tenant_id, event_key)`。
- 聚合 `(tenant_id, channel_id, stat_date, source_type, event_type, id)`。
- UV 去重 `(tenant_id, channel_id, source_type, visitor_key_hash, stat_date)`。
- 账号事件 `(tenant_id, account_id, event_type, occurred_at, id)`。

### 4.6 `promotion_channel_daily_metric`（渠道日统计投影）

表粒度：`tenant_id + channel_id + country_code + stat_date + source_type`。

| 字段 | 类型 | 可空/默认 | 含义与示例 |
|---|---|---|---|
| `id` | BIGINT | PK | 日统计主键，例如 `100001` |
| `tenant_id` | BIGINT | NOT NULL | 租户 ID，例如 `1` |
| `channel_id` | BIGINT | NOT NULL | 渠道 ID，例如 `5001` |
| `country_code` | CHAR(2) | NOT NULL | 统计国家快照，例如 `IN` |
| `stat_date` | DATE | NOT NULL | 统计日期，例如 `2026-07-17` |
| `source_type` | TINYINT | NOT NULL | 1=基础推广、2=裂变推广，例如 `2` |
| `page_view_count` | BIGINT | DEFAULT 0 | 页面访问事件次数，例如 `12580` |
| `uv_count` | BIGINT | DEFAULT 0 | 当日去重访客数，例如 `9432` |
| `login_request_count` | BIGINT | DEFAULT 0 | 登录请求次数，例如 `3260` |
| `login_request_visitor_count` | BIGINT | DEFAULT 0 | 发起登录的去重访客数，例如 `3012` |
| `login_success_count` | BIGINT | DEFAULT 0 | 登录成功事件次数，例如 `2250` |
| `login_success_visitor_count` | BIGINT | DEFAULT 0 | 登录成功去重访客数，例如 `2188` |
| `login_success_account_count` | BIGINT | DEFAULT 0 | 登录成功去重账号数，例如 `2175` |
| `unbind_account_count` | BIGINT | DEFAULT 0 | 当日解绑去重账号数，例如 `83` |
| `same_day_unbind_account_count` | BIGINT | DEFAULT 0 | 当日成功且当日解绑账号数，例如 `21` |
| `source_watermark_event_id` | BIGINT | DEFAULT 0 | 已聚合最大事件 ID，例如 `9000001` |
| `revision` | INT | DEFAULT 0 | 聚合投影版本，例如 `6` |
| `computed_at` | BIGINT | NOT NULL | 聚合完成时间，例如 `1784217700000` |
| `created_at` | BIGINT | NOT NULL | 创建时间，例如 `1784217700000` |
| `updated_at` | BIGINT | NOT NULL | 更新时间，例如 `1784217760000` |

索引：唯一 `(tenant_id, channel_id, country_code, stat_date, source_type)`；区间查询 `(tenant_id, stat_date, channel_id, country_code, source_type)`。

CTR、请求登录率、登录成功率、访客上号率、解绑率和获号成本均为派生值，不落库。区间内要求精确 UV 时从原始事件按 `visitor_key_hash` 去重，不能简单累加每日 UV。

### 4.7 `promotion_channel_daily_ad_revision`（渠道日广告数据版本）

职责：保存广告平台同步或人工补录的有效版本，历史业务值不覆盖。

| 字段 | 类型 | 可空/默认 | 含义与示例 |
|---|---|---|---|
| `id` | BIGINT | PK | 广告版本主键，例如 `110001` |
| `tenant_id` | BIGINT | NOT NULL | 租户 ID，例如 `1` |
| `channel_id` | BIGINT | NOT NULL | 渠道 ID，例如 `5001` |
| `country_code` | CHAR(2) | NOT NULL | 统计国家，例如 `IN` |
| `stat_date` | DATE | NOT NULL | 广告自然日，例如 `2026-07-17` |
| `revision_no` | INT | NOT NULL | 业务粒度内版本号，例如 `3` |
| `data_source` | TINYINT | NOT NULL | 1=人工补录、2=Facebook、3=TikTok、4=快手、5=MGSKY，例如 `1` |
| `currency_code` | CHAR(3) | DEFAULT `USD` | ISO 4217 币种，例如 `USD` |
| `spend` | DECIMAL(20,6) | DEFAULT 0 | 广告消耗，例如 `1234.567800` |
| `impressions` | BIGINT | DEFAULT 0 | 广告展示次数，例如 `500000` |
| `clicks` | BIGINT | DEFAULT 0 | 广告点击次数，例如 `18320` |
| `service_rate` | DECIMAL(9,6) | DEFAULT 0 | 手续费率小数，例如 `0.085000` 表示 8.5% |
| `other_fee` | DECIMAL(20,6) | DEFAULT 0 | 其他费用，例如 `12.500000` |
| `valid_to` | BIGINT | NULL | 版本失效时间；当前版本=NULL，例如 `NULL` |
| `current_marker` | TINYINT generated | generated | 当前版本唯一键辅助；当前=1、历史=NULL，例如 `1` |
| `changed_by` | BIGINT | NULL | 补录/同步操作者，例如 `20001` |
| `change_reason` | VARCHAR(255) | NULL | 变更原因，例如 `修正平台延迟回传` |
| `created_at` | BIGINT | NOT NULL | 版本创建时间，例如 `1784217660000` |

索引：

- 唯一 `(tenant_id, channel_id, country_code, stat_date, revision_no)`。
- 唯一当前版本 `(tenant_id, channel_id, country_code, stat_date, current_marker)`。
- 区间查询 `(tenant_id, stat_date, channel_id, country_code, current_marker)`。

旧版本只更新 `valid_to`，金额、次数、费率等业务值不可修改。手续费、总费用和 CTR 在查询层计算。

### 4.8 `promotion_operation_log`（推广管理操作审计）

职责：记录模板备注、渠道变更、敏感配置更新、探测和补录操作。前后值 JSON 必须先脱敏。

| 字段 | 类型 | 可空/默认 | 含义与示例 |
|---|---|---|---|
| `id` | BIGINT | PK | 审计主键，例如 `120001` |
| `tenant_id` | BIGINT | NOT NULL | 租户 ID，例如 `1` |
| `object_type` | TINYINT | NOT NULL | 1=模板、2=渠道、3=追踪配置、4=广告补录，例如 `2` |
| `object_id` | BIGINT | NOT NULL | 被操作对象 ID，例如 `5001` |
| `action_type` | TINYINT | NOT NULL | 1=新增、2=修改、3=启用、4=禁用、5=删除、6=探测、7=补录，例如 `6` |
| `result_status` | TINYINT | NOT NULL | 1=成功、2=失败，例如 `1` |
| `request_id` | VARCHAR(64) | NULL | 请求链路 ID，例如 `req-8f31` |
| `before_summary` | JSON | NULL | 脱敏前值摘要，例如 `{"status":0}` |
| `after_summary` | JSON | NULL | 脱敏后值摘要，例如 `{"status":1,"token":"[CHANGED]"}` |
| `reason_code` | VARCHAR(64) | NULL | 结果原因码，例如 `TOKEN_EXPIRED` |
| `reason_message` | VARCHAR(255) | NULL | 脱敏原因摘要，例如 `访问令牌已过期` |
| `operator_id` | BIGINT | NULL | 操作人 ID，例如 `20001` |
| `occurred_at` | BIGINT | NOT NULL | 操作发生时间，例如 `1784217660000` |
| `created_at` | BIGINT | NOT NULL | 审计落库时间，例如 `1784217660123` |

索引：对象时间线 `(tenant_id, object_type, object_id, occurred_at, id)`；操作者时间线 `(tenant_id, operator_id, occurred_at, id)`；请求排查 `(tenant_id, request_id, id)`。

---

## 5. `account` 兼容迁移

```text
ALTER account.channel_name VARCHAR(64) -> VARCHAR(128)
ADD account.promotion_channel_id BIGINT NULL
ADD INDEX (tenant_id, promotion_channel_id, deleted_at, created_at)
```

兼容规则：

- 历史行不回填，`promotion_channel_id=NULL` 合法。
- 新渠道账号写稳定 ID 和名称快照。
- 渠道改名不批量改历史 `account.channel_name`。
- 当前名称展示可通过渠道 ID 关联；渠道不存在或已删除时回退快照。

---

## 6. 全字段关联图

最终业务文档必须提供 Mermaid `erDiagram`：

- 展示八张新表和现有 `account`、`country` 的逻辑关联。
- 每个实体列出所有字段。
- 每个字段后的说明同时包含业务含义和示例值。
- 关系线上说明一对一、一对多和可选关系。
- 若单图渲染过宽，可额外拆成“主数据关系图”和“事件统计关系图”，但必须保留一张完整总图。

关系基数：

- 一个模板对应多个域名。
- 一个域名对应多个渠道。
- 一个渠道最多一个当前追踪配置，但可有多条历史审计。
- 一个渠道对应多条原始事件、日统计和广告版本。
- 一个渠道可归因多个账号，一个账号最多保存一个当前首次获客渠道 ID。
- 国家主数据可被多个渠道目标国家、预选区号引用；事件和统计保存国家码快照。

---

## 7. 统计口径与一致性

- `promotion_channel_event` 是访问和转化事实源，`promotion_channel_daily_metric` 是可重建投影。
- 基础推广和裂变推广通过 `source_type` 分行，不在同一行重复两套列。
- 日 UV 是当日去重；跨日区间精确 UV 从事件表做区间去重，不能直接相加。
- CTR=`clicks/impressions`；请求登录率=`login_request_visitor_count/uv_count`；登录成功率=`login_success_visitor_count/login_request_visitor_count`；访客上号率=`login_success_visitor_count/uv_count`。
- 获号成本默认=`spend/login_success_account_count`；如产品改为总费用口径，只改查询公式，不改表。
- 手续费=`spend*service_rate`；总费用=`spend+手续费+other_fee`，不落冗余列。
- 分母为 0 时返回 NULL，由展示层显示 `-`。
- 解绑事件从账号状态/Kafka回写时，通过 `account.promotion_channel_id` 归因，并以协议事件 ID 生成幂等 `event_key`。

---

## 8. 生命周期与事务

- 模板禁用后不能新绑定域名或渠道；已有渠道和历史统计保留。
- 域名存在有效渠道时不能更换模板；无有效渠道后可软删并重新建立绑定。
- 渠道必须先禁用再软删。禁用后新链接解析失败，历史事件和统计仍可查询。
- 渠道新增事务同时校验模板、锁定/创建域名绑定、创建渠道和脱敏审计。
- 追踪配置更新只接受新 Token，不回显旧 Token；密文、密钥 ID、指纹同事务更新。
- 原始事件落库依赖数据库唯一键处理重复消息，重复事件返回幂等成功，不重复累加。
- 日统计可由调度器增量更新，也必须支持按租户/渠道/日期重建。
- 补录保存先锁当前版本，关闭旧 `valid_to`，再插入 `revision_no+1`，并记录操作日志。

---

## 9. 迁移、回滚与测试

### 9.1 Flyway

- 当前最新迁移为 `V057__historical_group_pull_execution.sql`。
- 实施时新增一个唯一 V058 迁移；如果并行开发占用 V058，实施前重新探测并顺延版本号。
- 顺序：模板 → 域名 → 渠道 → 追踪配置 → 事件 → 日统计 → 广告版本 → 审计 → `account` 兼容列与索引。

### 9.2 回滚

- 回滚脚本只作为人工审核脚本，不由 Flyway 自动执行。
- 先删除 `account` 新索引和 `promotion_channel_id`，再恢复 `channel_name VARCHAR(64)`；执行前必须验证没有超过 64 字符的数据。
- 按引用反方向删除八张新表。
- 生产回滚前必须导出渠道事件和补录历史，禁止直接丢失业务事实。

### 9.3 DbTest

- 八张表存在、引擎和字符集正确。
- 所有字段类型、可空性、默认值、注释和生成表达式正确。
- 每个字段注释都包含中文业务含义和 `例如` 示例。
- 所有唯一索引和普通索引列顺序正确。
- 两租户可以使用相同模板编码和渠道编码，但不能同时占用同一个真实域名。
- 同租户重复活跃模板编码/渠道编码失败；软删后可重建。
- 重复 `event_key` 被数据库拒绝，跨租户相同键允许。
- 同一广告粒度只能有一个当前版本，历史版本可以多条。
- `account` 历史行保持可读，新列允许 NULL，原筛选 SQL 不受影响。
- Token 列为密文字节列；审计测试确保示例日志不出现 Token 原文。

---

## 10. 决策日志

| 决策 | 结论 |
|---|---|
| 落地页模板与现有营销模板 | 独立 `promotion_landing_template`，禁止复用 `marketing_template` |
| H5 边界 | 只覆盖归因和统计事件，不建抽奖/奖励/OTP/分享达标表 |
| 域名规则 | 独立 `promotion_domain`，数据库唯一键保证全局活跃域名唯一 |
| 推广/裂变链接 | 不存完整 URL，由域名、渠道码和来源类型生成 |
| Token | 垂直拆表、应用层加密、接口不回显、审计脱敏 |
| 统计事实 | 原始事件可重算，日统计为查询投影 |
| 基础/裂变 | 同一日统计表按 `source_type` 分行 |
| 广告补录 | 有效期版本化，不覆盖历史业务值 |
| 账号兼容 | 新增 `promotion_channel_id`，保留并扩展 `channel_name` 快照 |
| 字段说明 | SQL COMMENT 和最终字段图必须为每个字段提供含义与示例 |

