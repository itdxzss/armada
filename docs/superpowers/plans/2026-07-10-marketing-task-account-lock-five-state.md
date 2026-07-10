# Marketing Task Account Lock and Five-State Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make ordinary group-marketing tasks lock selected accounts when creation commits, retain locks while pending/running/paused, release only on completed/closed terminal states, and expose the confirmed start/pause/resume/close UI without restart.

**Architecture:** Reuse `marketing_account_occupancy` as the current-only database lock with its existing `(tenant_id, account_id)` unique key. Reuse task status codes 1/2/5/7, add 8, make creation acquire all target accounts transactionally, and centralize terminal release. The frontend consumes per-account lock metadata and a five-state action map; protocol-layer queues remain unchanged.

**Tech Stack:** Java 17, Spring Boot 3.3, MyBatis XML, MySQL 8, Flyway, JUnit 5, Mockito, AssertJ, Vue 3, TypeScript, Element Plus, Node test runner.

---

## File map

### Backend state and lifecycle

- Create `armada-api/src/main/resources/db/migration/V050__marketing_task_five_state_lifecycle.sql`: forward-only task-status migration.
- Modify `armada-api/src/main/java/com/armada/marketing/model/enums/MarketingTaskStatus.java`: expose only PENDING/SENDING/PAUSED/COMPLETED/CLOSED.
- Modify `armada-api/src/main/java/com/armada/marketing/service/MarketingTaskService.java`: replace restart/stop with pause/resume/close.
- Modify `armada-api/src/main/java/com/armada/marketing/controller/MarketingTaskController.java`: publish the four lifecycle endpoints.
- Modify `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`: lifecycle and terminal-delete contracts.
- Modify `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`: guarded five-state SQL.
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`: lifecycle orchestration and terminal account release.
- Delete `armada-api/src/main/java/com/armada/marketing/model/dto/RestartMarketingTaskDTO.java`: restart is no longer a supported concept.

### Backend account lock and account tree

- Modify `armada-api/src/main/java/com/armada/marketing/mapper/MarketingAccountOccupancyMapper.java` and its XML: active-owner semantics and per-account owner reads.
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountOccupancyService.java`: strict create-time all-account lock and exact conflict message.
- Modify `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingAccountTreeAccountRow.java`: carry owner projection fields from SQL.
- Modify `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTreeAccountVO.java`: expose structured lock fields.
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeService.java`: row-level disabled behavior instead of group rejection.

### Backend scheduler and linked mutations

- Modify `MarketingTaskLifecycleWorker.java`, `MarketingRoundWorker.java`, and `MarketingRoundScheduler.java`: preserve locks outside terminal transitions.
- Modify `MarketingTemplateServiceImpl.java`: complete and release nonterminal tasks when their template is removed.
- Modify focused Java unit, SQL-shape, controller, migration, and DbTests under `armada-api/src/test/java/com/armada/marketing/**`.

### Frontend

- Modify `wheel-saas-pure-web/src/api/marketing-task.ts`: five status codes, lock metadata, pause/resume/close APIs, no restart.
- Modify `src/views/task/group-marketing/constants.ts`: five labels and one lifecycle action map.
- Modify `components/GroupMarketingTaskTable.vue`: one start/pause/resume action and separate close action.
- Modify `components/GroupMarketingCreateDrawer.vue`: locked account exclusion and exact Tooltip.
- Modify `composables/marketing-selection.ts`: defensive locked-account exclusion.
- Modify `composables/useGroupMarketingTaskPage.ts` and `index.vue`: lifecycle calls and close confirmation.
- Delete `components/GroupMarketingRestartDialog.vue`, `composables/useMarketingTaskRestart.ts`, and their test.
- Rewrite focused Node tests in the same group-marketing directory.

## Task 1: Add the five-state database and enum contract

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V050__marketing_task_five_state_lifecycle.sql`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/enums/MarketingTaskStatus.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/MarketingTaskDataModelMigrationDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`

- [ ] **Step 1: Write failing status and migration tests**

Add a focused enum test to `MarketingTaskMapperSqlShapeTest` or a new `MarketingTaskStatusTest`:

```java
@Test
void taskStatus_exposesOnlyFiveLifecycleStates() {
    assertThat(MarketingTaskStatus.values())
            .extracting(MarketingTaskStatus::name, MarketingTaskStatus::code)
            .containsExactly(
                    tuple("PENDING", 1),
                    tuple("SENDING", 2),
                    tuple("PAUSED", 5),
                    tuple("COMPLETED", 7),
                    tuple("CLOSED", 8));
}
```

Extend `MarketingTaskDataModelMigrationDbTest` so the V050 migration text must contain:

```java
assertThat(migrationSql).contains("status IN (3, 4, 6)");
assertThat(migrationSql).contains("SET status = 7");
assertThat(migrationSql).contains("8=已关闭");
assertThat(migrationSql).contains("DELETE FROM marketing_account_occupancy");
```

- [ ] **Step 2: Run RED**

Run:

```bash
cd armada-api
mvn -Dtest=MarketingTaskMapperSqlShapeTest test
```

Expected: FAIL because PAUSED/COMPLETED/CLOSED do not exist and the enum still contains SUCCESS/FAILED/STOPPED/PARTIAL_FAILED/ENDED.

- [ ] **Step 3: Add the migration and minimal enum**

Create V050 with this forward migration:

```sql
UPDATE marketing_task
SET status = 7,
    next_round_at = NULL,
    finished_at = COALESCE(finished_at, updated_at)
WHERE status IN (3, 4, 6);

DELETE FROM marketing_account_occupancy
WHERE marketing_task_id IN (
    SELECT id
    FROM marketing_task
    WHERE status = 7 OR deleted_at IS NOT NULL
);

ALTER TABLE marketing_task
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1
    COMMENT '任务状态:1=未启动 2=执行中 5=已暂停 7=已完成 8=已关闭';
```

Replace the task enum constants with:

```java
PENDING(1),
SENDING(2),
PAUSED(5),
COMPLETED(7),
CLOSED(8);
```

Keep `fromStartMode` returning only PENDING or SENDING.

- [ ] **Step 4: Run GREEN and static migration checks**

Run:

```bash
mvn -Dtest=MarketingTaskMapperSqlShapeTest test
git diff --check
```

Expected: focused enum/SQL-shape tests pass and no whitespace errors are reported. Do not run the true-DB migration test until the local/test schema target is explicitly confirmed.

- [ ] **Step 5: Commit**

```bash
git add armada-api/src/main/resources/db/migration/V050__marketing_task_five_state_lifecycle.sql armada-api/src/main/java/com/armada/marketing/model/enums/MarketingTaskStatus.java armada-api/src/test/java/com/armada/marketing/MarketingTaskDataModelMigrationDbTest.java armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java
git commit -m "feat(marketing): define five-state task lifecycle"
```

## Task 2: Lock every selected account in the creation transaction

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingAccountOccupancyMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingAccountOccupancyMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountOccupancyService.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingAccountOccupancyMapperSqlShapeTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingAccountOccupancyServiceTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplLifecycleTest.java`

- [ ] **Step 1: Write failing strict-lock tests**

Add these behaviors to `MarketingAccountOccupancyServiceTest`:

```java
@Test
void lockTaskAccountsOrThrow_otherOwner_rejectsWithExactTaskName() {
    MarketingTask task = task(42L, MarketingTaskStatus.PENDING.code());
    MarketingAccountOccupancyOwnerRow owner = owner(31L, 99L, "夏季营销");
    when(mapper.selectOwnersByTaskAccounts(42L)).thenReturn(List.of(owner));

    assertThatThrownBy(() -> service.lockTaskAccountsOrThrow(task, 1_000L))
            .isInstanceOf(BusinessException.class)
            .hasMessage("该账号正在被任务【夏季营销】占用，请先关闭原任务后再使用。");
}

@Test
void lockTaskAccountsOrThrow_allOwnedByCurrentTask_succeeds() {
    MarketingTask task = task(42L, MarketingTaskStatus.PENDING.code());
    task.setSelectedAccountCount(1);
    when(mapper.selectOwnersByTaskAccounts(42L))
            .thenReturn(List.of(owner(31L, 42L, "当前任务")));

    service.lockTaskAccountsOrThrow(task, 1_000L);

    verify(mapper).insertAvailableTaskAccounts(42L, 1_000L);
}

@Test
void lockTaskAccountsOrThrow_missingOwner_rejectsAndRollsBackCreation() {
    MarketingTask task = task(42L, MarketingTaskStatus.PENDING.code());
    task.setSelectedAccountCount(2);
    when(mapper.selectOwnersByTaskAccounts(42L))
            .thenReturn(List.of(owner(31L, 42L, "当前任务")));

    assertThatThrownBy(() -> service.lockTaskAccountsOrThrow(task, 1_000L))
            .isInstanceOf(BusinessException.class)
            .hasMessage("营销账号锁定失败，请刷新后重试");
}
```

Change `MarketingTaskServiceImplLifecycleTest` to require strict locking for a future PENDING task after targets persist:

```java
service.createTask(futurePendingRequest());
verify(occupancyService).lockTaskAccountsOrThrow(eq(insertedTask.get()), anyLong());
```

Update the SQL-shape test to require active states and no end-time stale deletion:

```java
assertThat(insertSql).contains("mt.status IN (1, 2, 5)");
assertThat(ownerSql).contains("mt.status IN (1, 2, 5)");
assertThat(staleSql).contains("mt.status IN (1, 2, 5)");
assertThat(staleSql).doesNotContain("task_end_at");
```

- [ ] **Step 2: Run RED**

```bash
cd armada-api
mvn -Dtest=MarketingAccountOccupancyMapperSqlShapeTest,MarketingAccountOccupancyServiceTest,MarketingTaskServiceImplLifecycleTest test
```

Expected: FAIL because future tasks are not locked, the strict method does not exist, and SQL only recognizes status 2.

- [ ] **Step 3: Implement strict create-time locking**

Add this public method to `MarketingAccountOccupancyService`:

```java
@Transactional(rollbackFor = Exception.class)
public Map<Long, MarketingAccountOccupancyOwnerRow> lockTaskAccountsOrThrow(
        MarketingTask task, long now) {
    Map<Long, MarketingAccountOccupancyOwnerRow> owners = acquireAndLoadTaskAccounts(task, now);
    MarketingAccountOccupancyOwnerRow conflict = owners.values().stream()
            .filter(owner -> !Objects.equals(task.getId(), owner.getMarketingTaskId()))
            .sorted(Comparator.comparing(MarketingAccountOccupancyOwnerRow::getAccountId))
            .findFirst()
            .orElse(null);
    if (conflict != null) {
        String taskName = StringUtils.hasText(conflict.getTaskName())
                ? conflict.getTaskName()
                : "其它营销任务";
        throw new BusinessException(ErrorCode.CONFLICT,
                "该账号正在被任务【" + taskName + "】占用，请先关闭原任务后再使用。");
    }
    int expected = task.getSelectedAccountCount() == null ? 0 : task.getSelectedAccountCount();
    if (owners.size() != expected) {
        throw new BusinessException(ErrorCode.CONFLICT, "营销账号锁定失败，请刷新后重试");
    }
    return owners;
}
```

Change occupancy SQL guards from `status = 2` to `status IN (1, 2, 5)`. Change `deleteStale` so it retains every row whose owner task exists, is not deleted, and is in those three states; remove the `task_end_at` predicate.

In `createTask`, remove the group-level `assertAccountGroupAvailable` call and replace the conditional SENDING acquisition with an unconditional call after target insertion:

```java
taskMapper.insertTargets(targets);
occupancyService.lockTaskAccountsOrThrow(task, now);
```

The existing outer `@Transactional` ensures a conflict rolls back task, targets, and newly acquired free-account rows.

Delete the now-unused `assertAccountGroupAvailable` service method and `selectFirstOwnerByAccountGroupId` mapper/XML statement so no group-level rejection path remains.

- [ ] **Step 4: Run GREEN**

```bash
mvn -Dtest=MarketingAccountOccupancyMapperSqlShapeTest,MarketingAccountOccupancyServiceTest,MarketingTaskServiceImplLifecycleTest test
xmllint --noout src/main/resources/mapper/marketing/MarketingAccountOccupancyMapper.xml
```

Expected: strict lock tests pass and mapper XML is valid.

- [ ] **Step 5: Commit**

```bash
git add armada-api/src/main/java/com/armada/marketing/mapper/MarketingAccountOccupancyMapper.java armada-api/src/main/resources/mapper/marketing/MarketingAccountOccupancyMapper.xml armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountOccupancyService.java armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java armada-api/src/test/java/com/armada/marketing/mapper/MarketingAccountOccupancyMapperSqlShapeTest.java armada-api/src/test/java/com/armada/marketing/service/MarketingAccountOccupancyServiceTest.java armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplLifecycleTest.java
git commit -m "feat(marketing): lock accounts when task is created"
```

## Task 3: Return per-account lock metadata in the account tree

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingAccountOccupancyMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingAccountOccupancyMapper.xml`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingAccountTreeAccountRow.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTreeAccountVO.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeService.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeServiceTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskAccountTreeDbTest.java`

- [ ] **Step 1: Write failing row-level tree tests**

Replace the group rejection expectation with mixed rows:

```java
@Test
void accountTree_occupiedAccount_isDisabledWithoutHidingFreeAccount() {
    MarketingAccountTreeAccountRow occupied = onlineAccount(31L);
    occupied.setOccupiedTaskId(99L);
    occupied.setOccupiedTaskName("夏季营销");
    MarketingAccountTreeAccountRow free = onlineAccount(32L);
    when(taskMapper.selectAccountTreeAccounts(12L)).thenReturn(List.of(occupied, free));

    MarketingAccountTreeVO tree = service.accountTree(12L);

    assertThat(tree.accounts()).hasSize(2);
    assertThat(tree.accounts().get(0).locked()).isTrue();
    assertThat(tree.accounts().get(0).selectable()).isFalse();
    assertThat(tree.accounts().get(0).disabledReason())
            .isEqualTo("该账号正在被任务【夏季营销】占用，请先关闭原任务后再使用。");
    assertThat(tree.accounts().get(1).locked()).isFalse();
    assertThat(tree.accounts().get(1).selectable()).isTrue();
}
```

Add a lazy-load test where `selectOwnerByAccountId(31L)` returns an owner and the result is locked with no groups.

- [ ] **Step 2: Run RED**

```bash
cd armada-api
mvn -Dtest=MarketingAccountTreeRealtimeServiceTest test
```

Expected: FAIL because the VO has no lock fields and accountTree still invokes the group-level gate.

- [ ] **Step 3: Implement the structured owner projection**

Add fields and accessors to `MarketingAccountTreeAccountRow`:

```java
private Long occupiedTaskId;
private String occupiedTaskName;
```

Extend the VO record between `disabledReason` and `groupsError`:

```java
Boolean locked,
Long occupiedTaskId,
String occupiedTaskName,
```

In both account-tree SQL queries, add active occupancy joins and projections:

```sql
LEFT JOIN marketing_account_occupancy occupancy ON occupancy.account_id = a.id
LEFT JOIN marketing_task owner_task ON owner_task.id = occupancy.marketing_task_id
                                      AND owner_task.deleted_at IS NULL
                                      AND owner_task.status IN (1, 2, 5)
```

```sql
owner_task.id AS occupiedTaskId,
owner_task.task_name AS occupiedTaskName
```

Remove `assertAccountGroupAvailable` from accountTree. Add mapper method:

```java
MarketingAccountOccupancyOwnerRow selectOwnerByAccountId(@Param("accountId") Long accountId);
```

Use it in lazy loading. In the VO converter, owner data overrides machine availability:

```java
boolean locked = account.getOccupiedTaskId() != null;
boolean selectable = !locked && selectableByMachineState(account, status);
String disabledReason = locked
        ? "该账号正在被任务【" + account.getOccupiedTaskName()
                + "】占用，请先关闭原任务后再使用。"
        : machineDisabledReason(account, status);
```

- [ ] **Step 4: Run GREEN**

```bash
mvn -Dtest=MarketingAccountTreeRealtimeServiceTest,MarketingAccountOccupancyMapperSqlShapeTest test
xmllint --noout src/main/resources/mapper/marketing/MarketingTaskMapper.xml
```

Expected: mixed occupied/free tree tests pass and XML is valid.

- [ ] **Step 5: Commit**

```bash
git add armada-api/src/main/java/com/armada/marketing/mapper/MarketingAccountOccupancyMapper.java armada-api/src/main/resources/mapper/marketing/MarketingAccountOccupancyMapper.xml armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml armada-api/src/main/java/com/armada/marketing/model/vo/MarketingAccountTreeAccountRow.java armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTreeAccountVO.java armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeService.java armada-api/src/test/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeServiceTest.java armada-api/src/test/java/com/armada/marketing/service/MarketingTaskAccountTreeDbTest.java
git commit -m "feat(marketing): expose account lock owners in task tree"
```

## Task 4: Implement start, pause, resume, close, and terminal-only delete

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/service/MarketingTaskService.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/controller/MarketingTaskController.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
- Delete: `armada-api/src/main/java/com/armada/marketing/model/dto/RestartMarketingTaskDTO.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplLifecycleTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/controller/MarketingTaskControllerDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`

- [ ] **Step 1: Write failing lifecycle transition tests**

Add focused service tests:

```java
@Test
void startTask_beforeWindow_staysPendingWithoutTouchingLock() {
    MarketingTask task = task(PENDING.code(), now + 60_000L, now + 600_000L);
    when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task);
    when(taskMapper.activateTask(TASK_ID, PENDING.code(), PENDING.code(), anyLong())).thenReturn(1);

    service.startTask(TASK_ID);

    verify(occupancyService, never()).releaseTaskAccounts(anyLong());
    verify(occupancyService, never()).acquireAndLoadTaskAccounts(any(), anyLong());
}

@Test
void pauseTask_runningTask_keepsAccountLock() {
    when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task(SENDING.code(), past, future));
    when(taskMapper.pauseTask(eq(TASK_ID), anyLong())).thenReturn(1);

    service.pauseTask(TASK_ID);

    verify(occupancyService, never()).releaseTaskAccounts(anyLong());
}

@Test
void resumeTask_pausedTask_entersSendingWithoutReacquiring() {
    when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task(PAUSED.code(), past, future));
    when(taskMapper.resumeTask(eq(TASK_ID), anyLong())).thenReturn(1);

    service.resumeTask(TASK_ID);

    verify(occupancyService, never()).acquireAndLoadTaskAccounts(any(), anyLong());
}

@ParameterizedTest
@ValueSource(ints = {1, 2, 5})
void closeTask_nonTerminalTask_closesAndReleases(int status) {
    when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task(status, past, future));
    when(taskMapper.closeTask(eq(TASK_ID), eq(status), anyLong())).thenReturn(1);

    service.closeTask(TASK_ID);

    verify(occupancyService).releaseTaskAccounts(TASK_ID);
}
```

Add terminal rejection tests for status 7 and 8. Update controller tests to require `/pause`, `/resume`, `/close` and to prove `/restart` is absent.

Add these boundary tests:

```java
@Test
void startTask_schedulerWonRace_returnsCurrentSendingTask() {
    when(taskMapper.selectTaskById(TASK_ID))
            .thenReturn(task(PENDING.code(), past, future), task(SENDING.code(), past, future));
    when(taskMapper.activateTask(eq(TASK_ID), eq(PENDING.code()), eq(SENDING.code()), anyLong()))
            .thenReturn(0);

    assertThat(service.startTask(TASK_ID).status()).isEqualTo(SENDING.code());
}

@Test
void resumeTask_afterEnd_completesAndReleases() {
    when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task(PAUSED.code(), past, past));
    when(taskMapper.endExpiredTask(eq(TASK_ID), anyLong())).thenReturn(1);

    service.resumeTask(TASK_ID);

    verify(occupancyService).releaseTaskAccounts(TASK_ID);
    verify(taskMapper, never()).resumeTask(anyLong(), anyLong());
}

@Test
void batchDelete_nonTerminalTask_rejectsWholeBatch() {
    when(taskMapper.countNonTerminalByIds(List.of(TASK_ID))).thenReturn(1);

    assertThatThrownBy(() -> service.batchDelete(List.of(TASK_ID)))
            .hasMessage("未完成任务不可删除，请先手动关闭任务");
}
```

- [ ] **Step 2: Run RED**

```bash
cd armada-api
mvn -Dtest=MarketingTaskServiceImplLifecycleTest,MarketingTaskMapperSqlShapeTest test
```

Expected: FAIL because pause/resume/close contracts do not exist and restart/stop remain.

- [ ] **Step 3: Implement lifecycle API and guarded SQL**

Service interface:

```java
MarketingTaskVO startTask(Long id);
MarketingTaskVO pauseTask(Long id);
MarketingTaskVO resumeTask(Long id);
MarketingTaskVO closeTask(Long id);
```

Controller endpoints:

```java
@PostMapping("/{id}/pause")
public ApiResponse<MarketingTaskVO> pause(@PathVariable Long id) {
    return ApiResponse.ok(service.pauseTask(id));
}

@PostMapping("/{id}/resume")
public ApiResponse<MarketingTaskVO> resume(@PathVariable Long id) {
    return ApiResponse.ok(service.resumeTask(id));
}

@PostMapping("/{id}/close")
public ApiResponse<MarketingTaskVO> close(@PathVariable Long id) {
    return ApiResponse.ok(service.closeTask(id));
}
```

Mapper contracts and SQL:

```java
int pauseTask(Long id, long now);
int resumeTask(Long id, long now);
int closeTask(Long id, int expectedStatus, long now);
int countNonTerminalByIds(List<Long> ids);
int batchSoftDeleteTerminal(List<Long> ids, long deletedAt);
```

```sql
-- pause
SET status = 5, next_round_at = NULL, updated_at = #{now}
WHERE id = #{id} AND deleted_at IS NULL AND status = 2

-- resume
SET status = 2, next_round_at = #{now}, updated_at = #{now}
WHERE id = #{id} AND deleted_at IS NULL AND status = 5
  AND (task_end_at IS NULL OR task_end_at > #{now})

-- close
SET status = 8, next_round_at = NULL,
    finished_at = COALESCE(finished_at, #{now}), updated_at = #{now}
WHERE id = #{id} AND deleted_at IS NULL
  AND status = #{expectedStatus} AND status IN (1, 2, 5)
```

`startTask` only accepts PENDING. Before the start time it uses the existing guarded PENDING-to-PENDING update and returns the task without acquiring or releasing. In the active window it transitions to SENDING. If end time has passed, call the completed transition and release before returning/rejecting.

If the guarded start update returns zero, re-read the task. Return it when it is already SENDING because the scheduler won the race; reject every other state change. This makes the manual and automatic start paths idempotent without permitting a terminal transition.

`resumeTask` completes and releases an expired PAUSED task instead of restoring it. `batchDelete` first rejects any ID with status 1/2/5, then soft-deletes only status 7/8 and defensively invokes `releaseTaskAccounts` for each normalized deleted task ID inside the service transaction.

Delete restart DTO, method, controller endpoint, mapper statement, service code, and tests.

- [ ] **Step 4: Run GREEN**

```bash
mvn -Dtest=MarketingTaskServiceImplLifecycleTest,MarketingTaskMapperSqlShapeTest test
xmllint --noout src/main/resources/mapper/marketing/MarketingTaskMapper.xml
```

Expected: all lifecycle unit/SQL tests pass and XML is valid.

- [ ] **Step 5: Commit**

```bash
git add armada-api/src/main/java/com/armada/marketing/service/MarketingTaskService.java armada-api/src/main/java/com/armada/marketing/controller/MarketingTaskController.java armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplLifecycleTest.java armada-api/src/test/java/com/armada/marketing/controller/MarketingTaskControllerDbTest.java armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java
git rm armada-api/src/main/java/com/armada/marketing/model/dto/RestartMarketingTaskDTO.java
git commit -m "feat(marketing): add pause resume and terminal close"
```

## Task 5: Align scheduler, round worker, template deletion, and release invariants

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingTaskLifecycleWorker.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundScheduler.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTemplateServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: corresponding scheduler/template tests.

- [ ] **Step 1: Write failing release-invariant tests**

Update scheduler tests to require:

```java
// Scheduled PENDING -> SENDING must not reacquire: creation already owns accounts.
worker.startDueWaitingTask(1L, 42L);
verify(occupancyService, never()).acquireAndLoadTaskAccounts(any(), anyLong());

// Dirty early SENDING -> PENDING must retain the lock.
roundWorker.runRound(1L, 42L);
verify(occupancyService, never()).releaseTaskAccounts(42L);

// Expired PENDING/SENDING/PAUSED -> COMPLETED releases.
worker.endExpiredTask(1L, 42L);
verify(occupancyService).releaseTaskAccounts(42L);
```

Update template deletion tests so associated status 1/2/5 tasks are completed and released, not paused.

- [ ] **Step 2: Run RED**

```bash
cd armada-api
mvn -Dtest=MarketingTaskLifecycleWorkerTest,MarketingRoundWorkerTest,MarketingTemplateServiceImplTest test
```

Expected: FAIL because scheduled start reacquires, early defer releases, and template deletion still writes status 5.

- [ ] **Step 3: Implement terminal-only release**

- Remove acquisition from scheduled start.
- Remove release from early-SENDING defer.
- Keep release after an `endExpiredTask` update succeeds.
- Ensure `selectExpiredRunnableTasks` and `endExpiredTask` use statuses `(1, 2, 5)` and write status 7.
- Replace `stopRunnableTasksByTemplateIds` with `completeRunnableTasksByTemplateIds`, setting status 7, clearing `next_round_at`, and writing `finished_at`.
- Keep `releaseAccountsByTemplateIds` immediately after the completed update in the same template-delete transaction.
- Keep RoundWorker status guards at SENDING so pause/close prevents new rounds.

- [ ] **Step 4: Run GREEN and focused backend regression**

```bash
mvn -Dtest=MarketingAccountOccupancyMapperSqlShapeTest,MarketingAccountOccupancyServiceTest,MarketingTaskServiceImplLifecycleTest,MarketingAccountTreeRealtimeServiceTest,MarketingTaskLifecycleWorkerTest,MarketingRoundWorkerTest,MarketingRoundSchedulerTest,MarketingTaskMapperSqlShapeTest,MarketingTemplateServiceImplTest test
```

Expected: focused backend suite passes with zero failures/errors.

- [ ] **Step 5: Commit**

```bash
git add armada-api/src/main/java/com/armada/marketing/scheduler armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTemplateServiceImpl.java armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml armada-api/src/test/java/com/armada/marketing/scheduler armada-api/src/test/java/com/armada/marketing/service/MarketingTemplateServiceImplTest.java
git commit -m "fix(marketing): release account locks only at terminal states"
```

## Task 6: Add frontend lock metadata and occupied-account feedback

**Files:**
- Modify: `wheel-saas-pure-web/src/api/marketing-task.ts`
- Modify: `wheel-saas-pure-web/src/views/task/group-marketing/components/GroupMarketingCreateDrawer.vue`
- Modify: `wheel-saas-pure-web/src/views/task/group-marketing/composables/marketing-selection.ts`
- Modify: matching Node tests.

- [ ] **Step 1: Write failing frontend lock tests**

Extend `GroupMarketingCreateDrawer.test.ts`:

```ts
it("shows the owning task for a locked account", () => {
  assert.match(source, /occupiedTaskName/);
  assert.match(
    source,
    /该账号正在被任务【.*】占用，请先关闭原任务后再使用。/
  );
  assert.match(source, /el-tooltip/);
});
```

Extend `marketing-selection.test.ts` with a locked ONLINE account where `selectable` is accidentally true and assert it is excluded from defaults/selections.

- [ ] **Step 2: Run RED**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts src/views/task/group-marketing/composables/marketing-selection.test.ts
```

Expected: FAIL because lock fields and Tooltip rendering are absent.

- [ ] **Step 3: Implement lock-aware frontend rendering**

Extend `MarketingTreeAccount`:

```ts
locked?: boolean | null;
occupiedTaskId?: number | null;
occupiedTaskName?: string | null;
```

Make both selection helpers require `account.locked !== true`. Extend TreeNode with `disabledReason`, populate it from the backend, and render the label through Element Plus Tooltip:

```vue
<template #default="{ data }">
  <el-tooltip
    v-if="data.disabledReason"
    :content="data.disabledReason"
    placement="top-start"
  >
    <span class="disabled-account-label">{{ data.label }}</span>
  </el-tooltip>
  <span v-else>{{ data.label }}</span>
</template>
```

When structured owner data exists but `disabledReason` is missing, build the exact fallback message from `occupiedTaskName`.

- [ ] **Step 4: Run GREEN**

Run the same Node command and expect all focused tests to pass.

- [ ] **Step 5: Commit in the frontend repository**

```bash
git add src/api/marketing-task.ts src/views/task/group-marketing/components/GroupMarketingCreateDrawer.vue src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts src/views/task/group-marketing/composables/marketing-selection.ts src/views/task/group-marketing/composables/marketing-selection.test.ts
git commit -m "feat(marketing): disable accounts locked by other tasks"
```

## Task 7: Replace frontend restart/stop UI with start/pause/resume/close

**Files:**
- Modify: `wheel-saas-pure-web/src/api/marketing-task.ts`
- Modify: `src/views/task/group-marketing/constants.ts`
- Modify: `components/GroupMarketingTaskTable.vue`
- Modify: `composables/useGroupMarketingTaskPage.ts`
- Modify: `index.vue`
- Delete: restart component/composable and tests.
- Modify: lifecycle/page Node tests.

- [ ] **Step 1: Write failing five-state UI tests**

Rewrite `GroupMarketingTaskLifecycleUi.test.ts` to require:

```ts
assert.match(table, /taskLifecycleAction/);
assert.match(table, />\s*启动\s*<\/el-button>/);
assert.match(table, />\s*暂停\s*<\/el-button>/);
assert.match(table, />\s*恢复\s*<\/el-button>/);
assert.match(table, />\s*手动关闭\s*<\/el-button>/);
assert.doesNotMatch(table, /重新启动/);
```

Add page tests proving:

```ts
await pageState.pauseTask(task({ status: 2 }));
assert.equal(calls[0].url, "/api/marketing-tasks/42/pause");

await pageState.resumeTask(task({ status: 5 }));
assert.equal(calls[0].url, "/api/marketing-tasks/42/resume");
```

For close, queue a confirmed Element Plus dialog result, call `closeTask`, and assert `/close`, success text, and returned row status 8. Add a canceled-confirmation test that makes no API call.

- [ ] **Step 2: Run RED**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/task/group-marketing/components/GroupMarketingTaskLifecycleUi.test.ts src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
```

Expected: FAIL because pause/resume/close APIs and action mapping do not exist and restart remains.

- [ ] **Step 3: Implement five-state API and UI**

Use this task-status type:

```ts
export type MarketingTaskStatus = 1 | 2 | 5 | 7 | 8;
```

Export API methods:

```ts
export const startMarketingTask = (id: number) => lifecycle(id, "start");
export const pauseMarketingTask = (id: number) => lifecycle(id, "pause");
export const resumeMarketingTask = (id: number) => lifecycle(id, "resume");
export const closeMarketingTask = (id: number) => lifecycle(id, "close");
```

Remove restart request/type/API. In constants, centralize the action:

```ts
export type MarketingTaskLifecycleAction = "start" | "pause" | "resume";

export function taskLifecycleAction(status: MarketingTaskStatus) {
  if (status === 1) return { action: "start" as const, label: "启动", type: "success" as const };
  if (status === 2) return { action: "pause" as const, label: "暂停", type: "warning" as const };
  if (status === 5) return { action: "resume" as const, label: "恢复", type: "success" as const };
  return null;
}
```

The table renders one lifecycle button from this function and a separate close button only for `[1, 2, 5]`.

In `useGroupMarketingTaskPage`, add pause/resume methods and close confirmation:

```ts
await ElMessageBox.confirm(
  `确认手动关闭任务【${row.taskName}】？关闭后不可恢复。`,
  "手动关闭营销任务",
  { type: "warning", confirmButtonText: "确认关闭", cancelButtonText: "取消" }
);
```

After early start returns status 1, retain the existing waiting message. Delete restart page wiring and files. Change batch delete precheck so any status 1/2/5 row is rejected with “请先手动关闭任务”。

- [ ] **Step 4: Run GREEN and frontend focused regression**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts src/views/task/group-marketing/components/GroupMarketingMaterialDrawer.test.ts src/views/task/group-marketing/components/GroupMarketingTaskLifecycleUi.test.ts src/views/task/group-marketing/components/detail-rollup.test.ts src/views/task/group-marketing/composables/marketing-selection.test.ts src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
```

Expected: all group-marketing Node tests pass and no restart test remains.

- [ ] **Step 5: Commit in the frontend repository**

```bash
git add src/api/marketing-task.ts src/views/task/group-marketing/constants.ts src/views/task/group-marketing/components/GroupMarketingTaskTable.vue src/views/task/group-marketing/components/GroupMarketingTaskLifecycleUi.test.ts src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts src/views/task/group-marketing/index.vue
git rm src/views/task/group-marketing/components/GroupMarketingRestartDialog.vue src/views/task/group-marketing/composables/useMarketingTaskRestart.ts src/views/task/group-marketing/composables/useMarketingTaskRestart.test.ts
git commit -m "feat(marketing): implement five-state task controls"
```

## Task 8: Documentation, full verification, and review

**Files:**
- Modify: `armada/.harness/changes/marketing-task/summary.md`
- Modify: `armada/.harness/changes/marketing-task/db-migrations.sql`
- Modify: `armada/.harness/changes/marketing-task/rollback.sql`
- Modify: `wheel-saas-pure-web/.harness/changes/marketing-task-frontend/summary.md`

- [ ] **Step 1: Update harness records**

Document the exact five states, create-time lock, terminal-only release, no restart, row-level occupied message, and Outbox boundary. Append V050 to the migration artifact and add rollback notes that reverse code deployment before changing status comments; do not delete live occupancy rows during rollback without environment confirmation.

- [ ] **Step 2: Run full backend unit verification**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=MarketingAccountOccupancyMapperSqlShapeTest,MarketingAccountOccupancyServiceTest,MarketingTaskServiceImplLifecycleTest,MarketingAccountTreeRealtimeServiceTest,MarketingTaskLifecycleWorkerTest,MarketingRoundWorkerTest,MarketingRoundSchedulerTest,MarketingTaskMapperSqlShapeTest,MarketingTemplateServiceImplTest test
```

Expected: zero failures and zero errors.

- [ ] **Step 3: Run backend XML/static checks**

```bash
xmllint --noout src/main/resources/mapper/marketing/MarketingAccountOccupancyMapper.xml
xmllint --noout src/main/resources/mapper/marketing/MarketingTaskMapper.xml
git diff --check
```

Expected: both XML files valid and no whitespace errors.

- [ ] **Step 4: Run frontend verification**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts src/views/task/group-marketing/components/GroupMarketingMaterialDrawer.test.ts src/views/task/group-marketing/components/GroupMarketingTaskLifecycleUi.test.ts src/views/task/group-marketing/components/detail-rollup.test.ts src/views/task/group-marketing/composables/marketing-selection.test.ts src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
./node_modules/.bin/tsc --noEmit
./node_modules/.bin/vue-tsc --noEmit --skipLibCheck
./node_modules/.bin/eslint --max-warnings 0 src/api/marketing-task.ts src/views/task/group-marketing
./node_modules/.bin/stylelint "src/views/task/group-marketing/**/*.vue" --cache=false
npm run build
git diff --check
```

Expected: tests, type checks, lint, stylelint, build, and diff check all exit 0.

- [ ] **Step 5: Run true-DB tests only after environment confirmation**

After confirming the target is a disposable local/test schema, run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=MarketingTaskDataModelMigrationDbTest,MarketingAccountOccupancyMapperDbTest,MarketingTaskMutationDbTest,MarketingTaskAccountTreeDbTest,MarketingTaskControllerDbTest test
```

