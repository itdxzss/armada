# Normal Link Pull Retry and Takeover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make normal-link pull batches close per participant, retry every explicit failure three times, release uncertain participants for takeover after one roster check, and persist a 3–5 second random gap between adjacent WhatsApp side effects.

**Architecture:** Web and Android protocol workers report the same per-number `outcome + executionState` fact. Armada persists one immutable attempt row per participant and call, projects only the active attempt into the material/station aggregate rows, and owns retry, takeover, late-callback, reconciliation, puller selection, and timing decisions. Every retry or takeover creates a new call; read-only roster checks and database-only transitions do not add silence.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis/MyBatis-Plus, Flyway, H2 MySQL mode, JUnit 5, Mockito, AssertJ, TypeScript, Node.js 24+, Jest, Go 1.25, Go test/race/vet/build.

## Global Constraints

- Scope is only ordinary pull tasks in `NORMAL_LINK` mode; do not change retry semantics for other modes.
- A participant is either a material number or a station number. A puller is the account executing the bulk WhatsApp operation.
- Every explicit participant failure consumes one failure count, regardless of reason code or protocol `retryable`; initial execution plus three retries means at most four explicit failed executions.
- Success is globally monotonic. No failure, unknown, release, cancellation, duplicate callback, or later attempt may downgrade it.
- `UNKNOWN + NOT_STARTED` releases immediately without a roster query. `UNKNOWN + UNCERTAIN` and missing callbacks after the existing 60-second collection window share one roster query per call, then either become success or are released without consuming a failure count.
- New work prefers the next available puller after the previous puller. Reuse the previous puller only when no other puller is available and it is still usable; otherwise enter existing puller-resource waiting.
- A submitted call is immutable and is never reset to `PLANNED`, rehydrated with fewer members, or sent again. Retry and takeover always create a new call, command ID, idempotency key, and participant attempt.
- Web and Android must emit identical batch-result semantics. `FAILED + STARTED` is legal only for an explicit per-member WhatsApp report.
- Persist 3,000–5,000 ms random silence in `next_run_at`; never use `Thread.sleep`. For pull batches use `max(last_batch_submitted_at + pull_interval, last_whatsapp_result_at + random_silence)`, never a sum.
- A ten-number bulk add is one WhatsApp side effect; do not delay between numbers. Database-only transitions and the read-only group roster query do not add silence.
- Preserve the existing 60-second result collection configuration; do not add a new retry or delay setting to the frontend or backend configuration surface.
- Add schema only through Flyway `V106`; do not use JPA schema generation or hand-edited environment databases.
- Do not backfill or reinterpret historical calls. Only calls that have rows in `pull_task_pull_call_member_attempt` enter the new state machine.
- Do not include deployment, publishing, remote access, live database changes, historical-data jobs, or rollback execution. The user owns release work.
- Follow each repository's `AGENTS.md`. Keep Java classes under 800 lines, methods under 100 lines, and public method parameter lists at five or fewer; use DTOs for larger state transitions.
- Preserve user-owned dirty work. Stage and commit only files named by the task being completed.

---

## File Map and Contract Ownership

### Web protocol repository: `../armada-protocol/protocol-layer`

- `src/commands/pull-task-action.ts` owns the result type and JSON field name `executionState`.
- `src/commands/pull-task-action-state.ts` validates cached results and converges stale `PROCESSING` commands without replaying WhatsApp.
- `src/commands/group-participants-executor.ts` distinguishes before-call, after-call, per-member, missing-report, and cached-result paths.
- `src/commands/group-participants-executor.test.ts` is the executable batch contract.

### Android protocol repository: `../whatsapp-server-feature-android-zhuan`

- `internal/armada/group_participants_sender.go` owns before-call/after-call and per-member execution-state mapping.
- `internal/armada/group_action_state.go` owns stale-command convergence and durable idempotency state.
- `internal/armada/group_action_event.go` owns `GroupActionResult`, legal batch combinations, and the published JSON contract.
- The matching `_test.go` files must use the same truth table as Web.

### Armada backend repository: current repository

- Kafka consumer/event/callback files ingest and validate `executionState` but do not decide retries.
- `pull_task_pull_call_member_attempt` is the immutable per-number execution ledger.
- Material and station aggregate rows keep current status, explicit failure count, active attempt, and last puller.
- `PullTaskPullCallPlanningTransactionService` creates the call, attempts, and aggregate bindings in one transaction.
- `PullTaskBatchAddPayloadHydrator` builds the immutable command participant list from attempt rows.
- A focused `PullTaskPullCallParticipantResultService` owns current and late callback transitions; `PullTaskProtocolResultCallbackServiceImpl` delegates batch-participant work to it.
- A focused `PullTaskPullCallReconciliationService` owns the one-snapshot uncertain-result convergence; the existing unknown-result coordinator invokes it.
- `PullTaskOperationDelayPolicy` is the only source of random side-effect silence.
- `.harness/changes/2026-08-08-normal-link-pull-retry-takeover.md` records implementation and verification evidence.

---

### Task 1: Add the Web batch execution-state contract

**Repository:** `../armada-protocol/protocol-layer`

**Files:**
- Modify: `src/commands/pull-task-action.ts`
- Modify: `src/commands/pull-task-action-state.ts`
- Modify: `src/commands/group-participants-executor.ts`
- Test: `src/commands/group-participants-executor.test.ts`

**Contract:**

```ts
export type PullTaskActionExecutionState =
  | 'NOT_STARTED'
  | 'STARTED'
  | 'UNCERTAIN'

export interface PullTaskActionExecutionResult {
  targetJid: string | null
  outcome: 'SUCCESS' | 'FAILED' | 'UNKNOWN'
  executionState?: PullTaskActionExecutionState
  reasonCode?: string
  reasonMessage?: string
  retryable: boolean
}
```

- [ ] **Step 1: Replace the current offline and timeout assertions with the approved truth table**

