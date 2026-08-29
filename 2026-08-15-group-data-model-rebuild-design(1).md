# 群组数据模型重建设计（方案 A：六张权威表）

> 日期：2026-08-15
> 状态：待业务与技术评审；尚未进入实施
> 本地审计分支：Armada、Web 前端、Web/Baileys 协议层、Android Zhuan 均为 1.0.3-snapshot；项目自身 pom/package/go module 版本不作为本设计的分支标识
> 目标环境：第一套测试环境 test1；本次仅做只读核对，不改库、不部署

## 1. 结论先行

方案 A 的“六张表”是六张群组当前事实权威表，不是把项目内所有含 group 的表强行压成六张。

最终权威表固定为：

1. wa_group：真实 WhatsApp 群身份和 Armada 本地运营属性。
2. wa_group_profile：群当前资料、设置、群状态、当前邀请指针和最后一次完整成员快照头。
3. wa_group_invite：邀请 code、链接有效性、邀请历史及未解析链接预览。
4. wa_group_participant：群成员当前 presence、role、PN/LID 身份和最近进退群事实。
5. wa_account_group_binding：Armada 账号生命周期与群成员的绑定，以及该账号自己的 baseline / 上控后语义。
6. account_group_sync_state：账号全量群快照水位、完整性、账号绑定代次和空 baseline 语义。

六表替代的是现有“群组当前态和最近事实”表簇，包括 group_link、group_link_preview、group_link_health、account_group_membership、account_group_baseline（含 last_group_sync_requested_at）、whatsapp_group_member_snapshot、whatsapp_group_member_cache、whatsapp_group_member_state、whatsapp_group_member_join_fact、whatsapp_group_departed_member，以及 account.group_baseline_state。

因此不能简单理解成“六张机械替换原先八张”。最初提到的八张只是群列表主查询附近的口径；全仓闭包后发现，当前事实实际散落在十张表和 account 的字段里。六张新表是按六类事实重新归一，旧表不是一表对一表迁移。join_fact / departed_member 目前也只是“每成员最近一条”的可变投影，不是真正 append-only 审计，最近进退群事实迁入 participant 后删除；若未来要完整事件流水，应另行设计审计留存，不能继续让这两张当前投影充当第二主值。

下列类型的表不会被六表替代：

- 配置表：group_folder、group_link_label。
- 导入过程与审计：group_link_import_batch、group_link_import_detail。
- 耐久任务：group_metadata_sync_task、group_batch_task、group_batch_task_item。
- 业务过程和结果：进群、拉群、历史群拉取、群组营销、建群营销、普通群创建等任务表。
- 协议可靠投递：protocol_command_outbox、Kafka topic / consumer offset、DLT，以及 Android 本地失败日志；当前后端没有数据库事件 inbox，不能假设它存在。

这些保留表只保存配置、请求快照、执行进度和历史结果，不再拥有或反向覆盖群名称、当前邀请链接、当前成员关系、当前角色、当前群状态等事实。

首期只允许两类最小、非权威过程设施。这里必须说清楚：方案 A 是六张权威事实表，不是宣称整个实施只新增六张物理表；过程表绝不能成为第二主值。

| 过程设施 | 必要性 / 生命周期 | 允许保存 | 严禁保存 |
|---|---|---|---|
| group_model_migration_run（名称实施时固定） | 可续跑回填必需；迁移与观察结束后归档 | run id/status/lease、source watermark、分批 count/hash、conflict count/签字引用 | topic/effect 状态机；群名、当前链接、成员/角色、当前账号群关系镜像 |
| group_snapshot_effect_outbox | 账号快照同时满足“副作用不丢”和 SQL `<=10` 时必需；若实现前证明现有单表可等价承载才复用 | event_id、effect_type=METADATA/IMMEDIATE_MARKETING、account/group 目标、PENDING/PROCESSING/DONE/DEAD、retry/error/audit time | EMITTED/SUPPRESSED 跨版本状态机；任一六表当前字段的可回写副本 |

临时 legacy mapping 优先放在待删除 group_link 的 additive canonical 列；只有合法历史无法 typed 化时，才保留最小只读 legacy_group_link_compat。上述过程/兼容设施都必须有唯一 writer、禁止反向覆盖六表，并在数据字典中标 `NON_AUTHORITATIVE`。账号协议绑定历史、Web/Android 非幂等命令 journal、全量 v1/v2 effect admission、effect epoch 和 CUTOVER spool 不纳入六表首期；如果生产明确要求零停机切换，再以独立 ADR 和独立验收预算评审，不能反向膨胀本数据模型。

## 2. 为什么必须重建，而不是继续加表

当前模型把四类不同实体塞进 group_link 及其附表：

- 未解析的邀请链接；
- 已解析的真实 WhatsApp 群；
- 某个 Armada 账号与群的关系；
- 协议观察、健康检测和任务状态。

由此已经出现下列可复现问题：

1. 真实群用 wa://group/{jid} 假链接满足 group_link.link_url 非空和唯一约束。
2. membership_state 是租户×群级字段，但真实关系是账号×群×成员，多个账号会互相覆盖。
3. is_historical / is_post_control 固化在群上且只升不降，丢失“对哪个账号而言”的语义。
4. group_name 实际是 Armada 本地展示名、wa_subject 才是 WhatsApp 群名，但旧表和 VO 没把两种语义清楚分层；avatar_url 更被本地资料与协议回读共用。
5. group_link_health 同时混合邀请链接失效、群 suspended / terminated、执行账号异常和临时检测错误。
6. account 群轻量快照、邀请预览、完整 metadata、显式事件使用不同的新旧判断规则，晚到旧事件可以覆盖新值。
7. role 事件当前可能顺带断言 is_in_group=true；但“角色发生变化”并不能证明当前仍在群。
8. 成员 presence 和 role 共用一个 state_updated_at，一个维度的新事件会挡住另一个维度的合法更新。
9. 完整成员快照采用“删旧快照再插入”，同时另有 cache、state、join fact、departure fact，当前态不唯一。
10. Web 的 v1 account.groups_reported 当前没有显式发送 legacy `snapshotComplete=true`；后端却把 null + `skippedGroupCount=0` 推断为完整快照，可能错误把遗漏群标记为离群。
11. 列表 SQL 无条件聚合全量成员和账号关系。test1 的成员数据约 44～45 万行时，无筛选列表约 1.2 秒，而只查群、资料、当前链接、分组的瘦查询约 32 毫秒。
12. 操作员软删除的群会被后续账号事件复活；first_seen 来源也可能被“自建群”后写覆盖。

继续给旧表加字段只能增加同一事实的镜像数量，不能修复权威归属和事件顺序。

## 3. 设计原则与不可破坏的不变量

### 3.1 一个事实只有一个权威位置

| 事实 | 唯一权威位置 |
|---|---|
| 群 JID、本地展示名/头像、分组、备注、软删除、首次来源 | wa_group |
| WhatsApp 群名、描述、头像、人数、设置、群状态、当前邀请指针 | wa_group_profile |
| 邀请 code、链接有效性、失效/替换历史、未解析预览 | wa_group_invite |
| 成员是否在群、管理员/群主角色、PN/LID、最近进退群 | wa_group_participant |
| Armada 账号对应哪个 participant、baseline / 上控后上下文 | wa_account_group_binding |
| 账号全量群快照是否完整、空快照、空 baseline、代次和水位 | account_group_sync_state |
| 账号在线、封号、风控、协议绑定 | account / account_state |
| 任务状态、失败原因、重试、执行快照 | 各自任务表 |

列表 VO、任务快照、导出文件可以包含派生值，但不能作为回写当前事实的来源。

### 3.2 六条硬不变量

1. 每租户每个规范化 group_jid 只有一个 wa_group，软删除后也不允许创建第二个。
2. 每群当前邀请只由 profile.current_invite_id 一个指针表达；“是不是当前链接”和“链接是否有效”是两个独立事实，链接 URL 永远由 invite_code 派生，不存第二份 URL。
3. 每租户每群每个 WhatsApp 人只有一个 participant；PN JID 和 LID JID 后续确认属于同一人时必须事务合并。
4. presence 和 role 分开排序、分开更新；角色事件不能改变 presence。
5. v2 只有显式 complete=true 且 skippedCount=0 的快照，才有资格把未出现项标为缺失；v1 adapter 只做字段映射，缺失完整性字段一律视为 partial。
6. 协议事件永远不能自动清除 wa_group.deleted_at；恢复只能由明确的运营操作完成。

### 3.3 统一基础约定

- 所有业务时间统一为 BIGINT epoch 毫秒；旧 group_created_at 秒值迁移时乘以 1000。
- group_jid、pn_jid、lid_jid 使用 ASCII binary collation，入库前 trim、转小写并校验 JID 后缀；phone 只保留可信规范化数字。
- PN/LID 必须先做等价于 Baileys `jidNormalizedUser` / Android JID canonicalizer 的 user-level 归一：`123:1@s.whatsapp.net` 与 `123@s.whatsapp.net` 都存为后者，LID 的 device/agent 段也移除但绝不转成手机号。回填先规范化、报告碰撞，再建唯一键。
- invite_code 和 event_id / source_event_id 使用 ASCII binary collation但保留原始大小写；WhatsApp 邀请 code 可能大小写敏感，绝不能统一转小写。URL 解析只去空白和固定 path 前缀。
- group_jid 只接受规范化的 @g.us；禁止再生成 wa://group/{jid}。
- tenant_id 在六表全部显式存在，继续由 MyBatis-Plus 租户拦截器隔离。
- 不增加物理外键，沿用 Armada 当前逻辑外键和 Service 事务校验模式，避免在线迁移锁表。
- 状态用 TINYINT + Java enum，SQL COMMENT 必须逐值说明。
- null 表示“未观察到”，不表示“清空”。协议清空字段必须携带明确 field mask。
- 低基数 P/I 的 `field_version_keys` 是固定 key→base64 compact version map，空 map 写 `{}`；高基数 M/B/S 和标量头使用 `*_version_key VARBINARY(128)`，未观察写单字节 `0x00` 而不是 NULL。S.baseline_fact_version_key 另保留“单字节 `0x01`”作为唯一 LEGACY_UNKNOWN_BASELINE_KEY；正常 FactVersion 仍是 118 字节且首字节 `0x01`，因此该短前缀按 unsigned lexicographic 严格低于任一正常事实 key。业务列、observed_at 和 version key 在锁定同一行后一次更新；禁止用数据库 updated_at 比较新旧。
- `first_seen_at` 只能按 `MIN(existing, acceptedFactTime)` 单调向前，`last_seen_at` 只能按 `MAX(existing, acceptedFactTime)` 单调向后；晚到事实不得让两个边界反向移动，`first_seen_source` 只随更早且更可靠的首次事实修正。

### 3.4 MySQL 物理约束基线

