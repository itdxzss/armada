# 营销任务数据模型

本文冻结一期「营销任务」后台数据模型。本轮只落 schema 与文档,不实现 Controller/Service/Mapper。

## 设计原则

1. `marketing_template` 仍是营销素材唯一事实源,任务只保存模板 ID 与名称快照。
2. `account_state` 仍是账号在线、封禁、风控、禁言事实源,任务表不复制账号状态事实。
3. `group_link` / `group_link_preview` / `group_link_health` 仍是群入口、群协议元数据、群健康事实源,任务目标只保存执行快照与 `group_jid`。
4. 营销任务按「任务配置 → 账号×群目标 → 发送尝试历史」三层拆表,避免宽表。
5. 账号登录前群基线用一账号一行 JSON 数组,避免一账号多群时 baseline 表行数随群数膨胀。

## 表清单

| 表 | 聚合归属 | 作用 |
|---|---|---|
| `marketing_task` | marketing | 营销任务主表,保存任务配置、任务状态和任务级计数 |
| `marketing_task_target` | marketing | 执行目标表,一行表示一个账号+群组执行对 |
| `marketing_task_send_attempt` | marketing | 发送尝试历史,保存成功/失败/跳过与重试原因 |
| `account_group_baseline` | account/marketing 前置能力 | 账号登录前群基线快照,营销任务账号群树排除历史群 |
| `account.group_baseline_state` | account | 标记账号是否需要拍群基线 |

## account.group_baseline_state

账号表新增列:

| 字段 | 类型 | 说明 |
|---|---|---|
| `group_baseline_state` | `TINYINT NOT NULL DEFAULT 1` | 营销树群基线状态:1=待拍 2=已拍 3=不启用过滤 |

存量账号在迁移时置为 `3=不启用过滤`,因为无法还原它们登录平台前的历史群。新导入账号走默认 `1=待拍`,后续账号群同步任务首次拿到实时群列表时写入 `account_group_baseline`,再把状态置为 `2=已拍`。

## account_group_baseline

一行表示一个账号的一次登录前群基线快照。一个账号即使有多个历史群,也只占一行。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `account_id` | `BIGINT NOT NULL` | 账号 ID |
| `baseline_group_jids` | `JSON NOT NULL` | 首次拍基线时账号已在群 JID 数组;空数组表示已拍但无历史群 |
| `group_count` | `INT NOT NULL DEFAULT 0` | 数组长度,便于排查和统计 |
| `captured_at` | `BIGINT NOT NULL` | 拍基线时间(epoch 毫秒) |
| `created_at` | `BIGINT NOT NULL` | 创建时间(epoch 毫秒) |
| `updated_at` | `BIGINT NOT NULL` | 更新时间(epoch 毫秒) |

索引:

| 索引 | 字段 | 说明 |
|---|---|---|
| `uq_account_baseline` | `tenant_id, account_id` | 一个账号只有一份基线快照 |
| `idx_account_baseline_captured` | `tenant_id, captured_at` | 排查/统计拍基线批次 |

账号群树查询口径:

```text
账号当前群列表 - baseline_group_jids = 可展示的新群
```

不支持高效反查「哪些账号基线包含某个 group_jid」,这是有意取舍。一期只按账号查询基线,JSON 数组能减少表行数和索引体积。

## marketing_task

