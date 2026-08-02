# 普通群链接拉群任务数据模型设计（待确认）

日期：2026-08-02
状态：待用户确认；确认前不创建 Flyway、不修改生产表
范围：`pull_task` 的 `STANDARD` 普通群链接任务，不含拉群营销、群组管理、新群、速拉和公告群

## 1. 设计结论

1. 复用现有 `pull_task` 作为任务主表，复用 `group_link` 作为群入口事实，不创建第二套任务主表或群链接主表。
2. `config_json` 只保留兼容展示快照，执行器不得从 JSON 恢复业务状态；可执行配置、计划、游标和结果全部结构化落表。
3. 创建页的随机匹配由服务端生成短期预览计划；最终创建时把计划冻结到任务，启动、暂停恢复和服务重启均复用同一计划。
4. TXT 原文件内容和非法原文不落库；只保存文件元数据、解析统计、规范化号码、原始行号和必要的失败原因。日志和接口默认脱敏号码。
5. 明确结果不重试；`UNKNOWN` 保持独立状态，由查询或回调收敛。联系人失败、成员失败和权限失败均保留逐项结果。
6. 人工暂停和资源等待使用独立字段表达；管理员、拉手或站台恢复不会绕过人工暂停。
7. 不定义数据库外键，沿用项目现状由 Service 保证引用完整性；所有业务表都有 `tenant_id` 并由 MyBatis 租户拦截器隔离。

## 2. 关系概览

```text
pull_task
  ├─ 1:1 pull_task_standard_setting
  ├─ 1:1 pull_task_standard_plan
  │       └─ 1:N pull_task_group_execution
  │               ├─ 1:N pull_task_material_member
  │               ├─ 1:N pull_task_group_account
  │               ├─ 1:N pull_task_contact_action
  │               ├─ 1:N pull_task_puller_invite
  │               ├─ 1:N pull_task_pull_call
  │               │       └─ 1:N pull_task_pull_call_participant
  │               └─ 1:N pull_task_resource_supplement
  ├─ 1:N pull_task_puller_lease
  └─ 1:N pull_task_operation_log

pull_task_group_execution ── N:1 group_link
pull_task_group_account   ── N:1 account
```

## 3. 现有表调整

### 3.1 `pull_task`

保留现有主键、租户、名称、类型、模式、状态、汇总、配置快照和审计字段，新增以下生命周期字段：

| 字段 | 类型 | 空值/默认 | 说明 |
|---|---|---|---|
| `copied_from_task_id` | BIGINT | NULL | 复制来源任务；复制后生成新预览计划，不复用旧计划 |
| `started_at` | BIGINT | NULL | 首次真实启动时间，epoch 毫秒 |
| `finished_at` | BIGINT | NULL | 全部执行行进入业务终态的时间 |
| `ended_at` | BIGINT | NULL | 人工结束时间；人工结束不可恢复 |
| `version` | INT | NOT NULL DEFAULT 1 | 生命周期更新乐观锁版本 |

索引：`idx_pull_task_copy (tenant_id, copied_from_task_id, id)`；现有列表和状态索引继续使用。

新普通任务写入：

- `task_type = 'STANDARD'`。
- `mode = 'GROUP_LINK'`；历史 `OLD_LINK` 记录只兼容读取，不再由新创建接口产生。
- 父任务状态只表达 `WAIT_START / EXECUTING / PAUSED / COMPLETED / ENDED`。单群资源等待不把仍有其他群运行的父任务改成暂停。
- `group_count`、`expected_pull_count` 是冻结计划汇总；真实结果从执行明细聚合，不从 `config_json` 推断。

### 3.2 复用表

- `group_link`：最终创建任务时按规范化链接复用或插入，新增记录 `origin = 3`；预览草稿不污染群链接池。
- `group_link_preview` / `group_link_health`：保存群 JID、公开邀请页预览和健康结果。
- `account` / `account_state`：账号分组、在线、风控和冷却事实源。
- `account_group_membership`：任务成功动作回写后用于群成员关系对账，不替代任务逐动作历史。
- `protocol_command_outbox`：所有真实协议命令继续走 Outbox；payload 只放任务、执行行和动作记录 ID，不复制敏感凭据。

