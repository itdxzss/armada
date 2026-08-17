# 变更记录：群组数据模型重建（方案 A）

- 日期 / 工作目录：2026-08-15 / `/Users/daishuaishuai/IdeaProjects/armada`
- 分支：`armada` 与 `wheel-saas-pure-web` 已从各自 `1.0.3-snapshot` 创建并推送 `1.0.3-group`，均已跟踪各自 `origin/1.0.3-group`
- 需求来源：以六张权威表重建群组当前事实模型，完整排查群组依赖；仅删除已指定的列表展开详情，不改变主列表、业务逻辑或协议合同
- 设计文档：`docs/superpowers/specs/2026-08-15-group-data-model-rebuild-design.md`
- 状态：V120～V124、人工回填、增量补齐、baseline/first-post 硬门禁、test1 全部筛选/排序/分页对账、群列表及相关业务读取切换均已进入 `1.0.3-group` 并部署 test1，未部署生产。2026-08-17 剩余成员详情/导出、账号列表群数及新增群资格切读已随提交 `a3b28b9c` 推送并部署 test1；同版本构建物的 `LEGACY_MEMBER_SNAPSHOTS` 已补跑完成并通过数据对账。本地未提交代码已停止八张旧事实表的在线读写，保留 `group_link` 的旧数值 ID/87 条未解析邀请兼容，以及 `group_link_preview` 的创建者手机号、国家、洲兼容；没有删除旧表。test1 十张旧表/兼容表备份和隔离恢复演练已通过，待部署本轮代码后完成运行时零访问观察门禁

## 目标

在 `armada` 后端用六张职责单一的权威表替换群组当前事实表簇，消除多表同字段和列表全量聚合，同时保持现有 API、列表和全部群组业务结果不变。

## 已确认范围

| 项目 | 本期定位 | 代码改动 |
|---|---|---|
| `armada` | 唯一主要实施项目 | 六表、Flyway、迁移、Mapper/Service、当前事件 Adapter、现有 API 兼容查询和后端测试 |
| `wheel-saas-pure-web` | 一个已确认的小 UI 改动 + 回归验证 | 删除行展开详情；只有后端无法保持 ID 时再允许 row-key/request-key 映射 |
| `armada-protocol` | 只读审计与黑盒回归 | 无 |
| Android Zhuan | 只读审计与黑盒回归 | 无 |

除已确认删除的行展开详情外，前端主列表列、筛选、状态、按钮、批量规则、页面流程、请求/响应 DTO 和业务动作不变。Web/Baileys 与 Android 继续使用当前 topic、payload、事件名和重试逻辑。

不过度设计是本变更红线：每个新增表/字段/状态/抽象必须有当前 reader/writer、已证实问题和可执行验收；不为未来场景预建协议、API、框架或扩展点。Agent 只能按文件所有权完成明确任务，范围外问题只报告。

## 缺口拆解 / 任务清单

- [x] 核对现有群组表、字段、Mapper、Service、Controller、定时任务和 Flyway
- [x] 核对列表、详情、导入、历史群、任务、营销、拉群、建群、导出和账号群数量
- [x] 核对 Web/Baileys 与 Android 当前事件合同和后端 Consumer/Adapter
- [x] 核对 test1 数据规模、列表 SQL 基线和部署/深检入口
- [x] 固定六张权威表的职责、字段、约束和索引
- [x] 纠正 legacy `joined_at` 迁移规则
- [x] 固化 InnoDB RR 普通读/排序写死锁约束
- [x] 固化 400 群账号快照 SQL `<=10` 门禁
- [x] 将前端 typed/capability、新 API、V2 topic/schema、binding token、Android spool 等移出本期
- [x] 将当前 Web null-complete 兼容判断和 Android 现有完整性字段固定为本期 Adapter 口径
- [x] 新增仅包含六张表的 V120 最小建表迁移；不含旧表 DML/ALTER、回填、outbox 或协议设施
- [x] 在真实 MySQL 8.4.8 上验证 V120 可执行、可重入和关键 CHECK/排序规则
- [x] 新增未接事件入口的账号群快照五表批量持久化底座；不写 invite、旧表或副作用表
- [x] 真实 MySQL 8.4.8 验证 400 群 `<=10`、完整空快照、重放和 M-before-B
- [x] 复用现有账号群报告事件的租户校验、过期句柄过滤、完整性判断和事务，同步写入新表；旧表结果继续驱动营销
- [x] 复用用户手动历史群刷新的同一完整快照同步写入新表；新模型影子写失败不改变旧表、邀请刷新或原有成功口径
- [x] 复用现有账号自身进退群事件的校验、事实时间和来源优先级，同步写入 `wa_group`、`wa_group_participant`、`wa_account_group_binding`；`remove` 保持“不在群/退出原因未知”
- [x] 复用现有普通成员进群、退群事实服务同步写入 `wa_group_participant`；支持 PN/LID，保持现有事实时间、退出类型和乱序优先级，不创建账号群关系
- [x] 复用现有两个完整成员快照入口同步更新 `wa_group_participant` 和 `wa_group_profile` 成员快照头；落后快照不写成员，明确进退群事实优先，不创建账号群关系
- [x] 复用现有完整群详情入口同步更新 `wa_group_profile` 的群名、描述、成员数、建群时间和设置；保留字段空值语义并拒绝过期资料
- [x] 复用现有当前邀请码中央 Service 同步更新 `wa_group_invite` 和 `wa_group_profile.current_invite_id`；支持先有邀请码后绑定群 JID，保留轮换水位和封禁保护
- [x] 复用现有健康回报中央 Service 将已计算的状态、封禁、人数、检测时间、错误和失败次数同步到当前 `wa_group_invite`；不新增邀请码、不更新同群旧邀请码、不改变检测或重试规则
- [x] 复用现有邀请 Service 同步导入校验已接受的 `label_id`、邀请码、公开群名和头像；失败链接仍不创建主数据
- [x] 复用现有完整 metadata 写入 SQL，同步协议已确认的群名和四项现有权限；不增加协议回读、重试或新权限字段
- [x] 复用现有本地资料、URL 群头像、详情群名/头像、metadata 列表镜像、导入链接分组和群组分组入口同步 `wa_group` / `wa_group_invite`；使用批量 SQL，不增加锁，未解析邀请不虚构 `folder_id`
- [x] 旧群链接和链接分组删除只在真实群最后一条有效 alias 消失时同步软删 `wa_group`，运营分组删除同步清空 `wa_group.folder_id`；普通 UI 删除不占用仅供系统退役的 `wa_group_invite.deleted_at`，重新观察、导入或编辑时恢复新表软删除态
- [x] 用 test1 只读报告量化同 JID 多 active legacy 行及 alias 级属性冲突：当前均为 0
- [x] 新增只读回填门禁脚本，拆分旧模型孤儿引用与 `wa_group` 回填后的 binding 目标未解析；实时双写 first-post 只统计现状，baseline=1 + first-post 非空继续作为硬门禁
- [x] 新增通过启动参数 `--armada.group-model-backfill.run-once=true` 人工触发的 `wa_group` 一次性回填入口；无 `@Scheduled`，回填前后各执行一次只读冲突门禁，每批独立事务，不使用 `FOR UPDATE` 或逐群调用；普通阶段执行 50000 行集合写，旧成员快照按源表主键游标每 5000 行推进，避免重复全表扫描和大事务压满 test1；支持用 `--armada.group-model-backfill.start-stage=<stage>` 从已提交阶段续跑，用源/目标水位阻止较旧回填覆盖较新的实时双写
- [x] 在同一人工入口顺序增加 `wa_group_profile`、`wa_group_invite` 和当前邀请码指针的 50000 行集合回填；保持群资料/本地展示/公开预览/健康字段边界、秒转毫秒、NULL 未知语义和实时水位，不映射普通 UI 删除为邀请系统退役
- [x] 在同一人工入口增加 `wa_group_participant`、`wa_account_group_binding`、`account_group_sync_state` 的保守集合回填；旧 `joined_at` 只进 `membership_active_since_at`，baseline 只迁 1/NULL，回填 SQL 不写 first-post，成员当前态与最近进退群事实按各自水位写入
- [x] 将旧列表实际读取的 `whatsapp_group_member_snapshot` 按主键游标集合回填到 `wa_group_participant`；每个主键区间只扫描一次，直接依赖唯一键 upsert 及 snapshot 水位写 presence/role，不再为筛选待写行额外关联两次目标成员表；后续更可靠的 state/join/exit 阶段仍可覆盖
- [x] 补齐旧预览群主号码/国家到 `wa_group_participant` 的保守回填；只写 role=群主及其观察水位，不虚构在群态
- [x] 新增未接业务入口的群列表影子 Mapper；复用现有 `GroupLinkQuery` / `GroupLinkVoRow`，count 不聚合成员，page 先分页 legacy 行句柄、再仅对本页从六表补齐当前事实，显式租户连接且不加锁
- [x] 在本地临时 MySQL 8 执行六表回填、幂等和门禁用例
- [x] 在本地临时 MySQL 8 代表性固定数据上对比旧/新列表的总数、行集合、字段、排序、分页和组合筛选
- [x] 在 test1 固定水位数据上对比旧/新列表的总数、行集合、字段、排序、分页和全部筛选
- [x] 用户已评审六表字段和迁移/切换方案
- [x] test1 写入、部署和列表切读均已取得用户确认
- [x] 账号列表群数改读 `wa_account_group_binding + self participant`，继续保持旧 `membership_status IN (1,2)` 的可发送口径
- [x] 账号群完整快照的新增群资格改由新成员/关系当前事实决定；迁移期旧快照曾保留双写，本轮退役代码已停止该写入
- [x] 群详情成员、营销导出成员当前态及最近进退群事实改读六表；详情仍按 profile 快照版本读取最后一次完整快照，已确认踢人/角色动作继续即时反映
- [x] 复用现有 `LEGACY_MEMBER_SNAPSHOTS` 人工阶段补迁旧详情快照头和成员版本；未增加表、字段、定时器或新阶段
- [x] 停止八张旧事实表的在线读取和双写；保留旧表实体及人工回填 Mapper，不执行 `DROP`、`TRUNCATE` 或数据删除
- [x] 保留 `group_link` 作为现有 `groupLinkId`、alias 和 87 条未解析邀请的兼容句柄，不改变 API ID
- [x] 将 `group_link_preview` 在线兼容写收窄到创建者手机号、国家和洲；其他群资料、邀请、成员、健康事实只写六张新表
- [x] 新增在线 Mapper 静态门禁，禁止八张旧事实表重新进入运行时 SQL，并限制 preview 兼容字段范围
- [x] 完成 test1 十张旧表/兼容表备份、校验和核验及隔离 MySQL 全量恢复演练；源/恢复逐表行数一致
- [ ] 部署本轮未提交代码后，观察正常流量并确认八张旧事实表运行时读写为 0
- [ ] 旧表物理删除另立阶段，必须再次确认；本轮明确不删除

