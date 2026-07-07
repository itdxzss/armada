# Group Creation Marketing Account Retry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make each 建群营销执行项 keep switching to another normal online account and recreating a new group when group creation or the one-shot marketing send fails, until no usable account remains.

**Architecture:** Add retry history to `group_creation_marketing_item`, then centralize retry decisions in a small service used by both `GroupCreationMarketingWorker` and `MarketingSendResultServiceImpl`. Mapper updates remain state-guarded so stale send-result events cannot reset an item that has already moved to a newer attempt.

**Tech Stack:** Java 17, Spring Boot, MyBatis XML mappers, Flyway SQL migrations, Jackson, JUnit 5, Mockito, AssertJ.

---

## File Structure

- Create `armada-api/src/main/resources/db/migration/V043__group_creation_marketing_retry_history.sql`: adds `retry_history_json` to `group_creation_marketing_item`.
- Modify `armada-api/src/main/java/com/armada/marketing/model/entity/GroupCreationMarketingItem.java`: adds the `retryHistoryJson` property.
- Modify `armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml`: maps `retry_history_json`, adds next-account selection, retry reset, no-available final failure, and item-by-id lookup SQL.
- Modify `armada-api/src/main/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapper.java`: declares the new mapper methods.
- Create `armada-api/src/main/java/com/armada/marketing/model/support/GroupCreationMarketingRetryHistory.java`: parses, appends, serializes, and extracts attempted account IDs from retry history JSON.
- Create `armada-api/src/test/java/com/armada/marketing/model/support/GroupCreationMarketingRetryHistoryTest.java`: covers retry history behavior without database dependencies.
- Create `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingRetryService.java`: owns retry scheduling and no-available final failure decisions.
- Create `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingRetryServiceTest.java`: unit-tests retry decisions with mocked mapper.
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`: delegates account-check and group-create failures to retry service.
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java`: delegates 建群营销 send failures to retry service.
- Modify `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`: replaces direct failed-item expectations for group-create failures with retry expectations.
- Modify `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java`: asserts 建群营销 send failure invokes retry service while normal marketing failure remains unchanged.
- Modify `armada-api/src/test/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapperDbTest.java`: covers account exclusion, retry reset, and no-available final failure.
- Modify `armada-api/src/test/java/com/armada/marketing/GroupCreationMarketingMigrationDbTest.java`: asserts the migration column exists.

---

### Task 1: Migration and Item Mapping

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V043__group_creation_marketing_retry_history.sql`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/entity/GroupCreationMarketingItem.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/marketing/GroupCreationMarketingMigrationDbTest.java`

- [ ] **Step 1: Write the failing migration test**

In `GroupCreationMarketingMigrationDbTest#groupCreationMarketingTablesExist`, add:

```java
assertThat(columnType("group_creation_marketing_item", "retry_history_json")).isEqualTo("json");
```

- [ ] **Step 2: Run the migration test to verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
armada-api/dbtest.sh GroupCreationMarketingMigrationDbTest#groupCreationMarketingTablesExist
```

Expected: fail with an assertion or SQL metadata failure because `retry_history_json` does not exist.

- [ ] **Step 3: Add the Flyway migration**

Create `armada-api/src/main/resources/db/migration/V043__group_creation_marketing_retry_history.sql` with:

```sql
SET @gcm_retry_history_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'group_creation_marketing_item'
      AND column_name = 'retry_history_json'
);

SET @gcm_retry_history_sql := IF(
    @gcm_retry_history_exists = 0,
    'ALTER TABLE group_creation_marketing_item ADD COLUMN retry_history_json JSON DEFAULT NULL COMMENT ''换号重试历史'' AFTER participant_result_json',
    'SELECT 1'
);

PREPARE gcm_retry_history_stmt FROM @gcm_retry_history_sql;
EXECUTE gcm_retry_history_stmt;
DEALLOCATE PREPARE gcm_retry_history_stmt;
```

- [ ] **Step 4: Add entity mapping**

In `GroupCreationMarketingItem`, add the field near `participantResultJson`:

```java
private String retryHistoryJson;
```

Add the accessors:

```java
public String getRetryHistoryJson() {
    return retryHistoryJson;
}

public void setRetryHistoryJson(String retryHistoryJson) {
    this.retryHistoryJson = retryHistoryJson;
}
```

In `GroupCreationMarketingTaskMapper.xml`, update `ItemResultMap`:

```xml
<result column="retry_history_json" property="retryHistoryJson"/>
```

Update `ItemColumns` so it includes `retry_history_json` immediately after `participant_result_json`:

```xml
participant_result_json, retry_history_json, marketing_task_id, marketing_target_id, marketing_attempt_id, command_id,
```

- [ ] **Step 5: Run the migration test to verify GREEN**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
armada-api/dbtest.sh GroupCreationMarketingMigrationDbTest#groupCreationMarketingTablesExist
```

Expected: pass.

- [ ] **Step 6: Commit**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/resources/db/migration/V043__group_creation_marketing_retry_history.sql \
  armada-api/src/main/java/com/armada/marketing/model/entity/GroupCreationMarketingItem.java \
  armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml \
  armada-api/src/test/java/com/armada/marketing/GroupCreationMarketingMigrationDbTest.java
git commit -m "feat: add group creation retry history column"
```

Expected: one commit containing only the migration, item mapping, mapper result mapping, and migration test.

---

### Task 2: Retry History Utility

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/model/support/GroupCreationMarketingRetryHistory.java`
- Create: `armada-api/src/test/java/com/armada/marketing/model/support/GroupCreationMarketingRetryHistoryTest.java`

- [ ] **Step 1: Write failing utility tests**

Create `GroupCreationMarketingRetryHistoryTest`:

```java
package com.armada.marketing.model.support;

import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroupCreationMarketingRetryHistoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void appendRecordsAttemptAndReturnsAttemptedAccountIds() {
        GroupCreationMarketingAccountCandidate account = account(811L, "6285378444041", "acc_6285378444041");

        GroupCreationMarketingRetryHistory.Snapshot snapshot = GroupCreationMarketingRetryHistory.append(
                objectMapper,
                null,
                account,
                GroupCreationMarketingRetryHistory.PHASE_GROUP_CREATE,
                "GROUP_CREATE_FAILED",
                "协议层错误 500 INTERNAL_ERROR: rate-overlimit",
                null,
                1783395965156L);

        assertThat(snapshot.attemptedAccountIds()).containsExactly(811L);
        assertThat(snapshot.json())
                .contains("\"accountId\":811")
                .contains("\"accountPhone\":\"6285378444041\"")
                .contains("\"protocolAccountId\":\"acc_6285378444041\"")
                .contains("\"phase\":\"GROUP_CREATE\"")
                .contains("\"reasonCode\":\"GROUP_CREATE_FAILED\"")
                .contains("\"failedAt\":1783395965156");
    }

    @Test
    void appendPreservesExistingAttemptsAndAddsGroupJidForSendFailure() {
        GroupCreationMarketingAccountCandidate first = account(811L, "6285378444041", "acc_6285378444041");
        GroupCreationMarketingRetryHistory.Snapshot firstSnapshot = GroupCreationMarketingRetryHistory.append(
                objectMapper,
                null,
                first,
                GroupCreationMarketingRetryHistory.PHASE_GROUP_CREATE,
                "GROUP_CREATE_FAILED",
                "rate-overlimit",
                null,
                1000L);

        GroupCreationMarketingAccountCandidate second = account(812L, "6282313663114", "acc_6282313663114");
        GroupCreationMarketingRetryHistory.Snapshot secondSnapshot = GroupCreationMarketingRetryHistory.append(
                objectMapper,
                firstSnapshot.json(),
                second,
                GroupCreationMarketingRetryHistory.PHASE_MESSAGE_SEND,
                "SEND_FAILED",
                "forbidden",
                "120363retry@g.us",
                2000L);

        assertThat(secondSnapshot.attemptedAccountIds()).containsExactly(811L, 812L);
        assertThat(secondSnapshot.json())
                .contains("\"groupJid\":\"120363retry@g.us\"")
                .contains("\"phase\":\"MESSAGE_SEND\"");
    }

    private static GroupCreationMarketingAccountCandidate account(Long id, String phone, String protocolAccountId) {
        GroupCreationMarketingAccountCandidate account = new GroupCreationMarketingAccountCandidate();
        account.setAccountId(id);
        account.setAccountPhone(phone);
        account.setProtocolAccountId(protocolAccountId);
        return account;
    }
}
```

