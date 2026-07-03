# IP Proxy Import Format Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make IP TXT import fail fast on the first formatting error before sample-checking or importing proxies.

**Architecture:** Add a backend pre-parse format gate in `IpProxyServiceImpl` before `LineImporter` runs, and reuse it for both sample-check and final import. Keep the frontend's current local validation, but add a backend-error fallback that maps the existing `BusinessException` message into the import dialog's existing error area.

**Tech Stack:** Java 17, Spring Boot 3.3.5, JUnit 5, Mockito, AssertJ, Vue 3, TypeScript, Element Plus, Node test runner.

---

## File Structure

- `armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java`: backend TDD tests for fail-fast format validation and detector/DB non-interaction on bad TXT.
- `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`: backend import format gate and shared error message helpers.
- `wheel-saas-pure-web/src/views/resource/ip/ip-import-format.ts`: frontend local port validation alignment with backend integer parsing.
- `wheel-saas-pure-web/src/views/resource/ip/composables/useResourceIpPage.test.ts`: frontend TDD tests for invalid field count, invalid port, and backend fallback errors.
- `wheel-saas-pure-web/src/views/resource/ip/composables/useResourceIpPage.ts`: frontend backend-error mapping into the existing import dialog error state.

## Task 1: Backend RED Tests For Format Gate

**Files:**
- Modify: `armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java`

- [ ] **Step 1: Add backend fail-fast tests**

Add these tests near the existing import/sample-check tests in `IpProxyServiceImplTest`:

```java
    @Test
    void sampleCheckImport_blankLineThrowsFormatGateBeforeDetectorOrDb() {
        IpProxyImportDTO request = new IpProxyImportDTO(
                "美国",
                1,
                "供应商A",
                "1.1.1.1:8080:user1:pass1\n\n2.2.2.2:8080:user2:pass2",
                "US",
                "smart");
        when(countryService.resolveIpRegion("US")).thenReturn("美国");

        assertThatThrownBy(() -> service.sampleCheckImport(request))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(ex.getMessage()).isEqualTo(
                            "上传的文件中存在格式错误数据：第 2 行：格式错误，空行不允许");
                });

        verify(mapper, never()).selectActiveDedupTuples(any());
        verifyNoInteractions(detector);
    }

    @Test
    void sampleCheckImport_badFieldCountThrowsFormatGateBeforeDetectorOrDb() {
        IpProxyImportDTO request = new IpProxyImportDTO(
                "美国",
                1,
                "供应商A",
                "1.1.1.1:8080:user1:pass1\nbad-line",
                "US",
                "smart");
        when(countryService.resolveIpRegion("US")).thenReturn("美国");

        assertThatThrownBy(() -> service.sampleCheckImport(request))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(ex.getMessage()).isEqualTo(
                            "上传的文件中存在格式错误数据：第 2 行：格式错误，应为 代理地址:端口:用户名:密码");
                });

        verify(mapper, never()).selectActiveDedupTuples(any());
        verifyNoInteractions(detector);
    }

    @Test
    void sampleCheckImport_emptyFieldThrowsFormatGateBeforeDetectorOrDb() {
        IpProxyImportDTO request = new IpProxyImportDTO(
                "美国",
                1,
                "供应商A",
                "1.1.1.1:8080::pass1",
                "US",
                "smart");
        when(countryService.resolveIpRegion("US")).thenReturn("美国");

        assertThatThrownBy(() -> service.sampleCheckImport(request))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(ex.getMessage()).isEqualTo(
                            "上传的文件中存在格式错误数据：第 1 行：格式错误，存在空字段");
                });

        verify(mapper, never()).selectActiveDedupTuples(any());
        verifyNoInteractions(detector);
    }

    @Test
    void sampleCheckImport_invalidPortThrowsFormatGateBeforeDetectorOrDb() {
        IpProxyImportDTO request = new IpProxyImportDTO(
                "美国",
                1,
                "供应商A",
                "1.1.1.1:0:user1:pass1",
                "US",
                "smart");
        when(countryService.resolveIpRegion("US")).thenReturn("美国");

        assertThatThrownBy(() -> service.sampleCheckImport(request))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(ex.getMessage()).isEqualTo(
                            "上传的文件中存在格式错误数据：第 1 行：格式错误，端口必须为正整数");
                });

        verify(mapper, never()).selectActiveDedupTuples(any());
        verifyNoInteractions(detector);
    }

    @Test
    void importProxies_badFormatThrowsFormatGateBeforeInsert() {
        IpProxyImportDTO request = new IpProxyImportDTO(
                "美国",
                1,
                "供应商A",
                "1.1.1.1:8080:user1:pass1\nbad-line",
                "US",
                "smart");
        when(countryService.resolveIpRegion("US")).thenReturn("美国");

        assertThatThrownBy(() -> service.importProxies(request))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(ex.getMessage()).isEqualTo(
                            "上传的文件中存在格式错误数据：第 2 行：格式错误，应为 代理地址:端口:用户名:密码");
                });

        verify(mapper, never()).selectActiveDedupTuples(any());
        verify(mapper, never()).insertBatch(any());
        verifyNoInteractions(detector);
    }
```

