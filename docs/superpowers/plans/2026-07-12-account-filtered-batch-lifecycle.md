# Account Filtered Batch Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make “批量登录/批量离线” operate on selected account IDs or, when nothing is selected, every account matching the last successfully applied list filters, with backend preview, eligibility skipping, safe chunking, and aggregate results.

**Architecture:** Keep the existing ID execution URLs for selected rows and add separate query-based URLs for unselected rows. Both routes delegate to a new Armada batch lifecycle orchestrator that resolves targets in the current tenant, skips ineligible login accounts, calls the existing lifecycle command service in 500-account online or 1,000-account offline chunks, and aggregates partial failures. The Vue page keeps editable and applied filter snapshots separately, previews the exact backend scope, confirms it, and chooses the ID or query API without ever collecting IDs from frontend pagination.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis XML, JUnit 5, Mockito, AssertJ, Vue 3, TypeScript, Element Plus, Node test runner, pnpm.

---

## Scope and repository map

This is one end-to-end feature spanning two repositories. The backend slice is independently testable before the frontend is connected, but neither slice alone satisfies the user-visible requirement.

### Armada backend files

- Create `armada-api/src/main/java/com/armada/account/model/enums/AccountBatchOperation.java`: `ONLINE` / `OFFLINE` preview operation.
- Create `armada-api/src/main/java/com/armada/account/model/enums/AccountBatchScope.java`: explicit `IDS` / `QUERY` preview scope.
- Create `armada-api/src/main/java/com/armada/account/model/enums/AccountBatchSkipReason.java`: stable skip-reason keys.
- Create `armada-api/src/main/java/com/armada/account/model/dto/AccountBatchQueryDTO.java`: batch filter body without pagination.
- Create `armada-api/src/main/java/com/armada/account/model/dto/AccountBatchPreviewDTO.java`: explicit preview selector.
- Create `armada-api/src/main/java/com/armada/account/model/dto/AccountBatchTargetQuery.java`: internal cursor query.
- Create `armada-api/src/main/java/com/armada/account/model/vo/AccountBatchPreviewVO.java`: matched/executable/skipped preview.
- Create `armada-api/src/main/java/com/armada/account/model/vo/AccountBatchCommandResultVO.java`: backward-compatible command fields plus skipped/failed summaries.
- Create `armada-api/src/main/java/com/armada/account/model/vo/AccountBatchPreviewRow.java`: mapper aggregate row.
- Create `armada-api/src/main/java/com/armada/account/model/vo/AccountBatchTargetRow.java`: target ID, state, and credential-presence row.
- Create `armada-api/src/main/java/com/armada/account/service/AccountBatchLifecycleService.java`: preview and four execution entry points.
- Create `armada-api/src/main/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImpl.java`: target resolution, skip rules, chunking, partial-failure aggregation.
- Modify `armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java`: preview and cursor target methods.
- Modify `armada-api/src/main/resources/mapper/account/AccountMapper.xml`: shared-filter preview and target SQL.
- Modify `armada-api/src/main/java/com/armada/account/controller/AccountController.java`: preserve ID routes and add preview/query routes.
- Create `armada-api/src/test/java/com/armada/account/model/dto/AccountBatchQueryDTOTest.java`.
- Create `armada-api/src/test/java/com/armada/account/mapper/AccountBatchTargetMapperDbTest.java`.
- Create `armada-api/src/test/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImplTest.java`.
- Modify `armada-api/src/test/java/com/armada/account/controller/AccountControllerTest.java`.

### wheel-saas-pure-web frontend files

- Modify `../wheel-saas-pure-web/src/api/account.ts`: preview and query execution types/functions.
- Modify `../wheel-saas-pure-web/src/api/account-mapping.ts`: strip pagination and normalize batch filters.
- Modify `../wheel-saas-pure-web/src/api/account.test.ts` and `src/api/account-mapping.test.ts`: API contracts.
- Create `../wheel-saas-pure-web/src/views/account/index/account-query-state.ts`: filter normalization and latest-success applied snapshot coordinator.
- Create `../wheel-saas-pure-web/src/views/account/index/account-query-state.test.ts`.
- Create `../wheel-saas-pure-web/src/views/account/index/account-batch-operation.ts`: preview selector and confirmation/result text.
- Create `../wheel-saas-pure-web/src/views/account/index/account-batch-operation.test.ts`.
- Modify `../wheel-saas-pure-web/src/views/account/index/composables/useAccountListPage.ts`: applied filters, preview/confirm/execute flow.
- Modify `../wheel-saas-pure-web/src/views/account/index/components/AccountListTable.vue`: labels and batch loading state.
- Modify `../wheel-saas-pure-web/src/views/account/index/components/AccountListTable.test.ts`.
- Modify `../wheel-saas-pure-web/src/views/account/index/index.vue`: pass the new loading prop.
- Create `../wheel-saas-pure-web/.harness/changes/account-filtered-batch-lifecycle/summary.md`: change and verification record required by the frontend repository rules.

No database migration and no protocol-layer change are required.

---