- [ ] **Step 2: Run the utility test to verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=GroupCreationMarketingRetryHistoryTest test
```

Expected: fail at compilation because `GroupCreationMarketingRetryHistory` does not exist.

- [ ] **Step 3: Implement the utility**

Create `GroupCreationMarketingRetryHistory`:

```java
package com.armada.marketing.model.support;

import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

public final class GroupCreationMarketingRetryHistory {

    public static final String PHASE_ACCOUNT_CHECK = "ACCOUNT_CHECK";
    public static final String PHASE_GROUP_CREATE = "GROUP_CREATE";
    public static final String PHASE_MESSAGE_SEND = "MESSAGE_SEND";

    private GroupCreationMarketingRetryHistory() {
    }

    public static Snapshot append(ObjectMapper objectMapper,
                                  String currentJson,
                                  GroupCreationMarketingAccountCandidate account,
                                  String phase,
                                  String reasonCode,
                                  String reasonMessage,
                                  String groupJid,
                                  long failedAt) {
        History history = parse(objectMapper, currentJson);
        history.attempts().add(new Attempt(
                account == null ? null : account.getAccountId(),
                account == null ? null : account.getAccountPhone(),
                account == null ? null : account.getProtocolAccountId(),
                phase,
                reasonCode,
                reasonMessage,
                groupJid,
                failedAt));
        String json = write(objectMapper, history);
        return new Snapshot(json, attemptedAccountIds(history));
    }

    public static List<Long> attemptedAccountIds(ObjectMapper objectMapper, String currentJson) {
        return attemptedAccountIds(parse(objectMapper, currentJson));
    }

