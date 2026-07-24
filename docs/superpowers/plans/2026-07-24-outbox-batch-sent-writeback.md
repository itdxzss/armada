# Outbox Batch SENT Writeback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在每个最多 100 条的 Kafka ACK 窗口完成后，用一条 SQL 批量把成功 Outbox 行更新为 `SENT`，避免 1000 条成功命令产生 1000 次数据库提交。

**Architecture:** `ProtocolCommandPublisher` 整批只准备一次凭据和代理，并在每个应用层发送窗口收敛后同步通知窗口消费者；`ProtocolCommandDispatcher` 在通知中先批量回写成功行，再逐条处理少量 RETRY/DEAD。批量 SQL 始终校验同一组 `locked_by + locked_at`，Dispatcher 执行器继续保持单线程。

**Tech Stack:** Java 17、Spring Boot 3.3.5、Spring Kafka、MyBatis XML、JUnit 5、Mockito、AssertJ、MySQL DbTest

---

### Task 1: Publisher 暴露逐窗口完成通知

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java`
- Test: `armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java`

- [ ] **Step 1: 写逐窗口通知的失败测试**

在 `ProtocolCommandPublisherTest` 新增测试。使用 `maxInFlight=2` 和三个已完成 Kafka Future，记录 `send` 与窗口回调顺序：

```java
@Test
void publishBatchByWindow_notifiesCompletedWindowBeforeSubmittingNextWindow() {
    ProtocolCommandPublisher boundedPublisher = publisherWithMaxInFlight(2);
    List<ProtocolCommandOutbox> rows = List.of(
            passthroughOutboxRow("cmd_100", 100L, "acc_100"),
            passthroughOutboxRow("cmd_101", 101L, "acc_101"),
            passthroughOutboxRow("cmd_102", 102L, "acc_102"));
    List<String> events = new ArrayList<>();
    when(kafkaTemplate.send(any(), any(), any())).thenAnswer(invocation -> {
        events.add("send:" + invocation.getArgument(1, String.class));
        return CompletableFuture.completedFuture(null);
    });

    boundedPublisher.publishBatchByWindow(rows, outcomes -> events.add(
            "window:" + outcomes.stream()
                    .map(outcome -> outcome.row().getKafkaKey())
                    .collect(Collectors.joining(","))));

    assertThat(events).containsExactly(
            "send:acc_100",
            "send:acc_101",
            "window:acc_100,acc_101",
            "send:acc_102",
            "window:acc_102");
}
```

补充 `java.util.ArrayList` 和 `java.util.stream.Collectors` import。

- [ ] **Step 2: 运行测试确认 RED**

Run:

```bash
cd armada-api
mvn -Dtest=ProtocolCommandPublisherTest#publishBatchByWindow_notifiesCompletedWindowBeforeSubmittingNextWindow test
```

Expected: 编译失败，提示 `publishBatchByWindow` 不存在。

- [ ] **Step 3: 实现窗口通知并保留原 API**

在 `ProtocolCommandPublisher` 引入 `java.util.function.Consumer`。保留 `publishBatch(rows)` 的返回契约，通过新方法收集窗口结果：

```java
public List<ProtocolCommandPublishOutcome> publishBatch(List<ProtocolCommandOutbox> rows) {
    List<ProtocolCommandPublishOutcome> outcomes = new ArrayList<>();
    publishBatchByWindow(rows, outcomes::addAll);
    return List.copyOf(outcomes);
}

