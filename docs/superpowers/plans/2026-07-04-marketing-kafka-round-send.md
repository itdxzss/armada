# Marketing Kafka Round Send Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build periodic Kafka-based marketing sends where each active marketing task sends one message to every selected group each round, then schedules the next round by `send_interval_seconds`.

**Architecture:** Armada API owns task scheduling, attempt records, and protocol command outbox rows. `armada-protocol` consumes the new `message.send.requested` command, calls Baileys `sendMessage`, then publishes `message.send_result_reported`; Armada consumes that event and idempotently updates attempt/task counters. First release uses the existing `protocol.master.commands.v1` command topic and one message event consumer, with a 5-thread marketing round executor and backlog protection.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis XML, Flyway, Kafka, JUnit/Mockito/DbTest, TypeScript, Jest, Baileys.

---

## File Structure

Armada API:

- Create `armada-api/src/main/resources/db/migration/V038__marketing_kafka_round_send.sql`: add round scheduling and attempt result columns.
- Modify `armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTask.java`: add `currentRoundNo`, `nextRoundAt`, `lastRoundStartedAt`.
- Create `armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTaskSendAttempt.java`: entity for `marketing_task_send_attempt`.
- Create `armada-api/src/main/java/com/armada/marketing/model/enums/MarketingSendAttemptStatus.java`: submitted/success/failed/skipped status constants.
- Modify `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java` and `mapper/marketing/MarketingTaskMapper.xml`: scheduling, target selection, attempt insertion, result updates.
- Create `armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolMarketingMessageCommandRequest.java`: outbox request for message sends.
- Modify `armada-api/src/main/java/com/armada/platform/protocol/service/ProtocolCommandOutboxService.java` and `impl/ProtocolCommandOutboxServiceImpl.java`: enqueue `message.send.requested` rows.
- Create `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundSchedulerProperties.java`: bind scheduler settings.
- Create `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundScheduler.java`: scan due tasks and submit work to a fixed pool.
- Create `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`: claim a round, create attempts, write outbox commands.
- Create `armada-api/src/main/java/com/armada/marketing/service/MarketingMessageComposer.java`: turn template/file into text/image/link command payloads.
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`: initialize `nextRoundAt` on create/start.
- Create `armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolMessageEventConsumerProperties.java`: bind message event topic/group.
- Modify `armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolKafkaConfiguration.java`: enable the new properties.
- Create `armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageEventConsumer.java`: parse `message.send_result_reported`.
- Create `armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageSendResultReportedEvent.java`: parsed event record.
- Create `armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageSendResultReportedSink.java`: platform-to-marketing sink interface.
- Create `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java`: handle result events and update attempts/tasks.

Armada protocol:

- Modify `armada-protocol/protocol-layer/src/commands/types.ts`: add `message.send.requested`.
- Modify `armada-protocol/protocol-layer/src/commands/worker-consumer.ts`: execute message send commands.
- Modify `armada-protocol/protocol-layer/src/events/subjects.ts`: add `message.send_result_reported`.
- Modify `armada-protocol/protocol-layer/src/commands/types.test.ts`: parser coverage.
- Modify `armada-protocol/protocol-layer/src/commands/worker-consumer.test.ts`: worker send/result coverage.

## Task 1: Schema and Java Entities

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V038__marketing_kafka_round_send.sql`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTask.java`
- Create: `armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTaskSendAttempt.java`
- Create: `armada-api/src/main/java/com/armada/marketing/model/enums/MarketingSendAttemptStatus.java`
- Test: `armada-api/src/test/java/com/armada/marketing/MarketingKafkaRoundSendMigrationDbTest.java`

- [ ] **Step 1: Write the failing migration DbTest**

Create `armada-api/src/test/java/com/armada/marketing/MarketingKafkaRoundSendMigrationDbTest.java`:

```java
package com.armada.marketing;

import com.armada.test.DbTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingKafkaRoundSendMigrationDbTest extends DbTestBase {

    @Test
    void marketingTaskHasRoundSchedulerColumns() {
        assertThat(columnType("marketing_task", "current_round_no")).isEqualTo("bigint");
        assertThat(columnType("marketing_task", "next_round_at")).isEqualTo("bigint");
        assertThat(columnType("marketing_task", "last_round_started_at")).isEqualTo("bigint");
    }

    @Test
    void marketingAttemptHasRoundAndProtocolResultColumns() {
        assertThat(columnType("marketing_task_send_attempt", "round_no")).isEqualTo("bigint");
        assertThat(columnType("marketing_task_send_attempt", "command_id")).isEqualTo("varchar");
        assertThat(columnType("marketing_task_send_attempt", "message_id")).isEqualTo("varchar");
        assertThat(columnType("marketing_task_send_attempt", "submitted_at")).isEqualTo("bigint");
        assertThat(columnType("marketing_task_send_attempt", "result_at")).isEqualTo("bigint");
        assertThat(indexExists("marketing_task_send_attempt", "uq_marketing_task_attempt_round")).isTrue();
    }
}
```

- [ ] **Step 2: Run the DbTest and verify it fails**

Run from `armada/armada-api`:

```bash
mvn -q -Dtest=MarketingKafkaRoundSendMigrationDbTest test
```

Expected: FAIL because `round_no`, `current_round_no`, and `uq_marketing_task_attempt_round` do not exist.

- [ ] **Step 3: Add the Flyway migration**

Create `armada-api/src/main/resources/db/migration/V038__marketing_kafka_round_send.sql`:

```sql
ALTER TABLE marketing_task
    ADD COLUMN current_round_no BIGINT NOT NULL DEFAULT 0
        COMMENT '营销轮次序号;每成功抢占一轮递增1' AFTER retry_limit,
    ADD COLUMN next_round_at BIGINT DEFAULT NULL
        COMMENT '下一轮应生成时间(epoch毫秒)' AFTER started_at,
    ADD COLUMN last_round_started_at BIGINT DEFAULT NULL
        COMMENT '最近一轮生成开始时间(epoch毫秒)' AFTER next_round_at;

ALTER TABLE marketing_task_send_attempt
    ADD COLUMN round_no BIGINT NOT NULL DEFAULT 0
        COMMENT '所属营销轮次;从1开始' AFTER target_id,
    ADD COLUMN command_id VARCHAR(64) DEFAULT NULL
        COMMENT '协议命令ID;用于排查Kafka投递' AFTER is_retry,
    ADD COLUMN message_id VARCHAR(128) DEFAULT NULL
        COMMENT '协议层返回的WhatsApp消息ID' AFTER reason_message,
    ADD COLUMN submitted_at BIGINT DEFAULT NULL
        COMMENT '写入协议outbox时间(epoch毫秒)' AFTER message_id,
    ADD COLUMN result_at BIGINT DEFAULT NULL
        COMMENT '协议层发送结果回写时间(epoch毫秒)' AFTER submitted_at,
    MODIFY COLUMN status TINYINT NOT NULL
        COMMENT '尝试状态:0=已提交 1=成功 2=失败 3=跳过';

