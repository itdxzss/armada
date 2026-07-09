# Account List Group Count Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show real post-takeover group counts in the account list and persist the first-detected group join time for future marketing filters.

**Architecture:** Keep the existing baseline model as the single source for history filtering. `account.groups_reported` writes only baseline-diffed active membership rows; account list SQL aggregates those active rows into `groupsNum`. Marketing account tree must not capture baseline from UI lazy loading.

**Tech Stack:** Java 17, Spring Boot, MyBatis XML, Flyway, MapStruct, JUnit/AssertJ, Vue 3 TypeScript.

---

## File Map

- Create: `armada-api/src/main/resources/db/migration/V047__account_group_membership_joined_at.sql`
  Adds `account_group_membership.joined_at` and an account/joined index.
- Modify: `armada-api/src/main/java/com/armada/group/model/entity/AccountGroupMembership.java`
  Adds `joinedAt`.
- Modify: `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
  Inserts `joined_at`, preserves it for existing active rows, and assigns a fresh value for a new active row after rejoin.
- Modify: `armada-api/src/test/java/com/armada/group/service/AccountGroupMembershipReportServiceDbTest.java`
  Locks `joined_at` behavior with DbTests.
- Modify: `armada-api/src/main/java/com/armada/account/model/vo/AccountListVoRow.java`
  Adds mapper projection field `groupsNum`.
- Modify: `armada-api/src/main/resources/mapper/account/AccountMapper.xml`
  Aggregates active membership count into `groupsNum`.
- Modify: `armada-api/src/main/java/com/armada/account/converter/AccountConverter.java`
  Stops forcing `groupsNum=0`; keeps `friendsNum=0`.
- Modify: `armada-api/src/test/java/com/armada/account/mapper/AccountListMapperDbTest.java`
  Locks SQL aggregation.
- Modify: `armada-api/src/test/java/com/armada/account/converter/AccountConverterTest.java`
  Locks converter mapping.
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeService.java`
  Stops capturing baseline from marketing lazy loading.
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskAccountTreeDbTest.java`
  Updates PENDING account-tree behavior.
- Modify: `.harness/changes/account-list-group-count/summary.md`
  Tracks change, DB impact, validation evidence.
- Verify only: `wheel-saas-pure-web/src/api/account.ts`
  Existing mapping already supports `groupsNum -> groups_num`; only update tests if needed.

---

### Task 1: Persist First-Detected Join Time

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V047__account_group_membership_joined_at.sql`
- Modify: `armada-api/src/main/java/com/armada/group/model/entity/AccountGroupMembership.java`
- Modify: `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- Test: `armada-api/src/test/java/com/armada/group/service/AccountGroupMembershipReportServiceDbTest.java`

- [ ] **Step 1: Write failing DbTests**

Add assertions to `applyGroupsReported_filtersBaselineUpsertsVisibleMembershipAndDeletesMissingMemberships`:

```java
Long joinedAt = jdbc.queryForObject("""
        SELECT joined_at
        FROM account_group_membership
        WHERE account_id = ?
          AND group_jid = ?
          AND deleted_at IS NULL
        """, Long.class, accountId, visibleJid);
assertThat(joinedAt).isNotNull();
assertThat(joinedAt).isBetween(1782626401000L, System.currentTimeMillis());
```

Add a new test:

```java
@Test
void applyGroupsReported_preservesJoinedAtForStillActiveMembershipAndResetsAfterRejoin() {
    long accountId = seedAccount("923300001004");
    String groupJid = "120363joined-at@g.us";
    seedBaseline(accountId, "[]");
    long groupLinkId = seedExistingGroup(groupJid);
    long originalJoinedAt = 1782626300000L;
    seedMembership(accountId, groupLinkId, groupJid, originalJoinedAt);

    service.applyGroupsReported(new AccountGroupsReportedEvent(
            TEST_TENANT_ID,
            accountId,
            "acc_923300001004",
            1782626401000L,
            List.of(new AccountGroupsReportedEvent.Group(
                    groupJid, "持续在群", 12, null, null, false, false, null)),
            "evt-joined-at-1"));

    assertThat(activeJoinedAt(accountId, groupJid)).isEqualTo(originalJoinedAt);

    service.applyGroupsReported(new AccountGroupsReportedEvent(
            TEST_TENANT_ID,
            accountId,
            "acc_923300001004",
            1782626501000L,
            List.of(),
            "evt-joined-at-2"));

    service.applyGroupsReported(new AccountGroupsReportedEvent(
            TEST_TENANT_ID,
            accountId,
            "acc_923300001004",
            1782626601000L,
            List.of(new AccountGroupsReportedEvent.Group(
                    groupJid, "重新入群", 12, null, null, false, false, null)),
            "evt-joined-at-3"));

    assertThat(activeJoinedAt(accountId, groupJid)).isNotEqualTo(originalJoinedAt);
}
```

Add helpers:

```java
private void seedMembership(long accountId, long groupLinkId, String groupJid, long joinedAt) {
    long now = System.currentTimeMillis();
    jdbc.update("""
            INSERT INTO account_group_membership
                (tenant_id, account_id, group_link_id, group_jid, is_admin,
                 joined_at, last_seen_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?)
            """, TEST_TENANT_ID, accountId, groupLinkId, groupJid, joinedAt, now, now, now);
}

