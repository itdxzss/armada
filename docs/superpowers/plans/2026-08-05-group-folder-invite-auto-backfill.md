# Group Folder Invite Auto Backfill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically recover real WhatsApp invite codes for existing and future foldered self-created groups while preserving the rule that groups without a real invite code are not usable pull-task targets.

**Architecture:** Reuse the existing durable `group_metadata_sync_task` state machine. Enrich due tasks with a computed `inviteRequired` flag, rotate execution accounts by attempt number, use fresh metadata participant roles to select an online Armada administrator for invite reads, enqueue internal groups when assigned to a folder, and use one Flyway data migration to requeue existing missing-invite groups.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis/MyBatis-Plus, Flyway SQL, JUnit 5, Mockito, AssertJ, H2 MySQL mode, Maven.

---

## File structure

### New files

- `armada-api/src/main/java/com/armada/group/service/GroupInviteUnavailableException.java` — safe, domain-specific retry signal after metadata is preserved but a required invite is still unavailable.
- `armada-api/src/main/java/com/armada/group/service/impl/GroupFolderAssignmentService.java` — focused transaction for folder validation, locking, assignment, and internal-group metadata enqueue.
- `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorMapperInMemoryTest.java` — focused H2 test for real account-selection SQL, ordering, phone filtering, tenant isolation, and rotation inputs.
- `armada-api/src/test/java/com/armada/group/service/impl/GroupFolderAssignmentServiceTest.java` — folder assignment and enqueue service tests.
- `armada-api/src/main/resources/db/migration/V099__group_folder_invite_auto_backfill.sql` — idempotently creates/resets durable tasks for existing foldered internal groups without invite codes.
- `armada-api/src/test/java/com/armada/group/GroupFolderInviteAutoBackfillMigrationSqlTest.java` — structural contract for MySQL-specific migration SQL.
- `.harness/changes/group-folder-invite-auto-backfill/summary.md` — final impact, constraints, evidence, and rollback record.
- `.harness/changes/group-folder-invite-auto-backfill/db-migrations.sql` — reviewed copy of the data migration.
- `.harness/changes/group-folder-invite-auto-backfill/rollback.sql` — non-destructive rollback statement and rationale.

### Modified files

- `armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java` — replace the single-account query with bounded candidate and fresh-admin candidate queries.
- `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml` — return ordered candidate lists and filter fresh administrators by confirmed phone.
- `armada-api/src/main/java/com/armada/group/service/GroupExecutionAccountSelector.java` — choose candidates by attempt modulo and expose fresh-admin selection.
- `armada-api/src/main/java/com/armada/group/scheduler/GroupMetadataSyncJob.java` — pass the completed-attempt count into account selection.
- `armada-api/src/test/java/com/armada/group/scheduler/GroupMetadataSyncJobTest.java` — lock candidate rotation input.
- `armada-api/src/test/java/com/armada/group/mapper/AccountGroupMembershipMapperSqlTest.java` — update static SQL-shape assertions for the two new query IDs.
- `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorTest.java` — unit-test modulo rotation and fresh-admin filtering delegation.
- `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorDbTest.java` — update the optional true-MySQL test to the new selector signature.
- `armada-api/src/main/java/com/armada/group/model/entity/GroupMetadataSyncTask.java` — add non-persistent `inviteRequired` runtime property.
- `armada-api/src/main/resources/mapper/group/GroupMetadataSyncTaskMapper.xml` — compute `inviteRequired` and resume both deferred and failed tasks on account online.
- `armada-api/src/main/java/com/armada/group/mapper/GroupMetadataSyncTaskMapper.java` — rename recovery mapper method and accept recoverable statuses.
- `armada-api/src/main/java/com/armada/group/service/GroupMetadataSyncTaskService.java` — rename the account-online recovery contract.
- `armada-api/src/main/java/com/armada/group/service/impl/GroupMetadataSyncTaskServiceImpl.java` — resume `DEFERRED` and `FAILED` tasks.
- `armada-api/src/main/java/com/armada/account/service/impl/AccountStateChangedSinkAdapter.java` — call the broadened recovery contract after an applied ONLINE event.
- `armada-api/src/test/java/com/armada/group/mapper/GroupMetadataSyncTaskMapperDbTest.java` — H2 coverage for `inviteRequired` and account-online recovery.
- `armada-api/src/test/java/com/armada/group/service/impl/GroupMetadataSyncTaskServiceImplTest.java` — unit coverage for recoverable status arguments.
- `armada-api/src/test/java/com/armada/account/service/impl/AccountStateChangedSinkAdapterTest.java` — update the service interaction assertion.
- `armada-api/src/main/java/com/armada/group/service/impl/GroupMetadataSnapshotServiceImpl.java` — identify an Armada admin from fresh metadata and preserve partial metadata before retrying a required invite.
- `armada-api/src/test/java/com/armada/group/service/impl/GroupMetadataSnapshotServiceImplTest.java` — regression tests for stale admin flags, LID-only identities, and partial success.
- `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java` — delegate folder assignment to the focused service without growing the existing nine-parameter constructor.
- `armada-api/src/test/java/com/armada/group/service/GroupLinkServiceImplTest.java` — replace embedded assignment tests with a delegation assertion and updated fixture constructor.
- `.harness/changes/2026-08-05-group-folder-invite-auto-backfill.md` — remove after its content is moved into the required change directory.