ALTER TABLE marketing_task_send_attempt
    DROP INDEX uq_marketing_task_attempt_no,
    ADD UNIQUE KEY uq_marketing_task_attempt_round (tenant_id, target_id, round_no),
    ADD KEY idx_marketing_task_attempt_command (tenant_id, command_id);
```

- [ ] **Step 4: Add Java fields and attempt entity**

In `MarketingTask.java`, add fields with getters/setters:

```java
private Long currentRoundNo;
private Long nextRoundAt;
private Long lastRoundStartedAt;

public Long getCurrentRoundNo() {
    return currentRoundNo;
}

public void setCurrentRoundNo(Long currentRoundNo) {
    this.currentRoundNo = currentRoundNo;
}

public Long getNextRoundAt() {
    return nextRoundAt;
}

public void setNextRoundAt(Long nextRoundAt) {
    this.nextRoundAt = nextRoundAt;
}

public Long getLastRoundStartedAt() {
    return lastRoundStartedAt;
}

public void setLastRoundStartedAt(Long lastRoundStartedAt) {
    this.lastRoundStartedAt = lastRoundStartedAt;
}
```

Create `MarketingSendAttemptStatus.java`:

```java
package com.armada.marketing.model.enums;

public enum MarketingSendAttemptStatus {
    SUBMITTED(0),
    SUCCESS(1),
    FAILED(2),
    SKIPPED(3);

    private final int code;

    MarketingSendAttemptStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
```

Create `MarketingTaskSendAttempt.java`:

```java
package com.armada.marketing.model.entity;

public class MarketingTaskSendAttempt {
    private Long id;
    private Long tenantId;
    private Long marketingTaskId;
    private Long targetId;
    private Long roundNo;
    private Integer attemptNo;
    private Boolean retry;
    private String commandId;
    private Integer status;
    private String reasonCode;
    private String reasonMessage;
    private String messageId;
    private Long submittedAt;
    private Long resultAt;
    private Long attemptedAt;
    private Long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getMarketingTaskId() { return marketingTaskId; }
    public void setMarketingTaskId(Long marketingTaskId) { this.marketingTaskId = marketingTaskId; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public Long getRoundNo() { return roundNo; }
    public void setRoundNo(Long roundNo) { this.roundNo = roundNo; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public Boolean getRetry() { return retry; }
    public void setRetry(Boolean retry) { this.retry = retry; }
    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getReasonMessage() { return reasonMessage; }
    public void setReasonMessage(String reasonMessage) { this.reasonMessage = reasonMessage; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public Long getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Long submittedAt) { this.submittedAt = submittedAt; }
    public Long getResultAt() { return resultAt; }
    public void setResultAt(Long resultAt) { this.resultAt = resultAt; }
    public Long getAttemptedAt() { return attemptedAt; }
    public void setAttemptedAt(Long attemptedAt) { this.attemptedAt = attemptedAt; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 5: Run the migration DbTest and commit**

Run:

```bash
mvn -q -Dtest=MarketingKafkaRoundSendMigrationDbTest test
```

Expected: PASS.

Commit:

```bash
git add armada-api/src/main/resources/db/migration/V038__marketing_kafka_round_send.sql armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTask.java armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTaskSendAttempt.java armada-api/src/main/java/com/armada/marketing/model/enums/MarketingSendAttemptStatus.java armada-api/src/test/java/com/armada/marketing/MarketingKafkaRoundSendMigrationDbTest.java
git commit -m "feat(marketing): add round send schema"
```

## Task 2: Marketing Round Mapper Primitives

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Test: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingRoundMapperDbTest.java`

- [ ] **Step 1: Write failing mapper tests**

Create `MarketingRoundMapperDbTest.java` with three tests:

```java
package com.armada.marketing.mapper;

import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.test.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingRoundMapperDbTest extends DbTestBase {

    @Autowired
    private MarketingTaskMapper mapper;

    @Test
    void selectDueSendingTasksOnlyReturnsDueSendingRows() {
        long now = System.currentTimeMillis();
        Long due = insertTask("due", 2, now - 1_000);
        insertTask("future", 2, now + 60_000);
        insertTask("stopped", 5, now - 1_000);

        List<MarketingTask> rows = mapper.selectDueSendingTasks(now, 10);

        assertThat(rows).extracting(MarketingTask::getId).contains(due);
    }

    @Test
    void claimDueRoundMovesNextRoundAndIncrementsRound() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("claim", 2, now - 1_000);

        int claimed = mapper.claimDueRound(taskId, now, now + 30_000);
        MarketingTask after = mapper.selectTaskById(taskId);

        assertThat(claimed).isEqualTo(1);
        assertThat(after.getCurrentRoundNo()).isEqualTo(1L);
        assertThat(after.getNextRoundAt()).isEqualTo(now + 30_000);
        assertThat(after.getLastRoundStartedAt()).isEqualTo(now);
    }

    @Test
    void insertAttemptsAndApplyResultAreIdempotentByAttemptId() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("attempt", 2, now - 1_000);
        Long targetId = insertTarget(taskId, "120363001@g.us");
        MarketingTaskSendAttempt attempt = new MarketingTaskSendAttempt();
        attempt.setMarketingTaskId(taskId);
        attempt.setTargetId(targetId);
        attempt.setRoundNo(1L);
        attempt.setAttemptNo(1);
        attempt.setRetry(false);
        attempt.setStatus(MarketingSendAttemptStatus.SUBMITTED.code());
        attempt.setCommandId("cmd_attempt_1");
        attempt.setSubmittedAt(now);
        attempt.setAttemptedAt(now);
        attempt.setCreatedAt(now);

        mapper.insertSendAttempts(List.of(attempt));
        int first = mapper.markAttemptSuccess(attempt.getId(), "wamid.1", now + 10);
        int second = mapper.markAttemptSuccess(attempt.getId(), "wamid.1", now + 20);

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);
    }
}
```

Use local helper inserts in the test class that create minimal `marketing_task` and `marketing_task_target` rows through `jdbc.update(...)`, matching required columns from `V014`.

- [ ] **Step 2: Run mapper tests and verify failure**

Run:

```bash
mvn -q -Dtest=MarketingRoundMapperDbTest test
```

Expected: FAIL because mapper methods do not exist.

- [ ] **Step 3: Add mapper methods**

Add to `MarketingTaskMapper.java`:

```java
List<MarketingTask> selectDueSendingTasks(@Param("now") long now, @Param("limit") int limit);

int claimDueRound(@Param("id") Long id, @Param("now") long now, @Param("nextRoundAt") long nextRoundAt);

long countUnfinishedAttempts(@Param("taskId") Long taskId);

int insertSendAttempts(@Param("attempts") List<MarketingTaskSendAttempt> attempts);

int markAttemptSuccess(@Param("attemptId") Long attemptId,
                       @Param("messageId") String messageId,
                       @Param("resultAt") long resultAt);

int markAttemptFailed(@Param("attemptId") Long attemptId,
                      @Param("reasonCode") String reasonCode,
                      @Param("reasonMessage") String reasonMessage,
                      @Param("resultAt") long resultAt);

int incrementTaskSendCounters(@Param("taskId") Long taskId,
                              @Param("successDelta") int successDelta,
                              @Param("failedDelta") int failedDelta,
                              @Param("now") long now);
```

Import `MarketingTaskSendAttempt`.

- [ ] **Step 4: Add XML mappings**

Update `MarketingTaskMapper.xml`:

```xml
<select id="selectDueSendingTasks" resultMap="MarketingTaskResultMap">
    SELECT <include refid="TaskColumns"/>
    FROM marketing_task
    WHERE deleted_at IS NULL
      AND status = 2
      AND next_round_at IS NOT NULL
      AND next_round_at &lt;= #{now}
    ORDER BY next_round_at ASC, id ASC
    LIMIT #{limit}
</select>

<update id="claimDueRound">
    UPDATE marketing_task
    SET current_round_no = current_round_no + 1,
        last_round_started_at = #{now},
        next_round_at = #{nextRoundAt},
        updated_at = #{now}
    WHERE id = #{id}
      AND deleted_at IS NULL
      AND status = 2
      AND next_round_at IS NOT NULL
      AND next_round_at &lt;= #{now}
</update>

<select id="countUnfinishedAttempts" resultType="long">
    SELECT COUNT(*)
    FROM marketing_task_send_attempt
    WHERE marketing_task_id = #{taskId}
      AND status = 0
</select>

<insert id="insertSendAttempts" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO marketing_task_send_attempt
        (marketing_task_id, target_id, round_no, attempt_no, is_retry, command_id,
         status, reason_code, reason_message, message_id, submitted_at, result_at,
         attempted_at, created_at)
    VALUES
    <foreach collection="attempts" item="a" separator=",">
        (#{a.marketingTaskId}, #{a.targetId}, #{a.roundNo}, #{a.attemptNo}, #{a.retry},
         #{a.commandId}, #{a.status}, #{a.reasonCode}, #{a.reasonMessage}, #{a.messageId},
         #{a.submittedAt}, #{a.resultAt}, #{a.attemptedAt}, #{a.createdAt})
    </foreach>
</insert>

<update id="markAttemptSuccess">
    UPDATE marketing_task_send_attempt
    SET status = 1,
        message_id = #{messageId},
        result_at = #{resultAt}
    WHERE id = #{attemptId}
      AND status = 0
</update>

<update id="markAttemptFailed">
    UPDATE marketing_task_send_attempt
    SET status = 2,
        reason_code = #{reasonCode},
        reason_message = #{reasonMessage},
        result_at = #{resultAt}
    WHERE id = #{attemptId}
      AND status = 0
</update>

<update id="incrementTaskSendCounters">
    UPDATE marketing_task
    SET sent_message_count = sent_message_count + #{successDelta},
        failed_message_count = failed_message_count + #{failedDelta},
        last_sent_at = CASE WHEN #{successDelta} &gt; 0 THEN #{now} ELSE last_sent_at END,
        updated_at = #{now}
    WHERE id = #{taskId}
      AND deleted_at IS NULL
</update>
```

Add `current_round_no`, `next_round_at`, and `last_round_started_at` to `TaskColumns` and result map.

- [ ] **Step 5: Run tests and commit**

Run:

```bash
mvn -q -Dtest=MarketingRoundMapperDbTest test
```

Expected: PASS.

Commit:

```bash
git add armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml armada-api/src/test/java/com/armada/marketing/mapper/MarketingRoundMapperDbTest.java
git commit -m "feat(marketing): add round mapper primitives"
```

## Task 3: Message Send Outbox Command

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolMarketingMessageCommandRequest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/service/ProtocolCommandOutboxService.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/platform/protocol/service/ProtocolMarketingMessageOutboxServiceTest.java`

- [ ] **Step 1: Write failing service test**

Create `ProtocolMarketingMessageOutboxServiceTest.java` using Mockito for mapper and trigger:

```java
package com.armada.platform.protocol.service;

import com.armada.platform.kafka.config.ProtocolAccountCommandProperties;
import com.armada.platform.kafka.config.ProtocolMasterCommandProperties;
import com.armada.platform.kafka.dispatch.ProtocolCommandDispatchTrigger;
import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.model.command.ProtocolMarketingMessageCommandRequest;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.service.impl.ProtocolCommandOutboxServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolMarketingMessageOutboxServiceTest {

    @Test
    void enqueueMarketingMessageCommandsWritesMasterTopicRows() {
        ProtocolCommandOutboxMapper mapper = mock(ProtocolCommandOutboxMapper.class);
        ProtocolCommandDispatchTrigger trigger = mock(ProtocolCommandDispatchTrigger.class);
        when(mapper.batchInsertPending(any())).thenReturn(1);
        ProtocolMasterCommandProperties master = new ProtocolMasterCommandProperties();
        master.setTopic("protocol.master.commands.v1");

        ProtocolCommandOutboxServiceImpl service = new ProtocolCommandOutboxServiceImpl(
                mapper, new ObjectMapper(), trigger,
                new ProtocolAccountCommandProperties(), master);

        service.enqueueMarketingMessageCommands(List.of(new ProtocolMarketingMessageCommandRequest(
                1L, 101L, 9001L, 7001L, 1L, 501L,
                "acc_8613800138000", "120363001@g.us", "TEXT",
                "hello", null, null, "marketing_task")));

        ArgumentCaptor<List<ProtocolCommandOutbox>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).batchInsertPending(captor.capture());
        ProtocolCommandOutbox row = captor.getValue().get(0);
        assertThat(row.getCommandType()).isEqualTo("message.send.requested");
        assertThat(row.getAggregateType()).isEqualTo("MARKETING_SEND_ATTEMPT");
        assertThat(row.getAggregateId()).isEqualTo(9001L);
        assertThat(row.getKafkaTopic()).isEqualTo("protocol.master.commands.v1");
        assertThat(row.getKafkaKey()).isEqualTo("acc_8613800138000");
        assertThat(row.getProtocolAccountId()).isEqualTo("acc_8613800138000");
        assertThat(row.getPayloadJson()).contains("\"attemptId\":9001");
        verify(trigger).dispatchAfterCommit(captor.getValue());
    }
}
```

- [ ] **Step 2: Run the test and verify failure**

Run:

```bash
mvn -q -Dtest=ProtocolMarketingMessageOutboxServiceTest test
```

Expected: FAIL because `ProtocolMarketingMessageCommandRequest` and `enqueueMarketingMessageCommands` do not exist.

- [ ] **Step 3: Add command request record and service method**

Create `ProtocolMarketingMessageCommandRequest.java`:

```java
package com.armada.platform.protocol.model.command;

public record ProtocolMarketingMessageCommandRequest(
        Long tenantId,
        Long marketingTaskId,
        Long attemptId,
        Long targetId,
        Long roundNo,
        Long accountId,
        String protocolAccountId,
        String groupJid,
        String messageType,
        String text,
        String imageBase64,
        String imageMimetype,
        String source
) {
}
```

Add to `ProtocolCommandOutboxService`:

```java
ProtocolCommandOutboxEnqueueResult enqueueMarketingMessageCommands(
        List<ProtocolMarketingMessageCommandRequest> commands);
```

- [ ] **Step 4: Implement outbox row conversion**

In `ProtocolCommandOutboxServiceImpl`, add constants:

```java
public static final String COMMAND_TYPE_MESSAGE_SEND_REQUESTED = "message.send.requested";
public static final String AGGREGATE_TYPE_MARKETING_SEND_ATTEMPT = "MARKETING_SEND_ATTEMPT";
```

Add method:

```java
@Override
@Transactional(rollbackFor = Exception.class)
public ProtocolCommandOutboxEnqueueResult enqueueMarketingMessageCommands(
        List<ProtocolMarketingMessageCommandRequest> commands) {
    validateMarketingMessageCommands(commands);
    String batchId = commands.size() == 1 ? null : newBatchId();
    long now = System.currentTimeMillis();
    List<String> commandIds = new ArrayList<>(commands.size());
    List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
    Set<String> uniqueCommandIds = new HashSet<>(commands.size());
    for (ProtocolMarketingMessageCommandRequest command : commands) {
        String commandId = newCommandId();
        if (!uniqueCommandIds.add(commandId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
        }
        commandIds.add(commandId);
        rows.add(toMarketingMessageOutboxRow(command, commandId, batchId, now));
    }
    return insertPendingRows(batchId, commandIds, rows);
}
```

Add converter:

```java
private ProtocolCommandOutbox toMarketingMessageOutboxRow(
        ProtocolMarketingMessageCommandRequest command,
        String commandId,
        String batchId,
        long now) {
    ProtocolCommandOutbox row = new ProtocolCommandOutbox();
    row.setCommandId(commandId);
    row.setBatchId(batchId);
    row.setCommandType(COMMAND_TYPE_MESSAGE_SEND_REQUESTED);
    row.setAggregateType(AGGREGATE_TYPE_MARKETING_SEND_ATTEMPT);
    row.setAggregateId(command.attemptId());
    row.setKafkaTopic(masterCommandProperties.getTopic());
    row.setKafkaKey(command.protocolAccountId());
    row.setProtocolAccountId(command.protocolAccountId());
    row.setPayloadJson(toPayloadJson(new MarketingMessagePayload(
            command.tenantId(), command.marketingTaskId(), command.attemptId(), command.targetId(),
            command.roundNo(), command.accountId(), command.protocolAccountId(), command.groupJid(),
            command.messageType(), command.text(),
            command.imageBase64() == null ? null : new MarketingImagePayload(command.imageBase64(), command.imageMimetype()),
            textOrDefault(command.source(), "marketing_task"))));
    row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
    row.setRetryCount(0);
    row.setNextRetryAt(IMMEDIATE_RETRY_AT);
    row.setCreatedAt(now);
    row.setUpdatedAt(now);
    return row;
}
```

Add helper records inside the service:

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
private record MarketingMessagePayload(
        Long tenantId,
        Long marketingTaskId,
        Long attemptId,
        Long targetId,
        Long roundNo,
        Long accountId,
        String protocolAccountId,
        String groupJid,
        String messageType,
        String text,
        MarketingImagePayload image,
        String source
) {
}

private record MarketingImagePayload(String base64, String mimetype) {
}
```

Add validation:

```java
private static void validateMarketingMessageCommands(List<ProtocolMarketingMessageCommandRequest> commands) {
    validateCommandBatchSize(commands, "营销消息发送命令");
    for (ProtocolMarketingMessageCommandRequest command : commands) {
        if (command == null
                || command.tenantId() == null
                || command.marketingTaskId() == null
                || command.attemptId() == null
                || command.targetId() == null
                || command.roundNo() == null
                || command.accountId() == null
                || !StringUtils.hasText(command.protocolAccountId())
                || !StringUtils.hasText(command.groupJid())
                || !StringUtils.hasText(command.messageType())) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销消息发送命令缺少必要字段");
        }
    }
}
```

Use the existing service's batch-size helper if present; otherwise add a private helper that enforces `commands != null`, non-empty, and `size <= MAX_COMMANDS_PER_BATCH`.

- [ ] **Step 5: Run test and commit**

Run:

```bash
mvn -q -Dtest=ProtocolMarketingMessageOutboxServiceTest test
```

Expected: PASS.

Commit:

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolMarketingMessageCommandRequest.java armada-api/src/main/java/com/armada/platform/protocol/service/ProtocolCommandOutboxService.java armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java armada-api/src/test/java/com/armada/platform/protocol/service/ProtocolMarketingMessageOutboxServiceTest.java
git commit -m "feat(protocol): enqueue marketing message commands"
```

## Task 4: Marketing Message Composer

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/service/MarketingMessageComposer.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingMessageComposerTest.java`

- [ ] **Step 1: Write failing composer tests**

Create tests:

```java
package com.armada.marketing.service;

import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingMessageComposerTest {

    private final MarketingMessageComposer composer = new MarketingMessageComposer();

    @Test
    void normalLinkComposesLinkText() {
        MarketingTemplate template = template(LinkMode.NORMAL.code(), null);
        template.setContent("标题");
        template.setBodyText("正文");
        template.setPromotionLink("https://example.com");

        MarketingMessageComposer.ComposedMessage message = composer.compose(template, null);

        assertThat(message.messageType()).isEqualTo("LINK");
        assertThat(message.text()).contains("标题", "正文", "https://example.com");
    }

    @Test
    void imageTextWithFileComposesImagePayload() {
        MarketingTemplate template = template(LinkMode.IMAGE_TEXT.code(), 99L);
        template.setContent("标题");
        template.setBodyText("正文");
        MarketingTemplateFile file = new MarketingTemplateFile();
        file.setContent(new byte[] {1, 2, 3});
        file.setContentType("image/png");

        MarketingMessageComposer.ComposedMessage message = composer.compose(template, file);

        assertThat(message.messageType()).isEqualTo("IMAGE");
        assertThat(message.text()).contains("标题", "正文");
        assertThat(message.imageBytes()).containsExactly(1, 2, 3);
        assertThat(message.imageMimetype()).isEqualTo("image/png");
    }

    @Test
    void buttonModeFallsBackToText() {
        MarketingTemplate template = template(LinkMode.BUTTON.code(), null);
        template.setContent("按钮标题");
        template.setBodyText("按钮正文");

        MarketingMessageComposer.ComposedMessage message = composer.compose(template, null);

        assertThat(message.messageType()).isEqualTo("TEXT");
        assertThat(message.text()).contains("按钮标题", "按钮正文");
    }

    private static MarketingTemplate template(Integer linkMode, Long imageFileId) {
        MarketingTemplate template = new MarketingTemplate();
        template.setId(10L);
        template.setTemplateName("template");
        template.setLinkMode(linkMode);
        template.setImageFileId(imageFileId);
        return template;
    }
}
```

- [ ] **Step 2: Run composer tests and verify failure**

Run:

```bash
mvn -q -Dtest=MarketingMessageComposerTest test
```

Expected: FAIL because `MarketingMessageComposer` does not exist.

- [ ] **Step 3: Implement composer**

Create:

```java
package com.armada.marketing.service;

import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MarketingMessageComposer {

    public ComposedMessage compose(MarketingTemplate template, MarketingTemplateFile imageFile) {
        if (template == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销模板不能为空");
        }
        LinkMode mode = LinkMode.fromCode(template.getLinkMode());
        String text = composeText(template);
        if (mode == LinkMode.IMAGE_TEXT && imageFile != null && imageFile.getContent() != null
                && imageFile.getContent().length > 0) {
            return new ComposedMessage("IMAGE", text, imageFile.getContent(), imageFile.getContentType());
        }
        if (mode == LinkMode.NORMAL && StringUtils.hasText(template.getPromotionLink())) {
            return new ComposedMessage("LINK", text, null, null);
        }
        return new ComposedMessage("TEXT", text, null, null);
    }

    private static String composeText(MarketingTemplate template) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, template.getContent());
        appendLine(sb, template.getBodyText());
        appendLine(sb, template.getPromotionLink());
        String text = sb.toString().trim();
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销模板发送内容为空");
        }
        if (text.length() > 4096) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销模板发送内容超过4096字符");
        }
        return text;
    }

    private static void appendLine(StringBuilder sb, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(value.trim());
    }

    public record ComposedMessage(String messageType, String text, byte[] imageBytes, String imageMimetype) {
    }
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
mvn -q -Dtest=MarketingMessageComposerTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add armada-api/src/main/java/com/armada/marketing/service/MarketingMessageComposer.java armada-api/src/test/java/com/armada/marketing/service/MarketingMessageComposerTest.java
git commit -m "feat(marketing): compose protocol message payloads"
```

## Task 5: Round Scheduler and Worker

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundSchedulerProperties.java`
- Create: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundScheduler.java`
- Create: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundSchedulerTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`

