# Group Link Import Invalid Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Treat a WhatsApp invite link with no public-page group name as `失败/链接失效` and leave `group_link` unchanged.

**Architecture:** Keep the transactional batch import and `LineImporter` skeleton, but detect public-page validity before changing the main table. Reject active import duplicates first; for new, soft-deleted, or ungrouped links, require a non-null `waSubject`, then reuse the same metadata when writing `group_link_preview`.

**Tech Stack:** Java 17, Spring Boot, MyBatis, JUnit 5, Mockito, AssertJ, Testcontainers/MySQL, Flyway.

---

## File map

- Modify `armada-api/src/test/java/com/armada/group/service/impl/GroupLinkImportServiceImplTest.java`: behavior tests.
- Modify `armada-api/src/test/java/com/armada/group/service/impl/GroupLinkImportServiceDbTest.java`: persisted invariants.
- Modify `armada-api/src/test/java/com/armada/group/mapper/GroupListDataModelMigrationDbTest.java`: comment expectations.
- Modify `armada-api/src/main/java/com/armada/group/model/enums/GroupLinkImportFailReason.java`: new failure reason.
- Modify `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkImportServiceImpl.java`: detect-before-mutate flow.
- Modify `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkImportResultVO.java`: result documentation.
- Modify `armada-api/src/main/java/com/armada/group/mapper/GroupLinkImportDetailMapper.java`: mapper documentation.
- Modify `armada-api/src/main/resources/mapper/group/GroupLinkImportDetailMapper.xml`: SQL comments.
- Create `armada-api/src/main/resources/db/migration/V054__group_link_import_invalid_reason.sql`: database comments.

### Task 1: Write failing service tests

- [ ] **Step 1: Stub valid metadata for existing success tests**

Add `@BeforeEach` and a lenient default stub in `GroupLinkImportServiceImplTest` so existing success scenarios explicitly represent valid links:

```java
@BeforeEach
void stubValidInvitePage() {
    lenient().when(invitePageFetcher.fetch(anyString())).thenAnswer(invocation -> {
        String url = invocation.getArgument(0);
        String code = url.substring(url.lastIndexOf('/') + 1);
        return new GroupInvitePageMetadata(code, "有效群名", null);
    });
}
```

Add static import `org.mockito.Mockito.lenient` and import `org.junit.jupiter.api.BeforeEach`. Test-specific stubs declared inside tests override this default.

- [ ] **Step 2: Replace the old fetch-failure expectation**

Replace `invitePageFetchFailure_doesNotFailImport` with a test named `invitePageFetchFailure_marksInvalid_andDoesNotInsertLink`. Stub `fetch` to throw, then assert:

```java
assertThat(result.successRows()).isZero();
assertThat(result.failedRows()).isEqualTo(1);
assertThat(result.errors()).containsExactly("第 1 行：链接失效");
verify(groupLinkMapper, never()).insert(any());
verify(previewMapper, never()).upsertInvitePageMetadata(any());
```

Capture the inserted detail list and assert `result=FAILED`, `failReason=LINK_INVALID`, and `groupLinkId=null`.

- [ ] **Step 3: Add focused invalid variants**

Add tests for:

```text
metadata has avatar but waSubject is null -> invalid, no insert
soft-deleted existing link has no waSubject -> invalid, never adoptToLabel
active labelId=null link has no waSubject -> invalid, never adoptActiveIntoImport
active link already in an import label -> duplicate, never fetch public page
```

- [ ] **Step 4: Run the unit test and verify RED**

Run from `armada-api/`:

```bash
mvn -q -Dtest=GroupLinkImportServiceImplTest test
```

Expected: FAIL because current code mutates `group_link` before fetching and counts missing metadata as success.

### Task 2: Write failing DB and schema tests

- [ ] **Step 1: Stub valid metadata for existing DB success tests**

Add this `@BeforeEach` to `GroupLinkImportServiceDbTest`:

```java
@BeforeEach
void stubValidInvitePage() {
    when(invitePageFetcher.fetch(anyString())).thenAnswer(invocation -> {
        String url = invocation.getArgument(0);
        String code = url.substring(url.lastIndexOf('/') + 1);
        return new GroupInvitePageMetadata(code, "有效群名", null);
    });
}
```

- [ ] **Step 2: Add invalid main-table invariants**

Add an invalid-new-link test that stubs metadata with `waSubject=null` and asserts:

```java
assertThat(result.successRows()).isZero();
assertThat(result.failedRows()).isEqualTo(1);
assertThat(groupLinkMapper.selectAnyByUrl(url)).isNull();
assertThat(detailFailReason(result.batchId(), 1))
        .isEqualTo(GroupLinkImportFailReason.LINK_INVALID);
assertThat(detailGroupLinkId(result.batchId(), 1)).isNull();
```

Add a JDBC `detailFailReason` helper. Add two more tests proving a soft-deleted link remains deleted and an active ungrouped link keeps `label_id/import_batch_id=null` when the public page has no subject.

- [ ] **Step 3: Change schema comment expectations**

In `GroupListDataModelMigrationDbTest` expect:

```java
assertThat(columnComment("group_link_import_batch", "failed_rows"))
        .isEqualTo("失败总数(重复 + 格式错误 + 链接失效)");
assertThat(columnComment("group_link_import_detail", "fail_reason"))
        .isEqualTo("失败原因:重复/格式错误/链接失效;成功时为空");
```

- [ ] **Step 4: Run the DB tests and verify RED**

```bash
mvn -q -Dtest=GroupLinkImportServiceDbTest,GroupListDataModelMigrationDbTest test
```

Expected: FAIL because invalid links currently mutate the main table and V053 leaves the old comments.

### Task 3: Implement detect-before-mutate