方案字段表中的 `VARCHAR(n) ASCII BIN` 是简写，实际 DDL 必须展开为 `VARCHAR(n) CHARACTER SET ascii COLLATE ascii_bin`。六表统一 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`；JID、invite code、event/snapshot/version identity 等机器标识逐列使用 ascii_bin，不能继承大小写不敏感排序。

所有枚举、三态布尔、非负计数都加 CHECK；M 增加 PN/LID 至少一个非空、`role_membership_epoch <= membership_epoch` 及“可读角色只属于当前 epoch”的约束，P 增加 current-invite state/pointer 组合约束，JSON NOT NULL 列的所有 insert/backfill 显式写 `{}`。每个普通 `*_version_key` 统一约束为 `key=X'00' OR (OCTET_LENGTH(key)=118 AND LEFT(key,1)=X'01')`；S.baseline_fact_version_key 额外允许单字节 X'01' legacy sentinel，并由状态组合限制其唯一用途，不能只校验“长度 118”让垃圾 key 通过。CHECK、NULL 唯一键、JSON 和 generated-column 行为以 MySQL 8.4.8 为准，H2 不作为这些约束的验收依据。

最少约束清单：G.first_seen_source 在声明枚举内且 seen time 非负；P.group_status/current_invite_state/四个设置布尔合法、member_count 和时长非负、PRESENT↔pointer 非空；I.validity/settings 合法、preview_count/failure_count 非负；M.presence/role/exit_type 合法、PN/LID 至少一项、所有 epoch 非负；B.generation 非负、baseline 布尔只允许 0/1/NULL；S.baseline_state/completeness/reportScope/detailLevel/backend 合法，complete 与 baseline_filter_enabled 为布尔，全部 count/generation 非负，并完整实现第 5.6 节四种 baseline 与 binding 组合约束。跨行 tenant/group/pointer/participant 一致性因不建物理 FK，由 Reducer 事务校验和第 15 节巡检双重保证。

## 4. 目标关系

~~~mermaid
erDiagram
    ACCOUNT ||--|| ACCOUNT_GROUP_SYNC_STATE : "一个账号一个同步水位"
    ACCOUNT ||--o{ WA_ACCOUNT_GROUP_BINDING : "账号生命周期绑定"
    GROUP_FOLDER ||--o{ WA_GROUP : "运营分组"
    WA_GROUP ||--|| WA_GROUP_PROFILE : "当前资料、邀请指针与快照头"
    WA_GROUP ||--o{ WA_GROUP_INVITE : "邀请历史"
    WA_GROUP ||--o{ WA_GROUP_PARTICIPANT : "当前成员事实"
    WA_GROUP ||--o{ WA_ACCOUNT_GROUP_BINDING : "账号观察关系"
    WA_GROUP_PARTICIPANT ||--o{ WA_ACCOUNT_GROUP_BINDING : "账号对应的成员"
~~~

未解析邀请允许 wa_group_invite.group_id 为 null；一旦可靠解析出 group_jid，先按 JID upsert wa_group，再把邀请绑定到该 group_id。

## 5. 六张表详细设计

### 5.1 wa_group

聚合职责：真实 WhatsApp 群身份，以及仅由 Armada 运营操作维护的属性。

| 字段 | 类型 | 空值 / 默认 | 说明 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 群稳定 ID；迁移时已解析旧记录尽量保留 group_link.id |
| tenant_id | BIGINT | NOT NULL | 租户 ID |
| group_jid | VARCHAR(128) ASCII BIN | NOT NULL | 规范化 WhatsApp 群 JID |
| folder_id | BIGINT | NULL | 运营群组分组，逻辑关联 group_folder.id |
| display_name | VARCHAR(128) | NULL | Armada 本地展示名覆盖；不修改 WhatsApp subject |
| display_avatar_url | VARCHAR(1024) | NULL | Armada 本地展示头像覆盖；不修改 WhatsApp avatar |
| remark | VARCHAR(255) | NULL | Armada 本地运营备注，不是 WhatsApp 群描述 |
| first_seen_source | TINYINT | NOT NULL | 1邀请解析 2进群任务 3拉群任务 4自建群 5账号快照 6群事件 7迁移回填；仅随更早可靠事实修正 |
| first_seen_at | BIGINT | NOT NULL | 首次可靠识别该 JID 的事实时间 |
| last_seen_at | BIGINT | NOT NULL | 任一被接受协议事实最近看见该群的时间 |
| managed_creator_account_id | BIGINT | NULL | Armada 自建群时的创建账号；非自建为空 |
| managed_created_at | BIGINT | NULL | Armada 建群动作确认成功时间，不等同 WhatsApp 原始建群时间 |
| created_by | BIGINT | NULL | 本地人工创建/导入操作人；协议自动登记为空 |
| created_at | BIGINT | NOT NULL | 数据行创建时间 |
| updated_at | BIGINT | NOT NULL | 数据行更新时间 |
| deleted_at | BIGINT | NULL | 运营软删除；协议事件不得清除 |

约束与索引：

| 名称 | 列 | 类型 / 用途 |
|---|---|---|
| PRIMARY | id | 主键 |
| uq_wa_group_jid | tenant_id, group_jid | 无条件唯一；软删也占位 |
| idx_wa_group_list | tenant_id, deleted_at, created_at, id | 默认列表和稳定分页 |
| idx_wa_group_folder | tenant_id, folder_id, deleted_at, created_at, id | 分组筛选 |
| idx_wa_group_source | tenant_id, first_seen_source, deleted_at, created_at, id | 来源筛选 |
| idx_wa_group_creator | tenant_id, managed_creator_account_id, managed_created_at | 自建群追踪 |

禁止进入本表的旧字段：link_url、invite_code、label_id、import_batch_id、membership_state、is_historical、is_post_control、sync_protocol_mask、health_status、member_count。旧 group_name 明确迁为本表 display_name，不再与 WhatsApp subject 混写。

### 5.2 wa_group_profile

聚合职责：WhatsApp 群当前资料、设置、明确群状态、当前邀请指针，以及最后一次完整成员快照的提交头。每次创建 wa_group 都在同一事务创建空 profile，确保 current invite 的 group-level watermark 永远有落点。

| 字段 | 类型 | 空值 / 默认 | 说明 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| tenant_id | BIGINT | NOT NULL | 租户 ID |
| group_id | BIGINT | NOT NULL | 逻辑关联 wa_group.id |
| subject | VARCHAR(255) | NULL | WhatsApp 当前真实群名；不再保留 group_name 镜像 |
| description | VARCHAR(1024) | NULL | WhatsApp 群描述 |
| avatar_url | VARCHAR(1024) | NULL | 最近确认头像 URL |
| member_count | INT | NULL | 最新被接受来源观察到的群成员数 |
| wa_created_at | BIGINT | NULL | WhatsApp 建群时间，统一 epoch 毫秒 |
| group_status | TINYINT | NOT NULL, 0 | 0未知 1正常 2SUSPENDED 3TERMINATED |
| announce_only | TINYINT | NULL | NULL未知 0所有成员可发言 1仅管理员 |
| admin_only_edit_info | TINYINT | NULL | NULL未知 0所有成员可编辑 1仅管理员 |
| member_add_mode | TINYINT | NULL | NULL未知 0仅管理员添加 1所有成员添加 |
| join_approval_mode | TINYINT | NULL | NULL未知 0关闭审批 1开启审批 |
| ephemeral_duration_seconds | INT | NULL | NULL未知 0关闭，其余为实际秒数 |
| field_version_keys | JSON | NOT NULL | 每个可独立更新字段的 compact fact-version key，精确定义见下文 |
| last_metadata_observed_at | BIGINT | NULL | 最近一次成功接受任意 metadata 字段的观察时间 |
| current_invite_state | TINYINT | NOT NULL, 0 | 0未知 1存在当前 code 2协议明确不存在(REVOKED) |
| current_invite_id | BIGINT | NULL | 逻辑关联 wa_group_invite.id；该群唯一的当前邀请指针，撤销时写 NULL |
| current_invite_observed_at | BIGINT | NULL | 最近一次当前邀请关系事实时间；即使指针为 NULL 也保留 |
| current_invite_version_key | VARBINARY(128) | NOT NULL | 当前邀请指针的 compact fact-version key；防止 revoke / 轮换后旧 code 复活 |
| current_invite_reason_code | VARCHAR(64) | NULL | REVOKED / SOURCE_RESET 等稳定原因；导入池隐藏不改变此事实 |
| member_snapshot_at | BIGINT | NULL | 最近一次被接受的完整成员快照时间 |
| member_snapshot_id | VARCHAR(128) ASCII BIN | NULL | 完整成员快照批次 ID；仅用于身份 / 幂等，不参与新旧排序 |
| member_snapshot_fact_version_key | VARBINARY(128) | NOT NULL | 完整成员快照头的 compact fact-version key |
| member_snapshot_observer_account_id | BIGINT | NULL | 完成该快照的 Armada 账号 |
| member_snapshot_participant_count | INT | NULL | 该完整快照实际成员数；完整空群为 0 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

field_version_keys 不是自由 JSON。键固定为 subject、description、avatarUrl、memberCount、waCreatedAt、groupStatus、announceOnly、adminOnlyEditInfo、memberAddMode、joinApprovalMode、ephemeralDurationSeconds；每个值是下述 FactVersion 经 canonical binary encoding 后的 base64 字符串：

~~~json
{
  "observedAt": 1786700000000,
  "sourceType": 4,
  "authorityTier": 20,
  "sourcePriority": 40,
  "protocolBackend": "ANDROID",
  "observerAccountId": 12345,
  "bindingInstanceId": "01K2BINDINGINSTANCE",
  "bindingGeneration": 7,
  "sequenceDomain": "ANDROID:01K2BINDINGINSTANCE:WGP2",
  "hasSourceSequence": true,
  "sourceSequence": 123,
  "sourceEventId": "upstream-fact-id",
  "eventId": "stable-envelope-id"
}
~~~

Java Reducer 把 FactVersion 编成可按 unsigned byte lexicographic 比较的 version key：`0x01 | observedAt(u64 BE) | authorityTier(u16) | sourcePriority(u16) | SHA-256(sequenceDomain) | hasSequence(u8) | sourceSequence(u64 BE) | SHA-256(sourceEventId) | SHA-256(eventId)`，共 118 字节；`0x00` 专用于 UNOBSERVED。跨 domain 的 hash 顺序没有业务含义，只提供稳定 total order。生产者只发送原始字段，不能自行编码；Java encoder 用 Web/Android/Migration golden fixtures 固定。

使用逐字段 version key 是为了避免一组共用 watermark 导致“只更新头像的新事件”错误挡住“稍早但仍应补齐群描述的事件”。这些 key 不参与列表查询；Reducer 在锁定单行后同时更新业务列和对应 key。M 不保存 8 份 verbose JSON，避免约 45 万成员产生不可接受的行宽和写放大；实施门禁必须在 MySQL 8.4 实测 backfill 后的数据页、二级索引、redo 和单行大小。

约束与索引：

| 名称 | 列 | 类型 / 用途 |
|---|---|---|
| PRIMARY | id | 主键 |
| uq_wa_group_profile_group | tenant_id, group_id | 一群一行 |
| idx_wa_group_profile_status | tenant_id, group_status, group_id | 群状态筛选 |
| idx_wa_group_profile_count | tenant_id, member_count, group_id | 成员数范围 |
| idx_wa_group_profile_created | tenant_id, wa_created_at, group_id | 群龄筛选 |
| uq_wa_group_profile_invite | tenant_id, current_invite_id | 同一非空 invite 最多被一个 profile 指为当前；MySQL 允许多 NULL |

群主不是 profile.owner_phone。群主由 wa_group_participant 在 `presence=IN_GROUP AND role_membership_epoch=membership_epoch AND role=OWNER` 下派生；国家和大洲由其已确认 PN 手机号的可重建 country 投影派生。

Reducer 更新 current invite 前必须锁 profile，先比较 current_invite_version_key，再校验目标 invite 与 profile 的 tenant_id / group_id 相同且未软删。增加 CHECK：state=PRESENT 时 pointer 必须非空，state=UNKNOWN/EXPLICIT_NONE 时 pointer 必须为空。指针为空时版本仍不能倒退；这条 group-level watermark 是晚到旧 code 无法复活的最终边界。

### 5.3 wa_group_invite

聚合职责：邀请 code 的生命周期、链接有效性、检查结果，以及尚未解析成真实群时的公开预览。当前邀请关系只在 profile.current_invite_id，不在每个 code 行复制 is_current。

| 字段 | 类型 | 空值 / 默认 | 说明 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 邀请稳定 ID；迁移首条旧链接尽量保留 group_link.id |
| tenant_id | BIGINT | NOT NULL | 租户 ID |
| group_id | BIGINT | NULL | 已解析时逻辑关联 wa_group.id；未解析可为空 |
| invite_code | VARCHAR(128) ASCII BIN | NOT NULL | 规范化 code；URL 由它派生 |
| label_id | BIGINT | NULL | 导入链接分组，逻辑关联 group_link_label.id |
| pool_hidden_at | BIGINT | NULL | 仅从“导入邀请池”隐藏；不删除协议邀请事实，也不影响群当前指针 |
| display_name | VARCHAR(128) | NULL | 未解析邀请在 Armada 的本地展示名；不等于 preview_subject |
| display_avatar_url | VARCHAR(1024) | NULL | 未解析邀请的本地展示头像覆盖 |
| remark | VARCHAR(255) | NULL | 未解析链接的运营备注；解析后群备注只认 wa_group.remark |
| first_seen_source | TINYINT | NOT NULL | 1人工导入 2进群任务 3拉群任务 4建群回读 5账号/群事件 6迁移回填；仅随更早可靠事实修正 |
| validity_status | TINYINT | NOT NULL, 0 | 0未知 1有效 2明确失效；与 profile 当前指针独立 |
| validity_observed_at | BIGINT | NULL | 有效性事实时间 |
| validity_version_key | VARBINARY(128) | NOT NULL | 有效性的 compact fact-version key |
| invalid_reason_code | VARCHAR(64) | NULL | 明确失效的稳定原因码 |
| invalidated_at | BIGINT | NULL | 最近一次被明确判失效 / 撤销的事实时间 |
| superseded_at | BIGINT | NULL | 被新 code 替换的事实时间；替换不等于校验失效 |
| preview_subject | VARCHAR(255) | NULL | 仅未解析邀请使用的公开预览群名 |
| preview_description | VARCHAR(1024) | NULL | 未解析邀请可观察到时保存的群描述 |
| preview_avatar_url | VARCHAR(1024) | NULL | 仅未解析邀请使用的预览头像 |
| preview_member_count | INT | NULL | 仅未解析邀请使用的预览人数 |
| preview_owner_phone | VARCHAR(32) | NULL | 仅协议明确返回 PN 时填写 |
| preview_owner_country_iso2 | CHAR(2) ASCII BIN | NULL | preview_owner_phone 的可重建国家筛选投影 |
| country_resolution_version | VARBINARY(32) | NOT NULL | confirmed-phone country resolver 语义/依赖的 SHA-256；全零表示待重算 |
| preview_wa_created_at | BIGINT | NULL | 未解析邀请可观察到的建群时间(epoch毫秒) |
| preview_announce_only | TINYINT | NULL | 未解析邀请预览的发言模式 |
| preview_admin_only_edit_info | TINYINT | NULL | 未解析邀请的资料编辑设置 |
| preview_member_add_mode | TINYINT | NULL | 未解析邀请的成员添加设置 |
| preview_join_approval_mode | TINYINT | NULL | 未解析邀请的入群审批设置 |
| preview_ephemeral_duration_seconds | INT | NULL | 未解析邀请的限时消息秒数 |
| preview_observed_at | BIGINT | NULL | 最近预览成功时间 |
| preview_observer_account_id | BIGINT | NULL | 执行预览的 Armada 账号 |
| field_version_keys | JSON | NOT NULL | 上述全部 preview 字段的固定 compact version key map；格式同 profile |
| last_checked_at | BIGINT | NULL | 最近一次链接校验完成时间 |
| last_check_result | TINYINT | NOT NULL, 0 | 0未知 1成功取得目标邀请结论 2目标邀请临时不可用 3执行链失败；只有 2 可影响 I-only 状态 |
| last_check_error_domain | TINYINT | NOT NULL, 0 | 0无 1邀请查询瞬时错误 2执行账号 3无执行账号 4投递 5载荷 6未知；与 result 形成封闭组合 |
| last_check_version_key | VARBINARY(128) | NOT NULL | 最近校验 attempt 的 compact version；成功/失败/计数都按它 CAS |
| last_success_checked_at | BIGINT | NULL | 最近一次成功取得邀请有效/失效结论的时间 |
| last_success_check_version_key | VARBINARY(128) | NOT NULL | 最近成功目标结论的 compact version；未观察为 `0x00` |
| last_error_code | VARCHAR(64) | NULL | 最近一次稳定错误码，不放长错误文本 |
| failure_count | INT | NOT NULL, 0 | 连续校验失败次数；成功归零 |
| first_seen_at | BIGINT | NOT NULL | 首次观察时间 |
| last_seen_at | BIGINT | NOT NULL | 最近观察时间 |
| created_by | BIGINT | NULL | 人工导入操作人 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |
| deleted_at | BIGINT | NULL | 系统退役；只允许非当前、无活跃引用且过保留期的邀请进入，普通 UI 删除不用它 |

I.field_version_keys 同样不是自由 JSON，固定且仅允许这些 key：previewSubject、previewDescription、previewAvatarUrl、previewMemberCount、previewOwnerPhone、previewWaCreatedAt、previewAnnounceOnly、previewAdminOnlyEditInfo、previewMemberAddMode、previewJoinApprovalMode、previewEphemeralDurationSeconds。preview_owner_country_iso2 / country_resolution_version 是可重建投影，preview_observed_at / preview_observer_account_id 是批次头，display_name / display_avatar_url / remark 是本地运营字段，validity / check 是独立事实域，均不得放进这个 map。四个 preview 群设置的 NULL / 0 / 1 含义与 P 对应字段完全一致；老事件没有 fieldMask 时只能补非空值，不能把缺字段解释为 false 或清空。

约束与索引：

| 名称 | 列 | 类型 / 用途 |
|---|---|---|
| PRIMARY | id | 主键 |
| uq_wa_group_invite_code | tenant_id, invite_code | 无条件唯一；重复导入复用原行 |
| idx_wa_group_invite_group | tenant_id, group_id, last_seen_at, id | 群的邀请历史 |
| idx_wa_group_invite_label | tenant_id, label_id, pool_hidden_at, deleted_at, created_at, id | 导入链接分组 |
| idx_wa_group_invite_check | tenant_id, validity_status, last_check_result, last_checked_at, id | 链接检测候选 |
| idx_wa_group_invite_country | tenant_id, preview_owner_country_iso2, pool_hidden_at, id | I-only 国家筛选 |

MySQL CHECK 必须把 check state 写成封闭 OR：UNKNOWN 要求 domain=NONE、check/success key 都为 `0x00` 且 last_checked_at/last_success_checked_at 都为空；SUCCESS 要求 domain=NONE、两个 key 均为合法 118 字节且 success key=check key、两个时间非空；TARGET_TRANSIENT 只允许 INVITE_QUERY_TRANSIENT；EXECUTION_FAILURE 只允许 EXECUTOR_ACCOUNT/NO_EXECUTOR/DELIVERY/PAYLOAD/UNKNOWN。后两类要求 last_checked_at 非空、check key 为合法 118 字节，success key 可为 `0x00` 或 118 字节且与 success time 的空值一致；failure_count>=0。所有 nullable 时间分支显式 IS NULL/IS NOT NULL，不能让 SQL UNKNOWN 漏过。TARGET_TRANSIENT 的 `check_key > success_key` 是状态读取条件，不强制成 CHECK，因为更旧 transient 可合法留作被后续 success 覆盖的审计 attempt。

规则：

- 不保存完整 invite_url，API 返回时拼接 https://chat.whatsapp.com/{invite_code}。
- group_id 一旦绑定，预览字段不再作为群资料来源；Reducer 把可靠值归并到 profile 后清空预览字段。
- preview_owner_country_iso2 与 M.phone_country_iso2 使用同一个 confirmed-phone resolver，只是可重建索引投影；协议不能自报覆盖，resolver 版本变化时统一重算。
- 邀请轮换必须锁 profile 并比较 group-level current_invite_version_key，在一个事务内 upsert 新 code、写旧行 superseded_at，再把 profile 写成 state=PRESENT/current_invite_id=新行；旧行原有 validity_status 保留为历史观察。
- 当前 code 校验明确失效时只更新该 invite.validity；profile 指针仍可指向它，表示“最后已知当前 code 已失效”。显式 REVOKED 且没有新 code 时把 profile 写 state=EXPLICIT_NONE、current_invite_id=NULL、reason=REVOKED，并写旧 invite INVALID / invalidated_at。
- 检查状态组合固定为：UNKNOWN 只能配 NONE、`last_check_version_key=0x00`；SUCCESS 只能配 NONE，并同时推进 `last_success_check_version_key/at`；TARGET_TRANSIENT 只能配 INVITE_QUERY_TRANSIENT；EXECUTION_FAILURE 只能配 EXECUTOR_ACCOUNT/NO_EXECUTOR/DELIVERY/PAYLOAD/UNKNOWN。账号封禁、离线、无管理员、无执行账号、投递和载荷错误都属于执行链失败，只更新检查调度诊断，不把链接判 INVALID/UNAVAILABLE；成功校验只推进 validity 版本，不擅自断言它就是群当前 code。
- 链接失效只影响 invite；群 suspended / terminated 只影响 profile；账号封禁只影响 account_state。
- 非导入来源首次创建 I 时默认 `pool_hidden_at=first_seen_at`，避免协议发现的 current/history invite 自动冒充“已收编导入池”；明确导入成功才清 hidden。再次导入同一大小写精确 code 时分两类：当前已可见且 label_id 非空为 result=FAILED/failReason=DUPLICATE，不改变 I；当前 hidden 或未归组(label_id IS NULL)为 result=SUCCESS/successType=ADOPTED，复用现有 `GroupLinkImportSuccessType.ADOPTED` 口径，清 pool_hidden_at、写本次目标 label，并新增成功 detail。普通协议观察可以继续更新隐藏行，但不会替用户恢复导入分组。若同一 code 被可靠来源解析到不同 group_id，禁止自动改绑，记 CODE_GROUP_CONFLICT 并进入人工 / 协议回读队列。
- 普通“删除邀请”只是隐藏导入池归属；若用户真的要撤销群当前邀请，必须走 WhatsApp revoke 命令并以协议确认推进 P.current_invite。这样后续再次观察同 code 不会在“运营删除”和“协议当前事实”之间互相复活，也不需要违反 code 无条件唯一键。
- 系统退役 deleted_at 只是一种保留期/GC 状态，不代表运营禁止；若更晚的权威协议事实再次确认同一大小写精确 code，允许复用该 identity 并清 deleted_at，但不得自动清 pool_hidden_at 或恢复 label。
- 检查结果先比较 last_check_version_key：winning 成功清 error、failure_count=0，并以同一个 key 推进 success key；winning 失败才递增 failure_count。明确 INVALID/REVOKED 仍是“成功取得目标结论”，先推进 SUCCESS 再写 validity；晚到失败不得覆盖更新成功，也不得把连续失败数加乱。I-only 的 UNAVAILABLE 唯一条件是 `last_check_result=TARGET_TRANSIENT AND last_check_error_domain=INVITE_QUERY_TRANSIENT AND last_check_version_key > last_success_check_version_key`；执行链失败永不改变资源状态。

### 5.4 wa_group_participant

聚合职责：群内 WhatsApp 人的规范身份、当前 presence、当前 role，以及项目当前需要的最近一次进群和退群事实。

| 字段 | 类型 | 空值 / 默认 | 说明 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 成员稳定 ID |
| tenant_id | BIGINT | NOT NULL | 租户 ID |
| group_id | BIGINT | NOT NULL | 逻辑关联 wa_group.id |
| pn_jid | VARCHAR(191) ASCII BIN | NULL | 已确认 PN JID |
| pn_identity_version_key | VARBINARY(128) | NOT NULL | PN alias 的 compact 证据版本；冲突不能按 updated_at 覆盖 |
| lid_jid | VARCHAR(191) ASCII BIN | NULL | 已确认 LID JID |
| lid_identity_version_key | VARBINARY(128) | NOT NULL | LID alias 的 compact 证据版本 |
| last_identity_merge_version_key | VARBINARY(128) | NOT NULL | 最近一次 PN/LID 行合并的可信 alias 证据版本 |
| phone | VARCHAR(32) ASCII BIN | NULL | 由确认 PN 规范化得到的查询投影；不得从未知 LID 猜号码 |
| phone_country_iso2 | CHAR(2) ASCII BIN | NULL | 由 confirmed PN + canonical country resolver 计算的可重建查询投影；不是第二份身份事实 |
| country_resolution_version | VARBINARY(32) | NOT NULL | confirmed-phone country resolver 语义/依赖的 SHA-256；全零表示待重算 |
| presence_status | TINYINT | NOT NULL, 0 | 0未知 1在群 2主动退出 3被移除 4完整快照缺失 5已退出但原因未知；5 用于可靠 departure、不能退化成“从未观察” |
| presence_observed_at | BIGINT | NULL | presence 事实时间 |
| presence_version_key | VARBINARY(128) | NOT NULL | presence 的 compact fact-version key |
| membership_epoch | BIGINT | NOT NULL, 0 | 每次从明确不在群转为在群时递增；隔离退群前后的角色事实 |
| membership_epoch_started_at | BIGINT | NULL | 当前 membership epoch 的开始事实时间 |
| membership_epoch_started_version_key | VARBINARY(128) | NOT NULL | 当前 epoch 开始事实的 compact version key |
| role | TINYINT | NOT NULL, 0 | 0未知 1成员 2管理员 3群主 4管理员或群主但上游无法细分 |
| role_membership_epoch | BIGINT | NOT NULL, 0 | 当前 role 属于哪个 membership_epoch；两者不等时角色按 UNKNOWN 读取 |
| role_observed_at | BIGINT | NULL | role 事实时间 |
| role_version_key | VARBINARY(128) | NOT NULL | role 的 compact fact-version key |
| last_snapshot_id | VARCHAR(128) ASCII BIN | NULL | 最近一次处理该成员的完整快照批次 ID，包括本次被判缺失 |
| last_observer_account_id | BIGINT | NULL | 最近一次接受事实的观察账号 |
| last_joined_at | BIGINT | NULL | 最近一次可靠 WhatsApp 进群时间 |
| last_join_event_at | BIGINT | NULL | 最近进群事实的排序时间 |
| last_join_version_key | VARBINARY(128) | NOT NULL | 最近进群事实的 compact fact-version key |
| last_join_observer_account_id | BIGINT | NULL | 最近进群观察账号 |
| last_exited_at | BIGINT | NULL | 最近一次可靠退出时间 |
| last_exit_type | TINYINT | NULL | 1主动退出 2被移除 3原因未知 |
| last_exit_event_at | BIGINT | NULL | 最近退出事实的排序时间 |
| last_exit_version_key | VARBINARY(128) | NOT NULL | 最近退出事实的 compact version key；来源从 event/journal 诊断 |
| first_seen_at | BIGINT | NOT NULL | 首次观察时间 |
| last_seen_at | BIGINT | NOT NULL | 最近观察时间 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

约束与索引：

| 名称 | 列 | 类型 / 用途 |
|---|---|---|
| PRIMARY | id | 主键 |
| uq_wa_group_participant_pn | tenant_id, group_id, pn_jid | 非空 PN 唯一 |
| uq_wa_group_participant_lid | tenant_id, group_id, lid_jid | 非空 LID 唯一 |
| idx_wa_group_participant_list | tenant_id, group_id, presence_status, role, id | 群成员/管理员查询 |
| idx_wa_group_participant_phone | tenant_id, phone, group_id | 账号和手机号反查 |
| idx_wa_group_participant_country | tenant_id, phone_country_iso2, presence_status, role, group_id | 群主国家筛选；大洲再连接 country |
| idx_wa_group_participant_exit | tenant_id, group_id, last_exited_at | 退群导出 |

增加 CHECK：pn_jid 与 lid_jid 至少一个非空。
另加枚举 CHECK：presence_status IN (0,1,2,3,4,5)、role IN (0,1,2,3,4)、last_exit_type IS NULL OR IN (1,2,3)；不能让 DEPARTED_UNKNOWN 只存在于 Java enum 而数据库拒绝。

PN/LID 合并必须锁定两个候选行和相关 binding，先按 total-order version 选 winning presence / join / exit，再把 binding 改指向保留行、补齐 PN/LID，最后删除被合并行。两个旧行的 membership_epoch 是各自局部 fencing token，不能 `MAX()` 后假装同一段历史：合并时创建一个大于两边的 synthetic merge epoch，epoch start 取 `max(aliasEvidenceVersion, winning IN_GROUP transition/version)`；role 默认清 UNKNOWN。只有 role 与 winning presence 来自同一 snapshot/event，或 role version 可靠晚于 merge epoch start，才能保留到新 epoch。若 winning presence 不是 IN_GROUP，role 一律 UNKNOWN 且不可读。手机号只由 PN 生成，不能拿 LID 数字部分当手机号。phone_country_iso2 只是可丢弃重建的索引投影：phone 或 canonical resolver version 变化时增量重算；权威仍是 PN/phone 与 country 主数据，不允许协议事件直接自报覆盖。

可信 alias evidence 限定为协议明确同时给出 PN+LID、后端已有受控账号 PN 与协议可信 LID 映射、或命令后同账号回读；仅数字相似、展示 phone 或本机推测不算。已有非空 alias 收到不同值时不直接覆盖：Reducer affected rows=0，把稳定 eventId 与冲突摘要送现有 Kafka DLT/告警，并把该群 metadata task 标 IDENTITY_CONFLICT 触发回读；不为冲突另造第二份成员主值。pn/lid/merge version 让修复后重放可判定。

participant.id 才是 Armada 内部 canonical 身份；不再另存一个会在 PN / LID 之间摇摆的 participant_jid 主值。canonical API 的成员动作提交 participantId，后端按目标协议 backend 选择 pn_jid 或 lid_jid；v1 兼容 VO 才按“可信 PN 优先，否则 LID”派生 participantJid。这样 Web / Android 的寻址差异不会变成第三份身份主值。

成员第一次从 UNKNOWN 进入 IN_GROUP 时把 membership_epoch 从 0 置 1；从 LEFT / REMOVED / SNAPSHOT_ABSENT / DEPARTED_UNKNOWN 再次进入时原子递增。重复的 IN_GROUP 观察不增 epoch。每次新 epoch 都把 role 重置为 UNKNOWN，并把 role_membership_epoch 置为新 epoch。晚到的旧角色事件只要 key 早于 membership_epoch_started_version_key 就拒绝；key 相等仅允许来自同一 eventId / snapshot 的原子 presence+role 事实。有效角色事件落在当前 epoch。这样成员退群前的管理员身份不会在重新入群后自动复活。

账号轻量群报告若只有 `admin=true` 而没有 superadmin / owner 信息，只能写 role=ADMIN_OR_OWNER，不能伪造 ADMIN；`admin=false` 可写 MEMBER。ADMIN_OR_OWNER 可以参与“有管理权限”候选，但踢人 / 降权等 owner 敏感操作必须先刷新成确切 ADMIN 或 OWNER。

本表不再保存 is_admin / is_owner 镜像；两者由 role 派生。当前成员、完整快照缓存、最近进群、最近退群也不再分散到四张表。

### 5.5 wa_account_group_binding

聚合职责：某条 Armada account 记录在每个协议绑定生命周期中，对应群内哪个 participant；同时保存只对该账号、该绑定代次成立的 baseline 和上控后语义。旧代次只作生命周期证据，当前查询必须与 sync_state 的当前 binding_instance / generation 相等。

| 字段 | 类型 | 空值 / 默认 | 说明 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| tenant_id | BIGINT | NOT NULL | 租户 ID |
| account_id | BIGINT | NOT NULL | 逻辑关联 account.id |
| group_id | BIGINT | NOT NULL | 逻辑关联 wa_group.id |
| participant_id | BIGINT | NOT NULL | 逻辑关联 wa_group_participant.id |
| binding_instance_id | VARCHAR(64) ASCII BIN | NOT NULL | 后端为一次“账号↔协议账号”绑定生成的全局稳定 ID |
| binding_generation | BIGINT | NOT NULL | 写入时对应 account_group_sync_state.binding_generation |
| was_in_initial_baseline | TINYINT | NULL | NULL尚未完成分类 0不在初始基线 1在初始基线 |
| baseline_subject_snapshot | VARCHAR(255) | NULL | 初始基线时群名快照；是历史证据，不是当前群名 |
| baseline_captured_at | BIGINT | NULL | 本关系被纳入初始 baseline 的时间 |
| first_observed_at | BIGINT | NOT NULL | 该 account 生命周期首次看到此群 |
| last_observed_at | BIGINT | NOT NULL | 最近一次看到此关系 |
| last_observed_version_key | VARBINARY(128) | NOT NULL | 最近观察的 compact fact-version key |
| last_complete_snapshot_id | VARCHAR(128) ASCII BIN | NULL | 最近包含该关系的账号完整快照批次 ID |
| first_post_control_observed_at | BIGINT | NULL | baseline 明确完成后首次可靠确认“账号在新群”的事实时间；明确 JOIN/ADD 取 occurredAt，合格实时 FULL_ACCOUNT_SET 取 queryStartedAt，baseline 内和未知关系一律为 NULL |
| first_post_control_observed_version_key | VARBINARY(128) | NOT NULL | 上述首次可靠确认的 compact fact-version key；空值时固定 `0x00` |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |
| retired_at | BIGINT | NULL | 该绑定代次失效时间，不表示 participant 退出群；legacy 已失效但无可信时间时允许 0 |

约束与索引：

| 名称 | 列 | 类型 / 用途 |
|---|---|---|
| PRIMARY | id | 主键 |
| uq_wa_account_group_binding | tenant_id, account_id, binding_generation, group_id | 一个账号绑定代次对一个群一行 |
| uq_wa_binding_instance_group | tenant_id, binding_instance_id, group_id | 同一绑定实例对一个群只能一行；兼作 v2 事件和当前代次关联 |
| idx_wa_binding_group_participant | tenant_id, group_id, participant_id, retired_at | 执行账号和群成员关联 |
| idx_wa_binding_account_seen | tenant_id, account_id, binding_generation, last_observed_at, group_id | 账号群列表 |
| idx_wa_binding_historical | tenant_id, group_id, was_in_initial_baseline, account_id | 租户生命周期“曾是历史群”筛选 |
| idx_wa_binding_post_control | tenant_id, group_id, first_post_control_observed_at, account_id | 租户生命周期“曾上控后确认的新群”筛选 |

本表明确禁止 membership_status、is_admin、is_owner、last_exit_type。查询“账号当前是否在群、是不是管理员”必须 binding → participant；查询“账号是否在线可执行”再连接 account → account_state。

B 的分类约束不只靠 Service 约定：`first_post_control_observed_at IS NOT NULL` 必须同时满足 `was_in_initial_baseline=0`，`was_in_initial_baseline=1` 或 NULL 时该字段必须为 NULL；对应 version key 的空值组合亦由 CHECK 封闭。这条约束防止 baseline 历史群被后续快照或迁移时间误标成“上控后新群”。列名刻意使用 observed 而不是 joined：完整快照只能证明“查询开始时已在群”，不能伪造 WhatsApp 实际加入时间。

所有当前查询必须 `B.account_id=S.account_id AND B.binding_instance_id=S.binding_instance_id AND B.binding_generation=S.binding_generation AND B.retired_at IS NULL`。解除 / 换绑在推进 S 前先把旧 instance 的 B.retired_at 写入；不复用、不清空旧行。删除和重新导入同一手机号会产生新的 account.id，因此也会自然产生新的 binding 生命周期。

### 5.6 account_group_sync_state

聚合职责：账号级全量群快照的提交头和 baseline 状态。没有这张表，就无法表达“完整但为空的快照”和“已拍但为空的 baseline”。

| 字段 | 类型 | 空值 / 默认 | 说明 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| tenant_id | BIGINT | NOT NULL | 租户 ID |
| account_id | BIGINT | NOT NULL | 逻辑关联 account.id |
| binding_instance_id | VARCHAR(64) ASCII BIN | NULL | 当前绑定实例；未绑定账号为空，绑定时由后端生成且不可由协议自报 |
| binding_generation | BIGINT | NOT NULL, 0 | Armada 协议账号业务绑定代次；换绑时递增，不是 socket 重连次数 |
| baseline_filter_enabled | TINYINT | NOT NULL, 1 | 独立运营策略：1启用 baseline 排除 0不启用；与账号是否当前绑定正交 |
| baseline_state | TINYINT | NOT NULL, 1 | 1 PENDING待拍 2 CAPTURED已拍 3 DISABLED不启用过滤 |
| baseline_completeness | TINYINT | NOT NULL, 0 | 0 NONE尚无 1 EXPLICIT_COMPLETE上游显式完整 2 LEGACY_UNKNOWN存量完整性未知 |
| baseline_captured_at | BIGINT | NULL | EXPLICIT_COMPLETE 固定取形成 baseline 的 queryStartedAt/observedAt；LEGACY_UNKNOWN 按迁移规则；禁止取 completedAt、ingestedAt 或 now；空 baseline 也填写 |
| baseline_snapshot_id | VARCHAR(128) ASCII BIN | NULL | 形成 baseline 的完整账号快照批次 ID；存量未知可为空 |
| baseline_fact_version_key | VARBINARY(128) | NOT NULL | 形成 baseline 的 compact fact-version key；存量未知用 MIGRATION 固定低版本 |
| baseline_group_count | INT | NULL | baseline 群数；显式完整空集合为 0，存量无法证明时为空 |
| last_sync_requested_at | BIGINT | NULL | 最近一次同步命令入队时间 |
| last_sync_attempt_at | BIGINT | NULL | 最近一次账号群查询尝试的事实 / 开始时间 |
| last_sync_attempt_version_key | VARBINARY(128) | NOT NULL | 最近一次成功或失败 attempt 的 compact version，错误水位按它 CAS |
| last_reported_at | BIGINT | NULL | 最近一次收到任意群报告的事实时间 |
| last_report_fact_version_key | VARBINARY(128) | NOT NULL | 最近任意群报告的 compact total-order key |
| last_report_source | VARCHAR(64) ASCII BIN | NULL | wa_groups_dirty、online_sync、manual 等 |
| last_report_backend | TINYINT | NULL | 1WEB 2ANDROID |
| last_report_scope | TINYINT | NULL | 1DELTA 2FULL_ACCOUNT_SET；描述是否覆盖账号全部群 |
| last_detail_level | TINYINT | NULL | 1SUMMARY 2FULL_METADATA；描述每群资料丰富度，不决定缺失语义 |
| last_snapshot_complete | TINYINT | NOT NULL, 0 | 最近报告是否由上游显式确认完整 |
| last_skipped_group_count | INT | NOT NULL, 0 | 最近报告跳过条目数 |
| last_reported_group_count | INT | NOT NULL, 0 | 最近报告有效群数 |
| last_query_started_at | BIGINT | NULL | 最近报告对应协议查询开始时间 |
| last_query_completed_at | BIGINT | NULL | 最近报告对应协议查询完成时间 |
| last_complete_snapshot_at | BIGINT | NULL | 最近一次被接受的完整快照时间 |
| last_complete_snapshot_id | VARCHAR(128) ASCII BIN | NULL | 最近完整快照批次 ID |
| last_complete_fact_version_key | VARBINARY(128) | NOT NULL | 最近完整快照的 compact total-order key；mark-missing 以它 CAS |
| last_complete_group_count | INT | NULL | 最近完整账号群快照实际群数；完整空集合为 0 |
| last_error_code | VARCHAR(64) | NULL | 最近一次账号群同步稳定错误码；成功时清空 |
| last_error_at | BIGINT | NULL | 最近同步错误发生时间 |
| consecutive_failure_count | INT | NOT NULL, 0 | 连续同步失败次数；成功报告归零 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

约束与索引：

| 名称 | 列 | 类型 / 用途 |
|---|---|---|
| PRIMARY | id | 主键 |
| uq_account_group_sync_state | tenant_id, account_id | 一个账号一行 |
| uq_account_group_sync_binding | tenant_id, binding_instance_id | 当前非空绑定实例全租户唯一 |
| idx_account_group_sync_baseline | tenant_id, baseline_state, account_id | baseline 调度 |
| idx_account_group_sync_requested | tenant_id, last_sync_requested_at, account_id | 同步调度与巡检 |

binding 活跃性与 baseline 策略是两个维度：active binding 可以是 DISABLED（过滤策略关闭），未绑定账号也必须是 DISABLED（没有当前生命周期可拍）。baseline_state 与 baseline_completeness 只允许下列组合；这里的 NONE 是 baseline_completeness，不是第四种 baseline_state：

- PENDING + NONE：要求 baseline_filter_enabled=1 且 binding_instance_id 非 NULL；baseline_captured_at / baseline_snapshot_id / baseline_group_count 必须为 NULL，baseline_fact_version_key 必须为 UNOBSERVED `0x00`。
- CAPTURED + EXPLICIT_COMPLETE：要求 baseline_filter_enabled=1 且 binding_instance_id 非 NULL；baseline_captured_at、baseline_snapshot_id、非 UNOBSERVED baseline_fact_version_key、baseline_group_count 均必填；完整空集合的 count 合法为 0。
- CAPTURED + LEGACY_UNKNOWN：要求 baseline_filter_enabled=1 且 binding_instance_id 非 NULL；baseline_captured_at 必须非 NULL，有可信旧时间就取旧值、缺失写 0；baseline_snapshot_id 与 baseline_group_count 必须为 NULL，baseline_fact_version_key 必须精确等于单字节 `0x01` LEGACY_UNKNOWN_BASELINE_KEY；旧 JSON 明确列出的 B 可以标 1，未列出的保持 NULL。
- DISABLED + NONE：全部 baseline header 为 NULL，fact key 为 `0x00`；允许“active binding + baseline_filter_enabled=0”，也允许“无 binding + 任意保留策略值”，但 filter_enabled=0 时只能是该组合。

除此以外的组合全部由 CHECK 和 Reducer 拒绝。实际 MySQL CHECK 必须把四个 OR 分支中的每个 nullable header 都写成显式 `IS NULL` / `IS NOT NULL`，不能让 SQL UNKNOWN 当作通过；EXPLICIT_COMPLETE 还要求 snapshot_id 非空字符串、baseline_group_count>=0、fact key 为 118 字节，LEGACY_UNKNOWN 要求 key 精确等于 `0x01`。另加 binding CHECK：instance 非空时字符串非空且 generation>0。解绑后 generation 可以保留大于 0，不能写反向约束。当前活跃账号发生新绑定 / 换绑时清空旧 generation 的 baseline、report、complete-snapshot header 与对应 version key：策略启用进入 PENDING/NONE，策略关闭进入 DISABLED/NONE；解绑或软删除进入 DISABLED/NONE但不改 baseline_filter_enabled，重新绑定时再按保留策略决定 PENDING 或 DISABLED。仅 socket 重连不改变 binding，也不重置 baseline。

本表不复制 account.protocol_account_id 和 account.protocol_backend。Consumer 用事件里的 armadaAccountId 找 account，再同时校验 protocolAccountId、bindingInstanceId、bindingGeneration 四元组与 account + sync_state 当前值完全一致；只靠 protocolAccountId + generation 不足以防止“同一协议账号改绑到另一 Armada account 且 generation 数值碰巧相同”。

binding_generation 的递增边界必须唯一：仅当 Armada account 与协议账号建立新业务绑定、换绑或解除后重绑时，在同一事务中 `+1` 并生成全新的 binding_instance_id；WebSocket 重连、onlineAttempt 变化、普通离线再上线都不递增。账号激活 / 登录命令把 armadaAccountId、protocolAccountId、bindingInstanceId、generation 下发给 Web / Android 当前会话，所有被动事件原样回传。解除绑定后旧 instance 和 generation 永久失效。

同一事务按“锁 account+S → retire 旧 B → 推进 generation → 按账号最终状态写新 instance 或 NULL → 按上述二维状态机重置 baseline/report header → 提交”执行：活跃新绑定在策略启用时为 PENDING/NONE、策略关闭时为 DISABLED/NONE；解绑 / 软删为 DISABLED/NONE。旧代次 B 保留但不参与当前查询。缺少 bindingInstanceId / generation 的 v1 被动事件不得写 B/S，也不得改变 M 的 presence / role；最多按低优先级补充不为空的 G/P 观察并进入兼容对账。

account 域还必须补数据库级“活跃协议账号唯一”约束：在 account 上增加只在 `deleted_at IS NULL`、protocol_id 非空且 protocol_account_id 非空时取值的 ascii_bin generated active protocol account 列，并对 `(tenant_id, protocol_id, active_protocol_account_id)` 建唯一键；protocol_id 在当前模型中代表 backend，进入绑定态时不得为空。绑定事务先锁目标 account / S，再依赖该唯一键拒绝同租户两个活跃 account 绑定同一 backend + protocolAccountId，不能只靠 S.binding_instance_id 唯一。

从任一账号首次创建 S 之前开始，绑定、解绑、换绑、软删除、重新启用和配对接管必须全部由 AccountBindingLifecycleService 作为唯一 writer，在同一事务维护 account+S+B；尚无 S 时先初始化再完成本次生命周期动作，已有 S 时走正常 generation 状态机，不能再次调用 MIGRATION_BINDING_INIT。旧 AccountService / import / pairing 路径必须先改为委托该服务，并用代码扫描和运行审计证明旁路为 0；否则 Phase 2A 期间发生的换绑只能被 barrier 发现、无法安全修复。migration run ledger 还要把每条 legacy baseline row id/hash/source watermark 绑定到 INIT 当时的 instance/generation；生命周期推进前把尚未落 B 的该代 baseline token 转成“仅可回填 retired lifecycle”的 token并永久失效其 CURRENT 权限，新 generation 绝不能继承旧 JSON。

首期不新增 `account_protocol_binding_history`。切换暂停窗口必须停止新命令、排空或明确取消全部在途命令；command/outbox 继续冻结提交时的 account/backend/protocolAccount/binding instance/generation。切换后迟到且四元组不再 current 的结果只隔离并触发 metadata/member 确认回读，不能凭一张新 history 表为旧 generation 补写 B 或触发营销。若产品以后要求“换绑后仍自动接纳旧 command 的真实结果”，再单独设计账号绑定历史，不把它作为六表上线前置。

账号软删除、解绑和配对接管都属于 binding 生命周期写入：同一事务锁 account+S，retire 当前 B 并推进 generation；软删 / 解绑清空 binding_instance_id 并写 DISABLED/NONE，配对接管建立新 instance 时按 baseline_filter_enabled 写 PENDING/NONE 或 DISABLED/NONE。Consumer 除四元组外还必须校验 `account.deleted_at IS NULL`。重新导入相同手机号产生新 account / instance，旧队列事件永久失效，不能复用旧 S 或 B。

账号群查询失败也先 CAS last_sync_attempt_version_key；winning 成功报告清 error/failure，winning 失败才递增。last_report_fact_version_key 只在真正收到报告时推进，不能用一次失败伪装成空快照。

## 6. 状态归属和派生口径

### 6.1 列表状态不是一列数据库状态

API 的列表 status 是只读组合值：

1. profile.group_status 为 SUSPENDED / TERMINATED：BANNED。
2. profile.current_invite_id 指向的 invite 为 INVALID，或者 profile.current_invite_state=EXPLICIT_NONE：LINK_INVALID。
3. group_metadata_sync_task.last_probe_result=TEMP_UNAVAILABLE，且 last_probe_fact_version_key 按 unsigned bytes 严格大于 last_success_fact_version_key：v1 兼容为 UNAVAILABLE。
4. 群有可靠 metadata 或当前邀请被确认：AVAILABLE。
5. 没有任何可靠观察：UNCHECKED。

TEMP_UNAVAILABLE 只来自过程任务中被明确分类为 GROUP_QUERY_TRANSIENT 的最近 probe，不写 P.group_status；账号/权限/调度/未投递类失败继续只由任务状态表达。任何被 Reducer 接受的正向群 metadata 或“当前邀请存在”的确认，都在同一事务 / durable effect 中推进 task.last_success_at 与 last_success_fact_version_key；临时失败只推进 last_probe_at、last_probe_result 和 last_probe_fact_version_key。相同或更旧 probe 不能盖过成功。这样既保留方案 A 的 UNAVAILABLE 筛选 / 批量刷新门禁，又不会再把“账号被封”“邀请失效”“群被停用”“一次请求超时”写进同一个 health 行。

probe 写入矩阵固定如下，Adapter 必须先产出 errorDomain，Service 不能再用“非成功且非链接失效”统归 UNAVAILABLE：

| 结果 / errorDomain | 权威写入 | task probe 处理 | 列表含义 |
|---|---|---|---|
| METADATA_OK / CURRENT_INVITE_OK / authoritative group status response | P/I 对应事实 | upsert task，推进 SUCCESS probe + success key | AVAILABLE，或被 BANNED/LINK_INVALID 高优先级覆盖 |
| CHAT_SUSPENDED / CHAT_TERMINATED | P.group_status | 这是成功取得稳定事实，推进 SUCCESS + success key | BANNED |
| INVITE_INVALID / REVOKED | I.validity / P.current pointer | 这是成功取得稳定事实，推进 SUCCESS + success key | LINK_INVALID |
| GROUP_QUERY_TRANSIENT（已由仍 eligible 的执行账号真正发起，协议明确为目标群临时失败） | 不改 P/I | 推进 TEMP_UNAVAILABLE probe key，不推进 success | 仅当 probe key > success key 时 UNAVAILABLE |
| ACCOUNT_BANNED / ACCOUNT_OFFLINE / RISK / RATE_LIMIT / OBSERVER_PERMISSION | 更新 account/account_state 或换执行账号 | task DEFERRED/RETRY，记录 errorDomain；不推进 group probe key | 不改变群 status |
| NO_EXECUTOR / NO_ADMIN / scheduler capacity / lease conflict | 不改六表 | 仅 next_run/reason；不推进 probe key | 不改变群 status |
| publish 未确认、payload invalid、unknown error | outbox/DLT/告警 | 不推进 probe/success，确认回读 | 不改变群 status |

正向事实到达时即使 group_metadata_sync_task 尚不存在，也要在 Reducer 同事务 upsert；若 P/current invite 已有更早 accepted success，首次创建 task 必须用该 accepted FactVersion/observedAt 初始化 last_success key/time，不能从 `0x00` 起步后让一次临时失败把已有可用群误判 UNAVAILABLE。I-only 仍使用 I.check 的独立同序规则，不读取 group metadata task。

默认列表 count/page-id 不连接 task；只有请求 status 筛选时才连接并使用上述同一个 CASE。分页得到 GROUP IDs 后，metadata/status enrichment 也复用同一个 CASE，禁止 count、筛选和展示分别解释“最近”。

上述优先级只用于 v1 `legacyStatus` 兼容。canonical API 分别返回 groupStatus、inviteValidity、hasCurrentInvite 和 executionCapability，不再鼓励新业务依赖一个混合枚举。

I-only 行没有 P.group_status，v1 状态单独固定为：validity=INVALID→LINK_INVALID；`last_check_result=TARGET_TRANSIENT`、domain=INVITE_QUERY_TRANSIENT 且 check key 严格晚于 success key→UNAVAILABLE；否则 success key 非 `0x00`→AVAILABLE；从未有可靠目标结论→UNCHECKED。EXECUTION_FAILURE 不进入状态 CASE，永不伪造 BANNED。count/page/display 共用这一 CASE。

### 6.2 历史群与上控后群

历史群和上控后群是账号维度事实，不在 wa_group 存布尔镜像：

- 历史群：EXISTS binding.was_in_initial_baseline=1。
- 上控后群：EXISTS binding.first_post_control_observed_at IS NOT NULL。
- 同时属于两类：两个 EXISTS 同时成立；可以来自不同账号，保持当前租户级列表的“曾发生过”口径。
- 群列表这两个标签是租户生命周期口径，允许命中 retired 的旧 binding；账号营销排除 baseline 时，必须按同一个 account_id 且 JOIN S 当前 binding_instance / generation，不能使用租户级派生标签或旧代次。

写入规则采用保守边界，不维护 baseline 前候选：EXPLICIT_COMPLETE baseline 提交时，本批 B 全部标 1，`baseline_captured_at` 固定取该快照的 queryStartedAt/observedAt。baseline 明确完成后，只有当前事务第 2 条普通读确认 **B 物理不存在** 的群，才允许由当前 instance 的明确 JOIN/ADD，或 `FULL_ACCOUNT_SET+complete=true+skippedCount=0` 的实时快照新建 B=0 并写 first-post；existing B 无论 was=1 还是 NULL 都不能被升级为 0。incoming fact version key 必须大于 S.baseline_fact_version_key，且 occurredAt/queryStartedAt 必须严格大于 S.baseline_captured_at；同毫秒保守不触发营销。JOIN/ADD 取 occurredAt，合格 FULL_ACCOUNT_SET 取 queryStartedAt。单纯 metadata、invite、partial/delta 快照或其他账号观察不能触发。LEGACY_UNKNOWN 的未列出 binding 已在迁移时以 was=NULL 建行，切后首个及重复完整快照只能推进观察水位，永远不能据此变成上控后新群；DISABLED 也不伪造 0/1。这样宁可漏掉 baseline 拍摄窗口内刚加入的群，也不把历史群误发营销；若产品未来要补这个窗口，必须有新的可信事件证据并单独评审。

如果产品需要“按指定账号查看历史群”，接口必须显式接收 accountId 或账号分组条件，不能继续复用租户级 sticky 字段。

### 6.3 受控管理员与可用管理员

- 任何角色读取都必须同时满足 `participant.presence_status=IN_GROUP AND participant.role_membership_epoch=participant.membership_epoch`；随后才可判断 role IN (ADMIN, OWNER, ADMIN_OR_OWNER)。这条条件适用于列表管理员、群主、国家筛选、执行账号和营销选择，禁止各 Mapper 各写一套。
- 方案 A 的 v1 `admin` / 管理员关键字保持现网隐私口径：只返回 phone 非空且能匹配同租户、未删除 Armada account 的“受控管理员”，建议 UI 改名“受控管理员号码”；不能因换表自动扩大为所有观察到的 WhatsApp 手机号。
- 可用管理员：在受控管理员条件上，再要求当前 generation 的有效 binding、account 未删除、protocol_account_id 有值、account_state 在线且正常。
- canonical API 如未来需要 `observedAdminPhones`，必须另做权限、脱敏和产品评审；默认列表不返回该字段。

### 6.4 群主、创建者国家和群龄

- 当前 owner / superadmin 由 participant.role=OWNER 获取。
- current exact OWNER 必须同时满足 IN_GROUP 和 role epoch 相等；同群匹配 0 人返回未知，匹配多于 1 人返回 OWNER_CONFLICT 并触发完整 metadata/member refresh，绝不能按 MIN(id) 随便选一个。OWNER_CHANGED 若只给新 owner、缺少旧 owner，先写新事实并将群标记待刷新，在完整回读前 owner 敏感操作不可执行。
- creatorPhone 现有前端名称容易误导；目标 API 使用 ownerPhone，UI 是否改文案另行确认。
- 国家按上述唯一 current owner 的 `M.phone_country_iso2` 查询投影筛选，大洲再连接 country；分页的 count/page 都在 SQL 中下推相同 EXISTS，不能页后 Java 过滤。权威仍由确认 phone + country 配置派生，不再把 creator_country_iso2 / creator_continent_code 固化在 profile。
- 群龄由 profile.wa_created_at 计算；所有时间单位改为毫秒，前端不再额外乘 1000。

### 6.5 v1 membershipState 的精确定义

方案 A 继续接受旧筛选值，但改为确定性派生，优先级固定为 OWNER(3) > JOINED(2) > TARGET(1)：

1. OWNER：`G.managed_creator_account_id IS NOT NULL`。这是 Armada 历史创建属性；创建账号后来退群、离线或被删除仍保持 OWNER，避免“自建群”身份漂移。
2. JOINED：不是 OWNER，且存在 account 未删除、S 当前 binding instance/generation、B 未 retired，并且对应 M 为 IN_GROUP 的受控账号关系。
3. TARGET：以上均不成立，包括未解析 I-only 行。

旧 group_link.membership_state 是会被不同 writer 覆盖的 sticky 值，因此 shadow diff 中允许且必须解释下列语义修正：多账号一进一退仍为 JOINED；最后一个当前受控账号退出或账号删除后回 TARGET；自建账号退出仍为 OWNER。count、page、filter 必须共用同一 SQL 谓词并覆盖这些组合测试。

v1 origin 也必须显式适配：邀请/进群/拉群/自建/账号快照分别映射旧 1/2/3/4/5；新的“群事件首次发现”兼容为 5=账号/协议同步。MIGRATION 不是业务来源，回填能证明旧 origin 时必须保留对应业务来源，完全未知才在 canonical 返回 MIGRATION、v1 保守映射 5。origin 筛选和展示共用该映射。

## 7. 事件归并和乱序规则

### 7.1 两阶段 fencing

账号事件先做生命周期 fencing，再做字段新旧比较：

1. PROTOCOL_FACT 先用 armadaAccountId 找到同租户、未删除 account，再同时校验 protocolBackend、protocolAccountId、bindingInstanceId、bindingGeneration 与 account+account_group_sync_state 当前四元组完全相等；任一不等都隔离，不能只比较 generation。
2. 先按字段的 source admission / authority tier 判断该来源能否覆盖，再使用固定总序版本：observedAt、authorityTier、sourcePriority、sequenceDomain、hasSourceSequence、sourceSequence、sourceEventId、eventId。
3. 版本更大才允许覆盖；相同 eventId 重放必须无副作用。
4. ingestedAt / 数据库 updated_at 永远不能判断业务事实新旧。

source admission 分两类：FILL_ONLY 只有当前 version key=UNOBSERVED 才能写，不能在显式 clear 后重新填值；AUTHORITATIVE_RECONCILIATION 则以 observedAt 为跨时间主序，authorityTier/sourcePriority 只在同一事实时间决胜。authorityTier 不是永久 floor，否则一次显式 ADD 会让更晚的完整 absent 快照永远无法对账。SUSPENDED/TERMINATED、人工删除等少数终态另有显式 guard。total order 只在通过 admission 后比较，保证重放与同毫秒并发确定。

bindingGeneration 只用于第一阶段 fencing，不拿不同账号的 generation 比资料新旧。sequenceDomain 固定为可复现的 `protocolBackend:bindingInstanceId:sourceType`；无绑定的迁移 / 人工来源使用约定的稳定 domain。无序列来源也必须给稳定 domain，并把 hasSourceSequence/sourceSequence 规范为 0/0，禁止“有时跳过某一维”的条件比较。跨 domain 在 observedAt、authorityTier 和 priority 完全相同时按编码后的 SHA-256(domain) 字节决胜，这是人为但稳定且无业务含义的 total order；同 domain 才由 sequence 提供实际先后。随机 UUID 只能用于去重或最后的确定性 tie-break，不能当时间。

协议有 WhatsApp 原始时间就用原始时间，没有时查询类用 queryStartedAt；只能用本机 Now() 的兼容事件标记低置信来源，不能覆盖已有的显式进退群 / 角色事实。Web 和 Android 节点必须 NTP 对时并监控时钟偏差。

### 7.2 来源优先级

先应用字段级 admission：

- MIGRATION_UNKNOWN、无 generation 的 v1 Now()、历史缓存属于 FILL_ONLY，只允许写 UNOBSERVED 字段。
- unresolved invite 的公开 preview 只写 I.preview；绑定 G 时最多以低 tier 填 P 的空字段，之后不覆盖 P。
- P 的 subject/description/avatar/settings：公开 preview、历史缓存和 account SUMMARY 只 FILL_ONLY / 触发 refresh；实时 metadata、显式事件和命令回读属于 AUTHORITATIVE_RECONCILIATION，按事实时间更新。
- P.member_count：明确 live SUMMARY、完整成员快照和命令回读都可作为当前观察，但历史 health / 无时间 preview 只 fill-null。
- M.presence 只有显式 ADD/JOIN/LEAVE/REMOVE 或完整权威快照可覆盖；partial/点查不得写缺失。live account SUMMARY 的 self `admin` 只在 role=UNKNOWN / 同低 tier 时写 MEMBER 或 ADMIN_OR_OWNER，绝不能覆盖 exact ADMIN/OWNER；完整 metadata、明确角色事件或命令后回读才能写/覆盖 exact role，冲突时触发刷新。
- I.validity 只有明确成功校验、INVITE_REVOKED/INVALID 信号可写；网络、离线、无权限只写 check attempt。

通过 admission 后，sourcePriority 只在 observedAt 与 authorityTier 相等时生效：

| 事实族 | 从低到高 |
|---|---|
| profile | 邀请公开预览 < 账号轻量群快照 < 普通 metadata < 显式群事件 < 命令后同账号回读 |
| participant presence | 不完整点查 < 完整快照 < add/join 显式事件 < leave/remove 显式事件 |
| participant role | 账号轻量信息 < 完整 metadata < promote/demote 显式事件 < 命令后回读 |
| invite | 公开预览 < metadata 返回 code < 主动查询当前 code < invite_link_changed 显式事件 |
| group status | 普通可见/metadata 成功 < 明确恢复事件 < suspended/terminated 明确信号 |

SUSPENDED / TERMINATED 是终态保护：普通“仍能看到群”不能恢复，只有明确的恢复信号或人工确认流程可以改回正常。

### 7.3 字段清空

- payload 字段缺失：不改。
- 字段存在且为 null：仍不自动清空，除非 fieldMask 明确包含该字段。
- 显式 clear：写 null / 0，并推进该字段版本。
- 老协议没有 fieldMask 时只能做非空补充，不能执行破坏性清空。

### 7.4 账号完整群快照事务

下面的锁规则是现网 MySQL 8.4 / InnoDB REPEATABLE READ 已经用并发测试验证过的正确性约束，不是可选的性能建议，实施时必须原样保留：

> 先用普通一致性读区分存量/新增，再按表和唯一键全局排序写入。RR 下禁止对缺失键先 UPDATE，否则 next-key/gap 锁会与后续 INSERT 的插入意向锁形成 supremum 死锁。

具体落库约束只有以下几条：

- 输入先在内存中规范化、去重并排序；对可能不存在且本事务需要插入的业务唯一键，先做不带 `FOR UPDATE` 的批量普通一致性读，一次区分 existing / missing。
- 按第 7.8 节的表顺序写，表内按本次 DML 实际使用的唯一键升序写；未分配 ID 的 G 必须按 `(tenant_id, group_jid)` 排序，不得假定已有 groupId。
- existing 集合才允许走 UPDATE；missing 集合直接走按键排序的 multi-row INSERT / IODKU。禁止“逐群 UPDATE，affectedRows=0 再 INSERT”，也禁止在 Java 循环里交错调用多张表的 Mapper / Service。普通读后发生的并发插入由同一排序的 IODKU / duplicate-key 重读收敛，不得改变锁序。
- 为满足批量预算，可以把 existing+missing 按完整唯一键排成一条 multi-row IODKU：existing 命中 UPDATE 分支，missing 直接 INSERT，仍然不存在“先 UPDATE 缺失键”。不得先把 existing 整批写完、再写 missing 而破坏全键序；这种合并 IODKU 必须在 MySQL 8.4 RR 的 existing/missing 交叉测试中证明无 supremum 死锁，不能只凭 SQL 外形认定安全。
- 预分类必须用上述普通一致性读；但 missing 插入后需给下游引用的 AUTO_INCREMENT ID 不得再用旧 RR read view 解析。G.id 由第 16 节预算的第 4 条 current/locking bulk read 解析；新 self M.participant_id 由第 8 条 `B INSERT ... SELECT M` 对 M 完整自然唯一键的 current/locking source lookup 一并解析，不得另加第 11 条普通 M 查询。普通 account SUMMARY 快照不涉及 I；其他事件路径的新 I.id 同样必须用 current read，但按自己的 SQL 预算验收。
- 完整快照的缺失关系先普通读出候选主键，再按主键升序做定点 version-CAS UPDATE；禁止一条 account-wide `UPDATE ... NOT IN (...)` 在 RR 下扫范围并持有 gap 锁。
- 只对 MySQL deadlock / lock timeout 用同一稳定 eventId 有限退避重试；重试是最后保险，不能代替普通读分类和全局锁序。

一个显式完整 account.groups_reported 必须在同一事务中：

1. 按 account→S 顺序锁定 account 和 account_group_sync_state，并校验 bindingGeneration。
2. 用 queryStartedAt、sourceSequence、sourceEventId、eventId 生成 incoming fact key，只与 S.last_complete_fact_version_key 比较新旧；snapshotId 只用于批次身份和幂等，不能按字符串或随机 UUID 大小判断时间。completedAt / publishedAt 只用于耗时和延迟，避免“更早开始但更晚完成”的查询覆盖新快照。
3. 为每个有效 group_jid upsert wa_group。
4. 把轻量群资料按字段版本归并到 profile。
5. 为该账号的 PN 创建或解析 participant；FULL_ACCOUNT_SET 可以按逐行 version CAS 确认 self presence=IN_GROUP，只有 payload 明确携带 self role 且 fieldMask 包含 role 时才更新角色。当前 Web SUMMARY 实际只有 groupJid/subject，角色缺失绝不能被解释为 MEMBER。
6. 只有 reportScope=FULL_ACCOUNT_SET、complete=true 且 skippedCount=0，才处理本账号此前存在但本批未出现的 self participant；每行都执行 `existing presence_version_key < incoming snapshot fact key` 的 CAS 后才改 SNAPSHOT_ABSENT。快照查询开始后已提交的 ADD/JOIN/REMOVE 事件必须胜出，不能只 CAS S 头后无条件批量 mark missing。全部 M 的 present/absent 写入必须在 B 之前完成，不得持有 B 锁后反向再取 M 锁。
7. upsert binding，并写同一个 snapshotId 到 B.last_complete_snapshot_id。
8. 推进 sync_state 的完整快照头。
9. 如果 baseline_state=PENDING，本批同时完成 baseline；即使 groups=[] 也写 CAPTURED。
10. 在同一事务用一条 multi-row SQL 写 `group_snapshot_effect_outbox`，只保存本次账号快照实际需要的 metadata / immediate-marketing intent；唯一键固定为 `(event_id,effect_type,account_id,group_id)`。事务提交后 worker 才分别批写现有 metadata task、marketing send attempt 和必要的 protocol outbox。禁止在快照事务里逐群展开副作用。MIGRATION_*、was=1/NULL、existing B、partial/delta 或时间边界不满足的行在生成参数前直接排除，绝不能写可执行 intent。

这张表是为两个已证实的约束做的最小取舍：保留 G ID current-read 和现有返回契约时，六表快照事实写占 9 条 SQL，直接再写 metadata task 与 marketing attempt 两张物理表最低 11 条；单一 intent 批写才可把快照事务控制在 10 条。它不是通用事件总线，不记录所有 v1/v2 的 EMITTED/SUPPRESSED，不承担 writer epoch 或 Kafka 分区 fencing，也不允许业务查询群当前值。若实施阶段选择取消 G ID 返回、让后续 DML 全部 `INSERT ... SELECT G` 并通过 MySQL RR 门禁，可重新评审直接写两张既有任务表的 10 条方案；未经并发证明不得为了少一张过程表擅自删掉 current-read。

v2 Web 发布器必须显式发送 complete 和 skippedCount。v1 adapter 仅把旧 `snapshotComplete` 映射为 complete、`skippedGroupCount` 映射为 skippedCount；任一缺失一律按不完整处理，删除后端“Web null + 0 等于完整”的推断。

当前实现还有一个更早的顺序问题：PENDING 账号会在判断 complete 之前拍 baseline，Android 或 partial 回报也可能把不完整集合固化。新事务必须先验证完整性和版本，再原子写 baseline header 与 binding 标记。

### 7.5 单群完整成员快照事务

1. 锁定 wa_group_profile 的 member snapshot header。
2. 由 queryStartedAt / 上游事实时间生成 incoming member snapshot fact key，只与 P.member_snapshot_fact_version_key 比较；snapshotId 仅作批次 identity / 幂等，不参与新旧排序。旧 key 或相同 eventId 重放直接 affected rows=0。
3. 逐成员做 PN/LID 身份归并，并分别以 presence/role version CAS；只有 fieldMask 明确携带的维度才更新。
4. 只有上游明确 complete=true 且 skippedCount=0 时，才对本批未出现、当前仍为 IN_GROUP 的成员逐行执行 `presence_version_key < incoming snapshot fact key` CAS 后写 SNAPSHOT_ABSENT。查询切点之后提交的 JOIN/REMOVE 均不得被快照反向覆盖。
5. 同一事务更新 member_count、member_snapshot_at、member_snapshot_id、member_snapshot_fact_version_key、member_snapshot_observer_account_id 和 member_snapshot_participant_count；不存在未定义的 snapshot event_id 镜像。
6. “有界批次”只允许同一事务内的 JDBC batch / 分段 SQL，禁止分段 commit；任一批失败时 header 与成员行全部回滚。数据事务成功后再结算 group_metadata_sync_task；失败则任务保留错误，旧完整快照继续可读。

### 7.6 局部成员事件

- add / join：只更新目标 participant 的 presence=IN_GROUP 和最近进群事实。
- leave / remove：先按 JID 定位或创建 participant，再更新 presence 和最近退出事实；不得先把群或账号关系写成“已加入”。
- promote / demote：只更新 role；没有 presence 证据时保持原 presence。
- owner 变化：更新事件中明确涉及成员的 role，不在 profile 再存 owner_phone；若缺少旧 owner 身份，记录 OWNER_REFRESH_REQUIRED 并调度完整刷新，不能自行挑选或清除其他 OWNER。
- 一个事件有多个参与者时允许共享 eventId；幂等键是 tenant + group + participant + 事实族 + eventId。

### 7.7 统一 v2 事件信封

所有会改变六表的 fact 都使用同一个基础信封，但先分 origin：PROTOCOL_FACT、OPERATOR_FACT、MIGRATION_FACT。协议四元组只对 PROTOCOL_FACT 必填；本地运营和迁移不能伪造 protocol account，只能写各自获授权的事实族。

| 字段 | 要求 |
|---|---|
| version | 明确 v2；Consumer 必须按版本路由 |
| factOrigin | PROTOCOL / OPERATOR / MIGRATION |
| tenantId | 必填；与解析出的 account / resource tenant 交叉校验 |
| eventId | 重试稳定；双发时 v1/v2 必须相同，不能每次 publish 或每个版本随机生成 |
| sourceEventId | WGP2、HistorySync、query、command 的原始事实 ID；双发时 v1/v2 必须相同 |
| observedAt | 事实发生或查询开始时间 |
| completedAt | 查询 / 命令完成时间 |
| publishedAt | 仅观测发布延迟，不参与新旧排序 |
| protocolBackend | PROTOCOL_FACT 必填 WEB / ANDROID；其他 origin 为空 |
| protocolAccountId | PROTOCOL_FACT 必填 |
| armadaAccountId | PROTOCOL_FACT 必填后端 account.id；必须与当前绑定上下文一致 |
| bindingInstanceId | PROTOCOL_FACT 必填且由后端生成；协议不得自行生成 |
| bindingGeneration | PROTOCOL_FACT 必填 Armada 业务绑定代次 |
| onlineAttemptId | 有在线批次时携带，辅助诊断 |
| sequenceDomain | 稳定的 backend:bindingInstance:sourceType；Consumer 必须校验，不能跨 domain 比 sequence |
| sourceSequence | 上游能提供时携带的单调序列 |
| fieldMask | 明确哪些字段被观察、哪些字段被显式清空 |

OPERATOR_FACT 使用 `OPERATOR:{userId}:{actionType}` domain，只能改 G.display/folder/remark/deleted、显式人工恢复等运营字段；普通 MIGRATION_FACT 使用固定 migration run/domain，只能按第 13 节以低 authority 回填 G/P/I/M，不能伪造协议生命周期，也不得以本地 now 冒充 WhatsApp 事实时间。

B/S 迁移只开放两个不走公共事件 DTO 的受限 Reducer command：`MIGRATION_BINDING_INIT` 仅在 Phase 1 预填或 Phase 2M 最终补齐、持有 migration run lease、锁 account+S 且 S 尚未初始化时，按当前 account 绑定生成后端拥有的 instance/generation；`MIGRATION_BASELINE_BACKFILL` 也只在这两个阶段且新 writer 尚未生效时运行，按账号锁住 account+S，对 `旧 account_group_membership.group_jid UNION 经第 10.3 节确认的 state=2 真实 baseline JSON group_jid` 先解析 / 创建 self M，再逐项 FILL_ONLY upsert B；baseline-only JID 也必须落 B，不能因已经退出、没有 membership 行而丢失历史排除证据。state=1/3、WATERMARK_ONLY 和 AMBIGUOUS_EMPTY_BASELINE 的 JSON 绝不进入该 union。若另行批准 Phase 2A/2B，复用相同 command，不新增第三种 writer。

该 command 有且只有两个 admission mode。CURRENT：account 当前有 binding、四元组同时等于 migration ledger 为旧证据固定的 INIT instance/generation，且 token 未被 lifecycle invalidate；才可写该 current lifecycle。只有第 10.3 节判定为 legacy state=CAPTURED(2) + 合法真实 baseline row 时，才把 filter_enabled=1、PENDING/NONE 原子转 CAPTURED/LEGACY_UNKNOWN；legacy state=PENDING(1) 保持 PENDING，state=DISABLED(3) 保持 active instance + baseline_filter_enabled=0 + DISABLED/NONE，二者的 current membership B.was_in_initial_baseline 都为 NULL。LEGACY_RETIRED：旧 relation/baseline token 已绑定到历史/已推进 lifecycle，只能写 token 指定的旧 instance/generation 并令 B.retired_at 非 NULL，不得改变 S；初始即未绑定或软删的账号使用 `LR:` + Base32(SHA-256(tenantId,accountId)) 的确定性 synthetic binding_instance_id（总长不超过 64）、generation=0。retired_at 取可信解绑/删除时间、没有则 0，synthetic / old instance 绝不写成 S.current，只为租户历史 EXISTS 和旧任务证据保留 retired B，协议 fact 永远不能命中。无旧 baseline 的启用策略活跃账号保持 PENDING/NONE 等待 v2 显式完整快照；无 PN/账号身份而无法建立 self M 的行进入冲突门禁，不能丢弃。

该 command 的 B admission 精确限定为：identity 六列 tenant_id/account_id/group_id/participant_id/binding_instance_id/binding_generation；was_in_initial_baseline 只有第 10.3 节判定为 state=2 的合法真实 baseline JSON 明确列出时才写 1，state=1/3 或 watermark-only row 一律 NULL，绝不迁 0；baseline_subject_snapshot / baseline_captured_at 只取上述真实 baseline 证据；first_observed_at 取 membership.created_at、status_updated_at、joined_at、last_seen_at、真实 baseline captured time 中最早的可信非空值，last_observed_at 取其中最晚值，完全无时间证据时两者写 0；last_observed_version_key 使用对应事实时间的固定低 migration FactVersion，无证据为 `0x00`；last_complete_snapshot_id 固定 NULL；本期 legacy migration 不存在任何合法 writer 可把 was_in_initial_baseline 写 0，因此 first_post_control_observed_at / version key **一律** NULL/`0x00`；CURRENT retired_at 写 NULL，LEGACY_RETIRED 按上段写非 NULL。created_at/updated_at 可记录 migration row audit time，但绝不能进入 FactVersion。真实旧 baseline header 的 captured_at 缺失时写 0，snapshotId/count 为 NULL，baseline_fact_version_key 精确写单字节 `0x01`；这个 0 是“无可信时间”的 sentinel。若以后找到可迁 was=0 的新证据源，必须作为新设计单独评审，不在本迁移留人工旁路。

两个受限 command 都记录 migrationRunId/source watermark，不能推进已由实时 writer 接管的 generation、不能写 EXPLICIT_COMPLETE、不能改 M presence/role，已有实时 B 字段也不能被 migration 覆盖；重复执行 affected rows=0。这样迁移仍统一经过 Reducer admission，但不会把迁移初始化伪装成协议事实。

effect 参数生成在任何任务/outbox 写入之前先按 origin 拦截：MIGRATION_FACT、MIGRATION_BINDING_INIT 和 MIGRATION_BASELINE_BACKFILL 不得生成 `IMMEDIATE_MARKETING` intent，也不得 reserve marketing task/send attempt/protocol outbox、不得 wake worker；拦截数只记指标/迁移日志。这是执行前禁止，不能依赖迁移完成后的 count 门禁才发现误发。

account.groups_reported.v2 的 wire contract 精确使用 snapshotId、reportScope=FULL_ACCOUNT_SET/DELTA、detailLevel=SUMMARY/FULL_METADATA、complete、skippedCount、queryStartedAt、completedAt、groups；v2 DTO / JSON 禁止再出现 snapshotVersion、snapshotComplete、skippedGroupCount 三套别名。集合覆盖范围与单群资料丰富度必须分开：轻量 SUMMARY 仍可以是账号全部群的完整集合；只有 FULL_ACCOUNT_SET + complete=true + skippedCount=0 才能判缺失和拍 baseline。groups=[] 必须能明确区分“完整空集合”和“不完整 / 查询失败”。v1 adapter 是唯一允许认识旧字段名的边界：`snapshotVersion→snapshotId`、`snapshotComplete→complete`、`skippedGroupCount→skippedCount`；缺失值不补猜。

成员事件拆分语义：

- participant_presence_changed：ADD/JOIN/LEAVE/REMOVE，只写 presence。
- participant_role_changed：PROMOTE/DEMOTE/OWNER_CHANGED，只写 role。
- full_group_members_snapshot：明确 complete/skippedCount/snapshotId，同时提供 participantJid、pnJid、lidJid、phone。

邀请事件必须允许 action=REVOKED 且 newCode=null；不能因为 DTO 强制非空 code 而无法表达撤销。

### 7.8 所有写路径必须进同一个 Reducer

- Kafka Web 事件、Kafka Android 事件、HTTP metadata 回读、命令结果确认、定时刷新和迁移 backfill 都调用同一 GroupFactReducer。
- Controller、任务 Worker 和协议 Adapter 不得直接更新 profile / invite / participant。
- 多 topic、不同 account key 和多后端之间没有群级全局顺序，数据库 CAS 才是最终一致性边界。
- 单 JVM synchronized 不能代替数据库行锁 / CAS；多实例必须得到同一结果。

跨聚合事务统一锁顺序为 `account → S → G → P → I → M → B → durable effect → legacy projection`；不涉及某层时跳过但不得反向取锁。G 始终按 `(tenant_id,group_jid)` 升序，解析出 ID 后的 P/I/M/B 按 groupId 及各表实际命中的唯一键升序；新 M 也不得用尚未分配的 participantId 当排序依据。PN/LID 合并先锁较小 participantId 再锁较大 ID，invite 更新固定 P→I。输入 ID 必须先去重排序；只对数据库 deadlock / lock timeout 使用同一稳定 eventId 做有限次数退避重试，并记录重试耗尽指标，业务校验失败不重试。

## 8. 真实群和邀请链接的 API / ID 边界

当前 /api/group-links 同时承担“群组列表”和“导入链接池”，导致 group_link_id 在不同任务中语义不一。目标接口分开：

| 资源 | 目标 ID | 典型接口 |
|---|---|---|
| 真实群 | groupId = wa_group.id | /api/groups、/api/groups/{id}/detail、设置、成员操作 |
| 邀请链接 | inviteId = wa_group_invite.id | /api/group-invites、导入、迁移分组、链接预检 |
| 导入批次 | batchId | 继续使用 group_link_import_batch / detail 接口 |

成员列表返回 participantId、pnJid、lidJid、maskedPhone 和 capability；canonical 升降权 / 踢人接口提交 participantId，不让前端选择 PN/LID。协议 Adapter 在执行时基于 backend 选可用 alias，命令结果仍同时回传 participantId 和实际 targetJid 便于审计。

兼容期仍可保留 /api/group-links 路由，但 Controller 必须按具体动作转到 GroupQueryService 或 GroupInviteService，内部禁止再存在一个同时代表两种实体的 GroupLink entity。

迁移 ID 规则：

- 旧行能解析 group_jid：JID 候选必须汇总 wa://、preview.group_jid、account_group_membership、member snapshot/cache/state 以及活跃任务/结果冻结的 groupJid；同一 legacy ID 只有一个规范 JID才可自动归并，多 JID 进入 CONFLICT。随后同租户同规范 JID 的全部旧行成组，wa_group.id 确定性沿用最小 legacy group_link.id；其余 legacy ID 全部映射到该 canonical groupId，禁止创建重复群。
- 旧行有真实 chat.whatsapp.com code：同租户、大小写精确 invite_code 的最小 legacy group_link.id 确定性作为 wa_group_invite.id；两个目标表可以有相同数值 ID，它们是不同类型。
- 旧 wa://group/{jid} 只生成 wa_group，不生成 invite。
- 旧未解析真实邀请只生成 wa_group_invite，不生成 wa_group。
- 所有保留任务表必须按业务语义把 group_link_id 改成 group_id 或 group_invite_id；不允许靠“数字刚好相同”永久兼容。

同一 JID 的旧行在 folder、remark、deleted_at 或 origin 上不一致时，ID 仍可确定，但业务值必须进入冲突报告：全为 active 才自动迁 active，全为 deleted 才自动迁 deleted；active/deleted 混合不得用 `MIN` / `MAX` 猜运营意图。first_seen_source 取有事实时间支持的最早可靠来源，而不是最后一次写入来源。

兼容期不新增第七张权威表，而是在待删除的旧 group_link 上临时增加 canonical_group_id、canonical_invite_id、legacy_semantic_kind、legacy_group_primary、legacy_invite_primary 五个普通列，并增加 nullable generated `canonical_primary_group_id = CASE WHEN legacy_group_primary=1 THEN canonical_group_id ELSE NULL END` 与 `canonical_primary_invite_id = CASE WHEN legacy_invite_primary=1 THEN canonical_invite_id ELSE NULL END`，供 LegacyGroupLinkResolver 使用；同时增加 `idx_group_link_canonical_group(tenant_id, canonical_group_id, deleted_at, id)`、`idx_group_link_canonical_invite(tenant_id, canonical_invite_id, deleted_at, id)` 和唯一键 `(tenant_id, canonical_primary_group_id)`、`(tenant_id, canonical_primary_invite_id)`，避免每次投影扫描全表并保证一个 G / I 各只有一个 v1 主 ID：

| legacy_semantic_kind | canonical_group_id | canonical_invite_id | 语义 |
|---|---:|---:|---|
| SYNTHETIC_GROUP | 必填 | 可空 | 旧 wa://group/{jid} 只代表真实群；preview 有真实 code 时可同时指向 invite |
| INVITE_UNRESOLVED | 空 | 必填 | 真实邀请尚未拿到 JID |
| INVITE_RESOLVED | 必填 | 必填 | 同一旧行同时包含真实群和邀请 |
| GROUP_ONLY | 必填 | 空 | 已知 JID，但没有任何真实邀请 code |
| CONFLICT | 可空 | 可空 | JID/code 冲突，切流前必须人工或刷新解决 |

CONFLICT 行不生成 / 更新 G，也不进入 canonical 列表投影；能独立证明的 invite code 可暂存 I，但不能据此绑定 group_id 或 current pointer。凡 active 行或被保留任务、outbox、DLT、Android JSONL 引用的软删行出现 active/deleted、JID/code、tenant 或 designated-primary 冲突，Phase 2B 前必须降为 0，或者具备逐行、带负责人和审计理由的签字处置；一份“冲突报告已生成”不等于可以切 writer。

不是只分类 active 行：每个 legacy group_link 行都要分类，至少所有被保留表、outbox、DLT 或历史任务引用的软删行必须覆盖。Resolver 入参固定为 tenantId + legacyGroupLinkId + expectedResourceType，任何跨租户裸 ID 查询都拒绝。

G designated primary 只在 identity foundation 建立一次，候选排序固定为：active SYNTHETIC_GROUP/GROUP_ONLY → active INVITE_RESOLVED → 已删除 SYNTHETIC_GROUP/GROUP_ONLY → 已删除 INVITE_RESOLVED，同档取最小 id。新发现且没有 legacy 行的 G，由 Adapter 在锁定 G 后幂等插入一条 wa:// 兼容行并设 group primary；该假 URL 永不进入 I。I designated primary 则只从拥有大小写精确真实 `chat.whatsapp.com/{code}` 的 INVITE_UNRESOLVED/INVITE_RESOLVED 行选择，active 优先、同档最小 id；绝不能把 I.id 当成 group_link.id，也不能用 wa:// 行代替邀请 alias。

Phase 2B 后，每个会被 v1 import/select API 看见、或将写入仍需 `group_link_id` 的任务/结果的新增 I，都必须在“创建/收编 I + import detail/任务”同一事务中由 `InviteLegacyAliasService` 锁 I 并幂等取得 invite primary：已有真实 URL alias 就复用，否则插入真实 URL 的 legacy 行、填 canonical_invite_id/semantic kind 并置 `legacy_invite_primary=1`。若同一旧 INVITE_RESOLVED 行会同时成为 group primary，identity foundation 必须先创建/复用该 G 的 wa:// synthetic alias 并把 group primary 移到 synthetic 行，使 group primary 与 invite primary 物理分离；这是邀请隐藏和群归档能够独立回滚的前提。新任务同时写 `group_invite_id + invite primary legacy id`，新 G 仍写 `group_id + group primary legacy id`。

两类 primary 选定后都不因普通刷新漂移。group primary 的 legacy deleted 投影只跟随 G.deleted_at；invite primary 只跟随 I.pool_hidden_at/系统退役：隐藏时保留 mapping 和历史引用但退出 v1 invite 选择，ADOPTED 时复用同一行并恢复可见；I 解析到 G 或 G 被归档都不删除 invite primary。Phase 2B 前的 rollback-compatible release 必须把“按任务冻结 legacy ID 取输入”与“UI active invite 选择”拆开：前者以 tenant+exact ID 允许读取已隐藏 alias 的冻结 code，后者始终按 I.pool_hidden_at 过滤；否则 hidden current invite 被活跃任务引用时，旧 binary 回滚会因 deleted_at 查不到。invite GC 也必须等待它不是 P.current、无活跃/历史保留引用、v1 回滚窗结束且 compat 映射已归档。Phase 6 若仍有历史引用，只把两种 primary 角色搬到最小只读 compat map，不保留 link/profile/health 当前值。

兼容 ID 分阶段：Phase 5 行集折叠前，v1 列表每条 alias 的 `id` 必须仍是该 legacy row 自身，保证前端 row-key 唯一；响应另带 groupId、canonicalGroupPrimaryId 和 resourceType。Resolver 接受任一 typed=GROUP alias 并归到同一 G，新任务 / 新兼容引用只生成 primary ID，历史任务继续保留原 alias。用户在 Phase 2B 前批准 duplicate collapse 后，folder/delete/remark 等本地群动作从任一 alias 调用都明确作用于同一个 G，并由投影同步全部 aliases；这是已批准语义，不再假装逐 alias 独立。Phase 5 typed 行集真正折成一个 GROUP 时才只返回一行，resourceKey 使用 `GROUP:{groupId}`；不得在逐 legacy row 阶段把多个 id 都改成 primary。

所有保留任务 / 结果行在不改原 legacy 列的前提下回填 nullable typed group_id / group_invite_id；新写双填旧兼容列和 typed 列，直到旧二进制回滚窗口结束。每张表在加 v2 唯一键前先按 canonical key 做 `GROUP BY ... HAVING COUNT(*) > 1` 冲突 dry-run；当前态定义 winner/合并，历史证据保留 legacy key，新唯一键只约束 semantics_version=v2 的新写。Phase 6 只有在逐表 `legacy id 非空且所需 typed id 为空=0`、活跃 outbox / v1 topic / DLT / Android JSONL 均排空后，才能删除 group_link 与通用 LegacyGroupLinkResolver。若受法定历史留存或冲突数据影响无法达到 0，就把上述映射列抽成最小 `legacy_group_link_compat` 非权威映射表，并仅保留只读 `HistoryCompatResolver`：它只服务已结束历史详情，禁止参与新任务、当前资格、列表和任何写入；兼容表不保存群当前事实，不计入六表。

### 8.1 拆分后的删除、分组和导入语义

旧模型的一次“删除”会误伤两类资源，拆分后必须明确：

- 删除真实群：只软删除 wa_group，使其退出新任务候选和群列表；participant、binding、invite 和历史任务证据保留。Reducer 可以继续接收事实用于对账，但永远不能清 deleted_at。
- 恢复真实群：只能由明确的运营恢复接口清 deleted_at；恢复后读取期间积累的最新事实。
- 从邀请池删除：只把 wa_group_invite.pool_hidden_at 写入并清 label_id；P.current_invite_id、validity 和协议事实不变，历史任务冻结 URL 不变。真正撤销当前 code 必须走 WhatsApp revoke 并等待协议确认，不能把本地隐藏伪装成 REVOKED。
- 删除邀请链接分组：选择单一事务语义，不留“事务/作业”二义。锁 label 后先校验影响行数不超过经压测阈值，在同一事务把该 label 当前 I 清 label_id + 写 pool_hidden_at、软删 label 与其 import batch；group_link_import_detail 没有 deleted_at，保持不可变审计，只通过 batch.deleted_at 从普通查询隐藏。任一步失败全部回滚；超过阈值则拒绝并另行设计有显式 DELETING 状态的分片作业，不能临时半删。不软删 canonical invite，也绝不级联删除 canonical wa_group。若产品选择“仅解除分组不隐藏”，也必须另做明确动作。因为 P 可以继续指向隐藏 invite，不会产生悬空 current pointer。
- 删除群组分组 folder：wa_group.folder_id 清空为未分组，不删除群或邀请。
- 重复导入同一 code：活跃且已归组写 result=FAILED/failReason=DUPLICATE detail，不改 pool/label；hidden 或未归组写 result=SUCCESS/successType=ADOPTED detail并恢复到本次 label。两者都复用同一 invite，不得创建第二个群。
- invite 由未解析变已解析：若 JID 对应 G 已存在，只绑定关系，不覆盖既有 first_seen_source、folder、display、remark、deleted_at，并对不同本地 display/remark 给出冲突提示；若本次首次新建 G，可用 I.display_name/display_avatar_url/remark 初始化本地字段，first_seen_source=邀请解析。公开 preview 只能作为 P 的低优先级资料候选。
- group 列表删除与 invite 池删除是两个权限和审计动作，v2 API 不提供一个同时删除两者的模糊 endpoint。

这里有一个需要产品确认但不阻塞模型的兼容点：当前 `GroupLinkLabelServiceImpl.batchDelete` 会级联软删 label 下所有 group_link，里面可能已经是真实群。方案 A 推荐修正为“隐藏导入资源、不删除真实群”；如果仍需要批量隐藏这些群，应提供显式“归档已解析群”选项并在 UI 二次确认，不能通过数据级联暗中完成。批处理必须逐项复用上述状态机并校验所有 current pointer，不能直接一条 UPDATE 把被 P 引用的 I 写 deleted_at。

## 9. 列表查询设计

### 9.1 仅删除展开行，不会自动简化 SQL

前端已经移除行展开区，但展开区字段与当前生产消费关系如下：

| 字段 | 删除展开区后的情况 |
|---|---|
| groupJid | 主表“群 JID”列和详情抽屉仍使用，不能删除 |
| ownerPhone | 不再直接展示，但仍被关键词和 creatorPhone 映射使用 |
| membershipStateLabel | 页面不再展示；后端仍公开 membershipState 查询 |
| lastPreviewAt | 页面无生产消费，可先从列表响应裁掉 |
| lastCheckAt | 页面无生产消费，可先从列表响应裁掉 |
| lastHealthError | 页面无生产消费，可先从列表响应裁掉 |
| remark | 群名称单元格和详情编辑仍使用 |

当前 GroupLinkMapper 的 FROM 固定连接 group_link、import_batch、preview、health、folder、country、全量管理员聚合、可用管理员聚合和 metadata task。删 Vue 模板或少 SELECT 三列不会让这些 JOIN 自动消失。

### 9.2 方案 A 的默认查询：page-first + 按页 enrichment

本设计默认不擅自删除现有列和筛选，先保留旧 GET /api/group-links 的响应能力：

迁移兼容期的 v1 Adapter 先从 active legacy group_link + canonical mapping 起步，因此逐条保留旧 row ID、I-only 行和行级来源；不再连接旧 preview/health/member 聚合。最终 typed 方案 A 列表适配层的行集合明确定义为 `active G UNION active、未隐藏且 group_id IS NULL 的 I-only`，并返回 resourceType + groupId/inviteId；约 87 条未解析邀请不会因为 G-only 查询消失。多个 legacy resolved 行归并为一个 G 是有意去重，不得伪装成“逐页完全相等”：该 canonical mutation/最终行集语义必须在 Phase 2B 前批准；未批准只能停在 Phase 2A，不能切 writer。

1. count 查询只连接当前被筛选条件真正需要的表，并对 GROUP / INVITE 两个 branch 使用同一筛选适用矩阵。
2. page-id 查询应用全部筛选和稳定排序，先 LIMIT 得到 10 / 20 个 `(resourceType, resourceId)`。
3. GROUP 基础批查仅用 wa_group + wa_group_profile + current wa_group_invite + group_folder；I-only 基础批查只用 invite + label。
4. 仅对本页 GROUP IDs 批查“受控管理员号码”。
5. 仅对本页 GROUP IDs 批查可用管理员和账号状态。
6. 仅对本页 GROUP IDs 批查历史/上控标签、owner 国家等 enrichment；role 查询全部带 presence + membership epoch 条件。
7. metadataSyncStatus/SyncedAt/Error 只按本页 GROUP IDs 批查 group_metadata_sync_task，默认 count/page-id 不连接任务表。
8. Service 按 page-id 原顺序合并结果，禁止内存分页或页后国家/大洲过滤。

sourceFileName 不进入六表，但兼容层与最终 typed 行必须分层。Phase 5 前的 v1 alias Adapter 仍按“本 legacy row 的 import_batch_id / label_id（或其 canonical_invite_id 对应的本行收编记录）”输出和筛选 sourceFileName/labelId，不能把同 G 的多个 aliases 提前改成同一个值；这些列只是待删除旧行的兼容展示。用户批准并切到 collapsed typed GROUP 后，才使用下述唯一标量 `listAdoption`。

group_link_import_detail 增加 typed group_invite_id 和索引 `(tenant_id, group_invite_id, result, created_at, id)`；typed 候选必须 result=SUCCESS、batch 未删除。GROUP branch 在绑定该 G 的全部 I 的候选 detail 中按 `detail.created_at DESC, detail.id DESC` 只取第一条；I-only branch 只在本 I 的候选中按同序取第一条。sourceFileName 来自这条 detail 的 batch，labelId 则读取“这条 detail 所属 invite 当前的 I.label_id”，而不是任取该群另一个 invite 的 label。移动 invite 分组会改变 labelId，但不会改写其历史 sourceFileName；result=FAILED/failReason=DUPLICATE 不会抢占候选，隐藏后的 SUCCESS+ADOPTED 或其他新成功才按同一排序切换。sourceFileName 与 labelId 的筛选、count、page-id 和 enrichment 必须复用同一个 listAdoption CTE / anti-later 定义，禁止展示选中 A 邀请、label 筛选却命中 B 邀请。

现有前端列和筛选的目标来源固定如下，I-only 不适用的群内事实返回 null/0，不能随手连接一条 resolved group：

| v1 / 方案 A 字段 | GROUP branch | I-only branch |
|---|---|---|
| id / typed IDs | v1 alias 阶段=原 legacy id + canonicalGroupPrimaryId；typed=groupId/resourceKey | v1=原 legacy id；typed=inviteId/resourceKey |
| groupName / avatar / remark | G.display fallback P.subject/avatar；G.remark | I.display fallback preview；I.remark |
| groupJid | G.group_jid | null |
| url / currentInviteUrl | P.current pointer 对应 I code；无 pointer 为 null | 本 I.code |
| memberCount | P.member_count | I.preview_member_count |
| status | §6.1 的 group/current-invite/probe 组合 | I.validity + check attempt，不读取 P.group_status |
| sourceFileName / labelId | v1 alias=本旧行来源；typed GROUP=listAdoption batch / selected I 当前 label | v1 alias=本旧行来源；typed I-only=本 I listAdoption / 当前 label |
| origin / membershipState | G.first_seen_source；§6.5 派生 | I.first_seen_source；固定 TARGET |
| historical / postControl | B 生命周期 EXISTS | false / false |
| folder | G.folder | null；invite label 是不同维度 |
| owner / country / continent / age | current exact OWNER M + country；P.wa_created_at | I.preview_owner_phone/country；I.preview_wa_created_at |
| admin / availableAdmin | 仅受控 M/B/account enrichment | null / 0 |
| metadataSync* | 本页 G 对应 metadata task | null |
| syncProtocolMask | deprecated，兼容期旧值透传 | deprecated，兼容期旧值透传 |

成员数、年龄、国家、状态等筛选对两个 branch 分别使用上表来源再 UNION；若某 branch 值未知则不命中已指定范围。GROUP 的 labelId 只测试 listAdoption.selected_invite_id 对应的当前 label，不能 `EXISTS` 任意关联 I；其他历史 invite / label 在邀请池接口查看。labelId 与 folderId 是两个独立筛选，不能继续因 group_link 混表而互换。

高级筛选改用有针对性且可命中索引的 EXISTS：

- 管理员关键字：EXISTS participant + active controlled account，限定 IN_GROUP、role epoch 相等、ADMIN/OWNER/ADMIN_OR_OWNER + phone，保持方案 A 的现网隐私范围。
- 可用管理员：EXISTS S 当前 instance/generation 的 binding → effective-role participant → active account → account_state。
- 历史/上控：EXISTS binding。
- 群龄/成员数/群状态：直接命中 profile 索引。
- 名称/JID/当前邀请：group/profile/current invite；I-only branch 搜当前 invite 自身，不搜索已解析群的历史 invite。
- 国家/大洲：EXISTS 唯一 exact OWNER participant，使用 phone_country_iso2 / country.continent_code 且带 role epoch；OWNER_CONFLICT 不命中确定国家。

这样默认 count 和 page 不再无条件扫描约 45 万成员并做全租户 GROUP BY。每页会增加几条小批量查询，但复杂度受 page size 控制。

### 9.3 GROUP / I-only 操作 capability

typed 行必须返回全局稳定 `resourceKey=GROUP:{groupId}` 或 `INVITE:{inviteId}`，不能只拿数值 ID 做 row-key，因为 G/I 允许同号；v1 alias 阶段使用 `resourceKey=LEGACY:{legacyGroupLinkId}`。同时返回 capabilities 对象及禁用 reasonCode，前端不得再对所有行无条件显示同一组动作：

capability 不是前端猜测值。GROUP 的 `canStartJoinTask` 精确要求 G 未归档、P.group_status 不是 SUSPENDED/TERMINATED、P.current_invite_id 非空且该 I 未明确 INVALID；禁用原因按 `GROUP_ARCHIVED → GROUP_TERMINAL → NO_CURRENT_INVITE → INVITE_INVALID` 优先返回。I-only 只要求 I 可见且未明确 INVALID。GROUP 的 `canRefreshCurrentInvite` 延续现网“BANNED/UNAVAILABLE 不执行”语义：G 未归档、非 SUSPENDED/TERMINATED、最近 group probe 不是压过 success 的 GROUP_QUERY_TRANSIENT，并存在当前可执行管理员；原因按 `GROUP_ARCHIVED → GROUP_TERMINAL → GROUP_PROBE_UNAVAILABLE → NO_ELIGIBLE_ADMIN` 返回。LINK_INVALID 不阻止刷新链接，因为修复失效 code 正是该动作的用途。Controller 创建任务前必须在锁定当前 G/P/I/M/B/S 后重复同一谓词；列表返回 capability 不能充当授权或并发控制。

| 当前 UI 动作 | GROUP | I-only | 批量规则 |
|---|---|---|---|
| 群组信息 | `canViewGroupDetail`；打开 `/api/groups/{groupId}` 与成员抽屉 | `canViewInvitePreview`；文案“邀请预览”，走 `/api/group-invites/{inviteId}/preview-detail`，无成员动作 | 不批量 |
| 进群任务 | `canStartJoinTask` 按上面的群终态 + current invite 谓词；提交 groupId + currentInviteId | 使用本 inviteId/code，明确 INVALID 时禁用 | 任务 DTO 传 typed resource，不再只传 row.id/url |
| 批量分组 | `canAssignFolder`，只改 G.folder_id | false；invite label 是另一入口 | 选择含 I-only 时整批前端禁用，后端也逐 ID 拒绝，不能静默跳过 |
| 刷新群链接 | `canRefreshCurrentInvite`，按 G 选择可执行管理员并更新 P.current pointer | false；I-only 使用邀请池独立 `canCheckInvite` / precheck，不复用本按钮 | 混合选择整批拒绝；任务 item 使用 group_id |
| 获取最新群信息 | `canRefreshMetadata`，目标 G/P/M | false；I-only 只能走 preview/check | 混合选择整批拒绝；任务 item 使用 group_id |
| 删除 | `canArchiveGroup`，软删 G，文案“归档真实群” | `canHideInvite`，写 I.pool_hidden_at/清 label，文案“从邀请池隐藏” | 混合删除先按类型显示数量/不同后果，再提交显式 `{resourceType,id,action}` item；逐项结果允许部分失败，绝不使用一个无类型 ids 数组猜动作 |

v1 `/api/group-links/{id}/detail` 必须先按 legacy_semantic_kind 路由：INVITE_UNRESOLVED 到 GroupInviteService preview detail，SYNTHETIC_GROUP/GROUP_ONLY/INVITE_RESOLVED 到 GroupQueryService；不能一律强求 groupId。现有 v1 batch endpoint 在兼容期也必须逐 ID typed resolve，folder/metadata/invite-refresh 遇 I-only 返回稳定 `RESOURCE_TYPE_NOT_SUPPORTED`，删除则明确映射 ARCHIVE_GROUP/HIDE_INVITE。新前端在 Phase 2B 前上线 capability 控制、type-specific 确认和 typed task payload；后端始终重复校验 tenant、permission、capability，不能信任按钮是否置灰。

所有批量入口还必须在权限/capability 校验之后、任务 item 或任何外部 effect 创建之前做 canonical 去重，不能只对 legacy 数字 ID 调 `distinct()`。每个输入位置先以 `tenantId + legacyId + expectedResourceType` 解析为 `GROUP:{groupId}` / `INVITE:{inviteId}`；相同 canonical key 只保留请求中第一个位置作为 execution representative，后续 alias 返回 `CANONICAL_DUPLICATE`、representative index 和 canonical key，不算失败、不再创建任务、命令或 effect。响应固定区分 `requestedCount`（原始输入位置数）、`resolvedCount`（成功解析的位置数）、`canonicalCount/totalCount`（唯一工作单元数）和逐输入 `aliasResults`；任务进度 total/success/failure 只统计 canonical work item。folder、metadata、invite refresh 这类 all-or-nothing 批次在任何唯一资源不支持/无权限时整批零写入；typed delete 保持逐项部分结果；营销 selection、拉群候选和导出目标也在写唯一键前按 canonical group 去重。幂等键与 `business_effect_key` 只含 canonical key，不能因另一个 alias 再执行一次。

### 9.4 可选方案 B：用户批准后再瘦前端

如果后续明确同意精简列表，前端从 typed `/api/group-resources` 切到 canonical `GET /api/groups`，默认四表查询只返回：

- groupId、groupJid；
- displayName / subject、remark、displayAvatar / WhatsApp avatar；
- folder；
- memberCount；
- 派生 groupStatus；
- currentInviteUrl；
- 操作 capability。

以下列和筛选可以移到详情或专页，但本设计不视为已获批准：

- 受控管理员号码和关键字；全部观察管理员若获隐私批准另行设计；
- 可用管理员列及筛选；
- owner / 国家 / 大洲 / 群龄；
- 历史群 / 上控后群标签及高级筛选；
- metadata task 状态和错误。

导入链接列表不能改用该四表接口；它必须查询 wa_group_invite，因为未解析邀请没有 group_id。

## 10. 旧字段到新模型的迁移

### 10.1 主表和资料

| 旧字段 / 表 | 目标 | 迁移规则 |
|---|---|---|
| group_link.id | wa_group.id 或 wa_group_invite.id | 按是否有真实 JID / code 分型；两边都存在时两个类型可沿用同一数字 |
| group_link.link_url | wa_group_invite.invite_code | 只迁真实 chat.whatsapp.com code；wa:// 假链接丢弃 |
| group_link.group_name | wa_group.display_name / invite.display_name | 这是 Armada 本地展示名；已解析群进 G，未解析邀请进 I，绝不能当 WhatsApp subject |
| group_link.label_id | wa_group_invite.label_id | 仅链接导入归属 |
| group_link.import_batch_id | 导入 detail / batch | 不进入六表；导入历史已有过程表 |
| group_link.origin | group.first_seen_source / invite.first_seen_source | 按已解析和未解析实体分别迁；以后仅有更早可靠事实可修正，普通后写不可覆盖 |
| group_link.membership_state | 派生 | 从 binding + participant + managed creator 派生，删除列 |
| group_link.is_historical | binding.was_in_initial_baseline | 按账号 baseline 重建；群级旧值只用于异常对账 |
| group_link.is_post_control | binding.first_post_control_observed_at | 按账号事件重建；群级旧值只用于异常对账 |
| group_link.sync_protocol_mask | 删除 | 协议后端属于 account；观察来源由 sync_state / event 记录 |
| group_link.folder_id | wa_group.folder_id | 仅真实群迁移 |
| group_link.remark | group.remark 或 invite.remark | 已解析群进 group；未解析链接进 invite |
| group_link.deleted_at | G.deleted_at 或 I.pool_hidden_at | 真实群运营删除迁 G；邀请池删除迁隐藏投影，不把协议邀请事实退役；混合行进入冲突/分型，事件不复活 G |

展示名与 WhatsApp subject 分离：

- group_name 原样迁 display_name；preview.wa_subject 才迁 profile.subject。两者不同不是数据冲突，而是本地覆盖功能。
- 列表名称固定 `COALESCE(G.display_name, P.subject, G.group_jid)`；本地清空 display_name 后才跟随 WhatsApp subject。
- 旧 preview.avatar_url 同时被“本地资料头像”和“WhatsApp 图片回读”两条路径共用，无法从存量证明来源。为保持当前 UI，已解析群先保守迁 G.display_avatar_url，未解析迁 I.display_avatar_url；P.avatar_url 只由有来源版本的 metadata 回填。列表头像 `COALESCE(G.display_avatar_url, P.avatar_url)`。
- `/profile` 只改 G 的 display 字段和 remark；`/subject`、`/picture` 才发协议命令并在确认 / 回读后写 P。两个接口不得再落同一列。

### 10.2 preview、health 与邀请

| 旧字段 | 目标 | 规则 |
|---|---|---|
| preview.group_jid | wa_group.group_jid | 与 wa:// JID 不同则隔离 |
| preview.invite_code | wa_group_invite.invite_code | 结合 invite_code_observed_at 建版本 |
| preview.wa_subject / description / settings | profile | 仅已解析群；未解析才留 invite.preview |
| preview.avatar_url | G/I 本地展示覆盖；P 需可靠 metadata 重建 | 存量无本地/WA 来源标记，不能直接宣称是 WhatsApp 当前头像 |
| preview.member_size | profile.member_count 候选 | 与其他人数按事实时间比较 |
| preview.owner_phone | participant OWNER 候选 | 只有确认 PN 才迁 |
| preview.group_created_at | profile.wa_created_at | 秒转毫秒 |
| preview.creator_country_* | 删除 | 从 owner phone 派生 |
| preview.metadata_observed_at | profile 字段版本 | 作为各非空 metadata 初始 observedAt |
| health.current_count | profile.member_count 候选 | 用 last_check_at 与其他来源比较 |
| health.health_status=LINK_INVALID | invite.validity_status=INVALID 候选 | 必须存在真实 invite，且只迁有效性，不擅自改 profile.current_invite_id |
| health.is_banned=1 | profile.group_status 候选 | 只有 last_health_error 明确为 CHAT_SUSPENDED / CHAT_TERMINATED 才自动迁；普通 BANNED 隔离核验 |
| health.last_check/error/failure | I.check 或 metadata task probe | 明确链接错误迁 I；群/账号/网络临时错误与调度水位迁非权威 probe task，不能猜时进入冲突报告 |

现有 is_banned 既可能来自群 suspended，也可能来自检测账号 BANNED，不能整列复制成群封禁。

### 10.3 账号关系和 baseline

| 旧字段 / 表 | 目标 | 规则 |
|---|---|---|
| account_group_membership.account_id / group_jid | binding + participant | 用 account.ws_phone 构造确认 PN participant |
| membership.is_admin | participant.role 候选 | 与完整成员/角色事件按时间比较 |
| membership.membership_status | participant.presence 候选 | 按 status_updated_at 和显式 exit 事实比较 |
| membership.joined_at | 不回填 binding.first_post_control_observed_at | **禁止直迁**。本期 legacy command 只能写 was=1/NULL，绝不迁 0，因此该列不存在可达的正向迁移分支；first_post_control_observed_at / version key 固定写 NULL/`0x00` |
| membership.last_seen_at | binding.last_observed_at | 保留 |
| membership.last_exit_* | participant.last_exit_* 候选 | 与 departed_member 合并取可靠较新值 |
| account_group_baseline JSON | binding baseline + sync_state header | 只有下述 state=2 + 真实合法 row 才迁；不能以 row 存在推断已拍 |
| account.group_baseline_state | sync_state.baseline_filter_enabled / baseline_state | state=3 是过滤策略关闭，不代表账号未绑定；迁后从 account 删除 |
| account_group_baseline.last_group_sync_requested_at | sync_state.last_sync_requested_at | 迁值后随 Phase 6 删除 account_group_baseline |

迁移以 account.group_baseline_state 为主值，不能以 account_group_baseline row 是否存在判断 CAPTURED。现有 markGroupSyncRequested 会为没有 baseline row 的 state=2/3 账号插入 `JSON_ARRAY()/count=0/captured_at=requestedAt` 只为保存同步水位，而且后续请求只更新 last_group_sync_requested_at/updated_at；因此时间相等式只能提示 placeholder，不能永久、确定地区分“真实空 baseline”。WATERMARK_ONLY 必须有创建版本、审计/binlog或调用链证据；state=2 的空数组若无正向 provenance 一律记 AMBIGUOUS_EMPTY_BASELINE，人工签字，不能自动当真实空集合。确定性矩阵如下：

现有 `AccountGroupMembershipSnapshotServiceImpl.membershipRow` 每次都把本次 `syncAt` 赋给 `joined_at`；`AccountGroupMembershipMapper.xml` 又会在新行、旧值为 NULL，或退群后再回到在群时改写它。所以该列是“快照首次建行/最近回群观察时间”的混合值，不是 WhatsApp 首次入群事实，也无法单独证明发生在 baseline 之后。迁移 runner 可保留防御断言 `was_in_initial_baseline=0 AND lifecycle_baseline_captured_at>0 AND joined_at>lifecycle_baseline_captured_at`，但本期输入集的 was 只可能是 1/NULL，所以断言命中数必须为 0，实际回填一律 NULL/`0x00`。不允许用 `COALESCE`、行存在性或“JSON 未列出”推导 was=0，也不为假想的手工证据增加第四种 migration writer。

| legacy 状态 | row 证据 | 目标 |
|---|---|---|
| 1 PENDING | 无 row，或可证明 WATERMARK_ONLY | baseline_filter_enabled=1；active binding 为 PENDING/NONE，未绑定为 DISABLED/NONE；只迁 sync watermark |
| 1 PENDING | 非空 / 其他声称 baseline 的 row | STATE_ROW_CONFLICT，人工判断，不能自动拍 baseline |
| 2 CAPTURED | 非空 JSON，或有正向 capture provenance 的空 JSON；且 JSON_TYPE=ARRAY、group_count=JSON_LENGTH、JID 全合法去重、时间非负 | baseline_filter_enabled=1；active CURRENT 写 CAPTURED/LEGACY_UNKNOWN，未绑定/旧代写 LEGACY_RETIRED B |
| 2 CAPTURED | 缺 row、可证明 WATERMARK_ONLY、无 provenance 的空数组、非法 JSON/count/JID | AMBIGUOUS_EMPTY_BASELINE / BASELINE_EVIDENCE_CONFLICT，阻断 writer cut |
| 3 DISABLED | 任意 row / 无 row | baseline_filter_enabled=0；active binding 仍保留 current instance 且 S=DISABLED/NONE，membership B.was=NULL；row 只迁 sync watermark，JSON 不得当 baseline |

state=3 的非空 JSON 进入异常报告并在 drop 前导出 / 签字处置，但不能改写现网“不启用过滤”的行为。active/inactive binding 与 baseline policy 分别对账，禁止把所有 DISABLED 账号塞进 synthetic retired lifecycle。

通过上述矩阵的真实旧 baseline 没有可信 v1 `snapshotComplete` 证据，因此只写 baseline_completeness=LEGACY_UNKNOWN：

- JSON 中明确列出的群可写 was_in_initial_baseline=1，继续保守排除；
- JSON 未列出的既有 binding 保持 NULL，不能断言“不在 baseline”；
- LEGACY_UNKNOWN 不得仅凭集合差异触发“上控后新群立即营销”；
- 迁移/backfill 只记录事实，不创建“上控后新群立即营销” effect intent。即时营销只能由切换后实时接受的、当前 binding 的明确 JOIN/ADD 或 `FULL_ACCOUNT_SET+complete=true+skippedCount=0` 新群确认事实在同一 Reducer 事务中创建，禁止切换后扫描迁移字段补发；
- 是否由运营重新建立可信基线必须单独审批，不能把迁移当天的当前群集合冒充最初上线前 baseline。

### 10.4 成员表簇

| 旧表 | 目标 | 规则 |
|---|---|---|
| whatsapp_group_member_snapshot | participant + profile snapshot header | 同群最新 snapshot_at 归并；重复身份先报告 |
| whatsapp_group_member_cache | profile snapshot header | 与 snapshot 表对账版本、观察账号、subject、announce |
| whatsapp_group_member_state | participant | 旧表只有共享 state_updated_at，不能假装存在两个独立时钟；按下述 source 白名单拆维度 |
| whatsapp_group_member_join_fact | participant.last_join_* | 每成员现有最新事实可无损折叠 |
| whatsapp_group_departed_member | participant.last_exit_* | 每成员现有最新事实可无损折叠 |

`whatsapp_group_member_state` 回填必须检查 state_source / source_event_id：PROMOTE/DEMOTE 只迁 role，不迁 `is_in_group=true`；ADD/JOIN/LEAVE/REMOVE 只迁 presence；只有可证明为 complete 的同批 member snapshot 才同时迁 presence+role。来源不明、旧 handler 可能由角色事件顺带写入的 presence 一律 UNKNOWN / FILL_ONLY，不能据此复活成员。与 join/departed/snapshot 的冲突按各自可证明事实 key 进入 Reducer，不使用迁移 now()。

旧 snapshot 只有在 legacy cache/header 的 `snapshotVersion`、观察账号、完成时间与该批成员行一致，且行数/完成标记可证明整批完整时，才初始化 P.member_snapshot header 并具有 SNAPSHOT_ABSENT 权限；该 legacy 值迁入 member_snapshot_id。缺 header、版本截断、数量不一致的成员行只能作为逐人 FILL_ONLY 观察并进入刷新队列，不能因“表名叫 snapshot”就断言未出现成员已离群。

若业务未来需要完整的 append-only 进退群审计，应另立事件审计需求；当前旧表本身也只保留每成员最近一次，不应假装它们是完整历史。

### 10.5 人数冲突

member_count 初值按可证明的事实时间选择：

1. 被接受的最新完整成员快照计数；
2. health.current_count + last_check_at；
3. preview.member_size + metadata_observed_at / last_preview_at；
4. 无时间来源只能进入待刷新队列，不能用表优先级硬覆盖。

迁移后任何来源都通过 profile.memberCount 的 field_version_keys 归并，不再用 COALESCE(health.current_count, preview.member_size)。

### 10.6 所有旧 group_link_id 不能同一种替换

| 旧引用位置 | 实际语义 | 目标策略 |
|---|---|---|
| group_link_import_detail.group_link_id | 导入邀请；成功后也可能同时已解析群 | 新写 group_invite_id；旧写入经 resolver 取 inviteId |
| group_link_preview / group_link_health | 混合的当前资料投影 | 分字段迁入 P / I，完成切换后删除 |
| account_group_membership.group_link_id | 账号与真实群关系 | 迁入 B.group_id，并关联 M |
| whatsapp_group_member_snapshot.group_link_id | 真实群成员快照 | 迁入 M，快照头进 P |
| group_metadata_sync_task.group_link_id | 真实群当前资料同步目标 | 新增 group_id；回滚窗内新任务用 primary legacy ID + group_id 双填 |
| group_batch_task_item.group_link_id | 真实群批量刷新目标 | 新增 group_id；回滚窗内双填，旧项经 resolver 读取 |
| marketing_task_target / send_attempt.group_link_id | 任务目标 / 实际发送结果快照 | 历史原值不覆盖；新写双填 primary legacy ID + group_id，并继续冻结 group_jid |
| group_creation_marketing_item.group_link_id | 建群成功后的真实群 | 新增 group_id；回滚窗内新成功项双填，历史原值保留 |
| group_pull_marketing_execution.group_link_id | 建群 / 拉群成功后的真实群 | 新增 group_id；回滚窗内新成功项双填，历史原值保留 |
| pull_task_group_marketing_group_occupancy.group_link_id | 被流程占用的真实群 | 新增 group_id；活跃占用在切读前回填，回滚窗内双填 |
| pull_task_group_execution.group_link_id | 草稿阶段可能只是邀请输入，成功后才有真实群 | 新增 group_invite_id 冻结输入 + nullable group_id 保存解析 / 创建结果；回滚窗内 legacy 列用对应 invite/group legacy ID 双填 |
| normal_group_creation_item.group_link_id | 建群结果真实群 | 新增 group_id；回滚窗内新成功项双填，历史原值保留 |

历史任务表不做一次性全表 `UPDATE group_link_id = group_id`。多个旧 link 可能归并到同一 canonical group，直接替换会撞击 `(task, account, group_link_id)`、`(task, group_link_id)`、`(group_link_id, participant_jid)` 等旧唯一键，也会篡改执行时证据。新列只用于新写和当前态 enrichment；旧记录通过 resolver 读取，待保留周期结束后按各业务独立归档策略处理。

### 10.7 保留表兼容 DDL / 双写矩阵

回滚窗口内绝不能写“只填 typed ID”：现有 metadata task、batch item 的 group_link_id 是 NOT NULL，旧二进制也只认识它。统一规则是 typed 列先 nullable additive；新 binary 对需要当前资源的新行同时写 canonical typed ID 和 designated primary legacy ID；旧列约束/索引保留到旧 binary 退场。具体门禁如下：

| 保留表族 | additive typed 列 | 回滚窗写法 | 唯一键并存 / 冲突处理 | typed 收紧时点 |
|---|---|---|---|---|
| group_link_import_detail | group_invite_id + invite/success/time 索引 | 成功行同时写原 group_link_id + inviteId；失败行都空 | 审计行不折叠；按 batch/line 保持历史 | 所有成功历史可解析或 compat map 长期保留后 |
| join_task_result | group_invite_id + nullable group_id | 原 link 继续是执行输入快照；新任务创建 I 后写 inviteId，成功解析 JID 后补 groupId | 原 task/account/link 幂等语义不改，typed ID 只做当前 enrichment / 回读 | 活跃任务全部 typed 后；历史 link 永久保留快照 |
| group_metadata_sync_task | group_id、semantics_version | legacy primary + groupId 双填 | 旧 `(tenant,group_link_id)` 保留；canonical collapse 先选 lease/next_run winner 合并指标，再启用仅 v2 非空的 generated group key 唯一 | 旧 scheduler 全退场且 active typed-null=0 后；历史可继续 nullable |
| group_batch_task_item | group_id、semantics_version | task item 双填 | 保留旧 task+legacy 唯一；新增仅 v2 的 task+group generated 唯一，碰撞项按执行状态保留历史、只选一个 active winner | 旧 worker 全退场且 active typed-null=0 后 |
| marketing_task_target / send_attempt / success_group / export | group_id、semantics_version | 新 target/attempt 双填，所有执行字段继续冻结 snapshot | 旧 `target_unique_group_key` 不改；并列增加基于 typed groupId 的 v2 generated key，dry-run 后才设 v2 | 任务创建/轮次/导出旧 worker 全退场后 |
| group_creation_marketing_item、group_pull_marketing_execution、normal_group_creation_item | group_id | 仅成功得到真实群后双填；未成功保持两边空/原状态允许值 | 历史唯一键不改；v2 幂等键按 task/item canonical group 单独增加 | 所有可恢复 active item typed-null=0 后 |
| pull occupancy / pull_task_group_execution | occupancy.group_id；execution.group_invite_id + result group_id | 输入 invite 和结果 group 分域双填；不得用同一个 legacy 数字猜类型 | active 占用 canonical collision 先按 lease/status 选 winner；历史 execution 保留原键 | active waiting/running 全 typed 后 |
| protocol_command_outbox | typed resource_type/resource_id + payload_schema_version | v2 payload 带 typed ID，回滚窗同时保留 legacyGroupLinkId 和冻结 JID/code | command idempotency key 不因换 ID 改变；同一命令不能双发 | 所有可投递旧 payload、DLT 和回执排空后 |

上述“v2 generated key”只对 `semantics_version=2` 返回非 NULL，利用 MySQL 多 NULL 语义避免历史行被错误折叠。加键前每表必须输出 canonical collision 报告和确定的 winner；禁止用 `INSERT IGNORE` 丢历史。旧列从 NOT NULL 改 nullable / 删除、旧 generated key 删除、typed 列改 NOT NULL 都是独立后续迁移，不与 writer 切换同版执行。旧 binary 回滚演练必须能读取并继续处理 barrier 后新建的任务，否则不得结束双填。

## 11. 全业务依赖闭包

本章用于证明六表切换不会漏掉读写角落，不等于把发现的每个协议可靠性问题都纳入首期。首期只改“仍会读写群当前事实”的路径和切换必需门禁；非幂等建群 journal、三套 v2 topic 双发、零停机 spool、账号绑定历史等独立问题保留为风险记录，另立方案，不能作为六表 DDL/切读的隐含前置。

下表是实施计划必须逐项迁移和回归的业务闭包。六表以 G/P/I/M/B/S 简写；配置、账号和任务表仍按原职责保留。

| 业务角落 | 当前关键依赖 | 目标读取 / 写入 | 不可破坏的行为 |
|---|---|---|---|
| 群组列表、筛选、分页 | link/preview/health/member aggregate/membership/task | G/P/I/folder 基础，M/B/S 按页 enrichment | count 与 page 同口径；默认不全表聚合 |
| 群详情 | preview/member snapshot/metadata task | G/P/M；I 显示当前链接；任务表显示同步错误 | 打开详情只读本地最后成功事实 |
| 本地展示名/头像/备注 | group_link + preview 混写 | 只写 G.display / remark | 不调用协议；清空后 fallback WhatsApp 值 |
| WhatsApp 群名、头像、设置、限时消息 | 协议写 + preview 镜像 | 协议确认后按版本写 P；任务刷新 | 超时必须同账号回读确认 |
| 成员升降权、踢人 | snapshot + 实时 metadata | M；成功后触发完整 metadata | owner 保护、批量逐项结果、UNKNOWN 语义 |
| 群组分组与删除 | group_link.folder/deleted | G.folder/deleted | 删除不被事件复活；删 folder 转未分组 |
| 批量刷新链接 | preview/health + batch task | I + 原 batch task | 异常群门禁；逐项结果可追踪 |
| 批量获取最新群信息 | preview/snapshot + batch task | P/M/I + 原 batch task | 异步进度和失败不污染当前成功快照 |
| 导入链接、链接分组、迁移 | group_link + label/batch/detail | I + 保留 label/batch/detail | 未解析链接不伪造 G；重复 code 幂等 |
| 进群任务 | group_link URL、任务快照 | 创建 I；成功后 G/I/M/B | 任务历史冻结 URL/结果；不能把 wa:// 当邀请 |
| 标准拉群链接选择 | group_link 列表 | invite 专用兼容接口 | 未解析 invite 可选；不依赖 group_id |
| 拉群执行 / 候选 / waiting pool | group_link/JID/membership/admin | G/P/M/B/S，执行时 I | waiting pool 继续以规范 JID 稳定寻址 |
| 拉群营销 | 任务群快照 + 当前群状态 | 历史展示读任务；当前资格读 G/P/M/B/S/I | 不用当前资料改写历史结果 |
| 群组营销账号树 | account_group_membership + link | B→M→G/P/I + account_state | 固定目标和动态目标语义保持 |
| 上控后新群立即营销 | membership added + baseline | S 判 baseline 完成，B 记录首次，M 当前在群 | 只对同 account 的新群触发一次 |
| 历史/上控群分类与存量回填 | GroupClassificationService/BackfillJob 写 group_link sticky flags | B/S 生命周期事实 + metadata task；旧 job 在切 writer 前停用 | 不再把账号语义固化到 G；state=1/3、未知 baseline 不分类 |
| 历史群列表与账号分组 | baseline JSON + membership + preview | B/S 定范围，M 当前关系，G/P/I 显示 | 不再创建第二套历史群实体 |
| 历史群拉取与营销 | 历史执行任务快照 | 当前资格按六表；历史执行按任务表 | 恢复、停止、重试和历史明细不变 |
| 建群营销 | item.group_link_id + preview | 成功归并 G/P/I/M/B；任务表保留快照 | 部分成功和失败补偿语义不变 |
| 新建普群 | normal creation item | 成功归并 G/P/I/M/B；任务 item 保留 | creator、次管理员、成员、权限和退群阶段不重放 |
| 独立建群 API | `POST /api/groups/create` 同步 HTTP 结果当前不登记群 | durable create-result 经 Reducer 归并 G/P/M/B，确认 invite 才写 I；原响应形状兼容 | commandId + binding 四元组可重放；保留“Armada 自建”和 creator 因果，外部成功不能因进程崩溃丢失 |
| 成员导出 | snapshot/cache/state/join/depart | M + P header | 当前成员、最近加入、最近退出列不丢 |
| 账号列表群数量 | membership | B JOIN M，COUNT DISTINCT group_id | 只数当前 IN_GROUP，租户隔离 |
| 执行账号选择 | membership.is_admin + account_state | B→M.role/presence→account/account_state | 管理员优先、在线、正常、协议绑定有效 |
| 群健康巡检 | health candidate | G/P/I + execution selector + metadata task probe 水位 | invite 错误、群状态、账号状态分域；保留调度退避 |
| metadata 调度和 backfill | metadata task + preview/snapshot | 任务表保留；成功提交 P/M/I | 一群一任务、租户/账号并发和租约不变 |
| 协议命令 outbox / 回执 | groupLinkId/JID/code snapshots | 新命令用 typed groupId/inviteId；JID/code 继续冻结 | Kafka 幂等、重试、未知结果 fencing 不变 |

### 11.1 已确认的契约雷区

1. GroupListRow.id 当前等于 group_link.id，并被详情、批量、文件夹、营销 selection 和多个任务表广泛当作 groupLinkId。
2. 灰度响应必须同时提供 groupId 和 legacyGroupLinkId；旧路由的动作先走 resolver，不能静默把一个新自增 ID 当旧 ID。
3. 历史任务记录中的 group_link_id 是执行时快照，不应批量改写；新任务字段改用明确的 group_id / group_invite_id。
4. 群列表跳进群任务当前传 row.url；事件型群可能拿到 wa:// 假 URL。目标应传 currentInviteUrl，或只传 groupId 由后端解析当前邀请。
5. JoinTaskQuery.groupId 当前实际表示账号分组 ID，必须改名为 accountGroupId，避免与 canonical groupId 混淆。
6. creatorPhone 当前由 ownerPhone 转换而来；API 和 UI 必须统一语义。
7. `/profile` 的 groupName/avatarUrl 是本地展示覆盖，`/subject` 与 `/picture` 才改 WhatsApp；目标 API 和数据库必须继续分开，不能再把两个动作写进同一 preview 列。

### 11.2 协议事件到六表

| 当前 / 目标事件 | 六表写入 | 过程表 / 副作用 | 关键限制 |
|---|---|---|---|
| account.groups_reported.v2 | S 先 CAS；G/P/M/B | metadata task、上控后营销 | 只有 FULL_ACCOUNT_SET+complete+skippedCount=0 可 mark missing / baseline；SUMMARY 不妨碍集合完整 |
| account.group_membership_changed | G/M/B；S 只读 admission/fencing、不写水位 | metadata task | SELF 身份必须解析到 participant；单群 delta 不能伪装成 account report/sync attempt |
| account.group_participant_joined / v2 participant_presence_changed(ADD) | G/M；命中受控 participant 时补 B | metadata task | joinedAt/sourceEventId 逐成员；只写 presence+last join，不写 role |
| account.group_participant_departed | G/M；已有目标 B 最多推进观察水位 | metadata task | LEFT/REMOVED/UNKNOWN 分开；只写 presence+last exit；成员退群不是 binding instance 退役 |
| account.group_past_participants.reported | G/M 最近退出事实；已有目标 B 最多推进观察水位 | 无完整快照副作用 | HISTORY_SYNC 只是 departure 集，不是 current member full set，绝不 mark 其他成员 missing |
| participant_presence_changed.v2 | G/M；ADD/JOIN 命中受控 participant 且通过该目标账号 fencing 时才可补 B；LEAVE/REMOVE 只推进已有 B 观察水位 | metadata task | 不写 role；departure 不新建、不 retire B |
| participant_role_changed.v2 | G/M | metadata task、执行账号资格刷新 | promote/demote 不写 presence |
| full_group_members_snapshot.v2 | P snapshot header + M | metadata task 成功 | 逐成员 CAS，不 delete-all/insert |
| group.profile_fields_observed.v2 | G/P | metadata task | fieldMask + 每字段版本 |
| group.invite_link_changed.v2 | G/P/I | batch link task | 支持 revoke / null code；P 的 group-level pointer/watermark 是当前关系主值 |
| group.health_reported.v2 | P.group_status 或 I.validity_status | 告警/任务错误 | 必须按明确 error domain 分流，不改变 P.current_invite_id |
| group.join_result_reported | G/I/M/B | join task result | 任务结果与当前事实分别提交 |
| group.action_result_reported | 仅确认字段写 P/M/I | 各业务任务 | ACK 不等于事实，必要时回读 |
| group.members.result_reported | 完整性明确时写 P/M | pull task member query | 不完整结果只供任务，不 mark missing |
| account.group_metadata_sync_requested | S.last_sync_requested_at | group_metadata_sync_task | 不改变群当前资料 |
| normal_group.action_result_reported.v2（专用 topic） | GROUP_CREATE 确认后写 G/P/M/B，确认 code 才写 I；settings/leave 只写各自被确认字段 | normal creation/direct API/两类建群营销的 result/admission | 所有 GroupCreatePort caller 统一 durable；任务状态是否仍在当前 step 不得阻止已确认群事实归并 |

group.metadata_updated 当前虽在协议类型和 OpenAPI 中声明，但 Web 没有稳定 producer，Java Consumer 也未真正应用，不能把它当作迁移依赖；应由 v2 profile / snapshot 事件替代或补齐端到端实现。

专用 `protocol.normal-group.events.v1` 不是普通任务噪音，而是第三条会形成群当前事实的活动链。首期保留该独立 topic，在暂停切换时与 account group-sync、general group 两条链一起排空并切换唯一 writer；不能把它并入 general group topic。是否新增三套 v2 topic 和零停机 shadow 双发不属于六表首期，另立迁移 ADR。

normal result canonical envelope 至少固定：schemaVersion/eventId/sourceEventId/observedAt/completedAt/publishedAt，tenantId、flowKind（NORMAL_TASK/DIRECT_API/GROUP_CREATION_MARKETING/GROUP_PULL_MARKETING）、correlationType/correlationId、taskId/itemId（不适用时可空）、operation、commandId/attemptNo/outcome/reasonCode/retryable，armadaAccountId/protocolBackend/protocolAccountId/bindingInstanceId/bindingGeneration，`creatorIdentity{pnJid?,lidJid?}`、groupJid、requestedSubject、`confirmedSubject`、requestedSettings、confirmedSettingsFieldMask/confirmedSettingsValues、requestedParticipants、participantResultsComplete，以及逐 participant `{requestIndex/memberRef,requestedJid,actualPnJid?,actualLidJid?,outcome=CONFIRMED_IN_GROUP/REJECTED/UNCONFIRMED,rawStatus}`、可选 confirmedRoleChanges/confirmedInviteCode。P.subject 只读 confirmedSubject，绝不从 requestedSubject + overall success 反推；若协议用通用 field mask，则 subject 必须在 confirmed mask/value 中有等价的独立确认位。成功 create-result 经协议身份或可信 account.wsPhone fallback 后，creatorIdentity 的 PN/LID 至少一个必填。participantResultsComplete=true 时，requestIndex 必须非负、唯一并完整覆盖 requestedParticipants 的每个位置；否则 schema 校验失败并进入确认回读，不能把缺项解释为成功或拒绝。creatorIdentity 只能来自协议明确身份，或后端在上述四元组通过后把 account.wsPhone 规范成 PN；protocolAccountId 本身不能当 participant JID。若某 participant 是 Armada 受控账号，命令快照还必须逐人携带 participantArmadaAccountId + 该账号的 bindingInstanceId/generation，不能从手机号反查后猜当前代次。

GROUP_CREATE 使用 `createOutcome=CREATED/CREATED_PARTIAL/NOT_CREATED/UNKNOWN`；CREATED 或 CREATED_PARTIAL 且返回 groupJid 时，即使后续设置、提权、退群或任务结算失败，也先用该 eventId 归并 G.first_seen_source=SELF_BUILT、managed_creator_account_id/managed_created_at，并以 creatorIdentity 写 creator M=IN_GROUP+OWNER、以执行账号四元组建当前 B。P.subject 只取协议明确确认的创建值；其他成员只在逐项 outcome=CONFIRMED_IN_GROUP 时写 M=IN_GROUP，且仅当其逐人受控账号四元组也通过当前 S 校验时才写 B，DIRECT_API 的普通外部联系人绝不能生成 B。整体 CREATED、`participants=[]`、participantResultsComplete=false 或缺项都不能推断请求成员成功/失败；I 仅在协议明确返回 code 时创建。GROUP_SETTINGS_APPLY 可能前几步成功、后一步失败，Producer 必须累计 confirmed mask/value，Reducer 只写 confirmed 字段，不能从 requestedSettings 或整体失败猜回滚。GROUP_LEAVE 确认成功只把 creator M 写 LEFT 并更新该 B 的观察水位，不能写 B.retired_at——retired_at 只表示账号 binding instance 生命周期失效；若 result 明确携 confirmedRoleChanges 才写候选角色，否则只排 metadata refresh，不能从任务配置猜 candidate 已提权。

`GroupOperationServiceImpl`、`GroupCreationMarketingWorker`、`GroupPullMarketingExecutionWorker` 与 normal creation dispatcher 是当前四个直接/间接建群入口，不能只修 controller。四者都必须先建相应 flowKind 的 idempotent admission、把相同 commandId 送协议端，并以专用 v2 result 作为外部建群成功的 durable 证据；原 worker 任务表继续保存步骤和部分成功，但不得在“WhatsApp 已建群→稍后 registerSelfBuiltGroup”之间保留进程崩溃空窗。结果 Consumer 固定两段可重试顺序：事务 A 只按 immutable command snapshot + epoch/fencing 调 GroupFactReducer；提交后事务 B 再按 commandId/expectedStep CAS 结算 normal/direct/marketing process，并原子 reserve 下一命令 effect。task 不存在、terminal 或 step mismatch 只让 B affected rows=0 + 告警，不能跳过/回滚 A；B 失败时整条 Kafka record 重试，A 由 eventId/version no-op，两个事务都完成后才提交 contiguous Kafka offset。这样也不需要让 task lock 反向侵入 `account→S→G...` 的事实锁序。

“先调用 WhatsApp、成功后再存 result”本身无法与外部系统形成原子事务，因此这里的可靠性承诺必须精确为 **at-most-once dispatch + 自动或人工可审计确认**，不能宣称任何崩溃都能自动找回。Web/Android 在外部调用前先把同一 commandId、payload fingerprint、冻结 binding、stable eventId、requested subject/participants/settings、startedAt 持久化为 PREPARED；抢占者 CAS 为 EXECUTING 后最多调用 WhatsApp 一次。调用返回后先把原始结果写 `RESULT_DURABLE` journal/CUTOVER_SPOOL，再发布 v2；发布失败只重放 journal，绝不重做外部调用。若进程在“WhatsApp 已执行→结果落稳”之间崩溃，恢复器把过期 EXECUTING 收敛为 UNKNOWN，禁止自动重新建群；它使用冻结 creator、confirmed account identity、subject、participants、命令时间窗和协议端可查询的 created/upsert 证据寻找候选，再对候选执行 metadata/member 回读。只有唯一候选且 JID/creator/关键事实吻合时，才用原 commandId/eventId 生成带 `recoveryEvidence` 的 confirmed result；零个或多个候选必须进入人工 reconciliation，由操作员明确绑定既有 JID 或判定 NOT_CREATED，结论同样写 journal 后走同一 Reducer。不能唯一确认时系统宁可保持 UNKNOWN/阻断任务，也绝不再次调用建群。Phase 2B 前 Web 与 Android 都要注入“外部成功后、journal 写入前崩溃”故障，证明不会二次执行且 UNKNOWN 有告警、查询和人工闭环；设计中“外部成功不丢”指不会被静默当失败并重建，不代表无证据时自动猜出群。

normal creation 的次管理员提权也必须从 `NormalGroupCreationProtocolResultService.groupCreated()` 当前事务内同步 `participantPort.updateParticipants(PROMOTE)` 拆出：事务只把 `SECONDARY_ADMIN_PROMOTE` commandId + 逐目标快照写 protocol outbox 并把 item 置等待；Web/Android 执行后把逐 participant status、实际 targetJid、账号四元组、effect token 和稳定 eventId 写 durable result，再由 Consumer 结算任务并仅对明确成功项推进 M.role。UNKNOWN/投递失败触发 metadata 确认，不自动重发提权；“WhatsApp 已提权但结果消费事务回滚”重放只命中同一 command/effect admission。任何外部 WhatsApp 操作都不得在 Kafka result 的数据库事务内部直接调用，否则事务重试会重复产生不可回滚副作用。

命令结果先要求四元组逐字段等于不可变 command/outbox 快照，并且仍与 S 当前值一致，才按正常 current B/effect 归并。instance/generation 已退役或无法证明 current 的结果一律 quarantine，只触发 metadata/member 确认回读；首期不为旧 generation 新建 B，也不触发“上控后新群”或其他当前代 effect。切换前排空在途命令是这条保守规则的前置门禁。

`ProtocolBindingRef{armadaAccountId,backend,protocolAccountId,bindingInstanceId,generation}` 必须在 enqueue 时锁 account+S 并冻结进 durable command reference/outbox，不得等 payload hydrate/publish 时现查。dispatch 前校验冻结 ref 仍 current；若尚未执行就已换绑，确定失败且不调用 WhatsApp。执行后 result 原样回传冻结 ref，重试/DLT 不得给旧 command 换成新 generation。

`POST /api/groups/create` 保持现有同步成功响应，但内部不再使用 `group-create-api:` + 随机 UUID 作为不可追踪 correlation。canonical API 要求调用方在第一次请求前生成并提供稳定 `Idempotency-Key/requestId`；服务端首次收到后可以原样回显，但不能用“服务端临时生成并在响应里告诉客户端”宣称覆盖响应丢失，因为首次响应若丢失，客户端没有任何稳定键可用于重试。Phase 1 先发布前端/SDK 生成键和兼容接收能力；进入 Phase 2B 前所有活跃调用方覆盖率必须为 100%，正式 durable route 对缺键返回 `IDEMPOTENCY_KEY_REQUIRED` 且绝不调用 WhatsApp。仅为旧客户端保留的无键 v1 行为最多存在于 Phase 2A，并须单独计量/下线，不能进入 writer cut。过程/admission 表或现有 outbox 对 `(tenant_id,idempotency_key)` 唯一，首次事务冻结 `SHA-256(accountId + normalizedSubject + sortedDistinctNormalizedParticipants + settings)` 请求摘要和 commandId；同 key 同摘要返回已存在结果/处理中状态，同 key 不同摘要返回 `IDEMPOTENCY_KEY_CONFLICT`，UNKNOWN/响应丢失只查询原 command，绝不再次建群。commandId、eventId 和 business_effect_key 从该持久记录稳定取得，并绑定当前 binding 四元组/effect token。Web/Android 协议端在执行外部建群后，必须先把同一 `flowKind=DIRECT_API` v2 result 以 broker ack 或受控 durable spool 落稳，再返回 HTTP。后端将 HTTP 确认用同一个 eventId 调 Reducer 并在事务提交后返回；若进程在 HTTP 成功与本地提交之间崩溃，Kafka replay 补交，若 Kafka 已先到则同步路径 affected rows=0。超时/UNKNOWN 返回现有错误并提供 commandId 查询/对账，不重发建群；禁止仅等待未来 groups.upsert，因为那会丢失自建、creator 和请求成员的因果。v1 direct route 不具备 durable result 时只允许在 Phase 2A 继续旧行为，所有活跃 Web/Android 路由具备 v2 durable result 是 Phase 2B 前置门禁。

#### 11.2.1 三类 Android 成员 v1 事件的显式兼容映射

这三类事件已经在 `protocol.account.group-sync.events.v1` 活跃生产/消费，不能只写一句“以后由 participant_presence_changed.v2 替代”。`V1MemberFactAdapter` 的逐成员映射固定如下：

| v1 event/source | v1 字段 | M 写入 | 禁止推断 |
|---|---|---|---|
| account.group_participant_joined / WGP2_NOTIFICATION | groupJid、participantJid、phone、joinedAt、sourceEventId | presence=IN_GROUP、presence_observed_at=joinedAt；推进 membership epoch；last_joined_at/last_join_event_at=joinedAt；两个 version key 都以该 participant sourceEventId + envelope eventId 编码 | 不写 role；缺少本 participant 的记录不能 mark absent |
| account.group_participant_departed / WGP2_NOTIFICATION | 同上，另有 exitType LEFT/REMOVED/UNKNOWN、exitedAt | presence 分别为 LEFT/REMOVED/DEPARTED_UNKNOWN；last_exited_at/last_exit_event_at=exitedAt；last_exit_type=1/2/3；role 随 winning 非 IN_GROUP epoch 失效 | UNKNOWN 不能猜 REMOVED；没有 `WGP2_ACTOR_DIFFERENT` 证据的 remove 保持 UNKNOWN |
| account.group_past_participants.reported / HISTORY_SYNC | 与 departure 相同 | 使用同一 exit 映射和 CAS；它可补齐较早 last-exit，只有 FactVersion 胜出时才改变 current presence | 它不是完整成员快照，不删除/mark-missing 未上报成员，也不覆盖更晚 JOIN |

三类事件先用 observer 的 PROTOCOL 四元组做 admission，再只按 groupJid 确保 G identity（不得清 G.deleted_at）并解析 M；不写 P/I/S。presence 与 last_join/last_exit 是独立 version family：更早 HISTORY exit 可以补 last-exit 历史，但不能覆盖更晚 JOIN 的 current presence；同理稍晚到达的旧 JOIN 可补 last-join 而不能复活 current presence。JOIN 还写 last_join_observer_account_id=observerAccountId，从 UNKNOWN/LEFT/REMOVED/SNAPSHOT_ABSENT/DEPARTED_UNKNOWN 赢到 IN_GROUP 时才 epoch+1 并使旧 role 失效；departure winning 非 IN_GROUP 后 role 不再可读。v2 每个 participant 都必须携 sourceEventId + 自己的 observedAt，top eventId 与 v1 twin 相同；没有可靠单调 sequence 时显式 absent，禁止用 Kafka offset或本机计数伪造 sourceSequence。

participantJid 为规范 `@s.whatsapp.net` 时写 M.pn_jid，并可从该 PN 安全派生 phone；为 `@lid` 时只写 lid_jid。v1 的独立 phone 若没有“同一上游事实明确绑定 PN+LID”的证据，只作冲突/回读提示，不能据此凭空生成 pn_jid。participant 恰好能解析到某个当前受控 account、通过该目标账号的 binding 四元组且事件版本不早于其 control boundary 时，JOIN 才 upsert 当前 B；departure/history 不新建也不 retire B，最多推进已有 B.last_observed 水位。B.retired_at 只表示账号↔协议 binding instance 生命周期失效，成员退群后仍需保留该代 baseline/历史证据。事件 envelope 的观察账号只是 observer，普通成员进退不能改 observer 自己的 B/S。

Web/Baileys 走的是另一条现役 v1 `group.participant_changed`，不是上述三个同名 Android event；当前 Java 对 add/remove 直接忽略，只处理 promote/demote，换六表前必须一起闭合。Web v2 adapter 把 add→IN_GROUP/last join，promote/demote 只写 role。Baileys 当前把真实 LEAVE 与 REMOVE 都折为 `action=remove`，所以不能无条件写 REMOVED：规范化 operator PN/LID 与目标 participant alias 明确相同才写 LEFT，明确不同才写 REMOVED，operator 缺失或 alias 冲突写 DEPARTED_UNKNOWN；v2 必须增加 operatorPn/operatorLid/operatorAliasEvidence，不能只传模糊 operator 字符串。participants 的 id/lid/phoneNumber 同样转成显式 pnJid/lidJid/aliasEvidence。

Baileys `group-participants.update` 当前公开 callback 没有可靠上游 timestamp/source id，不能把 `new Date()` 和每次随机 UUID 伪装成 WhatsApp 事实。优先在受控 Baileys patch/raw notification bridge 保留 message key/stanza id 与 messageTimestamp，并把它们作为稳定 per-participant sourceEventId/observedAt；若某来源确实拿不到，第一次 callback 在任何 v1/v2 publish 之前必须把完整 payload 原子追加到 Web durable bridge journal，生成一次 stable sourceEventId 并固定 callbackObservedAt，后续 twin/retry 只重放该记录。后一模式标 `clockConfidence=CALLBACK_LOW`，对已有 presence 仅 FILL_ONLY/不覆盖，并强制安排 metadata/full-member 确认；本机 journal sequence 不能冒充 WhatsApp sourceSequence。拿不到 raw time/id 且没有 durable first-observation journal 的 Web build 不得进入 shadow。

若同一个 self WGP2/participant notification 还并行产生 `account.group_membership_changed`，Producer 先生成一份 canonical member fact，再让两种 envelope 引用同一个 participant sourceEventId/observedAt、presence 和 exitType：actor=target 为 LEFT、明确不同为 REMOVED、歧义为 DEPARTED_UNKNOWN；不得让 membership delta 把同一次 departure 固定成另一枚举。Reducer 对 M/epoch/effect 只能应用一次，另一 envelope 最多补同一 B observation。goroutine/Kafka 交叉到达不得靠 eventId hash 随机决定业务值。Web/Android active build 的 tuple+stable twin、add/remove shadow count、self 双链逐字段相等和 Java consumer fixture 都属于同一 Phase 2B 门禁，不能只升级一端。

滚动兼容不是无条件信任旧消息：升级后的 Android v1 envelope 必须 additive 携带 protocolBackend、bindingInstanceId、bindingGeneration、effectAuthorityEpoch，并与 v2 twin 复用相同 envelope eventId / participant sourceEventId。LEGACY epoch 下现有 v1 handler 继续写旧表，v2 只 shadow。当前 `AccountEventFallbackWriter` 在 Kafka 耗尽后 fsync 到名为 account-state 的 JSONL 就返回成功，而文件实际也含这三类群事件，且没有 retention/容量/自动重投；所以“落本地=双发成功”不成立。barrier 必须逐 Android 节点冻结并登记 JSONL/spool 文件 identity、byte/record watermark 与 checksum，扫描全部旧文件，以原 eventId/observedAt/token 分别重放目标 v1/v2，取得 broker ack + 对应 consumer applied-ledger，再 checkpoint/只读归档；任一节点不可达、未登记文件、解析失败或 replay gap 都阻断 cut。新 CUTOVER_SPOOL 对每个 record 持久保存 per-target delivery state，只有两个目标各自 ack 才可清理。

barrier 还要等待 v1 consumer 到记录水位，并把最后旧写捕获进 foundation/backfill。切 REDUCER 后，仅完整四元组且 token 被 cut ledger 接受的晚到 v1 才可由该 Adapter 按上表进入 Reducer；缺字段、旧/未知 generation 或旧 session 的 v1 一律 quarantine + 单群 metadata/full-member 回读，不能 destructive 写 M。Android 对三类事实 durable 双发 v1+v2、逐 sourceEventId 对账为 100%，v1 topic/DLT/JSONL 排空并观察两个业务周期后，才删除 v1 Adapter/producer；任何一类 lag 或来源计数不闭合都阻断 retirement。

### 11.3 Web / Android 当前差异与整改门禁

| 问题 | Web/Baileys | Android Zhuan | 切新模型前要求 |
|---|---|---|---|
| 账号群快照完整性 | patch 已统计 skippedCount，但轻量 API / publisher 丢字段 | 根据 RemovalAuthoritative 与 skippedCount 计算 | 两端统一显式 kind/complete/skippedCount |
| 快照触发合并 | dirty/upsert 等来源未表达权威级别 | dirty + created 同窗口可能被 created source 降级 | 合并时“完整性能力”取更强，不只取 source priority |
| 事实时间 | participant 多使用本机处理时间 | SELF membership 使用 Now() | 使用 WGP2 / HistorySync / queryStartedAt |
| eventId | 普通事件可能随机生成 | builder 有源信息但需统一稳定规则 | 重试稳定，保留 sourceEventId |
| participant add/remove | 会发布，但 Java 当前忽略普通 add/remove | WGP2 / HistorySync 路径较完整 | Java v2 全量消费 presence 动作 |
| promote/demote | Java 当前写 inGroup=true | Android 已发布管理员角色事件 | role 只改 role，不复活 presence |
| invite revoke | route 可 revoke，但 changed event 强制非空 code | builder 同样要求 code | v2 action=REVOKED 可无 code |
| binding fencing | 无统一 generation | 无统一 generation | 所有写六表事件都校验 bindingGeneration |
| Kafka 失败 | Kafka producer 重试 | 本地 JSONL 后返回成功，缺可靠重投 | Android DLQ 必须有 retention、重启恢复和 replay |
| metadata 契约 | OpenAPI 与真实 payload 有差异 | 字段较丰富但命名不同 | 用共享 JSON fixture 做 producer→Java consumer 契约测试 |

后端当前各 Mapper 的同时间 tie-break 也不一致：membership、preview、health、member cache 使用不同规则。实施时必须删除分散的 upsert 判断，集中到 Reducer 版本比较。

### 11.4 现有表的最终去留清单

| 处置 | 现有表 / 字段 | 原因 |
|---|---|---|
| 六表切稳后删除 | group_link、group_link_preview、group_link_health、account_group_membership、account_group_baseline（含 last_group_sync_requested_at）、whatsapp_group_member_snapshot、whatsapp_group_member_cache、whatsapp_group_member_state、whatsapp_group_member_join_fact、whatsapp_group_departed_member、account.group_baseline_state | 都是被六表吸收的当前投影或最近事实；长期保留会形成第二主值 |
| 保留配置/主数据 | group_folder、group_link_label、account_group、country、country_phone_prefix_mapping | folder / invite label / 账号分组和国家配置不是 WhatsApp 当前事实；country 只作为派生输入，prefix mapping 继续服务 IP 等前缀业务，不得成为六表第二主值 |
| 保留导入审计 | group_link_import_batch、group_link_import_detail | 冻结每次文件、重复、成功失败结果；新成功引用使用 group_invite_id |
| 保留群同步任务 | group_metadata_sync_task、group_batch_task、group_batch_task_item | 保存租约、进度、重试和逐项结果；新目标使用 typed group_id |
| 保留进群与营销 | join_task、join_task_result、marketing_task、marketing_task_target、marketing_task_send_attempt、marketing_task_success_group、marketing_task_export_job、marketing_account_occupancy | 业务过程 / 结果证据；当前资格才读六表 |
| 保留建群营销 | group_creation_marketing_task / item、group_pull_marketing_task / execution / material / execution_material / account_stat | 任务输入、步骤、部分成功与历史结果 |
| 保留历史群拉取 | historical_group_pull_execution、historical_group_pull_member | 某次拉取的冻结执行结果，不是成员当前态 |
| 保留拉群任务 | pull_task 及 group_execution、group_account、account_action、pull_call、pull_call_member_attempt、pull_wave、member_query、material_member、各 setting / summary / occupancy 表 | 调度、占用、请求和结果历史；当前群 / 成员 / 邀请改读六表 |
| 保留普通建群 | normal_group_creation_admission_lock、task、item、item_member、item_secondary_admin | 四阶段执行和幂等补偿证据 |
| 保留协议可靠性 | protocol_command_outbox、Kafka topic/offset、现有 DLT、Android 失败日志；如迁移实现需要数据库 event inbox 必须显式新增 | 命令可靠投递和事件重放，不是群事实 |

保留表允许冻结 subject、groupJid、inviteUrl、memberCount、participantJid 等“执行时快照”，但字段名 / 注释必须标 snapshot，禁止被 Reducer 当当前值读回。活跃记录增加 typed ID；已结束历史记录先维持原值并通过 legacy resolver 打开，Phase 6 前必须回填 typed ID，无法回填的则长期转入最小 compat map。

`group_metadata_sync_task` 兼容期增加 nullable group_id，并补以下投影列：`last_probe_result TINYINT NOT NULL`（UNKNOWN/SUCCESS/TEMP_UNAVAILABLE）、`last_probe_error_domain TINYINT NOT NULL`（NONE/GROUP_QUERY_TRANSIENT/EXECUTOR_ACCOUNT/NO_EXECUTOR/DELIVERY/PAYLOAD/UNKNOWN）、last_probe_error_code、`last_probe_fact_version_key VARBINARY(128) NOT NULL`、`last_success_fact_version_key VARBINARY(128) NOT NULL`（两个 key 都与 FactVersion canonical key 同型，未观察为 `0x00`）、last_probe_at、last_success_at、consecutive_probe_failures。它承接旧 health 的调度水位、临时失败和退避。候选按 next_run_at / last_probe_at，不因删除 health 后反复抢占；任何 accepted positive metadata / current-invite read 在同一 Reducer 事务 upsert task 并推进 success key，只有第 6.1 节 GROUP_QUERY_TRANSIENT 才 CAS TEMP probe key。UNAVAILABLE 唯一判定是 `last_probe_result=TEMP_UNAVAILABLE AND last_probe_error_domain=GROUP_QUERY_TRANSIENT AND last_probe_fact_version_key > last_success_fact_version_key`；晚到失败不得覆盖成功，P/I 仍只接收明确的群 / 邀请事实。

### 11.5 国家派生只有一个口径

当前 `CountryServiceImpl` 有两条不同算法：`activePhonePrefixResolver()` 用 country.phone_prefix + `country_phone_prefix_mapping` 做最长前缀，`resolveActiveCountriesByPhoneNumbers()` 用 libphonenumber 严格解析 ISO2；群 snapshot/history 与营销导出因此可能对同一号码给出不同国家。方案 A 把后者升级为唯一 `ConfirmedPhoneCountryResolver`：只接受已确认 PN/纯国际号码，去 device suffix 和 `@s.whatsapp.net`，经固定版本 libphonenumber 校验并取得唯一 ISO2，再要求该 ISO2 在 active country 集合中；无效号、LID、未知/非唯一 region 返回 null，不退回最长前缀猜测。group list、I preview、成员导出、营销导出和 country filter 全部调用/物化这个口径。`country_phone_prefix_mapping` 继续供 IP 分配等明确的“前缀展示”业务使用，但不进入 M/I country 投影，也不能覆盖 confirmed-phone 结果。

`country_resolution_version` 固定为 `SHA-256("confirmed-phone-country:v1" + libphonenumber artifact/metadata fingerprint + 按 ISO2 排序的 active country ISO2 集合)`；名称、国旗排序变化不重算，libphonenumber 版本或 country 启用/停用/ISO2 变化必须产生新 hash。`CountryProjectionReindexService/Job` 是唯一重算 owner：配置事务登记新 hash/重算作业，按 M.id、I.id 有界批次更新，并在写回时再次校验 phone/ownerPhone 的 fact version 未变化；任务可重入、有进度/失败水位但不得存第二份当前 country。读路径只信任 `country_resolution_version=:currentHash`，旧 hash/全零统一当 unknown 并排队重算；列表展示、count/filter 和导出复用同一谓词，所以重建期间可以暂时少命中，不能一边用旧投影、一边现场跑另一算法。初始 backfill、配置变更和依赖升级都要对全量 hash/count 做前后审计，并把有意的国家口径变化列入 expected diff。

## 12. API 兼容策略

### 12.1 v1 保持

迁移观察期保留现有路由和 JSON 字段：

- /api/group-links 用兼容 VO 适配新模型；
- /api/group-links/import、migrate 和 labelId 查询走 invite 服务；
- `/api/group-links/batch-preview` 在兼容期只做 typed invite check adapter：每个 legacy ID 有 canonical_invite_id 时检查该 I；只有 canonical_group_id 时在锁定 P 后冻结本次 current_invite_id，无当前 I 返回 `NO_INVITE_RESOURCE`；随后按 inviteId canonical 去重。它不再直接写 preview/health，执行账号/投递失败只写 I 的 EXECUTION_FAILURE 诊断，目标邀请明确结果才经 Reducer 写 I/P；Phase 6 与其他 v1 route 一起下线；
- /api/group-links/{id}/detail 先按 legacy kind 路由 GROUP detail 或 I-only preview；只有 GROUP 的成员与设置动作才由 resolver 找 groupId，I-only 返回稳定不支持码；
- 旧 groupLinkId 仍出现在历史任务 VO 中，但标注 legacy。

列表展开区删除后，lastPreviewAt、lastCheckAt、lastHealthError、membershipStateLabel 等无生产消费字段可先标 deprecated；syncProtocolMask 只是“历史上曾由 Web/Android 观察”的 sticky 镜像，六表没有可靠来源无损重建，也标 deprecated。这些字段只保留到 v1 `/api/group-links` 在 Phase 6 下线，typed `/api/group-resources` 不再提供；不能承诺永久逐值相等。

时间单位也要版本隔离：库内和 canonical API 使用 epoch 毫秒，并把字段明确命名 `waCreatedAtMs`；v1 `/api/group-links` 的 groupCreatedAt 继续返回秒，因为当前列表会再 `*1000`，历史页使用 `dayjs.unix`。不能在灰度期只改后端数值单位，否则日期和群龄会放大 1000 倍。

v1 群列表 VO 的 `url` 必须由 P.current_invite_id 对应 code 生成，不能继续返回 designated legacy 行的旧 link_url；同时新增明确的 currentInviteUrl/hasCurrentInvite。切新读前发布最小前端兼容：`openJoinTask` 优先使用 currentInviteUrl，无当前邀请时禁用并提示刷新链接。导入邀请页的 url 仍来自 I，两个适配器不能共用。

### 12.2 canonical API

新代码只使用：

- /api/group-resources：方案 A 的 typed 混合列表，仅用于保持当前 UI 列；每行明确 resourceType + groupId/inviteId，动作按类型路由；
- /api/groups：真实群列表；
- /api/groups/{groupId}：群详情和本地资料；
- /api/groups/{groupId}/...：metadata、设置和成员动作；
- /api/group-invites：邀请池、导入、分组迁移和预检；
- `POST /api/group-invites/batch-check`：提交 inviteIds 与可选 preferredAccountId，返回 requested/resolved/canonical count 和逐输入结果；只写第 5.3 节 I-check 状态，resolved I 的可靠资料才按版本归并 P；
- DTO 字段明确为 groupId、groupInviteId、accountGroupId，禁止新增 groupLinkId。

新路由必须沿用并显式映射现有 RBAC：`tenant:group_link:view` 的读取边界不因改名扩大，导入/label、群本地删除、invite pool 隐藏、WhatsApp revoke、成员管理分别使用原权限或新增更窄权限。Controller 先做 tenant + permission 校验再调用 typed resolver；不能因为 `/api/groups` 是新路径而绕过菜单/按钮权限，权限映射和前端菜单迁移属于契约测试范围。

### 12.3 历史任务

任务创建时冻结的 groupJid、inviteUrl、subject、memberCount、执行账号和结果是历史证据。详情页面优先展示任务快照；只有“当前状态”区域才 enrichment 六表。这样迁移后不会让旧任务随群改名或链接轮换而改变。

## 13. 分阶段迁移

首期默认采用“可回滚的短暂停写切单 writer”，不以零停机为目标。下面新增的 Phase 2M 是方案 A 的实施基线；原 Phase 2A/2B 保留为零停机风险审计备选，不纳入首期工期、DDL 或上线门禁。若业务明确要求零停机，必须把备选内容移入单独 ADR，重新评审必要性、表数和测试预算。

### Phase 0：冻结语义和依赖清单

- 确认本设计六表字段、状态和 API 边界。
- 对所有 groupLinkId 按“真实群 / 邀请 / 历史快照”分类。
- 给全部旧表写入点登记 owner；未登记写入点不得进入迁移。
- 生成 test1 数据质量报告和基准查询，不做数据修改。
- 生成 active `(tenant,ProtocolBackend,protocolAccountId)` 重复与 null/unknown backend 报告，逐行确定保留绑定和处置人；Phase 1 暂停入口前不得临时猜 winner。
- 暂停新增旧 group_link 当前态字段。

退出门禁：依赖闭包无“未知写入方”，用户评审设计通过。

### Phase 1：只扩展，不切流

- 用一组按部署边界拆开的 additive Flyway 版本创建六表、约束、索引和兼容列；不把数据回填塞进 migration。
- active protocol 唯一键必须在第一条 S 和 AccountBindingLifecycleService 新 writer 启用前完成，不能留保护空窗：先用 feature flag 短暂停账号导入、删除、绑定、解绑、换绑、配对接管和协议激活入口，按 ProtocolBackend 规则规范当前 protocol_id / protocol_account_id，dry-run `(tenant,backend,protocolAccountId)` 冲突并由人工解除重复绑定；再以独立 additive Flyway 增加 ascii_bin generated active protocol account 列和唯一键，验证旧 writer 的既有账号更新仍可运行。唯一键建立失败就保持入口暂停并回滚本次应用发布，不得创建 S；成功后才部署/启用 AccountBindingLifecycleService 并恢复入口。
- 增加 typed ID、Reducer、Mapper、只读对账服务和仅供账号快照使用的 `group_snapshot_effect_outbox`。把仍会创建/修改 account_group_baseline 的旧入口登记到 writer 清单；首期在 Phase 2M 暂停这些入口后做最终 backfill，不建设全量 v1/v2 effect admission、LegacyBaselineBridge 或账号绑定历史。
- 旧 API、旧读写行为暂时不变；v2 producer 尚不允许启用。
- Backend consumer 先兼容 bindingGeneration、fieldMask 和 v2 的 snapshotId/complete/skippedCount；v1 adapter 单独映射旧 snapshotVersion/snapshotComplete/skippedGroupCount，缺失字段只允许非破坏性更新。
- 数据 backfill 不放进 Flyway。nullable 兼容列优先 `ALGORITHM=INSTANT, LOCK=NONE`，二级索引独立使用经克隆验证的 `ALGORITHM=INPLACE, LOCK=NONE`；执行前设置短 lock_wait_timeout、检查长事务/metadata lock，由单一 migration runner 串行执行。MySQL DDL 非事务，必须演练中途失败后的结构探测、幂等续跑和 Flyway repair。
- 旧 writer 仍运行时先做一次可重入预填并记录每张源表的主键/updated_at 水位：G/P/I/M 全量，B/S 按第 10.3 节保守迁移。本阶段六表只供 shadow read，不触发任务/营销，也不宣称已经追平；Phase 2M 暂停后必须从这些水位补最终增量并重新跑全量 count/hash 门禁。

退出门禁：干净 MySQL 8.4.8 Testcontainers 从 V001 执行到新版本、V116 生产结构快照升级到新版本均通过，并覆盖 JSON/CHECK/NULL 唯一/generated key/锁并发；CI 若因无 Docker 跳过该测试则迁移构建失败。H2 仅继续验证 Mapper/Service 逻辑，不替代 MySQL DDL。克隆库 `EXPLAIN ALTER`、预计数据/索引增量、磁盘/buffer pool/undo/binlog/复制延迟余量都有签字；active protocol 冲突为 0、唯一键已在线且所有生命周期入口已切到 AccountBindingLifecycleService 后才允许出现第一条 S；六表为空时其余旧业务无行为变化。

### Phase 2M：首期短暂停写切换（实施基线）

- 暂停账号群快照、群事件 consumer、群相关 scheduler/API，以及会产生建群、进退群、metadata、营销副作用的 worker；停止新 command admission。
- 排空 Kafka 已提交前的群事件和 protocol_command_outbox；所有已下发命令必须有成功/失败/取消终态，UNKNOWN 阻断切换，不为它新增账号绑定历史或协议端 journal。
- 记录旧表最终主键/updated_at 水位，按同一 migrationRunId 重跑最终增量和 baseline backfill；执行第 15 节 count/hash/冲突/营销零增量门禁。
- Phase 1 必须已经完成所有当前态 reader 的新 SQL shadow 对账。暂停窗口内把唯一 writer 和全部“群当前态”读 flag 同时切到六表；旧表冻结但保留，历史任务继续读自己的冻结快照。任一 reader 未就绪或 flag 校验失败，都在恢复入口前把 writer/read flags 一起退回旧模型，六表预填继续只读；不允许先恢复新 writer、再让旧 reader读取已经停止更新的旧表。
- 恢复 consumer/worker 后，每个当前绑定账号主动跑一次显式 `FULL_ACCOUNT_SET+complete=true+skippedCount=0`；迁移已有 B（包括 was=NULL）只推进观察水位，不得升级为上控后新群。观察 DB 锁、SQL 数、lag、effect outbox 和新读 SQL；恢复后不再把旧当前态表作为读回滚目标，只能修复后 roll-forward。

退出门禁：旧 writer 运行指标为 0；新 writer 单写；迁移 effect 可执行增量为 0；在途/UNKNOWN command 为 0；MySQL RR 并发与 SQL `<=10` 门禁真实运行且未 skip。若暂停窗口无法排空，直接恢复旧 writer 并终止本次切换，不临时引入双写状态机。

### Phase 2A（备选，不纳入首期）：旧 writer 运行时建立零停机身份地基

- migration run 是耐久状态机 PREPARING→SHADOWING→CUTOVER_PENDING→COMMITTED，或 CUTOVER_PENDING→ABORTING→ABORTED；lease 过期不创建新 runId，必须以同一 runId 幂等续跑。确需新 run 时只能显式 ADOPT 一个已 ABORTED predecessor：全量验证 mapping、S/B foundation、baseline token hash 后，仅在 migration ledger 转移 owner，保留原 S instance/generation，不重跑 INIT、不删表重建；重新记录新的 topic start/effect epoch。存在未决 CUTOVER_PENDING/ABORTING 或已 COMMITTED predecessor 时禁止 adopt。
- 可重入预建全部 legacy 映射、canonical G + 空 P、I、账号 S，以及 `旧 account membership JID UNION 经第 10.3 节确认的 state=2 真实 baseline JSON JID` 所需的 self M；state=1/3、WATERMARK_ONLY 和 AMBIGUOUS_EMPTY_BASELINE 的 JSON 不参与该 union。这一步不把其余易变 profile/member 事实当成最终完成。
- 所有 group_link（含被历史引用的软删行）按 tenant+JID/code 分类；同 JID 保留 min legacy ID，临时映射索引先建好。
- MIGRATION_BINDING_INIT 只有在 Phase 1 已证明 active protocol 唯一键在线且生命周期旁路为 0 后，才独立判断 binding 活跃性：未绑定 / 已软删 account 写 S(instance=NULL,generation=0,baseline=DISABLED/NONE)，当前已绑定 account 由后端生成 MIGRATION bindingInstanceId、generation=1，绝不能让协议自行生成。再按第 10.3 节迁 baseline policy：legacy state=3 即使账号 active 也保留 current instance、写 baseline_filter_enabled=0 + DISABLED/NONE；state=1/2 写 filter_enabled=1，初始 PENDING/NONE，state=2 的合法证据随后由 BASELINE_BACKFILL 捕获。
- 在第一条 canary v2 消息发布前，确认三个 v2 fact topic（account group-sync、general group、normal-group result）无正式 consumer member，冻结并记录各自 topic UUID、partition count、每分区 leader epoch 与当时 LEO；该 LEO 就是该分区 replay 的 inclusive shadow_start_offset。直到正式 group 追平 barrier high-water 前禁止 topic recreation、partition expansion、compaction 或 retention 缩短，不能发布后才补记 offset。
- Web / Android 先发布能接收并回传 binding 上下文与 effectAuthorityEpoch 的兼容版本；当前在线会话通过受控 context refresh / 重新激活拿到 instance+generation。记录完上述起点后，在旧 v1 writer 仍是唯一 authority 时，按 Web 单账号、Android 单账号、小租户 canary 开启 v1+v2 durable shadow 双发，验证后扩到全部当前 binding；所有 shadow fact 固化 `LEGACY_SHADOW:{epochId}`，并带正确四元组、稳定 eventId 和 canonical complete/skippedCount/fieldMask。
- shadow 扩到某租户后，新绑定 / 换绑只有在协议会话确认 v2 capability 和当前四元组后才能激活，并自动继承 durable 双发；退出 Phase 2A 前重扫“已绑定但未见 v2 capability/heartbeat”的账号必须为 0。否则 barrier 前临时新增的账号会形成未被 backlog 覆盖的暗角。
- 生产 Reducer v2 consumer group 此时不 subscribe、无 member、无 commit；验证只能用独立 audit consumer group 或直接读取原始消息，不能让 shadow 验证偷走正式 replay offset。把预先取得的 topic identity 与 shadow_start_offset 写入有审计的 migration run ledger，并证明 Kafka retention、最大 lag、Android JSONL / DLT 容量覆盖“全量双发验证 + barrier + 完整 replay + 安全余量”。
- identity foundation 期间旧 writer 仍可能新增/修改行，所以这里只记录扫描水位，不宣称闭包；Phase 2B barrier 内必须最终 catch-up。
- 旧 `link_url` 只证明 code 曾存在，不能直接设 P.current invite。只有 preview.invite_code + invite_code_observed_at、明确命令回读或 invite changed 事实可初始化；同 JID 多 code 按事实版本选，完全同版本冲突进入刷新队列。
- 在正式 v2 replay 之前执行 MIGRATION_BASELINE_BACKFILL：为全部 legacy membership / 经第 10.3 节确认的 baseline evidence 建立低 authority B。state=2 合法 CURRENT token 写 current B 并把 S 固化为 CAPTURED/LEGACY_UNKNOWN；未绑定 / 已失效 token 写 LEGACY_RETIRED B，S 保持 DISABLED/NONE；state=1/3 的 current membership 只建 was=NULL 的 B。旧 writer 仍运行时只算预填，Phase 2B barrier 必须按最终水位重跑；不得把这一步推迟到 shadow backlog 消费之后。v1 新增真实 baseline 由 LegacyBaselineBridge 同步登记并处理，generation 推进会 invalidate 旧 token；watermark-only row 只迁 last_sync_requested_at。
- shadow 前逐 effect family 验证 v1 authority 已使用与 v2 相同的 stable eventId/sourceEventId 和 business_effect_key；从 shadow_start_offset 起，每个 effect-eligible v1 fact 都必须有 EMITTED 或 SUPPRESSED 决策，不允许以“没有任务”表达 not-applicable。
- G/I 显式沿用 legacy ID 后立即校验各目标表 `AUTO_INCREMENT > MAX(id)`，并在进入 Phase 2B 前再次校验；不能等易变事实回填后才发现新实时 insert 与保留 ID 碰撞。

退出门禁：initial mapping 覆盖率完成；所有影响 active / referenced row 的 CONFLICT 为 0 或有逐行签字处置；G/P/I/M/B/S 和两个受限 migration command 可重入；legacy baseline state/row 矩阵已逐账号分类，state=2 合法证据每条有唯一 token（CURRENT 为 S=CAPTURED/LEGACY_UNKNOWN + current B，LEGACY_RETIRED 为 S=DISABLED/NONE + 指定 retired B），state=1/3 不产生 baseline B=1；所有 effect family 已接入跨版本 tri-state admission；AccountBindingLifecycleService / LegacyBaselineBridge 以外的 S/B lifecycle 与旧 baseline writer 静态扫描和运行审计均为 0；所有目标协议版本都能携带绑定上下文；Web/Android 全部当前 binding 已对其 account/general event families durable 双发，全部 normal/direct/营销建群 executor 已对 normal-group result durable 双发，三 topic 各 family 从 start 起的 v1/v2 twin 对账闭合；正式 Reducer groups 未提交任何 v2 offset，且 migration ledger 已封存全部 shadow_start_offset，retention / replay 容量门禁通过。

### Phase 2B（备选，不纳入首期）：双发追平并切单一 writer

进入本阶段前必须取得第 19 节决策 7 的明确批准：多个 resolved aliases 的 folder/delete/remark 等本地语义归并到同一个 G，最终列表折成一个 GROUP；同时批准 v1 过渡期“id 保持原 alias、动作解析到 canonical G”的行为。未批准时最多完成 Phase 2A additive/shadow 准备，禁止切 writer，因为六表无法继续维护每个 alias 各自的本地当前值。

这一步不是让各业务 Service 永久“双写两套主表”：

- 所有群当前事实先进入一个 GroupFactReducer，只写六张权威表。
- 同一数据库事务内由 LegacyProjectionAdapter 根据新事实单向生成旧表兼容投影，供尚未切换的旧读路径和快速回滚使用。
- 禁止旧 Service 继续独立写 group_link_preview / health / membership / member_state。
- 投影失败时本地事务整体回滚，Kafka 消息不得 ack，由稳定 eventId 重试；HTTP 命令已经产生外部副作用时，必须由确认回读 / metadata 任务重放本地事实。以六表为主，旧表只是由 Adapter 计算的可重建副本。
- 每个 legacy 表标记 owner=projection，代码扫描门禁禁止新增直接 writer。

滚动发布必须使用数据库 / 配置中心统一的 writer epoch，不能让旧实例和新实例各写一套。进入 spool 前先完成所有可离线完成的 conflict/baseline/effect/容量预检。Phase 2A 已保证全部当前 binding 的 account/general families 与全部建群 executor 的 normal-result family 持续 v1+v2 durable 双发，不存在“切 writer 后再逐步开启 v2 producer”的事件空窗。barrier 内短暂停 AccountGroupSyncJob、GroupClassificationBackfillJob、GroupLinkHealthCheckJob、GroupMetadataSyncJob、GroupBatchTaskJob 及营销/拉群/四类建群等会读写群当前态或产生外部 effect 的 scheduler/worker，暂停群写 API，以及账号导入/批量删除、绑定/解绑/换绑、配对接管等所有入口；具体清单来自 Phase 0 writer/effect manifest，不能只靠这段示例。与此同时由后端签发 `CUTOVER_PENDING:{cutId}` 并命令 Web/Android 进入 CUTOVER_SPOOL。此后新形成的被动事实固化 pending token，按稳定 eventId 写本地 durable spool / journal但暂不发布；barrier 已关闭全部新 command admission，所以不得形成新的 pending command。barrier 前已 enqueue/dispatch 的命令继续携带其不可变 `LEGACY_SHADOW` token，完成后的 result 也原样双发，不能按“结果形成时间”改成 pending；此前 producer retry、DLT/JSONL 中的事实同样保持原 token，绝不能在 replay 时“升级”。

记录 LEO 前必须把 direct HTTP、普通建群、两类建群营销、join/pull/metadata/member/settings/revoke 等 manifest 中所有旧 epoch command 逐 commandId 清零：PREPARED 未执行项可确定取消；PUBLISHED/EXECUTING 项必须取得 durable terminal result 和 v1 authority 的 EMITTED/SUPPRESSED 决议；UNKNOWN 按 non-idempotent execution journal 自动/人工 reconciliation 后也必须有明确结论。任何无法在暂停窗口闭合的 command 立即 ABORT barrier，不能把它丢进普通 metadata 修复队列，也不采用“切后再给旧 command 临时转交 REDUCER”的隐式规则。等待这些旧 command result 的 v1/v2 publish ack、v1 consumers 全部 drain，并确认每个 LEGACY_SHADOW effect-eligible fact 的终态决策已经提交；Web 与 Android 都必须先通过 spool 容量、崩溃恢复和有序 replay 演练，无法耐久暂停发布就不能使用该 barrier。

Kafka LEO 采样前还要完成 producer barrier handshake。以后端冻结的 active session/node roster 为全集，每个 Web session、Android node/activation context 必须 CAS 持久化 pending epoch、关闭普通 publish gate，并回执 session/binding generation、spool identity + start watermark/checksum、最后已 broker-ack 的 v1/v2 topic-partition/record watermark；控制面逐项与 account+S 和 broker 对账。未回执、不可达或在回执水位后仍发布 LEGACY_SHADOW record 的节点都阻断 cut；只有先经 AccountBindingLifecycleService 合法 fencing/retire、且其 outbox/DLT/JSONL/spool 已闭合的节点才能从 roster 移除。不能只检查 Android 文件而忽略 Web worker/session。并发测试必须覆盖“ack 前最后一条 publish”和 ack 后旧进程复活，后者只能被 broker/consumer epoch fencing 拒绝。

随后记录每个 v2 topic-partition 此刻的 LEO，同时作为 exclusive shadow_effect_cut_offset 和 barrier_high_water_offset；它只是完整性 / 追平水位，不决定单条消息的 effect 权威。再从 Phase 2A 旧表扫描水位最终追平新增/updated/deleted 行，补齐映射/G/P/I/M/B/S，并逐账号核对 account、S 与协议当前会话的 armadaAccountId/protocolAccountId/bindingInstanceId/generation 四元组。接管前必须向 broker 重新读取 topic UUID/partition count/beginningOffset/endOffset，并逐分区证明 `beginningOffset <= shadow_start_offset <= shadow_effect_cut_offset = barrier_high_water_offset <= endOffset`；任一不成立立即中止，不能自动跳到 latest。

暂停窗口必须先按最终旧水位重跑 MIGRATION_BASELINE_BACKFILL，并逐个 state=2 合法 token 校验：CURRENT 类必须是相同 instance/generation 的 S=CAPTURED/LEGACY_UNKNOWN，LEGACY_RETIRED 类必须是 S=DISABLED/NONE 且 token 指定 B.retired_at 非 NULL；任何 generation 推进后的旧 token 都不得贴到新 S。再按规范 group_jid 比较该真实 JSON 集合与“token 对应 lifecycle 中 B.was_in_initial_baseline=1”的 count + order-independent hash，任何 unmapped JID、少行或多行都阻断；state=1/3 的 B=1 必须为 0，state=3 active account 仍须有 current instance。只有第 10.3 节所有 STATE_ROW/BASELINE_EVIDENCE conflict 清零或逐行签字，才满足正式 v2 replay 前置条件。保持正式 group 无 member，用 Kafka Admin API 把每个 partition 的 committed offset 可重试地 alter 到 ledger 中的 shadow_start_offset，再全量 read-back 校验；该 API 不承诺跨 partition 原子性，因此任一失败都保持 group 空闲并重试 / 还原到全部一致，不能启动半套 offset。

最终旧 writer 水位、冲突、baseline、legacy-authority tri-state decision 覆盖率和四元组全部通过后，在同一后端事务把 writer epoch 切为 REDUCER 并把 cutId 决议写成 COMMIT_TO_REDUCER；这是不可逆的 point-of-no-return，此后只能 roll-forward/rebuild，不能把 pending fact 改判 legacy。若在该事务之前任一门禁失败，则写 ABORT_TO_LEGACY 并把 run 置 ABORTING，保持正式 v2 group 无 member，把 pending spool 以原 token 双发，恢复 v1 consumer 处理并补齐 EMITTED/SUPPRESSED；随后用 Kafka Admin API 删除该正式 group 在三个 v2 fact topic 全部分区上由本 run 预置的 committed offsets（必要时删除空 consumer group），逐分区 read-back 为 absent，并把清理证据和 topic identity 写 ledger 后才转 ABORTED。清理失败不影响 v1 恢复，但 run 保持 ABORTING、禁止下一 run/ADOPT；不得把 stale dormant offset 当作新 run 的合法起点。COMMIT 成功后才以固定 group.id、`auto.offset.reset=none`、关闭 auto commit 的正常 subscribe/rebalance 模式启动，首次 assignment listener 必须断言 position 等于已写 committed offset。数据库事实与 legacy projection 事务提交后才手工 commit Kafka offset；重启和扩容继续从 committed offset，不再重新 seek shadow 起点。

正式 consumer 对每个 partition 必须严格顺序处理，或者只提交“所有 offset < N 都已完成数据库事务”的 contiguous next-offset frontier；禁止 async ack gap、禁止 batch 中高 offset 先成功就越过低 offset 失败项。rebalance revoke 前先停止 poll，完成可完成事务并提交连续 frontier，其余在途全部放弃、由新 owner 从未提交 offset 重放。对 `[shadow_start_offset, barrier_high_water_offset)` 的 catch-up 区间，任何解析、schema、epoch/fencing、Reducer、数据库或 effect admission 失败都暂停该 partition，修复后以同一 record/eventId 重试；不得用“重试耗尽→DLT→commit source offset”跳过。追平门禁要求该区间逐 partition 连续 committed、unresolved DLT/quarantine=0。

Reducer 从 shadow backlog 重放时，FactVersion/CAS 让旧 shadow fact 不能倒灌 barrier catch-up；任何 LEGACY_SHADOW 或 ABORT_TO_LEGACY pending record（即使 offset 因晚重投已越过 cut）都只复用 GroupEffectAdmission 的 EMITTED/SUPPRESSED 决策，缺决策即停分区，绝不自动补发；PENDING 无决议或 REDUCER record 位于 cut 前都视为 fencing 违规并停分区。COMMIT 与正式 consumer position 校验成功、所有在线 producer session 已确认 committed epoch 后，Web/Android 才退出 CUTOVER_SPOOL，把期间事实以原 observedAt/eventId 和 pending token 发布；Consumer 通过 COMMIT 决议把它作为 REDUCER authority，不能改写 token。离线旧 session 重连必须先刷新 epoch；携带旧/缺失/未知 epoch 的事实只进隔离与确认回读，不能默认为 REDUCER。新事务同时由 LegacyProjectionAdapter 生成旧兼容投影。各分区 committed offset 都达到或超过 exclusive barrier_high_water_offset 后才算追平并进入 live tail，最后恢复只会进入 Reducer 的 scheduler/API/lifecycle 入口；旧 v1 consumer 保持停写，协议 v1 镜像可在回滚观察期保留但不得再成为 writer。每次启动 / rebalance 都重新验证 topic UUID、partition count 和 `beginning <= committed <= end`；在追平前禁止 topic 扩分区或重建。若不能完成这个 barrier，或 retention 已越过 start，就必须使用已验证的 durable change journal / 备份重建；不能靠按 ID 回填猜增量，也不能使用 latest。

barrier 前保存旧当前态一致性快照、Kafka consumer offsets、protocol_command_outbox 水位及 Android JSONL 水位；观察期所有 HTTP/scheduler/protocol 事实必须可由 Kafka/命令 outbox 或新增的 durable fact change journal 重建。journal 若需要新增，只保存不可变 fact envelope、offset 和处理结果，不保存另一份“当前群值”，因此是过程审计而非第七张权威表。切换后强制所有当前绑定账号各跑一次 FULL_ACCOUNT_SET+complete 快照；barrier 期间 dirty、命令在途或 metadata 未完成的群进入 metadata / full-member 修复队列。v1 晚到结果只允许结算原任务并触发确认回读，禁止直接写 destructive presence、role、current invite 或 baseline。

v2 Consumer binary 可以提前部署，但正式 production consumer group 在 Phase 2A shadow 期间不得 subscribe、ack 或 commit；producer 已全量 durable 双发，独立 audit group 只做契约 / fencing 验证。切 REDUCER epoch 后，正式 group 严格按上述 Admin alter-offset + read-back + subscribe-position 校验协议接管，并先追 barrier high-water，再正常提交六表事务。禁止使用 `latest`、自动 offset reset 或“写 flag 关闭但仍 ack”跨过 shadow backlog；barrier 后只允许 reducer read/consumer canary，不能再用 producer canary 补齐此前未产生的消息。

投影必须按事实域处理一对多 legacy 映射：group 资料更新所有映射到该 group 的旧 preview，但不覆盖各自行的真实 link_url；P.current_invite_id 只把当前 code 投影到 designated primary legacy 行，invite 有效性更新映射到该 invite 的旧 health；账号关系优先复用该 account 原 membership 行，没有时只指向该 group 的 primary legacy 行。迁移期新发现但没有 legacy 行的群，Adapter 可以只在旧表生成 `wa://group/{jid}` 兼容行，假 URL 永远不能反向进入 I。

