# Five-Part and Six-Part Account Import Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep `importFormat=1` backward compatible while allowing the console and API to import either five-part or six-part Android account rows, generating a unique `phone_id` only for five-part input.

**Architecture:** Normalize five-part input to the existing six-field Android credential at the backend parser boundary. Preserve the caller's original row in `raw_payload`, keep the current account/credential/outbox/Kafka path unchanged, and update the frontend label and mapping without introducing a new format code.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, AssertJ, MyBatis/JdbcTemplate DB tests, Vue 3, TypeScript, Node test runner, pnpm, Maven.

---

## Constraints and invariants

- `importFormat=1`, `credFormat=1`, and the internal `AccountImportKind` value `six` remain unchanged.
- Five columns mean `phone,static_pub_key,static_pri_key,id_pub_key,id_pri_key`; six columns add caller-supplied `phone_id`.
- A generated `phone_id` is 32 lowercase hexadecimal characters and is unique per parsed row.
- `account_credential.creds_json` always contains the normalized six fields; `account_import_detail.raw_payload` always contains the untouched source row.
- The online outbox stores routing metadata only. Credentials and generated `phone_id` are hydrated from `account_credential` only when the Kafka envelope is built.
- No Flyway migration and no `armada-protocol` change are part of this work.
- Never put production/test account credentials in source, tests, logs, commits, or this plan. All fixtures below are synthetic.

## Task 1: Add failing backend compatibility tests

**Files:**

- Modify: `armada-api/src/test/java/com/armada/account/service/AccountImportParserTest.java`
- Modify: `armada-api/src/test/java/com/armada/account/service/AccountImportServiceImplDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/account/controller/AccountImportControllerDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/account/dispatch/AccountImportOnlineDispatcherDbTest.java`

### Step 1: Add parser tests for normalization, uniqueness, and invalid widths

- [ ] Replace the old five-column rejection test with a success test that preserves the raw row and validates the generated ID:

```java
@Test
void six_fiveColumns_generatesPhoneIdAndPreservesOriginalRawPayload() {
    String line = "919000000101,static-pub,static-pri,identity-pub,identity-pri";

    ParsedEntry entry = parser.parse(ImportFormat.SIX, null, line).get(0);

    assertThat(entry.getParseError()).isNull();
    assertThat(entry.getWid()).isEqualTo("919000000101");
    assertThat(entry.getRawPayload()).isEqualTo(line);
    assertThat(entry.getData().get("phone_id").asText()).matches("[0-9a-f]{32}");
    assertThat(entry.getData().get("static_pub_key").asText()).isEqualTo("static-pub");
    assertThat(entry.getData().get("static_pri_key").asText()).isEqualTo("static-pri");
    assertThat(entry.getData().get("id_pub_key").asText()).isEqualTo("identity-pub");
    assertThat(entry.getData().get("id_pri_key").asText()).isEqualTo("identity-pri");
}
```

- [ ] Add a two-row test proving each five-part row receives an independently generated ID:

```java
@Test
void six_multipleFiveColumnRows_generateUniquePhoneIds() {
    String text = "919000000102,a,b,c,d\n919000000103,e,f,g,h";

    List<ParsedEntry> entries = parser.parse(ImportFormat.SIX, null, text);

    assertThat(entries).hasSize(2);
    assertThat(entries).allSatisfy(entry -> {
        assertThat(entry.getParseError()).isNull();
        assertThat(entry.getData().get("phone_id").asText()).matches("[0-9a-f]{32}");
    });
    assertThat(entries)
            .extracting(entry -> entry.getData().get("phone_id").asText())
            .doesNotHaveDuplicates();
}
```

- [ ] Keep `six_zhuanOrder_normalizesSemanticCredentialFields` and `six_emptyPhoneId_marksRowFailed` unchanged so six-part preservation and empty sixth-column behavior remain covered.

- [ ] Replace `six_wrongColumnCount_marksRowFailed` with explicit four- and seven-column cases:

```java
@Test
void six_nonFiveOrSixColumnCount_marksRowFailed() {
    List<ParsedEntry> fourColumns = parser.parse(
            ImportFormat.SIX, null, "919000000104,static-pub,static-pri,identity-pub");
    List<ParsedEntry> sevenColumns = parser.parse(
            ImportFormat.SIX, null,
            "919000000105,static-pub,static-pri,identity-pub,identity-pri,phone-id,extra");

    assertThat(fourColumns.get(0).getParseError()).contains("应为5列或6列");
    assertThat(sevenColumns.get(0).getParseError()).contains("应为5列或6列");
}
```

### Step 2: Add a DB test for normalized persistence and original export

- [ ] Add imports for `AccountCredential`, `AccountImportExportFile`, `JsonNode`, `ObjectMapper`, and `StandardCharsets` to `AccountImportServiceImplDbTest`.

- [ ] Add this service integration test:

```java
@Test
void import_fivePartPersistsAndroidSixCredentialAndExportsOriginalFiveColumns() throws Exception {
    String line = "919000000201,static-pub,static-pri,identity-pub,identity-pri";
    var meta = new AccountImportDTO(null, 1, 1, 1, "印度", null, "five-part-test", null);

    AccountImportBatchVO batch = service.importAccounts(meta, null, line);

    assertThat(batch.importedRows()).isEqualTo(1);
    Account account = accountMapper.selectActiveByWsPhone("919000000201");
    assertThat(account).isNotNull();
    assertThat(account.getProtocolId()).isEqualTo("ANDROID");

    AccountCredential credential = credentialMapper.selectByAccountId(account.getId());
    assertThat(credential.getCredFormat()).isEqualTo(1);
    JsonNode stored = new ObjectMapper().readTree(credential.getCredsJson());
    assertThat(stored.get("phone").asText()).isEqualTo("919000000201");
    assertThat(stored.get("phone_id").asText()).matches("[0-9a-f]{32}");

    String rawPayload = jdbcTemplate.queryForObject(
            "SELECT raw_payload FROM account_import_detail WHERE batch_id = ?",
            String.class,
            batch.id());
    assertThat(rawPayload).isEqualTo(line);

    AccountImportExportFile export = service.exportDetails(batch.id(), "all");
    assertThat(new String(export.bytes(), StandardCharsets.UTF_8).trim()).isEqualTo(line);
}
```

### Step 3: Add an HTTP contract test for the existing format code

- [ ] Add a controller test showing that multipart `importFormat=1` accepts a five-part text row without any new request field:

```java
@Test
void post_importFivePartWithExistingFormatCode_returnsImportedBatch() throws Exception {
    String line = "919000000301,static-pub,static-pri,identity-pub,identity-pri";

    mockMvc.perform(multipart("/api/account-imports")
                    .param("importFormat", "1")
                    .param("deviceOs", "1")
                    .param("accountType", "1")
                    .param("text", line)
                    .header(TENANT_HEADER, TENANT_CODE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.totalRows").value(1))
            .andExpect(jsonPath("$.data.importedRows").value(1))
            .andExpect(jsonPath("$.data.formatErrorRows").value(0));
}
```

### Step 4: Add an online-dispatch regression test without persisting credentials in outbox

- [ ] Extend the `OutboxRow` query projection to include the existing `protocol_backend` column:

```java
SELECT aggregate_id, status, protocol_backend, payload_json
```

```java
private record OutboxRow(
        Long aggregateId,
        Integer status,
        String protocolBackend,
        String payloadJson
) {
}
```

- [ ] Update the existing row mapper argument order and add a single-account query helper using `aggregate_id = ?`.

- [ ] Add this five-part dispatch test:

```java
@Test
void dispatchOnce_fivePartImportUsesAndroidSixRoutingWithoutCredentialInOutbox() throws Exception {
    long now = System.currentTimeMillis();
    insertIdleProxy(now, "印度");
    String phone = "9191" + String.format("%08d", now % 100_000_000L);
    String line = phone + ",static-pub,static-pri,identity-pub,identity-pri";
    var meta = new AccountImportDTO(null, 1, 1, 1, "印度", null, "five-part-dispatch", null);
    AccountImportBatchVO batch = importService.importAccounts(meta, null, line);
    Account account = accountMapper.selectActiveByWsPhone(phone);

    int dispatched = dispatcher.dispatchOnce();

    assertThat(dispatched).isEqualTo(1);
    assertThat(selectImportDetails(batch.id()))
            .extracting(ImportDetailRow::onlinePhase)
            .containsOnly(AccountImportOnlinePhase.DISPATCHED);

    OutboxRow outbox = selectOnlineOutboxRows(account.getId()).get(0);
    assertThat(outbox.status()).isEqualTo(ProtocolCommandOutboxStatus.PENDING.code());
    assertThat(outbox.protocolBackend()).isEqualTo("ANDROID");
    Map<String, Object> payload = objectMapper.readValue(outbox.payloadJson(), new TypeReference<>() {
    });
    assertThat(payload)
            .containsEntry("credentialFormat", "SIX_SEGMENT")
            .containsEntry("protocolBackend", "ANDROID")
            .doesNotContainKeys("credential", "sixdata", "static_pub_key", "static_pri_key",
                    "id_pub_key", "id_pri_key", "phone_id");
}
```

