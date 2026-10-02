# 同群多账号进群与清理衔接异常分析

日期：2026-09-18。范围：用户确认的第二套环境 perf2 / armada_perf；只读分析，未改业务代码、配置、任务状态或执行 WhatsApp 操作。时间均为北京时间，日志原始时间为 UTC。

## 结论

截图对应任务 #109，名称 `111`，每群 2 个账号、26 群、52 条明细，开启设置管理员和清理退群。52 条都有 command_id、attempt_no >= 1，不能归因于“第二个账号没有下发”。

两个独立问题已由数据库和日志证实：

1. 多个群的两个账号已经进群并提权，但逐账号清理把另一个本次新管理员踢出；另有本次新管理员被选成下一条提权的操作账号，并被当作“原号”主动退群。缺少同群整批成功后统一清理的边界。
2. 3 条明细已发出 Android 进群命令，后端也收到 JOINED 回执，但回执处理发生反复数据库死锁，业务明细仍为 PENDING/SUBMITTED，原因空白。不能将其显示成“未下发”，也不能据此重新发起进群。

未发现“第一个账号提权后整个任务提前 DONE”的证据：#109 查询时仍 RUNNING；部署制品中的 markDoneWhenNoPending 会检查全部未完成进群、提权和清理明细。真正提前发生的是单账号清理，而非任务完成判定。

## 环境与证据边界

- perf2 后端 3.110.124.52，实际连接数据库 URL 校验为 `/armada_perf`，查询包装在 `START TRANSACTION READ ONLY` 与 `ROLLBACK` 中。
- 当前容器启动时间 2026-09-18 08:26:05 UTC，RestartCount=0。
- 容器 `/app/app.jar` 与磁盘 `/home/app/armada-deploy/armada-api/target/armada-api-deploy.jar` SHA-256 相同：`1abc308dc1a35b4c5c15b084f425e4312533970269ca8e9b4e9fa8ccc8ea5e92`。
- Flyway V201–V205 均成功；已从上述实际制品抽取 JoinTaskResultMapper、JoinTaskMapper、JoinTaskCleanupMapper。
- 任务发生于当前容器启动前，历史处理行为以持久化记录及当时日志为证，不能用当前启动时间证明历史代码版本。
- 本地主仓库分支 1.0.3-snapshot，存在其他会话大量未提交修改；分析期间也有并发修改。运行制品与本地类文件不完全相同；本地代码用于定位逻辑，不能整体视为已经发布。
- 本地明细 SQL 已出现 approval 子表查询，部署 SQL 尚没有该连接。审批相关后续改动不等于已经解决本次问题。
- 临时原始取证文件在 `/tmp/join-multi-account-analysis-20260918/`：details.tsv、facts.tsv、participants.tsv、runtime.txt、errors.log。原始文件含完整业务标识，正文仅展示尾号。

## 截图群组实证

以下五个群的两个账号均有入群 SUCCESS 和管理员 SUCCESS；当前成员表与清理记录相互印证。

| 群名 | 新账号结果 | 清理/离群证据 |
|---|---|---|
| Send off ( Anganavadi) | 尾号 3465 留群；6427 被踢出 | 明细 372 清理移除明细 371；成员退出来源 `business-remove:join-cleanup:372:0` |
| Compain study (NIOS +2) | 尾号 8714 留群；6714 主动退群 | 明细 373 的提权操作号是账号 2584，即明细 374 的新账号；退出来源 `business-leave:join-cleanup:373:0` |
| Works | 尾号 7307 留群；1241 被踢出 | 明细 376 清理移除明细 375；退出来源 `business-remove:join-cleanup:376:0` |
| CHOs😊 | 尾号 6770 留群；5979 被踢出 | 明细 378 清理移除明细 377；退出来源 `business-remove:join-cleanup:378:0` |
| Jago grahak jago | 尾号 7162 留群；0016 被踢出 | 明细 379 清理移除明细 380；退出来源 `business-remove:join-cleanup:379:0` |

Works 的业务时间线：

