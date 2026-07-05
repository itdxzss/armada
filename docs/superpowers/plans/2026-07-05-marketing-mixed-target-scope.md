# Marketing Mixed Target Scope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support mixed marketing task targets where some accounts send to selected groups and other accounts dynamically send to all post-import groups.

**Architecture:** Reuse `marketing_task_target` and add account-level `target_scope`; fixed group targets remain one account-group pair per row, while account-dynamic targets are one row per account. Armada expands account-dynamic rows into per-group send attempts before each round, using `account_group_membership` minus `account_group_baseline`, and the frontend sends an explicit `targetScope` so tree parent selection is not confused with selecting every child group.

**Tech Stack:** Java 17, Spring Boot, MyBatis XML, Flyway SQL migrations, JUnit/AssertJ/Mockito, Vue 3 TypeScript, Element Plus.

---

## File Map

Backend `armada`:

- Create `armada-api/src/main/resources/db/migration/V039__marketing_mixed_target_scope.sql`: add `target_scope`, nullable target group columns, attempt group snapshot columns, and updated unique keys.
- Modify `armada-api/src/main/java/com/armada/marketing/model/dto/MarketingSelectionDTO.java`: add request `targetScope`.
- Create `armada-api/src/main/java/com/armada/marketing/model/enums/MarketingTargetScope.java`: map `GROUP_FIXED` and `ACCOUNT_DYNAMIC` to DB codes.
- Modify `armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTaskTarget.java`: add `targetScope`.
- Modify `armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTaskSendAttempt.java`: add `groupLinkId`, `groupJid`, `groupName`.
- Modify `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskTargetVO.java`: expose `targetScope`.
- Create `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingAccountCandidateRow.java`: account-only candidate for dynamic targets.
- Modify `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`: add dynamic account and group candidate queries.
- Modify `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`: update result maps, inserts, and dynamic target SQL.
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`: create mixed target rows and reject ambiguous account selections.
- Create `armada-api/src/main/java/com/armada/marketing/scheduler/ResolvedMarketingSendTarget.java`: normalized per-group send target used by the round worker.
- Modify `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`: expand dynamic account targets each round and write group snapshots to attempts.
- Modify tests under `armada-api/src/test/java/com/armada/marketing/**`: migration, create/read, mapper, and round worker coverage.

Frontend `wheel-saas-pure-web`:

- Modify `../wheel-saas-pure-web/src/api/marketing-task.ts`: add `targetScope` to `MarketingSelection`.
- Create `../wheel-saas-pure-web/src/views/task/group-marketing/composables/useMarketingTargetSelection.ts`: parse checked tree keys into explicit target-scope selections.
- Modify `../wheel-saas-pure-web/src/views/task/group-marketing/components/GroupMarketingCreateDrawer.vue`: use strict tree checking, account keys for dynamic targets, and group keys for fixed targets.
- Modify `../wheel-saas-pure-web/src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts`: update validation message.
- Modify frontend tests under `../wheel-saas-pure-web/src/views/task/group-marketing/**` and `../wheel-saas-pure-web/src/api/marketing-task.ts` tests.

## Task 1: Backend Migration and Model Contract

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V039__marketing_mixed_target_scope.sql`
- Create: `armada-api/src/main/java/com/armada/marketing/model/enums/MarketingTargetScope.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/dto/MarketingSelectionDTO.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTaskTarget.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTaskSendAttempt.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskTargetVO.java`
- Test: `armada-api/src/test/java/com/armada/marketing/MarketingKafkaRoundSendMigrationDbTest.java`

- [ ] **Step 1: Write the failing migration test**

Add these assertions to `MarketingKafkaRoundSendMigrationDbTest`:

```java
    @Test
    void marketingTargetSupportsMixedAccountAndGroupScopes() {
        assertThat(columnType("marketing_task_target", "target_scope")).isEqualTo("tinyint");
        assertThat(isNullable("marketing_task_target", "group_link_id")).isTrue();
        assertThat(isNullable("marketing_task_target", "group_jid")).isTrue();
        assertThat(isNullable("marketing_task_target", "group_link_url")).isTrue();
        assertThat(indexExists("marketing_task_target", "uq_marketing_task_target_scope")).isTrue();
    }

    @Test
    void marketingAttemptStoresResolvedGroupSnapshot() {
        assertThat(columnType("marketing_task_send_attempt", "group_link_id")).isEqualTo("bigint");
        assertThat(columnType("marketing_task_send_attempt", "group_jid")).isEqualTo("varchar");
        assertThat(columnType("marketing_task_send_attempt", "group_name")).isEqualTo("varchar");
        assertThat(indexExists("marketing_task_send_attempt", "uq_marketing_task_attempt_group_round")).isTrue();
    }

    private boolean isNullable(String tableName, String columnName) {
        String nullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
        return "YES".equals(nullable);
    }
```

- [ ] **Step 2: Run the migration test and verify it fails**

Run:

```bash
mvn -pl armada-api -Dtest=MarketingKafkaRoundSendMigrationDbTest test
```

Expected: FAIL because `target_scope`, attempt group snapshot columns, and the new unique indexes do not exist.

- [ ] **Step 3: Add the migration**

Create `armada-api/src/main/resources/db/migration/V039__marketing_mixed_target_scope.sql`:

```sql
ALTER TABLE marketing_task_target
    ADD COLUMN target_scope TINYINT NOT NULL DEFAULT 1
        COMMENT '目标范围:1=指定群组 2=账号动态群' AFTER account_phone,
    MODIFY COLUMN group_link_id BIGINT DEFAULT NULL
        COMMENT '目标群入口ID(→group_link.id);账号动态目标为空',
    MODIFY COLUMN group_jid VARCHAR(128) DEFAULT NULL
        COMMENT 'WhatsApp群JID,协议发送寻址用;账号动态目标为空',
    MODIFY COLUMN group_link_url VARCHAR(255) DEFAULT NULL
        COMMENT '群链接URL快照;账号动态目标为空',
    ADD COLUMN target_unique_group_key BIGINT
        GENERATED ALWAYS AS (
            CASE
                WHEN target_scope = 2 THEN 0
                ELSE COALESCE(group_link_id, -1)
            END
        ) STORED
        COMMENT '混合目标唯一键辅助列';

ALTER TABLE marketing_task_target
    DROP INDEX uq_marketing_task_target_pair,
    ADD UNIQUE KEY uq_marketing_task_target_scope
        (tenant_id, marketing_task_id, account_id, target_scope, target_unique_group_key);

ALTER TABLE marketing_task_send_attempt
    ADD COLUMN group_link_id BIGINT DEFAULT NULL
        COMMENT '本次实际发送群入口ID;固定群来自target,账号动态来自轮次解析' AFTER target_id,
    ADD COLUMN group_jid VARCHAR(128) DEFAULT NULL
        COMMENT '本次实际发送群JID' AFTER group_link_id,
    ADD COLUMN group_name VARCHAR(128) DEFAULT NULL
        COMMENT '本次实际发送群名称快照' AFTER group_jid,
    ADD COLUMN attempt_group_key VARCHAR(128)
        GENERATED ALWAYS AS (COALESCE(group_jid, CONCAT('target:', target_id))) STORED
        COMMENT '同target同轮次多群唯一键辅助列';

ALTER TABLE marketing_task_send_attempt
    DROP INDEX uq_marketing_task_attempt_round,
    ADD UNIQUE KEY uq_marketing_task_attempt_group_round
        (tenant_id, target_id, round_no, attempt_group_key);
```

- [ ] **Step 4: Add Java model and DTO fields**

Create `MarketingTargetScope.java`:

```java
package com.armada.marketing.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.util.StringUtils;

public enum MarketingTargetScope {
    GROUP_FIXED(1, "GROUP_FIXED"),
    ACCOUNT_DYNAMIC(2, "ACCOUNT_DYNAMIC");

    private final int code;
    private final String apiValue;

    MarketingTargetScope(int code, String apiValue) {
        this.code = code;
        this.apiValue = apiValue;
    }

    public int code() {
        return code;
    }

    public String apiValue() {
        return apiValue;
    }

    public static MarketingTargetScope fromApiValue(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择发送范围");
        }
        for (MarketingTargetScope scope : values()) {
            if (scope.apiValue.equalsIgnoreCase(value.trim())) {
                return scope;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "发送范围不合法: " + value);
    }

    public static String apiValueOf(Integer code) {
        if (code == null) {
            return GROUP_FIXED.apiValue;
        }
        for (MarketingTargetScope scope : values()) {
            if (scope.code == code) {
                return scope.apiValue;
            }
        }
        return GROUP_FIXED.apiValue;
    }
}
```

Change `MarketingSelectionDTO` to:

```java
public record MarketingSelectionDTO(Long accountId, String targetScope, List<Long> groupLinkIds) {
}
```

Add `targetScope` getter/setter to `MarketingTaskTarget`:

```java
    private Integer targetScope;

    public Integer getTargetScope() {
        return targetScope;
    }

    public void setTargetScope(Integer targetScope) {
        this.targetScope = targetScope;
    }
```

Add group snapshot fields to `MarketingTaskSendAttempt`:

```java
    private Long groupLinkId;
    private String groupJid;
    private String groupName;

    public Long getGroupLinkId() {
        return groupLinkId;
    }

    public void setGroupLinkId(Long groupLinkId) {
        this.groupLinkId = groupLinkId;
    }

    public String getGroupJid() {
        return groupJid;
    }

    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }
```

Change `MarketingTaskTargetVO` constructor fields to include `String targetScope` after `accountPhone`.

- [ ] **Step 5: Run the migration test and verify it passes**

Run:

```bash
mvn -pl armada-api -Dtest=MarketingKafkaRoundSendMigrationDbTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add armada-api/src/main/resources/db/migration/V039__marketing_mixed_target_scope.sql \
  armada-api/src/main/java/com/armada/marketing/model/enums/MarketingTargetScope.java \
  armada-api/src/main/java/com/armada/marketing/model/dto/MarketingSelectionDTO.java \
  armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTaskTarget.java \
  armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTaskSendAttempt.java \
  armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskTargetVO.java \
  armada-api/src/test/java/com/armada/marketing/MarketingKafkaRoundSendMigrationDbTest.java
git commit -m "feat: add marketing mixed target scope schema"
```

## Task 2: Backend Create Task Mixed Target Rows

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingAccountCandidateRow.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/controller/MarketingTaskControllerDbTest.java`

- [ ] **Step 1: Write failing create/read tests**

In `MarketingTaskCreateReadDbTest`, add tests that use existing `seedFixture`:

```java
import java.util.Map;

    @Test
    void createTask_allowsAccountDynamicTargetWithoutGroups() {
        Fixture fixture = seedFixture("dynamic-account");
        MarketingTaskVO created = service.createTask(request(
                "账号维度任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(fixture.accountId(), "ACCOUNT_DYNAMIC", List.of()))));

        assertThat(created.selectedAccountCount()).isEqualTo(1);
        assertThat(created.targetGroupCount()).isZero();
        assertThat(created.targetPairCount()).isEqualTo(1);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT target_scope, group_link_id, group_jid
                FROM marketing_task_target
                WHERE marketing_task_id = ?
                """, created.id());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(((Number) row.get("target_scope")).intValue()).isEqualTo(2);
            assertThat(row.get("group_link_id")).isNull();
            assertThat(row.get("group_jid")).isNull();
        });
    }

    @Test
    void createTask_rejectsSameAccountWithDynamicAndFixedScopes() {
        Fixture fixture = seedFixture("mixed-conflict");
        CreateMarketingTaskDTO req = request(
                "冲突任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(
                        new MarketingSelectionDTO(fixture.accountId(), "ACCOUNT_DYNAMIC", List.of()),
                        new MarketingSelectionDTO(fixture.accountId(), "GROUP_FIXED", List.of(fixture.groupLinkId()))));

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同一账号不能同时选择账号维度和指定群组");
    }
```

Update existing `MarketingSelectionDTO` calls in this test class to pass `"GROUP_FIXED"`.

- [ ] **Step 2: Run create/read tests and verify they fail**

Run:

```bash
mvn -pl armada-api -Dtest=MarketingTaskCreateReadDbTest test
```

Expected: FAIL because create code still requires group IDs and mapper inserts do not set `target_scope`.

- [ ] **Step 3: Add account candidate mapper contract**

Create `MarketingAccountCandidateRow.java`:

```java
package com.armada.marketing.model.vo;

public class MarketingAccountCandidateRow {
    private Long accountId;
    private String accountPhone;

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getAccountPhone() {
        return accountPhone;
    }

    public void setAccountPhone(String accountPhone) {
        this.accountPhone = accountPhone;
    }
}
```

Add to `MarketingTaskMapper.java`:

```java
    /** 查询一个账号是否可作为账号动态营销目标。 */
    MarketingAccountCandidateRow selectAccountTargetCandidate(@Param("accountGroupId") Long accountGroupId,
                                                              @Param("accountId") Long accountId);
