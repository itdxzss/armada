# Account List Stats Login State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Update Armada account-list statistics so pending online is `login_state=3`, offline counts only normal offline accounts, and the former banned card becomes an abnormal-account summary.

**Architecture:** The backend keeps `account_state.login_state` as the single source for online/offline/pending-online display and statistics. Account online command enqueue marks accounts `login_state=3` only after outbox insertion succeeds, and protocol Kafka state events later overwrite that running state with `ONLINE` or `OFFLINE`. The frontend stops deriving pending online locally and displays the backend stats contract directly.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis XML, Flyway, MySQL DbTest, JUnit 5, AssertJ, Vue 3, TypeScript, Element Plus, node:test with tsx.

---

## Scope

This plan spans two sibling repositories because the backend API contract and the account-list UI must change together:

- Backend: `/Users/daishuaishuai/IdeaProjects/armada`
- Frontend: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`

Both worktrees currently contain unrelated local changes. Implementation must stage and commit only files listed in each task.

## File Structure

Backend files:

- `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/entity/AccountLoginStateCode.java`: add `PENDING_ONLINE=3`.
- `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/mapper/AccountStateMapper.java`: expose a mapper method that marks accounts as pending online.
- `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountStateMapper.xml`: implement the pending-online update SQL.
- `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java`: call the pending-online mapper after outbox enqueue succeeds.
- `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/vo/AccountStatsVO.java`: add stats response fields.
- `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/vo/AccountStatsVoRow.java`: add mapper projection fields.
- `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountServiceImpl.java`: calculate `restrictedTotal` and build the new stats VO.
- `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountMapper.xml`: update stats aggregation SQL.
- `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/db/migration/V032__account_login_state_pending_comment.sql`: update the `login_state` column comment only.
- `/Users/daishuaishuai/IdeaProjects/armada/docs/business/account-data-model.md`: document `login_state=3`.

Backend tests:

- `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java`
- `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/AccountOnlineCommandServiceImplDbTest.java`
- `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/mapper/AccountStatsMapperDbTest.java`
- `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/AccountStateEventServiceImplDbTest.java`
- `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/controller/AccountControllerDbTest.java`

Frontend files:

- `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/account.ts`: update `LoginState` and stats response type.
- `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/account-display.ts`: render pending login state and abnormal stats card.
- `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/account-display.test.ts`: update display helper tests.
- `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/composables/useAccountListPage.ts`: add pending option and zero summary fields.
- `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/index.vue`: render abnormal-card breakdown.

---

## Task 1: Backend Pending Online Login State

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/AccountOnlineCommandServiceImplDbTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/AccountStateEventServiceImplDbTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/entity/AccountLoginStateCode.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/mapper/AccountStateMapper.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountStateMapper.xml`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java`

- [ ] **Step 1: Write failing unit tests for pending-online writes**

In `AccountOnlineCommandServiceImplTest`, add this static import if it is missing:

```java
import static org.mockito.ArgumentMatchers.anyLong;
```

In `AccountOnlineCommandServiceImplTest`, extend `online_validAccountCredentialAndAllocatedProxy_enqueuesOutboxCommandAndMapsAcceptedVo` with this assertion after the outbox verification:

```java
ArgumentCaptor<List<Long>> pendingIdsCaptor = ArgumentCaptor.forClass(List.class);
verify(stateMapper).markPendingOnline(pendingIdsCaptor.capture(), anyLong());
assertThat(pendingIdsCaptor.getValue()).containsExactly(100L);
```

Add this test to the same class:

```java
@Test
void online_enqueueThrows_doesNotMarkPendingOnline() {
    Account account = onlineAccount();
    AccountCredential credential = onlineCredential();
    ProxyEndpoint endpoint = onlineEndpoint();
    RuntimeException failure = new RuntimeException("outbox unavailable");
    when(accountMapper.selectActiveById(100L)).thenReturn(account);
    when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
    when(accountMapper.selectIpRegionsByAccountIds(List.of(100L), ImportResult.SUCCESS.getCode()))
            .thenReturn(List.of(ipRegionRow(100L, "印度")));
    when(ipProxyService.allocateOnlineEndpoint(new IpProxyAllocationRequest(100L, "印度")))
            .thenReturn(new IpProxyAllocation(7L, endpoint, "iproyal"));
    when(onlineAttemptIdGenerator.nextId()).thenReturn("oa_failed_before_pending");
    when(protocolCommandOutboxService.enqueueOnlineCommands(any())).thenThrow(failure);

    assertThatThrownBy(() -> service.online(100L)).isSameAs(failure);

    verify(stateMapper, never()).markPendingOnline(any(), anyLong());
    verify(ipProxyService).releaseOnlineAllocation(100L, 7L);
}
```

In the existing `onlineBatch_validAccountsCredentialsAndAllocatedProxies_enqueuesOneOutboxBatch` test, add this assertion after the outbox command assertions:

```java
ArgumentCaptor<List<Long>> pendingIdsCaptor = ArgumentCaptor.forClass(List.class);
verify(stateMapper).markPendingOnline(pendingIdsCaptor.capture(), anyLong());
assertThat(pendingIdsCaptor.getValue()).containsExactly(100L, 101L);
```

- [ ] **Step 2: Write failing DbTests for pending-online persistence and stale event protection**

In `AccountOnlineCommandServiceImplDbTest`, update `onlineBatch_validAccounts_snapshotsAllocatedProxyDisplayFields` with this assertion:

```java
assertThat(state.getLoginState()).isEqualTo(AccountLoginStateCode.PENDING_ONLINE);
assertThat(state.getStateSource()).isEqualTo("OUTBOX");
assertThat(state.getLastStateSyncTime()).isNotNull();
```

Add the import:

```java
import com.armada.account.model.entity.AccountLoginStateCode;
```

In `AccountStateEventServiceImplDbTest`, add this test:

```java
@Test
void applyStateChanged_staleOfflineAfterPendingOnline_doesNotClearPendingLoginState() {
    long now = System.currentTimeMillis();
    Account account = insertAccount("86190" + (now % 10_000_000L), now);
    insertDefaultState(account.getId(), now);
    long pendingAt = now + 5_000L;
    jdbcTemplate.update("""
            UPDATE account_state
            SET login_state = ?, last_state_sync_time = ?, state_source = ?, updated_at = ?
            WHERE account_id = ?
            """,
            AccountLoginStateCode.PENDING_ONLINE, pendingAt, "OUTBOX", pendingAt, account.getId());

    service.applyStateChanged(event(account, "ONLINE", "OFFLINE",
            now + 4_000L, "OFFLINE", null));

    AccountState state = stateMapper.selectByAccountId(account.getId());
    assertThat(state.getLoginState()).isEqualTo(AccountLoginStateCode.PENDING_ONLINE);
    assertThat(state.getStateSource()).isEqualTo("OUTBOX");
    assertThat(state.getLastStateSyncTime()).isEqualTo(pendingAt);
}
```

- [ ] **Step 3: Run focused tests and verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=AccountOnlineCommandServiceImplTest test
./dbtest.sh 'AccountOnlineCommandServiceImplDbTest#onlineBatch_validAccounts_snapshotsAllocatedProxyDisplayFields,AccountStateEventServiceImplDbTest#applyStateChanged_staleOfflineAfterPendingOnline_doesNotClearPendingLoginState'
```