public void publishBatchByWindow(
        List<ProtocolCommandOutbox> rows,
        Consumer<List<ProtocolCommandPublishOutcome>> completedWindowConsumer) {
    if (rows == null || rows.isEmpty()) {
        return;
    }
    Objects.requireNonNull(completedWindowConsumer, "completedWindowConsumer");
    PreparedEnvelopes prepared = prepareEnvelopes(rows);
    int maxInFlight = properties.getMaxInFlight();
    for (int start = 0; start < rows.size(); start += maxInFlight) {
        int end = Math.min(start + maxInFlight, rows.size());
        List<ProtocolCommandPublishOutcome> outcomes = publishWindow(
                rows.subList(start, end), prepared);
        completedWindowConsumer.accept(outcomes);
    }
}
```

把 `publishWindow` 改为接收原始行窗口和已经准备好的 envelope，窗口内的准备失败用 `completedFuture` 形成 outcome；可发送行仍先全部调用 `sendAsync`，再统一 `allOf().join()`：

```java
private List<ProtocolCommandPublishOutcome> publishWindow(
        List<ProtocolCommandOutbox> rows,
        PreparedEnvelopes prepared) {
    List<CompletableFuture<ProtocolCommandPublishOutcome>> pending = new ArrayList<>(rows.size());
    for (ProtocolCommandOutbox row : rows) {
        RuntimeException prepareFailure = prepared.failures().get(commandKey(row));
        if (prepareFailure != null) {
            pending.add(CompletableFuture.completedFuture(
                    ProtocolCommandPublishOutcome.failure(row, prepareFailure)));
            continue;
        }
        ProtocolCommandEnvelope envelope = prepared.envelopes().get(commandKey(row));
        if (envelope == null) {
            pending.add(CompletableFuture.completedFuture(
                    ProtocolCommandPublishOutcome.failure(row,
                            validation("协议命令 envelope 缺失: " + safeCommandId(row)))));
            continue;
        }
        pending.add(sendAsync(row, envelope));
    }
    CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
    return pending.stream().map(CompletableFuture::join).toList();
}
```

删除不再需要的 `PreparedPublish`、`PendingPublish` record 和全局下标占位逻辑。整批 `prepareEnvelopes(rows)` 仍只执行一次。

- [ ] **Step 4: 运行 Publisher 全部测试确认 GREEN**

Run:

```bash
cd armada-api
mvn -Dtest=ProtocolCommandPublisherTest test
```

Expected: `ProtocolCommandPublisherTest` 全部通过，既有输入顺序、窗口背压和批量 hydrate 测试保持绿色。

- [ ] **Step 5: 提交 Publisher 窗口通知**

```bash
git add armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java \
  armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java
git commit -m "refactor: 暴露协议命令发送窗口结果"
```

### Task 2: Mapper 增加批量 SENT 状态流转

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapper.java`
- Modify: `armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml`
- Test: `armada-api/src/test/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapperDbTest.java`

- [ ] **Step 1: 写批量状态流转的失败 DbTest**

在 `ProtocolCommandOutboxMapperDbTest` 新增测试，插入三条 PENDING、用同一个锁抢占，批量标记前两条并验证第三条保持 LOCKED：

```java
@Test
void markSentBatch_updatesOnlyRowsHeldBySameLockContext() {
    long now = System.currentTimeMillis();
    ProtocolCommandOutbox first = pendingCommand("batch-sent-1-" + now, null, 3601L, now);
    ProtocolCommandOutbox second = pendingCommand("batch-sent-2-" + now, null, 3602L, now);
    ProtocolCommandOutbox third = pendingCommand("batch-sent-3-" + now, null, 3603L, now);
    mapper.batchInsertPending(List.of(first, second, third));
    List<Long> ids = List.of(
            insertedId(first.getCommandId(), now),
            insertedId(second.getCommandId(), now),
            insertedId(third.getCommandId(), now));
    long lockedAt = now + 1;
    assertThat(mapper.markLocked(ids, "publisher-batch", lockedAt)).isEqualTo(3);

    int stale = mapper.markSentBatch(List.of(
            lockedRow(ids.get(0), first.getCommandId(), "publisher-batch", lockedAt + 1),
            lockedRow(ids.get(1), second.getCommandId(), "publisher-batch", lockedAt + 1)), now + 2);
    int updated = mapper.markSentBatch(List.of(
            lockedRow(ids.get(0), first.getCommandId(), "publisher-batch", lockedAt),
            lockedRow(ids.get(1), second.getCommandId(), "publisher-batch", lockedAt)), now + 3);

    assertThat(stale).isZero();
    assertThat(updated).isEqualTo(2);
    assertThat(state(ids.get(0)).status()).isEqualTo(ProtocolCommandOutboxStatus.SENT.code());
    assertThat(state(ids.get(1)).status()).isEqualTo(ProtocolCommandOutboxStatus.SENT.code());
    assertThat(state(ids.get(2)).status()).isEqualTo(ProtocolCommandOutboxStatus.LOCKED.code());
    assertThat(mapper.markSentBatch(List.of(
            lockedRow(ids.get(0), first.getCommandId(), "publisher-batch", lockedAt),
            lockedRow(ids.get(1), second.getCommandId(), "publisher-batch", lockedAt)), now + 4)).isZero();
}
```