## 4. 新增表

### 4.1 `pull_task_standard_setting`（普通拉群执行配置）

一条普通任务一行；只在最终创建时写入，字段全部为冻结配置。

| 字段 | 类型 | 空值/默认 | 说明 |
|---|---|---|---|
| `tenant_id` | BIGINT | NOT NULL | 租户 ID |
| `task_id` | BIGINT | NOT NULL | `pull_task.id` |
| `auto_start` | TINYINT(1) | NOT NULL DEFAULT 0 | 创建后是否自动启动 |
| `material_admin_timing` | TINYINT | NOT NULL | 1=成员入群后立即，2=本群料子全部终态后 |
| `pull_count_min` / `pull_count_max` | INT | NOT NULL | 单次料子人数闭区间，不含站台 |
| `pull_interval_seconds` | INT | NOT NULL | 同一拉手连续拉人调用间隔 |
| `puller_count_per_group` | INT | NOT NULL | 每群计划拉手数 |
| `station_count_per_call` | INT | NOT NULL | 每一次拉人调用叠加的站台数 |
| `concurrent_group_count` | INT | NOT NULL | 同一父任务最大运行执行行数 |
| `puller_risk_minutes` | INT | NOT NULL DEFAULT 0 | 拉手风控冷却分钟；0=不定时恢复 |
| `manager_group_id` / `puller_group_id` / `station_group_id` | BIGINT | NOT NULL | 三类账号分组 ID |
| `manager_group_name` / `puller_group_name` / `station_group_name` | VARCHAR(100) | NOT NULL | 三类分组名称快照 |
| `created_at` / `updated_at` | BIGINT | NOT NULL | epoch 毫秒 |

约束和索引：

- `PRIMARY KEY (tenant_id, task_id)`。
- Service 校验 `1 <= pull_count_min <= pull_count_max`，数量和间隔不得为负，执行群数和拉手数必须大于 0。
- 管理邀请拉手的 1 秒间隔是本期固定业务常量，不增加可配置列。

### 4.2 `pull_task_standard_plan`（服务端预览与冻结计划头）

预览时先写入；最终创建后绑定任务并冻结。过期预览只变更状态，不进入任务列表。

| 字段 | 类型 | 空值/默认 | 说明 |
|---|---|---|---|
| `id` | BIGINT | AUTO_INCREMENT | 主键 |
| `tenant_id` | BIGINT | NOT NULL | 租户 ID |
| `plan_token` | VARCHAR(64) ASCII/BINARY | NOT NULL | 返回前端的不可猜测计划令牌 |
| `task_id` | BIGINT | NULL | 冻结后关联 `pull_task.id`；预览时为空 |
| `plan_status` | TINYINT | NOT NULL DEFAULT 1 | 1=预览，2=已冻结，3=已过期，4=已取消 |
| `plan_version` | INT | NOT NULL DEFAULT 1 | 计划格式版本 |
| `valid_link_count` / `valid_file_count` | INT | NOT NULL DEFAULT 0 | 有效唯一链接和有效 TXT 数 |
| `matched_group_count` | INT | NOT NULL DEFAULT 0 | 冻结执行行数 |
| `unmatched_link_count` / `unmatched_file_count` | INT | NOT NULL DEFAULT 0 | 两侧剩余资源数 |
| `created_by` / `finalized_by` | BIGINT | NOT NULL / NULL | 预览和最终创建用户 |
| `expires_at` / `finalized_at` | BIGINT | NOT NULL / NULL | 过期和冻结时间 |
| `created_at` / `updated_at` | BIGINT | NOT NULL | 时间 |

约束和索引：

