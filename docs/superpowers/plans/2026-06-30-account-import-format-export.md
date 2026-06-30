# Account Import Original-Format Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Export account-import batches in the same container format used at import time: ZIP imports export ZIP files, TXT/pasted imports export TXT files, while preserving existing export scopes.

**Architecture:** Persist each newly imported detail row's original payload and source entry name, plus a batch-level source file type. Replace the CSV export service with a file export object that builds ZIP/TXT bytes from the persisted detail rows. Update the front-end API/composable to download blobs and use the back-end filename.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis XML, Flyway, JUnit/MockMvc, Vue 3, TypeScript, Axios wrapper, Element Plus.

---

## File Map

Back end in `/Users/daishuaishuai/IdeaProjects/armada`:

- Create `armada-api/src/main/resources/db/migration/V020__account_import_original_payload.sql`
  Adds `account_import_batch.source_file_type`, `account_import_detail.raw_payload`, and `account_import_detail.source_entry_name`.
- Create `armada-api/src/main/java/com/armada/account/model/entity/SourceFileType.java`
  Central constants and validation for `ZIP` / `TXT`.
- Create `armada-api/src/main/java/com/armada/account/model/vo/AccountImportExportFile.java`
  File response object: filename, content type, bytes.
- Create `armada-api/src/main/java/com/armada/account/model/vo/AccountImportExportRow.java`
  MyBatis export projection including raw payload. Do not reuse public detail VO.
- Modify `armada-api/src/main/java/com/armada/account/model/entity/AccountImportBatch.java`
  Add `sourceFileType`.
- Modify `armada-api/src/main/java/com/armada/account/model/entity/AccountImportDetail.java`
  Add `rawPayload` and `sourceEntryName`.
- Modify `armada-api/src/main/java/com/armada/account/model/entity/ParsedEntry.java`
  Carry `rawPayload` and `sourceEntryName` from parser to persistence.
- Modify `armada-api/src/main/java/com/armada/account/service/AccountImportParser.java`
  Preserve raw payloads for ZIP/TXT/JSON object and JSON array entries.
- Modify `armada-api/src/main/java/com/armada/account/service/AccountImportService.java`
  Replace `exportDetailsCsv` with `exportDetails`.
- Modify `armada-api/src/main/java/com/armada/account/service/impl/AccountImportServiceImpl.java`
  Persist new fields and generate ZIP/TXT exports.
- Modify `armada-api/src/main/java/com/armada/account/controller/AccountImportController.java`
  Return file bytes with dynamic content type and filename.
- Modify `armada-api/src/main/java/com/armada/account/mapper/AccountImportBatchMapper.java`
  No new method needed; entity field is returned by `selectById`.
- Modify `armada-api/src/main/java/com/armada/account/mapper/AccountImportDetailMapper.java`
  Add `selectExportRowsByBatch`.
- Modify `armada-api/src/main/resources/mapper/account/AccountImportBatchMapper.xml`
  Insert `source_file_type`.
- Modify `armada-api/src/main/resources/mapper/account/AccountImportDetailMapper.xml`
  Insert detail raw fields and add export-row SELECT.
- Modify tests:
  - `armada-api/src/test/java/com/armada/account/service/AccountImportParserTest.java`
  - `armada-api/src/test/java/com/armada/account/service/AccountImportServiceImplDbTest.java`
  - `armada-api/src/test/java/com/armada/account/mapper/AccountImportListMapperDbTest.java`
  - `armada-api/src/test/java/com/armada/account/controller/AccountImportControllerDbTest.java`

Front end in `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`:

- Modify `src/api/account-import.ts`
  Change export API to blob download and parse `Content-Disposition`.
- Modify `src/views/account/import/composables/useAccountImportPage.ts`
  Replace CSV-only download helper with generic blob download.
- Modify `src/api/__tests__/http-test-double.ts`
  Allow tests to simulate response headers via `beforeResponseCallback`.
- Create `src/api/account-import.test.ts`
  Cover export scope, blob response type, and filename parsing.

---

## Task 1: Schema And Persistence Fields

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V020__account_import_original_payload.sql`
- Create: `armada-api/src/main/java/com/armada/account/model/entity/SourceFileType.java`
- Modify: `armada-api/src/main/java/com/armada/account/model/entity/AccountImportBatch.java`
- Modify: `armada-api/src/main/java/com/armada/account/model/entity/AccountImportDetail.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountImportBatchMapper.xml`
- Modify: `armada-api/src/main/resources/mapper/account/AccountImportDetailMapper.xml`
- Test: `armada-api/src/test/java/com/armada/account/mapper/AccountImportListMapperDbTest.java`

- [ ] **Step 1: Write the failing mapper persistence test**

Add this test helper overload and test to `AccountImportListMapperDbTest`.

```java
private Long createBatch(Long groupId, String sourceFileName, String sourceFileType,
                         int total, int imported, int duplicate, int formatError, long now) {
    AccountImportBatch b = new AccountImportBatch();
    b.setAccountGroupId(groupId);
    b.setSourceFileName(sourceFileName);
    b.setSourceFileType(sourceFileType);
    b.setImportFormat(2);
    b.setDeviceOs(1);
    b.setAccountType(2);
    b.setTotalRows(total);
    b.setImportedRows(imported);
    b.setDuplicateRows(duplicate);
    b.setFormatErrorRows(formatError);
    b.setStatus(2);
    b.setCreatedAt(now);
    batchMapper.insert(b);
    return b.getId();
}