```

Add import:

```java
import com.armada.marketing.model.vo.MarketingAccountCandidateRow;
```

Add to `MarketingTaskMapper.xml`:

```xml
    <resultMap id="MarketingAccountCandidateRowResultMap" type="com.armada.marketing.model.vo.MarketingAccountCandidateRow">
        <result column="account_id" property="accountId"/>
        <result column="account_phone" property="accountPhone"/>
    </resultMap>
```

Add SQL:

```xml
    <select id="selectAccountTargetCandidate" resultMap="MarketingAccountCandidateRowResultMap">
        SELECT a.id AS account_id,
               a.ws_phone AS account_phone
        FROM account a
        JOIN account_state s ON s.account_id = a.id
        WHERE a.id = #{accountId}
          AND a.account_group_id = #{accountGroupId}
          AND a.deleted_at IS NULL
          AND s.login_state = 1
          AND (s.risk_status IS NULL OR s.risk_status = 1)
          AND s.mute_status IS NULL
        LIMIT 1
    </select>
```

- [ ] **Step 4: Update target result map and inserts**

In `MarketingTaskMapper.xml`, add target scope mapping:

```xml
        <result column="target_scope" property="targetScope"/>
```

Update `TargetColumns`:

```xml
        t.id, t.tenant_id, t.marketing_task_id, t.account_id, t.account_phone,
        a.protocol_account_id, t.target_scope, t.group_link_id, t.group_jid, t.group_link_url, t.group_name,