- [ ] **Step 2: 运行 DbTest 确认 RED**

在用户确认 `armada-api/.env` 指向允许写入且测试事务可回滚的数据库后运行：

```bash
cd armada-api
./dbtest.sh 'ProtocolCommandOutboxMapperDbTest#markSentBatch_updatesOnlyRowsHeldBySameLockContext'
```

Expected: 编译失败，提示 `markSentBatch` 不存在。

- [ ] **Step 3: 在 Mapper 接口实现锁上下文校验**

新增默认方法，所有行必须具有相同的非空 `lockedBy/lockedAt` 和非空 `commandId`；否则直接返回 0：

```java
default int markSentBatch(List<ProtocolCommandOutbox> lockedRows, long sentAt) {
    if (lockedRows == null || lockedRows.isEmpty()) {
        return 0;
    }
    ProtocolCommandOutbox first = lockedRows.get(0);
    if (!hasCommandLockContext(first)) {
        return 0;
    }
    boolean sameLock = lockedRows.stream().allMatch(row ->
            hasCommandLockContext(row)
                    && first.getLockedBy().equals(row.getLockedBy())
                    && first.getLockedAt().equals(row.getLockedAt()));
    if (!sameLock) {
        return 0;
    }
    return markSentBatchByCommandIdsInternal(
            lockedRows.stream().map(ProtocolCommandOutbox::getCommandId).toList(),
            first.getLockedBy(),
            first.getLockedAt(),
            ProtocolCommandOutboxStatus.LOCKED.code(),
            ProtocolCommandOutboxStatus.SENT.code(),
            sentAt);
}

@InterceptorIgnore(tenantLine = "true")
int markSentBatchByCommandIdsInternal(
        @Param("commandIds") List<String> commandIds,
        @Param("lockedBy") String lockedBy,
        @Param("lockedAt") long lockedAt,
        @Param("lockedStatus") int lockedStatus,
        @Param("sentStatus") int sentStatus,
        @Param("sentAt") long sentAt);

private static boolean hasCommandLockContext(ProtocolCommandOutbox row) {
    return row != null
            && row.getCommandId() != null
            && !row.getCommandId().isBlank()
            && row.getLockedBy() != null
            && !row.getLockedBy().isBlank()
            && row.getLockedAt() != null;
}
```

- [ ] **Step 4: 增加单语句批量 UPDATE**

在 `ProtocolCommandOutboxMapper.xml` 增加：

```xml
<update id="markSentBatchByCommandIdsInternal">
  UPDATE protocol_command_outbox
  SET status = #{sentStatus},
      sent_at = #{sentAt},
      last_error = NULL,
      updated_at = #{sentAt}
  WHERE deleted_at IS NULL
    AND status = #{lockedStatus}
    AND locked_by = #{lockedBy}
    AND locked_at = #{lockedAt}
    AND command_id IN
  <foreach collection="commandIds" item="commandId" open="(" separator="," close=")">
    #{commandId}
  </foreach>
</update>
```

- [ ] **Step 5: 校验 XML 并运行 DbTest 确认 GREEN**

Run:

```bash
xmllint --noout armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml
cd armada-api
./dbtest.sh 'ProtocolCommandOutboxMapperDbTest#markSentBatch_updatesOnlyRowsHeldBySameLockContext'
```

Expected: XML 校验退出码 0；目标 DbTest 通过且未被跳过。

- [ ] **Step 6: 提交批量 Mapper**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapper.java \
  armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml \
  armada-api/src/test/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapperDbTest.java
