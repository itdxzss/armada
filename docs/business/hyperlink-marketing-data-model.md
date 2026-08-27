# 超链营销数据模型

本文冻结「超链营销」模块的数据模型。**只落 schema 设计与论证，不实现 Controller/Service/Mapper。**

- 需求来源：`docs/superpowers/specs/2026-08-27-hyperlink-marketing-replication-design.md`
- 全局现状依据：`.harness/wiki/数据模型.md`（自动生成）
- 遵循：`.harness/rules/数据模型规范.md`

> ## 效力声明（2026-08-27 修订）
>
> 本文是超链营销的**目标数据模型唯一口径**。一期实施细节、接口、页面和测试以
> `docs/superpowers/specs/2026-08-27-hyperlink-data-template-phase1-design.md` 为准，两份文档的
> `data_package`、`data_package_phone`、`data_package_stat`、`data_package_import` 和
> `hyperlink_template` 字段定义必须一致。
>
> §4 任务族、§7 分析预聚合和 §8 账号画像是后续阶段的目标草案，开发前仍需结合当时协议能力复核；
> §6 通用素材是演进目标，一期不改名 `marketing_template_file`。Flyway 编号永远在实施前按目标分支
> 最高版本动态分配，本文不冻结具体数字。

---

## 一、设计原则

1. **超链任务与群组营销是两条目标链路，不合表**。`marketing_task_target` 的目标是「账号 × 群组」
   （`group_link_id` / `group_jid` 必居其一），超链的目标是「账号 × 手机号」。两者主键语义、唯一约束、
   状态机都不同，合表会得到一张一半列恒 NULL 的表。
2. **账号事实不复制**。`account` / `account_state` 仍是账号身份与在线、封禁、风控事实源；
   超链任务只保存执行时的号码与国家快照。
3. **当前号码池与历史投递分开**。`data_package_phone` 只表达当前代号码是否还能被领取；任务侧
   `hyperlink_task_recipient` 保存包、代次、导入批次、手机号和国家快照，投递尝试保存协议结果。
4. **按聚合垂直拆分，不做宽表**。超链任务拆成「配置 / 消息内容 / 计数」三张 1:1 表
   （理由见 §4.1）。参照 `marketing_task` 已达 38 列的教训。
5. **图片引用使用稳定 AssetId 语义**。一期 ID 仍指向现有 `marketing_template_file`；未来通过
   兼容 Service 和双读迁移演进为通用 `resource_asset`，不在一期直接改名，也不复制图片字节。
6. **不落无采集链路的死列**。账号画像字段（好友数、注册天数、设备类型等）只有在协议层确认
   能采集后才落列，否则本期不做该筛选项（规范一.4）。
7. **点击流水不存原始 IP**，只存由 IP 派生的国家码，与 promotion 模块既有的隐私保留策略同向。

---

## 二、表清单

| 表 | 聚合归属 | 状态 | 作用 |
|---|---|---|---|
| `data_package` | 资源池 | 新建 | 号码包主表，保存当前代指针和总数 |
| `data_package_phone` | 资源池 | 新建 | 按代次保存号码及当前池状态 |
| `data_package_stat` | 资源池 | 新建 | 包级池状态读模型，避免列表聚合号码表 |
| `data_package_import` | 资源池 | 新建 | 号码导入批次与解析结果 |
| `resource_asset` | 公共（文件） | 后续演进 | 通用素材目标；一期不建、不改名 |
| `resource_asset_tag` | 公共（文件） | 后续演进 | 素材标签字典 |
| `resource_asset_tag_ref` | 公共（文件） | 后续演进 | 素材 × 标签关联 |
| `hyperlink_template` | hyperlink | 新建 | 超链消息模板 |
| `hyperlink_strategy` | hyperlink | 新建 | 超链发送策略预设 |
| `hyperlink_task` | hyperlink | 新建 | 超链任务配置与生命周期 |
| `hyperlink_task_content` | hyperlink | 新建 | 任务消息内容快照（1:1） |
| `hyperlink_task_stat` | hyperlink | 新建 | 任务级计数（1:1，高频回写） |
| `hyperlink_task_recipient` | hyperlink | 新建 | 执行目标，一行=一个收件人 |
| `hyperlink_delivery_attempt` | hyperlink | 新建 | 一次协议发送尝试/物理消息分片 |
| `hyperlink_click` | hyperlink | 新建 | 点击流水 |
| `hyperlink_task_ban` | hyperlink | 新建 | 任务期间账号封号事实 |
| `hyperlink_stat_daily` | hyperlink | 新建 | 市场分析日粒度预聚合 |
| `account_profile` | account | **待全局评审** | 账号画像，承载新增筛选维度（§7） |

