# test1 并发建群期间群事件消费积压：只读排查

日期：2026-09-14。环境：第一套环境，后端 65.2.123.53。固定日志窗口：北京时间 10:17:00–10:41:00（右端不含）。未修改业务代码、远端配置、数据或服务。

## 结论

用户提出的“并发建群后群内多个账号上报事件，引发控端集中写库和锁竞争”得到现场日志和代码共同支持。

持续瓶颈不是单一死锁：同群多账号完整资料上报、逐受控成员重复 SQL、大事务持有同群锁，已经足以拖慢消费；现场还出现了死锁，以及大批消息处理超过 Kafka poll 时限后的重平衡和重复消费。当前证据不能量化死锁、普通锁等待、SQL执行和网络往返各自占比。

## 消息方向和通道

- Armada → Android：`protocol.android.group-action.commands.v1`。任务 241 共 28 条快照命令均已获得 Kafka 发送成功时间；查到的协议节点快照处理记录在秒级完成。
- Android → Armada：实际 topic `armada.protocol.group.events.v1`，消费组 `armada-api-group-events-staging`。
- 同一 consumer 处理 `group.profile_reported`（全群资料、成员）、`group.participant_changed`（成员/管理员增量）和 `group.snapshot_result_reported`（手动刷新结算）等事件。
- 已部署启动日志：3 个群事件消费者各 `max.poll.records=500`，`max.poll.interval.ms=300000`。

## 固定窗口实测

| 项目 | 结果 |
|---|---:|
| 资料事件进入处理器次数 | 397 |
| 成员/角色变更事件进入处理器次数 | 1073 |
| 资料事件涉及不同群数 | 54 |
| 完全相同 eventId 再次进入处理器 | 8 个 ID，多进入共 8 次 |
| poll timeout 告警 | 7 次 |
| 资料事件从生成到进入处理器的延迟，中位数 | 646.722 秒，约 10.8 分钟 |
| 同上 P95 / 最大值 | 1309.477 / 1372.344 秒，约 21.8 / 22.9 分钟 |

多群在窗口中各有 **15 个不同上报账号、15 份完整资料消息**，部分为 16–17 份。这证明放大来自不同账号对同一群的观察，不能只用相同 eventId 去重解决。

同一消费线程，一条资料事件开始到下一条群事件开始的间隔，中位数 4.599 秒，P95 10.231 秒；角色/成员事件对应中位数 0.309 秒。**这是相邻日志间隔，包含事务、锁等待和调度开销，不是精确的单 SQL 或 handler 计时。** 统计仅使用 0–60 秒的相邻间隔。

以上计数来自已经开始消费的日志，不代表 Kafka 总生产条数、尚未消费条数或实际条数 lag。没有“收到”日志的消息不在统计内。

## 死锁与锁竞争证据

北京时间 10:17:50.582 和 .583，两个群事件消费者在 `GroupProfileReportedSinkAdapter` 登记同一个群入口时出现死锁。对应 `mapper/group/GroupLinkMapper.xml` 的 `INSERT INTO group_link ... ON DUPLICATE KEY UPDATE ...`。异常嵌套打印多次，不能把每行 Deadlock 字样算作一次死锁。

后续只读数据库快照显示 `Innodb_row_lock_current_waits=2`，业务连接中 2 个处于 update 状态。全局累计锁等待值涵盖数据库启动以来约 109 天，不能归到本次测试，因此不用于本次死锁/等待次数结论。

10:22:50、10:22:57、10:23:00 三个消费者先后出现 `consumer poll timeout has expired`，伴随 `CommitFailedException`；之后仍有超时和重平衡。任务 241 最终在 10:26:59 以 7 项 TIMEOUT 收口。

## 写放大机制

1. Android 每个受控账号收到建群通知，都发一份包含完整成员的 profile；promote/demote 则只发角色增量，不重新查询全群。消息 key 是账号，同群不同账号的消息可能分散到不同分区。
2. Armada 每份 profile 开一个事务，登记群、提前锁 `group_link/wa_group`、写资料和完整成员，再为所有受控成员逐个写账号群绑定；到整份事务结束才释放群锁。
3. 完整成员已批量写过后，每个受控成员又走一次单成员处理：查账号、重新解析/锁群、查/锁成员、UPSERT 成员和绑定。正常已有群路径每成员约 8 次 Mapper SQL，额外还有整群固定 SQL。
4. 对现场的 15 人群，15 份 profile × 每份 15 名受控成员 × 约 8 次 SQL，**仅这一段常规路径就约 1800 次 SQL/群**。这是代码推导的量级，未用数据库 trace 实测；新群、身份合并、错误分支会改变数量。
5. 完全相同版本重放仍会走完整成员写；成员快照版本落败后，外层也仍会执行逐受控成员绑定。数据结果幂等不等于避免重复 SQL 和锁成本。
6. 已有统一锁序能减少特定反向锁序问题，不能消除同群长事务竞争。盲目增加消费者并发可能增加争锁。

资料事件取邀请码已异步执行，当前代码不支持“Kafka 消费线程同步等待邀请码网络请求”作为直接原因。

## 代码依据和版本核对

- `GroupProfileReportedSinkAdapter.java:78`：整个 profile 的事务及群锁；`:254` 逐受控成员收敛绑定。
- `AccountGroupCurrentSnapshotPersistenceImpl.java:447`：单成员写流程；`:824` 完整快照写入与重放行为。
- `ProtocolGroupParticipantChangedSinkAdapter.java:99`、`GroupParticipantObservationServiceImpl.java:70`：成员增量事务和绑定处理。
- `ProtocolGroupEventConsumer.java:193`：混合群事件 listener，未为重消息配置专用小 poll 批次。
- Android `group_snapshot_coordinator.go:303`、`:347`：每个接收账号分别处理 create 和 promote；`client.go:77` 按账号做 Kafka key。

实际运行 JAR SHA256：`bd12e52f4559f26367dfcdbd946c8c4d869793e6dadef034c02cbe38e1006e1e`。

在远端仅计算哈希，核对 5 个核心处理类与本地 `target/classes` 全部一致；2 个 Mapper 同时与本地编译目录和当前源码一致。本地匹配字节码也确认了先锁群、再写成员和逐账号循环。没有将整份本地 HEAD 等同于线上版本。

## 尚未量化的部分

业务数据库账号没有 `performance_schema.events_statements_summary_by_digest` 查询权限，因此没有真实单 SQL 耗时排行、死锁双方完整锁图或耗时分解。不能把相邻日志间隔当作纯 SQL 耗时，不能据此判定 RDS 算力不足。

后续修复优先级建议：减少相同群状态的重复工作及逐成员重复 SQL，缩短同群事务；针对实际死锁 SQL 复核锁序/唯一键竞争；为重群事件限制 poll 批次。需保留每账号在群/角色事实、较新事件覆盖规则与手动查询结算关联，不能直接丢弃其他账号事件。

附件 `log-metrics.json` 为聚合统计（deadlock_blocks 是异常片段，含嵌套重复，不是独立死锁次数）；`runtime-hashes.json` 为部署代码哈希，不包含代码或业务数据。
