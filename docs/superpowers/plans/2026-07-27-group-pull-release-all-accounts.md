# 拉群营销释放全部账号 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 点击“释放账号”后停止任务下所有活动执行，释放全部建群账号和营销分组，并防止已确认额度的失败执行误扣后续预留额度。

**Architecture:** 保留现有异步安全释放入口和执行租约。执行 worker 取得租约后先识别任务是否处于释放中；释放中则进入 Finalizer 的统一取消结算，不再调用协议能力。Finalizer 根据执行阶段区分“额度仍预留”和“额度已确认”，只归还真实预留额度。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis、JUnit 5、AssertJ、Mockito、Maven

---

### Task 1: 修正失败执行的额度结算

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingFinalizerTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingFinalizer.java`

- [ ] **Step 1: 写失败测试，证明已确认额度的执行不会再扣预留**

在 `GroupPullMarketingFinalizerTest` 增加两个独立用例：

```java
@Test
void failureBeforeQuotaConfirmationCancelsReservedQuota() {
    SettlementFixture fixture = new SettlementFixture(GroupPullExecutionStage.CREATE_GROUP);

    fixture.finalizer().fail(501L, "创建群组失败");

    assertThat(fixture.calls).contains("cancelMarketingQuota");
}

@Test
void failureAfterQuotaConfirmationKeepsReservedQuotaForOtherExecutions() {
    SettlementFixture fixture = new SettlementFixture(GroupPullExecutionStage.SET_MARKETER_ADMIN);

    fixture.finalizer().fail(501L, "管理员设置失败");

    assertThat(fixture.calls).doesNotContain("cancelMarketingQuota");
}
```

测试 fixture 返回活动执行，并记录 `cancelMarketingQuota`、料子收口和账号释放调用。第二个用例在当前实现下应失败，因为 `finish` 对所有失败阶段都会调用 `cancelMarketingQuota`。

- [ ] **Step 2: 运行定向测试确认 RED**

Run:

```bash
cd armada-api && mvn -Dtest='GroupPullMarketingFinalizerTest' test
```

Expected: `failureAfterQuotaConfirmationKeepsReservedQuotaForOtherExecutions` 失败，实际调用中包含 `cancelMarketingQuota`。

- [ ] **Step 3: 最小实现阶段感知的额度归还**

在 `GroupPullMarketingFinalizer` 增加私有判断，并替换失败结算中的无条件额度取消：

```java
private static boolean hasReservedMarketingQuota(GroupPullMarketingExecution execution) {
    return execution.getCurrentStage() < GroupPullExecutionStage.ADD_MATERIALS.code();
}
```

```java
if (hasReservedMarketingQuota(execution)) {
    mapper.cancelMarketingQuota(
            execution.getTaskId(), execution.getMarketingAccountId(), now);
}
```

额度确认和推进到 `ADD_MATERIALS` 已在同一事务中，因此阶段小于 5 表示仍需归还预留，阶段 5 及以后表示额度已经确认。

- [ ] **Step 4: 运行定向测试确认 GREEN**

Run:

```bash
cd armada-api && mvn -Dtest='GroupPullMarketingFinalizerTest' test
```

Expected: `BUILD SUCCESS`，两个额度边界用例均通过。

### Task 2: 释放中任务取消所有活动执行

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingFinalizerTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorkerTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingFinalizer.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java`

- [ ] **Step 1: 写 Finalizer 取消结算失败测试**

增加用例，要求任务释放取消把正式执行置为取消、归还料子、按阶段归还额度并释放账号：

```java
@Test
void taskReleaseCancelsFormalExecutionAndReleasesItsAccount() {
    SettlementFixture fixture = new SettlementFixture(GroupPullExecutionStage.CREATE_GROUP);

    fixture.finalizer().cancelForTaskRelease(501L);

    assertThat(fixture.terminalStatus).isEqualTo(GroupPullExecutionStatus.CANCELED.code());
    assertThat(fixture.terminalStage).isEqualTo(GroupPullExecutionStage.COMPLETED.code());
    assertThat(fixture.calls).containsSubsequence(
            "completeFailedJoinedMaterials",
            "releaseUnjoinedMaterials",
            "cancelMarketingQuota",
            "releaseTaskAccount",
            "markExecutionReleased");
}
```

- [ ] **Step 2: 运行 Finalizer 测试确认 RED**

Run:

```bash
cd armada-api && mvn -Dtest='GroupPullMarketingFinalizerTest' test
```

Expected: 编译失败或测试失败，提示 `cancelForTaskRelease` 尚不存在。

- [ ] **Step 3: 实现统一任务释放取消入口**

在 `GroupPullMarketingFinalizer` 增加事务方法：