上述为全链路目标清单，不代表一期全部落库。一期只创建四张数据包表和 `hyperlink_template`；
后续业务表在对应菜单实施时再建。所有业务表都带 `tenant_id`，**无需登记
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
| `remark` | `VARCHAR(255)` | 备注 |
| `current_generation` | `INT NOT NULL DEFAULT 1` | 当前可见号码代次；覆盖成功后原子递增 |
| `phone_count` | `INT NOT NULL DEFAULT 0` | 当前代号码总数 |
| `version` | `INT NOT NULL DEFAULT 1` | 名称/备注乐观锁版本；统计更新不修改它 |
| `created_by` | `BIGINT` | 创建人 user_id |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |
| `deleted_at` | `BIGINT` | 软删时间；NULL=未删 |
| `is_active` | `TINYINT`（生成列） | 软删唯一键辅助：活行=1 软删=NULL |

索引：

| 索引 | 字段 | 说明 |
|---|---|---|
| `uq_data_package_name` | `tenant_id, package_name, is_active` | 同租户下包名唯一 |
| `idx_data_package_tenant` | `tenant_id, deleted_at, id` | 列表分页 |

发送、送达和失败等高频统计不放在主表，统一由 §3.3 `data_package_stat` 提供。

### 3.2 data_package_phone（号码明细）

一行 = 包内一个号码。**这是号码的唯一事实源**。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `data_package_id` | `BIGINT NOT NULL` | →`data_package.id` |
| `generation` | `INT NOT NULL` | 所属代次；查询当前号码必须等于包的 `current_generation` |
| `source_import_id` | `BIGINT NOT NULL` | 产生该成员的导入批次 |
| `phone` | `VARCHAR(32) NOT NULL` | 完整国际号码，只含数字 |
| `country_iso2` | `CHAR(2)` | 导入时由区号经 `country_phone_prefix_mapping` 解析并快照；无法解析为 NULL |
| `pool_status` | `TINYINT NOT NULL DEFAULT 1` | 1未使用 2已领取 3发送成功 4已送达 5可重试失败 6未注册 |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：

| 索引 | 字段 | 说明 |
|---|---|---|
| `uq_data_package_phone` | `tenant_id, data_package_id, generation, phone` | 同代包内去重；新代允许重新导入同号 |
| `idx_data_package_phone_pick` | `tenant_id, data_package_id, generation, pool_status, id` | 当前代任务领号扫描 |
| `idx_data_package_phone_country` | `tenant_id, data_package_id, generation, country_iso2, id` | 当前代国家集合与筛选 |
| `idx_data_package_phone_import` | `tenant_id, source_import_id, id` | 导入追溯 |

> `country_iso2` 是**有意的反规范化**：导入时算一次，避免号码明细分页与国家分布统计每次
> join 区号映射表。区号映射表是平台元数据、极少变更，快照漂移风险可接受。

本表没有软删列。覆盖先写下一代，再原子更新 `data_package.current_generation`。代次 `g` 的退役时间
取成功切到 `g+1` 的覆盖导入批次 `finished_at`；退役满 30 天后，后台任务每批最多 2000 行硬删。
历史任务不保存本表主键，因此清理不会破坏历史事实。

