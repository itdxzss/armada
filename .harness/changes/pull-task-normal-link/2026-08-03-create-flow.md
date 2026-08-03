# 普通群链接任务创建链路

日期：2026-08-03
分支：`feature/pull-task-normal-group-link`
提交范围：`c9706f4d..ff8bcb7f`（13 个 feat/fix 提交）
设计文档：`docs/superpowers/specs/2026-08-03-pull-task-normal-link-create-flow-design.md`
实施计划：`docs/superpowers/plans/2026-08-03-pull-task-normal-link-create-flow.md`
对应 PRD 拆分任务：BE-01、BE-04、BE-05、BE-06

## 变更概述

实现普通群链接拉群任务的**创建链路**：结构化创建合同与参数校验、群链接逐行解析与公开邀请页真实预检、TXT 料子解析、链接与 TXT 的随机不放回匹配与草稿冻结，以及 `DRAFT → WAIT_START` 的提交。

创建页的中间态用一条 `pull_task` 草稿行承载（ADR-0007），执行行与料子成员全程挂在草稿任务下。链接解析、TXT 解析、随机匹配三块做成无 Spring 依赖的纯函数；外部 HTTP 预检在事务外完成，事务只包裹数据库写入。

**本切片不含**：执行器与调度（EX-\*）、前端创建页（FE-\*）、从群组列表分组选择群链接、群资料/权限设置与归档分组（PRD 明确为后续阶段）。创建完成的任务停在 `WAIT_START`，**还不能真正拉人进群**。

## 影响模块

### `task` 域（新增）

| 类 | 职责 |
|---|---|
| `service/PullTaskMaterialTxtParser` | TXT → 去重号码 + 逐行错误明细（纯函数） |
| `service/PullTaskLinkMatcher` | 不放回一对一随机匹配（纯函数） |
| `service/PullTaskLinkProbeService` | 链接逐行六态判定 + 16 并发有界抓取 |
| `service/PullTaskStandardDraftService` + `impl` | 草稿编排：`plan` / `current` / `removeRow` / `clear` |
| `service/impl/PullTaskStandardDraftWriter` | 草稿的四种事务写操作 |
| `service/PullTaskStandardCreateService` + `impl` | 提交冻结，单事务 `DRAFT → WAIT_START` |
| `config/PullTaskLinkProbeExecutorConfig` | 预检有界线程池 |
| `controller/PullTaskStandardController` | 五个端点 |
| `model/enums/PullTaskStandardLinkLineStatus` | 链接逐行六态 |
| `model/dto/PullTaskStandardCreateDTO` | 提交入参 |
| `model/vo/PullTaskStandard*VO` | 6 个出参 record |

### `task` 域（修改）

- `PullTask` 实体补 `createdBy`、`configJson` 两个字段（映射既有列，非新列）。
- `PullTaskMapper` 新增 `insertDraft` / `selectLatestDraftByCreator` / `submitDraft`；**`selectLifecycle` 的投影扩宽**，增加 `group_count` / `expected_pull_count` / `created_by` / `remark`。
- `PullTaskGroupExecutionMapper` 新增 `deleteDraftRow` / `selectOccupiedLinks` / `updateGroupLinkId`。
- `PullTaskMaterialMemberMapper` 新增 `deleteByExecution`。

### `group` 域（修改，为支撑本切片）

- `GroupInvitePageFetcher` 新增 `probe` 方法，返回新增的 `GroupInvitePageProbe(metadata, reachable)`。原 `fetch` 保持为接口抽象方法，实现收敛为 `probe(url).metadata()`——是委托而非并行路径，既有调用方 `GroupLinkPrecheckServiceImpl` 行为不变。
- `GroupLinkRegistryService` 新增 `registerPullTaskTargets`，返回归一化链接到 `group_link.id` 的映射。`registerOne` 的 `origin` 提为参数并改掉唯一既有调用点，未复制第二套登记逻辑。

### 测试基座（修改）

`PullTaskNormalLinkSchema` / `PullTaskNormalLinkH2Support` 从包级私有提升为 `public`，供 `com.armada.task.service` 包的服务集成测试复用。纯测试代码改动。

## 数据库变更

**无 schema 变更，无 Flyway 迁移。**

本切片全部落在数据层切片（`V090`，见 `2026-08-02-pull-task-normal-link-data-model-design.md`）已建好的六张表上。`PullTask` 实体新增的两个字段映射的是 `pull_task` 既有列。