- `UNIQUE uq_pull_task_plan_token (tenant_id, plan_token)`。
- `UNIQUE uq_pull_task_plan_task (tenant_id, task_id)`；MySQL 允许预览行的 NULL。
- `INDEX idx_pull_task_plan_expiry (tenant_id, plan_status, expires_at, id)`。
- 最终创建事务锁定 `plan_token`，校验归属/状态/过期时间，写主表和配置、绑定群入口并冻结；重复提交不会生成第二个任务。

### 4.3 `pull_task_group_execution`（群链接 × TXT 执行行）

一行就是一个冻结的“群链接 ↔ TXT”。预览时已生成随机匹配，最终创建后只补关联，不重新随机。

| 字段组 | 主要字段 | 说明 |
|---|---|---|
| 标识 | `id`, `tenant_id`, `plan_id`, `task_id NULL`, `seq` | `task_id` 预览时为空；`seq` 是展示和执行顺序 |
| 群链接 | `group_link_id NULL`, `normalized_link VARCHAR(255)`, `invite_code VARCHAR(64)`, `source_link_line_no INT`, `group_jid VARCHAR(128) NULL` | 启动时重查有效性/JID |
| TXT | `source_file_index INT`, `source_file_name VARCHAR(255)`, `source_file_size BIGINT`, `content_sha256 BINARY(32)`, `total_line_count INT`, `valid_member_count INT`, `invalid_line_count INT`, `duplicate_line_count INT` | 不保存原始文件内容 |
| 状态 | `execution_status TINYINT`, `stage TINYINT`, `manual_paused TINYINT(1)`, `wait_resource_type TINYINT NULL`, `reason_code VARCHAR(64) NULL`, `reason_message VARCHAR(255) NULL` | 人工暂停与资源等待独立 |
| 资源快照 | `required_manager_count`, `current_manager_count`, `required_puller_count`, `current_puller_count`, `station_shortage_count` | 启动冻结目标；当前值是可重算投影 |
| 结果汇总 | `material_processed_count`, `material_success_count`, `material_failed_count`, `material_unknown_count` | 由逐成员结果事务更新，可全量重算 |
| 检查点 | `next_manager_cursor`, `next_puller_invite_cursor`, `next_puller_cursor`, `next_material_cursor`, `next_run_at` | 恢复时从原游标继续 |
| 调度锁 | `locked_by`, `lock_token`, `locked_at`, `lock_expires_at`, `version` | 服务重启可回收超时锁 |
| 时间 | `started_at`, `finished_at`, `last_business_executed_at`, `created_at`, `updated_at` | epoch 毫秒 |

枚举：

- `execution_status`：0=预览，1=待启动，2=校验中，3=执行中，4=等待资源，5=已完成，6=失败终态，7=人工结束。
- `stage`：1=链接校验，2=管理入群，3=管理—拉手联系人，4=管理邀请拉手，5=拉手批量拉人，6=料子管理员设置，7=收口。
- `wait_resource_type`：1=管理员，2=拉手，3=站台；为空表示非资源等待。

约束和索引：

- `UNIQUE (tenant_id, plan_id, seq)`、`UNIQUE (tenant_id, plan_id, normalized_link)`、`UNIQUE (tenant_id, plan_id, source_file_index)`。
- `INDEX idx_pull_task_execution_page (tenant_id, task_id, id)`。
- `INDEX idx_pull_task_execution_due (tenant_id, task_id, manual_paused, execution_status, next_run_at, id)`。
- `INDEX idx_pull_task_execution_group (tenant_id, group_link_id, id)`。

### 4.4 `pull_task_material_member`（TXT 有效料子）

一条规范化号码一行；单个 TXT 内去重后只保留首次顺序，重复行中的任意 `A/a` 会提升 `admin_required`。