- 16:19:04.290：明细 376（7307）进群成功落库。
- 16:19:06.684：明细 375（1241）进群成功落库。
- 16:19:08.701：7307 提权成功事件时间。
- 16:19:12.705：1241 提权成功事件时间。
- 16:19:21.756：7307 的清理步骤确认移除 1241。

这些时间分别来自业务入群写入、提权事件和确认离群事实，不能把它们当成全部 WhatsApp 网络请求的精确起止时间，但足以证明两条任务均进群提权且后续清理移除同批账号。

其他同类样本：任务 #110（名称 `1111`）6 条全部进群 SUCCESS，其中明细 422 清理移除 421，424 清理移除 423；426 又把同批账号 2585 当作原号退群。这不是截图单群偶发现象。

## 三条已收到成功回执但未推进的明细

#109 查询快照：进群 SUCCESS 42、FAILED 7、PENDING 3；任务汇总 executed=49、success=21、failed=28、pending=3。任务汇总包含提权和清理结果，与单纯进群成功数不同。

| 明细 | 账号 ID / 尾号 | 命令 ID | 已确认事实 |
|---|---|---|---|
| 382 | 2576 / 4946 | cmd_c8e7cd6be55c4793a2a64d9e61b50e1f | 截图 Mech 500lvl 群的同链接第二条；JOINED 在 16:19:55、16:19:59、16:20:12 被消费日志记录 |
| 384 | 2574 / 2135 | cmd_863d4f44742d4af196ac850efa2e71d9 | JOINED 在 16:20:28、16:20:48、16:20:51、16:20:53 被消费日志记录 |
| 417 | 2541 / 0752 | cmd_3665724eece748188538da7b6c31755c | JOINED 在 16:20:11、16:20:14、16:20:22、16:20:24 被消费日志记录 |

三条 outbox 均 status=2（SENT）、retry_count=0、sent_at=16:19:00.679；明细仍 PENDING/SUBMITTED、reason 为空、group_jid 为空。SENT 本身只证明发布 Kafka；本例另有 JOINED 日志证明结果抵达后端。

相应消费线程紧接成功事件反复出现 `DeadlockLoserDataAccessException / Deadlock found when trying to get lock`，主要失败点为 `AccountGroupCurrentSnapshotMapper.xml`，栈包含 `JoinTaskResultServiceImpl.apply(...:159)`；同窗口也有 `JoinTaskMapper.xml` 汇总更新死锁（apply:175）及管理员回写死锁。进群成功状态与群事实写入处于同一事务，后续死锁会回滚前面的 SUCCESS 写入，使明细继续 SUBMITTED。

已确认：发送完成、JOINED 到达、消费事务死锁、明细未收敛。尚未确认：每条事件最终的 DLT 记录/offset、完整 InnoDB 等待环路。当前源码存在有限重试后进入 DLT 的配置，但本轮未读取 DLT，不将“已经进入死信”表述为已证实事实。

## 代码层面的缺口

1. `JoinTaskCleanupTransactions.completeAdminStage`：某条明细提权成功且开启开关就 enqueue，没有按同群所有计划账号判断是否达标。
2. `JoinTaskCleanupTargets.select`：只排除当前新号和原操作号，其余管理员都可进入 REMOVE，未排除本次同群计划保留的新号。
3. `JoinTaskAdminFacts.inspect` 与 `adminActorAccountId`：提权操作账号按当前可用管理员选取。当前新号可以成为下一条的操作账号；清理把“给本条提权的账号”直接当成“最终应退出的原号”，两个概念混用。单纯延后清理不能修复这一点。
4. `activateFirstPendingPerAccount` / `activateNextPending`：按账号串行、不同账号并发。同群多个账号目前并不实现用户要求的“账号1提权成功才进账号2”。原账号排期与同群阶段推进应明确区分。
5. 同群清理唯一在途键只能阻止同时清理，不能阻止两个账号先后各清理一次；需要同任务同群只有一次清理入口。
6. `JoinResultRowVO` 和前端明细尚未提供完整 dispatchState、nextExecuteAt、attemptNo、阻塞对象、命令/回执处理状态；空 reason 被展示为 `-`。成功历史与当前已离群也没有完整衔接。
7. 当前进群补偿扫描针对 outbox DEAD；SENT 后消费失败仍停留 SUBMITTED 的业务状态没有在本次样本中得到修复。