- [ ] **Step 2: Run backend tests and verify RED**

Run from `/Users/daishuaishuai/IdeaProjects/armada/armada-api`:

```bash
mvn -q -Dtest=IpProxyServiceImplTest test
```

Expected: FAIL. The blank line test should currently reach sample-check instead of throwing `上传的文件中存在格式错误数据：第 2 行：格式错误，空行不允许`, and the bad format import test should currently return import stats instead of throwing.

## Task 2: Backend Format Gate Implementation

**Files:**
- Modify: `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java`

- [ ] **Step 1: Add constants to `IpProxyServiceImpl`**

Add these constants near the existing import-related constants:

```java
    private static final String IMPORT_FORMAT_ERROR_TITLE = "上传的文件中存在格式错误数据";
    private static final String IMPORT_FORMAT_EXAMPLE = "代理地址:端口:用户名:密码";
```

- [ ] **Step 2: Call the format gate before `importOutcomes`**

In `importProxies`, add `validateImportTextFormatOrThrow(normalized.text());` after `validateImport(normalized);`:

```java
        IpProxyImportDTO normalized = normalizeImport(dto);
        // 先校验批次级字段。国家可为空,但协议、来源、导入文本必须完整,否则不进入逐行解析。
        validateImport(normalized);
        validateImportTextFormatOrThrow(normalized.text());

        long parseStartedAt = System.nanoTime();
        List<LineOutcome<ProxyLine, Boolean>> outcomes = importOutcomes(normalized);
```

In `sampleCheckImport`, add the same call after `validateImport(normalized);`:

```java
        IpProxyImportDTO normalized = normalizeImport(dto);
        validateImport(normalized);
        validateImportTextFormatOrThrow(normalized.text());
        List<ImportCandidate> candidates = filterNewCandidates(importCandidates(importOutcomes(normalized)));
```

- [ ] **Step 3: Add the format gate helpers**

Add these private static helpers before `parseProxyLine`:

