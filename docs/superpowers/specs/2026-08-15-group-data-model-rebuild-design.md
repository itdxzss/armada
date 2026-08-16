# 群组数据模型重建设计（方案 A：六张权威表）

> 日期：2026-08-15
> 状态：模型重建范围已收敛、待业务与技术评审、尚未实施；前端展开区小改已完成本地验证
> 分支基线：四项目均按 `1.0.3-snapshot` 审计；实施时仅 Armada 与 Web 前端使用本地 `1.0.3-group`，两个协议来源仓继续停留在原分支并保持零代码改动
> 目标环境：第一套测试环境 test1；本轮对 test1 仅做只读核对，不改库、不部署

## 1. 结论先行

### 1.1 本期已确认边界

本期是 **Armada 后端群组存储模型重构**，不是列表产品改版，也不是 WhatsApp 事件协议升级。全仓排查用于确认兼容边界和回归范围，不能等同于全仓改代码。

| 项目 | 本期定位 | 允许改动 | 明确不做 |
|---|---|---|---|
| `armada` | 唯一主要实施项目 | 六表、旧数据迁移、Mapper/Service 内部重构、现有 API/事件的后端 Adapter、查询 SQL、后端测试 | 借换表改变现有业务口径 |
| `wheel-saas-pure-web` | 一个已确认的小 UI 改动 + 回归验证 | 移除用户指定的行展开详情；只有后端无法保持数值 key 时，再允许调整 `row-key` 和请求 key 映射 | 改主列表列、筛选、状态含义、按钮、批量规则、页面流程或新增资源类型业务 |
| `armada-protocol` | 只读审计与回归项目 | 无代码改动 | 新字段、V2 schema/topic、producer 逻辑、eventId/generation 契约改造 |
| `whatsapp-server-feature-android-zhuan` | 只读审计与回归项目 | 无代码改动 | 事件结构、binding context、spool/ack、发布与重试机制改造 |

本期不可破坏的外部契约：

1. 群组列表的行集合、列、筛选条件、排序、分页、状态、按钮和动作结果保持现网语义。
2. 现有 Controller 路由、请求/响应 DTO 和权限保持；内部可以换主键，但默认由后端兼容现有 `id`。若最终只能改变 key，前端仅做 key 适配，不引入新的业务判断。
3. Web/Baileys 与 Android 继续发布现有 topic 和 payload；后端边界 Adapter 映射到内部六表命令。新模型不得反向要求生产端补字段。
4. 账号群快照、历史群、上控后群、营销、建群、拉群、导出等既有业务判断保持。发现的协议可靠性或历史语义缺陷只记录风险，未经单独确认不得趁本次迁移修正。
5. 本期不建设新事件协议、新 API、新前端业务流程或新的协议投递机制；这类问题只记录风险，不进入实施门禁。

因此，模型重建代码原则上只发生在 `armada`；`wheel-saas-pure-web` 仅保留已确认的展开区删除和必要时的 key 适配。两个协议来源仓只做兼容回归，不随数据库模型改造。

### 1.2 不过度设计红线

1. 只解决当前代码、当前数据和当前业务已经证明存在的问题；不为假设中的未来协议、未来页面或未来扩展预建能力。
2. 新增任何表、字段、状态、索引、抽象层或流程，都必须同时指出当前 reader/writer、当前失败场景和可执行验收；缺一项就不实施。
3. 能复用现有表、Service、Mapper 和任务机制并满足“唯一主值、正确锁序、SQL 预算、业务兼容”的，优先复用；不为形式统一重写正常业务。
4. 不建设通用事件平台、通用资源模型、第二套 API、第二套任务框架或无真实复用方的扩展点。设计文档中的备选项不是默认实施项，只有当前门禁证明必需时才启用。
5. 每个 Agent 只能修改任务清单明确授权的文件和行为；发现范围外问题只报告，不顺手修复、不自行扩大方案。
6. 评审不仅检查“有没有漏做”，也检查“有没有多做”；任何不能映射到当前需求和测试的代码都必须删除。

方案 A 的“六张表”是六张群组当前事实权威表，不是把项目内所有含 group 的表强行压成六张。

最终权威表固定为：

1. wa_group：真实 WhatsApp 群身份和 Armada 本地运营属性。
2. wa_group_profile：群当前资料、设置、群状态、当前邀请指针和最后一次完整成员快照头。
3. wa_group_invite：邀请 code、链接有效性、邀请历史及未解析链接预览。
4. wa_group_participant：群成员当前 presence、role、PN/LID 身份和最近进退群事实。
5. wa_account_group_binding：当前 Armada 账号与群成员的关系，以及该账号自己的 baseline / 上控后语义。
6. account_group_sync_state：账号全量群快照水位、完整性和空 baseline 语义。

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

兼容映射优先放在冻结的 `group_link` additive canonical 列；只有 test1 证明每个 legacy handle 都能逐条解析到 canonical 目标，且没有必须逐行保留的 alias 级业务属性时，才允许最终收窄为纯 ID map。否则保留最小 `legacy_group_link_compat`/旧表最小形态。上述过程/兼容设施都必须有唯一 writer、禁止反向覆盖六表，并在数据字典中标 `NON_AUTHORITATIVE`。账号协议绑定历史、协议重放状态机和零停机跨版本切换不纳入六表首期；若以后确有需要，另立设计，不能反向膨胀本数据模型。

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
| 账号全量群快照是否完整、空快照、空 baseline 和水位 | account_group_sync_state |
| 账号在线、封号、风控、协议绑定 | account / account_state |
| 任务状态、失败原因、重试、执行快照 | 各自任务表 |

列表 VO、任务快照、导出文件可以包含派生值，但不能作为回写当前事实的来源。

### 3.2 六条硬不变量

1. 每租户每个规范化 group_jid 只有一个 wa_group，软删除后也不允许创建第二个。
2. 每群当前邀请只由 profile.current_invite_id 一个指针表达；“是不是当前链接”和“链接是否有效”是两个独立事实，链接 URL 永远由 invite_code 派生，不存第二份 URL。
3. 每租户每群每个 WhatsApp 人只有一个 participant；PN JID 和 LID JID 后续确认属于同一人时必须事务合并。
4. presence 和 role 分列存储、不能再共享一个物理状态字段；但本期 Adapter 必须复现当前 Consumer 对每类事件实际写入的结果，是否纠正“角色事件顺带写在群”另立业务需求。
5. 快照完整性继续使用当前事件字段和当前后端判定口径；本次只迁移其存储位置，不修改 Web/Android payload，也不借模型重建改变完整/部分快照的业务语义。当前口径中已知的误判风险单列后续事项。
6. `wa_group.deleted_at` 只保存本地删除事实，不被其他表复制；协议再次观察后的现网恢复/可见性结果由兼容 Service 复现，是否收紧为“仅运营恢复”另立需求。

### 3.3 统一基础约定

- 所有业务时间统一为 BIGINT epoch 毫秒；旧 group_created_at 秒值迁移时乘以 1000。
- group_jid、pn_jid、lid_jid 使用 ASCII binary collation，入库前 trim、转小写并校验 JID 后缀；phone 只保留可信规范化数字。
- PN/LID 必须先做等价于 Baileys `jidNormalizedUser` / Android JID canonicalizer 的 user-level 归一：`123:1@s.whatsapp.net` 与 `123@s.whatsapp.net` 都存为后者，LID 的 device/agent 段也移除但绝不转成手机号。回填先规范化、报告碰撞，再建唯一键。
- invite_code 和 event_id / source_event_id 使用 ASCII binary collation但保留原始大小写；WhatsApp 邀请 code 可能大小写敏感，绝不能统一转小写。URL 解析只去空白和固定 path 前缀。
- group_jid 只接受规范化的 @g.us；禁止再生成 wa://group/{jid}。
- tenant_id 在六表全部显式存在，继续由 MyBatis-Plus 租户拦截器隔离。
- 不增加物理外键，沿用 Armada 当前逻辑外键和 Service 事务校验模式，避免在线迁移锁表。
- 状态用 TINYINT + Java enum，SQL COMMENT 必须逐值说明。
- null 默认表示“未观察到”。当前事件/接口的清空行为由现有 Adapter 兼容；本期不要求协议新增 fieldMask，也不增加新的破坏性清空语义。
- P/I/M 对当前代码确实存在乱序覆盖风险的字段可以保存内部 version key；B/S 继续使用当前业务时间/eventId 口径，不为协议缺失字段设计新的代次或版本状态机。version key 只服务后端幂等和乱序保护，不进入 API。
- `first_seen_at` 只能按 `MIN(existing, acceptedFactTime)` 单调向前，`last_seen_at` 只能按 `MAX(existing, acceptedFactTime)` 单调向后；晚到事实不得让两个边界反向移动，`first_seen_source` 只随更早且更可靠的首次事实修正。

### 3.4 MySQL 物理约束基线

