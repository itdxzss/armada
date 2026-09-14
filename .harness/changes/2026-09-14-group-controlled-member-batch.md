# 变更记录：受控群成员批量落库

- 日期 / 分支 / worktree：2026-09-14 / 1.0.3-snapshot / armada 主工作区
- 需求来源：群事件积压排查后，用户确认只做第 2 项逐成员 SQL 批量化；跨账号去重与锁方案暂不做。
- 状态：已部署 test1（2026-09-14，用户另行确认 Kafka 恢复 500）

## 目标

将一条群资料或成员事件中受控账号的重复查询及写入改为集合操作，缩短同群事务；每个账号的观察和绑定仍独立保留。

## 任务清单

- [x] 复现 15 个受控成员的逐账号持久化调用，得到回归测试失败证据。
- [x] 增加显式租户的批量上下文、锁后当前事实、账号绑定与退出周期清理 SQL。
- [x] 接入完整资料、受控成员对齐、精确进群三个调用点。
- [x] 验证现有状态优先级、迟到事件、PN/LID、租户、事务回滚及批量语句数量。
- [x] 审查改动并记录最终验证结果。

## 关键设计决策

- 不增加消息去重、事件版本或 Kafka 参数，不改变 GL→G→P→B 的既有事务边界，不删除必要锁。
- 同群按既有 200 行批次写成员与绑定；群解析/锁定和账号上下文查询每次调用只做一轮。
- 批量入口与旧单账号入口复用状态决胜和精确进群时间生成逻辑；原有单账号调用保持。
- 无表结构和 Flyway 变更。身份拆分仍走既有有证据的 PN/LID 合并，不推断跨账号事件相同。
- MySQL 8.4 实测发现派生 PN 列若继承连接字符集，新批查只能使用成员索引的 tenant/group 前缀；必须与原列 ascii/ascii_bin 一致，并用真实 EXPLAIN 验证完整 PN 唯一键。

## 验证

- 回归测试 `completeProfileDoesNotRunSingleAccountPersistenceForEveryMember` 在旧调用路径失败，证实 15 个成员走逐账号持久化。日志 `/tmp/group241-diag/batch-red.log`。
- 新 Mapper 使用 test scope H2、真实 XML、生产租户插件和 Spring 事务执行；H2 无法代表 InnoDB 执行计划、死锁和线上吞吐。
- 聚焦测试 8 类共 80 项通过，0 失败 / 错误 / 跳过，退出码 0；日志 `/tmp/group241-diag/batch-focused-final.log`。包含新增 H2 7 项、批量服务 5 项、原有单账号及事件调用链回归。
- H2 不能真实执行旧成员 UPSERT 中的复杂 `VALUES` 比较，也不能代表 MySQL 联表 `FOR UPDATE` 行锁。H2 计数只覆盖新增 context/current/binding 三条 SQL；1 与 15 账号均为 3 次，预置成员后验证各自绑定。
- 临时 MySQL 8.4.8，真实 V120/V139、Mapper XML、生产租户插件与 Spring 事务：整条受控成员持久化 1 账号 8 条、15 账号 8 条；原单账号入口循环 15 次为 120 条。仅此段 SQL 条数下降 93.3%，不是线上耗时结论。
- 第一轮 MySQL 8 项中 7 项通过，索引计划断言失败：3015 成员夹具下，新批查仅使用 tenant/group 两列，计划估算每次扫描 1507 行；绑定表已使用完整三列唯一索引。迟到退出、身份保留、回滚、InnoDB 两事务锁等待与 4 项旧路径行为均通过。日志 `/tmp/group241-diag/batch-mysql-first.log`。
- 修正 incoming PN 为 `CHAR(191) CHARACTER SET ascii COLLATE ascii_bin` 后，两条成员 JOIN 均使用完整 `(tenant_id,group_id,pn_jid)` 唯一索引，`eq_ref`、每次扫描估算 1 行。3015 绑定夹具下，绑定 JOIN 同样使用完整 `(tenant_id,account_id,group_id)` 唯一索引、`eq_ref`、1 行。无新增索引提示或锁策略。
- 最终验证共 88 个不同用例通过，0 失败 / 错误 / 跳过。修正生产 SQL 后统一跑 88 项，87 项通过；剩余绑定 EXPLAIN 用例因小夹具统计选择扫描而失败，补 3000 真实账号/成员/绑定并 ANALYZE 后，仅重跑新 MySQL 4 项全部通过，退出码 0；该轮未再修改生产代码。
- H2 测试只移除其不支持的 ASCII/COLLATE 修饰，先断言原 SQL 已具有正确修饰；真实 MySQL 直接加载未改写的生产 XML。
- 详细计数和前后执行计划：[controlled-batch-validation.json](../../docs/operations/evidence/group-event-backlog-20260914/controlled-batch-validation.json)。日志 `/tmp/group241-diag/batch-validation-final.log`、`/tmp/group241-diag/batch-mysql-final.log`。
- `xmllint --noout armada-api/src/main/resources/mapper/group/AccountGroupCurrentSnapshotMapper.xml` 与 `git diff --check` 通过。独立只读审查未发现其他阻塞问题。

