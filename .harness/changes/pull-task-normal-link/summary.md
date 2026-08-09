# 变更记录：普通群链接拉群任务 —— 数据层（M1）

## 2026-08-09 增量：V107 拉人波次与粘性拉手

- 新增 `pull_task_pull_wave`，在数据库层保证每条执行行最多一个派发中/收集中波次。
- `pull_task_group_execution` 新增活动波次、活动拉手和拉手分配代际字段。
- `pull_task_pull_call` 与逐号码 attempt 台账新增波次和分配代际关联；计划态拉手允许为空。
- V107 只新增/放宽结构，不回填历史数据；历史未完成调用由运行时兼容逻辑接管。
- 实施与验证证据持续记录在
  `.harness/changes/2026-08-09-normal-link-pull-wave-dispatch.md`。

- 日期 / 分支 / worktree: 2026-08-03 / `feature/pull-task-normal-group-link` / `/mnt/d/ideaProject/armada`
- 需求来源: `docs/superpowers/specs/2026-08-02-pull-task-normal-link-data-model-design.md`；
  `docs/adr/0001-limit-pull-task-v2-to-group-link-mode.md` ~ `0009-defer-manual-operation-audit-log.md`；
  实施计划见 `.superpowers/sdd/2026-08-02-pull-task-normal-link-data-layer/`（Task 1–10）。
- 状态: 数据层已完成，等待用户对可选真库验证授权。

## 目标（一句话）

为"普通群链接"拉群任务交付可承载真实执行闭环的数据层：6 张新表 + `pull_task` 生命周期列，
供后续 Service 层任务从这里的 Mapper 接口继续（本次不做 Service/Controller/调度器/前端）。

## 背景

设计源自 `2026-08-02-pull-task-normal-link-data-model-design.md`：在“新建拉群任务”的三个新入口
（新群模式、群链接模式、速拉模式）中，本期只落地群链接模式下的“普通群链接”版本（ADR-0001、
ADR-0002），且必须形成真实执行闭环而非停留在配置快照（ADR-0003）。第一阶段只支持自定义粘贴群
链接（ADR-0004），群链接与 TXT 料子文件按 ADR-0005 随机、不放回、一对一匹配；管理/拉手/站台的
进群与拉手链路由 ADR-0006 定义。三项数据模型结构决策——草稿任务替代独立预览计划表（ADR-0007）、
拉手跨任务互斥用部分唯一索引而非租约表（ADR-0008）、本期不做人工操作审计表（ADR-0009）——
在设计文档定稿前已单独评审并接受。

## 改动清单

- **`V090__pull_task_normal_link_execution.sql`**：`pull_task` 增补 3 列
  (`started_at` BIGINT、`finished_at` BIGINT、`version` INT NOT NULL DEFAULT 1)；
  新建 6 张表：`pull_task_standard_setting`（1:1 冻结执行配置）、
  `pull_task_group_execution`（1:N，群链接↔TXT 一对一冻结配对）、
  `pull_task_material_member`（1:N，TXT 号码与逐号码入群/提权结果）、
  `pull_task_group_account`（1:N，管理/拉手/站台角色、在群状态、拉手跨任务占用）、
  `pull_task_account_action`（1:N，加好友/邀请入群/踩链接入群的账号动作）、
  `pull_task_pull_call`（1:N，单次批量加成员协议调用）。全部 `ADD COLUMN`/`CREATE TABLE`
  经 `information_schema`/`IF NOT EXISTS` 守卫幂等；`normalized_link`、`invite_code`、
  `group_jid`、`normalized_phone`、`account_phone`、`command_id`、`idempotency_key` 等参与唯一键
  或精确匹配的字符串列显式声明 `CHARACTER SET ascii COLLATE ascii_bin`，覆盖表默认的
  `utf8mb4_0900_ai_ci`（大小写不敏感）。两个生成列
  (`pull_task_group_execution.link_occupancy_key`、`pull_task_group_account.occupancy_key`)
  均为 `CASE WHEN <有效条件> THEN <值> ELSE NULL END`，else 分支为 NULL（与 V089/V005 同款写法）。