## 关键设计决策

### 六张权威表

- `wa_group`：真实群身份和 Armada 本地群属性
- `wa_group_profile`：WhatsApp 当前资料、设置、群状态和快照头
- `wa_group_invite`：邀请 code、状态和未解析预览
- `wa_group_participant`：成员当前 presence、role、PN/LID 和最近进退群事实
- `wa_account_group_binding`：账号与群/成员关系及 baseline 上下文
- `account_group_sync_state`：账号群报告、baseline 和同步水位

六张是“当前事实权威表”，不是项目全部群相关物理表。folder、label、导入 batch/detail、metadata/batch task、进群/拉群/营销/建群/历史任务和 protocol outbox 继续保留。

### 一个事实只有一个主值

旧事实表切换后不能与六表永久双写。迁移 runner、快照 effect outbox 和 legacy handle 都是非权威设施，不得保存并反向覆盖群资料、当前邀请、成员角色或账号群关系。

### 现有业务合同不变

- `/api/group-links`、DTO、权限、字段名、时间单位和错误行为保持。
- 列表行集合、排序、分页、筛选、状态和动作逐项与旧查询相等。
- `groupLinkId` 继续作为 opaque 外部标识；默认数值 ID 不变。
- 现有任务状态、重试、占用、营销资格、历史快照、导出和建群流程不变。
- 新 API、typed resource、capability、duplicate collapse 和产品语义修正不在本期。

### 当前协议由后端适配

- Web 当前没有显式 complete/skipped/queryStartedAt/generation；后端继续保留 `snapshotComplete=null + skippedGroupCount=null/0` 视为完整的现行判断。
- Android 已有 `snapshotComplete/skippedGroupCount`，并已有 WGP2/HistorySync join/depart 时间和 sourceEventId。
- 后端 Java Adapter 把当前 v1 payload 映射成内部六表命令；不要求生产端补字段。
- Web 普通 participant add/remove 当前被 Java 忽略，本期仍不新增消费。
- V2 topic/schema、稳定 eventId、端到端 generation、fieldMask、spool/ack 和 durable create-result 全部另立需求。

### legacy ID 与 alias

同一 JID 可能对应多个 legacy row。用户已确认不允许折叠行或改变 alias 级业务，因此默认逐条保留 `id`、来源、folder、remark、deleted 和动作作用域。

若 test1 证明这些 alias 具有不同可变业务属性，就必须长期保留 `group_legacy_handle` 或旧表最小形态；它不是 WhatsApp 当前事实主表，但物理表总数会多于六张。不得为了“最终只有六张物理表”改变业务。

### 迁移安全

- 旧 `account_group_membership.joined_at` 是快照建行/回群混合时间，不能迁成 first-post。
- 本期所有 legacy migration 的 `first_post_control_observed_at` 必须为 NULL。
- 迁移 origin 不得生成即时营销 intent、task、send attempt 或 protocol command。
- 账号快照继承现有 MySQL RR 规则：先普通一致性读区分 existing/missing，按表和唯一键排序写，禁止 UPDATE 缺失键；全部 M 写入早于 B。
- 400 群完整账号快照的 MySQL statement/往返数 `<=10`，全部批量，禁止逐群 Service/Mapper 调用。

## 影响

### 数据库

当前代码新增 V120 六表建表迁移、V121 profile 健康字段和 V122 独立的健康检测人数；资料人数与健康检测人数不再共用一个字段。本地本轮退役代码已把账号群关系、baseline、成员快照/缓存/进退群事实、健康及群资料等在线写入口收敛到六张当前事实表，不再双写八张旧事实表。`group_link` 继续保留外部数值 ID、alias 和未解析邀请；`group_link_preview` 只兼容创建者手机号、国家、洲。人工回填入口没有定时器，只能显式使用 `--armada.group-model-backfill.run-once=true` 启动。旧表结构和现存数据完整保留；物理删除必须另立阶段并再次取得用户确认。

