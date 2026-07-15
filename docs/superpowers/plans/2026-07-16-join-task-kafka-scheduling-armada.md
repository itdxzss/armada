# Armada 进群任务 Kafka 调度 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Armada 批量进群从固定 16 个账号 lane、同步 HTTP 和 `Thread.sleep` 改为 MySQL 到期调度、现有协议 outbox/Kafka 命令和统一群结果事件，使单任务上百账号可并行入队，同任务同账号仍按随机间隔串行推进。

**Architecture:** `join_task_result` 保存 `WAITING/SUBMITTED/TERMINAL` 和下次到期时间；独立单线程调度器跨租户扫描到期行，按租户在短事务中 `FOR UPDATE SKIP LOCKED` 复核、读取账号协议引用、写现有 outbox，并原子标记当前尝试。Web 与 Android 返回 `group.join_result_reported` 后，任务域按 tenant/result/command/attempt 幂等应用结果，重试当前行或随机放行同账号下一行。outbox `DEAD` 通过当前在途行与 outbox 的关联查询转成 `KAFKA_PUBLISH_FAILED`，不增加结果 watchdog。

**Tech Stack:** Java 17、Spring Boot 3.3、MyBatis/MyBatis-Plus、Flyway、MySQL 8、Spring Kafka、JUnit 5、Mockito、AssertJ、Maven

---

## Scope and execution rules

- Implementation repository: `armada/`, module `armada-api/`.
- Source of truth: `docs/superpowers/specs/2026-07-15-join-task-kafka-scheduling-design.md`.
- Execute in an isolated worktree because the primary worktree already contains unrelated changes. Use the `using-git-worktrees` skill before Task 1.
- Run production work test-first: add one focused failing test, observe RED, add minimum implementation, observe GREEN, then commit that task.
- Run Maven commands from `armada/armada-api`; true-MySQL checks use `./dbtest.sh '<tests>'` only after confirming the target is the local/test database. Never print `.env` or credentials.
- Do not alter HTTP group-join adapters or `GroupJoinPort`; only the batch task stops calling them.
- Do not add Kafka topics, UI states, stop/cancel behavior, result watchdogs, cross-task account locks, or restart recovery.
- Recheck the newest Flyway version immediately before Task 1. This plan reserves `V055`; if another branch has taken it, rename only the migration and its test reference to the next free version.
- Commit only files named by the active task. Preserve unrelated workspace changes and `.claude/worktrees/` entries.

## Locked state and contracts

```text
Business status:  PENDING | SUCCESS | FAILED
Dispatch state:   WAITING | SUBMITTED | TERMINAL
Serial key:       tenant_id + join_task_id + account_id
Web command:      protocol.master.commands.v1
Android command:  protocol.android.commands.v1
Result event:     protocol.group.events.v1
Command type:     group.join.requested
Result type:      group.join_result_reported
Scheduler:        one dedicated thread, 1000 ms fixed delay, batch 500
```

Rollout dependency:

```text
armada-protocol plan ─┐
                      ├─ both consumers deployed and verified ─> this Armada plan deployed/enabled
Android Zhuan plan ───┘
```

---

### Task 1: Migrate `join_task_result` to the asynchronous state model

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V055__join_task_kafka_dispatch.sql`
- Modify: `armada-api/src/main/java/com/armada/task/model/entity/JoinTaskResult.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/JoinTaskDispatchState.java`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/JoinTaskMigrationDbTest.java`

- [ ] **Step 1: Write the failing schema assertions**

Extend `JoinTaskMigrationDbTest` to assert exactly 17 columns and these definitions/defaults:

```java
assertColumn("dispatch_state", "varchar", false, "WAITING");
assertColumn("next_execute_at", "bigint", true, null);
assertColumn("command_id", "varchar", true, null);
assertColumn("attempt_no", "int", false, "0");
assertIndex("idx_jtr_dispatch", "dispatch_state,next_execute_at,id");
assertIndex("idx_jtr_task_account", "tenant_id,join_task_id,account_id,status,id");
```

Add fixtures proving historical `SUCCESS/FAILED` rows become `TERMINAL`, while historical `PENDING` rows become `WAITING` with `next_execute_at IS NULL`.

