# 超链营销数据模型

本文冻结「超链营销」模块的数据模型。**只落 schema 设计与论证，不实现 Controller/Service/Mapper。**

- 需求来源：`docs/superpowers/specs/2026-08-27-hyperlink-marketing-replication-design.md`
- 全局现状依据：`.harness/wiki/数据模型.md`（自动生成）
- 遵循：`.harness/rules/数据模型规范.md`

> ## ⚠ 效力声明（2026-08-27 修订）
>
> 本文是**超链营销全模块的目标数据模型**，用于看清全局与聚合边界。但以下部分**已被一期
> 详细设计取代，不得据本文实施**：
>
> | 本文章节 | 状态 | 以何为准 |
> |---|---|---|
> | §3 数据包三张表 | **失效** | `docs/superpowers/specs/2026-08-27-hyperlink-data-template-phase1-design.md` §6.1~6.3 |
> | §5.1 `hyperlink_template` | **失效** | 同上 §6.4 |
> | §6 图片素材（改名 `resource_asset`） | **失效** | 同上 §8.4、§13.3——一期不改名，复用现有文件 ID |
> | §9 Flyway V140~V145 编排 | **失效** | `origin/1.0.3-snapshot` 已存在 `V140__group_canonical_first_classification.sql`，版本号须在实施前按目标分支实时最高版本重新分配 |
> | §4 任务族、§7 分析预聚合、§8 账号画像 | 仍有效 | 本文 |
>
> 一期设计在三处纠正了本文：号码行不应驮任务事实（本文 §3.2 的 `hyperlink_task_id` /
> `delivered_at` / `click_count` 是错的）；模板**确实**保存 `promotion_link` 与按钮目标 URL
> （本文 §5.1 末尾据 UI 文案所写的结论是错的，实际 payload 见
> `hylbuiaxykfrontendsource/readable/assets/templates-BLWMxusB.js:399`）；`marketing_template_file`
> 直接改名会让滚动发布期间的旧实例失效。

---

## 一、设计原则

1. **超链任务与群组营销是两条目标链路，不合表**。`marketing_task_target` 的目标是「账号 × 群组」
   （`group_link_id` / `group_jid` 必居其一），超链的目标是「账号 × 手机号」。两者主键语义、唯一约束、
   状态机都不同，合表会得到一张一半列恒 NULL 的表。
2. **账号事实不复制**。`account` / `account_state` 仍是账号身份与在线、封禁、风控事实源；
   超链任务只保存执行时的号码与国家快照。
3. **号码事实一处存**。号码归属数据包，`data_package_phone` 是唯一事实源；任务侧
   `hyperlink_task_recipient` 只保存本次任务的执行快照与结果。
4. **按聚合垂直拆分，不做宽表**。超链任务拆成「配置 / 消息内容 / 计数」三张 1:1 表
   （理由见 §4.1）。参照 `marketing_task` 已达 38 列的教训。
5. **图片素材只有一套表示**。现有 `marketing_template_file` 演进为通用 `resource_asset`，
   **不新建第二张图片表**（规范一.2 反 `account_group_id`/`tag_id`/`account_tag` 三镜像）。
6. **不落无采集链路的死列**。账号画像字段（好友数、注册天数、设备类型等）只有在协议层确认
   能采集后才落列，否则本期不做该筛选项（规范一.4）。
7. **点击流水不存原始 IP**，只存由 IP 派生的国家码，与 promotion 模块既有的隐私保留策略同向。

---

## 二、表清单

| 表 | 聚合归属 | 状态 | 作用 |
|---|---|---|---|
| `data_package` | 资源池 | 新建 | 号码包主表，保存包级计数 |
| `data_package_phone` | 资源池 | 新建 | 号码明细，号码事实源 |
| `data_package_import` | 资源池 | 新建 | 号码导入批次与解析结果 |
| `resource_asset` | 公共（文件） | **由 `marketing_template_file` 改名扩列** | 图片素材统一事实源 |
| `resource_asset_tag` | 公共（文件） | 新建 | 素材标签字典 |
| `resource_asset_tag_ref` | 公共（文件） | 新建 | 素材 × 标签关联 |
| `hyperlink_template` | hyperlink | 新建 | 超链消息模板 |
| `hyperlink_strategy` | hyperlink | 新建 | 超链发送策略预设 |
| `hyperlink_task` | hyperlink | 新建 | 超链任务配置与生命周期 |
| `hyperlink_task_content` | hyperlink | 新建 | 任务消息内容快照（1:1） |
| `hyperlink_task_stat` | hyperlink | 新建 | 任务级计数（1:1，高频回写） |
| `hyperlink_task_recipient` | hyperlink | 新建 | 执行目标，一行=一个收件人 |
| `hyperlink_click` | hyperlink | 新建 | 点击流水 |
| `hyperlink_task_ban` | hyperlink | 新建 | 任务期间账号封号事实 |
| `hyperlink_stat_daily` | hyperlink | 新建 | 市场分析日粒度预聚合 |
| `account_profile` | account | **待全局评审** | 账号画像，承载新增筛选维度（§7） |

新增 13 张 + 改名扩列 1 张 + 待评审 1 张。全部表带 `tenant_id`，**无需登记
`MyBatisConfig.IGNORED_TABLES`**。

---

## 三、资源池：数据包

### 3.1 data_package（号码包）

