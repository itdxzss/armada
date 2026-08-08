# Pull Task Group Ban Termination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop only the ordinary pull-task execution row whose WhatsApp group is explicitly suspended or terminated, while persisting the group ban and allowing sibling group executions to continue.

**Architecture:** Keep the existing `group.health_reported` event as the only trigger. The group health service returns the tenant-resolved `groupLinkId` after persisting the health fact; the existing health sink invokes a task-domain termination port only for `BANNED + CHAT_SUSPENDED/CHAT_TERMINATED`. The task lifecycle implementation reuses its existing per-execution command cancellation, puller release, and parent aggregation logic, and the unknown-result scan explicitly excludes `GROUP_BANNED` rows.

**Tech Stack:** Java 17, Spring Boot, Spring transactions, MyBatis/MyBatis-Plus tenant interception, JUnit 5, Mockito, AssertJ, H2 MySQL mode, Maven.

## Global Constraints

- Trigger termination only when `health=BANNED` and `errorCode` is exactly `CHAT_SUSPENDED` or `CHAT_TERMINATED`, case-insensitively after trimming.
- Do not infer a group ban from HTTP 403, `TEMPORARY_FAILURE`, account offline, proxy failure, or another generic operation failure.
- Terminate only the matching group execution row; sibling executions under the same parent task continue unchanged.
- Persist the terminal state as `FAILED / GROUP_BANNED / 群已被封禁`.
- Clear the matching execution's wait state, next run time, and lease; cancel only its unpublished commands and planned facts; release only its pullers.
- Exclude `GROUP_BANNED` executions from future unknown-result reconciliation scans.
- Duplicate ban events are idempotent; later healthy reports do not reactivate the failed execution.
- Add no database table, column, Kafka topic, dependency, or deployment step.
- Preserve all user-owned dirty work and stage only files listed by the current task.
- During execution, follow the repository rules and use `unit-test-write`, `unit-test-ci`, and `superpowers:verification-before-completion`.

---

## File Map

- `GroupLinkHealthReportService.java` and its implementation return the tenant-resolved group link ID after persisting health.
- `GroupLinkHealthReportedSinkAdapter.java` recognizes only explicit suspension/termination and calls the task termination port.
- `PullTaskGroupBanTerminationService.java` defines the task-domain entry point.
- `PullTaskStandardExecutionLifecycleServiceImpl.java` reuses per-execution cancellation, puller release, and parent completion.
- `PullTaskExecutionReasonCode.java` and `PullTaskExecutionTerminalTransition.java` carry `GROUP_BANNED` to persistence.
- `PullTaskUnknownReconciliationCriteria.java` carries excluded reason codes into the cross-tenant scan.
- `PullTaskGroupExecutionMapper.java` / `.xml` select active rows by group link, persist terminal reasons, and exclude banned rows from reconciliation.
- Focused JUnit/H2 tests prove exact routing, storage, tenant isolation, sibling preservation, idempotency, and no reconciliation retry.
- `.harness/changes/2026-08-08-pull-task-group-ban-terminal.md` records verification evidence; no migration or rollback SQL is required.

---

### Task 1: Return the resolved group identity from health persistence

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupLinkHealthReportService.java:8-17`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkHealthReportServiceImpl.java:56-90`
- Test: `armada-api/src/test/java/com/armada/group/service/GroupLinkHealthReportServiceImplTest.java:38-171`

**Interfaces:**
- Consumes: existing `GroupLinkHealthReportedEvent` and tenant-scoped `GroupLinkMapper.selectActiveIdByGroupJid(String)`.
- Produces: `Long GroupLinkHealthReportService.applyHealthReported(GroupLinkHealthReportedEvent event)`, returning the resolved `group_link.id` or `null`.

- [ ] **Step 1: Write failing return-value tests**

Capture the return value in the healthy, JID-only banned, and unknown-JID tests:

```java
Long groupLinkId = service.applyHealthReported(event);

assertThat(groupLinkId).isEqualTo(200L);
```

For the JID-only banned case assert `203L`. For the unknown group case assert `null` and keep `verifyNoInteractions(healthMapper)`.

