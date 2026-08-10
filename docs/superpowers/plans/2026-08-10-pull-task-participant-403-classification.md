# Pull Task Participant 403 Classification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent a per-participant WhatsApp 403 from removing a working puller while preserving account-level permission failure handling.

**Architecture:** The protocol command executor will align its per-participant 403 mapping with the existing group HTTP route and publish `PRIVACY_BLOCKED`, while retaining the raw protocol status in the sanitized reason message. The backend will additionally use the existing `executionState` boundary so legacy `GROUP_PERMISSION_DENIED + STARTED` participant events cannot remove a puller; only `NOT_STARTED` account-level permission failures retain removal semantics.

**Tech Stack:** TypeScript, Baileys 7, Jest, Java 17, Spring Boot, JUnit 5, Mockito

## Global Constraints

- Mixed success plus a minority of participant 403 results is accepted and must not remove or rotate the puller.
- Participant report details remain sanitized; do not publish Baileys `BinaryNode`, JIDs beyond the existing target contract, invite credentials, or other raw sensitive fields.
- A pre-side-effect account-level 403 remains `GROUP_PERMISSION_DENIED` and may remove the puller from the current execution.
- All per-participant 403 results remain target-scoped; they must not directly remove the puller without a separately confirmed account/group permission fact.
- Do not change database schemas, remote task data, deployment state, or unrelated status mappings.

---

### Task 1: Align protocol batch-participant 403 semantics

**Files:**
- Modify: `../armada-protocol/protocol-layer/src/commands/group-participants-executor.test.ts`
- Modify: `../armada-protocol/protocol-layer/src/commands/group-participants-executor.ts`

**Interfaces:**
- Consumes: Baileys `groupParticipantsUpdate()` member reports shaped as `{status, jid, content}`.
- Produces: `group.action_result_reported` events with `reasonCode=PRIVACY_BLOCKED`, `executionState=STARTED`, and a sanitized message containing protocol status `403`.

- [x] **Step 1: Write the failing mixed-result regression assertion**

Extend the existing batch test so the 403 event must match this literal behavior:

```ts
expect(events[1]).toMatchObject({
  outcome: 'FAILED',
  executionState: 'STARTED',
  reasonCode: 'PRIVACY_BLOCKED',
  reasonMessage: 'Protocol reported status 403'
})
```

Also lock the existing single-target promote behavior: a returned 403 for manager promotion remains
`GROUP_PERMISSION_DENIED`, so the batch-add fix cannot weaken genuine administrator checks.

- [x] **Step 2: Run the focused Jest test and verify RED**

Run:

```bash
cd ../armada-protocol/protocol-layer
npm test -- --runInBand src/commands/group-participants-executor.test.ts
```

Expected: FAIL because the current event contains `reasonCode=GROUP_PERMISSION_DENIED`.

- [x] **Step 3: Implement the minimal protocol mapping change**

Change only the per-participant mapping:

```ts
case '403':
  return batchAdd ? 'PRIVACY_BLOCKED' : 'GROUP_PERMISSION_DENIED'
```

Keep `failedResult()` unchanged so a thrown call/account-level 403 remains `GROUP_PERMISSION_DENIED`.

- [x] **Step 4: Run the focused Jest test and verify GREEN**

Run the same focused Jest command and require exit code 0.

### Task 2: Add backend compatibility protection

**Files:**
- Modify: `armada-api/src/test/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultServiceTest.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullCallParticipantResultService.java`

**Interfaces:**
- Consumes: `PullTaskBatchParticipantCallback.executionState()` and normalized `reasonCode`.
- Produces: target facts still close normally, while puller availability changes only for confirmed pre-side-effect account failures.

- [x] **Step 1: Write the failing participant-level protection test**

Replace the old participant-level removal expectation with:

```java
@Test
void participantPermissionResultDoesNotRemovePullerAfterBatchStarted() {
    stubAccountFailure("GROUP_PERMISSION_DENIED");

    assertThat(service.handle(callback(
            PullTaskBatchParticipantProtocolOutcome.FAILED,
            PullTaskParticipantExecutionState.STARTED,
            false, "GROUP_PERMISSION_DENIED"))).isTrue();

    verify(accountMapper, never()).markUnavailable(
            anyLong(), anyInt(), any(), any(), anyLong());
    verify(stickyPullers, never()).invalidateIfCurrent(
            any(), any(), any(), anyLong());
}
```

- [x] **Step 2: Preserve account-level removal coverage**

Add a second test using `UNKNOWN + NOT_STARTED + GROUP_PERMISSION_DENIED` and retain the existing assertions that the puller becomes `REMOVED` and the sticky generation is invalidated.

- [x] **Step 3: Run the focused JUnit test and verify RED**

Run:

```bash
cd armada-api
mvn -Dtest='PullTaskPullCallParticipantResultServiceTest' test
```

Expected: FAIL because `STARTED` participant failures currently remove the puller.

- [x] **Step 4: Implement the minimal execution-state guard**

In the `GROUP_PERMISSION_DENIED` branch, return without changing puller availability when:

```java
callback.executionState() == PullTaskParticipantExecutionState.STARTED
```

Retain current removal behavior for `NOT_STARTED` events.

- [x] **Step 5: Run the focused JUnit test and verify GREEN**

Run the same focused Maven command and require exit code 0.

### Task 3: Cross-repository verification

**Files:**
- Verify only; no new production files.

**Interfaces:**
- Consumes: the protocol event contract and backend callback state machine after Tasks 1 and 2.
- Produces: fresh test and compile evidence for handoff.

- [x] **Step 1: Verify protocol tests and TypeScript compilation**

```bash
cd ../armada-protocol/protocol-layer
npm test -- --runInBand src/commands/group-participants-executor.test.ts
npm run lint
```

- [x] **Step 2: Verify backend result-adapter and participant-service coverage**

```bash
cd armada-api
mvn -Dtest='PullTaskPullCallParticipantResultServiceTest,ProtocolPullTaskBatchParticipantResultAdapterTest,ProtocolGroupActionResultAdapterTest' test
```

- [x] **Step 3: Review both diffs for scope and sensitive data**

Confirm only the two production files, two test files, and this plan changed; confirm no raw Baileys `content`, phone, JID, credentials, or payload was newly logged or persisted.

- [x] **Step 4: Do not deploy without a separate test1 deployment request**

Report the local fix and verification evidence. Existing test1 task #46 remains unchanged until deployment and an explicitly authorized recovery action.