Update/add Jest cases named:

```text
reports UNKNOWN plus NOT_STARTED for every batch member when account resolution fails
reports explicit member success and failure plus STARTED
reports UNKNOWN plus UNCERTAIN for a missing member report
reports UNKNOWN plus UNCERTAIN for every member after timeout or post-call exception
republishes one cached result per member without touching WhatsApp again
expands a stale processing result to every batch member as UNKNOWN plus UNCERTAIN
```

Keep the single-target invite/promote assertions unchanged so this task does not expand their business semantics.

- [ ] **Step 2: Run the focused Web test and verify it fails**

Run from `armada-protocol/protocol-layer`:

```bash
npm test -- --runInBand src/commands/group-participants-executor.test.ts
```

Expected: the offline path still produces `FAILED`, and results do not contain `executionState`.

- [ ] **Step 3: Add the result field and cached-state validation**

Add the union type and optional field in `pull-task-action.ts`, and make `pullTaskActionEventData(...)` emit `executionState` when present. In `pull-task-action-state.ts`, accept only the three defined values when the field is present. Preserve old cached single-target results that omit the field.

- [ ] **Step 4: Track the side-effect boundary in the batch executor**

Set the local state immediately before calling `socket.groupParticipantsUpdate(...)`. Map:

```text
resolver/gate failure before call -> UNKNOWN + NOT_STARTED for every target
explicit per-member success       -> SUCCESS + STARTED
explicit per-member failure       -> FAILED + STARTED
missing per-member report         -> UNKNOWN + UNCERTAIN
timeout/post-call exception       -> UNKNOWN + UNCERTAIN for every target
```

Do not synthesize a per-member `FAILED` from a call-level exception. Keep one native bulk call for the whole participant list.

- [ ] **Step 5: Expand stale singleton results before publishing**

If durable idempotency returns a stale `PROCESSING` result with `targetJid=null`, expand it against the batch command's participant list and publish one `UNKNOWN + UNCERTAIN` event per target. Do not call WhatsApp again.

- [ ] **Step 6: Run focused and package verification**

```bash
npm test -- --runInBand src/commands/group-participants-executor.test.ts
npm run lint
npm run build
```

Expected: all commands exit zero.

- [ ] **Step 7: Commit the Web contract**

```bash
git add src/commands/pull-task-action.ts src/commands/pull-task-action-state.ts src/commands/group-participants-executor.ts src/commands/group-participants-executor.test.ts
git commit -m "feat: report batch participant execution state"
```

---

### Task 2: Mirror the contract in the Android protocol worker

**Repository:** `../whatsapp-server-feature-android-zhuan`

**Files:**
- Modify: `internal/armada/group_participants_sender.go`
- Modify: `internal/armada/group_action_state.go`
- Modify: `internal/armada/group_action_event.go`
- Test: `internal/armada/group_participants_test.go`
- Test: `internal/armada/group_action_state_test.go`
- Test: `internal/armada/group_action_event_test.go`
- Test: `internal/armada/group_action_executor_test.go`

**Contract:**

```go
const (
	ExecutionStateNotStarted = "NOT_STARTED"
	ExecutionStateStarted    = "STARTED"
	ExecutionStateUncertain  = "UNCERTAIN"
)

type GroupActionResult struct {
	TargetJID      string `json:"targetJid,omitempty"`
	Outcome        string `json:"outcome"`
	ExecutionState string `json:"executionState,omitempty"`
	ReasonCode     string `json:"reasonCode,omitempty"`
	ReasonMessage  string `json:"reasonMessage,omitempty"`
	Retryable      bool   `json:"retryable"`
}
```

- [ ] **Step 1: Add failing table-driven tests using the same Web truth table**

Replace `TestParticipantsSenderReportsOfflineForEveryMember` with a `UNKNOWN + NOT_STARTED` assertion. Extend the per-member, missing-report, timeout/error, stale-state, event JSON, and duplicate-command tests to assert `ExecutionState` and one event per target.

- [ ] **Step 2: Run the focused package tests and verify failure**

```bash
go test ./internal/armada -run 'Test(ParticipantsSender|BuildGroupActionResultEvents|RedisGroupActionCommandStateStore|GroupActionCommandExecutor)'
```

Expected: assertions fail because the field and new mapping do not exist.

- [ ] **Step 3: Implement the result constants and event field**

Add the constants and `ExecutionState` field to both `GroupActionResult` and `GroupActionResultEventData`, and copy it in `BuildGroupActionResultEvent(...)`. Validate batch results as the same legal combinations accepted by Armada:

```text
SUCCESS + STARTED
FAILED + STARTED
UNKNOWN + NOT_STARTED
UNKNOWN + UNCERTAIN
```

Keep the field optional for existing single-target sources.

- [ ] **Step 4: Mark the exact AddParticipants boundary**

Return `UNKNOWN + NOT_STARTED` when account/client resolution fails before `client.AddParticipants`. Once control reaches the native call, map call errors, timeouts, and missing reports to `UNKNOWN + UNCERTAIN`; only native member reports may produce `FAILED + STARTED`.

- [ ] **Step 5: Expand stale results and preserve idempotency**

When a stale durable `PROCESSING` record contains one targetless unknown result, expand it to the original batch participant list as `UNKNOWN + UNCERTAIN`, then publish all events without invoking the client.

- [ ] **Step 6: Format and verify the Android package**

```bash
gofmt -w internal/armada/group_participants_sender.go internal/armada/group_action_state.go internal/armada/group_action_event.go internal/armada/group_participants_test.go internal/armada/group_action_state_test.go internal/armada/group_action_event_test.go internal/armada/group_action_executor_test.go
go test -race ./internal/armada
go vet ./...
go build ./...
```

Expected: all commands exit zero.

- [ ] **Step 7: Commit the Android contract**

