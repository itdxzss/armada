# Full Params Android Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable full-params TXT/NDJSON account imports, convert each valid row to the existing six-segment credential shape, route it through Android, retain original rows for export, and reuse the current 10-second automatic-online dispatcher.

**Architecture:** Add one pure full-params-to-six converter and make the PARAMS parser consume one JSON object per nonblank line. Keep source semantics in `account_import_batch.import_format=3` and `account_import_detail.raw_payload`, while writing runtime semantics as `account.protocol_id=ANDROID` and `account_credential.cred_format=1`. Enable the existing front-end option and reuse the existing multipart file path for uploaded TXT files so the original filename is preserved.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Jackson, MyBatis, JUnit 5, AssertJ, Mockito, H2 test database, Vue 3, TypeScript, Element Plus, Node test runner, pnpm.

---

## Execution Preconditions

The current `armada` and `wheel-saas-pure-web` working trees contain unrelated in-progress changes. At execution time, create isolated worktrees for both repositories with `superpowers:using-git-worktrees`; do not implement this plan directly in the dirty primary worktrees. The implementation branches should start from commit `e398f2a` or a descendant containing the approved design and this plan.

Do not copy the three real full-params files into either repository, test resources, logs, patches, or commits. Tests must use synthetic values such as `static-public-test` and must never use real keys.

## File Map

Back end in `/Users/daishuaishuai/IdeaProjects/armada`:

- Create `armada-api/src/main/java/com/armada/account/converter/FullParamsToSixConverter.java`
  Pure validation and field mapping from one full-params JSON object to one normalized six-segment JSON object.
- Create `armada-api/src/test/java/com/armada/account/converter/FullParamsToSixConverterTest.java`
  Covers the six mappings, ignored fields, missing fields, invalid phone, and secret-safe errors.
- Modify `armada-api/src/main/java/com/armada/account/service/AccountImportParser.java`
  Parse PARAMS as NDJSON, preserve every original row, and delegate conversion.
- Modify `armada-api/src/test/java/com/armada/account/service/AccountImportParserTest.java`
  Replace the obsolete PARAMS object/array expectations with row-isolated NDJSON tests.
- Modify `armada-api/src/main/java/com/armada/account/model/entity/ImportFormat.java`
  Document PARAMS as full-params NDJSON converted to six credentials.
- Modify `armada-api/src/main/java/com/armada/account/model/entity/ParsedEntry.java`
  Document that PARAMS `data` contains normalized runtime credentials while `rawPayload` retains the source.
- Modify `armada-api/src/main/java/com/armada/account/service/impl/AccountImportRowWriter.java`
  Decouple source import format from runtime protocol and credential format.
- Modify `armada-api/src/test/java/com/armada/account/service/impl/AccountImportRowWriterTest.java`
  Verify PARAMS writes Android protocol, selected device OS, and SIX credential format.
- Modify `armada-api/src/test/java/com/armada/account/service/AccountImportServiceImplDbTest.java`
  Verify partial success, source/runtime storage split, queue phase, and original TXT export.
- Modify `armada-api/src/test/java/com/armada/account/controller/AccountImportControllerDbTest.java`
  Verify an uploaded full-params TXT file uses the existing multipart API and preserves its filename/content.
- Modify `armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java`
  Verify Android accounts with `cred_format=1` produce `SIX_SEGMENT` online commands.
- Modify `armada-api/src/test/java/com/armada/account/dispatch/AccountImportOnlineDispatcherDbTest.java`
  Verify full-params imports use the existing queued dispatcher and Android outbox route.

Front end in `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`:

- Modify `src/views/account/import/constants.ts`
  Enable full-params and state the NDJSON/Android constraints.
- Modify `src/views/account/import/constants.test.ts`
  Verify no import kind is disabled and full-params guidance is explicit.
- Modify `src/views/account/import/components/AccountImportDrawer.vue`
  Forward the selected TXT `File` for full-params while retaining the textarea preview.
- Modify `src/views/account/import/components/AccountImportDrawer.test.ts`
  Verify the drawer no longer discards non-JSON uploaded files.
- Modify `src/views/account/import/composables/useAccountImportPage.ts`
  Send full-params file uploads through the existing multipart file API; keep pasted full-params on the text path.
- Create `src/views/account/import/composables/useAccountImportPage.test.ts`
  Lock the two full-params submit routes and prevent JSON/six regressions.
- Modify `src/api/account-import.test.ts`
  Verify the existing upload API sends `importFormat=3`, the manually selected device, and the TXT file.

Documentation in `/Users/daishuaishuai/IdeaProjects/armada`:

- Modify `.harness/changes/2026-07-30-full-params-android-import.md`
  Record completed tasks and exact verification outputs after implementation.

No Flyway migration, Mapper XML change, protocol-layer change, or new API endpoint is required.

## Task 1: Build the Pure Full-Params-to-Six Converter

**Files:**

- Create: `armada-api/src/test/java/com/armada/account/converter/FullParamsToSixConverterTest.java`
- Create: `armada-api/src/main/java/com/armada/account/converter/FullParamsToSixConverter.java`

- [ ] **Step 1: Write the failing converter tests**

Create `FullParamsToSixConverterTest.java` with synthetic values only:

```java
package com.armada.account.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FullParamsToSixConverterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FullParamsToSixConverter converter = new FullParamsToSixConverter();

    @Test
    void convert_mapsOnlySixRuntimeFields() {
        ObjectNode source = validSource();
        source.put("registrationID", 77);
        source.put("device", "iPhone-test");
        source.put("signPreKeyPrivateKey", "ignored-private-test");

        FullParamsToSixConverter.Result result = converter.convert(source);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.phone()).isEqualTo("919000000001");
        assertThat(result.credential().size()).isEqualTo(6);
        assertThat(result.credential().path("phone").asText()).isEqualTo("919000000001");
        assertThat(result.credential().path("static_pub_key").asText()).isEqualTo("static-public-test");
        assertThat(result.credential().path("static_pri_key").asText()).isEqualTo("static-private-test");
        assertThat(result.credential().path("id_pub_key").asText()).isEqualTo("identity-public-test");
        assertThat(result.credential().path("id_pri_key").asText()).isEqualTo("identity-private-test");
        assertThat(result.credential().path("phone_id").asText()).isEqualTo("phone-uuid-test");
        assertThat(result.credential().has("registrationID")).isFalse();
        assertThat(result.credential().has("device")).isFalse();
        assertThat(result.credential().has("signPreKeyPrivateKey")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jid",
            "clientStaticPublicKey",
            "clientStaticPrivateKey",
            "identityPublicKey",
            "identityPrivateKey",
            "phoneUUID"
    })
    void convert_missingRequiredField_returnsCredentialIncompleteWithoutSecrets(String field) {
        ObjectNode source = validSource();
        source.remove(field);

        FullParamsToSixConverter.Result result = converter.convert(source);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error())
                .contains("凭据不全")
                .contains(field)
                .doesNotContain("static-private-test")
                .doesNotContain("identity-private-test");
    }

    @Test
    void convert_invalidJid_returnsFormatErrorWithoutEchoingValue() {
        ObjectNode source = validSource();
        source.put("jid", "invalid-sensitive-phone");

        FullParamsToSixConverter.Result result = converter.convert(source);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error())
                .contains("jid")
                .contains("7到15位纯数字")
                .doesNotContain("invalid-sensitive-phone");
    }

    private ObjectNode validSource() {
        ObjectNode source = mapper.createObjectNode();
        source.put("jid", "919000000001");
        source.put("clientStaticPublicKey", " static-public-test ");
        source.put("clientStaticPrivateKey", " static-private-test ");
        source.put("identityPublicKey", " identity-public-test ");
        source.put("identityPrivateKey", " identity-private-test ");
        source.put("phoneUUID", " phone-uuid-test ");
        return source;
    }
}
```

- [ ] **Step 2: Run the converter test and verify it fails**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=FullParamsToSixConverterTest test
```

Expected: compilation fails because `FullParamsToSixConverter` does not exist.

- [ ] **Step 3: Implement the minimal pure converter**

Create `FullParamsToSixConverter.java`:

```java
package com.armada.account.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Converts one full-params JSON object into the Android six-segment credential shape. */
@Component
public class FullParamsToSixConverter {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\d{7,15}$");

    private static final List<FieldMapping> FIELD_MAPPINGS = List.of(
            new FieldMapping("jid", "phone"),
            new FieldMapping("clientStaticPublicKey", "static_pub_key"),
            new FieldMapping("clientStaticPrivateKey", "static_pri_key"),
            new FieldMapping("identityPublicKey", "id_pub_key"),
            new FieldMapping("identityPrivateKey", "id_pri_key"),
            new FieldMapping("phoneUUID", "phone_id")
    );

    public Result convert(JsonNode source) {
        if (source == null || !source.isObject()) {
            return Result.failure("全参格式错误:必须为 JSON 对象");
        }

        ObjectNode credential = JsonNodeFactory.instance.objectNode();
        for (FieldMapping mapping : FIELD_MAPPINGS) {
            JsonNode valueNode = source.get(mapping.sourceField());
            if (valueNode == null || !valueNode.isTextual() || valueNode.asText().trim().isEmpty()) {
                return Result.failure("凭据不全:字段 " + mapping.sourceField() + " 必须为非空字符串");
            }
            credential.put(mapping.targetField(), valueNode.asText().trim());
        }

        String phone = credential.path("phone").asText();
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            return Result.failure("全参格式错误:jid 必须为7到15位纯数字");
        }
        return Result.success(phone, credential);
    }

    private record FieldMapping(String sourceField, String targetField) {
    }

    public record Result(String phone, ObjectNode credential, String error) {

        public static Result success(String phone, ObjectNode credential) {
            return new Result(phone, credential, null);
        }

        public static Result failure(String error) {
            return new Result(null, null, error);
        }

        public boolean isSuccess() {
            return error == null;
        }
    }
}
```

- [ ] **Step 4: Run the converter test and verify it passes**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=FullParamsToSixConverterTest test
```

Expected: exit code 0; all converter tests pass without printing any credential values.

- [ ] **Step 5: Commit the converter slice**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/account/converter/FullParamsToSixConverter.java armada-api/src/test/java/com/armada/account/converter/FullParamsToSixConverterTest.java
git commit -m "feat: convert full params to six credentials"
```

## Task 2: Parse Full Params as Row-Isolated NDJSON

**Files:**

- Modify: `armada-api/src/test/java/com/armada/account/service/AccountImportParserTest.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/AccountImportParser.java`
- Modify: `armada-api/src/main/java/com/armada/account/model/entity/ImportFormat.java`
- Modify: `armada-api/src/main/java/com/armada/account/model/entity/ParsedEntry.java`

- [ ] **Step 1: Replace obsolete PARAMS tests with failing NDJSON tests**

Change the parser construction at the top of `AccountImportParserTest`:

```java
private final AccountImportParser parser =
        new AccountImportParser(new com.armada.account.converter.FullParamsToSixConverter());
