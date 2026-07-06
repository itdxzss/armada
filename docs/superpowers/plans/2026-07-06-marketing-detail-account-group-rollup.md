# Marketing Detail Account Group Rollup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show marketing task detail rows by account, with per-group send statistics aggregated from actual send attempts.

**Architecture:** Armada extends the detail API with account rollup VOs computed from `marketing_task_send_attempt`, while keeping existing `targets` for compatibility. The Vue detail drawer consumes `accountTargets` directly and uses Element Plus table expansion for all group rows.

**Tech Stack:** Java 17, Spring Boot, MyBatis XML, JUnit DB tests, Vue 3, TypeScript, Element Plus, Node test runner.

---

## File Structure

- Modify `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskDetailVO.java`: add `accountTargets`.
- Create `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountTargetVO.java`: account row contract.
- Create `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskGroupStatVO.java`: group rollup contract.
- Create `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountGroupStatRow.java`: MyBatis raw group row.
- Modify `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`: add `selectAccountGroupStatsByTaskId`.
- Modify `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`: add group attempt aggregation SQL.
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`: build account rollups from targets plus group stats.
- Modify `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`: add failing DB tests.
- Modify `wheel-saas-pure-web/src/api/marketing-task.ts`: add frontend account/group detail types.
- Create `wheel-saas-pure-web/src/views/task/group-marketing/components/detail-rollup.ts`: small display helpers.
- Create `wheel-saas-pure-web/src/views/task/group-marketing/components/detail-rollup.test.ts`: helper tests.
- Modify `wheel-saas-pure-web/src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue`: summary and table UI.
- Create `wheel-saas-pure-web/.harness/changes/marketing-detail-account-group-rollup/summary.md`: frontend change note.

### Task 1: Backend Failing Tests

**Files:**
- Modify `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`

- [ ] **Step 1: Write failing DB tests**

Add tests that seed a created task, insert three attempts, and assert `accountTargets`.

```java
    @Test
    void getDetail_rollsUpAccountGroupsFromSendAttempts() {
        Fixture fixture = seedFixture("detail-rollup");
        long secondGroupId = seedGroup("detail-rollup-second", "120363099@g.us", "https://chat.whatsapp.com/detail-rollup-second");
        MarketingTaskVO created = service.createTask(request("发送记录聚合任务", fixture.accountGroupId(), fixture.templateId(), "PENDING",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId(), secondGroupId)))));
        List<Long> targetIds = jdbc.queryForList(
                "SELECT id FROM marketing_task_target WHERE marketing_task_id = ? ORDER BY id ASC",
                Long.class,
                created.id());
        insertAttempt(created.id(), targetIds.get(0), fixture.groupLinkId(), fixture.groupJid(), "群A", 1, null, null, 1000L);
        insertAttempt(created.id(), targetIds.get(0), fixture.groupLinkId(), fixture.groupJid(), "群A", 2, "MUTED", "群禁言", 2000L);
        insertAttempt(created.id(), targetIds.get(1), secondGroupId, "120363099@g.us", "群B", 1, null, null, 3000L);

        MarketingTaskDetailVO detail = service.getDetail(created.id());

        assertThat(detail.accountTargets()).singleElement().satisfies(account -> {
            assertThat(account.accountId()).isEqualTo(fixture.accountId());
            assertThat(account.accountPhone()).isEqualTo(fixture.phone());
            assertThat(account.sentMessageCount()).isEqualTo(2);
            assertThat(account.failedMessageCount()).isEqualTo(1);
            assertThat(account.lastAttemptAt()).isEqualTo(3000L);
            assertThat(account.groups()).hasSize(2);
            assertThat(account.groups().get(0).groupJid()).isEqualTo(fixture.groupJid());
            assertThat(account.groups().get(0).sentMessageCount()).isEqualTo(1);
            assertThat(account.groups().get(0).failedMessageCount()).isEqualTo(1);
            assertThat(account.groups().get(0).lastReason()).isEqualTo("群禁言");
            assertThat(account.groups().get(1).groupJid()).isEqualTo("120363099@g.us");
            assertThat(account.groups().get(1).sentMessageCount()).isEqualTo(1);
        });
    }

    @Test
    void getDetail_keepsAccountRowsWithoutSendAttempts() {
        Fixture fixture = seedFixture("detail-empty-rollup");
        MarketingTaskVO created = service.createTask(request("未发送聚合任务", fixture.accountGroupId(), fixture.templateId(), "PENDING",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));

        MarketingTaskDetailVO detail = service.getDetail(created.id());

        assertThat(detail.accountTargets()).singleElement().satisfies(account -> {
            assertThat(account.accountId()).isEqualTo(fixture.accountId());
            assertThat(account.sentMessageCount()).isZero();
            assertThat(account.failedMessageCount()).isZero();
            assertThat(account.groups()).isEmpty();
        });
    }
