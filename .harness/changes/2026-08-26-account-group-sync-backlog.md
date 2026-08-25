# 变更记录：账号群同步消费积压

- 日期 / 分支 / worktree: 2026-08-26 / `codex/fix-account-group-sync-backlog` / `account-group-sync-backlog-1.0.3`
- 需求来源: test1 `protocol.account.group-sync.events.v1` / `armada-api-account-group-sync-events` 实测积压与锁等待诊断
- 状态: test1 两轮候选均因真实并发死锁判定 FAIL；第三轮锁序修复已完成本地独立审查，待提交与部署验证

## 目标（一句话）

缩短单条账号群快照持有共享群行锁的时间，并保持 Kafka 重放幂等、群事实一致性和营销新群副作用原子性。

## 缺口拆解 / 任务清单

- [x] 锁定 Kafka listener、事务传播、锁序、重试与 DLT 语义
- [x] 用事务边界和 MySQL 并发测试复现当前风险
- [x] 实现兼容句柄批量登记与稳定锁序
- [x] 把兼容写与当前事实/营销副作用拆成两个可恢复事务
- [x] 验证重放幂等、阶段一部分提交恢复及定向相关测试

## 关键设计决策

- 保留 `max.poll.records=1` 与现有 4 consumer，不以调大并发掩盖数据库锁等待。
- 不拆分当前六表快照的最终归约事务；该路径已有集合 SQL 与按群 JID 锁序，营销新群副作用仍需与当前事实同事务提交。
- 先把旧兼容句柄/分类阶段集合化，由独立 Spring bean 的 `REQUIRES_NEW` 事务真实提交；第二阶段也使用独立 `REQUIRES_NEW`，避免 self-invocation 或外层事务把两个阶段重新合并。
- 第一阶段显式使用 `READ_COMMITTED`。真实 MySQL 8.4 的首次重叠插入在默认 RR 下复现过死锁；RC 可避免 RR 旧 read view，并缩短唯一键/间隙锁影响。第二阶段保持现有默认隔离级别和业务原子性。
- 第一阶段只提交辅助 `group_link`、历史/上控后分类 flag 和创建者兼容字段，**不创建可调度 metadata task**；第二阶段先建立/刷新当前绑定，再把本次真正新增分类集合 enqueue，并对 warm replay 只补缺失 task 或把 `DEFERRED` 恢复为 `PENDING`。既有 `RUNNING/SUCCEEDED/PENDING/RETRY` 不被 warm replay 重置，避免任务风暴。
- scheduler 的 `defer` 在 UPDATE 当下再次以 `NOT EXISTS` 检查可执行账号绑定。即使 scheduler 先读到“无账号”，随后 phase2 提交绑定，迟到的 defer 也影响 0 行，不会把刚可执行的任务永久藏进 `DEFERRED`。
- 所有集合写入按稳定键排序：账号观察句柄先锁既有 `group_link.id`、再按规范化 JID 处理 missing；分类提升和 phase2 预锁按 `group_link.id`。metadata task 先普通读发现 existing，再按 `task.id` 主键升序预锁，missing 才保持 `group_link.id` 稳定插入，使分类批量写与 ONLINE resume 使用同一 TASK 锁序。锁域统一为 **GL → W(当前六表) → TASK**；精确 add 也把分类/task 移到 W 之后。
- `group_link` 的批量预锁和分类 UPDATE 均强制 `PRIMARY` 访问路径。真实 InnoDB 锁图证明仅写 `ORDER BY id` 不够：未固定索引的 UPDATE 会回扫集合外小主键，与另一事务的升序 `FOR UPDATE` 形成环。
- 批量句柄的空群名新插入使用 JID fallback；命中并发行时空观察名不覆盖已经提交的真实群名。输入在进入 `TreeMap` 前过滤空 key。
- 服务入口、phase1 和 phase2 都按当前 `protocolAccountId` 复核账号绑定，并以账号 `last_complete_at` 淘汰所有 `syncAt <= watermark` 的旧事件（包括 incomplete）。这样旧 DLT 回放不会复活后来已被完整快照删除的群。
- 新群营销的 `detectedAt` 使用事件 `syncAt`；Kafka parser 和领域服务都拒绝缺少 `occurredAt/reportedAt` 的群列表事件，旧 Web DLT 回放不能再用处理时刻绕过完整水位或误命中后来才开始的营销任务。
- 手工历史群刷新复用相同阶段服务：第一阶段只提交兼容句柄/分类计划，第二阶段按账号→GL→W→TASK 落当前事实和分类任务。current facts 写失败不再被内层 catch 吞掉，不会把缺失新模型快照的账号计为成功或继续刷新邀请链接。
- 第一阶段提交、第二阶段锁等待失败时，异常继续交给 Kafka；正常重试会幂等重放 warm 第一阶段，再完成当前事实和 task 事务。不重置 offset、不改 DLT、不改真库。
- 接受短暂的阶段间一致性：第一阶段的辅助句柄/分类/创建者字段可先可见，当前六表事实仍是上一版，营销和分类 task 均未部分提交。若重试耗尽进入 DLT，辅助状态可能长期保留且分类 task 可能尚不存在；只有后续相关事件或人工/运维 DLT 重放才可能收敛，不能宣称必然自动恢复。