```

Replace `params_validWid_parsesOk`, `params_arrayTextPreservesRawPayloadAndEntryName`, `params_missingWid_marksError`, and `params_invalidWid_marksError` with:

```java
@Test
void params_ndjson_convertsEachLineAndPreservesOriginalPayload() {
    String first = fullParams("919000000101", "phone-id-101");
    String second = "  " + fullParams("919000000102", "phone-id-102") + "  ";

    List<ParsedEntry> entries = parser.parse(
            ImportFormat.PARAMS,
            null,
            first + "\n\n" + second);

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).getWid()).isEqualTo("919000000101");
    assertThat(entries.get(0).getRawPayload()).isEqualTo(first);
    assertThat(entries.get(0).getSourceEntryName()).isEqualTo("params-input[1]");
    assertThat(entries.get(0).getData().path("phone_id").asText()).isEqualTo("phone-id-101");
    assertThat(entries.get(0).getData().size()).isEqualTo(6);
    assertThat(entries.get(1).getWid()).isEqualTo("919000000102");
    assertThat(entries.get(1).getRawPayload()).isEqualTo(second);
    assertThat(entries.get(1).getSourceEntryName()).isEqualTo("params-input[3]");
}

@Test
void params_invalidMiddleLine_doesNotBlockValidNeighbors() {
    String first = fullParams("919000000201", "phone-id-201");
    String invalid = "{not-json-and-must-not-be-echoed}";
    String third = fullParams("919000000203", "phone-id-203");

    List<ParsedEntry> entries = parser.parse(
            ImportFormat.PARAMS,
            null,
            first + "\n" + invalid + "\n" + third);

    assertThat(entries).hasSize(3);
    assertThat(entries.get(0).getParseError()).isNull();
    assertThat(entries.get(1).getParseError())
            .contains("第2行")
            .contains("JSON 解析失败")
            .doesNotContain(invalid);
    assertThat(entries.get(1).getRawPayload()).isEqualTo(invalid);
    assertThat(entries.get(2).getParseError()).isNull();
}

@Test
void params_arrayLine_isRejectedAsOneRow() {
    String arrayLine = "[" + fullParams("919000000301", "phone-id-301") + "]";

    List<ParsedEntry> entries = parser.parse(ImportFormat.PARAMS, null, arrayLine);

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).getParseError()).contains("必须为 JSON 对象");
    assertThat(entries.get(0).getRawPayload()).isEqualTo(arrayLine);
}

@Test
void params_missingRequiredField_marksCredentialIncompleteWithoutEchoingSecrets() {
    String privateValue = "private-value-that-must-not-leak";
    String line = "{\"jid\":\"919000000401\","
            + "\"clientStaticPublicKey\":\"static-public\","
            + "\"clientStaticPrivateKey\":\"" + privateValue + "\","
            + "\"identityPublicKey\":\"identity-public\","
            + "\"identityPrivateKey\":\"identity-private\"}";

    List<ParsedEntry> entries = parser.parse(ImportFormat.PARAMS, null, line);

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).getParseError())
            .contains("凭据不全")
            .contains("phoneUUID")
            .doesNotContain(privateValue);
}

private static String fullParams(String phone, String phoneId) {
    return "{\"jid\":\"" + phone + "\","
            + "\"clientStaticPublicKey\":\"static-public-test\","
            + "\"clientStaticPrivateKey\":\"static-private-test\","
            + "\"identityPublicKey\":\"identity-public-test\","
            + "\"identityPrivateKey\":\"identity-private-test\","
            + "\"phoneUUID\":\"" + phoneId + "\","
            + "\"device\":\"iPhone-test\"}";
}
```

- [ ] **Step 2: Run parser tests and verify the new contract fails**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=AccountImportParserTest test
```

Expected: compilation fails because the parser has no converter constructor, or assertions fail because PARAMS still treats the entire input as one object/array and expects `wid`.

- [ ] **Step 3: Inject the converter and implement line-by-line PARAMS parsing**

Add the import, field, and constructor to `AccountImportParser`:

```java
import com.armada.account.converter.FullParamsToSixConverter;

private final FullParamsToSixConverter fullParamsConverter;

public AccountImportParser(FullParamsToSixConverter fullParamsConverter) {
    this.fullParamsConverter = fullParamsConverter;
}
```

Replace the current `parseParams`, `parseParamsArray`, and `parseParamsNode` methods with:

```java
private List<ParsedEntry> parseParams(byte[] fileBytes, String text) {
    String src = (text != null && !text.isEmpty()) ? text
            : (fileBytes != null ? new String(fileBytes, StandardCharsets.UTF_8) : "");
    if (src.isBlank()) {
        return makeErrorEntry("", "输入内容为空");
    }

    String[] lines = src.split("\\R", -1);
    List<ParsedEntry> result = new ArrayList<>(lines.length);
    for (int i = 0; i < lines.length; i++) {
        String line = lines[i];
        if (line.isBlank()) {
            continue;
        }
        result.add(parseParamsLine(line, i + 1));
    }
    return result.isEmpty() ? makeErrorEntry("", "输入内容为空") : result;
}

private ParsedEntry parseParamsLine(String line, int lineNo) {
    String source = "params-input[" + lineNo + "]";
    ParsedEntry entry = new ParsedEntry();
    entry.setRaw(source);
    entry.setRawPayload(line);
    entry.setSourceEntryName(source);

    JsonNode node;
    try {
        node = mapper.readTree(line);
    } catch (IOException e) {
        entry.setParseError("第" + lineNo + "行 JSON 解析失败");
        return entry;
    }
    if (node == null || !node.isObject()) {
        entry.setParseError("第" + lineNo + "行全参必须为 JSON 对象");
        return entry;
    }

    FullParamsToSixConverter.Result conversion = fullParamsConverter.convert(node);
    if (!conversion.isSuccess()) {
        entry.setParseError("第" + lineNo + "行" + conversion.error());
        return entry;
    }
    entry.setWid(conversion.phone());
    entry.setData(conversion.credential());
    return entry;
}
```

