# Pull Task Manager Approval Pause Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pause only the affected normal-link pull-task execution when its selected manager is awaiting group approval, show that fact clearly, and prevent repeated invite side effects.

**Architecture:** Keep Web and Android protocol result events unchanged. Armada maps `PENDING_APPROVAL` into a durable approval-wait state using the existing execution row and reason fields; the dispatch/recovery paths deliberately exclude that wait type. Android continues to verify membership inside its protocol service, while Armada only uses a member-list query for an uncertain recovery with a known group JID.

**Tech Stack:** Java 17, Spring Boot, MyBatis XML, JUnit 5/Mockito, Vue 3, TypeScript, Element Plus, pnpm.

## Global Constraints

- Do not change Android `InviteCode -> GroupParticipants` verification or its `group.join_result_reported` payload.
- Do not change the Web `/v1/groups/join` response contract or add a Web group-member read after a normal `JOINED + groupJid` result.
- `PENDING_APPROVAL` pauses one execution row only: no automatic retry, no manager replacement, no pull action, and `next_run_at = 0`.
- Group/link-terminal failures fail only their execution row; account-specific and uncertain failures retain their current recovery behavior.
- Add no Flyway migration, table, column, Kafka topic, or API field. Reuse existing `reasonCode`, `reasonMessage`, `executionStatus`, and `waitResourceType` fields.
- The implementation must not claim the group has later been approved; automatic approval recheck is explicitly out of scope.

## Execution Result (2026-08-08)

- 已将异步回调、同步恢复和补充管理员三条管理员入群路径统一收敛为
  `WAIT_RESOURCE + APPROVAL + next_run_at=0`；调度器的 Mapper claim 条件仅允许
  `MANAGER`、`PULLER`、`STATION` 三种资源等待类型，因此审批等待不会被自动领取。
- 新鲜 `JOINED` 直接使用协议事实推进；已知群 JID 的恢复和不确定结果仍保留成员查询。
  Android 的协议内成员查询未改动。
- 前端列表新增“等待入群审批”状态、筛选项和警告标签，详情沿用后端稳定原因文案。
- 按用户要求，所有改动保留在本地工作区供复核，未创建 commit。下面的 commit 检查点不执行。
- 后端聚焦回归、Web 协议 41 项测试和 Android 两项定向测试均已通过；前端全量
  `typecheck` 被既有测试编译配置问题阻断，详见变更记录。

---

### Task 1: Persist the approval-wait state and exclude it from recovery

**Files:**

- Modify: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskWaitResourceType.java`
- Modify: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskActionStatus.java`
- Modify: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskGroupAccountMembershipStatus.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskManagerJoinResultServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskResourceRecoveryTransactionService.java`
- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerJoinTransactionService.java`
- Test: `armada-api/src/test/java/com/armada/task/service/impl/PullTaskManagerJoinResultServiceImplTest.java`
- Test: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskResourceRecoveryTransactionIntegrationTest.java`
- Test: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskManagerJoinTransactionServiceTest.java`

**Interfaces:**

- Consumes: `PullTaskManagerJoinCallback.outcome()` values `JOINED`, `ALREADY_JOINED`, `PENDING_APPROVAL`, and `FAILED`.
- Produces: `PullTaskWaitResourceType.APPROVAL(4)`, `PullTaskActionStatus.PENDING_APPROVAL(7)`, and `PullTaskGroupAccountMembershipStatus.PENDING_APPROVAL(5)`.
- Produces: an execution transition with `executionStatus=WAIT_RESOURCE`, `stage=MANAGER_JOIN`, `waitResourceType=APPROVAL`, `reasonCode=MANAGER_JOIN_PENDING_APPROVAL`, `reasonMessage="管理员已提交入群申请，等待群主或管理员审批；该群拉群已暂停"`, and `nextRunAt=0L`.

- [ ] **Step 1: Write the failing callback-state tests**