@Test
void mapper_persistsOriginalExportMetadata() {
    long now = System.currentTimeMillis();
    Long groupId = createGroup("分组-original-export-meta", now);
    Long batchId = createBatch(groupId, "accounts.zip", "ZIP", 1, 1, 0, 0, now);

    AccountImportDetail detail = detail(batchId, 1, "861399900001", 1, null, now);
    detail.setRawPayload("{\"wid\":\"861399900001\",\"registrationId\":1}");
    detail.setSourceEntryName("861399900001.json");
    detailMapper.batchInsert(List.of(detail));

    AccountImportBatch savedBatch = batchMapper.selectById(batchId);
    assertThat(savedBatch.getSourceFileType()).isEqualTo("ZIP");

    List<AccountImportExportRow> rows = detailMapper.selectExportRowsByBatch(batchId, "all");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).rawPayload()).contains("\"861399900001\"");
    assertThat(rows.get(0).sourceEntryName()).isEqualTo("861399900001.json");
}
```

Add imports:

```java
import com.armada.account.model.vo.AccountImportExportRow;
```

- [ ] **Step 2: Run the failing mapper test**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
armada-api/dbtest.sh AccountImportListMapperDbTest#mapper_persistsOriginalExportMetadata
```

Expected: FAIL because `sourceFileType`, `rawPayload`, `sourceEntryName`, and `selectExportRowsByBatch` do not exist.

- [ ] **Step 3: Add the migration**

Create `V020__account_import_original_payload.sql`:

```sql
-- Persist original account-import payloads so export can rebuild ZIP/TXT files.

SET @ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE() AND table_name = 'account_import_batch' AND column_name = 'source_file_type') = 0,
    'ALTER TABLE account_import_batch ADD COLUMN source_file_type VARCHAR(16) DEFAULT NULL COMMENT ''原始导入容器:ZIP/TXT'' AFTER source_file_name',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND column_name = 'raw_payload') = 0,
    'ALTER TABLE account_import_detail ADD COLUMN raw_payload MEDIUMTEXT DEFAULT NULL COMMENT ''单条原始导入内容,敏感,不得进入日志或列表响应'' AFTER ws_phone',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND column_name = 'source_entry_name') = 0,
    'ALTER TABLE account_import_detail ADD COLUMN source_entry_name VARCHAR(512) DEFAULT NULL COMMENT ''原始条目名:zip内路径或line-N'' AFTER raw_payload',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
```

- [ ] **Step 4: Add Java model fields and source type constants**

Create `SourceFileType.java`:

```java
package com.armada.account.model.entity;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/**
 * 原始导入容器类型。决定账号导入批次导出为 ZIP 还是 TXT。
 */
public final class SourceFileType {

    public static final String ZIP = "ZIP";
    public static final String TXT = "TXT";

    private SourceFileType() {
    }

    public static void requireSupported(String value) {
        if (ZIP.equals(value) || TXT.equals(value)) {
            return;
        }
        throw new BusinessException(ErrorCode.VALIDATION, "该批次缺少原始导出材料");
    }
}
```

Add to `AccountImportBatch`:

```java
/** 原始导入容器:ZIP/TXT;历史批次为 NULL。 */
private String sourceFileType;

public String getSourceFileType() {
    return sourceFileType;
}

public void setSourceFileType(String sourceFileType) {
    this.sourceFileType = sourceFileType;
}
```

Place the field after `sourceFileName`.

Add to `AccountImportDetail`:

```java
/** 单条原始导入内容,敏感,不得进入日志或列表响应。 */
private String rawPayload;

/** 原始条目名:zip 内路径或 line-N。 */
private String sourceEntryName;

public String getRawPayload() {
    return rawPayload;
}

public void setRawPayload(String rawPayload) {
    this.rawPayload = rawPayload;
}

public String getSourceEntryName() {
    return sourceEntryName;
}

public void setSourceEntryName(String sourceEntryName) {
    this.sourceEntryName = sourceEntryName;
}
```

Place the fields after `wsPhone`.

- [ ] **Step 5: Add mapper SQL support**

Modify `AccountImportBatchMapper.xml` insert columns:

```xml
INSERT INTO account_import_batch (
  account_group_id, source_file_name, source_file_type,
  import_format, device_os, account_type, ip_region,
  total_rows, imported_rows, duplicate_rows, format_error_rows,
  status, created_at, created_by
) VALUES (
  #{accountGroupId}, #{sourceFileName}, #{sourceFileType},
  #{importFormat}, #{deviceOs}, #{accountType}, #{ipRegion},
  #{totalRows}, #{importedRows}, #{duplicateRows}, #{formatErrorRows},
  #{status}, #{createdAt}, #{createdBy}
)
```

Modify `AccountImportDetailMapper.xml` insert columns and values:

```xml
INSERT INTO account_import_detail (
  batch_id, line_no, ws_phone, raw_payload, source_entry_name, account_id,
  parse_result, fail_reason, login_result,
  online_phase, online_dispatched_at, login_settled_at, dispatch_attempts, login_reason,
  created_at
) VALUES
<foreach collection="rows" item="r" separator=",">
  (
    #{r.batchId}, #{r.lineNo}, #{r.wsPhone}, #{r.rawPayload}, #{r.sourceEntryName}, #{r.accountId},
    #{r.parseResult}, #{r.failReason}, #{r.loginResult},
    COALESCE(#{r.onlinePhase}, 0), #{r.onlineDispatchedAt}, #{r.loginSettledAt},
    COALESCE(#{r.dispatchAttempts}, 0), #{r.loginReason},
    #{r.createdAt}
  )
</foreach>
```

Add the export select:

```xml
<select id="selectExportRowsByBatch" resultType="com.armada.account.model.vo.AccountImportExportRow">
  SELECT d.id,
         d.line_no AS lineNo,
         d.ws_phone AS wsPhone,
         d.parse_result AS parseResult,
         d.raw_payload AS rawPayload,
         d.source_entry_name AS sourceEntryName
  FROM account_import_detail d
  JOIN account_import_batch b ON d.batch_id = b.id AND d.tenant_id = b.tenant_id
  WHERE d.batch_id = #{batchId}
  <choose>
    <when test="scope == 'success'">AND d.parse_result = 1</when>
    <when test="scope == 'fail'">AND d.parse_result IN (2, 3, 4)</when>
  </choose>
  ORDER BY d.line_no ASC
</select>
```