```java
@Transactional(rollbackFor = Exception.class)
public void cancelForTaskRelease(Long executionId) {
    GroupPullMarketingExecution execution = mapper.selectExecutionById(executionId);
    if (!active(execution)) {
        return;
    }
    long now = System.currentTimeMillis();
    if (mapper.markExecutionTerminal(
            execution.getId(),
            execution.getExecutionStatus(),
            execution.getCurrentStage(),
            GroupPullExecutionStatus.CANCELED.code(),
            GroupPullExecutionStage.COMPLETED.code(),
            "任务释放，执行已取消",
            now) != 1) {
        return;
    }
    mapper.completeFailedJoinedMaterials(execution.getId(), now);
    mapper.releaseUnjoinedMaterials(execution.getId(), now);
    if (hasReservedMarketingQuota(execution)) {
        mapper.cancelMarketingQuota(
                execution.getTaskId(), execution.getMarketingAccountId(), now);
    }
    if (occupancyService.releaseTaskAccount(
            execution.getTaskId(), execution.getBuilderAccountId())) {
        mapper.markExecutionReleased(execution.getId(), now);
    }
}
```

同步补充公开方法 Javadoc，明确它不迁移账号、不继续协议阶段，只为任务级资源释放收口。

- [ ] **Step 4: 运行 Finalizer 测试确认 GREEN**

Run:

```bash
cd armada-api && mvn -Dtest='GroupPullMarketingFinalizerTest' test
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 5: 写 worker 释放短路失败测试**

在 `GroupPullMarketingExecutionWorkerTest` 增加 Mockito 或记录代理用例，构造资源状态为 `RELEASING` 的任务：

```java
@Test
void releasingTaskCancelsExecutionWithoutCallingProtocol() {
    GroupPullMarketingExecution execution = execution();
    GroupPullMarketingTask task = task();
    task.setResourceStatus(GroupPullResourceStatus.RELEASING.code());
    when(mapper.selectExecutionById(501L)).thenReturn(execution);
    when(mapper.tryLeaseExecution(eq(501L), anyInt(), anyInt(), anyLong(), anyLong()))
            .thenReturn(1);
    when(mapper.selectTaskById(101L)).thenReturn(task);

    worker.process(501L);

    verify(finalizer).cancelForTaskRelease(501L);
    verifyNoInteractions(groupCreatePort);
    verify(mapper, never()).selectAccountRef(anyLong());
}
```

当前 worker 取得租约后直接读取建群账号并推进协议阶段，因此测试应失败。

- [ ] **Step 6: 运行 worker 测试确认 RED**

Run:

```bash
cd armada-api && mvn -Dtest='GroupPullMarketingExecutionWorkerTest' test
```

Expected: 未调用 `cancelForTaskRelease`，或仍调用建群账号/协议依赖。

- [ ] **Step 7: 实现释放状态短路**

在 worker 成功取得租约后、读取建群账号前增加：

```java
GroupPullMarketingTask task = mapper.selectTaskById(execution.getTaskId());
if (task != null
        && Integer.valueOf(GroupPullResourceStatus.RELEASING.code())
                .equals(task.getResourceStatus())) {
    finalizer.cancelForTaskRelease(executionId);
    return;
}
```

补充 `GroupPullResourceStatus` import。正常资源状态继续沿用原阶段流程；释放中任务不再调用任何协议端口。

- [ ] **Step 8: 运行 worker 测试确认 GREEN**

Run:

```bash
cd armada-api && mvn -Dtest='GroupPullMarketingExecutionWorkerTest' test
```

Expected: `BUILD SUCCESS`。

### Task 3: 回归验证与交付检查

**Files:**
- Verify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingFinalizer.java`
- Verify: `armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java`
- Verify: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingFinalizerTest.java`
- Verify: `armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorkerTest.java`

- [ ] **Step 1: 运行拉群营销相关服务与调度测试**

Run:

```bash
cd armada-api && mvn -Dtest='GroupPullMarketingFinalizerTest,GroupPullMarketingExecutionWorkerTest,GroupPullMarketingFirstMaterialDelayTest,GroupPullMarketingReleaseServiceTest,GroupPullMarketingSchedulerTest' test
```

Expected: `BUILD SUCCESS`，正常执行、延迟、释放和调度测试全部通过。

- [ ] **Step 2: 运行完整测试**

Run:

```bash
cd armada-api && mvn test
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 3: 检查差异和工作区边界**

Run:

```bash
git diff --check
git status --short
git diff -- armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingFinalizer.java armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingFinalizerTest.java armada-api/src/test/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorkerTest.java
```

Expected: 无空白错误；差异仅包含本次释放语义和额度修复，不包含现有 `.claude/worktrees` 在途内容。

- [ ] **Step 4: 记录验证结论**

交付说明中记录定向测试与完整测试的真实结果。部署第一套测试环境属于后续明确授权步骤；未部署前不得声称任务 50 已自动释放。