LegacyProjectionAdapter 必须显式处理新旧列宽，不让 MySQL strict mode 的 DataTooLong 毒死整个事实事务：subject 255→旧 group_name 128、avatar 1024→旧 preview.avatar_url 512 仅允许按 Unicode code point 安全截断并记录 projection_loss；group JID 128→旧 preview/join-result 64、新 snapshotId 128→legacy cache.snapshotVersion 64、participant JID 191→旧 snapshot 128、invite code 128→旧 preview 64 都是机器 identity，绝不截断。identity 无法表示时跳过该旧列/旧子投影并让新版本兼容读直接取六表，记录指标和修复队列；不得因旧镜像列宽让 Kafka 分区无限重试。任何 identity projection loss 会自动取消“回滚旧 binary”的资格，只允许新 binary 的 read-flag rollback，直到旧结构已扩宽或回滚窗口结束。

旧结构无法无损表达 LEGACY_UNKNOWN baseline、独立 presence/role clock、membership epoch 和 PN/LID 合并。Adapter 对这些事实只做保守投影：未知不写成 false、role 不改变旧 is_in_group、新 epoch 先清旧 role；原有 LEGACY_UNKNOWN baseline 在兼容期冻结，不由未知集合覆盖。writer 切换后新账号形成 EXPLICIT_COMPLETE baseline 时，则必须在同一六表事务的 legacy projection 阶段原子生成旧 account_group_baseline JSON 并更新 account.group_baseline_state，使旧营销 reader 不会把新账号误判为未拍基线。因此“快速回滚”是旧 API 的保守兼容，不是把旧表重新提升为事实源；回滚期间的新事实仍留在六表，修复后重放。

