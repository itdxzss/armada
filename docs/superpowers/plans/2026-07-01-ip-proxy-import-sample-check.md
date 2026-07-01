# IP Proxy Import Sample Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace TXT import's per-row asynchronous full detection with a synchronous 5-row sample gate, while keeping all successfully imported rows available and preserving manually selected country.

**Architecture:** `IpProxyServiceImpl#importProxies` will split parsing from persistence: parse all lines, identify rows that would be inserted, sample up to 5 of those rows, run `IpProxyDetector` synchronously, then insert all rows only if the sample passes. Existing asynchronous import detection helpers remain in the class but are no longer called by the import path.

**Tech Stack:** Java 17, Spring Boot 3.3.5, JUnit 5, Mockito, AssertJ.

---

### Task 1: Add Failing Service Tests

**Files:**
- Modify: `armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java`

- [ ] **Step 1: Add tests for the new sample-gate behavior**

Add tests covering:

```java
@Test
void importProxies_sampleFailureRejectsWholeBatchBeforeInsert() {
    when(countryService.resolveIpRegion("美国")).thenReturn("美国");
    when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);
    when(detector.check(any())).thenReturn(IpProxyCheckResult.failed(null, "代理连接超时", 1_719_800_000_000L));

    assertThatThrownBy(() -> service.importProxies(new IpProxyImportDTO("美国", 1, "供应商A",
            "1.1.1.1:8080:user1:pass1\n2.2.2.2:8080:user2:pass2")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("抽样检测失败")
            .hasMessageContaining("第 1 行")
            .hasMessageContaining("代理连接超时");

    verify(mapper, never()).insert(any());
}

@Test
void importProxies_sampleSuccessImportsAllRowsAsIdleAndOnlyFiveRowsHaveDetectionFields() {
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

    IpProxyImportResultVO result = service.importProxies(new IpProxyImportDTO("美国", 1, "供应商A",
            """
            1.1.1.1:8001:user1:pass1
            1.1.1.2:8002:user2:pass2
            1.1.1.3:8003:user3:pass3
            1.1.1.4:8004:user4:pass4
            1.1.1.5:8005:user5:pass5
            1.1.1.6:8006:user6:pass6
            """));

    assertThat(result.insertedRows()).isEqualTo(6);
    ArgumentCaptor<IpProxy> insertCaptor = ArgumentCaptor.forClass(IpProxy.class);
    verify(mapper, times(6)).insert(insertCaptor.capture());
    assertThat(insertCaptor.getAllValues()).allSatisfy(row -> {
        assertThat(row.getStatus()).isEqualTo(IpProxyStatus.IDLE.code());
        assertThat(row.getRegion()).isEqualTo("美国");
    });
    assertThat(insertCaptor.getAllValues().stream().filter(row -> row.getOutboundIp() != null)).hasSize(5);
    assertThat(insertCaptor.getAllValues().stream().filter(row -> row.getOutboundIp() == null)).hasSize(1);
    verify(detector, times(5)).check(any());
    verify(ipProxyCheckExecutor, never()).execute(any(Runnable.class));
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
mvn -pl armada-api -Dtest=IpProxyServiceImplTest test
```

Expected: the new tests fail because current import inserts before detecting and still submits async detection.

### Task 2: Implement Sample-Gated Import

**Files:**
- Modify: `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`

- [ ] **Step 1: Add small private helper records**

Use private records near `ProxyLine`:

```java
private record ImportCandidate(int lineNo, ProxyLine line) {}

private record SampleCheckSnapshot(IpProxyCheckResult result) {}
```

- [ ] **Step 2: Change import flow**

Implement `importProxies` as:

```java
List<LineOutcome<ProxyLine, Boolean>> outcomes = LineImporter.run(
        normalized.text(),
        IpProxyServiceImpl::parseProxyLine,
        ProxyLine::dedupKey,
        line -> mapper.countActiveByFullTuple(line.host(), line.port(), line.username(), line.password()) == 0);
List<ImportCandidate> insertCandidates = outcomes.stream()
        .filter(o -> o.kind() == Kind.PERSISTED && Boolean.TRUE.equals(o.persistResult()))
        .map(o -> new ImportCandidate(o.lineNo(), o.record()))
        .toList();
Map<Object, SampleCheckSnapshot> sampleResults = checkImportSamples(normalized, insertCandidates);
for (ImportCandidate candidate : insertCandidates) {
    persistProxy(normalized, candidate.line(), sampleResults.get(candidate.line().dedupKey()));
}
```

- [ ] **Step 3: Add sample checking helpers**

Add helpers:

```java
private Map<Object, SampleCheckSnapshot> checkImportSamples(IpProxyImportDTO dto, List<ImportCandidate> candidates)
private IpProxyCheckResult checkImportSample(IpProxyImportDTO dto, ImportCandidate candidate)
private IpProxyCheckRequest toCheckRequest(IpProxyImportDTO dto, ProxyLine line)
```

Rules:
- sample limit constant is `5`.
- sample from the first 5 insertion candidates for deterministic tests.
- call `detector.check(...)` directly.
- null result becomes failed.
- thrown runtime exception becomes failed.
- any failed result throws `BusinessException(ErrorCode.VALIDATION, "抽样检测失败: 第 X 行 ...")`.
- do not call `countryService.resolveIpRegionByIso2(...)` during import sample processing.

- [ ] **Step 4: Change persistence helper**

Change `persistProxy` to accept the optional sample snapshot, set every new row to `IpProxyStatus.IDLE`, and remove `submitImportDetection(row)` from the import path.

For sampled rows, copy detection fields and set check statuses to success. For unsampled rows, leave detection fields and check status fields null.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run:

```bash
mvn -pl armada-api -Dtest=IpProxyServiceImplTest test
```

Expected: focused service tests pass.

### Task 3: Update Old Import Detection Tests

**Files:**
- Modify: `armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java`

- [ ] **Step 1: Remove or rewrite tests asserting async import detection**

Tests named around `submitsDetectionToExecutor`, `whenDetectionTaskRejected`, `detectionTaskUsesCapturedTenantContext`, `smartDetectsCountryAndUpdatesRegionAndDetectionFields`, `detectionFailureKeepsRowAndMarksUnavailable`, and `detectorSuccessWithoutCountryCodeMarksUnavailable` must match the new import behavior or move coverage to manual `checkProxy`.

- [ ] **Step 2: Run focused tests**

Run:

```bash
mvn -pl armada-api -Dtest=IpProxyServiceImplTest test
```

Expected: all tests in `IpProxyServiceImplTest` pass.

### Task 4: Final Verification

**Files:**
- Modified files from previous tasks.

- [ ] **Step 1: Run focused resource tests**

Run:

```bash
mvn -pl armada-api -Dtest=IpProxyServiceImplTest,IpProxyControllerTest test
```

Expected: exit code 0.

- [ ] **Step 2: Review diff for unrelated changes**

Run:

```bash
git diff -- armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java docs/superpowers/specs/2026-07-01-ip-proxy-import-sample-check-design.md docs/superpowers/plans/2026-07-01-ip-proxy-import-sample-check.md
```

Expected: diff only contains import sample-check changes and docs.