## 按本次要求建议的最小修复边界（尚未实施）

- 同群按计划顺序执行“进群 → 提权 → 下个账号”；不同群仍可并行，并继续遵守每账号进群间隔和在途限制。不能简单把全任务改成单线程。
- 同群计划中所有需要保留的不同账号完成进群和提权后，只登记一次群级清理；失败/等待审批/结果未确认不能冒充达标。
- 清理名单排除本次同群应保留的全部新账号。最终退出的原账号在群级流程中明确固定，不能直接使用最后一次提权的 actor；新账号即使代为提权，也不得因此被退群。
- 当前用户要求以本轮为准；既有清理文档允许清理其他同批新管理员的范围与本轮保留多个新管理员的要求冲突，需要同步修改规则和测试，不能仅改一个计数条件。
- 复用现有任务明细和清理子记录，优先评估条件推进、唯一约束和单次清理归属；本分析不预设新增表、分布式锁或新消息队列。清理记录现以 result_id 为主键，若需新增群级锚点/原号快照/唯一约束，走 Flyway。
- 未进群明细的 group_jid 当前为空，分组不能按空 JID 聚合；需要使用可靠的目标群标识，至少按本任务规范化链接保留计划关系，群 JID 确认后处理同群别名归并。
- 同账号的下一条放行与群级清理等待必须一起设计，避免“账号等待本群清理，本群等待该账号后续明细”的新循环等待。
- 回执侧缩小事务冲突范围、核对群事实与任务汇总锁顺序，增加有界且幂等的消费失败恢复；已收到 JOINED 的明细优先重放/修复结果收敛，不重新发送进群命令。死锁的具体 SQL 改法需结合等待环路与并发回归证据决定。
- API 保留进群、管理员、清理三个独立事实，补执行状态、原因、等待对象、最近变化时间；前端显示待执行、等待同群前序账号、等待账号其他任务/间隔、命令待发送、已发送等待回执、回执处理失败、等待审核、进群失败、已终止，以及进群后被本任务踢出/主动退群。
- 后端推导当前状态和原因；不能把 reason 为空一律解释成待执行或协议失败。JOIN_PENDING_APPROVAL 目前部署行为归入 FAILED，需要与正在进行的审批功能保持一致。
- 所有同群聚合、查询、推进与唯一性判断包含 tenant_id 和任务归属，不能跨任务/跨租户混合计算计划。

## 验证与恢复边界

应补：同群 2/3 账号顺序、每群仅一次清理、清理保留全部目标账号、同批新号代为提权不被退群、前序失败/审批/结果未知、跨群同账号排期、重复回调、多实例同时末条完成、进程重启、死锁后原事件收敛、租户隔离、明细文案。SQL 与事务用真实 Mapper 验证；H2 不足以证明 InnoDB 死锁消除，需隔离的 MySQL 并发验证。

发布与历史数据修复分开。代码回退不能恢复已经踢出/退出的账号，不能直接重跑整个旧任务，否则可能再次清理。既有异常任务需要先按当前在群事实逐条制定恢复方案。

本轮只完成分析取证，未修改/重试/终止任务，未重放消息，未部署，未宣称业务验收通过。

## 追加：FOR UPDATE 与死锁来源核查

针对用户追问，继续只读核对 perf2 部署 JAR、Git 基线及原始异常。没有新增锁、修改代码或修改数据库权限。

### 已上线代码确有新增显式锁

以下两个 Mapper 不在当前 HEAD 基线中，是新增管理员/清理功能文件；实际部署 JAR 中均已存在：

```sql
-- JoinTaskAdminMapper.lock
SELECT * FROM join_task_result WHERE id = #{id} FOR UPDATE;
-- JoinTaskCleanupMapper.lock
SELECT * FROM join_task_cleanup WHERE result_id = #{resultId} FOR UPDATE;
```