| 字段 | 类型 | 空值/默认 | 说明 |
|---|---|---|---|
| `id`, `tenant_id`, `group_execution_id` | BIGINT | 主键/NOT NULL | 标识和归属 |
| `member_seq`, `source_line_no` | INT | NOT NULL | 去重后的稳定顺序与首次有效行号 |
| `normalized_phone` | VARCHAR(32) ASCII/BINARY | NOT NULL | 7–15 位、含国家码的号码 |
| `admin_required` | TINYINT(1) | NOT NULL DEFAULT 0 | 是否有 `A/a` 标识 |
| `admin_action_status` | TINYINT | NOT NULL DEFAULT 0 | 0=不需要，1=待执行，2=已提交，3=成功，4=失败，5=未知，6=取消 |
| `admin_executor_account_id` | BIGINT | NULL | 实际提权账号 |
| `admin_command_id`, `admin_reason_code`, `admin_reason_message` | VARCHAR(64/64/255) | NULL | 协议命令和脱敏原因 |
| `admin_result_at`, `created_at`, `updated_at` | BIGINT | NULL/NOT NULL | 时间 |

约束和索引：

- `UNIQUE (tenant_id, group_execution_id, member_seq)`。
- `UNIQUE (tenant_id, group_execution_id, normalized_phone)`。
- `INDEX idx_pull_task_material_admin (tenant_id, group_execution_id, admin_required, admin_action_status, id)`。
- 号码入群结果只保存在 `pull_task_pull_call_participant`，不在本表复制结果事实。

### 4.5 `pull_task_group_account`（单群角色账号）

保存管理、拉手、站台在某条执行行中的选择和当前资源快照；同一站台在同一群只能出现一次，但允许跨群复用。

| 字段组 | 主要字段 | 说明 |
|---|---|---|
| 标识 | `id`, `tenant_id`, `task_id`, `group_execution_id` | 归属 |
| 账号 | `account_id`, `account_phone VARCHAR(32)`, `protocol_account_id VARCHAR(128)` | 账号 ID 和快照 |
| 角色 | `role_type TINYINT`, `role_seq INT`, `source_type TINYINT`, `supplement_id NULL` | 1=管理，2=拉手，3=站台；来源 1=初始，2=补充 |
| 选择 | `selection_mode TINYINT`, `entry_mode TINYINT NULL`, `executor_group_account_id NULL` | 自动/手动；踩链接/管理员邀请/拉手批量加入 |
| 可用性 | `availability_status TINYINT`, `unavailable_reason_code`, `unavailable_reason_message`, `cooldown_until`, `last_validated_at` | 风控和资源恢复依据 |
| 当前群关系 | `membership_status TINYINT`, `admin_status TINYINT`, `membership_reason_code`, `membership_reason_message`, `joined_at`, `admin_verified_at` | 当前查询投影；详细事实仍在动作表 |
| 时间 | `created_at`, `updated_at` | epoch 毫秒 |

约束和索引：

- `UNIQUE (tenant_id, group_execution_id, role_type, account_id)`。
- `UNIQUE (tenant_id, group_execution_id, role_type, role_seq)`。
- `INDEX idx_pull_task_group_account_role (tenant_id, group_execution_id, role_type, availability_status, id)`。
- `INDEX idx_pull_task_group_account_account (tenant_id, account_id, role_type, availability_status, id)`。

### 4.6 `pull_task_contact_action`（双向联系人动作）

每个方向一行；好友失败不重试、不换号、不阻断后续邀请或拉人。

主要字段：`id`、`tenant_id`、`task_id`、`group_execution_id`、`relation_type`（1=管理—拉手，2=拉手—站台）、`from_group_account_id`、`to_group_account_id`、`from_account_id`、`to_account_id`、`action_status`（待执行/已提交/成功/失败/未知/取消）、`command_id`、`request_id`、`reason_code`、`reason_message`、`submitted_at`、`result_at`、`created_at`、`updated_at`。

约束和索引：

- `UNIQUE (tenant_id, group_execution_id, relation_type, from_account_id, to_account_id)`。
- `UNIQUE (tenant_id, request_id)`。
- `INDEX idx_pull_task_contact_pending (tenant_id, group_execution_id, action_status, id)`。
- 不增加 `retry_count`；明确结果不会产生第二行或第二次命令。

### 4.7 `pull_task_puller_invite`（管理邀请拉手）

