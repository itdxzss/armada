# Marketing Task Lifecycle Restart Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make ordinary task activation obey the stored start/end window and add an explicit, time-configurable restart flow for `ENDED` marketing tasks.

**Architecture:** Keep `marketing_task.task_start_at` and `task_end_at` as the execution window and `account_group_send_at` as an immutable group cutoff. The service decides whether activation means `PENDING` or `SENDING`, mapper updates use expected-status guards, and the round worker provides a second start-time gate. An `ENDED` task restarts through a separate endpoint and frontend dialog so the new execution window is explicit.

**Tech Stack:** Java 17, Spring Boot, MyBatis XML, JUnit 5/AssertJ, Spring MockMvc, Vue 3, TypeScript, Element Plus, Node test runner.

---

## Source Specification

- `docs/superpowers/specs/2026-07-10-marketing-task-lifecycle-restart-design.md`

## Scope Guard

This plan does not change account group lookup, queued message cancellation, account occupation, friend counts, membership `joined_at`, or the 72-hour account-group cutoff limit.

Before running a DbTest, inspect only the environment name/host classification and confirm `armada-api/.env` targets the disposable Armada test database. Never run these tests against production data.

## File Map

### Armada backend

- Create: `armada-api/src/main/java/com/armada/marketing/model/dto/RestartMarketingTaskDTO.java`
  Carries the new execution start and end times for an ended task.
- Modify: `armada-api/src/main/java/com/armada/marketing/controller/MarketingTaskController.java`
  Exposes `POST /api/marketing-tasks/{id}/restart`.
- Modify: `armada-api/src/main/java/com/armada/marketing/service/MarketingTaskService.java`
  Adds the restart business entry point.
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
  Computes activation state, rejects expired ordinary activation, validates restart windows, and preserves the fixed group cutoff.
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
  Replaces unconditional start with guarded activation and adds restart/defer methods.
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
  Implements guarded state updates without clearing `task_end_at` or changing `account_group_send_at`.
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`
  Defers a wrongly-sending task whose start time is still in the future.
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskMutationDbTest.java`
  Covers waiting activation, stopped-task resume, expired rejection, and restart persistence.
- Modify: `armada-api/src/test/java/com/armada/marketing/controller/MarketingTaskControllerDbTest.java`
  Covers the restart HTTP contract.
- Modify: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`
  Covers the worker start-time guard.
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`
  Locks expected-status/time guards and immutable cutoff SQL shape.

### Vue frontend

- Modify: `src/api/marketing-task.ts`
  Adds restart request type and API function.
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts`
  Owns restart dialog state, defaults, validation, submission, and activation feedback.
- Create: `src/views/task/group-marketing/components/GroupMarketingRestartDialog.vue`
  Renders the two datetime inputs.
- Create: `src/views/task/group-marketing/components/GroupMarketingRestartDialog.test.ts`
  Locks dialog fields and events.
- Create: `src/views/task/group-marketing/components/GroupMarketingTaskTable.test.ts`
  Locks start/restart button status gates.
- Modify: `src/views/task/group-marketing/components/GroupMarketingTaskTable.vue`
  Shows ordinary start only for `PENDING`/`STOPPED` and restart only for `ENDED`.
- Modify: `src/views/task/group-marketing/index.vue`
  Wires the restart row action and dialog.
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts`
  Covers restart defaults, validation, request payload, refresh, and waiting activation feedback.
- Modify: `.harness/changes/marketing-task-frontend/summary.md`
  Records the final contract and verification evidence.

---

### Task 1: Make Ordinary Activation Respect the Existing Window

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskMutationDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`

- [ ] **Step 1: Write failing DbTests for future, due, stopped, and expired tasks**

Add `STATUS_PENDING` and these cases to `MarketingTaskMutationDbTest`:

```java
private static final int STATUS_PENDING = 1;

@Test
void startTask_futurePendingTask_staysWaitingWithoutStartedAt() {
    Fixture fixture = seedFixture("start-future-pending");
    long now = System.currentTimeMillis();
    MarketingTaskVO created = createTaskWithTimes(
            "未来等待任务", fixture, "PENDING", now + 60_000L, now + 600_000L);

    MarketingTaskVO activated = service.startTask(created.id());

    assertThat(activated.status()).isEqualTo(STATUS_PENDING);
    assertThat(activated.startedAt()).isNull();
    assertThat(nextRoundAt(created.id())).isNull();
}

@Test
void startTask_stoppedTaskBeforeOriginalStart_returnsToWaiting() {
    Fixture fixture = seedFixture("resume-future-stopped");
    long now = System.currentTimeMillis();
    MarketingTaskVO created = createTaskWithTimes(
            "未来已停止任务", fixture, "PENDING", now + 60_000L, now + 600_000L);
    jdbc.update("UPDATE marketing_task SET status = 5 WHERE id = ?", created.id());

    MarketingTaskVO activated = service.startTask(created.id());

    assertThat(activated.status()).isEqualTo(STATUS_PENDING);
    assertThat(nextRoundAt(created.id())).isNull();
}

@Test
void startTask_stoppedTaskInsideWindow_resumesSending() {
    Fixture fixture = seedFixture("resume-active-stopped");
    long now = System.currentTimeMillis();
    MarketingTaskVO created = createTaskWithTimes(
            "窗口内已停止任务", fixture, "IMMEDIATE", now - 60_000L, now + 600_000L);
    MarketingTaskVO stopped = service.stopTask(created.id());

    MarketingTaskVO activated = service.startTask(stopped.id());

    assertThat(activated.status()).isEqualTo(STATUS_SENDING);
    assertThat(nextRoundAt(created.id())).isNotNull();
}