## Task 1: Rotate execution candidates and select fresh administrators

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupExecutionAccountSelector.java`
- Modify: `armada-api/src/main/java/com/armada/group/scheduler/GroupMetadataSyncJob.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorTest.java`
- Create: `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorMapperInMemoryTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/scheduler/GroupMetadataSyncJobTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/mapper/AccountGroupMembershipMapperSqlTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorDbTest.java`

- [ ] **Step 1: Write failing selector unit tests**

Replace the current single-result stubs in `GroupExecutionAccountSelectorTest` with candidate-list tests containing these behaviors:

```java
@Test
void findRotatesBoundedCandidatesByCompletedAttemptCount() {
    GroupExecutionAccount first = account(7L, "923310000001", true);
    GroupExecutionAccount second = account(8L, "923310000002", false);
    when(mapper.selectGroupExecutionAccounts(
            10L, AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 4))
            .thenReturn(List.of(first, second));
    GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

    assertThat(selector.find(10L, 0)).contains(first);
    assertThat(selector.find(10L, 1)).contains(second);
    assertThat(selector.find(10L, 2)).contains(first);
}

@Test
void findAdminByPhonesNormalizesPhonesAndRotatesOnlyFreshAdmins() {
    GroupExecutionAccount first = account(7L, "923310000001", false);
    GroupExecutionAccount second = account(8L, "923310000002", false);
    when(mapper.selectGroupExecutionAccountsByPhones(
            10L,
            List.of("923310000001", "923310000002"),
            AccountLoginStateCode.ONLINE,
            AccountStateCode.NORMAL,
            4)).thenReturn(List.of(first, second));
    GroupExecutionAccountSelector selector = new GroupExecutionAccountSelector(mapper);

    assertThat(selector.findAdminByPhones(
            10L, List.of(" 923310000002 ", "+92 3310000001", ""), 1))
            .contains(second);
}

private static GroupExecutionAccount account(long id, String phone, boolean admin) {
    return new GroupExecutionAccount(id, "WEB", "acc_" + phone, phone, admin);
}
```

Update the empty-result test to stub
`selectGroupExecutionAccounts(10L, AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL, 4)`
with `List.of()` and assert that `require(10L)` still throws `GROUP_EXECUTOR_UNAVAILABLE`.

- [ ] **Step 2: Run the selector unit test and verify red**

Run:

```bash
cd armada-api
mvn -Dtest='GroupExecutionAccountSelectorTest' test
```

Expected: compilation fails because `selectGroupExecutionAccounts`, `selectGroupExecutionAccountsByPhones`, `find(Long,int)`, and `findAdminByPhones` do not exist.

- [ ] **Step 3: Replace the mapper contract and XML query**

Replace `selectGroupExecutionAccount` with these two mapper methods:

```java
List<GroupExecutionAccount> selectGroupExecutionAccounts(
        @Param("groupLinkId") Long groupLinkId,
        @Param("onlineLoginState") int onlineLoginState,
        @Param("normalAccountState") int normalAccountState,
        @Param("limit") int limit);

List<GroupExecutionAccount> selectGroupExecutionAccountsByPhones(
        @Param("groupLinkId") Long groupLinkId,
        @Param("phones") List<String> phones,
        @Param("onlineLoginState") int onlineLoginState,
        @Param("normalAccountState") int normalAccountState,
        @Param("limit") int limit);
```

Replace the XML query with a shared column/join fragment and two list queries:

```xml
<sql id="groupExecutionAccountColumnsAndJoins">
  SELECT a.id AS accountId,
         a.protocol_id AS protocolId,
         a.protocol_account_id AS protocolAccountId,
         a.ws_phone AS wsPhone,
         COALESCE(m.is_admin, 0) AS groupAdmin
  FROM account_group_membership m
  INNER JOIN account a
    ON a.tenant_id = m.tenant_id
   AND a.id = m.account_id
   AND a.deleted_at IS NULL
   AND a.protocol_account_id IS NOT NULL
   AND a.protocol_account_id != ''
   AND a.ws_phone IS NOT NULL
   AND a.ws_phone != ''
  INNER JOIN account_state s
    ON s.tenant_id = a.tenant_id
   AND s.account_id = a.id
   AND s.login_state = #{onlineLoginState}
   AND s.account_state = #{normalAccountState}
  WHERE m.group_link_id = #{groupLinkId}
    AND m.deleted_at IS NULL
    AND m.membership_status = 1
</sql>

<select id="selectGroupExecutionAccounts"
        resultType="com.armada.group.model.vo.GroupExecutionAccount">
  <include refid="groupExecutionAccountColumnsAndJoins"/>
  ORDER BY COALESCE(m.is_admin, 0) DESC,
           COALESCE(m.last_seen_at, 0) DESC,
           m.id ASC
  LIMIT #{limit}
</select>

<select id="selectGroupExecutionAccountsByPhones"
        resultType="com.armada.group.model.vo.GroupExecutionAccount">
  <include refid="groupExecutionAccountColumnsAndJoins"/>
    AND a.ws_phone IN
    <foreach collection="phones" item="phone" open="(" separator="," close=")">
      #{phone}
    </foreach>
  ORDER BY COALESCE(m.last_seen_at, 0) DESC,
           m.id ASC
  LIMIT #{limit}
