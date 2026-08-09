# 普通拉群成员查询异步化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将普通拉群所有成员查询从同步 HTTP 改为 Outbox/Kafka 异步命令，并让批次派发与未知结果收敛互不阻塞。

**Architecture:** 后端阶段处理器在本地事务中创建成员查询业务记录和协议 Outbox 行；协议 Worker 通过账号 operation gate 读取 `groupMetadata` 并发布过滤后的成员事实事件。后端 Kafka consumer 幂等写回查询结果、推进等待的普通拉群执行行。派发和未知结果收敛分别由独立单线程调度器执行，数据库租约和版本条件仍是并发边界。

**Tech Stack:** Java 17, Spring Boot, MyBatis/Flyway, Kafka, TypeScript, Fastify, Baileys, Jest, Maven.

## Global Constraints

- 仅覆盖普通拉群（`NORMAL_LINK`）；不扩大到营销建群和营销拉群。
- 业务事务只写数据库和 Outbox；不得在调度线程直接调用协议 HTTP。
- 所有 Kafka 命令和事件以 `commandId` 幂等；重复/迟到结果不得重复推进状态。
- 协议结果只返回请求的目标成员事实，不回传无关完整群成员名单。
- Worker 读取群元数据必须经既有 account/group operation gate，与拉人操作串行。

---

### Task 1: 将未知结果收敛移到独立调度器

**Files:**
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskExecutionDispatchScheduler.java`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationScheduler.java`
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskExecutionDispatchSchedulerTest.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationSchedulerTest.java`

**Interfaces:**
- Consumes: `PullTaskExecutionDispatchCoordinator#dispatchOnce()` and `PullTaskUnknownResultReconciliationCoordinator#reconcileIfDue()`.
- Produces: two independent lifecycle-managed scheduled executors; dispatch trigger never invokes reconciliation.

- [ ] **Step 1: Write failing scheduler-isolation tests**

```java
@Test
void dispatchRunDoesNotInvokeUnknownResultReconciliation() {
    scheduler.start();
    verify(coordinator, timeout(500)).dispatchOnce();
    verifyNoInteractions(reconciliationCoordinator);
}

@Test
void reconciliationSchedulerRunsWithoutBlockingDispatchScheduler() {
    reconciliationScheduler.start();
    verify(reconciliationCoordinator, timeout(500)).reconcileIfDue();
}
```

- [ ] **Step 2: Run the two tests to verify the first one fails because dispatch currently invokes reconciliation**

Run: `mvn -pl armada-api -Dtest=PullTaskExecutionDispatchSchedulerTest test`

- [ ] **Step 3: Move reconciliation lifecycle ownership into the new scheduler**

```java
@Component
public class PullTaskUnknownResultReconciliationScheduler {
    @PostConstruct
    public void start() {
        executor.scheduleWithFixedDelay(
                this::reconcileSafely, 0L,
                properties.getResultReconciliationIntervalMs(), TimeUnit.MILLISECONDS);
    }
}
```

Remove the `PullTaskUnknownResultReconciliationCoordinator` dependency and the second `try` block from `PullTaskExecutionDispatchScheduler#runOnceSafely`. Keep both executors single-threaded and daemonized.

- [ ] **Step 4: Run scheduler tests**

Run: `mvn -pl armada-api -Dtest=PullTaskExecutionDispatchSchedulerTest,PullTaskUnknownResultReconciliationSchedulerTest test`

- [ ] **Step 5: Commit**

```bash
git add armada-api/src/main/java/com/armada/task/scheduler/PullTaskExecutionDispatchScheduler.java \
  armada-api/src/main/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationScheduler.java \
  armada-api/src/test/java/com/armada/task/scheduler/PullTaskExecutionDispatchSchedulerTest.java \
  armada-api/src/test/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationSchedulerTest.java
git commit -m "feat: isolate pull task reconciliation scheduler"
```