每个拉手一行，字段：`id`、归属三 ID、`invite_seq`、`manager_group_account_id`、`puller_group_account_id`、`action_status`、`command_id`、`request_id`、原因、提交/结果/创建/更新时间。相邻提交由调度器固定间隔 1 秒。

约束和索引：

- `UNIQUE (tenant_id, group_execution_id, puller_group_account_id)`。
- `UNIQUE (tenant_id, group_execution_id, invite_seq)`。
- `UNIQUE (tenant_id, request_id)`。

### 4.8 `pull_task_pull_call`（单次拉人调用）

一行代表一个拉手对同一群 JID 的一次真实批量加成员请求。

主要字段：`id`、归属三 ID、`call_seq`、`puller_group_account_id`、`puller_account_id`、`planned_material_count`、`planned_station_count`、`actual_participant_count`、`call_status`（计划/已提交/已回写/未知/取消）、`command_id`、`request_id`、`idempotency_key`、原因和提交/结果/创建/更新时间。

约束和索引：

- `UNIQUE (tenant_id, group_execution_id, call_seq)`。
- `UNIQUE (tenant_id, idempotency_key)`、`UNIQUE (tenant_id, request_id)`。
- `INDEX idx_pull_task_call_puller_time (tenant_id, puller_account_id, submitted_at, id)`，用于校验同拉手调用间隔。

### 4.9 `pull_task_pull_call_participant`（单次调用逐参与者结果）

料子和站台同批提交但保持不同类型；明确失败不重试，未知只做结果收敛。

主要字段：`id`、归属和 `pull_call_id`、`participant_type`（1=料子，2=站台）、`participant_key`（`M:<materialMemberId>` 或 `S:<groupAccountId>`）、`material_member_id NULL`、`station_group_account_id NULL`、`normalized_phone`、`result_status`（待提交/已提交/成功/失败/未知/取消）、`reason_code`、`reason_message`、`wa_jid`、`result_at`、创建/更新时间。

约束和索引：

- `UNIQUE (tenant_id, pull_call_id, participant_key)`。
- `UNIQUE uq_pull_task_material_once (tenant_id, material_member_id)`；NULL 不影响站台行。
- `UNIQUE uq_pull_task_station_group_once (tenant_id, group_execution_id, station_group_account_id)`；保证同群站台只提交一次，NULL 不影响料子行。
- `INDEX idx_pull_task_participant_result (tenant_id, group_execution_id, participant_type, result_status, id)`。

### 4.10 `pull_task_resource_supplement`（管理员/拉手/站台补充单）

主要字段：`id`、归属三 ID、`resource_type`（管理员/拉手/站台）、`account_group_id/name`、`selection_mode`（自动/手动）、`entry_mode NULL`（踩链接/当前管理员邀请；站台为空）、`executor_group_account_id`、`requested_count`、`selected_count`、`supplement_status`、`request_id`、原因、`created_by` 和时间。被选账号写入 `pull_task_group_account.supplement_id`。

约束和索引：

- `UNIQUE uq_pull_task_supplement_request (tenant_id, request_id)`。
- `INDEX idx_pull_task_supplement_group (tenant_id, group_execution_id, resource_type, supplement_status, id)`。
- 站台补充 Service 强制 `entry_mode IS NULL`；管理员/拉手才允许两种入群方式。

### 4.11 `pull_task_puller_lease`（拉手跨任务单群租约）

字段：`id`、`tenant_id`、`account_id`、`task_id`、`group_execution_id`、`lease_token`、`acquired_at`、`heartbeat_at`、`expires_at`、`released_at NULL`、`release_reason`、`active_key` 生成列、创建/更新时间。等待、人工暂停和终态释放租约，但不删除历史。

约束和索引：

- `UNIQUE uq_pull_task_puller_active (tenant_id, account_id, active_key)`，`released_at IS NULL` 时 `active_key=1`。
- `UNIQUE (tenant_id, lease_token)`。
- `INDEX (tenant_id, group_execution_id, released_at, id)`、`INDEX (tenant_id, released_at, expires_at, id)`。