Expected: compile failure because `PENDING_ONLINE` and `markPendingOnline` do not exist yet.

- [ ] **Step 4: Implement login-state constant and mapper method**

In `AccountLoginStateCode.java`, replace the class body with:

```java
public final class AccountLoginStateCode {

    private AccountLoginStateCode() {
    }

    /**
     * 在线:协议层确认账号已经连接 WhatsApp。
     */
    public static final int ONLINE = 1;

    /**
     * 离线:协议层上报非 ONLINE 状态,包括 OFFLINE、RECONNECTING、NEED_REAUTH 等。
     */
    public static final int OFFLINE = 2;

    /**
     * 待上线:Armada 已接受上线命令并写入 outbox,正在等待协议层 Kafka 回传最终状态。
     */
    public static final int PENDING_ONLINE = 3;
}
```

In `AccountStateMapper.java`, add the import:

```java
import java.util.List;
```

Add this default method and internal mapper method before `updateLoginState`:

```java
/**
 * 将账号登录态标记为待上线。
 *
 * <p>该状态由 Armada 在上线命令写入 outbox 后本地写入,用于 UI 展示“待上线”。
 * 同时更新 last_state_sync_time 作为乱序水位,避免点击上线前的旧协议事件覆盖待上线状态。</p>
 *
 * @param accountIds 账号主键列表
 * @param updatedAt  更新时间和本地乱序水位(epoch 毫秒)
 * @return 实际更新行数
 */
default int markPendingOnline(List<Long> accountIds, long updatedAt) {
    if (accountIds == null || accountIds.isEmpty()) {
        return 0;
    }
    return markPendingOnlineInternal(accountIds, AccountLoginStateCode.PENDING_ONLINE, "OUTBOX", updatedAt);
}

/**
 * 将账号登录态标记为待上线的 SQL 实现。
 */
int markPendingOnlineInternal(@Param("accountIds") List<Long> accountIds,
                              @Param("pendingLoginState") int pendingLoginState,
                              @Param("stateSource") String stateSource,
                              @Param("updatedAt") long updatedAt);
```