```

Update `insertTargets` column list and values:

```xml
            (marketing_task_id, account_id, account_phone, target_scope,
             group_link_id, group_jid, group_link_url, group_name,
```

```xml
            (#{t.marketingTaskId}, #{t.accountId}, #{t.accountPhone}, #{t.targetScope}, #{t.groupLinkId}, #{t.groupJid},
             #{t.groupLinkUrl}, #{t.groupName}, #{t.status}, #{t.sentMessageCount}, #{t.failedMessageCount},
```

- [ ] **Step 5: Implement mixed target creation**

In `MarketingTaskServiceImpl`, import:

```java
import com.armada.marketing.model.enums.MarketingTargetScope;
import com.armada.marketing.model.vo.MarketingAccountCandidateRow;
```

Replace `appendSelectionTargets` with scope-based logic:

```java
    private void appendSelectionTargets(Long accountGroupId, MarketingSelectionDTO selection, Set<String> seenPairs,
                                        List<MarketingTaskTarget> targets, long now) {
        if (selection == null || selection.accountId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号选择不能为空");
        }
        MarketingTargetScope scope = MarketingTargetScope.fromApiValue(selection.targetScope());
        if (scope == MarketingTargetScope.ACCOUNT_DYNAMIC) {
            String key = selection.accountId() + ":ACCOUNT_DYNAMIC";
            if (seenPairs.stream().anyMatch(item -> item.startsWith(selection.accountId() + ":GROUP_FIXED:"))) {
                throw new BusinessException(ErrorCode.VALIDATION, "同一账号不能同时选择账号维度和指定群组");
            }
            if (!seenPairs.add(key)) {
                throw new BusinessException(ErrorCode.VALIDATION, "同一账号不能重复选择账号维度");
            }
            targets.add(toDynamicAccountTarget(requireAccountCandidate(accountGroupId, selection.accountId()), now));
            return;
        }
        if (selection.groupLinkIds() == null || selection.groupLinkIds().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "指定群组发送必须选择群组");
        }
        for (Long groupLinkId : selection.groupLinkIds()) {
            if (groupLinkId == null) {
                continue;
            }
            if (!seenPairs.add(selection.accountId() + ":GROUP_FIXED:" + groupLinkId)) {
                continue;
            }
            if (seenPairs.contains(selection.accountId() + ":ACCOUNT_DYNAMIC")) {
                throw new BusinessException(ErrorCode.VALIDATION, "同一账号不能同时选择账号维度和指定群组");
            }
            targets.add(toFixedGroupTarget(requireCandidate(accountGroupId, selection.accountId(), groupLinkId), now));
        }
    }
```

Add account candidate and target builders:

```java
    private MarketingAccountCandidateRow requireAccountCandidate(Long accountGroupId, Long accountId) {
        MarketingAccountCandidateRow row = taskMapper.selectAccountTargetCandidate(accountGroupId, accountId);
        if (row == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号不可用或不属于当前分组: " + accountId);
        }
        return row;
    }

    private MarketingTaskTarget toDynamicAccountTarget(MarketingAccountCandidateRow row, long now) {
        MarketingTaskTarget target = baseTarget(row.getAccountId(), row.getAccountPhone(), now);
        target.setTargetScope(MarketingTargetScope.ACCOUNT_DYNAMIC.code());
        return target;
    }

    private MarketingTaskTarget toFixedGroupTarget(MarketingTargetCandidateRow row, long now) {
        MarketingTaskTarget target = baseTarget(row.getAccountId(), row.getAccountPhone(), now);
        target.setTargetScope(MarketingTargetScope.GROUP_FIXED.code());
        target.setGroupLinkId(row.getGroupLinkId());
        target.setGroupJid(row.getGroupJid());
        target.setGroupLinkUrl(row.getGroupLinkUrl());
        target.setGroupName(row.getGroupName());
        return target;
    }

    private MarketingTaskTarget baseTarget(Long accountId, String accountPhone, long now) {
        MarketingTaskTarget target = new MarketingTaskTarget();
        target.setAccountId(accountId);
        target.setAccountPhone(accountPhone);
        target.setStatus(MarketingTaskStatus.PENDING.code());
        target.setSentMessageCount(0);
        target.setFailedMessageCount(0);
        target.setRetryCount(0);
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        return target;
    }
```

Remove the old `toTarget` method after the new builders are wired.

Update `toTargetVO`:

```java
    private static MarketingTaskTargetVO toTargetVO(MarketingTaskTarget target) {
        return new MarketingTaskTargetVO(target.getId(), target.getAccountId(), target.getAccountPhone(),
                MarketingTargetScope.apiValueOf(target.getTargetScope()), target.getGroupLinkId(),
                target.getGroupJid(), target.getGroupLinkUrl(), target.getGroupName(),
                target.getStatus(), target.getSentMessageCount(), target.getFailedMessageCount(), target.getRetryCount(),
                target.getLastAttemptAt(), target.getLastSentAt(), target.getLastReason());
    }
```

Update `distinctGroupCount` to ignore dynamic null groups:

```java
    private static int distinctGroupCount(List<MarketingTaskTarget> targets) {
        return (int) targets.stream()
                .map(MarketingTaskTarget::getGroupLinkId)
                .filter(id -> id != null)
                .distinct()
                .count();
    }
```

- [ ] **Step 6: Run create/read tests and verify they pass**

Before running, update `MarketingTaskControllerDbTest.request` to construct selections with explicit scope:

```java
                List.of(new MarketingSelectionDTO(fixture.accountId(), "GROUP_FIXED", List.of(fixture.groupLinkId()))));
