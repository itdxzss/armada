# Manual Offline Intent Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make an explicit account offline action durably stop stale PROXY_FAILED reonline, while a later explicit online action re-enables normal retry.

**Architecture:** Store control-plane intent in nullable `account_state.desired_login_state`, independent from the protocol-reported `login_state`. Explicit online/offline commands update intent transactionally; immediate and scheduled PROXY_FAILED recovery require actual `OFFLINE/PROXY_FAILED` plus desired `ONLINE` or legacy `NULL`. Offline also cancels only unpublished account-online outbox rows.

**Tech Stack:** Java 17, Spring Boot 3.3, MyBatis XML, MySQL 8/Flyway, JUnit 5, Mockito.

---

### Task 1: Persist desired login state and gate recovery SQL

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V072__account_desired_login_state.sql`
- Modify: `armada-api/src/main/java/com/armada/account/model/entity/AccountState.java`
- Modify: `armada-api/src/main/java/com/armada/account/mapper/AccountStateMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountStateMapper.xml`
- Test: `armada-api/src/test/java/com/armada/account/service/AccountOnlineCommandServiceImplDbTest.java`
- Test: `armada-api/src/test/java/com/armada/account/recovery/ProxyFailedRecoveryDispatcherTest.java`

- [ ] **Step 1: Add failing tests for OFFLINE intent and legacy compatibility**

Add DbTest cases that set an account to `login_state=OFFLINE/state_source=PROXY_FAILED` and assert:

```java
assertEquals(0, stateMapper.claimProxyFailedReonline(accountId, now)); // desired OFFLINE
assertEquals(1, stateMapper.claimProxyFailedReonline(accountId, now)); // desired ONLINE
assertEquals(1, stateMapper.claimProxyFailedReonline(accountId, now)); // desired NULL legacy
```

Add dispatcher unit data for desired OFFLINE and assert it is not returned by the mapper-driven candidate scan contract.

- [ ] **Step 2: Run RED tests**

Run:

```bash
cd armada-api
mvn -Dtest=ProxyFailedRecoveryDispatcherTest test
./dbtest.sh 'AccountOnlineCommandServiceImplDbTest'
```

Expected: unit/DbTest compilation or assertions fail because desired state and SQL conditions do not exist.

- [ ] **Step 3: Add the migration and mapper contract**

Migration uses an `information_schema.columns` guard and adds:

```sql
ALTER TABLE account_state
  ADD COLUMN desired_login_state TINYINT DEFAULT NULL
  COMMENT '期望登录状态:1在线 2离线;NULL=历史未建立显式意图'
  AFTER login_state;
```

Add `desiredLoginState` getter/setter to `AccountState`. Add mapper operations:

```java
int updateDesiredLoginStateInternal(List<Long> accountIds, int desiredLoginState, long updatedAt);
```

Extend `claimProxyFailedReonlineInternal` and `selectProxyFailedRecoveryCandidates` with
`desiredOfflineState`; SQL must allow `desired_login_state IS NULL OR desired_login_state != OFFLINE`.

- [ ] **Step 4: Run GREEN tests and XML validation**

Run the same focused tests plus:

```bash
xmllint --noout armada-api/src/main/resources/mapper/account/AccountStateMapper.xml
```

Expected: PASS and XML exits 0.

### Task 2: Cancel unpublished stale online commands

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/service/ProtocolCommandOutboxService.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapper.java`
- Modify: `armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml`
- Test: `armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`
- Test: `armada-api/src/test/java/com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapperDbTest.java`

- [ ] **Step 1: Add failing service and DbTest cases**

Assert that cancellation:

```java
int canceled = service.cancelPendingAccountOnlineCommands(List.of(accountId));
assertEquals(1, canceled);
```

Only changes `account.online.requested` rows from PENDING to CANCELED, leaves LOCKED/SENT and
`account.offline.requested` unchanged, and stays within the current tenant.

- [ ] **Step 2: Run RED tests**

```bash
cd armada-api
mvn -Dtest=ProtocolCommandOutboxServiceImplTest test
./dbtest.sh 'ProtocolCommandOutboxMapperDbTest'
```