这是有截止日期的迁移设施，不是最终模型。所有消费者切完且 Phase 6 的 typed-reference / queue 水位门禁全部通过后，才删除投影和旧表。

退出门禁：writer barrier 有审计时间点；全部实例为 REDUCER epoch；直接旧 writer 扫描 / 运行指标为 0；事件重放、乱序和旧投影回归通过。

### Phase 3：实时 writer 收敛后回填易变事实

- Phase 2M 前必须已迁完所有当前读接口必需字段；本阶段只修复不影响正确读取的低置信/缺失 metadata 和明确冲突，不能把核心字段回填推迟到读切换后。
- 以 Phase 2M（或另行批准的 Phase 2B）writer barrier 后的一致快照按 tenant_id + legacy id 分片、短事务修复；新事实已走 Reducer，不存在“扫过后旧 writer 又改”的窗口。
- 身份与 legacy baseline 地基已在正式 replay 前完成；本阶段只按 P 易变字段 → 其余 participant M 的顺序回填。B 与 S 不再接受普通 Phase 3 migration 写入；当前 binding 关系只由已接管的实时 fact 更新，旧 membership/baseline 漏项必须退回 barrier 修复，不能在 REDUCER epoch 下临时扩权。
- 再次校验六表 `AUTO_INCREMENT > MAX(id)`；回填重跑只能按 canonical key / legacy 映射 upsert，不能因自增游标变化生成第二套 ID。
- backfill 每个字段必须使用旧表可证明的原事实时间；没有可信时间时 observedAt=0、source=MIGRATION_UNKNOWN。绝不能用迁移执行 `now()`，否则“低 priority”仍会因 observedAt 更大而压过实时事实。
- backfill 只写 MIGRATION_BACKFILL / MIGRATION_UNKNOWN 版本，并通过同一个 Reducer CAS，不覆盖 Phase 2 后更高版本的实时事实。
- 输出冲突表或结构化报告：JID 冲突、code 冲突、群名冲突、PN/LID 冲突、人数冲突、baseline 完整性未知、孤儿任务引用。
- 不在迁移脚本中远程调用 WhatsApp；需要刷新确认的行进入受控任务队列。
- 结束前从 barrier 水位重扫所有 updated_at / deleted_at 可判定旧行并做全量 key 对账；Phase 2M barrier 后发现任何旧当前态写都立即停止迁移并告警。

