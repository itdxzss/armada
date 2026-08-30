# 超链营销数据模型

本文冻结「超链营销」模块的数据模型。**只落 schema 设计与论证，不实现 Controller/Service/Mapper。**

- 需求来源：`docs/superpowers/specs/2026-08-27-hyperlink-marketing-replication-design.md`
- 全局现状依据：`.harness/wiki/数据模型.md`（自动生成）
- 遵循：`.harness/rules/数据模型规范.md`

> ## 效力声明（2026-08-28 冻结）
>
> 本文是超链营销的**目标数据模型唯一口径**。一期实施细节、接口、页面和测试以
> `docs/superpowers/specs/2026-08-27-hyperlink-data-template-phase1-design.md` 为准，两份文档的
> `data_package`、`data_package_phone`、`data_package_stat`、`data_package_import` 和
> `hyperlink_template` 字段定义必须一致。
> 前后端 HTTP 路径、JSON 字段、枚举与空值以同目录的
> `2026-08-27-hyperlink-data-template-phase1-api-contract.md` v1 为准。
>
> §4 任务族及其直接依赖的 §8 账号画像已经冻结为后续实施口径；§7 分析预聚合与 §6 通用素材
> 仍由各自菜单实施。Flyway 编号永远在实施前按目标分支最高版本动态分配，本文不冻结具体数字。
>
> **2026-08-27 二期校正**：§4 任务族、§5.2 策略、§6 素材、§7 分析已按静态前端事实修订
> （共 7 处，逐条列在 `2026-08-27-hyperlink-task-strategy-asset-analysis-design.md` §5.2）。
> 该文档是超链任务 / 策略 / 图片素材 / 市场分析四个模块的实施口径，与本文的字段定义必须一致。
> 超链任务后续六份页面/流程方案共享的 HTTP、DTO、枚举、指标、权限与错误合同，以
> `docs/superpowers/specs/2026-08-28-hyperlink-task-shared-contract.md` v1.1 为准。

### 事实边界与最终表数

- 竞品前端只能证明页面字段、接口数据集、动作、弹框和业务状态，**不能证明竞品数据库有几张表**。
- 本文中的表名、拆分和索引是 Armada 根据已确证业务事实、现有 MySQL/MyBatis 约束和数据量做出的
  物理设计，不伪装成竞品库表逆向结果。
- 超链任务最终使用 **10 张表**：3 张业务事实表、6 张执行/计费状态表、1 张专用查询投影表；另有
  **1 张共享硬依赖** `account_profile`。表数不是目标，判断标准是一个业务事实只存一处、发送主链可恢复、
  高频列表与详情查询不现场 GROUP BY 百万级流水。
- 用户已冻结口径：**同一任务内，一个收信号码只发送一次**。竞品详情是一位收信人一行，未展示轮次投递或
  重试历史；周期模式的前端明确约束“每轮发信账号数”，不能据此反推为“每轮重发全部收信人”。因此不建
  `hyperlink_task_recipient_round`、`hyperlink_delivery_attempt` 和 `hyperlink_task_ban`。

---

## 一、设计原则

1. **超链任务与群组营销是两条目标链路，不合表**。`marketing_task_target` 的目标是「账号 × 群组」
   （`group_link_id` / `group_jid` 必居其一），超链的目标是「账号 × 手机号」。两者主键语义、唯一约束、
   状态机都不同，合表会得到一张一半列恒 NULL 的表。
2. **账号事实不复制**。`account` / `account_state` 仍是账号身份与在线、封禁、风控事实源；
   超链任务只保存执行时的号码与国家快照。
3. **当前号码池与历史投递分开**。`data_package_phone` 只表达当前代号码是否还能被领取；任务侧
   `hyperlink_task_recipient` 保存号码、实际发信账号、协议结果与点击投影，成为本任务唯一发送事实。
4. **按工作负载拆分，但不制造 1:1 明细层**。配置、消息、任务运行态、调度轮次、唯一收信人发送事实和
   查询投影分别承担不同负载（理由见 §4.1）；一个 recipient 只发送一次，不再为未被竞品证明的“一人多轮”
   和“多次独立发送尝试”增加百万级明细表。
5. **图片引用使用稳定 AssetId 语义**。一期 ID 仍指向现有 `marketing_template_file`；未来通过
   兼容 Service 和双读迁移演进为通用 `resource_asset`，不在一期直接改名，也不复制图片字节。
6. **竞品可见账号画像不静默删除**。好友数、注册天数、允许拉群、轮号状态和五类来源进入
   `account_profile`；数据采集未打通时是任务上线硬依赖，不能通过隐藏筛选项规避。
7. **深度归因保存首触快照，不保存逐次点击流水**。竞品是一位收信人一行，只展示累计访问次数、首末访问
   时间和一套 IP/user-agent/国家/设备字段；这些都并入 recipient，通过敏感权限、审计和 90 天清理控制风险。
8. **短码绑定唯一 recipient 发送事实**。同一任务、同一收信号码只发送一次，实际发信账号也冻结在
   recipient；短码直接唯一定位 recipient，点击事件只保存 `recipient_id`，不另建短链表或 attempt 表。

---

## 二、表清单

| 表 | 聚合归属 | 状态 | 作用 |
|---|---|---|---|
| `data_package` | 资源池 | 已落地 | 号码包主表，保存当前代指针和总数 |
| `data_package_phone` | 资源池 | 已落地 | 按代次保存号码及当前池状态 |
| `data_package_stat` | 资源池 | 已落地 | 包级池状态读模型，避免列表聚合号码表 |
| `data_package_import` | 资源池 | 已落地 | 号码导入批次与解析结果 |
| `marketing_template_file` | 公共（文件） | 二期加列 | 素材事实源；加 `asset_name`/`width`/`height`/`created_by`/`updated_at`，**不新建第二张素材表、不改名** |
| `resource_asset_tag` | 公共（文件） | 二期新建 | 素材标签字典 |
| `resource_asset_tag_ref` | 公共（文件） | 二期新建 | 素材（`file_id`）× 标签关联 |
| `hyperlink_template` | hyperlink | 已落地；任务期扩标题长度 | 超链消息模板 |
| `hyperlink_strategy` | hyperlink | 新建 | 超链发送策略预设 |
| `hyperlink_task` ✅ | hyperlink | 新建 | 超链任务低频配置与冻结快照 |
| `hyperlink_task_content` ✅ | hyperlink | 新建 | 任务消息内容快照（1:1） |
| `hyperlink_task_runtime` ✅ | hyperlink | 新建 | 任务级生命周期与列表聚合（1:1，分钟级投影） |
| `hyperlink_task_round` | hyperlink | 新建 | 每轮调度时机、发信账号选择、受众分配计数与恢复状态 |
| `hyperlink_task_account_usage` ✅ | hyperlink | 新建 | 任务×账号累计成功槽位、在途并发与跨轮可用状态 |
| `hyperlink_task_round_account` | hyperlink | 新建 | 每轮已选账号集合与稳定分配顺序 |
| `hyperlink_task_recipient_claim` | hyperlink | 新建 | 大包收件人领取游标、代次操作互斥、租约与失败释放状态 |
| `hyperlink_task_recipient` ✅ | hyperlink | 新建 | 唯一发送事实，一行=任务内一个收信号码的一次发送 |
| `hyperlink_task_account_stat` ✅ | hyperlink | 新建 | 任务×发信账号查询投影，支撑默认统计排序分页 |
| `hyperlink_billing_reservation` | hyperlink | 新建 | 任务级报价与冻结/结算/释放状态，不复制钱包总账 |
| `hyperlink_stat_daily` | hyperlink | 新建 | 市场分析日粒度预聚合 |
| `hyperlink_stat_hourly` | hyperlink | 新建 | 市场分析小时粒度滚动 8 天预聚合 |
| `account_profile` | account | **共享硬依赖，需全局评审** | 账号画像，承载新增筛选维度（§8） |

> `✅` 表示该表的数据模型已经过用户逐项确认，不表示代码或数据库迁移已经完成。

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
| `deleted_by` | `BIGINT` | 删除人 user_id |
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
| `claimed_by_hyperlink_task_id` | `BIGINT` | 任务期新增；当前/历史领取任务，未领取或已释放为 NULL |
| `claimed_at` | `BIGINT` | 任务期新增；最近一次领取时间 |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：

| 索引 | 字段 | 说明 |
|---|---|---|
| `uq_data_package_phone` | `tenant_id, data_package_id, generation, phone` | 同代包内去重；新代允许重新导入同号 |
| `idx_data_package_phone_pick` | `tenant_id, data_package_id, generation, pool_status, id` | 当前代任务领号扫描 |
| `idx_data_package_phone_country` | `tenant_id, data_package_id, generation, country_iso2, id` | 当前代国家集合与筛选 |
| `idx_data_package_phone_import` | `tenant_id, source_import_id, id` | 导入追溯 |
| `idx_data_package_phone_claim` | `tenant_id, claimed_by_hyperlink_task_id, pool_status, id` | 任务领号恢复与精确释放 |

> `country_iso2` 是**有意的反规范化**：导入时算一次，避免号码明细分页与国家分布统计每次
> join 区号映射表。区号映射表是平台元数据、极少变更，快照漂移风险可接受。
>
> `pool_status=5 可重试失败` 是号码池对**后续其他任务是否可再次领取**的资源状态，不表示当前任务会给同一
> recipient 再发一次；当前任务仍受 `uq_hyperlink_recipient` 和唯一 `command_id` 约束。

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

### 4.1 按查询与运行负载拆分

表数不作为优化指标，但相同粒度也不能重复建模。执行链仍以单任务 50 万 recipient 作为防失控容量基线，每个
收信号码只生成一条发送事实；周期轮次只负责分批选择发信账号、领取尚未发送的 recipient，不重复生成收信人
投递。当前业务的单任务实际发送量预期不超过 10 万，访问趋势是低频详情查询，因此直接从 recipient 聚合；
账号统计需要默认排序、分页和导出，继续保留累计投影。最终拆分如下：

| 表 | 角色 | 主要读写路径 |
|---|---|---|
| `hyperlink_task` | 低频配置事实 | 创建/未开始编辑、列表 JOIN |
| `hyperlink_task_content` | 长文本/JSON 消息事实 | 详情与发送加载；列表不读 |
| `hyperlink_task_runtime` | 任务级状态 + 列表投影 | 生命周期条件更新；发送指标分钟级批量写，点击指标原子增量 |
| `hyperlink_task_recipient` | 任务内唯一受众 + 唯一发送事实 | 领号、派发/ACK、详情分页、点击 UV CAS、指标投影 |
| `hyperlink_task_round` | 一轮可恢复执行状态 | 调度 due scan、选号、分配计数与轮次审计 |
| `hyperlink_task_account_usage` | 任务×账号执行状态 | 同步占用成功槽位/在途并发、跨轮上限、账号失效事实 |
| `hyperlink_task_round_account` | 轮次×账号分配状态 | 固化每轮选中账号，调度重启不重新随机选号 |
| `hyperlink_task_recipient_claim` | 受众领取作业状态 | 50 万号码按批领取/释放，代次操作互斥且可断点恢复 |
| `hyperlink_billing_reservation` | 任务级计费状态 | 全包报价、外部账务幂等、恢复、结算与释放 |
| `hyperlink_task_account_stat` | 任务×账号查询投影 | 账号 Tab 默认排序分页，不扫 recipient 大表 |

