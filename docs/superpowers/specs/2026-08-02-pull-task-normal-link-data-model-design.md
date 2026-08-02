# 普通群链接拉群任务数据模型设计

日期：2026-08-02
状态：结构已确认；确认后创建 `V090__pull_task_normal_link_execution.sql`，在此之前不修改生产表
范围：`pull_task` 的 `STANDARD` 普通群链接任务，不含拉群营销、群组管理、新群、速拉和公告群

## 1. 设计原则

**每张表必须能回答“谁在什么时候按什么条件查它”。回答不了就不建表，把字段挂到已有实体上。**

据此得到 **6 张新表 + `pull_task` 增加 3 列**。上一版草案的 12 张新表里，5 张被删除或合并、1 张（人工操作审计）本期不做，逐条理由见 §7。

其余口径：

1. 复用 `pull_task` 作为任务主表，复用 `group_link` 作为群入口事实，不建第二套任务主表或群链接主表。
2. `config_json` 只保留展示快照，执行器不得从 JSON 恢复任何业务状态。
3. 创建页的随机匹配写入 **草稿任务**（`pull_task.status='DRAFT'`）。最终创建 = `DRAFT → WAIT_START` 一次状态迁移，不重新随机。
4. TXT 原文件内容和非法原文不落库；只保存文件元数据、解析统计、规范化号码和首次有效行号。被拒链接行与非法 TXT 行只在预览响应中瞬态返回，不持久化。
5. 明确结果不重试；`UNKNOWN` 是独立状态，只能由查询或回调收敛。
6. 人工暂停和资源等待用独立字段表达，资源恢复不解除人工暂停。
7. 不定义数据库外键，沿用项目现状由 Service 保证引用完整性；所有业务表都有 `tenant_id`。
8. 本期不做人工操作审计表，也不做复制任务。见 §8 的已知取舍。

## 2. 关系概览

```text
pull_task  (复用，+3 列，status 增加 DRAFT)
  ├─ 1:1 pull_task_standard_setting
  └─ 1:N pull_task_group_execution        ← 一行 = 一条群链接 ↔ 一个 TXT
          ├─ 1:N pull_task_material_member   ← 号码 + 入群结果 + 提权结果
          ├─ 1:N pull_task_group_account     ← 管理/拉手/站台 + 在群状态 + 拉手占用
          ├─ 1:N pull_task_account_action    ← 加好友 / 邀请入群 / 踩链接入群
          └─ 1:N pull_task_pull_call         ← 单次批量加成员协议调用

pull_task_group_execution ── N:1 group_link
pull_task_group_account   ── N:1 account
```

## 3. 通用约定