退出门禁：所有可确定数据迁完，异常数量和处置人明确，ID resolver 覆盖率达到 100% 或有逐行豁免，实时写与 backfill 并发测试无倒灌。

### Phase 4：影子读

- 本阶段实际执行时间在 Phase 1 预填完成后、Phase 2M 暂停切换前；章节编号只表示验证职责，不表示必须晚于 writer cut。切换后可继续运行同一对账，但不能拿旧表做在线 read rollback。
- 对采样请求或离线固定请求集同时执行旧查询和新查询，用户响应仍取旧结果；不能让每个线上请求都额外执行当前约 1.2 秒旧 SQL。采样器有并发上限、DB CPU/慢查询熔断和租户配额。
- 逐字段比较 groupJid、subject、memberCount、当前 invite、群状态、管理员、可用管理员、历史/上控标签。
- 差异按“允许的语义修正 / 数据异常 / 新模型 bug”分类，不能只比行数；I-only 保留、resolved legacy duplicate 折叠、membershipState 新口径和 syncProtocolMask deprecated 各有独立类别。
- 记录旧/新 SQL P50、P95、P99、扫描行数、临时表和排序情况；日志不记录完整成员或敏感 payload。
- 每份对账报告固定源表扫描水位、Kafka offset/outbox 水位和 migration run id，输出逐租户 canonical count/hash、软删、跨租户孤儿、typed-null 引用及 expected conflict；连续多个完整业务周期 unexplained diff=0 才能切读。