- [ ] **Step 1: Write scheduler test**

Create `MarketingRoundSchedulerTest.java`:

```java
package com.armada.marketing.scheduler;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.MarketingTask;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingRoundSchedulerTest {

    @Test
    void scanSubmitsDueTasksToWorker() {
        MarketingTaskMapper mapper = mock(MarketingTaskMapper.class);
        MarketingRoundWorker worker = mock(MarketingRoundWorker.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setEnabled(true);
        properties.setExecutorPoolSize(5);
        properties.setScanLimit(20);

        MarketingTask task = new MarketingTask();
        task.setTenantId(1L);
        task.setId(42L);
        when(mapper.selectDueSendingTasks(anyLong(), eq(20))).thenReturn(List.of(task));

        MarketingRoundScheduler scheduler = new MarketingRoundScheduler(mapper, worker, properties);
        scheduler.scanDueTasks();
        scheduler.shutdown();

        verify(worker, timeout(1_000)).runRound(1L, 42L);
    }
}
```

- [ ] **Step 2: Write worker test**

Create `MarketingRoundWorkerTest.java`:

```java
package com.armada.marketing.scheduler;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingRoundWorkerTest {

    @Test
    void backlogAtThresholdPostponesRoundWithoutOutbox() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);

        MarketingTask task = task();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(2000L);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets(1000));

        MarketingRoundWorker worker = worker(taskMapper, outbox, properties);
        worker.runRound(1L, 42L);

        verify(taskMapper, never()).claimDueRound(any(), anyLong(), anyLong());
        verify(outbox, never()).enqueueMarketingMessageCommands(any());
    }
}
```