private Long activeJoinedAt(long accountId, String groupJid) {
    return jdbc.queryForObject("""
            SELECT joined_at
            FROM account_group_membership
            WHERE account_id = ?
              AND group_jid = ?
              AND deleted_at IS NULL
            """, Long.class, accountId, groupJid);
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
armada-api/dbtest.sh AccountGroupMembershipReportServiceDbTest
```

Expected: FAIL because `joined_at` does not exist.

- [ ] **Step 3: Implement schema and mapper**

Create `V047__account_group_membership_joined_at.sql` with guarded `ADD COLUMN`, backfill, and guarded index creation. Add `joinedAt` getter/setter to `AccountGroupMembership`. Update `upsertMembership` to insert `joined_at` and use:

```sql
joined_at = COALESCE(account_group_membership.joined_at, VALUES(joined_at))
```

- [ ] **Step 4: Run test and verify GREEN**

Run the same Maven command. Expected: PASS.

---

### Task 2: Return Real groupsNum From Account List

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/model/vo/AccountListVoRow.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/account/converter/AccountConverter.java`
- Test: `armada-api/src/test/java/com/armada/account/mapper/AccountListMapperDbTest.java`
- Test: `armada-api/src/test/java/com/armada/account/converter/AccountConverterTest.java`

- [ ] **Step 1: Write failing mapper and converter tests**

Add to `AccountListMapperDbTest`:

```java
@Test
void listAccounts_mapsActiveMembershipCountToGroupsNum() {
    long now = System.currentTimeMillis();
    Account account = insertAccount("86135" + (now % 10000000L), now);
    insertDefaultState(account.getId(), now);
    seedAccountMembership(account.getId(), "120363list-active-1@g.us", null, now);
    seedAccountMembership(account.getId(), "120363list-active-2@g.us", null, now);
    seedAccountMembership(account.getId(), "120363list-deleted@g.us", now, now);

    AccountQuery q = new AccountQuery();
    q.setPhone(account.getWsPhone());

    AccountListVoRow row = accountMapper.selectPage(q).stream()
            .filter(item -> item.getId().equals(account.getId()))
            .findFirst()
            .orElseThrow();

    assertThat(row.getGroupsNum()).isEqualTo(2);
}
```

Add helper:

```java
private void seedAccountMembership(Long accountId, String groupJid, Long deletedAt, long now) {
    long groupLinkId = jdbc.queryForObject("""
            INSERT INTO group_link
                (tenant_id, link_url, origin, membership_state, created_at, updated_at)
            VALUES (?, ?, 5, 2, ?, ?)
            RETURNING id
            """, Long.class, TEST_TENANT_ID, "wa://group/" + groupJid, now, now);
    jdbc.update("""
            INSERT INTO account_group_membership
                (tenant_id, account_id, group_link_id, group_jid, joined_at,
                 last_seen_at, created_at, updated_at, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, TEST_TENANT_ID, accountId, groupLinkId, groupJid, now, now, now, now, deletedAt);
}
```

If MySQL `RETURNING` is unsupported in tests, use the existing `insertAndReturnId` helper pattern from group DbTests.

Add to `AccountConverterTest`:

```java
@Test
void toAccountListVO_mapsGroupsNumFromProjectionAndKeepsFriendsNumZero() {
    AccountListVoRow row = new AccountListVoRow();
    row.setGroupsNum(3);

    AccountListVO vo = converter.toAccountListVO(row);

    assertThat(vo.friendsNum()).isZero();
    assertThat(vo.groupsNum()).isEqualTo(3);
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
armada-api/dbtest.sh AccountListMapperDbTest
cd armada-api && mvn -Dtest=AccountConverterTest test
```

Expected: FAIL because `groupsNum` is absent or still forced to 0.

- [ ] **Step 3: Implement projection and SQL aggregation**

Add `groupsNum` field/getter/setter to `AccountListVoRow`. Add this select expression in `AccountMapper.xml`:

```sql
COALESCE(gm.groups_num, 0) AS groupsNum
```

Join a grouped subquery:

```sql
LEFT JOIN (
  SELECT tenant_id, account_id, COUNT(*) AS groups_num
  FROM account_group_membership
  WHERE deleted_at IS NULL
  GROUP BY tenant_id, account_id
) gm ON gm.tenant_id = a.tenant_id AND gm.account_id = a.id
```

Remove `@Mapping(target = "groupsNum", constant = "0")` from `AccountConverter`.

- [ ] **Step 4: Run tests and verify GREEN**

Run the same Maven command. Expected: PASS.

---

### Task 3: Stop Marketing Lazy Baseline Capture

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeService.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskAccountTreeDbTest.java`

- [ ] **Step 1: Write failing test**

Update the PENDING account-tree test so that opening the marketing tree does not change baseline state and does not write baseline JSON. Expected behavior:

```java
assertThat(jdbc.queryForObject(
        "SELECT group_baseline_state FROM account WHERE id = ?",
        Integer.class, pendingAccountId)).isEqualTo(1);
assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM account_group_baseline WHERE account_id = ?",
        Integer.class, pendingAccountId)).isZero();
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
armada-api/dbtest.sh MarketingTaskAccountTreeDbTest
```

Expected: FAIL because current service captures baseline from lazy loading.

- [ ] **Step 3: Implement minimal behavior change**

In `MarketingAccountTreeRealtimeService.refreshAccount`, change `BASELINE_PENDING` handling to return an empty group list with no baseline write and no membership refresh. Remove the private `capturePendingBaseline` method if unused, and remove now-unused `membershipMapper` / `objectMapper` dependencies only if no other code in the class needs them.

- [ ] **Step 4: Run test and verify GREEN**

Run the same Maven command. Expected: PASS.

---

### Task 4: Frontend Contract Check

**Files:**
- Verify: `wheel-saas-pure-web/src/api/account.ts`
- Test: `wheel-saas-pure-web/src/api/account.test.ts`

- [ ] **Step 1: Write or confirm failing frontend mapping test**

Ensure the account API test includes a backend row with:

```ts
friendsNum: 0,
groupsNum: 2
```

and expects:

```ts
assert.equal(row.friends_num, 0);
assert.equal(row.groups_num, 2);
```

- [ ] **Step 2: Run frontend test**

Run:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/account.test.ts
```

