# Normal Link Pull Wave Dispatch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make normal-link pull calls dispatch on their configured clock without waiting for earlier callbacks, settle retries only after the whole wave closes, and keep one puller until an account-level failure requires takeover.

**Architecture:** Armada adds a persisted internal wave above the existing identity-stable `pull_task_pull_call` and per-number attempt ledger. A wave freezes all calls, dispatches one due call per scheduler claim, then enters collection; callbacks only project facts and wake collection, while a dedicated settlement transaction creates the next retry wave. The group execution stores the sticky puller plus an assignment generation so delayed callbacks cannot invalidate a newer assignment.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis/MyBatis-Plus, Flyway, H2 MySQL mode, JUnit 5, Mockito, AssertJ, Maven; existing TypeScript/Jest protocol contract for verification only.

## Global Constraints

- Scope is only ordinary pull tasks in `NORMAL_LINK` mode; do not change other pull, join, marketing, or group-creation task schedulers.
- A user-visible “round” inside the original plan maps to one `pull_task_pull_call`; an internal wave contains all such initial calls or all calls of one later retry set.
- Freeze every call and participant in a wave before dispatch starts. After freezing, never add a participant or move it to another call; a late success may only cancel its still-`PLANNED` attempt.
- While a wave is `DISPATCHING`, submit the next call when its persisted interval expires even if every earlier call is still `SUBMITTED`.
- A callback immediately updates the immutable attempt and participant aggregate, but must not advance `next_call_seq`, rewrite `next_dispatch_at`, or create a retry call in the current wave.
- Enter a retry wave only after every call in the previous wave was processed and every attempt reached a terminal/released state.
- Every explicit participant failure consumes one failure count. Initial execution plus three retries means the fourth explicit failure is final.
- `UNKNOWN + NOT_STARTED` and a successful roster query that finds the number absent are retry candidates without consuming failure count.
- Not-started and roster-confirmed-absent retries have no independent counter; they continue until success, four explicit failures, final unknown, group termination, or manual end.
- Missing/uncertain results retain the existing 60,000 ms protection and 30,000 ms scan interval. Roster lookup remains synchronous HTTP through `GroupMemberListPort`, not Kafka.
- A failed/skipped roster query closes the participant as final `UNKNOWN`; do not assume absence, retry the number, or repeat the HTTP query indefinitely.
- Preserve monotonic success. Any old attempt may promote the aggregate to success; old failure/unknown facts never downgrade or reopen a newer execution.
- Store the sticky puller on `pull_task_group_execution`. Do not rotate it after a successful call or wave. Rotate only on confirmed account-level inability.
- Compare both `puller_group_account_id` and `puller_assignment_seq` before invalidating a sticky puller. This must remain safe for `A -> B -> A` reuse.
- Account not found/offline, `NEED_REAUTH`, `ACCOUNT_REACHOUT_RESTRICTED`, `RATE_LIMITED`, and `GROUP_PERMISSION_DENIED` trigger takeover. Participant-specific failure does not. `GROUP_UNAVAILABLE` terminates the group execution rather than rotating all pullers.
- Network/HTTP/owner/worker transient faults do not permanently mark an account unavailable. If side-effect start is uncertain, preserve the command and reconcile; never immediately reissue the same participants.
- Compute the next pull-call deadline from submission time: `submitted_at + max(pull_interval, persisted 3–5 second silence)`. A result callback never contributes to this deadline.
- Pause blocks new calls/new waves but not callbacks or read-only reconciliation. Resume dispatches at most one overdue call immediately and then resumes normal spacing. End cancels only unpublished work and never creates a retry wave.
- Use Flyway `V107`; the current highest migration is `V106`. V107 must contain no data backfill DML.
- Historical completed rows keep nullable wave fields. Runtime compatibility handles only open calls of an execution row and must never resend a submitted legacy call.
- Do not add or operate a production rollout flag in this implementation. Environment activation, gray release, database rollout, and code rollback require a separate approved deployment plan; this plan ends at locally verified forward-compatible code.
- Protocol batch callbacks already provide `outcome + executionState + reasonCode`; this plan audits that contract but does not modify protocol code. A failed audit is a reported blocker requiring a separate protocol change.
- Keep Java classes under 800 lines, methods under 100 lines, and newly introduced public service/Mapper method parameter lists at five or fewer. Use nested records for transition inputs; preserve the existing 10-field call-detail response shape.
- Mapper behavior must be tested against the real XML and production tenant interceptor in H2 MySQL mode; Mockito-only Mapper tests are insufficient.
- Run Java commands from `armada-api/`. Do not use `-pl armada-api`; this repository is not a multi-module Maven build.
- Do not deploy, access a remote environment, execute a real database migration, or modify historical production data.
- Preserve user-owned dirty files and `.claude/worktrees`; stage only files named by the current task.

---

## File Map and Contract Ownership

### Persisted wave model

- `armada-api/src/main/resources/db/migration/V107__pull_task_pull_wave.sql` owns the forward-only schema.
- `PullTaskPullWave`, `PullTaskPullWaveType`, and `PullTaskPullWaveStatus` own the Java representation.
- `PullTaskPullWaveMapper.java/.xml` owns wave creation, dispatch progression, collection wakeup, settlement, cancellation, and tenant isolation.
- `pull_task_group_execution.active_pull_wave_id` identifies the only active wave.
- `pull_task_group_execution.active_puller_group_account_id + puller_assignment_seq` own sticky assignment identity.
- `pull_task_pull_call.pull_wave_id + wave_call_seq + puller_assignment_seq` freeze call correlation.
- `pull_task_pull_call_member_attempt.pull_wave_id + puller_assignment_seq` permit direct wave convergence and delayed callback auditing.

### Planning and dispatch

- `PullTaskPullWavePlanningTransactionService` creates or resumes a whole wave and returns its next bounded action.
- `PullTaskStickyPullerTransactionService` validates/selects one puller, changes the assignment generation, and binds only a `PLANNED` call.
- `PullTaskPullExecutionProcessor` routes `DISPATCHING` to contacts/batch submission and `COLLECTING` to settlement.
- `PullTaskBatchAddTransactionService` keeps Outbox submission ownership but no longer rotates pullers or waits 60 seconds before the next call.

### Callback and collection

- `PullTaskPullCallParticipantResultService` continues to own current/late attempt facts and puller-risk classification.
- `PullTaskPullWaveProgressService` may wake a collecting wave but cannot mutate a dispatching wave's clock.
- `PullTaskPullWaveSettlementTransactionService` is the only component allowed to settle a wave and create its successor.
- `PullTaskPullCallReconciliationService` owns one HTTP roster attempt and passes a three-way observation: present, absent, or query unavailable.

### Lifecycle and evidence

- Parent and single-execution lifecycle services cancel `PLANNED` calls/attempts and mark the active wave canceled while leaving published calls callback-capable.
- `.harness/changes/2026-08-09-normal-link-pull-wave-dispatch.md` records RED/GREEN commands, migration evidence, focused acceptance, and final regression results.

---

### Task 1: Add the persisted wave, assignment generation, and Mapper foundation

**Files:**

- Create: `.harness/changes/2026-08-09-normal-link-pull-wave-dispatch.md`
- Create: `armada-api/src/main/resources/db/migration/V107__pull_task_pull_wave.sql`
- Create: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskPullWave.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskPullWaveType.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskPullWaveStatus.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskPullWaveTransition.java`
- Create: `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullWaveMapper.java`
- Create: `armada-api/src/main/resources/mapper/task/PullTaskPullWaveMapper.xml`
- Create: `armada-api/src/test/java/com/armada/task/PullTaskPullWaveMigrationSqlTest.java`
- Create: `armada-api/src/test/java/com/armada/task/mapper/PullTaskPullWaveMapperInMemoryTest.java`
- Modify: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskGroupExecution.java`
- Modify: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskPullCall.java`
- Modify: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskPullCallMemberAttempt.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskPullCallMemberAttemptMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchema.java`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchemaSelfTest.java`

**Interfaces:**

```java
public enum PullTaskPullWaveType {
    INITIAL(1), RETRY(2);
    public int code();
}

public enum PullTaskPullWaveStatus {
    DISPATCHING(1), COLLECTING(2), SETTLED(3), CANCELED(4);
    public int code();
    public static boolean active(int code);
}

