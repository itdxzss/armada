# Account Batch Online Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a narrow armada backend batch online endpoint that accepts up to 500 account IDs, allocates proxies locally, and submits one protocol-layer `/v1/accounts/online/batch` HTTP command.

**Architecture:** Keep the existing boundaries: account domain orchestrates account/credential/proxy allocation, resource domain owns proxy binding, and platform/protocol hides HTTP wire details. This slice sends one batch HTTP request to `armada-protocol`; it does not wait for Kafka online state and does not implement multi-batch scheduling for more than 500 accounts.

**Tech Stack:** Java 17, Spring Boot 3.3.5, RestClient, MyBatis XML, JUnit 5, Mockito, MockMvc, real DbTest for mapper XML.

---

## Scope

In scope:
- `POST /api/accounts/batch-online` with `AccountIdsDTO`.
- Max 500 account IDs per request.
- Whole-request validation for missing/deleted accounts, missing credentials, duplicate IDs, and empty list.
- Local DB proxy allocation by reusing `IpProxyService.allocateOnlineEndpoint(accountId)` per account.
- One protocol HTTP call through `AccountLifecyclePort.onlineBatch(...)`.
- Compensation release for allocations when protocol call fails or an item is not accepted.
- Safe logs without credential JSON or proxy password.

Out of scope:
- More than 500 IDs splitting or queueing.
- Waiting for Kafka state callback.
- `proxy_failed` automatic IP switch.
- Multi-worker `remote[]` redispatch. If protocol returns `remote[]`, this slice reports it and releases the local allocation.
- Replacing the RestClient request factory with a pooled Apache/Jetty client. This slice sends one HTTP request per batch; connection pooling can be measured and changed separately.

## File Structure

- Modify `armada-api/src/main/java/com/armada/platform/protocol/port/AccountLifecyclePort.java`
  - Add the batch lifecycle port method.
- Create protocol model records under `armada-api/src/main/java/com/armada/platform/protocol/model/command/` and `.../result/`
  - `BatchOnlineCommand`, `BatchOnlineCommandItem`
  - `BatchOnlineAccepted`, `BatchOnlineSummary`, `BatchOnlineItemResult`, `BatchOnlineRemoteRoute`, `BatchOnlineResultStatus`
- Modify `armada-api/src/main/java/com/armada/platform/protocol/http/account/HttpAccountLifecycleAdapter.java`
  - Translate batch command/result to protocol wire DTOs.
- Modify `armada-api/src/main/java/com/armada/account/service/AccountOnlineService.java`
  - Add `onlineBatch(AccountBatchOnlinePlan plan)`.
- Create `armada-api/src/main/java/com/armada/account/service/AccountBatchOnlinePlan.java`
  - Account-domain batch plan containing `List<AccountOnlinePlan>` plus `maxWaitMs`.
- Modify `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineServiceImpl.java`
  - Validate and translate batch plans to the protocol port.
- Modify `armada-api/src/main/java/com/armada/account/service/AccountOnlineCommandService.java`
  - Add `batchOnline(List<Long> accountIds)`.
- Modify `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java`
  - Batch account/credential validation, proxy allocation, batch protocol call, compensation release, VO mapping.
- Modify `armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java`
  - Add `selectActiveByIds(List<Long> ids)`.
- Modify `armada-api/src/main/resources/mapper/account/AccountMapper.xml`
  - Add batch active-account query.
- Modify `armada-api/src/main/java/com/armada/account/mapper/AccountCredentialMapper.java`
  - Add `selectByAccountIds(List<Long> accountIds)`.
- Modify `armada-api/src/main/resources/mapper/account/AccountCredentialMapper.xml`
  - Add batch active-credential query.
- Create `armada-api/src/main/java/com/armada/account/model/vo/AccountBatchOnlineVO.java`
  - Batch summary and item-level results for frontend.
- Create `armada-api/src/main/java/com/armada/account/model/vo/AccountBatchOnlineItemVO.java`
  - Item result mapping by armada account ID.
- Modify `armada-api/src/main/java/com/armada/account/controller/AccountController.java`
  - Add `POST /api/accounts/batch-online`.