The helper may use a varargs signature and a dynamically built SQL only if that is already idiomatic in this test. Otherwise, keep the existing two-account helper and add a separate one-account helper to avoid expanding production scope.

### Step 5: Run each new backend test and confirm the red state

- [ ] From `armada/armada-api`, run:

```bash
mvn -Dtest='AccountImportParserTest' test
mvn -Dtest='AccountImportServiceImplDbTest#import_fivePartPersistsAndroidSixCredentialAndExportsOriginalFiveColumns' test
mvn -Dtest='AccountImportControllerDbTest#post_importFivePartWithExistingFormatCode_returnsImportedBatch' test
mvn -Dtest='AccountImportOnlineDispatcherDbTest#dispatchOnce_fivePartImportUsesAndroidSixRoutingWithoutCredentialInOutbox' test
```

Expected: compilation succeeds, and each new five-part test fails because the current parser reports a six-column format error. Existing six-part tests must still compile.

Do not commit a deliberately red test-only state. Proceed directly to Task 2.

## Task 2: Implement backend five/six normalization at the parser boundary

**Files:**

- Modify: `armada-api/src/main/java/com/armada/account/service/AccountImportParser.java`
- Modify: `armada-api/src/main/java/com/armada/account/model/entity/ImportFormat.java`
- Modify: `armada-api/src/main/java/com/armada/account/model/entity/AccountCredential.java`

### Step 1: Add explicit width constants and UUID support

- [ ] Import `java.util.UUID` and add named constants beside the other parser constants:

```java
private static final int FIVE_SEGMENT_COLUMN_COUNT = 5;
private static final int SIX_SEGMENT_COLUMN_COUNT = 6;
```

### Step 2: Accept only five or six columns and normalize to six fields

- [ ] Change `parseSixLine` to reject every width except five and six:

```java
String[] parts = line.split(",", -1);
if (parts.length != FIVE_SEGMENT_COLUMN_COUNT
        && parts.length != SIX_SEGMENT_COLUMN_COUNT) {
    entry.setParseError(
            "五/六段格式错误:应为5列或6列(phone,static_pub_key,static_pri_key,"
                    + "id_pub_key,id_pri_key[,phone_id])");
    return entry;
}
```

- [ ] Keep the existing trim, phone validation, and non-empty loop. This deliberately preserves the six-column trailing-comma failure (`第6列为空`) while validating all five required fields for five-column input.

- [ ] Generate the missing value only after all caller-supplied columns validate:

```java
String phoneId = parts.length == SIX_SEGMENT_COLUMN_COUNT
        ? parts[5]
        : UUID.randomUUID().toString().replace("-", "");
```

- [ ] Continue constructing the same semantic JSON object, changing only the final assignment:

```java
data.put("phone_id", phoneId);
```

- [ ] Do not mutate `entry.rawPayload`, log any part of the source row, add Base64 validation, or change `AccountImportRowWriter`/`AccountOnlineCommandServiceImpl`/`ProtocolCommandPublisher`.

### Step 3: Align source comments with the compatibility contract

- [ ] Update `AccountImportParser` class/method comments to describe five- or six-column Android CSV input.
- [ ] Update `ImportFormat.SIX` documentation to list both accepted rows while keeping the enum name and code unchanged.
- [ ] Update `AccountCredential.credFormat` documentation from `1六段` to `1五/六段（运行时统一六字段）`. Do not change the field, its value, or database schema.

