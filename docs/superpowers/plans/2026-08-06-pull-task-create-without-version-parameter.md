# Pull Task Create Without Version Parameter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `version` from the normal-link pull-task draft/create HTTP contract and use only the server-side `DRAFT` status guard for the one-time create transition.

**Architecture:** The frontend no longer receives, validates, or submits a task version. The backend DTO/VO also remove the field, while `PullTaskMapper.submitDraft` keeps one atomic `DRAFT -> WAIT_START` update that increments the database version but does not compare a caller-provided version. All other task lifecycle optimistic-lock methods remain unchanged.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis, JUnit 5, H2 MySQL mode, Vue 3, TypeScript, Node test runner.

---

## File Structure

Backend production:

- Modify: `armada-api/src/main/java/com/armada/task/model/dto/PullTaskStandardCreateDTO.java`
- Modify: `armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardDraftVO.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardDraftServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardCreateTransactionService.java`
- Modify: `armada-api/src/main/java/com/armada/task/mapper/PullTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/task/PullTaskMapper.xml`

Backend tests:

- Modify: `armada-api/src/test/java/com/armada/task/model/dto/PullTaskStandardCreateDTOTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/controller/PullTaskStandardControllerTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardDraftServiceReadEditTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/service/PullTaskStandardCreateServiceTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/mapper/PullTaskDraftMapperInMemoryTest.java`

Frontend:

- Modify: `../wheel-saas-pure-web/src/api/pull-task.ts`
- Modify: `../wheel-saas-pure-web/src/api/pull-task.test.ts`
- Modify: `../wheel-saas-pure-web/src/views/task/pull-task/composables/useStandardPullTaskCreate.ts`
- Modify: `../wheel-saas-pure-web/src/views/task/pull-task/composables/useStandardPullTaskCreate.test.ts`

### Task 1: Remove the backend HTTP version contract and use a status-only create guard

**Files:**

- Modify all backend production and test files listed above.

- [ ] **Step 1: Write failing backend contract tests**

In `PullTaskStandardCreateDTOTest`, make the approved JSON contract omit `version` and assert:

```java
assertThat(json).doesNotContain("\"version\"");
```

In `PullTaskStandardControllerTest`, add:

```java
@Test
void draftJsonDoesNotExposeTaskVersion() throws Exception {
    String json = new ObjectMapper().writeValueAsString(EMPTY_VIEW);

    assertThat(json).doesNotContain("\"version\"");
}
```

In `PullTaskDraftMapperInMemoryTest`, first turn the old version-mismatch case into a behavioral RED test. Keep the current three-argument call only for this RED run, pass a deliberately mismatched version, and expect the draft-status transition to succeed:

```java
@Test
void submitDraftUsesDraftStatusInsteadOfCallerVersion() {
    PullTask draft = draftRow();
    mapper.insertDraft(draft);

    assertThat(mapper.submitDraft(submitRow(draft.getId()), 99, 900L)).isEqualTo(1);

    PullTask saved = mapper.selectLifecycle(draft.getId());
    assertThat(saved.getStatus()).isEqualTo("WAIT_START");
}
```

Keep the existing repeated-submission test unchanged for the RED run; it remains the state-guard negative case.

- [ ] **Step 2: Run the backend tests and verify RED**

Run from `armada/armada-api`:

```bash
mvn -q -Dtest='PullTaskStandardCreateDTOTest,PullTaskStandardControllerTest,PullTaskDraftMapperInMemoryTest' test
```

Expected: FAIL because DTO/VO JSON still exposes `version` and the mismatched caller version still blocks `submitDraft`.

- [ ] **Step 3: Remove `version` from backend DTO and VO**

Change the create DTO header to:

```java
public record PullTaskStandardCreateDTO(
        Long draftTaskId,
        String taskName,
        String remark,
        Integer autoStart,
        Long groupFolderId,
        PullTaskPullerSyncMode pullerSyncMode,
        Integer materialAdminTiming,
        Boolean clearExistingMembers,
        Integer pullCountMin,
        Integer pullCountMax,
        Integer pullIntervalSeconds,
        Integer pullerCountPerGroup,
        Integer stationCountPerCall,
        Integer concurrentGroupCount,
        Long managerGroupId,
        Long pullerGroupId,
        Long stationGroupId,
        Long managerFinishGroupId,
        Long pullerFinishGroupId,
        PullTaskStandardGroupSettingDTO groupSetting) {
```

