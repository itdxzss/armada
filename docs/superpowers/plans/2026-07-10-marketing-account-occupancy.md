# Marketing Account Occupancy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为普通群组营销任务增加账号级独占租约：创建时按账号分组检查当前占用，执行期动态抢占空闲账号、逐轮记录被占用跳过明细，并在任务停止或结束时可靠释放。

**Architecture:** 在 marketing 聚合内新增 current-only 的 `marketing_account_occupancy` 表，以 `(tenant_id, account_id)` 唯一键作为并发闸门；占用表只保存账号、普通营销任务和占用时间，任务名称与结束时间继续以 `marketing_task` 为唯一事实源。`MarketingAccountOccupancyService` 统一封装创建门禁、任务账号抢占、owner 查询、释放和残留清理；所有进入/离开 `SENDING` 的路径调用它。Round Worker 每轮为尚未归当前任务的账号补抢占，并把仍被其它任务占用的实际群目标写成 `SKIPPED/ACCOUNT_OCCUPIED`，不进入 outbox、不计失败。

**Tech Stack:** Java 17、Spring Boot 3.3、MyBatis XML、MySQL 8、Flyway、JUnit 5、Mockito、AssertJ、Vue 3（现有错误提示链路复用，无新增页面交互）。

**Execution override:** 用户要求在当前本地工作树查看结果且不要 commit。本计划所有 commit 步骤均省略；不得修改 `.claude/worktrees` 条目。建群营销不纳入账号占用。

**Execution status:** Tasks 1–5 and Task 6 steps 1, 2, 4 are implemented and locally verified. Task 6 step 3 remains pending until the target database from `armada-api/.env` is explicitly confirmed; no Flyway repair will be performed.

---

### Task 1: Account occupancy persistence

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V049__marketing_account_occupancy.sql`
- Create: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingAccountOccupancyOwnerRow.java`
- Create: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingAccountOccupancyMapper.java`
- Create: `armada-api/src/main/resources/mapper/marketing/MarketingAccountOccupancyMapper.xml`
- Create: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingAccountOccupancyMapperSqlShapeTest.java`
- Create: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingAccountOccupancyMapperDbTest.java`

- [ ] **Step 1: Write failing SQL-shape and DbTest coverage**

The tests must require these contracts before production files exist:

```java
assertThat(xml).contains("UNIQUE KEY uq_marketing_account_occupancy_account");
assertThat(insertSql).contains("INSERT IGNORE INTO marketing_account_occupancy");
assertThat(insertSql).contains("mt.status = 2");
assertThat(releaseSql).contains("marketing_task_id = #{taskId}");
```

The true DB test seeds two sending tasks sharing one account and proves only one active row can exist, then proves task-scoped release allows the second task to acquire it.

- [ ] **Step 2: Run RED**

Run:

```bash
cd armada-api
mvn -Dtest=MarketingAccountOccupancyMapperSqlShapeTest test
```

Expected: FAIL because the migration/mapper contract is missing.

- [ ] **Step 3: Add the current-only occupancy table and mapper**

The table must contain exactly the current lease facts:

```sql
CREATE TABLE marketing_account_occupancy (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    marketing_task_id BIGINT NOT NULL,
    occupied_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_marketing_account_occupancy_account (tenant_id, account_id),
    KEY idx_marketing_account_occupancy_task (tenant_id, marketing_task_id)
);
```

Do not duplicate `task_name` or `task_end_at`; owner projections join `marketing_task`. Mapper operations:

```java
int insertAvailableTaskAccounts(Long taskId, long occupiedAt);
List<MarketingAccountOccupancyOwnerRow> selectOwnersByTaskAccounts(Long taskId);
MarketingAccountOccupancyOwnerRow selectFirstOwnerByAccountGroupId(Long accountGroupId);
int releaseByTaskId(Long taskId);
int deleteStale(long now);
```

Stale means owner task missing/deleted, not `SENDING`, or past `task_end_at`.

- [ ] **Step 4: Run GREEN and XML validation**

```bash
xmllint --noout src/main/resources/mapper/marketing/MarketingAccountOccupancyMapper.xml
mvn -Dtest=MarketingAccountOccupancyMapperSqlShapeTest test
```

Expected: XML valid and focused test passes.

### Task 2: Occupancy domain service and create-time group gate

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountOccupancyService.java`
- Create: `armada-api/src/test/java/com/armada/marketing/service/MarketingAccountOccupancyServiceTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeService.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplLifecycleTest.java`