```java
    /**
     * 在抽检和正式导入前做 TXT 文件级格式门禁。
     *
     * <p>{@link LineImporter} 会跳过空行,但 IP 管理的产品口径是空行也属于格式错误。
     * 因此这里必须先扫原始文本,第一次碰到错误就抛业务异常,避免坏文件进入抽检或部分入库。</p>
     */
    private static void validateImportTextFormatOrThrow(String text) {
        String[] lines = text == null ? new String[0] : text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String raw = lines[i].trim();
            if (raw.isEmpty()) {
                throw importFormatException(lineNo, "格式错误，空行不允许");
            }
            String[] parts = raw.split(":", -1);
            if (parts.length != IMPORT_FIELDS) {
                throw importFormatException(lineNo, "格式错误，应为 " + IMPORT_FORMAT_EXAMPLE);
            }
            String host = parts[0].trim();
            String portText = parts[1].trim();
            String username = parts[2].trim();
            String password = parts[3].trim();
            if (host.isEmpty() || username.isEmpty() || password.isEmpty()) {
                throw importFormatException(lineNo, "格式错误，存在空字段");
            }
            if (!isPositiveInt(portText)) {
                throw importFormatException(lineNo, "格式错误，端口必须为正整数");
            }
        }
    }

    private static BusinessException importFormatException(int lineNo, String reason) {
        return new BusinessException(
                ErrorCode.VALIDATION,
                IMPORT_FORMAT_ERROR_TITLE + "：第 " + lineNo + " 行：" + reason);
    }
```

- [ ] **Step 4: Tighten positive integer validation**

Replace the existing `isPositiveInt` method with this implementation so `0` and values larger than Java `int` are rejected before `Integer.parseInt` runs:

```java
    private static boolean isPositiveInt(String s) {
        if (s.isEmpty()) {
            return false;
        }
        long value = 0L;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
            value = value * 10 + (s.charAt(i) - '0');
            if (value > Integer.MAX_VALUE) {
                return false;
            }
        }
        return value > 0L;
    }
```

- [ ] **Step 5: Run backend tests and verify GREEN**

Run from `/Users/daishuaishuai/IdeaProjects/armada/armada-api`:

```bash
mvn -q -Dtest=IpProxyServiceImplTest test
```

Expected: PASS. The new format gate tests pass, and existing import/sample-check/allocation tests remain green.

- [ ] **Step 6: Commit backend format gate**

Run from `/Users/daishuaishuai/IdeaProjects/armada`:

```bash
git add armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java \
  armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java
git commit -m "fix: gate ip import format before sample check"
```

Expected: commit contains only `IpProxyServiceImpl.java` and `IpProxyServiceImplTest.java`.

## Task 3: Frontend RED Tests For Error Display And Local Validation

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/resource/ip/composables/useResourceIpPage.test.ts`

- [ ] **Step 1: Add frontend tests for additional local format errors and backend fallback**

Add these tests near the existing `"stops before sample-checking when the txt file has a blank line"` test:

```ts
  it("stops before sample-checking when a txt line has the wrong field count", async () => {
    resetArmadaMock({
      passed: true,
      sampleSize: 1,
      samples: [],
      errors: []
    });
    resetMessageMock();
    const page = useResourceIpPage();
    page.importForm.value.countryValue = "US";
    page.importForm.value.source = "iproyal";
    setImportFile(page, "1.1.1.1:8080:u:p\nbad-line");

    await page.sampleCheckImport();

    assert.equal(page.showImportSampleCheckDialog.value, false);
    assert.equal(page.importCheckPassed.value, false);
    assert.equal(
      page.importCheckErrorTitle.value,
      "上传的文件中存在格式错误数据"
    );
    assert.deepEqual(page.importCheckErrors.value, [
      "第 2 行：格式错误，应为 代理地址:端口:用户名:密码"
    ]);
    assert.deepEqual(armadaCalls(), []);
    assert.deepEqual(
      messageCalls().map(call => call.text),
      ["上传的文件中存在格式错误数据"]
    );
  });

  it("stops before sample-checking when a txt line has an invalid port", async () => {
    resetArmadaMock({
      passed: true,
      sampleSize: 1,
      samples: [],
      errors: []
    });
    resetMessageMock();
    const page = useResourceIpPage();
    page.importForm.value.countryValue = "US";
    page.importForm.value.source = "iproyal";
    setImportFile(page, "1.1.1.1:0:u:p");

    await page.sampleCheckImport();

    assert.equal(page.showImportSampleCheckDialog.value, false);
    assert.equal(page.importCheckPassed.value, false);
    assert.equal(
      page.importCheckErrorTitle.value,
      "上传的文件中存在格式错误数据"
    );
    assert.deepEqual(page.importCheckErrors.value, [
      "第 1 行：格式错误，端口必须为正整数"
    ]);
    assert.deepEqual(armadaCalls(), []);
  });

  it("renders backend import format errors below the import form", async () => {
    resetArmadaMock(
      Promise.reject(
        new Error(
          "上传的文件中存在格式错误数据：第 2 行：格式错误，应为 代理地址:端口:用户名:密码"
        )
      )
    );
    resetMessageMock();
    const page = useResourceIpPage();
    page.importForm.value.countryValue = "US";
    page.importForm.value.source = "iproyal";
    setImportFile(page, "1.1.1.1:8080:u:p");

    await page.sampleCheckImport();

    assert.equal(page.showImportSampleCheckDialog.value, false);
    assert.equal(page.importCheckPassed.value, false);
    assert.equal(
      page.importCheckErrorTitle.value,
      "上传的文件中存在格式错误数据"
    );
    assert.deepEqual(page.importCheckErrors.value, [
      "第 2 行：格式错误，应为 代理地址:端口:用户名:密码"
    ]);
    assert.deepEqual(
      messageCalls().map(call => call.text),
      ["上传的文件中存在格式错误数据"]
    );
  });