### Step 4: Run targeted backend tests and confirm green

- [ ] From `armada/armada-api`, run:

```bash
mvn -Dtest='AccountImportParserTest' test
mvn -Dtest='AccountImportServiceImplDbTest#import_fivePartPersistsAndroidSixCredentialAndExportsOriginalFiveColumns' test
mvn -Dtest='AccountImportControllerDbTest#post_importFivePartWithExistingFormatCode_returnsImportedBatch' test
mvn -Dtest='AccountImportOnlineDispatcherDbTest#dispatchOnce_fivePartImportUsesAndroidSixRoutingWithoutCredentialInOutbox' test
mvn -Dtest='ProtocolCommandPublisherTest#publishBatch_onlineAndroidRowBuildsZhuanLifecyclePayload' test
```

Expected: all commands end with `BUILD SUCCESS`; parser tests prove five-part generation and old six-part preservation, DB/controller tests prove persistence/export/API compatibility, and dispatcher/publisher tests prove the secure Android online chain.

### Step 5: Review and commit the backend slice

- [ ] Run:

```bash
git diff --check
git diff -- armada-api/src/main/java/com/armada/account armada-api/src/test/java/com/armada/account armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java
```

- [ ] Confirm there is no credential value in logs/outbox assertions and no unrelated file change.
- [ ] Commit only the backend implementation/test files:

```bash
git add armada-api/src/main/java/com/armada/account/service/AccountImportParser.java \
  armada-api/src/main/java/com/armada/account/model/entity/ImportFormat.java \
  armada-api/src/main/java/com/armada/account/model/entity/AccountCredential.java \
  armada-api/src/test/java/com/armada/account/service/AccountImportParserTest.java \
  armada-api/src/test/java/com/armada/account/service/AccountImportServiceImplDbTest.java \
  armada-api/src/test/java/com/armada/account/controller/AccountImportControllerDbTest.java \
  armada-api/src/test/java/com/armada/account/dispatch/AccountImportOnlineDispatcherDbTest.java
git commit -m "feat(account): accept five-part Android imports"
```

## Task 3: Add frontend tests for the compatible label and legacy mapping

**Files:**

- Modify: `../wheel-saas-pure-web/src/views/account/import/constants.test.ts`
- Modify: `../wheel-saas-pure-web/src/views/account/import/components/AccountImportDrawer.test.ts`
- Modify: `../wheel-saas-pure-web/src/api/account-import.test.ts`

### Step 1: Add constants tests for the new user-facing wording

- [ ] Import `importKindLabelMap` and `importTypeOptions`, then add:

```ts
it("labels the existing six kind as five/six compatible", () => {
  const option = importKindOptions.find(item => item.value === "six");

  assert.equal(importKindLabelMap.six, "五/六段号");
  assert.equal(option?.label, "五/六段号");
  assert.match(option?.desc ?? "", /五段号或六段号/);
  assert.ok(
    importTypeOptions.some(
      item => item.label === "五/六段号" && item.value === "五/六段号"
    )
  );
});
```

### Step 2: Add a drawer template assertion

- [ ] Add:

```ts
it("labels the compatible text area as five/six content", () => {
  assert.match(
    source,
    /form\.importKind === 'six' \? '五\/六段号内容' : '全参账号内容'/
  );
});
```

### Step 3: Add API mapping tests for both new and legacy labels

- [ ] Add one list-query test that calls `listAccountImportTasks` first with `import_type: "五/六段号"` and then with the legacy `import_type: "六段号"`; after each reset, assert the captured `params.importFormat` is `1`.

- [ ] Add a response-label test:

```ts
it("renders import format 1 as the compatible five/six label", async () => {
  resetArmadaMock({
    list: [{ id: 1, sourceFileName: "accounts.txt", importFormat: 1 }],
    page: 1,
    pageSize: 10,
    total: 1
  });

  const result = await listAccountImportTasks();

  assert.equal(result.list[0]?.import_type, "五/六段号");
});
```

### Step 4: Run frontend tests and confirm the red state

- [ ] From `wheel-saas-pure-web`, run:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test \
  src/views/account/import/constants.test.ts \
  src/views/account/import/components/AccountImportDrawer.test.ts \
  src/api/account-import.test.ts