## 验证（evidence-before-done）

### TDD 红灯证据

- 旧实现仍逐群调用单句柄登记，批量快照测试期望一次返回稳定句柄时失败。
- 旧外层 `@Transactional` 下，真实 Spring 事务测试观察到第二阶段在第一阶段 commit 回调前开始，证明原设计没有拆事务。
- MySQL 8.4 首次并发重叠群快照在 RR 下实际出现 deadlock；第一阶段改为 RC 后，同一并发测试稳定通过。
- 旧分类路径逐群 `mark + enqueue`，新增集合调用约束后先编译/行为失败，再补最小批量 API 与 SQL 转绿。
- 第一版拆阶段会在 phase1 提前创建 task；回归构造出 scheduler 先读 `PENDING`/无账号、phase2 随后建绑定、scheduler 最后 defer 的 TOCTOU，证明仅 warm resume 不可靠。改为 phase1 只固化 flag、phase2 enqueue/reconcile，并给 defer 加执行时门禁后转绿。
- 旧完整事件只在 phase2 做水位判断时，late replay 已经在 phase1 改 flag/task；新增“旧 incomplete phase2 失败 → 新 complete 成功 → late old replay”先红，入口和两个锁事务统一水位后，兼容分类、GL、六表持久化、task 和营销调用计数全部不变。
- 批量空群名与真实群名并发、JID 顺序与既有主键反序、精确 add 与 phase2 的 GL/W/TASK 交错均使用 `performance_schema.data_lock_waits` 确定性 barrier；不用 sleep 或概率重试。
- 并发 cold 分类首次 10/10 在 `markClassifications` 与预锁查询间复现死锁。InnoDB 图显示同为 `group_link.PRIMARY`：分类 UPDATE 已持有目标集合，却因未固定访问路径继续请求集合外更小主键；另一事务持有小主键并等待目标集合。给 UPDATE 增加 `FORCE INDEX(PRIMARY)` 后同一 10 次并发回归转绿，没有加入重试掩盖。
- 手工历史群 current facts 写失败、群列表缺事件时间的 consumer/domain 两层校验分别先得到 3 个失败断言；旧实现确实会吞写失败并接受/分发空时间事件，最小实现后转绿。
- TASK 反序夹具显式构造 `task.id` 与 `group_link_id` 相反：第三个事务先锁 task 2，classification IODKU 先等待 task 2，ONLINE resume 再持有 task 1 等待 task 2；释放 blocker 后旧实现稳定报真实 `DeadlockLoserDataAccessException`。classification 改为显式 tenant 的 PRIMARY task.id 预锁后，同一 barrier 只串行等待并通过，不增加重试。