退出门禁：核心事实无未解释差异，性能达到第 16 节门禁。

### Phase 5：按域切读

下列顺序用于 Phase 1 分域开发、测试和 shadow 验证；Phase 2M 暂停窗口必须一次切完全部当前态 reader，不能在恢复写入后继续让未切域读取冻结旧表。第 19 节决策 7、alias→G 对账和前端 capability 契约必须在暂停前完成，才允许启用 collapsed typed `/api/group-resources`。

建议顺序：

1. 群详情和单群成员读取；
2. 执行账号选择；
3. 账号群数量与营销账号树；
4. 历史群和上控后逻辑；
5. 群组列表方案 A 切 typed `/api/group-resources`（列不变，ID/row type 明确）；
6. 批量刷新、导入链接和任务候选；
7. 导出与所有历史任务 enrichment；
8. 用户另行批准后才启用方案 B 前端。

每个域保留独立 feature flag 供切换前 shadow 和故障定位；Phase 2M 的生产切换由总门禁原子校验所有 flag。恢复新 writer 后不得单独把某域切回旧当前态表。

### Phase 6：删除冻结旧表

- 本阶段再次要求第 19 节决策 7 已明确批准；未批准不得删除 Resolver 或宣称模型迁移完成。
- 逐表确认所有仍需打开的历史/活跃行 typed ID 已回填；若任何合法历史仍只能靠 legacy ID，先长期落最小 legacy_group_link_compat，并把通用 Resolver 收窄为只读 HistoryCompatResolver；不能删除解析能力后留下打不开的数据。
- protocol_command_outbox 中 GROUP_LINK 引用/旧 payload、v1 topics、Kafka DLT、未确认回执与 Android JSONL 必须全部投递、迁移或按审计策略封存且可解析；水位未闭合不得 drop。