方案字段表中的 `VARCHAR(n) ASCII BIN` 是简写，实际 DDL 必须展开为 `VARCHAR(n) CHARACTER SET ascii COLLATE ascii_bin`。六表统一 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`；JID、invite code、event/snapshot/version identity 等机器标识逐列使用 ascii_bin，不能继承大小写不敏感排序。

所有枚举、三态布尔、非负计数都加 CHECK；M 增加 PN/LID 至少一个非空，P 增加 current-invite state/pointer 组合约束，JSON NOT NULL 列的 insert/backfill 显式写 `{}`。实际保留的 `*_version_key` 只允许未观察 sentinel 或固定编码长度。CHECK、NULL 唯一键、JSON 和 generated-column 行为以 MySQL 8.4.8 为准，H2 不作为这些约束的验收依据。

最少约束清单：G 的来源枚举和时间合法；P 的状态、设置、人数和当前 invite 指针组合合法；I 的 code、状态和计数合法；M 的 presence/role/exit 枚举、PN/LID 和计数合法；B 的 baseline 三态只允许 0/1/NULL；S 的 baseline 状态、当前后端 effective-complete 标记和计数合法。跨行 tenant/group/pointer/participant 一致性因不建物理 FK，由写入 Service 和第 15 节巡检保证。

## 4. 目标关系

~~~mermaid
erDiagram
    ACCOUNT ||--|| ACCOUNT_GROUP_SYNC_STATE : "一个账号一个同步水位"
    ACCOUNT ||--o{ WA_ACCOUNT_GROUP_BINDING : "账号当前群关系"
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
| id | BIGINT | PK, AUTO_INCREMENT | 后端内部群稳定 ID；旧 `group_link.id` 由兼容 handle 保持，不要求复用本主键 |
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
| deleted_at | BIGINT | NULL | 本地删除时间；v1 可见性/再次观察结果由兼容 Service 复现 |

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
| member_count | INT | NULL | metadata 或完整成员快照观察到的群成员数 |
| checked_member_count | INT | NULL | 链接健康检测最近一次成功观察到的人数；只按健康检测水位更新 |
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

field_version_keys 不是自由 JSON。键固定为 subject、description、avatarUrl、memberCount、waCreatedAt、groupStatus、announceOnly、adminOnlyEditInfo、memberAddMode、joinApprovalMode、ephemeralDurationSeconds；每个值是后端内部 FactVersion 经 canonical binary encoding 后的 base64 字符串。下例是 Java Adapter 归一后的内部对象，不是要求协议发送的 payload：

~~~json
{
  "observedAt": 1786700000000,
  "sourceType": 4,
  "authorityTier": 20,
  "sourcePriority": 40,
  "protocolBackend": "ANDROID",
  "observerAccountId": 12345,
  "sequenceDomain": "ANDROID:ACCOUNT:WGP2",
  "hasSourceSequence": false,
  "sourceSequence": 0,
  "sourceEventId": "current-source-id-or-envelope-id",
  "eventId": "current-envelope-id"
}
~~~

Java Reducer 把 FactVersion 编成可按 unsigned byte lexicographic 比较的 version key：`0x01 | observedAt(u64 BE) | authorityTier(u16) | sourcePriority(u16) | SHA-256(sequenceDomain) | hasSequence(u8) | sourceSequence(u64 BE) | SHA-256(sourceEventId) | SHA-256(eventId)`，共 118 字节；`0x00` 专用于 UNOBSERVED。跨 domain 的 hash 顺序没有业务含义，只提供稳定 total order。协议生产端不改；Java Adapter 对当前 payload 缺少的 sequence/sourceEventId 按第 11.2 节兼容映射，encoder 用后端当前 v1 fixtures 固定。

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

六表内部的当前群主事实不放在 profile，而由 wa_group_participant 的在群 OWNER 角色表示。但本期 v1 列表的 `creatorPhone`、创建者国家和大洲不是“当前群主”字段，仍严格沿用 `group_link_preview.owner_phone/creator_country_*`；不得在影子查询中改用当前 OWNER participant。把 v1 创建者改成当前群主属于独立业务变更。

Reducer 更新 current invite 前必须锁 profile，先比较 current_invite_version_key，再校验目标 invite 与 profile 的 tenant_id / group_id 相同且未软删。增加 CHECK：state=PRESENT 时 pointer 必须非空，state=UNKNOWN/EXPLICIT_NONE 时 pointer 必须为空。指针为空时版本仍不能倒退；这条 group-level watermark 是晚到旧 code 无法复活的最终边界。

### 5.3 wa_group_invite

聚合职责：邀请 code 的生命周期、链接有效性、检查结果，以及尚未解析成真实群时的公开预览。当前邀请关系只在 profile.current_invite_id，不在每个 code 行复制 is_current。

| 字段 | 类型 | 空值 / 默认 | 说明 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 后端内部邀请稳定 ID；旧 `group_link.id` 由兼容 handle 保持，不要求复用本主键 |
| tenant_id | BIGINT | NOT NULL | 租户 ID |
| group_id | BIGINT | NULL | 已解析时逻辑关联 wa_group.id；未解析可为空 |
| invite_code | VARCHAR(128) ASCII BIN | NOT NULL | 规范化 code；URL 由它派生 |
| label_id | BIGINT | NULL | 导入链接分组，逻辑关联 group_link_label.id |
| pool_hidden_at | BIGINT | NULL | 内部可见性候选；本期不用于改变现有删除/恢复语义，alias 级行为由 handle 兼容 |
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

I.field_version_keys 同样不是自由 JSON，固定且仅允许这些 key：previewSubject、previewDescription、previewAvatarUrl、previewMemberCount、previewOwnerPhone、previewWaCreatedAt、previewAnnounceOnly、previewAdminOnlyEditInfo、previewMemberAddMode、previewJoinApprovalMode、previewEphemeralDurationSeconds。display_name / display_avatar_url / remark 是本地字段，validity/check 是独立事实域。NULL/0/1 的写入必须复用当前事件和 Service 语义，不要求老协议提供 fieldMask。

约束与索引：

| 名称 | 列 | 类型 / 用途 |
|---|---|---|
| PRIMARY | id | 主键 |
| uq_wa_group_invite_code | tenant_id, invite_code | 无条件唯一；重复导入复用原行 |
| idx_wa_group_invite_group | tenant_id, group_id, last_seen_at, id | 群的邀请历史 |
| idx_wa_group_invite_label | tenant_id, label_id, pool_hidden_at, deleted_at, created_at, id | 导入链接分组 |
| idx_wa_group_invite_check | tenant_id, validity_status, last_check_result, last_checked_at, id | 链接检测候选 |
| idx_wa_group_invite_country | tenant_id, preview_owner_country_iso2, pool_hidden_at, id | I-only 国家筛选 |

邀请表的物理约束保持最小：invite_code 唯一、计数非负、枚举值合法，group_id 非空时必须与同租户 G 对应。复杂的 check error-domain 状态机、ADOPTED/隐藏/恢复新语义不在本期增加。

本期规则：

- 只存 invite_code，返回 URL 时按当前格式拼接；
- group_id 允许为空，可靠拿到 JID 后再绑定 G；
- P.current_invite_id 表示内部当前指针，但 v1 `url/status` 仍由兼容 Service 按现有优先级输出；
- preview、health、导入、重复、删除、label 和 batch 的写入结果复现当前 Service；
- `pool_hidden_at` 仅作为内部候选字段，不得被用来改变现有删除/恢复行为；需要 alias 级可见性时由 legacy handle 保留；
- country 投影、错误分域、revoke/null-code 和邀请生命周期治理都不作为本期业务改造。

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

增加 CHECK：pn_jid 与 lid_jid 至少一个非空；presence、role、exit_type 和计数只做枚举/非负校验。

PN/LID 同时保存是为了适配 Web/Android 当前寻址差异，不能拿 LID 数字猜手机号。迁移发现同群 PN/LID 冲突时先报告并阻断该行自动归并，不在首期用复杂 epoch 算法静默合并。

presence 与 role 分列，解决物理字段混用；但初始回填和实时 Adapter 必须复现当前 member state/cache/snapshot 与 Consumer 的可见结果。`membership_epoch`/version 字段只用于防止同一存储内部旧角色复活，不能据此改变当前列表管理员、成员详情、动作目标和导出。

现有群详情成员接口的业务口径是“最后一次完整成员快照”，不是把所有 participant 当前态直接列出。现有六表通过 `wa_group_profile.member_snapshot_version` 保存已提交快照头，通过 `wa_group_participant.last_snapshot_version` 标识属于该完整快照的成员；详情只读版本相等且 `presence_status=1` 的行。这样完整快照之后的新成员不会凭普通观察混入，已确认踢人仍会像旧实现一样立即移除，升降管理员仍在原快照成员上即时更新角色。旧详情快照必须先补齐这两个现有版本字段并通过对账，才能切换读取；不得只按 `presence_status=1` 扫全体成员。

现有 API 继续提交/返回当前 participantJid/JID 参数。`participant.id` 只是后端内部关联键，本期不新增 participantId API，也不让前端选择 PN/LID。

### 5.5 wa_account_group_binding

聚合职责：保存当前 Armada account 与真实群/self participant 的关系，以及该账号现有 baseline/上控后上下文。它替代 `account_group_membership` 的当前关系，不引入新的账号绑定代次业务。

| 字段 | 类型 | 空值 / 默认 | 说明 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| tenant_id | BIGINT | NOT NULL | 租户 ID |
| account_id | BIGINT | NOT NULL | 逻辑关联 account.id |
| group_id | BIGINT | NOT NULL | 逻辑关联 wa_group.id |
| participant_id | BIGINT | NOT NULL | 该 account 在群内的 self participant |
| was_in_initial_baseline | TINYINT | NULL | NULL 未知，0 当前业务判为上控后群，1 初始 baseline 内 |
| baseline_subject_snapshot | VARCHAR(255) | NULL | baseline 当时群名快照；不是当前群名 |
| baseline_captured_at | BIGINT | NULL | 该关系被纳入 baseline 的时间 |
| membership_active_since_at | BIGINT | NULL | 兼容旧 joined_at 的“当前在群周期首次建行/恢复在群观察时间”；只服务现有营销截止和导出，不参与历史/上控分类 |
| first_observed_at | BIGINT | NOT NULL | 当前旧模型可证明的首次观察时间 |
| last_observed_at | BIGINT | NOT NULL | 最近观察时间 |
| last_observed_event_id | VARCHAR(128) ASCII BIN | NULL | 当前事件有 eventId 时记录，用于后端幂等 |
| last_complete_snapshot_id | VARCHAR(128) ASCII BIN | NULL | 最近包含该关系的账号群报告 ID |
| first_post_control_observed_at | BIGINT | NULL | 当前业务首次判为上控后群的观察时间；legacy 迁移一律 NULL |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

约束与索引：

| 名称 | 列 | 用途 |
|---|---|---|
| PRIMARY | id | 主键 |
| uq_wa_account_group_binding | tenant_id, account_id, group_id | 一个账号对一个群一行 |
| idx_wa_binding_group_participant | tenant_id, group_id, participant_id | 执行账号与成员关联 |
| idx_wa_binding_account_seen | tenant_id, account_id, last_observed_at, group_id | 账号群列表 |
| idx_wa_binding_account_active_since | tenant_id, account_id, membership_active_since_at, group_id | 兼容现有 joined_at 营销截止查询 |
| idx_wa_binding_historical | tenant_id, group_id, was_in_initial_baseline, account_id | 历史/上控筛选候选 |

本表不复制 membership_status、is_admin、is_owner 或 last_exit_type；这些当前值来自 M，账号是否可执行再连接 account/account_state。`first_post_control_observed_at` 非空时必须满足 `was_in_initial_baseline=0`；was=1/NULL 时必须为空。

账号删除、换绑、重新导入和迟到事件继续遵循现有 account/Consumer 行为。没有 protocol generation 时，本表不伪造代次历史，也不拒绝旧逻辑原本接纳的事件；若以后需要换绑 fencing，另立账号/协议联合设计。

### 5.6 account_group_sync_state

聚合职责：每个 account 一行，保存账号群报告水位、当前 baseline 状态和完整空集合；不保存新的协议 binding token。

| 字段 | 类型 | 空值 / 默认 | 说明 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| tenant_id | BIGINT | NOT NULL | 租户 ID |
| account_id | BIGINT | NOT NULL | 逻辑关联 account.id |
| baseline_filter_enabled | TINYINT | NOT NULL, 1 | 继续承载当前是否启用 baseline 过滤 |
| baseline_state | TINYINT | NOT NULL, 1 | 1 PENDING，2 CAPTURED，3 DISABLED |
| baseline_completeness | TINYINT | NOT NULL, 0 | 0 NONE，1 CURRENT_COMPAT，2 LEGACY_UNKNOWN；CURRENT_COMPAT 表示按当前后端规则接受，不宣称上游显式完整 |
| baseline_captured_at | BIGINT | NULL | 当前业务形成 baseline 的时间；legacy 按迁移证据 |
| baseline_snapshot_id | VARCHAR(128) ASCII BIN | NULL | 当前报告有 snapshotVersion 时使用，否则可用 eventId；legacy 可空 |
| baseline_group_count | INT | NULL | baseline 群数；当前代码确认的空集合为 0，legacy 未知为空 |
| last_sync_requested_at | BIGINT | NULL | 最近同步命令入队时间 |
| last_reported_at | BIGINT | NULL | 最近收到账号群报告的时间 |
| last_report_event_id | VARCHAR(128) ASCII BIN | NULL | 最近报告 envelope eventId |
| last_report_source | VARCHAR(64) ASCII BIN | NULL | 当前 source 值 |
| last_report_backend | TINYINT | NULL | 1 WEB，2 ANDROID |
| last_snapshot_complete | TINYINT | NOT NULL, 0 | 后端按当前 `completeSnapshot()` 得出的有效完整标记 |
| last_skipped_group_count | INT | NOT NULL, 0 | 当前 payload 的 skippedGroupCount；缺失按现有逻辑处理 |
| last_reported_group_count | INT | NOT NULL, 0 | 最近报告有效群数 |
| last_complete_snapshot_id | VARCHAR(128) ASCII BIN | NULL | 最近一次按当前规则接受为完整的报告 ID |
| last_complete_at | BIGINT | NULL | 最近一次按当前规则接受为完整的报告时间 |
| last_error_code | VARCHAR(64) | NULL | 最近同步错误；成功时按当前逻辑清空 |
| last_error_at | BIGINT | NULL | 最近同步错误时间 |
| consecutive_failure_count | INT | NOT NULL, 0 | 连续失败次数 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

约束与索引：

| 名称 | 列 | 用途 |
|---|---|---|
| PRIMARY | id | 主键 |
| uq_account_group_sync_state | tenant_id, account_id | 一个账号一行 |
| idx_account_group_sync_baseline | tenant_id, baseline_state, account_id | baseline 调度 |
| idx_account_group_sync_requested | tenant_id, last_sync_requested_at, account_id | 同步调度与巡检 |

状态约束保持最小：PENDING/DISABLED 没有 baseline header；CAPTURED 必须有 captured_at，CURRENT_COMPAT 有可解释的 count，LEGACY_UNKNOWN 的 count/snapshotId 可以为空。具体何时从 PENDING 进入 CAPTURED、Web null-complete fallback、空集合和 state=3 行为全部沿用当前 Service，不在表 CHECK 中偷偷改变业务。

`account_group_sync_state` 不复制 account.protocol_account_id/backend。Consumer 继续按当前 account 解析，Java Adapter 在同一账号快照事务更新 S/B；协议无需新增字段。

## 6. 现有派生口径的兼容投影

六表只改变事实的存放位置，不重新定义列表和业务状态。以下字段均以当前 Controller、Service、Mapper 和测试的实际结果为合同：

- `status` 及其筛选优先级；
- `isHistorical`、`isPostControl`、`membershipState`、`origin`；
- `admin`、可用管理员、`creatorPhone`、国家、大洲、群龄；
- 秒/毫秒转换、null fallback、排序和 count/page 口径。

新列表 SQL 通过 G/P/I/M/B/S 与保留过程表重建相同 VO。不能因为新表把群、邀请、账号关系拆开，就顺便采用新的状态机、OWNER/JOINED/TARGET 优先级、exact-owner 算法、国家 resolver、管理员隐私范围或文案。上述任何语义修正都需要独立需求。

本期明确兼容口径：`creatorPhone`、创建者国家和大洲读取旧 preview；管理员文本仍是当前在群管理员/群主集合；可用账号仍是“账号在线正常 + 当前在群 + 管理员/群主”的原谓词。新模型事实比旧投影更新时允许结果不同，但必须能证明为同一谓词下的更新事实，不能用旧陈旧值反向覆盖，也不能借此改变谓词。

历史群和上控后事实内部放在 B/S，但 v1 输出和营销资格继续调用现有业务 Service 的兼容实现。迁移期唯一额外安全规则是：旧 `account_group_membership.joined_at` 不是“上控后首次加入”，不得迁入 `first_post_control_observed_at`，迁移来源不得生成即时营销副作用。实时事件的 baseline、历史/上控分类和营销触发仍保持当前逻辑，已知缺陷另列风险。

前端字段名与时间单位保持：继续返回 `creatorPhone`；v1 `groupCreatedAt` 继续返回当前秒值；管理员文案和筛选范围不改。内部可使用毫秒和规范化事实，但兼容 VO 必须转换回当前合同。

硬门禁是同一数据快照、同一请求参数下，静态字段、行数、顺序、筛选命中和动作资格逐项相等。成员字段若因新模型保存了更晚的明确 role/presence 事实而不同，必须逐成员证明来源和时间，业务谓词保持不变并登记为事实新旧差异；迁移丢失、缺 binding/participant 不得按 expected diff 放过。行展开区删除只属于前端 DOM 差异，另允许 test1 已登记的 1 条不可见 Unicode 名称/subject 归一化例外。

## 7. 事件归并和乱序规则

### 7.1 两阶段 fencing

账号事件先沿用当前账号解析/接纳逻辑，再做六表内部字段新旧比较：

1. Java Adapter 用现有 payload 的 tenant/account/protocolAccount/backend 按当前代码找到 account；不新增 generation 行键或四元组 fencing。
2. 先按字段的 source admission / authority tier 判断该来源能否覆盖，再使用固定总序版本：observedAt、authorityTier、sourcePriority、sequenceDomain、hasSourceSequence、sourceSequence、sourceEventId、eventId。
3. 版本更大才允许覆盖；相同 eventId 重放必须无副作用。
4. ingestedAt / 数据库 updated_at 永远不能判断业务事实新旧。

source admission 分两类：FILL_ONLY 只有当前 version key=UNOBSERVED 才能写，不能在显式 clear 后重新填值；AUTHORITATIVE_RECONCILIATION 则以 observedAt 为跨时间主序，authorityTier/sourcePriority 只在同一事实时间决胜。authorityTier 不是永久 floor，否则一次显式 ADD 会让更晚的完整 absent 快照永远无法对账。SUSPENDED/TERMINATED、人工删除等少数终态另有显式 guard。total order 只在通过 admission 后比较，保证重放与同毫秒并发确定。

内部版本键只使用当前事件实际具备的字段：有原始事实时间/sourceEventId 就使用，没有时沿用 envelope occurredAt/reportedAt 和 eventId；无 sourceSequence 固定为无序列。Web 快照没有真实 queryStartedAt，不能由后端伪造查询切点或声称具备跨重发稳定 eventId。更强的 generation/sequence/query-cut 保证留给后续协议治理。

### 7.2 来源优先级

先应用字段级 admission：

- MIGRATION_UNKNOWN 和历史缓存可以按迁移规则 FILL_ONLY；现有 v1 事件不能仅因没有 generation 或只能提供处理时间而被改变接纳级别，仍按当前 Adapter 行为处理。
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

- 每种当前事件/HTTP 回读对“字段缺失、null、空字符串、0”的处理，逐项复用现有 Consumer/Service。
- Java 内部命令可以表达 clear，但只有当前代码本来会清空的路径才允许生成。
- 不因新表增加 fieldMask 要求，也不把现有 null 改成新的清空或忽略语义。

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
- 只对 MySQL deadlock / lock timeout 用同一已接收的 envelope.eventId 有限退避重试；重试是最后保险，不能代替普通读分类和全局锁序。

一次当前 `account.groups_reported` 必须在同一事务中完成与旧实现等价的写入：

1. 按 account→S 顺序锁定 account 和 account_group_sync_state，并按当前 tenant/account/protocolAccount 校验。
2. 内部 `effectiveSnapshotAt/snapshotId/sourceEventId` 只从当前 payload 映射：有 snapshotVersion/sourceEventId 就使用，没有时退化为 envelope eventId；Web 没有 queryStartedAt/sourceSequence 时继续使用当前 reportedAt/occurredAt 口径，不能伪造更强查询切点。
3. 为每个有效 group_jid upsert wa_group。
4. 把轻量群资料按字段版本归并到 profile。
5. 为该账号的 PN 创建或解析 participant；只写当前 payload 和现有 Service 本来会写的字段。没有 fieldMask 时按“非空且旧逻辑会消费”处理，不能新增 clear 或 role 推断。
6. 完整性严格复用当前 `AccountGroupMembershipReportServiceImpl.completeSnapshot()`：Android 使用现有 `snapshotComplete/skippedGroupCount`；Web 继续保留 `snapshotComplete=null` 且 `skippedGroupCount=null/0` 视为完整的兼容判断。缺失关系的处理结果必须与旧实现逐账号对账；本期不借换表修正该推断。全部 M 写入必须在 B 之前完成，不得持有 B 锁后反向再取 M 锁。
7. upsert binding，并写同一个 snapshotId 到 B.last_complete_snapshot_id。
8. 推进 sync_state 的完整快照头。
9. baseline 的触发条件和空集合行为复现当前代码；本期只修正旧数据迁移不能把 `joined_at` 当“上控后加入”这一迁移事故，不顺带改变实时 baseline 业务。
10. 在同一事务用一条 multi-row SQL 写 `group_snapshot_effect_outbox`，只保存本次账号快照实际需要的 metadata / immediate-marketing intent；唯一键固定为 `(event_id,effect_type,account_id,group_id)`。事务提交后 worker 才分别批写现有 metadata task、marketing send attempt 和必要的 protocol outbox。禁止在快照事务里逐群展开副作用。MIGRATION_*、was=1/NULL、existing B、partial/delta 或时间边界不满足的行在生成参数前直接排除，绝不能写可执行 intent。

这张表是为两个已证实的约束做的最小取舍：保留 G ID current-read 和现有返回契约时，六表快照事实写占 9 条 SQL，直接再写 metadata task 与 marketing attempt 两张物理表最低 11 条；单一 intent 批写才可把快照事务控制在 10 条。它不是通用事件总线，不记录所有 v1/v2 的 EMITTED/SUPPRESSED，不承担 writer epoch 或 Kafka 分区 fencing，也不允许业务查询群当前值。若实施阶段选择取消 G ID 返回、让后续 DML 全部 `INSERT ... SELECT G` 并通过 MySQL RR 门禁，可重新评审直接写两张既有任务表的 10 条方案；未经并发证明不得为了少一张过程表擅自删掉 current-read。

已知风险：Web 没有显式 complete/skipped/queryStartedAt，当前后端的 null 推断可能把遗漏群当成完整；PENDING baseline 也可能早于完整性判断。这两项如果修复会改变历史群、上控后群和营销资格，必须另立业务/协议需求。本期 shadow 对账以现网结果为准，不能悄悄修复。

### 7.5 单群完整成员快照事务

1. 锁定 wa_group_profile 的 member snapshot header。
2. 由当前事件实际提供的上游事实时间或 envelope occurredAt/reportedAt 生成内部版本；snapshotId 缺失时使用 eventId。没有 queryStartedAt 时不声称具备查询切点隔离。
3. 逐成员做 PN/LID 身份归并，并分别更新当前事件/现有 Adapter 本来会写的 presence/role 字段；缺 fieldMask 时不增加清空语义。
4. 是否处理未出现成员继续复用当前事件类型和现有 complete/skipped 判断；新旧模型结果必须逐群对账。更强的查询切点 CAS 需要协议事实时间支持，另立后续需求。
5. 同一事务更新 member_count、member_snapshot_at、member_snapshot_id、member_snapshot_fact_version_key、member_snapshot_observer_account_id 和 member_snapshot_participant_count；不存在未定义的 snapshot event_id 镜像。
6. “有界批次”只允许同一事务内的 JDBC batch / 分段 SQL，禁止分段 commit；任一批失败时 header 与成员行全部回滚。数据事务成功后再结算 group_metadata_sync_task；失败则任务保留错误，旧完整快照继续可读。

### 7.6 局部成员事件兼容

本期只迁移当前 Java consumer 已经实际处理的动作；例如 Web 普通 participant add/remove 当前被忽略，则新模型仍忽略。扩大消费范围会改变成员、管理员、营销和导出结果，必须单独审批。对已处理动作，内部归并遵守以下字段边界：

- add / join：只更新目标 participant 的 presence=IN_GROUP 和最近进群事实。
- leave / remove：先按 JID 定位或创建 participant，再更新 presence 和最近退出事实；不得先把群或账号关系写成“已加入”。
- promote / demote：只更新 role；没有 presence 证据时保持原 presence。
- 现有角色事件或定点成员查询命中受控账号时，同时保证该账号到当前 participant 的 binding 存在；这类观察不得写 `last_joined_at`、`membership_active_since_at`、baseline 或 first-post，也不得触发新群营销。
- owner 变化：更新事件中明确涉及成员的 role，不在 profile 再存 owner_phone；若缺少旧 owner 身份，记录 OWNER_REFRESH_REQUIRED 并调度完整刷新，不能自行挑选或清除其他 OWNER。
- 一个事件有多个参与者时允许共享 eventId；幂等键是 tenant + group + participant + 事实族 + eventId。

### 7.7 后端内部事实适配（协议仓零改动）

本期不定义新的 wire envelope。`armada` 继续消费当前 Web/Baileys 与 Android 已发布的 topic、字段名和事件类型，在 Java 边界 Adapter 内转成只在后端进程中使用的 `GroupFactCommand`，再写六表。内部命令可以统一 origin、时间和来源字段，但不能要求生产端补发 `bindingInstanceId`、`bindingGeneration`、稳定 eventId、fieldMask 或 V2 字段。

Adapter 的原则是“现有输入、现有业务含义、只换存储实现”：当前 payload 没有的事实保持 unknown，并沿用当前后端已经生效的兼容判断；不得为了填满新表而猜值。本期 B/S 不保存协议 binding token。

更强的统一信封、V2 字段、稳定 eventId、fieldMask 和端到端 generation 属于独立协议治理，不在本文展开。



### 7.8 所有写路径必须进同一个 Reducer

- Kafka Web 事件、Kafka Android 事件、HTTP metadata 回读、命令结果确认、定时刷新和迁移 backfill 都调用同一 GroupFactReducer。
- Controller、任务 Worker 和协议 Adapter 不得直接更新 profile / invite / participant。
- 多 topic、不同 account key 和多后端之间没有群级全局顺序，数据库 CAS 才是最终一致性边界。
- 单 JVM synchronized 不能代替数据库行锁 / CAS；多实例必须得到同一结果。

跨聚合事务统一锁顺序为 `account → S → G → P → I → M → B → durable effect → legacy projection`；不涉及某层时跳过但不得反向取锁。G 始终按 `(tenant_id,group_jid)` 升序，解析出 ID 后的 P/I/M/B 按 groupId 及各表实际命中的唯一键升序；新 M 也不得用尚未分配的 participantId 当排序依据。PN/LID 合并先锁较小 participantId 再锁较大 ID，invite 更新固定 P→I。输入 ID 必须先去重排序；只对数据库 deadlock / lock timeout 使用同一已接收的 envelope.eventId 做有限次数退避重试，并记录重试耗尽指标，业务校验失败不重试。

## 8. 内部 ID 与现有 API 的兼容边界

六表内部区分 `wa_group.id`、`wa_group_invite.id` 和过程表 ID；外部继续使用现有 `/api/group-links` 和 opaque `groupLinkId`。

迁移先为每条 legacy row 分类：能可靠解析 JID 的映射到 G，有真实 invite code 的映射到 I，两者都有时同时记录两个内部引用；wa:// 只代表 G，不生成邀请。冲突 JID/code 不自动猜测，进入只读报告。

本期默认逐条保留列表 legacy `id`、行集合、来源、分组、备注、删除作用域和动作结果。最小兼容设施可以先放在冻结的 `group_link` additive canonical 列，也可以在旧表删除前迁到 `group_legacy_handle`；它只负责 public handle 到 G/I 的解析以及保持现有 alias 级合同，不得复制 WhatsApp 当前资料、成员、角色或账号关系。

这里存在一条必须诚实面对的可实施性门禁：如果 test1 存在同一 JID 多个 active legacy 行，且 folder/remark/source/deleted 等属性不同，那么“G 每 JID 唯一”“删除 group_link”“列表与动作完全不变”不能只靠纯 ID map 同时实现。此时必须长期保留能够表达这些 alias 属性的 handle 设施，或者暂不删除旧承载；未经用户另行批准，不得把多行折叠为一行。

历史任务的 group_link_id 和执行快照不改写。活跃过程表可增加 nullable `group_id/group_invite_id` 供后端查询优化，但回滚窗口继续双填现有 legacy ID；这只是内部引用，不引入 typed API、resourceType、capability 或协议 payload 变化。

默认数值 `id` 不变。只有实现验证证明无法保持时，才允许前端增加一个不透明 request key 并替换 row-key/请求 key；后端仍承担全部 G/I 解析，前端不根据 key 分支业务。

### 8.1 删除、分组和导入保持现网语义

删除、分组、重复导入、恢复、批量操作、权限、提示文案和结果原子性全部保持当前 endpoint 行为。G/I 的拆分只发生在后端内部，不能把当前一个动作改成“归档真实群”“隐藏邀请”或按 resourceType 分支。若现有行为本身存在混合语义问题，另立产品需求处理。

## 9. 列表查询设计

### 9.1 删除展开显示与 SQL 优化的边界

用户已要求移除行展开显示，但这只授权前端不渲染，不授权后端删除字段或把原有值改为空。现有 DTO 的字段和值继续兼容；只有完成全调用方扫描并另行确认 API 合同变更后，才能停止某个字段的 enrichment。因此，单独删除 Vue 展开模板不会自动简化 SQL，本期 SQL 优化仍依赖 page-first 和按页批量 enrichment。

删除 Vue 展开模板本身不会自动移除旧 SQL 的全量 JOIN 和成员聚合，查询仍需改造。

### 9.2 默认查询：现有行集上的 page-first + 按页 enrichment

本期完整保留 `GET /api/group-links` 的行集合、响应字段、筛选、排序、分页和状态口径。查询只优化执行方式：

1. count 只连接当前筛选实际需要的数据源，但谓词与旧查询等价；
2. page-id 先按现有筛选和稳定排序取得本页 legacy row IDs；
3. 按本页 ID 批量查询 G/P/I 基础信息、M/B/S 管理员与账号关系、folder/label/import 来源和任务状态；
4. Service 按原顺序合并，禁止内存分页、页后过滤和逐行 Mapper 调用；
5. count、page-id、enrichment 共用同一字段派生定义。

I-only、同 JID 多 legacy 行、sourceFileName、labelId、status 和所有 null fallback 都必须逐行保真。不得改为 `G UNION I-only` 后折叠 resolved alias，也不得引入 listAdoption、resourceType、resourceKey 或 capability。

性能目标来自 test1 基线：去掉全租户无条件成员聚合，把大表访问限制为筛选所需 EXISTS 和本页批查；具体索引与 SQL 在实施计划中用 `EXPLAIN ANALYZE` 固定。

### 9.3 前端与批量动作不变

当前按钮显示、禁用条件、删除确认、批量原子性、请求/结果计数和任务进度全部保持。后端可以内部解析 canonical G/I，但不得把 `CANONICAL_DUPLICATE`、mixed-type capability 或新的 reasonCode 暴露给前端。

除已经确认的行展开区删除外，前端不改列表业务。如果新模型确实无法继续提供统一数值 `id`，允许新增一个不透明请求 key 并集中替换 row-key/请求 key；页面不得解析其前缀，也不得据此分支业务。

## 10. 旧字段到新模型的迁移

### 10.1 主表和资料

| 旧字段 / 表 | 目标 | 迁移规则 |
|---|---|---|
| group_link.id | legacy handle → group_id / group_invite_id | 旧数值 ID 原样作为外部 handle；G/I 使用独立内部主键，两者都有时 handle 同时保存两个 nullable 引用 |
| group_link.link_url | wa_group_invite.invite_code | 只迁真实 chat.whatsapp.com code；wa:// 假链接丢弃 |
| group_link.group_name | wa_group.display_name / invite.display_name | 这是 Armada 本地展示名；已解析群进 G，未解析邀请进 I，绝不能当 WhatsApp subject |
| group_link.label_id | wa_group_invite.label_id 或 handle 兼容值 | 未解析邀请直接迁 I；同一已解析群的多个旧行标签不一致时逐行留在 handle，不能写成一个群级值 |
| group_link.import_batch_id | 导入 detail / batch | 不进入六表；导入历史已有过程表 |
| group_link.origin | group.first_seen_source / invite.first_seen_source + handle 兼容值 | 内部按实体迁移，但 v1 展示/筛选继续返回旧行 origin，不重算口径 |
| group_link.membership_state | B/M 内部关系 + handle 兼容值 | 新事实拆分存储；v1 继续返回旧行状态，不启用新的派生优先级 |
| group_link.is_historical | B/S + handle 兼容值 | B/S 承载账号事实；v1 列表和现有业务继续复现旧 sticky 结果 |
| group_link.is_post_control | B/S + handle 兼容值 | 迁移不得由 joined_at 新造 post-control；已有 v1 结果保留 |
| group_link.sync_protocol_mask | handle 兼容值 | 六表不把它当当前事实，但现有 DTO/筛选若仍消费则继续返回旧值 |
| group_link.folder_id | wa_group.folder_id 或 handle 兼容值 | 同 JID 所有有效旧行值一致时可迁 G；存在 alias 差异时逐行留在 handle |
| group_link.remark | G/I 或 handle 兼容值 | 单一 canonical 值可迁 G/I；同 JID/code 多旧行不一致时逐行留在 handle |
| group_link.deleted_at | G/I 内部状态 + handle 兼容值 | 每条 legacy row 的当前可见性和删除/恢复结果保持；不借迁移拆成新的归档/隐藏产品语义 |

展示名与 WhatsApp subject 分离：

- group_name 原样迁 display_name；preview.wa_subject 才迁 profile.subject。两者不同不是数据冲突，而是本地覆盖功能。
- v1 列表名称与 fallback 顺序由当前 Mapper/Service golden fixture 固定；内部 G/P 分层不能改变显示结果。
- 旧 preview.avatar_url 同时被“本地资料头像”和“WhatsApp 图片回读”两条路径共用，无法从存量证明来源。为保持当前 UI，已解析群先保守迁 G.display_avatar_url，未解析迁 I.display_avatar_url；P.avatar_url 只由有来源版本的 metadata 回填。列表头像 `COALESCE(G.display_avatar_url, P.avatar_url)`。
- `/profile`、`/subject`、`/picture` 的请求、结果和当前副作用保持；内部可分别写 G/P，但兼容行为以现有实现为准。

### 10.2 preview、health 与邀请

| 旧字段 | 目标 | 规则 |
|---|---|---|
| preview.group_jid | wa_group.group_jid | 与 wa:// JID 不同则隔离 |
| preview.invite_code | wa_group_invite.invite_code | 结合 invite_code_observed_at 建版本 |
| preview.wa_subject / description / settings | profile | 仅已解析群；未解析才留 invite.preview |
| preview.avatar_url | G/I 本地展示覆盖；P 需可靠 metadata 重建 | 存量无本地/WA 来源标记，不能直接宣称是 WhatsApp 当前头像 |
| preview.member_size | profile.member_count 候选 | 与其他人数按事实时间比较 |
| preview.owner_phone | handle/兼容投影；可作为 participant OWNER 回填候选 | v1 `creatorPhone` 继续返回该旧字段，不从当前 OWNER participant 重算 |
| preview.group_created_at | profile.wa_created_at | 秒转毫秒 |
| preview.creator_country_* | handle/兼容投影 | v1 列表、历史和导出按当前值/算法返回，不在本期统一重算 |
| preview.metadata_observed_at | profile 字段版本 | 作为各非空 metadata 初始 observedAt |
| health.current_count | profile.checked_member_count | 与 metadata/member snapshot 的 member_count 分开保存；列表继续按现有 `currentCount ?? memberSize` 回退 |
| health.health_status=LINK_INVALID | I/P 内部状态 + handle 兼容值 | 当前 status/filter 与批量门禁结果不变 |
| health.is_banned=1 | P 内部状态 + handle 兼容值 | 当前 BANNED 显示/筛选不因重新解释 error domain 改变 |
| health.last_check/error/failure | I/P 或保留 task + handle 兼容值 | 当前 DTO、状态和调度退避保持；错误分域治理另立需求 |

现有 is_banned 语义混合。内部可在冲突报告中标记来源不明，但兼容投影仍必须复现当前值；本期不修正为新的状态分类。

### 10.3 账号关系和 baseline

| 旧字段 / 表 | 目标 | 规则 |
|---|---|---|
| account_group_membership.account_id / group_jid | binding + participant | 用 account.ws_phone 构造确认 PN participant |
| membership.is_admin | participant.role 候选 | 与完整成员/角色事件按时间比较 |
| membership.membership_status | participant.presence 候选 | 按 status_updated_at 和显式 exit 事实比较 |
| membership.joined_at | binding.membership_active_since_at；不回填 binding.first_post_control_observed_at | 原值只迁兼容字段。**禁止**迁 first-post；本期 legacy migration 只能写 was=1/NULL，绝不迁 0，因此 first_post_control_observed_at 固定为 NULL |
| membership.last_seen_at | binding.last_observed_at | 保留 |
| membership.last_exit_* | participant.last_exit_* 候选 | 与 departed_member 合并取可靠较新值 |
| account_group_baseline JSON | binding baseline + sync_state header | 只有下述 state=2 + 真实合法 row 才迁；不能以 row 存在推断已拍 |
| account.group_baseline_state | sync_state.baseline_filter_enabled / baseline_state | state=3 是过滤策略关闭，不代表账号未绑定；迁后从 account 删除 |
| account_group_baseline.last_group_sync_requested_at | sync_state.last_sync_requested_at | 迁值后随 Phase 6 删除 account_group_baseline |

软删账号遗留的未软删 membership 不属于当前账号关系，影子回填按现有账号过滤跳过并记录审计；账号行真正缺失、未删除账号无法解析群入口或账号手机号非法仍是硬冲突。

迁移以 account.group_baseline_state 为主值，不能以 account_group_baseline row 是否存在判断 CAPTURED。现有 markGroupSyncRequested 会为没有 baseline row 的 state=2/3 账号插入 `JSON_ARRAY()/count=0/captured_at=requestedAt` 只为保存同步水位，而且后续请求只更新 last_group_sync_requested_at/updated_at；因此时间相等式只能提示 placeholder，不能永久、确定地区分“真实空 baseline”。WATERMARK_ONLY 必须有创建版本、审计/binlog或调用链证据；state=2 的空数组若无正向 provenance 一律记 AMBIGUOUS_EMPTY_BASELINE，人工签字，不能自动当真实空集合。确定性矩阵如下：

本期影子回填只接受一种现有调用链可解释的空集合证据：账号未删除、state=2、JSON 为合法空数组、`last_group_sync_requested_at IS NULL`，且协议捕获时间 `captured_at` 与落库 `created_at` 不同。它对应 `capturePendingAccountGroupBaseline(syncAt, now)` 的真实捕获形态；水位占位路径会同时写请求时间且两个时间相等。该证据只允许写 `CAPTURED/LEGACY_UNKNOWN/count=0`，不创建 baseline binding、不写 first-post，也不代表最终 writer/read cut 已获批准；不满足该形态的空集合继续阻断。

现有 `AccountGroupMembershipSnapshotServiceImpl.membershipRow` 每次都把本次 `syncAt` 赋给 `joined_at`；`AccountGroupMembershipMapper.xml` 又会在新行、旧值为 NULL，或退群后再回到在群时改写它。所以该列是“快照首次建行/最近回群观察时间”的混合值，不是 WhatsApp 首次入群事实，也无法单独证明发生在 baseline 之后。该值仅原样迁入 `membership_active_since_at` 以保持现有营销截止和导出；本期输入集的 was 只可能是 1/NULL，first-post 实际回填一律 NULL。不允许用 `COALESCE`、行存在性或“JSON 未列出”推导 was=0。

| legacy 状态 | row 证据 | 目标 |
|---|---|---|
| 1 PENDING | 无 row，或可证明 WATERMARK_ONLY | baseline_filter_enabled=1；active binding 为 PENDING/NONE，未绑定为 DISABLED/NONE；只迁 sync watermark |
| 1 PENDING | 非空 / 其他声称 baseline 的 row | STATE_ROW_CONFLICT，人工判断，不能自动拍 baseline |
| 2 CAPTURED | 非空 JSON，或有正向 capture provenance 的空 JSON；且 JSON_TYPE=ARRAY、group_count=JSON_LENGTH、JID 全合法去重、时间非负 | baseline_filter_enabled=1；S 写 CAPTURED/LEGACY_UNKNOWN，JSON 中明确群的 B 写 was=1 |
| 2 CAPTURED | 缺 row、可证明 WATERMARK_ONLY、无 provenance 的空数组、非法 JSON/count/JID | AMBIGUOUS_EMPTY_BASELINE / BASELINE_EVIDENCE_CONFLICT，阻断 writer cut |
| 3 DISABLED | 任意 row / 无 row | baseline_filter_enabled=0；当前账号群关系仍保留，S=DISABLED/NONE，B.was=NULL；row 只迁 sync watermark，JSON 不得当 baseline |

state=3 的非空 JSON 进入异常报告并在 drop 前导出 / 签字处置，但不能改写现网“不启用过滤”的行为。本期没有 synthetic/retired binding lifecycle。

通过上述矩阵的真实旧 baseline 没有可信 v1 `snapshotComplete` 证据，因此只写 baseline_completeness=LEGACY_UNKNOWN：

- JSON 中明确列出的群可写 was_in_initial_baseline=1，继续保守排除；
- JSON 未列出的既有 binding 保持 NULL，不能断言“不在 baseline”；
- LEGACY_UNKNOWN 不得仅凭集合差异触发“上控后新群立即营销”；
- 迁移/backfill 只记录事实，不创建“上控后新群立即营销” effect intent；切换后的实时触发继续调用当前业务判断（包括 Web 的现行完整性兼容），禁止切换后扫描迁移字段补发；
- 是否由运营重新建立可信基线必须单独审批，不能把迁移当天的当前群集合冒充最初上线前 baseline。

### 10.4 成员表簇

| 旧表 | 目标 | 规则 |
|---|---|---|
| whatsapp_group_member_snapshot | participant + profile snapshot header | 同群最新 snapshot_at 归并；重复身份先报告 |
| whatsapp_group_member_cache | profile snapshot header | 与 snapshot 表对账版本、观察账号、subject、announce |
| whatsapp_group_member_state | participant | 旧表只有共享 state_updated_at，不能假装存在两个独立时钟；按下述 source 白名单拆维度 |
| whatsapp_group_member_join_fact | participant.last_join_* | 每成员现有最新事实可无损折叠 |
| whatsapp_group_departed_member | participant.last_exit_* | 每成员现有最新事实可无损折叠 |

旧成员事实若没有任何旧群入口、账号关系或 baseline 引用，则不属于旧列表可达数据：只进入迁移审计，六表影子回填按内连接跳过，不能凭成员缓存凭空创建一个运营群。

`whatsapp_group_member_state` 的 presence/role 虽拆到不同列，回填值必须复现旧表当前可见结果；不能把旧 promote/demote 顺带形成的 `is_in_group` 在迁移时擅自改成 UNKNOWN。`state_source/source_event_id` 只用于冲突报告和以后治理，本期 golden/shadow parity 优先；迁移时间不得使用 now() 覆盖实时事实。

`whatsapp_group_member_snapshot` 本身是旧详情成功后整体替换的完整快照。迁移按租户和规范群 JID 选最新 `snapshot_at`（同时间按 `group_link_id` 确定），生成稳定的 `legacy:<group_link_id>:<snapshot_at>` 版本，同时写 profile 快照头和对应成员的 `last_snapshot_version`。如果新模型已经有时间更晚的完整缓存快照，旧详情快照不得覆盖；普通成员当前态、进退群事实仍按各自事实水位合并，不能用迁移时间覆盖实时事件。

若业务未来需要完整的 append-only 进退群审计，应另立事件审计需求；当前旧表本身也只保留每成员最近一次，不应假装它们是完整历史。

### 10.5 人数冲突

member_count 的迁移必须先按当前列表/详情使用的优先级得到兼容值，再写入 P；health/preview 时间冲突另出报告，不能在本期换成新的优先级。切换后 Adapter 继续按当前事件路径更新，列表逐值对账。

### 10.6 legacy groupLinkId 兼容规则

不同旧引用可能代表真实群、邀请输入或历史执行快照，不能机械执行 `group_link_id = group_id`：

- 当前群查询/任务目标可新增 nullable `group_id`；
- 邀请导入、进群输入可新增 nullable `group_invite_id`；
- 同时包含输入邀请和结果群的过程记录分别保存两者；
- 已结束历史记录保持原 group_link_id、JID、URL、subject 和结果快照；
- 回滚窗口的新写继续填写现有 legacy ID，并可额外填写内部 canonical ID；
- 旧唯一键和任务幂等语义不改，不用 `INSERT IGNORE` 丢冲突。

每张保留表在实施计划中逐项标注“当前引用 / 输入快照 / 结果快照”，只迁移当前引用；无法安全分类的历史行通过 legacy handle 打开。协议 outbox、DLT 和 Android JSONL 继续保存并发送当前 payload，不增加 resource_type、schemaVersion 或 V2 字段。

### 10.7 alias 兼容设施的去留

只有 test1 报告证明不存在需要保留的 alias 级业务差异，且所有活跃引用都可由 canonical ID 无损解析，才允许把 handle 收窄成纯 ID map。否则 handle 长期保留，物理表总数会多于六张，但六张仍是 WhatsApp 群当前事实的唯一权威表。不得为了宣称“最终只有六张物理表”而改变列表或任务行为。

## 11. 全业务依赖闭包

全仓排查覆盖下面所有消费角落，但本期只替换它们读取/写入群当前事实的位置，流程、状态、参数和结果保持。

| 业务角落 | 当前主要依赖 | 新模型内部来源 | 兼容门禁 |
|---|---|---|---|
| 群列表/筛选/分页/详情 | link/preview/health/member/membership/task | G/P/I/M/B/S + 保留表 + legacy handle | 行、字段、筛选、顺序、状态和动作相等 |
| 本地资料与分组/删除 | group_link | G + legacy handle | endpoint、作用域、权限和结果不变 |
| WhatsApp 资料/设置/成员 | preview/snapshot/cache/state | P/M/I | 当前命令、回读和错误行为不变 |
| 邀请导入/label/batch | link + import batch/detail | I + 原过程表 | 成功/失败/重复、来源和分组不变 |
| 账号群快照/群数量/执行账号 | membership/baseline/account_state | B/S/M + account | 当前 complete、计数、管理员选择口径不变 |
| 历史群/上控后群/即时营销 | baseline/membership/sticky flags | B/S + 兼容 Service | 当前资格和触发不变；迁移不得误发 |
| 进群/拉群/waiting pool | groupLinkId/JID/URL/membership | G/P/I/M/B/S + 任务快照 | 调度、占用、重试和结果不变 |
| 群组营销/导出 | membership/member facts/task snapshot | G/P/I/M/B/S + 原任务表 | 固定/动态目标、发送、导出列不变 |
| 建群营销/普通建群/direct create | 各自 task/item/result | 成功后按当前调用链登记 G/P/I/M/B | 不重做协议、幂等、步骤或补偿流程 |
| metadata/health/batch task | preview/health/task | P/I/M + 原任务表 | 租约、退避、逐项结果不变 |
| 历史任务与报表 | frozen groupLinkId/JID/URL/subject/result | 原任务快照；当前状态才 enrichment 六表 | 历史证据不改写 |
| Kafka/outbox/DLT/Android JSONL | 当前 v1 payload | 当前 Consumer → Java Adapter | topic/payload/retry 不变 |

已确认的合同雷区：`GroupListRow.id` 被详情、删除、分组、成员、进群、批量、营销和多类任务共同使用；`row.url` 仍被进群页消费；`creatorPhone`、groupCreatedAt 秒值和多个 groupId 名称具有历史含义。后端必须在兼容层消化这些歧义，不能要求前端改字段、改动作或把旧历史 ID 批量替换。

### 11.2 当前协议事件由后端 Adapter 落六表

本期只消费已经上线的 v1 topic 和 payload，不新增 producer 字段、事件名、schema 或 topic。

| 当前事件/入口 | 本期后端行为 |
|---|---|
| `account.groups_reported` | 继续进入现有账号群报告语义；Web 的 `snapshotComplete=null + skippedGroupCount=null/0` 仍按当前逻辑视为完整，Android 使用现有显式字段；Adapter 批量写 S/G/P/M/B |
| `account.group_membership_changed` | 保持当前 self membership 写入、分类和副作用语义，只替换底层表 |
| `account.group_participant_joined` | 保持当前 Android WGP2 join 映射，使用已有 joinedAt/sourceEventId |
| `account.group_participant_departed` | 保持 LEFT/REMOVED/UNKNOWN 与 exitedAt/sourceEventId 映射 |
| `account.group_past_participants.reported` | 保持 HistorySync 最近退出事实；它不是完整成员快照 |
| Web `group.participant_changed` | 保持当前 Java consumer 行为：现有 promote/demote 路径继续，当前被忽略的普通 add/remove 仍不新增消费 |
| 当前 invite/health/metadata/command-result 事件 | 逐项复用现有 Consumer/Service 的字段判断、错误和副作用，只把当前事实写入六表 |
| `protocol.normal-group.events.v1` | 保持现有 normal-group result 处理和任务结算，不改 topic、payload、幂等或协议执行流程 |
| HTTP metadata/命令回读、定时任务、导入和建群结果 | 保持当前调用链；后端边界统一委托六表写入组件，不要求协议重发 |

内部 Adapter 映射固定为：

| 内部值 | 当前来源 |
|---|---|
| tenant/account/protocolAccount/backend | 现有 payload 与当前账号解析逻辑 |
| observedAt | 事件原始时间；没有则沿用 envelope occurredAt/reportedAt |
| eventId | 现有 envelope eventId |
| sourceEventId | payload 有则使用，没有则退化为 eventId |
| snapshotId | 现有 snapshotVersion；没有则使用 eventId |
| complete/skipped | 完整复用当前 `completeSnapshot()` 判断 |
| scope/detail | 仅按当前事件类型作后端内部分类，不新增 wire 字段 |
| binding token | 本期不保存、不要求 wire 提供 |
| fieldMask | 不新增；只写当前 payload 实际提供且旧逻辑本来会写的字段 |
| sequence | 无上游序列时固定为无序列，不伪造 |

### 11.3 已知协议风险（不在本期修复）

- Web 没有 complete/skipped/queryStartedAt，后端当前 null 推断可能把遗漏群当完整；修复会改变 baseline、退群和营销资格。
- 当前协议没有端到端 binding token，无法完全隔离极端迟到的旧 session 事件。
- Web 部分 eventId 在重新发布时不稳定，普通 participant add/remove 当前未闭环。
- Android 本地 JSONL 的 retention/replay、Web/Android 统一 fieldMask、稳定 sourceEventId、V2 schema/topic 和 per-target spool 都属于独立协议可靠性需求。
- normal/direct/营销建群的 durable result、Idempotency-Key、外部成功崩溃恢复属于建群可靠性需求，不夹带在群表迁移中。

这些风险保留现状并在 test1 做黑盒回归；它们不能成为修改 `armada-protocol` 或 Android 的本期门禁。

### 11.4 现有表的最终去留清单

| 处置 | 现有表 / 字段 | 原因 |
|---|---|---|
| 六表切稳且兼容依赖归零后删除 | group_link_preview、group_link_health、account_group_membership、account_group_baseline（含 last_group_sync_requested_at）、whatsapp_group_member_snapshot、whatsapp_group_member_cache、whatsapp_group_member_state、whatsapp_group_member_join_fact、whatsapp_group_departed_member、account.group_baseline_state；`group_link` 仅在 alias/handle 可无损迁出后删除 | 当前事实迁入六表后不再双写；`group_link` 若仍承载外部 ID 或 alias 级属性，就以冻结兼容形态保留，不能为了表数强删 |
| 保留配置/主数据 | group_folder、group_link_label、account_group、country、country_phone_prefix_mapping | folder / invite label / 账号分组和国家配置不是 WhatsApp 当前事实；country 只作为派生输入，prefix mapping 继续服务 IP 等前缀业务，不得成为六表第二主值 |
| 保留导入审计 | group_link_import_batch、group_link_import_detail | 冻结每次文件、重复、成功失败结果；新成功引用使用 group_invite_id |
| 保留群同步任务 | group_metadata_sync_task、group_batch_task、group_batch_task_item | 保存租约、进度、重试和逐项结果；活跃记录可增加 nullable 内部 group_id，外部合同不变 |
| 保留进群与营销 | join_task、join_task_result、marketing_task、marketing_task_target、marketing_task_send_attempt、marketing_task_success_group、marketing_task_export_job、marketing_account_occupancy | 业务过程 / 结果证据；当前资格才读六表 |
| 保留建群营销 | group_creation_marketing_task / item、group_pull_marketing_task / execution / material / execution_material / account_stat | 任务输入、步骤、部分成功与历史结果 |
| 保留历史群拉取 | historical_group_pull_execution、historical_group_pull_member | 某次拉取的冻结执行结果，不是成员当前态 |
| 保留拉群任务 | pull_task 及 group_execution、group_account、account_action、pull_call、pull_call_member_attempt、pull_wave、member_query、material_member、各 setting / summary / occupancy 表 | 调度、占用、请求和结果历史；当前群 / 成员 / 邀请改读六表 |
| 保留普通建群 | normal_group_creation_admission_lock、task、item、item_member、item_secondary_admin | 四阶段执行和幂等补偿证据 |
| 保留协议可靠性 | protocol_command_outbox、Kafka topic/offset、现有 DLT、Android 失败日志；如迁移实现需要数据库 event inbox 必须显式新增 | 命令可靠投递和事件重放，不是群事实 |

保留表允许冻结 subject、groupJid、inviteUrl、memberCount、participantJid 等“执行时快照”，但字段名 / 注释必须标 snapshot，禁止被 Reducer 当当前值读回。活跃记录仅在查询优化确有需要时增加 nullable 内部 `group_id/group_invite_id`；已结束历史记录维持原值并通过 legacy resolver 打开。无法无损解析的记录长期保留 legacy handle，不作为清表阻断之外的新业务改造。

`group_metadata_sync_task` 兼容期增加 nullable group_id，并补以下投影列：`last_probe_result TINYINT NOT NULL`（UNKNOWN/SUCCESS/TEMP_UNAVAILABLE）、`last_probe_error_domain TINYINT NOT NULL`（NONE/GROUP_QUERY_TRANSIENT/EXECUTOR_ACCOUNT/NO_EXECUTOR/DELIVERY/PAYLOAD/UNKNOWN）、last_probe_error_code、`last_probe_fact_version_key VARBINARY(128) NOT NULL`、`last_success_fact_version_key VARBINARY(128) NOT NULL`（两个 key 都与 FactVersion canonical key 同型，未观察为 `0x00`）、last_probe_at、last_success_at、consecutive_probe_failures。它承接旧 health 的调度水位、临时失败和退避。候选按 next_run_at / last_probe_at，不因删除 health 后反复抢占；任何 accepted positive metadata / current-invite read 在同一 Reducer 事务 upsert task 并推进 success key，只有第 6.1 节 GROUP_QUERY_TRANSIENT 才 CAS TEMP probe key。UNAVAILABLE 唯一判定是 `last_probe_result=TEMP_UNAVAILABLE AND last_probe_error_domain=GROUP_QUERY_TRANSIENT AND last_probe_fact_version_key > last_success_fact_version_key`；晚到失败不得覆盖成功，P/I 仍只接收明确的群 / 邀请事实。

### 11.5 国家/大洲口径保持

当前列表、历史和营销导出存在不同号码解析路径，这是已知技术债。本期分别复现各自现有输出，不统一 resolver、不重算国家投影、不改变筛选命中；统一算法必须另立业务需求。

## 12. API 兼容策略

### 12.1 现有 API 是正式兼容合同

本期持续保留现有路由、请求/响应 DTO、字段名、时间单位、权限、错误码和动作语义，包括 `/api/group-links`、导入/迁移/label、batch-preview、详情、成员操作及全部任务引用。

`groupLinkId` 继续作为调用方看到的不透明标识。后端 resolver 将它映射到内部 G/I；历史任务中的 ID 与执行快照不批量改写。默认数值 `id` 不变；若无法保持，只允许前端做 request-key/row-key 映射。

用户已经要求删除的行展开显示不再渲染；其 DTO 字段仍兼容返回。v1 `groupCreatedAt` 继续返回秒，`creatorPhone`、`url` 和现有进群参数不改。

### 12.2 新 API 不在本期

`/api/groups`、`/api/group-resources`、`/api/group-invites`、participantId 动作、typed DTO 和 capability 都不实现、不联调、不作为旧表删除前置。若未来需要 API 拆分，单独评审。

### 12.3 历史任务

任务创建时冻结的 groupJid、inviteUrl、subject、memberCount、执行账号和结果是历史证据。详情页面优先展示任务快照；只有“当前状态”区域才 enrichment 六表。这样迁移后不会让旧任务随群改名或链接轮换而改变。

## 13. 分阶段迁移

首期默认采用“可回滚的短暂停写切单 writer”，不以零停机为目标。Phase 2M 是方案 A 的实施基线；零停机双发或跨版本切换不在本文设计，若未来业务明确要求，再单独评审必要性、表数和测试预算。

### Phase 0：冻结语义和依赖清单

- 确认本设计六表字段、状态和 API 边界。
- 对所有 groupLinkId 按“真实群 / 邀请 / 历史快照”分类。
- 给全部旧表写入点登记 owner；未登记写入点不得进入迁移。
- 生成 test1 数据质量报告和基准查询，不做数据修改。
- 生成当前 account/protocolAccount 解析歧义报告，只用于证明现有 Adapter 能稳定找到与旧代码相同的账号；本期不处置账号绑定或增加唯一约束。
- 暂停新增旧 group_link 当前态字段。

退出门禁：依赖闭包无“未知写入方”，用户评审设计通过。

### Phase 1：只扩展，不切流

当前已批准的首个实施切片仅为 `V120__group_data_model_foundation.sql` 中六张表的最小字段集。本文其余 `field_version_keys`、`*_version_key`、`membership_epoch`、`pool_hidden_at`、`group_status`、新 outbox 等候选设计均不进入本切片，也不得据此补入 V120；只有后续代码出现当前字段无法承载的可复现问题时，才单独举证评审。test1 对账已举证：已解析群即使没有当前邀请码也会产生群健康事实，而 V120 只在邀请表保存健康字段，导致约 9768 条已解析群状态丢失。因此 V121 仅给 `wa_group_profile` 增加现有兼容列表所需的 `health_status`、`banned`、`last_checked_at`、`last_error_code`、`failure_count`；已解析群写 profile，未解析邀请仍写 invite，不增加表、协议字段或新业务状态。

- 用一组按部署边界拆开的 additive Flyway 版本创建六表、约束、索引和兼容列；不把数据回填塞进 migration。
- 增加六表内部 canonical ID、Reducer/Mapper、只读对账服务、legacy handle 和仅供账号快照使用的 `group_snapshot_effect_outbox`。把仍会创建/修改 account_group_baseline 的旧入口登记到 writer 清单；不修改 account 绑定模型，不建设 V2 admission、binding history 或新生命周期 Service。
- 旧 API 和业务行为保持；六表发布不要求同步发布前端或两个协议项目。已确认的展开区删除可以独立发布，不与模型切换绑在一起。
- Backend consumer 继续反序列化当前 DTO，由 Java Adapter 将现有 `snapshotVersion`、`snapshotComplete`、`skippedGroupCount` 等字段映射到六表命令；不新增或等待 bindingGeneration、fieldMask、V2 snapshot 字段。
- 数据 backfill 不放进 Flyway。nullable 兼容列优先 `ALGORITHM=INSTANT, LOCK=NONE`，二级索引独立使用经克隆验证的 `ALGORITHM=INPLACE, LOCK=NONE`；执行前设置短 lock_wait_timeout、检查长事务/metadata lock，由单一 migration runner 串行执行。MySQL DDL 非事务，必须演练中途失败后的结构探测、幂等续跑和 Flyway repair。
- 旧 writer 仍运行时先做一次可重入预填并记录每张源表的主键/updated_at 水位：G/P/I/M 全量，B/S 按第 10.3 节保守迁移。本阶段六表只供 shadow read，不触发任务/营销，也不宣称已经追平；Phase 2M 暂停后必须从这些水位补最终增量并重新跑全量 count/hash 门禁。

退出门禁：干净 MySQL 8.4.8 Testcontainers 从 V001 执行到新版本、V116 生产结构快照升级到新版本均通过，并覆盖 JSON/CHECK/NULL 唯一/generated key/锁并发；CI 若因无 Docker 跳过该测试则迁移构建失败。H2 仅继续验证 Mapper/Service 逻辑，不替代 MySQL DDL。克隆库 `EXPLAIN ALTER`、预计数据/索引增量、磁盘/buffer pool/undo/binlog/复制延迟余量都有签字；六表为空且 flags 关闭时全部旧业务无行为变化。

### Phase 2M：首期短暂停写切换（实施基线）

- 暂停账号群快照、群事件 consumer、群相关 scheduler/API，以及会产生建群、进退群、metadata、营销副作用的 worker；停止新 command admission。
- 排空 Kafka 已提交前的群事件和 protocol_command_outbox；所有已下发命令必须有成功/失败/取消终态，UNKNOWN 阻断切换，不为它新增账号绑定历史或协议端 journal。
- 记录旧表最终主键/updated_at 水位，按同一 migrationRunId 重跑最终增量和 baseline backfill；执行第 15 节 count/hash/冲突/营销零增量门禁。
- Phase 1 必须已经完成所有当前态 reader 的新 SQL shadow 对账。暂停窗口内把唯一 writer 和全部“群当前态”读 flag 同时切到六表；旧表冻结但保留，历史任务继续读自己的冻结快照。任一 reader 未就绪或 flag 校验失败，都在恢复入口前把 writer/read flags 一起退回旧模型，六表预填继续只读；不允许先恢复新 writer、再让旧 reader读取已经停止更新的旧表。
- 恢复 consumer/worker 后，按现有账号群同步命令触发当前 Web/Android 报告，并由现有 complete 判断处理；迁移已有 B（包括 was=NULL）不得因迁移值产生即时营销。观察 DB 锁、SQL 数、lag、effect outbox 和新读 SQL；恢复后不再把旧当前态表作为读回滚目标，只能修复后 roll-forward。

退出门禁：旧 writer 运行指标为 0；新 writer 单写；迁移 effect 可执行增量为 0；在途/UNKNOWN command 为 0；MySQL RR 并发与 SQL `<=10` 门禁真实运行且未 skip。若暂停窗口无法排空，直接恢复旧 writer 并终止本次切换，不临时引入双写状态机。

### Phase 3：实时 writer 收敛后回填易变事实

- Phase 2M 前必须已迁完所有当前读接口必需字段；本阶段只修复不影响正确读取的低置信/缺失 metadata 和明确冲突，不能把核心字段回填推迟到读切换后。
- 以 Phase 2M writer barrier 后的一致快照按 tenant_id + legacy id 分片、短事务修复；新事实已走 Reducer，不存在“扫过后旧 writer 又改”的窗口。
- 身份与 legacy baseline 地基已在正式 replay 前完成；本阶段只按 P 易变字段 → 其余 participant M 的顺序回填。B 与 S 不再接受普通 Phase 3 migration 写入；当前 binding 关系只由已接管的实时 fact 更新，旧 membership/baseline 漏项必须退回 barrier 修复，不能在 REDUCER epoch 下临时扩权。
- 再次校验六表 `AUTO_INCREMENT > MAX(id)`；回填重跑只能按 canonical key / legacy 映射 upsert，不能因自增游标变化生成第二套 ID。
- backfill 每个字段必须使用旧表可证明的原事实时间；没有可信时间时 observedAt=0、source=MIGRATION_UNKNOWN。绝不能用迁移执行 `now()`，否则“低 priority”仍会因 observedAt 更大而压过实时事实。
- backfill 只写 MIGRATION_BACKFILL / MIGRATION_UNKNOWN 版本，并通过同一个 Reducer CAS，不覆盖 Phase 2 后更高版本的实时事实。
- 输出冲突表或结构化报告：JID 冲突、code 冲突、群名冲突、PN/LID 冲突、人数冲突、baseline 完整性未知、孤儿任务引用。
- 不在迁移脚本中远程调用 WhatsApp；需要刷新确认的行进入受控任务队列。
- 结束前从 barrier 水位重扫所有 updated_at / deleted_at 可判定旧行并做全量 key 对账；Phase 2M barrier 后发现任何旧当前态写都立即停止迁移并告警。

退出门禁：所有可确定数据迁完，异常数量和处置人明确，ID resolver 覆盖率达到 100% 或有逐行豁免，实时写与 backfill 并发测试无倒灌。

### Phase 4：影子读

- 在固定源数据水位下，对同一请求同时执行旧查询和新查询，用户仍取旧结果；采样有并发/DB 熔断。
- 比较行数、顺序、全部 DTO 字段、筛选、分页、状态、操作资格和业务数量。
- 后端 API/DTO/业务差异必须为 0；前端单独验证 DOM 不再渲染展开区。行折叠、membershipState 修正、国家算法变化和旧字段删除都不是 expected diff。
- 记录 SQL P50/P95/P99、扫描行数、临时表、排序和 explain 计划。
- 连续多个完整业务周期无未解释差异，才允许切读。

### Phase 5：按域切读

下列顺序用于 Phase 1 分域开发、测试和 shadow 验证；Phase 2M 暂停窗口必须一次切完全部当前态 reader，不能在恢复写入后继续让未切域读取冻结旧表。切换目标始终是现有后端 API 的内部 reader，不启用新的 typed `/api/group-resources`，也不以前端 capability 改造作为前置。

建议顺序：

1. 群详情和单群成员读取；
2. 执行账号选择；
3. 账号群数量与营销账号树；
4. 历史群和上控后逻辑；
5. 群组列表仍走 `/api/group-links`，只把兼容 VO 的底层查询切到六表；
6. 批量刷新、导入链接和任务候选；
7. 导出与所有历史任务 enrichment；
8. 前端按现有页面做回归；展开区删除独立验收，六表切换只有确需 key 适配时才追加前端版本。

每个域保留独立 feature flag 供切换前 shadow 和故障定位；Phase 2M 的生产切换由总门禁原子校验所有 flag。恢复新 writer 后不得单独把某域切回旧当前态表。

### Phase 6：清理冻结旧事实表

- 先确认所有旧当前事实 reader/writer 已迁移且运行访问为 0；过程表和历史任务继续保留。
- 现有 API 字段、legacy handle/Resolver 和历史解析能力不得因清理而删除。
- 如果 alias 级属性仍需保真，保留 `group_legacy_handle` 或旧表最小化形态；不得强行 drop。
- protocol outbox、DLT、回执和 Android JSONL 仍可能到达当前 payload，后端 Adapter/Resolver 必须持续可解析。
- Phase 6A 只清代码和冻结访问；至少两个业务周期后、逻辑备份与恢复演练通过，并经用户再次确认，Phase 6B 才可用独立 Flyway 删除已经证明无用的旧事实表。

删除旧表不可与第一次切读同版发布，也不承诺删除后秒级回到旧模型。

## 14. 部署顺序与版本兼容

### 14.1 推荐顺序

1. 发布六表、最小 migration run、`group_snapshot_effect_outbox`、兼容列和不依赖数据清理的 additive Flyway；旧业务仍只读写旧模型。
2. 部署新 Mapper/Reducer、当前事件 DTO 的 Java Adapter 和全部新读 SQL，但保持新 writer/read flags 关闭；不要求发布 Web/Baileys 或 Android，也不把展开区删除与六表发布绑定。
3. 执行 Phase 1 可重入预填和 Phase 4 shadow read；完成 test1 MySQL RR/SQL 数、列表性能、业务回归、count/hash/conflict、baseline 与迁移营销零增量门禁。
4. 现有 `/api/group-links` 的行、字段、筛选、动作和业务回归全部通过；默认 `id` 保持，若只能变更 key，则只完成前端 key 适配后进入切换。
5. 执行 Phase 2M：暂停全部群 writer/reader 入口和外部 effect，排空 Kafka/outbox/在途命令，按最终旧水位补增量并重跑门禁。
6. 在恢复流量前同时切换唯一 writer 和全部群当前态 reader 到六表；任一 flag/健康检查失败就整体退回旧模型，不能恢复半套。
7. 恢复流量后强制当前账号完整快照和 dirty 群修复，只允许 roll-forward；持续观察 DB 锁、SQL 数、effect outbox、Kafka lag、列表性能和业务差异。
8. 完成低置信 metadata 修复和至少两个完整业务周期观察后，按 Phase 6A 清理旧代码；Phase 6B 删除旧表必须另行确认和独立发布。
9. 若以后要做协议治理、typed API、前端瘦身或零停机切换，分别另立需求，不回填到本首期范围。

协议生产端不参与本期部署。后端必须在相同现有 topic/payload 下完成新旧存储 shadow 对账，证明 Adapter 对 Web 与 Android 当前事件的处理结果与旧逻辑一致。协议字段不足、迟到事件 fencing 或投递可靠性问题只进入风险清单，不阻断本次换表，也不由本次设计擅自改变处理口径。

### 14.2 回滚

旧表尚未删除时：

- Phase 2M 尚在暂停窗口且未恢复新 writer 时，writer/read flags 必须整体退回旧模型，不能只退一半。
- 一旦恢复新 writer，旧当前态表已冻结，不能再作为在线 read rollback。查询/VO bug 通过关闭有问题入口、修复新 SQL并 roll-forward；不得让该域回读冻结旧表。
- 若 Reducer 有问题，立即暂停对应 consumer/HTTP writer，保留 Kafka offset和 outbox 水位；以切换前旧表快照、六表和事件日志在临时表对账，由修正版 Reducer 重放/重建六表。旧表只是恢复证据，不重新成为主值。
- 如果本期确实发布过 key 适配，则回滚后端时同时切回对应 key 映射；没有 key 改动时前端无需回滚；
- 不逆向手工改共享库，不回滚已执行的 additive Flyway。

WhatsApp 命令已产生的建群、进退群、升降权、revoke 等外部副作用不可通过数据库回滚撤销；只能保留命令/回执证据并执行确认回读或经人工批准的补偿命令。回滚手册必须把 read rollback、data rebuild 和 external reconciliation 分成三个入口，禁止一个“回滚”按钮混做。

旧表删除后：

- 只能从备份恢复到临时表并重新投影，不再承诺秒级切回；
- 因此 drop 迁移必须在独立发布、完整恢复演练和用户确认之后执行。

## 15. 数据对账与硬门禁

### 15.1 结构与身份

- `(tenant_id, group_jid)`、`(tenant_id, invite_code)` 和 participant PN/LID 唯一约束无冲突；跨表 tenant/group/participant 指向一致。
- profile 当前 invite 指针合法；六表不存 wa:// 假邀请。
- 每个现有 public groupLinkId 都可在同租户解析，历史引用可打开；多 alias 行及其属性数量与旧模型相等。
- 若 test1 证明 alias 不是纯 ID，handle 设施必须被列为长期保留表，不能进入 drop 清单。

### 15.2 迁移与快照安全

- 旧 `membership.joined_at` 只迁 `membership_active_since_at`，一律不迁 `first_post_control_observed_at`；迁移产生 first-post 非空数必须为 0。
- `was_in_initial_baseline=1 AND first_post_control_observed_at IS NOT NULL` 数量必须为 0。
- 迁移 origin 不生成即时营销 intent/task/send attempt/protocol command，增量必须为 0。
- 现有 Web/Android 完整、部分、空快照在新旧模型上的群关系、baseline 和副作用逐项相等；不以缺 generation、queryStartedAt 或 fieldMask 拒绝当前事件。
- 同一当前 eventId 重放保持幂等；当前协议无法保证跨重新发布 eventId 稳定的风险不伪装成已解决。
- RR 锁序继续满足“普通一致性读 existing/missing、按表/唯一键排序、禁止 UPDATE 缺失键、M 全部早于 B”。

### 15.3 全业务数量与引用

- 列表、详情、账号群数、管理员/可用管理员、历史/上控、营销目标、进群/拉群候选、建群结果、导入批次、导出和历史任务逐租户对账。
- 任务状态、重试、占用、批量 requested/success/failure、错误码和历史快照不变。
- 当前 v1 topic、outbox、DLT 和 Android JSONL 的存量 payload 均可由新后端消费或解析，不要求生产端迁移。

### 15.4 列表响应

- 同一固定数据水位下，旧/new `/api/group-links` 的 total、静态字段、行顺序、筛选命中和 null fallback 完全相等；管理员/可用账号差异只接受已逐成员证明的新模型更新事实，不接受迁移缺行或关联断裂。
- count 与 page 使用同一谓词；最后一页无重复/漏行。
- 若只变 key，除 key 外所有值和行为仍相等，且前端只把 key 当 opaque 值。

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
- 默认页先从 legacy handle 确定与现网相同的行 ID，再批查 G/P/I/folder；复杂 enrichment 只处理 page IDs。
- test1 默认列表 P95 目标小于 200 毫秒，count P95 目标小于 100 毫秒。
- 启用管理员 / 可用账号等高级筛选时必须使用 EXISTS 和组合索引；EXPLAIN 不允许先对全租户 participant 做 GROUP BY。
- 国家/大洲筛选继续复现当前算法，并在约 45 万 M 行克隆数据上证明可 SQL 下推；不得 page 后 Java 过滤。
- 400 群的单账号完整快照，从进入 Reducer 到该事务提交，MySQL 可见 SQL execute / 往返总数必须 `<=10`；0/1/400 群都要计数，且 SQL 数不得随群数 N 线性增长。这 10 条包含 account+S 锁定、existing/missing 批量读、G ID 解析、G/P/M/B 批写、缺失 CAS、S header/baseline 和 durable effect intent；普通账号 SUMMARY 快照没有 invite 事实时不应写 I。

不增加第七张当前事实表的一个可实现预算如下；其中第 10 条是已明确披露的非权威过程 outbox。口径是单次正常 attempt，包含 Reducer 内所有递归 Mapper 和 snapshot effect outbox，不计 BEGIN/COMMIT 与提交后 worker；实施可以减少或用等价 SQL，但不得超过总数：

| # | SQL 批次 | 硬约束 |
|---|---|---|
| 1 | 同一条 locking read 锁 account + S | 固定 account→S 的 access order 和唯一索引；S 由账号创建/迁移时预建，快照路径不对缺失 S 做 UPDATE-miss/INSERT |
| 2 | 普通一致性 bulk read | 同一条不带 FOR UPDATE 的 SELECT/CTE 返回两支：输入 JID LEFT JOIN G/P/self-M/current-B 得 existing/missing+版本；该 account 的全部 B→M 与输入 anti-join 得 missing participantId。不得只查输入 JID，否则永远看不到 missing |
| 3 | G multi-row IODKU | 按 `(tenant_id,group_jid)` 完整排序，missing 不先 UPDATE |
| 4 | G ID current / locking bulk read | 按自然唯一键排序，解析并发 winner；后续全部使用这批 groupId |
| 5 | P multi-row IODKU | 按 groupId 排序，字段 version CASE 在 SQL 内完成 |
| 6 | self M multi-row IODKU | 该快路径只使用 account 已确认的 self PN，按 `(tenant_id,group_id,pn_jid)` 排序；不在账号快照中混写 PN/LID 或做 alias merge。alias 冲突拒绝/隔离到专用 identity reducer，不逐群查询 |
| 7 | missing M 主键定点 CAS UPDATE | 用第 2 条得到的 participantId 升序列表做单表 `UPDATE M ... WHERE tenant_id=? AND id IN (...) AND version_key<? ORDER BY id`，UPDATE 内重验版本；禁止 UPDATE JOIN B、范围扫 B 或无序 multi-table update |
| 8 | B `INSERT ... SELECT M ... IODKU` | SELECT 部分就是第 7.4 节要求的 M current/locking bulk read：以输入 groupId + confirmed self PN 对 M 完整唯一键做 exact lookup，固定/验证访问计划，只命中第 6 条已持锁的 present-M 行；先解析/锁定全部 participantId，再按 B `(tenant,account,groupId)` 排序写，禁止流式 M1→B1→M2→B2 或先碰 B 后再取新 M 锁；不另加 M/ID 查询 |
| 9 | S header/baseline UPDATE | S 已由第 1 条持锁；不是 B 后新取 S 锁 |
| 10 | `group_snapshot_effect_outbox` 批写 | metadata/营销只写稳定 intent，worker 提交后扇出；无 effect 时跳过 |

返回快照用第 4 条解析的 groupId 与本次内存输入组装，不再做逐群 `selectActiveById`。若实现无法同时证明这个预算和第 7.4 节的 RR 锁规则，则设计尚未通过；不得为凑 `<=10` 放弃防死锁约束。

- 所有群行必须用 multi-row / set-based SQL，业务循环只能组装参数，不得在循环内调 Mapper / Service。禁止逐群 registry、classification、profile、health/binding 写入和 `selectActiveById`；ID 与返回快照用一次批量查询解决。
- 验收以 datasource execute 计数和 MySQL 服务端 statement/往返观测双口径为准。仅把 N 次 `addBatch` 包在一次 Java `executeBatch` 里不算通过；必须证明 driver 重写为有界 multi-values SQL，或服务端实际执行的 statement 也不超过 10。超过上限时不得以“已禁 N+1”豁免，必须在设计评审重新给出数字和原因。
- 账号快照事务只能用一次 set-based 写入 `group_snapshot_effect_outbox`，由提交后 worker 再展开为发送尝试。现有 `MarketingNewGroupImmediateSendServiceImpl.claimImmediateAttempts` 的逐 candidate `insertSendAttempt` 不得在 Reducer/快照事务内复用，否则 SQL<=10 和事实锁序同时失效。
- 单群完整成员快照同样使用同一事务内的有界 multi-row / set-based SQL，禁止一个超大 IN、逐行 N+1 或分段 commit；其 SQL 预算在实施计划中按成员上限单独定数，不把本节的账号 400 群门禁套用为伪数字。
- MySQL 克隆回填必须量化 M 的 compact keys、兼容查询索引后的 data/index bytes、redo/undo/binlog 增量、buffer-pool 命中与复制延迟；预留不足禁止在 test1/生产执行。
- 在线 backfill 逐批记录 rows/s、锁等待、deadlock 和 replica lag，达到阈值自动暂停；shadow read 采样有熔断，不能把旧慢 SQL流量翻倍。
- 线上门禁同时观察 DB CPU、锁等待、死锁、Kafka lag 和 reducer reject 指标，不能只看接口耗时。

## 17. 测试策略

### 17.1 后端

Service/Mapper 快速测试使用 test scope H2 + 真实 Mapper XML、租户插件和事务；MySQL 8.4.8 Testcontainers 验证 JSON/CHECK/ascii_bin/generated column/索引/锁，test1 做最终验收。

本期必须覆盖：

- 六表 DDL、约束、索引、租户隔离和逻辑关联；
- 每张旧事实表的迁移 fixture、冲突报告、可重入 backfill 和回滚；
- joined_at 不迁 first-post、迁移营销副作用为 0；
- 当前 Web/Android v1 JSON → Java Adapter → 六表的逐字段兼容，包括 Web null complete 推断；
- 现有列表 DTO/筛选/排序/分页/状态、详情和全部动作的 golden/shadow parity；
- 同 JID 多 alias、I-only、folder/remark/source/deleted 差异和 handle 解析；
- 账号群数、执行账号、历史/上控、营销、进群、拉群、建群、导入、metadata、导出和历史任务回归；
- MySQL RR supremum 死锁复现与新 Mapper 的普通读分类、固定表序/键序、M-before-B；
- 0/1/400 群完整账号快照 SQL 计数，400 群全存量/全新增/混合均 `<=10`，不能用客户端 batch 隐藏服务端 N 次 statement；
- 迁移、shadow、切换、回滚和旧表访问为 0 的门禁。

不新增 typed/capability/V2/generation/spool/country 新算法测试；这些不是本期功能。现有 group、account、marketing、task、normal creation、historical、export 测试都属于回归范围，不能只跑 group 包。

### 17.2 Web/Baileys 协议回归

`armada-protocol` 本期零代码改动。运行其现有测试作为兼容基线，并在 `armada` 使用当前真实 v1 JSON fixture 验证反序列化与 Adapter 映射；不新增 V2 schema/topic、字段、fixture 生成链或 producer 逻辑。

### 17.3 Android Zhuan 回归

Android 本期零代码改动。运行现有 go test/go vet/go build（以仓库当前门禁为准），并用现有 `snapshotComplete/skippedGroupCount`、WGP2 join/depart 和 HistorySync 事件做后端黑盒回归；不新增 binding token、spool、ack 或共享 V2 fixture。

### 17.4 前端合同回归

`wheel-saas-pure-web` 只实施已确认的行展开区删除；模型切换默认不再改前端。验证：

- `/api/group-links` 列、筛选、排序、分页、状态、按钮和动作不变；
- 删除、分组、详情、成员、进群、批刷、任务轮询和营销 selection 继续使用当前合同；
- 展开区保持已移除；其他 DTO 字段仍兼容；
- 历史任务、I-only、重复 legacy 行、空值和错误提示不变；
- 若最终只能改变 key，仅验证不透明 row-key/request-key 映射，不增加资源类型判断。

### 17.5 test1 验收

真实环境写入和部署前必须再次确认目标为 test1。顺序为：

1. 本地全量单测和 SQL 结构测试；
2. 部署脚本自身测试；
3. test1 additive migration；
4. 只读 backfill dry-run 报告；
5. 小租户 / 小批次写入；
6. Web 与 Android 各选账号验证完整、partial、空快照，并核对当前实际 topic/payload、消费位点与新旧落库结果；
7. 列表、详情、进群、拉群、营销、历史群、四类建群入口、invite batch-check、导出冒烟；
8. 指标观察和回滚演练。

不得直接在 test1 全租户跑无 dry-run 的批量迁移。

## 18. 本次全仓审计证据与实施闭包门禁

本设计不是只从群组列表页面反推表结构。审计范围覆盖四个仓库、Flyway 全量 migration tree、后端直接读写、前端契约、两套协议生产端和 test1 只读数据；下表只列与群模型直接相关、混合相关或为切换提供基础设施的版本，确认无关的 migration 不硬塞进 owner 清单。以下路径按第 18.8 节 allowlist 分别建立后端改造任务、前端已确认小改或协议只读回归证据，不能把“被审计”误写成“必须改代码”。

### 18.1 Armada 后端

已按表名、列名、Mapper namespace、groupLinkId/groupJid/baseline/member state 扫描 Java、Mapper XML、Flyway、测试和配置，实施 commit 必须重新生成清单。重点 owner：

- 列表/CRUD/详情：`GroupLinkController`、`GroupLinkServiceImpl`、`GroupLinkMapper.xml`、preview/health Mapper、`GroupDetailServiceImpl`；
- 账号群/baseline：`AccountGroupMembershipReportServiceImpl`、`AccountGroupMembershipSnapshotServiceImpl`、`AccountGroupMembershipMapper.xml`、sync job/command、classification service/job；
- 资料/成员/邀请：metadata snapshot/persistence、participant observation、member snapshot/cache/state/join/depart、invite/health service；
- 账号与执行选择：`AccountMapper.xml`、account service/state、`GroupExecutionAccountSelector`；
- 任务全链：join、pull、marketing、historical、normal creation、两类 creation marketing、batch/metadata task、import/label；
- 可靠性与配置：protocol command outbox、现有 Kafka consumers/DLT、Flyway、部署与 deep-check 脚本。

实施时每个当前 reader/writer 只能标为 `migrated`、`compat-handle` 或 `history-only`；unknown 阻断切换。模型迁移代码只在后端；整体范围另含已确认的前端展开区删除，以及确有必要时的不透明 key 映射。

### 18.2 Web 前端（已确认小改与影响证据）

已核对 `src/api/group.ts` 以及群列表、详情、成员、导入、历史、进群、拉群、营销和建群页面。当前所有列表动作把 `row.id` 当同一个 opaque groupLinkId，表格 row-key 也是 `id`；按钮没有资源类型分支。前端已按用户要求仅删除行展开详情；除此之外由后端保持 DTO 与数值 ID。若 ID 确实无法保持，只允许集中替换 row-key/request-key。

### 18.3 Web/Baileys 协议层（只读影响证据）

已核对当前 OpenAPI、Baileys patch、account manager、worker consumer、publisher、group routes 和部署配置。Web 当前发布的账号群报告没有 complete/skipped/queryStartedAt/binding generation，后端已有 null 完整性兼容判断；本期 Adapter 必须保持它。协议缺口只进入第 11.3 节风险，不产生协议仓任务。

### 18.4 Android Zhuan（只读影响证据）

已核对 Android event builder、groups fetcher、WGP2/HistorySync join/depart、snapshot coordinator、command context、失败 JSONL 和部署配置。现有快照已经携带 `snapshotComplete/skippedGroupCount`，成员事件已有 joinedAt/exitedAt/sourceEventId；足以由后端 Adapter 按现有语义落六表。缺少 queryStartedAt/binding generation/fieldMask 不要求 Android 补齐。

### 18.5 test1 只读基线

- MySQL 8.4.8，Flyway 当前到 V116；本轮未执行 DDL、DML、部署或协议命令。
- 约 1.1 万 group_link、4.7～5.1 万账号群关系、43.8～45 万成员当前数据；这是活动系统区间快照。
- 默认列表 count/page 约 1.38 秒/1.23 秒；去掉无条件全量成员聚合的基础查询约 32 毫秒，简单 count 约 5 毫秒。
- 正式写入仍需再次确认目标 test1，并按 dry-run→小租户→全量对账顺序执行。

### 18.6 防漏改交付门禁

1. 目标 commit 重新生成旧事实表 reader/writer manifest，新增/unknown 项阻断。
2. 代码扫描限制六表写入口；旧事实 writer 切换后运行访问为 0。
3. 每个业务域有当前契约回归、shadow diff、数据对账和回滚演练。
4. 后端使用当前真实 v1 payload fixtures；不要求 Web/Android 生成新 fixture。
5. drop 前再次扫描旧表、列、groupLinkId、线上 SQL digest 和 legacy handle 依赖。

### 18.7 当前 reader/writer manifest（设计冻结版）

| 旧事实簇 | 当前主要 owner | 目标 | 门禁 |
|---|---|---|---|
| group_link/preview/health | list/detail/import/registry/classification/invite/health | G/P/I + handle + task probe | 当前 API parity；旧事实写为 0 |
| membership/baseline/account baseline 字段 | report/snapshot/status/sync/classification | B/S/M | joined_at 安全；群数/营销 parity |
| member snapshot/cache/state/join/depart | metadata/participant services 与 event sinks | P/M | 详情/导出/成员动作 parity |
| metadata/batch/import/label | 原 task/import services | 过程表保留，当前事实引用六表 | 状态、租约、来源、批量结果不变 |
| join/pull/marketing/history/creation families | 原 workers/result services | 历史快照保留，当前 enrichment 六表 | 活跃/历史引用均可打开 |
| protocol outbox/Kafka DLT/current v1 topics/Android JSONL | 当前 dispatcher/consumer/producer | 设施保留，后端 Adapter 兼容 | 不新增 topic/payload；存量可解析 |

Mapper/XML、注解 SQL、JdbcTemplate、Flyway、测试 fixture 和脚本 SQL 全部纳入扫描。

### 18.8 项目改动与回归 allowlist

| 项目 | 代码改动 | 本期验证 |
|---|---|---|
| `armada` | 允许：六表、Flyway、迁移 runner、Mapper/Service/Adapter、现有 API 兼容查询、后端测试 | 单测、MySQL 8.4 并发/DDL、SQL 数、旧新结果 shadow diff、test1 验收 |
| `wheel-saas-pure-web` | 已确认：删除行展开详情；可选：ID 无法保持时仅做 key 映射 | 展开区不存在；其余列表和业务 E2E/RBAC smoke 原样通过 |
| `armada-protocol` | 无 | 现有测试与 test1 事件黑盒兼容 |
| Android Zhuan | 无 | 现有测试与 test1 事件黑盒兼容 |

不得新增 V2 schema/topic、producer 字段、binding/effect token、共享 fixture 生成链、spool、ack ledger 或协议部署步骤。`armada-deploy` 只在后端新增表、feature flag、指标或测试脚本确有需要时更新，不配置新的协议 topic。

## 19. 已确认口径与剩余技术门禁

用户已确认：删除指定的行展开详情，但主列表和全部业务逻辑不变；前端除此之外最多做 key 适配；Web/Baileys 和 Android 不改协议。本文不再保留新 UI 流程、新 API、行折叠、国家算法或删除语义等业务待决策。

实施前只剩以下技术门禁：

1. 用 test1 只读报告量化同一 JID 多个 active legacy 行，以及它们在 folder/remark/source/deleted 等 alias 级属性上的差异。
2. 若存在需要长期保真的多 alias，保留最小 public-handle/legacy-alias 设施；如果它包含可变 alias 业务属性，就不能谎称是纯 ID map，也不能删除旧承载，直到另有产品决策。
3. 评审六表字段、索引、迁移规则、RR 锁序和 400 群 SQL `<=10` 是否可实施。
4. 确认 test1 的暂停窗口、回滚演练和对账周期；生产删除旧表仍需单独确认。
5. 旧 `joined_at` 迁移后 first-post 必须全空，迁移不得生成即时营销，这是数据安全门禁，不是业务改版。