```bash
git add internal/armada/group_participants_sender.go internal/armada/group_action_state.go internal/armada/group_action_event.go internal/armada/group_participants_test.go internal/armada/group_action_state_test.go internal/armada/group_action_event_test.go internal/armada/group_action_executor_test.go
git commit -m "feat: report batch participant execution state"
```

---

### Task 3: Ingest and validate `executionState` in Armada

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskParticipantExecutionState.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolPullTaskBatchParticipantResultReportedEvent.java`
- Modify: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskBatchParticipantCallback.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumer.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/ProtocolPullTaskBatchParticipantResultAdapter.java`
- Test: `armada-api/src/test/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumerTest.java`
- Test: `armada-api/src/test/java/com/armada/task/service/ProtocolPullTaskBatchParticipantResultAdapterTest.java`

- [ ] **Step 1: Add failing consumer contract tests**

Extend `onMessage_batchAddResultDispatchesPerParticipantCorrelation` to include `"executionState":"STARTED"`, and add rejection cases for missing/unknown state and illegal combinations such as `FAILED + NOT_STARTED`.

- [ ] **Step 2: Run the focused tests**

```bash
mvn -q -pl armada-api -Dtest=ProtocolGroupEventConsumerTest,ProtocolPullTaskBatchParticipantResultAdapterTest test
```

Expected: tests fail because the event/callback does not carry `executionState`.

- [ ] **Step 3: Add the enum and propagate it through the adapter**

Use exactly:

```java
public enum PullTaskParticipantExecutionState {
    NOT_STARTED,
    STARTED,
    UNCERTAIN
}
```

Add `executionState` to the event and callback records. The Kafka consumer must require it for `pull_task_batch_add`, parse case-sensitively, and reject illegal outcome/state pairs before dispatch. Keep `retryable` for diagnostics only.

- [ ] **Step 4: Verify and commit contract ingestion**

```bash
mvn -q -pl armada-api -Dtest=ProtocolGroupEventConsumerTest,ProtocolPullTaskBatchParticipantResultAdapterTest test
git add armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolPullTaskBatchParticipantResultReportedEvent.java armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumer.java armada-api/src/main/java/com/armada/task/model/dto/PullTaskBatchParticipantCallback.java armada-api/src/main/java/com/armada/task/model/enums/PullTaskParticipantExecutionState.java armada-api/src/main/java/com/armada/task/service/impl/ProtocolPullTaskBatchParticipantResultAdapter.java armada-api/src/test/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumerTest.java armada-api/src/test/java/com/armada/task/service/ProtocolPullTaskBatchParticipantResultAdapterTest.java
git commit -m "feat: ingest participant execution state"
```

---

### Task 4: Add the participant-attempt schema and ledger mapper

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V106__pull_task_participant_attempt.sql`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskParticipantType.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskParticipantAttemptStatus.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskPullCallRosterCheckStatus.java`
- Create: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskPullCallMemberAttempt.java`
- Modify: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskPullCall.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskParticipantAttemptTransition.java`
- Create: `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMemberAttemptMapper.java`
- Create: `armada-api/src/main/resources/mapper/task/PullTaskPullCallMemberAttemptMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkH2Support.java`
- Create: `armada-api/src/test/java/com/armada/task/PullTaskParticipantAttemptMigrationSqlTest.java`
- Create: `armada-api/src/test/java/com/armada/task/mapper/PullTaskPullCallMemberAttemptMapperInMemoryTest.java`

**Schema:**

Create `pull_task_pull_call_member_attempt` with the fields approved in the design: tenant/task/execution/call identity, participant type/ref, phone/JID snapshots, puller, monotonic `attempt_no`, `failure_count_before`, lifecycle status, outcome, execution state, result fields, and submitted/result/released/audit timestamps.

Add to `pull_task_material_member`:

```text
pull_failure_count BIGINT NOT NULL DEFAULT 0
active_pull_attempt_id BIGINT NULL
last_puller_group_account_id BIGINT NULL
```

Add to `pull_task_group_account`:

```text
membership_failure_count BIGINT NOT NULL DEFAULT 0
active_pull_attempt_id BIGINT NULL
last_puller_group_account_id BIGINT NULL
```

Add to `pull_task_pull_call`:

```text
roster_check_status SMALLINT NOT NULL DEFAULT 0
roster_check_started_at BIGINT NULL
roster_check_finished_at BIGINT NULL
```

Use `NOT_STARTED`, `CLAIMED`, `SUCCEEDED`, `FAILED`, and `SKIPPED` for the call-level roster-check state. `CLAIMED` is durable and is never changed back to `NOT_STARTED`; it is the cross-instance at-most-once gate for the external read.

Use a nullable `active_slot` column on the ledger (`1` for `PLANNED/SUBMITTED`, `NULL` after close/release/cancel) and a unique key over tenant, execution, participant type/ref, and `active_slot`. This allows many historical rows while preventing two active attempts. Also add the call/participant uniqueness, command-callback lookup, and execution/status scheduling indexes from the design.

- [ ] **Step 1: Write migration shape and H2 mapper tests first**

Assert all required columns, defaults, indexes, and no data-copy/backfill statements. Mapper tests must prove:

```text
insertPlanned fills its generated ID
duplicate participant in one call is rejected
two active attempts for one participant are rejected
release clears active_slot and permits a later attempt
selectByCallAndTarget matches the frozen normalized target JID
same IDs in another tenant remain invisible
```

- [ ] **Step 2: Run the focused tests and verify failure**

```bash
mvn -q -pl armada-api -Dtest=PullTaskParticipantAttemptMigrationSqlTest,PullTaskPullCallMemberAttemptMapperInMemoryTest test
```

- [ ] **Step 3: Implement enums, entity, migration, and mapper**

Use lifecycle values `PLANNED`, `SUBMITTED`, `CLOSED`, `RELEASED`, `CANCELED`. Keep `protocolOutcome` nullable before a result. Model transitions with no more than five top-level parameters:

```java
public record PullTaskParticipantAttemptTransition(
        Scope scope,
        Expected expected,
        Target target,
        PullTaskFactResult result) {
    public record Scope(long attemptId, long now) {}
    public record Expected(List<Integer> lifecycleStatuses) {}
    public record Target(
            int lifecycleStatus,
            String protocolOutcome,
            PullTaskParticipantExecutionState executionState,
            Long releasedAt) {}
}
```

All mutating mapper SQL must include tenant isolation and lifecycle CAS. Closing/releasing/canceling must set `active_slot = NULL` in the same update.

- [ ] **Step 4: Run focused tests and commit**

```bash
mvn -q -pl armada-api -Dtest=PullTaskParticipantAttemptMigrationSqlTest,PullTaskPullCallMemberAttemptMapperInMemoryTest test
git add armada-api/src/main/resources/db/migration/V106__pull_task_participant_attempt.sql armada-api/src/main/java/com/armada/task/model/enums/PullTaskParticipantType.java armada-api/src/main/java/com/armada/task/model/enums/PullTaskParticipantAttemptStatus.java armada-api/src/main/java/com/armada/task/model/enums/PullTaskPullCallRosterCheckStatus.java armada-api/src/main/java/com/armada/task/model/entity/PullTaskPullCallMemberAttempt.java armada-api/src/main/java/com/armada/task/model/entity/PullTaskPullCall.java armada-api/src/main/java/com/armada/task/model/dto/PullTaskParticipantAttemptTransition.java armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMemberAttemptMapper.java armada-api/src/main/resources/mapper/task/PullTaskPullCallMemberAttemptMapper.xml armada-api/src/test/java/com/armada/task/mapper/PullTaskNormalLinkH2Support.java armada-api/src/test/java/com/armada/task/PullTaskParticipantAttemptMigrationSqlTest.java armada-api/src/test/java/com/armada/task/mapper/PullTaskPullCallMemberAttemptMapperInMemoryTest.java
git commit -m "feat: persist pull participant attempts"
```

---

### Task 5: Add CAS transitions to material and station aggregates

**Files:**
- Modify: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskMaterialMember.java`
- Modify: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskGroupAccount.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskParticipantAttemptBinding.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskParticipantAggregateTransition.java`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskMaterialMemberMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskMaterialMemberMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupAccountMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskGroupAccountMapper.xml`
- Test: `armada-api/src/test/java/com/armada/task/mapper/PullTaskMaterialMemberMapperInMemoryTest.java`
- Test: `armada-api/src/test/java/com/armada/task/mapper/PullTaskGroupAccountMapperInMemoryTest.java`

**Interfaces:**

```java
public record PullTaskParticipantAttemptBinding(
        long participantId,
        long attemptId,
        long pullCallId,
        long pullerGroupAccountId,
        long now) {}