```

Update `postCreate_withoutTemplate_returnsValidationMessage` request JSON selection to:

```java
                  "selections":[{"accountId":%d,"targetScope":"GROUP_FIXED","groupLinkIds":[%d]}]
```

Update `getDetail_returnsTargets` to assert the scope:

```java
.andExpect(jsonPath("$.data.targets[0].targetScope").value("GROUP_FIXED"))
```

Run:

```bash
mvn -pl armada-api -Dtest=MarketingTaskCreateReadDbTest,MarketingTaskControllerDbTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add armada-api/src/main/java/com/armada/marketing/model/vo/MarketingAccountCandidateRow.java \
  armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java \
  armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml \
  armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java \
  armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java \
  armada-api/src/test/java/com/armada/marketing/controller/MarketingTaskControllerDbTest.java
git commit -m "feat: create marketing mixed target rows"
```

## Task 3: Backend Round Worker Dynamic Expansion

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/scheduler/ResolvedMarketingSendTarget.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`
- Test: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerDbTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingRoundMapperDbTest.java`

- [ ] **Step 1: Write failing worker unit tests**

In `MarketingRoundWorkerTest`, add:

```java
    @Test
    void accountDynamicTargetExpandsCurrentPostImportGroupsBeforeSending() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);
        properties.setOutboxBatchSize(500);

        MarketingTask task = task();
        MarketingTaskTarget dynamic = dynamicTarget(7001L, 501L);
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(List.of(dynamic));
        when(taskMapper.selectDynamicTargetCandidates(8L, 501L)).thenReturn(List.of(
                candidate(501L, 301L, "120363new1@g.us"),
                candidate(501L, 302L, "120363new2@g.us")));
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MarketingTaskSendAttempt> attempts = invocation.getArgument(0, List.class);
            long id = 9000L;
            for (MarketingTaskSendAttempt attempt : attempts) {
                attempt.setId(++id);
            }
            return attempts.size();
        }).when(taskMapper).insertSendAttempts(any());

        MarketingRoundWorker worker = worker(taskMapper, outbox, properties);
        worker.runRound(1L, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketingTaskSendAttempt>> attemptsCaptor = ArgumentCaptor.forClass(List.class);
        verify(taskMapper).insertSendAttempts(attemptsCaptor.capture());
        assertThat(attemptsCaptor.getValue()).extracting(MarketingTaskSendAttempt::getGroupJid)
                .containsExactly("120363new1@g.us", "120363new2@g.us");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolMarketingMessageCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outbox).enqueueMarketingMessageCommands(commandsCaptor.capture());
        assertThat(commandsCaptor.getValue()).extracting(ProtocolMarketingMessageCommandRequest::groupJid)
                .containsExactly("120363new1@g.us", "120363new2@g.us");
    }

    @Test
    void fixedGroupTargetDoesNotQueryDynamicGroups() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);

        MarketingTask task = task();
        task.setAccountGroupId(8L);
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets(1));
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MarketingTaskSendAttempt> attempts = invocation.getArgument(0, List.class);
            attempts.get(0).setId(9001L);
            return attempts.size();
        }).when(taskMapper).insertSendAttempts(any());

        worker(taskMapper, outbox, properties).runRound(1L, 42L);

        verify(taskMapper, never()).selectDynamicTargetCandidates(any(), any());
        verify(outbox).enqueueMarketingMessageCommands(any());
    }
```

Add helpers:

```java
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;

    private static MarketingTaskTarget dynamicTarget(Long targetId, Long accountId) {
        MarketingTaskTarget target = new MarketingTaskTarget();
        target.setId(targetId);
        target.setMarketingTaskId(42L);
        target.setTargetScope(2);
        target.setAccountId(accountId);
        target.setAccountPhone("923dynamic");
        target.setProtocolAccountId("acc_923dynamic");
        return target;
    }

    private static MarketingTargetCandidateRow candidate(Long accountId, Long groupLinkId, String groupJid) {
        MarketingTargetCandidateRow row = new MarketingTargetCandidateRow();
        row.setAccountId(accountId);
        row.setAccountPhone("923dynamic");
        row.setGroupLinkId(groupLinkId);
        row.setGroupJid(groupJid);
        row.setGroupLinkUrl("https://chat.whatsapp.com/" + groupLinkId);
        row.setGroupName("group-" + groupLinkId);
        return row;
    }