Add to `AccountImportDetailMapper.java`:

```java
List<AccountImportExportRow> selectExportRowsByBatch(@Param("batchId") Long batchId,
                                                     @Param("scope") String scope);
```

Create `AccountImportExportRow.java`:

```java
package com.armada.account.model.vo;

/**
 * Export-only projection for rebuilding account-import ZIP/TXT files.
 * rawPayload is sensitive and must not be returned by list/detail APIs.
 */
public record AccountImportExportRow(
        Long id,
        Integer lineNo,
        String wsPhone,
        Integer parseResult,
        String rawPayload,
        String sourceEntryName
) {
}
```

- [ ] **Step 6: Run the mapper test and commit**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
armada-api/dbtest.sh AccountImportListMapperDbTest#mapper_persistsOriginalExportMetadata
```

Expected: PASS.

Commit only these files:

```bash
git add armada-api/src/main/resources/db/migration/V020__account_import_original_payload.sql \
  armada-api/src/main/java/com/armada/account/model/entity/SourceFileType.java \
  armada-api/src/main/java/com/armada/account/model/entity/AccountImportBatch.java \
  armada-api/src/main/java/com/armada/account/model/entity/AccountImportDetail.java \
  armada-api/src/main/java/com/armada/account/model/vo/AccountImportExportRow.java \
  armada-api/src/main/java/com/armada/account/mapper/AccountImportDetailMapper.java \
  armada-api/src/main/resources/mapper/account/AccountImportBatchMapper.xml \
  armada-api/src/main/resources/mapper/account/AccountImportDetailMapper.xml \
  armada-api/src/test/java/com/armada/account/mapper/AccountImportListMapperDbTest.java
git commit -m "feat: persist account import original payloads"
```

---

## Task 2: Parser Preserves Raw Payload And Source Entry Names

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/model/entity/ParsedEntry.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/AccountImportParser.java`
- Test: `armada-api/src/test/java/com/armada/account/service/AccountImportParserTest.java`

- [ ] **Step 1: Write failing parser tests**

Add these tests to `AccountImportParserTest`.

```java
@Test
void json_zipPreservesRawPayloadAndEntryName() throws Exception {
    String entryJson = nakedCredsObject("8613800138999");
    byte[] zipBytes = buildZip("folder/8613800138999.json", entryJson.getBytes());

    List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, zipBytes, null);

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).getRawPayload()).isEqualTo(entryJson);
    assertThat(entries.get(0).getSourceEntryName()).isEqualTo("folder/8613800138999.json");
}

@Test
void json_arrayTextPreservesEachElementPayload() {
    String first = nakedCredsObject("8613800138101");
    String second = nakedCredsObject("8613800138102");

    List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, "[" + first + "," + second + "]");

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).getRawPayload()).contains("\"8613800138101\"");
    assertThat(entries.get(0).getSourceEntryName()).isEqualTo("text-input[0]");
    assertThat(entries.get(1).getRawPayload()).contains("\"8613800138102\"");
    assertThat(entries.get(1).getSourceEntryName()).isEqualTo("text-input[1]");
}

@Test
void json_invalidTextKeepsRawPayloadForFailureExport() {
    String invalid = "[{not valid json}]";

    List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, invalid);

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).getParseError()).isNotNull();
    assertThat(entries.get(0).getRawPayload()).isEqualTo(invalid);
    assertThat(entries.get(0).getSourceEntryName()).isEqualTo("text-input");
}

@Test
void params_arrayTextPreservesRawPayloadAndEntryName() {
    List<ParsedEntry> entries = parser.parse(
            ImportFormat.PARAMS,
            null,
            "[{\"wid\":\"8613800138201\"},{\"wid\":\"8613800138202\"}]");

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).getRawPayload()).contains("\"8613800138201\"");
    assertThat(entries.get(0).getSourceEntryName()).isEqualTo("params-input[0]");
    assertThat(entries.get(1).getRawPayload()).contains("\"8613800138202\"");
    assertThat(entries.get(1).getSourceEntryName()).isEqualTo("params-input[1]");
}
```

- [ ] **Step 2: Run parser tests and verify failure**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=AccountImportParserTest test
```

Expected: FAIL because `ParsedEntry#getRawPayload` and `getSourceEntryName` do not exist.

- [ ] **Step 3: Add `ParsedEntry` fields**

Add fields and accessors to `ParsedEntry.java`:

```java
/**
 * 单条原始导入内容。用于按原导入格式导出,不得写入日志或普通列表接口。
 */
private String rawPayload;

/**
 * 原始来源条目名:zip 内 entry 路径或 text/params 的 line-N/source[index]。
 */
private String sourceEntryName;

public String getRawPayload() {
    return rawPayload;
}

public void setRawPayload(String rawPayload) {
    this.rawPayload = rawPayload;
}

public String getSourceEntryName() {
    return sourceEntryName;
}

public void setSourceEntryName(String sourceEntryName) {
    this.sourceEntryName = sourceEntryName;
}
```

- [ ] **Step 4: Update parser to populate raw fields**

In `AccountImportParser`, add helper methods:

```java
private String compactJson(JsonNode node) {
    try {
        return mapper.writeValueAsString(node);
    } catch (IOException e) {
        return node == null ? "" : node.toString();
    }
}

private ParsedEntry makeErrorEntry(String source, String error, String rawPayload) {
    ParsedEntry entry = new ParsedEntry();
    entry.setRaw(source);
    entry.setSourceEntryName(source);
    entry.setRawPayload(rawPayload);
    entry.setParseError(error);
    return entry;
}
```

Keep the old `makeErrorEntry(String source, String error)` as a delegating overload:

```java
private List<ParsedEntry> makeErrorEntry(String source, String error) {
    return Collections.singletonList(makeErrorEntry(source, error, source));
}
```