Include helper methods in the test to build a `MarketingTask` with `status=2`, `sendIntervalSeconds=30`, `currentRoundNo=0`, `marketingTemplateId=77`, and 1000 `MarketingTaskTarget` rows.

- [ ] **Step 3: Run scheduler tests and verify failure**

Run:

```bash
mvn -q -Dtest=MarketingRoundSchedulerTest,MarketingRoundWorkerTest test
```

Expected: FAIL because scheduler classes do not exist.

- [ ] **Step 4: Implement scheduler properties and scheduler**

Create `MarketingRoundSchedulerProperties.java`:

```java
package com.armada.marketing.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "armada.marketing.round-scheduler")
public class MarketingRoundSchedulerProperties {
    private boolean enabled = true;
    private long scanFixedDelayMs = 1000;
    private int executorPoolSize = 5;
    private int scanLimit = 20;
    private int outboxBatchSize = 500;
    private int backlogMultiplier = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getScanFixedDelayMs() { return scanFixedDelayMs; }
    public void setScanFixedDelayMs(long scanFixedDelayMs) { this.scanFixedDelayMs = scanFixedDelayMs; }
    public int getExecutorPoolSize() { return executorPoolSize; }
    public void setExecutorPoolSize(int executorPoolSize) { this.executorPoolSize = executorPoolSize; }
    public int getScanLimit() { return scanLimit; }
    public void setScanLimit(int scanLimit) { this.scanLimit = scanLimit; }
    public int getOutboxBatchSize() { return outboxBatchSize; }
    public void setOutboxBatchSize(int outboxBatchSize) { this.outboxBatchSize = outboxBatchSize; }
    public int getBacklogMultiplier() { return backlogMultiplier; }
    public void setBacklogMultiplier(int backlogMultiplier) { this.backlogMultiplier = backlogMultiplier; }
}
```

Create `MarketingRoundScheduler.java`:

```java
package com.armada.marketing.scheduler;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.MarketingTask;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("kafka")
@EnableConfigurationProperties(MarketingRoundSchedulerProperties.class)
public class MarketingRoundScheduler {
    private static final Logger log = LoggerFactory.getLogger(MarketingRoundScheduler.class);
    private final MarketingTaskMapper taskMapper;
    private final MarketingRoundWorker worker;
    private final MarketingRoundSchedulerProperties properties;
    private final ExecutorService executor;

    public MarketingRoundScheduler(MarketingTaskMapper taskMapper,
                                   MarketingRoundWorker worker,
                                   MarketingRoundSchedulerProperties properties) {
        this.taskMapper = taskMapper;
        this.worker = worker;
        this.properties = properties;
        this.executor = Executors.newFixedThreadPool(Math.max(1, properties.getExecutorPoolSize()), runnable -> {
            AtomicIntegerHolder.SEQ.compareAndSet(0, 0);
            Thread thread = new Thread(runnable, "marketing-round-worker-" + AtomicIntegerHolder.SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${armada.marketing.round-scheduler.scan-fixed-delay-ms:1000}")
    public void scanDueTasks() {
        if (!properties.isEnabled()) {
            return;
        }
        List<MarketingTask> tasks = taskMapper.selectDueSendingTasks(System.currentTimeMillis(),
                Math.max(1, properties.getScanLimit()));
        for (MarketingTask task : tasks) {
            executor.execute(() -> worker.runRound(task.getTenantId(), task.getId()));
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static final class AtomicIntegerHolder {
        private static final AtomicInteger SEQ = new AtomicInteger();
    }
}
```

- [ ] **Step 5: Implement worker and start initialization**

Create `MarketingRoundWorker.java` with `runRound(Long tenantId, Long taskId)`, set `TenantContext`, check backlog, claim round, load template/image, create attempts, call `enqueueMarketingMessageCommands` in chunks of `outboxBatchSize`, and leave actual sends to Kafka.

Use this method skeleton:

```java
public void runRound(Long tenantId, Long taskId) {
    Long previousTenant = TenantContext.get();
    TenantContext.set(tenantId);
    try {
        doRunRound(taskId);
    } finally {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
```

In `MarketingTaskServiceImpl.buildTask`, initialize `nextRoundAt` when status is `SENDING`:

```java
task.setStartedAt(status == MarketingTaskStatus.SENDING ? now : null);
task.setNextRoundAt(status == MarketingTaskStatus.SENDING ? now : null);
task.setCurrentRoundNo(0L);
```

In `startTask`, after successful status change set `next_round_at=now` through the mapper method added in Task 2 or a small `startTask` SQL extension.

- [ ] **Step 6: Run tests and commit**

Run:

```bash
mvn -q -Dtest=MarketingRoundSchedulerTest,MarketingRoundWorkerTest,MarketingTaskMutationDbTest test
```

Expected: PASS.

Commit:

```bash
git add armada-api/src/main/java/com/armada/marketing/scheduler armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java armada-api/src/test/java/com/armada/marketing/scheduler
git commit -m "feat(marketing): schedule kafka send rounds"
```

## Task 6: Armada Message Result Consumer

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolMessageEventConsumerProperties.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolKafkaConfiguration.java`
- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageEventConsumer.java`
- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageSendResultReportedEvent.java`
- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageSendResultReportedSink.java`
- Create: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/platform/kafka/consumer/message/ProtocolMessageEventConsumerTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java`

- [ ] **Step 1: Write consumer parse test**

Create a test that feeds this JSON:

```json
{
  "eventId":"evt_1",
  "event":"message.send_result_reported",
  "version":"v1",
  "accountId":"acc_8613800138000",
  "occurredAt":"2026-07-04T10:00:00.000Z",
  "workerId":"worker-a",
  "data":{
    "tenantId":1,
    "marketingTaskId":42,
    "targetId":501,
    "attemptId":9001,
    "roundNo":1,
    "protocolAccountId":"acc_8613800138000",
    "groupJid":"120363001@g.us",
    "commandId":"cmd_1",
    "success":true,
    "messageId":"wamid.1",
    "timestamp":1783159200000
  }
}
```

Assert the sink receives `attemptId=9001`, `success=true`, and `messageId=wamid.1`.

- [ ] **Step 2: Write result service test**

Test success calls `markAttemptSuccess` once and increments success count once; a second identical event makes `markAttemptSuccess` return `0` and does not increment counters. Use Mockito stubs on `MarketingTaskMapper`.

- [ ] **Step 3: Implement properties and consumer**

Create `ProtocolMessageEventConsumerProperties.java` mirroring account/group consumer properties with defaults:

```java
public static final String DEFAULT_TOPIC = "protocol.message.events.v1";
public static final String DEFAULT_GROUP_ID = "armada-api-message-events";
```

Add it to `@EnableConfigurationProperties` in `ProtocolKafkaConfiguration`.

Implement `ProtocolMessageEventConsumer` with:

```java
public static final String EVENT_MESSAGE_SEND_RESULT_REPORTED = "message.send_result_reported";
```

Listener:

```java
@KafkaListener(
        topics = "${armada.protocol.kafka.message-events.topic:protocol.message.events.v1}",
        groupId = "${armada.protocol.kafka.message-events.group-id:armada-api-message-events}")
public void onMessage(String rawMessage) {
    JsonNode envelope = readEnvelope(rawMessage);
    String eventType = text(envelope, "event");
    if (!EVENT_MESSAGE_SEND_RESULT_REPORTED.equals(eventType)) {
        log.warn("协议消息事件暂未接入,跳过 eventId={} eventType={}", text(envelope, "eventId"), eventType);
        return;
    }
    sink.handleSendResultReported(toEvent(envelope, dataNode(envelope)));
}
```

- [ ] **Step 4: Implement marketing sink**

`MarketingSendResultServiceImpl` implements `ProtocolMessageSendResultReportedSink`:

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void handleSendResultReported(ProtocolMessageSendResultReportedEvent event) {
    Long previousTenant = TenantContext.get();
    TenantContext.set(event.tenantId());
    try {
        long resultAt = event.timestamp() == null ? System.currentTimeMillis() : event.timestamp();
        int updated = event.success()
                ? taskMapper.markAttemptSuccess(event.attemptId(), event.messageId(), resultAt)
                : taskMapper.markAttemptFailed(event.attemptId(), event.reasonCode(), event.reasonMessage(), resultAt);
        if (updated > 0) {
            taskMapper.incrementTaskSendCounters(event.marketingTaskId(),
                    event.success() ? 1 : 0,
                    event.success() ? 0 : 1,
                    resultAt);
        }
    } finally {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
```

- [ ] **Step 5: Run tests and commit**

Run:

```bash
mvn -q -Dtest=ProtocolMessageEventConsumerTest,MarketingSendResultServiceImplTest test
```

Expected: PASS.

Commit:

```bash
git add armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolMessageEventConsumerProperties.java armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolKafkaConfiguration.java armada-api/src/main/java/com/armada/platform/kafka/consumer/message armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java armada-api/src/test/java/com/armada/platform/kafka/consumer/message armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java
git commit -m "feat(marketing): consume message send results"
```

## Task 7: Protocol Parser Accepts Message Send Commands