- [ ] **Step 2: Run the migration test and verify RED**

```bash
./dbtest.sh 'JoinTaskMigrationDbTest'
```

Expected: missing columns/indexes or column count mismatch.

- [ ] **Step 3: Add the Flyway migration**

Create `V055__join_task_kafka_dispatch.sql`:

```sql
ALTER TABLE join_task_result
  ADD COLUMN dispatch_state VARCHAR(16) NOT NULL DEFAULT 'WAITING' AFTER status,
  ADD COLUMN next_execute_at BIGINT NULL AFTER dispatch_state,
  ADD COLUMN command_id VARCHAR(64) NULL AFTER next_execute_at,
  ADD COLUMN attempt_no INT NOT NULL DEFAULT 0 AFTER command_id,
  ADD KEY idx_jtr_dispatch (dispatch_state, next_execute_at, id),
  ADD KEY idx_jtr_task_account (tenant_id, join_task_id, account_id, status, id);

UPDATE join_task_result
SET dispatch_state = 'TERMINAL'
WHERE status IN ('SUCCESS', 'FAILED');

UPDATE join_task_result
SET dispatch_state = 'WAITING', next_execute_at = NULL
WHERE status = 'PENDING';
```

- [ ] **Step 4: Add the enum and entity mappings**

Create `JoinTaskDispatchState.java`:

```java
package com.armada.task.model.enums;

public enum JoinTaskDispatchState {
    WAITING,
    SUBMITTED,
    TERMINAL
}
```

Add `dispatchState`, `nextExecuteAt`, `commandId`, and `attemptNo` with ordinary getters/setters to `JoinTaskResult`.

- [ ] **Step 5: Run GREEN and commit**

```bash
./dbtest.sh 'JoinTaskMigrationDbTest'
git add armada-api/src/main/resources/db/migration/V055__join_task_kafka_dispatch.sql armada-api/src/main/java/com/armada/task/model/entity/JoinTaskResult.java armada-api/src/main/java/com/armada/task/model/enums/JoinTaskDispatchState.java armada-api/src/test/java/com/armada/task/mapper/JoinTaskMigrationDbTest.java
git commit -m "feat: add join task dispatch state"
```

### Task 2: Add due-row, lock, transition, and DEAD-outbox mapper primitives

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/model/dto/JoinTaskDispatchCandidate.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/JoinTaskDeadCommandCandidate.java`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/JoinTaskResultMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/JoinTaskResultMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/JoinTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/JoinTaskMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/JoinTaskResultMapperDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/JoinTaskMapperDbTest.java`

- [ ] **Step 1: Add failing true-MySQL mapper tests**

Cover all of the following in `JoinTaskResultMapperDbTest`:

- due scan is cross-tenant, ordered by `next_execute_at,id`, limited, and excludes non-running/non-waiting/future rows;
- `selectDueForUpdate` accepts only the current tenant and uses `FOR UPDATE SKIP LOCKED`;
- a `SUBMITTED` sibling blocks another row only for the same task/account, not another account or another task;
- `activateFirstPendingPerAccount` activates the minimum ID for every account, including 100 distinct accounts in one task;
- conditional result lookup requires `PENDING/SUBMITTED` plus matching `command_id/attempt_no`;
- terminal transition activates only the next greater waiting row for the same task/account;
- retry transition clears `command_id`, retains `PENDING`, and schedules the current row;
- DEAD scan joins `protocol_command_outbox` only to the currently matching `SUBMITTED` command, so already-retried/terminal rows disappear from later scans.

Cover in `JoinTaskMapperDbTest` that `markDoneWhenNoPending` changes only a `RUNNING` task with zero `PENDING` results.

- [ ] **Step 2: Run mapper tests and verify RED**

```bash
./dbtest.sh 'JoinTaskResultMapperDbTest,JoinTaskMapperDbTest'
```

- [ ] **Step 3: Add the candidate DTO and exact mapper API**

```java
package com.armada.task.model.dto;

public record JoinTaskDispatchCandidate(Long tenantId, Long resultId) {
}
```