最终命令（工作目录 `armada/`，Java 17；MySQL 仅本机 Testcontainers）：

```bash
DOCKER_HOST=unix:///Users/daishuaishuai/.orbstack/run/docker.sock \
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
mvn -q -f armada-api/pom.xml \
  -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar \
  -Dtest=AccountGroupControlledBatchMySqlTest test
```

完整本地/H2 用例：`AccountGroupControlledBatchMapperH2Test,AccountGroupCurrentSnapshotMapperH2Test,ProtocolGroupParticipantChangedSinkAdapterTest,ProtocolGroupProfileReportedConsumerTest,AccountGroupControlledBatchPersistenceTest,GroupProfileReportedSinkAdapterTest,GroupParticipantObservationServiceImplTest,AccountGroupCurrentSnapshotPersistenceImplTest`。
额外旧 MySQL 行为：`AccountGroupCurrentSnapshotPersistenceMySqlTest#controlledRoleObservationCreatesBindingWithoutFabricatingJoinOrClassification+delayedPreciseAddRepairsActiveSinceWithoutReplacingNewerInGroupObservation+acceptedExitStartsANewMembershipActiveSinceCycleOnNextAdd+delayedAddDoesNotOverrideNewerPreciseRemoveOrCreatePostControlClassification`。

## 部署

- 用户明确授权“第一套，kafka恢复为500吧，看看效果”后，只部署 test1 后端。主仓库 `1.0.3-snapshot`，基线 `d3835fa94ddee7218948b164521ad3eca9538d9d` 加当前批量改动；未 commit/push。
- 命令 `CI=true bash armada-deploy/deploy-test.sh --env test1 --be -y` 退出 0。本地、远端和运行容器 JAR 哈希一致：`3a17805a76c3dfb4ad497fefc470787e1c6105c73971bddd0e4b74ebb07aaf27`。
- 后端启动于 `2026-09-14T04:04:16.47104485Z`（北京时间 12:04:16）。脚本 API 就绪检查通过，后续观测无再次重启。三个群事件消费者均确认 `max.poll.records=500`、`max.poll.interval.ms=300000`。
- 修改前 `.env` 和 Compose 已备份于 test1 `/home/app/armada-deploy/backups/controlled-batch-500-20260914T040305Z`；保留已有环境变量透传，仅将 test1 的值由 10 恢复 500。
- [部署及观测证据](../../docs/operations/evidence/group-event-backlog-20260914/batch500-deploy/deployment.json)。启动后有 1 个 `resource/IpProxyMapper.xml` 死锁异常块（堆栈重复出现 4 次），属于换代理路径；本轮未发现群成员写入死锁。

## 遗留 / 跟进

- 已使用现场历史积压观测新版本 + 500 的实际处理效果；仍需持续留意高并发新建群下的表现，不能将短时提速等同于所有死锁或积压问题彻底解决。