### API / 前端

现有 API 和前端业务无变化；后端群组列表读取切到当前模型，继续复用现有请求、返回、转换、排序和分页合同。前端只删除已指定的行展开详情。若只能改变 key，追加改动必须集中在 row-key/request-key，不得解析 G/I 类型或新增业务判断。

### Kafka / 协议

不新增 topic、consumer group、payload 字段、OpenAPI schema 或协议部署步骤。现有 v1 topic/outbox/DLT/Android JSONL 必须继续能由新后端解析。

## 验证记录

本次新增 V120 六表最小建表迁移和迁移合同测试；V120 已在 test1 执行并验证成功，人工回填只写六张新表，尚未切换列表读取，也未改动协议环境。

本轮前端验证：

- `pnpm exec node --import tsx --test src/views/group/list/components/GroupListTable.test.ts`：4 个测试通过，包含展开行不存在门禁。
- `pnpm typecheck`：通过。

本轮六表回填验证：

- test1 V121 修复回填：`LEGACY_MEMBER_SNAPSHOTS` 按主键游标完成 94 个 5000 行批次，`affectedRows=486462`；随后 `PARTICIPANTS=126`、`ACCOUNT_PARTICIPANTS=1123`、`PARTICIPANT_JOIN_FACTS=42`、`PARTICIPANT_EXIT_FACTS=26`、`ACCOUNT_GROUP_BINDINGS=982`、`ACCOUNT_GROUP_SYNC_STATES=13`，runner 共 `batches=100`、`affectedRows=488774`，最终来源/冲突门禁通过并写出结束标志。
- test1 回填后精简门禁：`wa_group=11285`、`wa_group_profile=11285`、`wa_group_invite=1523`、`wa_group_participant=526887`、`wa_account_group_binding=52490`、`account_group_sync_state=391`；`was_in_initial_baseline=1 AND first_post_control_observed_at IS NOT NULL` 为 0，Flyway V121 成功记录为 1。
- test1 V122 已成功执行并有 1 条 Flyway 成功记录；`GROUPS -> PROFILES` 定向回填只更新 profile 8,334 行。核心字段快速对账覆盖 11,391 条有效旧列表行：群 JID、健康、封禁、最终成员数、分组、邀请、备注、头像和建群时间差异均为 0；87 条仍是旧表也无法解析 JID 的邀请，初次核对另有 3 条本地名称及 1 条不可见 Unicode subject 归一化差异。
- 群主定向回填 `affectedRows=96`，但实时流继续更新成员角色后，全量列表仍有群主 97 条不一致（旧有/新缺 96，新有/旧缺 1），因此没有伪报群主 parity 通过。进一步定位到用户手动历史群刷新漏掉新模型双写，已补同一快照的影子写和失败隔离测试；test1 对此前 18 个受影响群链接事务性补齐 20 个账号成员及关系，随后的定点查询中相关 21 个列表行全部匹配。
- 一次定点分类中的剩余 52 个“旧列表可用管理员、新模型不可用”不是漏迁：共 77 条新关系都已有 `presence_status=不在群`、来源均为 `SNAPSHOT_ABSENT`，事实时间全部不早于旧 membership，最小还新 441ms；保持新模型当前事实，不用旧表陈旧状态反向覆盖。实时事件继续进入后，下一次全量分桶统计该值变为 53，说明最终验收必须使用固定水位。
- 管理字段对账已改成只覆盖实际有数据的 24 个 ID 小分桶、最多 2 路并发，全部 11,391 行在查询门限内完成：管理员文本差异 21（旧有/新缺为 0）、群主差异 97（旧有/新缺 96）、可用管理员差异 53（全部为旧有/新缺）。该结果已足够阻止切读；不再使用会超时的全表聚合或 4 路并发查询。
- 随后短暂停止 test1 后端取得固定数据库水位，并用相同 24 个小分桶复核全部 11,391 行：管理员文本差异仍为 21，但逐成员差集只有“新有/旧无”，没有任何旧管理员成员在新表丢失；来源为 `GROUP_SNAPSHOT`、`ROLE_EVENT` 或既有 promote 事实。群主差异仍为 97，其中旧有/新缺 96 条对应的参与者身份行全部存在，均由观察时间不早于旧 metadata 的 `GROUP_SNAPSHOT` 写成非群主；另 1 条是新模型有、旧表无。不能用旧值反向覆盖更新事实。
- 同一固定水位下，可用管理员差异为 50 条：49 条关系的参与者当前为不在群，1 条当前不再是管理员；缺群、缺 binding、缺 participant 均为 0。此前定向补齐涉及的 21 条列表行已全部匹配。核心字段仍为：11,391 行、87 条旧邀请无法解析群 JID、初始名称差异 3、subject 差异 1，群 JID/健康/封禁/最终成员数/分组/邀请/备注/头像/建群时间差异均为 0。核对后已立即恢复后端，容器持续消费事件且容器内接口探针成功，临时回填容器为 0。
- test1 性能核对发现影子 Mapper 的默认 count/page-id 仍无条件连接资料和邀请表，原始形态在 5 秒门限内超时。已改成只按实际筛选动态连接所需表，并针对 `wa_group.group_jid` 的 ASCII 唯一索引显式转换右值字符集；分页 enrichment 按 `page_groups -> participant/binding` 固定连接顺序，避免 MySQL 错选为扫描 52 万 participant。对应 test1 只读 SQL 的默认 count 为 2.6～5.5ms，完整 20 行分页 `EXPLAIN ANALYZE` 实际约 7.8ms（含 EXPLAIN 的 profile 约 16ms），由优化前约 517ms 降到目标内。可用管理员 count 约 440ms、手机号关键字 count 约 1.97s；二者均为显式高级筛选且未做全租户 GROUP BY，后续若要求高级筛选也达到默认 P95，需要单独优化，不能借本期新增搜索投影表。
- 两条早期错误双写把 `group_jid` 写进 `wa_group.display_name`，已在 test1 按旧值为空、当前值等于 JID 的严格条件定点清空 2 行；复核后名称差异由 3 降为 1。剩余 1 条与 subject 差异是同一条仅含不可见 Unicode 组合符的旧 subject，经 MySQL `TRIM/NULLIF` 归一化为空，不做特殊字符例外设计。
- 最新安全门禁：`was_in_initial_baseline=1 AND first_post_control_observed_at IS NOT NULL=0`，合法 post-control 关系 816，binding 到 participant 的断链数 0。后端重启消化 Kafka 积压时旧 `AccountGroupMembershipMapper` 逐群更新曾出现锁等待，积压后最近 60 秒锁等待为 0 且群快照继续成功回写；错误点不在六表 Mapper。
- test1 固定只读水位 `connection=446234` 的列表功能对账覆盖 11,392 行：label、folder、无分组、历史/上控分类、成员数、国家/洲、群龄、来源文件、origin、membership state、健康状态、链接/JID 关键字等静态筛选的新旧数量与 ID 集合全部一致；集合一致的全部场景按现有唯一排序 `created_at DESC, id DESC` 逐位差异为 0。默认第 1/2/中间/最后一页及 1、20、100 三种页大小差异均为 0，20 条页大小最后一页 12 行、100 条页大小最后一页 92 行，新结果重复 ID 为 0。
- 创建者手机号、国家和洲最初被错误地从当前 OWNER participant 推导，导致国家筛选大面积偏差；影子 Mapper 已改回现有 v1 的 `group_link_preview.owner_phone/creator_country_*` 兼容口径。修复后 owner、country、continent 字段差异均为 0，国家 `IN` 为 2,817/2,817、洲 `ASIA` 为 2,924/2,924；本地真实 MySQL RED/GREEN 用例锁定“当前群主变化也不得改变 v1 创建者字段”。
- 管理员和可用账号继续使用现有业务谓词，但新模型保留更新的成员事实，不用旧表陈旧投影反向覆盖。上述固定水位中管理员关键字为旧 3,365、新 3,367（新模型多 2 条），可用管理员为旧 1,084、新 1,013（旧多 71 条）；排序位移只发生在这两个结果集合本身不同的筛选。进一步只读分类确认绝大部分是新模型已记录退群/降级，另有 3 条真实缺口，均为后端运行期间新增的 `WGP2_PROMOTE` 旧关系没有写入 `wa_account_group_binding`。
- `GroupParticipantObservationServiceImpl` 已补上述实时双写缺口，复用现有新模型持久化类写成员当前状态、角色和账号绑定；新增入口明确不写 `last_joined_at`、`membership_active_since_at`、baseline 或 first-post，避免把升管理员/成员查询时间误判为进群并触发营销。赢家事件的 `source_event_id` 由成员状态查询原样带入，迟到 promote 不会覆盖较新的 demote。代码尚未部署 test1，现存 3 条数据也未修改。
- `DOCKER_HOST=... mvn -q -Dtest=AccountGroupCurrentSnapshotPersistenceMySqlTest,GroupParticipantObservationServiceImplTest,WhatsappGroupMemberCacheMapperH2Test,WhatsappGroupMemberCacheMapperMysqlTest,GroupListCurrentMapperMySqlTest,GroupListCurrentMapperSqlShapeTest,GroupLinkControlledAdminMapperInMemoryTest test`：35 个受影响测试通过，0 失败、0 错误；包含真实 MySQL 角色观察建 binding 且不得生成进群/分类时间、事件号读取、列表兼容字段及分页筛选回归。
- `mvn -q -Dtest=AccountGroupMembershipReportServiceImplTest,HistoricalGroupAccountGroupRefreshServiceTest,AccountGroupMembershipStatusServiceImplTest,WhatsappGroupDepartedMemberServiceImplTest,WhatsappGroupMemberJoinFactServiceImplTest,WhatsappGroupMemberCacheServiceImplTest,GroupMetadataSnapshotPersistenceImplTest,GroupModelBackfillRunnerTest,GroupProfileHealthMigrationSqlTest,GroupListCurrentMapperSqlShapeTest,GroupModelBackfillMapperSqlShapeTest test`：通过。`xmllint` 校验 4 个群模型 Mapper XML、`git diff --check`：均通过。
- `DOCKER_HOST=unix://... mvn -q -Dtest=AccountGroupMembershipReportServiceImplTest,HistoricalGroupAccountGroupRefreshServiceTest,AccountGroupMembershipStatusServiceImplTest,WhatsappGroupDepartedMemberServiceImplTest,WhatsappGroupMemberJoinFactServiceImplTest,WhatsappGroupMemberCacheServiceImplTest,GroupMetadataSnapshotPersistenceImplTest,GroupModelBackfillRunnerTest,GroupProfileHealthMigrationSqlTest,GroupListCurrentMapperSqlShapeTest,GroupModelBackfillMapperSqlShapeTest,GroupLinkControlledAdminMapperInMemoryTest,GroupListCurrentMapperMySqlTest test`：通过；覆盖动态最小 JOIN、MySQL ASCII JID 唯一键查找、page-first enrichment 连接顺序、组合筛选和新旧固定数据等价。
- test1 页面字段对账没有伪报通过：已解析群健康/最终成员数全量查询设置 30 秒上限后被取消；最新 1000 个有效列表行的健康/成员数及管理员文本抽样查询设置 10 秒上限后也被取消。未继续扩大查询或压测，待 Kafka backlog 排空后补验。
- 回填结束恢复普通后端时，Kafka 积压快照并发更新旧 `group_link` 曾出现 4 次锁等待超时；不是新六表 SQL。随后连续观察窗口内新增锁超时为 0；最终 API 返回 401（未登录预期），`display_name ambiguous=0`、迁移/Schema 错误为 0、最近 120 秒锁超时/死锁为 0。
- test1 精简只读对账：群 JID 与 baseline/first-post 硬门禁通过；发现已解析群健康状态约 9768 条、管理员文本 554 条不一致。根因分别为已解析群健康错误依赖 `wa_group_invite`、旧列表完整成员快照未进入 participant 回填；原始 `member_size/current_count` 差异不作为门禁，最终前端字段按现有 converter 的 `currentCount ?? memberSize` 比较。
- `DOCKER_HOST=unix://... mvn -q -Dtest=GroupListCurrentMapperMySqlTest,AccountGroupCurrentSnapshotPersistenceMySqlTest,GroupCurrentLocalWriteMySqlTest test`：MySQL 8.4.8 共 38 个测试通过，0 失败、0 错误、0 跳过；覆盖 V121 实际 DDL、已解析/未解析健康字段归属、旧完整成员快照幂等回填、列表最终 VO 等价和现有六表双写回归。
- `mvn -q -Dtest=GroupProfileHealthMigrationSqlTest,GroupModelBackfillDryRunSqlTest,GroupModelBackfillMapperSqlShapeTest,GroupListCurrentMapperSqlShapeTest,GroupModelBackfillRunnerTest,GroupLinkServiceImplTest,GroupInviteLinkServiceImplTest,GroupLinkHealthReportServiceImplTest,GroupLinkControlledAdminMapperInMemoryTest test`：聚焦迁移合同、动态 SQL、人工 runner 和双写 Service 测试通过；邀请码旧事实拒绝过期/封禁恢复时，新 profile 同步拒绝覆盖。
- `mvn -q -Dtest=GroupDataModelFoundationMigrationMysqlTest,AccountGroupCurrentSnapshotPersistenceMySqlTest,GroupCurrentLocalWriteMySqlTest,AccountGroupSyncMySqlConcurrencyTest,GroupListCurrentMapperMySqlTest test`：本地临时 MySQL 8.4.8 共 45 个测试通过，0 失败、0 错误、0 跳过；覆盖 V120、六表批量双写、并发锁序、回填门禁和新旧列表固定数据等价。
- `mvn -q -Dtest=GroupModelBackfillRunnerTest,GroupModelBackfillMapperSqlShapeTest test`：9 个测试通过，0 失败、0 错误、0 跳过；覆盖人工启动参数、无定时器、普通阶段 50000 行循环、旧成员快照 5000 行主键游标及逐批进度日志、显式租户连接、NULL 与回填水位保护，以及无 `FOR UPDATE`。
- `mvn -q -Dtest=GroupListCurrentMapperSqlShapeTest,GroupModelBackfillRunnerTest,GroupModelBackfillMapperSqlShapeTest,GroupModelBackfillDryRunSqlTest test`：12 个测试通过，0 失败、0 错误、0 跳过；新增覆盖群主迁移阶段、列表 count/page 共用现有筛选、MyBatis 动态 SQL 实际解析、显式租户条件、page-first、本页成员聚合，以及不读取三张旧事实大表和不使用 `FOR UPDATE`。
- `mvn -q -Dtest=GroupLinkControlledAdminMapperInMemoryTest,GroupListCurrentMapperSqlShapeTest test`：6 个测试通过，0 失败、0 错误、0 跳过；H2 实际执行覆盖组合筛选 count 与租户隔离，动态 SQL 测试覆盖分页 SQL 形态。
- `DOCKER_HOST=... mvn -q -Dtest=GroupListCurrentMapperMySqlTest test`：本地临时 MySQL 8.4.8，1 个端到端对照测试通过；覆盖已解析群、未解析邀请、总数、全部返回字段、排序、两页分页、租户隔离和组合筛选。该结果只证明固定代表性数据一致，不替代 test1 固定水位全量对照。
- `DOCKER_HOST=... mvn -q -Dtest=GroupListCurrentMapperMySqlTest,GroupLinkControlledAdminMapperInMemoryTest,GroupListCurrentMapperSqlShapeTest,GroupModelBackfillRunnerTest,GroupModelBackfillMapperSqlShapeTest,GroupModelBackfillDryRunSqlTest test`：本阶段 16 个相关测试通过，0 失败、0 错误、0 跳过。
- `mvn -q -DskipTests package`、`xmllint --noout armada-api/src/main/resources/mapper/group/GroupModelBackfillMapper.xml`、`git diff --check`：均通过。
- `mvn -q test`：已尝试全量测试，但仓库既有 `PromotionCapiEventOutboxSchemaDbTest` 持续等待本地数据库连接，未进入完整测试集；为避免无效等待手动停止，退出码 130，不计为代码测试失败或全量通过。
- `DOCKER_HOST=... mvn -q -Dtest=GroupCurrentLocalWriteMySqlTest,AccountGroupSyncMySqlConcurrencyTest test`：本地临时 MySQL 8.4.8 共 22 个测试通过；其中 17 个覆盖群身份、已解析/未解析邀请、群资料、成员快照头、成员当前态与进退群事实、账号关系、baseline、同步状态、幂等、冲突门禁和不覆盖较新实时双写，5 个覆盖旧交叉写死锁复现、可重复读下排序写入无死锁及旧账号关系更新优先级。0 失败、0 错误、0 跳过。
- test1 后端通过项目现有 `armada-deploy/deploy-test.sh --env test1 --be -y` 部署实时角色双写修复；脚本因既有 Android base URL 环境档案差异返回非零，但远端容器为 running、未重启且启动后持续正常消费 Kafka 和响应接口。未部署前端、协议层或生产。
- test1 对 3 条已确认的 `WGP2_PROMOTE` 缺口执行严格定点事务，补入 3 条成员当前事实和 3 条账号群关系；未写加入时间、baseline、first-post 或营销资格。修复后缺群映射、缺账号群关系、关系缺成员均为 0，`baseline=1 AND first-post IS NOT NULL` 安全门禁为 0。
- test1 修复后固定一致性读对账覆盖 11,392 条默认列表行：静态筛选 ID 集合、唯一排序、页大小 1/20/100 的首页/中页/末页均一致，无重复或漏行；87 条未解析邀请继续走兼容路径，1 条不可见 Unicode 名称/主题为已知例外。管理员、群主和可用账号剩余差异均来自新模型已保存的更新成员事实，不是缺映射、缺关系或缺成员。
- 2026-08-16 增量修复允许同租户同群 JID 的多个 legacy handle 在都指向同一 canonical group 时通过回填门禁；先补齐 participant 1,056 行、binding 998 行，并修复 metadata 快照及拉群/自建群登记的关系双写。新版本部署后使用 canonical `group_id` 短写入屏障最终补齐 participant 630 行、binding 630 行；随后缺 binding、缺 self participant、历史 baseline 误写 first-post、binding 孤儿成员、canonical 群冲突和未解析 membership 群六项门禁均为 0，持续处理新账号群快照后缺口仍为 0。
- 群组列表读取入口已在本地切到 `GroupListCurrentMapper`；原接口、查询参数、转换器、排序分页和业务谓词未改，旧 `GroupLinkMapper` 仍服务其他写操作。`GroupLinkControllerTest`、`GroupLinkServiceImplTest`、`GroupListCurrentMapperSqlShapeTest`、`GroupLinkControlledAdminMapperInMemoryTest` 和真实 MySQL 8.4.8 的 `GroupListCurrentMapperMySqlTest` 通过，`mvn -q -DskipTests package`、Mapper XML 校验及 `git diff --check` 通过。
- 群组列表读取切换已通过同一项目部署脚本再次只部署 test1 后端。远端新镜像构建及容器替换成功；容器为 `running`、`RestartCount=0`，Kafka 分区正常重新分配，`/api/group-links` 未登录探针按现有合同返回 40104，未见新列表 SQL、Mapper 或 Spring 启动错误。重启消化账号群事件时旧 `AccountGroupMembershipMapper.xml` 出现 4 次 lock-wait 消费重试，无死锁；最近 60 秒 lock-wait、死锁和列表 SQL 错误均为 0，同时成功刷新账号群快照 22 次。脚本最终检查仍只被既有 Android base URL 环境档案差异阻断。
- test1 历史群筛选专项只读一致性快照：历史群总数新旧均为 4,419，六个群龄快捷区间、五个成员数快捷区间、亚洲、印度及“亚洲+印度+8～30 天+0～200 人”组合共 15 个场景，数量、ID 集合和 `created_at DESC,id DESC` 排序差异全部为 0。真实执行计划中历史群 count 为 1.65ms、首页 20 行为 0.038ms，均使用 `idx_group_link_historical`；前端历史筛选参数/抽屉 4 个测试通过。专项查询期间新列表 SQL 错误为 0。
- 后续持续观察中，账号群快照积压并发写旧 `AccountGroupMembershipMapper.xml` 时仍出现死锁重试；堆栈明确落在旧 `account_group_membership` 更新，不是历史群筛选或新列表查询，容器未重启且快照随后继续成功。该旧写路径锁竞争需作为独立问题处理，不能记成列表切读回归。
- 2026-08-17 最终业务冒烟确认 test1 容器为 `running`、`RestartCount=0`，容器 JAR 已包含群列表中文关键词对 ASCII JID/邀请码/手机号的 `CAST(... AS CHAR)` 兼容，最近日志未再出现 `Illegal mix of collations` 或列表 Mapper 错误；安全门禁 `was_in_initial_baseline=1 AND first_post_control_observed_at IS NOT NULL` 复核为 0。前端群列表 4 个测试及 `pnpm typecheck` 通过。
- 同次观察确认账号群事件仍持续出现死锁，主要受害 SQL 是 `AccountGroupCurrentSnapshotMapper.updateLegacyGroupReferences` 的“按一批群 JID 扫描全部 legacy alias”多表 UPDATE，并夹有旧 `AccountGroupMembershipMapper`；不能再把运行期死锁全部归因于旧表。热路径已本地改为仅回写旧流程本次已选中的 `group_link.id`，使用单表 `ORDER BY id ASC` 批量更新，不扩锁同群其他 alias，400 群 SQL 门禁不增加。真实 MySQL 8.4.8 的 17 条账号当前快照测试及 5 条既有并发测试通过；同群额外 alias 不被热事务改写的断言通过。该修复尚未部署 test1。
- 收口同时补齐真实 MySQL 测试建库链的 V124，并给旧成员快照回填增加目标差异过滤；重复执行同一主键区间现在返回 0，不再发出无效 upsert。真实 MySQL 8.4.8 的列表 3 条、旧入口/新表双写 24 条、账号快照 17 条、并发 5 条均通过；聚焦业务测试、`mvn -q -DskipTests package`、三个 Mapper XML 校验和 `git diff --check` 通过。
- 本地剩余切读收口：群详情只返回 `wa_group_profile.member_snapshot_version` 对应且当前仍在群的成员；成员导出排除仅由账号群快照产生的 self participant，同时保留完整快照、成员进退群和角色观察；最近进退群改读 participant 的对应事实列。旧详情快照迁移使用 `legacy:<group_link_id>:<snapshot_at>` 确定版本，较新的完整缓存快照不会被旧详情覆盖，也不会覆盖 profile 已有的列表成员数。账号列表群数和新增群营销资格均改读新关系事实；待拍 baseline 的禁止营销规则未变。
- 本轮 Java 17 最终定向回归 114 个测试通过，覆盖成员详情 presence、成员导出来源隔离、普通角色观察、详情已确认角色动作、账号群数、新增群资格和人工回填；`mvn -DskipTests package` 与 Mapper XML 校验通过。两个 Testcontainers 真 MySQL 新用例因本机无 Docker 未执行，不能记作通过。
- 旧事实表退役本地验证：八张旧事实表的在线 Mapper 静态门禁、当前关系/邀请/成员 Service 定向回归共 69 个测试通过；更新后的只读 Mapper H2 用例通过；`mvn -q -DskipTests compile`、`mvn -q -DskipTests test-compile`、Mapper XML 校验和 `git diff --check` 通过。
- 使用 OrbStack 的真实 MySQL 8.4.8 执行完整 `GroupCurrentLocalWriteMySqlTest`，共 25 个测试通过，0 失败、0 错误、0 跳过；新增回归证明创建者兼容写只能更新手机号/国家/洲，不能覆盖旧 preview 中已退役的群名、成员数、邀请或头像字段，过期观察也不能覆盖新值。
- 全量 `mvn test` 会进入项目既有的外部数据库集成测试，并因本机不可用的数据库连接持续等待；本轮没有把该环境阻断伪报为代码通过。退役范围已由上述聚焦测试、真实 MySQL 测试和静态 Mapper 门禁覆盖。