管理员的 claim、observe、apply 都调用前者。清理的 claim、succeeded、failed 先锁进群明细，再锁清理记录。管理员回执仍在同一事务中写群成员事实、提权状态及登记清理；清理确认成功时也在同一事务中写群退出事实，再推进阶段和刷新任务汇总。因此这些锁不是读取后立即释放，而会覆盖后续多表操作。

同时，普通进群回执原有 `selectSubmittedForUpdate` 在部署 JAR 中已改成普通 `selectSubmitted`；进群派发保留 `selectDueForUpdate ... FOR UPDATE SKIP LOCKED`。不能笼统声称“所有进群回执都新增了 FOR UPDATE”。

群模型 Mapper 在 HEAD 基线含 9 个 FOR UPDATE 查询，当前部署制品保留 5 个：selectControlledExistingAfterGroupLock、selectContextForUpdateByTenant、selectExistingAfterGroupLock、selectGroupIds、selectParticipantSnapshotVersionForUpdate。已移除的是单账号现状读取、按主键群读取、旧入口读取、成员身份读取的 4 处。保留的联合锁查询跨 wa_group、wa_group_participant、wa_account_group_binding，属于旧有锁逻辑；不能把所有群锁都说成本次新加。

### 事故日志证实的报错位置

对 16:19:50–16:21:20 窗口提取的异常块去重分析（计数是异常块，不是独立业务任务数）：

- 2 个异常块：`JoinTaskMapper.refreshCounters` 的 `UPDATE join_task SET ... (SELECT COUNT(*) FROM join_task_result LEFT JOIN join_task_cleanup ...)`，调用栈为进群结果 apply:175。
- 33 个异常块：`AccountGroupCurrentSnapshotMapper.upsertParticipantFacts` 的 `INSERT INTO wa_group_participant ... ON DUPLICATE KEY UPDATE`。调用链涉及自身进群结果、普通进群通知、成员观察/角色及离群事件。
- 3 条未收敛 JOINED 回执的对应消费线程，在成员 UPSERT 时反复触发死锁；管理员回写也有相同 SQL 的死锁。并非错误日志直接报在一条 SELECT FOR UPDATE 上。

当前调用链显示这些业务入口会在同一事务内叠加明细更新/显式行锁、群成员 UPSERT、账号绑定更新、任务汇总等操作。多个入口同时写相同成员/索引，汇总又读取整个任务的明细集合，构成明确的竞争范围。**但具体哪两个事务、哪个索引与锁模式形成哪一条等待环路，不能仅凭受害语句和 Java 调用栈定案。**

UPDATE 与 ON DUPLICATE KEY UPDATE 自身也会获取 InnoDB 锁，移除显式 FOR UPDATE 不代表整个事务无锁；机制参照 [MySQL 8.4 锁说明](https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html)。历史 pull_task 跨表汇总曾出现类似模式，只能用作排查线索，本轮没有把另一事故的等待环路冒充此次证据。

### 当前诊断限制

- 只读核验数据库版本为 MySQL 8.4.8，新诊断连接隔离级别 REPEATABLE-READ；不能据此断言每个业务方法都以 RR 运行，Spring 方法可能覆盖隔离级别。
- `innodb_print_all_deadlocks=0`，未改参数。
- `SHOW ENGINE INNODB STATUS` 返回 ERROR 1227，缺少 PROCESS 权限。
- 查询 `performance_schema.events_statements_history_long` 返回 ERROR 1142，缺少该表 SELECT 权限；同批后续 data_lock_waits 查询未执行，不将其描述成查过无记录。
- 现有部署备份没有找到对应事故分钟的前一份 JAR；当前制品 SQL 与事故异常 SQL可交叉核对，但尚未完整还原事故版本全部字节码。

