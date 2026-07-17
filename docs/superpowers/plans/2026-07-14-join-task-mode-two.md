# Join Task Mode Two Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make join-task mode two validate capacity, assign each group to exactly one account by round-robin, run account lanes concurrently, and honor configured retries.

**Architecture:** Creation performs server-side invariants before persistence. The pure plan generator assigns link `i` to account `i mod M`. The worker groups pending rows by account, executes each account lane serially with its own interval, runs lanes concurrently on a separate bounded executor, and uses normalized protocol retryability to stop permanent failures immediately.

**Tech Stack:** Java 17, Spring Boot, MyBatis, JUnit 5, Mockito

---

**Constraint:** Work on `1.0.1-snapshot` in place and do not commit.

### Task 1: Consume protocol retryability

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/exception/ProtocolErrorCode.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/exception/ProtocolException.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/ProtocolHttpExecutor.java`
- Test: `armada-api/src/test/java/com/armada/platform/protocol/http/ProtocolHttpExecutorTest.java`

- [ ] **Step 1: Write a failing HTTP error-contract test**

Return an error body containing `code=INVITE_REVOKED` and `retryable=false`; assert the resulting `ProtocolException` retains both fields.

- [ ] **Step 2: Verify the test fails**

Run: `mvn -Dtest=ProtocolHttpExecutorTest test`

Expected: FAIL because retryability and join error codes are not represented.

- [ ] **Step 3: Implement the contract**

Add join error codes, parse top-level `retryable`, store it in `ProtocolException.Metadata`, and expose `Optional<Boolean> retryable()`.

- [ ] **Step 4: Verify the test passes**

Run: `mvn -Dtest=ProtocolHttpExecutorTest test`

Expected: PASS.

### Task 2: Validate and generate mode-two plans

**Files:**
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/JoinTaskServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/PlanRowGenerator.java`
- Test: `armada-api/src/test/java/com/armada/task/service/JoinTaskCreateServiceTest.java`
- Test: `armada-api/src/test/java/com/armada/task/service/PlanRowGeneratorTest.java`

- [ ] **Step 1: Write failing service validation tests**

Assert mode two rejects selected-account count different from `executorAccountCount`, rejects valid links above `executorAccountCount * linksPerAccount`, and accepts fewer links than capacity.

- [ ] **Step 2: Write a failing round-robin test**

For accounts A/B/C and links L1-L5, assert rows are exactly `A-L1, B-L2, C-L3, A-L4, B-L5`.

- [ ] **Step 3: Verify tests fail**

Run: `mvn -Dtest=JoinTaskCreateServiceTest,PlanRowGeneratorTest test`

Expected: FAIL against current permissive validation and duplicate-link plan.

- [ ] **Step 4: Implement validation and round-robin generation**

Validate positive mode-two inputs, exact account count, and capacity using `long`. Generate exactly one pending row per valid link in input order.

- [ ] **Step 5: Verify tests pass**

Run: `mvn -Dtest=JoinTaskCreateServiceTest,PlanRowGeneratorTest test`

Expected: PASS.

### Task 3: Execute per-account lanes with retries

**Files:**
- Modify: `armada-api/src/main/java/com/armada/task/worker/JoinTaskWorker.java`
- Test: `armada-api/src/test/java/com/armada/task/worker/JoinTaskWorkerTest.java`

- [ ] **Step 1: Write failing retry tests**

Assert retry limit 2 produces at most 3 join calls, eventual success writes success once, exhausted retry writes failure once, and `retryable=false` stops after one call.

- [ ] **Step 2: Write failing lane tests**

Assert rows for the same account remain serial and sleep between operations, while two account lanes can enter their first join call concurrently. Assert tenant context is available inside lane threads.

- [ ] **Step 3: Verify worker tests fail**

Run: `mvn -Dtest=JoinTaskWorkerTest test`

Expected: FAIL because the current worker is globally serial and never retries.

- [ ] **Step 4: Implement bounded account-lane execution**

Use a task executor for task orchestration and a distinct bounded lane executor to avoid pool self-deadlock. Group rows by account ID, propagate and restore `TenantContext`, run each lane serially, and wait for all lanes before completing the task.

- [ ] **Step 5: Implement retry decisions**

Treat `retryLimit` as additional attempts. Retry only when enabled and the exception is not explicitly non-retryable. Apply the account interval between attempts and between that account's rows; allow other account lanes to continue.

- [ ] **Step 6: Verify worker and task suites**

Run: `mvn -Dtest=JoinTaskWorkerTest,JoinTaskCreateServiceTest,PlanRowGeneratorTest,ProtocolHttpExecutorTest test`

Expected: PASS.
