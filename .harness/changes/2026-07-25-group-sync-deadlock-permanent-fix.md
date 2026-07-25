# 变更记录：账号群同步 InnoDB 死锁永久修复

- 日期 / 分支 / worktree: 2026-07-25 / `fix/group-sync-deadlock` / `/Users/yanwc/IdeaProjects/armada`
- 需求来源: 用户要求基于 perf2 真实死锁证据永久修复，保持原有业务逻辑，并用 MySQL 8.4 Testcontainers 四线程交叉群列表复现与验证。
- 状态: 修复与验证完成，已拆分提交，待部署

## 目标（一句话）

保持账号群快照、状态时序、即时营销差量、租户隔离和 Kafka 契约不变，在 MySQL 默认 `REPEATABLE READ` 下通过固定写表/唯一键顺序和无锁存在性预读，消除已确认的 InnoDB supremum / 插入意向锁环。

## 缺口拆解 / 任务清单
- [x] 用 MySQL 8.4 Testcontainers 复现 `group_link_preview` / `group_link_health` 的 `supremum` 交叉死锁。
- [x] 用 Service 单测锁定 `group_link -> preview -> health -> membership` 的全局分阶段顺序及各表唯一键排序。
- [x] 将群同步写入改为按表分阶段，保持外层事务与新增群即时营销语义不变。
- [x] 普通一致性读先区分存量/新增；存量行走等价 UPDATE，缺失行直接走原子 upsert，避免 RR 下对缺失键先 UPDATE 获取 gap 锁。
- [x] membership UPDATE 复用原事实时间、来源优先级、joined/lastSeen/admin 合并语义，并与旧 upsert 做 MySQL 对照。
- [x] 完整快照缺失关系由账号范围 UPDATE 改为普通读选 ID、按主键升序定点 UPDATE，并在写入时复核状态时序与精确事件优先级。
- [x] 补 H2 MySQL 模式真实 Mapper、租户隔离和字段合并测试。
- [x] 运行聚焦测试、MySQL 四线程并发、影响链测试、XML 校验、编译和完整 diff 评审。

## 关键设计决策

- 保留 `AccountGroupMembershipReportServiceImpl.applyGroupsReported` 的事务边界；任何阶段失败都整体回滚，`addedGroups` 与即时营销仍只基于完整提交后的当前关系计算。
- 群入口先按规范化 JID/内部 URL 顺序全部解析；preview 和 health 按 `group_link_id` 顺序写；membership 按唯一键中的 `group_jid` 顺序写；事务不再写完一个群后返回较早的表。
- 保持 MySQL 默认 `REPEATABLE READ`；不降低 Kafka 并发，不增加重试次数，不调整锁超时或唯一索引，重试只保留为异常容错。
- 不做 Flyway 和表结构变更。存量更新改 SQL 路径，新行仍依赖原唯一键并发收敛。
- MySQL 中间红灯证明：对不存在的 membership 先 UPDATE，会在 RR 下为多个缺失键积累 gap/supremum 锁，随后 `INSERT` 或 `INSERT IGNORE` 都会与其它事务的插入意向锁成环。因此最终方案先普通一致性读取存在键，只对已存在行 UPDATE；缺失行不做前置加锁 UPDATE，直接按全局键序 upsert。
- 首次发现同一群的并发测试还证明：RR 旧 read view 可能看不到等待期间由其它事务提交的 `group_link`。账号观察 upsert 后的 ID 回查改为租户内唯一 URL 的 `FOR UPDATE` 当前读；不改业务字段，也不改变隔离级别。
- 完整快照测试进一步证明：原账号范围 `UPDATE ... group_jid NOT IN (...)` 即使具备生产二级索引，优化器仍可能扫描并锁住跨账号 PRIMARY 记录，和各事务刚插入的 membership 主键形成环。修复后先用普通一致性读选出缺失关系 ID，再按升序主键定点更新；UPDATE 中重复校验保留状态、事实时间和精确事件来源，避免读写间状态变化造成误覆盖。
- H2 验证 Mapper/租户/字段语义；MySQL 8.4 Testcontainers 验证 InnoDB `supremum`、插入意向锁和四线程并发，二者不可互相替代。

## 验证（evidence-before-done）

- 红灯：旧 preview/health 交叉 ODKU 在 MySQL 8.4.8 RR 中稳定产生 `1213 / 40001`，`SHOW ENGINE INNODB STATUS` 同时包含两表与 `supremum`。
- 红灯：仅分表排序后，四线程真实 Service 在 membership ODKU 死锁；改为缺失键 UPDATE-first 后仍死锁；`INSERT IGNORE` 仍死锁。InnoDB 证据显示两个事务都持有 `uq_account_group_membership` supremum X 锁并等待 insert intention。
- 红灯：完整快照保留原账号范围缺失关系 UPDATE 时，四线程出现跨账号 PRIMARY 记录死锁；补齐生产索引后仍能复现，排除“仅测试 DDL 缺索引”的假因。
- `mvn -q -Dtest=AccountGroupSyncMySqlConcurrencyTest test`：5 个 MySQL 8.4.8 RR 用例通过，0 失败/错误/跳过。覆盖旧死锁复现、4 线程 × 12 群 × 10 轮完整快照（每账号每轮实际定点更新 1 条缺失关系）、24 群池交叉覆盖的首次创建 4 线程 × 5 轮、membership 新旧 SQL 语义等价，以及缺失关系更新的状态优先级/租户边界。
- `mvn -q -Dtest=MysqlModeMapperInMemoryTest test`：6 个 H2 MySQL 模式用例通过；新增 SQL 真实执行并确认租户 7 的写入不影响租户 8。
- `mvn -q -Dtest=AccountGroupMembershipSnapshotServiceImplTest test`：8 个用例通过，包含全局表序/键序和存量行路径。
- 群入口、账号群快照、营销即时发送/重试/轮次影响链聚焦集通过。
- `mvn -q -DskipTests compile`、三个变更 Mapper 的 `xmllint --noout`、`git diff --check` 均通过。
- 广覆盖集实际运行 1130 个测试：1116 通过，10 失败、4 错误、0 跳过。失败均位于本次未改动文件：协议端口由 String 迁移为 `ProtocolAccountRef` 后的旧 mock、历史群旧断言，以及建群营销 Mapper 已使用 `update.*` 而测试仍断言旧参数；与本次 diff 无文件交集，未扩大范围修改。

## 部署
- commit / 环境 / 部署后验证结果: 已拆分为生产修复与回归测试提交，未部署。

## 遗留 / 跟进

- perf2 部署与稳定窗口观测需在代码和全部门禁通过后单独执行。