**Files:**
- Modify: `armada-protocol/protocol-layer/src/commands/types.ts`
- Modify: `armada-protocol/protocol-layer/src/commands/types.test.ts`

- [ ] **Step 1: Add failing parser test**

Append to `types.test.ts`:

```ts
it('接受 Armada outbox 的 message.send.requested envelope', () => {
  const parsed = parseMasterCommand({
    commandId: 'cmd_msg_1',
    commandType: 'message.send.requested',
    aggregateType: 'MARKETING_SEND_ATTEMPT',
    aggregateId: 9001,
    protocolAccountId: 'acc_100',
    payload: {
      tenantId: 1,
      marketingTaskId: 42,
      attemptId: 9001,
      targetId: 501,
      roundNo: 1,
      accountId: 100,
      protocolAccountId: 'acc_100',
      groupJid: '120363001@g.us',
      messageType: 'TEXT',
      text: 'hello',
      source: 'marketing_task'
    }
  })

  expect(parsed.ok).toBe(true)
  if (parsed.ok) {
    expect(parsed.command.type).toBe('message.send.requested')
    expect(parsed.command.accountId).toBe('acc_100')
    expect(parsed.command.payload).toMatchObject({ attemptId: 9001, messageType: 'TEXT' })
  }
})
```

- [ ] **Step 2: Run parser test and verify failure**

Run from `armada-protocol/protocol-layer`:

```bash
npm test -- commands/types.test.ts
```

Expected: FAIL with unsupported command type.

- [ ] **Step 3: Add the command type**

In `types.ts`, extend `MasterCommandType`:

```ts
| 'message.send.requested'
```

Add to `SUPPORTED_COMMAND_TYPES`:

```ts
'message.send.requested'
```

- [ ] **Step 4: Run parser test**

Run:

```bash
npm test -- commands/types.test.ts
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add protocol-layer/src/commands/types.ts protocol-layer/src/commands/types.test.ts
git commit -m "feat(commands): accept message send command"
```

## Task 8: Protocol Worker Sends Messages and Publishes Result

**Files:**
- Modify: `armada-protocol/protocol-layer/src/events/subjects.ts`
- Modify: `armada-protocol/protocol-layer/src/commands/worker-consumer.ts`
- Modify: `armada-protocol/protocol-layer/src/commands/worker-consumer.test.ts`

- [ ] **Step 1: Write failing worker tests**

Add to `worker-consumer.test.ts`:

```ts
it('执行 message.send.requested 文本消息并发布成功结果后 ack', async () => {
  const calls: string[] = []
  const command: MasterCommandEnvelope = {
    commandId: 'cmd_msg_1',
    type: 'message.send.requested',
    version: 'v1',
    accountId: 'acc_1',
    createdAt: '2026-07-04T00:00:00.000Z',
    payload: {
      tenantId: 1,
      marketingTaskId: 42,
      attemptId: 9001,
      targetId: 501,
      roundNo: 1,
      accountId: 100,
      protocolAccountId: 'acc_1',
      groupJid: '120363001@g.us',
      messageType: 'TEXT',
      text: 'hello',
      source: 'marketing_task'
    }
  }

  await executeWorkerCommand(command, {
    accounts: {
      offline: async () => undefined,
      getSocket: () => ({
        sendMessage: async (jid: string, content: unknown) => {
          calls.push(`send:${jid}:${JSON.stringify(content)}`)
          return { key: { id: 'wamid.1', remoteJid: jid }, messageTimestamp: 1783159200 }
        }
      } as never)
    },
    publisher: {
      publish: async (evt, accountId, data) => {
        calls.push(`publish:${evt}:${accountId}:${(data as Record<string, unknown>).messageId}`)
      }
    },
    ack: async () => {
      calls.push('ack')
    }
  })

  expect(calls).toEqual([
    'send:120363001@g.us:{"text":"hello"}',
    'publish:message.send_result_reported:acc_1:wamid.1',
    'ack'
  ])
})
```

Add a failure test where `sendMessage` throws and expect `publish:message.send_result_reported` with `success=false`, then `ack`.

- [ ] **Step 2: Run worker tests and verify failure**

Run:

```bash
npm test -- commands/worker-consumer.test.ts
```

Expected: FAIL because `message.send.requested` is unsupported.

- [ ] **Step 3: Add event type**

In `events/subjects.ts`, add `'message.send_result_reported'` to `EVENT_TYPES` and `CRITICAL_EVENTS`.

- [ ] **Step 4: Implement command executor path**

In `worker-consumer.ts`, extend `WorkerCommandExecutorDeps.accounts.getSocket` socket type to include:

```ts
sendMessage(jid: string, content: Record<string, unknown>): Promise<{ key?: { id?: string | null }; messageTimestamp?: unknown }>
```

In `executeWorkerCommand`, add:

```ts
if (command.type === 'message.send.requested') {
  await executeMessageSend(command, deps)
  return
}
```

Implement:

```ts
async function executeMessageSend(command: MasterCommandEnvelope, deps: WorkerCommandExecutorDeps): Promise<void> {
  const payload = messageSendPayload(command.payload)
  if (!deps.accounts.getSocket) throw new Error('message send requires accounts.getSocket')
  if (!deps.publisher) throw new Error('message send requires event publisher')
  try {
    const sock = deps.accounts.getSocket(command.accountId)
    const result = await sock.sendMessage(payload.groupJid, messageContent(payload))
    await deps.publisher.publish('message.send_result_reported', command.accountId, {
      tenantId: payload.tenantId,
      marketingTaskId: payload.marketingTaskId,
      targetId: payload.targetId,
      attemptId: payload.attemptId,
      roundNo: payload.roundNo,
      protocolAccountId: payload.protocolAccountId ?? command.accountId,
      groupJid: payload.groupJid,
      commandId: command.commandId,
      success: true,
      messageId: result?.key?.id ?? null,
      timestamp: Number(result?.messageTimestamp ?? 0)
    })
  } catch (error) {
    await deps.publisher.publish('message.send_result_reported', command.accountId, {
      tenantId: payload.tenantId,
      marketingTaskId: payload.marketingTaskId,
      targetId: payload.targetId,
      attemptId: payload.attemptId,
      roundNo: payload.roundNo,
      protocolAccountId: payload.protocolAccountId ?? command.accountId,
      groupJid: payload.groupJid,
      commandId: command.commandId,
      success: false,
      reasonCode: 'SEND_FAILED',
      reasonMessage: errorMessage(error)
    })
  }
  await deps.ack()
}
```

Implement `messageSendPayload` to require `tenantId`, `marketingTaskId`, `attemptId`, `targetId`, `roundNo`, `groupJid`, `messageType`, and non-empty `text` for `TEXT`/`LINK`. For `IMAGE`, require `image.base64`.

Implement `messageContent`:

```ts
function messageContent(payload: MessageSendPayload): Record<string, unknown> {
  if (payload.messageType === 'IMAGE') {
    return { image: Buffer.from(payload.image!.base64, 'base64'), caption: payload.text || undefined }
  }
  return { text: payload.text }
}
```

- [ ] **Step 5: Run tests and commit**

Run:

```bash
npm test -- commands/worker-consumer.test.ts
npm run lint
```

Expected: PASS.

Commit:

```bash
git add protocol-layer/src/events/subjects.ts protocol-layer/src/commands/worker-consumer.ts protocol-layer/src/commands/worker-consumer.test.ts
git commit -m "feat(commands): execute message send commands"
```

## Task 9: End-to-End Armada Round Generation DbTest

**Files:**
- Test: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerDbTest.java`

- [ ] **Step 1: Write DbTest for one 1000-target round**

Create a DbTest that seeds:

- one `marketing_template`;
- one `marketing_task` with `status=2`, `next_round_at=now-1000`, `send_interval_seconds=30`;
- 1000 `marketing_task_target` rows.

Call `MarketingRoundWorker.runRound(tenantId, taskId)` with a fake `ProtocolCommandOutboxService` bean that records request count.

Assert:

- `current_round_no=1`;
- 1000 `marketing_task_send_attempt` rows exist with `round_no=1`;
- outbox service received 1000 requests in two chunks of 500.

- [ ] **Step 2: Run DbTest and verify it passes**

Run:

```bash
mvn -q -Dtest=MarketingRoundWorkerDbTest test
```

Expected: PASS.

- [ ] **Step 3: Add backlog DbTest**

Seed 2000 submitted attempts for a 1000-target task, then call `runRound`. Assert no new attempts are inserted and `current_round_no` remains unchanged.

- [ ] **Step 4: Run full marketing test slice**

Run:

```bash
mvn -q -Dtest=MarketingRoundWorkerDbTest,MarketingRoundMapperDbTest,MarketingTaskMutationDbTest,MarketingTaskCreateReadDbTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerDbTest.java
git commit -m "test(marketing): cover round outbox generation"
```

## Task 10: Configuration and Documentation

**Files:**
- Modify: `armada-api/src/main/resources/application.yml`
- Modify: `armada-deploy/docker-compose.rds.yml`
- Modify: `armada-deploy/.env.example`
- Modify: `docs/business/marketing-task-data-model.md`

- [ ] **Step 1: Add default application settings**

Add to `application.yml`:

```yaml
armada:
  marketing:
    round-scheduler:
      enabled: ${MARKETING_ROUND_SCHEDULER_ENABLED:true}
      scan-fixed-delay-ms: ${MARKETING_ROUND_SCAN_FIXED_DELAY_MS:1000}
      executor-pool-size: ${MARKETING_ROUND_EXECUTOR_POOL_SIZE:5}
      scan-limit: ${MARKETING_ROUND_SCAN_LIMIT:20}
      outbox-batch-size: ${MARKETING_ROUND_OUTBOX_BATCH_SIZE:500}
      backlog-multiplier: ${MARKETING_ROUND_BACKLOG_MULTIPLIER:2}
  protocol:
    kafka:
      message-events:
        topic: ${PROTOCOL_MESSAGE_EVENTS_TOPIC:protocol.message.events.v1}
        group-id: ${PROTOCOL_MESSAGE_EVENTS_GROUP_ID:armada-api-message-events}
        retry-interval-ms: ${PROTOCOL_MESSAGE_EVENTS_RETRY_INTERVAL_MS:5000}
        max-retry-attempts: ${PROTOCOL_MESSAGE_EVENTS_MAX_RETRY_ATTEMPTS:3}
        dead-letter-topic-suffix: ${PROTOCOL_MESSAGE_EVENTS_DLT_SUFFIX:.DLT}
```

- [ ] **Step 2: Add deploy env variables**

Add the same environment variable names to `docker-compose.rds.yml` service environment and `.env.example`, with the defaults listed above.

- [ ] **Step 3: Update data model docs**

In `docs/business/marketing-task-data-model.md`, add:

```markdown
### 周期性轮次发送

营销任务启动后按 `send_interval_seconds` 生成轮次。每轮对任务内全部 `marketing_task_target`
各生成一条 `marketing_task_send_attempt` 和一条 `message.send.requested` 协议命令。
`marketing_task_send_attempt.round_no` 标识轮次,`command_id` 对应协议 outbox 命令,
`message_id/result_at` 由协议层发送结果事件回写。
```

- [ ] **Step 4: Run config binding tests**

Add or extend properties tests for `MarketingRoundSchedulerProperties` and `ProtocolMessageEventConsumerProperties`. Run:

```bash
mvn -q -Dtest=MarketingRoundSchedulerPropertiesTest,ProtocolMessageEventConsumerPropertiesTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add armada-api/src/main/resources/application.yml armada-deploy/docker-compose.rds.yml armada-deploy/.env.example docs/business/marketing-task-data-model.md armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundSchedulerPropertiesTest.java armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolMessageEventConsumerPropertiesTest.java
git commit -m "docs(marketing): configure kafka round sends"
```

## Task 11: Final Verification

**Files:**
- No source files unless previous tasks expose a defect.

- [ ] **Step 1: Run Armada focused tests**

Run from `armada/armada-api`:

```bash
mvn -q -Dtest=MarketingMessageComposerTest,MarketingRoundSchedulerTest,MarketingRoundWorkerTest,ProtocolMarketingMessageOutboxServiceTest,ProtocolMessageEventConsumerTest,MarketingSendResultServiceImplTest test
```

Expected: PASS.

- [ ] **Step 2: Run Armada DbTests for touched flows**

Run:

```bash
mvn -q -Dtest=MarketingKafkaRoundSendMigrationDbTest,MarketingRoundMapperDbTest,MarketingRoundWorkerDbTest,MarketingTaskMutationDbTest,MarketingTaskCreateReadDbTest test
```

Expected: PASS.

- [ ] **Step 3: Run protocol tests**

Run from `armada-protocol/protocol-layer`:

```bash
npm test -- commands/types.test.ts commands/worker-consumer.test.ts
npm run lint
```

Expected: PASS.

- [ ] **Step 4: Inspect git state**

Run:

```bash
git -C /Users/daishuaishuai/IdeaProjects/armada status --short
git -C /Users/daishuaishuai/IdeaProjects/armada-protocol status --short
```

Expected: only intentional source/test/docs changes are present.

- [ ] **Step 5: Final git inspection**

Run:

```bash
git -C /Users/daishuaishuai/IdeaProjects/armada status --short
git -C /Users/daishuaishuai/IdeaProjects/armada-protocol status --short
```

Expected: no unexpected changes. If verification exposed a defect, make the minimal fix, rerun the failed verification command, then commit only the exact files changed by that fix.