Change the draft VO to:

```java
public record PullTaskStandardDraftVO(
        Long draftTaskId,
        List<PullTaskStandardExecutionRowVO> rows,
        List<PullTaskStandardLinkLineVO> linkLines,
        List<PullTaskStandardFileResultVO> fileResults,
        int matchedCount,
        int remainingLinkCount,
        int ignoredFileCount) {
}
```

Update `PullTaskStandardDraftServiceImpl.EMPTY_VIEW` and `toView(...)` to stop supplying a version. Update the VO Javadoc and all test constructors/assertions so no text claims that the draft response exposes a version.

Update every `new PullTaskStandardCreateDTO(...)` test helper to remove the second constructor argument and every `base.version()` accessor. The canonical helper becomes:

```java
private static PullTaskStandardCreateDTO validRequest(long taskId) {
    return new PullTaskStandardCreateDTO(
            taskId, "任务", null, 0, null, PullTaskPullerSyncMode.SINGLE,
            1, false, 3, 8, 30, 2, 2, 1,
            11L, 12L, 13L, null, null, validGroupSetting());
}
```

- [ ] **Step 4: Replace the create Mapper version guard with a state guard**

Remove the `request.version()` validation block from `PullTaskStandardCreateTransactionService.validate` and call:

```java
if (pullTaskMapper.submitDraft(update, System.currentTimeMillis()) == 0) {
    throw new BusinessException(ErrorCode.CONFLICT, "任务已被并发提交，请刷新后重试");
}
```

Import `com.armada.task.model.enums.PullTaskStandardStatus`, then change `PullTaskMapper` to:

```java
int submitDraftTransition(
        @Param("row") PullTask row,
        @Param("expectedTaskType") PullTaskType expectedTaskType,
        @Param("expectedStatus") String expectedStatus,
        @Param("now") long now);

default int submitDraft(PullTask row, long now) {
    row.setStatus(PullTaskStandardStatus.WAIT_START.name());
    return submitDraftTransition(
            row,
            PullTaskType.STANDARD,
            PullTaskStandardStatus.DRAFT.name(),
            now);
}
```

Update its Javadoc to describe a status guard. In `PullTaskMapper.xml`, retain `version = version + 1` in the `SET` clause but delete:

```xml
AND version = #{expectedVersion}
```

Update the XML comment to “状态守卫”. Do not change any other lifecycle Mapper method that uses `expectedVersion`.

Now update every mapper test call to the final two-argument signature, including the successful transition, mismatched-version replacement, repeated submission, and tenant-isolation cases. Representative assertions are:

```java
assertThat(mapper.submitDraft(submitRow(draft.getId()), 900L)).isEqualTo(1);
assertThat(mapper.submitDraft(submitRow(draft.getId()), 901L)).isZero();
```

- [ ] **Step 5: Run focused backend tests and verify GREEN**

Run from `armada/armada-api`:

```bash
mvn -q -Dtest='PullTaskStandardCreateDTOTest,PullTaskStandardControllerTest,PullTaskStandardDraftServicePlanTest,PullTaskStandardDraftServiceReadEditTest,PullTaskDraftMapperInMemoryTest,PullTaskStandardCreateServiceTest' test
```

Expected: PASS. H2 must still show `DRAFT -> WAIT_START`, database version `1 -> 2`, repeated submission returning the same task, tenant isolation, and rollback on occupied links.

- [ ] **Step 6: Commit the backend change**