明确不建四类重复模型：`hyperlink_task_recipient_round`、`hyperlink_delivery_attempt`、
`hyperlink_task_ban` 和独立短链映射表。recipient 已经是任务内唯一逻辑发送；协议超时恢复重放同一个
`command_id`，不创建第二次业务发送。账号失效事实并入 task_account_usage，短码直接落 recipient。
账号累计统计仍是默认排序分页所需的读模型，可从 recipient 事实重建，不取代事实源。访问趋势按竞品的首访
归属口径直接聚合 recipient，不再单独保存 30 分钟桶。

以 50 万 recipient 为容量基线，核心路径的扫描上界固定为：

| 路径 | 读取起点 | 目标扫描量 |
|---|---|---|
| 任务列表 | task 分页后 1:1 JOIN runtime | 每任务 1 行，不聚合 recipient |
| 到期调度 | round `status + next_dispatch_at` 索引 | 只读本批 due round，不从 recipient 推断轮次 |
| 每轮选号/恢复 | round_account + account_usage 唯一键/状态索引 | 只读已选集合与仍有成功槽位的账号，不按 recipient COUNT 选号 |
| 首次领号/失败释放 | recipient_claim 游标 + phone claim 索引 | 每批固定行数，不持有覆盖 50 万行的长事务 |
| 本轮派发/恢复 | recipient `round_id + status + next_dispatch_at` 索引 | 只读本轮待发或超时发送中行 |
| 收信人列表 | recipient 直接索引分页 | 页面字段均在唯一发送事实，不 JOIN 发送流水 |
| 账号统计（无时间） | account_stat 指标索引 + account_usage 展示快照 | 指标直接排序分页，小规模 1:1 JOIN 不扫 recipient |
| 账号统计（有时间） | recipient 任务×发送时间索引 + account_usage 展示快照 | 最多扫描该任务命中时间范围的 recipient；当前单任务上限 50 万 |
| 深度归因 | recipient `click_count` 与发信号码快照索引 | 一位收件人一行，直接取得首次发信账号归因 |
| 72 小时访问趋势 | recipient `first_visit_at` 范围索引 | 扫描该任务已点击 recipient；当前业务上界不超过 10 万行，聚合为最多 144 个 30 分钟区间 |
| 计费恢复 | task 1:1 billing 待操作/重试索引 | 只读本批到期的冻结/调整/结算/释放操作，不按轮次 SUM |

这些是实施阶段 `EXPLAIN ANALYZE` 与压测的验收路径；投影延迟、批量大小和作业并发度可调，
但不允许为了少一张表退回全表 GROUP BY。

读优化不能把 ACK 主链拖慢，写入规则固定如下：

1. 派发认领事务先条件占用 task_account_usage 的在途/成功槽位，再条件更新一条待发 recipient，写入
   round/account/协议快照和唯一 `command_id`；恢复只查询或重放同一 command，不创建第二条发送事实。
   ACK 主事务条件推进 recipient 并释放/兑现账号槽位，不逐条争抢 runtime、round 和账号查询投影。
2. recipient 投影器按租户用 `FOR UPDATE SKIP LOCKED` 每批认领状态发生变化的事实行，先按 task、round、
   `task + account` 合并增量，再分别更新 runtime/round 与累计账号投影，最后
   回写 `metrics_projected_status`。同一批 1000 条 ACK 不等于逐条更新任务热行。
3. 每次短链访问必须在同一事务内锁 recipient，更新累计次数/首末访问/首触环境快照；随后对 runtime 做列级
   原子增量：首次访问令 `click_uv_num + 1`，每次访问令 `click_total + 1`，并维护任务首末访问时间。当前没有
   逐次点击流水，访问趋势按 recipient.`first_visit_at` 分桶、`COUNT(*)` 计算新增 UV；PV 只从 runtime 返回真实
   累计总量，分桶明确不可用，不把累计 `click_count` 伪装进首访桶。当前单任务不超过 10 万，无需维护桶投影表。
4. 账号首次从有效变为封号/失效时，条件更新 task_account_usage 的 `invalid_*` 字段并原子增加
   runtime.`invalid_account_count`；`/ban-stats` 直接按该任务的 usage 行分组，不扫描 recipient。
5. reconciliation 是限速修复作业，不参与页面请求；只校准指定任务/时间段，禁止高峰期全租户扫表。

runtime 是共享读模型但不允许整行覆盖：生命周期服务只写状态/时间，recipient 投影器只写发送指标，
账号首次实际派发时增加 `used_account_count`，公网点击入口只原子更新点击指标，usage 首次失效时只增加
`invalid_account_count`。所有写方使用列级原子 UPDATE；否则两个投影器并发时会把对方的新值覆盖。

高并发事务统一锁顺序，实施不得各 Service 自由发挥：派发为
`runtime → round → task_account_usage → account → recipient`，ACK 为 `task_account_usage → recipient`，
轮次收敛为 `round → runtime`，点击为 `recipient → runtime`，领号批次为
`recipient_claim → data_package_stat → data_package_phone → recipient`。先无锁读 ID 再按此顺序条件锁，状态不符
就重试；宁可短重试，也不要用相反锁序制造批量死锁。

### 4.2 hyperlink_task（低频配置与冻结快照）

> **2026-08-30 单一事实源修订**：发送策略六字段已经迁入统一 `hyperlink_strategy`，每个任务关联一条
> 独占 `TASK_SNAPSHOT`；任务表不再重复保存策略字段。最终口径以
> `docs/superpowers/specs/2026-08-30-hyperlink-strategy-template-competitor-parity-design.md` §7、§10 为准。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `task_name` | `VARCHAR(128) NOT NULL` | 任务名称；竞品允许重名 |
| `start_mode` | `TINYINT NOT NULL DEFAULT 1` | 1=立即执行 2=延后执行 |
| `task_delay_minutes` | `INT NOT NULL DEFAULT 0` | 延后分钟；立即执行时为 0 |
| `task_planned_end_at` | `BIGINT` | 预发布计划结束时间(epoch 毫秒) |
| `data_package_id` | `BIGINT` | →`data_package.id`；仅保存不发送时可为 NULL |
| `data_package_generation` | `INT` | 第一次启用/启动时冻结的号码代次 |
| `data_package_name_snapshot` | `VARCHAR(128)` | 包名展示快照 |
| `target_country_iso2s_snapshot` | `JSON` | 冻结代次去重国家数组；支持多国家包和列表国家筛选 |
| `source_template_id` | `BIGINT` | 内容来源模板 ID，仅追溯 |
| `source_template_version` | `INT` | 引用时模板版本 |
| `hyperlink_strategy_id` | `BIGINT NOT NULL` | 本任务独占 `TASK_SNAPSHOT` ID，任务内唯一强关联 |
| `account_send_concurrency` | `INT NOT NULL DEFAULT 20` | 竞品隐藏契约；范围 1~100 |
| `msg_interval_min_ms` | `INT NOT NULL DEFAULT 500` | 消息间隔下界，0~10000 毫秒 |
| `msg_interval_max_ms` | `INT NOT NULL DEFAULT 700` | 消息间隔上界，须 ≥ 下界 |
| `is_short_link_enabled` | `TINYINT(1) NOT NULL DEFAULT 0` | 派生快照；事实源是内容按钮的 `useShortLink` |
| `version` | `INT NOT NULL DEFAULT 1` | 未开始任务编辑的乐观锁版本 |
| `created_by` | `BIGINT` | 创建人 user_id |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：`idx_hyperlink_task_tenant(tenant_id, id)`、
`idx_hyperlink_task_created(tenant_id, created_at, id)`、
`idx_hyperlink_task_name(tenant_id, task_name, id)`、
`idx_hyperlink_task_package(tenant_id, data_package_id, id)`、
`idx_hyperlink_task_planned_end(tenant_id, task_planned_end_at, id)`、
`uq_hyperlink_task_strategy(tenant_id, hyperlink_strategy_id)`。

任务没有删除按钮、API 或权限，因此本表**不放**没有写入方的 `deleted_at/is_active`。消息内容模板仍是
“引用后复制”的弱引用；发送策略改为任务强关联同表 `TASK_SNAPSHOT`，其模板来源才是弱引用。
第一次启用/启动时冻结数据包和 recipient。延后任务在 `run_status=0` 编辑时可
在一个事务内释放旧预约与未发送领取、重建冻结快照；一旦进入 `run_status=1`，代次、人数、国家、
recipient 和内容不可变。
多国家筛选使用 `JSON_CONTAINS(target_country_iso2s_snapshot, JSON_QUOTE(?))`；任务量级远小于收件人，
不为此新增国家映射表。
`is_short_link_enabled` 只是列表/筛选用的派生投影，唯一事实源仍是 content.`buttons[*].useShortLink`；创建、复制、
未开始编辑必须在同一数据库事务内同时保存 task 与 content，禁止两个字段分步提交后产生漂移。

### 4.3 hyperlink_task_content（消息内容快照，1:1）

主键即 `hyperlink_task_id`，不另设自增 ID。

| 字段 | 类型 | 说明 |
|---|---|---|
| `hyperlink_task_id` | `BIGINT` | 主键，→`hyperlink_task.id` |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `message_schema_version` | `INT NOT NULL DEFAULT 1` | 消息内容契约版本 |
| `message_type` | `TINYINT NOT NULL` | 1=单图文 2=双图文（仅历史）3=普通按钮 4=卡片按钮 |
| `title` | `VARCHAR(1024) NOT NULL` | 竞品明确允许最多 1024 字 |
| `content` | `TEXT` | 单/双图文正文≤2000；按钮副标题/底部文字≤200 |
| `link_description` | `VARCHAR(512)` | 单/双图文链接描述 |
| `promotion_link` | `VARCHAR(2048)` | 原始推广链接 |
| `buttons` | `JSON` | 新任务按钮类型固定 `CTA_URL`、恰好一个，含 `useShortLink` |
| `card_text` | `VARCHAR(500)` | 卡片正文 |
| `link_preview_asset_id` | `BIGINT` | 链接预览图稳定素材 ID |
| `body_main_asset_id` | `BIGINT` | 正文主图/卡片图稳定素材 ID |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：`idx_hyperlink_task_content_link_asset(tenant_id, link_preview_asset_id, hyperlink_task_id)`、
`idx_hyperlink_task_content_body_asset(tenant_id, body_main_asset_id, hyperlink_task_id)`。两者用于素材删除保护和
引用定位；不把 `ref_count` 冗余回素材表。

任务内容与 `hyperlink_template` 继续复用 `HyperlinkMessageContent` DTO 和校验器。任务实施时先把已落地
`hyperlink_template.title` 与 `TITLE_MAX_LENGTH` 从 512 无损放宽到 1024，否则会少抄竞品能力。

