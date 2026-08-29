# 变更记录：群组数据模型重建（方案 A）

- 日期 / 分支 / worktree：2026-08-15 / 四个审计仓库均为 1.0.3-snapshot / /Users/daishuaishuai/IdeaProjects/armada
- 需求来源：用户要求以六张权威表重建群组模型，并穷举群组列表、详情、协议事件、任务、营销、历史群、导入、建群、导出和 test1 依赖
- 设计文档：docs/superpowers/specs/2026-08-15-group-data-model-rebuild-design.md
- 状态：进行中（设计已保存，待用户评审；未编码、未迁移、未部署）

## 目标（一句话）

用六张职责单一的权威表替换群组当前态表簇，在不破坏现有群组相关业务的前提下消除多表同字段、错误事件覆盖和列表全量聚合。

## 缺口拆解 / 任务清单

- [x] 核对现有群组表、字段、Mapper、Service、Controller 和定时任务
- [x] 核对前端群组列表、详情、导入、历史群、任务和营销契约
- [x] 核对 Web/Baileys 账号群快照、成员、邀请、健康和 metadata 事件
- [x] 核对 test1 当前数据规模和群组列表查询基准
- [x] 核对 test1 部署/深检/Kafka 检查/Android 编排脚本与本地四仓测试入口
- [x] 完成 Android Zhuan 事件契约最终交叉核对
- [x] 固定六张权威表职责、字段、约束、索引和数据归属
- [x] 设计 groupId / inviteId / legacyGroupLinkId 兼容边界
- [x] 设计 backfill、单一权威写入、旧兼容投影、shadow read 和按域切换
- [x] 设计回滚、对账、性能和跨仓测试门禁
- [x] 补齐专用 normal-group 第三 topic、四类建群入口、direct API 幂等与 durable result
- [x] 补齐 Android 三类成员 v1 事件和 Web add/remove 到 M 的兼容映射/退场门禁
- [x] 冻结旧群事实 reader/writer、Flyway、Kafka 配置、前端、country、schema/fixture 和运维脚本 owner manifest
- [x] 完成四路独立复核：六表不变量、MySQL/迁移、全业务兼容闭包、最终文档一致性
- [x] 纠正 legacy joined_at 迁移语义，固化 InnoDB RR 普通读/排序写死锁约束和 400 群快照 SQL<=10 门禁
- [ ] 用户评审并确认设计中的待决策项
- [ ] 评审通过后另写实施计划；本记录不授权直接改表或部署

## 关键设计决策

### 六张权威表

- wa_group：真实群身份和本地运营属性
- wa_group_profile：WhatsApp 当前资料、设置、群状态和成员快照头
- wa_group_invite：邀请 code、链接状态和未解析预览
- wa_group_participant：成员 presence、role、PN/LID 和最近进退群事实
- wa_account_group_binding：Armada account 与 participant 绑定及账号维度 baseline 语义
- account_group_sync_state：账号完整群快照、baseline 空集合、完整性和代次

### 六张不是项目全部群表

group_folder、group_link_label、导入 batch/detail、metadata/batch task、进群/拉群/营销/建群等过程表继续保留，但不得作为当前群事实主表。

首期另有两张明确的非权威过程表：最小 migration run/checkpoint，以及账号快照单 SQL 批写的 `group_snapshot_effect_outbox`。前者只存 run/status/lease/watermark/count/hash/conflict，后者只存 metadata/immediate-marketing intent 与投递状态；严禁保存可供业务回写的群当前值。它们不属于六张权威表，但物理表数必须对用户透明。合法历史无法 typed 化时可保留最小只读 legacy compat map。

### 已否决方案

- 三张大宽表：无法清晰承载未解析邀请、账号维度 baseline、空完整快照、PN/LID 和账号生命周期。
- 在旧表旁永久增加六张并让业务自行双写：会继续出现多主和字段漂移。
- 把列表全部字段做第七张 projection 表：当前规模不需要，先用 page-first + 按页 enrichment；只有实测仍不达标才单独评审。
- 把历史群继续固化成 wa_group 布尔列：会丢失 account 维度，营销排除不可靠。
- 用 wa://group/{jid} 保持 link_url 非空：假链接污染邀请语义。
- 把 Web snapshotComplete 缺失推断为完整：可能把遗漏群错误标离群。