### Task 2: 定义成员查询 Outbox 模型与协议 Kafka 契约

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V108__pull_task_member_query.sql`
- Create: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskMemberQuery.java`
- Create: `armada-api/src/main/java/com/armada/task/mapper/PullTaskMemberQueryMapper.java`
- Create: `armada-api/src/main/resources/mapper/task/PullTaskMemberQueryMapper.xml`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolPullTaskMemberQueryCommandRequest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/service/ProtocolCommandOutboxService.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`
- Modify: `protocol-layer/src/commands/types.ts`
- Modify: `protocol-layer/src/commands/worker-consumer.ts`
- Create: `protocol-layer/src/commands/group-members-query-executor.ts`
- Create: `protocol-layer/src/commands/group-members-query-executor.test.ts`

**Interfaces:**
- Produces `group.members.query.requested` command, payload fields `{tenantId,pullTaskId,groupExecutionId,queryId,purpose,groupJid,targetJids,protocolAccountId,commandId}`.
- Produces `group.members.result_reported` data fields `{tenantId,pullTaskId,groupExecutionId,queryId,purpose,commandId,outcome,participants,reasonCode,reasonMessage,timestamp}`.

- [ ] **Step 1: Write failing mapper and command tests**

```java
assertThat(mapper.insertPending(query)).isEqualTo(1);
assertThat(query.commandId()).isNotBlank();
assertThat(outboxService.enqueuePullTaskMemberQueryCommands(List.of(request))
        .insertedCount()).isEqualTo(1);
```

```ts
expect(parseMasterCommand(command).ok).toBe(true)
await executeWorkerCommand(command, deps)
expect(sock.groupMetadata).toHaveBeenCalledWith(groupJid)
expect(publisher.publish).toHaveBeenCalledWith('group.members.result_reported', accountId,
  expect.objectContaining({ commandId, outcome: 'SUCCESS', participants: [expectedParticipant] }))
```

- [ ] **Step 2: Run focused tests and verify missing mapper/command handling fails**

Run: `mvn -pl armada-api -Dtest=PullTaskMemberQueryMapperIntegrationTest test`

Run: `npm test -- group-members-query-executor.test.ts`

- [ ] **Step 3: Implement the durable query and Worker executor**

The migration creates a query table with tenant/execution scope, purpose, protocol account, group JID, command ID, status, attempt count, requested/completed timestamps and error fields; it has a unique key for `command_id` and an index on open query state. The executor obtains the socket, calls `groupMetadata`, filters participants to normalized requested JIDs, publishes exactly one success or failed result, and persists command state before publishing so replay republishes rather than calls WhatsApp again.

- [ ] **Step 4: Run focused backend and protocol tests**

Run: `mvn -pl armada-api -Dtest=PullTaskMemberQueryMapperIntegrationTest,ProtocolCommandOutboxServiceImplTest test`

Run: `npm test -- group-members-query-executor.test.ts worker-consumer.test.ts`

- [ ] **Step 5: Commit backend and protocol changes separately**

```bash
git -C /Users/daishuaishuai/IdeaProjects/armada/armada/.worktrees/codex-pull-member-query-async commit -m "feat: enqueue pull task member queries"
git -C /Users/daishuaishuai/IdeaProjects/armada-protocol/armada-protocol/.worktrees/codex-group-member-query-async commit -m "feat: execute group member queries from kafka"
```

### Task 3: 消费成员查询结果并驱动普通拉群执行行

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumer.java`
- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolPullTaskMemberQueryResultReportedEvent.java`
- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolPullTaskMemberQueryResultReportedSink.java`
- Create: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskMemberQueryResultServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskExecutionDispatchScheduler.java`
- Create: `armada-api/src/test/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumerMemberQueryTest.java`
- Create: `armada-api/src/test/java/com/armada/task/service/impl/PullTaskMemberQueryResultServiceImplTest.java`

**Interfaces:**
- Consumes the Task 2 result event and `PullTaskMemberQueryMapper#finishIfPending`.
- Produces a persisted snapshot and dispatch trigger only after an accepted result.

- [ ] **Step 1: Write failing result-consumer tests**

```java
consumer.onMessage(memberQueryResultJson(commandId, "SUCCESS"));
verify(resultSink).handleMemberQueryResultReported(eventCaptor.capture());

assertThat(service.handle(event)).isTrue();
verify(dispatchScheduler).trigger();
assertThat(service.handle(event)).isFalse(); // duplicate event
```

- [ ] **Step 2: Run tests to verify the new event is currently skipped**

Run: `mvn -pl armada-api -Dtest=ProtocolGroupEventConsumerMemberQueryTest,PullTaskMemberQueryResultServiceImplTest test`

- [ ] **Step 3: Add strict parsing and idempotent result settlement**

Require envelope/account consistency, command/query/execution relation and only `SUCCESS` or `FAILED`. Persist the filtered snapshot atomically with terminal query state. A successful or failed first result triggers the dispatcher; duplicate, late or mismatched results are logged and do not alter business facts.

- [ ] **Step 4: Run focused tests**

