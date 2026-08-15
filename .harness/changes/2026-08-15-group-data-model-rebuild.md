# 变更记录：群组数据模型重建（方案 A）

- 日期 / 工作目录：2026-08-15 / `/Users/daishuaishuai/IdeaProjects/armada`
- 分支：`armada` 与 `wheel-saas-pure-web` 已从各自 `1.0.3-snapshot` 创建并推送 `1.0.3-group`，均已跟踪各自 `origin/1.0.3-group`
- 需求来源：以六张权威表重建群组当前事实模型，完整排查群组依赖；仅删除已指定的列表展开详情，不改变主列表、业务逻辑或协议合同
- 设计文档：`docs/superpowers/specs/2026-08-15-group-data-model-rebuild-design.md`
- 状态：六表最小 additive DDL（V117）、现有写入口双写、只读回填门禁、六表人工分批回填和新表列表影子 Mapper 均已完成本地实现；旧表仍决定列表、营销和全部业务结果，影子 Mapper 尚未接入 Service，回填入口默认不注册且未在任何环境执行，真实 MySQL 回填/列表验证、切换读取、迁移和部署尚未进行

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
- [x] 新增仅包含六张表的 V117 最小建表迁移；不含旧表 DML/ALTER、回填、outbox 或协议设施
- [x] 在真实 MySQL 8.4.8 上验证 V117 可执行、可重入和关键 CHECK/排序规则
- [x] 新增未接事件入口的账号群快照五表批量持久化底座；不写 invite、旧表或副作用表
- [x] 真实 MySQL 8.4.8 验证 400 群 `<=10`、完整空快照、重放和 M-before-B
- [x] 复用现有账号群报告事件的租户校验、过期句柄过滤、完整性判断和事务，同步写入新表；旧表结果继续驱动营销
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
- [x] 新增通过启动参数 `--armada.group-model-backfill.run-once=true` 人工触发的 `wa_group` 一次性回填入口；无 `@Scheduled`，每批在独立事务中执行只读冲突门禁 + 1 条 500 行集合写，直到无数据，显式租户连接、自然键排序，不使用 `FOR UPDATE` 或逐群调用；用源/目标水位阻止较旧回填覆盖较新的实时双写
- [x] 在同一人工入口顺序增加 `wa_group_profile`、`wa_group_invite` 和当前邀请码指针的 500 行集合回填；保持群资料/本地展示/公开预览/健康字段边界、秒转毫秒、NULL 未知语义和实时水位，不映射普通 UI 删除为邀请系统退役
- [x] 在同一人工入口增加 `wa_group_participant`、`wa_account_group_binding`、`account_group_sync_state` 的保守集合回填；旧 `joined_at` 只进 `membership_active_since_at`，baseline 只迁 1/NULL，回填 SQL 不写 first-post，成员当前态与最近进退群事实按各自水位写入
- [x] 补齐旧预览群主号码/国家到 `wa_group_participant` 的保守回填；只写 role=群主及其观察水位，不虚构在群态
- [x] 新增未接业务入口的群列表影子 Mapper；复用现有 `GroupLinkQuery` / `GroupLinkVoRow`，count 不聚合成员，page 先分页 legacy 行句柄、再仅对本页从六表补齐当前事实，显式租户连接且不加锁
- [ ] 在容器环境执行最后三表的真实 MySQL 回填、幂等和门禁用例
- [ ] 在真实 MySQL 固定水位数据上对比旧/新列表的总数、行集合、字段、排序、分页和全部筛选
- [ ] 用户评审六表字段和迁移/切换方案
- [ ] 任何环境写入、切读或部署前再次取得用户确认

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

当前代码新增 V117 六表建表迁移，并已把账号群报告、账号自身进退群、普通成员进退群、完整成员和群资料快照、当前邀请码、公开邀请预览、健康回报、已确认群名/权限、本地资料、分组及删除/恢复入口接入新表双写；没有修改旧表结构或切流。新增的人工回填入口没有定时器，默认不存在于 Spring 容器；只允许在一个已确认的应用实例用 `--armada.group-model-backfill.run-once=true` 启动，当前按群身份、资料、成员快照头、邀请、成员、账号关系和同步状态的固定顺序执行 500 行集合回填，未在 test1 或其他环境执行。旧事实表在切换、观察、备份和恢复演练完成，并再次取得用户确认后才允许独立删除。

### API / 前端

现有 API 和前端业务无变化；前端只删除已指定的行展开详情。若只能改变 key，追加改动必须集中在 row-key/request-key，不得解析 G/I 类型或新增业务判断。

### Kafka / 协议

不新增 topic、consumer group、payload 字段、OpenAPI schema 或协议部署步骤。现有 v1 topic/outbox/DLT/Android JSONL 必须继续能由新后端解析。

## 验证记录

本次新增 V117 六表最小建表迁移和迁移合同测试；只在 Testcontainers 临时 MySQL 中执行了 DDL，没有对 test1、其他远程数据库或协议环境执行 DDL/DML、迁移或部署。

本轮前端验证：

- `pnpm exec node --import tsx --test src/views/group/list/components/GroupListTable.test.ts`：4 个测试通过，包含展开行不存在门禁。
- `pnpm typecheck`：通过。

本轮六表回填验证：

