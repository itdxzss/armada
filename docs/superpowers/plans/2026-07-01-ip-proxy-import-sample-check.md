# IP Proxy Manual Sample Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a manual "sample check" step to the IP TXT import dialog, where the frontend only enables import after a successful random 5-row backend check.

**Architecture:** `armada` exposes a new `POST /api/ip-proxies/import/sample-check` endpoint that parses the same TXT payload, finds rows that would be inserted, randomly checks up to 5 candidates, and returns a structured pass/fail result. `IpProxyServiceImpl#importProxies` imports without calling detection and leaves detection fields empty. `wheel-saas-pure-web` stores the latest passed check in dialog state; changing form inputs or file clears that state.

**Tech Stack:** Java 17, Spring Boot 3.3.5, JUnit 5, Mockito, AssertJ, Vue 3, TypeScript, Element Plus, Node test runner.

---

### File Structure

- `armada-api/src/main/java/com/armada/resource/model/vo/IpProxyImportSampleCheckVO.java`: response DTO for import sample checks.
- `armada-api/src/main/java/com/armada/resource/service/IpProxyService.java`: add `sampleCheckImport`.
- `armada-api/src/main/java/com/armada/resource/controller/IpProxyController.java`: expose `POST /api/ip-proxies/import/sample-check`.
- `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`: share import parsing, random sample selection, detection result conversion, and import persistence without detection.
- `armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java`: service behavior tests.
- `armada-api/src/test/java/com/armada/resource/controller/IpProxyControllerTest.java`: endpoint contract test.
- `wheel-saas-pure-web/src/api/resource-ip.ts`: add sample-check API and send `countryValue` on import.
- `wheel-saas-pure-web/src/api/resource-ip.test.ts`: API contract tests.
- `wheel-saas-pure-web/src/views/resource/ip/components/IpImportDialog.vue`: add country select, sample-check button, and disabled import button.
- `wheel-saas-pure-web/src/views/resource/ip/composables/useResourceIpPage.ts`: manage check state and clear it when inputs change.
- `wheel-saas-pure-web/src/views/resource/ip/composables/useResourceIpPage.test.ts`: page state tests.

### Task 1: Backend RED Tests For Manual Sample Check

**Files:**
- Modify: `armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/resource/controller/IpProxyControllerTest.java`

- [ ] **Step 1: Add service tests**

Add tests that describe the new backend behavior:

```java
@Test
void sampleCheckImport_failureReturnsFailedResultWithoutInsert() {
    when(countryService.resolveIpRegion("美国")).thenReturn("美国");
    when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);
    when(detector.check(any())).thenReturn(IpProxyCheckResult.failed(null, "代理连接超时", 1_719_800_000_000L));

    IpProxyImportSampleCheckVO result = service.sampleCheckImport(new IpProxyImportDTO(
            null, 1, "供应商A", "1.1.1.1:8080:user1:pass1", "US"));

    assertThat(result.passed()).isFalse();
    assertThat(result.sampleSize()).isEqualTo(1);
    assertThat(result.errors()).containsExactly("第 1 行：代理连接超时");
    assertThat(result.samples()).hasSize(1);
    assertThat(result.samples().get(0).lineNo()).isEqualTo(1);
    assertThat(result.samples().get(0).passed()).isFalse();

    verify(mapper, never()).insert(any());
}

@Test
void sampleCheckImport_successChecksAtMostFiveRandomCandidates() {
    when(countryService.resolveIpRegion("美国")).thenReturn("美国");
    when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);
    when(detector.check(any())).thenAnswer(invocation -> {
        IpProxyCheckRequest request = invocation.getArgument(0, IpProxyCheckRequest.class);
        return IpProxyCheckResult.success(
                request.id(),
                "103.10.10." + request.port(),
                "IN",
                "Mumbai",
                "Example ISP",
                null,
                null,
                400,
                IpProxyCheckTiming.zero(),
                1_719_800_000_000L);
    });

    IpProxyImportSampleCheckVO result = service.sampleCheckImport(new IpProxyImportDTO(null, 1, "供应商A",
            """
            1.1.1.1:8001:user1:pass1
            1.1.1.2:8002:user2:pass2
            1.1.1.3:8003:user3:pass3
            1.1.1.4:8004:user4:pass4
            1.1.1.5:8005:user5:pass5
            1.1.1.6:8006:user6:pass6
            """,
            "US"));

    assertThat(result.passed()).isTrue();
    assertThat(result.sampleSize()).isEqualTo(5);
    assertThat(result.samples()).hasSize(5);
    assertThat(result.errors()).isEmpty();
    verify(detector, times(5)).check(any());
    verify(mapper, never()).insert(any());
}

@Test
void importProxies_importsIdleRowsWithoutCallingDetectorOrWritingDetectionFields() {
    when(countryService.resolveIpRegion("美国")).thenReturn("美国");
    when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);

    IpProxyImportResultVO result = service.importProxies(new IpProxyImportDTO(
            null, 1, "供应商A", "1.1.1.1:8080:user1:pass1", "US"));

    assertThat(result.insertedRows()).isEqualTo(1);
    ArgumentCaptor<IpProxy> insertCaptor = ArgumentCaptor.forClass(IpProxy.class);
    verify(mapper).insert(insertCaptor.capture());
    IpProxy row = insertCaptor.getValue();
    assertThat(row.getStatus()).isEqualTo(IpProxyStatus.IDLE.code());
    assertThat(row.getRegion()).isEqualTo("美国");
    assertThat(row.getCheckStatus()).isNull();
    assertThat(row.getWhatsappCheckStatus()).isNull();
    assertThat(row.getOutboundIp()).isNull();
    verify(detector, never()).check(any());
    verify(ipProxyCheckExecutor, never()).execute(any(Runnable.class));
}
```