### 定向回归

```bash
mvn -q \
  -Dtest='AccountGroupMembershipReportServiceImplTest,AccountGroupMembershipReportPhaseServiceTest,AccountGroupMembershipReportTransactionBoundaryTest,AccountGroupMembershipSnapshotServiceImplTest,AccountGroupMembershipStatusServiceImplTest,GroupLinkRegistryServiceImplUnitTest,GroupClassificationServiceImplTest,GroupMetadataSyncTaskServiceImplTest,AccountGroupCurrentSnapshotPersistenceImplTest,HistoricalGroupAccountGroupRefreshServiceTest,ProtocolAccountEventConsumerTest,ProtocolKafkaConfigurationTest,GroupLinkMapperSqlShapeTest,GroupMetadataSyncTaskMapperDbTest' \
  test
```

- 结果：**113 tests，0 failure，0 error**。
- 真实 Spring + H2 事务边界测试从一个外层 RR 事务调用服务，确认两个阶段均经代理进入 `REQUIRES_NEW`；第一阶段实际隔离级别为 JDBC `READ_COMMITTED`。
- 第一次第二阶段抛 `CannotAcquireLockException` 时，commit/rollback 回调确认第一阶段已提交、第二阶段已回滚；同一事件重放后两阶段提交。
- 事务序列回归覆盖旧 incomplete 的 phase2 失败、新 complete 成功、旧事件晚回放；入口水位门禁后所有写域调用计数不再变化。

### 真实 MySQL 8.4 / 生产 mapper

```bash
DOCKER_HOST=unix:///Users/daishuaishuai/.orbstack/run/docker.sock \
  mvn -q -Dtest=GroupLinkRegistryBatchMySqlTest test
```

- 结果：**29 test invocations，0 failure，0 error**；MySQL `8.4.8`。9 个普通用例加两个 `@RepeatedTest(10)` 全部通过。
- 日志与 statement recorder 实际观察到生产 `ROW_NUMBER()` derived-table 句柄查询、多值 upsert，以及 tenant interceptor 对普通 mapper SQL/批量 metadata task 的租户注入；不是 JDBC 手写等价业务 SQL。
- 并发句柄：`@RepeatedTest(10)`，每轮 4 事务并发、每事务 100 群、并集 175 群；共 40 个并发事务执行，全部复用唯一句柄、无死锁、无重复 URL。
- 并发 cold 分类：`@RepeatedTest(10)`，同样每轮 4 事务、每事务 100 群、并集 175 群；共 40 个并发事务执行。最终 175 个 flag、175 个 task，所有 RUNNING task 都只由真正赢得 flag 更新的事务设置 `rerun_requested`，task 与 flag 的更新时间一致；无死锁。
- 事务模板显式使用 JDBC `READ_COMMITTED`，与 phase1 生产注解一致。锁等待 barrier 直接查询真实 MySQL `performance_schema.data_lock_waits`。
- 400 个既有句柄批量登记固定 **4 条** mapper statement（候选解析 / 既有 ID 预锁 / multi-values upsert / 最终解析），没有 JDBC batch 或逐群 SQL。
- 最新完整 29 次执行中，400 群 cold/warm phase1 分别为 **8 / 7 statements、155 / 105 ms**；phase1 均未创建 task。独立分类兼容入口 cold/warm 分别为 **5 / 1 statements、42 / 7 ms**；cold 多出的一条是发现已有 task.id 的集合查询。耗时是本机 OrbStack 单次观测值，只作量级证据；statement 数由断言固定。
- 真实竞态还覆盖：phase1 flag 提交但 phase2 失败后 warm replay 创建 due task；scheduler stale read 后 late defer 仍保持 task due；空名 loser 不覆盖真实群名；JID/ID 反序批量预锁；task.id/group_link_id 反序 classification 与 ONLINE resume；精确 add 与 phase2 两事务都遵循 GL→W→TASK。锁域探针的 GL/TASK 走生产 mapper/service，W 用同一真实事务中的最小行 UPDATE 建立确定性锁域；完整六表 mapper 另由既有持久化测试覆盖。