- `mvn -q -Dtest=GroupModelBackfillRunnerTest,GroupModelBackfillMapperSqlShapeTest,GroupModelBackfillDryRunSqlTest test`：10 个测试通过，0 失败、0 错误、0 跳过；覆盖人工启动参数、无定时器、各阶段 500 行循环批次、群 JID/邀请码/成员身份/账号 baseline 冲突先阻断、显式租户连接、自然键排序、NULL 与回填水位保护，以及无 `FOR UPDATE`；账号关系回填 SQL 明确不包含 first-post 字段。
- `mvn -q -Dtest=GroupListCurrentMapperSqlShapeTest,GroupModelBackfillRunnerTest,GroupModelBackfillMapperSqlShapeTest,GroupModelBackfillDryRunSqlTest test`：12 个测试通过，0 失败、0 错误、0 跳过；新增覆盖群主迁移阶段、列表 count/page 共用现有筛选、MyBatis 动态 SQL 实际解析、显式租户条件、page-first、本页成员聚合，以及不读取三张旧事实大表和不使用 `FOR UPDATE`。列表 SQL 尚未在真实 MySQL 执行，不能据此声称新旧结果一致。
- `mvn -q -DskipTests package`、`xmllint --noout armada-api/src/main/resources/mapper/group/GroupModelBackfillMapper.xml`、`git diff --check`：均通过。
- `mvn -q test`：已尝试全量测试，但仓库既有 `PromotionCapiEventOutboxSchemaDbTest` 持续等待本地数据库连接，未进入完整测试集；为避免无效等待手动停止，退出码 130，不计为代码测试失败或全量通过。
- `GroupCurrentLocalWriteMySqlTest` 已增加群身份、已解析/未解析邀请、群资料、成员快照头、成员当前态与进退群事实、账号关系、baseline、同步状态、幂等、重复 JID/code 和不覆盖较新实时双写用例；本轮因本机容器执行额度限制未运行，不能计为真实 MySQL 通过，执行环境恢复后补跑。

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
- `DOCKER_HOST=... mvn -q -Dtest=GroupCurrentLocalWriteMySqlTest#... test`：真实 MySQL 8.4.8 聚焦验证链接最后一条真实群 alias 删除、运营分组清空及资料恢复共 4 条测试通过；邀请池不被普通 UI 删除退役、标签/运营分组恢复及账号快照/邀请重新观察的完整 MySQL 复跑因本机容器执行额度限制待补。
- `mvn -q -Dtest='GroupLinkServiceImplTest,GroupLinkLabelServiceImplTest,GroupFolderServiceImplTest,GroupDetailServiceImplTest,GroupMetadataSnapshotPersistenceImplTest' test`：本轮 93 个相关单元测试通过，0 失败、0 错误、0 跳过。
- `mvn -q -DskipTests package`：通过；`git diff --check`：通过。新增重点生产类和 MySQL 测试类非注释行分别为 798、800，未超过红线。
- `mvn -q -Dtest=AccountGroupMembershipSnapshotServiceImplTest test`：12 个测试通过，0 失败、0 错误、0 跳过。
- `mvn -q -Dtest=AccountGroupSyncMySqlConcurrencyTest test`：本机无 Docker socket，5 个测试全部 skipped；不计为 MySQL 门禁通过。

只读证据：

- test1 MySQL 8.4.8，Flyway 到 V116。
- 2026-08-15 再次只读核验：`group_link` 共 11,256 行且均有效；11,169 行已解析为 11,169 个租户内唯一 `group_jid`，87 行仍是未解析邀请。
- test1 当前不存在同租户同 `group_jid` 的多条有效或已删除旧记录，因此 folder/label/displayName/remark/origin/membershipState/历史群/上控后群/协议来源掩码冲突数均为 0。迁移仍必须保留冲突检测，发现未来新增冲突时立即停止，不能静默折叠。
- 约 1.1 万 group_link、4.7～5.1 万账号群关系、43.8～45 万成员当前数据。
- 当前默认 count/page 约 1.38 秒/1.23 秒；去掉无条件成员聚合的基础查询约 32 毫秒，简单 count 约 5 毫秒。
- 当前 400 群快照约 2400～3600 条 SQL；现有 MySQL 并发测试已记录 supremum 死锁和正确锁序。

## 部署

- 未部署。任何 test1 写入、部署或批量迁移前必须再次确认目标环境。

## 剩余门禁

- 当前 test1 无多 alias/属性冲突，迁移期继续保留旧 `group_link` 作为外部 ID 兼容；不新增 alias 业务表，后续若门禁发现冲突则停止迁移并重新评审。
- 六表字段、索引、迁移规则和业务 parity 评审通过。
- 账号群报告、账号自身及普通成员进退群、完整成员/群资料快照、当前邀请码、公开预览、健康回报、群名/权限命令回读、本地资料、详情/metadata 列表镜像、分组及真实群 alias 级删除/恢复入口已接新表双写；未解析邀请继续由兼容 alias 保持 UI 删除语义，不滥用邀请系统退役字段。切换读取前仍须完成本轮新增 MySQL 恢复测试和真实可重复读并发门禁，本轮不新增重试框架。
- 后端列表 API/DTO 与全业务 shadow diff 为 0；前端另验收 DOM 不再渲染展开区。
- 旧表 drop 必须单独发布、恢复演练并再次取得用户确认。