Expected: FAIL because the cancellation API and SQL are absent.

- [ ] **Step 3: Implement minimal cancellation API**

The platform service delegates to a tenant-filtered mapper update with constants already defined by
`ProtocolCommandOutboxStatus` and `COMMAND_TYPE_ACCOUNT_ONLINE_REQUESTED`:

```sql
UPDATE protocol_command_outbox
SET status = #{canceledStatus},
    last_error = 'MANUAL_OFFLINE',
    updated_at = #{now}
WHERE aggregate_type = 'ACCOUNT'
  AND aggregate_id IN (...)
  AND command_type = #{onlineCommandType}
  AND status = #{pendingStatus}
  AND deleted_at IS NULL;
```

- [ ] **Step 4: Run GREEN tests and XML validation**

Run the same tests and `xmllint --noout` on `ProtocolCommandOutboxMapper.xml`; expect PASS.

### Task 3: Make explicit lifecycle commands own the intent

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java`

- [ ] **Step 1: Add failing behavior tests**

Use Mockito `InOrder` to require:

```java
stateMapper.updateDesiredLoginState(ids, AccountLoginStateCode.OFFLINE, anyLong());
outboxService.cancelPendingAccountOnlineCommands(ids);
outboxService.enqueueOfflineCommands(anyList());
```

For explicit online, require desired ONLINE before state claim/allocation. For
`reonlineAfterProxyFailure`, verify it never updates desired state. Add a test that explicit online after
OFFLINE intent proceeds and writes a new online outbox command.

- [ ] **Step 2: Run RED test**

```bash
cd armada-api
mvn -Dtest=AccountOnlineCommandServiceImplTest test
```

Expected: FAIL because lifecycle methods do not update intent or cancel old online rows.

- [ ] **Step 3: Implement transactional intent ownership**

Add default-isolation `@Transactional(rollbackFor = Exception.class)` to both public offline entry points.
Inside the shared private offline path, update desired OFFLINE, cancel pending online, then enqueue offline.
Explicit single/batch online and explicit takeover update desired ONLINE in their existing transactions.
Automatic proxy recovery, IP-delete relogin, protocol events and scheduler never write desired state.

- [ ] **Step 4: Run GREEN test**

Run `AccountOnlineCommandServiceImplTest`; expected PASS.

### Task 4: Regression, documentation, deployment and perf2 cleanup

**Files:**
- Modify: `.harness/wiki/数据模型.md` by running its generator
- Modify: `.harness/changes/2026-07-25-manual-offline-intent-gate.md`

- [ ] **Step 1: Run focused and regression verification**

```bash
cd armada-api
mvn -Dtest=AccountOnlineCommandServiceImplTest,ProxyFailedRecoveryDispatcherTest,ProtocolCommandOutboxServiceImplTest test
mvn -DskipTests package
xmllint --noout src/main/resources/mapper/account/AccountStateMapper.xml
xmllint --noout src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml
git diff --check
```

Expected: all commands exit 0. Run the affected DbTests against the confirmed test database and record exact output.

- [ ] **Step 2: Review scope and concurrency semantics**

Confirm the diff has no `READ_COMMITTED`, no protocol event writes to desired state, no cancellation of
LOCKED/SENT rows, and both immediate/scheduled PROXY_FAILED paths use the same C-transaction gate.

- [ ] **Step 3: Deploy to perf2 without committing**

Use the existing perf2 deployment path, verify Flyway V062 succeeded, backend restart count remains zero,
API health is 200, and logs show `PROXY_FAILED_REONLINE_SKIPPED` after an explicit offline test.

- [ ] **Step 4: Handle existing traffic safely**

Do not reset the lifecycle command topic while lag is effectively zero. After deployment, issue one fresh
batch offline so all selected rows receive desired OFFLINE and a newer keyed offline command. Recheck the
lifecycle command lag and account-state event lag; skip offsets only if a material old state backlog remains
and the user confirms losing mixed ONLINE/OFFLINE events is acceptable.

No commit step is included because the user explicitly requires local uncommitted changes.