### 静态与全量尝试

- `mvn -q -DskipTests test`：通过。
- `AccountGroupCurrentSnapshotPersistenceImplTest`：16/16 通过。
- `xmllint --noout`：三个修改的 mapper XML 均通过。
- `git diff --check`：通过。
- 额外运行既有 `AccountGroupCurrentSnapshotPersistenceMySqlTest`：本分支与同一原始 HEAD `63f754a4` 的全新 detached worktree、同 MySQL `8.4.8` 都得到完全相同的 **26 tests / 2 failures / 1 error**。三处均为基线既有：400 群旧 baseline 断言 expected 200/actual 400、精确 add 旧分类断言 expected 0/actual null、测试夹具 `group_link` 缺 `deleted_at`。因此记录为非回归证据，不为凑绿修改本轮业务或过期夹具；其余 23 项通过。
- 尝试 `mvn -q test`；模块既有 `@SpringBootTest` DB 测试默认连接本机 MySQL `root` 且无密码，首先因 `Access denied for user 'root'@'localhost'` 使共享 context 失败，随后还出现既有 H2 schema 漂移（例如缺 `group_folder.system_builtin`）。在已报告 637 tests / 57 errors 后终止重复连接等待，因此不能将模块全量标为通过；这些失败未触及本次生产文件，本轮定向 113 项和新增真实 MySQL 29 次执行均独立通过。

## 部署

- 首轮 commit：`68140ac8c0d295a817e41e6e740568d69e7f1405`，已部署 `test1`。
- 容器与 API 健康检查通过；`armada-backend` restart=0、OOM=false。
- 部署后 22 分钟日志发现 51 个 Kafka error-handler 异常块：group-report 22 次 deadlock、join 20 次 deadlock、departure 3 次 deadlock、join 6 次 `uq_wa_group_participant_phone` 冲突。group-report 首轮去重的 15 个事件均在下一次 Kafka 重试成功，未观察到 DLT 日志信号，但该候选仍判定 FAIL 并停止 Quick/canary/soak。
- Kafka group-sync lag 从 31,512 降到 28,286，说明集合化已提升吞吐，但并发正确性门禁未通过，不能据此放行。

### test1 反馈修复

- 生产 `V139` 的 `(tenant_id, group_id, phone)` 唯一键此前未进入 snapshot MySQL 夹具；现已纳入，并稳定复现 LID+可信手机号命中既有 PN 行时的二次 DuplicateKey。
- 五条 participant 写路径统一先按 PRIMARY 锁 `wa_group`，再锁/写 participant 与 binding，锁序为 `GL → G → P/B`；report 的 joined current-read 只处理已预锁 groupId 闭包并执行完整当前读锁，避免 RR 旧快照与晚扩锁。
- soft-deleted / missing 群在任何 G mutation 前先锁既有 GL/G；删除会重新引入 `G → GL` 的宽 `UPDATE JOIN`。
- phone-only PN/LID 身份使用同群可信手机号证据归并；冲突时明确失败，不吞异常、不盲重试。
- 独立审查：PASS，0 BLOCKER / 0 IMPORTANT。
- 验证：Unit/H2/migration 21/21；核心 MySQL 7/7；soft-delete 锁序 1/1；`GroupCurrentLocalWriteMySqlTest` 25/25；snapshot MySQL 31 项中 28 通过，剩余 3 项已在未修改基线、同一 MySQL 8.4.8 中逐字复现。

### test1 第二轮反馈与第三轮修复