Phase 6A 是 cleanup binary 发布，旧表仍保留：确认 Phase 2M 后至少两个完整业务周期没有旧当前态读写，再删除 / 禁用所有 runtime legacy current reader、writer、Entity、Mapper、通用 LegacyGroupLinkResolver 和 deprecated API 字段，全量滚动到确认不存在旧 binary 的版本。若存在 `legacy_group_link_compat`，只读 HistoryCompatResolver 作为显式白名单保留，只能访问该最小映射表 + typed 历史详情，SQL digest 不得访问待删旧当前态表；没有 compat row 时连该 resolver 一并删除。静态全仓扫描、运行 SQL digest、数据库审计和应用指标连续至少两个业务周期均证明旧当前态表/字段访问为 0。

Phase 6B 才是独立 DDL release：在 6A 门禁、逻辑备份、按表恢复演练和用户再次确认全部通过后，单独执行 Flyway 删除旧表（account_group_baseline 删除时一并移除其 last_group_sync_requested_at）及 account.group_baseline_state。DDL 完成后再重跑数据模型生成器更新 `.harness/wiki/数据模型.md`。不能让应用启动时的 Flyway 先删表、而滚动集群中仍有旧实例访问；6A 与 6B 不得合并为同一次发布。

删除旧表不可与第一次切读放在同一次发布。

## 14. 部署顺序与版本兼容

### 14.1 推荐顺序

1. 发布六表、最小 migration run、`group_snapshot_effect_outbox`、兼容列和不依赖数据清理的 additive Flyway；旧业务仍只读写旧模型。
2. 部署新 Mapper/Reducer、canonical 事件字段兼容和全部新读 SQL，但保持新 writer/read flags 关闭；协议端先补 complete/skippedCount/queryStartedAt、binding instance/generation 等本模型必需字段，不新增 v2 topic 双发。
3. 执行 Phase 1 可重入预填和 Phase 4 shadow read；完成 test1 MySQL RR/SQL 数、列表性能、业务回归、count/hash/conflict、baseline 与迁移营销零增量门禁。
4. 用户明确批准决策 7 的 canonical mutation + duplicate collapse 语义，并完成 alias/typed resource 前端兼容；未批准不进入切换。
5. 执行 Phase 2M：暂停全部群 writer/reader 入口和外部 effect，排空 Kafka/outbox/在途命令，按最终旧水位补增量并重跑门禁。
6. 在恢复流量前同时切换唯一 writer 和全部群当前态 reader 到六表；任一 flag/健康检查失败就整体退回旧模型，不能恢复半套。
7. 恢复流量后强制当前账号完整快照和 dirty 群修复，只允许 roll-forward；持续观察 DB 锁、SQL 数、effect outbox、Kafka lag、列表性能和业务差异。
8. 完成低置信 metadata 修复和至少两个完整业务周期观察后，按 Phase 6A 清理旧代码；Phase 6B 删除旧表必须另行确认和独立发布。
9. 若以后批准零停机或方案 B，分别另立 ADR/前端方案，不回填到本首期范围。

后端必须先于协议生产者部署，确保新增字段和事件先有兼容消费者。协议回滚到旧版本时，缺失 complete / generation 的事件只能作为 partial、非破坏性观察。

若现有 v1 topic 无法在 consumer 全量升级后安全承载 canonical 字段，可在独立协议 ADR 中决定是否升 v2 topic；本模型只要求 writer 切换前 producer/consumer 对 complete、skippedCount、queryStartedAt、fieldMask、binding instance/generation 和稳定 eventId 的契约一致，不预设必须三套 topic 双发。

### 14.2 回滚

旧表尚未删除时：

- Phase 2M 尚在暂停窗口且未恢复新 writer 时，writer/read flags 必须整体退回旧模型，不能只退一半。
- 一旦恢复新 writer，旧当前态表已冻结，不能再作为在线 read rollback。查询/VO bug 通过关闭有问题入口、修复新 SQL并 roll-forward；不得让该域回读冻结旧表。
- 若 Reducer 有问题，立即暂停对应 consumer/HTTP writer，保留 Kafka offset和 outbox 水位；以切换前旧表快照、六表和事件日志在临时表对账，由修正版 Reducer 重放/重建六表。旧表只是恢复证据，不重新成为主值。
- 前端方案 B 只需关闭租户 flag 回 v1 adapter；
- 不逆向手工改共享库，不回滚已执行的 additive Flyway。

WhatsApp 命令已产生的建群、进退群、升降权、revoke 等外部副作用不可通过数据库回滚撤销；只能保留命令/回执证据并执行确认回读或经人工批准的补偿命令。回滚手册必须把 read rollback、data rebuild 和 external reconciliation 分成三个入口，禁止一个“回滚”按钮混做。

旧表删除后：

- 只能从备份恢复到临时表并重新投影，不再承诺秒级切回；
- 因此 drop 迁移必须在独立发布、完整恢复演练和用户确认之后执行。

## 15. 数据对账与硬门禁

### 15.1 结构和身份

- tenant_id + group_jid 重复数必须为 0。
- tenant_id + invite_code 重复数必须为 0。
- 每个 profile 最多一个 current_invite_id；非空指针必须指向同租户、同群、未软删 invite；同一 invite 不得被两个 profile 指向；state/pointer CHECK 必须成立。
- profile、invite、participant、binding 的 group_id 必须能找到同租户 group。
- binding.participant_id 必须与 binding.group_id、tenant_id 一致。
- participant 的 PN/LID 至少一个非空；同群 PN 和 LID 各自重复数为 0。
- 同租户同 protocol_id + protocol_account_id 的 active account 最多一个；deleted account 不得持有 current S binding。
- 每个 canonical G / I 在回滚窗分别恰好一个 designated group/invite primary；两者物理分离；新 I 的 legacy invite alias 不依赖 I.id 数值且真实 URL/code 一致。
- 每群满足 IN_GROUP + current role epoch 的 exact OWNER 最多一个；多 OWNER 必须为显式冲突而非任选其一。
- 六张权威表和 typed API 中任何 wa://group/ 值数量必须为 0；冻结旧 `group_link.link_url` 中既有的兼容 alias 单独计数，不得反向进入 G/P/I/M/B/S。

### 15.2 状态和快照

- role 事件重放前后 presence 完全相同。
- DEPARTED_UNKNOWN→IN_GROUP 必须 epoch+1 且旧 role 不复活；LEFT/REMOVED/UNKNOWN 三类 last-exit 与 current presence 分开 CAS。
- partial account snapshot 不减少 IN_GROUP 数量。
- explicit complete + skippedCount=0 的空快照可以把该账号旧关系标缺失。
- complete + skippedCount>0 不得标缺失。
- 旧 bindingGeneration 事件不得更新六表。
- 同一 eventId 重放后业务列、fact version、updated_at 均不变且 affected rows=0；不能以 JSON 序列化或物理页“字节级”作为测试口径。
- “快照查询开始→ADD 提交→不含该成员的快照完成”保留 ADD；“快照查询开始→REMOVE 提交→仍含该成员的快照完成”保留 REMOVE。账号 FULL_ACCOUNT_SET 和单群 member snapshot 各覆盖这两类逐行 CAS 并发用例。
- profile.member_snapshot_id 对应的成员批次提交要么全部可见，要么全部不可见。
- CAPTURED + EXPLICIT_COMPLETE baseline 必须同时有 header；空 baseline 允许 0 条 INCLUDED binding。
- LEGACY_UNKNOWN baseline 不得把未列出的群写 was_in_initial_baseline=0。
- 迁移后 `COUNT(*) WHERE was_in_initial_baseline=1 AND first_post_control_observed_at IS NOT NULL` 必须为 0；更强不变量 `first_post_control_observed_at IS NOT NULL AND (was_in_initial_baseline IS NULL OR was_in_initial_baseline<>0)` 也必须为 0。
- 本期三种 migration origin 写入的 B 中 first_post_control_observed_at 非空行数必须为 0，对应 version key 必须全为 `0x00`；任何试图在 migration 中建立 was=0 的输入必须被 admission 拒绝，不为其增加手工 writer。实时 REDUCER 以后写入的非空 first-post 必须对应同 lifecycle 的 EXPLICIT_COMPLETE baseline，同时满足 incoming key 大于 S.baseline_fact_version_key、occurredAt/queryStartedAt 严格大于 S.baseline_captured_at，且该 B 在事务普通读时物理不存在；不得与 B.baseline_captured_at 比较。
- migration/backfill 产生的 `group_snapshot_effect_outbox` 行、“上控后新群立即营销”task、send attempt、protocol outbox/命令增量必须全为 0，不得 wake worker，不得在切换后扫描 B 补发；被 origin gate 排除的数量只记迁移指标。
- state=3 + active binding 必须保持 current instance 且为 baseline_filter_enabled=0 + DISABLED/NONE；不得误当“账号未绑定”。state=1/3、WATERMARK_ONLY、AMBIGUOUS_EMPTY_BASELINE 的 JSON 不得生成 baseline B=1。
- Phase 2M migration run lease 失效后用同一 runId 幂等续跑；明确 FAILED 后的新 run 必须重新验证全部 source watermark/count/hash/conflict，不设计 predecessor ADOPT 或跨 run 转移 binding owner。
- 以下 CUTOVER_PENDING/LEGACY_SHADOW/offset 门禁仅适用于另行批准的零停机 Phase 2A/2B；首期 Phase 2M 不创建这些 token，也不以它们作为通过条件。
- ABORT_TO_LEGACY 后 run 必须经过 ABORTING 清理本次预置的正式 consumer offsets，read-back absent 才能 ABORTED；下一 run/ADOPT 不接受 stale dormant offset。
- `[shadow_start_offset, barrier_high_water_offset)` 任一 poison record 不得通过 DLT+commit 跳过；各 partition 只提交连续完成 frontier，追平时 unresolved=0。
- account group-sync、general group、normal-group result 三个 v2 topic 都逐 partition 满足同一 identity/start/barrier/ABORT offset 门禁；漏任一 topic 即阻断。
- normal/direct/两类营销 create-result 在 task missing/terminal/step mismatch、consumer crash、解绑/换绑、HTTP 响应丢失下仍只建一个 G；retired command instance 不污染当前 S/B/effect。

### 15.3 业务数量和引用

- 旧真实群 JID 到新 groupId 解析率为 100%，冲突全部有人工结论。
- 旧真实邀请 code 到新 inviteId 解析率为 100%。
- 活跃任务中每个需要当前群的 legacyGroupLinkId 都能解析到 groupId 或被标为 invite-only。
- 各账号当前群数、各群受控账号数、管理员数、历史群数、上控后群数逐租户对账。
- 导入批次成功/失败/重复数不因拆分 group 与 invite 改变。
- I-only 资源在方案 A 不消失；sourceFileName 的 count/page/enrichment 选择同一最近成功批次，重复失败导入不切换、删除后重导入切换。
- 同一 G/I 的多个 legacy aliases 同批提交只产生一个 canonical work item/effect；requested/resolved/canonical count 与每个 CANONICAL_DUPLICATE alias result 对得上，folder/refresh/delete/marketing 各按既定原子性处理。
- Phase 2B 后新增/收编 I 同事务取得 invite primary legacy alias，v1 import/select 与旧 binary task 双填可用；hide→ADOPTED、resolve G→archive G、retention GC 不漂移/误删 alias。
- 历史任务详情、导出和重试读取的冻结快照不改变。

### 15.4 列表响应

- 方案 A 下旧 VO 保留字段逐页对账；resolved duplicate 折叠、membershipState 新口径及明确 deprecated 字段必须进入批准过的 expected-diff 清单，其余差异为阻断项。
- count 与 page 使用完全相同筛选，最后一页无重复/漏行。
- v1 OFFSET 分页只承诺单次查询排序稳定；翻页期间有新插入属于弱一致，文档/API 明示可能位移。canonical API 使用 `(createdAt,id,resourceType)` cursor 或 snapshot token，验收同一 token 内无重复/漏行。
- 无管理员、无 profile、无 invite、软删 folder、空成员、未知状态均有 null fallback。

## 16. 性能目标

test1 在 2026-08-15 的只读抽样是活动系统快照，数量会随事件变化：

- group_link 约 1.1 万行；
- account_group_membership 约 4.7～5.1 万行；
- whatsapp_group_member_snapshot / 当前成员数据约 43.8～45 万行；
- 大多数已解析群使用 wa://group/{jid} 假 URL；
- 真正未解析 / 导入邀请目标约 87 条；
- 当前无筛选 count 约 1.38 秒，分页约 1.23 秒；
- 去掉全量成员和账号聚合的瘦查询约 32 毫秒，简单 count 约 5 毫秒。

目标门禁：

- 默认列表 count 不允许访问 participant、binding、account_state、metadata task。
- 默认页基础查询只访问 G/P/current I/folder，复杂 enrichment 只处理 page IDs。
- test1 默认列表 P95 目标小于 200 毫秒，count P95 目标小于 100 毫秒。
- 启用管理员 / 可用账号等高级筛选时必须使用 EXISTS 和组合索引；EXPLAIN 不允许先对全租户 participant 做 GROUP BY。
- 国家/大洲筛选必须在约 45 万 M 行克隆数据上证明命中 phone_country_iso2 索引；不得 page 后 Java 过滤。
- 400 群的单账号完整快照，从进入 Reducer 到该事务提交，MySQL 可见 SQL execute / 往返总数必须 `<=10`；0/1/400 群都要计数，且 SQL 数不得随群数 N 线性增长。这 10 条包含 account+S 锁定、existing/missing 批量读、G ID 解析、G/P/M/B 批写、缺失 CAS、S header/baseline 和 durable effect intent；普通账号 SUMMARY 快照没有 invite 事实时不应写 I。

不增加第七张当前事实表的一个可实现预算如下；其中第 10 条是已明确披露的非权威过程 outbox。口径是单次正常 attempt，包含 Reducer 内所有递归 Mapper 和 snapshot effect outbox，不计 BEGIN/COMMIT 与提交后 worker；实施可以减少或用等价 SQL，但不得超过总数：

| # | SQL 批次 | 硬约束 |
|---|---|---|
| 1 | 同一条 locking read 锁 account + S 并校验 fencing | 用 `STRAIGHT_JOIN`/等价方式固定 account→S 的 access order 和唯一索引，再以 plan guard + MySQL 并发测试证明；S 必须在快照前已存在，缺失就拒绝，不在快照路径 UPDATE-miss/INSERT S |
| 2 | 普通一致性 bulk read | 同一条不带 FOR UPDATE 的 SELECT/CTE 返回两支：输入 JID LEFT JOIN G/P/self-M/current-B 得 existing/missing+版本；该 account + current generation 的全部 B→M 与输入 anti-join 得 missing participantId。不得只查输入 JID，否则永远看不到 missing |
| 3 | G multi-row IODKU | 按 `(tenant_id,group_jid)` 完整排序，missing 不先 UPDATE |
| 4 | G ID current / locking bulk read | 按自然唯一键排序，解析并发 winner；后续全部使用这批 groupId |
| 5 | P multi-row IODKU | 按 groupId 排序，字段 version CASE 在 SQL 内完成 |
| 6 | self M multi-row IODKU | 该快路径只使用 account 已确认的 self PN，按 `(tenant_id,group_id,pn_jid)` 排序；不在账号快照中混写 PN/LID 或做 alias merge。alias 冲突拒绝/隔离到专用 identity reducer，不逐群查询 |
| 7 | missing M 主键定点 CAS UPDATE | 用第 2 条得到的 participantId 升序列表做单表 `UPDATE M ... WHERE tenant_id=? AND id IN (...) AND version_key<? ORDER BY id`，UPDATE 内重验版本；禁止 UPDATE JOIN B、范围扫 B 或无序 multi-table update |
| 8 | B `INSERT ... SELECT M ... IODKU` | SELECT 部分就是第 7.4 节要求的 M current/locking bulk read：以输入 groupId + confirmed self PN 对 M 完整唯一键做 exact lookup，固定/验证访问计划，只命中第 6 条已持锁的 present-M 行；先解析/锁定全部 participantId，再按 B `(tenant,account,generation,groupId)` 排序写，禁止流式 M1→B1→M2→B2 或先碰 B 后再取新 M 锁；不另加 M/ID 查询 |
| 9 | S header/baseline UPDATE | S 已由第 1 条持锁；不是 B 后新取 S 锁 |
| 10 | `group_snapshot_effect_outbox` 批写 | metadata/营销只写稳定 intent，worker 提交后扇出；无 effect 时跳过 |

返回快照用第 4 条解析的 groupId 与本次内存输入组装，不再做逐群 `selectActiveById`。若实现无法同时证明这个预算和第 7.4 节的 RR 锁规则，则设计尚未通过；不得为凑 `<=10` 放弃防死锁约束。

- 所有群行必须用 multi-row / set-based SQL，业务循环只能组装参数，不得在循环内调 Mapper / Service。禁止逐群 registry、classification、profile、health/binding 写入和 `selectActiveById`；ID 与返回快照用一次批量查询解决。
- 验收以 datasource execute 计数和 MySQL 服务端 statement/往返观测双口径为准。仅把 N 次 `addBatch` 包在一次 Java `executeBatch` 里不算通过；必须证明 driver 重写为有界 multi-values SQL，或服务端实际执行的 statement 也不超过 10。超过上限时不得以“已禁 N+1”豁免，必须在设计评审重新给出数字和原因。
- 账号快照事务只能用一次 set-based 写入 `group_snapshot_effect_outbox`，由提交后 worker 再展开为发送尝试。现有 `MarketingNewGroupImmediateSendServiceImpl.claimImmediateAttempts` 的逐 candidate `insertSendAttempt` 不得在 Reducer/快照事务内复用，否则 SQL<=10 和事实锁序同时失效。
- 单群完整成员快照同样使用同一事务内的有界 multi-row / set-based SQL，禁止一个超大 IN、逐行 N+1 或分段 commit；其 SQL 预算在实施计划中按成员上限单独定数，不把本节的账号 400 群门禁套用为伪数字。
- MySQL 克隆回填必须量化 M 增加 compact keys、country 投影和二级索引后的 data/index bytes、redo/undo/binlog 增量、buffer-pool 命中与复制延迟；预留不足禁止在 test1/生产执行。
- 在线 backfill 逐批记录 rows/s、锁等待、deadlock 和 replica lag，达到阈值自动暂停；shadow read 采样有熔断，不能把旧慢 SQL流量翻倍。
- 线上门禁同时观察 DB CPU、锁等待、死锁、Kafka lag 和 reducer reject 指标，不能只看接口耗时。

## 17. 测试策略

### 17.1 后端

Service/Mapper 快速测试继续使用 test scope H2 MySQL 模式、真实 Mapper XML、MyBatis-Plus 租户插件和 Spring 事务；数据库发布门禁使用 MySQL 8.4.8 Testcontainers 和 V116 结构快照。JSON、CHECK、ascii_bin 大小写、NULL 唯一、generated key、在线 DDL、行锁/死锁绝不以 H2 结果代替；test1 只做在已通过容器/克隆验证之后的最终验收。

必须新增或改造：

- 六表 schema、注释、索引和租户隔离测试；
- group/invite typed ID 和 legacy resolver；
- 每字段版本向量、null / field mask、乱序和重放；
- account 完整/部分/空/跳过快照；
- PROTOCOL_FACT 四元组 fencing：同 generation 但 armadaAccountId、backend、protocolAccountId 或 bindingInstanceId 任一错误都隔离；deleted account 永不写六表；
- baseline PENDING、EXPLICIT_COMPLETE、LEGACY_UNKNOWN、DISABLED；
- participant presence / role 独立更新；
- account/group 完整快照逐行缺失 CAS 与查询切点后 ADD/REMOVE 并发；
- MySQL 8.4.8 RR 下复用现有 `AccountGroupSyncMySqlConcurrencyTest` 的 supremum 复现器，覆盖反向输入、重叠 missing key、existing+missing 混合、空/非空快照和连续多轮；另覆盖并发创建同一 G winner 与同一 self-M winner，证明第 4 条 G current read 和第 8 条 B `INSERT ... SELECT M` 的 exact current/locking source lookup 都能取得 winner ID，且不会在 B 后新取 M 锁。新六表 Mapper 必须保持“普通读分类 + 按表/唯一键排序 + 不 UPDATE 缺失键”，H2 不代替该门禁。
- 使用真实 Mapper 和事务对 0/1/400 群完整账号快照做 SQL 计数；400 群至少分别覆盖全存量、全新增、200 existing + 200 missing 混合（同时有 missing-CAS 和 effect）三种形态，每组列出实际 MappedStatement/服务端往返预算并且均 `<=10`。返回行数、缺失 CAS、baseline 和 effect intent 必须正确，MySQL 服务端不得看到被 `executeBatch` 隐藏的 N 次 statement。若混合形态不能同时通过 SQL 与死锁门禁，必须回到设计评审，绝不能为凑数字删掉 RR 规则。
- legacy joined_at 迁移 fixture 必须覆盖：baseline 内群即使 joined_at=首次快照时间或 joined_at>baseline 也保持 was=1 + first-post=NULL；was=NULL、缺 baseline time、joined_at<=baseline 全为 NULL；本期任何 legacy 行均不回填 first-post，伪造 was=0 的 migration 输入必须拒绝。切换后再单独测：EXPLICIT_COMPLETE 的 S/B baseline 时间精确等于 queryStartedAt 而不是 completedAt/now；事务预读时 B 物理不存在的群可由更晚的明确 JOIN/ADD 或合格实时 FULL_ACCOUNT_SET 以各自 fact time 建立 was=0/first-post；同毫秒、partial/delta 均不能。迁移时已存在且 was=NULL 的 B 在切后首个及重复完整快照中始终保持 NULL、无营销。三种 migration origin 在任务写入前都固定 SUPPRESSED，即时营销的可执行 effect/task/send attempt/protocol outbox/命令增量必须全为 0。
- PN、LID 先后到达和双行合并并发；
- suspended、terminated、invite invalid、account banned 分域；
- invite pool 隐藏、label batch delete、协议再次观察和真正 revoke 分域；current pointer 不悬空；
- group 删除不复活；
- metadata 原子快照和失败保留旧快照；
- 列表 count/page/enrichment、复杂 EXISTS、租户隔离；
- I-only union、sourceFileName 最近成功来源、国家/大洲 SQL 下推、受控管理员隐私口径、membershipState 优先级；
- 账号群计数、执行账号选择、营销、历史群、导出；
- backfill 可重入、旧 member_state source 白名单、typed-ID 唯一碰撞、projection 列宽损失、shadow compare。
- GroupClassificationBackfillJob/Service 在切换时永久停写旧 is_historical/is_post_control；新标签只由 B/S 派生，scheduler 不能继续抢回主值。
- MySQL 8.4.8 上验证 S 四态 + binding CHECK、普通 FactVersion `0x00`/118-byte key、baseline 单字节 `0x01` sentinel、nullable 分支 SQL UNKNOWN 不能漏过。
- active protocol 重复 dry-run/处置、入口暂停期间无新绑定、generated 唯一键安装失败保持禁写、建键成功后 lifecycle 并发只能一个 winner；首条 S 早于唯一键的测试必须失败。
- legacy baseline state/row provenance 矩阵、state=2 真实空集合签字、state=1/3 与 WATERMARK_ONLY 禁止写 B=1、LEGACY_RETIRED synthetic lifecycle。
- Phase 2M migration run 的 PREPARING/RUNNING/READY_TO_CUT/COMPLETED/FAILED、同 run 续跑和水位/count/hash 重验；零停机备选 Phase 2A/2B 若另行获批，再单独测试 effect epoch、双发/COMMIT/ABORT、offset cleanup/ADOPT 和连续 offset，不计入首期通过条件。
- GROUP / I-only capability matrix、混合批量预检、v1 alias row-key 唯一、typed resourceKey、隐藏重导 SUCCESS+ADOPTED 与活跃重复 FAILED+DUPLICATE。
- group terminal/current invite/probe/admin 的 capability reasonCode 与创建任务前锁内复验；BANNED/UNAVAILABLE 不刷新、LINK_INVALID 允许刷新。
- 所有 batch action 的 canonical alias 去重：同 G 多 alias、同 I 重复输入、混合类型/无权限；requested/resolved/canonical/total 与逐 alias result、task 唯一键、effect key 一致。
- 新 I legacy invite primary 创建/复用与 group primary 物理分离；hide→ADOPTED、resolve 后归档 G、历史引用/GC 和旧 binary 双填。
- I check 的 SUCCESS/TARGET_TRANSIENT/EXECUTION_FAILURE + error domain/CAS 矩阵；`/group-links/batch-preview` adapter 与 typed batch-check 同结果，账号/投递失败不产生 UNAVAILABLE。
- Android history/depart/join 与 Web participant add/remove 的逐字段映射；DEPARTED_UNKNOWN、每成员 fact time/sourceEventId、presence/join/exit 独立 CAS、LID+phone 不猜 PN、generic observer 不改自身 B/S。
- normal/direct/group-creation-marketing/group-pull-marketing 四类现有成功结果必须进入 Reducer：CREATED_PARTIAL、空/不完整 participant result、confirmed settings mask、外部联系人不建 B、受控 participant 逐人 fencing；task terminal/step mismatch 不能跳过已确认群事实。
- create result 在执行前/后换绑的测试：四元组仍 current 才写当前 B；stale command 只 quarantine + 确认回读，不为 retired generation 建 B、不触发 current effect。GROUP_LEAVE 后任务收尾不能把 creator 复活 IN_GROUP。
- country resolver/hash/reindex：libphonenumber 依赖或 active country 变化、旧 hash 视 unknown、列表 count/page/export 同口径；prefix mapping 变化不改 confirmed-phone 投影。
- 另行批准零停机/命令可靠性 ADR 后，才增加 durable create journal、Idempotency-Key 全覆盖、三 v2 topic listener/twin、manual contiguous commit 和 v2 durable ack 测试；这些不属于首期通过条件。
- legacy_group_link_compat 存在/不存在两路 cleanup：只读 HistoryCompatResolver 仅能打开已结束历史，当前列表/任务/写 API 命中它必须拒绝，待删旧表 SQL digest 仍为 0。