### 迁移期间唯一主值

首期 Phase 1 additive 建表、可重入预填和 shadow read；Phase 2M 短暂停全部群 writer/reader/effect，排空事件和命令，按最终水位补增量并重跑门禁，再在恢复流量前把唯一 writer 与全部群当前态 reader 一起切到六表。旧当前态表从此冻结，不做 LegacyProjectionAdapter，也不能作为在线读回滚；失败只能在暂停窗口整体退回，恢复新 writer 后只 roll-forward。观察期结束后先发 Phase 6A cleanup binary，确认旧当前态表访问为 0；另经用户确认才发 Phase 6B Flyway DROP。

首期迁移采用短暂停写、排空在途事件/命令、最终回填、切唯一 writer、恢复和对账，不建设全量 v1/v2 tri-state admission、effect epoch 或 CUTOVER_SPOOL。零停机 shadow 双发只保留为风险审计备选，若业务明确要求再单独立项。

### 兼容不是“只加 typed ID”

metadata task、batch item、marketing generated key 等旧列仍有 NOT NULL/唯一约束。旧 binary 回滚窗口内必须 designated legacy ID + typed ID 双填，按表并存 v1/v2 唯一键；只有 typed 引用、outbox、v1 topic、DLT 和 Android JSONL 水位全部闭合后，才允许删除 Resolver/旧列。

### 方案 A 列表行集

切换前 v1 Adapter 保留 active legacy row 和 I-only 行，每个 alias 的 id/resourceKey/sourceFileName/labelId 仍按本 legacy row 输出；动作只有在用户批准 canonical mutation 后才解析到同一个 G。最终方案 A 为 G UNION I-only；resolved legacy duplicate 折叠与 folder/delete/remark 语义归并是 Phase 2M 前必须确认的显式业务变化，不能混入普通 shadow diff。管理员列保持现网“受控管理员”隐私范围，国家/大洲使用可 SQL 下推的可重建 country 投影。

最终 typed 行的 sourceFileName / labelId 使用唯一的 listAdoption 标量：GROUP 从关联 invites 的最新成功 import detail 选中一条，文件名取其 batch、labelId 取该 selected invite 当前 label；筛选、count、page 和 enrichment 共用同一定义。活跃已归组的重复导入为 result=FAILED/failReason=DUPLICATE 且不改来源；hidden/未归组邀请再次收编复用现有 result=SUCCESS/successType=ADOPTED，清 hidden、写目标 label，并可成为新的 listAdoption。

所有 batch action 先把 legacy aliases 解析为 typed key，再按 canonical G/I 去重；requested/resolved/canonical count 和逐 alias `CANONICAL_DUPLICATE` 结果分开，任务/effect 只执行一次。新 I 在进入 v1 可见或旧任务引用前取得独立 invite primary legacy alias，不依赖 I.id，且与 G 的 synthetic group primary 分离。

成员当前态补 `DEPARTED_UNKNOWN`。Android history/depart/join 和 Web `group.participant_changed` add/remove 都进入明确的 M presence/join/exit version family；普通 participant departure 不 retire B，observer 不写自身 B/S。旧无 binding 四元组事件切换后只能隔离并回读。

## 影响

### 数据库

设计阶段无数据库变更。实施时 schema 变更只允许 Flyway，数据回填由独立、可重入的受控 migration runner 执行：

1. additive 创建六表和兼容列；
2. Flyway 之外由耐久 migration run ledger 驱动可重入 backfill、baseline provenance 分类、shadow/cutover 与对账；
3. 先发布 Phase 6A cleanup binary 并观察运行访问为 0；
4. 逻辑备份和逐表恢复演练后，经用户再次确认，才用独立 Phase 6B Flyway drop 旧表。