public record PullTaskPullWaveTransition(
        Scope scope,
        Target target,
        long now) {
    public record Scope(long id, long groupExecutionId,
                        int expectedStatus, int expectedVersion) {}
    public record Target(int status, int nextCallSeq,
                         long nextDispatchAt,
                         Long dispatchCompletedAt,
                         Long settledAt) {}
}
```

`PullTaskPullWaveMapper` produces these exact methods for later tasks:

```java
int insertInitialized(PullTaskPullWave row);
PullTaskPullWave selectById(@Param("id") long id);
PullTaskPullWave selectActiveByExecution(@Param("groupExecutionId") long executionId,
                                         @Param("statuses") List<Integer> statuses);
int transition(@Param("transition") PullTaskPullWaveTransition transition);
int wakeCollecting(@Param("id") long id,
                   @Param("expectedStatus") int expectedStatus,
                   @Param("now") long now);
int cancelByTask(@Param("taskId") long taskId,
                 @Param("activeStatuses") List<Integer> activeStatuses,
                 @Param("targetStatus") int targetStatus,
                 @Param("now") long now);
int cancelByExecution(@Param("groupExecutionId") long executionId,
                      @Param("activeStatuses") List<Integer> activeStatuses,
                      @Param("targetStatus") int targetStatus,
                      @Param("now") long now);
```

- [ ] **Step 1: Create the change tracker and write failing migration assertions**

Start the change tracker with status `实施中`, the approved design path, this plan path, and unchecked sections for schema, planning, dispatch, callback, reconciliation, lifecycle, protocol audit, and final verification.

Add tests that load V107 as text and assert:

```java
assertThat(sql).contains("CREATE TABLE pull_task_pull_wave");
assertThat(sql).contains("active_pull_wave_id BIGINT DEFAULT NULL");
assertThat(sql).contains("active_puller_group_account_id BIGINT DEFAULT NULL");
assertThat(sql).contains("puller_assignment_seq BIGINT NOT NULL DEFAULT 0");
assertThat(sql).contains("pull_wave_id BIGINT DEFAULT NULL");
assertThat(sql).contains("wave_call_seq INT DEFAULT NULL");
assertThat(sql).doesNotContainIgnoringCase("INSERT INTO", "UPDATE ", "DELETE FROM");
```

- [ ] **Step 2: Run the migration/schema tests and verify RED**

Run from `armada-api/`:

```bash
mvn -q -Dtest=PullTaskPullWaveMigrationSqlTest,PullTaskNormalLinkSchemaSelfTest test
```

Expected: compilation/file assertions fail because V107 and the wave DDL do not exist.

- [ ] **Step 3: Add V107 and the Java model**

V107 must:

```sql
CREATE TABLE pull_task_pull_wave (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    group_execution_id BIGINT NOT NULL,
    wave_no INT NOT NULL,
    wave_type TINYINT NOT NULL,
    wave_status TINYINT NOT NULL,
    planned_call_count INT NOT NULL,
    next_call_seq INT NOT NULL DEFAULT 1,
    next_dispatch_at BIGINT NOT NULL DEFAULT 0,
    dispatch_completed_at BIGINT DEFAULT NULL,
    settled_at BIGINT DEFAULT NULL,
    version INT NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    active_execution_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN wave_status IN (1, 2) THEN group_execution_id ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_wave_no (tenant_id, group_execution_id, wave_no),
    UNIQUE KEY uq_pull_task_wave_active (tenant_id, active_execution_id),
    KEY idx_pull_task_wave_due (tenant_id, wave_status, next_dispatch_at, id)
);
```

Then alter the three existing tables. Planned calls/attempts must allow a null puller; submitted rows are guarded in Java and Mapper CAS:

```sql
ALTER TABLE pull_task_group_execution
    ADD COLUMN active_pull_wave_id BIGINT DEFAULT NULL,
    ADD COLUMN active_puller_group_account_id BIGINT DEFAULT NULL,
    ADD COLUMN puller_assignment_seq BIGINT NOT NULL DEFAULT 0;

ALTER TABLE pull_task_pull_call
    ADD COLUMN pull_wave_id BIGINT DEFAULT NULL,
    ADD COLUMN wave_call_seq INT DEFAULT NULL,
    ADD COLUMN puller_assignment_seq BIGINT DEFAULT NULL,
    MODIFY puller_group_account_id BIGINT DEFAULT NULL
        COMMENT '执行本次调用的拉手角色行ID;计划态可为空',
    MODIFY puller_account_id BIGINT DEFAULT NULL
        COMMENT '执行本次调用的拉手账号ID;计划态可为空',
    ADD UNIQUE KEY uq_pull_task_call_wave_seq
        (tenant_id, pull_wave_id, wave_call_seq);

ALTER TABLE pull_task_pull_call_member_attempt
    ADD COLUMN pull_wave_id BIGINT DEFAULT NULL,
    ADD COLUMN puller_assignment_seq BIGINT DEFAULT NULL,
    MODIFY puller_group_account_id BIGINT DEFAULT NULL
        COMMENT '本次真实执行拉手角色行ID;计划态可为空',
    ADD KEY idx_pull_task_attempt_wave
        (tenant_id, pull_wave_id, lifecycle_status, id);
```

Mirror this shape in `PullTaskNormalLinkSchema` and add getters/setters to the three entities.

- [ ] **Step 4: Write real Mapper XML tests before implementing the Mapper**

Cover exactly:

```text
insertDispatchingWaveGeneratesIdAndDefaults
onlyOneActiveWavePerExecutionIsAllowed
transitionDispatchingToCollectingUsesVersionAndStatusCas
wakeCollectingNeverChangesDispatchingWave
settledWaveAllowsNextActiveWave
tenantIsolationHidesAnotherTenantWave
plannedCallAndAttemptAllowNullPullerButPersistWaveIdentity
```

The wake assertion must prove `wakeCollecting(... DISPATCHING ...) == 0` and `wakeCollecting(... COLLECTING ...) == 1`.

- [ ] **Step 5: Implement the wave Mapper and extend existing result maps**

Use the project tenant interceptor; do not add `@InterceptorIgnore`. `transition` must increment `version` and apply all expected fields:

```xml
UPDATE pull_task_pull_wave
SET wave_status = #{transition.target.status},
    next_call_seq = #{transition.target.nextCallSeq},
    next_dispatch_at = #{transition.target.nextDispatchAt},
    dispatch_completed_at = #{transition.target.dispatchCompletedAt},
    settled_at = #{transition.target.settledAt},
    version = version + 1,
    updated_at = #{transition.now}
WHERE id = #{transition.scope.id}
  AND group_execution_id = #{transition.scope.groupExecutionId}
  AND wave_status = #{transition.scope.expectedStatus}
  AND version = #{transition.scope.expectedVersion}
```

Add all new columns to existing Mapper selects and inserts; do not rely on `SELECT *` for the three modified production entities.

- [ ] **Step 6: Run the foundation tests**

```bash
mvn -q -Dtest=PullTaskPullWaveMigrationSqlTest,PullTaskNormalLinkSchemaSelfTest,PullTaskPullWaveMapperInMemoryTest,PullTaskPullCallMapperInMemoryTest,PullTaskPullCallMemberAttemptMapperInMemoryTest test
```

Expected: all tests pass, V107 has no DML, and H2 enforces one active wave.

- [ ] **Step 7: Commit the schema foundation**

```bash
git add .harness/changes/2026-08-09-normal-link-pull-wave-dispatch.md armada-api/src/main/resources/db/migration/V107__pull_task_pull_wave.sql armada-api/src/main/java/com/armada/task/model/entity/PullTaskPullWave.java armada-api/src/main/java/com/armada/task/model/enums/PullTaskPullWaveType.java armada-api/src/main/java/com/armada/task/model/enums/PullTaskPullWaveStatus.java armada-api/src/main/java/com/armada/task/model/dto/PullTaskPullWaveTransition.java armada-api/src/main/java/com/armada/task/model/entity/PullTaskGroupExecution.java armada-api/src/main/java/com/armada/task/model/entity/PullTaskPullCall.java armada-api/src/main/java/com/armada/task/model/entity/PullTaskPullCallMemberAttempt.java armada-api/src/main/java/com/armada/task/mapper/PullTaskPullWaveMapper.java armada-api/src/main/resources/mapper/task/PullTaskPullWaveMapper.xml armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml armada-api/src/main/resources/mapper/task/PullTaskPullCallMemberAttemptMapper.xml armada-api/src/test/java/com/armada/task/PullTaskPullWaveMigrationSqlTest.java armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchema.java armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchemaSelfTest.java armada-api/src/test/java/com/armada/task/mapper/PullTaskPullWaveMapperInMemoryTest.java
git commit -m "feat: add pull wave persistence"
```

---

### Task 2: Freeze complete initial and retry waves

**Files:**

- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskParticipantPlanBinding.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskPullWaveCandidate.java`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWavePreparation.java`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWavePlanningSelection.java`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWavePlanningResources.java`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWavePlanningTransactionService.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullWavePlanningIntegrationTest.java`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskMaterialMemberMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskMaterialMemberMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupAccountMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskGroupAccountMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMemberAttemptMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskPullCallMemberAttemptMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskStationSelectionService.java`

**Interfaces:**

```java
public record PullTaskParticipantPlanBinding(
        long participantId,
        long attemptId,
        long pullCallId,
        long now) {}