```

Set `task.setAccountGroupId(8L)` in the existing `task()` helper and set `target.setTargetScope(1)` in `targets(int count)`.

- [ ] **Step 2: Run worker tests and verify they fail**

Run:

```bash
mvn -pl armada-api -Dtest=MarketingRoundWorkerTest test
```

Expected: FAIL because `selectDynamicTargetCandidates` and attempt group snapshot setters are not wired into the worker.

- [ ] **Step 3: Add dynamic candidate mapper**

Add to `MarketingTaskMapper.java`:

```java
    /** 查询账号动态目标在当前轮次应发送的群。 */
    List<MarketingTargetCandidateRow> selectDynamicTargetCandidates(@Param("accountGroupId") Long accountGroupId,
                                                                    @Param("accountId") Long accountId);
```

Add SQL to `MarketingTaskMapper.xml`:

```xml
    <select id="selectDynamicTargetCandidates" resultType="com.armada.marketing.model.vo.MarketingTargetCandidateRow">
        SELECT a.id AS accountId,
               a.ws_phone AS accountPhone,
               g.id AS groupLinkId,
               p.group_jid AS groupJid,
               g.link_url AS groupLinkUrl,
               COALESCE(NULLIF(TRIM(g.group_name), ''), p.wa_subject) AS groupName
        FROM account a
        JOIN account_state s ON s.account_id = a.id
        JOIN account_group_membership m ON m.account_id = a.id
                                       AND m.deleted_at IS NULL
        JOIN group_link g ON g.id = m.group_link_id
                         AND g.deleted_at IS NULL
                         AND g.membership_state IN (2, 3)
        JOIN group_link_preview p ON p.group_link_id = g.id
                                 AND p.group_jid = m.group_jid
        LEFT JOIN group_link_health h ON h.group_link_id = g.id
        LEFT JOIN account_group_baseline b ON b.account_id = a.id
                                           AND a.group_baseline_state = 2
        WHERE a.id = #{accountId}
          AND a.account_group_id = #{accountGroupId}
          AND a.deleted_at IS NULL
          AND s.login_state = 1
          AND (s.risk_status IS NULL OR s.risk_status = 1)
          AND s.mute_status IS NULL
          AND p.group_jid IS NOT NULL
          AND TRIM(p.group_jid) &lt;&gt; ''
          AND (h.id IS NULL OR (COALESCE(h.is_banned, 0) = 0 AND (h.health_status IS NULL OR h.health_status = 1)))
          AND (
              a.group_baseline_state &lt;&gt; 2
              OR b.id IS NULL
              OR COALESCE(JSON_CONTAINS(b.baseline_group_jids, JSON_QUOTE(p.group_jid)), 0) = 0
          )
        ORDER BY g.id ASC
    </select>
```

- [ ] **Step 4: Add resolved send target record**

Create `ResolvedMarketingSendTarget.java`:

```java
package com.armada.marketing.scheduler;

import com.armada.marketing.model.entity.MarketingTaskTarget;

record ResolvedMarketingSendTarget(
        MarketingTaskTarget target,
        Long groupLinkId,
        String groupJid,
        String groupName) {
}
```

- [ ] **Step 5: Expand targets in `MarketingRoundWorker`**

Import:

```java
import com.armada.marketing.model.enums.MarketingTargetScope;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
```

Replace `List<MarketingTaskTarget> targets = taskMapper.selectTargetsByTaskId(taskId);` flow with:

```java
        List<MarketingTaskTarget> targets = taskMapper.selectTargetsByTaskId(taskId);
        List<ResolvedMarketingSendTarget> sendTargets = resolveSendTargets(task, targets);
        if (sendTargets.isEmpty()) {
            log.warn("营销任务轮次跳过:没有可发送目标 tenantId={} taskId={}", task.getTenantId(), task.getId());
            return;
        }
```

Use `sendTargets.size()` for `backlogThreshold`, attempt list capacity, logging `targetCount`, and batch command count.

Add:

```java
    private List<ResolvedMarketingSendTarget> resolveSendTargets(MarketingTask task, List<MarketingTaskTarget> targets) {
        List<ResolvedMarketingSendTarget> resolved = new ArrayList<>();
        for (MarketingTaskTarget target : targets) {
            if (Integer.valueOf(MarketingTargetScope.ACCOUNT_DYNAMIC.code()).equals(target.getTargetScope())) {
                List<MarketingTargetCandidateRow> groups =
                        taskMapper.selectDynamicTargetCandidates(task.getAccountGroupId(), target.getAccountId());
                if (groups.isEmpty()) {
                    log.info("营销任务账号动态目标本轮无可发送群 tenantId={} taskId={} targetId={} accountId={}",
                            task.getTenantId(), task.getId(), target.getId(), target.getAccountId());
                    continue;
                }
                for (MarketingTargetCandidateRow group : groups) {
                    resolved.add(new ResolvedMarketingSendTarget(target, group.getGroupLinkId(),
                            group.getGroupJid(), group.getGroupName()));
                }
                continue;
            }
            resolved.add(new ResolvedMarketingSendTarget(target, target.getGroupLinkId(),
                    target.getGroupJid(), target.getGroupName()));
        }
        return resolved;
    }
```

Update `toAttempt` to accept `ResolvedMarketingSendTarget`:

```java
    private MarketingTaskSendAttempt toAttempt(MarketingTask task,
                                               ResolvedMarketingSendTarget sendTarget,
                                               long roundNo,
                                               long now) {
        MarketingTaskTarget target = sendTarget.target();
        MarketingTaskSendAttempt attempt = new MarketingTaskSendAttempt();
        attempt.setMarketingTaskId(task.getId());
        attempt.setTargetId(target.getId());
        attempt.setGroupLinkId(sendTarget.groupLinkId());
        attempt.setGroupJid(sendTarget.groupJid());
        attempt.setGroupName(sendTarget.groupName());
        attempt.setRoundNo(roundNo);
        attempt.setAttemptNo(1);
        attempt.setRetry(false);
        attempt.setCommandId(newCommandId());
        attempt.setStatus(MarketingSendAttemptStatus.SUBMITTED.code());
        attempt.setSubmittedAt(now);
        attempt.setAttemptedAt(now);
        attempt.setCreatedAt(now);
        return attempt;
    }