若法定历史或冲突记录无法 typed 回填，先抽出不含当前事实的 `legacy_group_link_compat`，只保留只读 `HistoryCompatResolver` 服务已结束历史详情；Phase 6A 仍删除通用 LegacyGroupLinkResolver 和所有旧当前态表访问。

### API

- v1 /api/group-links 暂时兼容；
- typed 方案 A 混合列表使用 /api/group-resources + resourceType/groupId/groupInviteId；
- canonical 真实群使用 /api/groups 和 groupId；
- 邀请池使用 /api/group-invites 和 groupInviteId；
- typed invite 批检使用 `/api/group-invites/batch-check`；旧 batch-preview 仅作 typed adapter；
- direct create 使用 caller-stable Idempotency-Key、durable command/result 和同 eventId Reducer，保持原同步成功响应；
- 灰度期明确 legacyGroupLinkId，禁止静默替换 ID；
- 历史任务保留执行时快照。

### Redis / Kafka

- 当前未设计新增 Redis 结构。
- 推荐新增 `protocol.account.group-sync.events.v2` / `protocol.group.events.v2` / `protocol.normal-group.events.v2` 三个不兼容语义 topic；第三条覆盖 normal/direct/两类营销 durable create-result。canonical 事件契约补 bindingGeneration、snapshotId、complete、skippedCount、稳定 eventId、fieldMask 和 create-result 逐项确认，旧字段名只允许 v1 adapter 识别。
- 首期只要求后端 consumer 先兼容 canonical 字段，并在 Phase 2M 排空实际 topic 后切唯一 writer；全量 v1/v2 shadow 双发和 Admin offset 接管降为零停机备选。
- 原 effectAuthorityEpoch / LEGACY_SHADOW / CUTOVER_PENDING 方案降为零停机备选，不是六表首期协议字段或上线门禁。
- 当前后端没有数据库 event inbox；恢复依赖 Kafka offset/DLT、protocol command outbox、Android JSONL，以及迁移观察期明确新增的 durable fact journal（若现有介质不足）。

### 跨仓

- armada：六表、Reducer、迁移、Mapper/Service/API、兼容投影和回归测试。
- wheel-saas-pure-web：方案 A 保持当前列/筛选但迁到 typed resource ID；方案 B 需用户另行批准。
- armada-protocol：Web 完整快照、participant add/remove、normal/direct create result、字段版本和 v2 schema/fixture 契约。
- whatsapp-server-feature-android-zhuan：Android HistorySync/WGP2、PN/LID、binding context、per-target spool 和 normal/direct create result 契约。

## 关键约束