Do not log `line`, `node`, `rawPayload`, or converter source fields. The existing parser warning for PARAMS parse errors should be removed because the error is now represented per row.

Also update the parser class and `parse`/`parseParams` Javadocs so they state that PARAMS accepts one JSON object per nonblank TXT line. Remove the obsolete single-object/array claim.

- [ ] **Step 4: Update the model documentation to match runtime behavior**

Change the PARAMS Javadoc in `ImportFormat.java` to:

```java
/**
 * 全参账号格式:TXT/粘贴文本中每个非空行为一个 JSON 对象。
 * 导入时转换为 Android 六段凭据;批次仍以 PARAMS 标记原始来源。
 */
PARAMS(3);
```

Change `ParsedEntry.data` Javadoc to:

```java
/**
 * 运行时凭据 JSON。JSON 导入保留原始凭据对象;
 * SIX 和 PARAMS 导入均保存规范化后的六段对象。
 */
private JsonNode data;
```

- [ ] **Step 5: Run converter and parser tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=FullParamsToSixConverterTest,AccountImportParserTest test
```

Expected: exit code 0; JSON and SIX parser tests remain green, PARAMS accepts NDJSON only, and invalid rows are isolated.

- [ ] **Step 6: Commit the parser slice**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/account/service/AccountImportParser.java armada-api/src/main/java/com/armada/account/model/entity/ImportFormat.java armada-api/src/main/java/com/armada/account/model/entity/ParsedEntry.java armada-api/src/test/java/com/armada/account/service/AccountImportParserTest.java
git commit -m "feat: parse full params ndjson imports"
```

## Task 3: Separate Source Format from Runtime Credential Routing

**Files:**

- Modify: `armada-api/src/test/java/com/armada/account/service/impl/AccountImportRowWriterTest.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountImportRowWriter.java`

- [ ] **Step 1: Write the failing PARAMS row-writer test**

Add this test to `AccountImportRowWriterTest`:

```java
@Test
void writeOne_paramsImportStoresAndroidProtocolAndSixCredentialFormat() {
    when(accountMapper.insert(any(Account.class))).thenAnswer(invocation -> {
        Account account = invocation.getArgument(0);
        account.setId(456L);
        return 1;
    });
    when(stateMapper.insert(any())).thenReturn(1);
    when(credentialMapper.insert(any())).thenReturn(1);
    AccountImportRowWriter writer = new AccountImportRowWriter(accountMapper, stateMapper, credentialMapper);

    Long accountId = writer.writeOne("919000000501", sixEntry("919000000501"), 9L,
            new AccountImportDTO(9L, ImportFormat.PARAMS.getCode(), 2, 1,
                    "印度", null, null, "fullparams.txt"));

    ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
    ArgumentCaptor<AccountCredential> credentialCaptor = ArgumentCaptor.forClass(AccountCredential.class);
    verify(accountMapper).insert(accountCaptor.capture());
    verify(credentialMapper).insert(credentialCaptor.capture());

    assertThat(accountId).isEqualTo(456L);
    assertThat(accountCaptor.getValue().getProtocolId()).isEqualTo(ProtocolBackend.ANDROID.name());
    assertThat(accountCaptor.getValue().getDeviceOs()).isEqualTo(2);
    assertThat(credentialCaptor.getValue().getCredFormat()).isEqualTo(ImportFormat.SIX.getCode());
    assertThat(credentialCaptor.getValue().getCredsJson()).contains("\"phone\":\"919000000501\"");
}
```

Change the existing helper to accept a phone and keep the old test calling it with its original value:

```java
Long accountId = writer.writeOne("27612057408", sixEntry("27612057408"), 9L,
        new AccountImportDTO(9L, ImportFormat.SIX.getCode(), 1, 1,
                "ZA", null, null, "six.txt"));
```

```java
private static ParsedEntry sixEntry(String phone) {
    ParsedEntry entry = new ParsedEntry();
    ObjectMapper mapper = new ObjectMapper();
    var data = mapper.createObjectNode();
    data.put("phone", phone);
    data.put("id_pri_key", "id-pri-test");
    data.put("id_pub_key", "id-pub-test");
    data.put("static_pri_key", "static-pri-test");
    data.put("static_pub_key", "static-pub-test");
    data.put("phone_id", "phone-id-test");
    entry.setData(data);
    return entry;
}
```

- [ ] **Step 2: Run the row-writer test and verify it fails**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=AccountImportRowWriterTest test
```

Expected: the PARAMS assertion fails because current code leaves `protocol_id` empty and writes `cred_format=3`.

- [ ] **Step 3: Implement explicit runtime mappings**

In `AccountImportRowWriter`, replace the SIX-only protocol check:

```java
if (usesAndroidProtocol(importFormat)) {
    a.setProtocolId(ProtocolBackend.ANDROID.name());
}
```

Change credential construction from `c.setCredFormat(importFormat)` to:

```java
c.setCredFormat(runtimeCredentialFormat(importFormat));
```

Add these focused helpers:

```java
private boolean usesAndroidProtocol(int importFormat) {
    return importFormat == ImportFormat.SIX.getCode()
            || importFormat == ImportFormat.PARAMS.getCode();
}