In `AccountStateMapper.xml`, add this update after `selectByAccountId`:

```xml
  <!-- Armada 上线命令成功进入 outbox 后,本地标记等待协议层最终回传。 -->
  <update id="markPendingOnlineInternal">
    UPDATE account_state
    SET login_state = #{pendingLoginState},
        last_state_sync_time = #{updatedAt},
        state_source = #{stateSource},
        updated_at = #{updatedAt}
    WHERE account_id IN
    <foreach collection="accountIds" item="accountId" open="(" separator="," close=")">#{accountId}</foreach>
  </update>
```

- [ ] **Step 5: Mark accounts pending after successful online outbox enqueue**

In `AccountOnlineCommandServiceImpl.java`, after the single-account `enqueueOnlineCommands` call and before the accepted log, add:

```java
markPendingOnline(List.of(account.getId()));
```

In `enqueueOnlineBatch`, after `enqueueOnlineCommands(...)` returns and before the accepted log, add:

```java
markPendingOnline(prepared.stream().map(PreparedOnlineCommand::accountId).toList());
```

Add this private helper near `selectOnlineAccountIds`:

```java
private void markPendingOnline(List<Long> accountIds) {
    long now = System.currentTimeMillis();
    int updated = stateMapper.markPendingOnline(accountIds, now);
    if (updated != accountIds.size()) {
        log.warn("账号上线待回传状态更新数量不一致 expected={} updated={} accountIds={}",
                accountIds.size(), updated, accountIds);
    }
}
```

- [ ] **Step 6: Run focused tests and verify GREEN**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=AccountOnlineCommandServiceImplTest test
./dbtest.sh 'AccountOnlineCommandServiceImplDbTest#onlineBatch_validAccounts_snapshotsAllocatedProxyDisplayFields,AccountStateEventServiceImplDbTest#applyStateChanged_staleOfflineAfterPendingOnline_doesNotClearPendingLoginState'
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit Task 1**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/account/model/entity/AccountLoginStateCode.java \
        armada-api/src/main/java/com/armada/account/mapper/AccountStateMapper.java \
        armada-api/src/main/resources/mapper/account/AccountStateMapper.xml \
        armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java \
        armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java \
        armada-api/src/test/java/com/armada/account/service/AccountOnlineCommandServiceImplDbTest.java \
        armada-api/src/test/java/com/armada/account/service/AccountStateEventServiceImplDbTest.java
