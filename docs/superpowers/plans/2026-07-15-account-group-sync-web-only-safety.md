# Armada Android Group Event Compatibility and Web-Only Scheduler Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lock Android Zhuan group snapshots onto Armada's existing `account.groups_reported` baseline/membership path, while preventing the legacy scheduled `account.groups_sync.requested` job from selecting Android accounts and silently sending them to the Web master topic.

**Architecture:** Keep Android group refresh event-driven inside Zhuan and reuse the protocol-neutral `account.groups_reported` consumer plus baseline/membership service. Add characterization tests for Android sources and successful empty snapshots; do not branch production ingestion code by protocol. Separately narrow the existing cross-tenant group-sync candidate query to legacy/null or explicit `WEB` accounts, pass the backend discriminator as a typed service parameter, and lock the behavior with a real-MySQL mapper test. No Android group-sync Kafka command, topic, data model, or HTTP branch is added.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis XML, MySQL DbTest, JUnit 5, Mockito, AssertJ

---

## Execution prerequisites

Before editing, read `AGENTS.md`, `.harness/rules/编码规范.md`, `.harness/rules/工程结构.md`, `.harness/rules/数据模型规范.md`, `.harness/wiki/数据模型.md`, `.agents/skills/unit-test-write/SKILL.md`, and `.agents/skills/unit-test-ci/SKILL.md`. Run Maven and DbTest commands from `armada/armada-api`; run scoped diff, staging, and commit commands from the `armada/` repository root. Preserve the unrelated `.claude/worktrees/*` entries already present in the repository status.

## File boundaries

- Modify `armada-api/src/test/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumerTest.java`: lock Android source and empty-array ingestion on the shared consumer.
- Modify `armada-api/src/test/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImplTest.java`: lock Android initial/change sources on shared baseline and membership handling.
- Modify `armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java`: add the explicit Web backend query parameter.
- Modify `armada-api/src/main/resources/mapper/account/AccountMapper.xml`: exclude explicit Android rows in SQL.
- Modify `armada-api/src/main/java/com/armada/account/service/AccountGroupSyncCommandService.java`: pass `ProtocolBackend.WEB.name()`.
- Modify `armada-api/src/test/java/com/armada/account/service/AccountGroupSyncCommandServiceTest.java`: lock the service-to-mapper contract.
- Modify `armada-api/src/test/java/com/armada/account/mapper/AccountOnlineMapperDbTest.java`: prove real MySQL returns Web/legacy accounts and excludes Android.
- Modify `.harness/changes/account-group-sync/summary.md`: document the protocol safety constraint and rollback.

### Task 1: Lock Android onto the shared report/baseline path

**Files:**
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumerTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImplTest.java`

- [ ] **Step 1: Add an Android empty-snapshot consumer characterization test**

Add this test next to the existing `onMessage_groupsReportedEnvelope_dispatchesParsedGroupsEvent` test:

```java
@Test
void onMessage_androidGroupsReportedEnvelope_dispatchesEmptySnapshotOnSharedConsumer() {
    String raw = """
            {
              "eventId": "evt-android-groups-1",
              "event": "account.groups_reported",
              "version": "v1",
              "accountId": "acc_861800000001",
              "occurredAt": "2026-07-15T06:00:01Z",
              "workerId": "android-worker-a",
              "data": {
                "tenantId": 1,
                "accountId": 100,
                "protocolAccountId": "acc_861800000001",
                "source": "android_online_group_sync",
                "groups": []
              }
            }
            """;

    consumer.onMessage(raw);

    ArgumentCaptor<ProtocolAccountGroupsReportedEvent> captor =
            ArgumentCaptor.forClass(ProtocolAccountGroupsReportedEvent.class);
    verify(groupsReportedSink).handleGroupsReported(captor.capture());
    assertThat(captor.getValue().source()).isEqualTo("android_online_group_sync");
    assertThat(captor.getValue().groups()).isEmpty();
    verifyNoInteractions(sink);
}
```

This is intentionally a GREEN characterization test: the unified consumer must not maintain a Web/Android source allowlist.

- [ ] **Step 2: Make baseline and change-source tests explicitly Android**

In `applyGroupsReported_capturesPendingBaselineAndClearsVisibleMembership`, use the full constructor and assert that the initial source reaches the snapshot replacement:

```java
service.applyGroupsReported(new AccountGroupsReportedEvent(
        1L,
        10L,
        "acc_10",
        1782626401000L,
        List.of(
                new AccountGroupsReportedEvent.Group(
                        "120363old@g.us", "导入时旧群", 10, null, null, false, false, null),
                new AccountGroupsReportedEvent.Group(
                        "120363old@g.us", "重复旧群", 10, null, null, false, false, null),
                new AccountGroupsReportedEvent.Group(
                        " ", "空 JID", 0, null, null, false, false, null)),
        "evt-pending-baseline",
        "android_online_group_sync"));