private int runtimeCredentialFormat(int importFormat) {
    return importFormat == ImportFormat.PARAMS.getCode()
            ? ImportFormat.SIX.getCode()
            : importFormat;
}
```

Do not alter `meta.importFormat()` when building `account_import_batch`; source format `3` must remain visible in batch lists and exports.

- [ ] **Step 4: Run row-writer regression tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=AccountImportRowWriterTest test
```

Expected: exit code 0; SIX and PARAMS both route to Android/SIX, while the existing SIX test remains green.

- [ ] **Step 5: Commit the runtime-routing slice**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/account/service/impl/AccountImportRowWriter.java armada-api/src/test/java/com/armada/account/service/impl/AccountImportRowWriterTest.java
git commit -m "feat: route full params imports through android"
```

## Task 4: Prove Persistence, Partial Success, and Original Export

**Files:**

- Modify: `armada-api/src/test/java/com/armada/account/service/AccountImportServiceImplDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/account/controller/AccountImportControllerDbTest.java`

- [ ] **Step 1: Add the service integration test**

Add imports to `AccountImportServiceImplDbTest`:

```java
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.entity.ImportFormat;
import com.armada.account.model.vo.AccountImportExportFile;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
```

Add this test and helper:

```java
@Test
void import_fullParamsNdjson_splitsSourceAndRuntimeSemantics_withPartialSuccess() throws Exception {
    String valid = fullParams("919000000601", true);
    String invalid = fullParams("919000000602", false);
    String input = valid + "\n" + invalid;
    var meta = new AccountImportDTO(
            null, ImportFormat.PARAMS.getCode(), 2, 1, "印度", null, null, "fullparams.txt");

    AccountImportBatchVO batch = service.importAccounts(meta, null, input);

    assertThat(batch.importFormat()).isEqualTo(ImportFormat.PARAMS.getCode());
    assertThat(batch.deviceOs()).isEqualTo(2);
    assertThat(batch.totalRows()).isEqualTo(2);
    assertThat(batch.importedRows()).isEqualTo(1);
    assertThat(batch.formatErrorRows()).isEqualTo(1);

    Account account = accountMapper.selectActiveByWsPhone("919000000601");
    assertThat(account.getProtocolId()).isEqualTo(ProtocolBackend.ANDROID.name());
    assertThat(account.getDeviceOs()).isEqualTo(2);

    AccountCredential credential = credentialMapper.selectByAccountId(account.getId());
    assertThat(credential.getCredFormat()).isEqualTo(ImportFormat.SIX.getCode());
    JsonNode stored = new ObjectMapper().readTree(credential.getCredsJson());
    assertThat(stored.size()).isEqualTo(6);
    assertThat(stored.path("phone").asText()).isEqualTo("919000000601");
    assertThat(stored.path("phone_id").asText()).isEqualTo("phone-id-test");
    assertThat(stored.has("clientStaticPrivateKey")).isFalse();
    assertThat(stored.has("device")).isFalse();

    List<Integer> phases = jdbcTemplate.query(
            "SELECT online_phase FROM account_import_detail WHERE batch_id = ? ORDER BY line_no",
            (rs, rowNum) -> rs.getInt("online_phase"),
            batch.id());
    assertThat(phases).containsExactly(
            AccountImportOnlinePhase.QUEUED,
            AccountImportOnlinePhase.SKIPPED);

    List<String> payloads = jdbcTemplate.query(
            "SELECT raw_payload FROM account_import_detail WHERE batch_id = ? ORDER BY line_no",
            (rs, rowNum) -> rs.getString("raw_payload"),
            batch.id());
    assertThat(payloads).containsExactly(valid, invalid);

    AccountImportExportFile export = service.exportDetails(batch.id(), "all");
    assertThat(export.contentType()).isEqualTo("text/plain;charset=UTF-8");
    assertThat(new String(export.bytes(), StandardCharsets.UTF_8)).isEqualTo(input);
}