### 4.12 `pull_task_operation_log`（拉群任务操作审计）

字段：`id`、`tenant_id`、`task_id`、`group_execution_id NULL`、`supplement_id NULL`、`action_type`、`request_id`、`operator_user_id NULL`、`operator_name NULL`、`before_status`、`after_status`、`reason_code`、`reason_message`、`detail_json NULL`、`created_at`。系统自动恢复时操作者为空；JSON 只放不可检索且已脱敏的审计上下文。

约束和索引：

- `UNIQUE (tenant_id, action_type, request_id)`。
- `INDEX (tenant_id, task_id, created_at, id)`。
- `INDEX (tenant_id, group_execution_id, created_at, id)`。

## 5. 关键一致性规则

1. 预览计划 `PREVIEW -> FROZEN` 只允许一次；冻结事务同时写任务、setting、group_link 关联和 `task_id`。
2. 单文件单群：计划内链接、文件索引分别唯一；成员在执行行内按号码唯一。
3. 明确结果不重试：联系人、邀请、调用和参与者记录没有重试次数字段；重复回调只更新同一记录。
4. `UNKNOWN` 不释放为可重新提交的料子，只允许查询/回调更新为成功或失败。
5. 拉手跨任务互斥由有效租约唯一索引保证，不能只靠 JVM 内存锁。
6. 站台同群唯一由参与者唯一索引保证；跨不同执行行没有全局唯一限制。
7. 调度必须同时满足 `manual_paused=0`、资源已满足、父任务非暂停/结束。
8. 汇总列是查询投影，最终事实来自角色、动作、调用和参与者表，可全量重算。
9. 所有 Mapper 读写都经过租户拦截；H2/MySQL 测试覆盖同 ID 跨租户不可见、不可更新。

## 6. 迁移与回滚计划

- 预定迁移版本：`V090__pull_task_normal_link_execution.sql`，确认后再创建。
- 迁移新增 12 张表并给 `pull_task` 增加 5 个生命周期字段；不修改拉群营销表，不迁移历史营销任务。
- 历史 `STANDARD/OLD_LINK` 任务保留可读；没有冻结计划的历史任务不允许启动，页面提示复制为新任务。
- change 记录维护手工回滚 SQL：按依赖逆序删除新增表，再删除 `pull_task` 新增列和索引。共享库或生产回滚前仍需单独确认环境。
- Flyway 完成后用仓库生成器更新 `.harness/wiki/数据模型.md`，不手工编辑生成文档。

## 7. 分阶段实现和验证门禁

1. M1：链接/TXT 解析、服务端预览、冻结计划、创建/详情。先写解析、Service、Mapper、Flyway H2 测试，再做前端；最后跑浏览器到 Java API 再到数据库的 E2E。
2. M2：启动、管理入群、联系人、邀请拉手、拉人调用、逐参与者回写、A 料子提权。完成后跑前端到后端并连协议测试环境的真实 E2E。
3. M3：管理员/拉手/站台等待与补充、自动恢复、跨任务拉手租约。完成后跑资源耗尽和恢复的前后端 E2E。
4. M4：任务/单群暂停、恢复、结束、复制、重启恢复和未知结果收敛。完成后跑生命周期和故障恢复 E2E。
5. 每阶段保存实际命令、退出码和 Playwright trace/截图；没有真实输出不声明通过。

## 8. 本次需要确认的表结构决策

请确认以下四点后再进入 Flyway 和编码：

1. 复用 `pull_task` 主表，新增 5 个生命周期字段；新任务 `mode='GROUP_LINK'`，历史 `OLD_LINK` 只读兼容。
2. 新增上述 12 张结构化表，不用单个 JSON 承载执行状态和逐成员结果。
3. 预览计划先短期落库，最终创建后冻结；弃用预览过期，不污染 `group_link`。
4. 不保存 TXT 原文件和非法原文，只保存规范化号码、行号、哈希与解析统计。