    private static History parse(ObjectMapper objectMapper, String currentJson) {
        if (currentJson == null || currentJson.isBlank()) {
            return new History(new ArrayList<>());
        }
        try {
            History history = objectMapper.readValue(currentJson, History.class);
            return new History(history.attempts() == null ? new ArrayList<>() : new ArrayList<>(history.attempts()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("建群营销换号重试历史解析失败", ex);
        }
    }

    private static String write(ObjectMapper objectMapper, History history) {
        try {
            return objectMapper.writeValueAsString(history);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("建群营销换号重试历史序列化失败", ex);
        }
    }

    private static List<Long> attemptedAccountIds(History history) {
        return history.attempts().stream()
                .map(Attempt::accountId)
                .filter(id -> id != null)
                .distinct()
                .toList();
    }

    public record Snapshot(String json, List<Long> attemptedAccountIds) {
    }

    public record History(List<Attempt> attempts) {
    }

    public record Attempt(Long accountId,
                          String accountPhone,
                          String protocolAccountId,
                          String phase,
                          String reasonCode,
                          String reasonMessage,
                          String groupJid,
                          Long failedAt) {
    }
}
```

- [ ] **Step 4: Run the utility test to verify GREEN**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=GroupCreationMarketingRetryHistoryTest test
```

Expected: pass.

- [ ] **Step 5: Commit**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/marketing/model/support/GroupCreationMarketingRetryHistory.java \
  armada-api/src/test/java/com/armada/marketing/model/support/GroupCreationMarketingRetryHistoryTest.java
git commit -m "feat: add group creation retry history helper"
```

Expected: one commit containing only the retry history utility and its tests.

---

### Task 3: Mapper Retry Operations

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapperDbTest.java`

- [ ] **Step 1: Write failing DB tests for account exclusion and retry reset**

Add this test to `GroupCreationMarketingTaskMapperDbTest`:

```java
@Test
void accountRetrySelectsNextAvailableAccountAndResetsItemToPending() {
    long now = System.currentTimeMillis();
    Long accountGroupId = insertAccountGroup("mapper-retry-" + now, now);
    Long firstAccountId = insertAccount(accountGroupId, "6285378444041", "acc_6285378444041", now);
    Long secondAccountId = insertAccount(accountGroupId, "6282313663114", "acc_6282313663114", now);
    insertAccountState(firstAccountId, now);
    insertAccountState(secondAccountId, now);
    Long taskId = insertTask("mapper-retry-" + now, 1, now, accountGroupId);
    Long itemId = insertItem(taskId, 0, GroupCreationMarketingItemStatus.GROUP_CREATING.code(), null, now);

    var replacement = mapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(
            accountGroupId, java.util.List.of(firstAccountId));

    assertThat(replacement.getAccountId()).isEqualTo(secondAccountId);
    assertThat(mapper.resetItemForAccountRetry(
            itemId,
            GroupCreationMarketingItemStatus.GROUP_CREATING.code(),
            null,
            secondAccountId,
            "6282313663114",
            "acc_6282313663114",
            "{\"attempts\":[{\"accountId\":" + firstAccountId + "}]}",
            now + 1,
            now + 1)).isEqualTo(1);

    Map<String, Object> row = jdbc.queryForMap("""
            SELECT status, account_id, account_phone, protocol_account_id,
                   group_jid, command_id, reason_code, reason_message,
                   retry_history_json, next_run_at
            FROM group_creation_marketing_item
            WHERE id = ?
            """, itemId);
    assertThat(row.get("status")).isEqualTo(GroupCreationMarketingItemStatus.PENDING.code());
    assertThat(row.get("account_id")).isEqualTo(secondAccountId);
    assertThat(row.get("account_phone")).isEqualTo("6282313663114");
    assertThat(row.get("protocol_account_id")).isEqualTo("acc_6282313663114");
    assertThat(row.get("group_jid")).isNull();
    assertThat(row.get("command_id")).isNull();
    assertThat(row.get("reason_code")).isNull();
    assertThat(row.get("reason_message")).isNull();
    assertThat(String.valueOf(row.get("retry_history_json"))).contains(String.valueOf(firstAccountId));
    assertThat(row.get("next_run_at")).isEqualTo(now + 1);
}
```

Add these helpers to the test class:

```java
private Long insertAccountGroup(String name, long now) {
    return insertReturningId("""
            INSERT INTO account_group (tenant_id, name, remark, system_builtin, created_at, updated_at)
            VALUES (?, ?, NULL, 0, ?, ?)
            """, TEST_TENANT_ID, name, now, now);
}

private Long insertAccount(Long groupId, String phone, String protocolAccountId, long now) {
    return insertReturningId("""
            INSERT INTO account
                (tenant_id, ws_phone, account_type, ownership, account_group_id, protocol_account_id, created_at, updated_at)
            VALUES (?, ?, 1, 1, ?, ?, ?, ?)
            """, TEST_TENANT_ID, phone, groupId, protocolAccountId, now, now);
}

private void insertAccountState(Long accountId, long now) {
    jdbc.update("""
            INSERT INTO account_state
                (tenant_id, account_id, account_state, login_state, risk_status, mute_status, created_at, updated_at)
            VALUES (?, ?, 2, 1, 1, NULL, ?, ?)
            """, TEST_TENANT_ID, accountId, now, now);
}
```

Add an overload of `insertTask`:

```java
private Long insertTask(String name, int matchedItemCount, long now, Long accountGroupId) {
    return insertReturningId("""
            INSERT INTO group_creation_marketing_task
                (tenant_id, task_name, account_group_id, account_group_name,
                 marketing_template_id, marketing_template_name, status,
                 matched_item_count, unmatched_file_count, success_count,
                 failed_count, abandoned_count, created_at, updated_at)
            VALUES (?, ?, ?, 'A组', 1, '模板', 1, ?, 0, 0, 0, 0, ?, ?)
            """, TEST_TENANT_ID, name, accountGroupId, matchedItemCount, now, now);
}
```

Add `import java.util.Map;` near the other imports.

- [ ] **Step 2: Write failing DB tests for no available account and stale send event**

Add:

```java
@Test
void noAvailableAccountMarksItemFailedAndFinalizesParent() {
    long now = System.currentTimeMillis();
    Long taskId = insertTask("mapper-no-account-" + now, 1, now);
    Long itemId = insertItem(taskId, 0, GroupCreationMarketingItemStatus.MARKETING_SENDING.code(), null, now);
    jdbc.update("UPDATE group_creation_marketing_item SET command_id = ?, group_jid = ? WHERE id = ?",
            "cmd_stale_guard", "120363old@g.us", itemId);

    assertThat(mapper.markItemNoAvailableAccount(
            itemId,
            GroupCreationMarketingItemStatus.MARKETING_SENDING.code(),
            "cmd_stale_guard",
            "{\"attempts\":[{\"phase\":\"MESSAGE_SEND\"}]}",
            now + 2)).isEqualTo(1);

    Map<String, Object> item = jdbc.queryForMap("""
            SELECT status, reason_code, reason_message, retry_history_json
            FROM group_creation_marketing_item
            WHERE id = ?
            """, itemId);
    assertThat(item.get("status")).isEqualTo(GroupCreationMarketingItemStatus.FAILED.code());
    assertThat(item.get("reason_code")).isEqualTo("NO_AVAILABLE_ACCOUNT");
    assertThat(item.get("reason_message")).isEqualTo("没有可用账号");
    assertThat(String.valueOf(item.get("retry_history_json"))).contains("MESSAGE_SEND");
    assertThat(taskColumn(taskId, "failed_count")).isEqualTo(1);
    assertThat(taskStatus(taskId)).isEqualTo(GroupCreationMarketingTaskStatus.FAILED.code());
}

@Test
void retryResetRequiresMatchingCommandForSendingItems() {
    long now = System.currentTimeMillis();
    Long taskId = insertTask("mapper-stale-" + now, 1, now);
    Long itemId = insertItem(taskId, 0, GroupCreationMarketingItemStatus.MARKETING_SENDING.code(), null, now);
    jdbc.update("UPDATE group_creation_marketing_item SET command_id = ? WHERE id = ?", "cmd_new", itemId);

    assertThat(mapper.resetItemForAccountRetry(
            itemId,
            GroupCreationMarketingItemStatus.MARKETING_SENDING.code(),
            "cmd_old",
            900L,
            "628900",
            "acc_628900",
            "{\"attempts\":[]}",
            now + 3,
            now + 3)).isZero();
}
```

- [ ] **Step 3: Run mapper DB tests to verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
armada-api/dbtest.sh GroupCreationMarketingTaskMapperDbTest
```

Expected: fail at compilation because the mapper methods do not exist.

- [ ] **Step 4: Add mapper interface methods**

Add these methods to `GroupCreationMarketingTaskMapper`:

```java
GroupCreationMarketingItem selectItemById(@Param("id") Long id);

GroupCreationMarketingAccountCandidate selectFirstAvailableAccountCandidateByGroupIdExcluding(
        @Param("accountGroupId") Long accountGroupId,
        @Param("excludedAccountIds") List<Long> excludedAccountIds);

int resetItemForAccountRetry(@Param("id") Long id,
                             @Param("fromStatus") int fromStatus,
                             @Param("commandId") String commandId,
                             @Param("accountId") Long accountId,
                             @Param("accountPhone") String accountPhone,
                             @Param("protocolAccountId") String protocolAccountId,
                             @Param("retryHistoryJson") String retryHistoryJson,
                             @Param("nextRunAt") long nextRunAt,
                             @Param("updatedAt") long updatedAt);

int updateItemAccountForClaimRetry(@Param("id") Long id,
                                   @Param("accountId") Long accountId,
                                   @Param("accountPhone") String accountPhone,
                                   @Param("protocolAccountId") String protocolAccountId,
                                   @Param("retryHistoryJson") String retryHistoryJson,
                                   @Param("updatedAt") long updatedAt);

int markItemNoAvailableAccount(@Param("id") Long id,
                               @Param("fromStatus") int fromStatus,
                               @Param("commandId") String commandId,
                               @Param("retryHistoryJson") String retryHistoryJson,
                               @Param("finishedAt") long finishedAt);
```

- [ ] **Step 5: Add mapper XML SQL**

Add this select:

```xml
<select id="selectItemById" resultMap="ItemResultMap">
    SELECT <include refid="ItemColumns"/>
    FROM group_creation_marketing_item
    WHERE id = #{id}
    LIMIT 1
</select>
```

Add this select:

```xml
<select id="selectFirstAvailableAccountCandidateByGroupIdExcluding"
        resultType="com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate">
    SELECT a.id AS accountId,
           a.ws_phone AS accountPhone,
           a.protocol_account_id AS protocolAccountId,
           s.account_state AS accountState,
           s.login_state AS loginState,
           s.risk_status AS riskStatus,
           s.mute_status AS muteStatus
    FROM account a
    JOIN account_state s ON s.account_id = a.id AND s.tenant_id = a.tenant_id
    WHERE a.deleted_at IS NULL
      AND a.account_group_id = #{accountGroupId}
      AND a.protocol_account_id IS NOT NULL
      AND TRIM(a.protocol_account_id) &lt;&gt; ''
      AND s.login_state = 1
      AND s.account_state = 2
      AND (s.risk_status IS NULL OR s.risk_status = 1)
      AND s.mute_status IS NULL
      <if test="excludedAccountIds != null and excludedAccountIds.size() &gt; 0">
          AND a.id NOT IN
          <foreach collection="excludedAccountIds" item="excludedId" open="(" close=")" separator=",">
              #{excludedId}
          </foreach>
      </if>
    ORDER BY a.id ASC
    LIMIT 1
</select>
```

Add this update for pre-protocol account replacement after an item has already been claimed:

```xml
<update id="updateItemAccountForClaimRetry">
    UPDATE group_creation_marketing_item
    SET account_id = #{accountId},
        account_phone = #{accountPhone},
        protocol_account_id = #{protocolAccountId},
        retry_history_json = #{retryHistoryJson},
        updated_at = #{updatedAt}
    WHERE id = #{id}
      AND status = 2
</update>
```

Add this update for post-protocol failures that must recreate the group on a later scheduler tick:

```xml
<update id="resetItemForAccountRetry">
    UPDATE group_creation_marketing_item
    SET account_id = #{accountId},
        account_phone = #{accountPhone},
        protocol_account_id = #{protocolAccountId},
        group_jid = NULL,
        group_link_id = NULL,
        participant_result_json = NULL,
        marketing_task_id = NULL,
        marketing_target_id = NULL,
        marketing_attempt_id = NULL,
        command_id = NULL,
        status = 1,
        reason_code = NULL,
        reason_message = NULL,
        next_run_at = #{nextRunAt},
        finished_at = NULL,
        retry_history_json = #{retryHistoryJson},
        updated_at = #{updatedAt}
    WHERE id = #{id}
      AND status = #{fromStatus}
      <if test="commandId != null and commandId != ''">
          AND command_id = #{commandId}
      </if>
</update>
```

Add this update:

```xml
<update id="markItemNoAvailableAccount">
    UPDATE group_creation_marketing_item i
    JOIN group_creation_marketing_task t ON t.id = i.task_id
    SET i.status = 5,
        i.reason_code = 'NO_AVAILABLE_ACCOUNT',
        i.reason_message = '没有可用账号',
        i.retry_history_json = #{retryHistoryJson},
        i.finished_at = #{finishedAt},
        i.updated_at = #{finishedAt},
        t.failed_count = t.failed_count + 1,
        t.status = CASE
            WHEN t.success_count + t.failed_count + 1 + t.abandoned_count &gt;= t.matched_item_count
                THEN CASE WHEN t.success_count = 0 AND t.abandoned_count = 0 THEN 4 ELSE 5 END
            ELSE t.status
        END,
        t.finished_at = CASE
            WHEN t.success_count + t.failed_count + 1 + t.abandoned_count &gt;= t.matched_item_count
                THEN #{finishedAt}
            ELSE t.finished_at
        END,
        t.updated_at = #{finishedAt}
    WHERE i.id = #{id}
      AND i.status = #{fromStatus}
      <if test="commandId != null and commandId != ''">
          AND i.command_id = #{commandId}
      </if>
</update>
```

- [ ] **Step 6: Run mapper DB tests to verify GREEN**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
armada-api/dbtest.sh GroupCreationMarketingTaskMapperDbTest
```

Expected: pass.

- [ ] **Step 7: Commit**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapper.java \
  armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml \
  armada-api/src/test/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapperDbTest.java
git commit -m "feat: add group creation retry mapper operations"
```

Expected: one commit containing mapper API, XML, and DB tests.

---

### Task 4: Retry Service

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingRetryService.java`
- Create: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingRetryServiceTest.java`

- [ ] **Step 1: Write failing retry service tests**

Create `GroupCreationMarketingRetryServiceTest`:

```java
package com.armada.marketing.service;

import com.armada.marketing.mapper.GroupCreationMarketingTaskMapper;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.enums.GroupCreationMarketingItemStatus;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.armada.marketing.service.impl.GroupCreationMarketingRetryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupCreationMarketingRetryServiceTest {

    private final GroupCreationMarketingTaskMapper mapper = mock(GroupCreationMarketingTaskMapper.class);
    private final GroupCreationMarketingRetryService service =
            new GroupCreationMarketingRetryService(mapper, new ObjectMapper());

    @Test
    void replaceDuringClaimUpdatesAccountSnapshotAndContinuesCurrentClaim() {
        GroupCreationMarketingItem item = item(GroupCreationMarketingItemStatus.GROUP_CREATING.code(), null);
        GroupCreationMarketingTask task = task();
        GroupCreationMarketingAccountCandidate failed = account(811L, "6285378444041", "acc_6285378444041");
        GroupCreationMarketingAccountCandidate replacement = account(812L, "6282313663114", "acc_6282313663114");
        when(mapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(eq(8L), anyList())).thenReturn(replacement);
        when(mapper.updateItemAccountForClaimRetry(eq(11L), eq(812L), eq("6282313663114"),
                eq("acc_6282313663114"), org.mockito.ArgumentMatchers.contains("ACCOUNT_CHECK"), anyLong()))
                .thenReturn(1);

        var chosen = service.replaceDuringClaim(
                item, task, failed, GroupCreationMarketingRetryService.PHASE_ACCOUNT_CHECK,
                "ACCOUNT_OFFLINE", "账号离线", 1783395965000L);

        assertThat(chosen).contains(replacement);
        assertThat(item.getAccountId()).isEqualTo(812L);
        assertThat(item.getAccountPhone()).isEqualTo("6282313663114");
        assertThat(item.getProtocolAccountId()).isEqualTo("acc_6282313663114");
        verify(mapper).updateItemAccountForClaimRetry(eq(11L), eq(812L), eq("6282313663114"),
                eq("acc_6282313663114"), org.mockito.ArgumentMatchers.contains("ACCOUNT_OFFLINE"), anyLong());
    }

    @Test
    void retryFromGroupCreatingResetsItemToPendingWithReplacementAccount() {
        GroupCreationMarketingItem item = item(GroupCreationMarketingItemStatus.GROUP_CREATING.code(), null);
        GroupCreationMarketingTask task = task();
        GroupCreationMarketingAccountCandidate failed = account(811L, "6285378444041", "acc_6285378444041");
        GroupCreationMarketingAccountCandidate replacement = account(812L, "6282313663114", "acc_6282313663114");
        when(mapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(eq(8L), anyList())).thenReturn(replacement);
        when(mapper.resetItemForAccountRetry(eq(11L), eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()),
                eq(null), eq(812L), eq("6282313663114"), eq("acc_6282313663114"),
                org.mockito.ArgumentMatchers.contains("6285378444041"), anyLong(), anyLong())).thenReturn(1);

        GroupCreationMarketingRetryService.RetryDecision decision = service.retryFromGroupCreating(
                item, task, failed, GroupCreationMarketingRetryService.PHASE_GROUP_CREATE,
                "GROUP_CREATE_FAILED", "rate-overlimit", null, 1783395965156L);

        assertThat(decision).isEqualTo(GroupCreationMarketingRetryService.RetryDecision.RETRY_SCHEDULED);
        verify(mapper).selectFirstAvailableAccountCandidateByGroupIdExcluding(eq(8L), eq(List.of(811L)));
    }

    @Test
    void retryFromGroupCreatingFinalFailsWhenNoReplacementExists() {
        GroupCreationMarketingItem item = item(GroupCreationMarketingItemStatus.GROUP_CREATING.code(), null);
        GroupCreationMarketingTask task = task();
        GroupCreationMarketingAccountCandidate failed = account(811L, "6285378444041", "acc_6285378444041");
        when(mapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(eq(8L), anyList())).thenReturn(null);
        when(mapper.markItemNoAvailableAccount(eq(11L), eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()),
                eq(null), org.mockito.ArgumentMatchers.contains("rate-overlimit"), anyLong())).thenReturn(1);

        GroupCreationMarketingRetryService.RetryDecision decision = service.retryFromGroupCreating(
                item, task, failed, GroupCreationMarketingRetryService.PHASE_GROUP_CREATE,
                "GROUP_CREATE_FAILED", "rate-overlimit", null, 1783395965156L);

        assertThat(decision).isEqualTo(GroupCreationMarketingRetryService.RetryDecision.FINAL_FAILED);
    }