Add a separate transport-failure correlation record so a DEAD row can never fail a newer attempt:

```java
package com.armada.task.model.dto;

public record JoinTaskDeadCommandCandidate(
        Long tenantId, Long resultId, String commandId, int attemptNo
) {
}
```

Add these methods to `JoinTaskResultMapper`; cross-tenant scans must carry `@InterceptorIgnore(tenantLine = "true")`:

```java
@InterceptorIgnore(tenantLine = "true")
List<JoinTaskDispatchCandidate> selectDueCandidates(
        @Param("now") long now, @Param("limit") int limit);

List<JoinTaskResult> selectDueForUpdate(
        @Param("ids") List<Long> ids, @Param("now") long now);

int activateFirstPendingPerAccount(
        @Param("joinTaskId") Long joinTaskId, @Param("now") long now);

JoinTaskResult selectSubmittedForUpdate(
        @Param("id") Long id, @Param("commandId") String commandId,
        @Param("attemptNo") int attemptNo);

int markSubmitted(@Param("id") Long id, @Param("commandId") String commandId,
                  @Param("attemptNo") int attemptNo, @Param("now") long now);
int markRetry(@Param("id") Long id, @Param("reason") String reason,
              @Param("nextExecuteAt") long nextExecuteAt, @Param("now") long now);
int markTerminalSuccess(@Param("id") Long id, @Param("groupJid") String groupJid,
                        @Param("now") long now);
int markTerminalFailure(@Param("id") Long id, @Param("reason") String reason,
                        @Param("now") long now);
int activateNextPending(@Param("joinTaskId") Long joinTaskId,
                        @Param("accountId") Long accountId,
                        @Param("afterId") Long afterId,
                        @Param("nextExecuteAt") long nextExecuteAt,
                        @Param("now") long now);

@InterceptorIgnore(tenantLine = "true")
List<JoinTaskDeadCommandCandidate> selectDeadSubmittedCandidates(
        @Param("deadStatus") int deadStatus, @Param("limit") int limit);
```

Add to `JoinTaskMapper`:

```java
int startDraftTask(@Param("id") Long id, @Param("now") long now);
int markDoneWhenNoPending(@Param("id") Long id, @Param("now") long now);
```

- [ ] **Step 4: Implement guarded SQL**

The lock query must retain all guards in SQL:

```sql
SELECT r.*
FROM join_task_result r
JOIN join_task t ON t.id = r.join_task_id AND t.deleted_at IS NULL
WHERE r.id IN
<foreach collection="ids" item="id" open="(" separator="," close=")">
  #{id}
</foreach>
  AND t.status = 'RUNNING'
  AND r.status = 'PENDING'
  AND r.dispatch_state = 'WAITING'
  AND r.next_execute_at <= #{now}
  AND NOT EXISTS (
    SELECT 1 FROM join_task_result active
    WHERE active.tenant_id = r.tenant_id
      AND active.join_task_id = r.join_task_id
      AND active.account_id = r.account_id
      AND active.status = 'PENDING'
      AND active.dispatch_state = 'SUBMITTED'
  )
ORDER BY r.id
FOR UPDATE SKIP LOCKED
```

The DEAD query must join on all correlation columns:

```sql
SELECT r.tenant_id AS tenantId, r.id AS resultId,
       r.command_id AS commandId, r.attempt_no AS attemptNo
FROM join_task_result r
JOIN protocol_command_outbox o
  ON o.tenant_id = r.tenant_id
 AND o.aggregate_type = 'JOIN_TASK_RESULT'
 AND o.aggregate_id = r.id
 AND o.command_id = r.command_id
WHERE r.status = 'PENDING'
  AND r.dispatch_state = 'SUBMITTED'
  AND o.deleted_at IS NULL
  AND o.status = #{deadStatus}
ORDER BY o.updated_at, o.id
LIMIT #{limit}
```

- [ ] **Step 5: Run GREEN and commit**