git commit -m "perf: 批量回写 outbox sent 状态"
```

### Task 3: Dispatcher 按窗口批量回写成功结果

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatcher.java`
- Test: `armada-api/src/test/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatcherTest.java`

- [ ] **Step 1: 写成功窗口只批量回写一次的失败测试**

把 `dispatchInsertedRows_locksInsertedRowsByCommandIdAndDoesNotScanPending` 的 Publisher mock 改成窗口回调，并断言两条成功只调用一次 `markSentBatch`：

```java
doAnswer(invocation -> {
    Consumer<List<ProtocolCommandPublishOutcome>> consumer = invocation.getArgument(1);
    consumer.accept(List.of(success(first), success(second)));
    return null;
}).when(publisher).publishBatchByWindow(eq(List.of(first, second)), any());
when(mapper.markSentBatch(eq(List.of(first, second)), anyLong())).thenReturn(2);

ProtocolCommandDispatchResult result = dispatcher.dispatchInsertedRows(List.of(first, second));

assertThat(result.sent()).isEqualTo(2);
verify(mapper).markSentBatch(eq(List.of(first, second)), anyLong());
verify(mapper, never()).markSent(any(), anyLong());
```

补充 `doAnswer`、`any` 和 `java.util.function.Consumer` import。

- [ ] **Step 2: 写混合结果测试**

修改 `dispatchPendingNow_oneFailedRowDoesNotStopOtherLockedRows`，让 Publisher 回调一个包含失败和成功的窗口：

```java
doAnswer(invocation -> {
    Consumer<List<ProtocolCommandPublishOutcome>> consumer = invocation.getArgument(1);
    consumer.accept(List.of(
            failure(failed, ProtocolException.unknown("temporary kafka error", null)),
            success(sent)));
    return null;
}).when(publisher).publishBatchByWindow(eq(List.of(failed, sent)), any());
when(mapper.markSentBatch(eq(List.of(sent)), anyLong())).thenReturn(1);
when(mapper.markRetry(same(failed), anyLong(), eq("temporary kafka error"), anyLong())).thenReturn(1);
```

断言 `sent=1/retried=1/dead=0`，成功行不再调用单行 `markSent`。

- [ ] **Step 3: 运行 Dispatcher 测试确认 RED**

Run:

```bash
cd armada-api
mvn -Dtest=ProtocolCommandDispatcherTest test
```

Expected: 编译失败，提示 Dispatcher 尚未调用 `publishBatchByWindow` 或 `markSentBatch`。

- [ ] **Step 4: 实现窗口累计器和批量成功回写**

把 `sendLockedRows` 改为窗口回调：

```java
private RowDispatchCounts sendLockedRows(List<ProtocolCommandOutbox> rows) {
    RowDispatchAccumulator accumulator = new RowDispatchAccumulator();
    publisher.publishBatchByWindow(rows, outcomes -> dispatchWindow(outcomes, accumulator));
    return accumulator.toCounts();
}

private void dispatchWindow(
        List<ProtocolCommandPublishOutcome> outcomes,
        RowDispatchAccumulator accumulator) {
    List<ProtocolCommandOutbox> succeededRows = outcomes.stream()
            .filter(ProtocolCommandPublishOutcome::succeeded)
            .map(ProtocolCommandPublishOutcome::row)
            .toList();
    accumulator.sent += markSentBatch(succeededRows);
    for (ProtocolCommandPublishOutcome outcome : outcomes) {
        RuntimeException error = outcome.error();
        if (error == null) {
            continue;
        }
        ProtocolCommandOutbox row = outcome.row();
        if (error instanceof BusinessException ex) {
            log.warn("协议命令 outbox payload 不可发送 commandId={} batchId={} accountId={} error={}",
                    row.getCommandId(), row.getBatchId(), row.getAggregateId(), safeError(ex));
            accumulator.dead += markDead(row, ex);
        } else if (shouldMarkDead(row)) {
            log.warn("协议命令 outbox 发送失败且重试耗尽 commandId={} batchId={} accountId={} retryCount={} error={}",
                    row.getCommandId(), row.getBatchId(), row.getAggregateId(), row.getRetryCount(),
                    safeError(error));
            accumulator.dead += markDead(row, error);
        } else {
            log.warn("协议命令 outbox 发送失败等待重试 commandId={} batchId={} accountId={} retryCount={} "
                            + "retryDelayMs={} error={}",
                    row.getCommandId(), row.getBatchId(), row.getAggregateId(), row.getRetryCount(),
                    retryDelayMs(), safeError(error));
            accumulator.retried += markRetry(row, error);
        }
    }
}

private int markSentBatch(List<ProtocolCommandOutbox> rows) {
    if (rows.isEmpty()) {
        return 0;
    }
    long now = now();
    int updated = mapper.markSentBatch(rows, now);
    if (updated != rows.size()) {
        ProtocolCommandOutbox first = rows.get(0);
        log.warn("协议命令 outbox SENT 批量回写未全量命中 batchId={} requested={} updated={} "
                        + "lockedBy={} lockedAt={}",
                first.getBatchId(), rows.size(), updated, first.getLockedBy(), first.getLockedAt());
    }
    return updated;
}

private static final class RowDispatchAccumulator {
    private int sent;
    private int retried;
    private int dead;

    private RowDispatchCounts toCounts() {
        return new RowDispatchCounts(sent, retried, dead);
    }
}
```