- 时间列一律 `BIGINT`，epoch 毫秒，与 `pull_task` 家族一致。
- 建表统一 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`，所有列带中文 `COMMENT`。
- **任何进入唯一键或需要精确匹配的字符串列必须显式声明 `CHARACTER SET ascii COLLATE ascii_bin`**：`normalized_link`、`invite_code`、`group_jid`、`normalized_phone`、`account_phone`、`command_id`、`idempotency_key`。表默认排序规则 `utf8mb4_0900_ai_ci` 大小写不敏感，而 WhatsApp 邀请码是大小写敏感的 22 位串，不声明会把两条不同链接判为重复。既有 `group_link.link_url` 就是为此声明 `utf8mb4_bin` 的（`V003__group_import_links.sql`）。
- 生成列一律 `CASE WHEN <有效条件> THEN <值> ELSE NULL END`，**else 分支必须是 NULL**，与 `V089` / `V005` 的 `active_key` / `is_active` 同款。写成 0 会让唯一索引只允许一条历史记录。
- 后台调度器没有租户上下文（`MyBatisConfig` 无上下文时 fail-closed 回退 `-1`），跨租户扫描的 Mapper 必须标 `@InterceptorIgnore(tenantLine = "true")`，并配一条**不以 `tenant_id` 打头**的索引，与 `protocol_command_outbox.idx_dispatch` 同款。

## 4. 现有表调整

### 4.1 `pull_task`

新增 3 列：

| 列 | 类型 | 空值/默认 | 说明 |
|---|---|---|---|
| `started_at` | BIGINT | NULL | 首次真实启动时间 |
| `finished_at` | BIGINT | NULL | 进入 `COMPLETED` 或 `ENDED` 的时间 |
| `version` | INT | NOT NULL DEFAULT 1 | 生命周期更新乐观锁 |

`status` 取值集合增加 `DRAFT`，完整集合为 `DRAFT / WAIT_START / EXECUTING / PAUSED / COMPLETED / ENDED`。

新普通任务写入口径：

- `task_type='STANDARD'`、`mode='GROUP_LINK'`；历史 `OLD_LINK` 只兼容读取，新建接口不再产生。
- **任务列表、看板和所有统计一律过滤 `status <> 'DRAFT'`。** 草稿只在创建页可见。
- 一个用户同一时刻只保留一个 `STANDARD` 草稿：重新进入创建页复用同一行，创建页的“清除全部”删除该草稿的全部执行行。因此不需要预览过期作业——遗留草稿最多是每人一行。
- `config_json` 对 `STANDARD` 只写创建页原样快照，供展示和排查；草稿期允许 `{}`。
- 父任务 `group_count`、`expected_pull_count` 是冻结时写入的计划汇总；真实结果一律从执行明细聚合。
- 单群进入资源等待不会把仍有其他群在跑的父任务改成 `PAUSED`。

不新增列，理由记录在此以免反复：`copied_from_task_id` 随复制任务一并取消；`ended_at` 由 `status` 区分终态类型 + `finished_at` 记时间即可。

现有 `primary_stage` / `blocking_reason`（`V088` 已存在）对普通任务的口径定义为：取当前所有非终态执行行中 `stage` 最小者作为 `primary_stage`；`blocking_reason` 只在父任务被人工暂停或全部执行行都处于等待/终态时写入，不逐群同步。

### 4.2 复用表

- `group_link`：最终创建（`DRAFT → WAIT_START`）时按规范化链接复用或插入，新增记录 `origin = 3`。**草稿期不写 `group_link`**，草稿不污染群入口池。
- `group_link_preview` / `group_link_health`：群 JID、公开邀请页预览和健康结果。
- `account` / `account_state`：账号分组、在线、风控和冷却的事实源。
- `account_group_membership`：成功动作回写后用于群成员关系对账，不替代任务逐动作历史。
- `protocol_command_outbox`：所有真实协议命令继续走 Outbox；payload 只放任务、执行行和动作行 ID，不复制凭据。

## 5. 新增表

### 5.1 `pull_task_standard_setting`（冻结执行配置）

一条普通任务一行，`PRIMARY KEY (tenant_id, task_id)`。

| 字段 | 类型 | 空值/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | BIGINT | NOT NULL | 租户 ID |
| `task_id` | BIGINT | NOT NULL | `pull_task.id` |
| `auto_start` | TINYINT(1) | NOT NULL DEFAULT 0 | 创建后是否自动启动 |
| `material_admin_timing` | TINYINT | NOT NULL | 1=成员入群后立即，2=本群料子全部终态后 |
| `pull_count_min` / `pull_count_max` | INT | NOT NULL | 单次料子人数闭区间，不含站台 |
| `pull_interval_seconds` | INT | NOT NULL | 同一拉手账号连续拉人调用的最小间隔 |
| `puller_count_per_group` | INT | NOT NULL | 每条执行行的计划拉手数 |
| `station_count_per_call` | INT | NOT NULL | 每一次拉人调用叠加的站台数 |
| `concurrent_group_count` | INT | NOT NULL | 同一父任务最大同时运行执行行数 |
| `puller_risk_minutes` | INT | NOT NULL DEFAULT 0 | 拉手风控冷却分钟；0=不定时恢复 |
| `required_manager_count` | INT | NOT NULL DEFAULT 0 | **任务启动时**按管理分组可用账号数冻结的 N |
| `manager_group_id` / `puller_group_id` / `station_group_id` | BIGINT | NOT NULL | 三类账号分组 ID |
| `manager_group_name` / `puller_group_name` / `station_group_name` | VARCHAR(100) | NOT NULL | 分组名称快照，与 `account_group.name` 同宽 |
| `created_at` / `updated_at` | BIGINT | NOT NULL | |

- `required_manager_count` 必须落在任务级：执行行受并发槽位控制、启动时刻不同，逐行冻结会得到互不相同的 N，导致各群的“缺少管理员人数”口径不一致。
- Service 校验 `1 <= pull_count_min <= pull_count_max`，数量与间隔非负，`puller_count_per_group` 和 `concurrent_group_count` 大于 0。
- 管理邀请拉手的 1 秒间隔是本期固定业务常量，不建可配置列。

### 5.2 `pull_task_group_execution`（群链接 × TXT 执行行）

一行就是一个冻结的“群链接 ↔ TXT”。草稿期即写入，最终创建只改状态，不重新随机。

| 字段组 | 字段 | 说明 |
|---|---|---|
| 标识 | `id` BIGINT AI, `tenant_id`, `task_id` **NOT NULL**, `seq` INT | 草稿也是任务，`task_id` 全程非空 |
| 群链接 | `group_link_id` BIGINT NULL, `normalized_link` VARCHAR(255) **ascii_bin**, `invite_code` VARCHAR(64) **ascii_bin**, `source_link_line_no` INT, `group_jid` VARCHAR(128) **ascii_bin** NULL | `group_link_id` 冻结时回填；`group_jid` 启动校验时回填 |
| TXT | `source_file_index` INT, `source_file_name` VARCHAR(255), `total_line_count`, `valid_member_count`, `invalid_line_count`, `duplicate_line_count` INT | 不保存文件内容 |
| 状态 | `execution_status` TINYINT, `stage` TINYINT, `manual_paused` TINYINT(1), `wait_resource_type` TINYINT NULL, `reason_code` VARCHAR(64) NULL, `reason_message` VARCHAR(255) NULL | 人工暂停与资源等待独立 |
| 检查点 | `next_manager_index` INT, `next_puller_index` INT, `next_run_at` BIGINT | |
| 调度锁 | `lock_owner` VARCHAR(64) NULL, `lock_expires_at` BIGINT NULL, `version` INT | 超时锁可被抢占回收 |
| 占用 | `link_occupancy_key` VARCHAR(255) ascii_bin GENERATED | 见下 |
| 时间 | `started_at`, `finished_at`, `last_business_executed_at`, `created_at`, `updated_at` | |

枚举：

- `execution_status`：0=草稿，1=待启动，2=执行中，3=等待资源，4=已完成，5=失败终态，6=已放弃。
- `stage`：1=链接校验，2=管理入群，3=管理—拉手联系人，4=管理邀请拉手，5=拉人执行（含拉手—站台联系人），6=料子提权，7=收口。
- `wait_resource_type`：1=管理员，2=拉手，3=站台；NULL=非资源等待。

生成列：

```sql
link_occupancy_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (
        CASE WHEN execution_status IN (1, 2, 3) THEN normalized_link ELSE NULL END
    ) STORED