git commit -m "feat(account): mark accepted online commands pending"
```

---

## Task 2: Backend Stats Aggregation Contract

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/mapper/AccountStatsMapperDbTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/controller/AccountControllerDbTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/vo/AccountStatsVO.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/vo/AccountStatsVoRow.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountServiceImpl.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountMapper.xml`

- [ ] **Step 1: Write failing stats DbTest**

In `AccountStatsMapperDbTest`, add imports:

```java
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
```

Add this test:

```java
@Test
void statsSummary_pendingOnlineNormalOfflineAndRestrictedBreakdown() {
    long now = System.currentTimeMillis();
    AccountStatsVoRow before = accountMapper.statsSummary();

    Account normalOffline = insertAccount("86201" + (now % 100000000L), now);
    Account bannedOffline = insertAccount("86202" + (now % 100000000L), now);
    Account pending = insertAccount("86203" + (now % 100000000L), now);
    Account unbound = insertAccount("86204" + (now % 100000000L), now);
    Account muted = insertAccount("86205" + (now % 100000000L), now);
    Account exported = insertAccount("86206" + (now % 100000000L), now);

    insertDefaultState(normalOffline.getId(), now);
    insertDefaultState(bannedOffline.getId(), now);
    insertDefaultState(pending.getId(), now);
    insertDefaultState(unbound.getId(), now);
    insertDefaultState(muted.getId(), now);
    insertDefaultState(exported.getId(), now);

    jdbc.update("UPDATE account_state SET account_state = ?, login_state = ? WHERE account_id = ?",
            AccountStateCode.NORMAL, AccountLoginStateCode.OFFLINE, normalOffline.getId());
    jdbc.update("UPDATE account_state SET account_state = ?, login_state = ? WHERE account_id = ?",
            AccountStateCode.BANNED, AccountLoginStateCode.OFFLINE, bannedOffline.getId());
    jdbc.update("UPDATE account_state SET login_state = ? WHERE account_id = ?",
            AccountLoginStateCode.PENDING_ONLINE, pending.getId());
    jdbc.update("UPDATE account_state SET account_state = ? WHERE account_id = ?",
            AccountStateCode.UNBOUND, unbound.getId());
    jdbc.update("UPDATE account_state SET account_state = ?, mute_status = ? WHERE account_id = ?",
            AccountStateCode.NORMAL, 1, muted.getId());
    jdbc.update("UPDATE account_state SET account_state = ? WHERE account_id = ?",
            AccountStateCode.EXPORTED, exported.getId());

    AccountStatsVoRow after = accountMapper.statsSummary();

    assertThat(after.getOffline() - before.getOffline()).isEqualTo(1L);
    assertThat(after.getPendingOnline() - before.getPendingOnline()).isEqualTo(1L);
    assertThat(after.getBanned() - before.getBanned()).isEqualTo(1L);
    assertThat(after.getUnbound() - before.getUnbound()).isEqualTo(1L);
    assertThat(after.getMuted() - before.getMuted()).isEqualTo(1L);
    assertThat(after.getExported() - before.getExported()).isEqualTo(1L);
}
```

- [ ] **Step 2: Write failing API DbTest for new stats fields**

In `AccountControllerDbTest`, add this test under the stats section:

```java
@Test
void get_stats_returnsPendingOnlineAndRestrictedBreakdown() throws Exception {
    long ts = System.currentTimeMillis();
    Long pendingId = importOneAccount("86136" + (ts % 10000000L));
    Long mutedId = importOneAccount("86137" + (ts % 10000000L));
    jdbc.update("UPDATE account_state SET login_state = ? WHERE account_id = ?",
            3, pendingId);
    jdbc.update("UPDATE account_state SET account_state = ?, mute_status = ? WHERE account_id = ?",
            2, 1, mutedId);

    MvcResult result = mockMvc.perform(get("/api/accounts/stats")
                    .header(TENANT_HEADER, TENANT_CODE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.restrictedTotal").isNumber())
            .andExpect(jsonPath("$.data.exported").isNumber())
            .andExpect(jsonPath("$.data.unbound").isNumber())
            .andReturn();

    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    var data = objectMapper.readTree(body).path("data");
    assertThat(data.path("pendingOnline").longValue()).isGreaterThanOrEqualTo(1L);
    assertThat(data.path("muted").longValue()).isGreaterThanOrEqualTo(1L);
    assertThat(data.path("restrictedTotal").longValue()).isGreaterThanOrEqualTo(data.path("muted").longValue());
}
```

- [ ] **Step 3: Run focused tests and verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
./dbtest.sh 'AccountStatsMapperDbTest#statsSummary_pendingOnlineNormalOfflineAndRestrictedBreakdown,AccountControllerDbTest#get_stats_returnsPendingOnlineAndRestrictedBreakdown'
```

Expected: compile failure because new stats fields do not exist yet.

- [ ] **Step 4: Update stats VO and mapper row**

Replace `AccountStatsVO.java` with:

```java
package com.armada.account.model.vo;

/**
 * 账号统计卡出参 VO(前端统计卡区域用此结构)。
 *
 * <p>unassigned = total - assigned,restrictedTotal = banned + unbound + muted + exported,
 * 均由 Service 层派生。</p>
 *
 * @param total           本租户未软删账号总数
 * @param online          在线账号数(login_state=1)
 * @param offline         正常离线账号数(account_state=2 AND login_state=2)
 * @param pendingOnline   待上线账号数(login_state=3)
 * @param restrictedTotal 异常账号总计(banned + unbound + muted + exported)
 * @param banned          封禁账号数(account_state=3)
 * @param unbound         解绑账号数(account_state=5)
 * @param muted           禁言账号数(mute_status IS NOT NULL)
 * @param exported        导出账号数(account_state=4)
 * @param risk            风控中/待解除账号数(risk_status&gt;1)
 * @param assigned        已派单账号数(dispatched_at IS NOT NULL)
 * @param unassigned      未派单账号数(total - assigned)
 */
public record AccountStatsVO(
        long total,
        long online,
        long offline,
        long pendingOnline,
        long restrictedTotal,
        long banned,
        long unbound,
        long muted,
        long exported,
        long risk,
        long assigned,
        long unassigned
) {
}
```

In `AccountStatsVoRow.java`, add fields:

```java
/** login_state=3 待上线账号数。 */
private long pendingOnline;

/** account_state=5 解绑账号数。 */
private long unbound;

/** mute_status IS NOT NULL 禁言账号数。 */
private long muted;

/** account_state=4 导出账号数。 */
private long exported;
```

Add getters and setters:

```java
public long getPendingOnline() {
    return pendingOnline;
}

public void setPendingOnline(long pendingOnline) {
    this.pendingOnline = pendingOnline;
}

public long getUnbound() {
    return unbound;
}

public void setUnbound(long unbound) {
    this.unbound = unbound;
}

public long getMuted() {
    return muted;
}

public void setMuted(long muted) {
    this.muted = muted;
}

public long getExported() {
    return exported;
}

public void setExported(long exported) {
    this.exported = exported;
}
```

- [ ] **Step 5: Update service and SQL aggregation**

In `AccountServiceImpl#getStats`, replace the constructor block with:

```java
long unassigned = row.getTotal() - row.getAssigned();
long restrictedTotal = row.getBanned() + row.getUnbound() + row.getMuted() + row.getExported();
return new AccountStatsVO(
        row.getTotal(),
        row.getOnline(),
        row.getOffline(),
        row.getPendingOnline(),
        restrictedTotal,
        row.getBanned(),
        row.getUnbound(),
        row.getMuted(),
        row.getExported(),
        row.getRisk(),
        row.getAssigned(),
        unassigned
);
```

In `AccountMapper.xml`, replace the stats select list with:

```xml
      COUNT(*)                                                            AS total,
      COALESCE(SUM(CASE WHEN s.login_state = 1 THEN 1 ELSE 0 END), 0) AS online,
      COALESCE(SUM(CASE WHEN s.account_state = 2 AND s.login_state = 2 THEN 1 ELSE 0 END), 0) AS offline,
      COALESCE(SUM(CASE WHEN s.login_state = 3 THEN 1 ELSE 0 END), 0) AS pendingOnline,
      COALESCE(SUM(CASE WHEN s.account_state = 3 THEN 1 ELSE 0 END), 0) AS banned,
      COALESCE(SUM(CASE WHEN s.account_state = 5 THEN 1 ELSE 0 END), 0) AS unbound,
      COALESCE(SUM(CASE WHEN s.mute_status IS NOT NULL THEN 1 ELSE 0 END), 0) AS muted,
      COALESCE(SUM(CASE WHEN s.account_state = 4 THEN 1 ELSE 0 END), 0) AS exported,
      COALESCE(SUM(CASE WHEN s.risk_status &gt; 1 THEN 1 ELSE 0 END), 0) AS risk,
      COALESCE(SUM(CASE WHEN a.dispatched_at IS NOT NULL THEN 1 ELSE 0 END), 0) AS assigned
```

- [ ] **Step 6: Run focused tests and verify GREEN**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
./dbtest.sh 'AccountStatsMapperDbTest#statsSummary_pendingOnlineNormalOfflineAndRestrictedBreakdown,AccountControllerDbTest#get_stats_returnsPendingOnlineAndRestrictedBreakdown'
```

Expected: both tests pass.

- [ ] **Step 7: Commit Task 2**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/account/model/vo/AccountStatsVO.java \
        armada-api/src/main/java/com/armada/account/model/vo/AccountStatsVoRow.java \
        armada-api/src/main/java/com/armada/account/service/impl/AccountServiceImpl.java \
        armada-api/src/main/resources/mapper/account/AccountMapper.xml \
        armada-api/src/test/java/com/armada/account/mapper/AccountStatsMapperDbTest.java \
        armada-api/src/test/java/com/armada/account/controller/AccountControllerDbTest.java
git commit -m "feat(account): update account stats summary"
```

---

## Task 3: Backend Migration Comment And Business Docs

**Files:**
- Create: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/db/migration/V032__account_login_state_pending_comment.sql`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/docs/business/account-data-model.md`

- [ ] **Step 1: Add Flyway migration for the column comment**

Create `V032__account_login_state_pending_comment.sql`:

```sql
ALTER TABLE account_state
    MODIFY COLUMN login_state TINYINT DEFAULT NULL
    COMMENT '1在线 2离线 3待上线;NULL=未上报/未发起上线';
```

- [ ] **Step 2: Update account data model documentation**

In `docs/business/account-data-model.md`, replace every `login_state` description that says:

```text
1在线 2离线;NULL=未上报
```

with:

```text
1在线 2离线 3待上线;NULL=未上报/未发起上线
```

Also update the field-mouth paragraph near the top so it states:

```markdown
- `login_state` 可空、无默认:`NULL` = 未上报/未发起上线;`3` = Armada 已写入上线 outbox、等待协议 Kafka 回传结果。统计卡里的待上线只看 `login_state=3`,不再用 `total-online-offline` 推导。
```

- [ ] **Step 3: Run migration-focused DbTest**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
./dbtest.sh AccountSchemaDbTest
```

Expected: Flyway validates and `AccountSchemaDbTest` passes.

- [ ] **Step 4: Commit Task 3**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/resources/db/migration/V032__account_login_state_pending_comment.sql \
        docs/business/account-data-model.md
git commit -m "docs(account): document pending online login state"
```

---