- [ ] **Step 2: Add controller endpoint test**

Add a controller test:

```java
@Test
void sampleCheckImport_returnsServiceResult() throws Exception {
    IpProxyImportSampleCheckVO result = new IpProxyImportSampleCheckVO(
            true,
            1,
            List.of(new IpProxyImportSampleCheckVO.SampleRow(
                    1, "1.1.1.1", 8080, true, "8.8.8.8", "US", "Google", null)),
            List.of());
    when(service.sampleCheckImport(any())).thenReturn(result);

    mockMvc.perform(post("/api/ip-proxies/import/sample-check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"countryValue":"US","protocol":1,"source":"供应商A","text":"1.1.1.1:8080:u:p"}
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.passed").value(true))
            .andExpect(jsonPath("$.data.sampleSize").value(1))
            .andExpect(jsonPath("$.data.samples[0].lineNo").value(1));

    verify(service).sampleCheckImport(any(IpProxyImportDTO.class));
}
```

- [ ] **Step 3: Run backend tests and verify RED**

Run:

```bash
mvn -pl armada-api -Dtest=IpProxyServiceImplTest,IpProxyControllerTest test
```

Expected: compile fails because `IpProxyImportSampleCheckVO`, `sampleCheckImport`, and the new endpoint do not exist.

### Task 2: Backend GREEN Implementation

**Files:**
- Create: `armada-api/src/main/java/com/armada/resource/model/vo/IpProxyImportSampleCheckVO.java`
- Modify: `armada-api/src/main/java/com/armada/resource/service/IpProxyService.java`
- Modify: `armada-api/src/main/java/com/armada/resource/controller/IpProxyController.java`
- Modify: `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`

- [ ] **Step 1: Create response VO**

Create:

```java
package com.armada.resource.model.vo;

import java.util.List;

public record IpProxyImportSampleCheckVO(
        boolean passed,
        int sampleSize,
        List<SampleRow> samples,
        List<String> errors) {

    public record SampleRow(
            int lineNo,
            String host,
            Integer port,
            boolean passed,
            String outboundIp,
            String countryCode,
            String location,
            String errorMessage) {
    }
}
```

- [ ] **Step 2: Add service and controller contract**

Add to `IpProxyService`:

```java
IpProxyImportSampleCheckVO sampleCheckImport(IpProxyImportDTO dto);
```

Add to `IpProxyController`:

```java
@PostMapping("/import/sample-check")
public ApiResponse<IpProxyImportSampleCheckVO> sampleCheckImport(@RequestBody IpProxyImportDTO dto) {
    return ApiResponse.ok(service.sampleCheckImport(dto));
}
```

- [ ] **Step 3: Refactor parsing and implement sample check**

In `IpProxyServiceImpl`, keep `importProxies` parsing through `LineImporter`, but remove `checkImportSamples(...)` from the import path. Add:

```java
@Override
public IpProxyImportSampleCheckVO sampleCheckImport(IpProxyImportDTO dto) {
    IpProxyImportDTO normalized = normalizeImport(dto);
    validateImport(normalized);
    List<LineOutcome<ProxyLine, Boolean>> outcomes = importOutcomes(normalized);
    List<ImportCandidate> candidates = insertCandidates(outcomes);
    List<ImportCandidate> samples = randomImportSamples(candidates);
    List<IpProxyImportSampleCheckVO.SampleRow> rows = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (ImportCandidate candidate : samples) {
        IpProxyCheckResult result = checkImportSample(normalized, candidate);
        boolean passed = isImportSampleSuccessful(result);
        String error = passed ? null : sampleFailureReason(result);
        if (error != null) {
            errors.add("第 " + candidate.lineNo() + " 行：" + error);
        }
        rows.add(new IpProxyImportSampleCheckVO.SampleRow(
                candidate.lineNo(),
                candidate.line().host(),
                candidate.line().port(),
                passed,
                result == null ? null : result.outboundIp(),
                result == null ? null : result.countryCode(),
                result == null ? null : result.location(),
                error));
    }
    return new IpProxyImportSampleCheckVO(errors.isEmpty(), rows.size(), rows, List.copyOf(errors));
}
```