public record PullTaskPullWaveCandidate(
        int participantType,
        long participantRefId,
        String targetPhone,
        String targetJid,
        long failureCount) {}

public record PullTaskPullWavePreparation(
        PullTaskPullWave wave,
        PullTaskPullCall call,
        PullTaskExecutionDispatchResult result) {
    public static PullTaskPullWavePreparation ready(
            PullTaskPullWave wave, PullTaskPullCall call);
    public static PullTaskPullWavePreparation completed(
            PullTaskExecutionDispatchResult result);
    public boolean ready();
}

@Component
public record PullTaskPullWavePlanningSelection(
        PullTaskStationSelectionService stationSelectionService,
        PullTaskBatchSizeSelector batchSizeSelector) {}

@Component
public record PullTaskPullWavePlanningResources(
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskPullWaveMapper waveMapper,
        PullTaskPullCallMapper pullCallMapper,
        PullTaskPullCallMemberAttemptMapper attemptMapper,
        PullTaskPullWavePlanningSelection selection) {}
```

`PullTaskPullCallMemberAttemptMapper` adds:

```java
List<PullTaskPullWaveCandidate> selectRetryCandidatesByWave(
        @Param("pullWaveId") long pullWaveId,
        @Param("maxFailureCount") long maxFailureCount);
int countOpenByWave(@Param("pullWaveId") long pullWaveId,
                    @Param("openStatuses") List<Integer> openStatuses);
```

The planner exposes one package-private successor factory to the settlement service in the same scheduler package:

```java
PullTaskPullWave createRetryWave(
        PullTaskGroupExecution execution,
        PullTaskPullWave settledWave,
        List<PullTaskPullWaveCandidate> candidates,
        long now);
```

- [ ] **Step 1: Write failing whole-wave planning tests**

Seed 21 materials with `pull_count_min = pull_count_max = 5`, zero stations, and assert:

```java
PullTaskPullWavePreparation result = service.prepare(candidate, "worker-1", 1_000L);

assertThat(result.ready()).isTrue();
assertThat(waveMapper.selectById(result.wave().getId()).getPlannedCallCount()).isEqualTo(5);
assertThat(callMapper.selectByExecution(EXECUTION_ID))
        .extracting(PullTaskPullCall::getPlannedMaterialCount)
        .containsExactly(5, 5, 5, 5, 1);
assertThat(callMapper.selectByExecution(EXECUTION_ID))
        .allSatisfy(call -> assertThat(call.getPullerGroupAccountId()).isNull());
```

Add cases:

```text
prepareReturnsExistingActiveWaveWithoutRepartitioning
earlyFailedAggregateCannotBeSelectedAgainInsideTheActiveWave
retryWaveUsesOnlyPreviousWaveReleasedOrExplicitFailedAttempts
fourthExplicitFailureIsExcludedFromRetryWave
unknownFinalIsExcludedFromRetryWave
stationAndMaterialCandidatesKeepStableOrder
insufficientStationsRollBackTheWholeWaveAndEnterStationWait
```

- [ ] **Step 2: Run the planning integration test and verify RED**

```bash
mvn -q -Dtest=PullTaskPullWavePlanningIntegrationTest test
```

Expected: compilation fails because the planner and candidate query do not exist.

- [ ] **Step 3: Split plan binding from submission binding**

Change `bindPullAttempt` and `bindMembershipAttempt` to consume `PullTaskParticipantPlanBinding`; these methods set `pull_call_id`, aggregate in-flight status, and `active_pull_attempt_id`, but never set `last_puller_group_account_id`.

Keep `PullTaskParticipantAttemptBinding` for actual submission, where the sticky puller becomes known.

- [ ] **Step 4: Implement one-transaction whole-wave creation**

`PullTaskPullWavePlanningTransactionService.prepare(...)` must:

```text
1. Recheck parent/execution/lease/manual pause.
2. Return the existing active wave when present.
3. Select all eligible initial candidates, or previous-wave retry candidates.
4. Partition candidates with PullTaskBatchSizeSelector and existing station rules.
5. Insert one wave, all calls, all attempts, and all aggregate bindings.
6. Bind active_pull_wave_id with execution version/lease CAS.
7. Return the call at wave.next_call_seq.
```

Use stable keys that survive restart:

```java
call.setIdempotencyKey("pull-task-wave:" + wave.getId() + ":call:" + waveCallSeq);
call.setPullWaveId(wave.getId());
call.setWaveCallSeq(waveCallSeq);
attempt.setPullWaveId(wave.getId());
attempt.setPullerGroupAccountId(null);
```

Do not query or select a puller in this service.

- [ ] **Step 5: Make retry candidate SQL wave-scoped**

The SQL must select only attempts belonging to the immediate settled wave and only these facts:

```text
CLOSED + FAILED where aggregate failure_count < 4
RELEASED + UNKNOWN + NOT_STARTED
RELEASED + UNKNOWN + UNCERTAIN with reason_code = ROSTER_NOT_PRESENT
```

It must exclude `CLOSED + SUCCESS`, `CLOSED + UNKNOWN`, `CANCELED`, and any participant whose aggregate is already successful.

- [ ] **Step 6: Run planning and aggregate regression tests**

```bash
mvn -q -Dtest=PullTaskPullWavePlanningIntegrationTest,PullTaskMaterialMemberMapperInMemoryTest,PullTaskGroupAccountMapperInMemoryTest,PullTaskPullCallMemberAttemptMapperInMemoryTest test
```

Expected: all tests pass; one prepare call freezes all five calls, and retry selection is wave-scoped.

- [ ] **Step 7: Commit whole-wave planning**

```bash
git add armada-api/src/main/java/com/armada/task/model/dto/PullTaskParticipantPlanBinding.java armada-api/src/main/java/com/armada/task/model/dto/PullTaskPullWaveCandidate.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWavePreparation.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWavePlanningSelection.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWavePlanningResources.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWavePlanningTransactionService.java armada-api/src/main/java/com/armada/task/mapper/PullTaskMaterialMemberMapper.java armada-api/src/main/resources/mapper/task/PullTaskMaterialMemberMapper.xml armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupAccountMapper.java armada-api/src/main/resources/mapper/task/PullTaskGroupAccountMapper.xml armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMemberAttemptMapper.java armada-api/src/main/resources/mapper/task/PullTaskPullCallMemberAttemptMapper.xml armada-api/src/main/java/com/armada/task/scheduler/PullTaskStationSelectionService.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullWavePlanningIntegrationTest.java
git commit -m "feat: freeze complete pull waves"
```

---

### Task 3: Add sticky puller selection and generation-safe takeover

**Files:**

- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskStickyPullerTransition.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskStickyPullerInvalidation.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskPlannedCallPullerBinding.java`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskStickyPullerSelection.java`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskStickyPullerTransactionService.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskStickyPullerTransactionServiceTest.java`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupExecutionMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMemberAttemptMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskPullCallMemberAttemptMapper.xml`

**Interfaces:**

```java
public record PullTaskStickyPullerTransition(
        Scope scope,
        Target target,
        long now) {
    public record Scope(long executionId,
                        Long expectedPullerGroupAccountId,
                        long expectedAssignmentSeq) {}
    public record Target(Long pullerGroupAccountId,
                         long assignmentSeq,
                         int nextPullerIndex) {}
}

public record PullTaskPlannedCallPullerBinding(
        Scope scope,
        Target target,
        long now) {
    public record Scope(long pullCallId,
                        Long expectedPullerGroupAccountId,
                        int expectedCallStatus) {}
    public record Target(long pullerGroupAccountId,
                         long pullerAccountId,
                         long assignmentSeq) {}
}

public record PullTaskStickyPullerInvalidation(
        long executionId,
        long expectedPullerGroupAccountId,
        long expectedAssignmentSeq,
        String reasonCode,
        long now) {}

public record PullTaskStickyPullerSelection(
        PullTaskGroupAccount role,
        ProtocolAccountRef protocol,
        long assignmentSeq,
        PullTaskExecutionDispatchResult result) {
    public boolean ready();
}
```

Public service methods:

```java
PullTaskStickyPullerSelection bindForDispatch(
        PullTaskGroupExecution execution,
        PullTaskPullCall call,
        String lockOwner,
        long now);