    @Test
    void retryFromMarketingSendingUsesLoadedItemAndCommandGuard() {
        GroupCreationMarketingItem item = item(GroupCreationMarketingItemStatus.MARKETING_SENDING.code(), "cmd_old");
        item.setRetryHistoryJson("{\"attempts\":[{\"accountId\":810}]}");
        GroupCreationMarketingTask task = task();
        GroupCreationMarketingAccountCandidate replacement = account(812L, "6282313663114", "acc_6282313663114");
        when(mapper.selectItemById(11L)).thenReturn(item);
        when(mapper.selectTaskById(22L)).thenReturn(task);
        when(mapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(eq(8L), anyList())).thenReturn(replacement);
        when(mapper.resetItemForAccountRetry(eq(11L), eq(GroupCreationMarketingItemStatus.MARKETING_SENDING.code()),
                eq("cmd_old"), eq(812L), eq("6282313663114"), eq("acc_6282313663114"),
                org.mockito.ArgumentMatchers.contains("MESSAGE_SEND"), anyLong(), anyLong())).thenReturn(1);

        GroupCreationMarketingRetryService.RetryDecision decision = service.retryFromMarketingSending(
                11L, "cmd_old", "SEND_FAILED", "forbidden", "120363old@g.us", 1783395966000L);

        assertThat(decision).isEqualTo(GroupCreationMarketingRetryService.RetryDecision.RETRY_SCHEDULED);
        verify(mapper).selectFirstAvailableAccountCandidateByGroupIdExcluding(eq(8L), eq(List.of(810L, 7L)));
    }