- **六套实体 / Mapper / XML**：`PullTaskStandardSetting`、`PullTaskGroupExecution`、
  `PullTaskMaterialMember`、`PullTaskGroupAccount`、`PullTaskAccountAction`、`PullTaskPullCall`，
  各配 Mapper 接口 + XML + H2 内存测试（Task 4–9）。
- **11 个新增枚举**（`git log --diff-filter=A` 核对，非估算）：`PullTaskExecutionStatus`、
  `PullTaskExecutionStage`、`PullTaskWaitResourceType`、`PullTaskMaterialPullStatus`、
  `PullTaskMaterialAdminStatus`、`PullTaskGroupAccountRole`、`PullTaskGroupAccountAvailability`、
  `PullTaskGroupAccountMembershipStatus`、`PullTaskAccountActionType`、`PullTaskActionStatus`、
  `PullTaskPullCallStatus`。
- **`pull_task` 生命周期改动**（commit `cb1f75bc`）：
  - 列表/统计过滤新增 `AND NOT (task_type = 'STANDARD' AND status = 'DRAFT')`
    （ADR-0007：草稿只在创建页可见，不进入任务列表/看板；`GROUP_MARKETING` 既有的 `DRAFT`
    可见性不受影响，仍走原有 `OR` 分支）。
  - 新增 `updateStatusWithVersion`：状态前置校验 + 乐观锁版本号在数据库层复核
    (`WHERE id=? AND status=#{fromStatus} AND version=#{expectedVersion}`)，
    作为 ADR-0009 "人工操作幂等改由状态前置校验 + 乐观锁承担" 的落点，重复提交返回 0 行
    而非产生第二次副作用。
  - `PullTask` 实体补 `startedAt`/`finishedAt`/`version` 字段；新增 `selectLifecycle` 读取路径。

## 规范例外

`pull_task_group_execution` 有 33 列，超过 `.harness/rules/数据模型规范.md` 的 ~30 列经验阈值。

理由：本表严格建模**一个聚合**——一条"群链接 ↔ 一个 TXT 文件"的冻结配对，是本域唯一可独立调度
的执行单元。列的构成是：主键与租户/任务归属（4）、匹配结果本身（群链接、邀请码、行号、JID、
文件元数据共 7）、TXT 解析统计（4）、执行状态机（状态、阶段、人工暂停、资源等待类型、原因码/
描述共 6）、调度游标与锁（管理/拉手轮询游标、下次可调度时间、锁持有者、锁过期共 5）、乐观锁与
时间戳（版本、启动/完成/最近业务动作、创建/更新共 6）、以及 1 个用于跨任务占用互斥的生成列。
这些字段没有一个可以独立于"这条群链接执行行"存在——TXT 元数据与其绑定的链接严格 1:1、共享
同一生命周期（链接失效则该 TXT 的匹配作废，执行行终态则 TXT 统计不再更新），拆出一张
`pull_task_group_execution_material` 之类的影子表只会让"读一行执行状态"变成强制 JOIN，
且没有第二个访问路径需要独立于执行行读这些列。因此判定为"一个聚合内的必要列"而非 grab-bag。

## 验证（evidence-before-done）

**Step 1：全量回归**

环境说明：本沙箱无可达 MySQL（无 docker、无本地 3306 监听、无 `armada-api/.env`）。默认
`mvn test` 会按 Surefire 默认 include 规则一并拉起全部 `extends DbTestBase` 的类（75 个，
含本次新增的 1 个），这些类通过 `@SpringBootTest` 启动完整 Spring 上下文连接
`jdbc:mysql://localhost:3306/armada`；在无库环境下 HikariCP 不会快速失败，会反复
`HikariPool-1 - Starting...` 重试（实测同一个类连续重试 13+ 分钟未失败即被人工终止），
这与 Task 1–9 全程只用 `-Dtest=<具体类>` 跑测的做法一致，说明"直接跑 `mvn test`"在本沙箱
从未真正跑通过。为拿到真实、可终止的全量回归信号，改用：