结论：已确认管理员/清理功能又引入了 FOR UPDATE，也已确认两类具体死锁受害 SQL；尚不能声称已取得完整 InnoDB 等待环路，更不能证明简单删除某一处锁即可消除全部死锁。完整定案还需要具有诊断权限的连接及仍可取得的死锁报告，或在隔离 MySQL 中复现相同事务并记录实际等待关系。此阶段不再叠加锁或用无限重试替代根因修复。

## 追加：同版本 MySQL 已复现任务汇总死锁环路

2026-09-18，在独立、无网络的本机 MySQL **8.4.8** 容器中，使用 perf2 `SHOW CREATE TABLE` 返回的 `join_task`、`join_task_result`、`join_task_cleanup` DDL，以及事故日志中的完整 `refreshCounters` SQL，构造两个合成明细复现。未使用真实账号数据，未连接远程库进行实验。

两个事务均只执行“更新自己的一条结果 → refreshCounters → 提交”。整个实验没有 `SELECT FOR UPDATE`。事务隔离级别为 REPEATABLE READ。

| 事务 | 已持有 | 正在申请 | 被谁阻塞 |
| --- | --- | --- | --- |
| A，trx 1843 | `join_task PRIMARY(109)` 的 X 行锁；自身明细 371 的 X 行锁 | 统计子查询读取 `join_task_result PRIMARY(372)` 的 S 行锁 | B |
| B，trx 1842 | `join_task_result PRIMARY(372)` 的 X 行锁 | `UPDATE join_task` 申请 `PRIMARY(109)` 的 X 行锁 | A |

因此环路是：**A 持主表 109 → 等 B 的明细 372；B 持明细 372 → 等 A 的主表 109**。8.4.8 InnoDB 原始报告记录 `WE ROLL BACK TRANSACTION (2)`，B 返回 `ERROR 1213 (40001)`，A 成功提交。报告时间 09:24:10 UTC。这些事务号、行号属于本机合成实验，不能当成线上事故事务号。

仅将同样实验改为 READ COMMITTED 后，两个事务均正常提交。本次对照证明隔离级别对这一条环路有影响，不代表 RC 能消除其他表或索引上的死锁。第二次 `SHOW ENGINE INNODB STATUS` 仍显示第一次 RR 的历史报告；它不是 RC 又发生了一次死锁。

证据文件（本机临时目录）：

- `/private/tmp/join-deadlock-probe-20260918/task_probe_848.py`：复现脚本。
- `/private/tmp/join-deadlock-probe-20260918/task-probe-8.4.8-result.txt`：两种隔离级别的执行结果及持锁快照。
- `/private/tmp/join-deadlock-probe-20260918/join_probe_repeatable-8.4.8-innodb.txt`：完整 InnoDB 死锁报告。

**适用边界**：这是事故 SQL、线上 DDL、同数据库版本的可复现环路，还不是 RDS 事故当时的完整原始环路。当前工作区 `JoinTaskResultServiceImpl.apply` 已标记 READ_COMMITTED；不能将本机 RR 结果直接归因于当前代码，也不能仅凭新诊断连接默认 RR 推断事故业务事务的隔离级别。事故时旧 JAR、当时事务配置和线上 InnoDB 报告仍需交叉确认。

成员表 `upsertParticipantFacts` 的 33 个线上异常块仍未拿到对端事务及完整索引锁信息，保持未定案，不将上述主表/明细环路扩张为全部成员 UPSERT 异常的解释。

AWS CLI 旧会话过期。用户已明确授权 `aws login --profile lionel`；命令已启动，浏览器当前需要用户完成 IAM 登录。未增加数据库权限，未更改 `innodb_print_all_deadlocks`，未改业务代码、重放回执或部署。

## 追加：AWS CLI 线上证据核验结果

用户确认账号 `890608337256` 为第二套环境所属账号后，已通过独立 `armada-rds-diag` 配置完成登录，保留原 `lionel` 配置。浏览器仅用于登录，以下证据全部来自 AWS CLI。

