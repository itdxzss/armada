# 第二套任务 26 红框待启动行诊断

取证日期：2026-09-17。目标为 perf2 / 3.110.124.52 / armada_perf。只读查询使用 MAX_EXECUTION_TIME=5000 和 READ ONLY 事务；未修改业务状态、代码、配置或部署。以下时间均为北京时间（数据库与日志 UTC + 8）。

## 确认事实

截图群 JID 与文件名对应任务 #26 `任务_44329708`，并发群数为 1，来源分组为 #4「历史群组」。

| TXT | 前一轮执行 | 前一轮终态 | 红框后继执行 | 创建时间 | 最终状态 |
|---|---|---|---|---|---|
| 9-12AK11.txt | #112 / attempt 2 | GROUP_BANNED | #119 / attempt 3 | 19:36:01.549 | 父任务人工结束时取消 |
| 9-12AK14.txt | #116 / attempt 2 | GROUP_BANNED | #122 / attempt 3 | 19:38:46.640 | 父任务人工结束时取消 |

两条后继执行的 group_link_id、group_jid、started_at 均为空，角色记录及账号动作记录均为 0，每行仍保留 202 条料子记录。它们是待领取替补群的执行记录，并非已经分配后又跳过的两个群。

任务 #26 于 19:42:14.075 变为 ENDED，blocking_reason 为「人工结束」。#119/#122 同时进入 execution_status=6。它们现在不会继续调度。

## 日志与代码

19:37:19.148，执行 #119 在 acquireExecutionSlot 更新父任务时发生 MySQL DeadlockLoserDataAccessException，日志明确包含 taskId=26、executionId=119。调度器释放租约并将 nextRunAt 设为 1789645068563（19:37:48.563）。这是已证实的死锁退避，不是群邀请码失败或管理员不足。

19:36—19:42 调度日志持续出现 claimed>0、skipped>0、backlog=0；调度线程持续工作。后继行的启动还受并发上限 1 约束。#116、#117、#120、#118、#121 在该时段先后运行。日志不逐行记录每次未取得名额的具体分支，不能把每一次 skipped 都归为死锁或单一资源不足。

- PullTaskExecutionTransactionService.prepare：先选择群资源、竞争并发名额，成功后才绑定群并启动；没有取得名额则释放租约返回空。
- PullTaskExecutionDispatchCoordinator.process：空工作结果记作本轮 skipped；异常释放租约并退避。该统计不等于业务永久跳过。
- PullTaskGroupExecutionMapper.selectClaimed 按执行行 ID 排序，页面按 seq/attempt 排列；页面上下位置不是严格的实际启动顺序。回调也可在处理一批候选时改变槽位可用情况。

## 结论与限制

红框空白的直接原因是封群换群后继行尚未成功启动、未绑定替补群；已证实并发上限 1，以及 #119 的一次竞争槽位死锁退避。未发现两行已有协议动作被静默丢弃的证据。页面只显示待调度及零角色数量，无法明确说明并发等待或异常退避，是本次可观测性不足。

没有保存死锁当时的数据库等待图，故未认定死锁另一方的具体事务及完整锁环。未将系统收到的 GROUP_BANNED 判定扩展为对 WhatsApp 真实封群原因的独立验证。

本机原始时段日志：`/private/tmp/pull-skip-perf2-diag/backend.log`；最终计数：`/private/tmp/pull-skip-perf2-diag/settings-result.txt`。

## 追查：事务设计与死锁复现

用户追问后继续核实。第二套 MySQL 实际版本为 8.4.8，事务隔离为 REPEATABLE-READ，innodb_deadlock_detect=1。SHOW ENGINE INNODB STATUS 被拒绝，原因是业务数据库账号缺少 PROCESS 权限；没有扩大账号权限。

运行中 `/app/app.jar` 与远端部署包 SHA256 均为 `a258304817e9aef14b813aeb03554150d4cda2ea1332c0f28548e3753006b023`。从该部署包提取的 PullTaskMapper.xml 与本地完全一致；执行行 Mapper 只差本地尚未部署的 countLaterAttempts 查询，领取、启动、退避路径一致。

### 已复现的缺陷

在本机独立 MySQL 9.3.0、线上真实两张表的 DDL、REPEATABLE-READ 隔离级别下，用合成父任务及两条执行记录复现：

1. 事务 B 将执行 #114 从 EXECUTING 更新为 FAILED，暂未提交，持有调度索引对应记录的排他锁。
2. 事务 A 执行线上 acquireExecutionSlot SQL。该 SQL 在 UPDATE 父任务的条件中包含 EXISTS 候选行及 COUNT 运行行子查询。COUNT 扫描申请共享 next-key 锁，等待 B 的旧状态索引记录锁。
3. 事务 B 插入新的 WAIT_START 换群执行 #120，申请同一索引范围的插入意向锁，与 A 的共享 next-key 锁请求形成循环。
4. MySQL 报 ERROR 1213，并回滚事务 A（领取执行名额）；B 完成封群和后继插入。

锁表显示冲突索引为 `idx_pull_task_execution_dispatch`，锁键为 `(2,0,0,114)`。死锁图同时记录 A 的 `lock mode S waiting` 与 B 的 `lock_mode X locks gap before rec insert intention waiting`，末尾为 `WE ROLL BACK TRANSACTION (1)`。在第二个独立合成数据库重复相同顺序，结果一致。

这证明现有“UPDATE 内跨表统计运行数”与“更新旧执行后插入换群执行”的事务组合能产生死锁。它是明确的代码设计风险；不能归咎用户配置或简单称数据库偶发异常。本地版本 9.3 与线上 8.4 不同，且缺少现场死锁图，因此本次线上死锁与该机制高度吻合，但未证明现场另一方事务和锁键完全相同。

### 退避和排队问题

死锁异常路径只 releaseLockWithBackoff，保留本执行和料子，不把群标为不可用，不创建新换群，也不直接终止任务。约 30 秒只是再次具备调度资格，不保证获得名额。

prepare 未拿到名额时释放租约返回空，不保存逐次具体等待原因；调度统计将其归为 skipped。启动竞争不检查是否还有更早的待启动行，异步回调可在一轮候选处理间释放名额，因此没有严格先到先服务保证。并发为 1 会放大等待，但不是本次数据库死锁的责任归因。现场日志不足以逐次还原 #119/#122 每次未启动究竟落在哪个分支，不能声称一次 30 秒退避解释了全部数分钟等待。

### 修复方向（未改业务代码、未部署）

需要共同修正名额竞争与回调事务的锁协议，消除更新语句中跨执行行范围加锁的组合，保持并发上限校验的原子性；同时给到期重试明确排队顺序、保留错误原因和下一次重试时间，并用 MySQL 并发测试覆盖封群、插入后继和启动竞争。不能只增加重试次数或提高并发数。

本机复现脚本及两次结果：`/private/tmp/pull-skip-perf2-diag/reproduce.py`、`reproduce-result.txt`、`reproduce-repeat-result.txt`。临时 MySQL 仅启用本机 socket，完成后已关闭。线上全程只读。

MySQL 对索引记录/next-key 锁及阻挡插入的说明：[官方文档](https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html)。