### 4.4 hyperlink_task_runtime（任务状态与列表投影，1:1）

| 字段 | 类型 | 说明 |
|---|---|---|
| `hyperlink_task_id` | `BIGINT` | 主键，→`hyperlink_task.id` |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `is_enabled` | `TINYINT(1) NOT NULL DEFAULT 0` | 0=已停用/仅保存 1=启用 |
| `run_status` | `TINYINT NOT NULL DEFAULT 0` | 0=未开始 1=进行中 2=已完成 3=已暂停 4=已停止 |
| `provision_status` | `TINYINT NOT NULL DEFAULT 0` | 0无需准备/仅保存 1准备中 2已就绪 3失败待恢复；1/3 不进租户列表/调度 |
| `current_round_id` | `BIGINT` | →`hyperlink_task_round.id`；尚未建轮次为 NULL |
| `current_round_no` | `BIGINT NOT NULL DEFAULT 0` | 已开始的最新轮次号 |
| `started_at` / `last_send_at` / `finished_at` | `BIGINT` | 发送生命周期时间点 |
| `first_visit_at` / `last_visit_at` | `BIGINT` | 任务首次/最近访问分钟级投影 |
| `recipient_total` | `INT NOT NULL DEFAULT 0` | 任务内去重受众数 |
| `send_total` | `BIGINT NOT NULL DEFAULT 0` | 已提交协议发送的 recipient 数；同一 recipient 最多计 1 |
| `success_num` | `BIGINT NOT NULL DEFAULT 0` | 至少到达单钩的 recipient 数 |
| `delivered_num` | `BIGINT NOT NULL DEFAULT 0` | 至少到达双钩的 recipient 数，是 success 子集 |
| `read_num` | `BIGINT NOT NULL DEFAULT 0` | 已读 recipient 数，是 delivered 子集 |
| `fail_num` | `BIGINT NOT NULL DEFAULT 0` | 当前最终失败的 recipient 数 |
| `fail_404_num` | `BIGINT NOT NULL DEFAULT 0` | 当前确认未开通 WhatsApp 数，是 fail 子集 |
| `invalid_account_count` | `INT NOT NULL DEFAULT 0` | 本任务封号/失效账号去重数；竞品页面标签为“封号数” |
| `click_uv_num` | `INT NOT NULL DEFAULT 0` | 点击收件人去重数 |
| `click_total` | `BIGINT NOT NULL DEFAULT 0` | 访问 PV |
| `used_account_count` | `INT NOT NULL DEFAULT 0` | 实际使用账号去重数 |
| `actual_concurrency` | `INT NOT NULL DEFAULT 0` | 当前/最近一轮实际分配的并发账号数 |
| `execution_duration_sec` | `BIGINT NOT NULL DEFAULT 0` | 累计执行秒数 |
| `active_since_at` | `BIGINT` | 当前连续运行段开始时间；非运行态为 NULL |
| `metrics_updated_at` | `BIGINT` | 最近一次发送指标投影完成时间；列表 `metricsUpdatedAt` 事实源 |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | 行创建/任意状态或投影更新时间 |

索引：`idx_hyperlink_runtime_status(tenant_id, provision_status, is_enabled, run_status, last_send_at, hyperlink_task_id)`。
比率不落列；列表 API 用 task + runtime 一次 JOIN 返回。调度 due scan 改走 `hyperlink_task_round`，
不再把下一轮游标塞在任务投影行。ACK 只推进 recipient/account_usage，不逐条争抢 runtime 热点；分钟级指标
投影器按任务合并 recipient 增量后一次更新 runtime，符合竞品“聚合数据约每分钟同步”的可见口径。
生命周期和点击更新不能冒充发送指标同步时间；只有发送指标投影器成功提交后才推进 `metrics_updated_at`。
公网点击事务仍原子更新 runtime 的 UV/PV/首末访问字段，但只推进普通 `updated_at`。
开始/继续时把 `active_since_at` 置为当前时间；暂停、停止、完成时把
`now-active_since_at` 累加进 `execution_duration_sec` 后清空该字段。运行中展示值为
`execution_duration_sec + now-active_since_at`，因此暂停后时长冻结、继续后从新运行段接着累计。
运行指标的基本单位固定为 `recipient_id`：同一任务同一号码只有一条发送事实。状态推进时按旧投影与新投影
做幂等增减；单钩、双钩、
已读是包含关系，不是互斥桶。市场分析的 `send_total/success_num/delivered_num` 沿用同一口径。

### 4.5 hyperlink_task_recipient（任务内唯一发送事实）

竞品详情固定为一位收信人一行，用户冻结“同一任务内一个收信号码只发送一次”。因此本表同时承载受众快照、
实际发信账号、唯一协议命令、最终状态和点击投影；周期轮次只给尚未发送的 recipient 分配账号，不复制受众。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `hyperlink_task_id` | `BIGINT NOT NULL` | →`hyperlink_task.id` |
| `data_package_id` / `data_package_generation` | `BIGINT` / `INT` | 来源包与冻结代次 |
| `source_import_id` | `BIGINT NOT NULL` | 来源导入批次 |
| `recipient_phone_snapshot` | `VARCHAR(32) NOT NULL` | 收件号码快照 |
| `recipient_country_iso2_snapshot` | `CHAR(2)` | 收件国家快照 |
| `hyperlink_task_round_id` / `round_no` | `BIGINT` | 实际被分配发送的轮次；尚未分配时为 NULL |
| `account_id` | `BIGINT` | 实际发信账号；尚未分配时为 NULL |
| `sender_phone_snapshot` | `VARCHAR(32)` | 发信号码快照 |
| `sender_country_iso2_snapshot` | `CHAR(2)` | 发信国家快照 |
| `sender_account_type_snapshot` | `TINYINT` | 1=个人 2=商业 |
| `protocol_id` | `VARCHAR(32)` | 实际协议标识快照 |
| `protocol_backend` | `TINYINT` | 1=WEB(分身) 2=ANDROID(主设备) |
| `command_id` | `VARCHAR(64)` | 唯一协议命令 ID；超时恢复重放同一 ID |
| `protocol_message_id` | `VARCHAR(128)` | ACK 回关联键 |
| `short_code` | `VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin` | 深度追踪短码；未开启为 NULL，大小写精确 |
| `send_status` | `TINYINT NOT NULL DEFAULT 1` | 1待发 2发送中 3单钩 4双钩 5已读 6失败 7未开通WS |
| `next_dispatch_at` | `BIGINT NOT NULL DEFAULT 0` | 首次派发或同 command 恢复检查的最早时间 |
| `metrics_projected_status` | `TINYINT NOT NULL DEFAULT 1` | 最近已投影状态；初始待发不产生指标，故与 `send_status=1` 对齐 |
| `needs_metrics_projection` | `TINYINT`（生成列） | `send_status<>metrics_projected_status` 时为 1，否则 NULL |
| `fail_code` / `fail_reason` | `VARCHAR(64)` / `VARCHAR(255)` | 最终失败码与脱敏摘要 |
| `submitted_at` | `BIGINT` | 唯一命令被协议通道接受时间 |
| `sent_at` / `delivered_at` / `read_at` / `failed_at` | `BIGINT` | 各阶段时间 |
| `click_count` | `INT NOT NULL DEFAULT 0` | 该收件人访问次数 |
| `first_visit_at` / `last_visit_at` | `BIGINT` | 首次/最近点击时间 |
| `first_visit_ip_address` | `VARBINARY(16)` | 首次访问 IPv4/IPv6；按敏感权限还原 |
| `first_visit_user_agent` | `VARCHAR(512)` | 首次访问原始 UA（截断） |
| `first_visit_browser` / `first_visit_os` / `first_visit_device` | `VARCHAR(64)` | 首次访问 UA 解析结果 |
| `first_visit_language` | `VARCHAR(32)` | 首次访问 `Accept-Language` 首选语言 |
| `first_visit_country_iso2` | `CHAR(2)` | 首次访问 IP 派生国家 |
| `attribution_purged_at` | `BIGINT` | 首触敏感环境字段按 90 天规则清理的时间；累计次数与首末时间继续保留 |
| `metrics_projected_at` | `BIGINT` | 最近指标投影时间 |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：

- `uq_hyperlink_recipient(tenant_id, hyperlink_task_id, recipient_phone_snapshot)`：任务内同号码唯一。
- `uq_hyperlink_recipient_command(tenant_id, command_id)`：协议命令幂等；NULL 不冲突。
- `uq_hyperlink_recipient_ack(tenant_id, account_id, protocol_id, protocol_message_id)`：ACK 幂等；NULL 不冲突。
- `uq_hyperlink_recipient_short_code(short_code)`：公网入口精确反查；NULL 不冲突。
- `idx_hyperlink_recipient_task(tenant_id, hyperlink_task_id, send_status, id)`：明细分页和任务指标校准。
- `idx_hyperlink_recipient_account_sending(tenant_id, account_id, send_status, id)`：同账号跨任务发送中容量硬门禁。
- `idx_hyperlink_recipient_unassigned(tenant_id, hyperlink_task_id, send_status, hyperlink_task_round_id, id)`：按任务领取尚未分轮的待发收信人。
- `idx_hyperlink_recipient_pick(tenant_id, hyperlink_task_round_id, send_status, next_dispatch_at, id)`：本轮派发/恢复。
- `idx_hyperlink_recipient_source(tenant_id, data_package_id, data_package_generation, id)`：来源追溯。
- `idx_hyperlink_recipient_click(tenant_id, hyperlink_task_id, click_count, id)`：UV 与从不点击分析。
- `idx_hyperlink_recipient_visit_trend(tenant_id, hyperlink_task_id, first_visit_at, id)`：访问趋势按首访时间范围分桶；
  `click_count` 不进入索引，避免每次点击维护宽二级索引。
- `idx_hyperlink_recipient_attribution_retention(first_visit_at, attribution_purged_at, id)`：首触敏感字段 90 天分批清理。
- `idx_hyperlink_recipient_country(tenant_id, hyperlink_task_id, recipient_country_iso2_snapshot, id)`：国家筛选。
- `idx_hyperlink_recipient_sender_filter(tenant_id, hyperlink_task_id, sender_country_iso2_snapshot, fail_code, id)`：收信人 Tab 发信国家/失败筛选。
- `idx_hyperlink_recipient_task_time(tenant_id, hyperlink_task_id, submitted_at, account_id, send_status, id)`：账号 Tab 任意时间范围分组统计；覆盖单任务最多 50 万行的精确扫描。
- `idx_hyperlink_recipient_sender_phone(tenant_id, hyperlink_task_id, sender_phone_snapshot, id)`：深度归因发信号码筛选。
- `idx_hyperlink_recipient_projection(tenant_id, needs_metrics_projection, updated_at, id)`：指标投影器批量认领。
- `idx_hyperlink_recipient_stat(tenant_id, submitted_at, sender_country_iso2_snapshot,
  recipient_country_iso2_snapshot, sender_account_type_snapshot, protocol_backend)`：市场日/小时投影回填。