```

Expected: the new assertions fail because the current UI and API mapping still use only `六段号`.

Do not commit the red state. Proceed directly to Task 4.

## Task 4: Update the frontend without changing its request shape

**Files:**

- Modify: `../wheel-saas-pure-web/src/views/account/import/constants.ts`
- Modify: `../wheel-saas-pure-web/src/views/account/import/components/AccountImportDrawer.vue`
- Modify: `../wheel-saas-pure-web/src/api/account-import.ts`
- Test: the three files from Task 3

### Step 1: Update constants and explanatory text

- [ ] In `constants.ts`, change the existing `six` label and corresponding import type option to `五/六段号`.
- [ ] Change its description to `支持粘贴或上传 TXT，一行一个五段号或六段号。`.
- [ ] Keep `value: "six"`, `accept: ".txt"`, enabled/disabled behavior, device options, and all request structures unchanged.

### Step 2: Update the drawer field label

- [ ] Change only the `six` branch of the textarea label:

```vue
:label="form.importKind === 'six' ? '五/六段号内容' : '全参账号内容'"
```

### Step 3: Make the API mapping forward- and backward-compatible

- [ ] Update the public label union to document both current and legacy values:

```ts
export type AccountImportType =
  | "五/六段号"
  | "六段号"
  | "JSON号"
  | "全参账号"
  | string;
```

- [ ] Map both labels to the existing code and show only the new label for code `1`:

```ts
function importFormatCode(value?: string | number | null): number | undefined {
  if (typeof value === "number") return value;
  if (value === "五/六段号" || value === "六段号") return 1;
  if (value === "JSON号") return 2;
  if (value === "全参账号") return 3;
  return undefined;
}

function importFormatLabel(value?: number | null): string {
  if (value === 1) return "五/六段号";
  if (value === 2) return "JSON号";
  if (value === 3) return "全参账号";
  return "-";
}
```

### Step 4: Run frontend tests, types, and build

- [ ] From `wheel-saas-pure-web`, run:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test \
  src/views/account/import/constants.test.ts \
  src/views/account/import/components/AccountImportDrawer.test.ts \
  src/api/account-import.test.ts
pnpm run typecheck
pnpm run build
```

Expected: Node tests report zero failures; `tsc`, `vue-tsc`, and Vite production build exit `0`.

### Step 5: Review and commit the frontend slice

- [ ] Run:

```bash
git diff --check
git diff -- src/views/account/import src/api/account-import.ts src/api/account-import.test.ts
```

- [ ] Commit only the frontend compatibility files:

```bash
git add src/views/account/import/constants.ts \
  src/views/account/import/constants.test.ts \
  src/views/account/import/components/AccountImportDrawer.vue \
  src/views/account/import/components/AccountImportDrawer.test.ts \
  src/api/account-import.ts \
  src/api/account-import.test.ts
git commit -m "feat(account): label five and six part imports"
```

## Task 5: Run cross-repository verification and update evidence

**Files:**

- Modify: `.harness/changes/2026-08-06-five-six-account-import-compat.md`
- Verify: all files changed in Tasks 1–4

### Step 1: Run the focused backend regression set

- [ ] From `armada/armada-api`, run:

```bash
mvn -Dtest='AccountImportParserTest,AccountImportServiceImplDbTest,AccountImportControllerDbTest,AccountImportOnlineDispatcherDbTest' test
mvn -Dtest='ProtocolCommandPublisherTest#publishBatch_onlineAndroidRowBuildsZhuanLifecyclePayload' test
```

Expected: both Maven commands end in `BUILD SUCCESS`, including legacy six-part tests and the new five-part flow.

### Step 2: Re-run the focused frontend verification

- [ ] From `wheel-saas-pure-web`, run:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test \
  src/views/account/import/constants.test.ts \
  src/views/account/import/components/AccountImportDrawer.test.ts \
  src/api/account-import.test.ts