Expected: PASS if mapping already exists; otherwise FAIL then fix only `src/api/account.ts`.

---

### Task 5: Change Record and Verification

**Files:**
- Modify: `.harness/changes/account-list-group-count/summary.md`
- Create: `.harness/changes/account-list-group-count/db-migrations.sql`
- Create: `.harness/changes/account-list-group-count/rollback.sql`

- [ ] **Step 1: Document DB and rollback**

Record the Flyway migration and rollback SQL:

```sql
ALTER TABLE account_group_membership DROP INDEX idx_account_group_membership_account_joined;
ALTER TABLE account_group_membership DROP COLUMN joined_at;
```

- [ ] **Step 2: Run focused backend verification**

Run:

```bash
armada-api/dbtest.sh AccountGroupMembershipReportServiceDbTest
armada-api/dbtest.sh AccountListMapperDbTest
armada-api/dbtest.sh MarketingTaskAccountTreeDbTest
cd armada-api && mvn -Dtest=AccountConverterTest,AccountGroupMembershipReportServiceImplTest,MarketingAccountTreeRealtimeServiceTest test
```

Expected: PASS.

- [ ] **Step 3: Run XML/build verification**

Run:

```bash
cd armada-api && mvn -DskipTests compile
xmllint --noout armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml armada-api/src/main/resources/mapper/account/AccountMapper.xml
```

Expected: PASS or report unrelated pre-existing failures with evidence.

- [ ] **Step 4: Run frontend verification if files changed**

Run:

```bash
cd ../wheel-saas-pure-web && node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/account.test.ts
```

Expected: PASS.

---

## Plan Self-Review

- Spec coverage:群组数量、baseline PENDING/CAPTURED/DISABLED、`joined_at`、营销树不懒加载、好友数不做、前端映射均有任务。
- Placeholder scan:未发现占位项或未定义任务。
- Type consistency:`groupsNum` 后端 camelCase,前端映射到 `groups_num`;`joinedAt` Java 字段映射 `joined_at` 数据列。