点击 UV 从 recipient 的 `click_count>0` 计算。recipient 不保存会被清理的 `data_package_phone_id`；
任务与数据包代次固定 1:N，所以保留来源快照但不依赖资源池明细存活。派发事务对待发 recipient 做条件更新，
一次写入不可变的 round/account/command/short_code；协议超时只查询或重放同一个 `command_id`。前端没有
跨账号重试或尝试历史，当前模型禁止失败后换账号创建第二个命令；若产品以后明确增加该能力，必须重新评审，
不能把它偷偷塞进当前状态机。
停止任务时，尚未提交协议且没有 `command_id` 的待发 recipient 统一落为
`send_status=6`、`fail_code='TASK_STOPPED'`、`fail_reason='任务已停止'`、`failed_at=now`；竞品明细把这类行
展示为“失败 / 原因：任务已停止”，不能另造“跳过”状态。对应失败会由同一投影链计入 runtime、
未分配账号统计桶，以及已经分配过的对应 round；尚未分轮的行不虚构 round 指标。

本表字段略多，但全部属于“一个收信人一次发送及其首触归因”这一个聚合，且没有大 JSON/正文列；UA 等
可变长字段只在发生点击的少量行写入。拆成两个 50 万级 1:1
表只会增加派发、详情、ACK 和统计回源的 JOIN 与双写成本。因此这里有意保留宽行，列表查询必须显式选择所需列，
不能 `SELECT *`。

ACK 按唯一 command/message 关联 recipient，只允许状态单调前进。recipient 投影器按
`needs_metrics_projection=1` 批量认领，按旧/新状态合并 task、round 和 task+account 增量，
再回写 projected 状态；崩溃前不会丢增量，重复执行也不会重复计数，低频 reconciliation 可从 recipient 重建。

公网跳转无租户上下文，Mapper 使用 `@InterceptorIgnore(tenantLine = "true")` 精确反查 short_code。事务内
锁定 recipient：首次点击写首触环境和 `first_visit_at`，每次点击递增 `click_count` 并更新 `last_visit_at`；
同一事务再按“首次 UV、每次 PV”原子更新 runtime 的点击计数和首末访问时间，随后 302 跳转原始 URL。敏感读取/导出需
`tenant:hyperlink_task:attribution_sensitive` 权限和审计。首触 IP/UA/设备等字段满 90 天后每批最多清理
2000 个 recipient，只置空敏感环境并写 `attribution_purged_at`；累计 UV/PV 与首末时间继续保留。

### 4.6 hyperlink_billing_reservation（任务计费预约，task 1:1）

本表不是钱包总账。一行 = 一个任务对整份冻结受众的报价、冻结、结算和释放状态。竞品最后核对按
“数据包号码数量 × 单价”展示，而不是每轮重复计费；同一 recipient 只发送一次，因此周期轮次不再新增预约。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` / `hyperlink_task_id` | `BIGINT NOT NULL` | 租户、任务 |
| `billing_provider` | `VARCHAR(64) NOT NULL` | 实际钱包/账务提供方编码 |
| `quote_id` | `VARCHAR(128) NOT NULL` | 整份受众报价标识；不保存前端 quoteToken 明文 |
| `quote_expires_at` | `BIGINT NOT NULL` | 报价失效时间 |
| `price_code` | `VARCHAR(64) NOT NULL` | 普通/超级模式价码 |
| `pricing_mode` | `TINYINT NOT NULL` | 1=普通 2=超级并发 |
| `currency_code` | `VARCHAR(16) NOT NULL` | 计价币种，如 USDT |
| `unit_price` | `DECIMAL(20,8)` | 单一价格时的展示单价；多国家差异价时可 NULL |
| `pricing_breakdown` | `JSON NOT NULL` | 按国家的数量、单价、金额报价行 |
| `quoted_recipient_count` | `INT NOT NULL` | 冻结受众总人数 |
| `quoted_amount` | `DECIMAL(20,8) NOT NULL` | 任务预计冻结金额 |
| `reserved_amount` / `settled_amount` / `released_amount` | `DECIMAL(20,8) NOT NULL DEFAULT 0` | 任务金额状态 |
| `settled_send_count` | `BIGINT NOT NULL DEFAULT 0` | 已结算的唯一 recipient 数 |
| `reservation_status` | `TINYINT NOT NULL` | 1处理中 2已冻结 3部分结算 4已结清 5已释放 6失败 |
| `pending_operation` | `TINYINT NOT NULL DEFAULT 0` | 0无 1冻结 2调整 3结算 4释放 |
| `operation_idempotency_key` | `VARCHAR(128)` | 当前待恢复外部操作的幂等键；无待办时为 NULL |
| `next_retry_at` | `BIGINT` | 当前外部操作下一次恢复时间；无待办时为 NULL |
| `external_reservation_no` | `VARCHAR(128)` | 外部任务预约/冻结单号 |
| `failure_code` / `failure_reason` | `VARCHAR(64)` / `VARCHAR(255)` | 最近计费失败摘要 |
| `reserved_at` / `settled_at` / `released_at` | `BIGINT` | 生命周期时间点 |
| `version` | `INT NOT NULL DEFAULT 1` | 计费状态乐观锁 |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：`uq_hyperlink_billing_task(tenant_id, hyperlink_task_id)`、
`uq_hyperlink_billing_external(tenant_id, billing_provider, external_reservation_no)`、
`idx_hyperlink_billing_recovery(tenant_id, pending_operation, next_retry_at, reservation_status, id)`。
仅保存不发送的任务不创建预约。预约创建幂等键固定为 `reserve:{taskId}`；未开始编辑更换数据包时调整或释放后
重建同一任务预约，结算和释放使用外部预约号 + 本地 `version` 生成操作幂等键。余额不足时任务准备失败，
不能先派发 recipient 再补钱。任务金额直接读取本行，不做按轮 SUM，
真实钱包余额和逐笔账务仍由外部提供方持有。

外部账务调用不能和 MySQL 假装成一个原子事务。任务启用先完成 §4.10 recipient_claim 并得到准确人数，
再写 `reservation_status=1`、`pending_operation=1`、操作幂等键与恢复时间后调用 Gateway；成功后创建首轮、令任务进入 READY，明确失败则按 claim owner
分批释放并清理尚未派发的 recipient。结果未知时恢复任务用同一幂等键查询/重放，完成本地提交或补偿释放。
调整、结算、释放也必须先持久化各自的 `pending_operation` 与幂等键；外部结果完成并提交最终本地状态后，
再清空 `pending_operation/operation_idempotency_key/next_retry_at`。因此“处理中”不再承担辨别具体外部动作的责任。
后续周期轮次只分配剩余待发 recipient，不重复冻结同一号码。

### 4.7 hyperlink_task_round（每轮可恢复执行）

即时/预发布只有 round 1；周期每个间隔一行。轮次是发信账号选择和受众分批派发边界，不代表重复发送同一
收信人。调度器只扫描本表，不从 50 万 recipient 推断“当前跑到哪一轮”。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` / `hyperlink_task_id` | `BIGINT NOT NULL` | 租户与任务 |
| `round_no` | `BIGINT NOT NULL` | 任务内从 1 单调递增 |
| `round_status` | `TINYINT NOT NULL` | 1计划中 2选号中 3待派发 4派发中 5等回执 6已完成 7已暂停 8已取消 9失败 10无账号跳过 |
| `scheduled_at` | `BIGINT NOT NULL` | 计划开始时间；轮次原始计划事实 |
| `next_dispatch_at` | `BIGINT NOT NULL` | 下一次可认领/派发时间；初值为 scheduled_at，due scan 事实源 |
| `lease_owner` | `VARCHAR(64)` | 当前认领选号/派发工作的 worker；未被认领时为 NULL |
| `lease_expires_at` | `BIGINT` | worker 租约到期时间；过期后允许恢复器接管 |
| `assigned_recipient_count` | `INT NOT NULL DEFAULT 0` | 本轮已经分配的唯一 recipient 数 |
| `selected_account_count` / `actual_concurrency` | `INT NOT NULL DEFAULT 0` | 本轮账号分配与实际并发 |
| `send_total` / `success_num` / `delivered_num` / `read_num` | `BIGINT NOT NULL DEFAULT 0` | 本轮 recipient 状态投影 |
| `fail_num` / `fail_404_num` | `BIGINT NOT NULL DEFAULT 0` | 本轮失败与未注册投影 |
| `started_at` / `dispatch_completed_at` | `BIGINT` | 选号与派发时间点 |
| `last_send_at` / `finished_at` | `BIGINT` | 发送与完成时间点 |
| `failure_code` / `failure_reason` | `VARCHAR(64)` / `VARCHAR(255)` | 轮次失败摘要 |
| `version` | `INT NOT NULL DEFAULT 1` | 状态/游标乐观锁 |
| `active_task_id` | `BIGINT`（生成列） | 活动/暂停轮次取 task_id，终态为 NULL |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：`uq_hyperlink_round_no(tenant_id, hyperlink_task_id, round_no)`、
`uq_hyperlink_round_active(tenant_id, active_task_id)`、
`idx_hyperlink_round_due(tenant_id, round_status, next_dispatch_at, id)`、
`idx_hyperlink_round_recovery(tenant_id, round_status, lease_expires_at, id)`、
`idx_hyperlink_round_task(tenant_id, hyperlink_task_id, round_no, id)`。
`active_task_id` 在状态 1/2/3/4/5/7 时取 task_id，保证同任务最多一个非终态轮次。worker 使用条件更新 +
版本号认领并写入租约，执行中定时续租；worker 崩溃后只允许在 `lease_expires_at` 过期时按状态与版本接管，
进入终态/暂停或主动释放执行权时清空租约。`next_dispatch_at` 只表达业务可执行时间，不复用为 worker 存活信号。
选号完成后按 recipient 的“任务内待发且 round_id IS NULL”索引使用
`FOR UPDATE SKIP LOCKED` 分批分配给本轮账号，同一 recipient 只能从未分配变成已分配。宕机后从 round、
round_account 和 recipient 状态继续，不重新生成受众、不重复发送已存在 command 的行。

### 4.8 hyperlink_task_account_usage（任务×账号累计执行状态）