```bash
./dbtest.sh 'JoinTaskResultMapperDbTest,JoinTaskMapperDbTest'
git add armada-api/src/main/java/com/armada/task/model/dto/JoinTaskDispatchCandidate.java armada-api/src/main/java/com/armada/task/model/dto/JoinTaskDeadCommandCandidate.java armada-api/src/main/java/com/armada/task/mapper/JoinTaskResultMapper.java armada-api/src/main/resources/mapper/task/JoinTaskResultMapper.xml armada-api/src/main/java/com/armada/task/mapper/JoinTaskMapper.java armada-api/src/main/resources/mapper/task/JoinTaskMapper.xml armada-api/src/test/java/com/armada/task/mapper/JoinTaskResultMapperDbTest.java armada-api/src/test/java/com/armada/task/mapper/JoinTaskMapperDbTest.java
git commit -m "feat: add join dispatch mapper transitions"
```

### Task 3: Start each account immediately without launching lane workers

**Files:**
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/JoinTaskServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/task/service/JoinTaskStartServiceTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/service/JoinTaskCreateServiceTest.java`

- [ ] **Step 1: Change the start test to the database state transition**

Assert that `startTask(9)`:

```java
verify(joinTaskMapper).startDraftTask(eq(9L), anyLong());
verify(resultMapper).activateFirstPendingPerAccount(eq(9L), anyLong());
verify(joinTaskMapper).refreshCounters(9L);
verify(joinTaskMapper).markDoneWhenNoPending(eq(9L), anyLong());
verifyNoInteractions(joinTaskWorker);
```

Also assert a failed conditional start does not activate results. Update create tests so `FAILED` plan rows persist `TERMINAL`, while valid `PENDING` rows persist `WAITING`, `nextExecuteAt=null`, `attemptNo=0`.

- [ ] **Step 2: Run RED**

```bash
mvn -q -Dtest=JoinTaskStartServiceTest,JoinTaskCreateServiceTest test
```

- [ ] **Step 3: Remove `JoinTaskWorker` injection and after-commit launch**

Inside the existing `@Transactional startTask` method, use only:

```java
long now = System.currentTimeMillis();
if (joinTaskMapper.startDraftTask(id, now) != 1) {
    throw new BusinessException(ErrorCode.CONFLICT, "进群任务状态已变化，请刷新后重试");
}
resultMapper.activateFirstPendingPerAccount(id, now);
joinTaskMapper.refreshCounters(id);
joinTaskMapper.markDoneWhenNoPending(id, now);
```

Do not call `runAfterCommit`, `startAsync`, a protocol status port, or a synchronous join port.

- [ ] **Step 4: Run GREEN and commit**

```bash
mvn -q -Dtest=JoinTaskStartServiceTest,JoinTaskCreateServiceTest test
git add armada-api/src/main/java/com/armada/task/service/impl/JoinTaskServiceImpl.java armada-api/src/test/java/com/armada/task/service/JoinTaskStartServiceTest.java armada-api/src/test/java/com/armada/task/service/JoinTaskCreateServiceTest.java
git commit -m "refactor: start join tasks through due rows"
```

### Task 4: Expose protocol account references through the account Service boundary

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/service/AccountProtocolLookupService.java`
- Create: `armada-api/src/main/java/com/armada/account/service/impl/AccountProtocolLookupServiceImpl.java`
- Create: `armada-api/src/test/java/com/armada/account/service/AccountProtocolLookupServiceTest.java`

- [ ] **Step 1: Write failing lookup tests**

Assert batch lookup preserves only active requested accounts, maps `protocol_id` through `ProtocolBackend.fromProtocolId`, and returns the existing `ProtocolAccountRef(armadaAccountId, backend, protocolAccountId, wsPhone)`. Assert blank protocol ID/account handle/phone is rejected for that account without exposing credentials.

- [ ] **Step 2: Run RED**

```bash
mvn -q -Dtest=AccountProtocolLookupServiceTest test
```

- [ ] **Step 3: Implement the account-owned Service**

```java
package com.armada.account.service;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import java.util.List;

public interface AccountProtocolLookupService {
    List<ProtocolAccountRef> findActiveProtocolRefs(List<Long> accountIds);
}
```

The implementation may inject `AccountMapper`; task classes may inject only this Service, never `AccountMapper`.

- [ ] **Step 4: Run GREEN and commit**