- 第二轮候选启动后，严格按新容器 `startedAt` 观察到 16 个唯一 deadlock；全部发生在 `GROUP_REPORT`，受害 SQL 均为 `wa_group_profile` 批量 upsert。每个事件都在 Kafka 下一次投递成功，未观察到 lock timeout、phone 唯一键冲突或 DLT，但并发正确性门禁仍判定 FAIL。
- 门禁窗口内 group-sync lag 为 `12,914 → 11,075 → 10,736`，其余 5 个 consumer group 始终为 0；容器 restart=0、OOM=false。成功处理样本 p50=664 ms、p95=5,205 ms、max=14,777 ms，后端 CPU 一度接近 99%。lag 下降不能覆盖死锁事实，因此没有启动正式 Quick、WhatsApp canary 或 soak。
- 运行账号没有 MySQL `PROCESS` 权限，且 `innodb_print_all_deadlocks=0`，无法从历史事件恢复完整 wait graph。本轮不声称已识别线上每一次 deadlock 的确切对手 SQL；修复依据是生产调用链中可证明的反向锁序，并用真实 MySQL 8.4.8 RR 确定性 barrier 逐条复现。
- 当前邀请码、资料上报和健康写统一锁序：显式邀请码 `GL → G(PRIMARY) → PROFILE → INVITE`；已有群 `G → PROFILE → INVITE`；软删群先锁 G 主键再复活；公开预览 `GL → INVITE`；群绑定 `GL → G`；资料上报在任何 PROFILE/P/B 写前建立 `GL → G` 边界；健康写为 `G(PRIMARY) → PROFILE`。
- 红灯覆盖：显式邀请码 `GL ↔ PROFILE`、metadata `G ↔ PROFILE`、缺建群时间的 profile-reported `G ↔ PROFILE`、bindGroup `G ↔ GL`、公开预览 `INVITE ↔ GL`，旧实现均由 production mapper + MySQL RR barrier 稳定触发真实 deadlock 或顺序失败；修复后对应 barrier 与 SQL 顺序全部转绿，没有加入重试掩盖锁环。
- 第三轮冻结验证：相关单测 68/68；主控独立选择的 14 条核心 MySQL 并发/锁序 14/14；`GroupCurrentLocalWriteMySqlTest` 25/25；snapshot MySQL 40 项中 38 通过，唯一 2 个失败已在未修改基线逐字复现；`mvn -DskipTests package`、mapper XML、`git diff --check` 均通过。独立审查结论 PASS，0 BLOCKER / 0 IMPORTANT。

## 独立审查

- 结论：**PASS，0 BLOCKER / 0 IMPORTANT**。
- 审查关闭了手工历史刷新锁序与异常传播、TASK 反序锁序、缺失 `occurredAt` 绕过水位三项风险。
- 审查者独立复跑定向测试 **113/113**、真实 MySQL 8.4.8 **29/29**；冻结快照测试后未发生漂移。

## 遗留 / 跟进

- 第三轮 test1 部署已获用户授权但尚未执行；部署时保留现有 4 consumer 与 offset/DLT，部署后至少观察 500 个唯一群报告且不少于 5 分钟，同时检查 deadlock、DuplicateKey、lock timeout、DLT、单条耗时、CPU/内存和 lag。任一 deadlock 或版本/观测证据不完整，都停止后续 Quick/canary/soak。
- 阶段间短暂可见和 DLT 残留是有意风险边界；若业务不能接受辅助句柄/分类先可见，需要更大范围的 outbox/状态机设计，不属于本次止血修复。
- 为统一 GL→W 锁序，phase2 会按主键升序预锁本事件涉及的 legacy GL，并持有到当前事实事务提交。它消除了已复现的环，但共享群仍会串行；本地并发回归不能替代测试环境对单条消费耗时、InnoDB lock wait、lag 下降和 1h/6h/24h soak 的观察。
- 模块全量 DB 测试需要项目统一提供隔离测试数据源/schema，不能用本次修复顺手扩大处理。