此前聚焦验证：

- `mvn -q -Dtest='AccountGroupMembershipReportServiceImplTest,AccountGroupMembershipSnapshotServiceImplTest,ProtocolAccountEventConsumerTest,MarketingMembershipSendPolicyTest' test`：50 个测试通过，覆盖事件映射、旧表快照、快照完整性复用、新表调用边界和既有营销规则，0 失败、0 错误、0 跳过。
- `mvn -q -Dtest='GroupLinkControlledAdminMapperInMemoryTest,GroupMembershipCountSemanticsMapperH2Test,MarketingMembershipSendPolicyTest,AccountGroupMembershipSnapshotServiceImplTest,GroupDataModelFoundationMigrationSqlTest,FlywayMigrationVersionContractTest,FlywayMigrationSqlContractTest' test`：通过，0 失败。
- `DOCKER_HOST=... mvn -q -Dtest=GroupDataModelFoundationMigrationMysqlTest test`：真实 MySQL 8.4.8，2 个测试通过，0 失败、0 错误、0 跳过。
- `DOCKER_HOST=... mvn -q -Dtest=AccountGroupCurrentSnapshotPersistenceMySqlTest test`：真实 MySQL 8.4.8，3 个测试通过，覆盖 400 群 SQL 预算、空完整快照和重放/锁序，0 失败、0 错误、0 跳过。
- `DOCKER_HOST=... mvn -q -Dtest=AccountGroupCurrentSnapshotPersistenceMySqlTest test`：新增精确进退群双写后共 5 个测试通过；补充覆盖上控后分类、`remove -> UNKNOWN` 以及迟到 `add` 不覆盖较新 `remove`，0 失败、0 错误、0 跳过。
- `DOCKER_HOST=... mvn -q -Dtest=AccountGroupCurrentSnapshotPersistenceMySqlTest test`：普通成员进退群双写后共 7 个测试通过；补充覆盖 PN/LID、不创建账号群关系、退群优先级和迟到进群事实，0 失败、0 错误、0 跳过。
- `DOCKER_HOST=... mvn -q -Dtest=AccountGroupCurrentSnapshotPersistenceMySqlTest test`：完整成员、群资料和当前邀请码双写后共 12 个测试通过；补充覆盖成员角色、完整快照缺席、快照头水位、同毫秒版本决胜、明确退群保护、资料空值/过期保护、邀请码绑定/轮换/封禁保护，0 失败、0 错误、0 跳过。
- `mvn -q -Dtest=GroupMetadataSnapshotPersistenceImplTest,WhatsappGroupMemberCacheServiceImplTest test`：11 个测试通过，覆盖两个现有完整成员快照入口的双写调用和落后快照不写新表，0 失败、0 错误、0 跳过。
- `mvn -q -Dtest=GroupInviteLinkServiceImplTest,GroupInviteLinkServiceInMemoryTest test`：10 个测试通过，覆盖当前邀请码中央入口和群 JID 后绑定路径，0 失败、0 错误、0 跳过。
- `mvn -q -Dtest=GroupLinkHealthReportServiceImplTest,GroupInviteLinkServiceImplTest,GroupInviteLinkServiceInMemoryTest test`：17 个测试通过，覆盖健康结果沿用旧计算并进入新表写入口，以及当前邀请码既有回归，0 失败、0 错误、0 跳过。
- `DOCKER_HOST=... mvn -q -Dtest=AccountGroupCurrentSnapshotPersistenceMySqlTest test`：健康结果双写后共 13 个真实 MySQL 8.4.8 测试通过；补充覆盖当前邀请码的状态、封禁、人数、时间、错误和失败次数，0 失败、0 错误、0 跳过。
- `mvn -q -Dtest=GroupLinkHealthReportServiceImplTest,GroupInviteLinkServiceImplTest,GroupInviteLinkServiceInMemoryTest,GroupLinkImportServiceImplTest,GroupDetailServiceImplTest test`：76 个测试通过，覆盖健康回报、当前邀请码、导入公开预览及 label、群名和权限命令回读，0 失败、0 错误、0 跳过。
- `DOCKER_HOST=... mvn -q -Dtest=AccountGroupCurrentSnapshotPersistenceMySqlTest test`：本轮最终共 13 个真实 MySQL 8.4.8 测试通过；公开预览、导入 label、健康字段及单字段 confirmed metadata 均已落新表，0 失败、0 错误、0 跳过。
- `DOCKER_HOST=... mvn -q -Dtest=GroupCurrentLocalWriteMySqlTest test`：真实 MySQL 8.4.8，7 个测试通过；覆盖已解析群/未解析邀请本地名称、备注、头像，详情及 metadata 名称/头像旁路，导入链接分组、已解析群组分组，以及已解析群不误写未绑定邀请，0 失败、0 错误、0 跳过。
- `DOCKER_HOST=... mvn -q -Dtest=GroupCurrentLocalWriteMySqlTest,GroupLinkServiceImplTest,GroupDetailServiceImplTest,GroupMetadataSnapshotPersistenceImplTest test`：75 个测试通过，其中 7 个使用真实 MySQL 8.4.8，0 失败、0 错误、0 跳过。
- `DOCKER_HOST=... mvn -q -Dtest=GroupCurrentLocalWriteMySqlTest#... test`：真实 MySQL 8.4.8 聚焦验证链接最后一条真实群 alias 删除、运营分组清空及资料恢复共 4 条测试通过；随后已由上述 17 条完整 MySQL 用例覆盖邀请池、标签/运营分组恢复及账号快照/邀请重新观察。
- `mvn -q -Dtest='GroupLinkServiceImplTest,GroupLinkLabelServiceImplTest,GroupFolderServiceImplTest,GroupDetailServiceImplTest,GroupMetadataSnapshotPersistenceImplTest' test`：本轮 93 个相关单元测试通过，0 失败、0 错误、0 跳过。
- `mvn -q -DskipTests package`：通过；`git diff --check`：通过。新增重点生产类和 MySQL 测试类非注释行分别为 798、800，未超过红线。
- `mvn -q -Dtest=AccountGroupMembershipSnapshotServiceImplTest test`：12 个测试通过，0 失败、0 错误、0 跳过。
- `DOCKER_HOST=... mvn -q -Dtest=AccountGroupSyncMySqlConcurrencyTest test`：本地临时 MySQL 8.4.8，5 个测试通过，0 失败、0 错误、0 跳过；旧交叉写的 supremum 死锁可稳定复现，当前一致性读、自然键排序和批量写路径连续并发完成。