```bash
mvn -q -Dtest=AccountProtocolLookupServiceTest test
git add armada-api/src/main/java/com/armada/account/service/AccountProtocolLookupService.java armada-api/src/main/java/com/armada/account/service/impl/AccountProtocolLookupServiceImpl.java armada-api/src/test/java/com/armada/account/service/AccountProtocolLookupServiceTest.java
git commit -m "feat: expose protocol account lookup service"
```

### Task 5: Add the unified group-join outbox command and topic routing

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolGroupJoinCommandRequest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/service/ProtocolCommandOutboxService.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapperDbTest.java`

- [ ] **Step 1: Write failing Web/Android outbox tests**

Use one Web and one Android request in the same call. Assert ordered returned `commandIds`, aggregate type `JOIN_TASK_RESULT`, aggregate ID=result ID, Kafka key=`protocolAccountId`, Web topic=`protocol.master.commands.v1`, Android topic=`protocol.android.commands.v1`, and payload fields exactly match the approved contract.

- [ ] **Step 2: Run RED**

```bash
mvn -q -Dtest=ProtocolCommandOutboxServiceImplTest test
```

- [ ] **Step 3: Add the request record and Service method**

```java
package com.armada.platform.protocol.model.command;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

public record ProtocolGroupJoinCommandRequest(
        Long tenantId,
        Long joinTaskId,
        Long joinTaskResultId,
        Long accountId,
        String protocolAccountId,
        String wsPhone,
        ProtocolBackend protocolBackend,
        String inviteCode,
        int attemptNo,
        String source
) {
}
```

Add:

```java
ProtocolCommandOutboxEnqueueResult enqueueGroupJoinCommands(
        List<ProtocolGroupJoinCommandRequest> commands);
```

Use constants `group.join.requested` and `JOIN_TASK_RESULT`; set `batchId` to `join-task:<joinTaskId>` for this command family, even for a one-row batch. Route `WEB` to `ProtocolMasterCommandProperties`, `ANDROID` to `ProtocolAndroidCommandProperties`.

- [ ] **Step 4: Verify transactional persistence with true MySQL**

```bash
./dbtest.sh 'ProtocolCommandOutboxMapperDbTest'
```

Add a rollback assertion: if the surrounding transaction rolls back, no join outbox row remains and the dispatch trigger is not observed after commit.

- [ ] **Step 5: Commit**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolGroupJoinCommandRequest.java armada-api/src/main/java/com/armada/platform/protocol/service/ProtocolCommandOutboxService.java armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java armada-api/src/test/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapperDbTest.java
git commit -m "feat: enqueue group join protocol commands"
```