</select>
```

- [ ] **Step 4: Implement bounded modulo selection**

Replace `GroupExecutionAccountSelector` with the following behavior while keeping its existing package and imports:

```java
@Component
public final class GroupExecutionAccountSelector {

    private static final int MAX_RETRY_CANDIDATES = 4;

    private final AccountGroupMembershipMapper mapper;

    public GroupExecutionAccountSelector(AccountGroupMembershipMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<GroupExecutionAccount> find(Long groupLinkId, int completedAttempts) {
        return candidateAt(mapper.selectGroupExecutionAccounts(
                groupLinkId,
                AccountLoginStateCode.ONLINE,
                AccountStateCode.NORMAL,
                MAX_RETRY_CANDIDATES), completedAttempts);
    }

    public Optional<GroupExecutionAccount> findAdminByPhones(
            Long groupLinkId,
            List<String> adminPhones,
            int completedAttempts) {
        List<String> normalizedPhones = adminPhones == null
                ? List.of()
                : adminPhones.stream()
                        .map(GroupExecutionAccountSelector::digitsOnly)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList();
        if (normalizedPhones.isEmpty()) {
            return Optional.empty();
        }
        return candidateAt(mapper.selectGroupExecutionAccountsByPhones(
                groupLinkId,
                normalizedPhones,
                AccountLoginStateCode.ONLINE,
                AccountStateCode.NORMAL,
                MAX_RETRY_CANDIDATES), completedAttempts);
    }

    public GroupExecutionAccount require(Long groupLinkId) {
        return find(groupLinkId, 0).orElseThrow(() -> new BusinessException(
                ErrorCode.GROUP_EXECUTOR_UNAVAILABLE,
                "没有在线且仍在该群内的账号"));
    }

    private static Optional<GroupExecutionAccount> candidateAt(
            List<GroupExecutionAccount> candidates,
            int completedAttempts) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        int index = Math.floorMod(completedAttempts, candidates.size());
        return Optional.of(candidates.get(index));
    }

    private static String digitsOnly(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        return digits.isBlank() ? null : digits;
    }
}
```

Update `GroupMetadataSyncJob.process` to call:

```java
Optional<GroupExecutionAccount> selected = selector.find(
        task.getGroupLinkId(),
        task.getAttemptCount() == null ? 0 : task.getAttemptCount());
```

Update the job tests to stub `selector.find(10L, 0)` and `selector.find(20L, 0)`. Update the optional true-MySQL test to call `selector.find(groupLinkId, 0)`.

- [ ] **Step 5: Add a focused H2 mapper test**

Create `GroupExecutionAccountSelectorMapperInMemoryTest` using the same H2/MyBatis configuration pattern as `GroupMetadataSyncTaskMapperDbTest`, but load only `AccountGroupMembershipMapper.xml`. Its fixture must create minimal `account`, `account_state`, and `account_group_membership` tables and insert:

```java
long ordinary = seedAccount("923310000001", true, now);
long newestAdmin = seedAccount("923310000002", true, now);
long olderAdmin = seedAccount("923310000003", true, now);
long offlineAdmin = seedAccount("923310000004", false, now);
seedMembership(ordinary, groupLinkId, false, now + 30_000L);
seedMembership(newestAdmin, groupLinkId, true, now + 20_000L);
seedMembership(olderAdmin, groupLinkId, true, now + 10_000L);
seedMembership(offlineAdmin, groupLinkId, true, now + 40_000L);
```

Assert the real XML returns `[newestAdmin, olderAdmin, ordinary]`, excludes the offline account, isolates another tenant, and that `selectGroupExecutionAccountsByPhones` returns only the confirmed phone subset in stable order.

- [ ] **Step 6: Update the static SQL contract**

Change `AccountGroupMembershipMapperSqlTest` to assert:

```java
assertTrue(xml.contains("<select id=\"selectGroupExecutionAccounts\""));
assertTrue(xml.contains("<select id=\"selectGroupExecutionAccountsByPhones\""));
assertTrue(xml.contains("a.ws_phone IN"));
assertTrue(xml.contains("LIMIT #{limit}"));
```

Remove the obsolete assertion for `selectGroupExecutionAccount`.

- [ ] **Step 7: Run focused tests and XML validation**

Run:

```bash
cd armada-api
mvn -Dtest='GroupExecutionAccountSelectorTest,GroupExecutionAccountSelectorMapperInMemoryTest,GroupMetadataSyncJobTest,AccountGroupMembershipMapperSqlTest' test
xmllint --noout src/main/resources/mapper/group/AccountGroupMembershipMapper.xml
```

Expected: all selected tests pass; `xmllint` exits 0.

- [ ] **Step 8: Commit candidate rotation**

```bash
git add armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java \
  armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml \
  armada-api/src/main/java/com/armada/group/service/GroupExecutionAccountSelector.java \
  armada-api/src/main/java/com/armada/group/scheduler/GroupMetadataSyncJob.java \
  armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorTest.java \
  armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorMapperInMemoryTest.java \
  armada-api/src/test/java/com/armada/group/scheduler/GroupMetadataSyncJobTest.java \
  armada-api/src/test/java/com/armada/group/mapper/AccountGroupMembershipMapperSqlTest.java \
  armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorDbTest.java