只读证据：

- test1 MySQL 8.4.8，Flyway V120 已成功执行；V117～V119 为合入的拉群/协议索引和动作迁移，V120 为六表建表迁移。
- 2026-08-15 再次只读核验：`group_link` 共 11,256 行且均有效；11,169 行已解析为 11,169 个租户内唯一 `group_jid`，87 行仍是未解析邀请。
- test1 当前不存在同租户同 `group_jid` 的多条有效或已删除旧记录，因此 folder/label/displayName/remark/origin/membershipState/历史群/上控后群/协议来源掩码冲突数均为 0。迁移仍必须保留冲突检测，发现未来新增冲突时立即停止，不能静默折叠。
- 约 1.1 万 group_link、4.7～5.1 万账号群关系、43.8～45 万成员当前数据。
- 当前默认 count/page 约 1.38 秒/1.23 秒；去掉无条件成员聚合的基础查询约 32 毫秒，简单 count 约 5 毫秒。
- 当前 400 群快照约 2400～3600 条 SQL；现有 MySQL 并发测试已记录 supremum 死锁和正确锁序。

## 旧表退役阶段（保留不删除）

本阶段不新增表，不改变 API、列表、营销判断或协议合同，也不物理删除旧表。六张新表保存群组当前事实；兼容表只保存仍被现有合同要求的兼容信息。