- [ ] **Step 2: Run the focused test and verify the compile failure**

Run:

```bash
mvn -q -pl armada-api -Dtest=GroupLinkHealthReportServiceImplTest test
```

Expected: test compilation fails because `applyHealthReported` still returns `void`.

- [ ] **Step 3: Implement the return contract**

Change the interface to:

```java
/**
 * 应用协议层群健康回报并返回租户内解析出的群入口。
 *
 * @param event 群链接健康检测回报事件
 * @return 已写入健康状态的群入口 ID；未匹配有效群时为 null
 */
Long applyHealthReported(GroupLinkHealthReportedEvent event);
```

Change the implementation signature to `public Long applyHealthReported(...)`, return `null` from the unknown-group branch, and return `groupLinkId` immediately after the health upsert/log statement. Keep the existing `TenantContext` restoration in `finally`.

- [ ] **Step 4: Run the focused test**

Run:

```bash
mvn -q -pl armada-api -Dtest=GroupLinkHealthReportServiceImplTest test
```

Expected: all tests in the class pass.

- [ ] **Step 5: Commit Task 1 only**

```bash
git add armada-api/src/main/java/com/armada/group/service/GroupLinkHealthReportService.java armada-api/src/main/java/com/armada/group/service/impl/GroupLinkHealthReportServiceImpl.java armada-api/src/test/java/com/armada/group/service/GroupLinkHealthReportServiceImplTest.java
git commit -m "refactor: return resolved group from health report"
```

---

### Task 2: Add task-state storage primitives for a banned group

**Files:**
- Modify: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskExecutionReasonCode.java:5-52`
- Modify: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskExecutionTerminalTransition.java:3-13`
- Modify: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskUnknownReconciliationCriteria.java:5-49`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupExecutionMapper.java:36-316`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationCoordinator.java:88-115`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml:27-191,332-354`
- Test: `armada-api/src/test/java/com/armada/task/mapper/PullTaskGroupExecutionMapperInMemoryTest.java`
- Test: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationCoordinatorTest.java`

**Interfaces:**
- Consumes: caller-supplied tenant context, group link ID, task type/mode, active parent statuses, non-terminal execution statuses, and excluded reconciliation reasons.
- Produces:
  - `PullTaskExecutionReasonCode.GROUP_BANNED` with message `群已被封禁`.
  - `List<PullTaskGroupExecution> selectActiveByGroupLinkId(long, List<Integer>, String, String, List<String>)`.
  - `PullTaskExecutionTerminalTransition.reasonCode()` and `reasonMessage()`.
  - `PullTaskUnknownReconciliationCriteria.excludedReasonCodes()`.

- [ ] **Step 1: Write a failing active-row selection test**

Create tenant-7 ordinary executions with different `group_link_id` values and a same-link execution in tenant 8. Call:

```java
List<PullTaskGroupExecution> selected = mapper.selectActiveByGroupLinkId(
        9000L,
        List.of(
                PullTaskExecutionStatus.WAIT_START.code(),
                PullTaskExecutionStatus.EXECUTING.code(),
                PullTaskExecutionStatus.WAIT_RESOURCE.code()),
        PullTaskType.STANDARD.name(),
        "NORMAL_LINK",
        List.of(
                PullTaskStandardStatus.EXECUTING.name(),
                PullTaskStandardStatus.PAUSED.name()));

assertThat(selected)
        .extracting(PullTaskGroupExecution::getTaskId)
        .containsExactly(100L);
```

The fixture must also include a completed parent and a `GROUP_MARKETING` parent to prove both are excluded.

- [ ] **Step 2: Write a failing terminal-reason persistence test**

Construct:

```java
PullTaskExecutionTerminalTransition transition =
        new PullTaskExecutionTerminalTransition(
                100L, row.getId(), PullTaskExecutionStatus.EXECUTING.code(), 2,
                PullTaskExecutionStatus.FAILED.code(), 0,
                PullTaskExecutionReasonCode.GROUP_BANNED.name(),
                PullTaskExecutionReasonCode.GROUP_BANNED.message(),
                900L, 900L);