- RDS ARN：`arn:aws:rds:ap-south-1:890608337256:db:database-1`；端点与 perf2 JDBC 完全一致；版本 8.4.8；参数组 `default.mysql8.4`。
- 下载 `mysql-error-running.log.2026-09-18.8`、`.9` 和当前日志。事故所在的 `.9` 覆盖 08:04:47–08:59:06 UTC，11 行均为地址反向解析警告，没有 InnoDB 死锁报告。相邻日志也无报告。未启用 CloudWatch 日志导出；此前查询 `innodb_print_all_deadlocks=0`。
- Performance Insights 开启。按 `db.name=armada_perf` 查询 08:19:00–08:22:00 UTC 实际采样，确有以下 SQL：
  - `UPDATE join_task SET executed=(SELECT COUNT(*) FROM join_task_result ... join_task_cleanup ...) ... WHERE id=109 ...`，SQL ID `F91443CCFB5B1637659ECA2244C5C3D5A37022F5`。
  - `SELECT * FROM join_task_result WHERE id = 369 AND tenant_id = 1 FOR UPDATE`，SQL ID `352D6C5F3FAC618A765E578EE05D0BEA78878829`。这是事故窗口中数据库侧实际执行过显式锁的证据，但 369 不是本机复现的 371/372，不把它当作任务 109 环路中的一端。
  - 多个 `wa_group_participant` UPSERT，来源包括提权、进群与离群事件。
- PI 按等待事件分区后，上述语句采样落在 `wait/io/table/sql/handler`；该聚合事件不提供持锁事务、被阻塞事务、具体行/索引锁的完整关系，不能直接解读成死锁环路。
- `db.Transactions.deadlocks.avg` 查询返回时间点但无 Value，表示此次查询没有可用数值，不能当成零死锁。
- RDS 主用户为 `admin`，IAM 数据库认证未启用，未配置 RDS 托管主用户 Secret。AWS API 登录成功不会赋予应用 MySQL 用户 `wheel` 的 PROCESS 权限；没有通过重置密码、授权或配置变更绕过此限制。

证据位于 `/private/tmp/join-multi-account-analysis-20260918/rds-error-*.json`、`rds-error-*.log`、`rds-pi-sql.json`、`rds-pi-waits.json`、`rds-pi-deadlocks.json`。线上原始环路仍未取得；继续获取需要现有具备 PROCESS 权限的 MySQL 连接读取 `SHOW ENGINE INNODB STATUS`（只能看到仍保留的最近一份，可能已被后续死锁覆盖）。

## 已取得：RDS 成员写入真实死锁环路

用户提供管理员连接后，于 2026-09-18 09:40:37 UTC 通过 MySQL 客户端、TLS、perf2 SSH 隧道执行只读 SHOW/SELECT。成功取得 `SHOW ENGINE INNODB STATUS`。凭据仅通过隐藏输入与子进程环境传递，没有写入脚本、文档或证据文件。

**报告发生时间：2026-09-18 09:14:01 UTC / 北京时间 17:14:01。** 这是服务器保留的最近一次死锁，不是 16:19–16:21 原始事故报告；先前报告已被后续死锁替换。该报告明确属于 `armada_perf`，两端均来自应用用户 `wheel`。

两个事务正在执行相同的 `INSERT INTO wa_group_participant ... ON DUPLICATE KEY UPDATE`：tenant=1、group_id=20031、同一 LID、来源 ADD_EVENT、事件 ID `4002017318`，连事件时间与本次写入时间都一致。

| 事务 | MySQL connection | 持有 | 等待 | 结果 |
| --- | --- | --- | --- | --- |
| A / 309142528 | 616666 | LID 唯一索引的 X gap lock | 同一间隙的 X insert intention，受 B 的 gap lock 阻塞 | 被 InnoDB 回滚 |
| B / 309142525 | 616665 | 同一 LID 索引间隙的 X gap lock | 同一间隙的 X insert intention，受 A 的 gap lock 阻塞 | A 回滚后解除这条环路 |

锁位置完全相同：表 `armada_perf.wa_group_participant`，索引 `uq_wa_group_participant_lid`，space=740、page=596、heap=152。索引为 `(tenant_id, group_id, lid_jid)` 唯一键；报告中的物理记录是间隙右边界，不是正在插入的目标 LID。