## Task 4: Frontend Stats And Login-State Display

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/account.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/account-display.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/account-display.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/composables/useAccountListPage.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/index.vue`

- [ ] **Step 1: Write failing display helper tests**

In `account-display.test.ts`, update the login-state test:

```ts
it("maps login states to labels and tag types", () => {
  assert.equal(loginStateLabel(1), "在线");
  assert.equal(loginStateLabel(2), "离线");
  assert.equal(loginStateLabel(3), "待上线");
  assert.equal(loginStateTagType(1), "success");
  assert.equal(loginStateTagType(2), "danger");
  assert.equal(loginStateTagType(3), "warning");
  assert.equal(loginStateTagType(null), "info");
});
```

Replace the stats-card test with:

```ts
it("uses backend pending-online and restricted account statistics", () => {
  const cards = buildAccountStatCards({
    total: 10,
    banned: 1,
    unbound: 2,
    muted: 3,
    exported: 4,
    restrictedTotal: 10,
    online: 3,
    offline: 2,
    pendingOnline: 1,
    risk: 1,
    assigned: 4,
    unassigned: 6
  });

  assert.deepEqual(
    cards.map(card => [card.key, card.label, card.value]),
    [
      ["total", "总账号数", 10],
      ["restricted", "异常账号", 10],
      ["online", "在线账号", 3],
      ["offline", "离线账号", 2],
      ["pendingOnline", "待上线账号", 1],
      ["risk", "风控账号", 1],
      ["assigned", "已分配账号", 4],
      ["unassigned", "未分配账号", 6]
    ]
  );
  assert.deepEqual(cards[1].subItems, [
    { label: "封禁", value: 1 },
    { label: "解绑", value: 2 },
    { label: "禁言", value: 3 },
    { label: "导出", value: 4 }
  ]);
});
```

- [ ] **Step 2: Run display tests and verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import tsx src/views/account/index/account-display.test.ts
```

Expected: compile or assertion failure because `LoginState=3`, `warning`, and `subItems` are not implemented.

- [ ] **Step 3: Update API types**

In `src/api/account.ts`, change:

```ts
export type LoginState = 1 | 2;
```

to:

```ts
export type LoginState = 1 | 2 | 3;
```

Replace `TenantAccountSummary` with:

```ts
export interface TenantAccountSummary {
  total: number;
  banned: number;
  unbound: number;
  muted: number;
  exported: number;
  restrictedTotal: number;
  online: number;
  offline: number;
  pendingOnline: number;
  risk: number;
  assigned: number;
  unassigned: number;
}
```

- [ ] **Step 4: Update display helpers**

In `account-display.ts`, change:

```ts
export type AccountTagType = "success" | "danger" | "info";
```

to:

```ts
export type AccountTagType = "success" | "danger" | "info" | "warning";
```

Replace `AccountStatCard` with:

```ts
export interface AccountStatCard {
  key: string;
  label: string;
  value: number;
  subItems?: Array<{ label: string; value: number }>;
}
```

Replace login helpers with:

```ts
export function loginStateLabel(value?: number | null): string {
  if (value === 1) return "在线";
  if (value === 2) return "离线";
  if (value === 3) return "待上线";
  return "—";
}

export function loginStateTagType(value?: number | null): AccountTagType {
  if (value === 1) return "success";
  if (value === 2) return "danger";
  if (value === 3) return "warning";
  return "info";
}
```

Replace `buildAccountStatCards` with:

```ts
export function buildAccountStatCards(
  summary: TenantAccountSummary
): AccountStatCard[] {
  return [
    { key: "total", label: "总账号数", value: summary.total },
    {
      key: "restricted",
      label: "异常账号",
      value: summary.restrictedTotal,
      subItems: [
        { label: "封禁", value: summary.banned },
        { label: "解绑", value: summary.unbound },
        { label: "禁言", value: summary.muted },
        { label: "导出", value: summary.exported }
      ]
    },
    { key: "online", label: "在线账号", value: summary.online },
    { key: "offline", label: "离线账号", value: summary.offline },
    { key: "pendingOnline", label: "待上线账号", value: summary.pendingOnline },
    { key: "risk", label: "风控账号", value: summary.risk },
    { key: "assigned", label: "已分配账号", value: summary.assigned },
    { key: "unassigned", label: "未分配账号", value: summary.unassigned }
  ];
}
```