Change `parseJsonText`:

```java
private List<ParsedEntry> parseJsonText(String text, String source) {
    try {
        JsonNode root = mapper.readTree(text);
        if (root.isArray()) {
            return parseJsonArray(root, source);
        }
        if (root.isObject()) {
            return List.of(parseJsonNode(root, source, text, source));
        }
        return List.of(makeErrorEntry(source, "JSON 格式不支持:既不是对象也不是数组", text));
    } catch (IOException e) {
        log.warn("[AccountImportParser] JSON 解析失败 source={} error={}", source, e.getMessage());
        return List.of(makeErrorEntry(source, "JSON 解析失败: " + e.getMessage(), text));
    }
}
```

Change `parseJsonArray`:

```java
private List<ParsedEntry> parseJsonArray(JsonNode array, String source) {
    List<ParsedEntry> result = new ArrayList<>(array.size());
    for (int i = 0; i < array.size(); i++) {
        JsonNode node = array.get(i);
        String entryName = source + "[" + i + "]";
        result.add(parseJsonNode(node, entryName, compactJson(node), entryName));
    }
    return result;
}
```

Change `parseJsonNode` signature and body:

```java
private ParsedEntry parseJsonNode(JsonNode node, String source, String rawPayload, String sourceEntryName) {
    ParsedEntry entry = new ParsedEntry();
    entry.setRaw(source);
    entry.setSourceEntryName(sourceEntryName);
    entry.setRawPayload(rawPayload);
    entry.setData(node);
    entry.setWid(extractWid(node));
    String credError = checkJsonCredCompleteness(node);
    if (credError != null) {
        entry.setParseError(credError);
    }
    return entry;
}
```

Change `parseJsonZip`:

```java
String entryName = ze.getName();
byte[] content = zis.readAllBytes();
zis.closeEntry();
String entryText = new String(content, StandardCharsets.UTF_8);
result.addAll(parseJsonText(entryText, entryName));
```

Change `parseParamsNode` signature and call sites:

```java
private ParsedEntry parseParamsNode(JsonNode node, String source) {
    ParsedEntry entry = new ParsedEntry();
    entry.setRaw(source);
    entry.setSourceEntryName(source);
    entry.setRawPayload(compactJson(node));
    entry.setData(node);
    ...
}
```

Change PARAMS parse error handling to preserve the raw text:

```java
return List.of(makeErrorEntry("params-input", "JSON 解析失败: " + e.getMessage(), src));
```

- [ ] **Step 5: Run parser tests and commit**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=AccountImportParserTest test
```

Expected: PASS.

Commit:

```bash
git add armada-api/src/main/java/com/armada/account/model/entity/ParsedEntry.java \
  armada-api/src/main/java/com/armada/account/service/AccountImportParser.java \
  armada-api/src/test/java/com/armada/account/service/AccountImportParserTest.java
git commit -m "feat: preserve account import raw payloads"
```

---

## Task 3: Import Service Writes Source Type And Raw Payload

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountImportServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/account/service/AccountImportServiceImplDbTest.java`

- [ ] **Step 1: Write failing service persistence tests**

Add tests to `AccountImportServiceImplDbTest`.

```java
@Test
void import_textPersistsTxtSourceTypeAndRawPayload() {
    String first = "{\"wid\":\"8613861000001\",\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}";
    String second = "{\"wid\":\"8613861000002\",\"registrationId\":2,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}";
    var meta = new AccountImportDTO(null, 2, 1, 1, "美国", null, null);

    AccountImportBatchVO batch = service.importAccounts(meta, null, "[" + first + "," + second + "]");

    String sourceType = jdbcTemplate.queryForObject(
            "SELECT source_file_type FROM account_import_batch WHERE id = ?",
            String.class,
            batch.id());
    List<String> payloads = jdbcTemplate.query(
            "SELECT raw_payload FROM account_import_detail WHERE batch_id = ? ORDER BY line_no",
            (rs, rowNum) -> rs.getString("raw_payload"),
            batch.id());
    List<String> entryNames = jdbcTemplate.query(
            "SELECT source_entry_name FROM account_import_detail WHERE batch_id = ? ORDER BY line_no",
            (rs, rowNum) -> rs.getString("source_entry_name"),
            batch.id());

    assertThat(sourceType).isEqualTo("TXT");
    assertThat(payloads).hasSize(2);
    assertThat(payloads.get(0)).contains("8613861000001");
    assertThat(payloads.get(1)).contains("8613861000002");
    assertThat(entryNames).containsExactly("text-input[0]", "text-input[1]");
}

@Test
void import_zipPersistsZipSourceTypeAndEntryNames() throws Exception {
    String json = "{\"wid\":\"8613862000001\",\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}";
    byte[] zipBytes = buildZip("nested/8613862000001.json", json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var meta = new AccountImportDTO(null, 2, 1, 1, "美国", null, "accounts.zip");

    AccountImportBatchVO batch = service.importAccounts(meta, zipBytes, null);

    String sourceType = jdbcTemplate.queryForObject(
            "SELECT source_file_type FROM account_import_batch WHERE id = ?",
            String.class,
            batch.id());
    String payload = jdbcTemplate.queryForObject(
            "SELECT raw_payload FROM account_import_detail WHERE batch_id = ?",
            String.class,
            batch.id());
    String entryName = jdbcTemplate.queryForObject(
            "SELECT source_entry_name FROM account_import_detail WHERE batch_id = ?",
            String.class,
            batch.id());

    assertThat(sourceType).isEqualTo("ZIP");
    assertThat(payload).isEqualTo(json);
    assertThat(entryName).isEqualTo("nested/8613862000001.json");
}
```

Add helper:

```java
private byte[] buildZip(String entryName, byte[] content) throws Exception {
    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
    try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
        zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
        zos.write(content);
        zos.closeEntry();
    }
    return baos.toByteArray();
}
```

