# Pull Task Unified List and Global Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the backend contract and Vue page for the nine-column “拉群任务” unified list, plus tenant-scoped group-marketing global settings and create-page setting visibility, without merging the separate legacy “拉群营销” business.

**Architecture:** Keep `pull_task` as the common task master, read group-marketing aggregates from a one-to-one `pull_task_group_marketing_summary` table, and expose typed services under the `task` domain. Store the three global values in one tenant-scoped setting row. The frontend consumes the typed contract, splits the wide table and dialogs into focused components, preserves standard pull-task creation, and keeps unimplemented group-marketing submission/execution explicit.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis XML, Flyway, JUnit 5, H2 MySQL mode, Vue 3 `<script setup>`, TypeScript, Element Plus, pure-admin, Node test runner, pnpm/Vite

---

## Execution prerequisite

The current Armada checkout contains unrelated uncommitted group/protocol work. Before Task 1, ask the user for worktree permission and create isolated `codex/pull-task-unified-list-settings` worktrees under each repository's ignored `.worktrees/` directory. Run backend commands from the Armada worktree and frontend commands from the frontend worktree. Do not copy or clean changes from either primary checkout. `V087` is the latest migration while writing this plan; immediately before Task 1, run `find armada-api/src/main/resources/db/migration -maxdepth 1 -name 'V*.sql' | sort -V | tail` and rename the planned migration and its contract-test path string together if `V088` has since been claimed.

## File map

### Armada backend

- Create `armada-api/src/main/resources/db/migration/V088__pull_task_unified_list_and_global_settings.sql` for common metadata, aggregate/setting tables, indexes, and permission.
- Create `com.armada.task` entities, DTOs, enums, VOs, mappers, services, and controllers for list/settings/deletion.
- Create mapper XML under `armada-api/src/main/resources/mapper/task/`.
- Modify `com.armada.pulltask.PullTaskController` only to remove list and batch-delete mappings superseded by typed services.
- Add focused source-contract, H2 mapper, service, and controller tests under `armada-api/src/test/java/com/armada/task/`.

### Vue frontend

- Modify `src/api/pull-task.ts` and add `src/api/pull-task.test.ts`.
- Extract the nine-column table, global-setting dialog, task-type dialog, and standard-create drawer into focused components under `src/views/task/pull-task/components/`.
- Add focused composables for settings and standard creation.
- Update list/create pure domain modules, page state, page assembly, and tests.
- Create `.harness/changes/pull-task-unified-list-global-settings/summary.md` before frontend implementation.

### Records

- Update the Armada change record and design status only after local verification.
- Record frontend evidence in its matching `.harness/changes/` summary.

## Task 1: Add the database and permission contract

**Files:**

- Create: `armada-api/src/test/java/com/armada/task/mapper/PullTaskUnifiedListMigrationTest.java`
- Create: `armada-api/src/main/resources/db/migration/V088__pull_task_unified_list_and_global_settings.sql`

- [ ] **Step 1: Write the failing migration contract test**

Read the migration file and assert the common columns, both new tables, composite summary primary key, tenant setting primary key, and dedicated permission:

```java
class PullTaskUnifiedListMigrationTest {
    private static final String SQL = migrationSql();

    @Test
    void addsCommonMetadataWithoutInventingExecutionTime() {
        assertThat(SQL).contains(
                "task_type VARCHAR(32) NOT NULL DEFAULT 'STANDARD'",
                "group_source VARCHAR(32) DEFAULT NULL",
                "primary_stage VARCHAR(64) DEFAULT NULL",
                "blocking_reason VARCHAR(255) DEFAULT NULL",
                "last_business_executed_at BIGINT DEFAULT NULL");
        assertThat(SQL).doesNotContain("last_business_executed_at = created_at");
    }

    @Test
    void createsSummarySettingAndPermission() {
        assertThat(SQL).contains(
                "CREATE TABLE pull_task_group_marketing_summary",
                "CREATE TABLE pull_task_group_marketing_setting",
                "PRIMARY KEY (tenant_id, task_id)",
                "PRIMARY KEY (tenant_id)",
                "tenant:pull_task:settings");
    }

    private static String migrationSql() {
        try {
            return Files.readString(Path.of(
                    "src/main/resources/db/migration/V088__pull_task_unified_list_and_global_settings.sql"));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run `cd armada-api && mvn -Dtest=PullTaskUnifiedListMigrationTest test`.

Expected: FAIL because the migration file does not exist.

- [ ] **Step 3: Add the migration**

Add these common fields and indexes:

```sql
ALTER TABLE pull_task
    ADD COLUMN task_type VARCHAR(32) NOT NULL DEFAULT 'STANDARD' AFTER id,
    ADD COLUMN group_source VARCHAR(32) DEFAULT NULL AFTER task_type,
    ADD COLUMN primary_stage VARCHAR(64) DEFAULT NULL AFTER status,
    ADD COLUMN blocking_reason VARCHAR(255) DEFAULT NULL AFTER primary_stage,
    ADD COLUMN last_business_executed_at BIGINT DEFAULT NULL AFTER updated_at,
    ADD KEY idx_pull_task_type_status
        (tenant_id, task_type, status, deleted_at, id),
    ADD KEY idx_pull_task_source
        (tenant_id, group_source, deleted_at, id);