boolean invalidateIfCurrent(
        PullTaskGroupExecution execution,
        PullTaskPullCall call,
        String reasonCode,
        long now);

int transitionStickyPuller(
        @Param("transition") PullTaskStickyPullerTransition transition);
int clearStickyPuller(
        @Param("invalidation") PullTaskStickyPullerInvalidation invalidation);
```

- [ ] **Step 1: Write failing sticky-selection tests**

Cover:

```text
firstDispatchSelectsAtCursorAndCreatesGenerationOne
secondCallReusesCurrentPullerWithoutMovingCursor
offlineCurrentPullerSelectsNextAndIncrementsGenerationOnce
noReplacementClearsCurrentAssignmentAndReturnsWaitResource
clearingUnavailablePullerKeepsGenerationUntilReplacementIsAssigned
transportFailureDoesNotInvalidateStickyPuller
callbackFromOldGenerationCannotInvalidateNewPuller
firstAAfterAtoBtoAReuseCannotInvalidateSecondA
plannedCallAndAttemptsReceiveTheSamePullerAndGeneration
submittedCallCannotBeRebound
```

The critical assertion is:

```java
assertThat(service.invalidateIfCurrent(executionAtGeneration3,
        oldCallFromGeneration1, "ACCOUNT_NOT_ONLINE", 5_000L)).isFalse();
assertThat(executionMapper.selectById(EXECUTION_ID).getActivePullerGroupAccountId())
        .isEqualTo(PULLER_A_ROLE_ID);
assertThat(executionMapper.selectById(EXECUTION_ID).getPullerAssignmentSeq())
        .isEqualTo(3L);
```

- [ ] **Step 2: Run sticky tests and verify RED**

```bash
mvn -q -Dtest=PullTaskStickyPullerTransactionServiceTest test
```

Expected: compilation fails because sticky transition and binding APIs do not exist.

- [ ] **Step 3: Add execution and call CAS methods**

`PullTaskGroupExecutionMapper.transitionStickyPuller(...)` must compare current ID with null-safe SQL and compare generation exactly. The target generation must equal `expected + 1`; enforce that in the record constructor. `clearStickyPuller(PullTaskStickyPullerInvalidation)` compares both current ID and generation, sets only the active puller ID to null, and keeps the generation unchanged until a replacement is assigned. This yields assignment generations `A=1, B=2, A=3` rather than spending generations on intermediate null states.

`PullTaskPullCallMapper.bindPlannedPuller(...)` and `PullTaskPullCallMemberAttemptMapper.bindPlannedPullerByCall(...)` must update only `PLANNED` rows and write the same generation.

- [ ] **Step 4: Implement sticky selection**

Selection order:

```text
1. Load role rows in stable role_seq order.
2. Resolve active protocol refs in one lookup.
3. Reuse active_puller when role availability, membership, released_at, and protocol ref are valid.
4. Otherwise mark the old role OFFLINE/REMOVED/RISK_COOLDOWN as already classified.
5. Starting at next_puller_index, select the next valid role.
6. CAS execution assignment from generation g to g+1 and move next_puller_index to the role immediately after the newly selected puller.
7. Bind the PLANNED call and its attempts to the selected assignment.
```

Reusing the sticky puller does not move `next_puller_index`. No successful dispatch, result callback, wave settlement, or retry-wave creation may increment the generation. When every candidate is temporarily unavailable, clear only the active assignment and enter puller `WAIT_RESOURCE`; retain the execution's role rows/occupancy so supplemental recovery or a recovered account can resume the same wave. Release all puller occupations only on the existing execution end/group terminal paths.

- [ ] **Step 5: Keep account-level unavailability separate from transport faults**

`invalidateIfCurrent` accepts only these normalized codes:

```java
Set.of("ACCOUNT_NOT_FOUND", "ACCOUNT_NOT_ONLINE", "NEED_REAUTH",
       "ACCOUNT_REACHOUT_RESTRICTED", "RATE_LIMITED",
       "GROUP_PERMISSION_DENIED")
```

Return `false` without mutation for `NETWORK`, `TIMEOUT`, `HTTP_ERROR`, `NOT_OWNER`, `WORKER_BUSY`, `ACCOUNT_BUSY`, `TEMPORARY_FAILURE`, participant failures, and generation mismatch.

- [ ] **Step 6: Run sticky and Mapper tests**

```bash
mvn -q -Dtest=PullTaskStickyPullerTransactionServiceTest,PullTaskGroupExecutionMapperInMemoryTest,PullTaskPullCallMapperInMemoryTest,PullTaskPullCallMemberAttemptMapperInMemoryTest test
```

Expected: all tests pass, including `A -> B -> A` isolation.

- [ ] **Step 7: Commit sticky puller support**

```bash
git add armada-api/src/main/java/com/armada/task/model/dto/PullTaskStickyPullerTransition.java armada-api/src/main/java/com/armada/task/model/dto/PullTaskStickyPullerInvalidation.java armada-api/src/main/java/com/armada/task/model/dto/PullTaskPlannedCallPullerBinding.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskStickyPullerSelection.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskStickyPullerTransactionService.java armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupExecutionMapper.java armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMapper.java armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMemberAttemptMapper.java armada-api/src/main/resources/mapper/task/PullTaskPullCallMemberAttemptMapper.xml armada-api/src/test/java/com/armada/task/scheduler/PullTaskStickyPullerTransactionServiceTest.java
git commit -m "feat: keep pullers sticky across calls"
```

---

### Task 4: Dispatch every due call without waiting for previous results

**Files:**

- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskPullWaveDispatchAdvance.java`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWaveProgressService.java`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWaveSettlementResources.java`
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWaveSettlementTransactionService.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullExecutionProcessor.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskBatchAddTransactionService.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskBatchAddResources.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskBatchAddProcessor.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullerStationContactProcessor.java`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullWaveMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskPullWaveMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupExecutionMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskBatchAddProcessorTest.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullWaveDispatchIntegrationTest.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullWaveSettlementIntegrationTest.java`
- Delete after wiring: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallPlanningTransactionService.java`
- Delete after wiring: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallPlanningResources.java`
- Delete after wiring: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallPreparation.java`

**Interfaces:**

Add one transaction input shared by wave and execution Mapper updates:

```java
public record PullTaskPullWaveDispatchAdvance(
        Scope scope,
        Target target,
        Execution execution,
        long now) {
    public record Scope(long waveId,
                        int expectedWaveVersion,
                        int expectedCallSeq) {}
    public record Target(int nextCallSeq,
                         int waveStatus,
                         long nextDispatchAt,
                         Long dispatchCompletedAt) {}
    public record Execution(long executionId,
                            int expectedVersion,
                            String lockOwner) {}
}

@Component
public record PullTaskPullWaveSettlementResources(
        PullTaskMapper taskMapper,
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskPullWaveMapper waveMapper,
        PullTaskPullCallMemberAttemptMapper attemptMapper,
        PullTaskMaterialMemberMapper materialMapper) {}
```

Expose focused Mapper methods with one DTO argument. `PullTaskBatchAddTransactionService` calls both in the same Spring transaction:

```java
int advanceDispatch(@Param("advance") PullTaskPullWaveDispatchAdvance advance);
int advancePullWaveDispatch(@Param("advance") PullTaskPullWaveDispatchAdvance advance);
int wakePullWaveCollection(@Param("executionId") long executionId,
                           @Param("pullWaveId") long pullWaveId,
                           @Param("targetNextRunAt") long targetNextRunAt,
                           @Param("now") long now);
```

`advanceDispatch` belongs to `PullTaskPullWaveMapper`; the other two methods belong to `PullTaskGroupExecutionMapper`.

- [ ] **Step 1: Write the five-call non-blocking RED test**

Freeze five one-member calls with `pull_interval_seconds = 10`. Drive the processor at `0, 10_000, 20_000, 30_000, 40_000` while leaving call 1 through call 4 in `SUBMITTED`.

Assert:

```java
assertThat(calls()).extracting(PullTaskPullCall::getCallStatus)
        .containsExactly(
                PullTaskPullCallStatus.SUBMITTED.code(),
                PullTaskPullCallStatus.SUBMITTED.code(),
                PullTaskPullCallStatus.SUBMITTED.code(),
                PullTaskPullCallStatus.SUBMITTED.code(),
                PullTaskPullCallStatus.SUBMITTED.code());