### 在线访问边界

- 以下八张旧事实表退出在线读写：`account_group_baseline`、`account_group_membership`、`group_link_health`、`whatsapp_group_member_snapshot`、`whatsapp_group_member_cache`、`whatsapp_group_member_state`、`whatsapp_group_member_join_fact`、`whatsapp_group_departed_member`。
- `group_link` 继续承载现有 `groupLinkId`、同群 alias 和 87 条无法解析群 JID 的邀请。它不是当前群事实主表，不能反向覆盖六张新表。
- `group_link_preview` 只保留 `owner_phone`、`creator_country_iso2`、`creator_continent_code` 及各自观察水位的兼容读写；群名、成员数、邀请、头像、健康、权限等字段不再由在线流程写入。
- `GroupModelBackfillMapper` 仅供显式 `--armada.group-model-backfill.run-once=true` 人工迁移读取旧表，不是正常服务在线路径。静态门禁对正常 Mapper 禁止上述八张旧事实表，并限制 preview 兼容文件和字段。
- 本地退役代码尚未部署 test1。部署前只能确认代码和 Mapper 已收敛；必须在部署后用正常流量观察窗口完成运行时零访问门禁，不能用旧版本日志代替。

### test1 备份与恢复演练

- 2026-08-17 在 test1 对八张旧事实表及两张兼容表 `group_link`、`group_link_preview` 做一致性备份。备份保存在服务器 `/home/app/armada-deploy/backups/group-model-retirement-20260817-1132-cst/legacy-group-tables.sql.gz`，大小 `12,677,668` 字节，SHA-256 为 `9089f08da4c38e4aff2de9e4e01a046f0f7e20bbf62691304bc1c17521a9a75d`，`gzip -t` 通过。
- 源库快照行数：`account_group_baseline=1,207`、`account_group_membership=64,940`、`group_link=11,743`、`group_link_health=11,650`、`group_link_preview=11,683`、`whatsapp_group_departed_member=4,532`、`whatsapp_group_member_cache=2`、`whatsapp_group_member_join_fact=11,003`、`whatsapp_group_member_snapshot=470,195`、`whatsapp_group_member_state=12,775`。
- 备份已恢复到隔离的临时 MySQL 数据库 `armada_rollback_drill`；十张表恢复行数逐表与上述源快照完全一致。演练完成后只移除了临时 MySQL 容器，业务数据库、正常后端和旧表均未停止、覆盖或删除；备份文件及源/恢复计数文件继续保留。
- 当前已部署镜像另存为 `armada-backend:pre-legacy-fact-retirement-20260817`，镜像 ID 与当前运行版本一致，并完成不连接业务数据库的 Java 17 启动探针。若新版本异常，先回滚到该镜像；它仍读取六张新表并恢复旧事实双写，因此无需让旧表重新成为读取主表。
- 若要求回滚到比上述镜像更旧、仍读取旧事实的版本，必须先补偿新版本运行期间只写入六张新表的数据差额并重新对账；禁止直接回滚造成事实倒退。
- 本阶段没有执行 `DROP TABLE`、`TRUNCATE` 或旧数据删除。任何物理删除都必须另立变更、复核备份并再次取得用户确认。