assertThat(mapper.transitionTerminal(transition)).isEqualTo(1);
PullTaskGroupExecution terminal = mapper.selectById(row.getId());
assertThat(terminal.getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.FAILED.code());
assertThat(terminal.getReasonCode()).isEqualTo("GROUP_BANNED");
assertThat(terminal.getReasonMessage()).isEqualTo("群已被封禁");
assertThat(terminal.getWaitResourceType()).isNull();
assertThat(terminal.getNextRunAt()).isZero();
assertThat(terminal.getLockOwner()).isNull();

assertThat(mapper.transitionProtocolResult(new PullTaskExecutionResultTransition(
        row.getId(), 100L, terminal.getVersion(),
        PullTaskExecutionStatus.EXECUTING.code(),
        PullTaskExecutionStage.PULL_EXECUTION.code(),
        PullTaskExecutionStage.PULL_EXECUTION.code(), 0, 0L, 901L))).isZero();
```

The final assertion proves a late protocol result cannot reactivate the failed row.

- [ ] **Step 3: Write a failing reconciliation exclusion test**

Extend the terminal-row scan test with two executions containing open facts: one ordinary terminal row and one `FAILED/GROUP_BANNED` row. Construct criteria with:

```java
new PullTaskUnknownReconciliationCriteria(
        scope,
        executionStatuses,
        List.of(PullTaskExecutionReasonCode.GROUP_BANNED.name()),
        parent,
        facts)
```

Assert that the ordinary terminal row remains in the candidate result and the banned row does not.

- [ ] **Step 4: Run the mapper/coordinator tests and verify failure**

```bash
mvn -q -pl armada-api -Dtest=PullTaskGroupExecutionMapperInMemoryTest,PullTaskUnknownResultReconciliationCoordinatorTest test
```

Expected: compilation fails for the missing enum, DTO fields, criteria field, and mapper method.

- [ ] **Step 5: Add the enum and DTO fields**

Add:

```java
/** WhatsApp 明确通知目标群已暂停或终止。 */
GROUP_BANNED("群已被封禁"),
```

Expand the terminal transition:

```java
public record PullTaskExecutionTerminalTransition(
        long taskId,
        long executionId,
        int expectedExecutionStatus,
        int expectedVersion,
        int targetExecutionStatus,
        int targetManualPaused,
        String reasonCode,
        String reasonMessage,
        long finishedAt,
        long now) {
}
```

Insert `List<String> excludedReasonCodes` after `executionStatuses` in `PullTaskUnknownReconciliationCriteria`. In its compact constructor use:

```java
executionStatuses = List.copyOf(executionStatuses);
excludedReasonCodes = List.copyOf(excludedReasonCodes);
```

Update the production coordinator and mapper-test helper to pass `List.of(PullTaskExecutionReasonCode.GROUP_BANNED.name())`. In the coordinator test assert:

```java
assertThat(criteria.getValue().excludedReasonCodes()).containsExactly("GROUP_BANNED");
```

- [ ] **Step 6: Add the mapper selection method and SQL**

Add:

```java
List<PullTaskGroupExecution> selectActiveByGroupLinkId(
        @Param("groupLinkId") long groupLinkId,
        @Param("executionStatuses") List<Integer> executionStatuses,
        @Param("parentTaskType") String parentTaskType,
        @Param("parentTaskMode") String parentTaskMode,
        @Param("parentStatuses") List<String> parentStatuses);
```

Add tenant-intercepted SQL after `selectById`:

```xml
<select id="selectActiveByGroupLinkId"
        resultType="com.armada.task.model.entity.PullTaskGroupExecution">
  SELECT execution_row.*
  FROM pull_task_group_execution execution_row
  WHERE execution_row.group_link_id = #{groupLinkId}
    AND execution_row.execution_status IN
    <foreach collection="executionStatuses" item="status"
             open="(" separator="," close=")">#{status}</foreach>
    AND EXISTS (
      SELECT 1
      FROM pull_task parent_task
      WHERE parent_task.id = execution_row.task_id
        AND parent_task.tenant_id = execution_row.tenant_id
        AND parent_task.deleted_at IS NULL
        AND parent_task.task_type = #{parentTaskType}
        AND parent_task.mode = #{parentTaskMode}
        AND parent_task.status IN
        <foreach collection="parentStatuses" item="status"
                 open="(" separator="," close=")">#{status}</foreach>
    )
  ORDER BY execution_row.id ASC