- [ ] **Step 2: Run service tests and verify failure**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
armada-api/dbtest.sh AccountImportServiceImplDbTest#import_textPersistsTxtSourceTypeAndRawPayload
armada-api/dbtest.sh AccountImportServiceImplDbTest#import_zipPersistsZipSourceTypeAndEntryNames
```

Expected: FAIL because import service does not set the new fields yet.

- [ ] **Step 3: Implement source type detection and detail persistence**

In `AccountImportServiceImpl`, add constants/helper:

```java
private String resolveSourceFileType(byte[] fileBytes, String text) {
    if (text != null && !text.isEmpty()) {
        return SourceFileType.TXT;
    }
    return isZipBytes(fileBytes) ? SourceFileType.ZIP : SourceFileType.TXT;
}

private boolean isZipBytes(byte[] bytes) {
    return bytes != null && bytes.length >= 2 && bytes[0] == 0x50 && bytes[1] == 0x4B;
}
```

Add import:

```java
import com.armada.account.model.entity.SourceFileType;
```

In `importAccounts`, compute once:

```java
String sourceFileType = resolveSourceFileType(fileBytes, text);
AccountImportBatch batch = buildBatch(meta, resolvedGroupId, entries.size(), now, sourceFileType);
```

Change `buildBatch` signature and set field:

```java
private AccountImportBatch buildBatch(AccountImportDTO meta, Long groupId, int total, long now, String sourceFileType) {
    AccountImportBatch b = new AccountImportBatch();
    b.setAccountGroupId(groupId);
    b.setSourceFileName(StringUtils.hasText(meta.sourceFileName())
            ? meta.sourceFileName() : SOURCE_FILE_DEFAULT);
    b.setSourceFileType(sourceFileType);
    ...
    return b;
}
```

Change `buildDetail` to accept `ParsedEntry`:

```java
private AccountImportDetail buildDetail(int lineNo, ParsedEntry entry,
                                         Long accountId, RowClassification cls, long now) {
    AccountImportDetail d = new AccountImportDetail();
    d.setLineNo(lineNo);
    d.setWsPhone(entry.getWid());
    d.setRawPayload(entry.getRawPayload());
    d.setSourceEntryName(entry.getSourceEntryName() != null
            ? entry.getSourceEntryName()
            : "line-" + lineNo);
    d.setAccountId(accountId);
    d.setParseResult(cls.result().getCode());
    d.setFailReason(cls.failReason());
    d.setOnlinePhase(cls.result() == ImportResult.SUCCESS
            ? AccountImportOnlinePhase.QUEUED
            : AccountImportOnlinePhase.SKIPPED);
    d.setCreatedAt(now);
    return d;
}
```

Update the call:

```java
AccountImportDetail detail = buildDetail(lineNo, entry, accountId,
        new RowClassification(result, failReason), now);
```

- [ ] **Step 4: Run service tests and commit**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
armada-api/dbtest.sh AccountImportServiceImplDbTest
```

Expected: PASS.

Commit:

```bash
git add armada-api/src/main/java/com/armada/account/service/impl/AccountImportServiceImpl.java \
  armada-api/src/test/java/com/armada/account/service/AccountImportServiceImplDbTest.java
git commit -m "feat: store account import source metadata"
```

---

## Task 4: Back-End ZIP/TXT Export Service And Controller

**Files:**
- Create: `armada-api/src/main/java/com/armada/account/model/vo/AccountImportExportFile.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/AccountImportService.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountImportServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/account/controller/AccountImportController.java`
- Modify: `armada-api/src/test/java/com/armada/account/mapper/AccountImportListMapperDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/account/controller/AccountImportControllerDbTest.java`

- [ ] **Step 1: Replace CSV service tests with file export tests**

In `AccountImportListMapperDbTest`, replace CSV-specific tests with these tests. Keep helper data creation, but update inserted details to set raw payloads.

```java
@Test
void service_exportDetails_txtScopeFail_exportsOriginalPayloadsOnly() {
    long now = System.currentTimeMillis();
    Long groupId = createGroup("分组-txt-export-fail", now);
    Long batchId = createBatch(groupId, "accounts.txt", "TXT", 4, 1, 1, 2, now);
    insertDetailsWithPayloads(batchId, now);

    AccountImportExportFile file = importService.exportDetails(batchId, "fail");

    assertThat(file.filename()).isEqualTo("account-import-" + batchId + "-fail.txt");
    assertThat(file.contentType()).isEqualTo("text/plain;charset=UTF-8");
    String body = new String(file.bytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(body).contains("raw-duplicate", "raw-format", "raw-incomplete");
    assertThat(body).doesNotContain("raw-success");
}

@Test
void service_exportDetails_zipScopeSuccess_exportsOnlySuccessEntries() throws Exception {
    long now = System.currentTimeMillis();
    Long groupId = createGroup("分组-zip-export-success", now);
    Long batchId = createBatch(groupId, "accounts.zip", "ZIP", 4, 1, 1, 2, now);
    insertDetailsWithPayloads(batchId, now);

    AccountImportExportFile file = importService.exportDetails(batchId, "success");

    assertThat(file.filename()).isEqualTo("account-import-" + batchId + "-success.zip");
    assertThat(file.contentType()).isEqualTo("application/zip");
    java.util.Map<String, String> entries = unzip(file.bytes());
    assertThat(entries).containsOnlyKeys("line-1.json");
    assertThat(entries.get("line-1.json")).isEqualTo("raw-success");
}

@Test
void service_exportDetails_missingOriginalPayload_throwsBusinessError() {
    long now = System.currentTimeMillis();
    Long groupId = createGroup("分组-missing-payload", now);
    Long batchId = createBatch(groupId, "old.csv", null, 1, 1, 0, 0, now);
    detailMapper.batchInsert(List.of(detail(batchId, 1, "861399900009", 1, null, now)));

    assertThatThrownBy(() -> importService.exportDetails(batchId, "all"))
            .isInstanceOf(com.armada.shared.exception.BusinessException.class)
            .hasMessageContaining("该批次缺少原始导出材料");
}
```