public record PullTaskParticipantAggregateTransition(
        Scope scope,
        Expected expected,
        Target target,
        PullTaskFactResult result) {
    public record Scope(long participantId, long attemptId, long now) {}
    public record Expected(List<Integer> statuses, long failureCount) {}
    public record Target(
            int status,
            long failureCount,
            Long pullCallId,
            Long activeAttemptId) {}
}
```

- [ ] **Step 1: Add failing material and station state-machine mapper tests**

For each participant type prove:

```text
pending + no active attempt can bind exactly once
success retains the winning pull_call_id, clears active attempt, and cannot downgrade
failure counts 1, 2, 3 return to pending and clear pull_call_id
failure count 4 becomes FAILED/JOIN_FAILED and is not selectable
UNKNOWN release returns to UNCONSUMED/NOT_JOINED without changing failure count
an old attempt cannot mutate an aggregate owned by a newer active attempt
```

- [ ] **Step 2: Run mapper tests and verify failure**

```bash
mvn -q -pl armada-api -Dtest=PullTaskMaterialMemberMapperInMemoryTest,PullTaskGroupAccountMapperInMemoryTest test
```

- [ ] **Step 3: Implement entity fields, selectors, binding, and transition SQL**

Update pending selectors to require `active_pull_attempt_id IS NULL` and explicit failure count `< 4`. Make aggregate transitions conditional on both current status and `active_pull_attempt_id`; do not use a read-then-unconditional-write pattern.

Add a dedicated monotonic success promotion operation that may accept a late success from an old attempt. It must preserve a newer submitted attempt's pointer until that attempt closes, and it must never change a success row back to pending or failed.

- [ ] **Step 4: Verify and commit aggregate primitives**

```bash
mvn -q -pl armada-api -Dtest=PullTaskMaterialMemberMapperInMemoryTest,PullTaskGroupAccountMapperInMemoryTest test
git add armada-api/src/main/java/com/armada/task/model/entity/PullTaskMaterialMember.java armada-api/src/main/java/com/armada/task/model/entity/PullTaskGroupAccount.java armada-api/src/main/java/com/armada/task/model/dto/PullTaskParticipantAttemptBinding.java armada-api/src/main/java/com/armada/task/model/dto/PullTaskParticipantAggregateTransition.java armada-api/src/main/java/com/armada/task/mapper/PullTaskMaterialMemberMapper.java armada-api/src/main/resources/mapper/task/PullTaskMaterialMemberMapper.xml armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupAccountMapper.java armada-api/src/main/resources/mapper/task/PullTaskGroupAccountMapper.xml armada-api/src/test/java/com/armada/task/mapper/PullTaskMaterialMemberMapperInMemoryTest.java armada-api/src/test/java/com/armada/task/mapper/PullTaskGroupAccountMapperInMemoryTest.java
git commit -m "feat: project participant attempt state"
```

---

### Task 6: Plan immutable attempts and hydrate commands from them

**Files:**
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallPlanningResources.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallPlanningTransactionService.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskBatchAddResources.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskBatchAddTransactionService.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskBatchAddPayloadHydrator.java`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml`
- Delete: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskCallReschedule.java`
- Test: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullCallPlanningIntegrationTest.java`
- Test: `armada-api/src/test/java/com/armada/task/service/impl/PullTaskBatchAddPayloadHydratorTest.java`
- Test: `armada-api/src/test/java/com/armada/task/mapper/PullTaskPullCallMapperInMemoryTest.java`

- [ ] **Step 1: Replace tests that freeze the old whole-call reset behavior**

Delete/replace:

```text
offlinePullerResetUsesCommandAndStatusCasWhileKeepingFrozenPlan
offlinePullerReassignsAndResubmitsTheSameFrozenCall
```

Add tests proving:

```text
planning inserts the call, one attempt per station/material, and aggregate bindings atomically
attempt_no increases for failure retry, unknown takeover, and canceled-before-submit replacement
payload order and counts come from PLANNED attempt rows, not aggregate pull_call_id scans
submission changes the call and all attempts to SUBMITTED once
a submitted call has no API or SQL path back to PLANNED
next puller after A is B; A is reused only when no other usable puller exists
unusable A with no alternative enters existing WAIT_RESOURCE/puller shortage
```

- [ ] **Step 2: Run planning and hydration tests and verify failure**

```bash
mvn -q -pl armada-api -Dtest=PullTaskPullCallPlanningIntegrationTest,PullTaskBatchAddPayloadHydratorTest,PullTaskPullCallMapperInMemoryTest test
```

- [ ] **Step 3: Create attempts inside the planning transaction**

After selecting stable station/material rows and a puller, insert the call, calculate each participant's next monotonic `attempt_no`, insert attempt rows in stable order so every generated ID is available, and bind all aggregates. Any insert/bind mismatch must throw and roll back the entire call.

Advance the execution's existing stable puller cursor after each submitted call. A requeued subset therefore starts after the previous call's puller; a pool containing rows from different old calls follows that same execution cursor. Use each participant's `last_puller_group_account_id` to prevent immediate reuse when another usable puller exists and for audit, then store the chosen puller on every new attempt.

- [ ] **Step 4: Hydrate and submit the immutable attempt set**

Build the protocol participant list only from the call's `PLANNED` attempt rows. Mark the call and those exact attempt IDs submitted in the outbox transaction. Remove `rescheduleSubmitted` from Java/XML and delete `PullTaskCallReschedule`.

- [ ] **Step 5: Verify and commit planning**

```bash
mvn -q -pl armada-api -Dtest=PullTaskPullCallPlanningIntegrationTest,PullTaskBatchAddPayloadHydratorTest,PullTaskPullCallMapperInMemoryTest test
git add armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallPlanningResources.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallPlanningTransactionService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskBatchAddResources.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskBatchAddTransactionService.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskBatchAddPayloadHydrator.java armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMapper.java armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml armada-api/src/main/java/com/armada/task/model/dto/PullTaskCallReschedule.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullCallPlanningIntegrationTest.java armada-api/src/test/java/com/armada/task/service/impl/PullTaskBatchAddPayloadHydratorTest.java armada-api/src/test/java/com/armada/task/mapper/PullTaskPullCallMapperInMemoryTest.java
git commit -m "feat: plan immutable participant attempts"
```

---

### Task 7: Implement current-attempt success, failure, and not-started transitions

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultService.java`
- Create: `armada-api/src/test/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultServiceTest.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskProtocolResultCallbackServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/task/service/impl/PullTaskProtocolResultCallbackServiceImplTest.java`

**State constants:**

```java
static final int MAX_FAILURE_RETRY_COUNT = 3;
static final int MAX_EXPLICIT_FAILURE_COUNT = MAX_FAILURE_RETRY_COUNT + 1;
```

- [ ] **Step 1: Write focused failing tests for both participant types**

Cover material and station rows for:

```text
SUCCESS + STARTED closes the attempt and makes aggregate success
FAILED + STARTED at failure counts 0, 1, 2 releases to pending with counts 1, 2, 3
FAILED + STARTED at failure count 3 closes as final failure count 4
retryable=false follows the same retry path
UNKNOWN + NOT_STARTED releases immediately, preserves failure count, and needs no roster query
UNKNOWN + UNCERTAIN remains submitted for reconciliation
duplicate callback does not increment twice
callback target not present in the call is rejected
```

- [ ] **Step 2: Run the new service test and verify failure**

```bash
mvn -q -pl armada-api -Dtest=PullTaskPullCallParticipantResultServiceTest test
```

- [ ] **Step 3: Implement the focused transactional state machine**

Resolve the call by tenant and command ID, then the attempt by call and normalized target JID. Write the attempt fact first and project to the aggregate only when `active_pull_attempt_id` still equals this attempt. Use attempt and aggregate CAS updates; if exactly one side changed, throw so the transaction rolls back.

For explicit failures, ignore `retryable`, increment once, and choose pending versus final based only on the new count. For `UNKNOWN + NOT_STARTED`, set attempt `RELEASED`, clear the aggregate binding, and do not query group membership.

- [ ] **Step 4: Delegate from the existing callback service**

Make `PullTaskProtocolResultCallbackServiceImpl.handlePullCallParticipant(...)` delegate immediately to the new service. Remove the old batch helper methods, including the special `ACCOUNT_NOT_ONLINE` whole-call reschedule branch, so the existing class remains under the size limit.

- [ ] **Step 5: Verify and commit current-result handling**

```bash
mvn -q -pl armada-api -Dtest=PullTaskPullCallParticipantResultServiceTest,PullTaskProtocolResultCallbackServiceImplTest test
git add armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultService.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskProtocolResultCallbackServiceImpl.java armada-api/src/test/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultServiceTest.java armada-api/src/test/java/com/armada/task/service/impl/PullTaskProtocolResultCallbackServiceImplTest.java
git commit -m "feat: retry explicit participant failures"
```

---