### Task 6: Implement invite parsing, random interval policy, and result transitions

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/service/JoinTaskInviteCodeParser.java`
- Create: `armada-api/src/main/java/com/armada/task/service/JoinTaskIntervalPolicy.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/JoinTaskResultReportedEvent.java`
- Create: `armada-api/src/main/java/com/armada/task/service/JoinTaskResultService.java`
- Create: `armada-api/src/main/java/com/armada/task/service/impl/JoinTaskResultServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/model/enums/JoinTaskFailureReason.java`
- Create: `armada-api/src/test/java/com/armada/task/service/JoinTaskInviteCodeParserTest.java`
- Create: `armada-api/src/test/java/com/armada/task/service/JoinTaskIntervalPolicyTest.java`
- Create: `armada-api/src/test/java/com/armada/task/service/JoinTaskResultServiceTest.java`

- [ ] **Step 1: Write parser and interval RED tests**

Parser cases: canonical URL, case-insensitive host/scheme, trailing slash, pure invite code, wrong host, extra path/query, blank code. Interval cases: both distribution modes, closed lower/upper bounds, equal bounds, negative/reversed range rejection, millisecond overflow protection.

- [ ] **Step 2: Implement the focused policies**

The parser returns only a trimmed invite code. `JoinTaskIntervalPolicy` chooses the existing fixed or multi interval by distribution mode and calculates:

```java
long nextExecuteAt = Math.addExact(baseTime, Math.multiplyExact(randomSeconds, 1_000L));
```

Expose a package-private overload accepting `RandomGenerator` so boundary tests are deterministic; production uses `ThreadLocalRandom.current()`.

- [ ] **Step 3: Write result-state RED tests**

Cover `JOINED`, `ALREADY_JOINED`, `PENDING_APPROVAL`, explicit retryable failure, retry disabled, retry exhausted, duplicate event, stale command ID, stale attempt, terminal next-row activation, and final task `DONE`. Use fixed min=max intervals so expected `next_execute_at` is exact.

- [ ] **Step 4: Implement result application**

Use this event shape:

```java
public record JoinTaskResultReportedEvent(
        String eventId, Long tenantId, Long joinTaskId, Long joinTaskResultId,
        Long accountId, String protocolAccountId, String commandId, int attemptNo,
        String outcome, String groupJid, String reasonCode, String reasonMessage,
        boolean retryable, long timestamp, String workerId
) {
}
```

`JoinTaskResultServiceImpl.apply` must set/restore `TenantContext`, run in one transaction, lock with `selectSubmittedForUpdate`, and treat a missing match as idempotent/stale success. Retry when `retryEnabled && retryable && attemptNo <= retryLimit`; clear `command_id` on retry. On terminal transition, schedule only the next row with a fresh interval, then refresh counters and call `markDoneWhenNoPending`.

Add `KAFKA_PUBLISH_FAILED`, `RATE_LIMITED`, `TEMPORARY_FAILURE`, and `JOIN_RESULT_UNCONFIRMED` mappings to `JoinTaskFailureReason` where absent.

- [ ] **Step 5: Run GREEN and commit**

```bash
mvn -q -Dtest=JoinTaskInviteCodeParserTest,JoinTaskIntervalPolicyTest,JoinTaskResultServiceTest,JoinTaskFailureReasonTest test
git add armada-api/src/main/java/com/armada/task/service/JoinTaskInviteCodeParser.java armada-api/src/main/java/com/armada/task/service/JoinTaskIntervalPolicy.java armada-api/src/main/java/com/armada/task/model/dto/JoinTaskResultReportedEvent.java armada-api/src/main/java/com/armada/task/service/JoinTaskResultService.java armada-api/src/main/java/com/armada/task/service/impl/JoinTaskResultServiceImpl.java armada-api/src/main/java/com/armada/task/model/enums/JoinTaskFailureReason.java armada-api/src/test/java/com/armada/task/service/JoinTaskInviteCodeParserTest.java armada-api/src/test/java/com/armada/task/service/JoinTaskIntervalPolicyTest.java armada-api/src/test/java/com/armada/task/service/JoinTaskResultServiceTest.java armada-api/src/test/java/com/armada/task/model/enums/JoinTaskFailureReasonTest.java
git commit -m "feat: apply join task result transitions"
```

### Task 7: Build the short-transaction dispatcher and dedicated scheduler

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/scheduler/JoinTaskDispatchProperties.java`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/JoinTaskDispatchTransactionService.java`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/JoinTaskDispatchCoordinator.java`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/JoinTaskDispatchScheduler.java`
- Modify: `armada-api/src/main/resources/application.yml`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/JoinTaskDispatchTransactionServiceTest.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/JoinTaskDispatchCoordinatorTest.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/JoinTaskDispatchSchedulerTest.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/JoinTaskDispatchCapacityDbTest.java`

- [ ] **Step 1: Write transaction-service RED tests**

Assert one tenant transaction:

1. locks only due rows;
2. batch-loads account refs through `AccountProtocolLookupService`;
3. parses invite codes before enqueue;
4. calls one `enqueueGroupJoinCommands` batch;
5. zips ordered returned command IDs to ordered rows and marks attempt `old+1` as `SUBMITTED`;
6. rolls back result mutations when outbox enqueue fails;
7. terminally handles missing account/invalid invite and schedules the same account's next row without affecting other accounts.

- [ ] **Step 2: Implement the transactional service**

The public entry is:

```java
@Transactional(rollbackFor = Exception.class)
public JoinTaskDispatchStats dispatchTenant(Long tenantId, List<Long> resultIds, long now)
```

