# 变更记录：群组数据模型重建（方案 A）

- 日期 / 工作目录：2026-08-15 / `/Users/daishuaishuai/IdeaProjects/armada`
- 分支：`armada` 与 `wheel-saas-pure-web` 已从各自 `1.0.3-snapshot` 创建并推送 `1.0.3-group`，均已跟踪各自 `origin/1.0.3-group`
- 需求来源：以六张权威表重建群组当前事实模型，完整排查群组依赖；仅删除已指定的列表展开详情，不改变主列表、业务逻辑或协议合同
- 设计文档：`docs/superpowers/specs/2026-08-15-group-data-model-rebuild-design.md`
- 状态：六表最小 additive DDL（V117）和未接流量的 S/G/P/M/B 批量持久化底座已完成本地实现及真实 MySQL 8.4 验证；尚未回填、切流、迁移或部署

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
- [ ] 用 test1 报告量化同 JID 多 active legacy 行及 alias 级属性冲突
- [ ] 用户评审六表字段和迁移/切换方案
- [ ] 评审后另写实施计划；本记录不授权改表、部署或真实环境写入

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

当前代码新增 V117 六表建表迁移，以及尚未接入生产事件入口的 S/G/P/M/B 批量持久化底座；没有修改旧表，也没有回填或切流。后续数据回填只能由可重入 migration runner 执行。旧事实表在切换、观察、备份和恢复演练完成，并再次取得用户确认后才允许独立删除。

### API / 前端

现有 API 和前端业务无变化；前端只删除已指定的行展开详情。若只能改变 key，追加改动必须集中在 row-key/request-key，不得解析 G/I 类型或新增业务判断。

### Kafka / 协议

不新增 topic、consumer group、payload 字段、OpenAPI schema 或协议部署步骤。现有 v1 topic/outbox/DLT/Android JSONL 必须继续能由新后端解析。

## 验证记录

本次新增 V117 六表最小建表迁移和迁移合同测试；只在 Testcontainers 临时 MySQL 中执行了 DDL，没有对 test1、其他远程数据库或协议环境执行 DDL/DML、迁移或部署。

本轮前端验证：

- `pnpm exec node --import tsx --test src/views/group/list/components/GroupListTable.test.ts`：4 个测试通过，包含展开行不存在门禁。
- `pnpm typecheck`：通过。

此前聚焦验证：

- `mvn -q -Dtest='GroupLinkControlledAdminMapperInMemoryTest,GroupMembershipCountSemanticsMapperH2Test,MarketingMembershipSendPolicyTest,AccountGroupMembershipSnapshotServiceImplTest,GroupDataModelFoundationMigrationSqlTest,FlywayMigrationVersionContractTest,FlywayMigrationSqlContractTest' test`：通过，0 失败。
- `DOCKER_HOST=... mvn -q -Dtest=GroupDataModelFoundationMigrationMysqlTest test`：真实 MySQL 8.4.8，2 个测试通过，0 失败、0 错误、0 跳过。
- `DOCKER_HOST=... mvn -q -Dtest=AccountGroupCurrentSnapshotPersistenceMySqlTest test`：真实 MySQL 8.4.8，3 个测试通过，覆盖 400 群 SQL 预算、空完整快照和重放/锁序，0 失败、0 错误、0 跳过。
- `mvn -q -Dtest=AccountGroupMembershipSnapshotServiceImplTest test`：12 个测试通过，0 失败、0 错误、0 跳过。
- `mvn -q -Dtest=AccountGroupSyncMySqlConcurrencyTest test`：本机无 Docker socket，5 个测试全部 skipped；不计为 MySQL 门禁通过。

只读证据：

- test1 MySQL 8.4.8，Flyway 到 V116。
- 约 1.1 万 group_link、4.7～5.1 万账号群关系、43.8～45 万成员当前数据。
- 当前默认 count/page 约 1.38 秒/1.23 秒；去掉无条件成员聚合的基础查询约 32 毫秒，简单 count 约 5 毫秒。
- 当前 400 群快照约 2400～3600 条 SQL；现有 MySQL 并发测试已记录 supremum 死锁和正确锁序。

## 部署

- 未部署。任何 test1 写入、部署或批量迁移前必须再次确认目标环境。

## 剩余门禁

- test1 多 alias/属性冲突报告决定 legacy handle 是纯 ID map 还是长期业务 handle。
- 六表字段、索引、迁移规则和业务 parity 评审通过。
- 生产事件 Adapter 接入时再确定账号/S 入口锁与有界外层锁冲突重试，并通过真实 RR 并发门禁；本轮持久化底座不接流量、不自带重试。
- 后端列表 API/DTO 与全业务 shadow diff 为 0；前端另验收 DOM 不再渲染展开区。
- 旧表 drop 必须单独发布、恢复演练并再次取得用户确认。