### Task 8: Close calls and make late callbacks monotonic

**Files:**
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultService.java`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMemberAttemptMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskPullCallMemberAttemptMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml`
- Test: `armada-api/src/test/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultServiceTest.java`
- Test: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullCallPlanningIntegrationTest.java`

- [ ] **Step 1: Add failing late-result and closure tests**

Cover:

```text
old released attempt late FAILED/UNKNOWN updates history only
old released attempt late SUCCESS promotes pending, active, or final-failed aggregate to success
old success with a newer PLANNED call cancels that whole unsubmitted call and releases its remaining participants for clean replanning
old success with a newer SUBMITTED call keeps both facts and aggregate success
new submitted attempt later FAILED/UNKNOWN cannot downgrade that success or increment failure count
all attempts CLOSED/RELEASED/CANCELED closes the call once
an open UNCERTAIN attempt prevents call closure
duplicate and out-of-order callbacks do not advance execution twice
```

- [ ] **Step 2: Run focused tests and verify failure**

```bash
mvn -q -pl armada-api -Dtest=PullTaskPullCallParticipantResultServiceTest,PullTaskPullCallPlanningIntegrationTest test
```

- [ ] **Step 3: Implement late-success cancellation and submitted-call preservation**

If the winning old success finds a newer active `PLANNED` attempt, cancel its entire unsubmitted call, mark every planned attempt `CANCELED`, and release the other aggregate bindings; then preserve the winner as success and let the planner create a new call for the remainder. If the newer attempt is already `SUBMITTED`, do not cancel it; close its eventual fact without changing the successful aggregate.

- [ ] **Step 4: Implement idempotent call closure**

Close a new-model call as `WRITTEN_BACK` only when no attempt is `PLANNED` or `SUBMITTED`. Mixed participant outcomes remain in the ledger; do not leave released-unknown calls permanently `UNKNOWN`. Wake the execution once, without replaying or mutating the submitted call.

- [ ] **Step 5: Verify and commit late-result behavior**

```bash
mvn -q -pl armada-api -Dtest=PullTaskPullCallParticipantResultServiceTest,PullTaskPullCallPlanningIntegrationTest test
git add armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultService.java armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMemberAttemptMapper.java armada-api/src/main/resources/mapper/task/PullTaskPullCallMemberAttemptMapper.xml armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMapper.java armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml armada-api/src/test/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultServiceTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullCallPlanningIntegrationTest.java
git commit -m "feat: converge late participant results"
```

---

### Task 9: Reconcile uncertain batches with one group-roster query

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallReconciliationService.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullCallReconciliationServiceTest.java`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskUnknownResultResources.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationService.java`
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationServiceTest.java`

- [ ] **Step 1: Write failing reconciliation tests**

Use one call containing station and material attempts. Assert:

```text
before the 60-second collection deadline nothing is queried or released
one snapshot query covers every UNCERTAIN and missing participant in the call
members present in the snapshot become success
members absent from the snapshot are released without failure increments
query failure releases all unresolved participants and is not retried for that call
no usable in-group query account releases all unresolved participants without a protocol call
two reconcilers racing can claim the roster read only once
process restart after CLAIMED but before write-back releases unresolved rows without issuing a second read
NOT_STARTED rows never reach the roster-query service
already explicit success/failure rows remain unchanged
the call closes and becomes eligible for a new call after reconciliation
```

The user acceptance fixture must include A's batch with 1–4 success, 5 explicit failure, and 6–10 unresolved; use a single roster fetch and assert only the unresolved subset is locally compared.

- [ ] **Step 2: Run focused tests and verify failure**

```bash
mvn -q -pl armada-api -Dtest=PullTaskPullCallReconciliationServiceTest,PullTaskUnknownResultReconciliationServiceTest test
```

- [ ] **Step 3: Implement one-call reconciliation**

At the existing stale-result cutoff, load all open attempt rows for the call and convert missing callbacks to stored `UNKNOWN + UNCERTAIN`. Before the external read, CAS `roster_check_status` from `NOT_STARTED` to `CLAIMED` and commit that claim. Only the claimant may select one usable in-group account through the existing `GroupMemberListPort`, query the full member list once, normalize it once, and compare every unresolved target locally.

Persist `SUCCEEDED`, `FAILED`, or `SKIPPED` after applying the result. If a worker restarts with the call still `CLAIMED`, treat the snapshot result as unavailable and release the unresolved rows without another protocol read. A read-only roster query must not call `PullTaskOperationDelayPolicy` and must not move `next_run_at` solely because the query occurred.

- [ ] **Step 4: Delegate only new-model calls**

Route calls with participant-attempt rows to `PullTaskPullCallReconciliationService`. Preserve existing legacy behavior for historical calls with no attempt rows, satisfying the no-backfill constraint.

- [ ] **Step 5: Verify and commit reconciliation**

```bash
mvn -q -pl armada-api -Dtest=PullTaskPullCallReconciliationServiceTest,PullTaskUnknownResultReconciliationServiceTest test
git add armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullCallReconciliationService.java armada-api/src/main/java/com/armada/task/mapper/PullTaskPullCallMapper.java armada-api/src/main/resources/mapper/task/PullTaskPullCallMapper.xml armada-api/src/main/java/com/armada/task/scheduler/PullTaskUnknownResultResources.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationService.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullCallReconciliationServiceTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskUnknownResultReconciliationServiceTest.java
git commit -m "feat: release uncertain pull participants"
```

---

### Task 10: Centralize and persist the 3–5 second side-effect silence

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskOperationDelayPolicy.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskOperationDelayPolicyTest.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskManagerJoinResultServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskManagerAdminResultServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskContactSaveResultServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullerInviteResultServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskProtocolResultCallbackServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultService.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskBatchAddTransactionService.java`
- Modify: the matching result-service, callback-service, and planning integration tests.

**Policy:**

```java
@Component
public class PullTaskOperationDelayPolicy {
    static final long MIN_DELAY_MS = 3_000L;
    static final long MAX_DELAY_MS = 5_000L;