### Task 1: Define backend request and response contracts

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/model/enums/AccountBatchOperation.java`
- Create: `armada-api/src/main/java/com/armada/account/model/enums/AccountBatchScope.java`
- Create: `armada-api/src/main/java/com/armada/account/model/enums/AccountBatchSkipReason.java`
- Create: `armada-api/src/main/java/com/armada/account/model/dto/AccountBatchQueryDTO.java`
- Create: `armada-api/src/main/java/com/armada/account/model/dto/AccountBatchPreviewDTO.java`
- Create: `armada-api/src/main/java/com/armada/account/model/vo/AccountBatchPreviewVO.java`
- Create: `armada-api/src/main/java/com/armada/account/model/vo/AccountBatchCommandResultVO.java`
- Test: `armada-api/src/test/java/com/armada/account/model/dto/AccountBatchQueryDTOTest.java`

- [ ] **Step 1: Write the failing DTO test**

Create `AccountBatchQueryDTOTest.java` with tests proving that the request exposes only supported filters and converts to the existing SQL query type:

```java
package com.armada.account.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AccountBatchQueryDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void jsonDoesNotAcceptPaginationAndConvertsSupportedFilters() throws Exception {
        AccountBatchQueryDTO dto = objectMapper.readValue("""
                {"loginState":2,"country":" 美国 ","accountGroupId":7,"page":9,"pageSize":500}
                """, AccountBatchQueryDTO.class);

        AccountQuery query = dto.toAccountQuery();

        assertThat(query.getLoginState()).isEqualTo(2);
        assertThat(query.getCountry()).isEqualTo("美国");
        assertThat(query.getAccountGroupId()).isEqualTo(7L);
        assertThat(query.getPage()).isEqualTo(1);
        assertThat(query.getPageSize()).isEqualTo(10);
    }

    @Test
    void emptyJsonRepresentsAllActiveTenantAccounts() throws Exception {
        AccountBatchQueryDTO dto = objectMapper.readValue("{}", AccountBatchQueryDTO.class);
        AccountQuery query = dto.toAccountQuery();

        assertThat(query.getKeyword()).isNull();
        assertThat(query.getLoginState()).isNull();
        assertThat(query.getCountry()).isNull();
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run from `armada/armada-api`:

```bash
mvn -q -Dtest=AccountBatchQueryDTOTest test
```

Expected: compilation fails because `AccountBatchQueryDTO` does not exist.

- [ ] **Step 3: Add the enum and DTO contracts**

Use these exact enum values:

```java
public enum AccountBatchOperation { ONLINE, OFFLINE }
public enum AccountBatchScope { IDS, QUERY }
public enum AccountBatchSkipReason { BANNED, UNBOUND, TAKING_OVER, MISSING_CREDENTIAL }
```

Implement `AccountBatchQueryDTO` as a Jackson record with ignored unknown properties so `page/pageSize` never enter batch SQL:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountBatchQueryDTO(
        String keyword,
        String phone,
        Integer accountType,
        String protocolId,
        Integer accountState,
        Integer riskStatus,
        Integer loginState,
        Integer muteStatus,
        Long accountGroupId,
        Integer numberSource,
        String channelName,
        String country,
        String truthIp
) {
    public AccountQuery toAccountQuery() {
        AccountQuery query = new AccountQuery();
        query.setKeyword(trim(keyword));
        query.setPhone(trim(phone));
        query.setAccountType(accountType);
        query.setProtocolId(trim(protocolId));
        query.setAccountState(accountState);
        query.setRiskStatus(riskStatus);
        query.setLoginState(loginState);
        query.setMuteStatus(muteStatus);
        query.setAccountGroupId(accountGroupId);
        query.setNumberSource(numberSource);
        query.setChannelName(trim(channelName));
        query.setCountry(trim(country));
        query.setTruthIp(trim(truthIp));
        return query;
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
```

Add the explicit preview selector:

```java
public record AccountBatchPreviewDTO(
        AccountBatchOperation operation,
        AccountBatchScope scope,
        List<Long> ids,
        AccountBatchQueryDTO query
) {
}
```

- [ ] **Step 4: Add preview and aggregate result VOs**

Create `AccountBatchPreviewVO`:

```java
public record AccountBatchPreviewVO(
        long matched,
        long executable,
        long skipped,
        Map<String, Long> skipReasons
) {
}
```

Create `AccountBatchCommandResultVO` with all existing JSON fields plus the new summary and a conversion method for the legacy `accounts` branch:

```java
public record AccountBatchCommandResultVO(
        int requested,
        int submitted,
        int accepted,
        int timeout,
        int proxyRequired,
        int error,
        int remote,
        long elapsedMs,
        int skipped,
        int failed,
        Map<String, Integer> skipReasons,
        List<String> batchErrors,
        List<AccountBatchOnlineItemVO> results,
        List<AccountBatchOnlineRemoteRouteVO> remoteRoutes
) {
    public static AccountBatchCommandResultVO from(AccountBatchOnlineVO value) {
        return new AccountBatchCommandResultVO(
                value.requested(), value.submitted(), value.accepted(), value.timeout(),
                value.proxyRequired(), value.error(), value.remote(), value.elapsedMs(),
                0, value.error(), Map.of(), List.of(), value.results(), value.remoteRoutes());
    }
}
```

- [ ] **Step 5: Run the focused test and compile production sources**

```bash
mvn -q -Dtest=AccountBatchQueryDTOTest test
mvn -q -DskipTests compile
```

Expected: both commands pass.

- [ ] **Step 6: Commit the contract slice**

```bash
git add armada-api/src/main/java/com/armada/account/model armada-api/src/test/java/com/armada/account/model/dto/AccountBatchQueryDTOTest.java
git commit -m "feat(account): define batch lifecycle contracts"
```

---

### Task 2: Add backend preview and cursor target queries

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/model/dto/AccountBatchTargetQuery.java`
- Create: `armada-api/src/main/java/com/armada/account/model/vo/AccountBatchPreviewRow.java`
- Create: `armada-api/src/main/java/com/armada/account/model/vo/AccountBatchTargetRow.java`
- Modify: `armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountMapper.xml`
- Test: `armada-api/src/test/java/com/armada/account/mapper/AccountBatchTargetMapperDbTest.java`

- [ ] **Step 1: Write failing DbTests for query parity and cursor scanning**

Create `AccountBatchTargetMapperDbTest` extending `DbTestBase`. Seed four active accounts under the current test tenant: offline normal with credential, online normal with credential, banned without credential, and a nonmatching-country account. Add these assertions:

```java
@Test
void previewAndCursorUseTheSameAppliedFiltersAndCredentialPrecedence() {
    long now = System.currentTimeMillis();
    String phonePrefix = "86139" + (now % 1_000_000L);
    seedTarget(phonePrefix + "01", AccountStateCode.NORMAL, "印度", true, now);
    seedTarget(phonePrefix + "02", AccountStateCode.NORMAL, "印度", true, now + 1);
    seedTarget(phonePrefix + "03", AccountStateCode.BANNED, "印度", false, now + 2);
    seedTarget(phonePrefix + "04", AccountStateCode.NORMAL, "美国", true, now + 3);
    AccountBatchQueryDTO dto = new AccountBatchQueryDTO(
            null, phonePrefix, null, null, null, null, null, null,
            null, null, null, "印度", null);
    AccountQuery query = dto.toAccountQuery();

    AccountBatchPreviewRow preview = mapper.previewBatchTargetsByQuery(query);
    List<AccountBatchTargetRow> rows = mapper.selectBatchTargetsAfterId(
            AccountBatchTargetQuery.from(query, 0L, 2));

    assertThat(preview.getMatched()).isEqualTo(3);
    assertThat(preview.getBanned()).isEqualTo(1);
    assertThat(preview.getMissingCredential()).isZero();
    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(AccountBatchTargetRow::getId).isSorted();
}

@Test
void emptyFilterScansBeyondTheFirstBatchWithoutDuplicates() {
    long now = System.currentTimeMillis();
    String phonePrefix = "86138" + (now % 1_000_000L);
    seedTarget(phonePrefix + "01", AccountStateCode.NORMAL, "印度", true, now);
    seedTarget(phonePrefix + "02", AccountStateCode.NORMAL, "印度", true, now + 1);
    seedTarget(phonePrefix + "03", AccountStateCode.NORMAL, "印度", true, now + 2);
    seedTarget(phonePrefix + "04", AccountStateCode.NORMAL, "印度", true, now + 3);
    AccountQuery query = new AccountBatchQueryDTO(
            null, phonePrefix, null, null, null, null, null, null,
            null, null, null, null, null).toAccountQuery();
    List<AccountBatchTargetRow> first = mapper.selectBatchTargetsAfterId(
            AccountBatchTargetQuery.from(query, 0L, 2));
    List<AccountBatchTargetRow> second = mapper.selectBatchTargetsAfterId(
            AccountBatchTargetQuery.from(query, first.get(1).getId(), 2));

    assertThat(first).extracting(AccountBatchTargetRow::getId)
            .doesNotContainAnyElementsOf(second.stream().map(AccountBatchTargetRow::getId).toList());
}
```

Autowire `AccountMapper`, `AccountStateMapper`, and `AccountCredentialMapper`, then use this seed helper so the test exercises real joins and the tenant interceptor:

```java
private Account seedTarget(
        String phone, int stateCode, String country, boolean withCredential, long now) {
    Account account = new Account();
    account.setWsPhone(phone);
    account.setAccountType(1);
    account.setOwnership(1);
    account.setPriority(0);
    account.setCreatedAt(now);
    account.setUpdatedAt(now);
    accountMapper.insert(account);

    AccountState state = new AccountState();
    state.setAccountId(account.getId());
    state.setAccountState(stateCode);
    state.setProxyCountry(country);
    state.setProxyFailureCount(0);
    state.setPullIntoGroupCount(0);
    state.setCreatedAt(now);
    state.setUpdatedAt(now);
    stateMapper.insert(state);

    if (withCredential) {
        AccountCredential credential = new AccountCredential();
        credential.setAccountId(account.getId());
        credential.setWsPhone(phone);
        credential.setCredFormat(2);
        credential.setCredsJson("{\"creds\":{},\"keys\":{}}");
        credential.setCreatedAt(now);
        credential.setUpdatedAt(now);
        credentialMapper.insert(credential);
    }
    return account;
}
```

- [ ] **Step 2: Run the DbTest and verify it fails**

Run from `armada`:

```bash
armada-api/dbtest.sh AccountBatchTargetMapperDbTest
```

Expected: test compilation fails because the mapper methods and row types do not exist.

- [ ] **Step 3: Add the internal cursor and row types**

`AccountBatchTargetQuery` extends `AccountQuery`, copies every filter field, and adds `afterId` and `scanSize`:

```java
public class AccountBatchTargetQuery extends AccountQuery {
    private long afterId;
    private int scanSize;

    public static AccountBatchTargetQuery from(AccountQuery source, long afterId, int scanSize) {
        AccountBatchTargetQuery target = new AccountBatchTargetQuery();
        target.setKeyword(source.getKeyword());
        target.setPhone(source.getPhone());
        target.setAccountType(source.getAccountType());
        target.setProtocolId(source.getProtocolId());
        target.setAccountState(source.getAccountState());
        target.setRiskStatus(source.getRiskStatus());
        target.setLoginState(source.getLoginState());
        target.setMuteStatus(source.getMuteStatus());
        target.setAccountGroupId(source.getAccountGroupId());
        target.setNumberSource(source.getNumberSource());
        target.setChannelName(source.getChannelName());
        target.setCountry(source.getCountry());
        target.setTruthIp(source.getTruthIp());
        target.afterId = afterId;
        target.scanSize = scanSize;
        return target;
    }

    public long getAfterId() { return afterId; }
    public int getScanSize() { return scanSize; }
}
```

`AccountBatchTargetRow` is a mutable MyBatis row with `Long id`, `Integer accountState`, and `boolean credentialPresent`. `AccountBatchPreviewRow` is a mutable row with `long matched`, `long banned`, `long unbound`, `long takingOver`, and `long missingCredential`, each with normal getters/setters.

- [ ] **Step 4: Add mapper signatures and shared-filter SQL**

Add to `AccountMapper.java`:

```java
AccountBatchPreviewRow previewBatchTargetsByIds(@Param("ids") List<Long> ids);
AccountBatchPreviewRow previewBatchTargetsByQuery(AccountQuery query);
List<AccountBatchTargetRow> selectBatchTargetsByIds(@Param("ids") List<Long> ids);
List<AccountBatchTargetRow> selectBatchTargetsAfterId(AccountBatchTargetQuery query);
```

In `AccountMapper.xml`, add a reusable target projection and preserve skip precedence so each account contributes to at most one skip reason:

```xml
<sql id="batchTargetJoins">
  FROM account a
  LEFT JOIN account_state s ON s.account_id = a.id AND s.tenant_id = a.tenant_id
  LEFT JOIN account_group g ON g.id = a.account_group_id AND g.deleted_at IS NULL
  LEFT JOIN ip_proxy p ON p.bound_account_id = a.id AND p.tenant_id = a.tenant_id
    AND p.deleted_at IS NULL AND p.status = 2
</sql>

<sql id="batchPreviewColumns">
  COUNT(DISTINCT a.id) AS matched,
  COUNT(DISTINCT CASE WHEN s.account_state = 3 THEN a.id END) AS banned,
  COUNT(DISTINCT CASE WHEN s.account_state = 5 THEN a.id END) AS unbound,
  COUNT(DISTINCT CASE WHEN s.account_state = 7 THEN a.id END) AS takingOver,
  COUNT(DISTINCT CASE
    WHEN (s.account_state IS NULL OR s.account_state NOT IN (3, 5, 7))
      AND NOT EXISTS (
        SELECT 1 FROM account_credential c
        WHERE c.account_id = a.id AND c.tenant_id = a.tenant_id AND c.deleted_at IS NULL
      )
    THEN a.id END) AS missingCredential
</sql>
```

Use `<include refid="filter"/>` in both `previewBatchTargetsByQuery` and `selectBatchTargetsAfterId`. The cursor select must use `a.id &gt; #{afterId}`, `ORDER BY a.id ASC`, and `LIMIT #{scanSize}`. The ID variants must require a nonempty `ids` list and use `a.id IN (...)`; the service prevents empty-list calls.

- [ ] **Step 5: Run XML and database verification**

```bash
xmllint --noout armada-api/src/main/resources/mapper/account/AccountMapper.xml
armada-api/dbtest.sh AccountBatchTargetMapperDbTest
armada-api/dbtest.sh AccountListMapperDbTest#countPage_matchesSelectPage_size
```

Expected: XML parses; new DbTests pass; the existing shared list-count test remains green.

- [ ] **Step 6: Commit the mapper slice**

```bash
git add armada-api/src/main/java/com/armada/account/model/dto/AccountBatchTargetQuery.java armada-api/src/main/java/com/armada/account/model/vo/AccountBatchPreviewRow.java armada-api/src/main/java/com/armada/account/model/vo/AccountBatchTargetRow.java armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java armada-api/src/main/resources/mapper/account/AccountMapper.xml armada-api/src/test/java/com/armada/account/mapper/AccountBatchTargetMapperDbTest.java
git commit -m "feat(account): query batch lifecycle targets"
```

---

### Task 3: Implement selected-ID orchestration and 2,000-account capacity

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/service/AccountBatchLifecycleService.java`
- Create: `armada-api/src/main/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImplTest.java`

- [ ] **Step 1: Write failing tests for validation, skipping, online inclusion, and chunk sizes**

Use Mockito mocks for `AccountMapper` and `AccountOnlineCommandService`. Add tests with these exact expectations:

```java
@Test
void onlineByIds_accepts2000AndCallsFour500AccountChunks() {
    List<Long> ids = LongStream.rangeClosed(1, 2000).boxed().toList();
    when(mapper.selectBatchTargetsByIds(ids)).thenReturn(targets(ids, null, true));
    when(commandService.onlineBatch(any())).thenAnswer(invocation -> accepted(invocation.getArgument(0)));

    AccountBatchCommandResultVO result = service.onlineByIds(ids);

    ArgumentCaptor<List<Long>> chunks = ArgumentCaptor.forClass(List.class);
    verify(commandService, times(4)).onlineBatch(chunks.capture());
    assertThat(chunks.getAllValues()).allSatisfy(chunk -> assertThat(chunk).hasSize(500));
    assertThat(result.requested()).isEqualTo(2000);
    assertThat(result.accepted()).isEqualTo(2000);
}

@Test
void onlineByIds_skipsBlockedAndMissingCredentialButKeepsOnlineAccount() {
    when(mapper.selectBatchTargetsByIds(List.of(1L, 2L, 3L, 4L, 5L))).thenReturn(List.of(
            target(1L, AccountStateCode.BANNED, true),
            target(2L, AccountStateCode.UNBOUND, true),
            target(3L, AccountStateCode.TAKING_OVER, true),
            target(4L, AccountStateCode.NORMAL, false),
            target(5L, AccountStateCode.NORMAL, true)));
    when(commandService.onlineBatch(List.of(5L))).thenReturn(accepted(List.of(5L)));

    AccountBatchCommandResultVO result = service.onlineByIds(List.of(1L, 2L, 3L, 4L, 5L));

    verify(commandService).onlineBatch(List.of(5L));
    assertThat(result.skipped()).isEqualTo(4);
    assertThat(result.skipReasons()).containsEntry("BANNED", 1)
            .containsEntry("UNBOUND", 1)
            .containsEntry("TAKING_OVER", 1)
            .containsEntry("MISSING_CREDENTIAL", 1);
}

@Test
void onlineByIds_rejects2001BeforeQueryingTargets() {
    List<Long> ids = LongStream.rangeClosed(1, 2001).boxed().toList();
    assertThatThrownBy(() -> service.onlineByIds(ids))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("一次最多 2000 个账号");
    verifyNoInteractions(mapper, commandService);
}

@Test
void offlineByIds_callsTwo1000AccountChunksWithoutCredentialSkipping() {
    List<Long> ids = LongStream.rangeClosed(1, 2000).boxed().toList();
    when(mapper.selectBatchTargetsByIds(ids)).thenReturn(targets(ids, AccountStateCode.BANNED, false));
    when(commandService.offlineBatch(any())).thenAnswer(invocation -> accepted(invocation.getArgument(0)));

    AccountBatchCommandResultVO result = service.offlineByIds(ids);

    verify(commandService, times(2)).offlineBatch(any());
    assertThat(result.submitted()).isEqualTo(2000);
    assertThat(result.skipped()).isZero();
}
```

Define the test helpers in the same class so every generated target and accepted result is explicit:

```java
private static AccountBatchTargetRow target(Long id, Integer state, boolean credentialPresent) {
    AccountBatchTargetRow row = new AccountBatchTargetRow();
    row.setId(id);
    row.setAccountState(state);
    row.setCredentialPresent(credentialPresent);
    return row;
}

private static List<AccountBatchTargetRow> targets(
        List<Long> ids, Integer state, boolean credentialPresent) {
    return ids.stream().map(id -> target(id, state, credentialPresent)).toList();
}

private static AccountBatchOnlineVO accepted(List<Long> ids) {
    return new AccountBatchOnlineVO(
            ids.size(), ids.size(), ids.size(), 0, 0, 0, 0, 0L,
            ids.stream().map(id -> new AccountBatchOnlineItemVO(
                    id, "acc_" + id, "ACCEPTED", null, null)).toList(),
            List.of());
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

```bash
mvn -q -Dtest=AccountBatchLifecycleServiceImplTest test
```

Expected: compilation fails because the service does not exist.

- [ ] **Step 3: Define the service boundary**

```java
public interface AccountBatchLifecycleService {
    AccountBatchPreviewVO preview(AccountBatchPreviewDTO request);
    AccountBatchCommandResultVO onlineByIds(List<Long> ids);
    AccountBatchCommandResultVO offlineByIds(List<Long> ids);
    AccountBatchCommandResultVO onlineByQuery(AccountBatchQueryDTO query);
    AccountBatchCommandResultVO offlineByQuery(AccountBatchQueryDTO query);
}
```

- [ ] **Step 4: Implement ID normalization and eligibility classification**

In `AccountBatchLifecycleServiceImpl`, define:

```java
private static final int ID_REQUEST_MAX = 2_000;
private static final int ONLINE_CHUNK_SIZE = 500;
private static final int OFFLINE_CHUNK_SIZE = 1_000;

private List<Long> normalizeIds(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
        throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 列表不能为空");
    }
    if (ids.size() > ID_REQUEST_MAX) {
        throw new BusinessException(ErrorCode.VALIDATION, "批量账号操作一次最多 2000 个账号");
    }
    LinkedHashSet<Long> unique = new LinkedHashSet<>();
    for (Long id : ids) {
        if (id == null) throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能为空");
        if (!unique.add(id)) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能重复: " + id);
        }
    }
    return List.copyOf(unique);
}

private AccountBatchSkipReason skipReason(AccountBatchTargetRow row) {
    if (Integer.valueOf(AccountStateCode.BANNED).equals(row.getAccountState())) return AccountBatchSkipReason.BANNED;
    if (Integer.valueOf(AccountStateCode.UNBOUND).equals(row.getAccountState())) return AccountBatchSkipReason.UNBOUND;
    if (Integer.valueOf(AccountStateCode.TAKING_OVER).equals(row.getAccountState())) return AccountBatchSkipReason.TAKING_OVER;
    if (!row.isCredentialPresent()) return AccountBatchSkipReason.MISSING_CREDENTIAL;
    return null;
}
```

`onlineByIds` must load the normalized IDs, classify rows once, split eligible IDs with `List.subList`, call `commandService.onlineBatch`, and accumulate results. `offlineByIds` must use every active target row regardless of state or credential and call `offlineBatch` in 1,000-ID chunks.

- [ ] **Step 5: Implement the aggregate accumulator**

Use a private accumulator that maps existing batch results without returning unbounded detail lists:

```java
private void executeChunk(List<Long> chunk, boolean online, BatchAccumulator total) {
    total.submitted += chunk.size();
    try {
        AccountBatchOnlineVO result = online
                ? commandService.onlineBatch(chunk)
                : commandService.offlineBatch(chunk);
        total.accepted += result.accepted();
        int unaccepted = Math.max(0, chunk.size() - result.accepted());
        total.failed += unaccepted;
        if (unaccepted > 0) {
            total.addBatchError("批次存在 " + unaccepted + " 个未受理账号");
        }
        total.timeout += result.timeout();
        total.proxyRequired += result.proxyRequired();
        total.remote += result.remote();
        if (total.includeDetails) {
            total.results.addAll(result.results());
            total.remoteRoutes.addAll(result.remoteRoutes());
        }
    } catch (RuntimeException exception) {
        total.failed += chunk.size();
        total.addBatchError(safeBatchError(exception));
    }
}

private String safeBatchError(RuntimeException exception) {
    String message = exception.getMessage();
    String safe = message == null || message.isBlank()
            ? exception.getClass().getSimpleName()
            : message.replaceAll("[\\r\\n]+", " ");
    return safe.length() <= 200 ? safe : safe.substring(0, 200);
}
```

Construct the accumulator with `includeDetails=true` for the existing ID routes so their bounded (maximum 2,000) `results` and `remoteRoutes` remain compatible. Construct it with `includeDetails=false` for query routes so an unbounded filtered operation returns counts rather than a huge account-detail response. The accumulator’s `toVO()` sets old `error` equal to `failed`, includes the skip map, and includes only bounded batch-error summaries.

Implement `addBatchError` with a hard cap of 20 messages. Counts continue accumulating after the cap, but the response cannot grow without bound:

```java
private void addBatchError(String message) {
    if (batchErrors.size() < 20) batchErrors.add(message);
}
```

- [ ] **Step 6: Run focused service tests**

```bash
mvn -q -Dtest=AccountBatchLifecycleServiceImplTest test
```

Expected: all selected-ID validation, skip, online-inclusion, 500-chunk, and 1,000-chunk tests pass.

- [ ] **Step 7: Commit the selected-ID orchestrator**

```bash
git add armada-api/src/main/java/com/armada/account/service/AccountBatchLifecycleService.java armada-api/src/main/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImpl.java armada-api/src/test/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImplTest.java
git commit -m "feat(account): orchestrate selected batch lifecycle"
```

---

### Task 4: Add preview, filtered cursor execution, and partial-failure continuation

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImplTest.java`

- [ ] **Step 1: Add failing preview and query-execution tests**

Add tests covering explicit scope validation, empty-filter all-account behavior, multiple cursor pages, and a failed middle chunk:

```java
@Test
void previewOnlineByQueryReturnsMatchedExecutableAndExclusiveSkipCounts() {
    AccountBatchPreviewRow row = previewRow(1256, 20, 10, 6, 20);
    when(mapper.previewBatchTargetsByQuery(any(AccountQuery.class))).thenReturn(row);

    AccountBatchPreviewVO result = service.preview(new AccountBatchPreviewDTO(
            AccountBatchOperation.ONLINE, AccountBatchScope.QUERY, null, emptyQuery()));

    assertThat(result.matched()).isEqualTo(1256);
    assertThat(result.skipped()).isEqualTo(56);
    assertThat(result.executable()).isEqualTo(1200);
}

@Test
void onlineByQueryScansAllPagesAndContinuesAfterOneChunkFails() {
    AccountBatchQueryDTO query = emptyQuery();
    List<AccountBatchTargetRow> first = targets(range(1, 500), null, true);
    List<AccountBatchTargetRow> second = targets(range(501, 1000), null, true);
    List<AccountBatchTargetRow> third = targets(range(1001, 1100), null, true);
    when(mapper.selectBatchTargetsAfterId(any()))
            .thenReturn(first, second, third, List.of());
    when(commandService.onlineBatch(ids(first))).thenReturn(accepted(ids(first)));
    when(commandService.onlineBatch(ids(second))).thenThrow(new RuntimeException("outbox unavailable"));
    when(commandService.onlineBatch(ids(third))).thenReturn(accepted(ids(third)));

    AccountBatchCommandResultVO result = service.onlineByQuery(query);

    verify(commandService, times(3)).onlineBatch(any());
    assertThat(result.requested()).isEqualTo(1100);
    assertThat(result.accepted()).isEqualTo(600);
    assertThat(result.failed()).isEqualTo(500);
    assertThat(result.batchErrors()).containsExactly("outbox unavailable");
}

@Test
void previewRejectsAmbiguousSelector() {
    assertThatThrownBy(() -> service.preview(new AccountBatchPreviewDTO(
            AccountBatchOperation.ONLINE, AccountBatchScope.IDS, null, emptyQuery())))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("IDS 预估必须提供账号 ID");
}
```

Add these helpers to the same test class; reuse `target`, `targets`, and `accepted` from Task 3:

```java
private static AccountBatchQueryDTO emptyQuery() {
    return new AccountBatchQueryDTO(
            null, null, null, null, null, null, null, null,
            null, null, null, null, null);
}

private static List<Long> range(long first, long last) {
    return LongStream.rangeClosed(first, last).boxed().toList();
}

private static List<Long> ids(List<AccountBatchTargetRow> rows) {
    return rows.stream().map(AccountBatchTargetRow::getId).toList();
}

private static AccountBatchPreviewRow previewRow(
        long matched, long banned, long unbound, long takingOver, long missingCredential) {
    AccountBatchPreviewRow row = new AccountBatchPreviewRow();
    row.setMatched(matched);
    row.setBanned(banned);
    row.setUnbound(unbound);
    row.setTakingOver(takingOver);
    row.setMissingCredential(missingCredential);
    return row;
}
```

- [ ] **Step 2: Run tests and verify the new cases fail**

```bash
mvn -q -Dtest=AccountBatchLifecycleServiceImplTest test
```

Expected: preview/query tests fail because those methods are not implemented.

- [ ] **Step 3: Implement explicit preview dispatch**

```java
@Override
public AccountBatchPreviewVO preview(AccountBatchPreviewDTO request) {
    if (request == null || request.operation() == null || request.scope() == null) {
        throw new BusinessException(ErrorCode.VALIDATION, "批量预估操作和范围不能为空");
    }
    AccountBatchPreviewRow row;
    if (request.scope() == AccountBatchScope.IDS) {
        List<Long> ids = normalizeIds(request.ids());
        if (request.query() != null) {
            throw new BusinessException(ErrorCode.VALIDATION, "IDS 预估不能同时提供查询条件");
        }
        row = mapper.previewBatchTargetsByIds(ids);
    } else {
        if (request.ids() != null && !request.ids().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "QUERY 预估不能同时提供账号 ID");
        }
        AccountBatchQueryDTO query = request.query() == null ? emptyBatchQuery() : request.query();
        row = mapper.previewBatchTargetsByQuery(query.toAccountQuery());
    }
    return request.operation() == AccountBatchOperation.OFFLINE
            ? offlinePreview(row.getMatched())
            : onlinePreview(row);
}
```

`onlinePreview` sums the four exclusive skip columns, calculates `executable = matched - skipped`, and emits map keys from `AccountBatchSkipReason.name()` only when their count is greater than zero.

- [ ] **Step 4: Implement stable cursor execution**

For query execution, never use `page/pageSize` or OFFSET:

```java
private AccountBatchCommandResultVO executeByQuery(AccountBatchQueryDTO dto, boolean online) {
    AccountQuery filters = (dto == null ? emptyBatchQuery() : dto).toAccountQuery();
    long afterId = 0L;
    int scanSize = online ? ONLINE_CHUNK_SIZE : OFFLINE_CHUNK_SIZE;
    BatchAccumulator total = new BatchAccumulator();
    while (true) {
        List<AccountBatchTargetRow> rows = mapper.selectBatchTargetsAfterId(
                AccountBatchTargetQuery.from(filters, afterId, scanSize));
        if (rows.isEmpty()) break;
        afterId = rows.get(rows.size() - 1).getId();
        total.requested += rows.size();
        List<Long> executable = online ? classifyOnline(rows, total) : ids(rows);
        if (!executable.isEmpty()) executeChunk(executable, online, total);
        if (rows.size() < scanSize) break;
    }
    return total.toVO();
}
```

Expose it through `onlineByQuery` and `offlineByQuery`. Because each cursor page is at most the internal chunk size, each nonempty executable list is already safe for the existing command service.

- [ ] **Step 5: Run service and mapper tests**

```bash
mvn -q -Dtest=AccountBatchLifecycleServiceImplTest test
armada-api/dbtest.sh AccountBatchTargetMapperDbTest
```

Expected: preview, cursor scanning, and partial-failure continuation pass.

- [ ] **Step 6: Commit filtered orchestration**

```bash
git add armada-api/src/main/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImpl.java armada-api/src/test/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImplTest.java
git commit -m "feat(account): execute filtered batch lifecycle"
```

---

### Task 5: Wire backend controller routes without breaking legacy account items

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/controller/AccountController.java`
- Modify: `armada-api/src/test/java/com/armada/account/controller/AccountControllerTest.java`

- [ ] **Step 1: Update controller construction and write failing route tests**

Add an `@Mock AccountBatchLifecycleService` and pass it into the standalone controller. Add route tests:

```java
@Test
void postBatchOnlineWithIdsUsesOrchestrator() throws Exception {
    when(batchLifecycleService.onlineByIds(List.of(100L, 101L))).thenReturn(commandResult(2));
    mockMvc.perform(post("/api/accounts/batch-online")
                    .contentType("application/json")
                    .content("{\"ids\":[100,101]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accepted").value(2))
            .andExpect(jsonPath("$.data.skipped").value(0));
    verify(batchLifecycleService).onlineByIds(List.of(100L, 101L));
}

@Test
void postBatchOnlineByQueryUsesFilterOrchestrator() throws Exception {
    when(batchLifecycleService.onlineByQuery(any())).thenReturn(commandResult(1256));
    mockMvc.perform(post("/api/accounts/batch-online-by-query")
                    .contentType("application/json")
                    .content("{\"loginState\":2,\"country\":\"美国\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.requested").value(1256));
}

@Test
void postBatchPreviewReturnsBackendCounts() throws Exception {
    when(batchLifecycleService.preview(any())).thenReturn(
            new AccountBatchPreviewVO(1256, 1200, 56, Map.of("BANNED", 56L)));
    mockMvc.perform(post("/api/accounts/batch-operation-preview")
                    .contentType("application/json")
                    .content("{\"operation\":\"ONLINE\",\"scope\":\"QUERY\",\"query\":{}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.executable").value(1200));
}
```

Keep and update the existing `accounts` request tests to verify that requests containing protocol backends still call `onlineBatchWithProtocolBackends` / `offlineBatchWithProtocolBackends` and are wrapped with `AccountBatchCommandResultVO.from(...)`.

Use this controller-test helper for the mocked orchestrator results:

```java
private static AccountBatchCommandResultVO commandResult(int requested) {
    return new AccountBatchCommandResultVO(
            requested, requested, requested, 0, 0, 0, 0, 0L,
            0, 0, Map.of(), List.of(), List.of(), List.of());
}
```

- [ ] **Step 2: Run controller tests and verify failure**

```bash
mvn -q -Dtest=AccountControllerTest test
```

Expected: constructor and route tests fail before controller wiring exists.

- [ ] **Step 3: Wire the selected and query endpoints**

Change the two existing routes so only the legacy `accounts` branch calls the old service directly:

```java
@PostMapping("/batch-online")
public ApiResponse<AccountBatchCommandResultVO> batchOnline(@RequestBody AccountLifecycleBatchDTO request) {
    if (request.hasAccounts()) {
        return ApiResponse.ok(AccountBatchCommandResultVO.from(
                accountOnlineCommandService.onlineBatchWithProtocolBackends(request.commandItems())));
    }
    return ApiResponse.ok(accountBatchLifecycleService.onlineByIds(request.ids()));
}

@PostMapping("/batch-offline")
public ApiResponse<AccountBatchCommandResultVO> batchOffline(@RequestBody AccountLifecycleBatchDTO request) {
    if (request.hasAccounts()) {
        return ApiResponse.ok(AccountBatchCommandResultVO.from(
                accountOnlineCommandService.offlineBatchWithProtocolBackends(request.commandItems())));
    }
    return ApiResponse.ok(accountBatchLifecycleService.offlineByIds(request.ids()));
}
```

Add:

```java
@PostMapping("/batch-online-by-query")
public ApiResponse<AccountBatchCommandResultVO> batchOnlineByQuery(@RequestBody AccountBatchQueryDTO query) {
    return ApiResponse.ok(accountBatchLifecycleService.onlineByQuery(query));
}

@PostMapping("/batch-offline-by-query")
public ApiResponse<AccountBatchCommandResultVO> batchOfflineByQuery(@RequestBody AccountBatchQueryDTO query) {
    return ApiResponse.ok(accountBatchLifecycleService.offlineByQuery(query));
}

@PostMapping("/batch-operation-preview")
public ApiResponse<AccountBatchPreviewVO> previewBatchOperation(@RequestBody AccountBatchPreviewDTO request) {
    return ApiResponse.ok(accountBatchLifecycleService.preview(request));
}
```

- [ ] **Step 4: Run controller and existing command-service tests**

```bash
mvn -q -Dtest=AccountControllerTest,AccountOnlineCommandServiceImplTest,AccountBatchLifecycleServiceImplTest test
```

Expected: all tests pass, including legacy protocol-backend request coverage.

- [ ] **Step 5: Commit backend HTTP integration**

```bash
git add armada-api/src/main/java/com/armada/account/controller/AccountController.java armada-api/src/test/java/com/armada/account/controller/AccountControllerTest.java
git commit -m "feat(account): expose filtered batch lifecycle APIs"
```

---

### Task 6: Add frontend API mapping, preview requests, and confirmation helpers

**Files:**
- Modify: `../wheel-saas-pure-web/src/api/account.ts`
- Modify: `../wheel-saas-pure-web/src/api/account-mapping.ts`
- Modify: `../wheel-saas-pure-web/src/api/account.test.ts`
- Modify: `../wheel-saas-pure-web/src/api/account-mapping.test.ts`
- Create: `../wheel-saas-pure-web/src/views/account/index/account-batch-operation.ts`
- Create: `../wheel-saas-pure-web/src/views/account/index/account-batch-operation.test.ts`

- [ ] **Step 1: Write failing API and helper tests**

Add API assertions:

```ts
const batchResult = {
  requested: 2,
  submitted: 2,
  accepted: 2,
  timeout: 0,
  proxyRequired: 0,
  error: 0,
  remote: 0,
  elapsedMs: 0,
  skipped: 0,
  failed: 0,
  skipReasons: {},
  batchErrors: [],
  results: [],
  remoteRoutes: []
};

it("posts filtered online requests without pagination", async () => {
  resetArmadaMock(batchResult);
  await batchOnlineTenantAccountsByQuery({ loginState: 2, country: "美国" });
  assert.deepEqual(armadaCalls(), [{
    method: "post",
    url: "/api/accounts/batch-online-by-query",
    opts: { data: { loginState: 2, country: "美国" } }
  }]);
});

it("posts explicit query preview requests", async () => {
  resetArmadaMock({ matched: 1256, executable: 1200, skipped: 56, skipReasons: {} });
  await previewTenantAccountBatch({
    operation: "ONLINE",
    scope: "QUERY",
    query: { loginState: 2 }
  });
  assert.equal(armadaCalls()[0].url, "/api/accounts/batch-operation-preview");
});
```

Create helper tests:

```ts
it("uses selected IDs before applied filters", () => {
  assert.deepEqual(buildBatchPreviewRequest("ONLINE", [10, 11], { loginState: 2 }), {
    operation: "ONLINE",
    scope: "IDS",
    ids: [10, 11]
  });
});

it("describes a filtered unselected online operation", () => {
  const text = batchConfirmMessage("ONLINE", 0, true, {
    matched: 1256, executable: 1200, skipped: 56, skipReasons: {}
  });
  assert.equal(text,
    "当前未勾选账号，符合已生效筛选条件共 1,256 个；预计执行批量登录 1,200 个，跳过 56 个不可登录账号，是否继续？");
});

it("describes an unfiltered offline operation as all accounts", () => {
  const text = batchConfirmMessage("OFFLINE", 0, false, {
    matched: 1256, executable: 1256, skipped: 0, skipReasons: {}
  });
  assert.equal(text, "当前未勾选账号，将对全部 1,256 个账号执行批量离线，是否继续？");
});
```

- [ ] **Step 2: Run focused frontend tests and verify failure**

Run from `wheel-saas-pure-web`:

```bash
node --test src/api/account.test.ts src/api/account-mapping.test.ts src/views/account/index/account-batch-operation.test.ts
```

Expected: imports/functions fail because the new API and helper do not exist.

- [ ] **Step 3: Add batch filter and response types**

In `account.ts`, define an explicit filter type without pagination:

```ts
export type TenantAccountBatchQuery = Omit<
  BackendTenantAccountListParams,
  "page" | "pageSize"
>;

export type TenantAccountBatchOperation = "ONLINE" | "OFFLINE";
export type TenantAccountBatchScope = "IDS" | "QUERY";

export interface TenantAccountBatchPreview {
  matched: number;
  executable: number;
  skipped: number;
  skipReasons: Record<string, number>;
}

export interface TenantAccountBatchPreviewRequest {
  operation: TenantAccountBatchOperation;
  scope: TenantAccountBatchScope;
  ids?: number[];
  query?: TenantAccountBatchQuery;
}
```

Extend `TenantAccountBatchCommandResult` with required `skipped`, `failed`, `skipReasons`, and `batchErrors` while preserving existing fields.

- [ ] **Step 4: Add pagination-free mapping and API functions**

Export `BackendTenantAccountListParams` from `account-mapping.ts` and add:

```ts
export function toTenantAccountBatchQuery(
  query: TenantAccountListQuery
): Omit<BackendTenantAccountListParams, "page" | "pageSize"> {
  const { page: _page, pageSize: _pageSize, ...filters } =
    toTenantAccountListParams(query);
  return filters;
}
```

Add functions using only the new URLs:

```ts
export function previewTenantAccountBatch(
  data: TenantAccountBatchPreviewRequest
): Promise<TenantAccountBatchPreview> {
  return armadaRequest("post", "/api/accounts/batch-operation-preview", { data });
}

export function batchOnlineTenantAccountsByQuery(
  query: TenantAccountBatchQuery
): Promise<TenantAccountBatchCommandResult> {
  return armadaRequest("post", "/api/accounts/batch-online-by-query", { data: query });
}

export function batchOfflineTenantAccountsByQuery(
  query: TenantAccountBatchQuery
): Promise<TenantAccountBatchCommandResult> {
  return armadaRequest("post", "/api/accounts/batch-offline-by-query", { data: query });
}
```

- [ ] **Step 5: Implement the pure batch interaction helper**

`account-batch-operation.ts` must select IDs whenever any are present, otherwise copy applied filters:

```ts
export function buildBatchPreviewRequest(
  operation: TenantAccountBatchOperation,
  ids: number[],
  appliedFilters: TenantAccountBatchQuery
): TenantAccountBatchPreviewRequest {
  return ids.length > 0
    ? { operation, scope: "IDS", ids: [...ids] }
    : { operation, scope: "QUERY", query: { ...appliedFilters } };
}

export function batchConfirmMessage(
  operation: TenantAccountBatchOperation,
  selectedCount: number,
  hasAppliedFilters: boolean,
  preview: TenantAccountBatchPreview
): string {
  const matched = preview.matched.toLocaleString("zh-CN");
  const executable = preview.executable.toLocaleString("zh-CN");
  const skipped = preview.skipped.toLocaleString("zh-CN");
  const action = operation === "ONLINE" ? "批量登录" : "批量离线";
  if (selectedCount > 0) {
    const suffix = preview.skipped > 0
      ? `，预计执行${action} ${executable} 个，跳过 ${skipped} 个不可登录账号`
      : `，将执行${action}`;
    return `当前已勾选 ${matched} 个账号${suffix}，是否继续？`;
  }
  if (!hasAppliedFilters) {
    return `当前未勾选账号，将对全部 ${matched} 个账号执行${action}，是否继续？`;
  }
  const skipText = preview.skipped > 0
    ? `，跳过 ${skipped} 个不可登录账号`
    : "";
  return `当前未勾选账号，符合已生效筛选条件共 ${matched} 个；预计执行${action} ${executable} 个${skipText}，是否继续？`;
}

export function batchCommandResultMessage(
  operation: TenantAccountBatchOperation,
  result: TenantAccountBatchCommandResult
): string {
  const action = operation === "ONLINE" ? "批量登录" : "批量离线";
  return `${action}请求已提交，已受理 ${result.accepted.toLocaleString("zh-CN")}/${result.requested.toLocaleString("zh-CN")}，跳过 ${result.skipped.toLocaleString("zh-CN")}，失败 ${result.failed.toLocaleString("zh-CN")}`;
}
```

- [ ] **Step 6: Run focused tests and typecheck**

```bash
node --test src/api/account.test.ts src/api/account-mapping.test.ts src/views/account/index/account-batch-operation.test.ts
pnpm typecheck
```

Expected: API payload, pagination stripping, scope precedence, and Chinese confirmation tests pass; typecheck is green.

- [ ] **Step 7: Commit the frontend API slice**

```bash
git add src/api/account.ts src/api/account-mapping.ts src/api/account.test.ts src/api/account-mapping.test.ts src/views/account/index/account-batch-operation.ts src/views/account/index/account-batch-operation.test.ts
git commit -m "feat(account): add filtered batch APIs"
```

---

### Task 7: Separate editable and applied account filters

**Files:**
- Create: `../wheel-saas-pure-web/src/views/account/index/account-query-state.ts`
- Create: `../wheel-saas-pure-web/src/views/account/index/account-query-state.test.ts`
- Modify: `../wheel-saas-pure-web/src/views/account/index/composables/useAccountListPage.ts`

- [ ] **Step 1: Write the failing query-state tests**

```ts
it("does not apply edited filters before a successful request", () => {
  const state = createAccountQueryState({ loginState: 2 });
  const pending = state.begin({ country: "美国", accountState: 3 });
  assert.deepEqual(state.applied(), { loginState: 2 });
  assert.deepEqual(pending.filters, { country: "美国", accountState: 3 });
});

it("only lets the latest successful request replace applied filters", () => {
  const state = createAccountQueryState({ loginState: 2 });
  const oldRequest = state.begin({ country: "印度" });
  const latestRequest = state.begin({ country: "美国" });
  assert.equal(state.commit(oldRequest), false);
  assert.equal(state.commit(latestRequest), true);
  assert.deepEqual(state.applied(), { country: "美国" });
});

it("keeps a defensive copy of applied filters", () => {
  const filters = { loginState: 2 as const };
  const state = createAccountQueryState(filters);
  filters.loginState = 1;
  assert.deepEqual(state.applied(), { loginState: 2 });
});
```

- [ ] **Step 2: Run the focused test and verify failure**

```bash
node --test src/views/account/index/account-query-state.test.ts
```

Expected: module-not-found failure.

- [ ] **Step 3: Implement the latest-success coordinator**

```ts
export interface AccountQueryRequest {
  id: number;
  filters: TenantAccountBatchQuery;
}

export function createAccountQueryState(initial: TenantAccountBatchQuery) {
  let current = { ...initial };
  let latestRequestId = 0;
  return {
    applied: (): TenantAccountBatchQuery => ({ ...current }),
    begin: (filters: TenantAccountBatchQuery): AccountQueryRequest => ({
      id: ++latestRequestId,
      filters: { ...filters }
    }),
    isLatest: (request: AccountQueryRequest): boolean =>
      request.id === latestRequestId,
    commit: (request: AccountQueryRequest): boolean => {
      if (request.id !== latestRequestId) return false;
      current = { ...request.filters };
      return true;
    }
  };
}
```

- [ ] **Step 4: Refactor list loading to use candidate or applied filters explicitly**

In `useAccountListPage.ts`, replace `buildQuery()` with `buildEditingFilters()` returning `toTenantAccountBatchQuery(...)`. Initialize the coordinator from the route group filter. Use one loader:

```ts
async function loadAccountList(
  request: AccountQueryRequest,
  requestedPage: number,
  applyOnSuccess: boolean
): Promise<boolean> {
  loading.value = true;
  try {
    const response = await listTenantAccounts({
      ...request.filters,
      page: requestedPage,
      pageSize: pageSize.value
    });
    if (!queryState.isLatest(request)) return false;
    rows.value = response.list ?? [];
    total.value = response.total ?? 0;
    page.value = requestedPage;
    selectedRows.value = [];
    if (applyOnSuccess) queryState.commit(request);
    return true;
  } catch (error) {
    if (queryState.isLatest(request)) {
      ElMessage.error(apiErrorMessage(error, "账号列表加载失败"));
    }
    return false;
  } finally {
    if (queryState.isLatest(request)) loading.value = false;
  }
}
```

`searchAccounts` begins a request from editing filters and applies only after success. `refreshAccountList` begins a request from `queryState.applied()` and does not change the applied snapshot. `resetSearchForm` clears the form and invokes the same search path. Do not clear the old rows/total/applied filters on a failed search.

- [ ] **Step 5: Run query-state, mapping, and type tests**

```bash
node --test src/views/account/index/account-query-state.test.ts src/api/account-mapping.test.ts
pnpm typecheck
```

Expected: snapshot tests and typecheck pass.

- [ ] **Step 6: Commit the applied-filter state change**

```bash
git add src/views/account/index/account-query-state.ts src/views/account/index/account-query-state.test.ts src/views/account/index/composables/useAccountListPage.ts
git commit -m "feat(account): track applied list filters"
```

---

### Task 8: Integrate preview, confirmation, and dual execution paths into the account page

**Files:**
- Modify: `../wheel-saas-pure-web/src/views/account/index/composables/useAccountListPage.ts`
- Modify: `../wheel-saas-pure-web/src/views/account/index/components/AccountListTable.vue`
- Modify: `../wheel-saas-pure-web/src/views/account/index/components/AccountListTable.test.ts`
- Modify: `../wheel-saas-pure-web/src/views/account/index/index.vue`

- [ ] **Step 1: Add failing source-level label/loading tests**

Extend `AccountListTable.test.ts`:

```ts
it("names and guards the two lifecycle batch actions", () => {
  assert.match(source, />\s*批量登录\s*</);
  assert.match(source, />\s*批量离线\s*</);
  assert.match(source, /batchSubmitting: boolean/);
  assert.match(source, /:loading="batchSubmitting"/);
});
```

- [ ] **Step 2: Run the component test and verify failure**

```bash
node --test src/views/account/index/components/AccountListTable.test.ts
```

Expected: labels/loading assertions fail.

- [ ] **Step 3: Replace selected-only submission with preview-confirm-execute**

Add `batchSubmitting = ref(false)` and one operation function:

```ts
async function submitLifecycleBatch(
  operation: TenantAccountBatchOperation
): Promise<void> {
  if (batchSubmitting.value) return;
  const ids = selectedAccountIds();
  const appliedFilters = queryState.applied();
  const previewRequest = buildBatchPreviewRequest(operation, ids, appliedFilters);
  batchSubmitting.value = true;
  try {
    const preview = await previewTenantAccountBatch(previewRequest);
    if (preview.executable === 0) {
      ElMessage.warning("当前范围内没有可执行账号");
      return;
    }
    await ElMessageBox.confirm(
      batchConfirmMessage(
        operation,
        ids.length,
        Object.keys(appliedFilters).length > 0,
        preview
      ),
      operation === "ONLINE" ? "确认批量登录" : "确认批量离线",
      { confirmButtonText: "继续执行", cancelButtonText: "取消", type: "warning" }
    );
    const result = ids.length > 0
      ? operation === "ONLINE"
        ? await batchOnlineTenantAccounts(ids)
        : await batchOfflineTenantAccounts(ids)
      : operation === "ONLINE"
        ? await batchOnlineTenantAccountsByQuery(appliedFilters)
        : await batchOfflineTenantAccountsByQuery(appliedFilters);
    ElMessage.success(batchCommandResultMessage(operation, result));
    selectedRows.value = [];
    await refreshAccountList();
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(apiErrorMessage(error,
      operation === "ONLINE" ? "批量登录失败" : "批量离线失败"));
  } finally {
    batchSubmitting.value = false;
  }
}
```

Update `handleBatchAction` so `online` and `offline` call this function even when no rows are selected. Keep selection requirements unchanged for move-group, takeover, and delete. Remove batch-only use of `onlineBlockedTip` and `filterOnlineSubmittableAccounts`; retain `singleOnlineBlockedTip` for row actions.

`batchCommandResultMessage` must include accepted/requested, skipped, and failed counts, for example `批量登录请求已提交，已受理 1,190/1,256，跳过 56，失败 10`.

- [ ] **Step 4: Update table labels and duplicate-submit guard**

Add `batchSubmitting` to the table props. Put it on the dropdown button and disable both lifecycle menu entries while it is true:

```vue
<el-button
  :loading="batchSubmitting"
  :disabled="batchSubmitting"
  :icon="useRenderIcon(MoreFilled)"
>
  批量操作
  <span v-if="selectedCount">({{ selectedCount }})</span>
</el-button>
```

Change only the batch menu labels to `批量登录` and `批量离线`; keep row actions as `上线` and `下线`. Pass `:batch-submitting="batchSubmitting"` from `index.vue`.

- [ ] **Step 5: Run focused tests and typecheck**

```bash
node --test src/api/account.test.ts src/api/account-mapping.test.ts src/views/account/index/account-query-state.test.ts src/views/account/index/account-batch-operation.test.ts src/views/account/index/components/AccountListTable.test.ts
pnpm typecheck
```

Expected: all focused tests and typecheck pass.

- [ ] **Step 6: Commit the frontend interaction**

```bash
git add src/views/account/index/composables/useAccountListPage.ts src/views/account/index/components/AccountListTable.vue src/views/account/index/components/AccountListTable.test.ts src/views/account/index/index.vue
git commit -m "feat(account): support filtered batch actions"
```

---

### Task 9: Record the change and run full verification

**Files:**
- Create: `../wheel-saas-pure-web/.harness/changes/account-filtered-batch-lifecycle/summary.md`
- Verify all files from Tasks 1–8.

- [ ] **Step 1: Write the frontend change summary**

Record these exact sections in `summary.md`:

```markdown
# Account filtered batch lifecycle

## Scope
- Selected rows use the existing ID endpoints, up to 2,000 IDs.
- No selection uses the last successfully applied account filters.
- Backend preview supplies matched/executable/skipped counts.

## Safety rules
- Empty IDs never mean all accounts.
- Empty query means all active accounts in the current tenant.
- Online skips banned, unbound, taking-over, and missing-credential accounts.
- Existing online accounts still enter the protocol command path.
```

Add a `## Verification` section only after Steps 2–4 have run. Record each command exactly as executed, its exit status, and the safe local/test environment used for browser acceptance. If a command fails or cannot run, record the failure or environmental blocker instead of marking it passed.

- [ ] **Step 2: Run complete backend verification**

From `armada`:

```bash
cd armada-api
mvn -q -Dtest=AccountBatchQueryDTOTest,AccountBatchLifecycleServiceImplTest,AccountControllerTest,AccountOnlineCommandServiceImplTest test
mvn -q -DskipTests compile
cd ..
xmllint --noout armada-api/src/main/resources/mapper/account/AccountMapper.xml
armada-api/dbtest.sh AccountBatchTargetMapperDbTest
armada-api/dbtest.sh AccountListMapperDbTest
git diff --check
```

Expected: every command exits 0. If `.env` is unavailable for DbTests, record that exact environmental blocker and do not claim database verification passed.

- [ ] **Step 3: Run complete frontend verification**

From `wheel-saas-pure-web`:

```bash
node --test src/api/account.test.ts src/api/account-mapping.test.ts src/views/account/index/account-query-state.test.ts src/views/account/index/account-batch-operation.test.ts src/views/account/index/components/AccountListTable.test.ts
pnpm typecheck
pnpm build
git diff --check
```

Expected: focused tests, TypeScript/Vue typecheck, production build, and whitespace check all exit 0.

- [ ] **Step 4: Perform browser acceptance against a safe local/test backend**

Verify these scenarios without using production data:

1. Search `登录状态 = 离线`, then edit country/status without clicking 查询; batch preview still sends only `loginState=2`.
2. Click 查询 successfully; the next preview uses the new filters.
3. Select rows; preview and execution use `IDS` and the old ID endpoint.
4. Clear selection with active filters; preview and execution use `QUERY` and the new query endpoint.
5. Reset and wait for a successful list reload; no-selection preview sends an empty query and confirmation says “全部”.
6. Cancel the confirmation; no execution request is sent.
7. Confirm an operation; result toast shows accepted, skipped, and failed totals.

Record the environment and observed result in the change summary. Do not connect to a remote environment without first confirming the target environment under the workspace red lines.

- [ ] **Step 5: Commit the frontend verification record**

```bash
git add .harness/changes/account-filtered-batch-lifecycle/summary.md
git commit -m "docs(account): record filtered batch verification"
```

- [ ] **Step 6: Review both repository histories and worktrees**

```bash
git -C /Users/daishuaishuai/IdeaProjects/armada status --short
git -C /Users/daishuaishuai/IdeaProjects/armada log -6 --oneline
git -C /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web status --short
git -C /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web log -6 --oneline
```

Expected: only known pre-existing unrelated worktree entries remain in Armada; feature commits are present in both repositories; no credentials or `.env` files are staged.