```

Update `enqueueCommands` signature and loop:

```java
    private EnqueueSummary enqueueCommands(MarketingTask task,
                                           List<ResolvedMarketingSendTarget> sendTargets,
                                           List<MarketingTaskSendAttempt> attempts,
                                           MarketingMessageComposer.ComposedMessage message) {
        int batchSize = outboxBatchSize(message);
        List<ProtocolMarketingMessageCommandRequest> batch = new ArrayList<>(batchSize);
        String imageBase64 = message.imageBytes() == null ? null : Base64.getEncoder().encodeToString(message.imageBytes());
        int batchCount = 0;
        int commandCount = 0;
        for (int i = 0; i < attempts.size(); i++) {
            ResolvedMarketingSendTarget sendTarget = sendTargets.get(i);
            MarketingTaskTarget target = sendTarget.target();
            MarketingTaskSendAttempt attempt = attempts.get(i);
            batch.add(new ProtocolMarketingMessageCommandRequest(
                    task.getTenantId(),
                    task.getId(),
                    attempt.getId(),
                    target.getId(),
                    attempt.getRoundNo(),
                    target.getAccountId(),
                    protocolAccountId(target),
                    sendTarget.groupJid(),
                    message.messageType(),
                    message.text(),
                    imageBase64,
                    message.imageMimetype(),
                    SOURCE_MARKETING_TASK,
                    attempt.getCommandId()));
            if (batch.size() == batchSize) {
                outboxService.enqueueMarketingMessageCommands(batch);
                batchCount++;
                commandCount += batch.size();
                batch = new ArrayList<>(batchSize);
            }
        }
        if (!batch.isEmpty()) {
            outboxService.enqueueMarketingMessageCommands(batch);
            batchCount++;
            commandCount += batch.size();
        }
        return new EnqueueSummary(batchSize, batchCount, commandCount);
    }
```

- [ ] **Step 6: Update attempt insert SQL**

In `insertSendAttempts`, add columns:

```xml
            (marketing_task_id, target_id, group_link_id, group_jid, group_name, round_no, attempt_no, is_retry, command_id,
```

Add values:

```xml
            (#{a.marketingTaskId}, #{a.targetId}, #{a.groupLinkId}, #{a.groupJid}, #{a.groupName}, #{a.roundNo}, #{a.attemptNo}, #{a.retry},
```

- [ ] **Step 7: Add DB test for baseline exclusion in round worker**

In `MarketingRoundWorkerDbTest`, add one test that inserts one `ACCOUNT_DYNAMIC` target plus two memberships, one baseline group and one new group. Assert only the new group creates an attempt:

```java
    @Test
    void accountDynamicRoundSendsOnlyPostImportGroups() {
        long now = System.currentTimeMillis();
        Long templateId = insertTemplate("dynamic-round-" + now);
        Long accountGroupId = insertAccountGroup("dynamic-round-" + now);
        Long accountId = insertOnlineAccount(accountGroupId, "923399900001", 2);
        Long oldGroupId = insertGroup("old-dynamic", "120363old@g.us", 1, 0);
        Long newGroupId = insertGroup("new-dynamic", "120363new@g.us", 1, 0);
        insertBaseline(accountId, "[\"120363old@g.us\"]");
        insertMembership(accountId, oldGroupId, "120363old@g.us");
        insertMembership(accountId, newGroupId, "120363new@g.us");
        Long taskId = insertTask("dynamic-round-" + now, templateId, 0L, now - 1_000, 30);
        insertDynamicTarget(taskId, accountId, "923399900001");
        List<List<ProtocolMarketingMessageCommandRequest>> batches = new ArrayList<>();

        worker(recordingOutbox(batches)).runRound(TEST_TENANT_ID, taskId);

        assertThat(jdbc.queryForList("""
                SELECT group_jid
                FROM marketing_task_send_attempt
                WHERE tenant_id = ? AND marketing_task_id = ?
                """, String.class, TEST_TENANT_ID, taskId)).containsExactly("120363new@g.us");
        assertThat(batches.stream().flatMap(List::stream).toList())
                .extracting(ProtocolMarketingMessageCommandRequest::groupJid)
                .containsExactly("120363new@g.us");
    }
```

Add helper methods in the same test class using the same `insertAndReturnId` pattern already present:

```java
    private Long insertAccountGroup(String name) {
        long now = System.currentTimeMillis();
        return insertAndReturnId("""
                INSERT INTO account_group (tenant_id, name, system_builtin, created_at, updated_at)
                VALUES (?, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, name);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
    }

    private Long insertOnlineAccount(Long accountGroupId, String phone, int baselineState) {
        long now = System.currentTimeMillis();
        Long accountId = insertAndReturnId("""
                INSERT INTO account
                    (tenant_id, ws_phone, account_type, ownership, account_group_id,
                     group_baseline_state, priority, created_at, updated_at)
                VALUES (?, ?, 1, 1, ?, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, phone);
            ps.setLong(3, accountGroupId);
            ps.setInt(4, baselineState);
            ps.setLong(5, now);
            ps.setLong(6, now);
        });
        jdbc.update("""
                INSERT INTO account_state
                    (tenant_id, account_id, account_state, login_state, risk_status, mute_status, created_at, updated_at)
                VALUES (?, ?, 2, 1, 1, NULL, ?, ?)
                """, TEST_TENANT_ID, accountId, now, now);
        return accountId;
    }

    private void insertBaseline(Long accountId, String baselineGroupJids) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO account_group_baseline
                    (tenant_id, account_id, baseline_group_jids, group_count, captured_at, created_at, updated_at)
                VALUES (?, ?, ?, JSON_LENGTH(?), ?, ?, ?)
                """, TEST_TENANT_ID, accountId, baselineGroupJids, baselineGroupJids, now, now, now);
    }

    private Long insertGroup(String suffix, String groupJid, Integer healthStatus, Integer banned) {
        long now = System.currentTimeMillis();
        Long groupLinkId = insertAndReturnId("""
                INSERT INTO group_link
                    (tenant_id, link_url, group_name, origin, membership_state, created_at, updated_at)
                VALUES (?, ?, ?, 2, 2, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "https://chat.whatsapp.com/" + suffix);
            ps.setString(3, "营销群-" + suffix);
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
        jdbc.update("""
                INSERT INTO group_link_preview
                    (tenant_id, group_link_id, group_jid, wa_subject, announce_only, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, ?, ?)
                """, TEST_TENANT_ID, groupLinkId, groupJid, "WA群-" + suffix, now, now);
        jdbc.update("""
                INSERT INTO group_link_health
                    (tenant_id, group_link_id, health_status, is_banned, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, groupLinkId, healthStatus, banned, now, now);
        return groupLinkId;
    }

    private void insertMembership(Long accountId, Long groupLinkId, String groupJid) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO account_group_membership
                    (tenant_id, account_id, group_link_id, group_jid, last_seen_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, groupLinkId, groupJid, now, now, now);
    }

    private void insertDynamicTarget(Long taskId, Long accountId, String phone) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO marketing_task_target
                    (tenant_id, marketing_task_id, account_id, account_phone, target_scope,
                     group_link_id, group_jid, group_link_url, group_name,
                     status, sent_message_count, failed_message_count, retry_count,
                     last_attempt_at, last_sent_at, last_reason, created_at, updated_at)
                VALUES
                    (?, ?, ?, ?, 2, NULL, NULL, NULL, NULL, 1, 0, 0, 0, NULL, NULL, NULL, ?, ?)
                """, TEST_TENANT_ID, taskId, accountId, phone, now, now);
    }
