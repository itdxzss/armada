# Protocol Command Publisher Window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Armada 协议命令发布从逐条等待 Kafka ACK 改为默认最多 100 条在途的可配置异步窗口，并保留每条 outbox 独立结果。

**Architecture:** `ProtocolCommandPublisher` 继续一次准备整个 outbox 批次的 envelope，再把可发送行按 `maxInFlight` 切成窗口。每个窗口先连续调用异步 `KafkaTemplate.send`，每条 Future 独立应用超时并转换为 outcome，窗口全部收敛后按输入索引回填结果，再进入下一窗口。

**Tech Stack:** Java 17、Spring Boot 3.3.5、Spring Kafka `KafkaTemplate`、`CompletableFuture`、JUnit 5、Mockito、AssertJ

---

## 文件结构

- 修改 `armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolCommandPublisherProperties.java`：增加窗口默认值、配置字段和正数校验。
- 修改 `armada-api/src/main/resources/application.yml`：暴露 `PROTOCOL_COMMAND_MAX_IN_FLIGHT`，默认 100。
- 修改 `armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java`：实现窗口切分、异步提交、Future 收敛和按输入位置回填结果。
- 修改 `armada-api/src/main/java/com/armada/platform/protocol/model/result/ProtocolCommandPublishOutcome.java`：同步更新“逐条发送”的过期 Javadoc。
- 修改 `armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolCommandPublisherPropertiesTest.java`：锁定配置默认值、覆盖值、YAML 和非法值。
- 修改 `armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java`：锁定窗口内并发、跨窗口背压、混合结果和返回顺序。
- 修改 `.harness/changes/2026-07-17-protocol-command-publisher-window.md`：记录实施状态与真实验证证据。

### Task 1: 增加可配置的最大在途数

**Files:**
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolCommandPublisherPropertiesTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolCommandPublisherProperties.java`
- Modify: `armada-api/src/main/resources/application.yml`

- [ ] **Step 1: 写配置失败测试**

在 `ProtocolCommandPublisherPropertiesTest` 增加以下测试，并在现有 application.yml 默认测试中增加窗口断言：

```java
@Test
void bindsCommandPublisherMaxInFlight() {
    contextRunner
            .withPropertyValues("armada.protocol.command-publisher.max-in-flight=37")
            .run(context -> {
                ProtocolCommandPublisherProperties properties =
                        context.getBean(ProtocolCommandPublisherProperties.class);
                assertThat(properties.getMaxInFlight()).isEqualTo(37);
            });
}

@Test
void providesDefaultMaxInFlight() {
    contextRunner.run(context -> {
        ProtocolCommandPublisherProperties properties =
                context.getBean(ProtocolCommandPublisherProperties.class);
        assertThat(properties.getMaxInFlight())
                .isEqualTo(ProtocolCommandPublisherProperties.DEFAULT_MAX_IN_FLIGHT);
    });
}

@Test
void rejectsNonPositiveMaxInFlight() {
    contextRunner
            .withPropertyValues("armada.protocol.command-publisher.max-in-flight=0")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasRootCauseMessage("协议命令 Kafka 最大在途数必须大于 0");
            });
}
```

把 `bindsApplicationYamlCommandPublisherDefaults` 的断言扩展为：

```java
assertThat(properties.getSendTimeoutMs()).isEqualTo(10_000L);
assertThat(properties.getMaxInFlight()).isEqualTo(100);
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```bash
cd armada-api
mvn -Dtest=ProtocolCommandPublisherPropertiesTest test
```

Expected: 编译失败，提示 `getMaxInFlight` 和 `DEFAULT_MAX_IN_FLIGHT` 尚不存在。

- [ ] **Step 3: 实现最小配置**

在 `ProtocolCommandPublisherProperties` 增加：

```java
/** 默认 Kafka 同时在途发送数。 */
public static final int DEFAULT_MAX_IN_FLIGHT = 100;

/** Kafka 同时在途发送数。 */
private int maxInFlight = DEFAULT_MAX_IN_FLIGHT;

/**
 * 获取 Kafka 同时在途发送数。
 *
 * @return Kafka 同时在途发送数
 */
public int getMaxInFlight() {
    return maxInFlight;
}

/**
 * 设置 Kafka 同时在途发送数。
 *
 * @param maxInFlight Kafka 同时在途发送数
 * @throws IllegalArgumentException 最大在途数不大于 0 时抛出
 */
public void setMaxInFlight(int maxInFlight) {
    if (maxInFlight <= 0) {
        throw new IllegalArgumentException("协议命令 Kafka 最大在途数必须大于 0");
    }
    this.maxInFlight = maxInFlight;
}
```