    private static GroupCreationMarketingItem item(int status, String commandId) {
        GroupCreationMarketingItem item = new GroupCreationMarketingItem();
        item.setId(11L);
        item.setTaskId(22L);
        item.setAccountId(7L);
        item.setAccountPhone("8613000000000");
        item.setProtocolAccountId("acc_7");
        item.setStatus(status);
        item.setCommandId(commandId);
        return item;
    }

    private static GroupCreationMarketingTask task() {
        GroupCreationMarketingTask task = new GroupCreationMarketingTask();
        task.setId(22L);
        task.setAccountGroupId(8L);
        return task;
    }

    private static GroupCreationMarketingAccountCandidate account(Long id, String phone, String protocolAccountId) {
        GroupCreationMarketingAccountCandidate account = new GroupCreationMarketingAccountCandidate();
        account.setAccountId(id);
        account.setAccountPhone(phone);
        account.setProtocolAccountId(protocolAccountId);
        return account;
    }
}
```

- [ ] **Step 2: Run retry service tests to verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=GroupCreationMarketingRetryServiceTest test
```

Expected: fail at compilation because `GroupCreationMarketingRetryService` does not exist.

- [ ] **Step 3: Implement retry service**

Create `GroupCreationMarketingRetryService`:

```java
package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.GroupCreationMarketingTaskMapper;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.enums.GroupCreationMarketingItemStatus;
import com.armada.marketing.model.support.GroupCreationMarketingRetryHistory;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupCreationMarketingRetryService {

    public static final String PHASE_ACCOUNT_CHECK = GroupCreationMarketingRetryHistory.PHASE_ACCOUNT_CHECK;
    public static final String PHASE_GROUP_CREATE = GroupCreationMarketingRetryHistory.PHASE_GROUP_CREATE;
    public static final String PHASE_MESSAGE_SEND = GroupCreationMarketingRetryHistory.PHASE_MESSAGE_SEND;

    private final GroupCreationMarketingTaskMapper mapper;
    private final ObjectMapper objectMapper;

    public GroupCreationMarketingRetryService(GroupCreationMarketingTaskMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public Optional<GroupCreationMarketingAccountCandidate> replaceDuringClaim(GroupCreationMarketingItem item,
                                                                               GroupCreationMarketingTask task,
                                                                               GroupCreationMarketingAccountCandidate failedAccount,
                                                                               String phase,
                                                                               String reasonCode,
                                                                               String reasonMessage,
                                                                               long now) {
        GroupCreationMarketingRetryHistory.Snapshot snapshot = append(
                item, failedAccount, phase, reasonCode, reasonMessage, item.getGroupJid(), now);
        GroupCreationMarketingAccountCandidate replacement = mapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(
                task.getAccountGroupId(), snapshot.attemptedAccountIds());
        if (replacement == null) {
            mapper.markItemNoAvailableAccount(
                    item.getId(), GroupCreationMarketingItemStatus.GROUP_CREATING.code(), null, snapshot.json(), now);
            return Optional.empty();
        }
        int updated = mapper.updateItemAccountForClaimRetry(
                item.getId(),
                replacement.getAccountId(),
                replacement.getAccountPhone(),
                replacement.getProtocolAccountId(),
                snapshot.json(),
                now);
        if (updated == 0) {
            return Optional.empty();
        }
        item.setAccountId(replacement.getAccountId());
        item.setAccountPhone(replacement.getAccountPhone());
        item.setProtocolAccountId(replacement.getProtocolAccountId());
        item.setRetryHistoryJson(snapshot.json());
        return Optional.of(replacement);
    }

    @Transactional(rollbackFor = Exception.class)
    public RetryDecision retryFromGroupCreating(GroupCreationMarketingItem item,
                                                GroupCreationMarketingTask task,
                                                GroupCreationMarketingAccountCandidate failedAccount,
                                                String phase,
                                                String reasonCode,
                                                String reasonMessage,
                                                String groupJid,
                                                long now) {
        GroupCreationMarketingRetryHistory.Snapshot snapshot = append(
                item, failedAccount, phase, reasonCode, reasonMessage, groupJid, now);
        GroupCreationMarketingAccountCandidate replacement = mapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(
                task.getAccountGroupId(), snapshot.attemptedAccountIds());
        if (replacement == null) {
            int updated = mapper.markItemNoAvailableAccount(
                    item.getId(), GroupCreationMarketingItemStatus.GROUP_CREATING.code(), null, snapshot.json(), now);
            return updated > 0 ? RetryDecision.FINAL_FAILED : RetryDecision.STALE;
        }
        int updated = mapper.resetItemForAccountRetry(
                item.getId(),
                GroupCreationMarketingItemStatus.GROUP_CREATING.code(),
                null,
                replacement.getAccountId(),
                replacement.getAccountPhone(),
                replacement.getProtocolAccountId(),
                snapshot.json(),
                now,
                now);
        return updated > 0 ? RetryDecision.RETRY_SCHEDULED : RetryDecision.STALE;
    }

    @Transactional(rollbackFor = Exception.class)
    public RetryDecision retryFromMarketingSending(Long itemId,
                                                   String commandId,
                                                   String reasonCode,
                                                   String reasonMessage,
                                                   String groupJid,
                                                   long now) {
        GroupCreationMarketingItem item = mapper.selectItemById(itemId);
        if (item == null || item.getTaskId() == null) {
            return RetryDecision.STALE;
        }
        GroupCreationMarketingTask task = mapper.selectTaskById(item.getTaskId());
        if (task == null) {
            return RetryDecision.STALE;
        }
        GroupCreationMarketingAccountCandidate failedAccount = new GroupCreationMarketingAccountCandidate();
        failedAccount.setAccountId(item.getAccountId());
        failedAccount.setAccountPhone(item.getAccountPhone());
        failedAccount.setProtocolAccountId(item.getProtocolAccountId());
        GroupCreationMarketingRetryHistory.Snapshot snapshot = append(
                item, failedAccount, PHASE_MESSAGE_SEND, reasonCode, reasonMessage, groupJid, now);
        GroupCreationMarketingAccountCandidate replacement = mapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(
                task.getAccountGroupId(), snapshot.attemptedAccountIds());
        if (replacement == null) {
            int updated = mapper.markItemNoAvailableAccount(
                    itemId, GroupCreationMarketingItemStatus.MARKETING_SENDING.code(), commandId, snapshot.json(), now);
            return updated > 0 ? RetryDecision.FINAL_FAILED : RetryDecision.STALE;
        }
        int updated = mapper.resetItemForAccountRetry(
                itemId,
                GroupCreationMarketingItemStatus.MARKETING_SENDING.code(),
                commandId,
                replacement.getAccountId(),
                replacement.getAccountPhone(),
                replacement.getProtocolAccountId(),
                snapshot.json(),
                now,
                now);
        return updated > 0 ? RetryDecision.RETRY_SCHEDULED : RetryDecision.STALE;
    }

    private GroupCreationMarketingRetryHistory.Snapshot append(GroupCreationMarketingItem item,
                                                               GroupCreationMarketingAccountCandidate failedAccount,
                                                               String phase,
                                                               String reasonCode,
                                                               String reasonMessage,
                                                               String groupJid,
                                                               long now) {
        return GroupCreationMarketingRetryHistory.append(
                objectMapper,
                item.getRetryHistoryJson(),
                failedAccount,
                phase,
                reasonCode,
                reasonMessage,
                groupJid,
                now);
    }

    public enum RetryDecision {
        RETRY_SCHEDULED,
        FINAL_FAILED,
        STALE
    }
}
```