```

- [ ] **Step 8: Run worker tests and verify they pass**

Run:

```bash
mvn -pl armada-api -Dtest=MarketingRoundWorkerTest,MarketingRoundWorkerDbTest,MarketingRoundMapperDbTest test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add armada-api/src/main/java/com/armada/marketing/scheduler/ResolvedMarketingSendTarget.java \
  armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java \
  armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml \
  armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java \
  armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java \
  armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerDbTest.java \
  armada-api/src/test/java/com/armada/marketing/mapper/MarketingRoundMapperDbTest.java
git commit -m "feat: expand dynamic marketing account targets"
```

## Task 4: Frontend Explicit Account Scope Selection

**Files:**
- Modify: `../wheel-saas-pure-web/src/api/marketing-task.ts`
- Create: `../wheel-saas-pure-web/src/views/task/group-marketing/composables/useMarketingTargetSelection.ts`
- Create: `../wheel-saas-pure-web/src/views/task/group-marketing/composables/useMarketingTargetSelection.test.ts`
- Modify: `../wheel-saas-pure-web/src/views/task/group-marketing/components/GroupMarketingCreateDrawer.vue`
- Modify: `../wheel-saas-pure-web/src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts`
- Modify: `../wheel-saas-pure-web/src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts`
- Modify: `../wheel-saas-pure-web/src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts`

- [ ] **Step 1: Write failing frontend selection helper tests**

Create `useMarketingTargetSelection.test.ts`:

```ts
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { buildMarketingSelections } from "./useMarketingTargetSelection";

describe("marketing target selection", () => {
  it("builds account dynamic selections from account keys", () => {
    assert.deepEqual(buildMarketingSelections(["account:101"]), [
      { accountId: 101, targetScope: "ACCOUNT_DYNAMIC", groupLinkIds: [] }
    ]);
  });

  it("builds fixed group selections from group keys", () => {
    assert.deepEqual(
      buildMarketingSelections(["group:101:11", "group:101:12"]),
      [{ accountId: 101, targetScope: "GROUP_FIXED", groupLinkIds: [11, 12] }]
    );
  });

  it("rejects mixed account and group scope for the same account", () => {
    assert.throws(
      () => buildMarketingSelections(["account:101", "group:101:11"]),
      /同一账号不能同时选择账号维度和指定群组/
    );
  });
});
```

- [ ] **Step 2: Run helper tests and verify they fail**

Run from `../wheel-saas-pure-web`:

```bash
pnpm test src/views/task/group-marketing/composables/useMarketingTargetSelection.test.ts
```

Expected: FAIL because `useMarketingTargetSelection.ts` does not exist.

- [ ] **Step 3: Add frontend API type and selection helper**

In `src/api/marketing-task.ts`, add:

```ts
export type MarketingSelectionTargetScope =
  | "GROUP_FIXED"
  | "ACCOUNT_DYNAMIC";
```

Change `MarketingSelection`:

```ts
export interface MarketingSelection {
  accountId: number;
  targetScope: MarketingSelectionTargetScope;
  groupLinkIds: number[];
}
```

Create `useMarketingTargetSelection.ts`:

```ts
import type { MarketingSelection } from "@/api/marketing-task";