同步更新类 Javadoc，说明该配置同时控制 ACK 超时和异步窗口。在 `application.yml` 的
`command-publisher` 下增加：

```yaml
max-in-flight: ${PROTOCOL_COMMAND_MAX_IN_FLIGHT:100}
```

- [ ] **Step 4: 运行配置测试并确认 GREEN**

Run:

```bash
cd armada-api
mvn -Dtest=ProtocolCommandPublisherPropertiesTest test
```

Expected: `ProtocolCommandPublisherPropertiesTest` 全部通过，BUILD SUCCESS。

- [ ] **Step 5: 提交配置切片**

```bash
git add armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolCommandPublisherProperties.java \
  armada-api/src/main/resources/application.yml \
  armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolCommandPublisherPropertiesTest.java
git commit -m "feat: configure protocol command publish window"
```

### Task 2: 实现有界异步发送窗口

**Files:**
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/model/result/ProtocolCommandPublishOutcome.java`

- [ ] **Step 1: 写窗口行为失败测试**

在 `ProtocolCommandPublisherTest` 增加 `CountDownLatch`、`TimeUnit` 和 `AtomicInteger` imports，并增加：

```java
@Test
void publishBatch_submitsOneWindowBeforeWaitingAndKeepsInputOrder() throws Exception {
    ProtocolCommandPublisher boundedPublisher = publisherWithMaxInFlight(2);
    List<ProtocolCommandOutbox> rows = List.of(
            passthroughOutboxRow("cmd_100", 100L, "acc_100"),
            passthroughOutboxRow("cmd_101", 101L, "acc_101"),
            passthroughOutboxRow("cmd_102", 102L, "acc_102"));
    CompletableFuture<SendResult<String, ProtocolCommandEnvelope>> first = new CompletableFuture<>();
    CompletableFuture<SendResult<String, ProtocolCommandEnvelope>> second = new CompletableFuture<>();
    CompletableFuture<SendResult<String, ProtocolCommandEnvelope>> third = new CompletableFuture<>();
    List<CompletableFuture<SendResult<String, ProtocolCommandEnvelope>>> sendFutures =
            List.of(first, second, third);
    CountDownLatch firstWindowSubmitted = new CountDownLatch(2);
    CountDownLatch thirdSubmitted = new CountDownLatch(1);
    AtomicInteger sendCount = new AtomicInteger();
    when(kafkaTemplate.send(any(), any(), any())).thenAnswer(invocation -> {
        int index = sendCount.getAndIncrement();
        if (index < 2) {
            firstWindowSubmitted.countDown();
        } else {
            thirdSubmitted.countDown();
        }
        return sendFutures.get(index);
    });

    CompletableFuture<List<ProtocolCommandPublishOutcome>> publishing =
            CompletableFuture.supplyAsync(() -> boundedPublisher.publishBatch(rows));

    boolean submittedFullWindow = firstWindowSubmitted.await(1, TimeUnit.SECONDS);
    int sendsBeforeAck = sendCount.get();
    second.completeExceptionally(new IllegalStateException("broker unavailable"));
    boolean crossedWindowBeforeFirstCompleted = thirdSubmitted.await(100, TimeUnit.MILLISECONDS);
    first.complete(null);
    boolean submittedNextWindow = thirdSubmitted.await(1, TimeUnit.SECONDS);
    third.complete(null);
    List<ProtocolCommandPublishOutcome> outcomes = publishing.get(1, TimeUnit.SECONDS);

    assertThat(submittedFullWindow).isTrue();
    assertThat(sendsBeforeAck).isEqualTo(2);
    assertThat(crossedWindowBeforeFirstCompleted).isFalse();
    assertThat(submittedNextWindow).isTrue();
    assertThat(outcomes)
            .extracting(outcome -> outcome.row().getCommandId())
            .containsExactly("cmd_100", "cmd_101", "cmd_102");
    assertThat(outcomes)
            .extracting(ProtocolCommandPublishOutcome::succeeded)
            .containsExactly(true, false, true);
    assertThat(outcomes.get(1).error()).isInstanceOf(ProtocolException.class);
}
```

增加测试专用构造 helper：

```java
private ProtocolCommandPublisher publisherWithMaxInFlight(int maxInFlight) {
    ProtocolCommandPublisherProperties properties = new ProtocolCommandPublisherProperties();
    properties.setSendTimeoutMs(1_000);
    properties.setMaxInFlight(maxInFlight);
    return new ProtocolCommandPublisher(
            kafkaTemplate,
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL),
            properties,
            credentialMapper,
            ipProxyMapper,
            new ProxyResolver());
}