verify(snapshotService).replaceVisibleGroups(
        eq(10L),
        argThat(visible -> visible != null && visible.isEmpty()),
        eq(1782626401000L),
        eq("evt-pending-baseline"),
        eq("android_online_group_sync"));
```

In `applyGroupsReported_propagatesCorrelationWhenReplacingVisibleGroups`, replace `wa_groups_dirty` with `android_group_created` in both the event and verification. This proves the same service captures the first Android report as baseline and applies later Android reports as current membership without a production branch.

- [ ] **Step 3: Run the shared-ingestion tests**

Run:

```bash
cd armada-api
mvn -Dtest=ProtocolAccountEventConsumerTest,AccountGroupMembershipReportServiceImplTest test
```

Expected: BUILD SUCCESS. If this fails, treat it as a shared event-contract defect and fix it with the smallest production change before proceeding; do not add an Android-specific consumer.

- [ ] **Step 4: Commit the characterization tests**

From the `armada/` repository root:

```bash
git add \
  armada-api/src/test/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumerTest.java \
  armada-api/src/test/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImplTest.java
git commit -m "test: lock Android group reports onto shared ingestion"
```

### Task 2: Drive the Web-only candidate contract with tests

**Files:**
- Modify: `armada-api/src/test/java/com/armada/account/service/AccountGroupSyncCommandServiceTest.java`
- Modify: `armada-api/src/test/java/com/armada/account/mapper/AccountOnlineMapperDbTest.java`

- [ ] **Step 1: Change the service test to require an explicit Web discriminator**

Update the mock setup in `enqueueDueSyncCommands_groupsCandidatesByTenantAndRestoresTenantContextForOutboxInsert`:

```java
when(accountMapper.selectGroupSyncCandidates(
        50,
        AccountLoginStateCode.ONLINE,
        AccountStateCode.NORMAL,
        AccountGroupBaselineStateCode.CAPTURED,
        ProtocolBackend.WEB.name()))
        .thenReturn(candidates);
```

Add imports for `AccountLoginStateCode`, `AccountStateCode`, `AccountGroupBaselineStateCode`, and `ProtocolBackend`, then add this verification after `enqueueDueSyncCommands(50)`:

```java
verify(accountMapper).selectGroupSyncCandidates(
        50,
        AccountLoginStateCode.ONLINE,
        AccountStateCode.NORMAL,
        AccountGroupBaselineStateCode.CAPTURED,
        ProtocolBackend.WEB.name());