```

Create `pull_task_group_marketing_summary` with `(tenant_id, task_id)` as primary key and these non-negative `INT NOT NULL DEFAULT 0` counters:

```text
target_group_count
transfer_success_count
transfer_pending_close_count
transfer_partial_count
transfer_failed_count
transfer_running_count
transfer_waiting_count
planned_target_count
effective_target_count
joined_success_count
already_in_group_count
privacy_restricted_count
invalid_number_count
unregistered_count
pull_result_unknown_count
remaining_target_count
marketing_waiting_count
marketing_running_count
marketing_paused_count
marketing_completed_count
marketing_abnormal_stopped_count
message_success_count
message_failed_count
message_unknown_count
abnormal_group_count
puller_shortage_group_count
banned_account_count
available_puller_count
```

Also add five `TINYINT(1) NOT NULL DEFAULT 0` shortage flags named `target_data_shortage`, `puller_shortage`, `water_army_shortage`, `admin_shortage`, `marketing_admin_shortage`, plus required `created_at` and `updated_at`.

Create `pull_task_group_marketing_setting` with primary key `tenant_id`; `marketing_silence_minutes INT NOT NULL`, `group_lockdown_minutes INT NOT NULL`, `max_marketing_accounts_per_group INT NOT NULL`; nullable `created_by` and `updated_by`; and non-null epoch-millisecond `created_at` and `updated_at`. Do not add business defaults.

Seed a `TaskPullSettings` button permission with `tenant:pull_task:settings` beneath every active tenant's `TaskPull` menu using the existing `INSERT IGNORE ... SELECT tenant ... JOIN sys_menu` migration pattern.

- [ ] **Step 4: Verify GREEN and commit**

Run the focused test and `git diff --check`, then commit only the migration and its test as `feat: add pull task unified list schema`.

## Task 2: Implement the common task page mapper

**Files:**

- Create: `armada-api/src/main/java/com/armada/task/model/entity/PullTask.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskQuery.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskFilter.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskType.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskGroupSource.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskMarketingStatus.java`
- Create: `armada-api/src/main/java/com/armada/task/mapper/PullTaskMapper.java`
- Create: `armada-api/src/main/resources/mapper/task/PullTaskMapper.xml`
- Create: `armada-api/src/test/java/com/armada/task/mapper/PullTaskMapperInMemoryTest.java`

- [ ] **Step 1: Write a failing H2 MySQL-mode mapper test**

Configure the production tenant plugin and real `PullTaskMapper.xml` against an H2 MySQL-mode schema. Seed tenants 7 and 8, then assert SQL-backed filtering and tenant isolation:

```java
PullTaskFilter filter = new PullTaskFilter(
        null, "印度", "EXECUTING", PullTaskType.GROUP_MARKETING,
        PullTaskGroupSource.HISTORICAL, "乙");
assertThat(mapper.countPage(filter)).isEqualTo(1);
assertThat(mapper.selectPage(filter, 0, 10))
        .extracting(PullTask::getId)
        .containsExactly(12L);
```

Also test exact ID, standard type, mixed source, page limit, and ID-desc ordering.

- [ ] **Step 2: Run and verify RED**

Run `mvn -Dtest=PullTaskMapperInMemoryTest test`.

Expected: compilation fails because the entity, filter, and mapper do not exist.

- [ ] **Step 3: Add exact enums and query conversion**

```java
public enum PullTaskType { STANDARD, GROUP_MARKETING }
public enum PullTaskGroupSource { HISTORICAL, SELF_COLLECTED, MIXED }
public enum PullTaskMarketingStatus {
    DRAFT, WAIT_START, VALIDATING, WAITING_RESOURCE, EXECUTING,
    PARTIAL_COMPLETED, PAUSED, STOPPED, COMPLETED, FAILED
}
```

`PullTaskQuery extends PageQuery` and exposes mutable `id`, `keyword`, `status`, `taskType`, `groupSource`, and `operator` fields. `taskType` and `groupSource` use their enums; `status` remains a string because ordinary and marketing tasks have different status sets. Its `toFilter()` trims blank strings to `null`. `PullTaskFilter` is a record with the same six typed fields.

- [ ] **Step 4: Implement the entity and mapper**

`PullTask` maps `id`, enum-valued `taskType`, enum-valued nullable `groupSource`, `taskName`, `groupName`, `mode`, `status`, `primaryStage`, `blockingReason`, `groupCount`, `expectedPullCount`, `operatorName`, `createdAt`, `updatedAt`, `lastBusinessExecutedAt`, `remark`, and `deletedAt`.

Mapper contract:

```java
long countPage(@Param("filter") PullTaskFilter filter);
List<PullTask> selectPage(@Param("filter") PullTaskFilter filter,
                          @Param("offset") int offset,
                          @Param("limit") int limit);
int batchSoftDeleteAllowed(@Param("ids") List<Long> ids,
                           @Param("deletedAt") long deletedAt);