现有 group、account、marketing、task、normal creation、historical、export 的 DbTest / Mapper / Service 测试都属于回归范围，不能只跑 group 包。

### 17.2 Web/Baileys 协议

- account.groups_reported 必须携带 snapshotId、complete、skippedCount、generation、稳定 eventId。
- groups.update / upsert、dirty、online sync 的 complete 语义逐来源测试。
- participant add/remove/leave/promote/demote 不混淆 presence 与 role。
- Web `group.participant_changed` add/remove 不再被 Java 忽略；self membership twin 共享事实时间/sourceEventId，乱序不重复增 epoch。
- invite_link_changed、health_reported、metadata_updated 的 observedAt 和 field mask。
- Kafka 重试不能生成新的业务 eventId。
- normal create 的现有结果必须明确 createOutcome、creatorIdentity、participantResultsComplete/逐项结果、confirmed settings mask/role changes；不能从 requested 值或整体 success 猜当前事实。
- 若独立协议 ADR 决定启用 `openapi/protocol-v2.yaml` 和三套 v2 topic，再增加生成类型/fixture/checksum 与 durable ack 门禁；首期只验实际启用契约。
- 零停机备选若获批，CUTOVER_SPOOL 的容量、崩溃恢复、session epoch 和重放测试另列 ADR；首期 Phase 2M 不建设该设施。
- 日志只记数量和 ID，不记录群成员 payload。

### 17.3 Android Zhuan

- HistorySync 与 WGP2 的 add/remove/leave/suspended/terminated。
- account.group_past_participants/joined/departed 按实际启用 envelope 保持稳定 sourceEventId；HISTORY 不 mark missing，UNKNOWN departure 有明确 is-not-in-group。
- admin / superadmin role 事件。
- PN/LID/phone 确认边界。
- 全量群列表和完整成员结果的 complete / skippedCount 语义。
- Web 与 Android 对同一事件契约的 JSON fixture 交叉测试。
- 现有 Android JSONL 的失败恢复继续作为已知风险单独处置；不借六表首期顺带建设 CUTOVER_SPOOL/effect epoch。
- go test、go vet、go build；涉及并发消费时补 go test -race。

### 17.4 前端

- 展开区保持删除，GroupListTable 不再出现 expand 列。
- 方案 A 保持现有列/筛选；方案 B 只在 feature flag 下启用。
- filter 序列化、null fallback、列表/详情懒加载。
- 批量 ID、文件夹、批量任务轮询。
- 同一 canonical G 的多 legacy alias 选择只显示一个执行进度，逐 alias duplicate 结果可解释；folder/refresh/delete/marketing 的 mixed-type 行为一致。
- 导入链接和拉群选择器继续走 invite / legacy adapter。
- 历史群列表、详情、执行。
- 进群任务使用 current invite，不再传 wa://。
- join-task 页面同时覆盖“群列表 typed current invite”和“纯文本导入 I-only”，group terminal/invite invalid reasonCode 禁用准确。
- 营销 selection 使用稳定 groupId，旧任务快照仍可打开。
- v1 / v2 混合版本和 flag 回滚 E2E。

### 17.5 test1 验收

真实环境写入和部署前必须再次确认目标为 test1。顺序为：

1. 本地全量单测和 SQL 结构测试；
2. 部署脚本自身测试；
3. test1 additive migration；
4. 只读 backfill dry-run 报告；
5. 小租户 / 小批次写入；
6. Web 与 Android 各选账号验证完整、partial、空快照，并核对实际启用 topic 的 eventId、canonical payload、消费位点与 applied 结果；首期不要求三套 v2 twin；
7. 列表、详情、进群、拉群、营销、历史群、四类建群入口、invite batch-check、导出冒烟；
8. 指标观察和回滚演练。

不得直接在 test1 全租户跑无 dry-run 的批量迁移。

## 18. 本次全仓审计证据与实施闭包门禁

本设计不是只从群组列表页面反推表结构。审计范围覆盖四个仓库、Flyway 全量 migration tree、后端直接读写、前端契约、两套协议生产端和 test1 只读数据；下表只列与群模型直接相关、混合相关或为切换提供基础设施的版本，确认无关的 migration 不硬塞进 owner 清单。以下路径是实施计划必须逐项建立改造任务与回归用例的 owner 清单。

### 18.1 Armada 后端

| 范围 | 已核对的关键位置 | 得出的模型约束 |
|---|---|---|
| 旧群事实/兼容来源 Flyway | V003、V006、V008、V010～V012、V014、V017～V018、V047、V054、V060、V083_1、V084～V086、V090～V092、V096～V100、V105、V109 | group_link 从邀请表变成混合群入口；成员、baseline、UNKNOWN 退出证据、秒/毫秒和任务引用不能一刀切 ID |
| 保留过程/历史 Flyway | V007、V038、V040～V043、V045、V050～V052、V055～V057、V059、V070、V078、V080、V082～V083、V087～V089、V093～V095、V101～V104、V106～V108、V110、V112～V116 | 保留任务、命令、执行结果和冻结快照，但活跃引用增加 typed ID；V112 的 batch/task item 明确是过程表，不属于群当前事实；历史事实不反写六表 |
| 共享设施 Flyway | V005、V013、V015、V021、V046、V071、V075～V076、V111；V081 为间接 CAPI outbox | account/protocol binding 列与旧查询索引、protocol outbox、country、RBAC/群菜单历史、trace 等不被六表替代；新 active protocol 唯一键必须在 V005/V015 的现有列与索引语义上做升级验证，迁移需同步 schema/权限/可靠性 owner |
| 列表与 CRUD | `GroupLinkController`、`GroupLinkServiceImpl`、`GroupLinkMapper.xml`、`GroupLinkPreviewMapper.xml`、`GroupLinkHealthMapper.xml` | 删除展开 UI 不会消除固定 JOIN；列表改 page-first；group / invite API 拆分 |
| 群详情与直接 WhatsApp 操作 | `GroupDetailServiceImpl`、subject/picture/timed-message/permission/member Controller 入口、`GroupProfilePort`、`GroupSettingsPort`、`GroupParticipantPort` 及 Web/Android adapters | 本地 profile 只写 G.display/remark；协议确认或同账号回读才按 fieldMask 写 P/M/I；限时消息落 P.ephemeral_duration_seconds，任何命令超时都不能凭请求值改当前事实 |
| 账号群快照 | `AccountGroupMembershipReportServiceImpl`、`AccountGroupMembershipSnapshotServiceImpl`、`AccountGroupMembershipMapper.xml`、`GroupLinkRegistryServiceImpl`、`AccountGroupSyncMySqlConcurrencyTest` | baseline 当前会早于完整性判断；joined_at 会被快照 syncAt 建行/改写，不能直迁 post-control；400 群约 2400～3600 SQL；新 Mapper 必须继承 MySQL RR 普通读分类、唯一键排序和 current-read ID 解析；Web null complete 推定完整必须删除 |
| 同步请求水位 | `AccountGroupSyncJob`、`AccountGroupSyncCommandService.markRequested`、`AccountMapper.markGroupSyncRequested` / `AccountMapper.xml` | 当前缺 baseline row 时插入空 JSON/count=0/capturedAt，把“请求过”伪装成空基线；目标只原子推进 S.last_sync_requested_at，不创建 baseline 事实 |
| 历史/上控分类 writer | `GroupClassificationServiceImpl`、`GroupClassificationBackfillJob`、`AccountGroupMembershipStatusServiceImpl` | 当前会写 group_link.is_historical/is_post_control 并排 metadata task；目标由 B/S 派生，旧 job 必须在 Phase 2B barrier 停用且不得被投影恢复为独立 writer |
| 群资料与成员 | `GroupMetadataSnapshotServiceImpl`、`GroupMetadataSnapshotPersistenceImpl`、`GroupParticipantObservationServiceImpl`、`ProtocolGroupParticipantChangedSinkAdapter` | 当前 delete-all/insert 和 JVM 锁不适合多实例；role/presence 必须分域 CAS |
| 邀请与健康 | `GroupInviteLinkServiceImpl`、`GroupLinkHealthReportServiceImpl` | invite 失效、群终态和探测账号封禁必须分域；revoke 必须允许 null code |
| 账号页 | `AccountMapper.xml` | 群数量改为 B→M 且只计当前 IN_GROUP |
| 群组营销 | `MarketingTaskMapper.xml`、`MarketingRoundWorker`、`MarketingNewGroupImmediateSendServiceImpl`、导出 Mapper / Provider | 固定/动态目标、baseline 排除、执行资格、导出进退群事实均需迁移；当前 immediate-send 逐 candidate insert attempt 不得复用到快照事务，migration origin 必须在任务写入前固定抑制 |
| 拉群全链路 | `PullTaskGroupMarketingCandidateMapper.xml`、`PullTaskGroupJoinPayloadHydrator`、`PullTaskManagerJoinResultServiceImpl`、`PullTaskManagerJoinProtocolExecutor`、`PullTaskStandardExecutionLifecycleServiceImpl` / `PullTaskGroupBanTerminationService`、member-query 与 participant-action hydrator | 候选、占用、当前 invite、管理员执行资格分别取权威表和流程表；群终态联动按 canonical groupId 终止活跃执行，不再依赖混合 groupLinkId |
| 建群链路 | `GroupOperationServiceImpl`、`NormalGroupCreationProtocolResultService`、`GroupCreationMarketingWorker`、`GroupPullMarketingExecutionWorker` | 四个 GroupCreatePort/command 入口统一 durable create-result；事实事务先于任务结算，任务 item 继续冻结执行证据 |
| 分组删除 | `GroupLinkLabelServiceImpl.batchDelete` | 当前 label 会级联删除混合 group_link；新模型必须阻断对 canonical group 的隐式级联 |
| 账号删除/换绑 | `AccountServiceImpl`、账号导入/配对/上线命令、`AccountMapper.xml` | 软删当前只改 account；目标必须同步 retire B、推进 S fencing，并给 active protocol account 加唯一键 |
| 导入来源 | `GroupLinkImportServiceImpl`、`group_link_import_batch/detail`、V003/V010 | sourceFileName 来自最近成功 detail；重复失败不改变收编来源，detail 需 typed inviteId |
| 兼容任务约束 | V040、V098、V112 及 metadata/batch/marketing Mapper | legacy ID 仍有 NOT NULL/唯一/generated key；回滚窗必须双填，不能“只写 typed” |
| 协议 outbox / 旧队列 | `protocol_command_outbox`、Kafka DLT 与各 consumer | GROUP_LINK payload 和旧回执在 Resolver drop 前必须排空/迁移；当前没有 DB event inbox |
| Kafka runtime | `application.yml`、`ProtocolKafkaConfiguration`、`ProtocolAccountGroupSyncEventConsumerProperties`、`ProtocolGroupEventConsumerProperties`、`NormalGroupCreationKafkaProperties`、`ProtocolAccountEventErrorProperties` 及三类 Consumer | 当前全局 auto-offset-reset=latest 不可用于 shadow replay；v2 使用专用 no-member/manual-commit/none 配置和配置/监听器测试 |
| 权限 | `GroupLinkController` 及菜单/按钮权限 | canonical group/invite 路由必须映射原 view/import/delete/member 权限，不能因改路径扩大授权 |

行为型 Flyway 还要逐条保真：V051 的 marketing success group 是累计任务证据；V054 的 invalid reason 要同时落 I validity 与 import result；V057 的 historical execution/JID/subject/invite 是冻结历史；V082 同时创建 prefix mapping 并给 join result 加 joined_at；V086 的旧 group_created_at 是 Unix 秒，迁 P 必须乘 1000 并记录来源；V087.source_account_group_id 是账号分组而非 WhatsApp groupId；V097/V100 把无 actor 证据的 removed 保守降 UNKNOWN，不能在新 M 又“修回”REMOVED；V108 的 member query JSON/JID 是命令结果过程，不是 M 当前主值。每条都要有 migration fixture 和反向对账，不以“表已保留”代替语义测试。

后端扫描不只按类名：还按 `group_link`、`group_link_id`、`group_jid`、`membership_state`、`is_historical`、`is_post_control`、`invite_code`、成员四表名和 baseline 字段扫描 Java、Mapper XML、Flyway、测试与配置。实施开始前在目标 commit 再生成一次引用清单，新增或漏登直接 writer 时禁止切流。

### 18.2 Web 前端

已核对 `src/api/group.ts`、`group-import.ts`、`historical-group.ts`、`account-group.ts`、`join-task.ts`、`account.ts`、`group-folder.ts`、`common-group-task.ts`、`pull-task.ts`、`marketing-task.ts`、`group-creation-marketing.ts`、`group-pull-marketing.ts`，以及下列页面族：

- `src/views/group/list`：列表列、筛选、详情、成员动作、folder、批量刷新和 timed message；
- `src/views/group/imports`：未解析邀请、label、batch/detail；
- `src/views/group/history` 与 `src/views/account/group`：账号维度历史群和当前群；
- `src/views/task/group-marketing`：groupLinkId selection、账号树和导出；
- `src/views/task/pull-task`：候选群、current invite、执行详情和补量；
- `src/views/task/group-creation-marketing` / `group-pull-marketing`：建群结果和历史详情。
- `src/views/task/join-task/**`：从群列表打开时改传 typed group/currentInvite，纯文本粘贴仍创建 I-only；不能把 wa:// 当邀请。
- `src/views/account/index/**`：这里的 group_id/group_name 是 account_group，不是 WhatsApp groupId；只迁 groupsNum 的 B→M/IN_GROUP 计数。
- `PullTaskCreateDrawer.vue`、`PullTaskStandardSettings.vue`、`CommonGroupConfigurationSections.vue`、`useCommonGroupCreate.ts`、`useStandardPullTaskCreate.ts`：folder 只选 canonical G，normal create result 新增 groupId 且回滚期保留 groupLinkId。
- `common-group-task-logs.ts`、`common-group/common-group-form.ts`、`CommonGroupCreateFlow.vue` 及 `CommonGroupCreate.test.ts`：普通建群日志/结果的 JID、typed ID 与 durable command 状态一同回归。

展开区删除后，groupJid、remark 仍在主列表 / 详情使用，ownerPhone 仍间接参与关键词映射；lastPreviewAt、lastCheckAt、lastHealthError、membershipStateLabel 才是无生产消费的 deprecated 候选。`GroupListRow.id` 仍被大量动作当 legacyGroupLinkId，灰度期必须双 ID，不能直接改数值语义。

### 18.3 Web/Baileys 协议层

已核对 `openapi/protocol-v1.yaml` 与 generated types/aliases/regenerate script、`protocol-layer/patches/baileys+7.0.0-rc11.patch`、`src/config.ts`/`config.test.ts`、`src/server.ts`、`src/worker/account-manager.ts`、`event-bridge.ts`、`src/commands/worker-consumer.ts`、`normal-group-creation-executor.ts`、`src/events/publisher.ts`、`subjects.ts`、groups routes、deploy compose/Kubernetes/PM2 配置和相应测试。

已确认的风险：

- Baileys patch 能算 legacy `skippedGroupCount`，但轻量 API 到 publisher 链路没有完整带出 complete / skippedCount / queryStartedAt；v2 adapter 必须统一 canonical 字段名；
- 多 topic、不同 Kafka key、多个账号和 HTTP 回读之间不存在群级全局顺序；
- participant add/remove 会发布，但 Java 当前普通动作处理不完整；promote/demote 旧适配会附带 inGroup=true；
- `group.metadata_updated` 虽有类型声明，但 Web 没有稳定 producer、Java 也没有完整消费闭环；
- 随机重建 eventId 会破坏重试幂等，v2 必须从事实源生成稳定 ID；
- normal-group result 有独立 v1 topic/config，不能漏出第三个 v2 fact topic；Web 配置禁止 normal command/result 与通用 topic 重名；
- 当前 OpenAPI、TS builder、Go struct、Java DTO/Consumer 各自手写，没有共享 golden fixture/checksum；第 18.8 节把 v2 schema owner 固定下来。

### 18.4 Android Zhuan

已核对 `internal/configs/configs.go`，`internal/armada/options.go`、`config.go`、`start.go`、`client.go`、`event.go`、`command.go`、`context_store.go`、`consumer_pool.go`、`consumer.go`、`groups_fetcher.go`、`group_snapshot_coordinator.go`、`group_join_event.go`、`group_departure_event.go`、`group_invite_link_event.go`、`group_action_*`、`normal_group_creation_sender.go`、`account_event_dlq.go`，以及 WGP2 / HistorySync、deploy configs/node/coordinator/multinode templates、compose 和生成/部署测试。

已确认的风险：

- Android 已有 RemovalAuthoritative / skippedCount 语义，但 snapshot coordinator 合并 dirty 与 created 触发时可能因 source 选择降低完整性；
- SELF membership 和部分成员事件使用处理时刻 `Now()`，不是上游事实时间；
- invite change builder 同样不能自然表达 revoke + null code；
- Kafka 失败落本地 JSONL 后可返回 nil，目前缺少明确 retention、进程重启恢复和 replay 成功删除闭环；切 v2 前必须补齐；
- `CommandContext` 当前没有 bindingInstanceId/bindingGeneration/effectAuthorityEpoch，account 解绑 / 重绑缺少贯穿所有事件的业务 generation；v2 activation context 必须耐久保存，旧进程、命令 retry、JSONL 和 spool 重放绝不能贴当前 token；
- 单一 GroupSyncTopic publisher 与“Kafka 失败后 JSONL 即成功”无法证明 v1/v2 两目标均送达，需 per-target ack/applied ledger 和逐节点旧文件闭包；
- 三类 Android join/depart/history v1 与 Web `group.participant_changed` 的差异已在 11.2.1 固化，两个生产端都要覆盖 presence。

### 18.5 test1 只读基线

- MySQL 8.4.8，Flyway 当前到 V116；本轮没有执行 DDL、DML、部署或协议命令。
- 约 1.1 万 group_link、4.7～5.1 万账号群关系、43.8～45 万成员当前数据；系统持续接收事件，因此这些是 2026-08-15 的区间快照。
- 大部分真实群使用 `wa://group/{jid}` 假链接，占真实未解析 / 导入邀请的数量远小于 canonical group 数。
- 当前默认列表 count/page 约 1.38 秒 / 1.23 秒；瘦基础查询约 32 毫秒、简单 count 约 5 毫秒。
- 本地快速测试入口为 `cd armada-api && mvn test`；Mapper 主测试使用 H2 MySQL mode。迁移 CI 另起 MySQL 8.4.8 Testcontainers，生成列、JSON、CHECK、EXPLAIN ANALYZE、锁、容量和并发再在 test1 克隆验证；不能把 test1 当第一次发现 DDL 不兼容的地方。
- 已把 `armada-deploy/.env.example`、`deploy-test.sh`/`deploy-test.test.sh`、`docker-compose.rds.yml`、`envs/test1.conf`/`perf2.conf`、`lib/armada.sh`、`lib/deep-check.sh`、`lib/kafka-check.mjs`、`lib/zhuan.sh`、`verify-config.mjs`、`package-prod.test.sh` 和 prod app/protocol compose 纳入实施闭包。当前 `kafka-check.mjs` 主要校验 topic partition 数和 consumer group 存在/状态，还不能证明 topic UUID/leader epoch、no-member、offset absent/固定值或连续 frontier；后续新增三个 v2 topic/group、writer epoch、CUTOVER_SPOOL、Reducer lag/reject 指标时，配置模板、深检、只读 Kafka 检查、部署脚本测试和离线包必须同版更新，不能只改应用 `application.yml`。
- 正式 test1 写入顺序仍按第 17.5 节并再次确认目标环境；本轮没有执行 `deploy-test.sh`，也没有修改任何共享环境配置。

### 18.6 防漏改的交付门禁

“翻遍代码”只能降低风险，不能靠口头承诺保证零故障。实施时用以下机器可检查门禁把依赖闭包固化：

1. 本设计第 18.7 节已冻结当前旧十表和 account baseline 的 reader / writer manifest；实施目标 commit 必须由扫描脚本重生成并 diff，逐项标 `legacy-adapter`、`migrated` 或 `history-only`，新增项/`unknown` 均禁止切读。
2. ArchUnit / 代码扫描限制只有 GroupFactReducer repository 能写六表；Phase 2M 恢复后任何 runtime 代码都不得写旧当前态表。
3. 数据库权限允许时，给旧当前态表直接 writer 加指标 / 审计；连续观察无未登记写入。
4. 每个业务域同时有契约测试、shadow diff、数据对账、feature flag 和独立回滚演练。
5. v1/v2 producer→Kafka JSON→Java Consumer 使用共享 fixtures；Web 与 Android 都必须覆盖 complete empty、partial、skippedCount>0、乱序、重放、revoke 和 stale generation。
6. 所有任务表先区分“当前引用”与“执行时快照”；历史证据禁止批量覆盖，活跃任务逐条验证 resolver。
7. drop 旧表前再次全仓扫描表名、列名、groupLinkId DTO 和线上 SQL digest；任一引用非零就不执行 drop。

### 18.7 当前 reader / writer manifest（设计提交冻结版）

下表是 2026-08-15 在四仓 `1.0.3-snapshot` 用表名、列名、Mapper namespace、groupLinkId/groupJid/baseline/member state 关键字扫描得到的当前闭包，不是“实施时再补”的空任务。路径均相对所属仓库；同族测试也计入引用但不列作生产 writer。实施 commit 的机器清单必须至少覆盖这些行，多一项要分类，少一项视为扫描失败。

| legacy object / fact | 当前主要 reader | 当前 writer / scheduler | 目标唯一 owner | 切换阶段 | retirement / assertion test |
|---|---|---|---|---|---|
| group_link | `GroupLinkMapper.xml`、列表/详情/历史/拉群/营销 Mapper | `GroupLinkImportServiceImpl`、`GroupLinkLabelServiceImpl`、`GroupLinkRegistryServiceImpl`、`GroupLinkServiceImpl`、`GroupFolderServiceImpl`、`GroupClassificationServiceImpl/BackfillJob`、snapshot/metadata services | G identity/local；I invite；切换后冻结 | 2M 停写并切读，6B drop | runtime writer/read scan=0；alias resolver/canonical dedup；`GroupClassificationBackfillProperties.enabled` 部署级显式 false 且 job metric=0 |
| group_link_preview | `GroupLinkPreviewMapper.xml`、GroupLinkMapper/detail/history | `GroupInviteLinkServiceImpl`、`GroupLinkImportServiceImpl`、`GroupLinkRegistryServiceImpl`、`GroupMetadataSnapshotPersistenceImpl`、`GroupBatchRefreshSupport` | P current profile；I-only preview；projection only | 2B/5/6B | field/version shadow diff；旧 mapper write SQL digest=0 |
| group_link_health | `GroupLinkHealthMapper.xml`、列表、health/batch candidate | `AccountGroupMembershipSnapshotServiceImpl`、`GroupInviteLinkServiceImpl`、`GroupLinkHealthReportServiceImpl`、`GroupLinkHealthCheckJob` | P stable group status；I validity/check；metadata task transient probe | 2B/5/6B | error-domain matrix；BANNED/LINK_INVALID/UNAVAILABLE 分域；old job stopped |
| account_group_membership | `AccountGroupMembershipMapper.xml`、历史群、账号计数、`GroupExecutionAccountSelector`、营销/拉群 | report/snapshot/status/classification/profile/participant/registry services | M presence/role + B account relation；S 只做 account snapshot header | 2B/5/6B | account group count/admin selector/baseline hash；direct writer=0 |
| account_group_baseline + account.baseline_state/last_group_sync_requested_at | `AccountGroupMembershipMapper.xml`、classification/marketing | `AccountGroupMembershipReportServiceImpl`、`AccountGroupSyncCommandService.markRequested`→`AccountMapper.markGroupSyncRequested`、`AccountGroupSyncJob`、LegacyBaselineBridge | S baseline/report/request watermarks；B.was_in_initial_baseline | Phase 1 bridge，2B 单写，6B drop | `AccountGroupSyncCommandServiceTest`、`AccountGroupSyncJobTest`、`AccountOnlineMapperDbTest.markGroupSyncRequested_createsEmptyBaselineWatermarkWhenMissing` 改为“请求不造空 baseline” |
| whatsapp_group_member_snapshot | `WhatsappGroupMemberSnapshotMapper.xml`、`GroupDetailServiceImpl`、`GroupDetailSnapshotReaderImpl` | `GroupMetadataSnapshotPersistenceImpl`、`GroupParticipantObservationServiceImpl`、`WhatsappGroupMemberCacheServiceImpl` | P snapshot header + M per-row CAS | 2B/5/6B | complete/partial/concurrent add-remove；不再 delete-all/insert |
| whatsapp_group_member_cache + whatsapp_group_member_state | cache/state Mapper、detail/marketing export | `GroupParticipantObservationServiceImpl`、`WhatsappGroupMemberCacheServiceImpl`、Android/Web participant sinks | M presence/role/current identity | 2B/5/6B | Web add/remove + Android join/depart/history twin；DEPARTED_UNKNOWN；role epoch |
| whatsapp_group_member_join_fact + whatsapp_group_departed_member | marketing/history export、`WhatsappGroupBusinessDepartureService` | `WhatsappGroupMemberJoinFactServiceImpl`、`WhatsappGroupDepartedMemberServiceImpl` via ProtocolGroupJoin/Departure sinks | M last_join / last_exit family | 2B/5/6B | V097/V100 UNKNOWN evidence；join/exit independent CAS；历史 export parity |
| group_metadata_sync_task | list/detail status、scheduler、batch info refresh | metadata task service/worker、`GroupBatchInfoRefreshWorker`、sync requested adapter | 保留过程表；target=group_id；事实成功只经 Reducer 写 P/M/I | Phase 1 additive，2B writer bridge，长期保留 | one active task/G；probe error-domain；任务失败不覆盖事实 |
| group_batch_task/item | group list polling、batch result | `GroupBatchTaskServiceImpl` 与 workers | 保留过程表；canonical group_id + semantics_version | Phase 1/5，长期保留 | alias canonical dedup；task+group v2 key；requested/canonical counts |
| group_link_import_batch/detail + label | import/listAdoption/来源显示 | import/label services | 保留审计/配置；detail.group_invite_id；I.label/pool | Phase 1/5，长期保留 | DUPLICATE vs ADOPTED；label delete 原子；new I legacy invite primary |
| join/marketing/pull/history/creation task result families | 各任务详情、重试、导出 | 相应 scheduler/worker/result service | 历史快照保留；活跃 target typed G/I；当前资格读六表 | Phase 1 additive，5 按域切，保留 | typed-null active=0；legacy 快照不改；collision dry-run；历史详情 resolver |
| protocol_command_outbox、Kafka DLT/offset、Android JSONL/spool | dispatcher/retry/reconciliation | outbox service、Web/Android publisher | 非权威可靠性设施；immutable typed resource + binding/effect token | Phase 1～6 | command/eventId replay；三 topic contiguous offsets；per-target delivery；queue/old payload 水位=0 |
| normal/direct/两类营销 create result | normal item、营销 execution、direct response | `NormalGroupCreationProtocolResultService`、`GroupOperationServiceImpl`、`GroupCreationMarketingWorker`、`GroupPullMarketingExecutionWorker` 当前各自处理 | normal-group v2 durable result→Reducer；任务另事务结算 | Phase 1 schema/outbox，2B 切 result | 四 flowKind；external-success crash；creator leave 不被收尾复活；stale task 仍落 G |
| country + country_phone_prefix_mapping | country options、group/history、marketing export、IP | country admin/service；当前无 group country reindex owner | 平台主数据；`ConfirmedPhoneCountryResolver` 派生 M/I，新增 reindex job | Phase 1 resolver，3 backfill，长期保留 | current hash only；list/count/export same resolver；MyBatis ignored-table 审查 |

对应生产 Mapper 清单至少包含 `resources/mapper/group/GroupLinkMapper.xml`、`GroupLinkPreviewMapper.xml`、`GroupLinkHealthMapper.xml`、`AccountGroupMembershipMapper.xml`、`WhatsappGroupMemberSnapshotMapper.xml`、`WhatsappGroupMemberCacheMapper.xml`、`WhatsappGroupMemberJoinFactMapper.xml`、`WhatsappGroupDepartedMemberMapper.xml`、`GroupMetadataSyncTaskMapper.xml`、`resources/mapper/account/AccountMapper.xml` 与 `resources/mapper/platform/country/CountryMapper.xml`。CI 扫描规则对六表 UPDATE/INSERT 只放行 Reducer repositories；Phase 2M 后的 runtime binary 对旧当前态 UPDATE/INSERT 零放行。XML、注解 SQL、JdbcTemplate、Flyway、测试 fixture 和脚本 SQL 都纳入，不只扫 Java 类名。

### 18.8 跨仓契约、配置与运维 allowlist

v2 契约唯一源固定在 `armada-protocol/openapi/protocol-v2.yaml`，用 OpenAPI webhook/schema 同时声明 account group-sync、general group、normal-group result 三类 envelope；`openapi/regenerate-types.sh` 生成 TS 类型，并生成 `openapi/fixtures/events-v2/*.json` 与 `manifest.sha256`。Java 把生成 fixture 固定到 `armada-api/src/test/resources/protocol/events-v2`，Android 固定到 `internal/armada/testdata/events-v2`，两仓记录源 schema git SHA + manifest checksum；CI 比对 checksum、反序列化 canonical/unknown-field fixtures，并让 Web/Go producer 实际输出逐字节语义等价 payload。schema 变化必须同 PR/联动提交更新三端 fixture；手写 JSON 单测不能替代共享 fixture。v1 `openapi/protocol-v1.yaml` 保持既有语义，只由边界 Adapter 认识旧字段。

运行配置 owner 同样进入契约：后端 `application.yml` 与 Kafka properties/configuration/listener tests；Web `src/config.ts`/`server.ts` 及 deploy env；Android configs/options/start/client/context store 与各 deploy template。三个 v2 topic 分别拥有显式 topic、正式 group、audit group、partition/retention，正式 listener 强制 manual commit + `auto.offset.reset=none`；测试证明配置缺失会 fail closed，不能悄悄继承当前全局 `latest`。Android activation context 和 command reference 必须把 binding/effect token 持久化，Web session 也一样。

运维脚本分三类冻结：

- 可保留 protocol/JID smoke：工作区根 `query-group-invite.sh`、`armada-deploy/tools/single-group-probe.sh`、`armada-deploy/tools/batch-group-create.sh`；它们直调协议 groupJid，不读旧群表，但要加入 Web/Android route、NOT_OWNER reroute 和 v2 durable create-result 冒烟。
- 明确排除：`mutual-add-account-groups.sh` 操作的是 account_group 账号分组，不得把其 groupId 机械替换为 WhatsApp groupId。
- 必须随迁移更新：前端 `scripts/verify-group-import-menu.mjs` / `verify-group-list-menu.mjs` 的 RBAC smoke，以及 `.harness/wiki/gen_datamodel.py` 的硬编码群表清单；只有 Phase 6B 后才重生成 `.harness/wiki/数据模型.md`。

## 19. 待用户确认的业务决策

这些不影响六表职责，但会影响 API / UI 和迁移操作，实施计划前需要确认：

1. 方案 A 是否长期保留现有全部列表列和高级筛选；默认先全兼容，仅移除已删展开区的无消费字段。
2. UI 当前 creatorPhone 实际来自群主，是否把文案和 API 统一改为 ownerPhone / 群主。
3. 删除导入链接分组时，推荐隐藏 invite pool 归属、删除 batch/label 审计可见性但保留 canonical invite 和已解析真实群；是否需要额外“同时归档这些群”的显式勾选操作。
4. LEGACY_UNKNOWN baseline 是否提供运营重新确认流程；默认保持未知并禁止据此触发新群营销。
5. 是否需要完整的成员进退群 append-only 审计；默认只迁移项目当前实际保存的“最近一次”事实。
6. 是否允许创建三个 v2 Kafka fact topic；本设计推荐允许，分别隔离账号群快照、通用群事实、普通/直接建群 durable result 的不兼容语义。专用 normal-group v1 链已在运行，漏掉第三个 topic 会直接漏自建群事实。
7. 是否批准把多个 resolved legacy 行的 folder/delete/remark 等本地当前语义归并到一个 G，并最终折叠为一个 GROUP；这是 Phase 2B 切 writer 的前置决策。批准后，过渡期 v1 Adapter 仍逐 legacy row 返回原 alias id、动作解析到 canonical G，I-only 始终保留；未批准时最多停在 Phase 2A additive/shadow 准备，不能进入单一六表 writer。
8. v1 `admin` 是否把 UI 文案改成“受控管理员号码”；默认保持现网受控范围，不开放全部观察手机号。
9. 是否批准把群列表/历史/营销导出的国家统一为第 11.5 节 strict confirmed-phone resolver；这会修复现有最长前缀与 libphonenumber 两套口径，但少量共享区号/无效号码会从“猜测国家”变成 unknown。默认推荐批准并把差异列入迁移报告；未批准则必须先确定另一套单一算法，不能继续两套并存。