Use `Collections.shuffle(samples, ThreadLocalRandom.current())` or equivalent to make each check choose a fresh random sample. Keep comments in `IpProxyServiceImpl` explaining:

- sample check is a manual preflight used by the dialog;
- import itself no longer invokes detection;
- selected country is business ownership and detection does not backfill `region`;
- full async import detection code remains for compatibility but is no longer called from TXT import.

- [ ] **Step 4: Run backend tests and verify GREEN**

Run:

```bash
mvn -pl armada-api -Dtest=IpProxyServiceImplTest,IpProxyControllerTest test
```

Expected: backend focused tests pass.

### Task 3: Frontend RED Tests For API And Dialog State

**Files:**
- Modify: `wheel-saas-pure-web/src/api/resource-ip.test.ts`
- Modify: `wheel-saas-pure-web/src/views/resource/ip/composables/useResourceIpPage.test.ts`

- [ ] **Step 1: Add API tests**

Add tests:

```ts
it("sample-checks IP import payloads with countryValue", async () => {
  resetArmadaMock({ passed: true, sampleSize: 1, samples: [], errors: [] });

  await sampleCheckIpProxyImport({
    countryValue: "US",
    proxyType: "HTTP",
    source: "iproyal",
    text: "1.1.1.1:8080:u:p"
  });

  assert.deepEqual(armadaCalls(), [
    {
      method: "post",
      url: "/api/ip-proxies/import/sample-check",
      opts: {
        data: {
          countryValue: "US",
          allocationMode: "smart",
          protocol: 1,
          source: "iproyal",
          text: "1.1.1.1:8080:u:p"
        }
      },
      config: { timeout: 120000 }
    }
  ]);
});

it("imports IP proxies with selected countryValue", async () => {
  resetArmadaMock({ totalRows: 1, insertedRows: 1, skippedRows: 0, failedRows: 0, errors: [] });

  await importIpProxies({
    countryValue: "US",
    proxyType: "SOCKS5",
    source: "iproyal",
    text: "1.1.1.1:8080:u:p"
  });

  assert.deepEqual(armadaCalls()[0].opts.data.countryValue, "US");
});
```

- [ ] **Step 2: Add page-state tests**

Add tests:

```ts
it("requires a passed import sample check before submitting import", async () => {
  const page = useResourceIpPage();
  page.importForm.value.countryValue = "US";
  page.importForm.value.source = "iproyal";
  page.setImportTextForTest("1.1.1.1:8080:u:p");

  await page.submitImport();

  assert.equal(page.importCheckPassed.value, false);
  assert.equal(armadaCalls().some(call => call.url === "/api/ip-proxies/import"), false);
});

it("clears import sample-check pass when import inputs change", async () => {
  const page = useResourceIpPage();
  page.markImportCheckPassedForTest();

  page.importForm.value.countryValue = "IN";
  await Promise.resolve();

  assert.equal(page.importCheckPassed.value, false);
});
```

If the existing tests cannot inject file text, add a small test-only helper returned by `useResourceIpPage` that is inert in production UI.

- [ ] **Step 3: Run frontend tests and verify RED**

Run:

```bash
pnpm test src/api/resource-ip.test.ts src/views/resource/ip/composables/useResourceIpPage.test.ts
```

Expected: tests fail because the API function and page state do not exist.

### Task 4: Frontend GREEN Implementation

**Files:**
- Modify: `wheel-saas-pure-web/src/api/resource-ip.ts`
- Modify: `wheel-saas-pure-web/src/views/resource/ip/composables/useResourceIpPage.ts`
- Modify: `wheel-saas-pure-web/src/views/resource/ip/components/IpImportDialog.vue`
- Modify: `wheel-saas-pure-web/src/views/resource/ip/index.vue`

- [ ] **Step 1: Add API types and functions**

In `resource-ip.ts`, change import input to include `countryValue` and add:

```ts
export interface IpProxyImportSampleRow {
  lineNo: number;
  host: string;
  port: number;
  passed: boolean;
  outboundIp?: string | null;
  countryCode?: string | null;
  location?: string | null;
  errorMessage?: string | null;
}

export interface IpProxyImportSampleCheckResult {
  passed: boolean;
  sampleSize: number;
  samples: IpProxyImportSampleRow[];
  errors: string[];
}

export function sampleCheckIpProxyImport(
  input: IpProxyImportInput
): Promise<IpProxyImportSampleCheckResult> {
  return armadaRequest<IpProxyImportSampleCheckResult>(
    "post",
    "/api/ip-proxies/import/sample-check",
    {
      data: {
        countryValue: input.countryValue,
        allocationMode: "smart",
        protocol: proxyTypeToProtocol(input.proxyType),
        source: input.source,
        text: input.text
      }
    },
    { timeout: 120000 }
  );
}
```

- [ ] **Step 2: Add import dialog state**

In `useResourceIpPage.ts`:

- extend `IpImportForm` with `countryValue`;
- add `importChecking`, `importCheckPassed`, `importCheckResult`, and `importCheckErrors`;
- add `sampleCheckImport`;
- make `submitImport` return early with warning if `importCheckPassed` is false;
- clear check state when country/type/source/file changes.

- [ ] **Step 3: Update dialog UI**

In `IpImportDialog.vue`:

- add a required country `el-select` using `countryOptions` and `countryOptionLabel`;
- remove allocation-mode radios from the dialog;
- add a “检测” button in footer, loading on `importChecking`;
- disable “开始导入” unless `importCheckPassed && !importing && !importChecking`;
- show an `el-alert` for passed/failed sample-check results.

- [ ] **Step 4: Wire index props/events**

In `index.vue`, pass `countryOptions`, `countryOptionLabel`, `importChecking`, `importCheckPassed`, `importCheckErrors`, and `sampleCheckImport` into `IpImportDialog`.

- [ ] **Step 5: Run frontend tests and verify GREEN**

Run:

```bash
pnpm test src/api/resource-ip.test.ts src/views/resource/ip/composables/useResourceIpPage.test.ts
```

Expected: focused frontend tests pass.

### Task 5: Final Verification

**Files:**
- All files modified above.

- [ ] **Step 1: Run backend focused verification**

Run:

```bash
mvn -pl armada-api -Dtest=IpProxyServiceImplTest,IpProxyControllerTest test
```

Expected: exit code 0.

- [ ] **Step 2: Run frontend focused verification**

Run:

```bash
pnpm test src/api/resource-ip.test.ts src/views/resource/ip/composables/useResourceIpPage.test.ts
```

Expected: exit code 0.

- [ ] **Step 3: Run diff whitespace check**

Run:

```bash
git -C /Users/daishuaishuai/IdeaProjects/armada diff --check
git -C /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web diff --check
```

Expected: both commands exit 0.

### Task 6: Import Sample Check Result Dialog

**Files:**
- Modify: `armada-api/src/main/java/com/armada/resource/model/vo/IpProxyImportSampleCheckVO.java`
- Modify: `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/resource/controller/IpProxyControllerTest.java`
- Modify: `wheel-saas-pure-web/src/api/resource-ip.ts`
- Modify: `wheel-saas-pure-web/src/api/resource-ip.test.ts`
- Create: `wheel-saas-pure-web/src/views/resource/ip/components/IpImportSampleCheckDialog.vue`
- Modify: `wheel-saas-pure-web/src/views/resource/ip/components/IpImportDialog.vue`
- Modify: `wheel-saas-pure-web/src/views/resource/ip/composables/useResourceIpPage.ts`
- Modify: `wheel-saas-pure-web/src/views/resource/ip/composables/useResourceIpPage.test.ts`
- Modify: `wheel-saas-pure-web/src/views/resource/ip/index.vue`

- [ ] **Step 1: Extend backend sample row tests**

Assert `SampleRow` contains `connectionStatus`, `whatsappStatus`, `isp`, `checkedAt`, `detectedLatitude`, and `detectedLongitude`.

- [ ] **Step 2: Implement backend fields**

Add those fields to `IpProxyImportSampleCheckVO.SampleRow` and populate them from `IpProxyCheckResult`.

- [ ] **Step 3: Add frontend API and state tests**

Assert API mapping accepts the enriched fields, opens a sample-check result dialog, and ignores stale sample-check responses when import inputs change before the response returns.

- [ ] **Step 4: Implement frontend dialog**

Create an Element Plus dialog that lists sample rows with line number, proxy address, connection status, WhatsApp, outbound IP, region/location, ISP, checked time, and error reason.

- [ ] **Step 5: Verify**

Run focused backend tests, frontend node tests, `tsc`, `vue-tsc`, and `git diff --check`.
