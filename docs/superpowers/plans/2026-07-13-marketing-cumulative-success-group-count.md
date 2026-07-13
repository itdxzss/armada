# Marketing Cumulative Success Group Count Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `targetGroupCount` represent the number of unique WhatsApp groups that a marketing task has actually sent to successfully, counted once per task and never decremented.

**Architecture:** Add an immutable `marketing_task_success_group` fact table with a database unique key on tenant, task, and `group_jid`. The existing send-result transaction inserts the fact only after an attempt becomes successful and increments `marketing_task.target_group_count` only when that insert wins; task creation starts the counter at zero, while a one-time Flyway migration backfills historical successful attempts.

**Tech Stack:** Java 17, Spring Boot 3, MyBatis XML, MySQL 8, Flyway, JUnit 5, AssertJ, Mockito, Vue 3, TypeScript, Element Plus, Node test runner.

---

## Scope and Repository Routing

- Backend and database work: `/Users/daishuaishuai/IdeaProjects/armada/armada-api`.
- Frontend label work: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`.
- Do not modify `MarketingRoundWorker`; queueing and timed-round target resolution remain unchanged.
- Do not add a current-available-group counter in this change.
- Do not extend this counter to `group_creation_marketing` events.
- Preserve unrelated `.claude/worktrees` and deployment-script changes in both repositories.

## File Structure

Backend files:

- Create `armada-api/src/main/resources/db/migration/V051__marketing_task_success_group.sql`: create the immutable deduplication table, backfill historical facts, reset the task counter to the new meaning, and update the column comment.
- Create `armada-api/src/test/java/com/armada/marketing/MarketingSuccessfulGroupMigrationSqlTest.java`: verify the migration contains the required success-only, trimmed-JID, deduplicated backfill.
- Modify `armada-api/src/test/java/com/armada/marketing/MarketingTaskDataModelMigrationDbTest.java`: verify the new table, unique index, and task-column comment against the migrated database.
- Modify `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`: prove fixed and dynamic tasks both start with zero cumulative successful groups.
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`: initialize `targetGroupCount` to zero and remove creation-time group counting.
- Modify `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java`: cover first-success, duplicate-event, same-group, missing-JID, failure, and counter-update failure behavior.
- Modify `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`: verify mapper SQL uses the persisted successful attempt and atomic insert-ignore fact.
- Modify `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`: expose successful-attempt JID lookup, fact insertion, and task-counter increment methods.
- Modify `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`: implement the three tenant-scoped SQL operations.
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java`: maintain the cumulative group fact inside the existing result transaction.
- Modify `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplDbTest.java`: verify cross-round and cross-group behavior plus transaction rollback.

Frontend files:

- Create `src/views/task/group-marketing/components/GroupMarketingTaskCountUi.test.ts`: pin the new column wording and existing API field.
- Modify `src/views/task/group-marketing/components/GroupMarketingTaskTable.vue`: display “累计有效群组数量”.
- Modify `src/views/task/group-marketing/constants.ts`: keep the dynamic-column metadata aligned with the table.

### Task 1: Add the Success-Group Schema and Historical Backfill

**Files:**
- Create: `armada-api/src/test/java/com/armada/marketing/MarketingSuccessfulGroupMigrationSqlTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/MarketingTaskDataModelMigrationDbTest.java`
- Create: `armada-api/src/main/resources/db/migration/V051__marketing_task_success_group.sql`

- [ ] **Step 1: Write the failing migration SQL-shape test**

Create `MarketingSuccessfulGroupMigrationSqlTest.java`:

```java
package com.armada.marketing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingSuccessfulGroupMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V051__marketing_task_success_group.sql");

    @Test
    void migrationCreatesDedupFactsAndBackfillsOnlySuccessfulAttempts() throws IOException {
        assertThat(MIGRATION).exists();
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS marketing_task_success_group")
                .contains("UNIQUE KEY uq_marketing_task_success_group")
                .contains("(tenant_id, marketing_task_id, group_jid)")
                .contains("FROM marketing_task_send_attempt a")
                .contains("a.status = 1")
                .contains("TRIM(a.group_jid)")
                .contains("ROW_NUMBER() OVER")
                .contains("PARTITION BY a.tenant_id, a.marketing_task_id, TRIM(a.group_jid)")
                .contains("UPDATE marketing_task task")
                .contains("target_group_count = COALESCE(success.success_group_count, 0)")
                .contains("任务累计成功触达去重群数")
                .doesNotContain("DELETE FROM marketing_task_success_group");
    }
}
```

- [ ] **Step 2: Add a failing database schema test**

In `MarketingTaskDataModelMigrationDbTest`, add the table assertion to `v014_createsMarketingTaskTablesAndAccountBaselineTable`:

```java
        assertThat(tableExists("marketing_task_success_group")).isTrue();