```

- [ ] **Step 2: Add local test helpers**

Add helpers in the same test class.

```java
    private long seedGroup(String suffix, String groupJid, String linkUrl) {
        long now = System.currentTimeMillis();
        long groupId = insertAndReturnId("""
                INSERT INTO group_link (tenant_id, link_url, group_name, membership_state, created_at, updated_at)
                VALUES (?, ?, ?, 2, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, linkUrl);
            ps.setString(3, "营销群-" + suffix);
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
        jdbc.update("""
                INSERT INTO group_link_preview (tenant_id, group_link_id, group_jid, wa_subject, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, groupId, groupJid, "预览群-" + suffix, now, now);
        return groupId;
    }

    private void insertAttempt(long taskId,
                               long targetId,
                               long groupLinkId,
                               String groupJid,
                               String groupName,
                               int status,
                               String reasonCode,
                               String reasonMessage,
                               long resultAt) {
        jdbc.update("""
                INSERT INTO marketing_task_send_attempt
                    (tenant_id, marketing_task_id, target_id, group_link_id, group_jid, group_name,
                     round_no, attempt_no, is_retry, command_id, status, reason_code, reason_message,
                     submitted_at, result_at, attempted_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, 1, 0, ?, ?, ?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, taskId, targetId, groupLinkId, groupJid, groupName,
                "cmd-" + targetId + "-" + resultAt, status, reasonCode, reasonMessage,
                resultAt - 10, resultAt, resultAt - 20, resultAt - 30);
    }
```

- [ ] **Step 3: Run test and verify RED**

Run: `cd armada-api && ./dbtest.sh MarketingTaskCreateReadDbTest`

Expected: compilation fails because `MarketingTaskDetailVO.accountTargets()` does not exist.

### Task 2: Backend Implementation

**Files:**
- Create `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountTargetVO.java`
- Create `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskGroupStatVO.java`
- Create `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountGroupStatRow.java`
- Modify `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskDetailVO.java`
- Modify `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`

- [ ] **Step 1: Add VO records**

Create `MarketingTaskGroupStatVO`.

```java
package com.armada.marketing.model.vo;

/**
 * 单账号下一个真实发送群组的聚合统计。
 */
public record MarketingTaskGroupStatVO(
        Long groupLinkId,
        String groupJid,
        String groupLinkUrl,
        String groupName,
        Integer sentMessageCount,
        Integer failedMessageCount,
        Long lastAttemptAt,
        Long lastSentAt,
        String lastReason) {
}
```

Create `MarketingTaskAccountTargetVO`.

```java
package com.armada.marketing.model.vo;

import java.util.List;

/**
 * 营销任务明细页账号维度统计。
 */
public record MarketingTaskAccountTargetVO(
        Long accountId,
        String accountPhone,
        Integer status,
        Integer sentMessageCount,
        Integer failedMessageCount,
        Long lastAttemptAt,
        Long lastSentAt,
        String lastReason,
        List<MarketingTaskGroupStatVO> groups) {
}
```

Create `MarketingTaskAccountGroupStatRow`.

```java
package com.armada.marketing.model.vo;

/**
 * 从发送记录聚合出的账号+群组原始行。
 */
public class MarketingTaskAccountGroupStatRow {
    private Long accountId;
    private String accountPhone;
    private Long groupLinkId;
    private String groupJid;
    private String groupLinkUrl;
    private String groupName;
    private Integer sentMessageCount;
    private Integer failedMessageCount;
    private Long lastAttemptAt;
    private Long lastSentAt;
    private String lastReason;

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

    public String getGroupLinkUrl() {
        return groupLinkUrl;
    }

    public void setGroupLinkUrl(String groupLinkUrl) {
        this.groupLinkUrl = groupLinkUrl;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public Integer getSentMessageCount() {
        return sentMessageCount;
    }

    public void setSentMessageCount(Integer sentMessageCount) {
        this.sentMessageCount = sentMessageCount;
    }

    public Integer getFailedMessageCount() {
        return failedMessageCount;
    }

    public void setFailedMessageCount(Integer failedMessageCount) {
        this.failedMessageCount = failedMessageCount;
    }

    public Long getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Long lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Long getLastSentAt() {
        return lastSentAt;
    }

    public void setLastSentAt(Long lastSentAt) {
        this.lastSentAt = lastSentAt;
    }

    public String getLastReason() {
        return lastReason;
    }

    public void setLastReason(String lastReason) {
        this.lastReason = lastReason;
    }
}
```

- [ ] **Step 2: Extend detail VO**

Update `MarketingTaskDetailVO` constructor fields to append:

```java
        List<MarketingTaskTargetVO> targets,
        List<MarketingTaskAccountTargetVO> accountTargets) {
}
```

- [ ] **Step 3: Add mapper method**

Add to `MarketingTaskMapper`.

```java
    /** 从真实发送记录按账号+群组聚合营销明细。 */
    List<MarketingTaskAccountGroupStatRow> selectAccountGroupStatsByTaskId(@Param("taskId") Long taskId);
```

- [ ] **Step 4: Add aggregation SQL**

Add to `MarketingTaskMapper.xml`.

```xml
    <select id="selectAccountGroupStatsByTaskId" resultType="com.armada.marketing.model.vo.MarketingTaskAccountGroupStatRow">
        SELECT t.account_id AS accountId,
               t.account_phone AS accountPhone,
               COALESCE(a.group_link_id, p.group_link_id, t.group_link_id) AS groupLinkId,
               COALESCE(NULLIF(TRIM(a.group_jid), ''), NULLIF(TRIM(p.group_jid), ''), t.group_jid) AS groupJid,
               COALESCE(g.link_url, t.group_link_url) AS groupLinkUrl,
               COALESCE(NULLIF(TRIM(a.group_name), ''), NULLIF(TRIM(g.group_name), ''), p.wa_subject, t.group_name) AS groupName,
               SUM(CASE WHEN a.status = 1 THEN 1 ELSE 0 END) AS sentMessageCount,
               SUM(CASE WHEN a.status = 2 THEN 1 ELSE 0 END) AS failedMessageCount,
               MAX(COALESCE(a.result_at, a.attempted_at, a.created_at)) AS lastAttemptAt,
               MAX(CASE WHEN a.status = 1 THEN COALESCE(a.result_at, a.attempted_at, a.created_at) ELSE NULL END) AS lastSentAt,
               SUBSTRING_INDEX(
                   GROUP_CONCAT(
                       CASE
                           WHEN a.status = 2 THEN COALESCE(NULLIF(TRIM(a.reason_message), ''), a.reason_code, '发送失败')
                           ELSE NULL
                       END
                       ORDER BY COALESCE(a.result_at, a.attempted_at, a.created_at) DESC
                       SEPARATOR '\n'
                   ),
                   '\n',
                   1
               ) AS lastReason
        FROM marketing_task_target t
        JOIN marketing_task_send_attempt a ON a.target_id = t.id
        LEFT JOIN group_link_preview p ON p.group_jid = COALESCE(NULLIF(TRIM(a.group_jid), ''), NULLIF(TRIM(t.group_jid), ''))
        LEFT JOIN group_link g ON g.id = COALESCE(a.group_link_id, p.group_link_id, t.group_link_id)
        WHERE t.marketing_task_id = #{taskId}
          AND a.marketing_task_id = #{taskId}
          AND a.status IN (1, 2)
        GROUP BY t.account_id,
                 t.account_phone,
                 COALESCE(a.group_link_id, p.group_link_id, t.group_link_id),
                 COALESCE(NULLIF(TRIM(a.group_jid), ''), NULLIF(TRIM(p.group_jid), ''), t.group_jid),
                 COALESCE(g.link_url, t.group_link_url),
                 COALESCE(NULLIF(TRIM(a.group_name), ''), NULLIF(TRIM(g.group_name), ''), p.wa_subject, t.group_name)
        ORDER BY t.account_id ASC, MAX(COALESCE(a.result_at, a.attempted_at, a.created_at)) DESC
    </select>
```

- [ ] **Step 5: Build account rollups in service**

In `getDetail`, fetch `groupStats` and pass account rollups to `toDetailVO`.

```java
        List<MarketingTaskTargetVO> targets = taskMapper.selectTargetsByTaskId(id)
                .stream().map(MarketingTaskServiceImpl::toTargetVO).toList();
        List<MarketingTaskAccountGroupStatRow> groupStats = taskMapper.selectAccountGroupStatsByTaskId(id);
        List<MarketingTaskAccountTargetVO> accountTargets = toAccountTargets(targets, groupStats);
        log.info("营销任务详情查询 id={} targets={} accounts={}", id, targets.size(), accountTargets.size());
        return toDetailVO(task, targets, accountTargets);
```

Add helpers:

```java
    private static List<MarketingTaskAccountTargetVO> toAccountTargets(List<MarketingTaskTargetVO> targets,
                                                                       List<MarketingTaskAccountGroupStatRow> groupStats) {
        Map<Long, List<MarketingTaskAccountGroupStatRow>> statsByAccount = groupStats.stream()
                .collect(Collectors.groupingBy(MarketingTaskAccountGroupStatRow::getAccountId, LinkedHashMap::new, Collectors.toList()));
        return targets.stream()
                .collect(Collectors.toMap(MarketingTaskTargetVO::accountId, Function.identity(), MarketingTaskServiceImpl::mergeAccountTarget, LinkedHashMap::new))
                .values()
                .stream()
                .map(target -> toAccountTarget(target, statsByAccount.getOrDefault(target.accountId(), List.of())))
                .toList();
    }

    private static MarketingTaskTargetVO mergeAccountTarget(MarketingTaskTargetVO left, MarketingTaskTargetVO right) {
        return left;
    }

    private static MarketingTaskAccountTargetVO toAccountTarget(MarketingTaskTargetVO target,
                                                                List<MarketingTaskAccountGroupStatRow> rows) {
        List<MarketingTaskGroupStatVO> groups = rows.stream().map(MarketingTaskServiceImpl::toGroupStatVO).toList();
        int sent = rows.stream().mapToInt(row -> zero(row.getSentMessageCount())).sum();
        int failed = rows.stream().mapToInt(row -> zero(row.getFailedMessageCount())).sum();
        Long lastAttemptAt = rows.stream().map(MarketingTaskAccountGroupStatRow::getLastAttemptAt)
                .filter(Objects::nonNull).max(Long::compareTo).orElse(null);
        Long lastSentAt = rows.stream().map(MarketingTaskAccountGroupStatRow::getLastSentAt)
                .filter(Objects::nonNull).max(Long::compareTo).orElse(null);
        String lastReason = rows.stream()
                .filter(row -> row.getLastReason() != null && !row.getLastReason().isBlank())
                .max(Comparator.comparing(row -> row.getLastAttemptAt() == null ? 0L : row.getLastAttemptAt()))
                .map(MarketingTaskAccountGroupStatRow::getLastReason)
                .orElse(null);
        return new MarketingTaskAccountTargetVO(target.accountId(), target.accountPhone(), target.status(),
                sent, failed, lastAttemptAt, lastSentAt, lastReason, groups);
    }

    private static MarketingTaskGroupStatVO toGroupStatVO(MarketingTaskAccountGroupStatRow row) {
        return new MarketingTaskGroupStatVO(row.getGroupLinkId(), row.getGroupJid(), row.getGroupLinkUrl(),
                row.getGroupName(), zero(row.getSentMessageCount()), zero(row.getFailedMessageCount()),
                row.getLastAttemptAt(), row.getLastSentAt(), row.getLastReason());
    }

    private static int zero(Integer value) {
        return value == null ? 0 : value;
    }
```

Add imports:

```java
import com.armada.marketing.model.vo.MarketingTaskAccountGroupStatRow;
import com.armada.marketing.model.vo.MarketingTaskAccountTargetVO;
import com.armada.marketing.model.vo.MarketingTaskGroupStatVO;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
```

- [ ] **Step 6: Run backend test and verify GREEN**

Run: `cd armada-api && ./dbtest.sh MarketingTaskCreateReadDbTest`

Expected: build succeeds and the new tests pass.

### Task 3: Frontend Failing Tests

**Files:**
- Create `wheel-saas-pure-web/src/views/task/group-marketing/components/detail-rollup.test.ts`

- [ ] **Step 1: Write helper tests**

```ts
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  firstGroupSummary,
  groupCountLabel,
  hasGroupRows
} from "./detail-rollup";
import type { MarketingTaskAccountTargetRow } from "@/api/marketing-task";

const row: MarketingTaskAccountTargetRow = {
  accountId: 3,
  accountPhone: "923300000003",
  status: 3,
  sentMessageCount: 2,
  failedMessageCount: 1,
  lastAttemptAt: 3000,
  lastSentAt: 3000,
  lastReason: "群禁言",
  groups: [
    {
      groupLinkId: 11,
      groupJid: "120363011@g.us",
      groupLinkUrl: "https://chat.whatsapp.com/11",
      groupName: "群A",
      sentMessageCount: 1,
      failedMessageCount: 1,
      lastAttemptAt: 2000,
      lastSentAt: 1000,
      lastReason: "群禁言"
    },
    {
      groupLinkId: 12,
      groupJid: "120363012@g.us",
      groupLinkUrl: "https://chat.whatsapp.com/12",
      groupName: "群B",
      sentMessageCount: 1,
      failedMessageCount: 0,
      lastAttemptAt: 3000,
      lastSentAt: 3000,
      lastReason: null
    }
  ]
};

describe("marketing detail rollup helpers", () => {
  it("uses the first group as the collapsed summary", () => {
    assert.equal(firstGroupSummary(row), "群A · 1条");
    assert.equal(groupCountLabel(row), "共 2 个群");
    assert.equal(hasGroupRows(row), true);
  });

  it("shows an empty send record label when no group rows exist", () => {
    assert.equal(firstGroupSummary({ ...row, groups: [] }), "暂无发送记录");
    assert.equal(groupCountLabel({ ...row, groups: [] }), "");
    assert.equal(hasGroupRows({ ...row, groups: [] }), false);
  });
});
```

- [ ] **Step 2: Run frontend test and verify RED**

Run: `node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/task/group-marketing/components/detail-rollup.test.ts`

Expected: module not found for `./detail-rollup`.

### Task 4: Frontend Implementation

**Files:**
- Modify `wheel-saas-pure-web/src/api/marketing-task.ts`
- Create `wheel-saas-pure-web/src/views/task/group-marketing/components/detail-rollup.ts`
- Modify `wheel-saas-pure-web/src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue`
- Create `wheel-saas-pure-web/.harness/changes/marketing-detail-account-group-rollup/summary.md`

- [ ] **Step 1: Extend API types**

Add:

```ts
export interface MarketingTaskGroupStatRow {
  groupLinkId?: number | null;
  groupJid?: string | null;
  groupLinkUrl?: string | null;
  groupName?: string | null;
  sentMessageCount: number;
  failedMessageCount: number;
  lastAttemptAt?: number | null;
  lastSentAt?: number | null;
  lastReason?: string | null;
}

export interface MarketingTaskAccountTargetRow {
  accountId: number;
  accountPhone: string;
  status: MarketingTaskTargetStatus;
  sentMessageCount: number;
  failedMessageCount: number;
  lastAttemptAt?: number | null;
  lastSentAt?: number | null;
  lastReason?: string | null;
  groups: MarketingTaskGroupStatRow[];
}
```

Update detail:

```ts
export interface MarketingTaskDetail extends MarketingTaskRow {
  targets: MarketingTaskTargetRow[];
  accountTargets?: MarketingTaskAccountTargetRow[];
}
```

- [ ] **Step 2: Add display helpers**

Create `detail-rollup.ts`.

```ts
import type { MarketingTaskAccountTargetRow } from "@/api/marketing-task";

export function hasGroupRows(row: MarketingTaskAccountTargetRow): boolean {
  return row.groups.length > 0;
}

export function firstGroupSummary(row: MarketingTaskAccountTargetRow): string {
  const first = row.groups[0];
  if (!first) return "暂无发送记录";
  const name = first.groupName || first.groupJid || "未命名群组";
  return `${name} · ${first.sentMessageCount}条`;
}

export function groupCountLabel(row: MarketingTaskAccountTargetRow): string {
  return row.groups.length > 1 ? `共 ${row.groups.length} 个群` : "";
}
```

- [ ] **Step 3: Update drawer UI**

In `GroupMarketingDetailDrawer.vue`:

- import `computed`, `MarketingTaskAccountTargetRow`, and helpers.
- compute `accountRows` as `detail?.accountTargets ?? []`.
- change summary title to `总发送条数`.
- remove the `最后发送时间` description item.
- change table data to `accountRows`.
- add expand column and group detail table.
- rename send column to `号发送总条数`.
- add combined `群组情况` column with `firstGroupSummary(row)`.

- [ ] **Step 4: Add frontend change summary**

Create `.harness/changes/marketing-detail-account-group-rollup/summary.md`.

```md
# 营销明细账号群组聚合

## 背景

营销任务明细页从目标行展示调整为账号行展示。固定选群和账号动态的群组明细统一来自后端发送记录聚合。

## 改动

- 明细汇总区将发送条数改为总发送条数,移除最后发送时间。
- 明细表一行一个账号,发送条数列改为号发送总条数。
- 群组情况列默认展示第一条群组统计,展开行展示全部群组。
```

- [ ] **Step 5: Run frontend helper test and verify GREEN**

Run: `node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/task/group-marketing/components/detail-rollup.test.ts`

Expected: test passes.

### Task 5: Full Verification

**Files:**
- No code changes unless verification finds defects.

- [ ] **Step 1: Run backend focused tests**

Run: `cd armada-api && ./dbtest.sh MarketingTaskCreateReadDbTest`

Expected: success.

- [ ] **Step 2: Run frontend focused tests**

Run: `node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/task/group-marketing/components/detail-rollup.test.ts src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts`

Expected: success.

- [ ] **Step 3: Run frontend type/build check**

Run: `./node_modules/.bin/rimraf dist && ./node_modules/.bin/vite build`

Expected: success.

- [ ] **Step 4: Inspect diffs**

Run in both repos: `git diff --stat` and `git diff --check`

Expected: no whitespace errors; diffs limited to marketing detail backend/frontend files and docs.