- 同一事实只在一张权威表维护。
- partial / skippedCount>0 快照不得删除未出现关系。
- role 事件不得断言 presence。
- 已退出但原因不明用 DEPARTED_UNKNOWN；成员退群不等于 account binding instance 退役。
- 软删除群不得被协议事件自动恢复。
- 旧 baseline 迁移标 LEGACY_UNKNOWN，不能伪装为显式完整。
- 旧 membership.joined_at 是快照建行/回群混合时间，禁止直迁 first_post_control_observed_at；本期 legacy command 绝不迁 was=0，所以 first-post 一律 NULL/`0x00`，迁移不得触发即时营销。字段改用 observed 而非 joined，避免把快照观察时间伪装成真实加入时间。切换后只有 EXPLICIT_COMPLETE baseline 之后、事务预读时 B 物理不存在的群，才可由实时明确 JOIN/ADD 或合格 FULL_ACCOUNT_SET 建立 was=0/first-post；迁移已有 was=NULL 的 B 永远不能被首轮强制快照升级。
- baseline 策略与 binding 生命周期正交：state=3 的活跃账号保留 current binding，但写 baseline_filter_enabled=0 + DISABLED/NONE；state=1/3、WATERMARK_ONLY 和无正向 provenance 的空 JSON 不得生成 baseline B=1。
- 完整账号/成员快照按查询切点版本对每个缺失成员做 CAS，不能覆盖切点后的 ADD/REMOVE。
- 账号快照必须继承现有 MySQL RR 硬约束：先普通一致性读区分 existing/missing，再按表和实际唯一键升序写；禁止对 missing key 先 UPDATE 再 INSERT，缺失关系用排序主键定点 CAS。
- 预分类用普通一致性读；新 G/M/I 写后为下游解析 AUTO_INCREMENT ID 时必须按自然唯一键做 current/locking bulk read，避免 RR 旧 read view 看不到并发 winner。事务中全部 M present/absent 必须早于 B，不得反向取锁。
- 400 群单账号完整快照 MySQL 可见 SQL execute/往返 `<=10`，禁止任何逐群 Mapper/Service 调用；不能用 Java executeBatch 隐藏服务端 N 次 statement。
- SQL<=10 的第 10 批固定为 `group_snapshot_effect_outbox` 的单一 multi-row 写，提交后 worker 再展开 metadata/marketing 任务；保留 G ID current-read 时，直接写两张任务表的最低预算是 11，不能仍宣称只有 10。
- migration origin 在生成 effect 参数前直接排除即时营销，不写 intent/task/send attempt/protocol outbox，也不唤醒 worker；这条是执行前禁止，不依赖事后 count。
- 所有 role 查询都要求 IN_GROUP 且 role epoch 等于 membership epoch。
- 账号软删除/换绑必须 retire B、推进 S generation；软删/解绑写 DISABLED/NONE，新活跃 binding 写 PENDING/NONE，并校验 active protocol account 唯一。
- active protocol generated 唯一键必须早于首条 S 和 lifecycle 新 writer；绑定入口暂停、冲突处置、Flyway 建键、验证、恢复的顺序不可颠倒。
- 远程、真库、部署和批量数据写入前重新确认 test1。
- 当前四个审计仓库及工作区已有其他会话在途改动，后续实施不得覆盖。

## 验证（evidence-before-done）

本次仅设计和只读审计，没有运行迁移、DDL/DML 或部署。为确认现有快照锁序测试基座，运行了与本轮文档修订相关的聚焦测试：

- `mvn -q -Dtest=AccountGroupMembershipSnapshotServiceImplTest test`：12 个测试通过，0 失败 / 0 错误 / 0 跳过；覆盖现有全局表/键顺序、existing update 和完整/不完整快照行为。
- `mvn -q -Dtest=AccountGroupSyncMySqlConcurrencyTest test`：本机无可用 Docker socket，Surefire 明确记录 5 个测试全部 skipped；不计为 MySQL 门禁通过。新方案的 MySQL 8.4 RR / SQL<=10 验收必须在 Docker 可用的 CI 或等价环境重跑，skip 必须让迁移构建失败。

已取得的只读证据：

- test1 MySQL 8.4.8，当前 Flyway 到 V116；
- 群主记录约 1.1 万，账号群关系约 4.7～5.1 万，成员当前数据约 43.8～45 万；
- 当前默认 count / page 约 1.38 秒 / 1.23 秒；
- 去除全量成员和账号聚合的瘦查询约 32 毫秒，简单 count 约 5 毫秒；
- Web Baileys patch 能统计 legacy skippedGroupCount，但当前 publisher payload 未显式携带 canonical complete/skippedCount；
- 后端仍存在把 Web legacy snapshotComplete=null + skipped=0 推定为完整的兼容逻辑；
- 当前 PENDING baseline 在完整性判断前就可能被固化；同步请求 SQL 还会把缺 row 初始化为空 JSON/count=0/capturedAt；
- 当前账号快照无条件把 syncAt 写入 membership.joined_at，新行、空值或退群再进时会固化/改写，不能当作首次入群或 baseline 后入群证据；
- `replaceVisibleGroups` 稳态路径存在逐群 registry/classification/preview/health/membership/`selectActiveById`；400 群约 2400～2800 条，叠加首次分类、metadata enqueue 和即时营销可接近 3600 条；
- 现有 `AccountGroupSyncMySqlConcurrencyTest` 在 MySQL 8.4 RR 中可复现 preview/health supremum 死锁，并验证普通读分类、固定表序和唯一键排序后的并发快照；
- `protocol.normal-group.events.v1` 是独立活动结果链，当前任务终态/step mismatch 会提前丢弃真实建群结果，次管理员提权还在结果消费事务内同步调用 WhatsApp；
- direct API 和两类营销 worker 都存在“外部建群成功、登记当前群前崩溃”的窗口，direct API 每次重试还生成新 UUID；
- Android 三类成员事件缺 binding instance/generation/effect token，Kafka 失败 JSONL 落盘即返回成功但没有自动重放/容量/retention；Web add/remove 当前 Java consumer 直接忽略；
- country 当前存在 libphonenumber 严格解析与共享区号最长前缀两套口径，列表/历史和营销导出可能不一致。