pnpm run typecheck
pnpm run build
```

Expected: all tests/type checks/builds pass again from the committed state.

### Step 3: Inspect both repositories for scope and whitespace errors

- [ ] In each repository, run `git status --short`, `git diff --check HEAD~1..HEAD`, and `git show --stat --oneline HEAD`.
- [ ] Preserve the pre-existing `.claude/worktrees/*` entries in `armada`; do not stage or modify them.
- [ ] Confirm `armada-protocol` has no diff and neither repository contains account credentials, PEM files, tokens, or generated build artifacts in the commit.

### Step 4: Record evidence and commit the documentation update

- [ ] Update `.harness/changes/2026-08-06-five-six-account-import-compat.md`:
  - mark design review, implementation plan, backend, frontend, and local verification items complete;
  - record the exact commands and actual pass counts/build results;
  - record the backend and frontend commit IDs;
  - leave test-environment deployment unchecked until Task 6 is actually complete.
- [ ] Commit the updated change record on the backend branch (the implementation plan itself is committed before execution begins):

```bash
git add .harness/changes/2026-08-06-five-six-account-import-compat.md
git commit -m "docs: record five-part import verification"
```

## Task 6: Deploy to the first test environment and perform acceptance checks

**Files:**

- Modify after acceptance: `.harness/changes/2026-08-06-five-six-account-import-compat.md`
- Deploy: backend and frontend only; no protocol-layer deployment

This task changes remote state. Stop and obtain an explicit confirmation that the target is still `test1` before running any deploy or remote write command.

### Step 1: Confirm and inspect the target environment

- [ ] After the user confirms `test1`, run the read-only environment check from `armada`:

```bash
./armada-deploy/deploy-test.sh --env test1 --check
./armada-deploy/deploy-test.sh --env test1 --all --dry-run
```

Expected: the profile identifies `第一套环境`; backend/frontend targets and health checks are reachable; the dry run contains no protocol/Zhuan deployment.

### Step 2: Deploy backend and frontend

- [ ] Run:

```bash
./armada-deploy/deploy-test.sh --env test1 --all -y
```

Expected: backend JAR and frontend dist build successfully, only Armada backend/nginx are restarted, and deployment health checks pass. Do not use `--full`, `--protocol`, or `--zhuan` because this feature has no protocol-layer change.

### Step 3: Perform controlled five/six acceptance

- [ ] In the first-environment console, use a dedicated test group and two fresh operator-supplied synthetic/test accounts that are not committed to either repository:
  1. import one five-part row through the `五/六段号` entry;
  2. import one six-part row through the same entry;
  3. verify both batches report one imported row and zero format errors;
  4. verify both accounts are `ANDROID` and enter the intended group;
  5. export both batches and compare the exported rows byte-for-byte with their original five- and six-column inputs.
- [ ] Verify the online chain at three boundaries without printing credentials:
  - Armada DB/API: import detail advances from `QUEUED` to `DISPATCHED` and account state reaches the expected online state;
  - outbox/Kafka routing: `protocol_backend=ANDROID`, credential format is `SIX_SEGMENT`, and the outbox payload contains no credential material;
  - protocol callback/logs: the generated `phone_id` reaches the Android command and the state event returns to the matching account, using only masked phone numbers in captured evidence.
- [ ] If a fresh usable test account is unavailable, report acceptance as blocked by test data; do not reuse or alter unrelated live accounts.

### Step 4: Record deployment evidence and close the change record

- [ ] Update `.harness/changes/2026-08-06-five-six-account-import-compat.md` with:
  - deployment command and timestamp;
  - backend/frontend commit IDs actually deployed;
  - masked batch/account identifiers;
  - five-part and six-part import/export/online results;
  - any transient retry and its final state.
- [ ] Mark deployment complete only if both input widths pass. Commit the evidence:

```bash
git add .harness/changes/2026-08-06-five-six-account-import-compat.md
git commit -m "docs: record test1 five-part import acceptance"
```

## Final completion criteria

- [ ] Five-part and six-part inputs both work through the same console/API format code.
- [ ] Five-part input gets a generated unique 32-character lowercase hexadecimal `phone_id`.
- [ ] Existing six-part `phone_id` is preserved exactly.
- [ ] Runtime credentials are six-field Android credentials, while export remains byte-for-byte original.
- [ ] Outbox remains credential-free and the existing publisher creates the Android Kafka envelope.
- [ ] Backend regression tests, frontend tests, type checks, and production build pass.
- [ ] First-environment deployment/acceptance is either completed with evidence or explicitly reported as pending behind the remote-state approval/test-data gate.