private static ProtocolCommandOutbox passthroughOutboxRow(String commandId,
                                                          Long accountId,
                                                          String protocolAccountId) {
    ProtocolCommandOutbox row = outboxRow(
            commandId,
            1L,
            accountId,
            protocolAccountId,
            "{\"accountId\":" + accountId + ",\"protocolAccountId\":\"" + protocolAccountId
                    + "\",\"source\":\"scheduled_account_group_sync\"}");
    row.setCommandType("account.groups_sync.requested");
    row.setKafkaTopic("protocol.master.commands.v1");
    return row;
}
```

让现有 `setUp` 复用 `publisherWithMaxInFlight(ProtocolCommandPublisherProperties.DEFAULT_MAX_IN_FLIGHT)`，避免两套构造逻辑。

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```bash
cd armada-api
mvn -Dtest=ProtocolCommandPublisherTest#publishBatch_submitsOneWindowBeforeWaitingAndKeepsInputOrder test
```

Expected: 断言 `submittedFullWindow` 失败；旧实现只提交第一条后就等待 ACK。

- [ ] **Step 3: 实现最小窗口发送逻辑**

在 `ProtocolCommandPublisher`：

1. 移除同步 `.get` 所需的 `ExecutionException`、`TimeoutException` imports。
2. 增加 `Collections`、`CompletableFuture`、`CompletionException` imports，保留 `TimeUnit` 给 `orTimeout`。
3. 把 `publishBatch` 发送阶段替换为以下结构：

```java
public List<ProtocolCommandPublishOutcome> publishBatch(List<ProtocolCommandOutbox> rows) {
    if (rows == null || rows.isEmpty()) {
        return List.of();
    }
    PreparedEnvelopes prepared = prepareEnvelopes(rows);
    List<ProtocolCommandPublishOutcome> outcomes =
            new ArrayList<>(Collections.nCopies(rows.size(), null));
    List<PreparedPublish> sendable = new ArrayList<>();
    for (int index = 0; index < rows.size(); index++) {
        ProtocolCommandOutbox row = rows.get(index);
        RuntimeException prepareFailure = prepared.failures().get(commandKey(row));
        if (prepareFailure != null) {
            outcomes.set(index, ProtocolCommandPublishOutcome.failure(row, prepareFailure));
            continue;
        }
        ProtocolCommandEnvelope envelope = prepared.envelopes().get(commandKey(row));
        if (envelope == null) {
            outcomes.set(index, ProtocolCommandPublishOutcome.failure(row,
                    validation("协议命令 envelope 缺失: " + safeCommandId(row))));
            continue;
        }
        sendable.add(new PreparedPublish(index, row, envelope));
    }
    for (int start = 0; start < sendable.size(); start += properties.getMaxInFlight()) {
        int end = Math.min(start + properties.getMaxInFlight(), sendable.size());
        publishWindow(sendable.subList(start, end), outcomes);
    }
    return List.copyOf(outcomes);
}
```

增加窗口发送和异步结果转换：

```java
private void publishWindow(List<PreparedPublish> window,
                           List<ProtocolCommandPublishOutcome> outcomes) {
    List<PendingPublish> pending = new ArrayList<>(window.size());
    for (PreparedPublish prepared : window) {
        pending.add(new PendingPublish(
                prepared.index(),
                sendAsync(prepared.row(), prepared.envelope())));
    }
    CompletableFuture.allOf(pending.stream()
                    .map(PendingPublish::outcome)
                    .toArray(CompletableFuture[]::new))
            .join();
    for (PendingPublish publish : pending) {
        outcomes.set(publish.index(), publish.outcome().join());
    }
}

private CompletableFuture<ProtocolCommandPublishOutcome> sendAsync(
        ProtocolCommandOutbox row,
        ProtocolCommandEnvelope envelope) {
    try {
        return kafkaTemplate.send(row.getKafkaTopic(), row.getKafkaKey(), envelope)
                .orTimeout(properties.getSendTimeoutMs(), TimeUnit.MILLISECONDS)
                .handle((ignored, error) -> {
                    if (error != null) {
                        return ProtocolCommandPublishOutcome.failure(
                                row, kafkaFailure(row, unwrapCompletion(error)));
                    }
                    log.debug("协议命令 Kafka 发送成功 commandId={} batchId={} accountId={} "
                                    + "protocolAccountId={} topic={}",
                            row.getCommandId(), row.getBatchId(), row.getAggregateId(),
                            row.getProtocolAccountId(), row.getKafkaTopic());
                    return ProtocolCommandPublishOutcome.success(row,
                            new ProtocolCommandPublishResult(
                                    row.getCommandId(), row.getKafkaTopic(), row.getKafkaKey()));
                });
    } catch (RuntimeException ex) {
        return CompletableFuture.completedFuture(
                ProtocolCommandPublishOutcome.failure(row, kafkaFailure(row, ex)));
    }
}