    private final LongSupplier delaySupplier;

    public long nextSideEffectAt(long occurredAt) { /* sampled once */ }

    public long maxDeadline(long currentDeadline, long occurredAt) {
        return Math.max(currentDeadline, nextSideEffectAt(occurredAt));
    }
}
```

Provide a package-visible constructor accepting `LongSupplier`; production uses `ThreadLocalRandom.current().nextLong(3_000L, 5_001L)`.

- [ ] **Step 1: Add deterministic boundary and persistence tests**

Prove supplied values `3_000` and `5_000` are accepted and persisted exactly, values outside the range are rejected in tests, restart reads the stored deadline without resampling, duplicate callbacks do not resample, one bulk call samples only once when the call closes, and database-only stage changes do not add another delay.

Update the invite test that currently expects a fixed 3 seconds. Add callback tests for manager join, manager admin, contact save, puller invite, batch close, and material admin transitions where the next stage can issue a WhatsApp side effect.

- [ ] **Step 2: Add pull-interval max tests**

In `PullTaskPullCallPlanningIntegrationTest`, assert:

```text
pull interval 10s, random 4s -> next eligible at 10s
pull interval 2s, random 4s  -> next eligible at 4s
UNKNOWN + NOT_STARTED        -> no new random deadline
UNCERTAIN/STARTED result      -> random deadline applies
roster query only             -> no new random deadline
```

- [ ] **Step 3: Run focused tests and verify failure**

```bash
mvn -q -pl armada-api -Dtest=PullTaskOperationDelayPolicyTest,PullTaskManagerJoinResultServiceImplTest,PullTaskManagerAdminResultServiceImplTest,PullTaskContactSaveResultServiceImplTest,PullTaskPullerInviteResultServiceImplTest,PullTaskProtocolResultCallbackServiceImplTest,PullTaskPullCallParticipantResultServiceTest,PullTaskPullCallPlanningIntegrationTest test
```

- [ ] **Step 4: Implement and inject the single policy**

Replace `INVITE_RESULT_DELAY_MS` and every callback transition that currently makes an adjacent side-effect stage immediately runnable. Combine existing retry/cooldown deadlines with random silence using `Math.max`. Single-target operations sample once when their first accepted result is written. A bulk add samples once in the winning call-finalization CAS after every attempt is closed/released/canceled; it does not sample per participant callback. Persist the returned absolute deadline, and processors must preserve rather than overwrite a future `next_run_at` during internal stage transitions.

Do not apply the policy to closing, task completion, resource waiting, attempt release alone, or group-roster reads.

- [ ] **Step 5: Verify and commit delay behavior**

```bash
mvn -q -pl armada-api -Dtest=PullTaskOperationDelayPolicyTest,PullTaskManagerJoinResultServiceImplTest,PullTaskManagerAdminResultServiceImplTest,PullTaskContactSaveResultServiceImplTest,PullTaskPullerInviteResultServiceImplTest,PullTaskProtocolResultCallbackServiceImplTest,PullTaskPullCallParticipantResultServiceTest,PullTaskPullCallPlanningIntegrationTest test
git add armada-api/src/main/java/com/armada/task/scheduler/PullTaskOperationDelayPolicy.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskOperationDelayPolicyTest.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskManagerJoinResultServiceImpl.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskManagerAdminResultServiceImpl.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskContactSaveResultServiceImpl.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullerInviteResultServiceImpl.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskProtocolResultCallbackServiceImpl.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultService.java armada-api/src/main/java/com/armada/task/scheduler/PullTaskBatchAddTransactionService.java armada-api/src/test/java/com/armada/task/service/impl/PullTaskManagerJoinResultServiceImplTest.java armada-api/src/test/java/com/armada/task/service/impl/PullTaskManagerAdminResultServiceImplTest.java armada-api/src/test/java/com/armada/task/service/impl/PullTaskContactSaveResultServiceImplTest.java armada-api/src/test/java/com/armada/task/service/impl/PullTaskPullerInviteResultServiceImplTest.java armada-api/src/test/java/com/armada/task/service/impl/PullTaskProtocolResultCallbackServiceImplTest.java armada-api/src/test/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultServiceTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullCallPlanningIntegrationTest.java
git commit -m "feat: persist random pull operation silence"
```

---

### Task 11: Keep cancellation, reads, and completion consistent with attempts

**Files:**
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardExecutionLifecycleResources.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardExecutionLifecycleServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardLifecycleResources.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardLifecycleServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskStandardReadMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskStandardReadMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardReadFactMappers.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardReadServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardExecutionLifecycleServiceTest.java`
- Test: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardLifecycleServiceTest.java`
- Test: `armada-api/src/test/java/com/armada/task/mapper/PullTaskStandardReadMapperInMemoryTest.java`
- Test: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardReadServiceTest.java`

- [ ] **Step 1: Add failing lifecycle and aggregation tests**

Assert:

```text
manual task/execution end cancels PLANNED attempts and clears their aggregate bindings
submitted attempt facts remain auditable and cannot be deleted
released unknown and failure-awaiting-retry count as pending, not used or failed
failure count 4 counts as final failed exactly once
historical attempt rows never inflate success/failed/member totals
execution cannot complete while pending participants, active attempts, uncertain attempts, or puller-resource wait remain
an execution can complete after every participant is success or final failed and no admin work remains
```

- [ ] **Step 2: Run focused tests and verify failure**

```bash
mvn -q -pl armada-api -Dtest=PullTaskStandardExecutionLifecycleServiceTest,PullTaskStandardLifecycleServiceTest,PullTaskStandardReadMapperInMemoryTest,PullTaskStandardReadServiceTest test
```