assertThat(wave().getWaveStatus()).isEqualTo(PullTaskPullWaveStatus.COLLECTING.code());
assertThat(wave().getDispatchCompletedAt()).isEqualTo(40_000L);
verifyNoInteractions(participantCallbackFixture);
```

Do not inject callbacks in this task's dispatch test; callback checkpoint isolation is Task 5, and pause/resume behavior is Task 7.

- [ ] **Step 2: Run the dispatch test and verify RED**

```bash
mvn -q -Dtest=PullTaskPullWaveDispatchIntegrationTest,PullTaskBatchAddProcessorTest test
```

Expected: current planning returns the first `SUBMITTED` call and later calls are not sent.

- [ ] **Step 3: Route PULL_EXECUTION by wave state**

Replace the current call-planning path in `PullTaskPullExecutionProcessor` with:

```java
PullTaskPullWavePreparation preparation = waves.prepare(candidate, lockOwner, now);
if (!preparation.ready()) return preparation.result();
if (preparation.wave().getWaveStatus() == PullTaskPullWaveStatus.COLLECTING.code()) {
    return settlement.settle(candidate, preparation.wave(), lockOwner, now);
}
PullTaskStickyPullerSelection selected = pullers.bindForDispatch(
        candidate, preparation.call(), lockOwner, now);
if (!selected.ready()) return selected.result();
PullTaskStationContactStepResult contactsResult = contacts.process(
        candidate, preparation.call(), lockOwner, now);
return switch (contactsResult) {
    case MORE_CONTACTS -> PullTaskExecutionDispatchResult.DEFERRED;
    case LOST -> PullTaskExecutionDispatchResult.LOST;
    case CALL_READY -> batch.process(candidate, preparation.call(), lockOwner, now);
};
```

- [ ] **Step 4: Remove result-driven defer and rotate logic from batch submission**

In `PullTaskBatchAddTransactionService`:

- remove the `SUBMITTED` replay branch that waits for reconciliation;
- remove `nextPullerCursor` updates after success;
- remove cancel/replan on a pre-dispatch unavailable puller; sticky selection rebinds a `PLANNED` call instead;
- submit the Outbox command, mark call/attempts submitted, set `execution.last_business_executed_at = now`, then advance wave cursor and execution checkpoint in the same transaction. Callbacks never rewrite the recent-execution timestamp.

Compute exactly one persisted deadline:

```java
long intervalAt = now + setting.getPullIntervalSeconds() * 1_000L;
long nextDispatchAt = Math.max(intervalAt, delayPolicy.nextSideEffectAt(now));
```

If this was the final call, set wave status `COLLECTING`, set `dispatch_completed_at = now`, and set execution `next_run_at = now` so collection can calculate its first outstanding deadline. Otherwise store `nextDispatchAt` in both wave and execution.

- [ ] **Step 5: Preserve one-call-per-claim behavior**

Even when `next_dispatch_at` is far in the past, one invocation of `process(...)` may submit at most one batch call. The next scheduler claim observes the newly persisted deadline. Do not loop over overdue calls in Java.

- [ ] **Step 6: Implement the collection transaction used by the router**

`PullTaskPullWaveSettlementTransactionService.settle(...)` must be fully functional before wiring the router:

```text
1. Recheck parent, execution lease/version, active_pull_wave_id, and wave status.
2. If open attempt count > 0, set execution.next_run_at to the earliest submitted_at + 60,000 and release the lease.
3. If open count = 0, CAS wave COLLECTING -> SETTLED.
4. Query retry candidates only from this wave.
5. If candidates exist, use the Task 2 planner's package-private createRetryWave(...) and replace active_pull_wave_id.
6. If none exist, clear active_pull_wave_id and advance to MATERIAL_ADMIN or CLOSING using existing timing rules.
```

`PullTaskPullWaveProgressService.wakeCollecting(tenantId, executionId, waveId, now)` is a short tenant-scoped transaction. First call `waveMapper.wakeCollecting(...)`; only when that update confirms `COLLECTING`, call a new `PullTaskGroupExecutionMapper.wakePullWaveCollection(executionId, waveId, now)` that sets `next_run_at = now` only for the matching active wave in `PULL_EXECUTION`. A `DISPATCHING`, settled, canceled, replaced, or terminal wave must update neither row. Task 5 calls this service after result facts close a call. The unique active-wave key and status/version CAS must prevent concurrent successor creation.

- [ ] **Step 7: Run dispatch, settlement, Outbox, timing, and payload tests**

```bash
mvn -q -Dtest=PullTaskPullWaveDispatchIntegrationTest,PullTaskPullWaveSettlementIntegrationTest,PullTaskBatchAddProcessorTest,PullTaskBatchAddPayloadHydratorTest,PullTaskOperationDelayPolicyTest,ProtocolCommandOutboxServiceImplTest test
```

Expected: five calls submit at their own deadlines without callbacks; the empty/fully terminal collection path settles once; payloads remain attempt-backed and each command is unique.

- [ ] **Step 8: Delete the old single-call planner and commit dispatch wiring**

After `rg -n 'PullTaskPullCallPlanning' armada-api/src/main armada-api/src/test` returns no live references, delete the three old planning files and update Spring test configurations.

```bash
git add armada-api/src/main/java/com/armada/task/model/dto/PullTaskPullWaveDispatchAdvance.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWaveProgressService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWaveSettlementResources.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWaveSettlementTransactionService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullExecutionProcessor.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskBatchAddTransactionService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskBatchAddResources.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskBatchAddProcessor.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullerStationContactProcessor.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallPlanningTransactionService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallPlanningResources.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallPreparation.java armada-api/src/main/java/com/armada/task/mapper/PullTaskPullWaveMapper.java armada-api/src/main/resources/mapper/task/PullTaskPullWaveMapper.xml armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupExecutionMapper.java armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml armada-api/src/test/java/com/armada/task/scheduler/PullTaskBatchAddProcessorTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullWaveDispatchIntegrationTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullWaveSettlementIntegrationTest.java
git commit -m "feat: dispatch pull waves on schedule"
```

---

### Task 5: Make callbacks ledger-only and apply generation-safe account errors

**Files:**

- Create: `armada-api/src/main/java/com/armada/task/service/PullTaskGroupExecutionFailureService.java`
- Create: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskGroupExecutionFailureServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskGroupExecutionFailureResources.java`
- Create: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullCallResultCoordination.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskPlannedCallPrune.java`
- Create: `armada-api/src/test/java/com/armada/task/service/impl/PullTaskGroupExecutionFailureServiceTest.java`
- Modify: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskExecutionReasonCode.java`
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullWaveSettlementIntegrationTest.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultService.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationService.java`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMemberAttemptMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskPullCallMemberAttemptMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultServiceTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationServiceTest.java`

**Interfaces:**

```java
public interface PullTaskGroupExecutionFailureService {
    void terminate(long tenantId,
                   long executionId,
                   PullTaskExecutionReasonCode reasonCode,
                   long now);
}

@Component
public record PullTaskGroupExecutionFailureResources(
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskPullCallMapper callMapper,
        PullTaskPullCallMemberAttemptMapper attemptMapper,
        PullTaskPullWaveMapper waveMapper,
        PullTaskGroupAccountMapper accountMapper) {}

@Component
public record PullTaskPullCallResultCoordination(
        PullTaskStickyPullerTransactionService stickyPullers,
        PullTaskGroupExecutionFailureService groupFailure,
        PullTaskPullWaveProgressService waveProgress) {}

public class PullTaskPullWaveProgressService {
    public void wakeCollecting(
            long tenantId, long executionId, long waveId, long now);
}

public record PullTaskPlannedCallPrune(
        long pullCallId,
        int participantType,
        int expectedCallStatus,
        long now) {}

int prunePlannedParticipant(
        @Param("prune") PullTaskPlannedCallPrune prune);

```

- [ ] **Step 1: Write callback/settlement RED tests**

Add these cases:

```text
callbackDuringDispatchUpdatesAttemptButDoesNotChangeWaveCursorOrDeadline
lastCallbackDuringCollectionWakesExecutionImmediately
duplicateExplicitFailureDoesNotIncrementFailureCountTwice
settlementWaitsWhileAnyAttemptIsPlannedOrSubmitted
settlementCreatesOneRetryWaveFromAllEligibleResults
concurrentLastCallbacksCreateOnlyOneRetryWave
sameStickyPullerIsPreservedIntoRetryWave
needReauthCallbackInvalidatesOnlyMatchingGeneration
rateLimitedCallbackInvalidatesOnlyMatchingGeneration
groupPermissionDeniedRemovesPullerForThisExecution
groupUnavailableTerminatesExecutionAndCancelsRemainingPlannedCalls
lateSuccessPrunesOnlyItsPlannedAttemptAndKeepsOtherCallMembers
```