```java
boolean handled = service.apply(new PullTaskManagerJoinCallback(
        7L, 100L, 11L, 601L, "cmd-pull-1",
        PullTaskManagerJoinProtocolOutcome.PENDING_APPROVAL,
        "120363group@g.us", "JOIN_PENDING_APPROVAL", "ignored", false, 5_000L));

assertThat(handled).isTrue();
assertThat(actionTransition.getValue().targetStatus())
        .isEqualTo(PullTaskActionStatus.PENDING_APPROVAL.code());
assertThat(membershipTransition.getValue().targetStatus())
        .isEqualTo(PullTaskGroupAccountMembershipStatus.PENDING_APPROVAL.code());
assertThat(executionTransition.getValue().target().executionStatus())
        .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
assertThat(executionTransition.getValue().target().waitResourceType())
        .isEqualTo(PullTaskWaitResourceType.APPROVAL.code());
assertThat(executionTransition.getValue().target().nextRunAt()).isZero();
```

Add a recovery integration assertion that an execution row with `WAIT_RESOURCE + APPROVAL` is not claimed by resource recovery and retains its version, reason, and `next_run_at=0`.

- [ ] **Step 2: Run the failing backend tests**

Run:

```bash
cd armada-api && mvn -q -Dtest=PullTaskManagerJoinResultServiceImplTest,PullTaskResourceRecoveryTransactionIntegrationTest,PullTaskManagerJoinTransactionServiceTest test
```

Expected: failure because approval-specific enums and transition classification do not exist.

- [ ] **Step 3: Implement the minimal state-machine changes**

Add the three enum values with the exact numeric codes above. Add `PENDING_APPROVAL` to `ResultKind`; map only the protocol outcome `PENDING_APPROVAL` to it. Map that kind to the approval action/member statuses and to the target below; preserve a nonblank callback `groupJid` through the existing `COALESCE` mapper behavior.

```java
case PENDING_APPROVAL -> new PullTaskManagerJoinResultTransition.Target(
        PullTaskExecutionStatus.WAIT_RESOURCE.code(),
        PullTaskExecutionStage.MANAGER_JOIN.code(),
        callback.groupJid(), PullTaskWaitResourceType.APPROVAL.code(),
        PullTaskExecutionReasonCode.MANAGER_JOIN_PENDING_APPROVAL.name(),
        "管理员已提交入群申请，等待群主或管理员审批；该群拉群已暂停",
        0L, null);
```

Make `PullTaskResourceRecoveryTransactionService` reject or skip wait type `APPROVAL` before it evaluates manager/puller/station availability. Make `PullTaskManagerJoinTransactionService.prepareExisting` return deferred without creating a command when the retained manager/action facts are both `PENDING_APPROVAL`.

- [ ] **Step 4: Run the focused backend tests**

Run the command from Step 2.

Expected: PASS; pending approval writes no due time, recovery does not claim it, and no new outbox command is created.

- [ ] **Step 5: Commit the state-machine change**

```bash
git add armada-api/src/main/java/com/armada/task/model/enums/PullTaskWaitResourceType.java \
  armada-api/src/main/java/com/armada/task/model/enums/PullTaskActionStatus.java \
  armada-api/src/main/java/com/armada/task/model/enums/PullTaskGroupAccountMembershipStatus.java \
  armada-api/src/main/java/com/armada/task/service/impl/PullTaskManagerJoinResultServiceImpl.java \
  armada-api/src/main/java/com/armada/task/scheduler/PullTaskResourceRecoveryTransactionService.java \
  armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerJoinTransactionService.java \
  armada-api/src/test/java/com/armada/task/service/impl/PullTaskManagerJoinResultServiceImplTest.java \
  armada-api/src/test/java/com/armada/task/scheduler/PullTaskResourceRecoveryTransactionIntegrationTest.java \
  armada-api/src/test/java/com/armada/task/scheduler/PullTaskManagerJoinTransactionServiceTest.java
git commit -m "fix: pause pull group awaiting manager approval"
```

### Task 2: Remove redundant post-success member reads while retaining uncertain recovery verification

**Files:**

- Modify: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerJoinProcessor.java`
- Test: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskManagerJoinProcessorTest.java`

**Interfaces:**

- Consumes: a `GroupJoinResult` whose `outcome()` is `JOINED` and whose `groupJid()` is nonblank.
- Produces: `PullTaskManagerJoinOutcome.confirmed(groupJid)` without calling `GroupMemberListPort.list`.
- Retains: `knownGroupJid` recovery calls `GroupMemberListPort.list` exactly once before progressing.