```

The XML shares one filter fragment, always includes `deleted_at IS NULL`, applies only non-null conditions, and performs `ORDER BY id DESC LIMIT/OFFSET`. Batch deletion uses:

```sql
AND (
  (task_type = 'GROUP_MARKETING' AND status = 'DRAFT')
  OR
  (task_type = 'STANDARD' AND status IN ('WAIT_START', 'COMPLETED', 'ENDED'))
)
```

- [ ] **Step 5: Verify GREEN and commit**

Run the focused H2 test and `git diff --check`; commit Task 2 files as `feat: add pull task list mapper`.

## Task 3: Implement the marketing aggregate mapper

**Files:**

- Create: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskGroupMarketingSummary.java`
- Create: `armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupMarketingSummaryMapper.java`
- Create: `armada-api/src/main/resources/mapper/task/PullTaskGroupMarketingSummaryMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskMapperInMemoryTest.java`

- [ ] **Step 1: Add failing aggregate assertions**

Seed a summary for tenant 7/task 12 and a cross-tenant summary for task 13. Assert `selectByTaskIds(List.of(12L, 13L))` returns only task 12 and preserves `message_unknown_count=0` as zero.

- [ ] **Step 2: Run and verify RED**

Run `mvn -Dtest=PullTaskMapperInMemoryTest test`.

Expected: compilation fails because summary types do not exist.

- [ ] **Step 3: Implement the entity and batch mapper**

Give the entity a getter/setter field for every summary column from Task 1. The mapper exposes only:

```java
List<PullTaskGroupMarketingSummary> selectByTaskIds(
        @Param("taskIds") List<Long> taskIds);
```

The service must skip the mapper call when IDs are empty. The XML uses one current-tenant `task_id IN (...)` query; do not add N+1 single-task reads.

- [ ] **Step 4: Verify GREEN and commit**

Run the H2 test and commit the entity, mapper, XML, and test change as `feat: add pull task marketing summary mapper`.

## Task 4: Build the typed list service and controller

**Files:**

- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskListAction.java`
- Create: `armada-api/src/main/java/com/armada/task/model/enums/PullTaskResourceShortageType.java`
- Create: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskListVO.java`
- Create: `armada-api/src/main/java/com/armada/task/service/PullTaskListService.java`
- Create: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskListServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/task/controller/PullTaskListController.java`
- Modify: `armada-api/src/main/java/com/armada/pulltask/PullTaskController.java`
- Create: `armada-api/src/test/java/com/armada/task/service/PullTaskListServiceTest.java`
- Create: `armada-api/src/test/java/com/armada/task/controller/PullTaskListControllerTest.java`

- [ ] **Step 1: Write failing formula tests**

Mock both mappers. Build a summary where terminal transfer counts are `50,10,5,3`, joined success is `7_260`, and effective target is `10_000`. Assert processed groups are `68`, effective success rate is `72.6`, missing summary records stay null, and a true puller shortage produces only a `PULLER` shortage entry.

- [ ] **Step 2: Run and verify RED**

Run `mvn -Dtest=PullTaskListServiceTest,PullTaskListControllerTest test`.

Expected: compilation fails because service, VO, and controller do not exist.

- [ ] **Step 3: Define the typed response**

Create `PullTaskListVO` as a record with these exact top-level fields:

```text
id, taskName, groupName, mode, taskType, groupSource,
status, primaryStage, blockingReason, operatorName,
groupCount, expectedPullCount, remark,
groupProgress, pullResult, marketingProgress, messageStats,
exceptionStats, resourceStats, lastExecutedAt, allowedActions
```

Use these exact nested records:

```text
GroupProgress(processedGroupCount, targetGroupCount,
  transferSuccessCount, transferPendingCloseCount, transferPartialCount,
  transferFailedCount, transferRunningCount, transferWaitingCount)
PullResult(plannedTargetCount, effectiveTargetCount, joinedSuccessCount,
  alreadyInGroupCount, privacyRestrictedCount, invalidNumberCount,
  unregisteredCount, unknownCount, remainingTargetCount, effectiveSuccessRate)
MarketingProgress(waitingCount, runningCount, pausedCount, completedCount,
  abnormalStoppedCount)
MessageStats(successCount, failedCount, unknownCount)
ExceptionStats(abnormalGroupCount, pullerShortageGroupCount, bannedAccountCount)
ResourceShortage(PullTaskResourceShortageType type)
ResourceStats(remainingTargetCount, availablePullerCount,
  List<ResourceShortage> shortages)
```

All counts use `Integer`, the rate uses `BigDecimal`, and missing marketing summary means all six marketing-specific nested groups are `null`. Do not return a `Map`.

- [ ] **Step 4: Implement mapping formulas**

The service must:

1. Return an empty `PageResult` without summary lookup when count is zero.
2. Fetch one page, then one batch of summaries for only `GROUP_MARKETING` IDs.
3. Sum the four terminal transfer counts for processed groups.
4. Calculate `joined * 100 / effectiveTarget` with one decimal via `BigDecimal`; return null for a non-positive denominator.
5. Emit shortage codes `TARGET_DATA`, `PULLER`, `WATER_ARMY`, `ADMIN`, and `MARKETING_ADMIN` only for true flags.
6. Never fall back from `lastBusinessExecutedAt` to created/updated time.
7. Always include `DETAIL`; include `DELETE` for group-marketing `DRAFT` and standard `WAIT_START`/`COMPLETED`/`ENDED`. Do not return `START`, `PAUSE`, `RESUME`, or `STOP` in this slice because the current lifecycle endpoints explicitly reject execution while the executor is unconnected.

- [ ] **Step 5: Add the thin controller and remove the old list mapping**

```java
@RestController
@RequestMapping("/api/pull-tasks")
@PreAuthorize("hasAuthority('tenant:pull_task:view')")
public class PullTaskListController {
    private final PullTaskListService service;