</select>
```

In `transitionTerminal` set:

```xml
reason_code = #{transition.reasonCode},
reason_message = #{transition.reasonMessage},
```

Immediately after the status condition in `selectUnknownResultCandidates` add:

```xml
<if test="criteria.excludedReasonCodes != null and criteria.excludedReasonCodes.size() > 0">
  AND (execution_row.reason_code IS NULL OR execution_row.reason_code NOT IN
    <foreach collection="criteria.excludedReasonCodes" item="reason"
             open="(" separator="," close=")">#{reason}</foreach>)
</if>
```

- [ ] **Step 7: Keep manual group-end semantics unchanged**

Update `PullTaskStandardExecutionLifecycleServiceImpl.end`:

```java
new PullTaskExecutionTerminalTransition(
        taskId, executionId, execution.getExecutionStatus(), execution.getVersion(),
        PullTaskExecutionStatus.ABANDONED.code(), NOT_PAUSED,
        null, null, now, now)
```

Find and update any remaining constructor calls:

```bash
grep -R -n "new PullTaskExecutionTerminalTransition" armada-api/src/main armada-api/src/test
```

- [ ] **Step 8: Run tests and validate XML**

```bash
mvn -q -pl armada-api -Dtest=PullTaskGroupExecutionMapperInMemoryTest,PullTaskUnknownResultReconciliationCoordinatorTest test
xmllint --noout armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml
```

Expected: all tests pass and `xmllint` exits 0.

- [ ] **Step 9: Commit Task 2 only**

```bash
git add armada-api/src/main/java/com/armada/task/model/enums/PullTaskExecutionReasonCode.java armada-api/src/main/java/com/armada/task/model/dto/PullTaskExecutionTerminalTransition.java armada-api/src/main/java/com/armada/task/model/dto/PullTaskUnknownReconciliationCriteria.java armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupExecutionMapper.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationCoordinator.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardExecutionLifecycleServiceImpl.java armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml armada-api/src/test/java/com/armada/task/mapper/PullTaskGroupExecutionMapperInMemoryTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationCoordinatorTest.java
git commit -m "feat: add banned group terminal state primitives"
```

---

### Task 3: Terminate the matching execution from an explicit ban event

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/service/PullTaskGroupBanTerminationService.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardExecutionLifecycleServiceImpl.java:28-245`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkHealthReportedSinkAdapter.java:1-44`
- Create test: `armada-api/src/test/java/com/armada/group/service/GroupLinkHealthReportedSinkAdapterTest.java`
- Test: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardExecutionLifecycleServiceTest.java`

**Interfaces:**
- Consumes: Task 1's resolved group link and Task 2's mapper/terminal primitives.
- Produces: `void PullTaskGroupBanTerminationService.terminateBannedGroup(long tenantId, long groupLinkId)`.

- [ ] **Step 1: Write failing adapter routing tests**

Create a Mockito test with mocked `GroupLinkHealthReportService` and `PullTaskGroupBanTerminationService`. Stub:

```java
when(healthService.applyHealthReported(any())).thenReturn(203L);

adapter.handleHealthReported(protocolEvent("BANNED", "CHAT_SUSPENDED"));

verify(terminationService).terminateBannedGroup(12L, 203L);
```

Repeat for `" banned " / " chat_terminated "` to pin trim/case normalization. Verify no termination for these pairs:

```text
BANNED + ACCOUNT_BANNED
ERROR + CHAT_SUSPENDED
BANNED + null
HEALTHY + null
```

For each negative case use `verifyNoInteractions(terminationService)`. Add an unknown-group test where health persistence returns `null`; even `BANNED + CHAT_SUSPENDED` must not invoke termination.

Add failure propagation tests:

```java
when(healthService.applyHealthReported(any()))
        .thenThrow(new IllegalStateException("health write failed"));
assertThatThrownBy(() -> adapter.handleHealthReported(
        protocolEvent("BANNED", "CHAT_SUSPENDED")))
        .isInstanceOf(IllegalStateException.class);
verifyNoInteractions(terminationService);
```

In a separate test, let health persistence return `203L`, make
`terminationService.terminateBannedGroup(12L, 203L)` throw, and assert the same
exception leaves `handleHealthReported`; the Kafka container can then redeliver.

- [ ] **Step 2: Write failing lifecycle integration tests**

Autowire `PullTaskGroupBanTerminationService` in `PullTaskStandardExecutionLifecycleServiceTest`. Make fixture executions use distinct `group_link_id` values. Seed two non-terminal siblings and existing command/material facts, then call:

```java
banTerminationService.terminateBannedGroup(7L, 9001L);
```

Assert:

```java
assertThat(intColumn("execution_status", "pull_task_group_execution", 11L))
        .isEqualTo(PullTaskExecutionStatus.FAILED.code());
assertThat(stringColumn("reason_code", 11L)).isEqualTo("GROUP_BANNED");
assertThat(stringColumn("reason_message", 11L)).isEqualTo("群已被封禁");
assertThat(intColumn("execution_status", "pull_task_group_execution", 12L))
        .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
assertThat(taskMapper.selectLifecycle(1L).getStatus()).isEqualTo("EXECUTING");
```

Reuse the existing per-execution end assertions to prove only execution 11 has unpublished facts canceled and pullers released.

- [ ] **Step 3: Add idempotency, tenant, and completion tests**

Pin duplicate behavior:

```java
banTerminationService.terminateBannedGroup(7L, 9001L);
int version = intColumn("version", "pull_task_group_execution", 11L);
banTerminationService.terminateBannedGroup(7L, 9001L);
assertThat(intColumn("version", "pull_task_group_execution", 11L)).isEqualTo(version);
```

Call with tenant 8 for a tenant-7 link and assert no tenant-7 row changes. Terminate the last non-terminal row of a running parent and assert `COMPLETED`. Terminate the last row of a paused parent and assert it remains `PAUSED` until the existing resume aggregation runs. Set an outer `TenantContext` before the call and assert it is restored afterward.

- [ ] **Step 4: Run tests and verify failure**

```bash
mvn -q -pl armada-api -Dtest=GroupLinkHealthReportedSinkAdapterTest,PullTaskStandardExecutionLifecycleServiceTest test
```

Expected: compilation/tests fail because the new port and routing do not exist.

- [ ] **Step 5: Create the task-domain port**

```java
package com.armada.task.service;

/** WhatsApp 明确封禁群后终止对应普通拉群执行行。 */
public interface PullTaskGroupBanTerminationService {

    /**
     * 终止当前租户内占用指定群入口的非终态普通拉群执行行。
     *
     * @param tenantId 事件租户 ID
     * @param groupLinkId 已解析的群入口 ID
     */
    void terminateBannedGroup(long tenantId, long groupLinkId);
}
```

- [ ] **Step 6: Implement termination by reusing existing lifecycle cleanup**

Make `PullTaskStandardExecutionLifecycleServiceImpl` implement both interfaces. Add deterministic SQL input lists:

```java
private static final List<Integer> NON_TERMINAL_STATUS_LIST = List.of(
        PullTaskExecutionStatus.WAIT_START.code(),
        PullTaskExecutionStatus.EXECUTING.code(),
        PullTaskExecutionStatus.WAIT_RESOURCE.code());
private static final List<String> ACTIVE_PARENT_STATUS_LIST = List.of(
        PullTaskStandardStatus.EXECUTING.name(),
        PullTaskStandardStatus.PAUSED.name());
```

Implement:

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void terminateBannedGroup(long tenantId, long groupLinkId) {
    Long previousTenant = TenantContext.get();
    try {
        TenantContext.set(tenantId);
        long now = currentTimeMillis.getAsLong();
        List<PullTaskGroupExecution> executions = resources.executionMapper()
                .selectActiveByGroupLinkId(
                        groupLinkId, NON_TERMINAL_STATUS_LIST,
                        PullTaskType.STANDARD.name(), NORMAL_LINK_MODE,
                        ACTIVE_PARENT_STATUS_LIST);
        for (PullTaskGroupExecution execution : executions) {
            failBannedExecution(execution, now);
        }
    } finally {
        restoreTenant(previousTenant);
    }
}
```

The helper is:

```java
private void failBannedExecution(PullTaskGroupExecution execution, long now) {
    PullTaskExecutionTerminalTransition transition =
            new PullTaskExecutionTerminalTransition(
                    execution.getTaskId(), execution.getId(),
                    execution.getExecutionStatus(), execution.getVersion(),
                    PullTaskExecutionStatus.FAILED.code(), NOT_PAUSED,
                    PullTaskExecutionReasonCode.GROUP_BANNED.name(),
                    PullTaskExecutionReasonCode.GROUP_BANNED.message(),
                    now, now);
    if (resources.executionMapper().transitionTerminal(transition) != 1) {
        throw new IllegalStateException("群封禁终止执行行发生并发变化");
    }
    cancelNotSubmitted(execution.getTaskId(), execution.getId(), now);
    releasePullers(execution.getId(), now);
    completionService.completeIfTerminalByExecutionId(execution.getId(), now);
}
```

Add a private `restoreTenant(Long)` matching other Kafka-driven task services. A CAS miss throws so Kafka can redeliver; a duplicate event after commit selects no non-terminal row and returns normally.

- [ ] **Step 7: Route only explicit group signals from the health adapter**

Inject `PullTaskGroupBanTerminationService` into `GroupLinkHealthReportedSinkAdapter`. Add:

```java
private static final Set<String> GROUP_BAN_REASONS = Set.of(
        "CHAT_SUSPENDED", "CHAT_TERMINATED");

private static boolean isExplicitGroupBan(ProtocolGroupHealthReportedEvent event) {
    return "BANNED".equals(normalize(event.health()))
            && GROUP_BAN_REASONS.contains(normalize(event.errorCode()));
}

private static String normalize(String value) {
    return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
}
```

Process in this order:

```java
Long groupLinkId = service.applyHealthReported(toGroupEvent(event));
if (groupLinkId != null && isExplicitGroupBan(event)) {
    terminationService.terminateBannedGroup(event.tenantId(), groupLinkId);
}
```

Define the conversion helper exactly as:

```java
private static GroupLinkHealthReportedEvent toGroupEvent(
        ProtocolGroupHealthReportedEvent event) {
    return new GroupLinkHealthReportedEvent(
            event.tenantId(), event.groupLinkId(), event.groupJid(), event.health(),
            event.memberCount(), event.checkedAt(), event.errorCode(),
            event.protocolAccountId(), event.eventId());
}
```

Do not add inference in `ProtocolGroupEventConsumer` or operation-result classifiers.

- [ ] **Step 8: Run the focused tests**

```bash
mvn -q -pl armada-api -Dtest=GroupLinkHealthReportedSinkAdapterTest,PullTaskStandardExecutionLifecycleServiceTest test
```

Expected: all tests pass; only the matching execution fails, siblings continue, duplicates no-op, and tenant context is restored.

- [ ] **Step 9: Run the focused regression set**

```bash
mvn -q -pl armada-api -Dtest=GroupLinkHealthReportServiceImplTest,ProtocolGroupEventConsumerTest,GroupLinkHealthReportedSinkAdapterTest,PullTaskGroupExecutionMapperInMemoryTest,PullTaskStandardExecutionLifecycleServiceTest,PullTaskUnknownResultReconciliationCoordinatorTest test
```

Expected: all listed classes pass.

- [ ] **Step 10: Commit Task 3 only**

```bash
git add armada-api/src/main/java/com/armada/task/service/PullTaskGroupBanTerminationService.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardExecutionLifecycleServiceImpl.java armada-api/src/main/java/com/armada/group/service/impl/GroupLinkHealthReportedSinkAdapter.java armada-api/src/test/java/com/armada/group/service/GroupLinkHealthReportedSinkAdapterTest.java armada-api/src/test/java/com/armada/task/service/PullTaskStandardExecutionLifecycleServiceTest.java
git commit -m "feat: stop pull execution when group is banned"
```

---

### Task 4: Run final verification and record evidence

**Files:**
- Create: `.harness/changes/2026-08-08-pull-task-group-ban-terminal.md`

**Interfaces:**
- Consumes: implementation and test output from Tasks 1-3.
- Produces: a change record with exact evidence and explicit no-deployment status.

- [ ] **Step 1: Run focused tests under Java 17**

```bash
java -version
mvn -q -pl armada-api -Dtest=GroupLinkHealthReportServiceImplTest,ProtocolGroupEventConsumerTest,GroupLinkHealthReportedSinkAdapterTest,PullTaskGroupExecutionMapperInMemoryTest,PullTaskStandardExecutionLifecycleServiceTest,PullTaskUnknownResultReconciliationCoordinatorTest test
```

Expected: Java reports version 17 and all focused tests pass.

- [ ] **Step 2: Compile and validate XML**

```bash
mvn -q -pl armada-api -DskipTests compile
xmllint --noout armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml
```

Expected: both commands exit 0.

- [ ] **Step 3: Check whitespace and repository status**

```bash
git show --check --stat $(git log -1 --format=%H --grep='^refactor: return resolved group from health report$')
git show --check --stat $(git log -1 --format=%H --grep='^feat: add banned group terminal state primitives$')
git show --check --stat $(git log -1 --format=%H --grep='^feat: stop pull execution when group is banned$')
git status --short
```

Expected: all three commit checks exit 0. Status contains no accidental edits to user-owned files; unrelated dirty files are listed in the handoff and remain unstaged.

- [ ] **Step 4: Review the final implementation diff**

```bash
git show --stat --oneline $(git log -1 --format=%H --grep='^refactor: return resolved group from health report$')
git show --stat --oneline $(git log -1 --format=%H --grep='^feat: add banned group terminal state primitives$')
git show --stat --oneline $(git log -1 --format=%H --grep='^feat: stop pull execution when group is banned$')
git show --format=fuller $(git log -1 --format=%H --grep='^feat: stop pull execution when group is banned$') -- armada-api/src/main/java/com/armada/group armada-api/src/main/java/com/armada/task armada-api/src/test/java/com/armada/group armada-api/src/test/java/com/armada/task
```

Confirm the diff contains: exact trigger pair, tenant-scoped group selection, `FAILED/GROUP_BANNED`, per-execution cancellation/release, sibling preservation, unknown-scan exclusion, no schema change, and no generic 403 inference.

- [ ] **Step 5: Create the verified change record**

After Steps 1-4 pass, create the file with this exact content:

```markdown
# 变更记录：普通拉群任务遇群封禁后终止单群

- 日期 / 分支: 2026-08-08 / 1.0.2-snapshot
- 需求来源: WhatsApp 明确 suspended/terminated 后停止对应群执行，不影响同任务其他群
- 状态: 实现完成，未部署

## 目标

将明确群封禁事实同步为 FAILED/GROUP_BANNED 执行终态，并停止该群后续调度与未知结果收敛。

## 关键边界

- 仅处理 BANNED + CHAT_SUSPENDED/CHAT_TERMINATED。
- 不根据普通 403 或临时失败推断封禁。
- 只终止命中群，其他群继续。
- 无数据库迁移。

## 验证

- Java 17 定向测试通过。
- armada-api 编译通过。
- PullTaskGroupExecutionMapper.xml 校验通过。
- git diff --check 通过。

## 部署

- 未部署；没有修改远程环境或测试数据。
```

- [ ] **Step 6: Commit the verified change record**

```bash
git add .harness/changes/2026-08-08-pull-task-group-ban-terminal.md
git commit -m "docs: record pull task group ban termination"
```

- [ ] **Step 7: Perform the required completion gate**

Invoke `superpowers:verification-before-completion` and the repository `unit-test-ci` skill. Re-run every command those skills require before claiming completion. Do not deploy, SSH, or update remote test data without a separate user request.