- [ ] **Step 5: Update account list composable**

In `useAccountListPage.ts`, update `ZERO_SUMMARY`:

```ts
const ZERO_SUMMARY: TenantAccountSummary = {
  total: 0,
  banned: 0,
  unbound: 0,
  muted: 0,
  exported: 0,
  restrictedTotal: 0,
  online: 0,
  offline: 0,
  pendingOnline: 0,
  risk: 0,
  assigned: 0,
  unassigned: 0
};
```

Change the login state form type:

```ts
loginState: "" | "1" | "2" | "3";
```

Update `loginStateOptions`:

```ts
const loginStateOptions = [
  { label: "在线", value: "1" },
  { label: "离线", value: "2" },
  { label: "待上线", value: "3" }
];
```

- [ ] **Step 6: Render abnormal stats breakdown**

In `index.vue`, inside each stats card after `</el-statistic>`, add:

```vue
        <div v-if="card.subItems?.length" class="account-stat-breakdown">
          <span v-for="item in card.subItems" :key="item.label">
            {{ item.label }} {{ item.value }}
          </span>
        </div>
```

Add CSS:

```css
.account-stat-breakdown {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2px 8px;
  margin-top: 6px;
  font-size: 12px;
  line-height: 18px;
  color: var(--el-text-color-secondary);
}
```

- [ ] **Step 7: Run frontend focused tests and typecheck**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import tsx src/views/account/index/account-display.test.ts
node --import tsx src/api/account.test.ts
pnpm typecheck
```

Expected: tests pass and typecheck passes.

- [ ] **Step 8: Commit Task 4**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git add src/api/account.ts \
        src/views/account/index/account-display.ts \
        src/views/account/index/account-display.test.ts \
        src/views/account/index/composables/useAccountListPage.ts \
        src/views/account/index/index.vue
git commit -m "feat(account): display pending and restricted account stats"
```

---

## Task 5: Full Verification

**Files:**
- No code files should change in this task.

- [ ] **Step 1: Run backend focused verification**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=AccountOnlineCommandServiceImplTest,AccountControllerTest test
./dbtest.sh 'AccountStatsMapperDbTest,AccountOnlineCommandServiceImplDbTest,AccountStateEventServiceImplDbTest,AccountControllerDbTest'
```

Expected: all tests pass with zero skipped DbTests.

- [ ] **Step 2: Validate edited mapper XML**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
xmllint --noout src/main/resources/mapper/account/AccountMapper.xml src/main/resources/mapper/account/AccountStateMapper.xml
```

Expected: no XML parse errors. If `xmllint` is unavailable, the DbTests from Step 1 are the required XML/parser verification.

- [ ] **Step 3: Run frontend verification**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import tsx src/views/account/index/account-display.test.ts
node --import tsx src/api/account.test.ts
pnpm typecheck
```

Expected: tests and typecheck pass.

- [ ] **Step 4: Inspect git status in both repositories**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git status --short
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git status --short
```

Expected: only pre-existing unrelated local changes remain. No task files should be unstaged after the task commits.

---

## Self-Review

Spec coverage:

- `login_state=3` pending online is implemented in Task 1 and documented in Task 3.
- Offline-only-normal count is implemented in Task 2.
- Restricted breakdown and total are implemented in Task 2 and rendered in Task 4.
- Frontend stops deriving pending online in Task 4.
- Tests cover backend mapper/service/event behavior and frontend display behavior.

Type consistency:

- Backend names: `PENDING_ONLINE`, `pendingOnline`, `restrictedTotal`, `unbound`, `muted`, `exported`.
- Frontend names match backend JSON property names: `pendingOnline`, `restrictedTotal`, `unbound`, `muted`, `exported`.