一行 = 一个可被超链任务选作受众的号码集合。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `package_name` | `VARCHAR(128) NOT NULL` | 数据包名称 |
| `phone_count` | `INT NOT NULL DEFAULT 0` | 包内号码总数 |
| `used_num` | `INT NOT NULL DEFAULT 0` | 已被任务领用号码数 |
| `unused_num` | `INT NOT NULL DEFAULT 0` | 未使用号码数 |
| `success_num` | `INT NOT NULL DEFAULT 0` | 单钩（发送成功）号码数 |
| `delivered_num` | `INT NOT NULL DEFAULT 0` | 双钩（已送达）号码数 |
| `fail_num` | `INT NOT NULL DEFAULT 0` | 发送失败号码数 |
| `fail_404_num` | `INT NOT NULL DEFAULT 0` | 未开通 WhatsApp 号码数 |
| `click_uv_num` | `INT NOT NULL DEFAULT 0` | 点击去重号码数 |
| `remark` | `VARCHAR(255)` | 备注 |
| `created_by` | `BIGINT` | 创建人 user_id |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |
| `deleted_at` | `BIGINT` | 软删时间；NULL=未删 |
| `is_active` | `TINYINT`（生成列） | 软删唯一键辅助：活行=1 软删=NULL |

索引：

| 索引 | 字段 | 说明 |
|---|---|---|
| `uq_data_package_name` | `tenant_id, package_name, is_active` | 同租户下包名唯一 |
| `idx_data_package_tenant` | `tenant_id, deleted_at, id` | 列表分页 |

> `unused_num` 与 `phone_count - used_num` 冗余，保留是因为列表页与新建任务弹窗都要直接展示
> 「未使用 N 条」，不冗余就得每次全表 COUNT。回写与 `used_num` 同事务。

### 3.2 data_package_phone（号码明细）

一行 = 包内一个号码。**这是号码的唯一事实源**。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `data_package_id` | `BIGINT NOT NULL` | →`data_package.id` |
| `phone` | `VARCHAR(32) NOT NULL` | 完整国际号码，只含数字 |
| `country_iso2` | `CHAR(2)` | 导入时由区号经 `country_phone_prefix_mapping` 解析并快照；无法解析为 NULL |
| `send_status` | `TINYINT NOT NULL DEFAULT 1` | 1=未使用 2=已领用 3=发送成功(单钩) 4=已送达(双钩) 5=发送失败 6=未开通WS |
| `fail_code` | `VARCHAR(32)` | 失败码，如 `404`；成功为 NULL |
| `hyperlink_task_id` | `BIGINT` | 最近领用该号码的任务 ID；未领用为 NULL |
| `used_at` | `BIGINT` | 领用时间(epoch 毫秒) |
| `delivered_at` | `BIGINT` | 双钩时间(epoch 毫秒) |
| `click_count` | `INT NOT NULL DEFAULT 0` | 该号码累计点击次数 |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：

| 索引 | 字段 | 说明 |
|---|---|---|
| `uq_data_package_phone` | `tenant_id, data_package_id, phone` | **包内号码去重**，覆盖/追加导入都靠它 |
| `idx_data_package_phone_pick` | `tenant_id, data_package_id, send_status, id` | 任务领号扫描 |
| `idx_data_package_phone_country` | `tenant_id, data_package_id, country_iso2` | 国家分布统计 |

> `country_iso2` 是**有意的反规范化**：导入时算一次，避免号码明细分页与国家分布统计每次
> join 区号映射表。区号映射表是平台元数据、极少变更，快照漂移风险可接受。

### 3.3 data_package_import（导入批次）