For ledger-only behavior assert the exact persisted dispatch checkpoint before and after callback:

```java
assertThat(after.getNextCallSeq()).isEqualTo(before.getNextCallSeq());
assertThat(after.getNextDispatchAt()).isEqualTo(before.getNextDispatchAt());
```

- [ ] **Step 2: Run callback and settlement tests and verify RED**

```bash
mvn -q -Dtest=PullTaskPullCallParticipantResultServiceTest,PullTaskPullWaveSettlementIntegrationTest,PullTaskUnknownResultReconciliationServiceTest test
```

Expected: current callback code still advances `next_puller_index/next_run_at`, and it does not wake or use the collecting-wave settlement path correctly.

- [ ] **Step 3: Replace callback wake logic**

In `PullTaskPullCallParticipantResultService.closeCallIfReady(...)`:

```text
1. Close the call when all attempts are terminal/released/canceled.
2. Never compute pull interval or random silence.
3. Never update next_puller_index.
4. Call waveProgress.wakeCollecting(...) only after the call closes.
5. wakeCollecting SQL must require wave_status = COLLECTING.
```

Remove `PullTaskOperationDelayPolicy` from this callback service; dispatch timing now belongs exclusively to Task 4. Inject the three new coordination services through `PullTaskPullCallResultCoordination` so the service constructor remains bounded.

Apply the same rule to the legacy wake path in `PullTaskUnknownResultReconciliationService`: a wave-backed call wakes collection; it never advances the pull stage by itself.

- [ ] **Step 4: Integrate callback facts with the existing settlement transaction**

Early callbacks during `DISPATCHING` only close attempts/calls. When a call closes during `COLLECTING`, wake the execution row to `now`; the scheduler then invokes the Task 4 settlement transaction. Add a concurrent-last-callback test proving one `RETRY` wave is created by the status/version CAS.

- [ ] **Step 5: Prune only one not-yet-submitted late-success target**

Replace whole-call cancellation in `cancelNewerPlannedCall(...)` with:

```text
1. CAS the newer target attempt PLANNED -> CANCELED and clear active_slot.
2. Promote the participant aggregate to SUCCESS.
3. Decrement planned_material_count or planned_station_count on the PLANNED call.
4. Cancel the call only when both counts become zero.
5. Never add a replacement participant or renumber wave_call_seq.
```

Add a `PullTaskPlannedCallPrune` record rather than a Mapper method with more than five parameters.

- [ ] **Step 6: Apply account/group error classification**

After writing the original attempt fact:

- offline/not found/reauth: mark role `OFFLINE`, call `invalidateIfCurrent`;
- rate limit/reachout: mark `RISK_COOLDOWN` with existing configured cooldown, call `invalidateIfCurrent`;
- group permission denied: mark role `REMOVED` for this execution, call `invalidateIfCurrent`;
- group unavailable: call `PullTaskGroupExecutionFailureService.terminate(... GROUP_UNAVAILABLE ...)` and do not rotate pullers;
- all other participant/transport errors: do not touch sticky assignment.

Add `GROUP_UNAVAILABLE("群当前不可继续执行拉人")` to `PullTaskExecutionReasonCode`.

`PullTaskGroupExecutionFailureServiceImpl` performs one tenant-scoped transaction: load the execution, CAS it to terminal `FAILED` with `GROUP_UNAVAILABLE`, cancel only `PLANNED` calls/attempts and the active wave, release execution roles, then call `PullTaskParentCompletionService.completeIfTerminalByExecutionId(...)`. Leave every `SUBMITTED` call and published attempt writable so delayed callbacks/reconciliation can finish their ledger facts without creating a successor wave.

- [ ] **Step 7: Run callback, settlement, lifecycle, and race-focused tests**

```bash
mvn -q -Dtest=PullTaskPullCallParticipantResultServiceTest,PullTaskGroupExecutionFailureServiceTest,PullTaskPullWaveSettlementIntegrationTest,PullTaskUnknownResultReconciliationServiceTest,PullTaskGroupExecutionMapperInMemoryTest,PullTaskPullCallMapperInMemoryTest test
```

Expected: all tests pass; dispatch checkpoints are unchanged by early callbacks and one retry wave is created after full collection.

- [ ] **Step 8: Commit callback and settlement behavior**

```bash
git add armada-api/src/main/java/com/armada/task/service/PullTaskGroupExecutionFailureService.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskGroupExecutionFailureServiceImpl.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskGroupExecutionFailureResources.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullCallResultCoordination.java armada-api/src/main/java/com/armada/task/model/dto/PullTaskPlannedCallPrune.java armada-api/src/main/java/com/armada/task/model/enums/PullTaskExecutionReasonCode.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationService.java armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMapper.java armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMemberAttemptMapper.java armada-api/src/main/resources/mapper/task/PullTaskPullCallMemberAttemptMapper.xml armada-api/src/test/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultServiceTest.java armada-api/src/test/java/com/armada/task/service/impl/PullTaskGroupExecutionFailureServiceTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullWaveSettlementIntegrationTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationServiceTest.java
git commit -m "feat: make pull callbacks wave aware"
```

---

### Task 6: Close unknown results with one HTTP roster decision

**Files:**

- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskRosterObservation.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskUncertainParticipantSettlement.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallReconciliationService.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultService.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationCoordinator.java`
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullCallReconciliationServiceTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationCoordinatorTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullWaveSettlementIntegrationTest.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskExecutionDispatchPropertiesTest.java`

**Interfaces:**

```java
public enum PullTaskRosterObservation {
    PRESENT,
    ABSENT,
    UNAVAILABLE
}

public record PullTaskUncertainParticipantSettlement(
        Context context,
        PullTaskPullCallMemberAttempt attempt,
        PullTaskRosterObservation observation,
        long now) {
    public record Context(long tenantId,
                          PullTaskPullCall call,
                          PullTaskGroupExecution execution) {}
}

public boolean settleUncertain(PullTaskUncertainParticipantSettlement settlement);
```

- [ ] **Step 1: Write the three-way roster RED tests**

Replace boolean-only assertions with:

```text
presentClosesSuccessWithoutFailureCount
absentReleasesForNextWaveWithoutFailureCount
httpFailureClosesFinalUnknownWithoutRetry
missingQueryAccountClosesFinalUnknownWithoutRetry
notStartedNeverCallsGroupMemberListPort
oneHttpSnapshotSettlesAllUnknownAttemptsInTheCall
concurrentScannersClaimRosterAtMostOnce
reconciliationUsesGroupMemberListPortAndPublishesNoKafkaCommand
scanBeforeSixtySecondsDoesNothing
thirtySecondCoordinatorCadenceProducesSixtyToNinetySecondFirstCheck
lateSuccessPromotesFinalUnknownWithoutReopeningSettledWave
lateFailureAfterFinalUnknownDoesNotCreateRetryWave
```

For HTTP failure assert:

```java
assertThat(savedAttempt.getLifecycleStatus()).isEqualTo(CLOSED);
assertThat(savedAttempt.getProtocolOutcome()).isEqualTo("UNKNOWN");
assertThat(savedMaterial.getPullStatus()).isEqualTo(PullTaskMaterialPullStatus.UNKNOWN.code());
assertThat(attemptMapper.selectRetryCandidatesByWave(WAVE_ID, 4)).isEmpty();
```

- [ ] **Step 2: Run focused reconciliation tests and verify RED**

```bash
mvn -q -Dtest=PullTaskPullCallReconciliationServiceTest,PullTaskUnknownResultReconciliationCoordinatorTest,PullTaskPullWaveSettlementIntegrationTest test
```

Expected: current failed snapshot is treated as absent/released instead of final unknown.

- [ ] **Step 3: Implement the three outcomes**

Map snapshot status as:

```java
PullTaskRosterObservation observation = switch (snapshot.status()) {
    case SUCCEEDED -> snapshot.member(attempt.getTargetJid()) == null
            ? PullTaskRosterObservation.ABSENT
            : PullTaskRosterObservation.PRESENT;
    case FAILED, SKIPPED -> PullTaskRosterObservation.UNAVAILABLE;
    default -> throw new IllegalStateException("名单核实状态未完成");
};
```

`UNAVAILABLE` writes attempt `CLOSED + UNKNOWN + UNCERTAIN`, clears the aggregate active attempt, keeps the same failure count, and sets material/station aggregate `UNKNOWN`. It must not use `RELEASED`.

