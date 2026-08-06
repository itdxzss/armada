# JSON Import Protocol ID Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure newly imported JSON accounts persist `protocol_id = 'WEB'` while preserving existing SIX and PARAMS behavior.

**Architecture:** Keep protocol selection inside the existing `AccountImportRowWriter.buildAccount` creation boundary. Add one regression test that captures the real `Account` passed to `AccountMapper`, observe it fail while JSON imports leave the field null, then add the smallest explicit JSON branch. The previously approved SQL remains an operator-run test-environment correction and is never executed by this plan.

**Tech Stack:** Java 17, Spring Boot 3.3.5, JUnit 5, Mockito, AssertJ, Maven

---

### Task 1: Persist WEB for JSON account imports

**Files:**
- Modify: `armada-api/src/test/java/com/armada/account/service/impl/AccountImportRowWriterTest.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountImportRowWriter.java:101`

- [ ] **Step 1: Write the failing JSON import regression test**

Add this test beside `writeOne_marksSixImportAsAndroidProtocol`:

```java
@Test
void writeOne_marksJsonImportAsWebProtocol() {
    when(accountMapper.insert(any(Account.class))).thenAnswer(invocation -> {
        Account account = invocation.getArgument(0);
        account.setId(124L);
        return 1;
    });
    when(stateMapper.insert(any())).thenReturn(1);
    when(credentialMapper.insert(any())).thenReturn(1);
    AccountImportRowWriter writer = new AccountImportRowWriter(
            accountMapper, stateMapper, credentialMapper);

    ParsedEntry entry = new ParsedEntry();
    var data = new ObjectMapper().createObjectNode();
    data.put("me", "json-account");
    entry.setData(data);

    Long accountId = writer.writeOne("27612057409", entry, 9L,
            new AccountImportDTO(9L, ImportFormat.JSON.getCode(), 1, 1,
                    "ZA", null, null, "account.json"));

    ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
    ArgumentCaptor<AccountCredential> credentialCaptor =
            ArgumentCaptor.forClass(AccountCredential.class);
    verify(accountMapper).insert(accountCaptor.capture());
    verify(credentialMapper).insert(credentialCaptor.capture());
    assertThat(accountId).isEqualTo(124L);
    assertThat(accountCaptor.getValue().getProtocolId())
            .isEqualTo(ProtocolBackend.WEB.name());
    assertThat(credentialCaptor.getValue().getCredFormat())
            .isEqualTo(ImportFormat.JSON.getCode());
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `armada-api/`:

```bash
mvn -Dtest='AccountImportRowWriterTest#writeOne_marksJsonImportAsWebProtocol' test
```

Expected: FAIL because `Account.protocolId` is `null` instead of `WEB`. A compilation or fixture error is not an acceptable RED result; correct the test until the assertion fails for this reason.

- [ ] **Step 3: Add the minimal JSON protocol assignment**

Replace the existing protocol assignment block in `AccountImportRowWriter.buildAccount` with:

```java
if (importFormat == ImportFormat.SIX.getCode()) {
    a.setProtocolId(ProtocolBackend.ANDROID.name());
} else if (importFormat == ImportFormat.JSON.getCode()) {
    a.setProtocolId(ProtocolBackend.WEB.name());
}
```

Do not add a default branch: `PARAMS` must remain unchanged under the approved scope.

- [ ] **Step 4: Run the focused test class and verify GREEN**

Run from `armada-api/`:

```bash
mvn -Dtest=AccountImportRowWriterTest test
```

Expected: PASS for both JSON→WEB and SIX→ANDROID cases.

- [ ] **Step 5: Run adjacent account protocol tests**

Run from `armada-api/`:

```bash
mvn -Dtest='AccountImportRowWriterTest,AccountProtocolLookupServiceTest' test
```

Expected: BUILD SUCCESS with no test failures.

- [ ] **Step 6: Review the scoped diff**

Run from the repository root:

```bash
git diff --check -- \
  armada-api/src/main/java/com/armada/account/service/impl/AccountImportRowWriter.java \
  armada-api/src/test/java/com/armada/account/service/impl/AccountImportRowWriterTest.java
git diff -- armada-api/src/main/java/com/armada/account/service/impl/AccountImportRowWriter.java \
  armada-api/src/test/java/com/armada/account/service/impl/AccountImportRowWriterTest.java
```

Expected: only the JSON regression test and the explicit JSON→WEB assignment appear; existing unrelated working-tree changes remain untouched.

- [ ] **Step 7: Commit the implementation**

```bash
git add armada-api/src/main/java/com/armada/account/service/impl/AccountImportRowWriter.java \
  armada-api/src/test/java/com/armada/account/service/impl/AccountImportRowWriterTest.java
git commit -m "fix(account): persist web protocol for JSON imports"
```

### Task 2: Preserve the operator-run SQL handoff

**Files:**
- Reference: `docs/superpowers/specs/2026-08-06-json-import-protocol-id-design.md`

- [ ] **Step 1: Confirm the SQL scope without executing it**

Verify the design document still contains an operator-run transaction whose update predicate is exactly:

```sql
WHERE protocol_id IS NULL;
```

Expected: the SQL updates `protocol_id` to `WEB`, refreshes `updated_at`, reports `ROW_COUNT()`, and no SSH, MySQL client, deployment, or remote mutation command is run by Codex.