git commit -m "fix: rotate group metadata execution accounts"
```

## Task 2: Mark required invites and reopen failed tasks on account online

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/model/entity/GroupMetadataSyncTask.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/GroupMetadataSyncTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/GroupMetadataSyncTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupMetadataSyncTaskService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupMetadataSyncTaskServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountStateChangedSinkAdapter.java`
- Modify: `armada-api/src/test/java/com/armada/group/mapper/GroupMetadataSyncTaskMapperDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/GroupMetadataSyncTaskServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/account/service/impl/AccountStateChangedSinkAdapterTest.java`

- [ ] **Step 1: Write failing H2 tests for `inviteRequired` and recovery**

Extend `GroupMetadataSyncTaskMapperDbTest.resetSchema` with minimal `group_link`, `group_link_preview`, `account`, `account_state`, and `account_group_membership` tables. Add a test that seeds four due tasks and asserts:

```java
List<GroupMetadataSyncTask> due = mapper.selectDueCandidates(
        List.of(GroupMetadataSyncStatus.PENDING.code()), 2_000L, 10);

assertThat(due)
        .extracting(GroupMetadataSyncTask::getGroupLinkId,
                GroupMetadataSyncTask::getInviteRequired)
        .containsExactly(
                tuple(101L, true),
                tuple(102L, false),
                tuple(103L, false),
                tuple(104L, false));
```

The fixtures must represent, respectively: foldered internal missing invite, foldered internal with invite, unassigned internal missing invite, and foldered external link.

Add another test that creates `DEFERRED`, `FAILED`, `SUCCEEDED`, and `RUNNING` tasks for groups containing the same account, then calls the renamed recovery mapper and asserts only `DEFERRED` and `FAILED` become `PENDING` with cleared errors and immediate `next_run_at`.

- [ ] **Step 2: Run the mapper test and verify red**

```bash
cd armada-api
mvn -Dtest='GroupMetadataSyncTaskMapperDbTest' test
```

Expected: compilation fails because `getInviteRequired` and the broadened recovery method are absent.

- [ ] **Step 3: Add the runtime-only entity property and SQL projection**

Add this non-persistent property and accessors to `GroupMetadataSyncTask`:

```java
/** 本次任务是否必须补齐运营分组使用的真实邀请码；非任务表持久列。 */
private Boolean inviteRequired;

public Boolean getInviteRequired() {
    return inviteRequired;
}

public void setInviteRequired(Boolean inviteRequired) {
    this.inviteRequired = inviteRequired;
}
```

Add this projection to `selectDueCandidates` after `groupJid`:

```sql
CASE
  WHEN link.folder_id IS NOT NULL
   AND link.link_url LIKE 'wa://group/%'
   AND NULLIF(TRIM(preview.invite_code), '') IS NULL
    THEN TRUE
  ELSE FALSE
END AS inviteRequired
```

- [ ] **Step 4: Broaden account-online recovery without touching running tasks**

Rename the service method to:

```java
void resumeRecoverableForAccount(Long accountId, long now);
```

Rename the mapper method to:

```java
int resumeRecoverableForAccount(
        @Param("accountId") Long accountId,
        @Param("recoverableStatuses") List<Integer> recoverableStatuses,
        @Param("pendingStatus") int pendingStatus,
        @Param("triggerSource") int triggerSource,
        @Param("now") long now);
```

Replace the XML predicate with:

```xml
WHERE task.status IN
<foreach collection="recoverableStatuses" item="status" open="(" separator="," close=")">
  #{status}
</foreach>
```

The update must retain the current joins and set `attempt_count=0`, `status=PENDING`, `trigger_source=ACCOUNT_ONLINE`, `next_run_at=now`, `rerun_requested=FALSE`, and both error columns to `NULL`.

Implement the service call with:

```java
mapper.resumeRecoverableForAccount(
        accountId,
        List.of(GroupMetadataSyncStatus.DEFERRED.code(), GroupMetadataSyncStatus.FAILED.code()),
        GroupMetadataSyncStatus.PENDING.code(),
        GroupMetadataSyncTrigger.ACCOUNT_ONLINE.code(),
        now);
```

Update `AccountStateChangedSinkAdapter` and its test to call `resumeRecoverableForAccount` only after an applied ONLINE state event.

- [ ] **Step 5: Run focused task-state tests and XML validation**

```bash
cd armada-api
mvn -Dtest='GroupMetadataSyncTaskMapperDbTest,GroupMetadataSyncTaskServiceImplTest,AccountStateChangedSinkAdapterTest' test
xmllint --noout src/main/resources/mapper/group/GroupMetadataSyncTaskMapper.xml
```

Expected: all selected tests pass; XML validation exits 0.

- [ ] **Step 6: Commit task runtime semantics**

```bash
git add armada-api/src/main/java/com/armada/group/model/entity/GroupMetadataSyncTask.java \
  armada-api/src/main/java/com/armada/group/mapper/GroupMetadataSyncTaskMapper.java \
  armada-api/src/main/resources/mapper/group/GroupMetadataSyncTaskMapper.xml \
  armada-api/src/main/java/com/armada/group/service/GroupMetadataSyncTaskService.java \
  armada-api/src/main/java/com/armada/group/service/impl/GroupMetadataSyncTaskServiceImpl.java \
  armada-api/src/main/java/com/armada/account/service/impl/AccountStateChangedSinkAdapter.java \
  armada-api/src/test/java/com/armada/group/mapper/GroupMetadataSyncTaskMapperDbTest.java \
  armada-api/src/test/java/com/armada/group/service/impl/GroupMetadataSyncTaskServiceImplTest.java \
  armada-api/src/test/java/com/armada/account/service/impl/AccountStateChangedSinkAdapterTest.java
git commit -m "fix: reopen missing group invites after account online"
```

