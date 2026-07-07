# Group Creation Marketing Account Replacement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a 建群营销 item's assigned account is no longer usable or online, replace it with a currently normal online account from the same account group and continue the existing item.

**Architecture:** Keep the existing task/item data model. Add one mapper query for the first currently available account in the task account group and one mapper update that rewrites the claimed item's account snapshot while it is in `GROUP_CREATING`. Protocol group-create failures still end the item as failed; this change only handles pre-protocol account unavailability.

**Tech Stack:** Java 17, Spring Boot, MyBatis XML mappers, JUnit 5, Mockito.

---

## File Structure

- Modify `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`: load the task before account validation, choose a replacement when the assigned account is unusable/offline, persist the replacement snapshot, then continue the existing group-create flow.
- Modify `armada-api/src/main/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapper.java`: add `selectFirstAvailableAccountCandidateByGroupId` and `updateItemAccountIfCreating`.
- Modify `armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml`: add the SQL for those mapper methods.
- Modify `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`: add a red test for offline-assigned-account replacement and update existing offline/unusable tests for the no-replacement path.

---

### Task 1: Worker Test Coverage

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`

- [ ] **Step 1: Add a failing replacement test**

Add a test named `offlineAssignedAccountIsReplacedByAvailableGroupAccountBeforeGroupCreate`. It should arrange item account `7` as offline, task account group `8`, replacement account `9` as normal online, and successful protocol group create. It must assert:

```java
verify(groupCreationMapper).updateItemAccountIfCreating(eq(11L), eq(9L), eq("8613999999999"), eq("acc_9"), anyLong());
verify(groupCreatePort).create("acc_9", "活动群-1", List.of("8613900000000", "8613911111111"), true);
verify(groupLinkRegistryService).registerSelfBuiltGroup(eq("120363created@g.us"), eq("活动群-1"),
        eq(9L), eq("8613999999999"), eq(2), anyLong());
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=GroupCreationMarketingWorkerTest#offlineAssignedAccountIsReplacedByAvailableGroupAccountBeforeGroupCreate test
```

Expected: fail because the mapper methods and worker replacement behavior do not exist.

---

### Task 2: Mapper and Worker Implementation

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java`

- [ ] **Step 1: Add mapper methods**

Add:

```java
GroupCreationMarketingAccountCandidate selectFirstAvailableAccountCandidateByGroupId(@Param("accountGroupId") Long accountGroupId);

int updateItemAccountIfCreating(@Param("id") Long id,
                                @Param("accountId") Long accountId,
                                @Param("accountPhone") String accountPhone,
                                @Param("protocolAccountId") String protocolAccountId,
                                @Param("updatedAt") long updatedAt);
```

- [ ] **Step 2: Add SQL**

`selectFirstAvailableAccountCandidateByGroupId` uses the same availability criteria as task creation:

```sql
WHERE a.deleted_at IS NULL
  AND a.account_group_id = #{accountGroupId}
  AND a.protocol_account_id IS NOT NULL
  AND TRIM(a.protocol_account_id) <> ''
  AND s.login_state = 1
  AND s.account_state = 2
  AND (s.risk_status IS NULL OR s.risk_status = 1)
  AND s.mute_status IS NULL
ORDER BY a.id ASC
LIMIT 1
```

`updateItemAccountIfCreating` updates the item account snapshot only when `status = 2`.

- [ ] **Step 3: Implement worker replacement**

After claim:

```java
GroupCreationMarketingTask task = requireTask(item.getTaskId());
GroupCreationMarketingAccountCandidate account =
        resolveExecutableAccount(item, task, groupCreationMapper.selectAccountCandidateByAccountId(item.getAccountId()), now);
if (account == null) {
    return;
}
```

`resolveExecutableAccount` returns the original account when usable and online. Otherwise it selects a first available account from the task group, persists it with `updateItemAccountIfCreating`, mutates the in-memory item snapshot, and returns the replacement. If no replacement exists, it preserves the existing abandoned behavior.

---

### Task 3: Verification

**Files:**
- Test: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`

- [ ] **Step 1: Run focused worker tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=GroupCreationMarketingWorkerTest test
```

Expected: all worker tests pass.

- [ ] **Step 2: Run mapper-related compile coverage**

Run:

```bash
mvn -Dtest=GroupCreationMarketingTaskMapperDbTest test
```

Expected: mapper XML loads and tests pass.