一行 = 一次 TXT 上传。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `data_package_id` | `BIGINT NOT NULL` | →`data_package.id` |
| `import_mode` | `TINYINT NOT NULL` | 导入模式：1=追加(去重后并入) 2=覆盖(清空原号码) |
| `source_file_name` | `VARCHAR(255)` | 上传文件原名 |
| `total_rows` | `INT NOT NULL DEFAULT 0` | 解析总行数 |
| `valid_rows` | `INT NOT NULL DEFAULT 0` | 有效手机号行数 |
| `invalid_rows` | `INT NOT NULL DEFAULT 0` | 格式非法行数 |
| `duplicated_rows` | `INT NOT NULL DEFAULT 0` | 与包内已有号码重复行数 |
| `created_by` | `BIGINT` | 上传人 user_id |
| `created_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：`idx_data_package_import_pkg`（`tenant_id, data_package_id, created_at`）。

> `import_mode` 用 TINYINT 而非 hylb 的 `overwrite`/`append` 字符串，遵循规范二「状态/枚举列 TINYINT」。

**已决**（2026-08-27）：

- **单次导入上限 5000 行**。数据包总号码数不设上限，只约束单次上传。超限直接拒收，
  提示用户拆分文件。
- **不做国家风险拦截**。因此 `blocked_rows` / `blocked_country_iso2s` 两列**不落**——
  没有写入方的列就是死列（规范一.4）。将来若要做拦截，届时用 Flyway 加列。
- 覆盖模式（`import_mode=2`）的「清空原号码 + 导入新号码」**必须在同一事务内**完成，
  不允许清空成功而导入失败留下空包。5000 行的批量插入需在此事务边界内分批执行。

---

## 四、hyperlink 聚合：任务族

### 4.1 为什么拆三张表

超链任务若单表承载，列数约 **57 列**，远超规范一.3 的 ~30 列阈值。按关注点拆：

| 表 | 列数 | 关注点 | 读写特征 |
|---|---|---|---|
| `hyperlink_task` | 29 | 任务配置与生命周期 | 列表页高频读，配置写少 |
| `hyperlink_task_content` | 13 | 消息内容快照 | 列表页**不读**，详情与发送时读 |
| `hyperlink_task_stat` | 15 | 任务级计数 | 发送与 ack 回流**高频写** |

拆分收益是具体的：列表分页不必带上 `buttons` JSON 与长文本；ack 高频回写不与配置行争锁。

### 4.2 hyperlink_task（任务配置与生命周期）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `task_name` | `VARCHAR(128) NOT NULL` | 任务名称 |
| `task_type` | `TINYINT NOT NULL` | 任务模式：1=即时 2=持续运营 3=周期循环 |
| `status` | `TINYINT NOT NULL DEFAULT 1` | 1=未开始 2=待发送 3=发送中 4=已暂停 5=已完成 6=已停止 7=仅保存 |
| `start_mode` | `TINYINT NOT NULL DEFAULT 1` | 启动方式：1=立即执行 2=延后执行 |
| `task_delay_minutes` | `INT NOT NULL DEFAULT 0` | 延后执行分钟数；`start_mode=1` 时恒 0 |
| `task_planned_end_at` | `BIGINT` | 计划结束时间(epoch 毫秒)；仅持续运营模式必填 |
| `task_interval_minutes` | `INT` | 周期轮次间隔(分钟)；仅周期模式必填，≥1 |
| `data_package_id` | `BIGINT` | →`data_package.id`；仅保存不发送时可为 NULL |
| `data_package_name` | `VARCHAR(128)` | 数据包名称快照 |
| `hyperlink_template_id` | `BIGINT` | 引用的模板 ID；模板只带入内容，不建立强依赖 |
| `hyperlink_strategy_id` | `BIGINT` | 引用的策略 ID；策略只带入配置，不建立强依赖 |
| `account_filter` | `JSON` | 发送账号筛选条件；NULL 或 `{}` = 不限定（全部有效账号） |
| `max_use_account` | `INT NOT NULL DEFAULT 0` | 最大使用账号数；0=不限号数 |
| `concurrent_num` | `INT NOT NULL DEFAULT 1` | 最大执行账号数，须 ≤ `max_use_account`(非0时) |
| `account_max_send_num` | `INT NOT NULL DEFAULT 0` | 每账号最大发送条数；0=打死/封号为止 |
| `account_send_concurrency` | `INT NOT NULL DEFAULT 1` | 单账号同时并发量，1~100 |
| `msg_interval_min_sec` | `INT NOT NULL` | 消息间隔下界(秒) |
| `msg_interval_max_sec` | `INT NOT NULL` | 消息间隔上界(秒)，须 ≥ 下界 |
| `is_short_link_enabled` | `TINYINT(1) NOT NULL DEFAULT 0` | 深度追踪：0=发原始链接无点击数据 1=每收件人独立短码 |
| `remark` | `VARCHAR(512)` | 任务备注 |
| `started_at` | `BIGINT` | 首次启动时间(epoch 毫秒) |
| `last_send_at` | `BIGINT` | 最近一次成功发送时间(epoch 毫秒) |
| `finished_at` | `BIGINT` | 进入终态时间(epoch 毫秒) |
| `created_by` | `BIGINT` | 创建人 user_id |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |
| `deleted_at` | `BIGINT` | 软删时间；NULL=未删 |

索引：

| 索引 | 字段 | 说明 |
|---|---|---|
| `idx_hyperlink_task_tenant` | `tenant_id, deleted_at, id` | 列表分页 |
| `idx_hyperlink_task_status_time` | `tenant_id, status, last_send_at` | 状态筛选 + 最后发送排序 |
| `idx_hyperlink_task_due` | `tenant_id, status, task_planned_end_at, id` | 到期结束扫描 |
| `idx_hyperlink_task_package` | `tenant_id, data_package_id` | 数据包反查引用 |

> **模板/策略是弱引用**：前端语义是"引用模板一键带入内容"，带入后任务自持一份快照
> （`hyperlink_task_content`）。因此删除模板不影响在跑任务，只需 `task_ref_count` 做删除提示。
> 这是有意为之，不是遗漏外键。

### 4.3 hyperlink_task_content（消息内容快照，1:1）

主键即 `hyperlink_task_id`，不另设自增 id。

| 字段 | 类型 | 说明 |
|---|---|---|
| `hyperlink_task_id` | `BIGINT` | 主键，→`hyperlink_task.id` |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `message_type` | `TINYINT NOT NULL` | 1=单图文 2=双图文 3=普通按钮 4=卡片按钮 |
| `title` | `VARCHAR(255)` | 消息标题 / 按钮气泡上方加粗大字 |
| `content` | `TEXT` | 正文 / 卡片正文 |
| `link_description` | `VARCHAR(512)` | 链接描述（标题下灰色小字） |
| `promotion_link` | `VARCHAR(512)` | 推广链接（点击后跳转的原始 URL） |
| `buttons` | `JSON` | `[{name,display_text,value}]` 最多 3；`name`∈`cta_url`/`cta_copy`/`quick_reply`；仅按钮类消息 |
| `card_text` | `TEXT` | 底部小字 / 卡片底部文案 |
| `link_preview_asset_id` | `BIGINT` | 链接预览图 →`resource_asset.id` |
| `body_main_asset_id` | `BIGINT` | 正文主图 →`resource_asset.id` |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

> `cta_call`（电话按钮）**本期不入枚举**：协议层 `card-content.ts` 的 `ButtonCardButtonType`
> 只有 `link|copy|quick`，落一个发不出去的按钮类型就是死数据。协议层扩展后再加。

### 4.4 hyperlink_task_stat（任务计数，1:1）

| 字段 | 类型 | 说明 |
|---|---|---|
| `hyperlink_task_id` | `BIGINT` | 主键，→`hyperlink_task.id` |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `recipient_total` | `INT NOT NULL DEFAULT 0` | 受众总数（已生成的收件人行数） |
| `send_total` | `INT NOT NULL DEFAULT 0` | 已发送总数 |
| `success_num` | `INT NOT NULL DEFAULT 0` | 单钩数（`server_ack`） |
| `delivered_num` | `INT NOT NULL DEFAULT 0` | 双钩数（`delivery_ack`） |
| `read_num` | `INT NOT NULL DEFAULT 0` | 已读数（`read`） |
| `fail_num` | `INT NOT NULL DEFAULT 0` | 失败总数 |
| `fail_404_num` | `INT NOT NULL DEFAULT 0` | 未开通 WhatsApp 数 |
| `banned_count` | `INT NOT NULL DEFAULT 0` | 本任务期间封号账号数（去重） |
| `click_uv_num` | `INT NOT NULL DEFAULT 0` | 点击去重受众数 |
| `click_total` | `INT NOT NULL DEFAULT 0` | 点击总次数 |
| `used_account_count` | `INT NOT NULL DEFAULT 0` | 实际使用账号数（去重） |
| `execution_duration_sec` | `INT NOT NULL DEFAULT 0` | 已执行时长(秒) |
| `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