```
cd armada-api && mvn -q test -Dtest='!*DbTest,!GroupLinkRegistryServiceImplTest,!GroupCreationMarketingTaskServiceImplTest' -DfailIfNoTests=false
EXIT_CODE=1
```

（排除模式覆盖全部 `extends DbTestBase` 的类：73 个以 `DbTest` 结尾 + 2 个例外
`GroupLinkRegistryServiceImplTest`/`GroupCreationMarketingTaskServiceImplTest`；本次新增的
`PullTaskNormalLinkCollationDbTest` 同样被排除，因此**没有连接过真实数据库**。）

实际结果：`Tests run: 1551, Failures: 3, Errors: 0, Skipped: 7`。

- **7 个 Skipped**：`AccountGroupSyncMySqlConcurrencyTest`（5 个方法）与
  `PullTaskGroupMarketingOccupancyMySqlTest`（2 个方法），surefire 报告里的跳过消息均为
  `"disabledWithoutDocker is true and Docker is not available"`——已核实确系 Docker 不可用触发
  的既有跳过，与本次改动无关。
- **3 个 Failures**：`HistoricalGroupPullWorkerImplTest` 2 个方法（Mockito 参数捕获期望的方法
  签名与实现不一致）+ `GroupCreationMarketingTaskMapperSqlShapeTest` 1 个方法（断言字符串
  `#{pendingStatus}` 与 XML 实际的 `#{update.pendingStatus}` 不匹配）。这三个失败与本计划的
  普通群链接数据层**无关**（分属历史群拉人与拉群营销业务域，本计划未触碰这两个类所在的任何
  文件）。已用 `git worktree` 检出本计划开工前一提交（`741e6ca3`，`b6c530b7` 的父提交）单独
  重跑这两个类，复现**完全相同**的 3 个失败、相同断言信息，证明是本计划开工前就存在的既有
  失败，非本次引入的回归：

  ```
  cd <worktree@741e6ca3>/armada-api && mvn -q -Dtest='HistoricalGroupPullWorkerImplTest,GroupCreationMarketingTaskMapperSqlShapeTest' -DfailIfNoTests=false test
  EXIT_CODE=1
  Tests run: 7, Failures: 3, Errors: 0, Skipped: 0
  ```

- 本计划直接产出的测试类全部通过：`FlywayMigrationHistoryContractTest`(1)、
  `FlywayMigrationSqlContractTest`(1)、`FlywayMigrationVersionContractTest`(2)、
  `PullTaskNormalLinkMigrationSqlTest`(7)、`PullTaskNormalLinkSchemaSelfTest`(7)、
  `PullTaskMapperInMemoryTest`(5，含 Task 3 补的三列)、
  `PullTaskLifecycleMapperInMemoryTest`(6)、`PullTaskStandardSettingMapperInMemoryTest`(3)、
  `PullTaskGroupExecutionMapperInMemoryTest`(11)、`PullTaskMaterialMemberMapperInMemoryTest`(8)、
  `PullTaskGroupAccountMapperInMemoryTest`(11)、`PullTaskAccountActionMapperInMemoryTest`(7)、
  `PullTaskPullCallMapperInMemoryTest`(7)，全部 `Failures: 0, Errors: 0, Skipped: 0`。
- `EpochMillisSchemaDbTest`/`AccountSchemaDbTest`（brief 点名关注的全库列类型扫描测试）
  均 `extends DbTestBase`，本沙箱无法连库执行，因此**未获得这两个测试的真实断言结果**；
  但 V090 的 DDL（`.harness/changes/pull-task-normal-link/db-migrations.sql`）人工核对显示
  新表全部时间列（`created_at`/`updated_at`/`started_at`/`finished_at`/`joined_at`/
  `occupied_at`/`released_at`/`submitted_at`/`result_at`/`pull_result_at`/`admin_result_at`/
  `last_business_executed_at`/`next_run_at`/`lock_expires_at`/`cooldown_until`/
  `unavailable...` 等）均为 `BIGINT`，与既有约定一致；这两个测试需要在可连库环境重跑以拿到
  真实断言证据。