原始锁模式：

```text
HOLDS:   lock_mode X locks gap before rec
WAITING: lock_mode X locks gap before rec insert intention waiting
*** WE ROLL BACK TRANSACTION (1)
```

环路解释：**A 的插入意向被 B 的间隙锁挡住；B 的插入意向又被 A 的间隙锁挡住。** 两个 gap lock 本身可以共存，但各自插入时会受对方 gap lock 限制，因此形成环路。这与前述本机主表/明细统计环路是两类不同问题。

09:14:02.040 后端异常与报告时间对齐，调用链：

`ProtocolAccountEventConsumer.onGroupSyncMessage → handleGroupSyncEnvelope → ProtocolGroupJoinSinkImpl.handleJoins:63 → WhatsappGroupMemberJoinFactServiceImpl.saveLatest:33 → AccountGroupCurrentSnapshotPersistenceImpl.applyParticipantJoins:737 → persistParticipantFacts:1035 → upsertParticipantFactsInBatches:1141`。

本次查到的是普通成员进群通知的 UPSERT，不是管理员事务的 `JoinTaskAdminMapper.lock`。运行制品中的成员身份预读 `selectParticipantIdentityRows` 已无 FOR UPDATE。因此不能把“确有新增管理员锁”直接当作本报告的起因；本报告证明的是上述成员唯一索引间隙锁环路，不能从受害 SQL 单独断言最早持锁语句。也尚不能区分同一业务事件的并行写入来自多账号观察、重复投递或其他并发入口。

进一步查询确认 `events_statements_history_long=NO`；两个原连接 616665/616666 已不在 `performance_schema.threads`，无法通过它们的短历史还原完整事务 SQL 序列。未改变消费者、诊断开关或数据库参数。

原始证据：

- `/private/tmp/join-multi-account-analysis-20260918/rds-admin-innodb.txt`：完整线上 InnoDB 报告。
- `/private/tmp/join-multi-account-analysis-20260918/rds-admin-history.txt`：历史消费者状态、实际表索引及边界记录。
- `/private/tmp/join-multi-account-analysis-20260918/member-deadlock-091401.log`：对应后端异常和调用栈。

本轮已经取得并解释一份真实 RDS 死锁环路；没有修改业务代码、增加锁、修改线上权限、重试业务任务或部署。16:19 原事故的所有事务环路仍不能由这份较晚报告逐一还原。

## 补充纠偏：一个任务也会收到多个账号的成员通知

2026-09-19 用户质疑“只有一个进群任务，为何方案这么复杂”后，进一步核对：

- 09:14:00.760 UTC，consumer `#6-2` 收到账号 2570 的成员通知；09:14:00.764，consumer `#6-3` 收到账号 2632 的通知。两个 envelope eventId 的账号前缀不同，但事件后缀均为 `d8d27bedbfd55ad5`。死锁异常属于 `#6-3` 的同一 trace。09:14:02.046 又看到该通知重试。
- 这支持多账号观察同一成员变动、分别产生通知并行处理的解释；一个业务任务也可以产生多个数据库事务。不能把“并发事务”直接解释成用户开了多个任务，也不能把不同账号 envelope 简单视为同一 Kafka 消息重复投递。
- 只读查询群 20031 的任务映射：任务 109 对应明细 387/388、账号 2571/2570；任务 114 对应明细 432/433、账号 2588/2587。17:14 报告中的目标成员手机号对应账号 2587，它在该群的进群记录属于后续任务 114。
- 因此 17:14 原始报告虽然属于共享成员写入链路，**不能直接作为最初任务 109 在 16:19 失败的完整根因报告**。109 同群第二账号问题仍以此前各自的实际进群、提权、清理与回执日志为依据。

修复范围应先收敛到同一成员事实被多账号通知并行写入的具体处理点，验证合适的幂等处理与最小 SQL 调整；此前拆写入、身份合并、恢复机制等整套建议不是已经验证必须实施的改造方案。