Set and restore `TenantContext` inside the method. Do not call Kafka directly and do not query account runtime status. Validate `inserted == commands.size()` and `commandIds.size() == commands.size()` before marking submissions.

- [ ] **Step 3: Write coordinator/scheduler RED tests**

Assert the coordinator groups candidates by tenant, caps at configured 500, reconciles matching outbox `DEAD` rows via `JoinTaskResultService` with the candidate's exact `commandId/attemptNo` and `KAFKA_PUBLISH_FAILED/retryable=true`, and records `scanned/claimed/enqueued/skipped` without invite/full-phone logging.

Assert the scheduler owns exactly one named daemon thread, uses fixed delay, prevents overlapping runs, survives one coordinator exception, and shuts down on bean destruction.

- [ ] **Step 4: Implement configuration and scheduler**

Add to `application.yml`:

```yaml
armada:
  task:
    join-dispatcher:
      enabled: ${JOIN_TASK_DISPATCHER_ENABLED:true}
      fixed-delay-ms: ${JOIN_TASK_DISPATCHER_FIXED_DELAY_MS:1000}
      batch-size: ${JOIN_TASK_DISPATCHER_BATCH_SIZE:500}
```

Use a dedicated `ScheduledExecutorService` created with one thread named `join-task-dispatcher-1`. The scheduled method calls only the coordinator; it must contain no `sleep`, account executor, protocol HTTP call, or result wait.

- [ ] **Step 5: Prove capacity and concurrency with true MySQL**

`JoinTaskDispatchCapacityDbTest` creates one task with at least 100 accounts and one pending row each, starts it, runs one coordinator tick with a fake outbox Service, and asserts all 100 become `SUBMITTED` in that tick. Add a two-transaction test showing `SKIP LOCKED` prevents duplicate claim.

```bash
./dbtest.sh 'JoinTaskDispatchCapacityDbTest,JoinTaskResultMapperDbTest'
```

- [ ] **Step 6: Run unit GREEN and commit**

```bash
mvn -q -Dtest=JoinTaskDispatchTransactionServiceTest,JoinTaskDispatchCoordinatorTest,JoinTaskDispatchSchedulerTest test
git add armada-api/src/main/java/com/armada/task/scheduler armada-api/src/main/resources/application.yml armada-api/src/test/java/com/armada/task/scheduler
git commit -m "feat: schedule due join commands"
```