删除 Dispatcher 私有的单行 `markSent` 方法；失败路径的 `markRetry`、`markDead` 保持不变。

- [ ] **Step 5: 更新其余 Dispatcher mock 并运行 GREEN**

所有只返回一个窗口的现有测试统一使用 `doAnswer` 调用窗口消费者。运行：

```bash
cd armada-api
mvn -Dtest=ProtocolCommandDispatcherTest,ProtocolCommandPublisherTest test
```

Expected: 两个测试类全部通过。

- [ ] **Step 6: 提交 Dispatcher 改造**

```bash
git add armada-api/src/main/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatcher.java \
  armada-api/src/test/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatcherTest.java
git commit -m "perf: 按 kafka 窗口批量确认 outbox"
```

### Task 4: 回归验证与变更记录

**Files:**
- Modify: `.harness/changes/2026-07-24-batch-online-optimistic-proxy-allocation.md`

- [ ] **Step 1: 运行相关单元测试**

```bash
cd armada-api
mvn -Dtest=ProtocolCommandPublisherTest,ProtocolCommandDispatcherTest test
```

Expected: Maven `BUILD SUCCESS`，0 failures，0 errors。

- [ ] **Step 2: 运行 Outbox Mapper 真库测试类**

仅在用户确认 `.env` 目标数据库后运行：

```bash
cd armada-api
./dbtest.sh 'ProtocolCommandOutboxMapperDbTest'
```

Expected: 测试类全部真实执行并通过；不得出现 skip。

- [ ] **Step 3: 编译生产代码并检查差异**

```bash
cd armada-api
mvn -DskipTests package
cd ..
git diff --check HEAD~3..HEAD
git status --short
```

Expected: `BUILD SUCCESS`；无 whitespace error；仅包含计划内文件和原工作区已有的无关在途文件。

- [ ] **Step 4: 更新 change 记录**

在 `.harness/changes/2026-07-24-batch-online-optimistic-proxy-allocation.md` 记录：

```markdown
- [x] Kafka Publisher 每个 ACK 窗口完成后通知 Dispatcher。
- [x] Outbox 成功状态按窗口批量 UPDATE，失败状态保留逐条回写。

### Outbox 批量回写验证

- `mvn -Dtest=ProtocolCommandPublisherTest,ProtocolCommandDispatcherTest test`: 记录实际结果。
- `./dbtest.sh 'ProtocolCommandOutboxMapperDbTest'`: 记录实际结果或目标数据库未确认的阻塞原因。
- `mvn -DskipTests package`: 记录实际结果。
```

- [ ] **Step 5: 提交验证记录**

```bash
git add .harness/changes/2026-07-24-batch-online-optimistic-proxy-allocation.md
git commit -m "docs: 记录 outbox 批量回写验证"
```