**Step 2：数据模型文档生成器**

```
cd .harness/wiki && python3 gen_datamodel.py
FileNotFoundError: [Errno 2] No such file or directory: '/tmp/wheel_tables.tsv'
```

生成器需要真库 `information_schema` 转储出的 `/tmp/wheel_{tables,columns,indexes}.tsv`，本沙箱
无法连库产生这三个文件。**未重跑，未手工编辑 `数据模型.md`**（规范明令禁手改）。
`数据模型.md` 仍待在可连库环境重新生成，届时应出现本次 6 张新表和 `pull_task` 的 3 个新列。

**Step 3：可选真库补充测试 —— 已写，未执行**

新增 `armada-api/src/test/java/com/armada/task/PullTaskNormalLinkCollationDbTest.java`：
唯一能证明 `normalized_link` 的 `ascii_bin` 排序规则生效的测试——表默认
`utf8mb4_0900_ai_ci` 大小写不敏感，若漏声明 `ascii_bin` 会把仅大小写不同的两条邀请码判为
重复；H2 默认大小写敏感，这个缺陷在内存测试里会静默通过，因此只有真 MySQL 能暴露。

**本类没有被执行过**（既没有被 Step 1 的排除式全量回归跑到，也没有单独运行）。按
`AGENTS.md` 红线，真库操作前必须先确认目标环境；执行命令留给用户在确认环境后运行：

```
armada-api/dbtest.sh PullTaskNormalLinkCollationDbTest
```

编译验证（未执行测试，只验证编译）：

```
cd armada-api && mvn -q -DskipTests compile test-compile
EXIT_CODE=0
```

## 未覆盖

本次只交付数据层。以下均未接入，属于后续任务范围：

- Service 层：草稿生成/冻结、启动前复核、执行器状态机推进、幂等回调、检查点恢复。
- Controller 层：创建页粘贴链接解析、TXT 上传解析、任务/群组详情、补充管理员/拉手/站台。
- 调度器：`pull_task_group_execution.idx_pull_task_execution_dispatch` 索引已建，但无调度线程
  读取它；`claimDue`/`selectClaimed`/`releaseLock` 等跨租户 Mapper 方法尚无调用方。
- 协议编排：进群、加好友、邀请、批量拉人的真实协议调用均未接入，`pull_task_account_action`/
  `pull_task_pull_call` 只有可插入可查询的数据结构。
- 前端：创建页、任务看板、群组明细、补充资源弹窗均未改动。

M1（第一阶段最小真实执行闭环，ADR-0003）的闭环尚未打通——本次只是让闭环有地方存数据，
不代表闭环本身已可运行。

## 部署

- commit / 环境 / 部署后验证结果: 尚未提交到远程；未部署；未连接任何远程或真实数据库。

## 遗留 / 跟进

- `数据模型.md` 需在可连库环境重跑 `gen_datamodel.py`。
- `PullTaskNormalLinkCollationDbTest` 需用户确认目标环境后执行
  `armada-api/dbtest.sh PullTaskNormalLinkCollationDbTest`，验证邀请码大小写敏感排序规则。
- `EpochMillisSchemaDbTest`/`AccountSchemaDbTest` 类的全库列类型扫描断言需在可连库环境重跑，
  拿到覆盖本次新表的真实断言结果（当前只做了人工 DDL 核对）。
- 回滚：`.harness/changes/pull-task-normal-link/rollback.sql`（按依赖逆序 `DROP TABLE` 6 张新表 +
  `pull_task` 的 3 列）。**执行回滚后必须同时手工删除 `flyway_schema_history` 中
  `version='090'` 的那一行**，否则该库后续重新迁移到 V090 时会因 checksum 校验失败导致启动
  crash-loop。回滚前需按 `AGENTS.md` 红线单独确认目标环境。
- 设计文档 §6 一致性规则第 2/3/4/8/9 条（单文件单群完整校验、结果不重试的服务端实现、
  `UNKNOWN` 收敛、调度取行完整条件组合、汇总重算）需 Service 层参与，落在本计划之外。