权限点 `tenant:pull_task:create` 已在 `V078__pull_task_and_channel_stats.sql` 中存在，无需补种子数据。

## API 变更

新增五个端点，全部在 `/api/pull-tasks/standard` 下，权限 `tenant:pull_task:view`（类级）+ `tenant:pull_task:create`（写操作）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/draft/plan` | multipart：`linksText`（每次全量携带）+ `files[]`；增量追加匹配 |
| GET | `/draft` | 回读当前用户草稿 |
| DELETE | `/draft/rows/{rowId}` | 单行移除，链接与 TXT 一并丢弃 |
| DELETE | `/draft` | 清除全部执行行，保留草稿任务行 |
| POST | （根路径） | 提交冻结，`DRAFT → WAIT_START` |

**旧 `POST /api/pull-tasks`（`OLD_LINK` / `CREATE_NEW`，只存不透明 JSON 快照）行为完全不变**，与新路径隔离。前端 `wheel-saas-pure-web/src/api/pull-task.ts:493` 仍在调它；等 FE 切片切换完成后由单独一个变更下线。

新任务的 `pull_task.mode` 取新值 `NORMAL_LINK`（`VARCHAR(32)`，加值不需要迁移）。**V078 的列注释 `OLD_LINK老群链接 CREATE_NEW自建群` 因此过时**，未单独发迁移改注释。

## Redis 变更

无。

## 关键约束

1. **邀请页预检必须在事务外。** 单次最坏约 40 秒外部 HTTP，事务包住会让数据库连接被网络阻塞占用，并发创建时拖垮连接池。为此 `PullTaskStandardDraftServiceImpl` **刻意不标 `@Transactional`**，写操作全部委托给独立 bean `PullTaskStandardDraftWriter`——Spring 自调用不走代理，事务边界只能落在另一个 bean 上。
2. **占用冲突整单回滚。** 提交时 `freezeDraftRows` 撞 `uq_pull_task_execution_link_occupancy` 唯一键，捕获 `DuplicateKeyException` 转 `BusinessException(CONFLICT)`，整个事务回滚，草稿完整保留可继续编辑。不采用"跳过冲突行、其余继续"——PRD 硬要求落库计划与创建页所见完全一致，偷偷少一行用户无法察觉。
3. **重复提交幂等。** 提交入口按主键直查任务行，`WAIT_START` 走幂等分支并**在写 `pull_task_standard_setting` 之前返回**，否则第二次提交会撞该表主键。不报错、不建第二个任务。
4. **链接三态预检。** 格式非法 / 批内重复 / 公开页明确无群资料 → 拒绝入池；抓取超时或网络错误 → `PROBE_INCOMPLETE` 仍入池，由启动时重新校验兜底。避免把自身网络抖动当成用户链接失效。
5. **链接文本不落库。** 每次请求全量携带，服务端用「有效链接 − 已成行链接」得到剩余池。代价是刷新页面后未成行的链接需重新粘贴，前端应用 `sessionStorage` 缓解。
6. **剩余未匹配 TXT 不落库。** 执行行两侧列均 `NOT NULL`，草稿期不存在半成品行；剩余链接不足时多出的文件当场拒绝并回 `ignoredFileCount`，由前端保留 `File` 对象在用户补粘链接后重发。
7. **`source_file_index` 取与 `seq` 相同的值。** `uq_pull_task_execution_file (tenant_id, task_id, source_file_index)` 要求任务内唯一，而增量上传没有全局上传序号可用。
8. **单行移除先删料子再删执行行。** 执行行删除带 `execution_status = 0` 守卫，返回 0 说明已冻结，抛业务异常让整笔回滚，料子随之恢复。
9. **每用户一条草稿。** 同用户双击或多标签页可能漏出第二条，用 `selectLatestDraftByCreator`（取最新一条）容忍，未为此加唯一索引迁移——遗留草稿是每用户常量级。
10. **数值口径**：单次有效链接 ≤200、抓取并发 16、单次上传文件 ≤50、单文件 ≤2MB、单文件 ≤20000 行、号码 7–15 位纯数字。

## 验证

各任务 TDD（先红后绿），H2 MySQL 模式加载真实 Mapper XML 与生产 `MyBatisConfig` 租户拦截器：

| 任务 | 测试 | 结果 |
|---|---|---|
| TXT 解析器 | `PullTaskMaterialTxtParserTest` | 8/8 |
| 随机匹配器 | `PullTaskLinkMatcherTest` | 9/9（含变异验证：禁用 shuffle 后新测试单独失败） |
| 邀请页 probe | `HttpGroupInvitePageFetcherProbeTest` + 2 个既有回归 | 11/11 |
| 六态判定 | `PullTaskLinkProbeServiceTest` | 11/11 |
| 草稿任务行 Mapper | `PullTaskDraftMapperInMemoryTest` + 3 个既有回归 | 22/22 |
| 草稿编辑 Mapper | `PullTaskDraftEditMapperInMemoryTest` + 2 个既有回归 | 27/27 |
| 群入口登记 | `GroupLinkRegistryPullTaskTargetTest` + 1 个既有回归 | 8/8 |
| 草稿事务写入 | `PullTaskStandardDraftWriterTest` | 8/8（含验证事务代理生效的回滚用例） |
| 草稿读编排 | `PullTaskStandardDraftServiceReadEditTest` | 7/7 |
| 增量匹配追加 | `PullTaskStandardDraftServicePlanTest` + 读编排回归 | 18/18 |
| 提交冻结 | `PullTaskStandardCreateServiceTest` + Mapper 回归 | 14/14 |
| Controller | `PullTaskStandardControllerTest` | 7/7 |

**全量回归**：`mvn test -Dtest='!*DbTest,!GroupLinkRegistryServiceImplTest,!GroupCreationMarketingTaskServiceImplTest' -DfailIfNoTests=false`
→ **1645 run / 3 failures / 0 errors / 7 skipped**。

3 条失败为**预存在且与本切片无关**，已双向核实：
- `HistoricalGroupPullWorkerImplTest` 2 条 —— Mockito 参数不匹配于 `GroupParticipantPort.updateParticipants`；该类对本切片改动的任何类零依赖。
- `GroupCreationMarketingTaskMapperSqlShapeTest` 1 条 —— 断言 `group_creation_marketing_item` 的 SQL 形状，营销表，本切片未触及。
- 另在基线提交 `c9706f4d` 的临时 worktree 上重现了同样 3 条失败。

**真库 DbTest 未执行**：全仓 75 个 `extends DbTestBase` 的测试需要 `armada-api/.env` 注入真实 MySQL 凭据，本机无该文件时它们挂死而非快速失败。按 AGENTS.md，真库验证走 `armada-api/dbtest.sh`，属可选补充而非本地门禁。其中 `GroupLinkRegistryServiceImplTest` 与 `GroupCreationMarketingTaskServiceImplTest` 类名不带 `DbTest` 后缀，通配符排不掉，必须点名排除。

## 回滚方案

纯代码变更，无 schema 变更、无数据迁移。回滚 = 回退 `c9706f4d..ff8bcb7f` 区间的提交。旧 `POST /api/pull-tasks` 未被改动，回滚后前端现有创建页不受影响。

## 遗留

1. **单次粘贴链接量级待确认。** 现按几十条量级设计：上限 200 条、16 并发、同步等待最坏约 40 秒。若实际运营经常一次粘贴上千条，该同步模型不成立，需改为后台异步检测 + 前端轮询进度，接口形态随之变化。**实现前未取得确认，按假设推进。**
2. **审计口径偏差。** 设计文档 6.6 要求写操作记录 `requestId`，但全仓没有 requestId 基础设施（无 MDC 过滤器、无 trace id 透传）。本切片按现有能力记录租户、操作者、动作与结果，未为此引入新的链路追踪机制。
3. **未做逐任务代码评审。** 按用户指示，本切片只保留 TDD 与全量回归，取消了逐任务评审与最终整支评审。Task 1 与 Task 2 在取消前完成了评审（各 2 条 Minor 已记录在 SDD ledger）。集成缝的设计正确性主要靠 H2 断言兜底，前后端联调时需重点验证。
4. **Task 12 未执行显式红阶段。** 实现者在首次 `mvn` 调用前已同时写好测试与 Controller，缺少"先看到测试失败"的证据。
5. **`V078` 列注释过时**（`mode` 列的 `OLD_LINK老群链接 CREATE_NEW自建群`），未单独发迁移修正。
6. **`registerPullTaskTargets` 的 `origin` 变更**未覆盖真库验证——`GroupLinkRegistryServiceImplTest` 是真库测试，本次未执行；单元测试已覆盖复用、复活、新建三条分支。

## 下一步

按 PRD 拆分，下一个自然切片是 **EX-01**（执行行 claim + 检查点状态机 + 并发群数为 1 的调度骨架）。M1「最小真实闭环」的验收门槛需要 EX-01 ~ EX-08 全部完成后才能达成。