```

- [ ] **Step 2: Run frontend tests and verify RED**

Run from `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/resource/ip/composables/useResourceIpPage.test.ts
```

Expected: FAIL. The invalid port test may already pass if `0` is rejected locally; the backend fallback test should fail because `sampleCheckImport` currently clears state and only shows an error toast.

## Task 4: Frontend Error Mapping Implementation

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/resource/ip/ip-import-format.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/resource/ip/composables/useResourceIpPage.ts`
- Test: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/resource/ip/composables/useResourceIpPage.test.ts`

- [ ] **Step 1: Export the shared format error title**

In `ip-import-format.ts`, add:

```ts
export const IP_IMPORT_FORMAT_ERROR_TITLE = "上传的文件中存在格式错误数据";
```

Keep the existing `MIXED_COUNTRY_VALUE` and `MIXED_COUNTRY_LABEL` exports unchanged.

- [ ] **Step 2: Align frontend positive integer validation with backend `int` parsing**

Replace `isPositiveInteger` in `ip-import-format.ts` with:

```ts
function isPositiveInteger(value: string): boolean {
  if (!/^[1-9]\d*$/.test(value)) return false;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed <= 2147483647;
}
```

- [ ] **Step 3: Import the title in `useResourceIpPage.ts`**

Change the existing import from `../ip-import-format` to include `IP_IMPORT_FORMAT_ERROR_TITLE`:

```ts
import {
  IP_IMPORT_FORMAT_ERROR_TITLE,
  MIXED_COUNTRY_VALUE,
  ipAllocationModeOptions,
  validateIpImportTextFormat
} from "../ip-import-format";
```

- [ ] **Step 4: Use the shared title in local format validation**

In `ensureImportTextFormatPassed`, replace the string literal title with `IP_IMPORT_FORMAT_ERROR_TITLE`:

```ts
    importCheckErrorTitle.value = IP_IMPORT_FORMAT_ERROR_TITLE;
    showImportSampleCheckDialog.value = false;
    message(IP_IMPORT_FORMAT_ERROR_TITLE, { type: "warning" });