## Task 3: Use fresh metadata administrators and preserve partial success

**Files:**
- Create: `armada-api/src/main/java/com/armada/group/service/GroupInviteUnavailableException.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupMetadataSnapshotServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/GroupMetadataSnapshotServiceImplTest.java`

- [ ] **Step 1: Write failing stale-admin and partial-success tests**

Add a mocked `GroupExecutionAccountSelector` to `GroupMetadataSnapshotServiceImplTest` and pass it into `service()`. Add these tests:

```java
@Test
void requiredInviteUsesFreshMetadataAdminWhenCachedFlagIsFalse() {
    GroupMetadataSyncTask task = task();
    task.setInviteRequired(true);
    task.setAttemptCount(1);
    GroupExecutionAccount reader = account(false);
    GroupExecutionAccount freshAdmin = new GroupExecutionAccount(
            78L, "WEB", "acc_admin", "8613800000000", false);
    when(metadataPort.getMetadata(reader.protocolRef(), task.getGroupJid()))
            .thenReturn(metadata(1_722_470_400L, true));
    when(selector.findAdminByPhones(
            task.getGroupLinkId(), List.of("8613800000000"), 0))
            .thenReturn(Optional.of(freshAdmin));
    when(invitePort.getInvite(freshAdmin.protocolRef(), task.getGroupJid()))
            .thenReturn(new GroupInviteResult(task.getGroupJid(), "invite-code", "url"));
    when(countryService.resolveActiveCountriesByPhoneNumbers(List.of("8613800000000")))
            .thenReturn(Map.of());
    when(persistence.persist(any(), anyList())).thenReturn(true);

    service().execute(task, reader);

    ArgumentCaptor<GroupLinkPreview> preview = ArgumentCaptor.forClass(GroupLinkPreview.class);
    verify(persistence).persist(preview.capture(), anyList());
    assertThat(preview.getValue().getInviteCode()).isEqualTo("invite-code");
}

@Test
void requiredInviteFailurePersistsMetadataThenSignalsRetry() {
    GroupMetadataSyncTask task = task();
    task.setInviteRequired(true);
    task.setAttemptCount(1);
    GroupExecutionAccount reader = account(false);
    GroupExecutionAccount freshAdmin = new GroupExecutionAccount(
            78L, "WEB", "acc_admin", "8613800000000", false);
    when(metadataPort.getMetadata(reader.protocolRef(), task.getGroupJid()))
            .thenReturn(metadata(1_722_470_400L, true));
    when(selector.findAdminByPhones(
            task.getGroupLinkId(), List.of("8613800000000"), 0))
            .thenReturn(Optional.of(freshAdmin));
    when(invitePort.getInvite(freshAdmin.protocolRef(), task.getGroupJid()))
            .thenThrow(new IllegalStateException("remote detail must not escape"));
    when(countryService.resolveActiveCountriesByPhoneNumbers(List.of("8613800000000")))
            .thenReturn(Map.of());
    when(persistence.persist(any(), anyList())).thenReturn(true);

    assertThatThrownBy(() -> service().execute(task, reader))
            .isInstanceOf(GroupInviteUnavailableException.class)
            .hasMessage("分组群邀请码暂不可用");
    verify(persistence).persist(any(), anyList());
}
```

Add a third test whose only admin participant is `12345@lid` with `phone=null`; assert no invite call is made, metadata is persisted, and a required invite raises `GroupInviteUnavailableException`.

Update the existing `administratorPersistsCompleteMetadataInviteGeoAndOwnerFirstSnapshot` fixture so its
reader is the metadata owner phone and the fresh selector returns it:

```java
GroupExecutionAccount account = new GroupExecutionAccount(
        77L, "WEB", "acc_77", "8613800000000", true);
when(selector.findAdminByPhones(
        task.getGroupLinkId(), List.of("8613800000000"), 0))
        .thenReturn(Optional.of(account));
```

- [ ] **Step 2: Run the snapshot test and verify red**

```bash
cd armada-api
mvn -Dtest='GroupMetadataSnapshotServiceImplTest' test
```

Expected: compilation fails because the selector dependency and `GroupInviteUnavailableException` do not exist.

- [ ] **Step 3: Add a safe retry exception**

Create:

```java
package com.armada.group.service;

/** 群 metadata 已保留，但运营分组所需真实邀请码仍不可用。 */
public final class GroupInviteUnavailableException extends RuntimeException {

    /** 创建不携带协议敏感详情的可重试异常。 */
    public GroupInviteUnavailableException() {
        super("分组群邀请码暂不可用");
    }
}
```

- [ ] **Step 4: Reorder snapshot execution around fresh roles**

Inject `GroupExecutionAccountSelector` into `GroupMetadataSnapshotServiceImpl`. In `execute`, normalize members before choosing the invite account, then use:

```java
List<String> freshAdminPhones = members.stream()
        .filter(row -> Boolean.TRUE.equals(row.getIsAdmin()))
        .map(WhatsappGroupMemberSnapshot::getPhone)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .sorted()
        .toList();
int completedAttempts = Math.max(0,
        (task.getAttemptCount() == null ? 0 : task.getAttemptCount()) - 1);
GroupExecutionAccount inviteAccount = selector.findAdminByPhones(
                task.getGroupLinkId(), freshAdminPhones, completedAttempts)
        .orElseGet(() -> freshAdminPhones.isEmpty() && account.groupAdmin() ? account : null);
String inviteCode = inviteAccount == null
        ? null
        : safeInviteCode(inviteAccount, groupJid);
```

Persist preview and members exactly once. After persistence and metrics recording, enforce only the computed task requirement:

```java
if (Boolean.TRUE.equals(task.getInviteRequired()) && inviteCode == null) {
    throw new GroupInviteUnavailableException();
}
```

Keep `safeInviteCode` logging limited to group JID and exception type, and keep returning `null` on protocol failure so metadata persists before the safe retry signal is raised.

- [ ] **Step 5: Run snapshot and scheduler tests**

```bash
cd armada-api
mvn -Dtest='GroupMetadataSnapshotServiceImplTest,GroupMetadataSyncJobTest' test
```

Expected: all selected tests pass. The scheduler test must still record the exception simple class name as task error code and the fixed safe message `群详情同步执行失败`.

- [ ] **Step 6: Commit fresh-admin invite recovery**

```bash
git add armada-api/src/main/java/com/armada/group/service/GroupInviteUnavailableException.java \
  armada-api/src/main/java/com/armada/group/service/impl/GroupMetadataSnapshotServiceImpl.java \
  armada-api/src/test/java/com/armada/group/service/impl/GroupMetadataSnapshotServiceImplTest.java
git commit -m "fix: recover group invites from fresh admin metadata"
```

## Task 4: Enqueue internal groups when assigned to an operational folder

**Files:**
- Create: `armada-api/src/main/java/com/armada/group/service/impl/GroupFolderAssignmentService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java`
- Create: `armada-api/src/test/java/com/armada/group/service/impl/GroupFolderAssignmentServiceTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/GroupLinkServiceImplTest.java`

- [ ] **Step 1: Write failing folder assignment tests**

Create `GroupFolderAssignmentServiceTest` with mocked `GroupLinkMapper`, `GroupFolderMapper`, and `GroupMetadataSyncTaskService`. Cover:

```java
@Test
void assigningFolderEnqueuesOnlyInternalGroupsAfterValidatedUpdate() {
    GroupFolder folder = new GroupFolder();
    folder.setId(10L);
    GroupLink internal = group(101L, "wa://group/120363internal@g.us");
    GroupLink external = group(102L, "https://chat.whatsapp.com/external");
    when(folderMapper.selectActiveByIdsForUpdate(List.of(10L))).thenReturn(List.of(folder));
    when(groupLinkMapper.selectActiveByIdsForUpdate(List.of(101L, 102L)))
            .thenReturn(List.of(internal, external));
    when(groupLinkMapper.assignFolder(eq(List.of(101L, 102L)), eq(10L), anyLong()))
            .thenReturn(2);

    int updated = service.assign(List.of(102L, 101L, 101L), 10L);

    assertThat(updated).isEqualTo(2);
    verify(metadataSyncTaskService).enqueue(
            eq(101L), eq(GroupMetadataSyncTrigger.BACKFILL), anyLong());
    verify(metadataSyncTaskService, never()).enqueue(
            eq(102L), any(), anyLong());
}
```

Also preserve the existing rejection tests for missing groups and missing folder, and add a null-folder test that performs assignment but never enqueues metadata.

- [ ] **Step 2: Run the new test and verify red**

```bash
cd armada-api
mvn -Dtest='GroupFolderAssignmentServiceTest' test
```

Expected: compilation fails because `GroupFolderAssignmentService` does not exist.

- [ ] **Step 3: Extract the focused transactional service**

Create a `@Service` with constructor dependencies `GroupLinkMapper`, `GroupFolderMapper`, and `GroupMetadataSyncTaskService`. Move the existing ID normalization, folder lock, group locks, and mapper update unchanged. After the mapper update, execute:

```java
if (folderId != null) {
    long triggeredAt = System.currentTimeMillis();
    groups.stream()
            .filter(GroupFolderAssignmentService::isInternalGroup)
            .forEach(group -> metadataSyncTaskService.enqueue(
                    group.getId(), GroupMetadataSyncTrigger.BACKFILL, triggeredAt));
}
```

Use this exact internal-link predicate:

```java
private static boolean isInternalGroup(GroupLink group) {
    return group.getLinkUrl() != null && group.getLinkUrl().startsWith("wa://group/");
}
```

Keep `BATCH_MAX=100`, the current lock order, validation messages, deduplication via `TreeSet`, and one shared `now` value for the assignment update and task triggers.

- [ ] **Step 4: Delegate without growing the existing constructor**

In `GroupLinkServiceImpl`, replace the `GroupFolderMapper` field and constructor parameter with `GroupFolderAssignmentService`. Replace the current `assignFolder` body with:

```java
@Override
public int assignFolder(List<Long> ids, Long folderId) {
    return folderAssignmentService.assign(ids, folderId);
}
```