### Task 8: Consume unified group-join result events

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupJoinResultReportedEvent.java`
- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupJoinResultReportedSink.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumer.java`
- Create: `armada-api/src/main/java/com/armada/task/service/impl/JoinTaskResultReportedSinkAdapter.java`
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumerTest.java`
- Create: `armada-api/src/test/java/com/armada/task/service/JoinTaskResultReportedSinkAdapterTest.java`

- [ ] **Step 1: Write consumer RED tests**

Add a second sink mock and cover complete join envelope parsing, numeric/string ID compatibility, missing tenant/result/command rejection, invalid outcome rejection, sink failure bubbling for Kafka retry, and continued health-event routing.

- [ ] **Step 2: Add event/sink contracts**

```java
public interface ProtocolGroupJoinResultReportedSink {
    void handleJoinResultReported(ProtocolGroupJoinResultReportedEvent event);
}
```

The platform event contains the approved data fields plus envelope `eventId/workerId`. The task adapter performs a field-for-field conversion and calls `JoinTaskResultService.apply`.

- [ ] **Step 3: Extend consumer dispatch**

Add `EVENT_GROUP_JOIN_RESULT_REPORTED = "group.join_result_reported"`; dispatch with a `switch` so unknown group events are still logged/skipped and `group.health_reported` behavior remains unchanged. Never use protocol `occurredAt/timestamp` as the scheduling base; the task Service captures its own application time.

- [ ] **Step 4: Run GREEN and commit**

```bash
mvn -q -Dtest=ProtocolGroupEventConsumerTest,JoinTaskResultReportedSinkAdapterTest,JoinTaskResultServiceTest test
git add armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupJoinResultReportedEvent.java armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupJoinResultReportedSink.java armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumer.java armada-api/src/main/java/com/armada/task/service/impl/JoinTaskResultReportedSinkAdapter.java armada-api/src/test/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumerTest.java armada-api/src/test/java/com/armada/task/service/JoinTaskResultReportedSinkAdapterTest.java
git commit -m "feat: consume group join result events"
```

### Task 9: Remove the lane worker and document the model

**Files:**
- Delete: `armada-api/src/main/java/com/armada/task/worker/JoinTaskWorker.java`
- Delete: `armada-api/src/test/java/com/armada/task/worker/JoinTaskWorkerTest.java`
- Modify: `.harness/wiki/数据模型.md`
- Create: `.harness/changes/2026-07-16-join-task-kafka-scheduling.md`

- [ ] **Step 1: Prove no production reference remains**

```bash
rg -n "JoinTaskWorker|join-task.worker|account-lane-pool-size|Thread\.sleep" armada-api/src/main armada-api/src/test
```

Expected before deletion: only the worker class/test and obsolete configuration references. Expected after deletion: no matches in the join-task production path.

- [ ] **Step 2: Delete the worker only after Tasks 1-8 are green**

Keep `GroupJoinPort`, Web/Android HTTP adapters, and their tests because the existing HTTP capability remains supported.

- [ ] **Step 3: Update generated data-model documentation and change record**

Document 17 `join_task_result` columns, the two new indexes, business/dispatch state matrix, topic routing, no-restart-recovery boundary, and rollback switch `JOIN_TASK_DISPATCHER_ENABLED=false`. Do not include broker addresses, credentials, complete invite links, or phone numbers.

- [ ] **Step 4: Run focused and full verification**

Confirm the DB target is local/test, then run:

```bash
./dbtest.sh 'JoinTaskMigrationDbTest,JoinTaskResultMapperDbTest,JoinTaskMapperDbTest,JoinTaskDispatchCapacityDbTest,ProtocolCommandOutboxMapperDbTest'
mvn -q -Dtest=JoinTaskStartServiceTest,JoinTaskCreateServiceTest,AccountProtocolLookupServiceTest,ProtocolCommandOutboxServiceImplTest,JoinTaskInviteCodeParserTest,JoinTaskIntervalPolicyTest,JoinTaskResultServiceTest,JoinTaskDispatchTransactionServiceTest,JoinTaskDispatchCoordinatorTest,JoinTaskDispatchSchedulerTest,ProtocolGroupEventConsumerTest,JoinTaskResultReportedSinkAdapterTest test
mvn -q test
```

Expected: all focused tests pass; full-suite failures, if any, must be rerun individually and classified before completion.

- [ ] **Step 5: Commit cleanup/docs**

```bash
git add -u armada-api/src/main/java/com/armada/task/worker/JoinTaskWorker.java armada-api/src/test/java/com/armada/task/worker/JoinTaskWorkerTest.java
git add .harness/wiki/数据模型.md .harness/changes/2026-07-16-join-task-kafka-scheduling.md
git commit -m "refactor: retire join task lane worker"
```

### Task 10: Cross-repository contract and rollout gate

**Files:**
- Verify only; no remote mutation in this task.

- [ ] **Step 1: Compare the three wire contracts byte-for-field**

Verify both protocol plans accept Armada's `group.join.requested` payload and emit the exact `group.join_result_reported` data fields. Confirm Web uses the master topic, Android uses the Android command topic, and both publish to the group event topic.

- [ ] **Step 2: Verify failure scenarios**

Run contract fixtures for outbox send timeout/retry/DEAD, protocol `TIMEOUT`, result publisher failure, duplicate command, duplicate result, stale command ID, and stale attempt. Confirm transport replays retain `commandId/attemptNo`; only a new business retry changes both.

- [ ] **Step 3: Enforce the rollout gate**

Before any environment deployment, obtain separate confirmation of the target environment and verify there are no legacy `RUNNING` join tasks. Deploy and verify Web and Android consumers first. Only then deploy Armada with the scheduler initially disabled, validate configuration/topic names, and explicitly enable it. No SSH, deployment, topic creation, or database mutation is authorized by this plan.