```

约束和索引：

- `UNIQUE (tenant_id, task_id, seq)`
- `UNIQUE (tenant_id, task_id, normalized_link)` — 单任务内链接唯一
- `UNIQUE (tenant_id, link_occupancy_key)` — **同一群链接不被两个普通任务同时占用**；草稿不占用，终态释放
- `INDEX (tenant_id, task_id, id)` — 任务详情分页
- **`INDEX (execution_status, manual_paused, next_run_at, id)`** — 不带租户前缀，供后台调度器跨租户取待执行行
- `INDEX (tenant_id, group_link_id, id)` — 按群入口反查

不设列的决策：

- **不设 `current_manager_count` / `current_puller_count` 等资源快照列。** 详情页按 `group_account` 现算（`LEFT JOIN ... GROUP BY`，单页 20 行）。快照列意味着要在 6 个写路径上同步计数器，且随时可能与明细不一致。
- **不设料子游标列。** “下一个未消费料子” = `pull_task_material_member` 中该执行行 `pull_call_id IS NULL` 且 `member_seq` 最小的行，走既有索引即可。
- 不设 `content_sha256` / `source_file_size`：本期不做内容级去重（需求明确“不同 TXT 独立解析，不做任务级全局去重”），没有消费方。

### 5.3 `pull_task_material_member`（料子号码 + 逐号码结果）

一条规范化号码一行。单文件内按清洗后号码去重，保留首次出现的顺序和行号；重复行中任意 `A/a` 提升 `admin_required`。

| 字段 | 类型 | 空值/默认 | 说明 |
|---|---|---|---|
| `id` | BIGINT | AI | |
| `tenant_id`, `group_execution_id` | BIGINT | NOT NULL | |
| `member_seq`, `source_line_no` | INT | NOT NULL | 去重后稳定顺序 / 首次有效行号 |
| `normalized_phone` | VARCHAR(32) **ascii_bin** | NOT NULL | 7–15 位含国家码纯数字 |
| `admin_required` | TINYINT(1) | NOT NULL DEFAULT 0 | 是否带 `A/a` |
| `pull_call_id` | BIGINT | NULL | 被哪次调用消费；**NULL 即未消费** |
| `pull_status` | TINYINT | NOT NULL DEFAULT 0 | 0=未消费 1=已提交 2=成功 3=失败 4=未知 5=取消 |
| `pull_reason_code` / `pull_reason_message` | VARCHAR(64/255) | NULL | 脱敏原因 |
| `wa_jid` | VARCHAR(128) ascii_bin | NULL | 成功入群后的成员 JID |
| `pull_result_at` | BIGINT | NULL | |
| `admin_status` | TINYINT | NOT NULL DEFAULT 0 | 0=不需要 1=待执行 2=已提交 3=成功 4=失败 5=未知 6=取消 |
| `admin_command_id` | VARCHAR(64) **ascii_bin** | NULL | 提权协议命令 ID |
| `admin_reason_code` | VARCHAR(64) | NULL | |
| `admin_result_at`, `created_at`, `updated_at` | BIGINT | | |

约束和索引：

- `UNIQUE (tenant_id, group_execution_id, member_seq)`
- `UNIQUE (tenant_id, group_execution_id, normalized_phone)` — 单文件去重键
- `INDEX (tenant_id, group_execution_id, pull_status, member_seq)` — 取下一批未消费料子
- `INDEX (tenant_id, group_execution_id, admin_required, admin_status, id)` — 取待提权料子
- `INDEX (tenant_id, admin_command_id)` — 提权回调定位

拉人结果直接落在本表而不是单独的“参与者”表：一个料子号码一生只属于一次调用，这个不变量应该由一个列（`pull_call_id`）结构性表达，而不是靠另一张表上的唯一索引。

### 5.4 `pull_task_group_account`（角色账号 + 在群状态 + 拉手占用）

保存管理、拉手、站台在某条执行行中的选择、当前状态和占用。同一站台在同一执行行只出现一次，允许跨执行行复用。

| 字段组 | 字段 | 说明 |
|---|---|---|
| 标识 | `id`, `tenant_id`, `task_id`, `group_execution_id` | |
| 账号 | `account_id` BIGINT, `account_phone` VARCHAR(32) **ascii_bin** | 号码为展示快照，其余属性 join `account` |
| 角色 | `role_type` TINYINT, `role_seq` INT, `source_type` TINYINT, `selection_mode` TINYINT, `entry_mode` TINYINT NULL | 1=管理 2=拉手 3=站台；来源 1=初始 2=补充；入群方式 1=踩链接 2=管理员邀请 3=拉手拉入 |
| 在群 | `membership_status` TINYINT, `joined_at` BIGINT NULL, `pull_call_id` BIGINT NULL | 站台记录由哪次调用拉入 |
| 权限 | `admin_status` TINYINT | 仅 `role_type=1` 有意义 |
| 可用性 | `availability_status` TINYINT, `unavailable_reason_code` VARCHAR(64) NULL, `cooldown_until` BIGINT NULL | 风控冷却与恢复依据 |
| 占用 | `occupied_at` BIGINT NULL, `released_at` BIGINT NULL, `occupancy_key` BIGINT GENERATED | 仅拉手 |
| 时间 | `created_at`, `updated_at` | |

枚举：

- `membership_status`：0=未入群 1=入群中 2=在群 3=入群失败 4=结果未知。
- `admin_status`：0=不适用 1=待设置 2=已提交 3=成功 4=失败 5=未知。
- `availability_status`：1=可用 2=风控冷却 3=离线或不可用 4=已移出本行。

生成列：

```sql
occupancy_key BIGINT GENERATED ALWAYS AS (
    CASE WHEN role_type = 2 AND released_at IS NULL THEN account_id ELSE NULL END
) STORED
```

约束和索引：

- **`UNIQUE (tenant_id, occupancy_key)`** — 同一拉手账号同时只服务一条执行行，跨父任务生效。执行行完成、失败或进入资源等待时写 `released_at` 释放；恢复时把 `released_at` 置回 NULL 重新竞争，被别的任务抢走则唯一键直接拒绝，符合“恢复时重新竞争拉手”的要求。
- `UNIQUE (tenant_id, group_execution_id, role_type, account_id)` — 顺带保证站台同群只入一次。
- `UNIQUE (tenant_id, group_execution_id, role_type, role_seq)`。
- `INDEX (tenant_id, group_execution_id, role_type, availability_status, id)`。
- `INDEX (tenant_id, account_id, role_type, id)` — 按账号反查参与过的执行行。

单独的拉手租约表被这个部分唯一索引取代：租约的唯一职责是账号级互斥，而拉手行本来就在这张表里，两套记录还要互相同步。

**补充资源不建表**：用户确认的“补充指令”其持久化形态就是新插入的若干行——`source_type=2` 记录来源，`entry_mode` 记录进群方式，`selection_mode` 记录自动/手动。这些行本身不可变，暂停重启后从它们的 `membership_status` 继续推进。站台补充强制 `entry_mode IS NULL`。

### 5.5 `pull_task_account_action`（账号动作：加好友 / 邀请入群 / 踩链接入群）

替代原草案的联系人表与邀请表，并补上原草案缺失的“管理员踩链接入群”“站台被拉手拉入”记录。

| 字段 | 类型 | 空值/默认 | 说明 |
|---|---|---|---|
| `id` | BIGINT | AI | |
| `tenant_id`, `task_id`, `group_execution_id` | BIGINT | NOT NULL | |
| `action_type` | TINYINT | NOT NULL | 1=保存联系人 2=邀请入群 3=踩链接入群 |
| `actor_group_account_id` | BIGINT | NULL | 动作发起方；踩链接时为空 |
| `target_group_account_id` | BIGINT | NOT NULL | 动作对象 |
| `action_status` | TINYINT | NOT NULL DEFAULT 1 | 1=待执行 2=已提交 3=成功 4=失败 5=未知 6=取消 |
| `command_id` | VARCHAR(64) **ascii_bin** | NULL | 协议命令 ID |
| `reason_code` / `reason_message` | VARCHAR(64/255) | NULL | 脱敏原因 |
| `submitted_at`, `result_at`, `created_at`, `updated_at` | BIGINT | | |

约束和索引：

- `UNIQUE (tenant_id, group_execution_id, action_type, actor_group_account_id, target_group_account_id)` — 天然幂等键。双向加好友是 actor/target 互换的两行，各自独立记录结果；服务重启不会对已确认成功的动作重复提交。
- **`UNIQUE (tenant_id, command_id)`** — 协议回调按 `command_id` 定位到具体动作行。
- `INDEX (tenant_id, group_execution_id, action_status, id)` — 取本行待执行动作。

不设 `retry_count`：明确结果即终态，不产生第二行也不发第二次命令。不设 `request_id`：这些是系统自动动作，上面的唯一键已经是幂等键。

“下一位待邀请拉手”由本表推导：本行 `role_type=2` 的 `group_account` 中，不存在 `action_type=2` 对应行者即待邀请，无需额外游标。

### 5.6 `pull_task_pull_call`（单次批量加成员调用）

一行代表一个拉手对同一群 JID 的一次真实批量加成员请求。

| 字段 | 类型 | 空值/默认 | 说明 |
|---|---|---|---|
| `id` | BIGINT | AI | |
| `tenant_id`, `task_id`, `group_execution_id` | BIGINT | NOT NULL | |
| `call_seq` | INT | NOT NULL | 本执行行内序号 |
| `puller_group_account_id`, `puller_account_id` | BIGINT | NOT NULL | |
| `planned_material_count`, `planned_station_count` | INT | NOT NULL | 本次料子人数（闭区间随机结果）与站台数 |
| `call_status` | TINYINT | NOT NULL DEFAULT 1 | 1=计划 2=已提交 3=已回写 4=结果未知 5=取消 |
| `command_id` | VARCHAR(64) **ascii_bin** | NULL | |
| `idempotency_key` | VARCHAR(64) **ascii_bin** | NOT NULL | 计划阶段生成 |
| `reason_code` / `reason_message` | VARCHAR(64/255) | NULL | |
| `submitted_at`, `result_at`, `created_at`, `updated_at` | BIGINT | | |

约束和索引：

- `UNIQUE (tenant_id, group_execution_id, call_seq)`
- `UNIQUE (tenant_id, idempotency_key)`、`UNIQUE (tenant_id, command_id)`
- `INDEX (tenant_id, puller_account_id, submitted_at, id)` — 校验同一拉手账号的连续调用间隔

**计划—提交事务规则**：一个事务内写入 call 行（`call_status=1`，含 `idempotency_key`）、把本次料子的 `pull_call_id` 指向该 call、把本次站台的 `pull_call_id` 指向该 call，然后才投递协议命令。崩溃恢复时看到 `call_status=1` 的行，用原 `idempotency_key` 重投，绝不重新分配料子。

## 6. 关键一致性规则

1. `DRAFT → WAIT_START` 只允许一次，冻结事务同时改任务状态、写 setting、复用或插入 `group_link` 并回填 `group_link_id`、把执行行 `execution_status` 从 0 改为 1。重复提交因状态前置校验失败而不会产生第二个任务。
2. 单文件单群：单任务内链接唯一、文件序号唯一；成员在执行行内按规范化号码唯一。
3. 明确结果不重试：动作表、调用表和料子行都没有重试次数字段；重复回调只更新同一行。
4. `UNKNOWN` 不释放为可重新提交的资源，只允许被查询或回调收敛为成功或失败。
5. 拉手跨任务互斥由 `pull_task_group_account.occupancy_key` 唯一索引保证，不依赖 JVM 内存锁。
6. 站台同执行行唯一由 `UNIQUE (tenant_id, group_execution_id, role_type, account_id)` 保证；跨执行行无全局限制。
7. 群链接跨任务互斥由 `link_occupancy_key` 唯一索引保证，**只覆盖普通任务之间**；与拉群营销任务的群占用互不感知，见 §8。
8. 调度取行必须同时满足 `manual_paused=0`、无资源等待、父任务非 `PAUSED`/`ENDED`，且抢到 `lock_owner`。
9. 一切汇总都是查询投影，事实来自角色行、动作行、调用行和料子行，可全量重算。
10. 人工操作幂等靠状态前置校验 + 乐观锁，不靠幂等记录表，见 §8。
11. 所有 Mapper 默认经租户拦截；需要跨租户扫描的调度类 Mapper 显式标注 `@InterceptorIgnore`，并只走无租户前缀的索引。

## 7. 相对上一版草案的删减

| 上一版表 | 处理 | 理由 |
|---|---|---|
| `pull_task_standard_plan` | 删，改用 `pull_task.status='DRAFT'` | 预览计划本质是未提交的任务。用草稿任务承载后，不需要 plan_token、不需要执行行上的 `task_id NULL`、不需要预览过期 GC 作业 |
| `pull_task_contact_action`<br>`pull_task_puller_invite` | 合并为 `pull_task_account_action` | 两表字段几乎相同；合并后顺带覆盖原本没有落点的“管理员踩链接入群”“站台入群” |
| `pull_task_pull_call_participant` | 删，结果落回本体表 | 料子结果归 `material_member.pull_call_id`，站台结果归 `group_account.pull_call_id`。“一个料子只属于一次调用”由列结构性表达，比另一张表加唯一索引更直接 |
| `pull_task_resource_supplement` | 删 | 补充指令的持久化形态就是新插入的 `group_account` 行 |
| `pull_task_puller_lease` | 删，改为 `group_account.occupancy_key` 部分唯一索引 | 租约只为账号级互斥，拉手行本就在 `group_account` 里 |
| `pull_task_operation_log` | 本期不做 | 见 §8 |

同时删除的列：执行行的 5 个资源快照列（改现算）、4 个游标压成 2 个、4 个锁列压成 2 个、`content_sha256`、`source_file_size`、`copied_from_task_id`、`ended_at`、动作表的 `request_id`。

一并修复的上一版缺陷：唯一键列缺少二进制排序规则、`required_manager_count` 冻结粒度与需求 #37 冲突、动作表 `command_id` 无索引导致回调无查询路径、调度索引以 `tenant_id` 打头导致后台任务扫不到行、生成列 else 分支未定义、预览行无回收机制、§1 承诺持久化失败原因但无承载表。

## 8. 已知取舍

以下五项是明确决定不做或降级，不是遗漏：

1. **不持久化被拒的链接行和非法 TXT 行。** 文件名、原始行号和失败原因只在预览响应里瞬态返回，用户修正后重传。任务创建成功后这些数据没有消费方，为它单建一张表不划算。代价：创建页刷新后看不到上一次的拒绝明细。
2. **不做人工操作审计表。** 谁在什么时候暂停、恢复、结束或补充资源不留痕。仓库里也没有通用审计表可借。这张表是纯追加的，将来要补只是一个新增表的迁移，不动这 6 张表的任何字段。
3. **人工操作幂等改为状态前置校验 + 乐观锁**：
   - 暂停/恢复/结束（任务级和单群）：`UPDATE ... WHERE status IN (合法前置态) AND version = ?`；重复提交发现已是目标态，返回当前状态视为成功。
   - 补充管理员/拉手/站台：服务端校验“当前数 + 补充数 ≤ 计划数”；手动选号时 `UNIQUE (tenant_id, group_execution_id, role_type, account_id)` 直接吞掉重复。
4. **不做复制任务。** 新 PRD 原型（`proto-v1/index.html`）里的“复制任务”只出现在拉群营销任务的看板 mock 行中，属于本期排除的营销分支；普通群链接版列表没有该操作。需要整体重做时新建任务、重新粘贴链接和上传 TXT。
5. **群占用只覆盖普通任务之间。** 拉群营销侧有独立的 `pull_task_group_marketing_group_occupancy`（按 `group_jid` 唯一）。两套占用互不感知，同一个群可能被一个普通任务和一个营销任务同时选中。要跨模式互斥需要泛化营销那张表，本期不做。

## 9. 迁移与回滚

- 迁移版本：`V090__pull_task_normal_link_execution.sql`（`V089` 是当前最高版本，无冲突）。
- 内容：新增 6 张表，给 `pull_task` 增加 3 列。不修改拉群营销表，不迁移历史营销任务。
- 历史 `STANDARD/OLD_LINK` 任务保留可读；没有执行行的历史任务不允许启动，页面提示新建任务。
- change 记录维护手工回滚 SQL：按依赖逆序 `DROP` 6 张新表，再删 `pull_task` 的 3 个新列。共享库或生产回滚前仍需单独确认环境。
- Flyway 完成后用仓库生成器更新 `.harness/wiki/数据模型.md`，不手工编辑生成文档。

## 10. 验证门禁

关于测试基座的事实：**Flyway 只对真实 MySQL 生效**（`DbTestBase` 启动完整上下文连测试库，凭据经 `armada-api/dbtest.sh` 从 gitignored 的 `.env` 注入）。H2 内存测试（`MysqlModeMapperInMemoryTest` 等，`MODE=MySQL`）的建表语句是在测试类里手工维护的，不跑迁移脚本。

因此：

1. **排序规则、生成列和唯一键语义必须由真库 DbTest 断言**，H2 复现不了——H2 默认大小写敏感，会让 `ai_ci` 造成的重复判定问题静默通过。至少要覆盖：邀请码仅大小写不同的两条链接可以共存、同一拉手被第二条执行行占用时唯一键抛错、释放后可重新占用。
2. 新增 6 张表意味着 H2 测试类里要同步 6 段手写 DDL，这部分只用于 Mapper SQL 与租户拦截器的验证。
3. 每张表都要有跨租户不可见、不可更新的租户隔离测试。
4. 分阶段门禁沿用需求文档的 M1～M4；每阶段保存实际命令、退出码和 Playwright trace/截图，没有真实输出不声明通过。

M1 需要全部 6 张表：最小闭环要跑通“管理入群 → 管理—拉手好友 → 邀请拉手 → 拉手—站台好友 → 批量拉人 → 逐号码回写”，每一步都落在其中一张表上。