```

- [ ] **Step 2: Add the real-database Android exclusion test**

Add this test to `AccountOnlineMapperDbTest`:

```java
@Test
void selectGroupSyncCandidates_includesWebAndLegacyButExcludesAndroidAccounts() {
    long now = System.currentTimeMillis();
    Account explicitWeb = insertAccount("86170" + (now % 10000000L), now);
    Account legacyWeb = insertAccount("86171" + (now % 10000000L), now + 1);
    Account android = insertAccount("86172" + (now % 10000000L), now + 2);

    jdbc.update("UPDATE account SET protocol_id = ? WHERE id = ?",
            ProtocolBackend.WEB.name(), explicitWeb.getId());
    jdbc.update("UPDATE account SET protocol_id = NULL WHERE id = ?", legacyWeb.getId());
    jdbc.update("UPDATE account SET protocol_id = ? WHERE id = ?",
            ProtocolBackend.ANDROID.name(), android.getId());

    for (Account account : List.of(explicitWeb, legacyWeb, android)) {
        insertDefaultState(account.getId(), now);
        markNormalOnline(account.getId());
        markBaselineCaptured(account.getId(), now);
    }

    List<AccountGroupSyncCandidate> candidates = accountMapper.selectGroupSyncCandidates(
            50,
            AccountLoginStateCode.ONLINE,
            AccountStateCode.NORMAL,
            AccountGroupBaselineStateCode.CAPTURED,
            ProtocolBackend.WEB.name());

    assertThat(candidates).extracting(AccountGroupSyncCandidate::accountId)
            .contains(explicitWeb.getId(), legacyWeb.getId())
            .doesNotContain(android.getId());
}
```

Add:

```java
import com.armada.platform.protocol.model.enums.ProtocolBackend;
```

- [ ] **Step 3: Update all existing mapper test calls to the five-argument contract**

In `AccountOnlineMapperDbTest`, append `ProtocolBackend.WEB.name()` to every existing `selectGroupSyncCandidates` call:

```java
List<AccountGroupSyncCandidate> candidates = accountMapper.selectGroupSyncCandidates(
        50,
        AccountLoginStateCode.ONLINE,
        AccountStateCode.NORMAL,
        AccountGroupBaselineStateCode.CAPTURED,
        ProtocolBackend.WEB.name());
```

The watermark ordering test keeps limit `2` and appends the same fifth argument.

- [ ] **Step 4: Run the focused unit compilation and confirm RED**

Run:

```bash
cd armada-api
mvn -Dtest=AccountGroupSyncCommandServiceTest test
```

Expected: compilation FAIL because `AccountMapper.selectGroupSyncCandidates` still accepts four arguments.

### Task 3: Implement the Web-only SQL boundary

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/account/service/AccountGroupSyncCommandService.java`

- [ ] **Step 1: Add the mapper parameter**

Replace the mapper signature with:

```java
@InterceptorIgnore(tenantLine = "true")
List<AccountGroupSyncCandidate> selectGroupSyncCandidates(
        @Param("limit") int limit,
        @Param("onlineLoginState") int onlineLoginState,
        @Param("normalAccountState") int normalAccountState,
        @Param("baselineCapturedState") int baselineCapturedState,
        @Param("webProtocolId") String webProtocolId);
```

Extend the Javadoc with:

```java
 * @param webProtocolId          Web 协议标识;Android 账号不得进入硬编码 Web topic 的旧同步任务
```

- [ ] **Step 2: Add the SQL filter without changing tenant or state predicates**

In `AccountMapper.xml`, insert the following predicate immediately after the nonblank `protocol_account_id` predicates:

```xml
      AND (
        a.protocol_id IS NULL
        OR TRIM(a.protocol_id) = ''
        OR UPPER(TRIM(a.protocol_id)) = #{webProtocolId}
      )
```

Update the query comment to state that null/blank legacy rows remain Web-compatible, explicit `WEB` rows are included, and explicit `ANDROID` rows are excluded.

- [ ] **Step 3: Pass the enum value from the service**

Add:

```java
import com.armada.platform.protocol.model.enums.ProtocolBackend;
```

Then replace the candidate call with:

```java
List<AccountGroupSyncCandidate> candidates = accountMapper.selectGroupSyncCandidates(
        batchSize,
        AccountLoginStateCode.ONLINE,
        AccountStateCode.NORMAL,
        AccountGroupBaselineStateCode.CAPTURED,
        ProtocolBackend.WEB.name());
```

Do not add `protocolBackend` to `ProtocolAccountGroupSyncCommandRequest`; this task deliberately preserves the command as Web-only.

- [ ] **Step 4: Run the service test and confirm GREEN**

Run:

```bash
cd armada-api
mvn -Dtest=AccountGroupSyncCommandServiceTest test
```

Expected: BUILD SUCCESS and `AccountGroupSyncCommandServiceTest` passes.

### Task 4: Verify the real SQL and outbox invariant

**Files:**
- Modify: `.harness/changes/account-group-sync/summary.md`
- Verify: `armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`

- [ ] **Step 1: Run the focused real-MySQL DbTest**

Run:

```bash
cd armada-api
./dbtest.sh 'AccountOnlineMapperDbTest#selectGroupSyncCandidates_includesWebAndLegacyButExcludesAndroidAccounts'
```