```

- [ ] **Step 5: Add backend format error mapping helpers**

Add these helper functions near `ensureImportTextFormatPassed` in `useResourceIpPage.ts`:

```ts
  function splitBackendImportFormatError(text: string): string | null {
    if (!text.startsWith(IP_IMPORT_FORMAT_ERROR_TITLE)) {
      return null;
    }
    const detail = text
      .slice(IP_IMPORT_FORMAT_ERROR_TITLE.length)
      .replace(/^[:：]\s*/, "")
      .trim();
    return detail || IP_IMPORT_FORMAT_ERROR_TITLE;
  }

  function applyBackendImportFormatError(text: string): boolean {
    const detail = splitBackendImportFormatError(text);
    if (!detail) {
      return false;
    }
    importCheckPassed.value = false;
    importCheckResult.value = null;
    importCheckErrors.value = [detail];
    importCheckErrorTitle.value = IP_IMPORT_FORMAT_ERROR_TITLE;
    showImportSampleCheckDialog.value = false;
    message(IP_IMPORT_FORMAT_ERROR_TITLE, { type: "warning" });
    return true;
  }
```

- [ ] **Step 6: Use backend format error mapping in sample-check catch**

Replace the `catch` block in `sampleCheckImport` with:

```ts
    } catch (error) {
      const text = apiErrorMessage(error, "抽样检测失败");
      if (applyBackendImportFormatError(text)) {
        return;
      }
      clearImportCheckState();
      message(text, { type: "error" });
    } finally {
      importChecking.value = false;
    }
```

- [ ] **Step 7: Use backend format error mapping in submit catch**

Replace the `catch` block in `submitImport` with:

```ts
    } catch (error) {
      const text = apiErrorMessage(error, "IP 导入失败");
      if (applyBackendImportFormatError(text)) {
        return;
      }
      message(text, { type: "error" });
    } finally {
      importing.value = false;
    }
```

- [ ] **Step 8: Run frontend tests and verify GREEN**

Run from `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/resource/ip/composables/useResourceIpPage.test.ts
```

Expected: PASS. The existing blank-line test, new wrong-field-count test, new invalid-port test, and backend fallback test all pass.

- [ ] **Step 9: Commit frontend error mapping**

Run from `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`:

```bash
git add src/views/resource/ip/ip-import-format.ts \
  src/views/resource/ip/composables/useResourceIpPage.ts \
  src/views/resource/ip/composables/useResourceIpPage.test.ts
git commit -m "fix: show ip import format errors in dialog"
```

Expected: commit contains only the three frontend IP import files.

## Task 5: Full Verification

**Files:**
- Verify only; no file edits.

- [ ] **Step 1: Run focused backend verification**

Run from `/Users/daishuaishuai/IdeaProjects/armada/armada-api`:

```bash
mvn -q -Dtest=IpProxyServiceImplTest,IpProxyControllerTest test
```

Expected: PASS.

- [ ] **Step 2: Run focused frontend verification**

Run from `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/resource/ip/composables/useResourceIpPage.test.ts
node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/resource-ip.test.ts
```

Expected: PASS.

- [ ] **Step 3: Check backend diff**

Run from `/Users/daishuaishuai/IdeaProjects/armada`:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` prints no output. `git status --short` may show pre-existing `.claude/worktrees/agent-af50e0bc4d135f5c8` and `.claude/worktrees/wf_ca150a80-294-1` entries, but no uncommitted `IpProxyServiceImpl` or `IpProxyServiceImplTest` changes remain after the backend commit.

- [ ] **Step 4: Check frontend diff**

Run from `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` prints no output. `git status --short` may show pre-existing account-import changes, but no uncommitted resource IP import changes remain after the frontend commit.

## Self-Review

- Spec coverage: Task 1 and Task 2 implement backend fail-fast format validation before sample-check/import; Task 3 and Task 4 preserve frontend local validation and add backend fallback display; Task 5 verifies backend and frontend focused behavior.
- Placeholder scan: no placeholder steps or unspecified test instructions remain.
- Type consistency: backend uses existing `BusinessException`, `ErrorCode`, `IpProxyImportDTO`, and `IpProxyServiceImplTest` helper patterns; frontend uses existing `importCheckErrorTitle`, `importCheckErrors`, `showImportSampleCheckDialog`, `apiErrorMessage`, and `message` APIs.