Remove the now-unused `normalizeFolderAssignmentIds` method and `TreeSet` import. In `GroupLinkServiceImplTest`, mock the assignment service, update the constructor fixture, remove the three moved validation tests, and add:

```java
@Test
void assignFolderDelegatesToFocusedTransactionService() {
    when(folderAssignmentService.assign(List.of(102L, 101L), 10L)).thenReturn(2);

    assertThat(service.assignFolder(List.of(102L, 101L), 10L)).isEqualTo(2);

    verify(folderAssignmentService).assign(List.of(102L, 101L), 10L);
}
```

- [ ] **Step 5: Run folder service tests**

```bash
cd armada-api
mvn -Dtest='GroupFolderAssignmentServiceTest,GroupLinkServiceImplTest' test
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit folder-trigger behavior**

```bash
git add armada-api/src/main/java/com/armada/group/service/impl/GroupFolderAssignmentService.java \
  armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java \
  armada-api/src/test/java/com/armada/group/service/impl/GroupFolderAssignmentServiceTest.java \
  armada-api/src/test/java/com/armada/group/service/GroupLinkServiceImplTest.java
git commit -m "fix: enqueue invite sync when groups enter folders"
```

## Task 5: Add the one-time Flyway auto-backfill

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V099__group_folder_invite_auto_backfill.sql`
- Create: `armada-api/src/test/java/com/armada/group/GroupFolderInviteAutoBackfillMigrationSqlTest.java`
- Create: `.harness/changes/group-folder-invite-auto-backfill/db-migrations.sql`
- Create: `.harness/changes/group-folder-invite-auto-backfill/rollback.sql`

- [ ] **Step 1: Recheck the migration version before writing**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
rg --files armada-api/src/main/resources/db/migration | sort -V | tail -20
```

Expected at plan time: `V098__group_list_history_metadata.sql` is the highest version and `V099` is free. If another in-worktree change has occupied `V099`, use the next free integer consistently in the migration and test path before editing.

- [ ] **Step 2: Write a failing migration contract test**

Create `GroupFolderInviteAutoBackfillMigrationSqlTest`:

```java
package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** 分组内部群邀请码一次性自动回补迁移契约。 */
class GroupFolderInviteAutoBackfillMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V099__group_folder_invite_auto_backfill.sql");

    @Test
    void migrationRequeuesOnlyActiveFolderedInternalGroupsWithoutInvite() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("insert into group_metadata_sync_task")
                .contains("link.folder_id is not null")
                .contains("link.link_url like 'wa://group/%'")
                .contains("nullif(trim(preview.invite_code), '') is null")
                .contains("link.deleted_at is null")
                .contains("on duplicate key update")
                .contains("when status = 2 then status")
                .contains("when status = 2 then true")
                .contains("else 0 end")
                .contains("last_error_code")
                .contains("last_error_message");
        assertThat(sql).doesNotContain("delete from group_metadata_sync_task");
    }
}
```

- [ ] **Step 3: Run the migration test and verify red**

```bash
cd armada-api
mvn -Dtest='GroupFolderInviteAutoBackfillMigrationSqlTest' test
```

Expected: test errors because the V099 migration file does not exist.

- [ ] **Step 4: Create the idempotent data migration**

Create `V099__group_folder_invite_auto_backfill.sql` with this SQL:

```sql
SET @group_invite_backfill_now :=
    CAST(ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000) AS UNSIGNED);

INSERT INTO group_metadata_sync_task (
    tenant_id,
    group_link_id,
    status,
    trigger_source,
    attempt_count,
    next_run_at,
    lease_until,
    execution_account_id,
    rerun_requested,
    last_started_at,
    last_success_at,
    last_error_code,
    last_error_message,
    created_at,
    updated_at
)
SELECT link.tenant_id,
       link.id,
       1,
       7,
       0,
       @group_invite_backfill_now,
       NULL,
       NULL,
       0,
       NULL,
       NULL,
       NULL,
       NULL,
       @group_invite_backfill_now,
       @group_invite_backfill_now
FROM group_link link
LEFT JOIN group_link_preview preview
  ON preview.tenant_id = link.tenant_id
 AND preview.group_link_id = link.id
WHERE link.deleted_at IS NULL
  AND link.folder_id IS NOT NULL
  AND link.link_url LIKE 'wa://group/%'
  AND NULLIF(TRIM(preview.invite_code), '') IS NULL
ON DUPLICATE KEY UPDATE
    trigger_source = 7,
    attempt_count = CASE WHEN status = 2 THEN attempt_count ELSE 0 END,
    next_run_at = CASE WHEN status = 2 THEN next_run_at ELSE VALUES(next_run_at) END,
    lease_until = CASE WHEN status = 2 THEN lease_until ELSE NULL END,
    execution_account_id = CASE WHEN status = 2 THEN execution_account_id ELSE NULL END,
    rerun_requested = CASE WHEN status = 2 THEN TRUE ELSE FALSE END,
    last_error_code = CASE WHEN status = 2 THEN last_error_code ELSE NULL END,
    last_error_message = CASE WHEN status = 2 THEN last_error_message ELSE NULL END,
    status = CASE WHEN status = 2 THEN status ELSE 1 END,
    updated_at = VALUES(updated_at);