比率**不落列**，一律由前端按 §六 的公式现算。落列会在分子分母异步回流时出现自相矛盾的快照。

### 4.5 hyperlink_task_recipient（执行目标）

一行 = 本任务向一个手机号的一次投递。对应接口 `/hyperlink-tasks/{id}/recipients`。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `hyperlink_task_id` | `BIGINT NOT NULL` | →`hyperlink_task.id` |
| `data_package_phone_id` | `BIGINT NOT NULL` | →`data_package_phone.id` |
| `recipient_phone` | `VARCHAR(32) NOT NULL` | 收件人号码快照 |
| `recipient_country_iso2` | `CHAR(2)` | 收件人国家快照 |
| `account_id` | `BIGINT` | 发信账号 →`account.id`；未分配为 NULL |
| `sender_phone` | `VARCHAR(32)` | 发信账号号码快照 |
| `sender_country_iso2` | `CHAR(2)` | 发信账号国家快照（由 `ws_phone` 区号解析） |
| `protocol_id` | `VARCHAR(32)` | 协议标识快照 |
| `short_code` | `VARCHAR(16)` | 深度追踪短码；未开启深度追踪为 NULL |
| `protocol_message_id` | `VARCHAR(128)` | 协议层消息 ID，**ack 事件回关联靠它** |
| `send_status` | `TINYINT NOT NULL DEFAULT 1` | 1=待发送 2=发送中 3=发送成功(单钩) 4=已送达(双钩) 5=已读 6=发送失败 7=未开通WS 8=已跳过 |
| `fail_code` | `VARCHAR(32)` | 失败码 |
| `fail_reason` | `VARCHAR(255)` | 失败原因（落库前按列宽截断） |
| `retry_count` | `INT NOT NULL DEFAULT 0` | 已重试次数 |
| `sent_at` | `BIGINT` | 发送成功(单钩)时间 |
| `delivered_at` | `BIGINT` | 送达(双钩)时间 |
| `read_at` | `BIGINT` | 已读时间 |
| `failed_at` | `BIGINT` | 失败时间 |
| `click_count` | `INT NOT NULL DEFAULT 0` | 该收件人点击次数 |
| `first_visit_at` | `BIGINT` | 首次点击时间 |
| `last_visit_at` | `BIGINT` | 最近点击时间 |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：

| 索引 | 字段 | 说明 |
|---|---|---|
| `uq_hyperlink_recipient` | `tenant_id, hyperlink_task_id, recipient_phone` | 同任务同号码只投一次 |
| `uq_hyperlink_recipient_short_code` | `short_code` | 短码全局唯一；NULL 不参与唯一约束 |
| `idx_hyperlink_recipient_task` | `tenant_id, hyperlink_task_id, send_status, id` | 明细分页 + 状态筛选 |
| `idx_hyperlink_recipient_msg` | `protocol_message_id` | **ack 事件回写查找** |
| `idx_hyperlink_recipient_account` | `tenant_id, hyperlink_task_id, account_id` | 发信账号维度统计 |
| `idx_hyperlink_recipient_country` | `tenant_id, hyperlink_task_id, sender_country_iso2, recipient_country_iso2` | 国家对聚合 |
| `idx_hyperlink_recipient_click` | `tenant_id, hyperlink_task_id, click_count` | UV 统计与「从来不点」分析 |

> **点击 UV 从这张表算，不从 `hyperlink_click` 算**：`COUNT(*) WHERE click_count > 0`
> 走索引即可，而 `hyperlink_click` 上的 `COUNT(DISTINCT recipient_id)` 在千万行量级会拖垮分析页。
> 这是 `click_count` / `first_visit_at` / `last_visit_at` 三个冗余列存在的唯一理由。

### 4.6 hyperlink_click（点击流水）

一行 = 一次点击。**本模块最大的表**，需配归档策略。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID（由 `short_code` 反查得到并显式写入） |
| `hyperlink_task_id` | `BIGINT NOT NULL` | →`hyperlink_task.id` |
| `recipient_id` | `BIGINT NOT NULL` | →`hyperlink_task_recipient.id` |
| `recipient_phone` | `VARCHAR(32) NOT NULL` | 收件人号码快照（深度归因导出直出，免 join） |
| `recipient_country_iso2` | `CHAR(2)` | 收件人国家快照 |
| `short_code` | `VARCHAR(16) NOT NULL` | 被访问的短码 |
| `visit_order` | `INT NOT NULL` | 该收件人的第几次访问，从 1 起 |
| `user_agent` | `VARCHAR(512)` | 原始 UA（截断） |
| `browser` | `VARCHAR(64)` | UA 解析出的浏览器 |
| `os` | `VARCHAR(64)` | UA 解析出的操作系统 |
| `device` | `VARCHAR(64)` | UA 解析出的设备类型 |
| `language` | `VARCHAR(32)` | `Accept-Language` 首选语言 |
| `visit_country_iso2` | `CHAR(2)` | **由访问 IP 派生的国家码；不落原始 IP** |
| `visit_at` | `BIGINT NOT NULL` | 访问时间(epoch 毫秒) |

索引：