private static Throwable unwrapCompletion(Throwable error) {
    if (error instanceof CompletionException && error.getCause() != null) {
        return error.getCause();
    }
    return error;
}
```

把 `kafkaFailure` 的第二个参数从 `Exception` 扩为 `Throwable`，删除旧同步 `send` 方法，并增加：

```java
private ProtocolException kafkaFailure(ProtocolCommandOutbox row, Throwable error) {
    return ProtocolException.unknown(
            "协议命令 Kafka 发送失败 commandId=" + row.getCommandId(), error);
}

private record PreparedPublish(
        int index,
        ProtocolCommandOutbox row,
        ProtocolCommandEnvelope envelope
) {
}

private record PendingPublish(
        int index,
        CompletableFuture<ProtocolCommandPublishOutcome> outcome
) {
}
```

同步更新 `publishBatch` 和 `ProtocolCommandPublishOutcome` Javadoc：先批量补全，再按有界窗口异步发送，结果与 rows
顺序一致。不要保留“逐条发送”的旧注释。

- [ ] **Step 4: 运行 Publisher 测试并确认 GREEN**

Run:

```bash
cd armada-api
mvn -Dtest=ProtocolCommandPublisherTest test
```

Expected: `ProtocolCommandPublisherTest` 全部通过，BUILD SUCCESS。

- [ ] **Step 5: 运行 Publisher + Dispatcher 回归**

Run:

```bash
cd armada-api
mvn -Dtest='ProtocolCommandPublisherPropertiesTest,ProtocolCommandPublisherTest,ProtocolCommandDispatcherTest' test
```

Expected: 三个测试类全部通过，0 failure，0 error，BUILD SUCCESS。

- [ ] **Step 6: 提交发送窗口切片**

```bash
git add armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java \
  armada-api/src/main/java/com/armada/platform/protocol/model/result/ProtocolCommandPublishOutcome.java \
  armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java
git commit -m "perf: publish protocol commands with bounded async window"
```

### Task 3: 全量验证与变更记录

**Files:**
- Modify: `.harness/changes/2026-07-17-protocol-command-publisher-window.md`

- [ ] **Step 1: 检查任务 diff 未混入其它会话文件**

Run:

```bash
git status --short
git diff --check
git diff HEAD~2 -- armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolCommandPublisherProperties.java \
  armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java \
  armada-api/src/main/java/com/armada/platform/protocol/model/result/ProtocolCommandPublishOutcome.java \
  armada-api/src/main/resources/application.yml \
  armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolCommandPublisherPropertiesTest.java \
  armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java
```

Expected: `git diff --check` 无输出；只审阅本任务文件，工作区原有部署脚本和其它文档改动保持未暂存、未修改。

- [ ] **Step 2: 运行完整 Maven 测试**

Run:

```bash
cd armada-api
mvn test
```

Expected: BUILD SUCCESS。若本机 MySQL/Flyway 环境导致既有数据库集成测试失败，记录退出码、已执行测试数和共同根因，不能声称全量通过。

- [ ] **Step 3: 更新变更记录**

在 `.harness/changes/2026-07-17-protocol-command-publisher-window.md`：

- 把实施和验证任务勾选；
- 状态改为“实现完成，未部署”；
- 写入每条实际执行命令、测试数、失败/跳过数和 BUILD 结果；
- 明确没有 DB、API、Redis 或跨仓变更；
- 保留“默认 100 需在测试环境观察”的跟进项。

- [ ] **Step 4: 提交验证记录**

```bash
git add .harness/changes/2026-07-17-protocol-command-publisher-window.md
git commit -m "docs: record protocol command publish window verification"
```

- [ ] **Step 5: 最终证据复核**

Run:

```bash
git status --short
git log -4 --oneline
```

Expected: 本任务三个实施提交均存在；剩余状态只包含任务开始前已有的工作区改动。最终交付说明必须区分聚焦测试、全量测试和未执行的远程压测。