```

Use existing stable codes `1=PENDING`, `2=RUNNING`, and `7=BACKFILL`; do not add a parallel enum representation.

- [ ] **Step 5: Add migration and rollback records**

Copy the exact migration SQL into `.harness/changes/group-folder-invite-auto-backfill/db-migrations.sql`. Create the non-destructive rollback file:

```sql
-- 回滚应用版本时保留已排队任务和已取得的真实邀请码。
-- 这些任务无法与部署前已存在的 BACKFILL 任务可靠区分，禁止批量删除或恢复旧错误终态。
SELECT 1;
```

- [ ] **Step 6: Run migration contracts and version collision guard**

```bash
cd armada-api
mvn -Dtest='GroupFolderInviteAutoBackfillMigrationSqlTest,FlywayMigrationVersionContractTest' test
```

Expected: both tests pass and no normalized Flyway version collision is reported.

- [ ] **Step 7: Commit the automatic data backfill**

```bash
git add armada-api/src/main/resources/db/migration/V099__group_folder_invite_auto_backfill.sql \
  armada-api/src/test/java/com/armada/group/GroupFolderInviteAutoBackfillMigrationSqlTest.java \
  .harness/changes/group-folder-invite-auto-backfill/db-migrations.sql \
  .harness/changes/group-folder-invite-auto-backfill/rollback.sql
git commit -m "fix: backfill missing group folder invites"
```

## Task 6: Run regressions, review the diff, and finalize the change record

**Files:**
- Create: `.harness/changes/group-folder-invite-auto-backfill/summary.md`
- Delete: `.harness/changes/2026-08-05-group-folder-invite-auto-backfill.md`
- Verify: all files changed by Tasks 1-5

- [ ] **Step 1: Run the focused regression suite**

```bash
cd armada-api
mvn -Dtest='GroupExecutionAccountSelectorTest,GroupExecutionAccountSelectorMapperInMemoryTest,GroupMetadataSyncJobTest,GroupMetadataSyncTaskMapperDbTest,GroupMetadataSyncTaskServiceImplTest,AccountStateChangedSinkAdapterTest,GroupMetadataSnapshotServiceImplTest,GroupFolderAssignmentServiceTest,GroupLinkServiceImplTest,GroupFolderMapperInMemoryTest,GroupFolderInviteAutoBackfillMigrationSqlTest,FlywayMigrationVersionContractTest' test
```

Expected: all selected tests pass with zero failures and zero errors.

- [ ] **Step 2: Validate both modified Mapper XML files**

```bash
xmllint --noout \
  src/main/resources/mapper/group/AccountGroupMembershipMapper.xml \
  src/main/resources/mapper/group/GroupMetadataSyncTaskMapper.xml
```

Expected: exit code 0 and no output.

- [ ] **Step 3: Run the broader backend test suite**

```bash
mvn test
```

Expected: all locally repeatable tests pass. If an existing `*DbTest` attempts an unconfirmed external datasource, stop that test run, record its exact class and output, and retain the focused H2 suite as the local database evidence; do not connect to a true database without a separate environment confirmation.

- [ ] **Step 4: Review scope and formatting**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git status --short
git diff --stat HEAD~4..HEAD
git diff --check
git diff HEAD~4..HEAD -- armada-api/src/main .harness/changes/group-folder-invite-auto-backfill
```

Expected: no whitespace errors; no frontend, protocol, deployment, credential, or unrelated user files in the diff; the pre-existing `.claude/worktrees/agent-af50e0bc4d135f5c8` and `.claude/worktrees/wf_ca150a80-294-1` status entries remain untouched.

- [ ] **Step 5: Perform the manual architecture review**

Confirm all of the following against `.harness/rules/`:

```text
[ ] Controller -> Service -> Mapper is preserved; no Repository added.
[ ] The group domain only uses its own Mapper and the existing protocol ports.
[ ] Runtime SQL remains tenant-isolated; cross-tenant due-task query retains explicit ignore annotation.
[ ] No real invite URL, participant list, credentials, or phone batch is logged.
[ ] No fake invite, group-JID fallback, production mock, or in-memory production data path was added.
[ ] Missing invite still fails GroupFolderMapper usability filtering.
[ ] RUNNING task leases are preserved by both runtime enqueue and Flyway migration.
[ ] No method exceeds 100 lines and no constructor gains a tenth parameter.
```

- [ ] **Step 6: Finalize the structured change record**

Create `.harness/changes/group-folder-invite-auto-backfill/summary.md` with these sections and fill them using only actual evidence from Steps 1-5:

```markdown
# 分组自建群邀请链接自动回补

## 变更概述

## 影响模块

## 数据库变更

## API / Redis / Kafka 变更

## 关键约束

## 验证结果

## 部署与验收

## 回滚方案

## 遗留风险
```

Move the completed checklist and evidence from `.harness/changes/2026-08-05-group-folder-invite-auto-backfill.md` into the structured summary, then delete the old single-file record so there is one authoritative change record.

- [ ] **Step 7: Commit verification documentation**

```bash
git add .harness/changes/group-folder-invite-auto-backfill/summary.md \
  .harness/changes/2026-08-05-group-folder-invite-auto-backfill.md
git commit -m "docs: record group invite backfill verification"
```

- [ ] **Step 8: Report completion without deploying**

Report the focused and broad test outputs, migration version, commit list, and remaining operational condition: a group becomes usable only if an actually online Armada administrator can return a real invite code. State explicitly that no deployment or remote data mutation was performed.