文档自检：

- [x] 六表字段与一个事实一处存逐项检查
- [x] 全仓旧表直接读写引用与业务矩阵交叉检查
- [x] Web / Android 事件矩阵交叉检查
- [x] 独立复核 snapshot CAS、writer barrier、legacy DDL、列宽和数据级回滚
- [x] 终审修复迁移 command admission 与 v2 writer 切换事件空窗
- [x] 统一 snapshotId/complete/skippedCount、baseline 状态组合、TEMP_UNAVAILABLE 时钟和 invite preview version keys
- [x] 固定 baseline provenance/state 矩阵、CURRENT/LEGACY_RETIRED admission、S 四态 MySQL CHECK 与 lifecycle fencing
- [x] 固定 Kafka topic identity/offset 接管、逐分区连续 commit、effect authority token、COMMIT/ABORT ledger 和跨版本副作用幂等
- [x] 固定 v1 alias 与 typed resource 分层、GROUP/I-only capability、重复导入/删除 label/listAdoption 语义
- [x] 审计过 ABORT offset/CUTOVER_SPOOL 风险；现已降为零停机备选，不纳入六表首期
- [x] 闭合 canonical batch alias 去重、新 I invite primary、I-check error domain、terminal capability 与 typed batch-preview
- [x] 把专用 normal-group 纳入第三 topic，覆盖四类建群 flow、两事务结算、binding command-result 例外、提权 outbox 与 direct idempotency
- [x] 把 Android 三类 v1 成员事件和 Web add/remove 显式映射到 M，并冻结 per-target JSONL/spool retirement gate
- [x] 冻结当前 reader/writer manifest、完整 Flyway 分类、country resolver/reindex、跨仓 schema/fixture 和运维 allowlist
- [x] 二次复核 joined_at 迁移、RR supremum 死锁和快照 SQL 计数，并增加可机器验收的硬门禁
- [ ] 最新增量合入后的业务闭包与最终一致性独立复核
- [x] 文档链接、状态枚举、迁移顺序、格式和回滚最终机械自检

## 部署

- commit / 环境 / 部署后验证结果：未部署；本轮只提交设计文档和本变更记录。

## 遗留 / 跟进

- 用户确认是否长期保留当前全部列表列和高级筛选；未确认前默认采用兼容方案 A。
- 用户确认是否把多个 resolved legacy 行的本地当前语义归并到一个 G 并最终折叠；这是进入 Phase 2M 的前置条件。未确认前只能停在 Phase 1 shadow read，v1 Adapter 保留旧 row universe，I-only 始终不丢。
- 用户确认管理员列是否改名“受控管理员号码”；默认不扩大手机号暴露范围。
- 用户确认国家/大洲是否统一为 strict confirmed-phone resolver；默认推荐统一，并把共享区号差异列为 expected diff。
- 用户确认 owner 在 UI 继续叫“创建者”还是改成“群主”。
- 对 group_name 与 wa_subject 冲突行，需要切读前 metadata 刷新或人工结论。
- 对 LEGACY_UNKNOWN baseline，是否提供运营重置/确认流程。
- 如果业务要完整的成员进退群流水，需要另行设计 append-only 审计，而不是把当前最新事实表误当历史。
- 设计评审通过后，按开发流程另写可执行实施计划和每阶段测试清单。