- [ ] **Step 4: Run retry service tests to verify GREEN**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=GroupCreationMarketingRetryServiceTest test
```

Expected: pass.

- [ ] **Step 5: Commit**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingRetryService.java \
  armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingRetryServiceTest.java
git commit -m "feat: add group creation retry service"
```

Expected: one commit containing only retry service code and unit tests.

---

### Task 5: Worker Group-Create Retry

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`

- [ ] **Step 1: Update worker test setup to use the retry service**

In `GroupCreationMarketingWorkerTest`, add a field:

```java
private GroupCreationMarketingRetryService retryService;
```

In `setUp`, initialize it before the worker:

```java
retryService = new GroupCreationMarketingRetryService(groupCreationMapper, new ObjectMapper());
worker = new GroupCreationMarketingWorker(
        groupCreationMapper,
        templateMapper,
        fileMapper,
        messageComposer,
        outboxService,
        contactPort,
        groupCreatePort,
        retryService,
        new ObjectMapper(),
        transactionManager);
```

- [ ] **Step 2: Replace the existing group-create failure test with a RED retry test**

Replace `processProtocolGroupCreateFailureMarksItemFailed` with:

```java
@Test
void processProtocolGroupCreateFailureSchedulesRetryWithReplacementAccount() {
    GroupCreationMarketingItem item = item();
    GroupCreationMarketingTask task = task(null);
    GroupCreationMarketingAccountCandidate failedAccount =
            account(7L, "8613000000000", "acc_7", AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE);
    GroupCreationMarketingAccountCandidate replacementAccount =
            account(9L, "8613999999999", "acc_9", AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE);
    when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(item));
    when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
            eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
    when(groupCreationMapper.selectTaskById(22L)).thenReturn(task);
    when(groupCreationMapper.selectAccountCandidateByAccountId(7L)).thenReturn(failedAccount);
    when(groupCreatePort.create(eq("acc_7"), eq("活动群-1"), anyList(), eq(true)))
            .thenThrow(new IllegalStateException("rate-overlimit"));
    when(groupCreationMapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(eq(8L), eq(List.of(7L))))
            .thenReturn(replacementAccount);
    when(groupCreationMapper.resetItemForAccountRetry(eq(11L), eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()),
            isNull(), eq(9L), eq("8613999999999"), eq("acc_9"),
            org.mockito.ArgumentMatchers.contains("rate-overlimit"), anyLong(), anyLong())).thenReturn(1);

    worker.processDueItems(10);

    verify(groupCreationMapper).resetItemForAccountRetry(eq(11L),
            eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), isNull(),
            eq(9L), eq("8613999999999"), eq("acc_9"),
            org.mockito.ArgumentMatchers.contains("\"phase\":\"GROUP_CREATE\""), anyLong(), anyLong());
    verify(groupCreationMapper, never()).markItemFailed(eq(11L), eq("GROUP_CREATE_FAILED"),
            any(), any(), anyLong());
    verify(outboxService, never()).enqueueMarketingMessageCommands(any());
}
```

- [ ] **Step 3: Update existing account-check tests to the new no-available-account behavior**

In `offlineAssignedAccountIsReplacedByAvailableGroupAccountBeforeGroupCreate`, replace the old `updateItemAccountIfCreating` stubbing and verification with:

```java
when(groupCreationMapper.updateItemAccountForClaimRetry(eq(11L), eq(9L), eq("8613999999999"),
        eq("acc_9"), org.mockito.ArgumentMatchers.contains("ACCOUNT_OFFLINE"), anyLong())).thenReturn(1);
```

and:

```java
verify(groupCreationMapper).updateItemAccountForClaimRetry(eq(11L), eq(9L), eq("8613999999999"),
        eq("acc_9"), org.mockito.ArgumentMatchers.contains("\"phase\":\"ACCOUNT_CHECK\""), anyLong());
```

In `processOfflineItemIsAbandonedWithoutCallingProtocol`, rename the test to `processOfflineItemFinalFailsWhenNoReplacementExists` and replace the `markItemAbandoned` verification with:

```java
when(groupCreationMapper.markItemNoAvailableAccount(eq(11L), eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()),
        isNull(), org.mockito.ArgumentMatchers.contains("ACCOUNT_OFFLINE"), anyLong())).thenReturn(1);

verify(groupCreationMapper).markItemNoAvailableAccount(eq(11L),
        eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), isNull(),
        org.mockito.ArgumentMatchers.contains("\"reasonCode\":\"ACCOUNT_OFFLINE\""), anyLong());
```

In `processBannedItemIsAbandonedWithoutCallingProtocol`, rename the test to `processBannedItemFinalFailsWhenNoReplacementExists` and replace the `markItemAbandoned` verification with:

```java
when(groupCreationMapper.markItemNoAvailableAccount(eq(11L), eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()),
        isNull(), org.mockito.ArgumentMatchers.contains("ACCOUNT_UNUSABLE"), anyLong())).thenReturn(1);

verify(groupCreationMapper).markItemNoAvailableAccount(eq(11L),
        eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), isNull(),
        org.mockito.ArgumentMatchers.contains("\"reasonCode\":\"ACCOUNT_UNUSABLE\""), anyLong());