- Tests:
  - `armada-api/src/test/java/com/armada/platform/protocol/http/account/HttpAccountLifecycleAdapterTest.java`
  - `armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineServiceImplTest.java`
  - `armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java`
  - `armada-api/src/test/java/com/armada/account/controller/AccountControllerTest.java`
  - `armada-api/src/test/java/com/armada/account/mapper/AccountListMapperDbTest.java`
  - `armada-api/src/test/java/com/armada/account/mapper/AccountImportWriteMapperDbTest.java`
- Create `.harness/changes/account-batch-online/summary.md`
  - Record scope, API, DB, verification, rollout notes.

---

### Task 1: Protocol Batch Port Contract

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/port/AccountLifecyclePort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/command/BatchOnlineCommand.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/command/BatchOnlineCommandItem.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/result/BatchOnlineAccepted.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/result/BatchOnlineSummary.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/result/BatchOnlineItemResult.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/result/BatchOnlineRemoteRoute.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/result/BatchOnlineResultStatus.java`
- Test: `armada-api/src/test/java/com/armada/platform/protocol/port/AccountLifecyclePortContractTest.java`

- [ ] **Step 1: Write the failing contract test**

Add a test that compiles only when the new batch port method and model records exist:

```java
@Test
void onlineBatchContractUsesPortScopedTypesAndProtocolAccountIds() {
    OnlineCommand onlineCommand = new OnlineCommand(
            CredentialFormat.BAILEYS_JSON,
            "{\"creds\":{},\"keys\":{}}",
            new ProxyDescriptor("socks5", "socks5://proxy.internal:1080", "sticky-001", "IN"));
    BatchOnlineCommand command = new BatchOnlineCommand(
            List.of(new BatchOnlineCommandItem("acc_001", onlineCommand)),
            60_000);
    BatchOnlineAccepted accepted = new BatchOnlineAccepted(
            Instant.parse("2026-06-27T10:00:00Z"),
            120L,
            new BatchOnlineSummary(1, 1, 0, 1, 0, 0, 0),
            List.of(new BatchOnlineItemResult("acc_001", BatchOnlineResultStatus.ACCEPTED, null, null)),
            List.of());
    AccountLifecyclePort port = new AccountLifecyclePort() {
        @Override
        public OnlineAccepted online(String protocolAccountId, OnlineCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BatchOnlineAccepted onlineBatch(BatchOnlineCommand command) {
            return accepted;
        }
    };

    assertThat(port.onlineBatch(command)).isSameAs(accepted);
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
mvn -Dtest=AccountLifecyclePortContractTest test
```

Expected: compilation fails because `BatchOnlineCommand` and `onlineBatch(...)` do not exist.

- [ ] **Step 3: Add the minimal contract records**

Create:

```java
public record BatchOnlineCommand(List<BatchOnlineCommandItem> items, int maxWaitMs) {
}
```

```java
public record BatchOnlineCommandItem(String protocolAccountId, OnlineCommand command) {
}
```

```java
public enum BatchOnlineResultStatus {
    ACCEPTED,
    TIMEOUT,
    PROXY_REQUIRED,
    ERROR,
    REMOTE
}
```

```java
public record BatchOnlineSummary(
        int requested,
        int local,
        int remote,
        int accepted,
        int timeout,
        int proxyRequired,
        int error
) {
}
```

```java
public record BatchOnlineItemResult(
        String protocolAccountId,
        BatchOnlineResultStatus result,
        Integer retryAfterMs,
        String error
) {
}
```

```java
public record BatchOnlineRemoteRoute(
        String protocolAccountId,
        String ownerWorkerId,
        String ownerEndpoint,
        String note
) {
}
```

```java
public record BatchOnlineAccepted(
        Instant requestedAt,
        long elapsedMs,
        BatchOnlineSummary summary,
        List<BatchOnlineItemResult> results,
        List<BatchOnlineRemoteRoute> remote
) {
}
```

Modify `AccountLifecyclePort`:

```java
BatchOnlineAccepted onlineBatch(BatchOnlineCommand command);
```

- [ ] **Step 4: Run the contract test**

Run:

```bash
mvn -Dtest=AccountLifecyclePortContractTest test
```

Expected: PASS.

---

### Task 2: Protocol HTTP Adapter Batch Call

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/account/HttpAccountLifecycleAdapter.java`
- Test: `armada-api/src/test/java/com/armada/platform/protocol/http/account/HttpAccountLifecycleAdapterTest.java`

- [ ] **Step 1: Write the failing adapter test**

Add a test proving one HTTP request is sent to `/v1/accounts/online/batch` with two items:

```java
@Test
void onlineBatchPostsOneBatchRequestAndMapsAcceptedResponse() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    AccountLifecyclePort port = new HttpAccountLifecycleAdapter(new ProtocolHttpExecutor(builder.build()));
    BatchOnlineCommand command = new BatchOnlineCommand(List.of(
            new BatchOnlineCommandItem("acc_001", new OnlineCommand(
                    CredentialFormat.BAILEYS_JSON,
                    "{\"creds\":{\"noiseKey\":\"n1\"},\"keys\":{}}",
                    new ProxyDescriptor("socks5", "socks5://user:pass@proxy-a:1080", "sticky-001", "IN"))),
            new BatchOnlineCommandItem("acc_002", new OnlineCommand(
                    CredentialFormat.PARAMS,
                    "{\"login\":\"raw\"}",
                    new ProxyDescriptor("http", "http://user:pass@proxy-b:8080", "sticky-002", "SG")))
    ), 60_000);

    server.expect(requestTo("http://protocol.internal/v1/accounts/online/batch"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""
                    {
                      "maxWaitMs": 60000,
                      "items": [
                        {
                          "accountId": "acc_001",
                          "format": "baileys_json",
                          "credential": {"creds": {"noiseKey": "n1"}, "keys": {}},
                          "proxy": {
                            "protocol": "socks5",
                            "url": "socks5://user:pass@proxy-a:1080",
                            "sessionId": "sticky-001",
                            "country": "IN"
                          }
                        },
                        {
                          "accountId": "acc_002",
                          "format": "params",
                          "credential": {"login": "raw"},
                          "proxy": {
                            "protocol": "http",
                            "url": "http://user:pass@proxy-b:8080",
                            "sessionId": "sticky-002",
                            "country": "SG"
                          }
                        }
                      ]
                    }
                    """))
            .andRespond(withSuccess("""
                    {
                      "requestedAt": "2026-06-27T10:00:00Z",
                      "elapsedMs": 80,
                      "summary": {
                        "requested": 2,
                        "local": 2,
                        "remote": 0,
                        "accepted": 1,
                        "timeout": 1,
                        "proxyRequired": 0,
                        "error": 0
                      },
                      "results": [
                        {"accountId": "acc_001", "result": "accepted"},
                        {"accountId": "acc_002", "result": "timeout", "retryAfterMs": 5000}
                      ],
                      "remote": []
                    }
                    """, MediaType.APPLICATION_JSON));

    BatchOnlineAccepted result = port.onlineBatch(command);

    assertThat(result.summary().requested()).isEqualTo(2);
    assertThat(result.summary().accepted()).isEqualTo(1);
    assertThat(result.results()).extracting(BatchOnlineItemResult::result)
            .containsExactly(BatchOnlineResultStatus.ACCEPTED, BatchOnlineResultStatus.TIMEOUT);
    server.verify();
}
```

- [ ] **Step 2: Run the adapter test to verify it fails**

Run:

```bash
mvn -Dtest=HttpAccountLifecycleAdapterTest test
```

Expected: compilation fails because `onlineBatch(...)` is not implemented.

- [ ] **Step 3: Implement adapter method**

Implementation outline:

```java
private static final String ONLINE_BATCH_URI = "/v1/accounts/online/batch";

@Override
public BatchOnlineAccepted onlineBatch(BatchOnlineCommand command) {
    BatchOnlineCommand safeCommand = requireBatchCommand(command);
    BatchOnlineRequest request = new BatchOnlineRequest(
            safeCommand.items().stream().map(HttpAccountLifecycleAdapter::toBatchItemRequest).toList(),
            safeCommand.maxWaitMs());
    BatchOnlineResponse response = httpExecutor.postTyped(
            ONLINE_BATCH_URI,
            request,
            BatchOnlineResponse.class);
    return toBatchAccepted(response);
}
```

Add private wire records inside the adapter:

```java
private record BatchOnlineRequest(List<BatchOnlineItemRequest> items, int maxWaitMs) {
}

private record BatchOnlineItemRequest(
        String accountId,
        String format,
        Map<String, Object> credential,
        ProxyDescriptor proxy
) {
}

private record BatchOnlineResponse(
        Instant requestedAt,
        long elapsedMs,
        BatchOnlineSummaryResponse summary,
        List<BatchOnlineItemResponse> results,
        List<BatchOnlineRemoteResponse> remote
) {
}
```

Map wire `result` values:

```java
private static BatchOnlineResultStatus toBatchStatus(String result) {
    if ("accepted".equals(result)) {
        return BatchOnlineResultStatus.ACCEPTED;
    }
    if ("timeout".equals(result)) {
        return BatchOnlineResultStatus.TIMEOUT;
    }
    if ("proxy_required".equals(result)) {
        return BatchOnlineResultStatus.PROXY_REQUIRED;
    }
    return BatchOnlineResultStatus.ERROR;
}
```

- [ ] **Step 4: Run adapter tests**

Run:

```bash
mvn -Dtest=HttpAccountLifecycleAdapterTest test
```

Expected: PASS.

---

### Task 3: Account Batch Online Service

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/service/AccountOnlineService.java`
- Create: `armada-api/src/main/java/com/armada/account/service/AccountBatchOnlinePlan.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineServiceImplTest.java`

- [ ] **Step 1: Write the failing service test**

Add a test that proves `AccountOnlineServiceImpl` resolves proxies and delegates once to the batch port:

```java
@Test
void onlineBatch_resolvesAllProxiesAndDelegatesOneBatchCommandToProtocolPort() {
    AccountOnlinePlan first = new AccountOnlinePlan(
            "acc_001",
            CredentialFormat.BAILEYS_JSON,
            "{\"creds\":{},\"keys\":{}}",
            new ProxyEndpoint(ProxyEndpoint.PROTOCOL_SOCKS5, "proxy-a", 1080,
                    new ProxyCredentials("u1", "p1"), "IN"));
    AccountOnlinePlan second = new AccountOnlinePlan(
            "acc_002",
            CredentialFormat.PARAMS,
            "{\"login\":\"raw\"}",
            new ProxyEndpoint(ProxyEndpoint.PROTOCOL_HTTP, "proxy-b", 8080,
                    new ProxyCredentials("u2", "p2"), "SG"));
    BatchOnlineAccepted accepted = new BatchOnlineAccepted(
            Instant.parse("2026-06-27T10:00:00Z"),
            80L,
            new BatchOnlineSummary(2, 2, 0, 2, 0, 0, 0),
            List.of(
                    new BatchOnlineItemResult("acc_001", BatchOnlineResultStatus.ACCEPTED, null, null),
                    new BatchOnlineItemResult("acc_002", BatchOnlineResultStatus.ACCEPTED, null, null)),
            List.of());
    when(accountLifecyclePort.onlineBatch(any(BatchOnlineCommand.class))).thenReturn(accepted);

    BatchOnlineAccepted result = service.onlineBatch(new AccountBatchOnlinePlan(List.of(first, second), 60_000));

    assertThat(result).isSameAs(accepted);
    ArgumentCaptor<BatchOnlineCommand> captor = ArgumentCaptor.forClass(BatchOnlineCommand.class);
    verify(accountLifecyclePort).onlineBatch(captor.capture());
    assertThat(captor.getValue().items()).hasSize(2);
    assertThat(captor.getValue().items()).extracting(BatchOnlineCommandItem::protocolAccountId)
            .containsExactly("acc_001", "acc_002");
}
```

- [ ] **Step 2: Run the service test to verify it fails**

Run:

```bash
mvn -Dtest=AccountOnlineServiceImplTest test
```

Expected: compilation fails because `AccountBatchOnlinePlan` and `onlineBatch(...)` do not exist.

- [ ] **Step 3: Add account-domain batch plan**

Create:

```java
public record AccountBatchOnlinePlan(
        List<AccountOnlinePlan> items,
        int maxWaitMs
) {
}
```

Modify `AccountOnlineService`:

```java
BatchOnlineAccepted onlineBatch(AccountBatchOnlinePlan plan);
```

- [ ] **Step 4: Implement service validation and delegation**

Rules:
- `plan == null` → `BusinessException(ErrorCode.VALIDATION, "账号批量上线计划不能为空")`
- `items == null || items.isEmpty()` → validation
- `items.size() > 500` → validation
- Reuse the same validation path as single `online(...)` for protocol ID, credential format, credential JSON, and proxy endpoint.
- Build one `BatchOnlineCommand`.
- Call `accountLifecyclePort.onlineBatch(...)` exactly once.

- [ ] **Step 5: Run service tests**

Run:

```bash
mvn -Dtest=AccountOnlineServiceImplTest test
```

Expected: PASS.

---

### Task 4: Batch Account and Credential Lookup

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/account/mapper/AccountCredentialMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountCredentialMapper.xml`
- Test: `armada-api/src/test/java/com/armada/account/mapper/AccountListMapperDbTest.java`
- Test: `armada-api/src/test/java/com/armada/account/mapper/AccountImportWriteMapperDbTest.java`

- [ ] **Step 1: Write failing DbTests**

Add to `AccountListMapperDbTest`:

```java
@Test
void selectActiveByIds_returnsOnlyRequestedActiveAccounts() {
    long now = System.currentTimeMillis();
    Account first = insertAccount("86135001" + (now % 10000L), now);
    Account second = insertAccount("86135002" + (now % 10000L), now);

    List<Account> rows = accountMapper.selectActiveByIds(List.of(first.getId(), second.getId()));

    assertThat(rows).extracting(Account::getId).containsExactlyInAnyOrder(first.getId(), second.getId());
}
```

Add to `AccountImportWriteMapperDbTest` after existing credential insert setup:

```java
@Test
void selectByAccountIds_returnsActiveCredentialsForRequestedAccounts() {
    long now = System.currentTimeMillis();
    Account first = insertAccount("86136001" + (now % 10000L), now);
    Account second = insertAccount("86136002" + (now % 10000L), now);
    insertCredential(first.getId(), first.getWsPhone(), now);
    insertCredential(second.getId(), second.getWsPhone(), now);

    List<AccountCredential> rows = credentialMapper.selectByAccountIds(List.of(first.getId(), second.getId()));

    assertThat(rows).extracting(AccountCredential::getAccountId)
            .containsExactlyInAnyOrder(first.getId(), second.getId());
}
```

- [ ] **Step 2: Run DbTests to verify they fail**

Run:

```bash
./dbtest.sh 'AccountListMapperDbTest#selectActiveByIds_returnsOnlyRequestedActiveAccounts,AccountImportWriteMapperDbTest#selectByAccountIds_returnsActiveCredentialsForRequestedAccounts'
```

Expected: compilation fails because mapper methods do not exist.

- [ ] **Step 3: Add mapper methods**

`AccountMapper.java`:

```java
List<Account> selectActiveByIds(@Param("ids") List<Long> ids);
```

`AccountMapper.xml`:

```xml
<select id="selectActiveByIds" resultType="com.armada.account.model.entity.Account">
  <if test="ids != null and ids.size() &gt; 0">
  SELECT * FROM account
  WHERE deleted_at IS NULL
    AND id IN
  <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
  </if>
  <if test="ids == null or ids.size() == 0">
  SELECT NULL AS id FROM DUAL WHERE 1=0
  </if>
</select>
```

`AccountCredentialMapper.java`:

```java
List<AccountCredential> selectByAccountIds(@Param("accountIds") List<Long> accountIds);
```

`AccountCredentialMapper.xml`:

```xml
<select id="selectByAccountIds" resultType="com.armada.account.model.entity.AccountCredential">
  <if test="accountIds != null and accountIds.size() &gt; 0">
  SELECT * FROM account_credential
  WHERE deleted_at IS NULL
    AND account_id IN
  <foreach collection="accountIds" item="i" open="(" separator="," close=")">#{i}</foreach>
  </if>
  <if test="accountIds == null or accountIds.size() == 0">
  SELECT NULL AS id FROM DUAL WHERE 1=0
  </if>
</select>
```

- [ ] **Step 4: Run DbTests**

Run:

```bash
./dbtest.sh 'AccountListMapperDbTest#selectActiveByIds_returnsOnlyRequestedActiveAccounts,AccountImportWriteMapperDbTest#selectByAccountIds_returnsActiveCredentialsForRequestedAccounts'
```

Expected: PASS.

---

### Task 5: Batch Online Command Orchestration

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/service/AccountOnlineCommandService.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/account/model/vo/AccountBatchOnlineVO.java`
- Create: `armada-api/src/main/java/com/armada/account/model/vo/AccountBatchOnlineItemVO.java`
- Test: `armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java`

- [ ] **Step 1: Write failing orchestration tests**

Add success-path test:

```java
@Test
void batchOnline_validAccounts_allocatesProxiesAndCallsProtocolBatchOnce() {
    Account first = onlineAccount(100L, "acc_001");
    Account second = onlineAccount(101L, "acc_002");
    AccountCredential firstCredential = onlineCredential(100L, 2, "{\"creds\":{},\"keys\":{}}");
    AccountCredential secondCredential = onlineCredential(101L, 3, "{\"login\":\"raw\"}");
    when(accountMapper.selectActiveByIds(List.of(100L, 101L))).thenReturn(List.of(first, second));
    when(credentialMapper.selectByAccountIds(List.of(100L, 101L))).thenReturn(List.of(firstCredential, secondCredential));
    when(ipProxyService.allocateOnlineEndpoint(100L)).thenReturn(new IpProxyAllocation(7L, onlineEndpoint()));
    when(ipProxyService.allocateOnlineEndpoint(101L)).thenReturn(new IpProxyAllocation(8L, onlineEndpoint()));
    when(accountOnlineService.onlineBatch(any(AccountBatchOnlinePlan.class))).thenReturn(new BatchOnlineAccepted(
            Instant.parse("2026-06-27T10:00:00Z"),
            90L,
            new BatchOnlineSummary(2, 2, 0, 2, 0, 0, 0),
            List.of(
                    new BatchOnlineItemResult("acc_001", BatchOnlineResultStatus.ACCEPTED, null, null),
                    new BatchOnlineItemResult("acc_002", BatchOnlineResultStatus.ACCEPTED, null, null)),
            List.of()));

    AccountBatchOnlineVO result = service.batchOnline(List.of(100L, 101L));

    ArgumentCaptor<AccountBatchOnlinePlan> captor = ArgumentCaptor.forClass(AccountBatchOnlinePlan.class);
    verify(accountOnlineService).onlineBatch(captor.capture());
    assertThat(captor.getValue().items()).hasSize(2);
    assertThat(result.requested()).isEqualTo(2);
    assertThat(result.accepted()).isEqualTo(2);
    verify(ipProxyService, never()).releaseOnlineAllocation(any(), any());
}
```

Add compensation-path test:

```java
@Test
void batchOnline_itemTimeout_releasesOnlyThatItemAllocation() {
    Account first = onlineAccount(100L, "acc_001");
    Account second = onlineAccount(101L, "acc_002");
    when(accountMapper.selectActiveByIds(List.of(100L, 101L))).thenReturn(List.of(first, second));
    when(credentialMapper.selectByAccountIds(List.of(100L, 101L))).thenReturn(List.of(
            onlineCredential(100L, 2, "{\"creds\":{},\"keys\":{}}"),
            onlineCredential(101L, 2, "{\"creds\":{},\"keys\":{}}")));
    when(ipProxyService.allocateOnlineEndpoint(100L)).thenReturn(new IpProxyAllocation(7L, onlineEndpoint()));
    when(ipProxyService.allocateOnlineEndpoint(101L)).thenReturn(new IpProxyAllocation(8L, onlineEndpoint()));
    when(accountOnlineService.onlineBatch(any(AccountBatchOnlinePlan.class))).thenReturn(new BatchOnlineAccepted(
            Instant.parse("2026-06-27T10:00:00Z"),
            90L,
            new BatchOnlineSummary(2, 2, 0, 1, 1, 0, 0),
            List.of(
                    new BatchOnlineItemResult("acc_001", BatchOnlineResultStatus.ACCEPTED, null, null),
                    new BatchOnlineItemResult("acc_002", BatchOnlineResultStatus.TIMEOUT, 5000, null)),
            List.of()));

    AccountBatchOnlineVO result = service.batchOnline(List.of(100L, 101L));

    assertThat(result.accepted()).isEqualTo(1);
    assertThat(result.timeout()).isEqualTo(1);
    verify(ipProxyService).releaseOnlineAllocation(101L, 8L);
    verify(ipProxyService, never()).releaseOnlineAllocation(100L, 7L);
}
```

Add validation tests:
- empty list throws `BusinessException(ErrorCode.VALIDATION, "账号 ID 列表不能为空")`
- more than 500 IDs throws validation
- duplicate ID throws validation
- missing/deleted account throws before proxy allocation
- missing credential throws before proxy allocation
- protocol exception releases every allocated proxy and rethrows original exception

- [ ] **Step 2: Run orchestration tests to verify they fail**

Run:

```bash
mvn -Dtest=AccountOnlineCommandServiceImplTest test
```

Expected: compilation fails because batch VO/methods do not exist.

- [ ] **Step 3: Add account VO records**

```java
public record AccountBatchOnlineItemVO(
        Long accountId,
        String protocolAccountId,
        String result,
        boolean accepted,
        Integer retryAfterMs,
        String error,
        String ownerWorkerId,
        String ownerEndpoint
) {
}
```

```java
public record AccountBatchOnlineVO(
        int requested,
        int local,
        int remote,
        int accepted,
        int timeout,
        int proxyRequired,
        int error,
        long elapsedMs,
        List<AccountBatchOnlineItemVO> items
) {
}
```

- [ ] **Step 4: Add command-service interface method**

```java
AccountBatchOnlineVO batchOnline(List<Long> accountIds);
```

- [ ] **Step 5: Implement batch orchestration**

Rules:
- Validate list `1..500`.
- Reject duplicate IDs before any DB call.
- Load accounts with `selectActiveByIds`.
- Preserve request order by building `Map<Long, Account>` and iterating the original ID list.
- Require every requested account to exist and have `protocolAccountId`.
- Load credentials with `selectByAccountIds`.
- Require every requested account to have credential.
- Allocate one proxy per account with `IpProxyService.allocateOnlineEndpoint(accountId)`.
- Build `AccountBatchOnlinePlan`.
- Call `accountOnlineService.onlineBatch(...)` once.
- Map protocol `protocolAccountId` results back to armada `accountId`.
- Release allocation for every item result other than `ACCEPTED`.
- Release allocation for every `remote[]` item because this slice does not redispatch to `ownerEndpoint`.
- If `accountOnlineService.onlineBatch(...)` throws, release all allocated proxies and rethrow the original exception.

Logging:
- Start: `账号批量上线开始 requested={}`
- Before protocol: `账号批量上线调用协议层 requested={} allocated={} credentialTotalLength={}`
- Done: `账号批量上线协议层返回 requested={} accepted={} timeout={} proxyRequired={} error={} remote={} elapsedMs={}`
- No credential JSON or proxy password in logs.

- [ ] **Step 6: Run orchestration tests**

Run:

```bash
mvn -Dtest=AccountOnlineCommandServiceImplTest test
```

Expected: PASS.

---

### Task 6: Controller Endpoint

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/controller/AccountController.java`
- Test: `armada-api/src/test/java/com/armada/account/controller/AccountControllerTest.java`

- [ ] **Step 1: Write failing MockMvc test**

```java
@Test
void postBatchOnline_delegatesToCommandServiceAndReturnsApiResponse() throws Exception {
    AccountBatchOnlineVO vo = new AccountBatchOnlineVO(
            2, 2, 0, 2, 0, 0, 0, 88L,
            List.of(
                    new AccountBatchOnlineItemVO(100L, "acc_001", "ACCEPTED", true, null, null, "worker-a", null),
                    new AccountBatchOnlineItemVO(101L, "acc_002", "ACCEPTED", true, null, null, "worker-a", null)));
    when(accountOnlineCommandService.batchOnline(List.of(100L, 101L))).thenReturn(vo);

    mockMvc.perform(post("/api/accounts/batch-online")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ids\":[100,101]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.requested").value(2))
            .andExpect(jsonPath("$.data.accepted").value(2))
            .andExpect(jsonPath("$.data.items[0].accountId").value(100));

    verify(accountOnlineCommandService).batchOnline(List.of(100L, 101L));
}
```

- [ ] **Step 2: Run controller test to verify it fails**

Run:

```bash
mvn -Dtest=AccountControllerTest test
```

Expected: compilation or assertion failure because endpoint is not implemented.

- [ ] **Step 3: Add controller method**

```java
@PostMapping("/batch-online")
public ApiResponse<AccountBatchOnlineVO> batchOnline(@RequestBody AccountIdsDTO request) {
    return ApiResponse.ok(accountOnlineCommandService.batchOnline(request.ids()));
}
```

- [ ] **Step 4: Run controller test**

Run:

```bash
mvn -Dtest=AccountControllerTest test
```

Expected: PASS.

---

### Task 7: Harness Summary and Verification

**Files:**
- Create: `.harness/changes/account-batch-online/summary.md`

- [ ] **Step 1: Write change summary**

Use this content:

```markdown
# 变更记录：账号批量上线

- 日期 / 分支 / worktree: 2026-06-27 / main / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 批量上线最多 500 个账号，armada 一次请求协议层 batch 接口，不逐个账号 HTTP 调协议
- 状态: 待验证

## 目标（一句话）

`POST /api/accounts/batch-online` 接收最多 500 个账号，后端校验账号和凭据、分配代理，然后一次性投递协议层 `/v1/accounts/online/batch`。

## 关键设计决策

- HTTP 返回只代表批量上线命令已受理，不代表账号已经 ONLINE。
- 最终登录状态仍等 Kafka 异步回填。
- 这刀不支持超过 500 自动分片，不做 ownerEndpoint 远端重投，不等 Kafka。
- 协议调用必须是一次 batch HTTP，不允许循环逐个账号调用 `/online`。
- 本地代理分配复用 `IpProxyService.allocateOnlineEndpoint(accountId)`，每个账号一段短事务。

## 验证

- 目标单测：执行本计划 Task 7 Step 2，并记录命令、退出码和通过用例数。
- 真库 DbTest：执行本计划 Task 7 Step 3，并记录命令、退出码和 Flyway/SQL 结果。
- 空白检查：执行本计划 Task 7 Step 4，结果必须无输出。

## 遗留 / 跟进

- 超过 500 的分片调度。
- ownerEndpoint remote 重投。
- HTTP 连接池压测和必要时替换底层 request factory。
- Kafka 回填失败后的代理释放/换 IP。
```

- [ ] **Step 2: Run targeted unit tests**

Run:

```bash
mvn -Dtest=AccountControllerTest,AccountOnlineCommandServiceImplTest,AccountOnlineServiceImplTest,HttpAccountLifecycleAdapterTest,AccountLifecyclePortContractTest test
```

Expected: PASS.

- [ ] **Step 3: Run mapper DbTests**

Run:

```bash
./dbtest.sh 'AccountListMapperDbTest#selectActiveByIds_returnsOnlyRequestedActiveAccounts,AccountImportWriteMapperDbTest#selectByAccountIds_returnsActiveCredentialsForRequestedAccounts,IpProxyMapperDbTest'
```

Expected: PASS.

- [ ] **Step 4: Run diff whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 5: Review scope before final**

Confirm:
- No protocol-side code changed in this armada slice.
- No per-account HTTP loop was added.
- No Kafka waiting or status overwrite was added.
- No credential JSON or proxy password appears in logs.
- New mapper XML has DbTest coverage.

---

## Self-Review

- Spec coverage: the plan covers armada batch endpoint, one protocol batch call, local proxy allocation, compensation release, no Kafka wait, and max 500.
- Placeholder scan: no unfinished-marker text remains; every out-of-scope item is explicitly deferred.
- Type consistency: protocol batch models use `BatchOnline*`; account-domain plan uses `AccountBatchOnlinePlan`; account API response uses `AccountBatchOnlineVO`.
- Scope check: this is one backend slice. Frontend button, >500 scheduling, remote redispatch, and HTTP connection pool replacement are separate slices.