```bash
git add \
  armada-api/src/main/java/com/armada/task/model/dto/PullTaskStandardCreateDTO.java \
  armada-api/src/main/java/com/armada/task/model/vo/PullTaskStandardDraftVO.java \
  armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardDraftServiceImpl.java \
  armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardCreateTransactionService.java \
  armada-api/src/main/java/com/armada/task/mapper/PullTaskMapper.java \
  armada-api/src/main/resources/mapper/task/PullTaskMapper.xml \
  armada-api/src/test/java/com/armada/task/model/dto/PullTaskStandardCreateDTOTest.java \
  armada-api/src/test/java/com/armada/task/controller/PullTaskStandardControllerTest.java \
  armada-api/src/test/java/com/armada/task/service/PullTaskStandardDraftServiceReadEditTest.java \
  armada-api/src/test/java/com/armada/task/service/PullTaskStandardCreateServiceTest.java \
  armada-api/src/test/java/com/armada/task/mapper/PullTaskDraftMapperInMemoryTest.java
git commit -m "fix: 取消拉群创建版本参数"
```

### Task 2: Remove the frontend draft/create version field

**Files:**

- Modify all frontend files listed above.

- [ ] **Step 1: Write failing frontend payload tests**

In both frontend test files, remove `"version"` from expected create-payload key arrays while leaving production code unchanged. Add:

```ts
assert.equal("version" in createData, false);
```

For the composable test, use its existing `payload` variable:

```ts
assert.equal("version" in payload, false);
```

- [ ] **Step 2: Run the frontend tests and verify RED**

Run from `wheel-saas-pure-web`:

```bash
node --test --experimental-strip-types \
  --loader ./src/api/__tests__/node-test-loader.mjs \
  src/api/pull-task.test.ts \
  src/views/task/pull-task/composables/useStandardPullTaskCreate.test.ts
```

Expected: FAIL because the current create payload still contains `version`.

- [ ] **Step 3: Remove the frontend version field**

Remove `version` from `PullTaskStandardDraft` and `PullTaskStandardCreateRequest`. Update `emptyDraft()` and the test `draft()` helper to omit it. Change the create guard to:

```ts
draft.value.draftTaskId === null || draft.value.rows.length === 0
```

Delete this property from the create payload and all API test callers:

```ts
version: draft.value.version,
```

- [ ] **Step 4: Run frontend tests and type checking, then verify GREEN**

Run from `wheel-saas-pure-web`:

```bash
node --test --experimental-strip-types \
  --loader ./src/api/__tests__/node-test-loader.mjs \
  src/api/pull-task.test.ts \
  src/views/task/pull-task/composables/useStandardPullTaskCreate.test.ts
pnpm typecheck
```

Expected: both test files PASS and TypeScript/Vue type checking exits 0.

- [ ] **Step 5: Commit the frontend change**

```bash
git add src/api/pull-task.ts \
  src/api/pull-task.test.ts \
  src/views/task/pull-task/composables/useStandardPullTaskCreate.ts \
  src/views/task/pull-task/composables/useStandardPullTaskCreate.test.ts
git commit -m "fix: 取消拉群创建版本参数"
```

### Task 3: Cross-repository verification

**Files:**

- Verify only; no new production files.

- [ ] **Step 1: Search for stale create-contract references**

Run from `IdeaProjects`:

```bash
rg -n '缺少草稿任务版本号|request\.version\(\)|base\.version\(\)|version: draft\.value\.version' \
  armada/armada-api/src wheel-saas-pure-web/src
```

Expected: no matches. Unrelated lifecycle `version` references remain unchanged.

- [ ] **Step 2: Run the backend regression suite**

Run from `armada/armada-api`:

```bash
mvn test
```

Expected: BUILD SUCCESS with zero test failures.

- [ ] **Step 3: Run the frontend regression checks**

Run from `wheel-saas-pure-web`:

```bash
node --test --experimental-strip-types \
  --loader ./src/api/__tests__/node-test-loader.mjs \
  src/api/pull-task.test.ts \
  src/views/task/pull-task/composables/useStandardPullTaskCreate.test.ts
pnpm typecheck
pnpm build
```

Expected: tests PASS, type checking exits 0, and Vite build exits 0.

- [ ] **Step 4: Inspect final diffs and repository state**

Run in both repositories:

```bash
git diff --check
git status --short
git log -3 --oneline
```

Expected: no whitespace errors; only the pre-existing `.claude/worktrees` state remains in `armada`, and both repositories contain the intentional commits from this plan.