```

Add this focused test:

```java
    @Test
    void marketingTaskSuccessGroup_hasTaskGroupUniqueFactAndCumulativeCountComment() {
        assertThat(columnType("marketing_task_success_group", "marketing_task_id")).isEqualTo("bigint");
        assertThat(columnType("marketing_task_success_group", "group_jid")).isEqualTo("varchar");
        assertThat(columnType("marketing_task_success_group", "first_success_attempt_id")).isEqualTo("bigint");
        assertThat(columnType("marketing_task_success_group", "first_success_at")).isEqualTo("bigint");
        assertThat(indexExists("marketing_task_success_group", "uq_marketing_task_success_group")).isTrue();
        assertThat(columnComment("marketing_task", "target_group_count"))
                .isEqualTo("任务累计成功触达去重群数");
    }
```

- [ ] **Step 3: Run the tests and verify RED**

Run from `armada/armada-api`:

```bash
mvn -q -Dtest=MarketingSuccessfulGroupMigrationSqlTest test
```

Expected: FAIL because `V051__marketing_task_success_group.sql` does not exist.

Run:

```bash
./dbtest.sh MarketingTaskDataModelMigrationDbTest#marketingTaskSuccessGroup_hasTaskGroupUniqueFactAndCumulativeCountComment
```

Expected: FAIL because `marketing_task_success_group` does not exist.

- [ ] **Step 4: Create the Flyway migration**

Create `V051__marketing_task_success_group.sql`:

```sql
CREATE TABLE IF NOT EXISTS marketing_task_success_group (
    id                       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id                BIGINT       NOT NULL                COMMENT '租户ID',
    marketing_task_id        BIGINT       NOT NULL                COMMENT '普通群组营销任务ID(→marketing_task.id)',
    group_jid                VARCHAR(128) NOT NULL                COMMENT '首次成功触达的WhatsApp群JID',
    first_success_attempt_id BIGINT       NOT NULL                COMMENT '首次成功发送尝试ID(→marketing_task_send_attempt.id)',
    first_success_at         BIGINT       NOT NULL                COMMENT '首次成功时间(epoch毫秒)',
    created_at               BIGINT       NOT NULL                COMMENT '创建时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_marketing_task_success_group (tenant_id, marketing_task_id, group_jid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群组营销任务累计成功群组事实';

INSERT IGNORE INTO marketing_task_success_group
    (marketing_task_id, group_jid, first_success_attempt_id, first_success_at, created_at, tenant_id)
SELECT ranked.marketing_task_id,
       ranked.group_jid,
       ranked.attempt_id,
       ranked.first_success_at,
       ranked.first_success_at,
       ranked.tenant_id
FROM (
    SELECT a.tenant_id,
           a.marketing_task_id,
           a.id AS attempt_id,
           TRIM(a.group_jid) AS group_jid,
           COALESCE(a.result_at, a.attempted_at, a.created_at) AS first_success_at,
           ROW_NUMBER() OVER (
               PARTITION BY a.tenant_id, a.marketing_task_id, TRIM(a.group_jid)
               ORDER BY COALESCE(a.result_at, a.attempted_at, a.created_at) ASC, a.id ASC
           ) AS success_rank
    FROM marketing_task_send_attempt a
    WHERE a.status = 1
      AND a.group_jid IS NOT NULL
      AND TRIM(a.group_jid) <> ''
) ranked
WHERE ranked.success_rank = 1;

UPDATE marketing_task task
LEFT JOIN (
    SELECT tenant_id,
           marketing_task_id,
           COUNT(*) AS success_group_count
    FROM marketing_task_success_group
    GROUP BY tenant_id, marketing_task_id
) success ON success.tenant_id = task.tenant_id
         AND success.marketing_task_id = task.id
SET task.target_group_count = COALESCE(success.success_group_count, 0);

ALTER TABLE marketing_task
    MODIFY COLUMN target_group_count INT NOT NULL DEFAULT 0
    COMMENT '任务累计成功触达去重群数';
```

- [ ] **Step 5: Run migration tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=MarketingSuccessfulGroupMigrationSqlTest test
```

Expected: PASS.

Run:

```bash
./dbtest.sh MarketingTaskDataModelMigrationDbTest
```

Expected: PASS, including the new table, unique index, and column comment assertions.

- [ ] **Step 6: Commit the migration slice**

```bash
git add armada-api/src/main/resources/db/migration/V051__marketing_task_success_group.sql armada-api/src/test/java/com/armada/marketing/MarketingSuccessfulGroupMigrationSqlTest.java armada-api/src/test/java/com/armada/marketing/MarketingTaskDataModelMigrationDbTest.java
git commit -m "feat(marketing): add successful group fact table"
```

### Task 2: Initialize New Tasks with Zero Successful Groups

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java:41`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java:538`

- [ ] **Step 1: Change the fixed-target creation test to the new meaning**

In `createTask_persistsTaskAndTargetsFromSelections`, replace the target-group assertion with:

```java
        assertThat(created.targetGroupCount())
                .as("累计成功群数在首次成功回调前必须为0")
                .isZero();
```

Keep `createTask_accountDynamicSelectionPersistsAccountTargetWithoutGroupSnapshot` asserting zero.

- [ ] **Step 2: Run the focused test and verify RED**

Run from `armada/armada-api`:

```bash
./dbtest.sh MarketingTaskCreateReadDbTest#createTask_persistsTaskAndTargetsFromSelections
```

Expected: FAIL because the current fixed-group creation path returns `targetGroupCount = 1`.

- [ ] **Step 3: Initialize the cumulative counter to zero**

In `MarketingTaskServiceImpl.buildTask`, replace the count block with:

```java
        // 账号数和执行目标行数在创建时确定；累计成功群数只能由成功结果回调递增。
        task.setSelectedAccountCount(distinctAccountCount(targets));
        task.setTargetGroupCount(0);
        task.setTargetPairCount(targets.size());
```

Delete the now-unused `distinctGroupCount` method:

```java
    private static int distinctGroupCount(List<MarketingTaskTarget> targets) {
        return (int) targets.stream()
                .map(MarketingTaskTarget::getGroupLinkId)
                .filter(groupLinkId -> groupLinkId != null)
                .distinct()
                .count();
    }
```

- [ ] **Step 4: Run creation tests and verify GREEN**

Run:

```bash
./dbtest.sh MarketingTaskCreateReadDbTest
```

Expected: PASS; both fixed and dynamic tasks start at zero while target rows are unchanged.

- [ ] **Step 5: Commit the creation semantics**

```bash
git add armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java
git commit -m "fix(marketing): initialize successful group count at zero"
```

### Task 3: Specify Success-Result Deduplication Behavior

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`

- [ ] **Step 1: Extend the first-success unit test**

In `successEventUpdatesAttemptAndIncrementsSuccessCountOnce`, add mapper stubs before invoking the service:

```java
        when(mapper.selectSuccessfulAttemptGroupJid(42L, 9001L)).thenReturn("120363001@g.us");
        when(mapper.insertSuccessfulGroupFromAttempt(42L, 9001L, 1783159200000L)).thenReturn(1);
        when(mapper.incrementTaskSuccessfulGroupCount(42L, 1783159200000L)).thenReturn(1);
```

Add verifications after the existing attempt and target assertions:

```java
        verify(mapper).selectSuccessfulAttemptGroupJid(42L, 9001L);
        verify(mapper).insertSuccessfulGroupFromAttempt(42L, 9001L, 1783159200000L);
        verify(mapper).incrementTaskSuccessfulGroupCount(42L, 1783159200000L);
```

- [ ] **Step 2: Add same-group, duplicate-event, missing-JID, and counter-failure tests**

Add these tests to `MarketingSendResultServiceImplTest`:

```java
    @Test
    void laterSuccessfulAttemptForCountedGroupDoesNotIncrementGroupCount() {
        ProtocolMessageSendResultReportedEvent event = event(true);
        when(mapper.markAttemptSuccess(9001L, "wamid.1", "120363001@g.us", 1783159200000L)).thenReturn(1);
        when(mapper.selectSuccessfulAttemptGroupJid(42L, 9001L)).thenReturn("120363001@g.us");
        when(mapper.insertSuccessfulGroupFromAttempt(42L, 9001L, 1783159200000L)).thenReturn(0);

        service.handleSendResultReported(event);

        verify(mapper).incrementTaskSendCounters(42L, 1, 0, 1783159200000L);
        verify(mapper, never()).incrementTaskSuccessfulGroupCount(42L, 1783159200000L);
    }

    @Test
    void successfulAttemptWithoutPersistedGroupJidDoesNotIncrementGroupCount() {
        Logger logger = (Logger) LoggerFactory.getLogger(MarketingSendResultServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ProtocolMessageSendResultReportedEvent event = event(true);
            when(mapper.markAttemptSuccess(9001L, "wamid.1", "120363001@g.us", 1783159200000L)).thenReturn(1);
            when(mapper.selectSuccessfulAttemptGroupJid(42L, 9001L)).thenReturn(null);

            service.handleSendResultReported(event);

            verify(mapper, never()).insertSuccessfulGroupFromAttempt(42L, 9001L, 1783159200000L);
            verify(mapper, never()).incrementTaskSuccessfulGroupCount(42L, 1783159200000L);
            verify(mapper).incrementTaskSendCounters(42L, 1, 0, 1783159200000L);
            assertThat(appender.list)
                    .anyMatch(log -> log.getFormattedMessage().contains("营销成功结果缺少有效群JID")
                            && log.getFormattedMessage().contains("attemptId=9001"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void successfulGroupFactRollsBackWhenTaskCounterCannotBeUpdated() {
        ProtocolMessageSendResultReportedEvent event = event(true);
        when(mapper.markAttemptSuccess(9001L, "wamid.1", "120363001@g.us", 1783159200000L)).thenReturn(1);
        when(mapper.selectSuccessfulAttemptGroupJid(42L, 9001L)).thenReturn("120363001@g.us");
        when(mapper.insertSuccessfulGroupFromAttempt(42L, 9001L, 1783159200000L)).thenReturn(1);
        when(mapper.incrementTaskSuccessfulGroupCount(42L, 1783159200000L)).thenReturn(0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.handleSendResultReported(event))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("累计成功群组数量更新失败");
    }
```

Extend `duplicateSuccessEventDoesNotIncrementCountersAgain` with:

```java
        verify(mapper, never()).selectSuccessfulAttemptGroupJid(42L, 9001L);
        verify(mapper, never()).insertSuccessfulGroupFromAttempt(42L, 9001L, 1783159200000L);
        verify(mapper, never()).incrementTaskSuccessfulGroupCount(42L, 1783159200000L);
```

Extend `failedEventUpdatesAttemptTargetAndIncrementsFailureCountOnce` with:

```java
        verify(mapper, never()).selectSuccessfulAttemptGroupJid(42L, 9001L);
        verify(mapper, never()).insertSuccessfulGroupFromAttempt(42L, 9001L, 1783159200000L);
        verify(mapper, never()).incrementTaskSuccessfulGroupCount(42L, 1783159200000L);
```

- [ ] **Step 3: Add a failing mapper SQL-shape test**

Add this test to `MarketingTaskMapperSqlShapeTest`:

```java
    @Test
    void successfulGroupCountUsesPersistedSuccessfulAttemptAndAtomicFactInsert() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String groupJidSql = selectBlock(xml, "selectSuccessfulAttemptGroupJid");
        String insertFactSql = insertBlock(xml, "insertSuccessfulGroupFromAttempt");
        String incrementSql = updateBlock(xml, "incrementTaskSuccessfulGroupCount");

        assertThat(groupJidSql)
                .contains("TRIM(group_jid)")
                .contains("marketing_task_id = #{taskId}")
                .contains("status = 1");
        assertThat(insertFactSql)
                .contains("INSERT IGNORE INTO marketing_task_success_group")
                .contains("FROM marketing_task_send_attempt a")
                .contains("a.id = #{attemptId}")
                .contains("a.marketing_task_id = #{taskId}")
                .contains("a.status = 1")
                .contains("TRIM(a.group_jid)");
        assertThat(incrementSql)
                .contains("target_group_count = target_group_count + 1")
                .contains("deleted_at IS NULL");
    }
```

Add this helper beside `selectBlock` and `updateBlock`:

```java
    private static String insertBlock(String xml, String id) {
        String startTag = "<insert id=\"" + id + "\"";
        int start = xml.indexOf(startTag);
        assertThat(start).as("mapper insert " + id + " exists").isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</insert>", start);
        assertThat(end).as("mapper insert " + id + " closes").isGreaterThan(start);
        return xml.substring(start, end);
    }
```

- [ ] **Step 4: Run unit tests and verify RED**

Run from `armada/armada-api`:

```bash
mvn -q -Dtest=MarketingSendResultServiceImplTest,MarketingTaskMapperSqlShapeTest test
```

Expected: compilation or assertion failure because the new mapper methods and SQL blocks do not exist.

### Task 4: Implement Atomic Success-Group Maintenance

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`

- [ ] **Step 1: Add mapper method contracts**

Add to `MarketingTaskMapper` after `markAttemptFailed`:

```java
    /** 读取已成功 attempt 最终持久化的非空群JID。 */
    String selectSuccessfulAttemptGroupJid(@Param("taskId") Long taskId,
                                           @Param("attemptId") Long attemptId);

    /** 从首次成功 attempt 原子写入任务+群去重事实；已存在时返回0。 */
    int insertSuccessfulGroupFromAttempt(@Param("taskId") Long taskId,
                                         @Param("attemptId") Long attemptId,
                                         @Param("now") long now);

    /** 仅在新成功群事实插入后将任务累计成功群数加1。 */
    int incrementTaskSuccessfulGroupCount(@Param("taskId") Long taskId,
                                          @Param("now") long now);
```

- [ ] **Step 2: Add tenant-scoped mapper SQL**

Add to `MarketingTaskMapper.xml` after `markAttemptFailed`:

```xml
    <select id="selectSuccessfulAttemptGroupJid" resultType="java.lang.String">
        SELECT TRIM(group_jid)
        FROM marketing_task_send_attempt
        WHERE id = #{attemptId}
          AND marketing_task_id = #{taskId}
          AND status = 1
          AND group_jid IS NOT NULL
          AND TRIM(group_jid) &lt;&gt; ''
        LIMIT 1
    </select>

    <insert id="insertSuccessfulGroupFromAttempt">
        <!-- INSERT SELECT 显式携带 a.tenant_id，保持与现有占用表写入相同的租户拦截器约定。 -->
        INSERT IGNORE INTO marketing_task_success_group
            (marketing_task_id, group_jid, first_success_attempt_id, first_success_at, created_at, tenant_id)
        SELECT a.marketing_task_id,
               TRIM(a.group_jid),
               a.id,
               COALESCE(a.result_at, #{now}),
               #{now},
               a.tenant_id
        FROM marketing_task_send_attempt a
        WHERE a.id = #{attemptId}
          AND a.marketing_task_id = #{taskId}
          AND a.status = 1
          AND a.group_jid IS NOT NULL
          AND TRIM(a.group_jid) &lt;&gt; ''
    </insert>

    <update id="incrementTaskSuccessfulGroupCount">
        UPDATE marketing_task
        SET target_group_count = target_group_count + 1,
            updated_at = #{now}
        WHERE id = #{taskId}
          AND deleted_at IS NULL
    </update>
```

- [ ] **Step 3: Add the transactional service helper**

Add imports to `MarketingSendResultServiceImpl`:

```java
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
```

Add this helper before `handleGroupCreationMarketingResult`:

```java
    private boolean recordSuccessfulGroup(ProtocolMessageSendResultReportedEvent event, long resultAt) {
        String groupJid = taskMapper.selectSuccessfulAttemptGroupJid(event.marketingTaskId(), event.attemptId());
        if (!StringUtils.hasText(groupJid)) {
            log.warn("营销成功结果缺少有效群JID,累计群组数未更新 tenantId={} taskId={} targetId={} attemptId={}",
                    event.tenantId(), event.marketingTaskId(), event.targetId(), event.attemptId());
            return false;
        }
        int inserted = taskMapper.insertSuccessfulGroupFromAttempt(
                event.marketingTaskId(), event.attemptId(), resultAt);
        if (inserted == 0) {
            return false;
        }
        if (inserted != 1) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "累计成功群组事实写入数量异常: taskId=" + event.marketingTaskId()
                            + ", attemptId=" + event.attemptId() + ", inserted=" + inserted);
        }
        int incremented = taskMapper.incrementTaskSuccessfulGroupCount(event.marketingTaskId(), resultAt);
        if (incremented != 1) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "累计成功群组数量更新失败: taskId=" + event.marketingTaskId()
                            + ", attemptId=" + event.attemptId());
        }
        return true;
    }
```

- [ ] **Step 4: Call the helper only for a newly applied success result**

Inside `if (updated > 0)`, replace the success/failure target branch with:

```java
                boolean newSuccessfulGroup = false;
                if (event.success()) {
                    taskMapper.markTargetSuccessFromAttempt(event.targetId(), event.attemptId(), resultAt);
                    newSuccessfulGroup = recordSuccessfulGroup(event, resultAt);
                } else {
                    taskMapper.markTargetFailedFromAttempt(event.targetId(), event.attemptId(),
                            event.reasonCode(), event.reasonMessage(), resultAt);
                }
```

Extend the applied-result log template with `newSuccessfulGroup={}` and pass `newSuccessfulGroup` as its final argument:

```java
                log.info("营销发送结果已回写 tenantId={} taskId={} targetId={} attemptId={} roundNo={} "
                                + "commandId={} protocolAccountId={} groupJid={} success={} messageId={} "
                                + "reasonCode={} workerId={} newSuccessfulGroup={}",
                        event.tenantId(), event.marketingTaskId(), event.targetId(), event.attemptId(),
                        event.roundNo(), event.commandId(), event.protocolAccountId(), event.groupJid(),
                        event.success(), event.messageId(), event.reasonCode(), event.workerId(), newSuccessfulGroup);
```

Keep `incrementTaskSendCounters` unchanged so every successful message still increases `sent_message_count`, even when the group fact already exists.

- [ ] **Step 5: Run unit and SQL-shape tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=MarketingSendResultServiceImplTest,MarketingTaskMapperSqlShapeTest test
```

Expected: PASS.

- [ ] **Step 6: Commit the success-result implementation**

```bash
git add armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java
git commit -m "feat(marketing): count unique groups after send success"
```

### Task 5: Verify Database Deduplication and Transaction Rollback

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplDbTest.java`

- [ ] **Step 1: Generalize the attempt and event test helpers**

Keep the existing two-argument helper and add an account-specific overload so the tests can prove cross-account deduplication:

```java
    private Long insertDynamicTarget(Long taskId, long now) {
        return insertDynamicTarget(taskId, 501L, "923sendresult", now);
    }

    private Long insertDynamicTarget(Long taskId, Long accountId, String accountPhone, long now) {
        return insertAndReturnId("""
                INSERT INTO marketing_task_target
                    (tenant_id, marketing_task_id, account_id, account_phone,
                     target_scope, group_link_id, group_jid, group_link_url, group_name,
                     status, sent_message_count, failed_message_count, retry_count,
                     last_attempt_at, last_sent_at, last_reason, created_at, updated_at)
                VALUES
                    (?, ?, ?, ?, 2, NULL, NULL, NULL, NULL,
                     1, 0, 0, 0, NULL, NULL, NULL, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setLong(2, taskId);
            ps.setLong(3, accountId);
            ps.setString(4, accountPhone);
            ps.setLong(5, now);
            ps.setLong(6, now);
        });
    }

    private Long insertFixedTarget(Long taskId,
                                   Long accountId,
                                   String accountPhone,
                                   Long groupLinkId,
                                   String groupJid,
                                   long now) {
        return insertAndReturnId("""
                INSERT INTO marketing_task_target
                    (tenant_id, marketing_task_id, account_id, account_phone,
                     target_scope, group_link_id, group_jid, group_link_url, group_name,
                     status, sent_message_count, failed_message_count, retry_count,
                     last_attempt_at, last_sent_at, last_reason, created_at, updated_at)
                VALUES
                    (?, ?, ?, ?, 1, ?, ?, NULL, '固定发送群',
                     1, 0, 0, 0, NULL, NULL, NULL, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setLong(2, taskId);
            ps.setLong(3, accountId);
            ps.setString(4, accountPhone);
            ps.setLong(5, groupLinkId);
            ps.setString(6, groupJid);
            ps.setLong(7, now);
            ps.setLong(8, now);
        });
    }
```

Replace `insertSubmittedAttempt` with:

```java
    private Long insertSubmittedAttempt(Long taskId,
                                        Long targetId,
                                        Long groupLinkId,
                                        String groupJid,
                                        long roundNo,
                                        long now) {
        return insertAndReturnId("""
                INSERT INTO marketing_task_send_attempt
                    (tenant_id, marketing_task_id, target_id, group_link_id, group_jid, group_name,
                     round_no, attempt_no, is_retry, command_id, status, reason_code,
                     reason_message, message_id, submitted_at, result_at, attempted_at, created_at)
                VALUES
                    (?, ?, ?, ?, ?, '发送结果群',
                     ?, 1, 0, ?, 0, NULL,
                     NULL, NULL, ?, NULL, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setLong(2, taskId);
            ps.setLong(3, targetId);
            ps.setLong(4, groupLinkId);
            ps.setString(5, groupJid);
            ps.setLong(6, roundNo);
            ps.setString(7, "cmd_success_group_" + taskId + "_" + roundNo + "_" + groupJid);
            ps.setLong(8, now);
            ps.setLong(9, now);
            ps.setLong(10, now);
        });
    }
```

Replace `successEvent` with:

```java
    private static ProtocolMessageSendResultReportedEvent successEvent(Long taskId,
                                                                       Long targetId,
                                                                       Long attemptId,
                                                                       String groupJid,
                                                                       long roundNo,
                                                                       long timestamp) {
        return new ProtocolMessageSendResultReportedEvent(
                "evt_success_group_" + attemptId,
                TEST_TENANT_ID,
                taskId,
                targetId,
                attemptId,
                roundNo,
                "acc_923sendresult",
                groupJid,
                "cmd_success_group_" + taskId + "_" + roundNo + "_" + groupJid,
                true,
                "wamid." + attemptId,
                null,
                null,
                timestamp,
                "worker-a",
                null,
                null,
                "marketing_task");
    }
```

Update the existing test call to pass `"120363sendresult@g.us"` and round `1L`.

- [ ] **Step 2: Add the cross-round same-group database test**

```java
    @Test
    void sameGroupSuccessfulAcrossRoundsCountsOnceButMessagesCountTwice() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("same-success-group-" + now, now);
        Long firstTargetId = insertDynamicTarget(taskId, 501L, "923sendresult-a", now);
        Long groupLinkId = insertGroupLink(now);
        String groupJid = "120363sendresult@g.us";
        Long secondTargetId = insertFixedTarget(
                taskId, 502L, "923sendresult-b", groupLinkId, groupJid, now + 1);
        Long firstAttemptId = insertSubmittedAttempt(taskId, firstTargetId, groupLinkId, groupJid, 1L, now);
        Long secondAttemptId = insertSubmittedAttempt(taskId, secondTargetId, groupLinkId, groupJid, 2L, now + 2_000);

        service.handleSendResultReported(successEvent(
                taskId, firstTargetId, firstAttemptId, groupJid, 1L, now + 1_000));
        service.handleSendResultReported(successEvent(
                taskId, secondTargetId, secondAttemptId, groupJid, 2L, now + 3_000));

        Map<String, Object> task = jdbc.queryForMap("""
                SELECT target_group_count, sent_message_count
                FROM marketing_task
                WHERE id = ?
                """, taskId);
        assertThat(task.get("target_group_count")).isEqualTo(1);
        assertThat(task.get("sent_message_count")).isEqualTo(2);
        Integer factRows = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM marketing_task_success_group
                WHERE marketing_task_id = ? AND group_jid = ?
                """, Integer.class, taskId, groupJid);
        assertThat(factRows).isEqualTo(1);
    }
```

- [ ] **Step 3: Add the distinct-group database test**

```java
    @Test
    void differentSuccessfulGroupsIncrementCumulativeCountSeparately() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("different-success-groups-" + now, now);
        Long targetId = insertDynamicTarget(taskId, now);
        Long firstGroupLinkId = insertGroupLink(now);
        Long secondGroupLinkId = insertGroupLink(now + 1);
        String firstGroupJid = "120363sendresult-a@g.us";
        String secondGroupJid = "120363sendresult-b@g.us";
        Long firstAttemptId = insertSubmittedAttempt(
                taskId, targetId, firstGroupLinkId, firstGroupJid, 1L, now);
        Long secondAttemptId = insertSubmittedAttempt(
                taskId, targetId, secondGroupLinkId, secondGroupJid, 2L, now + 2_000);

        service.handleSendResultReported(successEvent(
                taskId, targetId, firstAttemptId, firstGroupJid, 1L, now + 1_000));
        service.handleSendResultReported(successEvent(
                taskId, targetId, secondAttemptId, secondGroupJid, 2L, now + 3_000));

        Integer groupCount = jdbc.queryForObject(
                "SELECT target_group_count FROM marketing_task WHERE id = ?",
                Integer.class,
                taskId);
        assertThat(groupCount).isEqualTo(2);
    }

    @Test
    void sameGroupInDifferentTasksCountsOnceForEachTask() {
        long now = System.currentTimeMillis();
        String groupJid = "120363sendresult-shared@g.us";
        Long groupLinkId = insertGroupLink(now);
        Long firstTaskId = insertTask("shared-group-task-a-" + now, now);
        Long secondTaskId = insertTask("shared-group-task-b-" + now, now + 1);
        Long firstTargetId = insertDynamicTarget(firstTaskId, 601L, "923shared-a", now);
        Long secondTargetId = insertDynamicTarget(secondTaskId, 602L, "923shared-b", now + 1);
        Long firstAttemptId = insertSubmittedAttempt(
                firstTaskId, firstTargetId, groupLinkId, groupJid, 1L, now);
        Long secondAttemptId = insertSubmittedAttempt(
                secondTaskId, secondTargetId, groupLinkId, groupJid, 1L, now + 1);

        service.handleSendResultReported(successEvent(
                firstTaskId, firstTargetId, firstAttemptId, groupJid, 1L, now + 1_000));
        service.handleSendResultReported(successEvent(
                secondTaskId, secondTargetId, secondAttemptId, groupJid, 1L, now + 1_001));

        Integer firstCount = jdbc.queryForObject(
                "SELECT target_group_count FROM marketing_task WHERE id = ?",
                Integer.class,
                firstTaskId);
        Integer secondCount = jdbc.queryForObject(
                "SELECT target_group_count FROM marketing_task WHERE id = ?",
                Integer.class,
                secondTaskId);
        assertThat(firstCount).isEqualTo(1);
        assertThat(secondCount).isEqualTo(1);
    }
```

Replace `insertGroupLink(long now)` so every helper call generates a unique URL:

```java
    private Long insertGroupLink(long now) {
        return insertAndReturnId("""
                INSERT INTO group_link
                    (tenant_id, link_url, group_name, origin, membership_state, created_at, updated_at)
                VALUES
                    (?, ?, '发送结果群', 5, 2, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "https://chat.whatsapp.com/sendresult-" + now);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
    }
```

Update the existing `successResultRollsUpAttemptSnapshotToDynamicTargetDetail` assertion to:

```java
        assertThat(target.get("group_link_url"))
                .isEqualTo("https://chat.whatsapp.com/sendresult-" + now);
```

- [ ] **Step 4: Add a transaction rollback database test**

Add the static import:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

Add the test:

```java
    @Test
    void counterUpdateFailureRollsBackAttemptAndSuccessfulGroupFact() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("success-group-rollback-" + now, now);
        Long targetId = insertDynamicTarget(taskId, now);
        Long groupLinkId = insertGroupLink(now);
        String groupJid = "120363sendresult-rollback@g.us";
        Long attemptId = insertSubmittedAttempt(taskId, targetId, groupLinkId, groupJid, 1L, now);
        jdbc.update("UPDATE marketing_task SET deleted_at = ? WHERE id = ?", now + 500, taskId);

        assertThatThrownBy(() -> service.handleSendResultReported(
                successEvent(taskId, targetId, attemptId, groupJid, 1L, now + 1_000)))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("累计成功群组数量更新失败");

        Integer attemptStatus = jdbc.queryForObject(
                "SELECT status FROM marketing_task_send_attempt WHERE id = ?",
                Integer.class,
                attemptId);
        Integer factRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM marketing_task_success_group WHERE marketing_task_id = ?",
                Integer.class,
                taskId);
        assertThat(attemptStatus).isZero();
        assertThat(factRows).isZero();
    }
```

- [ ] **Step 5: Run the database tests and verify GREEN**

Run from `armada/armada-api`:

```bash
./dbtest.sh MarketingSendResultServiceImplDbTest
```

Expected: PASS; the same group has one fact and two successful messages, distinct groups count separately, and the forced task-counter failure rolls back attempt and fact writes.

- [ ] **Step 6: Commit database behavior tests**

```bash
git add armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplDbTest.java
git commit -m "test(marketing): verify successful group deduplication"
```

### Task 6: Rename the Frontend Column to the New Business Meaning

**Files:**
- Create: `src/views/task/group-marketing/components/GroupMarketingTaskCountUi.test.ts`
- Modify: `src/views/task/group-marketing/components/GroupMarketingTaskTable.vue:148`
- Modify: `src/views/task/group-marketing/constants.ts:17`

- [ ] **Step 1: Write the failing source-level UI test**

Create `GroupMarketingTaskCountUi.test.ts`:

```ts
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

function source(relativePath: string): string {
  return readFileSync(new URL(relativePath, import.meta.url), "utf8");
}

describe("group marketing cumulative successful group count", () => {
  it("labels targetGroupCount as cumulative successful groups", () => {
    const table = source("./GroupMarketingTaskTable.vue");
    const constants = source("../constants.ts");

    assert.match(table, /label="累计有效群组数量"/);
    assert.doesNotMatch(table, /label="营销群组数量"/);
    assert.match(
      constants,
      /label: "累计有效群组数量"[\s\S]*?prop: "targetGroupCount"/
    );
    assert.doesNotMatch(constants, /label: "营销群组数量"/);
  });
});
```

- [ ] **Step 2: Run the UI test and verify RED**

Run from `wheel-saas-pure-web`:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs src/views/task/group-marketing/components/GroupMarketingTaskCountUi.test.ts
```

Expected: FAIL because both sources still use “营销群组数量”.

- [ ] **Step 3: Change the table and column metadata**

In `GroupMarketingTaskTable.vue`, replace the column declaration with:

```vue
        <el-table-column
          v-if="!dynamicColumns[4].hide"
          label="累计有效群组数量"
          width="160"
        >
          <template #default="{ row }">
            {{ row.targetGroupCount ?? 0 }} 个群
          </template>
        </el-table-column>
```

In `constants.ts`, replace the column entry with:

```ts
  {
    label: "累计有效群组数量",
    prop: "targetGroupCount",
    width: 160
  },
```

- [ ] **Step 4: Run frontend tests and type checks**

Run:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs src/views/task/group-marketing/components/GroupMarketingTaskCountUi.test.ts
```

Expected: PASS.

Run:

```bash
./node_modules/.bin/tsc --noEmit
```

Expected: PASS.

Run:

```bash
./node_modules/.bin/vue-tsc --noEmit --skipLibCheck
```

Expected: PASS.

- [ ] **Step 5: Commit the frontend wording**

```bash
git add src/views/task/group-marketing/components/GroupMarketingTaskCountUi.test.ts src/views/task/group-marketing/components/GroupMarketingTaskTable.vue src/views/task/group-marketing/constants.ts
git commit -m "fix(marketing): label cumulative successful groups"
```

### Task 7: Full Focused Verification

**Files:**
- Verify all backend and frontend files changed by Tasks 1–6.

- [ ] **Step 1: Run backend unit and SQL-shape tests**

From `armada/armada-api`:

```bash
mvn -q -Dtest=MarketingSuccessfulGroupMigrationSqlTest,MarketingTaskMapperSqlShapeTest,MarketingSendResultServiceImplTest test
```

Expected: PASS.

- [ ] **Step 2: Run backend database tests**

```bash
./dbtest.sh MarketingTaskDataModelMigrationDbTest,MarketingTaskCreateReadDbTest,MarketingSendResultServiceImplDbTest
```

Expected: PASS with Flyway at V051 and no assertion failures.

- [ ] **Step 3: Compile the backend**

```bash
mvn -q -DskipTests package
```

Expected: exit code 0.

- [ ] **Step 4: Run frontend source tests**

From `wheel-saas-pure-web`:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs src/views/task/group-marketing/components/GroupMarketingTaskCountUi.test.ts
```

Expected: PASS.

Run the adjacent lifecycle regression test:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs src/views/task/group-marketing/components/GroupMarketingTaskLifecycleUi.test.ts
```

Expected: PASS.

- [ ] **Step 5: Run frontend type checks**

```bash
./node_modules/.bin/tsc --noEmit
```

Expected: PASS.

```bash
./node_modules/.bin/vue-tsc --noEmit --skipLibCheck
```

Expected: PASS.

- [ ] **Step 6: Check both worktrees without touching unrelated changes**

From `armada`:

```bash
git diff --check
git status --short
```

From `wheel-saas-pure-web`:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only the intended commits plus pre-existing unrelated worktree entries are present.

## Deployment Handoff

Deployment is not part of this implementation plan. Before applying V051 to a confirmed target environment, run this read-only diagnostic and record the result:

```sql
SELECT tenant_id,
       COUNT(*) AS successful_attempts_without_group_jid
FROM marketing_task_send_attempt
WHERE status = 1
  AND (group_jid IS NULL OR TRIM(group_jid) = '')
GROUP BY tenant_id
ORDER BY tenant_id;
```

Rows returned by this query cannot be included in the historical group count because they do not contain the required unique group ID. Do not deploy, reload, or run this query against a remote database without confirming the target environment with the user.

## Completion Criteria

- New fixed and dynamic marketing tasks both start with `targetGroupCount = 0`.
- A queued, submitted, failed, skipped, or retry-pending attempt never changes the group counter.
- The first successful attempt for a task and trimmed `group_jid` creates one immutable fact and increments the counter once.
- Later successes for the same task and group still increment successful-message count but do not increment group count.
- Different groups under the same task increment separately; the same group under different tasks increments once per task.
- Group loss or later failure cannot remove a success-group fact or decrement the counter.
- Historical task counters are backfilled from distinct successful attempt JIDs, not from creation-time targets.
- The list displays “累计有效群组数量” and continues reading backend `targetGroupCount`.
- Current available group count remains out of scope.