竞品“每账号最大发送数”的 tooltip 明确是**单个账号在本任务内允许成功发送的总上限**，不是每轮上限。
异步 `account_stat` 不能用于调度限额；否则投影延迟期间会继续派发，周期换轮后也无法可靠继承已用额度。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` / `hyperlink_task_id` / `account_id` | `BIGINT NOT NULL` | 租户、任务、账号 |
| `account_phone_snapshot` | `VARCHAR(32) NOT NULL` | 首次选中时的发信号码快照 |
| `sender_country_iso2_snapshot` | `CHAR(2)` | 发信国家快照 |
| `account_type_snapshot` | `TINYINT NOT NULL` | 1个人 2商业 |
| `account_created_at_snapshot` | `BIGINT NOT NULL` | 账号入库时间快照 |
| `success_limit` | `INT NOT NULL DEFAULT 0` | 本任务成功上限快照；0=不限 |
| `successful_send_count` | `BIGINT NOT NULL DEFAULT 0` | 已跨过单钩的 recipient 数，跨轮累计 |
| `reserved_success_slot_count` | `INT NOT NULL DEFAULT 0` | 已派发但尚未明确成功/失败的预占成功槽位 |
| `in_flight_count` | `INT NOT NULL DEFAULT 0` | 本任务该账号当前在途 command 数 |
| `usage_status` | `TINYINT NOT NULL DEFAULT 1` | 1可用 2达到成功上限 3已封号 4已失效 5人工停用 |
| `invalid_code` | `VARCHAR(64)` | 首次封号/失效错误码；竞品 `invalid_account_num` 的事实明细 |
| `invalid_reason` | `VARCHAR(255)` | 首次封号/失效原因（截断） |
| `invalid_at` | `BIGINT` | 本任务内首次封号/失效时间；NULL=从未失效 |
| `last_selected_round_no` | `BIGINT NOT NULL DEFAULT 0` | 最近被选入的轮次号 |
| `next_send_at` | `BIGINT NOT NULL DEFAULT 0` | 本账号下一条消息最早可派发时间 |
| `first_used_at` / `last_used_at` | `BIGINT` | 首次/最近实际分配 recipient 时间 |
| `version` | `INT NOT NULL DEFAULT 1` | 条件占槽与状态更新乐观锁 |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：`uq_hyperlink_task_account_usage(tenant_id, hyperlink_task_id, account_id)`、
`idx_hyperlink_task_account_select(tenant_id, hyperlink_task_id, usage_status, next_send_at, id)`、
`idx_hyperlink_task_account_reverse(tenant_id, account_id, usage_status, hyperlink_task_id, id)`、
`idx_hyperlink_task_account_invalid(tenant_id, hyperlink_task_id, invalid_at, invalid_code, id)`。

派发前执行条件更新：`usage_status=1`、`next_send_at<=now`、`in_flight_count<account_send_concurrency`，且
`success_limit=0 OR successful_send_count+reserved_success_slot_count<success_limit`。条件更新成功后才把一条
待发 recipient 分配给该账号并创建唯一 command。单钩 ACK 把 1 个 reserved 槽转成
successful，明确失败释放 reserved，在途 command 结束时减少 in-flight。
达到上限后原子置状态 2。这样并发派发最多预占到上限，不会因为 20 条在途消息把成功数冲过配置值。

账号状态事件首次把 `invalid_at` 从 NULL 改为非 NULL 时，同时把 usage_status 置 3/4 并原子增加
runtime.`invalid_account_count`；重复事件只补全原因，不重复计数。`/ban-stats` 按本表的 `invalid_code/reason`
分组。独立 `hyperlink_task_ban` 会重复 task/account/号码/国家且仍然只允许一行，故不建。

这张表只解决**本任务内**的限额和并发。多个任务共用同一账号时，派发事务先锁全局 `account` 行，再按
`account_id + send_status=2` 当前读锁取最多 20 条 recipient；达到 20 即延后，因此 Redis holder 过期或丢失也
不能突破硬上限。账号级 Redis 信号量继续提供分布式 holder 与续租校准，holder 使用稳定的
recipient.`command_id` 并带 TTL；不能用单 JVM 内存计数冒充全局限制。Redis 不可用或续租失败时发送链路仍须
fail closed、延后重试，不能无保护放行；TTL 只是运维续租窗口，不是容量正确性的安全边界。

### 4.9 hyperlink_task_round_account（轮次×发信账号分配）

一行 = 某轮已选中的一个账号。`max_use_account` 对周期是每轮上限；如果不固化集合，调度器重启或多 worker
并发重选会超过上限，预发布等待新账号时也无法判断还剩多少名额。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` / `hyperlink_task_id` / `hyperlink_task_round_id` | `BIGINT NOT NULL` | 租户、任务、轮次 |
| `round_no` | `BIGINT NOT NULL` | 轮次号快照 |
| `task_account_usage_id` / `account_id` | `BIGINT NOT NULL` | 任务账号执行行与账号 ID |
| `selection_no` | `INT NOT NULL` | 本轮稳定选号顺序，从 1 起 |
| `assignment_status` | `TINYINT NOT NULL DEFAULT 1` | 1可派发 2额度耗尽 3已封号 4离线等待 5已释放 |
| `selected_at` / `last_dispatch_at` / `released_at` | `BIGINT` | 分配生命周期时间 |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：`uq_hyperlink_round_account(tenant_id, hyperlink_task_round_id, account_id)`、
`uq_hyperlink_round_account_order(tenant_id, hyperlink_task_round_id, selection_no)`、
`idx_hyperlink_round_account_pick(tenant_id, hyperlink_task_round_id, assignment_status, id)`。
选号事务锁 round，按剩余名额批量 INSERT 本表并 UPSERT task_account_usage，随后原子更新
round.`selected_account_count`；唯一键和稳定 `selection_no` 使重放只补缺口。账号达到任务成功上限、封号或失效
时同步推进 usage 与当前 round_account 状态；短暂离线可保留分配并等待恢复，是否换号服从任务模式和剩余名额。

### 4.10 hyperlink_task_recipient_claim（受众批量领取作业）

单任务最多 50 万号码，不能用一个覆盖全包的长事务伪装“原子冻结”。本表把一次任务受众冻结变成可恢复的
批作业；一行 = 一个任务首次启用时对某个数据包代次的领取过程。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` / `hyperlink_task_id` / `data_package_id` | `BIGINT NOT NULL` | 租户、任务、数据包 |
| `data_package_generation` | `INT NOT NULL` | 短事务锁包时冻结的代次 |
| `claim_upper_phone_id` | `BIGINT NOT NULL` | 开始时该代最大 phone.id；后续追加号码不进入本任务 |
| `scan_cursor_phone_id` | `BIGINT NOT NULL DEFAULT 0` | 已处理到的最大 phone.id |
| `quoted_phone_count` | `INT NOT NULL` | 用户最后核对 quote 中的人数 |
| `claimed_phone_count` | `INT NOT NULL DEFAULT 0` | 已成功写入 recipient 的人数 |
| `claim_status` | `TINYINT NOT NULL` | 1准备中 2领取中 3任务持有中 4释放中 5已释放 6失败待恢复 7已关闭 |
| `lease_owner` | `VARCHAR(64)` | 当前批处理 worker |
| `lease_expires_at` | `BIGINT` | worker 租约；过期可被恢复器接管 |
| `failure_code` / `failure_reason` | `VARCHAR(64)` / `VARCHAR(255)` | 最近失败摘要 |
| `active_claim_key` | `TINYINT`（生成列） | 状态 1/2/4/6 时为 1，否则 NULL；OWNED 不占代次操作锁 |
| `version` | `INT NOT NULL DEFAULT 1` | 游标/状态乐观锁 |
| `started_at` / `finished_at` | `BIGINT` | 领取生命周期 |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：`uq_hyperlink_recipient_claim_task(tenant_id, hyperlink_task_id)`、
`uq_hyperlink_recipient_claim_generation(tenant_id, data_package_id, data_package_generation, active_claim_key)`、
`idx_hyperlink_recipient_claim_recovery(tenant_id, claim_status, lease_expires_at, id)`。

流程固定为：

1. 短事务锁 `data_package`/`data_package_stat`，冻结 current generation、当时代次最大 phone.id 和 quote 人数，
   插 claim 行；同代 active 唯一键阻止另一个任务同时穿插领取。quote 已过期或人数变化时在写号码前返回
   `QUOTE_STALE`，不能静默多扣/少扣后假装是原核对结果。
2. worker 每批先锁 claim/stat 行，再按 `id > cursor AND id <= upper AND pool_status=1` 使用覆盖索引取固定数量；
   同一短事务更新 phone 的 `pool_status/claimed_by_hyperlink_task_id/claimed_at`、INSERT recipient、更新
   data_package_stat 和 claim 游标/计数。recipient 唯一键保证整批重放不重复。
3. 扫描到 upper 后校验 `claimed_phone_count=quoted_phone_count`，冻结 task 的人数/国家快照并置 OWNED；
   后续导入追加号码不进入已冻结任务。状态 3 释放代次级“正在领取/释放”互斥，其他任务可继续领取该代尚未
   使用或后续追加的号码；每个号码的真实归属仍由 `data_package_phone.claimed_by_hyperlink_task_id` 隔离。
   随后才调用任务级计费并创建首轮调度状态。
4. 外部冻结明确失败、未开始编辑重建或补偿时，先把 OWNED 条件推进为 RELEASING 并取得代次操作锁，再按
   phone claim 索引批量把仍为 `pool_status=2` 的本任务行释放为未使用，并删除 `command_id IS NULL` 的
   recipient；已经派发的发送事实绝不回滚。若同代已有领取/释放作业持锁，则延后重试，不绕过唯一键并发补偿。
5. Gateway 结果未知由 billing_reservation 的待操作字段恢复，claim 保持 OWNED；领取或释放批作业在**已持有**
   代次操作锁时发生未知结果/worker 错误，才进入状态 6 并继续占锁。恢复器按号码 owner 与游标继续正向领取
   或释放，不能先释放唯一键让第二个作业进来，再慢慢猜哪些号码属于谁。

任务进入完成/停止终态时，先释放仍为 CLAIMED 的剩余号码，再把 claim 置 RELEASED；若所有号码都已进入
不可再领取终态且无需释放，则置 CLOSED。状态 3/5/7 不占代次操作锁；状态 6 在领取/释放作业尚未收敛前
继续阻止同代新的领取/释放作业，避免两个补偿流程交错。

任务创建接口只需原子建立 `runtime.provision_status=1` 的不可见头和 claim 作业，不持有长事务等待 50 万行；
任务只有在 claim、任务计费和首轮 round 都完成、provision_status 置 2 后才进入正常列表/调度。
仅保存任务为 0，恢复失败为 3。这样页面不会看到半包人数，失败也能按
`claimed_by_hyperlink_task_id` 精确回收。

### 4.11 hyperlink_task_account_stat（任务×发信账号查询投影）

本表只服务“发信账号维度统计”的**无时间范围累计查询**，不是发送事实，也不参与选号、账号限额或并发控制。
竞品该 Tab 默认按单钩数排序，还支持发信国家、成功数区间、指标排序、分页和导出；若每次打开页面都从最多
50 万 recipient 做 GROUP BY + 排序，会把高频读压力放到发送事实表。因此一行固定为“一个任务 × 一个发信
账号汇总桶”，由 recipient 异步投影，列表直接按指标索引分页。

截图中存在“未分配”行：任务停止时，部分 recipient 尚未选到发信账号，但页面仍把它们按失败原因汇总。
因此本表必须支持一个 `account_id=NULL` 的未分配桶，不能假设所有统计行都有真实账号。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` / `hyperlink_task_id` | `BIGINT NOT NULL` | 租户、任务 |
| `account_id` | `BIGINT` | 实际发信账号；NULL=未分配桶 |
| `account_bucket_key` | `BIGINT`（生成列） | `COALESCE(account_id, 0)`；仅用于让未分配桶也受唯一键约束，真实账号 ID 必须 >0 |
| `send_total` | `BIGINT NOT NULL DEFAULT 0` | 该账号已提交协议发送的唯一 recipient 数 |
| `success_num` / `delivered_num` / `read_num` | `BIGINT NOT NULL DEFAULT 0` | 单钩/双钩/已读 recipient 当前结果投影 |
| `failed_num` / `fail_404_num` | `BIGINT NOT NULL DEFAULT 0` | 失败/未开通 recipient 当前结果投影；未分配桶可累计任务停止等终态失败 |
| `first_send_at` / `last_send_at` | `BIGINT` | 首次/最近发送时间 |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | 创建与最近投影时间 |
| `reconciled_at` | `BIGINT` | 最近事实校准时间 |