private static String fullParams(String phone, boolean includePhoneUuid) {
    String tail = includePhoneUuid ? ",\"phoneUUID\":\"phone-id-test\"" : "";
    return "{\"jid\":\"" + phone + "\","
            + "\"clientStaticPublicKey\":\"static-public-test\","
            + "\"clientStaticPrivateKey\":\"static-private-test\","
            + "\"identityPublicKey\":\"identity-public-test\","
            + "\"identityPrivateKey\":\"identity-private-test\""
            + tail + ",\"device\":\"iPhone-test\"}";
}
```

- [ ] **Step 2: Add the multipart controller and export test**

Add this test to `AccountImportControllerDbTest`:

```java
@Test
void post_fullParamsTxt_importsViaExistingMultipartAndExportsOriginalText() throws Exception {
    String first = fullParamsLine("919000000701", "phone-id-701");
    String second = fullParamsLine("919000000702", "phone-id-702");
    String input = first + "\n" + second;
    MockMultipartFile file = new MockMultipartFile(
            "file",
            "fullparams.txt",
            "text/plain",
            input.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    MvcResult importResult = mockMvc.perform(multipart("/api/account-imports")
                    .file(file)
                    .param("importFormat", "3")
                    .param("deviceOs", "2")
                    .param("accountType", "1")
                    .header(TENANT_HEADER, TENANT_CODE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sourceFileName").value("fullparams.txt"))
            .andExpect(jsonPath("$.data.importFormat").value(3))
            .andExpect(jsonPath("$.data.importedRows").value(2))
            .andReturn();

    Long batchId = objectMapper.readTree(importResult.getResponse().getContentAsString())
            .path("data").path("id").longValue();

    mockMvc.perform(get("/api/account-imports/{batchId}/export", batchId)
                    .param("scope", "all")
                    .header(TENANT_HEADER, TENANT_CODE))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/plain"))
            .andExpect(header().string("Content-Disposition",
                    org.hamcrest.Matchers.containsString(".txt")))
            .andExpect(content().string(input));
}

private static String fullParamsLine(String phone, String phoneId) {
    return "{\"jid\":\"" + phone + "\","
            + "\"clientStaticPublicKey\":\"static-public-test\","
            + "\"clientStaticPrivateKey\":\"static-private-test\","
            + "\"identityPublicKey\":\"identity-public-test\","
            + "\"identityPrivateKey\":\"identity-private-test\","
            + "\"phoneUUID\":\"" + phoneId + "\"}";
}
```

- [ ] **Step 3: Run the service and controller integration tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=AccountImportServiceImplDbTest,AccountImportControllerDbTest test
```

Expected: exit code 0. The valid row is stored as Android/SIX and queued; the invalid row is skipped; the export body is byte-for-byte equal to the two original JSON lines joined by one newline.

- [ ] **Step 4: Commit the import integration coverage**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/test/java/com/armada/account/service/AccountImportServiceImplDbTest.java armada-api/src/test/java/com/armada/account/controller/AccountImportControllerDbTest.java
git commit -m "test: cover full params import persistence"
```

## Task 5: Prove Existing 10-Second Dispatch and Android Command Routing

**Files:**

- Modify: `armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/account/dispatch/AccountImportOnlineDispatcherDbTest.java`

- [ ] **Step 1: Strengthen the Android command-format regression test**

In `online_androidProtocolAccount_enqueuesAndroidBackendCommand`, change the credential setup to the real full-params runtime shape:

```java
credential.setCredFormat(ImportFormat.SIX.getCode());
credential.setCredsJson("{\"phone\":\"8613800138000\","
        + "\"static_pub_key\":\"static-public-test\","
        + "\"static_pri_key\":\"static-private-test\","
        + "\"id_pub_key\":\"identity-public-test\","
        + "\"id_pri_key\":\"identity-private-test\","
        + "\"phone_id\":\"phone-id-test\"}");
```

Add this assertion beside the existing backend assertion:

```java
assertThat(command.credentialFormat()).isEqualTo(CredentialFormat.SIX_SEGMENT);
```

Add the missing import if necessary:

```java
import com.armada.account.model.entity.ImportFormat;
```

- [ ] **Step 2: Add a dispatcher integration test for full-params imports**

Add imports to `AccountImportOnlineDispatcherDbTest`:

```java
import com.armada.account.model.entity.ImportFormat;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
```

Add this test:

```java
@Test
void dispatchOnce_fullParamsImportUsesExistingQueueAndAndroidOutboxRoute() {
    long now = System.currentTimeMillis();
    insertIdleProxy(now, "印度");
    String phone = "919" + String.format("%09d", now % 1_000_000_000L);
    String params = fullParams(phone);
    var meta = new AccountImportDTO(
            null, ImportFormat.PARAMS.getCode(), 2, 1, "印度", null, "dispatch-fullparams", "fullparams.txt");

    AccountImportBatchVO batch = importService.importAccounts(meta, null, params);
    Account account = accountMapper.selectActiveByWsPhone(phone);

    int dispatched = dispatcher.dispatchOnce();

    assertThat(dispatched).isEqualTo(1);
    assertThat(selectImportDetails(batch.id()))
            .extracting(ImportDetailRow::onlinePhase)
            .containsExactly(AccountImportOnlinePhase.DISPATCHED);
    assertThat(account.getProtocolId()).isEqualTo(ProtocolBackend.ANDROID.name());

    Integer credFormat = jdbc.queryForObject(
            "SELECT cred_format FROM account_credential WHERE account_id = ?",
            Integer.class,
            account.getId());
    assertThat(credFormat).isEqualTo(ImportFormat.SIX.getCode());

    String protocolBackend = jdbc.queryForObject(
            "SELECT protocol_backend FROM protocol_command_outbox "
                    + "WHERE tenant_id = ? AND aggregate_id = ? AND command_type = ?",
            String.class,
            TEST_TENANT_ID,
            account.getId(),
            "account.online.requested");
    assertThat(protocolBackend).isEqualTo(ProtocolBackend.ANDROID.name());
}

private static String fullParams(String phone) {
    return "{\"jid\":\"" + phone + "\","
            + "\"clientStaticPublicKey\":\"static-public-test\","
            + "\"clientStaticPrivateKey\":\"static-private-test\","
            + "\"identityPublicKey\":\"identity-public-test\","
            + "\"identityPrivateKey\":\"identity-private-test\","
            + "\"phoneUUID\":\"phone-id-test\"}";
}
```

This test calls `dispatcher.dispatchOnce()` directly. Do not add sleeps, an immediate-dispatch hook, or another scheduler. The production scheduler remains `fixedDelay=10000`.

- [ ] **Step 3: Run command and dispatcher tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=AccountOnlineCommandServiceImplTest,AccountImportOnlineDispatcherDbTest test
```

Expected: exit code 0; a queued PARAMS-source account is dispatched by the existing worker, its outbox backend is Android, and its hydrated online command format is SIX_SEGMENT.

- [ ] **Step 4: Commit the dispatch coverage**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java armada-api/src/test/java/com/armada/account/dispatch/AccountImportOnlineDispatcherDbTest.java
git commit -m "test: verify full params automatic online routing"
```

## Task 6: Enable the Front-End Full-Params Import Path

**Files:**

- Modify: `src/views/account/import/constants.test.ts`
- Modify: `src/views/account/import/components/AccountImportDrawer.test.ts`
- Create: `src/views/account/import/composables/useAccountImportPage.test.ts`
- Modify: `src/api/account-import.test.ts`
- Modify: `src/views/account/import/constants.ts`
- Modify: `src/views/account/import/components/AccountImportDrawer.vue`
- Modify: `src/views/account/import/composables/useAccountImportPage.ts`

- [ ] **Step 1: Write failing front-end behavior tests**

Replace the disabled-kind test in `constants.test.ts` with:

```ts
it("enables full params with ndjson and fixed Android guidance", () => {
  const disabledKinds = importKindOptions
    .filter(option => option.disabled)
    .map(option => option.value);
  const fullParams = importKindOptions.find(
    option => option.value === "fullparam"
  );

  assert.deepEqual(disabledKinds, []);
  assert.equal(fullParams?.accept, ".txt");
  assert.match(fullParams?.desc ?? "", /一行一个 JSON 对象/);
  assert.match(fullParams?.desc ?? "", /Android/);
});
```

Add this test to `AccountImportDrawer.test.ts`:

```ts
it("forwards the selected text file for full params upload", () => {
  assert.match(source, /file:\s*form\.file/);
  assert.doesNotMatch(
    source,
    /file:\s*form\.importKind\s*===\s*"json"\s*\?\s*form\.file\s*:\s*null/
  );
});
```

Create `src/views/account/import/composables/useAccountImportPage.test.ts`:

```ts
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

const source = readFileSync(
  new URL("./useAccountImportPage.ts", import.meta.url),
  "utf8"
);

describe("account import submit routing", () => {
  it("uploads JSON and selected full params files but keeps pasted text on text API", () => {
    assert.match(source, /payload\.importKind\s*===\s*"json"/);
    assert.match(
      source,
      /payload\.importKind\s*===\s*"fullparam"\s*&&\s*payload\.file\s*!=\s*null/
    );
    assert.match(source, /uploadAccountImportZip/);
    assert.match(source, /createAccountImportTask/);
  });
});
```

Extend the import list in `src/api/account-import.test.ts`:

```ts
import {
  createAccountImportTask,
  exportAccountImportTask,
  listAccountImportTasks,
  uploadAccountImportZip
} from "./account-import";
```

Add this API test:

```ts
it("uploads full params TXT with format 3 and the manually selected device", async () => {
  resetArmadaMock({
    id: 3,
    sourceFileName: "fullparams.txt",
    importFormat: 3,
    deviceOs: 2,
    accountType: 1,
    totalRows: 1,
    importedRows: 1,
    duplicateRows: 0,
    formatErrorRows: 0,
    status: 2
  });
  const file = new File(
    ["{\"jid\":\"919000000801\"}"],
    "fullparams.txt",
    { type: "text/plain" }
  );

  await uploadAccountImportZip({
    import_type: "全参账号",
    group: "默认分组",
    group_id: 1,
    device: "苹果",
    account_type: "个人",
    ip_allocation_mode: "smart",
    file
  });

  const [{ opts }] = armadaCalls();
  const form = (opts as { data: FormData }).data;
  assert.equal(form.get("importFormat"), "3");
  assert.equal(form.get("deviceOs"), "2");
  assert.equal(form.get("accountType"), "1");
  assert.equal((form.get("file") as File).name, "fullparams.txt");
});
```

- [ ] **Step 2: Run targeted front-end tests and verify the unsupported path fails**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/account/import/constants.test.ts src/views/account/import/components/AccountImportDrawer.test.ts src/views/account/import/composables/useAccountImportPage.test.ts src/api/account-import.test.ts
```

Expected: the constants and routing tests fail because full-params is disabled, the drawer drops its file, and the composable only uploads JSON files. The API multipart test may already pass because the existing upload helper is format-agnostic; it is a regression lock.

- [ ] **Step 3: Enable the option and make its constraints explicit**

Replace the `fullparam` entry in `constants.ts` with:

```ts
{
  label: "全参账号",
  value: "fullparam",
  desc: "支持粘贴或上传 TXT，一行一个 JSON 对象；上线协议固定为 Android，机型按人工选择展示。",
  accept: ".txt"
}
```

Do not force `form.device` to Android. The selected “安卓 / 苹果” value remains user-controlled.

- [ ] **Step 4: Preserve the selected TXT file in the drawer payload**

In `AccountImportDrawer.vue`, change only the emitted `file` field:

```ts
emit("submit", {
  importKind: form.importKind,
  filename: form.filename || null,
  groupId: Number(form.groupId),
  device: form.device,
  accountType: form.accountType,
  ipAllocationMode: form.ipAllocationMode,
  remark: form.remark.trim() || null,
  text: form.importKind === "json" ? null : form.text,
  file: form.file
});
```

The existing `handleUploadChange` already reads non-JSON TXT files into the textarea, so no second file-reading path is needed.

- [ ] **Step 5: Route uploaded full params through multipart and pasted full params through text**

Replace the initial JSON-only branch in `submitImport` with:

```ts
const shouldUploadFile =
  payload.importKind === "json" ||
  (payload.importKind === "fullparam" && payload.file != null);

if (shouldUploadFile) {
  if (!payload.file) {
    ElMessage.warning("请上传 JSON号 ZIP 包");
    return false;
  }
  await uploadAccountImportZip({
    import_type: importKindLabelMap[payload.importKind],
    group: groupName,
    group_id: payload.groupId,
    device: payload.device,
    account_type: payload.accountType,
    ip_allocation_mode: payload.ipAllocationMode,
    remark: payload.remark || null,
    file: payload.file
  });
} else {
  await createAccountImportTask({
    import_type: importKindLabelMap[payload.importKind],
    filename:
      payload.filename ||
      `粘贴${importKindLabelMap[payload.importKind]}_${Date.now()}.txt`,
    group: groupName,
    group_id: payload.groupId,
    device: payload.device,
    account_type: payload.accountType,
    service: null,
    ip_allocation_mode: payload.ipAllocationMode,
    remark: payload.remark || null,
    text: payload.text ?? ""
  });
}
```

Keep six-segment TXT uploads on their current text path; this task must not change existing six-segment source handling.

- [ ] **Step 6: Run targeted front-end tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/account/import/constants.test.ts src/views/account/import/components/AccountImportDrawer.test.ts src/views/account/import/composables/useAccountImportPage.test.ts src/api/account-import.test.ts
```

Expected: exit code 0; full-params is enabled, uploaded TXT preserves its `File`, pasted text still uses the text request, and device `苹果` maps to `deviceOs=2` without changing protocol behavior.

- [ ] **Step 7: Run front-end type and build gates**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
pnpm typecheck
pnpm build
```

Expected: both commands exit 0. Existing third-party build warnings are acceptable only if the build succeeds and no new warning points to the modified account-import files.

- [ ] **Step 8: Commit the front-end slice**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git add src/views/account/import/constants.ts src/views/account/import/constants.test.ts src/views/account/import/components/AccountImportDrawer.vue src/views/account/import/components/AccountImportDrawer.test.ts src/views/account/import/composables/useAccountImportPage.ts src/views/account/import/composables/useAccountImportPage.test.ts src/api/account-import.test.ts
git commit -m "feat: enable full params account imports"
```

## Task 7: Run Cross-Repository Regression Gates and Record Evidence

**Files:**

- Modify: `.harness/changes/2026-07-30-full-params-android-import.md`

- [ ] **Step 1: Run the complete focused backend test set**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=FullParamsToSixConverterTest,AccountImportParserTest,AccountImportRowWriterTest,AccountImportServiceImplDbTest,AccountImportControllerDbTest,AccountOnlineCommandServiceImplTest,AccountImportOnlineDispatcherDbTest test
```

Expected: exit code 0 with all named test classes passing. This is local H2/test-scope validation and must not connect to a remote or production database.

- [ ] **Step 2: Run broader account-import backend regression**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=FullParamsToSixConverterTest,AccountImportControllerDbTest,AccountImportOnlineDispatcherDbTest,AccountImportListMapperDbTest,AccountImportWriteMapperDbTest,AccountImportParserTest,AccountImportServiceImplDbTest,AccountImportRowWriterTest,AccountImportExportFilenameTest,AccountImportLoginResultSettlerTest test
```

Expected: exit code 0 with the complete account-import parser, persistence, export, online-dispatch, and settlement regression set green.

- [ ] **Step 3: Run the account-import front-end regression set**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/account-import.test.ts src/views/account/import/*.test.ts src/views/account/import/components/*.test.ts src/views/account/import/composables/*.test.ts
pnpm typecheck
pnpm build
```

Expected: all Node tests pass; typecheck and build exit 0.

- [ ] **Step 4: Check patches for whitespace and accidental secrets**

Run in `armada`:

```bash
git diff --check e398f2a..HEAD
rg -n '7-21飞行非洲全参|7-28佛爷|7-29-小米|accessToken|refreshToken' armada-api .harness/changes/2026-07-30-full-params-android-import.md
```

Run in `wheel-saas-pure-web`:

```bash
git diff --check HEAD~1..HEAD
rg -n '7-21飞行非洲全参|7-28佛爷|7-29-小米|accessToken|refreshToken' src
```

Expected: both diff checks exit 0 and the scans return no matches. The approved design mentions sample filenames only in its non-sensitive row-count section and is intentionally excluded from this implementation scan.

- [ ] **Step 5: Update the change record with real evidence**

In `.harness/changes/2026-07-30-full-params-android-import.md`:

- mark implementation tasks complete only when their commits exist;
- replace the design-only verification note with each exact command, exit status, and test count;
- record both backend and front-end commit hashes;
- leave deployment as “未部署” until a target environment is explicitly confirmed and verified.

Do not claim that the 10-second scheduled trigger was observed in a deployed environment based only on `dispatcher.dispatchOnce()` tests. Local proof establishes queue/dispatcher compatibility; deployed timing requires separate test-environment confirmation.

- [ ] **Step 6: Commit the final evidence record**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add .harness/changes/2026-07-30-full-params-android-import.md
git commit -m "docs: record full params import verification"
```

## Test-Environment Acceptance After Implementation

This section is not authorized by plan execution alone. Before SSH, deployment, remote API calls, or data import, confirm the exact test environment and deployment target with the user as required by `AGENTS.md`.

Once confirmed:

1. Deploy the backend before enabling/deploying the front end; no protocol service deployment is needed.
2. Import a small synthetic or user-approved full-params TXT containing at least one valid row and one intentionally invalid row.
3. Verify the batch shows `导入类型=全参账号` and the manually selected device.
4. Verify the valid detail starts `QUEUED` and is dispatched within the existing scheduler window without any immediate-dispatch endpoint.
5. Verify the account routes to Android and the online command uses six-segment credentials.
6. Verify the invalid row is skipped with a field-only error message.
7. Export all/success/fail scopes and confirm the output contains original full-params JSON rows, never normalized six JSON.
8. Do not include real credential values in screenshots, logs, issue comments, or the change record.
