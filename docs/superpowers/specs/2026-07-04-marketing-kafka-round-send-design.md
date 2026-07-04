# 营销任务 Kafka 轮次群发 — 规格

- 日期:2026-07-04
- 项目:armada
- 模块:`armada-api` / `armada-protocol`
- 范围:营销任务启动后的周期性群发、协议 Kafka 命令、发送结果回写

## 1. 业务口径

营销任务不是“一次性消费完目标群”的任务,而是周期性群发任务。

示例:

- 任务选择 1000 个群;
- 启动后第 1 轮向 1000 个群各发送 1 条消息;
- 间隔 `send_interval_seconds`,默认 30 秒;
- 第 2 轮继续向同一批 1000 个群各发送 1 条消息;
- 持续循环,直到人工停止任务或系统因保护策略暂停生成新轮次。

`send_interval_seconds` 表示轮与轮之间的间隔。第一版 `send_per_round` 不再解释为“一轮最多发多少个群”,避免把 1000 个目标群错误拆成小批慢慢发。第一版每个目标群每轮发送 1 条消息。

## 2. 架构决策

第一版采用 Kafka 异步命令,不在 Armada worker 中同步 HTTP 调协议层发送 1000 条消息。

原因:

- 一轮 1000 条是高副作用批量动作,同步 HTTP 会占用 Armada 线程并放大超时/重试风险;
- 现有 `protocol_command_outbox` 已具备事务后投递、Kafka dispatch、失败重试和兜底扫描能力;
- 协议层实际发送能力应由协议 worker/Kafka consumer 吸收,Armada 只负责调度和命令投递;
- 发送结果通过协议事件回流,再由 Armada 回写营销 attempt。

第一版不拆多 topic。默认继续投到现有 `protocol.master.commands.v1`,新增 `message.send.requested` command type。`protocol_command_outbox.kafka_topic` 保留未来切换空间,压测后如果营销消息挤压上线/下线/群同步,再把消息命令切到 `protocol.message.commands.v1`。

第一版不做 per-account 特殊排队。当前业务不考虑单个发言账号覆盖过多群的场景;如果后续出现同号高并发风控问题,再在协议层补 per-account gate。

## 3. 调度模型

新增全局营销轮次调度器,不为每个任务创建常驻线程。

配置建议:

- `armada.marketing.round-scheduler.enabled=true`
- `armada.marketing.round-scheduler.scan-fixed-delay-ms=1000`
- `armada.marketing.round-scheduler.executor-pool-size=5`
- `armada.marketing.round-scheduler.scan-limit=20`
- `armada.marketing.round-scheduler.outbox-batch-size=500`
- `armada.marketing.round-scheduler.backlog-multiplier=2`

运行方式:

1. 单个 scheduler 线程每 1 秒扫描到期任务;
2. 查询 `status=SENDING` 且 `next_round_at <= now` 的营销任务,最多 `scan-limit` 个;
3. 通过 DB 状态/锁字段抢占任务轮次,避免多实例重复生成同一轮;
4. 抢占成功后提交到固定大小 `roundExecutor`,测试环境先设 5 个线程;
5. 每个 round worker 负责为一个任务生成一轮命令;
6. 一轮生成完成后更新 `next_round_at = now + send_interval_seconds * 1000`。

`roundExecutor` 的工作是查库、写 attempt、写 outbox,不是直接发 WhatsApp 消息。4 核测试环境上这属于 DB/Kafka I/O 型负载,5 个线程作为第一版默认值可接受,后续根据 MySQL 写延迟和 Kafka 积压调低或调高。

## 4. 一轮发送流程

对一个已抢占的任务轮次:

1. 重新读取任务,确认状态仍为 `SENDING`;
2. 计算 `round_no`;
3. 查询该任务全部可发送 target;
4. 为每个 target 生成一条 `marketing_task_send_attempt`;
5. 每条 attempt 对应一条 `message.send.requested` 协议命令;
6. 按 `outbox-batch-size=500` 分批写入 `protocol_command_outbox`;
7. 由现有 outbox dispatcher 在事务提交后异步发 Kafka;
8. 更新任务 `next_round_at`;
9. 如果用户已停止任务,本轮之后不再生成下一轮。

一轮 1000 个群时,会生成 1000 条 attempt 和 1000 条 outbox command。写 outbox 拆成 2 批,每批 500。

## 5. 协议命令

新增 command type:

```text
message.send.requested
```

命令 routing key 使用 `protocolAccountId`。这不要求同账号严格有序,只是保持与现有 master router 的账号 owner 路由一致。

payload 包含:

- `tenantId`
- `marketingTaskId`
- `targetId`
- `attemptId`
- `roundNo`
- `accountId`
- `protocolAccountId`
- `groupJid`
- `messageType`: `TEXT` / `IMAGE` / `LINK`
- `text`
- `image`: 可选 `{base64,mimetype}`
- `source`: 固定 `marketing_task`

第一版支持 text/image/link。按钮超链模板先降级为文本,不接 wheel 的 `/v1/messages/button-card`;当前 `armada-protocol` 源码已确认存在 `/v1/messages/text`、`/v1/messages/image`、`/v1/messages/link`。

## 6. 协议层执行与事件回流

`armada-protocol` 需要扩展 master command parser 和 worker executor:

1. 接受 `message.send.requested`;
2. 按 owner worker 路由到账号所在 worker;
3. worker 根据 `messageType` 调 Baileys `sock.sendMessage`;
4. 成功或失败后发布发送结果事件;
5. ack worker inbox 命令。

新增事件建议:

```text
message.send_result_reported
```

事件 payload 包含:

- `tenantId`
- `marketingTaskId`
- `targetId`
- `attemptId`
- `roundNo`
- `protocolAccountId`
- `groupJid`
- `commandId`
- `success`
- `messageId`
- `timestamp`
- `reasonCode`
- `reasonMessage`

Armada 消费该事件后按 `attemptId` 幂等回写 attempt、target 计数和 task 计数。

## 7. 数据模型调整

当前 `marketing_task_send_attempt` 适合一次性目标尝试,不适合周期性轮次。需要前滚迁移补字段:

- `round_no BIGINT NOT NULL`
- `command_id VARCHAR(64) DEFAULT NULL`
- `message_id VARCHAR(128) DEFAULT NULL`
- `submitted_at BIGINT DEFAULT NULL`
- `result_at BIGINT DEFAULT NULL`

唯一约束调整为同一目标同一轮只有一条 attempt:

```text
tenant_id, target_id, round_no
```

`marketing_task` 需要补轮次调度字段:

- `current_round_no BIGINT NOT NULL DEFAULT 0`
- `next_round_at BIGINT DEFAULT NULL`
- `last_round_started_at BIGINT DEFAULT NULL`

停止任务时不删除 attempt/outbox,只把 task 状态置为 `STOPPED`;已投递的 Kafka 命令允许自然完成并回写结果,新轮次不再生成。

## 8. Backlog 保护

第一版不要求 30 秒内上一轮全部消费完。如果 30 秒到达且 backlog 未超过阈值,继续生成下一轮。

为避免无限堆积,增加任务级 backlog 保护:

```text
未完成 attempt 数 >= backlog_multiplier * target_count
```

达到阈值时:

- 不生成新轮次;
- 将 `next_round_at` 后推一个 `send_interval_seconds`;
- 任务保持 `SENDING`;
- 记录日志,便于压测观察瓶颈。

默认 `backlog_multiplier=2`。1000 个目标群时,未完成结果达到 2000 条就暂缓下一轮。

## 9. 停止与恢复

停止:

- `stopTask` 将任务置为 `STOPPED`;
- scheduler 后续不会抢占该任务;
- 正在生成的本轮在关键步骤前后重新检查任务状态,尽量尽快停止;
- 已经写入 outbox/Kafka 的命令不撤销。

服务重启:

- scheduler 从 DB 扫描 `SENDING` 且 `next_round_at <= now` 的任务恢复;
- 不依赖内存 active task 集合;
- 通过 DB 抢占避免多实例重复生成同一轮。

## 10. 测试与压测口径

单元测试:

- 调度器只扫描到期 `SENDING` 任务;
- `executor-pool-size=5` 配置绑定;
- backlog 达阈值时不生成新轮次;
- 一轮 1000 target 拆成 2 批 outbox;
- 停止任务后不生成下一轮。

DbTest:

- `marketing_task_send_attempt` 轮次唯一键生效;
- 同一 target 不同 round 可重复生成 attempt;
- 结果事件按 `attemptId` 幂等回写。

协议层测试:

- `message.send.requested` 被 parser 接受;
- worker 成功调用 `sendMessage`;
- 成功/失败都发布 `message.send_result_reported`;
- 未知 command type 仍被拒绝。

测试环境压测:

- 4 核环境先用 5 个 round executor 线程;
- 单任务 1000 群,间隔 30 秒;
- 观察 MySQL 写延迟、outbox dispatch 积压、Kafka consumer lag、协议层 CPU 和消息回写延迟;
- 只有当营销消息挤压生命周期命令时,再切专用 message command topic。