任务主表,一行就是一个营销任务。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `task_name` | `VARCHAR(128) NOT NULL` | 任务名称 |
| `account_group_id` | `BIGINT NOT NULL` | 创建时选择的账号分组 ID |
| `account_group_name` | `VARCHAR(100) NOT NULL` | 分组名称快照 |
| `marketing_template_id` | `BIGINT NOT NULL` | 营销模板 ID |
| `marketing_template_name` | `VARCHAR(128) NOT NULL` | 模板名称快照 |
| `status` | `TINYINT NOT NULL DEFAULT 1` | 1=待启动/未发送 2=发送中 3=发送成功 4=发送失败 5=已停止 6=部分失败 |
| `selected_account_count` | `INT NOT NULL DEFAULT 0` | 选中去重发送账号数 |
| `target_group_count` | `INT NOT NULL DEFAULT 0` | 选中去重目标群数 |
| `target_pair_count` | `INT NOT NULL DEFAULT 0` | 账号+群组执行目标行数 |
| `sent_message_count` | `INT NOT NULL DEFAULT 0` | 任务累计发送成功条数 |
| `failed_message_count` | `INT NOT NULL DEFAULT 0` | 任务累计发送失败条数 |
| `send_per_round` | `INT NOT NULL DEFAULT 1` | 单次发送数量 |
| `send_interval_seconds` | `INT NOT NULL DEFAULT 30` | 发送间隔秒数 |
| `is_online_check_enabled` | `TINYINT(1) NOT NULL DEFAULT 1` | 发送前是否检测账号在线 |
| `is_abnormal_group_skipped` | `TINYINT(1) NOT NULL DEFAULT 1` | 是否跳过异常群 |
| `is_auto_retry_enabled` | `TINYINT(1) NOT NULL DEFAULT 0` | 是否自动重试 |
| `retry_limit` | `INT NOT NULL DEFAULT 0` | 自动重试次数上限;一期勾选时为 1 |
| `remark` | `VARCHAR(512)` | 任务备注 |
| `started_at` | `BIGINT` | 首次启动时间(epoch 毫秒) |
| `last_sent_at` | `BIGINT` | 最后一次成功发送时间(epoch 毫秒),用于列表筛选 |
| `finished_at` | `BIGINT` | 进入成功/失败终态时间(epoch 毫秒) |
| `created_by` | `BIGINT` | 创建人 user_id |
| `created_at` | `BIGINT NOT NULL` | 创建时间(epoch 毫秒) |
| `updated_at` | `BIGINT NOT NULL` | 更新时间(epoch 毫秒) |
| `deleted_at` | `BIGINT` | 软删时间;NULL=未删 |

索引:

| 索引 | 字段 | 说明 |
|---|---|---|
| `idx_marketing_task_tenant` | `tenant_id, deleted_at, id` | 租户任务列表 |
| `idx_marketing_task_status_time` | `tenant_id, status, last_sent_at` | 状态 + 最后发送时间筛选 |
| `idx_marketing_task_template` | `tenant_id, marketing_template_id` | 模板删除时查找关联任务 |
| `idx_marketing_task_account_group` | `tenant_id, account_group_id` | 账号分组维度排查 |

## marketing_task_target

执行目标表,一行表示一个账号对一个群的发送目标。页面明细可按群展示,但底层必须保留账号维度。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `marketing_task_id` | `BIGINT NOT NULL` | 所属营销任务 ID |
| `account_id` | `BIGINT NOT NULL` | 发言账号 ID |
| `account_phone` | `VARCHAR(32) NOT NULL` | 发言账号号码快照 |
| `group_link_id` | `BIGINT NOT NULL` | 目标群入口 ID |
| `group_jid` | `VARCHAR(128) NOT NULL` | WhatsApp 群 JID,协议发送寻址用 |
| `group_link_url` | `VARCHAR(255) NOT NULL` | 群链接 URL 快照 |
| `group_name` | `VARCHAR(128)` | 群名称快照 |
| `status` | `TINYINT NOT NULL DEFAULT 1` | 1=待发送 2=发送中 3=成功 4=失败 5=部分失败 6=已跳过 7=已停止 |
| `sent_message_count` | `INT NOT NULL DEFAULT 0` | 该目标成功发送次数 |
| `failed_message_count` | `INT NOT NULL DEFAULT 0` | 该目标失败次数 |
| `retry_count` | `INT NOT NULL DEFAULT 0` | 该目标已自动重试次数 |
| `last_attempt_at` | `BIGINT` | 最近一次执行/跳过时间(epoch 毫秒) |
| `last_sent_at` | `BIGINT` | 最近一次成功发送时间(epoch 毫秒) |
| `last_reason` | `VARCHAR(255)` | 最近一次失败/跳过原因 |
| `created_at` | `BIGINT NOT NULL` | 创建时间(epoch 毫秒) |
| `updated_at` | `BIGINT NOT NULL` | 更新时间(epoch 毫秒) |