索引：`uq_hyperlink_account_stat(tenant_id, hyperlink_task_id, account_bucket_key)`、
`idx_hyperlink_account_stat_success(tenant_id, hyperlink_task_id, success_num, id)`、
`idx_hyperlink_account_stat_delivered(tenant_id, hyperlink_task_id, delivered_num, id)`、
`idx_hyperlink_account_stat_failed(tenant_id, hyperlink_task_id, failed_num, id)`。
投影规则：创建任务和轮次选号时不写本表；recipient 初始待发状态也不产生统计。协议接受发送令状态离开待发，
或待发 recipient 因任务停止直接进入终态后，投影器才按旧/新状态差量 UPSERT 对应账号桶。尚未分配账号就
进入终态的行进入 `account_bucket_key=0` 桶。一个 recipient 的实际账号一经派发即冻结，不跨账号重试，因此
真实账号桶的 `send_total` 求和应等于 runtime.`send_total`。投影失败可按任务从 recipient GROUP BY
`account_id` 全量重建，故本表不是第二事实源。

查询规则：

- 未选择发送时间：从本表读取累计指标并 LEFT JOIN `hyperlink_task_account_usage` 取得号码、国家、类型和入库时间
  快照，完成国家/成功数筛选、指标排序、分页和导出；task×account 行数很小，这个 JOIN 不构成性能瓶颈。
- 选择任意发送时间：按 recipient.`submitted_at` 精确过滤并 GROUP BY account_id，再 LEFT JOIN
  `hyperlink_task_account_usage` 取得号码、国家、类型和入库时间快照；命中
  `idx_hyperlink_recipient_task_time`，最坏扫描单任务 50 万行。
- 时间范围查询不读取本表累计指标，避免把全量数字伪装成区间数字；大范围导出走异步任务。

`account_id=NULL` 的未分配桶没有 usage 行，查询直接显示“未分配”，国家/类型为 NULL、存活天数为 0.0。
账号展示快照只保存在 usage，不在 stat 重复四份字段，避免两个 task×account 表更新时产生漂移。

`hyperlink_task_account_usage` 是同步调度状态，解决成功额度与在途并发；本表是分钟级查询投影。两者粒度虽然
都是 task×account，但写入时效和读取目的不同，禁止用本表的延迟指标参与派发判断，也禁止用 usage 现场拼页面统计。

### 4.12 访问趋势查询口径

访问趋势不建独立表。时间窗口按竞品从任务第一个 UV 起向后 12~72 小时，而非“当前时刻向前滚动”；30 分钟、
1 小时和 2 小时粒度都直接按 recipient.`first_visit_at` 分桶：`COUNT(*)` 是新增 UV。竞品旧实现将
`SUM(click_count)` 近似归入首访桶，但该值不能代表 PV 的实际发生时段，本模型禁止使用。累计 UV 在应用层按桶做前缀和，累计点击率再除
runtime.`success_num`；空桶由应用层补零。

```sql
SELECT FLOOR((first_visit_at - :window_start_at) / :bucket_size_ms) AS bucket_no,
       COUNT(*) AS new_uv
FROM hyperlink_task_recipient
WHERE tenant_id = :tenant_id
  AND hyperlink_task_id = :task_id
  AND first_visit_at >= :window_start_at
  AND first_visit_at < :window_end_at
GROUP BY bucket_no
ORDER BY bucket_no;
```

`:bucket_size_ms` 对 30 分钟/1 小时/2 小时分别取 `1800000`、`3600000`、`7200000`。

查询必须命中 `idx_hyperlink_recipient_visit_trend`，仅扫描所选窗口内已访问 recipient，不扫描未点击行。
当前业务单任务发送量不超过 10 万，且趋势是低频详情查询，因此不维护 30 分钟聚合表；若实测超出延迟目标，
先使用与页面“约每分钟同步”一致的短期缓存，再基于真实压测评审是否增加可重建投影，不能预建死表。

### 4.13 三种模式的冻结与轮次规则

1. 第一次启用/启动时先用短事务冻结数据包代次和 claim 上界，再按 §4.10 批量领取、生成 recipient；
   同任务同号码永远只有一行，最终人数保存为 runtime.`recipient_total`。claim、任务级计费和首轮 round
   全部完成后任务才可见/可派发。未开始编辑若更换数据包，必须按 claim owner 分批释放旧 CLAIMED、删除
   `command_id IS NULL` 的 recipient 和未消费轮次，再重建快照与任务预约；不能用 50 万行大事务硬回滚。
2. 预发布的“新号自动加入”指**新的合格发信账号**加入 round 1 调度，不吸收数据包后续导入的新收件人。
   即时任务零可用账号禁止启用；预发布零账号时保留 round 1 并按调度节奏重试选号。
3. 周期任务的下一轮只选择当轮发信账号，并从任务内 `send_status=1 AND round_id IS NULL` 的剩余 recipient
   中分配一批；它不重复生成收信人、不重复计费，也不重发此前轮次已经分配的号码。若账号为 0，本轮以状态
   10 收口；尚有待发号码时按周期创建下一轮等待新账号。
4. 停止任务时把尚未创建 `command_id` 的 recipient 记为失败：`send_status=6`、
   `fail_code=TASK_STOPPED`、`fail_reason=任务已停止`，并释放其号码池 CLAIMED 状态；已提交的 recipient
   及其 ACK、点击累计与首触归因全部保留。任务级预约按未结算人数释放余额，不能删除发送事实。
5. 协议超时只查询或重放 recipient 原 `command_id`，不会创建第二次业务发送；ACK 只允许推进该 recipient 的
   状态。短码也固定在该 recipient 上，点击直接归因到唯一发信账号。
6. `default_sub_task_num=50` 是 recipient 分配与派发的批量切片大小，可调但不落业务列、不参与计费。
7. 周期轮次完成/无账号跳过时，若仍有未分配 recipient，则在同一事务插入下一条 PLANNED round；`scheduled_at` 取
   `max(上一轮 scheduled_at + interval, 当前完成时间)`，漏过的周期不补跑、轮次不重叠。这样进程不会在
   “本轮已完成、下轮尚未创建”的缝隙宕机后永久停住；没有剩余 recipient 时任务完成，不再空转建轮。

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
| `title` | `VARCHAR(1024) NOT NULL` | 消息标题 / 按钮气泡上方加粗大字；任务期由 512 无损扩容 |
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

`hyperlink_strategy` 是模板与任务发送策略的唯一事实源。一行是一个可复用 `TEMPLATE` 或一个任务独占
`TASK_SNAPSHOT`；**只管竞品模板与任务共用的六项策略，不含消息内容、数据包、启动时机、消息间隔和单账号内部并发。**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `strategy_scope` | `TINYINT NOT NULL` | 1=模板 2=任务快照 |
| `owner_task_id` | `BIGINT` | 任务快照所属任务 ID；模板为空，任务快照绑定后必填且租户内唯一 |
| `source_strategy_id` | `BIGINT` | 快照来源模板 ID；弱追溯 |
| `strategy_name` | `VARCHAR(128)` | 模板必填；任务快照为空 |
| `task_type` | `TINYINT NOT NULL` | 1=即时 2=预发布(持续运营) 3=周期循环 |
| `task_interval_minutes` | `INT NOT NULL DEFAULT 0` | 周期轮次间隔(分钟)；模板下限 30、任务快照下限 1，按 scope 校验 |
| `max_use_account` / `concurrent_num` / `account_max_send_num` / `account_filter` | 统一策略字段 | 模板与任务快照语义、列型一致；`concurrent_num=0` 为 AUTO |
| `is_enabled` | `TINYINT(1) NOT NULL DEFAULT 1` | 模板是否可选；任务快照恒 1 |
| `version` | `INT NOT NULL DEFAULT 1` | 编辑乐观锁版本 |
| `created_by` | `BIGINT` | 创建人 user_id |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |
| `deleted_at` | `BIGINT` | 软删时间；NULL=未删 |
| `template_active` | `TINYINT`（生成列） | 仅模板软删唯一键辅助 |

索引按 `tenant_id + strategy_scope` 区分模板和任务快照；模板名称唯一键只约束未删除 `TEMPLATE`；
`tenant_id + owner_task_id` 保证一个任务只有一份独占快照。

> `hyperlink_task.hyperlink_strategy_id` 强关联本任务独占的 `TASK_SNAPSHOT`，任务表不再保存六个策略字段。
> 快照的 `source_strategy_id` 弱引用最初模板，所以模板修改/删除仍不影响任务。

> **`account_send_concurrency` / `msg_interval_min_sec` / `msg_interval_max_sec` 三列已删除**（2026-08-27 校正）：
> 竞品策略页没有这三个控件，提交体里是硬编码常量（`20` / `0` / `0`）。落列就是没有写入方的死列（规范一.4）。
> 任务页的提示文案也印证：策略只带入「任务模式 / 账号范围 / 并发 / 限号 / 周期间隔」。
> 出处 `readable/assets/strategy-D2fnr_pX.js:443-454, 657-671`。

存量迁移采用 expand/contract：先按任务旧六列一对一生成快照并回填策略 ID，切换所有读写后再删除旧列。
表结构、约束、事务和验收的唯一详细口径见 2026-08-30 竞品对齐设计 §7、§10。

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
| `created_by` | `BIGINT` | 上传人 user_id；存量行为 NULL |
| `updated_at` | `BIGINT NOT NULL` | epoch 毫秒；存量行取 `created_at` 回填 |

保留现有文件的稳定 ID、租户、原文件名、类型、大小、内容、创建和删除时间语义。

索引新增：`idx_resource_asset_name`（`tenant_id, deleted_at, asset_name`）供按名搜索。