@Test
void startTask_stoppedTaskAfterEnd_requiresRestart() {
    Fixture fixture = seedFixture("resume-expired-stopped");
    long now = System.currentTimeMillis();
    MarketingTaskVO created = createTaskWithTimes(
            "已过期停止任务", fixture, "IMMEDIATE", now - 120_000L, now + 60_000L);
    service.stopTask(created.id());
    jdbc.update("UPDATE marketing_task SET task_end_at = ? WHERE id = ?", now - 1L, created.id());

    assertThatThrownBy(() -> service.startTask(created.id()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("计划已结束")
            .hasMessageContaining("重新启动");
}
```

Add helpers:

```java
private MarketingTaskVO createTaskWithTimes(String taskName,
                                            Fixture fixture,
                                            String startMode,
                                            Long taskStartAt,
                                            Long taskEndAt) {
    return service.createTask(new CreateMarketingTaskDTO(
            taskName,
            fixture.accountGroupId(),
            "营销账号组",
            fixture.templateId(),
            "营销模板",
            startMode,
            null,
            taskStartAt,
            taskEndAt,
            1,
            30,
            true,
            true,
            false,
            "状态变更测试",
            List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));
}

private Long nextRoundAt(Long taskId) {
    return jdbc.queryForObject(
            "SELECT next_round_at FROM marketing_task WHERE id = ?", Long.class, taskId);
}
```

- [ ] **Step 2: Run the new DbTests and verify RED**

After confirming the configured database is the Armada test database, run:

```bash
./dbtest.sh 'MarketingTaskMutationDbTest#startTask_*'
```

Expected: the future-start cases return status `2` or a non-null `next_round_at`, and the expired stopped task does not return the required validation error.

- [ ] **Step 3: Add a failing SQL-shape test for guarded activation**

Add to `MarketingTaskMapperSqlShapeTest`:

```java
@Test
void taskActivationUsesExpectedStatusAndDoesNotRewriteSchedule() throws IOException {
    String xml = new String(
            getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
            StandardCharsets.UTF_8);

    String sql = updateBlock(xml, "activateTask");

    assertThat(sql)
            .contains("status = #{nextStatus}")
            .contains("status = #{expectedStatus}")
            .contains("task_end_at &gt; #{now}")
            .contains("CASE WHEN #{nextStatus} = 2")
            .doesNotContain("task_start_at =")
            .doesNotContain("task_end_at =")
            .doesNotContain("account_group_send_at =");
}
```

- [ ] **Step 4: Run the SQL-shape test and verify RED**

```bash
mvn -Dtest=MarketingTaskMapperSqlShapeTest#taskActivationUsesExpectedStatusAndDoesNotRewriteSchedule test
```

Expected: FAIL because mapper update `activateTask` does not exist.

- [ ] **Step 5: Replace unconditional start with guarded activation**

Replace the mapper method with:

```java
/** Activate a pending/stopped task inside its existing execution window. */
int activateTask(@Param("id") Long id,
                 @Param("expectedStatus") int expectedStatus,
                 @Param("nextStatus") int nextStatus,
                 @Param("now") long now);
```

Replace `<update id="startTask">` with:

```xml
<update id="activateTask">
    UPDATE marketing_task
    SET status = #{nextStatus},
        started_at = CASE WHEN #{nextStatus} = 2 THEN COALESCE(started_at, #{now}) ELSE started_at END,
        next_round_at = CASE WHEN #{nextStatus} = 2 THEN #{now} ELSE NULL END,
        updated_at = #{now}
    WHERE id = #{id}
      AND deleted_at IS NULL
      AND status = #{expectedStatus}
      AND (task_end_at IS NULL OR task_end_at &gt; #{now})
</update>
```

Replace `MarketingTaskServiceImpl.startTask` with:

```java
@Override
@Transactional(rollbackFor = Exception.class)
public MarketingTaskVO startTask(Long id) {
    MarketingTask task = requireTask(id);
    if (!List.of(STATUS_PENDING, STATUS_STOPPED).contains(task.getStatus())) {
        throw new BusinessException(ErrorCode.VALIDATION, "只有等待中或已停止的任务可以启动");
    }
    long now = System.currentTimeMillis();
    if (task.getTaskEndAt() != null && task.getTaskEndAt() <= now) {
        throw new BusinessException(ErrorCode.VALIDATION, "任务计划已结束,请重新设置开始和结束时间后重新启动");
    }
    int nextStatus = task.getTaskStartAt() != null && task.getTaskStartAt() > now
            ? STATUS_PENDING
            : STATUS_SENDING;
    int updated = taskMapper.activateTask(id, task.getStatus(), nextStatus, now);
    if (updated == 0) {
        throw new BusinessException(ErrorCode.VALIDATION, "任务状态已变化,请刷新后重试");
    }
    log.info("营销任务激活 id={} tenantId={} status={} taskStartAt={} taskEndAt={}",
            id, task.getTenantId(), nextStatus, task.getTaskStartAt(), task.getTaskEndAt());
    return toVO(requireTask(id));
}
```

- [ ] **Step 6: Run activation tests and verify GREEN**

```bash
mvn -Dtest=MarketingTaskMapperSqlShapeTest test
./dbtest.sh MarketingTaskMutationDbTest
```

Expected: all mapper shape and mutation tests pass.

- [ ] **Step 7: Commit the ordinary activation slice**

```bash
git add armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java
git add armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java
git add armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml
git add armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java
git add armada-api/src/test/java/com/armada/marketing/service/MarketingTaskMutationDbTest.java
git diff --cached --check
git commit -m "fix: honor marketing task activation window"
```

---

### Task 2: Add a Worker Start-Time Defense

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`

- [ ] **Step 1: Write a failing worker test**

Add to `MarketingRoundWorkerTest`:

```java
@Test
void futureStartTimeDefersDirtySendingTaskWithoutGeneratingCommands() {
    MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
    ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
    MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
    MarketingTask task = task();
    task.setTaskStartAt(System.currentTimeMillis() + 60_000L);
    when(taskMapper.selectTaskById(42L)).thenReturn(task);

    MarketingRoundWorker worker = worker(taskMapper, outbox, properties);
    worker.runRound(1L, 42L);

    verify(taskMapper).deferEarlySendingTask(eq(42L), anyLong());
    verify(taskMapper, never()).selectTargetsByTaskId(anyLong());
    verify(taskMapper, never()).insertSendAttempts(any());
    verify(outbox, never()).enqueueMarketingMessageCommands(any());
}
```

- [ ] **Step 2: Run the worker test and verify RED**

```bash
mvn -Dtest=MarketingRoundWorkerTest#futureStartTimeDefersDirtySendingTaskWithoutGeneratingCommands test
```

Expected: test compilation fails because `deferEarlySendingTask` does not exist.

- [ ] **Step 3: Add mapper SQL-shape coverage**

Extend the lifecycle SQL test:

```java
String deferSql = updateBlock(xml, "deferEarlySendingTask");
assertThat(deferSql)
        .contains("SET status = 1")
        .contains("next_round_at = NULL")
        .contains("status = 2")
        .contains("task_start_at &gt; #{now}");
```

- [ ] **Step 4: Implement the guarded defer operation**

Add to `MarketingTaskMapper`:

```java
/** Return a dirty sending task to waiting when its planned start is still in the future. */
int deferEarlySendingTask(@Param("id") Long id, @Param("now") long now);
```

Add to mapper XML:

```xml
<update id="deferEarlySendingTask">
    UPDATE marketing_task
    SET status = 1,
        next_round_at = NULL,
        updated_at = #{now}
    WHERE id = #{id}
      AND deleted_at IS NULL
      AND status = 2
      AND task_start_at IS NOT NULL
      AND task_start_at &gt; #{now}
</update>
```

Insert this check immediately after `long now = System.currentTimeMillis();` in `MarketingRoundWorker.doRunRound`:

```java
if (task.getTaskStartAt() != null && task.getTaskStartAt() > now) {
    taskMapper.deferEarlySendingTask(taskId, now);
    log.info("营销任务轮次跳过并恢复等待:未到任务开始时间 tenantId={} taskId={} taskStartAt={}",
            task.getTenantId(), task.getId(), task.getTaskStartAt());
    return;
}
```

- [ ] **Step 5: Run worker and mapper tests and verify GREEN**

```bash
mvn -Dtest=MarketingRoundWorkerTest,MarketingTaskMapperSqlShapeTest test
```

Expected: all selected tests pass and no attempt/outbox is generated in the future-start case.

- [ ] **Step 6: Commit the worker defense**

```bash
git add armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java
git add armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java
git add armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml
git add armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java
git add armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java
git diff --cached --check
git commit -m "fix: guard marketing rounds before start time"
```

---

### Task 3: Add the Ended-Task Restart Backend Contract

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/model/dto/RestartMarketingTaskDTO.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskMutationDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/controller/MarketingTaskControllerDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/controller/MarketingTaskController.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/MarketingTaskService.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`

- [ ] **Step 1: Write failing service DbTests for future and immediate restart**

Add to `MarketingTaskMutationDbTest`:

```java
@Test
void restartTask_endedTaskWithFutureWindow_returnsWaitingAndPreservesHistory() {
    Fixture fixture = seedFixture("restart-ended-future");
    long now = System.currentTimeMillis();
    MarketingTaskVO created = createTaskWithTimes(
            "已结束未来重启", fixture, "IMMEDIATE", now - 120_000L, now + 60_000L);
    long originalCutoff = created.accountGroupSendAt();
    jdbc.update("""
            UPDATE marketing_task
            SET status = 7, task_end_at = ?, finished_at = ?,
                sent_message_count = 9, current_round_no = 4
            WHERE id = ?
            """, now - 1L, now - 1L, created.id());
    long newStartAt = now + 60_000L;
    long newEndAt = now + 3_600_000L;

    MarketingTaskVO restarted = service.restartTask(
            created.id(), new RestartMarketingTaskDTO(newStartAt, newEndAt));

    assertThat(restarted.status()).isEqualTo(STATUS_PENDING);
    assertThat(restarted.taskStartAt()).isEqualTo(newStartAt);
    assertThat(restarted.taskEndAt()).isEqualTo(newEndAt);
    assertThat(restarted.accountGroupSendAt()).isEqualTo(originalCutoff);
    assertThat(restarted.finishedAt()).isNull();
    assertThat(restarted.sentMessageCount()).isEqualTo(9);
    assertThat(currentRoundNo(created.id())).isEqualTo(4L);
    assertThat(nextRoundAt(created.id())).isNull();
}

@Test
void restartTask_endedTaskInsideNewWindow_resumesSending() {
    Fixture fixture = seedFixture("restart-ended-now");
    long now = System.currentTimeMillis();
    MarketingTaskVO created = createTaskWithTimes(
            "已结束立即重启", fixture, "IMMEDIATE", now - 120_000L, now + 60_000L);
    jdbc.update("UPDATE marketing_task SET status = 7, task_end_at = ?, finished_at = ? WHERE id = ?",
            now - 1L, now - 1L, created.id());

    MarketingTaskVO restarted = service.restartTask(
            created.id(), new RestartMarketingTaskDTO(now - 1_000L, now + 3_600_000L));

    assertThat(restarted.status()).isEqualTo(STATUS_SENDING);
    assertThat(restarted.finishedAt()).isNull();
    assertThat(nextRoundAt(created.id())).isNotNull();
}
```

Add:

```java
private Long currentRoundNo(Long taskId) {
    return jdbc.queryForObject(
            "SELECT current_round_no FROM marketing_task WHERE id = ?", Long.class, taskId);
}
```

- [ ] **Step 2: Write failing validation and controller tests**

Add a non-ended validation assertion:

```java
@Test
void restartTask_nonEndedTask_isRejected() {
    Fixture fixture = seedFixture("restart-not-ended");
    long now = System.currentTimeMillis();
    MarketingTaskVO created = createTaskWithTimes(
            "未结束任务", fixture, "PENDING", now + 60_000L, now + 600_000L);

    assertThatThrownBy(() -> service.restartTask(
            created.id(), new RestartMarketingTaskDTO(now, now + 600_000L)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("只有已结束的任务可以重新启动");
}

@Test
void restartTask_endedTaskRejectsInvalidWindow() {
    Fixture fixture = seedFixture("restart-invalid-window");
    long now = System.currentTimeMillis();
    MarketingTaskVO created = createTaskWithTimes(
            "非法重启窗口", fixture, "IMMEDIATE", now - 120_000L, now + 60_000L);
    jdbc.update("UPDATE marketing_task SET status = 7, task_end_at = ?, finished_at = ? WHERE id = ?",
            now - 1L, now - 1L, created.id());

    assertThatThrownBy(() -> service.restartTask(
            created.id(), new RestartMarketingTaskDTO(now + 120_000L, now + 60_000L)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("结束时间必须晚于任务开始时间");

    assertThatThrownBy(() -> service.restartTask(
            created.id(), new RestartMarketingTaskDTO(now - 120_000L, now - 60_000L)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("结束时间必须晚于当前时间");
}
```

Add to `MarketingTaskControllerDbTest` after marking a created task ended with `JdbcTemplate`:

```java
@Test
void postRestart_updatesEndedTaskWindow() throws Exception {
    Fixture fixture = seedFixture("controller-restart");
    long id = createTask("Controller重新启动任务", fixture);
    long now = System.currentTimeMillis();
    jdbc.update("UPDATE marketing_task SET status = 7, task_end_at = ?, finished_at = ? WHERE id = ?",
            now - 1L, now - 1L, id);
    long newStartAt = now + 60_000L;
    long newEndAt = now + 3_600_000L;

    mockMvc.perform(post("/api/marketing-tasks/{id}/restart", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                            "taskStartAt", newStartAt,
                            "taskEndAt", newEndAt)))
                    .header(TENANT_HEADER, TENANT_CODE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.status").value(1))
            .andExpect(jsonPath("$.data.taskStartAt").value(newStartAt))
            .andExpect(jsonPath("$.data.taskEndAt").value(newEndAt))
            .andExpect(jsonPath("$.data.finishedAt").isEmpty());
}
```

- [ ] **Step 3: Run restart tests and verify RED**

```bash
./dbtest.sh 'MarketingTaskMutationDbTest#restartTask_*'
./dbtest.sh MarketingTaskControllerDbTest#postRestart_updatesEndedTaskWindow
```

Expected: test compilation fails because the restart DTO, service method, and endpoint do not exist.

- [ ] **Step 4: Add a failing SQL-shape test for immutable cutoff and preserved counters**

```java
@Test
void endedTaskRestartOnlyReplacesExecutionWindow() throws IOException {
    String xml = new String(
            getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
            StandardCharsets.UTF_8);

    String sql = updateBlock(xml, "restartEndedTask");

    assertThat(sql)
            .contains("status = #{nextStatus}")
            .contains("task_start_at = #{taskStartAt}")
            .contains("task_end_at = #{taskEndAt}")
            .contains("finished_at = NULL")
            .contains("status = 7")
            .doesNotContain("account_group_send_at =")
            .doesNotContain("sent_message_count =")
            .doesNotContain("failed_message_count =")
            .doesNotContain("current_round_no =");
}
```

- [ ] **Step 5: Implement the restart DTO, endpoint, service, and mapper**

Create `RestartMarketingTaskDTO.java`:

```java
package com.armada.marketing.model.dto;

/** New execution window for restarting an ended marketing task. */
public record RestartMarketingTaskDTO(Long taskStartAt, Long taskEndAt) {
}
```

Add to `MarketingTaskService`:

```java
/** Restart an ended task with a new execution window. */
MarketingTaskVO restartTask(Long id, RestartMarketingTaskDTO request);
```

Add to `MarketingTaskController`:

```java
@PostMapping("/{id}/restart")
public ApiResponse<MarketingTaskVO> restart(@PathVariable Long id,
                                            @RequestBody RestartMarketingTaskDTO request) {
    return ApiResponse.ok(service.restartTask(id, request));
}
```

Add to `MarketingTaskMapper`:

```java
/** Restart an ended task without changing group cutoff, counters, rounds, or target history. */
int restartEndedTask(@Param("id") Long id,
                     @Param("nextStatus") int nextStatus,
                     @Param("taskStartAt") long taskStartAt,
                     @Param("taskEndAt") long taskEndAt,
                     @Param("now") long now);
```

Add to mapper XML:

```xml
<update id="restartEndedTask">
    UPDATE marketing_task
    SET status = #{nextStatus},
        task_start_at = #{taskStartAt},
        task_end_at = #{taskEndAt},
        started_at = CASE WHEN #{nextStatus} = 2 THEN COALESCE(started_at, #{now}) ELSE started_at END,
        next_round_at = CASE WHEN #{nextStatus} = 2 THEN #{now} ELSE NULL END,
        finished_at = NULL,
        updated_at = #{now}
    WHERE id = #{id}
      AND deleted_at IS NULL
      AND status = 7
</update>
```

Add to `MarketingTaskServiceImpl`:

```java
@Override
@Transactional(rollbackFor = Exception.class)
public MarketingTaskVO restartTask(Long id, RestartMarketingTaskDTO request) {
    MarketingTask task = requireTask(id);
    if (!Integer.valueOf(STATUS_ENDED).equals(task.getStatus())) {
        throw new BusinessException(ErrorCode.VALIDATION, "只有已结束的任务可以重新启动");
    }
    long now = System.currentTimeMillis();
    validateRestartTimes(request, now);
    int nextStatus = request.taskStartAt() > now ? STATUS_PENDING : STATUS_SENDING;
    int updated = taskMapper.restartEndedTask(
            id, nextStatus, request.taskStartAt(), request.taskEndAt(), now);
    if (updated == 0) {
        throw new BusinessException(ErrorCode.VALIDATION, "任务状态已变化,请刷新后重试");
    }
    log.info("营销任务重新启动 id={} tenantId={} status={} taskStartAt={} taskEndAt={}",
            id, task.getTenantId(), nextStatus, request.taskStartAt(), request.taskEndAt());
    return toVO(requireTask(id));
}

private static void validateRestartTimes(RestartMarketingTaskDTO request, long now) {
    if (request == null || request.taskStartAt() == null) {
        throw new BusinessException(ErrorCode.VALIDATION, "请选择任务开始时间");
    }
    if (request.taskEndAt() == null) {
        throw new BusinessException(ErrorCode.VALIDATION, "请选择任务结束时间");
    }
    if (request.taskEndAt() <= now) {
        throw new BusinessException(ErrorCode.VALIDATION, "任务结束时间必须晚于当前时间");
    }
    if (request.taskEndAt() <= request.taskStartAt()) {
        throw new BusinessException(ErrorCode.VALIDATION, "任务结束时间必须晚于任务开始时间");
    }
}
```

Keep ordinary `startTask` restricted to exactly `STATUS_PENDING` and `STATUS_STOPPED`. Add the `RestartMarketingTaskDTO` import to the controller, service interface, and service implementation.

- [ ] **Step 6: Run backend restart tests and verify GREEN**

```bash
mvn -Dtest=MarketingTaskMapperSqlShapeTest test
./dbtest.sh MarketingTaskMutationDbTest
./dbtest.sh MarketingTaskControllerDbTest
```

Expected: mapper, service, and controller lifecycle tests pass.

- [ ] **Step 7: Commit the restart backend**

```bash
git add armada-api/src/main/java/com/armada/marketing/controller/MarketingTaskController.java
git add armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java
git add armada-api/src/main/java/com/armada/marketing/model/dto/RestartMarketingTaskDTO.java
git add armada-api/src/main/java/com/armada/marketing/service/MarketingTaskService.java
git add armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java
git add armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml
git add armada-api/src/test/java/com/armada/marketing/controller/MarketingTaskControllerDbTest.java
git add armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java
git add armada-api/src/test/java/com/armada/marketing/service/MarketingTaskMutationDbTest.java
git diff --cached --check
git commit -m "feat: restart ended marketing tasks with new window"
```

---

### Task 4: Add the Frontend Restart API and Page State

**Files:**
- Modify: `wheel-saas-pure-web/src/api/marketing-task.ts`
- Modify: `wheel-saas-pure-web/src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts`
- Modify: `wheel-saas-pure-web/src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts`

- [ ] **Step 1: Write failing tests for defaults and the restart request**

Add a task fixture near the top of `useGroupMarketingTaskPage.test.ts`:

```typescript
import type { MarketingTaskRow } from "@/api/marketing-task";
import {
  buildMarketingRestartForm,
  useGroupMarketingTaskPage
} from "./useGroupMarketingTaskPage";

function marketingTask(overrides: Partial<MarketingTaskRow> = {}): MarketingTaskRow {
  return {
    id: 42,
    taskName: "生命周期任务",
    accountGroupId: 8,
    accountGroupName: "北美账号",
    marketingTemplateId: 18,
    marketingTemplateName: "活动模板",
    status: 7,
    selectedAccountCount: 1,
    targetGroupCount: 1,
    targetPairCount: 1,
    sentMessageCount: 5,
    failedMessageCount: 0,
    sendPerRound: 1,
    sendIntervalSeconds: 30,
    onlineCheckEnabled: true,
    abnormalGroupSkipped: true,
    autoRetryEnabled: false,
    retryLimit: 0,
    accountGroupSendAt: 1_000,
    taskStartAt: 10_000,
    taskEndAt: 70_000,
    ...overrides
  };
}
```

Add tests:

```typescript
it("defaults restart window from the original duration", () => {
  const defaults = buildMarketingRestartForm(marketingTask(), 1_000_000);

  assert.deepEqual(defaults, {
    taskStartAt: "1000000",
    taskEndAt: "1060000"
  });
});

it("submits a new restart window and refreshes the list", async () => {
  const now = Date.now();
  const newStartAt = now + 60_000;
  const newEndAt = now + 3_600_000;
  const restarted = marketingTask({
    status: 1,
    taskStartAt: newStartAt,
    taskEndAt: newEndAt,
    finishedAt: null
  });
  resetArmadaMockQueue([
    restarted,
    { list: [restarted], total: 1, page: 1, pageSize: 10 }
  ]);
  resetElementPlusMock();
  const pageState = useGroupMarketingTaskPage();
  pageState.openRestartDialog(marketingTask());
  pageState.restartForm.taskStartAt = String(newStartAt);
  pageState.restartForm.taskEndAt = String(newEndAt);

  await pageState.submitRestart();

  assert.deepEqual(armadaCalls().map(call => ({
    method: call.method,
    url: call.url,
    data: (call.opts as { data?: unknown } | undefined)?.data
  })), [
    {
      method: "post",
      url: "/api/marketing-tasks/42/restart",
      data: { taskStartAt: newStartAt, taskEndAt: newEndAt }
    },
    {
      method: "get",
      url: "/api/marketing-tasks",
      data: undefined
    }
  ]);
  assert.equal(pageState.restartDialogOpen.value, false);
});

it("rejects a restart window whose end is not after its start", async () => {
  resetArmadaMock({});
  resetElementPlusMock();
  const now = Date.now();
  const pageState = useGroupMarketingTaskPage();
  pageState.openRestartDialog(marketingTask());
  pageState.restartForm.taskStartAt = String(now + 120_000);
  pageState.restartForm.taskEndAt = String(now + 60_000);

  await pageState.submitRestart();

  assert.deepEqual(armadaCalls(), []);
  assert.deepEqual(elementPlusCalls(), [
    { type: "warning", text: "任务结束时间必须晚于任务开始时间" }
  ]);
});

it("reports waiting when ordinary activation remains pending", async () => {
  resetArmadaMock(marketingTask({ status: 1 }));
  resetElementPlusMock();
  const pageState = useGroupMarketingTaskPage();

  await pageState.startTask(marketingTask({ status: 1 }));

  assert.deepEqual(elementPlusCalls(), [
    { type: "success", text: "任务已进入等待，将按计划时间执行" }
  ]);
});
```

- [ ] **Step 2: Run the composable tests and verify RED**

```bash
pnpm exec tsx src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
```

Expected: compilation fails because `buildMarketingRestartForm`, restart state, and restart API do not exist. If `tsx` is not locally available, request approval to install/use the project test runner; do not replace the test with an unexecuted assertion.

- [ ] **Step 3: Add API types and request function**

Add to `src/api/marketing-task.ts`:

```typescript
export interface RestartMarketingTaskRequest {
  taskStartAt: number;
  taskEndAt: number;
}

export function restartMarketingTask(
  id: number,
  data: RestartMarketingTaskRequest
): Promise<MarketingTaskRow> {
  return armadaRequest<MarketingTaskRow>(
    "post",
    `/api/marketing-tasks/${id}/restart`,
    { data }
  );
}
```

- [ ] **Step 4: Add restart state, defaults, validation, and submission**

Add exported form type and default helper:

```typescript
export interface GroupMarketingRestartForm {
  taskStartAt: string;
  taskEndAt: string;
}

export function buildMarketingRestartForm(
  task: MarketingTaskRow,
  now = Date.now()
): GroupMarketingRestartForm {
  const originalDuration =
    task.taskStartAt != null &&
    task.taskEndAt != null &&
    task.taskEndAt > task.taskStartAt
      ? task.taskEndAt - task.taskStartAt
      : DEFAULT_TASK_DURATION_MS;
  return {
    taskStartAt: String(now),
    taskEndAt: String(now + originalDuration)
  };
}
```

Add these fields to `GroupMarketingTaskPageState` and return object:

```typescript
activeRestartTask: Ref<MarketingTaskRow | null>;
closeRestartDialog: () => void;
openRestartDialog: (row: MarketingTaskRow) => void;
restartDialogOpen: Ref<boolean>;
restartForm: GroupMarketingRestartForm;
restartSubmitting: Ref<boolean>;
submitRestart: () => Promise<void>;
```

Create state inside `useGroupMarketingTaskPage`:

```typescript
const activeRestartTask = ref<MarketingTaskRow | null>(null);
const restartDialogOpen = ref(false);
const restartSubmitting = ref(false);
const restartForm = reactive<GroupMarketingRestartForm>({
  taskStartAt: "",
  taskEndAt: ""
});
```

Add methods:

```typescript
function openRestartDialog(row: MarketingTaskRow): void {
  activeRestartTask.value = row;
  Object.assign(restartForm, buildMarketingRestartForm(row));
  restartDialogOpen.value = true;
}

function closeRestartDialog(): void {
  restartDialogOpen.value = false;
  activeRestartTask.value = null;
}

async function submitRestart(): Promise<void> {
  const task = activeRestartTask.value;
  const taskStartAt = timestamp(restartForm.taskStartAt);
  const taskEndAt = timestamp(restartForm.taskEndAt);
  if (!task || !taskStartAt) {
    ElMessage.warning("请选择任务开始时间");
    return;
  }
  if (!taskEndAt) {
    ElMessage.warning("请选择任务结束时间");
    return;
  }
  if (taskEndAt <= Date.now()) {
    ElMessage.warning("任务结束时间必须晚于当前时间");
    return;
  }
  if (taskEndAt <= taskStartAt) {
    ElMessage.warning("任务结束时间必须晚于任务开始时间");
    return;
  }
  restartSubmitting.value = true;
  try {
    await restartMarketingTask(task.id, { taskStartAt, taskEndAt });
    ElMessage.success(taskStartAt > Date.now()
      ? "任务已重新启动，将按计划时间执行"
      : "营销任务已重新启动");
    closeRestartDialog();
    await refreshTasks();
  } catch (error) {
    ElMessage.error(apiErrorMessage(error, "重新启动营销任务失败"));
  } finally {
    restartSubmitting.value = false;
  }
}
```

Change ordinary activation feedback:

```typescript
ElMessage.success(
  updated.status === 1
    ? "任务已进入等待，将按计划时间执行"
    : "营销任务已启动"
);
```

- [ ] **Step 5: Run composable tests and verify GREEN**

```bash
pnpm exec tsx src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
```

Expected: all group-marketing page state tests pass.

- [ ] **Step 6: Commit the frontend state slice**

Run from `wheel-saas-pure-web`:

```bash
git add src/api/marketing-task.ts
git add src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts
git add src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
git diff --cached --check
git commit -m "feat: add marketing task restart state"
```

---

### Task 5: Wire the Restart Dialog and Status-Specific Actions

**Files:**
- Create: `wheel-saas-pure-web/src/views/task/group-marketing/components/GroupMarketingRestartDialog.vue`
- Create: `wheel-saas-pure-web/src/views/task/group-marketing/components/GroupMarketingRestartDialog.test.ts`
- Create: `wheel-saas-pure-web/src/views/task/group-marketing/components/GroupMarketingTaskTable.test.ts`
- Modify: `wheel-saas-pure-web/src/views/task/group-marketing/components/GroupMarketingTaskTable.vue`
- Modify: `wheel-saas-pure-web/src/views/task/group-marketing/index.vue`

- [ ] **Step 1: Write failing source tests for the dialog and table gates**

Create `GroupMarketingRestartDialog.test.ts`:

```typescript
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

const source = readFileSync(
  new URL("./GroupMarketingRestartDialog.vue", import.meta.url),
  "utf8"
);

describe("group marketing restart dialog", () => {
  it("edits a required start and end time and emits submit", () => {
    assert.match(source, /label="任务开始时间" required/);
    assert.match(source, /label="任务结束时间" required/);
    assert.match(source, /v-model="form\.taskStartAt"/);
    assert.match(source, /v-model="form\.taskEndAt"/);
    assert.match(source, /emit\("submit"\)/);
  });
});
```

Create `GroupMarketingTaskTable.test.ts`:

```typescript
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

const source = readFileSync(
  new URL("./GroupMarketingTaskTable.vue", import.meta.url),
  "utf8"
);

describe("group marketing task table lifecycle actions", () => {
  it("shows ordinary start only for waiting or stopped tasks", () => {
    assert.match(source, /v-if="row\.status === 1 \|\| row\.status === 5"/);
    assert.match(source, /'start'/);
  });

  it("shows restart only for ended tasks", () => {
    assert.match(source, /v-if="row\.status === 7"/);
    assert.match(source, /'restart'/);
    assert.match(source, />\s*重新启动\s*</);
  });
});
```

- [ ] **Step 2: Run component source tests and verify RED**

```bash
./node_modules/.bin/jiti src/views/task/group-marketing/components/GroupMarketingRestartDialog.test.ts
./node_modules/.bin/jiti src/views/task/group-marketing/components/GroupMarketingTaskTable.test.ts
```

Expected: the dialog file is missing and the table lacks the status-specific conditions.

- [ ] **Step 3: Create the restart dialog**

Create `GroupMarketingRestartDialog.vue`:

```vue
<script setup lang="ts">
import { watch } from "vue";
import type { GroupMarketingRestartForm } from "../composables/useGroupMarketingTaskPage";

defineOptions({ name: "GroupMarketingRestartDialog" });

defineProps<{ submitting: boolean }>();

const emit = defineEmits<{
  (event: "closed"): void;
  (event: "submit"): void;
}>();
const visible = defineModel<boolean>({ required: true });
const form = defineModel<GroupMarketingRestartForm>("form", { required: true });

watch(visible, value => {
  if (!value) emit("closed");
});
</script>

<template>
  <el-dialog v-model="visible" title="重新启动营销任务" width="520px">
    <el-form :model="form" label-width="120px">
      <el-form-item label="任务开始时间" required>
        <el-date-picker
          v-model="form.taskStartAt"
          type="datetime"
          value-format="x"
          class="form-control"
        />
      </el-form-item>
      <el-form-item label="任务结束时间" required>
        <el-date-picker
          v-model="form.taskEndAt"
          type="datetime"
          value-format="x"
          class="form-control"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="submitting" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="emit('submit')">
        确认重新启动
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-control {
  width: 100%;
}
</style>
```

- [ ] **Step 4: Gate table actions by status**

Replace the current generic start button with:

```vue
<el-button
  v-if="row.status === 1 || row.status === 5"
  link
  type="success"
  @click="emit('row-action', asMarketingTaskRow(row), 'start')"
>
  启动
</el-button>
<el-button
  v-if="row.status === 7"
  link
  type="success"
  @click="emit('row-action', asMarketingTaskRow(row), 'restart')"
>
  重新启动
</el-button>
```

Keep the existing stop action restricted to status `2`. Success, failure, and partial-failure tasks receive neither start nor restart.

- [ ] **Step 5: Wire restart state through the page**

Import `GroupMarketingRestartDialog` in `index.vue`. Destructure:

```typescript
closeRestartDialog,
openRestartDialog,
restartDialogOpen,
restartForm,
restartSubmitting,
submitRestart,
```

Add to `onTaskAction`:

```typescript
if (action === "restart") {
  openRestartDialog(row);
  return;
}
```

Render after the detail drawer:

```vue
<GroupMarketingRestartDialog
  v-model="restartDialogOpen"
  v-model:form="restartForm"
  :submitting="restartSubmitting"
  @submit="submitRestart"
  @closed="closeRestartDialog"
/>
```

- [ ] **Step 6: Run component tests, typecheck, and lint**

```bash
./node_modules/.bin/jiti src/views/task/group-marketing/components/GroupMarketingRestartDialog.test.ts
./node_modules/.bin/jiti src/views/task/group-marketing/components/GroupMarketingTaskTable.test.ts
./node_modules/.bin/tsc --noEmit
./node_modules/.bin/vue-tsc --noEmit --skipLibCheck
./node_modules/.bin/eslint --max-warnings 0 src/api/marketing-task.ts src/views/task/group-marketing/index.vue src/views/task/group-marketing/components/GroupMarketingRestartDialog.vue src/views/task/group-marketing/components/GroupMarketingTaskTable.vue src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts
```

Expected: all commands exit `0` with no warnings.

- [ ] **Step 7: Commit the frontend UI slice**

```bash
git add src/views/task/group-marketing/components/GroupMarketingRestartDialog.vue
git add src/views/task/group-marketing/components/GroupMarketingRestartDialog.test.ts
git add src/views/task/group-marketing/components/GroupMarketingTaskTable.vue
git add src/views/task/group-marketing/components/GroupMarketingTaskTable.test.ts
git add src/views/task/group-marketing/index.vue
git diff --cached --check
git commit -m "feat: add marketing task restart dialog"
```

---

### Task 6: Cross-Layer Regression Verification and Documentation

**Files:**
- Modify: `wheel-saas-pure-web/.harness/changes/marketing-task-frontend/summary.md`
- Verify all files changed in Tasks 1-5.

- [ ] **Step 1: Run the focused backend unit suite**

From `armada/armada-api`:

```bash
mvn -Dtest=MarketingRoundWorkerTest,MarketingRoundSchedulerTest,MarketingTaskMapperSqlShapeTest test
```

Expected: all selected tests pass with zero failures and errors.

- [ ] **Step 2: Run backend DbTests only after confirming the test environment**

```bash
./dbtest.sh MarketingTaskMutationDbTest
./dbtest.sh MarketingTaskControllerDbTest
./dbtest.sh MarketingRoundMapperDbTest
./dbtest.sh MarketingTaskCreateReadDbTest
```

Expected: all DbTests pass. Confirm task restart preserves `account_group_send_at`, counters, target rows, attempts, and round number.

- [ ] **Step 3: Run the focused frontend test suite**

From `wheel-saas-pure-web`:

```bash
pnpm exec tsx src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
./node_modules/.bin/jiti src/views/task/group-marketing/components/GroupMarketingRestartDialog.test.ts
./node_modules/.bin/jiti src/views/task/group-marketing/components/GroupMarketingTaskTable.test.ts
./node_modules/.bin/jiti src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts
```

Expected: all suites pass with zero failures.

- [ ] **Step 4: Run frontend static and production-build checks**

```bash
./node_modules/.bin/tsc --noEmit
./node_modules/.bin/vue-tsc --noEmit --skipLibCheck
./node_modules/.bin/eslint --max-warnings 0 src/api/marketing-task.ts src/views/task/group-marketing/index.vue src/views/task/group-marketing/components/GroupMarketingRestartDialog.vue src/views/task/group-marketing/components/GroupMarketingTaskTable.vue src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts
./node_modules/.bin/vite build
```

Expected: all commands exit `0`. The build may emit existing chunk-size notices, but no compilation error.

- [ ] **Step 5: Record final behavior and evidence**

Append to `.harness/changes/marketing-task-frontend/summary.md`:

```markdown
## 生命周期时间与重新启动（2026-07-10）

- 普通启动不突破 `taskStartAt/taskEndAt`；未来任务保持等待，到时由后端调度启动。
- 已结束任务通过独立重新启动弹窗提交新的开始、结束时间。
- 重新启动保持 `accountGroupSendAt`、目标、计数、轮次和发送历史，清空旧 `finishedAt`。
- Worker 对未来开始时间增加防御闸门，不生成 attempt/outbox。

### 验证

- 后端 lifecycle/worker/mapper 定向单测通过。
- 后端营销任务 DbTests 通过，测试环境已确认。
- 前端 group-marketing 定向测试、TypeScript、Vue typecheck、ESLint 和生产构建通过。
```

Replace each “通过” claim with the actual command result. If a command cannot run, record the command and exact blocker instead of claiming success.

- [ ] **Step 6: Review diffs and commit documentation**

In each repository:

```bash
git diff --check
git status --short
git diff --stat
```

Then commit the frontend summary only after checking the staged diff:

```bash
git add .harness/changes/marketing-task-frontend/summary.md
git diff --cached --check
git commit -m "docs: record marketing task restart verification"
```

Do not stage unrelated existing worktree files, credentials, heap snapshots, or nested worktrees.