```

Keep the `verify(contactPort, never()).saveContact(any(), any(), any())` and `verify(groupCreatePort, never()).create(any(), any(), anyList(), anyBoolean())` assertions.

- [ ] **Step 4: Add a RED test for no available account after group-create failure**

Add:

```java
@Test
void processProtocolGroupCreateFailureFinalFailsWhenNoReplacementAccountExists() {
    GroupCreationMarketingItem item = item();
    GroupCreationMarketingTask task = task(null);
    GroupCreationMarketingAccountCandidate failedAccount =
            account(7L, "8613000000000", "acc_7", AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE);
    when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(item));
    when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
            eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
    when(groupCreationMapper.selectTaskById(22L)).thenReturn(task);
    when(groupCreationMapper.selectAccountCandidateByAccountId(7L)).thenReturn(failedAccount);
    when(groupCreatePort.create(eq("acc_7"), eq("活动群-1"), anyList(), eq(true)))
            .thenThrow(new IllegalStateException("rate-overlimit"));
    when(groupCreationMapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(eq(8L), eq(List.of(7L))))
            .thenReturn(null);
    when(groupCreationMapper.markItemNoAvailableAccount(eq(11L), eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()),
            isNull(), org.mockito.ArgumentMatchers.contains("rate-overlimit"), anyLong())).thenReturn(1);

    worker.processDueItems(10);

    verify(groupCreationMapper).markItemNoAvailableAccount(eq(11L),
            eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), isNull(),
            org.mockito.ArgumentMatchers.contains("\"reasonCode\":\"GROUP_CREATE_FAILED\""), anyLong());
}
```

- [ ] **Step 5: Run worker tests to verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=GroupCreationMarketingWorkerTest test
```

Expected: fail at compilation because `GroupCreationMarketingWorker` constructor does not accept `GroupCreationMarketingRetryService`, or fail behaviorally because group-create failures still call `markItemFailed`.

- [ ] **Step 6: Inject retry service into worker**

Modify `GroupCreationMarketingWorker` fields and constructor:

```java
private final GroupCreationMarketingRetryService retryService;
```

Constructor parameter:

```java
GroupCreationMarketingRetryService retryService,
ObjectMapper objectMapper,
PlatformTransactionManager transactionManager
```

Assignment:

```java
this.retryService = retryService;
```

- [ ] **Step 7: Use retry service for account-check replacement**

In `resolveExecutableAccount`, replace the direct `selectFirstAvailableAccountCandidateByGroupId` path with:

```java
return retryService.replaceDuringClaim(item, task, account,
        GroupCreationMarketingRetryService.PHASE_ACCOUNT_CHECK,
        reasonCode, reasonMessage, now).orElse(null);
```

After this change, remove the now-unused `replaceItemAccount` method if no references remain in the class.

- [ ] **Step 8: Use retry service for group-create failures**

In the `catch (RuntimeException ex)` block around `groupCreatePort.create`, replace `markItemFailed` with:

```java
String reason = readableMessage(ex);
retryService.retryFromGroupCreating(
        item,
        task,
        account,
        GroupCreationMarketingRetryService.PHASE_GROUP_CREATE,
        REASON_GROUP_CREATE_FAILED,
        reason,
        null,
        System.currentTimeMillis());
return;
```

In the no-`groupJid` branch, replace `markItemFailed` with:

```java
String reason = "协议未返回群JID";
retryService.retryFromGroupCreating(
        item,
        task,
        account,
        GroupCreationMarketingRetryService.PHASE_GROUP_CREATE,
        REASON_GROUP_CREATE_FAILED,
        reason,
        null,
        System.currentTimeMillis());
return;
```

- [ ] **Step 9: Run worker tests to verify GREEN**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=GroupCreationMarketingWorkerTest test
```

Expected: pass.

- [ ] **Step 10: Commit**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java \
  armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java
git commit -m "feat: retry group creation marketing group failures with another account"
```

Expected: one commit containing worker behavior and worker unit tests.

---

### Task 6: Marketing Send Failure Retry

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java`

- [ ] **Step 1: Update service test setup**

In `MarketingSendResultServiceImplTest`, add:

```java
private final GroupCreationMarketingRetryService retryService = mock(GroupCreationMarketingRetryService.class);
private final MarketingSendResultServiceImpl service =
        new MarketingSendResultServiceImpl(mapper, groupCreationMapper, retryService);
```

Remove the old two-argument service construction.

- [ ] **Step 2: Replace group creation failure unit test with a RED retry assertion**

Replace `groupCreationFailedEventUpdatesItemByCommandIdWithoutMarketingTables` with:

```java
@Test
void groupCreationFailedEventSchedulesAccountRetryWithoutMarketingTables() {
    ProtocolMessageSendResultReportedEvent event = groupCreationEvent(false);
    when(retryService.retryFromMarketingSending(
            11L, "cmd_gcm_item_11", "SEND_FAILED", "rate limited",
            "120363001@g.us", 1783159200000L))
            .thenReturn(GroupCreationMarketingRetryService.RetryDecision.RETRY_SCHEDULED);

    service.handleSendResultReported(event);

    verify(retryService).retryFromMarketingSending(
            11L, "cmd_gcm_item_11", "SEND_FAILED", "rate limited",
            "120363001@g.us", 1783159200000L);
    verify(groupCreationMapper, never()).markItemFailedByCommandId(
            11L, "cmd_gcm_item_11", "SEND_FAILED", "rate limited", 1783159200000L);
    verify(mapper, never()).markAttemptFailed(9001L, "SEND_FAILED", "rate limited",
            "120363001@g.us", 1783159200000L);
}
```

- [ ] **Step 3: Run send result service tests to verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=MarketingSendResultServiceImplTest test
```

Expected: fail at compilation because the constructor still takes two arguments, or fail behaviorally because failures still call `markItemFailedByCommandId`.

- [ ] **Step 4: Inject retry service and change failure behavior**

Modify `MarketingSendResultServiceImpl` constructor:

```java
private final GroupCreationMarketingRetryService groupCreationRetryService;

public MarketingSendResultServiceImpl(MarketingTaskMapper taskMapper,
                                      GroupCreationMarketingTaskMapper groupCreationMapper,
                                      GroupCreationMarketingRetryService groupCreationRetryService) {
    this.taskMapper = taskMapper;
    this.groupCreationMapper = groupCreationMapper;
    this.groupCreationRetryService = groupCreationRetryService;
}
```

Modify `handleGroupCreationMarketingResult`:

```java
int updated;
if (event.success()) {
    updated = groupCreationMapper.markItemSuccessByCommandId(
            event.groupCreationItemId(), event.commandId(), event.groupJid(), event.messageId(), resultAt);
} else {
    GroupCreationMarketingRetryService.RetryDecision decision =
            groupCreationRetryService.retryFromMarketingSending(
                    event.groupCreationItemId(),
                    event.commandId(),
                    event.reasonCode(),
                    event.reasonMessage(),
                    event.groupJid(),
                    resultAt);
    updated = decision == GroupCreationMarketingRetryService.RetryDecision.STALE ? 0 : 1;
}
```

Keep the existing log branches. The success branch still logs `建群营销发送结果已回写`; failure branch now logs the same message when retry was scheduled or final failure was recorded.

- [ ] **Step 5: Run send result service tests to verify GREEN**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=MarketingSendResultServiceImplTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java \
  armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java