Add helpers:

```java
private void insertDetailsWithPayloads(Long batchId, long now) {
    List<AccountImportDetail> rows = new ArrayList<>();
    rows.add(detailWithPayload(batchId, 1, "861399100001", 1, null, "raw-success", "line-1.json", now));
    rows.add(detailWithPayload(batchId, 2, "861399200001", 2, "批内重复", "raw-duplicate", "line-2.json", now));
    rows.add(detailWithPayload(batchId, 3, "bad-phone-export", 3, "格式不合法", "raw-format", "line-3.json", now));
    rows.add(detailWithPayload(batchId, 4, "861399200003", 4, "缺 registrationId", "raw-incomplete", "line-4.json", now));
    detailMapper.batchInsert(rows);
}

private AccountImportDetail detailWithPayload(Long batchId, int lineNo, String phone, int result,
                                              String reason, String rawPayload, String sourceEntryName, long now) {
    AccountImportDetail d = detail(batchId, lineNo, phone, result, reason, now);
    d.setRawPayload(rawPayload);
    d.setSourceEntryName(sourceEntryName);
    return d;
}

private java.util.Map<String, String> unzip(byte[] bytes) throws Exception {
    java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
    try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
        java.util.zip.ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            entries.put(entry.getName(), new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            zis.closeEntry();
        }
    }
    return entries;
}
```

Add import:

```java
import com.armada.account.model.vo.AccountImportExportFile;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [ ] **Step 2: Write controller tests for dynamic file responses**

In `AccountImportControllerDbTest`, replace the CSV export test with:

```java
@Test
void get_exportTextImport_returnsTxtAttachment() throws Exception {
    MvcResult importResult = mockMvc.perform(multipart("/api/account-imports")
                    .param("importFormat", "2")
                    .param("deviceOs", "1")
                    .param("accountType", "1")
                    .param("text", VALID_JSON_TEXT)
                    .header(TENANT_HEADER, TENANT_CODE))
            .andReturn();

    Long batchId = objectMapper.readTree(importResult.getResponse().getContentAsString())
            .path("data").path("id").longValue();

    mockMvc.perform(get("/api/account-imports/{batchId}/export", batchId)
                    .param("scope", "all")
                    .header(TENANT_HEADER, TENANT_CODE))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/plain"))
            .andExpect(header().string("Content-Disposition",
                    org.hamcrest.Matchers.containsString("account-import-" + batchId + "-all.txt")))
            .andExpect(header().string("Content-Disposition",
                    org.hamcrest.Matchers.containsString("attachment")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("8613900000001")));
}

@Test
void get_exportZipImport_returnsZipAttachment() throws Exception {
    String json = "{\"wid\":\"8613900000099\",\"registrationId\":99,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}";
    MockMultipartFile file = new MockMultipartFile(
            "file",
            "accounts.zip",
            "application/zip",
            zip("8613900000099.json", json));

    MvcResult importResult = mockMvc.perform(multipart("/api/account-imports")
                    .file(file)
                    .param("importFormat", "2")
                    .param("deviceOs", "1")
                    .param("accountType", "1")
                    .header(TENANT_HEADER, TENANT_CODE))
            .andReturn();

    Long batchId = objectMapper.readTree(importResult.getResponse().getContentAsString())
            .path("data").path("id").longValue();

    MvcResult exportResult = mockMvc.perform(get("/api/account-imports/{batchId}/export", batchId)
                    .param("scope", "all")
                    .header(TENANT_HEADER, TENANT_CODE))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/zip"))
            .andExpect(header().string("Content-Disposition",
                    org.hamcrest.Matchers.containsString("account-import-" + batchId + "-all.zip")))
            .andReturn();

    java.util.Map<String, String> entries = unzip(exportResult.getResponse().getContentAsByteArray());
    assertThat(entries).containsEntry("8613900000099.json", json);
}
```

Add helpers:

```java
private byte[] zip(String entryName, String content) throws Exception {
    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
    try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
        zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
        zos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zos.closeEntry();
    }
    return baos.toByteArray();
}

private java.util.Map<String, String> unzip(byte[] bytes) throws Exception {
    java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
    try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
        java.util.zip.ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            entries.put(entry.getName(), new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            zis.closeEntry();
        }
    }
    return entries;
}
```

- [ ] **Step 3: Run export tests and verify failure**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
armada-api/dbtest.sh AccountImportListMapperDbTest#service_exportDetails_txtScopeFail_exportsOriginalPayloadsOnly
armada-api/dbtest.sh AccountImportControllerDbTest#get_exportTextImport_returnsTxtAttachment
```

Expected: FAIL because service/controller still expose CSV.

- [ ] **Step 4: Add export file record and service interface**

Create `AccountImportExportFile.java`:

```java
package com.armada.account.model.vo;

/**
 * Direct file response for account import export.
 */
public record AccountImportExportFile(
        String filename,
        String contentType,
        byte[] bytes
) {
}
```

Change `AccountImportService.java`:

```java
AccountImportExportFile exportDetails(Long batchId, String scope);
```

Remove `String exportDetailsCsv(Long batchId, String scope);`.

- [ ] **Step 5: Implement TXT/ZIP export generation**

In `AccountImportServiceImpl`, add imports:

```java
import com.armada.account.model.entity.SourceFileType;
import com.armada.account.model.vo.AccountImportExportFile;
import com.armada.account.model.vo.AccountImportExportRow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
```

Replace `exportDetailsCsv` with:

```java
@Override
public AccountImportExportFile exportDetails(Long batchId, String scope) {
    if (batchId == null) {
        throw new BusinessException(ErrorCode.VALIDATION, "batchId 不能为空");
    }
    AccountImportBatch batch = batchMapper.selectById(batchId);
    if (batch == null) {
        throw new BusinessException(ErrorCode.NOT_FOUND, "导入批次不存在");
    }
    SourceFileType.requireSupported(batch.getSourceFileType());

    String resolvedScope = (scope == null || scope.isBlank()) ? "all" : scope;
    List<AccountImportExportRow> rows = detailMapper.selectExportRowsByBatch(batchId, resolvedScope);
    ensureExportRowsHavePayload(rows);

    if (SourceFileType.ZIP.equals(batch.getSourceFileType())) {
        return new AccountImportExportFile(
                "account-import-" + batchId + "-" + resolvedScope + ".zip",
                "application/zip",
                buildZipExport(rows));
    }
    return new AccountImportExportFile(
            "account-import-" + batchId + "-" + resolvedScope + ".txt",
            "text/plain;charset=UTF-8",
            buildTextExport(rows));
}

private void ensureExportRowsHavePayload(List<AccountImportExportRow> rows) {
    for (AccountImportExportRow row : rows) {
        if (row.rawPayload() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "该批次缺少原始导出材料");
        }
    }
}

private byte[] buildTextExport(List<AccountImportExportRow> rows) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < rows.size(); i++) {
        if (i > 0) {
            sb.append('\n');
        }
        sb.append(rows.get(i).rawPayload());
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
}

private byte[] buildZipExport(List<AccountImportExportRow> rows) {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
         ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
        LinkedHashSet<String> usedNames = new LinkedHashSet<>();
        for (AccountImportExportRow row : rows) {
            String entryName = uniqueEntryName(resolveEntryName(row), usedNames);
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(row.rawPayload().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        zos.finish();
        return baos.toByteArray();
    } catch (IOException e) {
        throw new IllegalStateException("导出文件生成失败", e);
    }
}

private String resolveEntryName(AccountImportExportRow row) {
    if (StringUtils.hasText(row.sourceEntryName())) {
        String name = row.sourceEntryName();
        return name.endsWith(".json") ? name : name + ".json";
    }
    String phone = StringUtils.hasText(row.wsPhone()) ? row.wsPhone() : "unknown";
    return "line-" + row.lineNo() + "-" + phone + ".json";
}

private String uniqueEntryName(String preferredName, LinkedHashSet<String> usedNames) {
    if (usedNames.add(preferredName)) {
        return preferredName;
    }
    int dot = preferredName.lastIndexOf('.');
    String base = dot >= 0 ? preferredName.substring(0, dot) : preferredName;
    String ext = dot >= 0 ? preferredName.substring(dot) : "";
    int index = 2;
    while (true) {
        String candidate = base + "-" + index + ext;
        if (usedNames.add(candidate)) {
            return candidate;
        }
        index++;
    }
}
```

Remove unused CSV constants and helpers from `AccountImportServiceImpl`:

```java
CSV_DATETIME_FMT
CSV_BOM
CSV_HEADER
formatEpoch
csvEscape
```

- [ ] **Step 6: Update controller file response**

In `AccountImportController.exportDetails`, replace CSV handling with:

```java
AccountImportExportFile file = service.exportDetails(batchId, scope);

HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.parseMediaType(file.contentType()));
headers.setContentDisposition(
        org.springframework.http.ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build());

return ResponseEntity.ok().headers(headers).body(file.bytes());
```

Add import:

```java
import com.armada.account.model.vo.AccountImportExportFile;
```

Remove local CSV string/body code.

- [ ] **Step 7: Run export tests and commit**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
armada-api/dbtest.sh AccountImportListMapperDbTest
armada-api/dbtest.sh AccountImportControllerDbTest
```

Expected: PASS.

Commit:

```bash
git add armada-api/src/main/java/com/armada/account/model/vo/AccountImportExportFile.java \
  armada-api/src/main/java/com/armada/account/service/AccountImportService.java \
  armada-api/src/main/java/com/armada/account/service/impl/AccountImportServiceImpl.java \
  armada-api/src/main/java/com/armada/account/controller/AccountImportController.java \
  armada-api/src/test/java/com/armada/account/mapper/AccountImportListMapperDbTest.java \
  armada-api/src/test/java/com/armada/account/controller/AccountImportControllerDbTest.java
git commit -m "feat: export account imports as original file type"
```

---

## Task 5: Front-End Blob Download And Filename Parsing

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/account-import.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/import/composables/useAccountImportPage.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/__tests__/http-test-double.ts`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/account-import.test.ts`

- [ ] **Step 1: Write the failing API test**

Create `src/api/account-import.test.ts`:

```ts
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { httpCalls, resetHttpMock } from "./__tests__/http-test-double.ts";
import { exportAccountImportTask } from "./account-import.ts";

describe("account import API", () => {
  it("downloads exports as blob and uses backend filename", async () => {
    const blob = new Blob(["payload"], { type: "application/zip" });
    resetHttpMock({
      data: blob,
      headers: {
        "content-disposition":
          "attachment; filename*=UTF-8''account-import-9-success.zip"
      }
    });

    const result = await exportAccountImportTask(9, "SUCCESS");

    assert.equal(result.filename, "account-import-9-success.zip");
    assert.equal(result.blob, blob);
    assert.deepEqual(httpCalls(), [
      {
        method: "get",
        url: "/api/account-imports/9/export",
        opts: { params: { scope: "success" }, responseType: "blob" },
        configKeys: ["beforeResponseCallback"]
      }
    ]);
  });

  it("falls back to txt filename when content disposition is absent", async () => {
    const blob = new Blob(["payload"], { type: "text/plain" });
    resetHttpMock({ data: blob, headers: {} });

    const result = await exportAccountImportTask(12, "FAIL");

    assert.equal(result.filename, "account-import-12-fail.txt");
    assert.equal(result.blob, blob);
  });
});
```

- [ ] **Step 2: Run the failing front-end test**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/account-import.test.ts
```