### 3.3 data_package_stat（包级池状态读模型）

一行 = 一个数据包当前代的状态计数，主键与包 ID 相同。

| 字段 | 类型 | 说明 |
|---|---|---|
| `data_package_id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `generation` | `INT NOT NULL` | 本行统计对应的代次 |
| `unused_count` | `INT NOT NULL DEFAULT 0` | 未使用数 |
| `claimed_count` | `INT NOT NULL DEFAULT 0` | 已领取数 |
| `sent_count` | `INT NOT NULL DEFAULT 0` | 当前停留在单钩的数量 |
| `delivered_count` | `INT NOT NULL DEFAULT 0` | 当前已送达数量 |
| `retryable_failed_count` | `INT NOT NULL DEFAULT 0` | 当前可重试失败数量 |
| `unregistered_count` | `INT NOT NULL DEFAULT 0` | 当前确认未注册数量 |
| `updated_at` | `BIGINT NOT NULL` | 最近投影时间 |
| `reconciled_at` | `BIGINT` | 最近内部全量校准时间 |

索引：`uq_data_package_stat`（`tenant_id, data_package_id`）。列表按主键一对一 JOIN，禁止对号码表
现场 GROUP BY。导入事务同步维护本表；未来领取、发送、ACK 和失败通过可靠事件批量、幂等更新。
内部 reconciliation 可按当前代重算，但不开放租户 `/recount` API。

### 3.4 data_package_import（导入批次）

一行 = 一次 TXT 上传。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `data_package_id` | `BIGINT NOT NULL` | →`data_package.id` |
| `generation` | `INT` | 本次写入代次；审计刚创建时为空，锁定包后写入目标代次 |
| `import_mode` | `TINYINT NOT NULL` | 1追加 2覆盖 |
| `status` | `TINYINT NOT NULL` | 1处理中 2成功 3失败 |
| `source_file_name` | `VARCHAR(255) NOT NULL` | 上传文件原名，不保存原 TXT |
| `total_rows` | `INT NOT NULL DEFAULT 0` | 解析总行数 |
| `accepted_rows` | `INT NOT NULL DEFAULT 0` | 实际生效手机号行数 |
| `invalid_rows` | `INT NOT NULL DEFAULT 0` | 格式非法行数 |
| `duplicated_rows` | `INT NOT NULL DEFAULT 0` | 文件内或当前代包内重复行数 |
| `failure_reason` | `VARCHAR(512)` | 脱敏失败摘要 |
| `created_by` | `BIGINT` | 上传人 user_id |
| `created_at` | `BIGINT NOT NULL` | 开始时间 |
| `finished_at` | `BIGINT` | 完成时间 |

索引：

- `idx_data_package_import_pkg`（`tenant_id, data_package_id, created_at, id`）：包内导入历史。
- `idx_data_package_import_generation`（`tenant_id, data_package_id, generation, status, finished_at`）：旧代清理资格。
- `idx_data_package_import_status`（`tenant_id, status, created_at, id`）：失败和超时处理中恢复。

> `import_mode` 用 TINYINT 而非 hylb 的 `overwrite`/`append` 字符串，遵循规范二「状态/枚举列 TINYINT」。

**已决**（2026-08-27）：

- **单次导入上限 5000 行**。单包安全阈值由
  `armada.hyperlink.data-package.max-phones` 配置，默认 500000；它是防误操作阈值，不是数据库约束。
- **不做国家风险拦截**。因此 `blocked_rows` / `blocked_country_iso2s` 两列**不落**——
  没有写入方的列就是死列（规范一.4）。将来若要做拦截，届时用 Flyway 加列。
- 覆盖模式先完整解析并写入下一代，再在同一事务中原子切换包指针和统计；不在关键事务里删除旧代。
- 审计批次用独立短事务先记 `PROCESSING`。业务成功后记 `SUCCESS`；业务回滚后用独立事务记
  `FAILED`，超时处理中记录由恢复任务收敛，避免失败审计跟着业务事务一起消失。

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
| `data_package_generation` | `INT` | 启动领取时冻结的包代次；未启动可为 NULL |
| `data_package_name_snapshot` | `VARCHAR(128)` | 数据包名称快照 |
| `source_template_id` | `BIGINT` | 内容来源模板 ID；模板只带入内容，不建立运行时强依赖 |
| `source_template_version` | `INT` | 带入内容时的模板版本 |
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

> **模板/策略是弱引用**：前端语义是“引用后复制”。任务自持内容和配置快照，模板后续修改不影响任务。
> 删除保护或引用提示通过 `source_template_id` 实时查询，不在模板表维护引用计数。

### 4.3 hyperlink_task_content（消息内容快照，1:1）

主键即 `hyperlink_task_id`，不另设自增 id。

| 字段 | 类型 | 说明 |
|---|---|---|
| `hyperlink_task_id` | `BIGINT` | 主键，→`hyperlink_task.id` |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `message_schema_version` | `INT NOT NULL DEFAULT 1` | 消息内容契约版本 |
| `message_type` | `TINYINT NOT NULL` | 1=单图文 2=双图文 3=普通按钮 4=卡片按钮 |
| `title` | `VARCHAR(512) NOT NULL` | 消息标题 / 按钮气泡上方加粗大字 |
| `content` | `TEXT` | 单图文正文≤2000；按钮气泡正文≤200 |
| `link_description` | `VARCHAR(512)` | 链接描述（标题下灰色小字） |
| `promotion_link` | `VARCHAR(2048)` | 单图文原始推广链接 |
| `buttons` | `JSON` | 使用一期详细设计 §5.2 的版本化数组；一期恰好一个 `CTA_URL` |
| `card_text` | `VARCHAR(500)` | 卡片底部小字 |
| `link_preview_asset_id` | `BIGINT` | 链接预览图稳定素材 ID |
| `body_main_asset_id` | `BIGINT` | 正文主图 / 卡片 header 图稳定素材 ID |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

> 任务内容与 `hyperlink_template` 必须复用同一个 `HyperlinkMessageContent` DTO、字段长度和校验器。
> 一期未开放的按钮类型由后端明确拒绝，不能让模板保存成功、任务启动后才失败。

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

一行 = 本任务的一个收件人及其聚合状态。一次收件人可能有多次重试或多个物理消息，具体发送记录见 §4.6。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `hyperlink_task_id` | `BIGINT NOT NULL` | →`hyperlink_task.id` |
| `data_package_id` | `BIGINT NOT NULL` | 来源包 ID |
| `data_package_generation` | `INT NOT NULL` | 领取时的来源包代次 |
| `source_import_id` | `BIGINT NOT NULL` | 来源导入批次 |
| `recipient_phone_snapshot` | `VARCHAR(32) NOT NULL` | 收件人号码快照 |
| `recipient_country_iso2_snapshot` | `CHAR(2)` | 收件人国家快照 |
| `short_code` | `VARCHAR(16)` | 深度追踪短码；未开启深度追踪为 NULL |
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
| `uq_hyperlink_recipient` | `tenant_id, hyperlink_task_id, recipient_phone_snapshot` | 同任务同号码只生成一条收件人 |
| `uq_hyperlink_recipient_short_code` | `short_code` | 短码全局唯一；NULL 不参与唯一约束 |
| `idx_hyperlink_recipient_task` | `tenant_id, hyperlink_task_id, send_status, id` | 明细分页 + 状态筛选 |
| `idx_hyperlink_recipient_source` | `tenant_id, data_package_id, data_package_generation, id` | 包与代次追溯 |
| `idx_hyperlink_recipient_click` | `tenant_id, hyperlink_task_id, click_count` | UV 统计与「从来不点」分析 |

> **点击 UV 从这张表算，不从 `hyperlink_click` 算**：`COUNT(*) WHERE click_count > 0`
> 走索引即可，而 `hyperlink_click` 上的 `COUNT(DISTINCT recipient_id)` 在千万行量级会拖垮分析页。
> 这是 `click_count` / `first_visit_at` / `last_visit_at` 三个冗余列存在的唯一理由。

`hyperlink_task_recipient` **不保存 `data_package_phone_id`**。旧代号码按保留期清理，持久强引用最终会
悬空；包 ID、代次、导入批次和手机号/国家快照已足够完成历史展示、审计和分析。

### 4.6 hyperlink_delivery_attempt（投递尝试）

一行 = 对某个收件人的一次协议发送尝试中的一个物理消息分片。双图文和重试都可能产生多行。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `hyperlink_task_id` | `BIGINT NOT NULL` | 任务 ID，便于分区查询 |
| `recipient_id` | `BIGINT NOT NULL` | →`hyperlink_task_recipient.id` |
| `attempt_no` | `INT NOT NULL` | 第几次尝试，从 1 起 |
| `message_part_no` | `INT NOT NULL DEFAULT 1` | 本次尝试的第几个物理消息 |
| `account_id` | `BIGINT NOT NULL` | 本次实际发信账号 |
| `sender_phone_snapshot` | `VARCHAR(32)` | 发信号码快照 |
| `sender_country_iso2_snapshot` | `CHAR(2)` | 发信账号国家快照 |
| `protocol_id` | `VARCHAR(32) NOT NULL` | 协议标识快照 |
| `protocol_message_id` | `VARCHAR(128)` | 协议消息 ID，ACK 回关联键 |
| `status` | `TINYINT NOT NULL` | 待发/发送中/单钩/双钩/已读/失败 |
| `fail_code` | `VARCHAR(32)` | 本次失败码 |
| `fail_reason` | `VARCHAR(255)` | 本次失败原因 |
| `sent_at` / `delivered_at` / `read_at` / `failed_at` | `BIGINT` | 各阶段时间 |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：

- `UNIQUE(tenant_id, recipient_id, attempt_no, message_part_no)`：重试与分片幂等。
- `UNIQUE(tenant_id, account_id, protocol_id, protocol_message_id)`：ACK 唯一回关联；NULL 在发送前不参与冲突。
- `INDEX(tenant_id, hyperlink_task_id, status, id)`：任务投递状态扫描。

recipient 保存最终聚合状态，attempt 保存每次真实发送。不能把多个协议消息 ID 拼进 recipient 的字符串列。

### 4.7 hyperlink_click（点击流水）

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

### 4.8 hyperlink_task_ban（封号事实）

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
| `message_schema_version` | `INT NOT NULL DEFAULT 1` | 消息内容契约版本 |
| `title` | `VARCHAR(512) NOT NULL` | 消息标题 / 按钮气泡上方加粗大字 |
| `content` | `TEXT` | 单图文正文≤2000；按钮气泡正文≤200 |
| `link_description` | `VARCHAR(512)` | 单图文链接描述 |
| `promotion_link` | `VARCHAR(2048)` | 单图文原始推广链接 |
| `buttons` | `JSON` | 与 §4.3 相同的版本化按钮数组；一期恰好一个 `CTA_URL` |
| `card_text` | `VARCHAR(500)` | 卡片底部小字 |
| `link_preview_asset_id` | `BIGINT` | 链接预览图稳定素材 ID |
| `body_main_asset_id` | `BIGINT` | 正文主图 / 卡片 header 图稳定素材 ID |
| `remark` | `VARCHAR(255)` | 备注 |
| `version` | `INT NOT NULL DEFAULT 1` | 乐观锁和内容来源版本 |
| `created_by` | `BIGINT` | 创建人 user_id |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |
| `deleted_at` | `BIGINT` | 软删时间；NULL=未删 |
| `is_active` | `TINYINT`（生成列） | 软删唯一键辅助 |

索引：`uq_hyperlink_template_name`（`tenant_id, template_name, is_active`）、
`idx_hyperlink_template_tenant`（`tenant_id, deleted_at, id`）。

模板与任务内容必须共用同一个 `HyperlinkMessageContent` DTO 和校验器。模板完整保存
`promotion_link` 与按钮目标 URL；任务选择模板时复制内容并记录 `source_template_id/version`。
`taskRefCount` 由任务表实时查询，一期任务未上线时 API 明确返回 0，不落冗余列。

#### 5.1.1 与 `marketing_template` 的关系（已冻结）

这是本设计中**唯一触碰规范一.2「一个事实一处存」的地方**，必须说清。

现有 `marketing_template` 也是 WhatsApp 消息模板，字段有重合，但一期采用独立
`hyperlink_template`：

理由：
1. 枚举不同构。`marketing_template.link_mode` 是 `1=普通超链 2=按钮超链 3=图文内容`，
   超链是 `1=单图文 2=双图文 3=普通按钮 4=卡片按钮`。合表必须重新归一枚举，
   而群组营销在生产运行中。
2. 超链模板有 4 个 `marketing_template` 没有的字段（`title`、`link_description`、
   `card_text`、第二张图），而 `marketing_template.mention_all` 是群消息语义，
   私聊场景恒为 0——合表两边都会产生恒 NULL / 恒 0 的列，正是规范一.4 禁的死列。
3. 规范五「跨业务共享表的任何改动走全局评审，禁某业务私自加列」——`marketing_template`
   是群组营销在用的表，超链业务不应私自扩它。

代价是两套业务模板并存。边界控制为：共享稳定素材 ID 语义，但不强行共享一套业务枚举或表；
若未来要归一，必须作为独立迁移项目完整回归群营销，不能夹在超链一期里做。

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

## 六、公共：图片素材演进

### 6.1 一期复用与未来 `resource_asset`

现状：`marketing_template_file` 只有上传与取字节两个能力（8 列，`content` 为 `MEDIUMBLOB`）。
超链的「图片素材」页需要列表、命名、标签、引用计数、删除保护、批量上传。

一期不改表名、不复制图片字节。`link_preview_asset_id` / `body_main_asset_id` 的值直接指向
`marketing_template_file.id`，通过 `MarketingTemplateFileService` 校验租户、JPEG 格式和 500KB 上限。

未来素材菜单上线时，先提供通用 `ResourceAssetService` 兼容读取现有 ID，再按双读、回填、切流步骤
演进。目标 `resource_asset` 至少增加：

| 字段 | 类型 | 说明 |
|---|---|---|
| `asset_name` | `VARCHAR(128) NOT NULL` | 素材名称；存量行迁移时取 `original_filename` 回填 |
| `width` / `height` | `INT` | 图片像素尺寸；解析失败为 NULL |
| `ref_count` | `INT NOT NULL DEFAULT 0` | 被模板与任务引用总次数，删除保护用 |
| `created_by` | `BIGINT` | 上传人 user_id；存量行为 NULL |
| `updated_at` | `BIGINT NOT NULL` | epoch 毫秒；存量行取 `created_at` 回填 |

保留现有文件的稳定 ID、租户、原文件名、类型、大小、内容、创建和删除时间语义。

索引新增：`idx_resource_asset_name`（`tenant_id, deleted_at, asset_name`）供按名搜索。

迁移顺序必须是：兼容 Service/API → 存量回填和一致性校验 → 新旧调用方切流 → 确认无旧实例后再决定
是否改物理表名。不得先执行 `RENAME TABLE`，否则滚动发布中的旧 Mapper 会直接报表不存在。

> **遗留风险（不在本期解决，但登记在案）**：`content` 是存在 MySQL 里的 `MEDIUMBLOB`。
> 素材库支持批量上传后，主库体积会随素材量线性增长。本期沿用现状（不引入对象存储这个新基础设施），
> 但一旦素材量级起来，迁对象存储要作为独立技术债项立项。

### 6.2 `resource_asset_tag` / `resource_asset_tag_ref`（未来）

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

| 阶段 | 内容 |
|---|---|
| 一期数据包 | `data_package` / `data_package_phone` / `data_package_stat` / `data_package_import` |
| 一期模板 | `hyperlink_template` |
| 一期菜单权限 | 超链数据包、超链营销模板和对应 RBAC |
| 后续任务 | `hyperlink_strategy` / `hyperlink_task` / `_content` / `_stat` / `_recipient` / `_delivery_attempt` |
| 后续点击分析 | `hyperlink_click` / `hyperlink_task_ban` / `hyperlink_stat_daily` |
| 后续公共素材 | `resource_asset` 兼容迁移和两张标签表 |
| 待验证账号画像 | 仅在 §8.2 验证通过后创建 `account_profile` |

约束：

- 实施前同步目标分支并从全局最高 Flyway 版本继续编号；本文不写死版本号。
- `ADD COLUMN` 一律用 `information_schema` 守卫保证幂等。
- 公共素材未来迁移必须先上兼容代码，不能把直接改表名作为第一步。
- schema 落地后重跑 `.harness/wiki/gen_datamodel.py` 刷新 `数据模型.md`，**禁手改**。
- 所有新列必须带 `COMMENT`（自动文档依赖它）。

---

## 十、待决问题

### 10.1 已决（2026-08-27）

| # | 决策 |
|---|---|
| 1 | 接口命名走 `/api/hyperlink-tasks` + camelCase，与现有 Controller 一致 |
| 2 | 数据包单次导入上限 **5000 行**；单包阈值可配置、默认 500000（§3.4） |
| 3 | 覆盖导入使用代际切换，不在关键事务内删除旧号码；旧代按保留期分批清理 |
| 4 | 包级状态统计放在 `data_package_stat`，不放主表、不开放租户 `recount` API |
| 5 | 任务收件人保存包代次/导入批次/号码/国家快照，不保存 `data_package_phone_id` |
| 6 | 超链模板独立于群营销模板；一期图片复用 `marketing_template_file`，不改表名 |
| 7 | **不做**国家风险拦截，`blocked_rows` / `blocked_country_iso2s` 不落列（§3.4） |
| 8 | 设备类型（主设备/分身）由 `account.protocol_id` 派生，**不落 `wid_type` 列**（§8.1） |
| 9 | 存活天数由 `now - account.created_at` 派生，不落列 |
| 10 | **计费相关字段全部不做**：Armada 无计费体系 |

### 10.2 未决

| # | 问题 | 影响 |
|---|---|---|
| 1 | 「注册天数」的产品定义（§8.3） | WhatsApp 不暴露注册时间；含义未定则该筛选项不做 |
| 2 | Android 协议的拉群隐私读取能力 + 两侧 `friend_count` 口径统一（§8.2） | 决定 `account_profile` 落 2 列还是 0 列 |
| 3 | 账号画像同步触发时机与刷新频率（§8.2） | 主动查协议本身有风控暴露，高频刷新会伤号 |
| 4 | `hyperlink_click` 的归档/分区策略与保留期 | 不定就是埋雷 |
| 5 | 深度追踪短链域名是否与买量 `promotion_domain` 隔离 | 共用域名时超链被封会连带买量落地页一起挂 |

> **勘误**：本文与设计文档早先提到的「armada 的 `balances` / `consume-stats` 体系」不存在。
> 那几个是 hylb 的接口。armada 的 Java 代码与全部 Flyway 迁移中**没有任何 balance / recharge
> 相关的表或类**，无计费体系。因此超链任务页的「当前余额 / 超链单价 / 估算落地率」在 armada
> 没有可对接的依赖，本模块不设计相关字段。