git commit -m "feat: retry group creation marketing sends with another account"
```

Expected: one commit containing send result behavior and unit tests.

---

### Task 7: End-to-End DB Regression for Send Failure Retry

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplDbTest.java`

- [ ] **Step 1: Write a DB test for group creation send failure retry**

Add this test to `MarketingSendResultServiceImplDbTest`:

```java
@Test
void groupCreationSendFailureResetsItemToPendingWithReplacementAccount() {
    long now = System.currentTimeMillis();
    Long accountGroupId = insertAccountGroup("gcm-send-retry-" + now, now);
    Long failedAccountId = insertAccount(accountGroupId, "6285378444041", "acc_6285378444041", now);
    Long replacementAccountId = insertAccount(accountGroupId, "6282313663114", "acc_6282313663114", now);
    insertAccountState(failedAccountId, now);
    insertAccountState(replacementAccountId, now);
    Long taskId = insertGroupCreationTask("gcm-send-retry-" + now, accountGroupId, now);
    Long itemId = insertGroupCreationSendingItem(taskId, failedAccountId, now);

    service.handleSendResultReported(new ProtocolMessageSendResultReportedEvent(
            "evt_gcm_send_retry",
            TEST_TENANT_ID,
            null,
            null,
            null,
            null,
            "acc_6285378444041",
            "120363old@g.us",
            "cmd_gcm_send_retry",
            false,
            null,
            "SEND_FAILED",
            "forbidden",
            now + 1,
            "worker-a",
            taskId,
            itemId,
            "group_creation_marketing"));

    Map<String, Object> item = jdbc.queryForMap("""
            SELECT status, account_id, account_phone, protocol_account_id,
                   group_jid, command_id, reason_code, retry_history_json
            FROM group_creation_marketing_item
            WHERE id = ?
            """, itemId);
    assertThat(item.get("status")).isEqualTo(1);
    assertThat(item.get("account_id")).isEqualTo(replacementAccountId);
    assertThat(item.get("account_phone")).isEqualTo("6282313663114");
    assertThat(item.get("protocol_account_id")).isEqualTo("acc_6282313663114");
    assertThat(item.get("group_jid")).isNull();
    assertThat(item.get("command_id")).isNull();
    assertThat(item.get("reason_code")).isNull();
    assertThat(String.valueOf(item.get("retry_history_json"))).contains("MESSAGE_SEND");
    assertThat(String.valueOf(item.get("retry_history_json"))).contains("120363old@g.us");
}
```

Add helper methods to the DB test:

```java
private Long insertAccountGroup(String name, long now) {
    return insertAndReturnId("""
            INSERT INTO account_group (tenant_id, name, remark, system_builtin, created_at, updated_at)
            VALUES (?, ?, NULL, 0, ?, ?)
            """, ps -> {
        ps.setLong(1, TEST_TENANT_ID);
        ps.setString(2, name);
        ps.setLong(3, now);
        ps.setLong(4, now);
    });
}

private Long insertAccount(Long groupId, String phone, String protocolAccountId, long now) {
    return insertAndReturnId("""
            INSERT INTO account
                (tenant_id, ws_phone, account_type, ownership, account_group_id, protocol_account_id, created_at, updated_at)
            VALUES (?, ?, 1, 1, ?, ?, ?, ?)
            """, ps -> {
        ps.setLong(1, TEST_TENANT_ID);
        ps.setString(2, phone);
        ps.setLong(3, groupId);
        ps.setString(4, protocolAccountId);
        ps.setLong(5, now);
        ps.setLong(6, now);
    });
}

private void insertAccountState(Long accountId, long now) {
    jdbc.update("""
            INSERT INTO account_state
                (tenant_id, account_id, account_state, login_state, risk_status, mute_status, created_at, updated_at)
            VALUES (?, ?, 2, 1, 1, NULL, ?, ?)
            """, TEST_TENANT_ID, accountId, now, now);
}

private Long insertGroupCreationTask(String name, Long accountGroupId, long now) {
    return insertAndReturnId("""
            INSERT INTO group_creation_marketing_task
                (tenant_id, task_name, account_group_id, account_group_name,
                 marketing_template_id, marketing_template_name, status,
                 matched_item_count, unmatched_file_count, success_count,
                 failed_count, abandoned_count, created_at, updated_at)
            VALUES (?, ?, ?, 'A组', 1, '模板', 2, 1, 0, 0, 0, 0, ?, ?)
            """, ps -> {
        ps.setLong(1, TEST_TENANT_ID);
        ps.setString(2, name);
        ps.setLong(3, accountGroupId);
        ps.setLong(4, now);
        ps.setLong(5, now);
    });
}

private Long insertGroupCreationSendingItem(Long taskId, Long accountId, long now) {
    return insertAndReturnId("""
            INSERT INTO group_creation_marketing_item
                (tenant_id, task_id, file_index, file_name, material_content,
                 participant_count, account_id, account_phone, protocol_account_id,
                 group_subject, group_jid, command_id, status, next_run_at, created_at, updated_at)
            VALUES (?, ?, 0, 'retry.txt', '8613900000000', 1, ?, '6285378444041',
                    'acc_6285378444041', '活动群', '120363old@g.us',
                    'cmd_gcm_send_retry', 3, ?, ?, ?)
            """, ps -> {
        ps.setLong(1, TEST_TENANT_ID);
        ps.setLong(2, taskId);
        ps.setLong(3, accountId);
        ps.setLong(4, now);
        ps.setLong(5, now);
        ps.setLong(6, now);
    });
}
```

- [ ] **Step 2: Run DB test to verify RED or GREEN**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
armada-api/dbtest.sh MarketingSendResultServiceImplDbTest#groupCreationSendFailureResetsItemToPendingWithReplacementAccount
```

Expected after Tasks 1-6: pass. If it fails, fix only the mapper/service wiring needed for this behavior.

- [ ] **Step 3: Commit**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplDbTest.java
git commit -m "test: cover group creation send failure account retry"
```

Expected: one commit containing the DB regression test.

---

### Task 8: Final Verification

**Files:**
- Test: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingRetryServiceTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/model/support/GroupCreationMarketingRetryHistoryTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapperDbTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/GroupCreationMarketingMigrationDbTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplDbTest.java`

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=GroupCreationMarketingRetryHistoryTest,GroupCreationMarketingRetryServiceTest,GroupCreationMarketingWorkerTest,MarketingSendResultServiceImplTest test
```

Expected: all listed unit tests pass.

- [ ] **Step 2: Run focused DB tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
armada-api/dbtest.sh GroupCreationMarketingMigrationDbTest,GroupCreationMarketingTaskMapperDbTest,MarketingSendResultServiceImplDbTest
```

Expected: all listed DB tests pass.

- [ ] **Step 3: Run compile-level regression**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -DskipTests compile
```

Expected: compile succeeds.

- [ ] **Step 4: Inspect git status**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git status --short
```

Expected: only pre-existing unrelated dirty files remain. All files touched by this plan are either committed or intentionally left for the final commit.

- [ ] **Step 5: Final commit if verification changed files**

If a verification fix changed files after the previous task commits, run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/marketing \
  armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml \
  armada-api/src/main/resources/db/migration/V043__group_creation_marketing_retry_history.sql \
  armada-api/src/test/java/com/armada/marketing
git commit -m "fix: stabilize group creation account retry"
```

Expected: commit succeeds only when there are remaining tracked changes from this plan.