- [ ] **Step 1: Rewrite the success test to forbid the member-list call**

```java
when(joinPort.join(work.joinCommand()))
        .thenReturn(new GroupJoinResult("120363group@g.us", GroupJoinOutcome.JOINED));

assertThat(processor.process(candidate, "worker-1", 1_000L))
        .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
verify(memberListPort, never()).list(work.memberListQuery("120363group@g.us"));
verify(transactions).complete(work,
        PullTaskManagerJoinOutcome.confirmed("120363group@g.us"), 1_000L);
```

Keep `restartRecoveryVerifiesKnownGroupWithoutReplayingJoin` and assert it still performs the one member-list query.

- [ ] **Step 2: Run the failing processor test**

Run:

```bash
cd armada-api && mvn -q -Dtest=PullTaskManagerJoinProcessorTest test
```

Expected: failure because a fresh `JOINED` result currently invokes `verifyMembership`.

- [ ] **Step 3: Make fresh success authoritative**

Change `joinAndVerify` so `JOINED` with a nonblank group JID returns `confirmed(groupJid)` immediately. Keep the early `knownGroupJid` branch unchanged, so only a restart/recovery with a previously known JID reads the current members.

- [ ] **Step 4: Run the focused processor test**

Run the command from Step 2.

Expected: PASS; fresh Web success and Android’s already-verified success do not trigger a duplicate list read, while uncertain recovery remains protected.

- [ ] **Step 5: Commit the verification-boundary change**

```bash
git add armada-api/src/main/java/com/armada/task/scheduler/PullTaskManagerJoinProcessor.java \
  armada-api/src/test/java/com/armada/task/scheduler/PullTaskManagerJoinProcessorTest.java
git commit -m "fix: trust confirmed manager group join result"
```

### Task 3: Make waiting approval explicit in the ordinary pull-task UI

**Files:**

- Modify: `wheel-saas-pure-web/src/views/task/pull-task/standard-execution-display.ts`
- Test: `wheel-saas-pure-web/src/views/task/pull-task/standard-execution-display.test.ts`
- Test: `wheel-saas-pure-web/src/views/task/pull-task/components/PullTaskExecutionResourceActions.test.ts`

**Interfaces:**

- Consumes: the unchanged execution summary payload with `executionStatus=3`, `waitResourceType=4`, `reasonCode="MANAGER_JOIN_PENDING_APPROVAL"`, and the backend reason message.
- Produces: list status key `WAITING_APPROVAL`; existing `blockReason` continues to display the stable backend message and no supplement-manager action is offered.

- [ ] **Step 1: Write the failing display and action tests**

```ts
assert.equal(
  standardExecutionStatus({
    executionStatus: 3,
    stage: 2,
    waitResourceType: 4,
    reasonCode: "MANAGER_JOIN_PENDING_APPROVAL"
  }),
  "WAITING_APPROVAL"
);
```

Add a source-level action assertion that `waitResourceType === 4` does not make the “补充管理员” action visible.

- [ ] **Step 2: Run the failing frontend tests**

Run:

```bash
cd wheel-saas-pure-web && pnpm test -- --run src/views/task/pull-task/standard-execution-display.test.ts src/views/task/pull-task/components/PullTaskExecutionResourceActions.test.ts
```

Expected: the status is currently `INITIALIZING` and no dedicated waiting-approval assertion exists.

- [ ] **Step 3: Add the waiting-approval display branch**

Place this branch before the ordinary manager/puller/station wait branches:

```ts
if (
  execution.executionStatus === 3 &&
  execution.waitResourceType === 4 &&
  execution.reasonCode === "MANAGER_JOIN_PENDING_APPROVAL"
) {
  return "WAITING_APPROVAL";
}
```

Do not add a manager supplement action for this state. The existing detail drawer and `blockReason` already render `reasonMessage`, so no API client or drawer contract change is needed.

- [ ] **Step 4: Run focused frontend verification**

Run the command from Step 2, then:

```bash
cd wheel-saas-pure-web && pnpm typecheck && pnpm lint
```

Expected: all commands pass and the execution list reports “等待审批” through the existing status-label mapping.

- [ ] **Step 5: Commit the display change**