- [ ] **Step 1: Add the failure reason**

In `GroupLinkImportFailReason` add:

```java
public static final String LINK_INVALID = "链接失效";
```

- [ ] **Step 2: Refactor `persist` decision order**

Use this order in `GroupLinkImportServiceImpl.persist`:

```java
GroupLink existing = groupLinkMapper.selectAnyByUrl(url);
if (existing != null && existing.getDeletedAt() == null && existing.getLabelId() != null) {
    return duplicateResult();
}
GroupInvitePageMetadata metadata = fetchInvitePageMetadata(url);
if (metadata == null || metadata.waSubject() == null) {
    return invalidResult();
}
if (existing == null) {
    // existing insert logic, return success with metadata
} else if (existing.getDeletedAt() != null) {
    // existing revive logic, return success with metadata
} else {
    // existing adopt logic; a lost update still returns duplicate
}
```

Extend the private `Persisted` record with `GroupInvitePageMetadata metadata`. All failed results carry null metadata; successful results carry the validated metadata.

- [ ] **Step 3: Split fetch and preview persistence**

Replace `refreshInvitePageMetadata` with:

```java
private GroupInvitePageMetadata fetchInvitePageMetadata(String normalizedUrl) {
    try {
        return invitePageFetcher.fetch(normalizedUrl);
    } catch (RuntimeException e) {
        log.warn("WhatsApp 公开邀请页元数据抓取失败 url={} error={}",
                normalizedUrl, e.getMessage());
        return null;
    }
}
```

Add `saveInvitePageMetadata(groupLinkId, metadata)` containing only the existing `GroupLinkPreview` construction and `previewMapper.upsertInvitePageMetadata`. The success branch calls it once and derives detail `groupName` from the same metadata.

- [ ] **Step 4: Count invalid details**

Add `int invalid = 0`. In the failed persisted-outcome branch:

```java
if (GroupLinkImportFailReason.LINK_INVALID.equals(p.failReason())) {
    invalid++;
    errors.add("第 " + o.lineNo() + " 行：链接失效");
} else {
    duplicate++;
}
```

Set `batch.failedRows` and response `failedRows` to `duplicate + formatError + invalid`; leave `duplicateRows` and `formatErrorRows` unchanged. Add `invalid={}` to the import log.

- [ ] **Step 5: Run service tests and verify GREEN**

```bash
mvn -q -Dtest=GroupLinkImportServiceImplTest,GroupLinkImportServiceDbTest test
```

Expected: PASS, including no mutation for invalid new/soft-deleted/ungrouped links.

- [ ] **Step 6: Commit behavior**

```bash
git add armada-api/src/main/java/com/armada/group/model/enums/GroupLinkImportFailReason.java \
  armada-api/src/main/java/com/armada/group/service/impl/GroupLinkImportServiceImpl.java \
  armada-api/src/test/java/com/armada/group/service/impl/GroupLinkImportServiceImplTest.java \
  armada-api/src/test/java/com/armada/group/service/impl/GroupLinkImportServiceDbTest.java
git commit -m "fix(group): reject invalid links before import"
```

### Task 4: Align schema and documentation

- [ ] **Step 1: Add Flyway V054**

Create `armada-api/src/main/resources/db/migration/V054__group_link_import_invalid_reason.sql`:

```sql
-- 群链接公开邀请页无法识别群名时，导入明细新增“链接失效”失败原因。
-- 只修改 COMMENT，不修改历史数据与字段类型。

ALTER TABLE group_link_import_batch
    MODIFY COLUMN failed_rows INT NOT NULL DEFAULT 0
        COMMENT '失败总数(重复 + 格式错误 + 链接失效)';

ALTER TABLE group_link_import_detail
    MODIFY COLUMN fail_reason VARCHAR(255) DEFAULT NULL
        COMMENT '失败原因:重复/格式错误/链接失效;成功时为空';
```

- [ ] **Step 2: Align code comments**

Update `GroupLinkImportResultVO`, `GroupLinkImportDetailMapper.java`, and `GroupLinkImportDetailMapper.xml` so the documented failure set is `重复/格式错误/链接失效`. Document that `errors` includes both format and invalid row descriptions.

- [ ] **Step 3: Run the migration test and verify GREEN**

```bash
mvn -q -Dtest=GroupListDataModelMigrationDbTest test
```

Expected: PASS with both updated column comments.

- [ ] **Step 4: Commit schema alignment**

```bash
git add armada-api/src/main/resources/db/migration/V054__group_link_import_invalid_reason.sql \
  armada-api/src/main/java/com/armada/group/model/vo/GroupLinkImportResultVO.java \
  armada-api/src/main/java/com/armada/group/mapper/GroupLinkImportDetailMapper.java \
  armada-api/src/main/resources/mapper/group/GroupLinkImportDetailMapper.xml \
  armada-api/src/test/java/com/armada/group/mapper/GroupListDataModelMigrationDbTest.java
git commit -m "chore(group): document invalid import failures"
```

### Task 5: Final verification

- [ ] **Step 1: Run focused tests**

```bash
mvn -q -Dtest=GroupLinkUrlsTest,GroupLinkImportServiceImplTest,GroupLinkImportServiceDbTest,GroupLinkImportDetailMapperDbTest,GroupListDataModelMigrationDbTest test
```

Expected: BUILD SUCCESS with all selected tests passing.

- [ ] **Step 2: Run the full backend suite**

```bash
mvn -q test
```

Expected: BUILD SUCCESS. Report environment-only Docker/Testcontainers failures separately if encountered.

- [ ] **Step 3: Inspect final state**

```bash
git diff --check HEAD~2..HEAD
git status --short
```

Expected: no whitespace errors; only pre-existing unrelated `.claude/worktrees/*` entries remain.