- [ ] **Step 1: Write failing service tests**

Cover:

```java
assertThatThrownBy(() -> service.assertAccountGroupAvailable(12L, now))
    .hasMessage("该分组已被营销任务【夏季营销】占用，预计于【2026-07-10 20:00:00】释放，请稍后重试。");
```

Also prove multiple/unknown owner information falls back to:

```text
该分组正在执行其它营销任务，请等待当前任务结束后再参与新的营销任务。
```

Creation must invoke the gate before target/task insertion. Account-tree loading must use the same gate so occupied groups cannot expose selectable accounts.

- [ ] **Step 2: Run RED**

```bash
mvn -Dtest=MarketingAccountOccupancyServiceTest,MarketingTaskServiceImplLifecycleTest test
```

Expected: FAIL because no occupancy service/gate exists.

- [ ] **Step 3: Implement the minimal service**

Use `Asia/Shanghai` and `yyyy-MM-dd HH:mm:ss` for rich prompts. Public methods:

```java
void assertAccountGroupAvailable(Long accountGroupId, long now);
Map<Long, MarketingAccountOccupancyOwnerRow> acquireAndLoadTaskAccounts(MarketingTask task, long now);
int releaseTaskAccounts(Long taskId);
```

`assertAccountGroupAvailable` first deletes stale rows, then reads the first current owner. `createTask` calls it before building targets. `accountTree` calls it before loading accounts; the existing frontend `apiErrorMessage` path displays the backend message.

- [ ] **Step 4: Run GREEN**

```bash
mvn -Dtest=MarketingAccountOccupancyServiceTest,MarketingTaskServiceImplLifecycleTest test
```

Expected: focused tests pass.

### Task 3: Acquire and release at every lifecycle transition

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingTaskLifecycleWorker.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTemplateServiceImpl.java`
- Modify: corresponding lifecycle/template/round unit and DbTests

- [ ] **Step 1: Write failing lifecycle tests**

Tests must prove:

```java
// PENDING stays unoccupied before task_start_at.
verify(occupancyService, never()).acquireAndLoadTaskAccounts(any(), anyLong());

// Entering SENDING acquires all currently free target accounts.
verify(occupancyService).acquireAndLoadTaskAccounts(task, now);

// Leaving SENDING releases only this task's leases.
verify(occupancyService).releaseTaskAccounts(taskId);
```

Cover immediate create, active-window manual start, immediate restart, scheduled start, manual stop, scheduled end, Round Worker end guard, early defer, and template-delete stop.

- [ ] **Step 2: Run RED**

```bash
mvn -Dtest=MarketingTaskServiceImplLifecycleTest,MarketingTaskLifecycleWorkerTest,MarketingRoundWorkerTest,MarketingTemplateServiceImplTest test
```

Expected: FAIL on missing acquire/release interactions.

- [ ] **Step 3: Implement lifecycle hooks transactionally**

Rules:

```text
PENDING -> PENDING: no acquire
PENDING/STOPPED/ENDED -> SENDING: acquire available accounts in the same transaction
SENDING -> STOPPED/ENDED/PENDING: release task-owned rows in the same transaction
```

Template batch deletion releases occupancies for every affected ordinary task. Every log includes tenantId/taskId/acquired/released counts.

- [ ] **Step 4: Run GREEN**

Run the same focused suite and expect all tests to pass.

### Task 4: Per-round dynamic re-acquisition and occupied skip attempts

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerDbTest.java`

- [ ] **Step 1: Write failing Round Worker tests**

Cover a mixed round:

```java
assertThat(attempts).filteredOn(a -> a.getStatus() == MarketingSendAttemptStatus.SKIPPED.code())
    .extracting(MarketingTaskSendAttempt::getReasonCode)
    .containsOnly("ACCOUNT_OCCUPIED");
verify(outbox).enqueueMarketingMessageCommands(argThat(commands -> commands.size() == freeTargetCount));
```

Also cover all-occupied rounds: claim the round, write skipped attempts, advance `next_round_at`, produce no outbox, and do not increment failed counters. A later round where the owner disappears must acquire and send with the newly free account.

- [ ] **Step 2: Run RED**

```bash
mvn -Dtest=MarketingRoundWorkerTest test
```

Expected: FAIL because every resolved target currently enters outbox.

- [ ] **Step 3: Partition resolved targets by owner**

At each due round:

```java
Map<Long, MarketingAccountOccupancyOwnerRow> owners =
        occupancyService.acquireAndLoadTaskAccounts(task, now);
```

Targets owned by this task are sendable. Targets owned by another task become `SKIPPED` attempts with `ACCOUNT_OCCUPIED`; use the rich owner message when task/end time are available. Insert skipped and submitted attempts in one transaction, but enqueue only submitted attempts. Skipped attempts never call `incrementTaskSendCounters` or target-failure updates.

- [ ] **Step 4: Run GREEN**

```bash
mvn -Dtest=MarketingRoundWorkerTest test
```

Expected: mixed, all-occupied, and later-reacquired cases pass.

### Task 5: Expose occupied skip details without treating them as failures

**Files:**
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java` or focused detail DbTest

- [ ] **Step 1: Write failing detail-query tests**

Require `selectAccountGroupStatsByTaskId` to include attempt status `3`, keep failure totals limited to status `2`, and expose the latest skipped reason.

- [ ] **Step 2: Run RED**

```bash
mvn -Dtest=MarketingTaskMapperSqlShapeTest test
```

Expected: FAIL because the current query contains `a.status IN (1, 2)` and only status `2` contributes a reason.

- [ ] **Step 3: Update the aggregation SQL**

Use:

```sql
AND a.status IN (1, 2, 3)
```

`sentMessageCount` remains status `1`; `failedMessageCount` remains status `2`; `lastReason` includes status `2` and `3` only when the latest completed attempt is failed/skipped. A later success clears the old reason. The existing frontend already renders `lastReason`, so no new frontend branch is required.

- [ ] **Step 4: Run GREEN and XML validation**

```bash
xmllint --noout src/main/resources/mapper/marketing/MarketingTaskMapper.xml
mvn -Dtest=MarketingTaskMapperSqlShapeTest test
```

### Task 6: Harness artifacts and verification

**Files:**
- Modify: `.harness/changes/marketing-task/summary.md`
- Modify: `.harness/changes/marketing-task/db-migrations.sql`
- Modify: `.harness/changes/marketing-task/rollback.sql`
- Regenerate: `.harness/wiki/数据模型.md` after a confirmed true-DB migration run

- [ ] **Step 1: Update migration and rollback artifacts**

Append the V049 table creation to `db-migrations.sql`; rollback drops `marketing_account_occupancy` before task tables. Document that occupancy applies only to ordinary marketing tasks.

- [ ] **Step 2: Run focused regression**

```bash
mvn -Dtest=MarketingAccountOccupancyMapperSqlShapeTest,MarketingAccountOccupancyServiceTest,MarketingTaskServiceImplLifecycleTest,MarketingTaskLifecycleWorkerTest,MarketingRoundWorkerTest,MarketingRoundSchedulerTest,MarketingTaskMapperSqlShapeTest,MarketingTemplateServiceImplTest test
```

- [ ] **Step 3: Run true DB tests only against a confirmed local/test schema**

```bash
mvn -Dtest=MarketingAccountOccupancyMapperDbTest,MarketingTaskMutationDbTest,MarketingRoundWorkerDbTest test
```

If the known Flyway V037/V041 checksum mismatch still blocks startup, do not run `flyway repair`; report the exact blocker.

- [ ] **Step 4: Final static checks**

```bash
xmllint --noout src/main/resources/mapper/marketing/MarketingAccountOccupancyMapper.xml
xmllint --noout src/main/resources/mapper/marketing/MarketingTaskMapper.xml
git diff --check
git status --short
```

Expected: XML valid, no whitespace errors, no commit created, and `.claude/worktrees` entries untouched.

## Self-review

- Spec coverage: creation hard gate, delayed start, partial per-account execution, later-round re-acquisition, skip detail, release, stale cleanup and rich/generic messages are mapped to Tasks 1–5.
- Scope: only ordinary `marketing_task`; no group-creation mapper/service/worker behavior changes except existing classes may remain test dependencies.
- One-fact rule: owner task name/end time are joined from `marketing_task`, not copied into occupancy.
- No dead entity: the current-only table is write/delete oriented and owner reads are joined projections, so no unused table entity is introduced.
- Concurrency: database unique `(tenant_id, account_id)` is authoritative; Java pre-check is not treated as a lock.
- Queue rule: release does not cancel already-enqueued outbox commands.
- No placeholders: all required methods, states, reason code, messages, commands and affected paths are named above.