> **`ref_count` 不落列**（2026-08-27 校正）：引用方是 `hyperlink_template` 与 `hyperlink_task_content`
> 两张表，实时 `COUNT` 即可。落冗余列必然出现与真实引用不一致的时刻，而删除保护恰恰不能容忍这种不一致。
>
> **确认走"加列"而不是"新建第二张素材表"**：`marketing_template_file` 已经是素材的事实源
> （字节 + 租户 + 原名 + 类型 + 大小）。再建一张只管理名称标签的 `resource_asset`，
> 就有两行描述同一个素材，正是规范一.2 禁止的分歧。物理表名与 API 资源名 `resource-assets`
> 不一致是可接受的代价，改名登记为独立技术债。

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
| `file_id` | `BIGINT NOT NULL` | →`marketing_template_file.id`（素材的稳定 ID） |
| `resource_asset_tag_id` | `BIGINT NOT NULL` | →`resource_asset_tag.id` |
| `created_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：`uq_resource_asset_tag_ref`（`tenant_id, file_id, resource_asset_tag_id`）、
`idx_resource_asset_tag_ref_tag`（`tenant_id, resource_asset_tag_id, file_id`）供按标签反查。

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
| `task_type` | `TINYINT NOT NULL` | 1=即时 2=预发布(持续运营) 3=周期循环 |
| `protocol_backend` | `TINYINT NOT NULL` | 协议链路：1=WEB(分身) 2=ANDROID(主设备)；对应分析页「设备平台」筛选 |
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
| `uq_hyperlink_stat_daily` | `tenant_id, stat_date, sender_country_iso2, recipient_country_iso2, account_type, task_type, protocol_backend, is_short_link_enabled` | 幂等回填 |
| `idx_hyperlink_stat_daily_range` | `tenant_id, stat_date, id` | 日期范围扫描 |
| `idx_hyperlink_stat_daily_retention` | `stat_date, id` | 90 天分批保留清理 |

### 7.2 hyperlink_stat_hourly（滚动 8 天）

分析页按小时最多查询 7 天。若每次实时扫描全租户 recipient，发送量上来后仍会与 ACK/投影抢 IO；但把小时
聚合保留 90 天又会无谓膨胀。因此增加同维度、短保留的小时读模型。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `stat_hour_start_at` | `BIGINT NOT NULL` | 按业务时区整点对应的 UTC epoch 毫秒 |
| `sender_country_iso2` / `recipient_country_iso2` | `CHAR(2) NOT NULL` | 发信/被营销国家，未知 `ZZ` |
| `account_type` / `task_type` / `protocol_backend` | `TINYINT NOT NULL` | 账号类型、任务模式、协议维度 |
| `is_short_link_enabled` | `TINYINT(1) NOT NULL` | 深度追踪维度 |
| `send_total` / `success_num` / `delivered_num` | `BIGINT NOT NULL DEFAULT 0` | 本小时逻辑投递指标 |
| `click_uv_num` / `used_account_count` / `banned_account_count` | `BIGINT NOT NULL DEFAULT 0` | 本小时行内去重指标 |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：

- `uq_hyperlink_stat_hourly(tenant_id, stat_hour_start_at, sender_country_iso2,
  recipient_country_iso2, account_type, task_type, protocol_backend, is_short_link_enabled)`：幂等回填。
- `idx_hyperlink_stat_hourly_range(tenant_id, stat_hour_start_at, id)`：7 天范围扫描。
- `idx_hyperlink_stat_hourly_retention(stat_hour_start_at, id)`：8 天保留清理。

投影作业每 5 分钟回填当前/上一小时；每日低峰重算最近 8 天，吸收迟到 ACK、封号与点击。页面不直接扫
recipient；小时行超过 8×24 小时后分批硬删，原始事实仍在，可随时重建。日表继续保留 90 天，两张表使用同一
维度解析器和指标口径，不能各写一套 SQL。

### 7.3 为什么采用“日长期 + 小时短期”

分析页支持按日与按小时两种粒度。若两种粒度都预聚合：

```
维度基数 ≈ 发信国家(~50) × 被营销国家(~50) × 账号类型(2) × 任务模式(3) × 深度追踪(2) × 协议(2) ≈ 6 万组合
日粒度：6 万行/天  × 90 天 ≈ 540 万行（实际国家对高度稀疏，真实量级低两个数量级） → 可接受
时粒度：6 万 × 24 行/天 × 90 天 ≈ 1.3 亿行 → 不可接受
```

小时表若也保留 90 天，理论上限约 1.3 亿行；但需求只允许查询 7 天。采用 8 天滚动保留后，理论密集上限
降到约 1152 万行，且真实国家对高度稀疏，实际远低于该值。最终固定为：**日表保留 90 天、小时表保留
8 天，页面不实时聚合 recipient**。`idx_hyperlink_recipient_stat` 仍保留给投影回填和校准，不承担在线页面查询。

`used_account_count` / `click_uv_num` / `banned_account_count` 是**行内去重、跨行相加**的口径。
校正说明（2026-08-27）：竞品分析页的 KPI 卡就是把各国家对的 `summary` 逐行相加得到的
（`readable/assets/analysis-DA45fcKJ.js:1148-1205`），**同一账号跨国家对会被重复计数**。
这是竞品的既有口径，我们照抄——不做全局 `COUNT(DISTINCT)` 回源。
该口径必须写进列注释与接口注释，防止后人当 bug 修。

---

## 八、账号画像（共享硬依赖，需全局评审）

超链任务的账号筛选比 `AccountQuery` 现有维度多出 6 项。逐项对账：

| hylb 筛选项 | armada 现状 | 结论 |
|---|---|---|
| 国家 / 排除国家 | `account.ws_phone` 区号 + `country_phone_prefix_mapping` 派生 | ✅ 已有，无需加列 |
| 大洲 `continent` | `country` 表可扩，或由 iso2 映射 | ✅ 走 `country` 主数据 |
| 账号类型 | `account.account_type` | ✅ 已有 |
| 类型 `wid_type`（主设备/分身） | `account.protocol_id` | ✅ 派生 `ProtocolBackend`，不加列 |
| 设备类型 `platform`（六值） | `account.device_os` + `account.account_type` + `account.protocol_id` | ✅ 组合派生，不加复合列 |
| 分组 `group_ids` | `account.account_group_id` | ✅ 已有 |
| 渠道 `channel_ids` | `account.promotion_channel_id` | ✅ 已有 |
| 在线状态 | `account_state.login_state` | ✅ 已有 |
| 入库时间 | `account.created_at` | ✅ 已有 |
| 协议 `protocol_id` | `account.protocol_id` | ✅ 已有 |
| 封号码 / 原因 | `account_state.block_error_code` / `block_reason` | 任务筛选抽屉不显示；用于详情封号统计 |
| 导入方式（六段/全参） | `account_credential.cred_format`（1六段 2JSON 3全参） | ✅ 已有，直接按账号关联，不给 `account` 加冗余列 |
| **存活天数** | 无 | ✅ 由 `now - account.created_at` 派生，**不落列** |
| **最近登录时间** | `account_online_attempt_log` | 任务筛选抽屉不显示，不进入任务筛选 DTO |
| **轮号状态** | 无 | ✅ `account_profile.rotation_status`，由轮号流程同步 |
| **号码来源（五类）** | `account.number_source` 只有三类 | ✅ `account_profile.marketing_source` 统一为五类 |
| **好友数** | 无 | ✅ `account_profile.friend_count`；协议主动采集（见 8.2） |
| **是否允许拉群** | 无 | ✅ `account_profile.is_group_invite_allowed`；协议主动采集（见 8.2） |
| **注册天数** | 协议无直接数据 | ✅ 由号源/导入提供 `registered_at`，再计算号龄（见 8.3） |

### 8.1 `wid_type` 与 `platform` 都不需要新列

`wid_type`（`native6`=主设备 / `web5`=分身设备）**已经在 armada 里了**，只是没人这么叫它：

```java
// com.armada.platform.protocol.model.enums.ProtocolBackend
ProtocolBackend.fromProtocolId(account.protocol_id)  // → WEB | ANDROID
```

对应关系：`ANDROID` = 主设备(native6)，`WEB` = 分身设备(web5)。竞品另有一个复合筛选字段
`platform`，六个取值按以下白名单派生：

| `platform` | Armada 条件 |
|---|---|
| `android` | `device_os=1` 且 `account_type=1` |
| `smb_android` | `device_os=1`、`account_type=2`、`ProtocolBackend=ANDROID` |
| `smba` | `device_os=1`、`account_type=2`、`ProtocolBackend=WEB` |
| `ios` | `device_os=2` 且 `account_type=1` |
| `smb_ios` | `device_os=2`、`account_type=2`、`ProtocolBackend=ANDROID` |
| `smbi` | `device_os=2`、`account_type=2`、`ProtocolBackend=WEB` |

`account.protocol_id` 已有 `idx_tenant_protocol_account` 索引，筛选直接走它。
`platform` 的三个组成事实也都已有。**不落 `wid_type/platform` 列**——落了就是同一事实的第二处表示，
正是规范一.2 禁止的分歧；`device_os` 或协议未知时不匹配对应筛选值。任务前置迁移只为组合筛选补
`idx_account_hyperlink_platform(tenant_id, device_os, account_type, protocol_id, id)`，属于共享表索引变更，
随 `account_profile` 一起走全局评审。

### 8.2 方案：`account_profile`（1:1），承载任务筛选画像

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
| `friend_count_synced_at` | `BIGINT` | 好友数最近同步时间 |
| `is_group_invite_allowed` | `TINYINT(1)` | 隐私设置是否允许被拉群；NULL=未采集 |
| `group_invite_synced_at` | `BIGINT` | 拉群隐私最近同步时间 |
| `rotation_status` | `TINYINT` | 0 未轮号 1 轮号中 2 成功 3 失败；NULL=不适用/未采集 |
| `rotation_updated_at` | `BIGINT` | 轮号流程最近更新时间 |
| `registered_at` | `BIGINT` | WhatsApp 估算注册时间；由号源/导入信息换算，NULL=未知 |
| `registered_at_source` | `TINYINT` | 1供应商准确日期 2供应商号龄反推 3人工维护 |
| `marketing_source` | `TINYINT` | 0 买量 1 自登 2 买入 3 转入 4 群扫码 |
| `marketing_source_updated_at` | `BIGINT` | 五类运营来源最近更新时间 |
| `created_at` / `updated_at` | `BIGINT NOT NULL` | epoch 毫秒 |

索引：`uq_account_profile`（`tenant_id, account_id`）、
`idx_account_profile_friend`（`tenant_id, friend_count`）、
`idx_account_profile_friend_sync`（`tenant_id, friend_count_synced_at, account_id`）、
`idx_account_profile_invite`（`tenant_id, is_group_invite_allowed, account_id`）、
`idx_account_profile_invite_sync`（`tenant_id, group_invite_synced_at, account_id`）、
`idx_account_profile_rotation`（`tenant_id, rotation_status, account_id`）、
`idx_account_profile_registered`（`tenant_id, registered_at, account_id`）、
`idx_account_profile_source`（`tenant_id, marketing_source, account_id`）。

不用单一 `profile_source/synced_at`：五个画像事实来自不同系统、刷新频率也不同，一个总时间会把
“好友数刚更新、拉群隐私已经过期”伪装成整行新鲜。`marketing_source` 是竞品五类运营来源，和现有
三类获客字段 `account.number_source` 不是同一套分类，不做自动双写或强行值映射。

**采集路径（已核实，两条协议口径不同，须统一后再落列）**：

| 字段 | Web（Baileys） | Android（Go） |
|---|---|---|
| `friend_count` | 联系人靠 app-state 同步**被动到达**（`commands/contact-app-state-key.ts`、`contact-save-executor.ts`），协议层未落库计数，需新增 | 联系人已落 MySQL（`internal/service/axolotl/store/contacts.go` 的 `LoadContacts(ownerId)`），**COUNT 即得** |
| `is_group_invite_allowed` | `sock.fetchPrivacySettings()` 可读（`node_modules/baileys/lib/Socket/chats.d.ts:33`），返回 `{[key]: string}`，取 `groupadd` 项 —— **需主动请求** | `iq.go:219` 有 `case "privacy"` 分支，但当前只处理 status privacy，拉群隐私能力**待确认** |

好友数和拉群权限按以下规则刷新：账号上线成功后，字段为空或距上次同步超过 24 小时才异步入队；
后台只补扫在线且过期的账号。任务试算与正式圈号**不在请求内主动查协议**，只读最近一次画像；设置了
相关筛选时 NULL 不匹配，同时返回画像覆盖率和最近同步时间，避免为一次筛选高频探测伤号。

轮号状态来自 Armada 轮号流程；五类来源来自导入/运营来源规范化；注册时间优先读取号源提供的
注册日期，只有注册天数时按导入时点反推 `registered_at` 并记录 `registered_at_source=2`。

**完整性硬约束**：字段和筛选控件随任务菜单一起交付，不因暂缺数据源隐藏。某画像未知时保存 NULL，
带上下界的筛选不匹配 NULL；页面显示画像最近同步时间。Android 拉群权限和两侧好友数口径未打通时，
任务菜单只能标为依赖未完成，不能标为完整复刻。

### 8.3 注册天数口径

WhatsApp 协议本身**不对外暴露账号注册时间**，Baileys 与 Go 协议均无直接数据；但竞品任务筛选
明确提供独立的注册天数范围和 90/180/365/730/1095 天快捷值，因此 Armada 必须承接这个业务字段。

hylb 的账号筛选里 `retention_days`（存活天数）与 `register_days`（注册天数）是**两个独立字段**
（见 `readable/assets/account-filter-modal-BXDIvipG.js`），但存档里没有任何 tooltip 解释二者差别。
冻结口径：

1. 「留存天数」= 从 Armada 入库/开始运营到当前的天数，可由 `account.created_at` 计算，允许小数。
2. 「注册天数」= WhatsApp 号龄，按 `now - account_profile.registered_at` 计算整数天。
3. 注册时间由号源/导入字段提供；只有号龄时在导入时反推日期并记录来源，未知则 NULL。
4. 任何筛选边界都不把 NULL 当 0；未知画像只有在未设置注册天数条件时才可入选。

---

## 九、Flyway 迁移编排

| 阶段 | 内容 |
|---|---|
| 一期数据包 | `data_package` / `data_package_phone` / `data_package_stat` / `data_package_import` |
| 一期模板 | `hyperlink_template` |
| 一期菜单权限 | 超链数据包、超链营销模板和对应 RBAC |
| 二期策略 | `hyperlink_strategy` |
| 二期素材 | `marketing_template_file` 加管理列 + `resource_asset_tag` + `resource_asset_tag_ref` |
| 二期任务前置 | `hyperlink_template.title` 扩到 1024 + `account_profile` + `account` 组合筛选索引 + `data_package_phone` 领取 owner/索引 |
| 二期任务核心 | `hyperlink_task` / `_content` / `_runtime` / `_round` / `_account_usage` / `_round_account` / `_recipient_claim` / `_recipient` / `_billing_reservation` |
| 二期任务查询投影 | `hyperlink_task_account_stat` |
| 二期回流与点击 | recipient 首触归因字段与访问趋势索引已在任务核心；封号/失效事实并入 `_account_usage`，不新增逐次点击或 30 分钟桶表 |
| 二期分析 | `hyperlink_stat_daily` / `hyperlink_stat_hourly`（小时表滚动保留 8 天） |
| 二期账号画像 | 创建/扩展 `account_profile`，同步好友数、拉群权限、轮号、注册时间和五类来源 |

约束：

- 实施前同步目标分支并从全局最高 Flyway 版本继续编号；本文不写死版本号。
- `ADD COLUMN` 一律用 `information_schema` 守卫保证幂等。
- 公共素材未来迁移必须先上兼容代码，不能把直接改表名作为第一步。
- schema 落地后重跑 `.harness/wiki/gen_datamodel.py` 刷新 `数据模型.md`，**禁手改**。
- 所有新列必须带 `COMMENT`（自动文档依赖它）。

---

## 十、冻结决策与外部交付依赖

### 10.1 已决（2026-08-28）

| # | 决策 |
|---|---|
| 1 | 接口命名走 `/api/hyperlink-tasks` + camelCase，与现有 Controller 一致 |
| 2 | 数据包单次导入上限 **5000 行**；单包阈值可配置、默认 500000（§3.4） |
| 3 | 覆盖导入使用代际切换，不在关键事务内删除旧号码；旧代按保留期分批清理 |
| 4 | 包级状态统计放在 `data_package_stat`，不放主表、不开放租户 `recount` API |
| 5 | 任务收件人保存包代次/导入批次/号码/国家快照，不保存 `data_package_phone_id` |
| 6 | 超链模板独立于群营销模板；一期图片复用 `marketing_template_file`，不改表名 |
| 7 | **不做**国家风险拦截，`blocked_rows` / `blocked_country_iso2s` 不落列（§3.4） |
| 8 | `wid_type` 由协议派生，`platform` 由设备 OS + 账号类型 + 协议组合派生，均不落冗余列（§8.1） |
| 9 | 存活天数由 `now - account.created_at` 派生，不落列 |
| 10 | 竞品最后核对的余额/单价/预计冻结必须实现；`hyperlink_billing_reservation` 与任务 1:1，按冻结数据包人数一次预约，钱包总账仍由真实提供方负责 |
| 11 | 任务状态拆 `is_enabled` + `run_status` 两个正交字段；任务状态/列表投影放 runtime，调度时间/生成游标放 round |
| 12 | 消息间隔落**毫秒**列，竞品是 0.1 秒精度（§4.2） |
| 13 | 深度追踪是按钮级；短码、点击累计、首末时间和首触环境都落在唯一 recipient 上（§4.5），不建逐次点击流水 |
| 14 | **不开放双图文**——竞品新建时也只有单图文/普通按钮/卡片按钮三种（§4.3） |
| 15 | 策略删掉 `account_send_concurrency` / `msg_interval_*` 三列（§5.2） |
| 16 | `default_sub_task_num=50` 解释为调度批量切片大小，不落列、不参与业务计数和计费 |
| 17 | 素材走 `marketing_template_file` **加列**，不新建第二张素材表；`ref_count` 不落列（§6.1） |
| 18 | 分析的去重计数照抄竞品「跨行相加」口径，不做全局 `COUNT(DISTINCT)` 回源；日/小时分别读 90 天/8 天投影（§7） |
| 19 | 超链任务**不套用分组级账号占用锁**（占用模型是分组粒度，超链按筛选跨分组圈号） |
| 20 | 深度归因在 recipient 保存首次 IP/user-agent/设备等首触快照，敏感读取/导出加独立权限和审计，首触环境 90 天后置空；累计次数与首末时间长期保留 |
| 21 | 任务物理模型按工作负载固定为 10 张任务表 + 1 张共享画像表；领号作业、账号执行用量/轮次分配和账号累计统计独立落表；访问趋势按 recipient 首访时间直接聚合，不建账号小时、30 分钟桶、逐次点击、独立短链、封号、收信人轮次或发送尝试表；本次复核后表数不变 |
| 22 | 任务无删除能力，因此 `hyperlink_task` 不落无写入方的软删列 |
| 23 | 目标国家以 JSON 数组快照承接多国家数据包，不为列表筛选另建映射表 |
| 24 | 同一任务内一个收信号码最多发送一次；round 只负责按周期选择发信账号并分配剩余 recipient，不复制收信人、不按轮重复计费；协议超时重放同一 command_id；停止前尚未提交的行按 `TASK_STOPPED` 失败落账，不另设“跳过”状态 |
| 25 | 任务和模板标题统一放宽到 1024，补齐竞品已存在的长度能力 |
| 26 | ACK 不逐条更新 runtime 热行；先推进 recipient/account_usage，再由幂等批处理分钟级维护 round/runtime 和账号投影；`metrics_updated_at` 只代表发送指标投影新鲜度，点击原子更新不得推进它 |
| 27 | 账号无时间范围查询使用 account_stat 累计投影并 JOIN account_usage 取得唯一展示快照；任意时间范围直接按 recipient 任务×时间索引精确聚合，最坏扫描单任务 50 万行；访问趋势直接按 recipient 首访时间索引聚合，当前业务单任务不超过 10 万 |
| 28 | `account_max_send_num` 是任务内跨轮成功上限；task_account_usage 同步占槽，round_account 固化每轮选号，不能用异步账号统计或 recipient COUNT 参与派发 |
| 29 | 50 万号码用 recipient_claim + phone claim owner 分批领取/释放；禁止单个长事务冻结全包；代次互斥只覆盖准备、领取、释放和失败恢复，进入 OWNED 后释放操作锁，号码级 owner 继续隔离归属 |
| 30 | runtime 用 `active_since_at + execution_duration_sec` 累计有效运行时长；暂停冻结、继续续算，避免把墙钟等待时间算进执行时长 |
| 31 | round 的业务到期时间与 worker 租约分离；`next_dispatch_at` 表示可执行时间，`lease_owner/lease_expires_at` 支撑选号和派发崩溃接管 |
| 32 | 计费一行继续保持 task 1:1，但必须用 `pending_operation + operation_idempotency_key + next_retry_at` 区分并恢复冻结、调整、结算和释放 |
| 33 | task.`is_short_link_enabled` 是 content 按钮配置的派生投影，task/content 同事务保存；内容表为两个素材引用补反查索引 |
| 34 | account_stat 只保存累计指标；账号号码、国家、类型、入库时间四项展示快照唯一保存在 account_usage，查询时小表 JOIN，禁止双份快照漂移 |

### 10.2 外部交付依赖（不再改变本文表结构）

| # | 问题 | 影响 |
|---|---|---|
| 1 | `account_profile` 与 `account` 组合索引的共享模型全局评审 | 未通过前不能私自迁移 account 域结构 |
| 2 | 号源/导入提供 `registered_at` 或号龄 | 决定注册天数覆盖率；未知继续为 NULL |
| 3 | Android 拉群隐私读取能力 + 两侧好友数统一口径 | 未打通时任务不能标为完整复刻 |
| 4 | 深度追踪专用域名和证书 | 必须与买量域名隔离，避免封禁故障域互相拖累 |
| 5 | `HyperlinkBillingGateway` 的真实提供方、价码和结算规则 | 未接真实账务时不得上线启用任务 |

> **勘误**：本文与设计文档早先提到的「armada 的 `balances` / `consume-stats` 体系」不存在，
> 那几个是 hylb 的接口。这个事实不再用于删除竞品计费功能：任务域先冻结
> `HyperlinkBillingGateway`、报价快照和任务冻结预约契约，真实钱包/账务提供方是上线硬依赖。