    public PullTaskListController(PullTaskListService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<PullTaskListVO>> list(
            @ModelAttribute PullTaskQuery query) {
        return ApiResponse.ok(service.list(query));
    }
}
```

Delete only the old controller's list method, nested query class, and unused list helpers/imports. Keep legacy create/detail/groups/lifecycle/export behavior unchanged.

- [ ] **Step 6: Verify GREEN and commit**

Run list service/controller and mapper tests, then commit as `feat: expose typed pull task unified list`.

## Task 5: Implement tenant global settings

**Files:**

- Create: `armada-api/src/main/java/com/armada/task/model/entity/PullTaskGroupMarketingSetting.java`
- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskGroupMarketingSettingDTO.java`
- Create: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskGroupMarketingSettingVO.java`
- Create: `armada-api/src/main/java/com/armada/task/mapper/PullTaskGroupMarketingSettingMapper.java`
- Create: `armada-api/src/main/resources/mapper/task/PullTaskGroupMarketingSettingMapper.xml`
- Create: `armada-api/src/main/java/com/armada/task/service/PullTaskGroupMarketingSettingService.java`
- Create: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskGroupMarketingSettingServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/task/controller/PullTaskGroupMarketingSettingController.java`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskMapperInMemoryTest.java`
- Create: `armada-api/src/test/java/com/armada/task/service/PullTaskGroupMarketingSettingServiceTest.java`
- Create: `armada-api/src/test/java/com/armada/task/controller/PullTaskGroupMarketingSettingControllerTest.java`

- [ ] **Step 1: Write failing mapper/service/controller tests**

Cover unconfigured reads, first save, update, validation, and tenant isolation:

```java
assertThat(service.get()).isEqualTo(
        new PullTaskGroupMarketingSettingVO(false, null, null, null));
assertThatThrownBy(() -> service.save(
        new PullTaskGroupMarketingSettingDTO(-1, 10, 1), 99L))
        .isInstanceOf(BusinessException.class);
assertThatThrownBy(() -> service.save(
        new PullTaskGroupMarketingSettingDTO(0, 0, 0), 99L))
        .isInstanceOf(BusinessException.class);
assertThat(service.save(
        new PullTaskGroupMarketingSettingDTO(30, 60, 2), 99L))
        .isEqualTo(new PullTaskGroupMarketingSettingVO(true, 30, 60, 2));
```

The H2 test switches `TenantContext` between tenants 7 and 8 and proves one tenant cannot read or overwrite the other tenant's row.

- [ ] **Step 2: Run and verify RED**

Run `mvn -Dtest=PullTaskMapperInMemoryTest,PullTaskGroupMarketingSettingServiceTest,PullTaskGroupMarketingSettingControllerTest test`.

Expected: compilation fails because setting classes do not exist.

- [ ] **Step 3: Implement mapper upsert and validation**

Mapper contract:

```java
PullTaskGroupMarketingSetting selectCurrent();
int upsert(PullTaskGroupMarketingSetting setting);
```

Use MySQL `INSERT ... ON DUPLICATE KEY UPDATE`; let the production tenant interceptor inject `tenant_id`. On update, preserve `created_by/created_at` and replace only the three values plus `updated_by/updated_at`.

Service validation is exact and null-safe:

```java
if (request == null
        || request.marketingSilenceMinutes() == null
        || request.groupLockdownMinutes() == null
        || request.maxMarketingAccountsPerGroup() == null
        || request.marketingSilenceMinutes() < 0
        || request.groupLockdownMinutes() < 0
        || request.maxMarketingAccountsPerGroup() < 1) {
    throw new BusinessException(ErrorCode.VALIDATION,
            "静默和封控时间不能为负数，单群营销账号上限必须大于0");
}
```

`get()` returns `configured=false` without inserting a default row.

- [ ] **Step 4: Implement GET/PUT endpoints**

Use path `/api/pull-tasks/group-marketing-setting`. GET inherits `tenant:pull_task:view`; PUT requires `tenant:pull_task:settings`, accepts the typed DTO, passes `principal.userId()` to the service, and returns the saved VO.

- [ ] **Step 5: Verify GREEN and commit**

Run the three focused tests and `git diff --check`; commit Task 5 files as `feat: add pull task group marketing settings`.

## Task 6: Move batch deletion to the typed task service

**Files:**

- Create: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskIdsDTO.java`
- Create: `armada-api/src/main/java/com/armada/task/service/PullTaskMutationService.java`
- Create: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskMutationServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/controller/PullTaskListController.java`
- Modify: `armada-api/src/main/java/com/armada/pulltask/PullTaskController.java`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskMapperInMemoryTest.java`
- Create: `armada-api/src/test/java/com/armada/task/service/PullTaskMutationServiceTest.java`

- [ ] **Step 1: Write failing deletion-policy tests**

Seed and assert:

```text
STANDARD / WAIT_START        -> deleted
STANDARD / COMPLETED         -> deleted
STANDARD / EXECUTING         -> retained
GROUP_MARKETING / DRAFT      -> deleted
GROUP_MARKETING / WAIT_START -> retained
```

Also assert empty IDs return zero without a mapper call and duplicate IDs are deduplicated.

- [ ] **Step 2: Run and verify RED**

Run `mvn -Dtest=PullTaskMapperInMemoryTest,PullTaskMutationServiceTest test`.

Expected: mutation service/DTO are missing.

- [ ] **Step 3: Implement service and endpoint**

The service calls `batchSoftDeleteAllowed(distinctIds, System.currentTimeMillis())`. Add the typed endpoint to `PullTaskListController`:

```java
@PostMapping("/batch-delete")
@PreAuthorize("hasAuthority('tenant:pull_task:delete')")
public ApiResponse<Integer> batchDelete(@RequestBody PullTaskIdsDTO request) {
    return ApiResponse.ok(mutationService.batchDelete(request.ids()));
}
```

Remove the old batch-delete mapping and `longList` helper from `com.armada.pulltask.PullTaskController`. Leave every other endpoint intact.

- [ ] **Step 4: Verify backend slice and commit**

Run:

```bash
mvn -Dtest='PullTask*' test
mvn -DskipTests compile
git diff --check
```

Expected: focused H2/unit/source-contract tests pass without shared MySQL access. Commit as `refactor: enforce pull task delete policy`.

## Task 7: Define the frontend API and display domain

**Files:**

- Create: `wheel-saas-pure-web/src/api/pull-task.test.ts`
- Modify: `wheel-saas-pure-web/src/api/pull-task.ts`
- Modify: `wheel-saas-pure-web/src/views/task/pull-task/task-list-display.test.ts`
- Modify: `wheel-saas-pure-web/src/views/task/pull-task/task-list-display.ts`
- Modify: `wheel-saas-pure-web/src/views/task/pull-task/constants.ts`
- Create: `wheel-saas-pure-web/.harness/changes/pull-task-unified-list-global-settings/summary.md`

- [ ] **Step 1: Create the frontend change record**

Record the design link, two-repository scope, nine columns, three global fields, no legacy-menu merge, and no group-marketing submission/executor work. Leave implementation and verification unchecked.

- [ ] **Step 2: Write failing API tests**

Use the Armada test double and call:

```ts
await listPullTasks({
  page: 2,
  pageSize: 20,
  id: 8,
  keyword: "  印度  ",
  status: "EXECUTING",
  taskType: "GROUP_MARKETING",
  groupSource: "HISTORICAL",
  operator: "  运营甲  "
});
await getPullTaskGroupMarketingSetting();
await updatePullTaskGroupMarketingSetting({
  marketingSilenceMinutes: 30,
  groupLockdownMinutes: 60,
  maxMarketingAccountsPerGroup: 2
});
```

Assert exact URLs and that list params contain trimmed `keyword/operator` but no `orderState`, `banState`, or `mode` task-type alias.

- [ ] **Step 3: Write failing pure display tests**

```ts
assert.equal(displayMetric(null), "--");
assert.equal(displayMetric(0), "0");
assert.equal(displayRate(72.6), "72.6%");
assert.equal(taskTypeLabel("STANDARD"), "普通拉群");
assert.equal(taskTypeLabel("GROUP_MARKETING"), "拉群营销");
assert.equal(groupSourceLabel(null), "--");
assert.equal(shouldShowUnknownMessage(0), false);
assert.equal(shouldShowUnknownMessage(9), true);
```

Read `constants.ts` and assert the exact labels are:

```text
任务信息｜任务状态｜群组处理进度｜拉人结果｜营销进度｜消息发送｜异常情况｜剩余资源｜时间/操作
```

- [ ] **Step 4: Run and verify RED**

Run:

```bash
node --test --experimental-strip-types \
  --loader ./src/api/__tests__/node-test-loader.mjs \
  src/api/pull-task.test.ts \
  src/views/task/pull-task/task-list-display.test.ts
```

Expected: setting methods, query keys, helper, and nine-column contract are missing.

- [ ] **Step 5: Implement the TypeScript contracts**

Define:

```ts
export type PullTaskType = "STANDARD" | "GROUP_MARKETING";
export type PullTaskGroupSource = "HISTORICAL" | "SELF_COLLECTED" | "MIXED";
export type PullTaskStandardStatus =
  | "WAIT_START"
  | "EXECUTING"
  | "PAUSED"
  | "INTERRUPTED"
  | "COMPLETED"
  | "ENDED";
export type PullTaskMarketingStatus =
  | "DRAFT"
  | "WAIT_START"
  | "VALIDATING"
  | "WAITING_RESOURCE"
  | "EXECUTING"
  | "PARTIAL_COMPLETED"
  | "PAUSED"
  | "STOPPED"
  | "COMPLETED"
  | "FAILED";
export type PullTaskStatus =
  | PullTaskStandardStatus
  | PullTaskMarketingStatus;
export type PullTaskListAction =
  | "DETAIL"
  | "START"
  | "PAUSE"
  | "STOP"
  | "DELETE";

export interface PullTaskGroupMarketingSetting {
  configured: boolean;
  marketingSilenceMinutes: number | null;
  groupLockdownMinutes: number | null;
  maxMarketingAccountsPerGroup: number | null;
}
```

Add nested metric interfaces matching backend `PullTaskListVO`, update `PullTaskQuery`, and add GET/PUT setting functions. Update the status/source option constants and make status labelling accept both status and task type so the ten PRD marketing labels do not overwrite legacy ordinary-task wording. Preserve existing standard create/detail interfaces and endpoints.

- [ ] **Step 6: Verify GREEN and commit**

Run the API/display tests and commit frontend Task 7 files as `feat: add pull task list and setting contracts`.

## Task 8: Render the nine-column unified table

**Files:**

- Create: `wheel-saas-pure-web/src/views/task/pull-task/components/PullTaskTable.vue`
- Create: `wheel-saas-pure-web/src/views/task/pull-task/components/PullTaskTable.test.ts`
- Modify: `wheel-saas-pure-web/src/views/task/pull-task/index.vue`
- Modify: `wheel-saas-pure-web/src/views/task/pull-task/composables/usePullTaskPage.ts`
- Modify: `wheel-saas-pure-web/src/views/task/pull-task/PullTaskIndex.test.ts`

- [ ] **Step 1: Write failing table/page tests**

Assert `PullTaskTable.vue` has exactly nine labels, uses `el-tooltip` for group/pull/marketing detail, hides unknown-message zero through `shouldShowUnknownMessage`, displays `blockingReason` before `primaryStage`, and contains one `时间/操作` column. Assert `index.vue` no longer embeds ten columns.

Assert page query state contains only ID, keyword, status, task type, group source, operator, page, and page size.

- [ ] **Step 2: Run and verify RED**

Run:

```bash
node --test --experimental-strip-types \
  --loader ./src/api/__tests__/node-test-loader.mjs \
  src/views/task/pull-task/components/PullTaskTable.test.ts \
  src/views/task/pull-task/PullTaskIndex.test.ts \
  src/views/task/pull-task/task-list-display.test.ts
```

Expected: the component is missing and the old page still contains ten columns and invalid filters.

- [ ] **Step 3: Implement `PullTaskTable.vue`**

The component receives `columns`, `loading`, and `rows`; emits `action`, `refresh`, and `selection-change`; and renders:

- name plus `#id｜type｜source`;
- status plus `blockingReason || primaryStage`;
- progress and pull-result tooltips from nested metrics;
- marketing/message counts with unknown zero hidden;
- `异常群组N（缺少拉手M）` and a separate banned-account line;
- remaining target/puller plus shortage tags;
- `lastExecutedAt` and buttons from `allowedActions` in the same fixed-right column.

Do not render created time or demo fallbacks. Use `--` for null and `0` for zero.

- [ ] **Step 4: Simplify the page and query state**

Replace embedded table markup with `<PullTaskTable>`. Search fields become ID, task name, status, task type, group source, and operator. Remove `orderState`, `banState`, and task-mode filtering from types, defaults, reset, query construction, and template.

Map table `DETAIL/START/PAUSE/STOP` actions to the existing handlers so the component remains forward-compatible, although the backend does not return lifecycle actions in this slice. `DELETE` selects that row and invokes typed deletion. Do not create a group-marketing executor success path.

- [ ] **Step 5: Verify GREEN and commit**

Run focused tests and `pnpm typecheck`; commit as `feat: render pull task unified list`.

## Task 9: Add the pull-task global-setting dialog

**Files:**

- Create: `wheel-saas-pure-web/src/views/task/pull-task/components/PullTaskGlobalSettingDialog.vue`
- Create: `wheel-saas-pure-web/src/views/task/pull-task/components/PullTaskGlobalSettingDialog.test.ts`
- Create: `wheel-saas-pure-web/src/views/task/pull-task/composables/usePullTaskGlobalSetting.ts`
- Create: `wheel-saas-pure-web/src/views/task/pull-task/composables/usePullTaskGlobalSetting.test.ts`
- Modify: `wheel-saas-pure-web/src/views/task/pull-task/index.vue`

- [ ] **Step 1: Write failing dialog and composable tests**

Cover these exact behaviors:

- an unconfigured `GET /api/pull-tasks/group-marketing-setting` response opens an empty form rather than inserting defaults;
- a configured response maps `marketingSilenceMinutes`, `groupLockdownMinutes`, and `maxMarketingAccountsPerGroup` into the form;
- cancel closes the dialog without issuing `PUT`;
- invalid values do not issue `PUT`;
- save submits all three fields and closes only after the request succeeds;
- the list-page entry is labelled `全局设置` and guarded by `tenant:pull_task:settings`.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
node --test --experimental-strip-types \
  --loader ./src/api/__tests__/node-test-loader.mjs \
  src/views/task/pull-task/components/PullTaskGlobalSettingDialog.test.ts \
  src/views/task/pull-task/composables/usePullTaskGlobalSetting.test.ts
```

Confirm they fail because the composable and dialog do not exist.

- [ ] **Step 3: Implement `usePullTaskGlobalSetting`**

Expose `visible`, `loading`, `saving`, `form`, `open`, `cancel`, and `save`. Refetch the setting every time `open` is called. Keep all three form fields nullable until the API returns configured values. Validate:

- `marketingSilenceMinutes` is an integer greater than or equal to `0`;
- `groupLockdownMinutes` is an integer greater than or equal to `0`;
- `maxMarketingAccountsPerGroup` is an integer greater than or equal to `1`.

Use the existing `apiErrorMessage` helper and `ElMessage` for request failures. Do not introduce frontend fallback values.

- [ ] **Step 4: Implement and mount the dialog**

Build the form with Element Plus, including explicit units and validation messages. Add the top-level `全局设置` button to `index.vue`, wrap it with `v-auth="'tenant:pull_task:settings'"`, and mount one dialog instance. Opening it must always show the current tenant's persisted values.

- [ ] **Step 5: Verify and commit**

Run the focused tests and `pnpm typecheck`; commit as `feat: add pull task global settings`.

## Task 10: Add task-type selection and restore standard-task creation

**Files:**

- Create: `wheel-saas-pure-web/src/views/task/pull-task/components/PullTaskTypeDialog.vue`
- Create: `wheel-saas-pure-web/src/views/task/pull-task/components/PullTaskTypeDialog.test.ts`
- Create: `wheel-saas-pure-web/src/views/task/pull-task/components/PullTaskCreateDrawer.vue`
- Create: `wheel-saas-pure-web/src/views/task/pull-task/composables/useStandardPullTaskCreate.ts`
- Create: `wheel-saas-pure-web/src/views/task/pull-task/composables/useStandardPullTaskCreate.test.ts`
- Modify: `wheel-saas-pure-web/src/views/task/pull-task/index.vue`
- Modify: `wheel-saas-pure-web/src/views/task/pull-task/PullTaskIndex.test.ts`

- [ ] **Step 1: Write failing task-type routing tests**

Assert that clicking `新建拉群任务` first opens a selector with `普通拉群` and `拉群营销`. Choosing ordinary task opens the standard-task drawer. Choosing group marketing navigates to `/task/pull-task/create`; it must not navigate to or expose the former independent group-marketing menu route.

- [ ] **Step 2: Write failing standard-create tests**

Cover the preserved ordinary-task rules:

- `OLD_LINK` requires at least one existing group link;
- `CREATE_NEW` does not require an old link;
- puller accounts and material are required;
- a valid form posts to `/api/pull-tasks` using the existing standard-task payload and refreshes the unified list after success.

- [ ] **Step 3: Run the focused tests and verify RED**

Run:

```bash
node --test --experimental-strip-types \
  --loader ./src/api/__tests__/node-test-loader.mjs \
  src/views/task/pull-task/components/PullTaskTypeDialog.test.ts \
  src/views/task/pull-task/composables/useStandardPullTaskCreate.test.ts \
  src/views/task/pull-task/PullTaskIndex.test.ts
```

Confirm failures are caused by the missing selector, drawer, and composable.

- [ ] **Step 4: Recover the ordinary-create behavior from repository history**

Use these read-only references as the deterministic source:

```bash
git show e402b177^:src/views/task/pull-task/components/PullTaskCreateDrawer.vue
git show e402b177^:src/views/task/pull-task/composables/usePullTaskPage.ts
```

Recreate the drawer through `apply_patch`, but move its form defaults, validation, option loading, material/file handling, and create request into `useStandardPullTaskCreate.ts`. Preserve the historical request contract exactly. Do not add group-marketing global-setting fields to the ordinary task form.

- [ ] **Step 5: Integrate the selector**

Keep one `PullTaskTypeDialog` and one ordinary-create drawer in `index.vue`. On successful ordinary creation, close both flows and reload the table. Route group-marketing selection to the existing standalone configuration page at `/task/pull-task/create`.

- [ ] **Step 6: Verify and commit**

Run the focused tests and `pnpm typecheck`; commit as `feat: select pull task creation type`.

## Task 11: Load and display global settings on the group-marketing create page

**Files:**

- Modify: `wheel-saas-pure-web/src/views/task/pull-task/create/create-draft.ts`
- Modify: `wheel-saas-pure-web/src/views/task/pull-task/create/create-draft.test.ts`
- Modify: `wheel-saas-pure-web/src/views/task/pull-task/create/create-interactions.ts`
- Modify: `wheel-saas-pure-web/src/views/task/pull-task/create/create-interactions.test.ts`
- Create: `wheel-saas-pure-web/src/views/task/pull-task/create/usePullTaskCreateSetting.ts`
- Create: `wheel-saas-pure-web/src/views/task/pull-task/create/usePullTaskCreateSetting.test.ts`
- Modify: `wheel-saas-pure-web/src/views/task/pull-task/create/components/CreateMarketingSection.vue`
- Modify: `wheel-saas-pure-web/src/views/task/pull-task/create/index.vue`
- Modify: `wheel-saas-pure-web/src/views/task/pull-task/create/PullTaskCreatePage.test.ts`

- [ ] **Step 1: Write failing setting-state and validation tests**

Add these draft fields:

```ts
marketingSilenceMinutes: number | null;
groupLockdownMinutes: number | null;
maxMarketingAccountsPerGroup: number | null;
globalMaxMarketingAccountsPerGroup: number | null;
```

Test that a configured response maps all three global fields and initializes the task-level maximum from `maxMarketingAccountsPerGroup`. Test that an unconfigured response keeps every value `null` and yields `请先在拉群任务列表完成全局设置`. Test that a task-level maximum outside `1..globalMaxMarketingAccountsPerGroup` yields `单群营销账号上限必须在1到全局上限之间`.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
node --test --experimental-strip-types \
  --loader ./src/api/__tests__/node-test-loader.mjs \
  src/views/task/pull-task/create/create-draft.test.ts \
  src/views/task/pull-task/create/create-interactions.test.ts \
  src/views/task/pull-task/create/usePullTaskCreateSetting.test.ts \
  src/views/task/pull-task/create/PullTaskCreatePage.test.ts
```

Confirm failure is caused by the absent setting loader and draft fields.

- [ ] **Step 3: Implement the create-page setting loader**

Call `GET /api/pull-tasks/group-marketing-setting` on page mount. Expose a configured flag and validation hook. Never substitute defaults when the response is unconfigured. Make the setting validation run before any future submission hook.

- [ ] **Step 4: Render the PRD fields without pretending task execution is connected**

In `CreateMarketingSection.vue`, render marketing silence and lockdown duration as disabled global values. Render the per-task marketing-account maximum as an editable integer constrained to `1..globalMaxMarketingAccountsPerGroup`. Show a blocking alert and disable the page's continue/create entry when settings are unconfigured.

Keep the existing explicit boundary: this slice does not implement group-marketing task submission or executor integration. Do not add a fake success path or fabricate an execution result.

- [ ] **Step 5: Verify and commit**

Run the focused create-page tests and `pnpm typecheck`; commit as `feat: apply settings to marketing task draft`.

## Task 12: Run cross-repository verification and finish the records

**Files:**

- Modify: `.harness/changes/2026-07-31-pull-task-unified-list-global-settings.md`
- Modify: `docs/superpowers/specs/2026-07-31-pull-task-unified-list-global-settings-design.md`
- Modify: `wheel-saas-pure-web/.harness/changes/pull-task-unified-list-global-settings/summary.md`

- [ ] **Step 1: Verify the backend slice**

From `armada/armada-api`, run:

```bash
mvn -Dtest='PullTask*' test
mvn -DskipTests compile
```

Record the exact command results. A failing existing unrelated test is not evidence that this feature passes; isolate and document it before deciding whether a separate fix is in scope.

- [ ] **Step 2: Verify the frontend slice**

From `wheel-saas-pure-web`, run `node --test --experimental-strip-types --loader ./src/api/__tests__/node-test-loader.mjs` over:

- `src/api/pull-task.test.ts`
- `src/views/task/pull-task/PullTaskIndex.test.ts`
- `src/views/task/pull-task/components/PullTaskTable.test.ts`
- `src/views/task/pull-task/components/PullTaskGlobalSettingDialog.test.ts`
- `src/views/task/pull-task/components/PullTaskTypeDialog.test.ts`
- `src/views/task/pull-task/composables/usePullTaskGlobalSetting.test.ts`
- `src/views/task/pull-task/composables/useStandardPullTaskCreate.test.ts`
- `src/views/task/pull-task/create/create-draft.test.ts`
- `src/views/task/pull-task/create/create-interactions.test.ts`
- `src/views/task/pull-task/create/usePullTaskCreateSetting.test.ts`
- `src/views/task/pull-task/create/PullTaskCreatePage.test.ts`

Use the exact command:

```bash
node --test --experimental-strip-types \
  --loader ./src/api/__tests__/node-test-loader.mjs \
  src/api/pull-task.test.ts \
  src/views/task/pull-task/PullTaskIndex.test.ts \
  src/views/task/pull-task/components/PullTaskTable.test.ts \
  src/views/task/pull-task/components/PullTaskGlobalSettingDialog.test.ts \
  src/views/task/pull-task/components/PullTaskTypeDialog.test.ts \
  src/views/task/pull-task/composables/usePullTaskGlobalSetting.test.ts \
  src/views/task/pull-task/composables/useStandardPullTaskCreate.test.ts \
  src/views/task/pull-task/create/create-draft.test.ts \
  src/views/task/pull-task/create/create-interactions.test.ts \
  src/views/task/pull-task/create/usePullTaskCreateSetting.test.ts \
  src/views/task/pull-task/create/PullTaskCreatePage.test.ts
```

Then run:

```bash
pnpm typecheck
pnpm build
```

Record the exact command results.

- [ ] **Step 3: Audit scope and repository boundaries**

Review `git diff --stat`, `git diff --check`, and the full diff in each worktree. Confirm that implementation did not modify:

- `wheel-saas-pure-web/src/views/task/group-pull-marketing/`;
- existing backend `marketing` or `grouppull` business tables/services;
- `armada-protocol/`, deployment files, credentials, or unrelated dirty work from the primary checkout.

Confirm that missing summary rows render as `--`, present zero values render as `0`, the list has exactly nine columns, settings have no defaults, and group-marketing creation remains blocked while unconfigured.

- [ ] **Step 4: Update the change records and design status**

Add implemented file paths, schema/API/UI decisions, exact verification evidence, and the explicitly deferred group-marketing submission/executor work. Mark the design status implemented only after every required verification succeeds.

- [ ] **Step 5: Commit documentation and stop before external changes**

Commit the record updates in their respective repositories. Do not deploy, run a shared-database migration, SSH to an environment, or modify remote data without a separate environment confirmation from the user.