Run: `mvn -pl armada-api -Dtest=ProtocolGroupEventConsumerMemberQueryTest,PullTaskMemberQueryResultServiceImplTest test`

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: consume pull task member query results"
```

### Task 4: 用异步成员事实替换普通拉群各阶段 HTTP 调用

**Files:**
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerJoinProcessor.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerAdminProcessor.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskSupplementPullerProcessor.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskSupplementManagerProcessor.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationService.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallReconciliationService.java`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskMemberQueryService.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskMemberQueryServiceTest.java`
- Modify: existing processor and reconciliation tests adjacent to each modified class.

**Interfaces:**
- Consumes `PullTaskMemberQueryService#requestOrRead(QueryRequest)`.
- Produces `PENDING`, `AVAILABLE`, or `FAILED` facts; callers dispatch follow-up protocol actions only from `AVAILABLE` facts.

- [ ] **Step 1: Write failing processor tests proving no direct member-list port call occurs**

```java
processor.process(work);
verify(memberQueryService).requestOrRead(expectedQuery);
verifyNoInteractions(memberListPort);

when(memberQueryService.requestOrRead(expectedQuery)).thenReturn(MemberFacts.pending());
processor.process(work);
verifyNoInteractions(joinPort, participantPort);
```

- [ ] **Step 2: Run the processor/reconciliation test group and verify old HTTP mocks fail**

Run: `mvn -pl armada-api -Dtest=PullTaskManagerJoinProcessorTest,PullTaskManagerAdminProcessorTest,PullTaskSupplementPullerProcessorTest,PullTaskSupplementManagerProcessorTest,PullTaskUnknownResultReconciliationServiceTest,PullTaskPullCallReconciliationServiceTest test`

- [ ] **Step 3: Implement a shared request-or-read service**

For each caller, compute a stable purpose plus target JIDs. If a completed snapshot exists, return it; if an open query exists, return `PENDING`; otherwise atomically insert the query and its Outbox command then return `PENDING`. Replace every `memberListPort.list(...)` call in the six normal-pull classes with this service. On `PENDING`, persist/keep the existing stage wait state and return; on `FAILED`, preserve existing conservative unknown/retry behavior.

- [ ] **Step 4: Run all six focused test classes**

Run: `mvn -pl armada-api -Dtest=PullTaskManagerJoinProcessorTest,PullTaskManagerAdminProcessorTest,PullTaskSupplementPullerProcessorTest,PullTaskSupplementManagerProcessorTest,PullTaskUnknownResultReconciliationServiceTest,PullTaskPullCallReconciliationServiceTest,PullTaskMemberQueryServiceTest test`

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: make pull task member checks asynchronous"
```

### Task 5: 集成验证与运行说明

**Files:**
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskExecutionEndToEndIntegrationTest.java`
- Modify: `armada-api/src/main/resources/application.yml`
- Modify: `.harness/changes/pull-task-member-query-async/design.md`

**Interfaces:**
- Verifies the production configuration continues to use one dispatch worker and one reconciliation worker.
- Verifies a slow/unfinished member query cannot delay next due batch dispatch.

- [ ] **Step 1: Write the end-to-end regression test**

```java
givenPendingMemberQueryForOtherExecution();
givenDuePullBatch(executionId, dueAt);
dispatchCoordinator.dispatchOnce();
assertThat(outboxRowsForPullBatch(executionId)).hasSize(1);
assertThat(memberQueryStatus(otherExecutionId)).isEqualTo(PENDING);
```

- [ ] **Step 2: Run it before final configuration/documentation changes**

Run: `mvn -pl armada-api -Dtest=PullTaskExecutionEndToEndIntegrationTest test`

- [ ] **Step 3: Document configuration and operational signals**

Add explicit comments for `result-reconciliation-interval-ms`, separate reconciliation scheduler, query timeout/retry parameters and Kafka result topic. Record the expected logs/metrics keyed by `commandId`, `queryId`, `executionId` and `purpose` in the design document.

- [ ] **Step 4: Run complete relevant suites**

Run: `mvn -pl armada-api test`

Run: `npm test`

- [ ] **Step 5: Commit**

```bash
git commit -m "test: cover asynchronous pull task member checks"
```

## Self-review

- Scope coverage: Task 1 removes head-of-line scheduler blocking; Tasks 2–3 define and persist the Kafka command/result contract; Task 4 replaces every normal-pull `GroupMemberListPort` caller; Task 5 proves the original delayed-batch regression and documents operation.
- Placeholder scan: no TBD/TODO or unspecified error handling remains; failure and duplicate paths are explicitly covered by Tasks 2–4.
- Type consistency: every result carries `commandId`, `queryId`, `purpose`, tenant/task/execution identifiers and target facts; these are defined in Task 2 and consumed unchanged in Tasks 3–4.
