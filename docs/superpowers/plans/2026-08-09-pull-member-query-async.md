# 普通拉群成员查询异步化 Implementation Plan

**Goal:** 消除普通拉群批次派发被同步群成员查询阻塞的问题，同时保持现有拉群状态机和副作用幂等语义。

**Architecture:** 派发与未知收敛使用两个独立单线程调度器。后端用一张查询表和既有 Outbox 将群成员读取发到现有 Kafka topic；Web/Android 协议消费者读取群元数据后把过滤结果发回现有 group-events topic。后端幂等落结果并精准唤醒等待的执行行或收敛调度器。

**Constraints:** 仅普通拉群；不新增物理 topic、Redis 查询状态、业务阶段或专用超时调度器；普通流程“管理员↔拉手加联系人→管理员邀请拉手”不变。

---

## Task 1：隔离派发与未知结果收敛执行器

**Backend files**

- Modify `PullTaskExecutionDispatchScheduler.java`
- Create `PullTaskUnknownResultReconciliationScheduler.java`
- Modify/create对应 scheduler tests

1. 先写失败测试：用 latch 阻塞收敛 coordinator，同时证明 dispatch coordinator 仍能执行；验证 dispatch scheduler 不再调用收敛。
2. 运行定向 Maven 测试，确认因当前同线程实现而失败。
3. 从派发 scheduler 删除收敛依赖；新增独立单线程 daemon scheduler，提供合并重复信号的 `trigger()`。
4. 运行 scheduler 测试并提交。

## Task 2：后端成员查询表与 Outbox 命令

**Backend files**

- Create `V108__pull_task_member_query.sql`
- Create query entity/mapper/XML/status/purpose/result DTO
- Modify `ProtocolCommandOutboxService*`、Outbox mapper/cancel SQL
- Create `PullTaskMemberQueryPayloadHydrator`
- Create query service and focused tests

1. 先写 mapper/事务/Outbox 路由失败测试。
2. 创建单表模型：一次查询尝试一行，目标和结果使用 JSON；`command_id` 唯一，业务状态和执行行有索引。
3. 同一事务内依次插入 PENDING 查询、创建 Outbox、回绑 `commandId`；发布只能在提交后发生。
4. Web 命令路由到既有 master topic，Android 路由到既有 group-action topic，key 为协议账号。
5. Outbox 发布时 hydrate 目标 JSON；取消任务时同时取消尚未投递的查询命令。
6. `requestOrRead` 返回 `PENDING/AVAILABLE/FAILED`；已过期查询在下次调用时关闭并创建下一尝试，不加超时 scheduler。

## Task 3：Web 协议层执行异步成员查询

**Web protocol files**

- Modify command/event types、worker/master consumers、stream consumer、event subjects/publisher wiring
- Create member-query executor/event builder and Jest tests

1. 先写命令解析、目标过滤、无 owner、发布失败不确认的失败测试。
2. 增加 `group.members.query.requested`，在既有 account/group operation gate 内调用 `groupMetadata`。
3. 用 participant `id`、`phoneNumber`/LID 映射匹配请求目标，仅返回所需事实。
4. 发布 `group.members.result_reported`；master 无 owner 时发布 FAILED。
5. 不使用 action Redis state；broker ack 后才 XACK，失败时安全重读。

## Task 4：Android 协议层执行异步成员查询

**Android protocol files（先按项目 AGENTS 复核准确路径）**

- Modify group-action command router/start wiring/result publisher
- Create member-query command/executor/event and Go tests

1. 先写 group-action topic 命令解析、账号串行 gate、目标过滤、无账号/读取失败、发布失败的失败测试。
2. 在现有账号操作通道内调用已有群成员 IQ/client 能力。
3. 结果发布到既有 group-events topic，字段与 Web 完全一致。
4. 只读查询不增加 action Redis 状态，发布成功后才确认消费。

## Task 5：后端消费结果并精准唤醒

**Backend files**

- Modify `ProtocolGroupEventConsumer`
- Create member-query result event/sink/service
- Modify query mapper and execution-row wake SQL
- Tests for parsing/idempotency/wake-up

1. 先写失败测试：合法事件落库并唤醒；重复、账号不匹配和旧 `commandId` 不推进。
2. 严格校验 envelope、协议账号、查询/任务/执行行关联和终态枚举。
3. 条件更新当前 PENDING 查询，保存过滤结果或失败原因。
4. 阶段用途仅在任务/阶段/租约仍匹配时把 `next_run_at` 提前；提交后触发 dispatch scheduler。
5. 收敛用途提交后触发 reconciliation scheduler，不等待固定周期。

## Task 6：替换普通拉群的同步 HTTP 成员查询

**Backend callers**

- `PullTaskManagerJoinProcessor`
- `PullTaskManagerAdminProcessor`
- `PullTaskSupplementPullerProcessor`
- `PullTaskSupplementManagerProcessor`
- `PullTaskPullCallReconciliationService`
- `PullTaskUnknownResultReconciliationService`

1. 对六个类先写失败测试：调用 query service 后，`PENDING` 不调用旧 `GroupMemberListPort`，也不重复 join/invite/promote。
2. 将九处同步 `memberListPort.list(...)` 改为共享 `requestOrRead(...)`。
3. `PENDING` 时保留现有 `SUBMITTED/UNKNOWN` 动作状态并释放租约；结果唤醒后使用 `verificationOnly` 只验证。
4. 补拉手主流程继续“双方加联系人→管理员邀请”；手工 `JOIN_BY_LINK` 分支等待查询时不得再次 join。
5. FAILED 沿用现有保守的 unknown/retry 语义，不把查询失败推断成拉人失败。

## Task 7：跨模块验证与发布复核

1. 后端：scheduler 隔离、查询事务、Outbox 路由、超时/取消、重复/迟到结果、六类调用方测试。
2. Web：parser、gate、成员过滤、no-owner、broker retry tests。
3. Android：router、account lane、成员过滤、failure/retry tests。
4. 集成回归：阻塞未知收敛时，到期拉人批次仍及时生成 Outbox；查询等待不重复业务副作用。
5. `rg` 确认普通拉群六个类不再引用 `GroupMemberListPort`；营销路径保留不动。
6. 复核迁移、配置、结构化日志与部署顺序：协议消费者先上线，后端生产者后开启；无新物理 topic。