- [ ] **Step 3: Integrate attempts into cancellation and completion**

Cancel only unpublished calls/attempts. For submitted attempts, retain the fact and let callbacks/reconciliation close it while preventing new work after task termination. All aggregate counters must come from material/station current state, never by summing attempt history.

- [ ] **Step 4: Verify and commit lifecycle/read behavior**

```bash
mvn -q -pl armada-api -Dtest=PullTaskStandardExecutionLifecycleServiceTest,PullTaskStandardLifecycleServiceTest,PullTaskStandardReadMapperInMemoryTest,PullTaskStandardReadServiceTest test
git add armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardExecutionLifecycleResources.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardExecutionLifecycleServiceImpl.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardLifecycleResources.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardLifecycleServiceImpl.java armada-api/src/main/java/com/armada/task/mapper/PullTaskStandardReadMapper.java armada-api/src/main/resources/mapper/task/PullTaskStandardReadMapper.xml armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardReadFactMappers.java armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardReadServiceImpl.java armada-api/src/test/java/com/armada/task/service/PullTaskStandardExecutionLifecycleServiceTest.java armada-api/src/test/java/com/armada/task/service/PullTaskStandardLifecycleServiceTest.java armada-api/src/test/java/com/armada/task/mapper/PullTaskStandardReadMapperInMemoryTest.java armada-api/src/test/java/com/armada/task/service/PullTaskStandardReadServiceTest.java
git commit -m "feat: align pull task lifecycle with attempts"
```

---

### Task 12: Prove the full user scenario, concurrency, and restart behavior

**Files:**
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskExecutionEndToEndIntegrationTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullCallPlanningIntegrationTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/PullTaskNormalLinkCollationDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/PullTaskNormalLinkMigrationSqlTest.java`
- Create: `.harness/changes/2026-08-08-normal-link-pull-retry-takeover.md`

- [ ] **Step 1: Add the end-to-end acceptance scenario**

Seed puller A and ten target participants, with station participants included in the same call fixture. Drive callbacks so 1–4 succeed, 5 explicitly fails, and 6–10 remain unresolved when A becomes unusable. After one roster query assert:

```text
1-4 remain success and never appear in another command
5 has failure_count=1 and returns to pending
6-10 return to pending with failure_count=0
one new call uses puller B and its material target list contains exactly 5-10 in stable order
old call/command remains immutable and fully auditable
```

- [ ] **Step 2: Add retry, takeover, race, and restart scenarios**

Add integration cases for:

```text
failure, failure, failure, success -> four attempts and success
four explicit failures -> final failed and no fifth attempt
only A available and still usable -> A handles the new call
A unusable and no B -> WAIT_RESOURCE without losing pending rows
two planners racing -> only one active attempt per participant
duplicate Kafka callbacks -> one failure increment
restart after deadline persisted -> no resampling and no early dispatch
late old success before new submit -> cancel/replan
late old success after new submit -> retain both facts, aggregate success
```

- [ ] **Step 3: Run the focused backend acceptance suite**

```bash
mvn -q -pl armada-api -Dtest=PullTaskExecutionEndToEndIntegrationTest,PullTaskPullCallPlanningIntegrationTest,PullTaskNormalLinkCollationDbTest,PullTaskNormalLinkMigrationSqlTest test
```

Expected: all tests exit zero and assertions prove the exact user example.

- [ ] **Step 4: Write the change record with local evidence**

Document scope, the `V106` schema, protocol contract, fixed three-retry rule, no-history/no-release boundary, and the exact verification commands/results. Do not add deployment instructions or claim any environment migration was run.

- [ ] **Step 5: Run full local verification in all three repositories**

Armada:

```bash
mvn -q -pl armada-api test
```

Web protocol:

```bash
npm test -- --runInBand
npm run lint
npm run build
```

Android protocol:

```bash
go test -race ./internal/armada
go vet ./...
go build ./...
go test ./...
```

Expected: every command exits zero. If an unrelated pre-existing failure occurs, record the exact command and failure separately; do not weaken or skip the focused acceptance tests.

- [ ] **Step 6: Review the three-repository diff**

Check:

```bash
git -C /Users/daishuaishuai/IdeaProjects/armada diff --check
git -C /Users/daishuaishuai/IdeaProjects/armada-protocol diff --check
git -C /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan diff --check
```

Confirm there are no credentials, generated build outputs, frontend files, historical-data scripts, deployment files, fixed sleeps, or unrelated user changes in the staged sets.

- [ ] **Step 7: Commit backend acceptance evidence**

```bash
git add armada-api/src/test/java/com/armada/task/scheduler/PullTaskExecutionEndToEndIntegrationTest.java armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullCallPlanningIntegrationTest.java armada-api/src/test/java/com/armada/task/PullTaskNormalLinkCollationDbTest.java armada-api/src/test/java/com/armada/task/PullTaskNormalLinkMigrationSqlTest.java .harness/changes/2026-08-08-normal-link-pull-retry-takeover.md
git commit -m "test: verify pull retry and takeover flow"
```

---

## Final Acceptance Checklist

- [ ] Web and Android emit identical per-target `outcome + executionState` for the shared cases.
- [ ] Every explicit failure can be followed by exactly three retries; no fifth execution is possible.
- [ ] Unknown release never consumes a failure count and never leaves a participant occupied.
- [ ] Each uncertain call performs at most one read-only roster query.
- [ ] Success is never selected again, including after late or duplicate results.
- [ ] Retried/taken-over participants use a new call and prefer the next usable puller.
- [ ] Station and material participants pass the same state-transition tests.
- [ ] Every adjacent WhatsApp side effect observes a persisted 3–5 second deadline; batch interval and random silence use `max`.
- [ ] Internal database transitions and roster reads add no delay.
- [ ] Legacy calls without attempt rows retain legacy handling; no historical backfill exists.
- [ ] No frontend, deployment, remote-environment, or release work is included.