Expected: PASS. If the local `.env` or confirmed DbTest database is unavailable, stop and report that the required real-MySQL gate could not run; do not replace it with H2 or a mock.

- [ ] **Step 2: Run all account group-sync mapper cases**

Run:

```bash
cd armada-api
./dbtest.sh 'AccountOnlineMapperDbTest#selectGroupSyncCandidates_requiresOnlineNormalAccountWithCapturedBaseline'
./dbtest.sh 'AccountOnlineMapperDbTest#selectGroupSyncCandidates_excludesPendingBaselineAccountsWithoutBaselineRow'
./dbtest.sh 'AccountOnlineMapperDbTest#selectGroupSyncCandidates_ordersByOldestGroupSyncRequestWatermark'
```

Expected: all three PASS; existing online/state/baseline/watermark behavior is unchanged.

- [ ] **Step 3: Verify the outbox remains explicitly Web-routed**

Run:

```bash
cd armada-api
mvn -Dtest=ProtocolCommandOutboxServiceImplTest#enqueueAccountGroupSyncCommands_singleCommand_insertsMasterRoutedAccountCommand test
```

Expected: BUILD SUCCESS; the outbox row still has `protocolBackend=WEB` and the Web master topic.

- [ ] **Step 4: Update the change record**

Add these bullets to `.harness/changes/account-group-sync/summary.md`:

```markdown
- Android Zhuan 的 `android_online_group_sync`、`android_group_created`、`android_group_participant_self`、`android_groups_dirty` 统一复用 `account.groups_reported` consumer 与 baseline/membership 服务。
- 周期账号群同步候选只包含 legacy/null 或显式 WEB 账号；ANDROID 账号由 Zhuan 事件驱动群快照刷新。
- `account.groups_sync.requested` 保持 Web-only，不新增 Android 命令或 topic。
- 回滚该过滤会重新允许 Android 账号进入 Web master topic，因此只允许与整个 Android 事件链一起回滚。
```

- [ ] **Step 5: Run static diff checks and commit**

From the `armada/` repository root:

Run:

```bash
git diff --check
git status --short
git diff -- armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java \
  armada-api/src/main/resources/mapper/account/AccountMapper.xml \
  armada-api/src/main/java/com/armada/account/service/AccountGroupSyncCommandService.java \
  armada-api/src/test/java/com/armada/account/service/AccountGroupSyncCommandServiceTest.java \
  armada-api/src/test/java/com/armada/account/mapper/AccountOnlineMapperDbTest.java \
  .harness/changes/account-group-sync/summary.md
```

Expected: no whitespace errors; only the six planned files appear in the scoped diff.

Commit:

```bash
git add armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java \
  armada-api/src/main/resources/mapper/account/AccountMapper.xml \
  armada-api/src/main/java/com/armada/account/service/AccountGroupSyncCommandService.java \
  armada-api/src/test/java/com/armada/account/service/AccountGroupSyncCommandServiceTest.java \
  armada-api/src/test/java/com/armada/account/mapper/AccountOnlineMapperDbTest.java \
  .harness/changes/account-group-sync/summary.md
git commit -m "fix: keep scheduled group sync on Web accounts"
```

### Task 5: Final focused regression

**Files:**
- Verify only; modify only after adding a failing regression test for a discovered defect.

- [ ] **Step 1: Run the unit suite for scheduler and outbox orchestration**

Run:

```bash
cd armada-api
mvn -Dtest=ProtocolAccountEventConsumerTest,AccountGroupMembershipReportServiceImplTest,AccountGroupSyncCommandServiceTest,AccountGroupSyncJobTest,ProtocolCommandOutboxServiceImplTest test
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Confirm no Android group-sync command was introduced**

Run:

```bash
rg -n "account\.groups_sync\.requested|ProtocolAccountGroupSyncCommandRequest" \
  src/main/java/com/armada/platform src/main/java/com/armada/account
```

Expected: the command creation path remains `AccountGroupSyncCommandService -> ProtocolCommandOutboxService`, and the outbox implementation remains Web-only; no Android command topic branch exists for this command.

- [ ] **Step 3: Record verification evidence**

Append the exact unit-test and DbTest command outputs to the verification section of `.harness/changes/account-group-sync/summary.md`. Do not claim the DbTest passed unless its real output exited 0.