| 索引 | 字段 | 说明 |
|---|---|---|
| `idx_hyperlink_click_task_time` | `tenant_id, hyperlink_task_id, visit_at` | 点击明细分页、访问趋势 |
| `idx_hyperlink_click_recipient` | `tenant_id, recipient_id, visit_at` | 深度归因、单收件人访问序列 |

写入路径特殊：公网跳转接口无租户上下文，Mapper 用
`@InterceptorIgnore(tenantLine = "true")` 并显式带 `tenant_id`，
与 `PromotionPairingSessionMapper` / `PromotionCapiEventOutboxMapper` 的既有做法一致。

> **容量提醒**：数据包可达数十万号码，深度追踪下每号一个短码。`hyperlink_click`
> 是唯一会随点击量线性膨胀的表，实施时须同步定按 `visit_at` 的归档/分区策略，
> 不能等到表撑爆再补。

### 4.7 hyperlink_task_ban（封号事实）

一行 = 本任务期间一个账号的一次封号。对应接口 `/hyperlink-tasks/{id}/ban-stats`。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `hyperlink_task_id` | `BIGINT NOT NULL` | →`hyperlink_task.id` |
| `account_id` | `BIGINT NOT NULL` | →`account.id` |
| `account_phone` | `VARCHAR(32) NOT NULL` | 账号号码快照 |
| `sender_country_iso2` | `CHAR(2)` | 账号国家快照 |
| `ban_error_code` | `VARCHAR(32)` | 封号错误码（401/403/440），取自 `account_state.block_error_code` |
| `ban_reason` | `VARCHAR(255)` | 封号原因（截断） |
| `banned_at` | `BIGINT NOT NULL` | 封号时间(epoch 毫秒) |
| `created_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：

| 索引 | 字段 | 说明 |
|---|---|---|
| `uq_hyperlink_task_ban` | `tenant_id, hyperlink_task_id, account_id` | 同任务同账号只记一次，`banned_count` 去重靠它 |
| `idx_hyperlink_task_ban_reason` | `tenant_id, hyperlink_task_id, ban_error_code` | 原因分布聚合 |

> **为什么不直接查 `account_state`**：`account_state` 只保存账号**当前**状态，没有历史。
> 账号解封或被其他任务再次封禁后，本任务的封号事实就丢了。任务级归因必须自己记一行。

---

## 五、hyperlink 聚合：模板与策略

### 5.1 hyperlink_template（超链模板）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `template_name` | `VARCHAR(128) NOT NULL` | 模板名称 |
| `message_type` | `TINYINT NOT NULL` | 1=单图文 2=双图文 3=普通按钮 4=卡片按钮 |
| `title` / `content` / `link_description` / `promotion_link` / `buttons` / `card_text` / `link_preview_asset_id` / `body_main_asset_id` | 同 `hyperlink_task_content` | 内容字段，语义与列型完全一致 |
| `task_ref_count` | `INT NOT NULL DEFAULT 0` | 被任务引用次数，用于删除保护提示 |
| `remark` | `VARCHAR(255)` | 备注 |
| `created_by` | `BIGINT` | 创建人 user_id |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |
| `deleted_at` | `BIGINT` | 软删时间；NULL=未删 |
| `is_active` | `TINYINT`（生成列） | 软删唯一键辅助 |

索引：`uq_hyperlink_template_name`（`tenant_id, template_name, is_active`）、
`idx_hyperlink_template_tenant`（`tenant_id, deleted_at, id`）。

> ~~模板只保存按钮类型与按钮文字，不保存跳转链接~~ —— **此结论已被推翻（2026-08-27）**。
> 前端确有"跳转链接在创建任务时配置"的提示文案，但**实际保存逻辑与该提示冲突**：
> `readable/assets/templates-BLWMxusB.js:399` 显示单图文保存 `promotion_link`、
> 按钮类保存完整 `buttons`（含目标 URL）。以实际 payload 为准——模板保存完整跳转地址。

#### 5.1.1 ⚠ 与 `marketing_template` 的关系（需全局评审）

这是本设计中**唯一触碰规范一.2「一个事实一处存」的地方**，必须说清。

现有 `marketing_template` 也是"WhatsApp 消息模板"，字段重合约 60%。两张表并存有漂移风险。
两个方案：

**方案 A（本文采用）：独立 `hyperlink_template`**

理由：
1. 枚举不同构。`marketing_template.link_mode` 是 `1=普通超链 2=按钮超链 3=图文内容`，
   超链是 `1=单图文 2=双图文 3=普通按钮 4=卡片按钮`。合表必须重新归一枚举，
   而群组营销在生产运行中。
2. 超链模板有 4 个 `marketing_template` 没有的字段（`title`、`link_description`、
   `card_text`、第二张图），而 `marketing_template.mention_all` 是群消息语义，
   私聊场景恒为 0——合表两边都会产生恒 NULL / 恒 0 的列，正是规范一.4 禁的死列。
3. 规范五「跨业务共享表的任何改动走全局评审，禁某业务私自加列」——`marketing_template`
   是群组营销在用的表，超链业务不应私自扩它。

代价：两张模板表，未来两边同时改消息形态时需同步。缓解：二者共用 `resource_asset`
存图、共用同一份 `buttons` JSON 结构约定。

**方案 B（备选）：归一进 `marketing_template`**

把两套枚举真正合并成一套消息形态：
`1=纯文本超链 2=按钮(无图) 3=图文(图+文) 4=链接卡片 5=图+链接卡片 6=按钮(带图)`，
群组营销用 1/2/3，超链用 4/5/6/2，加 `business_type` 判别列 + 4 个内容列。

收益：一个概念一张表，彻底消除漂移。
代价：改动生产中群组营销的枚举语义，需数据迁移 + 回归；P0 就得动群组营销，风险前置。

**建议**：本期走 A 不阻塞 P0，但把这条登记为**已知的受控冗余**；若评审认为 B 的长期收益
更大，则 B 必须在 P0 之前独立立项，不能夹在超链需求里做。

### 5.2 hyperlink_strategy（超链策略）

一行 = 一份可在新建任务时"引用策略"一键带入的发送参数预设。**只管发送节奏与账号范围，
不含消息内容与数据包。**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `strategy_name` | `VARCHAR(128) NOT NULL` | 策略名称（仅后台展示，便于识别） |
| `task_type` | `TINYINT NOT NULL` | 1=即时 2=持续运营 3=周期循环 |
| `task_interval_minutes` | `INT` | 周期轮次间隔(分钟)；仅周期模式有效 |
| `max_use_account` / `concurrent_num` / `account_max_send_num` / `account_send_concurrency` / `msg_interval_min_sec` / `msg_interval_max_sec` / `account_filter` | 同 `hyperlink_task` | 参数字段，语义与列型完全一致 |
| `is_enabled` | `TINYINT(1) NOT NULL DEFAULT 1` | 0=停用（不出现在新建任务选项中） 1=启用 |
| `remark` | `VARCHAR(255)` | 备注 |
| `created_by` | `BIGINT` | 创建人 user_id |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |
| `deleted_at` | `BIGINT` | 软删时间；NULL=未删 |
| `is_active` | `TINYINT`（生成列） | 软删唯一键辅助 |

索引：`uq_hyperlink_strategy_name`（`tenant_id, strategy_name, is_active`）、
`idx_hyperlink_strategy_enabled`（`tenant_id, is_enabled, deleted_at, id`）。

> 策略与任务是**弱引用**：引用后参数复制进任务，改策略不影响在跑任务。这与前端
> "已带入策略「X」"的提示语义一致。

---

## 六、公共：图片素材

### 6.1 `marketing_template_file` → `resource_asset`（改名 + 扩列）

现状：`marketing_template_file` 只有上传与取字节两个能力（8 列，`content` 为 `MEDIUMBLOB`）。
超链的「图片素材」页需要列表、命名、标签、引用计数、删除保护、批量上传。

**不新建 `resource_asset` 表指向 `marketing_template_file`**——那会得到两张图片表，
正是规范一.2 点名的镜像反模式。改为**把现表演进成通用素材表**：

```sql
RENAME TABLE marketing_template_file TO resource_asset;
```

新增列：

| 字段 | 类型 | 说明 |
|---|---|---|
| `asset_name` | `VARCHAR(128) NOT NULL` | 素材名称；存量行迁移时取 `original_filename` 回填 |
| `width` / `height` | `INT` | 图片像素尺寸；解析失败为 NULL |
| `ref_count` | `INT NOT NULL DEFAULT 0` | 被模板与任务引用总次数，删除保护用 |
| `created_by` | `BIGINT` | 上传人 user_id；存量行为 NULL |
| `updated_at` | `BIGINT NOT NULL` | epoch 毫秒；存量行取 `created_at` 回填 |

保留列：`id` / `tenant_id` / `original_filename` / `content_type` / `size_bytes` / `content` /
`created_at` / `deleted_at`。共 14 列。

索引新增：`idx_resource_asset_name`（`tenant_id, deleted_at, asset_name`）供按名搜索。

**影响面**（改名的代价，需在实施计划里逐条兑现）：
- `MarketingTemplateFileController` / `MarketingTemplateFileService` / `MarketingTemplateFileMapper`
  及其 XML 的表名与类名
- `marketing_template.image_file_id` 的目标表变更（**列名保留不改**，避免波及群组营销的读写路径）
- 前端 `src/api/marketing-template.ts` 的 `imageFileId` 语义不变，无需改动

> **遗留风险（不在本期解决，但登记在案）**：`content` 是存在 MySQL 里的 `MEDIUMBLOB`。
> 素材库支持批量上传后，主库体积会随素材量线性增长。本期沿用现状（不引入对象存储这个新基础设施），
> 但一旦素材量级起来，迁对象存储要作为独立技术债项立项。

### 6.2 resource_asset_tag / resource_asset_tag_ref

标签是多对多，必须独立成表，不能塞进 `resource_asset` 的 JSON 列——
页面有「按标签筛选（任意匹配）」与标签下拉候选，JSON 列做不了索引化的反查。

`resource_asset_tag`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `tag_name` | `VARCHAR(64) NOT NULL` | 标签名 |
| `created_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：`uq_resource_asset_tag`（`tenant_id, tag_name`）。