Extend terminal callback handling for an attempt previously closed by `UNAVAILABLE`: a late `SUCCESS` changes only that old attempt fact to success, promotes the participant aggregate monotonically, and prunes a still-`PLANNED` retry attempt; a late `FAILED/UNKNOWN` may update only the old attempt fact/reason. Neither path increments failure count, wakes a settled/canceled wave, reopens collection, or creates a retry wave.

- [ ] **Step 4: Preserve one query attempt and wake collection**

`claimRosterCheck` remains the cross-instance gate. Always finish a claimed query with `SUCCEEDED`, `FAILED`, or `SKIPPED`. Do not let a failed query be reclaimed. After all uncertain attempts are handled, close the call and call `waveProgress.wakeCollecting(...)`.

- [ ] **Step 5: Run reconciliation and configuration tests**

```bash
mvn -q -Dtest=PullTaskPullCallReconciliationServiceTest,PullTaskUnknownResultReconciliationServiceTest,PullTaskUnknownResultReconciliationCoordinatorTest,PullTaskExecutionDispatchPropertiesTest,PullTaskPullWaveSettlementIntegrationTest test
```

Expected: all tests pass; defaults remain 60,000/30,000 ms and query failure cannot create a retry candidate.

- [ ] **Step 6: Commit unknown-result convergence**

```bash
git add armada-api/src/main/java/com/armada/task/model/enums/PullTaskRosterObservation.java armada-api/src/main/java/com/armada/task/model/dto/PullTaskUncertainParticipantSettlement.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallReconciliationService.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationCoordinator.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullCallReconciliationServiceTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationCoordinatorTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullWaveSettlementIntegrationTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskExecutionDispatchPropertiesTest.java
git commit -m "fix: close unavailable pull roster checks as unknown"
```

---

### Task 7: Preserve wave semantics across pause, end, and legacy open calls

**Files:**

- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardLifecycleResources.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardLifecycleServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardExecutionLifecycleResources.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardExecutionLifecycleServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWavePlanningTransactionService.java`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullWaveMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskPullWaveMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardLifecycleServiceTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardExecutionLifecycleServiceTest.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullWaveLegacyBootstrapIntegrationTest.java`

**Interfaces:**

`PullTaskPullCallMapper` adds a compatibility-only method:

```java
int attachOpenLegacyCallsToWave(
        @Param("groupExecutionId") long executionId,
        @Param("pullWaveId") long waveId,
        @Param("openStatuses") List<Integer> openStatuses,
        @Param("now") long now);
```

The SQL assigns `wave_call_seq` in existing `call_seq` order using Java-provided per-row updates if the database-neutral Mapper cannot express deterministic ranking. Do not use a MySQL-only user variable in H2-tested XML.

- [ ] **Step 1: Write lifecycle and compatibility RED tests**

Cover:

```text
pauseKeepsWaveAndCursorButBlocksNewDispatch
callbacksAndRosterReconciliationContinueWhilePaused
resumeSendsAtMostOneOverdueCallThenRestoresInterval
endCancelsPlannedCallsAttemptsAndWaveButKeepsPublishedCallbacksWritable
endNeverCreatesRetryWaveAfterSubmittedResultsSettle
legacySubmittedCallIsAttachedWithoutChangingCommandOrIdempotency
legacyPlannedCallIsAttachedWithoutRepartitioningItsParticipants
bootstrapPlansRemainingUnconsumedParticipantsAfterAttachedOpenCalls
completedHistoricalCallsRemainWithoutWaveId
```

- [ ] **Step 2: Run lifecycle/bootstrap tests and verify RED**

```bash
mvn -q -Dtest=PullTaskStandardLifecycleServiceTest,PullTaskStandardExecutionLifecycleServiceTest,PullTaskPullWaveLegacyBootstrapIntegrationTest test
```

Expected: lifecycle services do not cancel waves, and no runtime compatibility bootstrap exists.

- [ ] **Step 3: Cancel waves with unpublished work**

In task end and execution end transactions, call wave cancellation after attempts/calls are canceled and before pullers are released:

```java
waveMapper.cancelByExecution(
        executionId,
        List.of(DISPATCHING.code(), COLLECTING.code()),
        CANCELED.code(),
        now);
```

For parent end use `cancelByTask`. Do not change published call/attempt states; the existing Outbox cancellation rules remain authoritative.

- [ ] **Step 4: Bootstrap only open legacy work**

When `active_pull_wave_id` is null in PULL_EXECUTION:

```text
1. Read only PLANNED/SUBMITTED/UNKNOWN legacy calls that have no pull_wave_id.
2. Create one INITIAL wave.
3. Attach those calls and their attempts in call_seq order without changing command_id, idempotency_key, submitted_at, participant set, or status.
4. If the oldest open call already has a puller, initialize sticky assignment generation 1 from it.
5. Append calls for all remaining eligible unconsumed participants after the attached calls.
6. Set next_call_seq to the first PLANNED attached/new call; if none remain, enter COLLECTING.
```

Never attach completed historical calls and never replay a `SUBMITTED/UNKNOWN` call.

- [ ] **Step 5: Preserve resume spacing**

Resume may set the execution due now, but the processor still submits at most one call. After that call, Task 4 persists the normal next deadline. Add no catch-up loop and no arithmetic based on number of missed intervals.

- [ ] **Step 6: Run lifecycle and bootstrap regression**

```bash
mvn -q -Dtest=PullTaskStandardLifecycleServiceTest,PullTaskStandardExecutionLifecycleServiceTest,PullTaskPullWaveLegacyBootstrapIntegrationTest,PullTaskPullWaveDispatchIntegrationTest,PullTaskPullWaveSettlementIntegrationTest test
```

Expected: all tests pass, open legacy commands retain identity, and end never creates new work.

- [ ] **Step 7: Commit lifecycle and compatibility support**

```bash
git add armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardLifecycleResources.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardLifecycleServiceImpl.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardExecutionLifecycleResources.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardExecutionLifecycleServiceImpl.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWavePlanningTransactionService.java armada-api/src/main/java/com/armada/task/mapper/PullTaskPullWaveMapper.java armada-api/src/main/resources/mapper/task/PullTaskPullWaveMapper.xml armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMapper.java armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml armada-api/src/test/java/com/armada/task/service/PullTaskStandardLifecycleServiceTest.java armada-api/src/test/java/com/armada/task/service/PullTaskStandardExecutionLifecycleServiceTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullWaveLegacyBootstrapIntegrationTest.java
git commit -m "feat: preserve pull waves across lifecycle changes"
```

---

### Task 8: Align reads, metrics, and prove the full five-call scenario

**Files:**

- Modify: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardCallVO.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardReadServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWavePlanningTransactionService.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskBatchAddTransactionService.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWaveSettlementTransactionService.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskStickyPullerTransactionService.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallReconciliationService.java`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskStandardReadMapperInMemoryTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardReadServiceTest.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullWaveEndToEndIntegrationTest.java`
- Modify: `.harness/changes/2026-08-09-normal-link-pull-wave-dispatch.md`

**Interfaces:**

Do not expose a new frontend wave DTO or any wave ID. Preserve the existing call-detail order and make only the puller ID nullable because a frozen `PLANNED` call has not selected its puller yet:

```java
public record PullTaskStandardCallVO(
        long callId,
        int callSeq,
        Long pullerAccountId,
        int plannedMaterialCount,
        int plannedStationCount,
        int callStatus,
        String reasonCode,
        String reasonMessage,
        Long submittedAt,
        Long resultAt) {}
```

- [ ] **Step 1: Write read-model and exact acceptance RED tests**

The end-to-end fixture seeds 27 materials with `pull_count_min = pull_count_max = 6`, producing frozen call sizes `6, 6, 6, 6, 3`. It dispatches those five initial calls at 10-second intervals and produces these final facts:

```text
20 SUCCESS
3 explicit FAILED with failure_count = 1
2 UNKNOWN + NOT_STARTED
1 missing callback, roster HTTP success, absent
1 missing callback, roster HTTP failure, final UNKNOWN
```

After collection assert the retry wave contains exactly the six retryable numbers: three explicit failures, two not-started, and one roster-absent. It must exclude all 20 successes and the final unknown.

Also assert the initial and retry wave use the same puller until a rate-limit callback; calls after that callback use the next puller and a higher assignment generation.

- [ ] **Step 2: Run read and end-to-end tests and verify RED**

```bash
mvn -q -Dtest=PullTaskStandardReadMapperInMemoryTest,PullTaskStandardReadServiceTest,PullTaskPullWaveEndToEndIntegrationTest test
```

Expected: reading a frozen planned call fails because `row.getPullerAccountId()` is auto-unboxed into primitive `long`, and the complete scenario is not yet proven.