## 部署

- 本轮成员详情切读有明确前置顺序：先构建新版本；在旧正常服务仍提供旧成员读取时，用同一构建物单独运行 `--armada.group-model-backfill.run-once=true --armada.group-model-backfill.start-stage=LEGACY_MEMBER_SNAPSHOTS --armada.group-model-backfill.end-stage=LEGACY_MEMBER_SNAPSHOTS`；回填和成员版本对账通过后，再用现有 `armada-deploy/deploy-test.sh --env test1 --be -y` 发布正常服务。不能先发布正常服务再补迁，否则旧详情快照尚未带版本的群会短暂显示空成员。
- 已在用户确认后部署 test1；部署前保留 `armada-backend:pre-group-v120-20260815-2132` 镜像及对应回滚 jar。未部署生产环境；当前 test1 运行版本已包含实时角色双写修复和群组列表读取切换。
- test1 人工回填已于 2026-08-15 16:15:48 完成；慢阶段改为 50000 行批次，并从 `ACCOUNT_GROUP_BINDINGS` 安全续跑，续跑结果 `batches=2`、`affectedRows=3104`。最终计数为：`wa_group=11192`、`wa_group_profile=11192`、`wa_group_invite=1455`、`wa_group_participant=74885`、`wa_account_group_binding=51707`、`account_group_sync_state=383`。
- 回填结束后的硬门禁 `was_in_initial_baseline=1 AND first_post_control_observed_at IS NOT NULL` 为 0；runner 的完整来源/冲突门禁通过，未出现死锁或锁等待。一次性容器已移除，原正常后端已恢复且 API 验活通过。
- V121 修复版本已再次只部署 test1 后端；未部署前端、协议层或生产。旧成员快照 50000 行事务在 test1 会压满数据库，最终改为 5000 行主键游标小事务并完成全量回填；一次性容器及临时恢复守护均已退出，正常后端已恢复。部署脚本仍因 test1 远端 Android base URL 与本地环境档案不一致而在深检阶段返回非零，本次用容器状态、API、Flyway 和关键日志完成手工核验。
- 2026-08-17 在 `a3b28b9c` 已部署后，使用同版本构建物仅人工执行 `LEGACY_MEMBER_SNAPSHOTS`，共 95 批、影响 836,348 行；一次性容器已移除，正常后端 `running`、`RestartCount=0`。4,597 个已解析最新群中，3,946 个存在旧完整快照且新表存储成员全部精确一致；651 个群均为新表快照头较新，不是缺资料或缺快照头。按当前在群口径比较时 3,847 个精确一致、99 个群少 1,075 行，这 1,075 行全部是旧快照之后观察到的退群事实，因此不是漏迁。旧快照存储成员 414,113 行、当前可见 413,038 行，无“旧快照非空但新表无成员”群。
- 前两次废弃的一次性启动因未完全关闭既有调度，分别触发了正常保留策略清理 218 条已发送且过期的 outbox，以及一次普通拉群执行 244 的抢占后跳过；执行 244 的锁和租约均已释放、状态未成功推进。最终 95 批回填使用关闭既有调度的干净启动参数完成。
- 回填完成后的正常流量观察中，后端未重启，未发现群列表、回填或模型迁移错误；但 02:06～02:29 的账号上线恢复群元数据任务持续发生 7 次死锁和 6 次锁等待超时，受害 SQL 均为 `GroupMetadataSyncTaskMapper.resumeDeferredForAccount` 的 `group_metadata_sync_task` 更新及新关系表存在性判断。账号状态事件会继续完成，但该群组锁竞争尚未收口，不能记为运行稳定门禁通过。数据库应用账号无 `performance_schema` 读取权限，因此本次未伪报服务端慢 SQL 摘要通过。
- 账号列表群数新旧只读对账覆盖租户 1 的 421 个有效账号：260 个一致、161 个不一致，且全部为旧表计数大于新模型当前事实；旧表合计 56,329、新模型合计 52,725，单账号最大差 127。为避免继续给 test1 数据库加压，差异归因查询在 10 秒上限主动取消；该项目前不能记为“业务口径不变”通过。