`resource_asset_tag_ref`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `resource_asset_id` | `BIGINT NOT NULL` | →`resource_asset.id` |
| `resource_asset_tag_id` | `BIGINT NOT NULL` | →`resource_asset_tag.id` |
| `created_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：`uq_resource_asset_tag_ref`（`tenant_id, resource_asset_id, resource_asset_tag_id`）、
`idx_resource_asset_tag_ref_tag`（`tenant_id, resource_asset_tag_id, resource_asset_id`）供按标签反查。

---

## 七、市场分析预聚合

### 7.1 hyperlink_stat_daily

一行 = 一天 × 一个国家对 × 一组任务属性的汇总。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `stat_date` | `INT NOT NULL` | 统计日期，`yyyyMMdd` 整数 |
| `sender_country_iso2` | `CHAR(2) NOT NULL` | 发信国家；未知落 `ZZ` |
| `recipient_country_iso2` | `CHAR(2) NOT NULL` | 被营销国家；未知落 `ZZ` |
| `account_type` | `TINYINT NOT NULL` | 账号类型：1=个人 2=商业 |
| `task_type` | `TINYINT NOT NULL` | 1=即时 2=持续运营 3=周期循环 |
| `is_short_link_enabled` | `TINYINT(1) NOT NULL` | 是否深度追踪 |
| `send_total` | `INT NOT NULL DEFAULT 0` | 发送量 |
| `success_num` | `INT NOT NULL DEFAULT 0` | 单钩量 |
| `delivered_num` | `INT NOT NULL DEFAULT 0` | 双钩量 |
| `click_uv_num` | `INT NOT NULL DEFAULT 0` | 点击 UV |
| `used_account_count` | `INT NOT NULL DEFAULT 0` | 使用号数（去重） |
| `banned_account_count` | `INT NOT NULL DEFAULT 0` | 封号数 |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：

| 索引 | 字段 | 说明 |
|---|---|---|
| `uq_hyperlink_stat_daily` | `tenant_id, stat_date, sender_country_iso2, recipient_country_iso2, account_type, task_type, is_short_link_enabled` | 幂等回填 |
| `idx_hyperlink_stat_daily_range` | `tenant_id, stat_date, id` | 日期范围扫描 |

### 7.2 为什么只做日粒度

分析页支持按日与按小时两种粒度。若两种粒度都预聚合：

```
维度基数 ≈ 发信国家(~50) × 被营销国家(~50) × 账号类型(2) × 任务模式(3) × 深度追踪(2) ≈ 30000 组合
日粒度：30000 行/天  × 90 天 ≈ 270 万行   → 可接受
时粒度：30000 × 24 行/天 × 90 天 ≈ 6480 万行 → 不可接受
```

因此：**日粒度落预聚合表，小时粒度在 `hyperlink_task_recipient` 上实时聚合**，
并沿用前端已有的"粒度最多 N 天"限制约束查询窗口。`idx_hyperlink_recipient_country`
是这条实时聚合路径的支撑索引。

`used_account_count` / `click_uv_num` / `banned_account_count` 是**去重计数，不可跨行相加**：
按周/按月查询时必须回源重算，不能对日行求和。这一约束要在 Service 层显式落实。

---

## 八、账号画像（待全局评审，可能不做）

超链任务的账号筛选比 `AccountQuery` 现有维度多出 6 项。逐项对账：

| hylb 筛选项 | armada 现状 | 结论 |
|---|---|---|
| 国家 / 排除国家 | `account.ws_phone` 区号 + `country_phone_prefix_mapping` 派生 | ✅ 已有，无需加列 |
| 大洲 `continent` | `country` 表可扩，或由 iso2 映射 | ✅ 走 `country` 主数据 |
| 账号类型 | `account.account_type` | ✅ 已有 |
| 分组 `group_ids` | `account.account_group_id` | ✅ 已有 |
| 渠道 `channel_ids` | `account.promotion_channel_id` | ✅ 已有 |
| 在线状态 | `account_state.login_state` | ✅ 已有 |
| 入库时间 | `account.created_at` | ✅ 已有 |
| 协议 `protocol_id` | `account.protocol_id` | ✅ 已有 |
| 封号码 / 原因 | `account_state.block_error_code` / `block_reason` | ✅ 已有 |
| 导入方式（六段/全参） | `account_import_batch.import_format`（1六段 2JSON 3全参） | ⚠ 需经 `account_import_detail` 关联，或在 `account` 冗余 |
| **存活天数** | 无 | ✅ 由 `now - account.created_at` 派生，**不落列** |
| **设备类型**（主设备/分身） | 无 | ✅ 由 `account.protocol_id` 派生，**不落列**（见 8.1） |
| **最近登录时间** | `account_online_attempt_log` | ✅ armada 自有，无需协议层 |
| **好友数** | 无 | 🔶 需协议层**主动查**，两侧口径不同（见 8.2） |
| **是否允许拉群** | 无 | 🔶 需协议层**主动查**（见 8.2） |
| **注册天数** | 无 | ❌ **WhatsApp 不暴露，两条协议都拿不到**（见 8.3） |

### 8.1 设备类型不需要新列

`wid_type`（`native6`=主设备 / `web5`=分身设备）**已经在 armada 里了**，只是没人这么叫它：

```java
// com.armada.platform.protocol.model.enums.ProtocolBackend
ProtocolBackend.fromProtocolId(account.protocol_id)  // → WEB | ANDROID
```

对应关系：`ANDROID` = 主设备(native6)，`WEB` = 分身设备(web5)。

`account.protocol_id` 已有 `idx_tenant_protocol_account` 索引，筛选直接走它。
**不落 `wid_type` 列**——落了就是同一事实的第二处表示，正是规范一.2 禁止的分歧。

### 8.2 方案：`account_profile`（1:1），只承载真正需要采集的 2 个字段

理由：
1. `account` 是**跨业务共享的身份主表**，规范五明令"任何改动走全局评审，禁某业务私自加列"。
2. 这几个字段的写入特征是**协议层异步高频回写**，与身份主表的低频写入是两个关注点。
   armada 已有先例：`account_state`（高频 Kafka 回写）就是从 `account` 拆出去的。
   `account_profile` 沿用同一拆法，不发明新模式。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `account_id` | `BIGINT NOT NULL` | →`account.id` |
| `friend_count` | `INT` | 通讯录好友数；NULL=未采集 |
| `is_group_invite_allowed` | `TINYINT(1)` | 隐私设置是否允许被拉群；NULL=未采集 |
| `synced_at` | `BIGINT` | 最近一次画像同步时间(epoch 毫秒) |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：`uq_account_profile`（`tenant_id, account_id`）、
`idx_account_profile_friend`（`tenant_id, friend_count`）。

**采集路径（已核实，两条协议口径不同，须统一后再落列）**：

| 字段 | Web（Baileys） | Android（Go） |
|---|---|---|
| `friend_count` | 联系人靠 app-state 同步**被动到达**（`commands/contact-app-state-key.ts`、`contact-save-executor.ts`），协议层未落库计数，需新增 | 联系人已落 MySQL（`internal/service/axolotl/store/contacts.go` 的 `LoadContacts(ownerId)`），**COUNT 即得** |
| `is_group_invite_allowed` | `sock.fetchPrivacySettings()` 可读（`node_modules/baileys/lib/Socket/chats.d.ts:33`），返回 `{[key]: string}`，取 `groupadd` 项 —— **需主动请求** | `iq.go:219` 有 `case "privacy"` 分支，但当前只处理 status privacy，拉群隐私能力**待确认** |

两条链路都是**主动查**，WhatsApp 不会在账号上线时主动推送这两个值。因此还需定：
同步触发时机（上线后一次 / 定时刷新 / 任务前按需）与刷新频率——这本身是一次协议调用，
高频刷新同样有风控暴露。

**落列前置条件（硬约束）**：Android 侧 `is_group_invite_allowed` 能力确认 + 两侧
`friend_count` 口径统一 + 同步策略定稿。三者齐备前 `account_profile` 不进 Flyway，
超链账号筛选先按上表 ✅ 的 11 项交付。

### 8.3 注册天数：确认拿不到，需要产品重新定义

WhatsApp **不对外暴露账号注册时间**，Baileys 与 Go 协议均无此数据。

hylb 的账号筛选里 `retention_days`（存活天数）与 `register_days`（注册天数）是**两个独立字段**
（见 `readable/assets/account-filter-modal-BXDIvipG.js`），但存档里没有任何 tooltip 解释二者差别。
两种可能：

1. 「注册天数」= 号在**本系统**入库天数 → 那它与「存活天数」重复，armada 侧
   `now - account.created_at` 一个口径就够，不需要第二个字段。
2. 「注册天数」= 号源方提供的**号龄** → 那只能在账号导入时由号源带入，属于
   `account_import_detail` 的入参，与协议层无关。

**未澄清前不落列，前端隐藏该筛选项。**

---

## 九、Flyway 迁移编排

| 版本 | 文件 | 内容 |
|---|---|---|
| V140 | `V140__resource_asset.sql` | `marketing_template_file` 改名扩列 + 两张标签表 + 存量 `asset_name` 回填 |
| V141 | `V141__data_package.sql` | `data_package` / `data_package_phone` / `data_package_import` |
| V142 | `V142__hyperlink_template_strategy.sql` | `hyperlink_template` / `hyperlink_strategy` |
| V143 | `V143__hyperlink_task.sql` | `hyperlink_task` / `_content` / `_stat` / `_recipient` |
| V144 | `V144__hyperlink_click_ban.sql` | `hyperlink_click` / `hyperlink_task_ban` |
| V145 | `V145__hyperlink_stat_daily.sql` | `hyperlink_stat_daily` |
| （待定） | `V1xx__account_profile.sql` | 仅在 §8.2 验证通过后落 |

约束：

- 版本号跨分支提交前**核对防撞号**（`1.0.3-group` 曾发生 V117 撞号）。
- `ADD COLUMN` 一律用 `information_schema` 守卫保证幂等。
- V140 是**改名迁移**，须同时提供 `.harness/changes/hyperlink-marketing/db-migrations.sql`
  与 `rollback.sql`（回滚即 `RENAME TABLE resource_asset TO marketing_template_file` + `DROP COLUMN`）。
- schema 落地后重跑 `.harness/wiki/gen_datamodel.py` 刷新 `数据模型.md`，**禁手改**。
- 所有新列必须带 `COMMENT`（自动文档依赖它）。

---

## 十、待决问题

### 10.1 已决（2026-08-27）

| # | 决策 |
|---|---|
| 1 | 接口命名走 `/api/hyperlink-tasks` + camelCase，与现有 Controller 一致 |
| 2 | 数据包单次导入上限 **5000 行**；总量不限；覆盖模式清空+导入同事务（§3.3） |
| 3 | **不做**国家风险拦截，`blocked_rows` / `blocked_country_iso2s` 不落列（§3.3） |
| 4 | 设备类型（主设备/分身）由 `account.protocol_id` 派生，**不落 `wid_type` 列**（§8.1） |
| 5 | 存活天数由 `now - account.created_at` 派生，不落列 |
| 6 | **计费相关字段全部不做**：armada 无计费体系（见 10.2 第 3 条） |

### 10.2 未决

| # | 问题 | 影响 |
|---|---|---|
| 1 | `hyperlink_template` 独立 vs 归一进 `marketing_template`（§5.1.1） | 决定 P0 是否需要动生产中的群组营销 |
| 2 | `marketing_template_file` 改名的影响面是否接受（§6.1） | 不接受则退化为两张图片表，违反规范一.2 |
| 3 | 「注册天数」的产品定义（§8.3） | WhatsApp 不暴露注册时间；含义未定则该筛选项不做 |
| 4 | Android 协议的拉群隐私读取能力 + 两侧 `friend_count` 口径统一（§8.2） | 决定 `account_profile` 落 2 列还是 0 列 |
| 5 | 账号画像同步触发时机与刷新频率（§8.2） | 主动查协议本身有风控暴露，高频刷新会伤号 |
| 6 | `hyperlink_click` 的归档/分区策略与保留期 | 不定就是埋雷 |
| 7 | 深度追踪短链域名是否与买量 `promotion_domain` 隔离 | 共用域名时超链被封会连带买量落地页一起挂 |

> **勘误**：本文与设计文档早先提到的「armada 的 `balances` / `consume-stats` 体系」不存在。
> 那几个是 hylb 的接口。armada 的 Java 代码与全部 Flyway 迁移中**没有任何 balance / recharge
> 相关的表或类**，无计费体系。因此超链任务页的「当前余额 / 超链单价 / 估算落地率」在 armada
> 没有可对接的依赖，本模块不设计相关字段。