- [ ] **Step 3: Keep aggregate counts number-based**

Verify the existing aggregate SQL against these rules in `PullTaskStandardReadMapperInMemoryTest`:

```text
SUCCESS counts each material/station once from aggregate status.
FAILED counts only fourth explicit failure terminal states.
UNKNOWN includes final roster-query-unavailable aggregates.
SUBMITTED counts each material aggregate once while its current frozen attempt is planned/submitted; attempt history is never joined into the count.
Historical attempts and multiple waves never inflate total or success count.
```

Change `PullTaskStandardCallVO.pullerAccountId` from `long` to `Long` and keep `PullTaskStandardReadServiceImpl.call(...)` in the exact existing component order. Do not return `pull_wave_id`, `wave_call_seq`, or `puller_assignment_seq`; those stay in persistence, structured logs, and internal tests.

- [ ] **Step 4: Add structured lifecycle logs**

At wave create, call submit, wave collect, wave settle, puller invalidate, puller assign, and roster finish, log:

```text
tenantId taskId groupExecutionId waveId waveNo callId waveCallSeq
pullerGroupAccountId pullerAccountId pullerAssignmentSeq
plannedCalls nextCallSeq nextDispatchAt pendingAttempts reasonCode
```

Use existing number masking and do not log participant payloads or credentials.

- [ ] **Step 5: Run the full focused Java acceptance set**

```bash
mvn -q -Dtest=PullTaskPullWaveMigrationSqlTest,PullTaskPullWaveMapperInMemoryTest,PullTaskPullWavePlanningIntegrationTest,PullTaskStickyPullerTransactionServiceTest,PullTaskPullWaveDispatchIntegrationTest,PullTaskPullCallParticipantResultServiceTest,PullTaskPullWaveSettlementIntegrationTest,PullTaskPullCallReconciliationServiceTest,PullTaskPullWaveLegacyBootstrapIntegrationTest,PullTaskPullWaveEndToEndIntegrationTest,PullTaskStandardLifecycleServiceTest,PullTaskStandardExecutionLifecycleServiceTest,PullTaskStandardReadMapperInMemoryTest,PullTaskStandardReadServiceTest test
```

Expected: all tests pass; the end-to-end test proves five non-blocking initial calls and one correctly filtered retry wave.

- [ ] **Step 6: Record focused evidence and commit acceptance/read changes**

Write the exact command, test count, elapsed time, and key assertions into the change tracker.

```bash
git add armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardCallVO.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardReadServiceImpl.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWavePlanningTransactionService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskBatchAddTransactionService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullWaveSettlementTransactionService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskStickyPullerTransactionService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallReconciliationService.java armada-api/src/test/java/com/armada/task/mapper/PullTaskStandardReadMapperInMemoryTest.java armada-api/src/test/java/com/armada/task/service/PullTaskStandardReadServiceTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullWaveEndToEndIntegrationTest.java .harness/changes/2026-08-09-normal-link-pull-wave-dispatch.md
git commit -m "feat: finalize pull wave observability"
```

---

### Task 9: Audit the existing protocol contract and run final verification

**Repositories:**

- Backend: current `armada` repository
- Web protocol verification only: `../armada-protocol/protocol-layer`

**Files:**

- Modify: `.harness/changes/2026-08-09-normal-link-pull-wave-dispatch.md`

**Existing protocol truth table to preserve:**

```text
before-call account failure -> UNKNOWN + NOT_STARTED + ACCOUNT_NOT_ONLINE/NEED_REAUTH
explicit member result      -> SUCCESS/FAILED + STARTED
missing member report       -> UNKNOWN + UNCERTAIN
post-call timeout/error     -> UNKNOWN + UNCERTAIN
403/not-authorized          -> GROUP_PERMISSION_DENIED
408/401/412 reachout block  -> ACCOUNT_REACHOUT_RESTRICTED
429/rate-overlimit          -> RATE_LIMITED
409 group unavailable       -> GROUP_UNAVAILABLE
```

- [ ] **Step 1: Run the existing Web protocol contract as a read-only audit**

From `../armada-protocol/protocol-layer`:

```bash
npm test -- --runInBand src/commands/group-participants-executor.test.ts src/commands/pull-task-action-state.test.ts src/commands/master-consumer.test.ts
```

Expected: all existing tests pass and preserve the batch callback shape, idempotency, broker acknowledgement, `NOT_STARTED`, `UNCERTAIN`, explicit-result, and duplicate-report behavior. Make no protocol code change and record “audit only” in the tracker.

- [ ] **Step 2: Verify the normalized-code source contract without editing protocol code**

Run from `../armada-protocol/protocol-layer`:

```bash
for token in ACCOUNT_NOT_ONLINE NEED_REAUTH GROUP_PERMISSION_DENIED ACCOUNT_REACHOUT_RESTRICTED RATE_LIMITED GROUP_UNAVAILABLE NOT_STARTED UNCERTAIN "'401'" "'408'" "'409'" "'412'"; do
  rg -n -F "$token" src/commands/group-participants-executor.ts src/commands/group-participants-executor.test.ts || exit 1
done
```

Expected: source and focused tests contain every normalized value in the preserved truth table. If either protocol command fails or a row is absent, stop and report a protocol-contract blocker instead of silently expanding this backend plan.

- [ ] **Step 3: Run backend focused and package verification**

From `armada-api/`:

```bash
mvn -q -Dtest=PullTaskPullWaveMigrationSqlTest,PullTaskPullWaveMapperInMemoryTest,PullTaskPullWavePlanningIntegrationTest,PullTaskStickyPullerTransactionServiceTest,PullTaskPullWaveDispatchIntegrationTest,PullTaskPullCallParticipantResultServiceTest,PullTaskPullWaveSettlementIntegrationTest,PullTaskPullCallReconciliationServiceTest,PullTaskPullWaveLegacyBootstrapIntegrationTest,PullTaskPullWaveEndToEndIntegrationTest,PullTaskStandardLifecycleServiceTest,PullTaskStandardExecutionLifecycleServiceTest test
mvn -q -DskipTests package
```

Expected: focused behavior and package compilation both exit zero.

- [ ] **Step 4: Run broad local regression without real-database tests**

Use the project-established local exclusion for environment/real-database `DbTest` classes and the two Spring tests that also connect to a real database despite not using the suffix:

```bash
find src/test/java -name '*DbTest.java' -print
mvn -q test -Dtest='!*DbTest,!GroupLinkRegistryServiceImplTest,!GroupCreationMarketingTaskServiceImplTest' -DfailIfNoTests=false
```

Expected: all local tests outside the explicitly excluded environment tests pass; the focused wave suite from Step 3 remains an independent mandatory gate.

- [ ] **Step 5: Run static repository checks**

From the `armada` repository root:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors. Status may contain pre-existing `.claude/worktrees` entries; the change tracker must distinguish them from this implementation.

- [ ] **Step 6: Complete the evidence tracker**

Set every implemented checklist item to checked and record:

- migration version and no-DML assertion;
- five-call timestamps and unchanged early-callback deadline;
- retry-wave participant counts;
- 60–90 second roster timing;
- final-unknown behavior on HTTP failure;
- sticky puller reuse and generation-safe takeover;
- pause/resume/end and legacy bootstrap evidence;
- protocol audit result;
- focused test, broad regression, package, and `git diff --check` output.

- [ ] **Step 7: Commit final evidence**

Commit only the backend evidence in `armada`:

```bash
git add .harness/changes/2026-08-09-normal-link-pull-wave-dispatch.md
git commit -m "docs: record pull wave verification"
```

---

## Completion Gate

Implementation is complete only when all statements below are supported by passing tests and recorded command output:

- Five initial calls submit on their persisted interval while the first four remain `SUBMITTED`.
- Early success, failure, unknown, duplicate, and delayed callbacks do not change the dispatch cursor or deadline.
- No retry wave exists until the initial wave has dispatched every call and settled every attempt.
- The retry wave contains only explicit failures below count 4, not-started attempts, and roster-confirmed-absent attempts.
- A failed roster HTTP request produces final aggregate `UNKNOWN`, creates no retry candidate, and does not block the wave.
- The same puller remains active across calls and waves until a classified account failure.
- Old-generation callbacks cannot invalidate B after `A -> B` or a newly assigned A after `A -> B -> A`.
- Pause preserves the wave, resume does not burst, and end never creates new work while published results remain writable.
- Legacy open calls preserve command/idempotency/participant identity and are never resent.
- Frontend task inputs remain unchanged and no wave concept leaks into creation/configuration APIs.