```bash
git add src/views/task/pull-task/standard-execution-display.ts \
  src/views/task/pull-task/standard-execution-display.test.ts \
  src/views/task/pull-task/components/PullTaskExecutionResourceActions.test.ts
git commit -m "feat: show manager approval wait for pull group"
```

### Task 4: Verify cross-protocol behavior and record the decision

**Files:**

- Modify: `.harness/changes/2026-08-08-pull-task-manager-approval-pause.md`
- Modify: `docs/superpowers/specs/2026-08-08-pull-task-manager-approval-pause-design.md`
- Modify: `docs/superpowers/plans/2026-08-08-pull-task-manager-approval-pause-implementation.md`
- Verify only: `armada-protocol/protocol-layer/src/commands/group-join-executor.test.ts`
- Verify only: `whatsapp-server-feature-android-zhuan/internal/armada/join_sender_test.go`

**Interfaces:**

- Consumes: unchanged event outcomes `JOINED`, `PENDING_APPROVAL`, `ALREADY_JOINED`, and `FAILED` from both protocol implementations.
- Produces: evidence that Android’s member verification remains intact and no protocol payload change is required.

- [ ] **Step 1: Add regression cases before the full verification pass**

Ensure Java tests cover all four terminal categories: approval wait, group/link terminal failure, account-specific failure, and retryable/unknown failure. Ensure the Web test covers a fresh `JOINED` without a list read and recovery with `knownGroupJid` with one list read.

- [ ] **Step 2: Run protocol contract tests without changing protocol source**

Run:

```bash
cd armada-protocol/protocol-layer && npm test -- --runInBand src/commands/group-join-executor.test.ts
cd whatsapp-server-feature-android-zhuan && go test ./internal/armada -run 'TestZhuanGroupJoinSender(UsesExactWSPhoneAndConfirmsMembership|ReturnsPendingWhenSelfIsAbsent)$'
```

Expected: both pass unchanged; Android still calls `GroupParticipants` and emits `PENDING_APPROVAL` when self is absent.

- [ ] **Step 3: Run the backend and frontend regression suites**

Run:

```bash
cd armada/armada-api && mvn -q -Dtest=PullTaskManagerJoinResultServiceImplTest,PullTaskManagerJoinProcessorTest,PullTaskManagerJoinTransactionServiceTest,PullTaskResourceRecoveryTransactionIntegrationTest test
cd wheel-saas-pure-web && pnpm test -- --run src/views/task/pull-task/standard-execution-display.test.ts src/views/task/pull-task/components/PullTaskExecutionResourceActions.test.ts
git -C armada diff --check
git -C wheel-saas-pure-web diff --check
```

Expected: all targeted tests and whitespace checks pass. Do not claim a full Maven/Go suite passed unless it is actually run and its output is recorded.

- [ ] **Step 4: Update change evidence and decision records**

Record exact command output, modified API behavior, absence of Android/Web protocol modifications, the no-Flyway decision, and the explicit non-goal of automatic approval recheck.

- [ ] **Step 5: Commit documentation**

```bash
git add .harness/changes/2026-08-08-pull-task-manager-approval-pause.md \
  docs/superpowers/specs/2026-08-08-pull-task-manager-approval-pause-design.md \
  docs/superpowers/plans/2026-08-08-pull-task-manager-approval-pause-implementation.md
git commit -m "docs: plan manager approval pause"
```

## Self-Review

- Spec coverage: Task 1 implements single-group pause, explicit persisted facts, no repeated command, and correct group/account/unknown failure separation. Task 2 implements the Web success member-query boundary while retaining recovery verification. Task 3 exposes the state to users. Task 4 verifies the unchanged Android behavior and records operational limits.
- Completeness scan: every implementation action has an exact target, expected state transition, and verification command; no generic follow-up markers remain.
- Type consistency: the producer enum values are defined in Task 1 and used consistently by the backend transition and frontend status branch; the protocol event names are unchanged.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-08-pull-task-manager-approval-pause-implementation.md`. Two execution options:

1. Subagent-Driven (recommended) - Dispatch a fresh subagent per task, review between tasks, fast iteration.
2. Inline Execution - Execute tasks in this session using executing-plans, batch execution with checkpoints.