索引:

| 索引 | 字段 | 说明 |
|---|---|---|
| `uq_marketing_task_target_pair` | `tenant_id, marketing_task_id, account_id, group_link_id` | 同任务内账号+群组不可重复 |
| `idx_marketing_task_target_task` | `tenant_id, marketing_task_id, id` | 查任务明细 |
| `idx_marketing_task_target_status_time` | `tenant_id, status, last_sent_at` | 发送引擎切片 |
| `idx_marketing_task_target_account` | `tenant_id, account_id` | 账号维度排查 |
| `idx_marketing_task_target_group_jid` | `tenant_id, group_jid` | 群维度排查/协议寻址辅助 |

## marketing_task_send_attempt

发送尝试历史表,保留每次成功、失败、跳过和自动重试结果。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT` | 主键 |
| `tenant_id` | `BIGINT NOT NULL` | 租户 ID |
| `marketing_task_id` | `BIGINT NOT NULL` | 所属营销任务 ID |
| `target_id` | `BIGINT NOT NULL` | 所属执行目标 ID |
| `attempt_no` | `INT NOT NULL` | 同一目标下第几次尝试,从 1 开始 |
| `is_retry` | `TINYINT(1) NOT NULL DEFAULT 0` | 是否自动重试 |
| `status` | `TINYINT NOT NULL` | 1=成功 2=失败 3=跳过 |
| `reason_code` | `VARCHAR(64)` | 机器可识别原因码,如 ACCOUNT_OFFLINE/GROUP_BANNED |
| `reason_message` | `VARCHAR(255)` | 页面展示原因 |
| `attempted_at` | `BIGINT NOT NULL` | 执行时间(epoch 毫秒) |
| `created_at` | `BIGINT NOT NULL` | 记录创建时间(epoch 毫秒) |

索引:

| 索引 | 字段 | 说明 |
|---|---|---|
| `uq_marketing_task_attempt_no` | `tenant_id, target_id, attempt_no` | 同一目标尝试序号唯一 |
| `idx_marketing_task_attempt_task` | `tenant_id, marketing_task_id, id` | 查任务尝试历史 |
| `idx_marketing_task_attempt_target` | `tenant_id, target_id, attempt_no` | 查目标尝试历史 |
| `idx_marketing_task_attempt_status_time` | `tenant_id, status, attempted_at` | 排查失败/跳过记录 |

## 被否决方案

### `account_group_baseline` 一群一行

优点是可以按 `group_jid` 索引反查。缺点是一账号十个群、十万账号就是百万行,而一期查询只按账号读取基线,不需要反查某个群在哪些账号的基线里。因此改为一账号一行 JSON 数组。

### 只建 `marketing_task` + `marketing_task_detail`

`wheel` 最早就是两张表,后续又补 `speaker_account_id`、`group_jid`、`last_sent_at` 等发送引擎字段。armada 这次直接把账号×群目标和发送尝试历史分开,避免后续再拆。

### 在任务表复制模板正文和按钮

一期需求中“修改营销素材”会覆盖原模板,下一轮读取最新模板。任务里复制模板正文会产生多份素材事实,本轮不做。若后续需要审计“当时发送的具体内容”,应单独设计模板版本表。

## 迁移

Flyway 文件: `armada-api/src/main/resources/db/migration/V014__marketing_task_data_model.sql`。

本地测试库已有 `V013__protocol_command_outbox.sql` 迁移历史,当前工作树缺这个文件,所以本轮营销任务迁移排到 V014 防撞号。