Expected: FAIL because `AccountImportExport` still has `content`, export uses `responseType: "text"`, and the HTTP test double does not expose headers.

- [ ] **Step 3: Enhance the HTTP test double**

Replace `src/api/__tests__/http-test-double.ts` with:

```ts
interface HttpCall {
  method: string;
  url: string;
  opts?: unknown;
  configKeys?: string[];
}

interface MockHttpResponse {
  data: unknown;
  headers?: Record<string, string>;
}

let response: unknown;
let calls: HttpCall[] = [];

export function resetHttpMock(nextResponse: unknown): void {
  response = nextResponse;
  calls = [];
}

export function httpCalls(): HttpCall[] {
  return [...calls];
}

function isMockHttpResponse(value: unknown): value is MockHttpResponse {
  return (
    typeof value === "object" &&
    value !== null &&
    "data" in value
  );
}

export const http = {
  async request<T>(
    method: string,
    url: string,
    opts?: unknown,
    config?: { beforeResponseCallback?: (response: unknown) => void }
  ): Promise<T> {
    const call: HttpCall = {
      method,
      url,
      opts
    };
    if (config) {
      call.configKeys = Object.keys(config);
    }
    calls.push(call);
    if (isMockHttpResponse(response)) {
      config?.beforeResponseCallback?.({
        headers: response.headers ?? {},
        data: response.data
      });
      return response.data as T;
    }
    return response as T;
  }
};
```

- [ ] **Step 4: Update account import API export types**

In `src/api/account-import.ts`, change export interface:

```ts
export interface AccountImportExport {
  filename: string;
  blob: Blob;
}
```

Add filename parser helpers:

```ts
function contentDispositionFilename(disposition?: string): string | null {
  if (!disposition) return null;
  const utf8Match = disposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8Match?.[1]) {
    return decodeURIComponent(utf8Match[1].trim().replace(/^"|"$/g, ""));
  }
  const asciiMatch = disposition.match(/filename="?([^";]+)"?/i);
  return asciiMatch?.[1]?.trim() ?? null;
}

function fallbackExportFilename(id: number, scope: string, blob: Blob): string {
  const extension = blob.type.includes("zip") ? "zip" : "txt";
  return `account-import-${id}-${scope}.${extension}`;
}
```

Replace `exportAccountImportTask`:

```ts
export function exportAccountImportTask(
  id: number,
  kind: string
): Promise<AccountImportExport> {
  const scope = exportScope(kind);
  let disposition = "";
  return http
    .request<Blob>(
      "get",
      `/api/account-imports/${id}/export`,
      {
        params: { scope },
        responseType: "blob"
      },
      {
        beforeResponseCallback: response => {
          const headers = (response as { headers?: Record<string, string> }).headers;
          disposition =
            headers?.["content-disposition"] ??
            headers?.["Content-Disposition"] ??
            "";
        }
      }
    )
    .then(blob => ({
      filename:
        contentDispositionFilename(disposition) ??
        fallbackExportFilename(id, scope, blob),
      blob
    }));
}
```

- [ ] **Step 5: Update the page download helper**

In `useAccountImportPage.ts`, remove:

```ts
const BOM = String.fromCharCode(0xfeff);
```

Replace `downloadCsv` with:

```ts
function downloadFile(filename: string, blob: Blob): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
```

Update `exportTask`:

```ts
const response = await exportAccountImportTask(row.id, kind);
downloadFile(response.filename || `account-import-${row.id}-${kind}.txt`, response.blob);
ElMessage.success("导出文件已生成");
```

- [ ] **Step 6: Run front-end tests and typecheck, then commit**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/account-import.test.ts
pnpm typecheck
```

Expected: the account import API test PASSes; `pnpm typecheck` PASSes.

Commit in the front-end repo:

```bash
git add src/api/account-import.ts \
  src/views/account/import/composables/useAccountImportPage.ts \
  src/api/__tests__/http-test-double.ts \
  src/api/account-import.test.ts
git commit -m "feat: download account import exports as files"
```

---

## Task 6: End-To-End Verification And Cleanup

**Files:**
- Verify only unless tests expose a missed compile or contract issue.

- [ ] **Step 1: Run targeted back-end tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
armada-api/dbtest.sh AccountImportParserTest
armada-api/dbtest.sh AccountImportServiceImplDbTest
armada-api/dbtest.sh AccountImportListMapperDbTest
armada-api/dbtest.sh AccountImportControllerDbTest
```

Expected: all PASS.

- [ ] **Step 2: Run broader back-end account tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest='*Account*Test' test
```

Expected: all account tests PASS.

- [ ] **Step 3: Run front-end verification**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/account-import.test.ts
pnpm typecheck
pnpm build
```

Expected: test, typecheck, and build PASS.

- [ ] **Step 4: Inspect final diffs for sensitive payload leaks**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
rg -n "rawPayload\\)|getRawPayload\\(|raw_payload|creds_json" armada-api/src/main/java armada-api/src/main/resources/mapper/account
```

Expected:
- `raw_payload` appears in mapper insert and export-only select.
- `getRawPayload()` appears only in detail persistence/export code.
- No log line prints `rawPayload`, `raw_payload`, or `creds_json` values.

- [ ] **Step 5: Check repo status and commit any verification fixes**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git status --short
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git status --short
```

Expected: only intentional committed changes remain. If verification produced small fixes in the account import export files, commit those exact paths. For a back-end fix use:

```bash
git add armada-api/src/main/java/com/armada/account/service/impl/AccountImportServiceImpl.java \
  armada-api/src/main/java/com/armada/account/controller/AccountImportController.java \
  armada-api/src/main/resources/mapper/account/AccountImportDetailMapper.xml
git commit -m "fix: align account import export verification"
```

For a front-end fix use:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git add src/api/account-import.ts src/views/account/import/composables/useAccountImportPage.ts
git commit -m "fix: align account import export download"
```