## 剩余门禁

本节保留各实施阶段的过程记录；其中“本地”“尚未提交/部署”等旧描述，以本文顶部状态和 2026-08-17 最新验证记录为准。

- V123 已按既定兼容方案给 `group_link` 增加 nullable `group_id/group_invite_id`，按租户 + 群 JID/邀请码回填；列表通过这两个 ID 连接 `wa_group/wa_group_invite`，账号群快照和邀请写入口同步维护引用。`group_link.id`、前端合同和业务判断不变，六张新表仍是当前群事实的唯一主表；已部署 test1，尚未提交或部署生产。
- V123 后的首批低风险读取迁移已在本地完成：运营分组数量/可用链接改读新资料和邀请健康事实，元数据同步候选改从 `wa_group`/当前邀请读取 JID 与邀请码，按群 JID 查兼容 handle 改走 `group_link.group_id -> wa_group.id` 并保留 `wa://group/` fallback。未修改调度状态机、营销、历史群分类或账号群关系。
- 第二批本地切换已完成：健康检测候选从 `wa_group/wa_group_profile` 读取 JID、封禁和最近检测时间；本地名称/备注/头像/分组/alias 删除通过 handle 的 canonical ID 直连新表，不再连接旧 preview；账号上线恢复延期 metadata 任务改用 `wa_account_group_binding + self participant presence_status=1`，管理员优先、在线账号选择、任务状态机和重试规则不变。相关 H2、SQL 形态及 MySQL 8 聚焦回归通过，尚未提交或部署。
- 拉群任务的群营销候选、管理员账号选择和等待池复核已改读 `wa_account_group_binding/wa_group_participant/wa_group/wa_group_profile`；历史来源只认已迁移的 `group_link.is_historical`，不再扫描 baseline JSON，也不使用 `joined_at` 推断上控后新群。创建者手机号仍保留旧 preview 兼容口径，避免当前群主变化改写既有业务含义。
- 普通营销的固定目标校验、账号动态群、发送前当前群复核、账号树群数量和任务详情当前成员状态已改读新当前事实；旧 `joined_at` 的发送时间边界一对一映射为 `membership_active_since_at`，初始历史群通过 `was_in_initial_baseline=1` 拦截，未把 legacy 迁移关系写成 `first_post_control_observed_at`。H2 真实 Mapper SQL、租户改写、边界和退群拦截回归通过；本地真库当前不可连接，新增 MySQL 聚焦用例尚待数据库恢复后执行。本批未提交、未部署。
- 营销轮次发送前的批量成员状态复核继续读新 binding 和 self participant，并映射为旧的五个业务状态码；账号完整快照的新增群差集也已由同一次新模型写前事实决定。旧账号群快照事实写已在本轮退役代码中停止。
- 群详情头和成员列表均已改读新模型。profile 的 `member_snapshot_version` 是提交头，participant 的 `last_snapshot_version` 标识最后完整快照成员；查询同时要求 `presence_status=1`，因此旧逻辑已确认的踢人会立即消失，角色事件仍在原快照成员上更新。发布前必须先补迁旧详情快照版本。
- 账号列表 `groupsNum` 已改读 binding 对应 self participant 的当前在群状态，等价保持旧 `membership_status IN (1,2)` 可发送口径。首次基线证据改读 `account_group_sync_state`；创建者手机号/国家/洲继续兼容旧 preview。其余旧事实表仅允许人工回填证据读取，不再进入在线读写。
- 当前 test1 无多 alias/属性冲突，迁移期继续保留旧 `group_link` 作为外部 ID 兼容；不新增 alias 业务表，后续若门禁发现冲突则停止迁移并重新评审。
- 账号群报告、账号自身及普通成员进退群、完整成员/群资料快照、当前邀请码、公开预览、健康回报、群名/权限命令回读、本地资料、详情/metadata 列表镜像、分组及真实群 alias 级删除/恢复入口已经六张新表为唯一当前事实写入目标；未解析邀请继续由兼容 alias 保持 UI 删除语义，不滥用邀请系统退役字段。本地 MySQL 恢复、可重复读并发和 test1 默认列表执行计划门禁已通过；本轮不新增重试框架。
- 兼容口径已明确：创建者/国家/洲严格沿用旧 preview；管理员和可用账号沿用旧业务谓词，但读取新模型的更新成员事实，不复制旧表陈旧结果。固定水位保留 1 条不可见 Unicode subject/名称已知例外，87 条未解析邀请继续走兼容路径。列表切读已部署 test1，进入观察期；尚未删除旧列表 SQL 或旧事实表。
- 旧表物理删除不在本阶段；必须单独发布、复核现有恢复演练并再次取得用户确认。