If the known V037/V041 checksum mismatch remains, report it exactly and do not run `flyway repair`.

- [ ] **Step 6: Request code review**

Review both repositories against the written design, with special attention to:

```text
creation rollback on partial lock conflict
no release from PENDING/SENDING->PAUSED/PENDING transitions
no lifecycle transition out of COMPLETED/CLOSED
no restart API/UI residue
row-level exact occupied message
close/outbox race limited to already queued commands
```

- [ ] **Step 7: Commit documentation**

Backend:

```bash
git add .harness/changes/marketing-task
git commit -m "docs(marketing): record five-state account locking"
```

Frontend:

```bash
git add .harness/changes/marketing-task-frontend/summary.md
git commit -m "docs(marketing): record five-state task UI"
```

## Plan self-review

- Spec coverage: creation lock, precise conflict, pending auto-start, early start no-op, pause retention, resume, completion/close release, no restart, row-level account feedback, deletion/template linkage, and Outbox boundary are each assigned to Tasks 1–7.
- Scope: no files under group-creation marketing, speed-group, or `armada-protocol` are modified.
- Type consistency: task states use 1/2/5/7/8 throughout; `pauseTask`, `resumeTask`, and `closeTask` names match backend and frontend APIs.
- TDD: every production behavior task begins with a focused failing test and an explicit RED command before implementation.
- Environment safety: true-DB/Flyway execution is deferred until a disposable local/test target is explicitly confirmed; no repair or remote action is included.