function numberFrom(value: string): number | null {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export function accountKey(accountId: number): string {
  return `account:${accountId}`;
}

export function groupKey(accountId: number, groupLinkId: number): string {
  return `group:${accountId}:${groupLinkId}`;
}

export function buildMarketingSelections(
  checkedKeys: Array<string | number>
): MarketingSelection[] {
  const dynamicAccounts = new Set<number>();
  const fixedGroups = new Map<number, number[]>();

  for (const rawKey of checkedKeys) {
    const key = String(rawKey);
    if (key.startsWith("account:")) {
      const accountId = numberFrom(key.split(":")[1] ?? "");
      if (accountId !== null) dynamicAccounts.add(accountId);
      continue;
    }
    if (key.startsWith("group:")) {
      const [, accountIdRaw, groupLinkIdRaw] = key.split(":");
      const accountId = numberFrom(accountIdRaw ?? "");
      const groupLinkId = numberFrom(groupLinkIdRaw ?? "");
      if (accountId === null || groupLinkId === null) continue;
      const groups = fixedGroups.get(accountId) ?? [];
      groups.push(groupLinkId);
      fixedGroups.set(accountId, groups);
    }
  }

  for (const accountId of dynamicAccounts) {
    if (fixedGroups.has(accountId)) {
      throw new Error("同一账号不能同时选择账号维度和指定群组");
    }
  }

  return [
    ...Array.from(dynamicAccounts).map(accountId => ({
      accountId,
      targetScope: "ACCOUNT_DYNAMIC" as const,
      groupLinkIds: []
    })),
    ...Array.from(fixedGroups.entries()).map(([accountId, groupLinkIds]) => ({
      accountId,
      targetScope: "GROUP_FIXED" as const,
      groupLinkIds: Array.from(new Set(groupLinkIds))
    }))
  ];
}
```

- [ ] **Step 4: Run helper tests and verify they pass**

Run:

```bash
pnpm test src/views/task/group-marketing/composables/useMarketingTargetSelection.test.ts
```

Expected: PASS.

- [ ] **Step 5: Update drawer to use strict account-vs-group selection**

In `GroupMarketingCreateDrawer.vue`:

Add import:

```ts
import {
  accountKey,
  buildMarketingSelections,
  groupKey
} from "../composables/useMarketingTargetSelection";
```

Change tree key builders:

```ts
    id: accountKey(account.accountId),
```

```ts
      id: groupKey(account.accountId, group.groupLinkId),
```

Change default keys to accounts:

```ts
function defaultCheckedKeys(): string[] {
  return props.treeAccounts
    .filter(account => account.status === "ONLINE")
    .map(account => accountKey(account.accountId));
}
```

Change `buildSelections`:

```ts
function buildSelections(): MarketingSelection[] {
  const checked = treeRef.value?.getCheckedKeys(false) ?? [];
  return buildMarketingSelections(checked);
}
```

Add strict checking to `<el-tree>`:

```vue
            check-strictly
```

Change toolbar text:

```vue
              在线账号 {{ onlineAccountCount }} 个 · 当前可见群组
              {{ totalGroupCount }} 个
```

- [ ] **Step 6: Update drawer static test**

In `GroupMarketingCreateDrawer.test.ts`, add:

```ts
  it("uses strict tree checking so account scope is not the same as selecting all groups", () => {
    assert.match(source, /check-strictly/);
    assert.match(source, /buildMarketingSelections/);
    assert.match(source, /getCheckedKeys\(false\)/);
  });
```

- [ ] **Step 7: Update page validation test expectations**

In `useGroupMarketingTaskPage.test.ts`, update existing payload expectations:

```ts
      selections: [{ accountId: 3, targetScope: "GROUP_FIXED", groupLinkIds: [11] }]
```

Add a create test for account dynamic payload:

```ts
  it("creates a marketing task with account dynamic selection", async () => {
    resetArmadaMock({ list: [], total: 0, page: 1, pageSize: 10 });
    const pageState = useGroupMarketingTaskPage();
    pageState.accountGroups.value = [
      {
        id: 8,
        name: "北美账号",
        totalAccounts: 1,
        onlineAccounts: 1,
        abnormalAccounts: 0,
        bannedAccounts: 0,
        updatedAt: "2026-07-04 15:00:00",
        systemBuiltin: false
      }
    ];
    pageState.marketingTemplates.value = [
      {
        id: 18,
        templateName: "活动模板",
        linkMode: 1,
        textType: "PROMO",
        content: "标题",
        bodyText: "正文",
        buttons: []
      }
    ];
    pageState.createForm.taskName = "账号维度任务";
    pageState.createForm.accountGroupId = 8;
    pageState.createForm.marketingTemplateId = 18;

    await pageState.createTask({
      form: { ...pageState.createForm },
      selections: [{ accountId: 3, targetScope: "ACCOUNT_DYNAMIC", groupLinkIds: [] }]
    });

    const calls = armadaCalls();
    assert.deepEqual((calls[0].opts as { data: { selections: unknown } }).data.selections, [
      { accountId: 3, targetScope: "ACCOUNT_DYNAMIC", groupLinkIds: [] }
    ]);
  });
```

Change validation message in `useGroupMarketingTaskPage.ts`:

```ts
      ElMessage.warning("请至少选择一个发送账号");
```

- [ ] **Step 8: Run frontend tests**

Run from `../wheel-saas-pure-web`:

```bash
pnpm test src/views/task/group-marketing/composables/useMarketingTargetSelection.test.ts \
  src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
```

Expected: PASS.

- [ ] **Step 9: Commit frontend changes**

```bash
git -C ../wheel-saas-pure-web add src/api/marketing-task.ts \
  src/views/task/group-marketing/composables/useMarketingTargetSelection.ts \
  src/views/task/group-marketing/composables/useMarketingTargetSelection.test.ts \
  src/views/task/group-marketing/components/GroupMarketingCreateDrawer.vue \
  src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
git -C ../wheel-saas-pure-web commit -m "feat: select marketing account target scope"
```

## Task 5: Full Verification

**Files:**
- Verify backend and frontend changed files from Tasks 1-4.

- [ ] **Step 1: Run backend targeted tests**

Run from `armada`:

```bash
mvn -pl armada-api -Dtest=MarketingKafkaRoundSendMigrationDbTest,MarketingTaskCreateReadDbTest,MarketingTaskControllerDbTest,MarketingRoundWorkerTest,MarketingRoundWorkerDbTest,MarketingRoundMapperDbTest test
```

Expected: PASS.

- [ ] **Step 2: Run backend module test gate**

Run:

```bash
mvn -pl armada-api test
```

Expected: PASS.

- [ ] **Step 3: Run frontend targeted tests**

Run from `../wheel-saas-pure-web`:

```bash
pnpm test src/views/task/group-marketing/composables/useMarketingTargetSelection.test.ts \
  src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
```

Expected: PASS.

- [ ] **Step 4: Run frontend typecheck/build gates**

Run:

```bash
pnpm typecheck
pnpm build
```

Expected: PASS.

- [ ] **Step 5: Check git status in both repositories**

Run:

```bash
git status --short
git -C ../wheel-saas-pure-web status --short
```

Expected: only unrelated pre-existing `.claude/worktrees` entries in `armada`, and no unstaged task files.
