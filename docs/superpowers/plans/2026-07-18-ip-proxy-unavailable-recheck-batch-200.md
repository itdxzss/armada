# IP Proxy Unavailable Recheck Batch 200 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make each unavailable-IP recheck round process at most 200 proxies while keeping the 15-minute fixed delay and forcing the standard test/production Compose deployments to pass 200 even when host `.env` files retain an old value.

**Architecture:** Keep the existing scheduler, service loop, and configuration-property boundary. Change the application defaults to 200, then make both standard Compose templates set both relevant container environment variables to the literal value `200`, so host interpolation and `env_file` values cannot reintroduce 20.

**Tech Stack:** Java 17, Spring Boot 3.3.5 configuration properties, JUnit 5/AssertJ, Docker Compose YAML, Node.js deploy-config verifier, Bash production-package tests.

---

### Task 1: Lock and update the application default

**Files:**
- Modify: `armada-api/src/test/java/com/armada/resource/scheduler/IpProxyUnavailableRecheckJobPropertiesTest.java`
- Modify: `armada-api/src/test/java/com/armada/resource/scheduler/IpProxyUnavailableRecheckJobTest.java`
- Modify: `armada-api/src/main/java/com/armada/resource/scheduler/IpProxyUnavailableRecheckJobProperties.java`
- Modify: `armada-api/src/main/resources/application.yml`

- [ ] **Step 1: Write failing tests for the Java and YAML defaults**

Add these imports to `IpProxyUnavailableRecheckJobPropertiesTest`:

```java
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
```

Change the default assertion and add an application-YAML assertion:

```java
@Test
void defaultsMatchSpec() {
    IpProxyUnavailableRecheckJobProperties properties = new IpProxyUnavailableRecheckJobProperties();

    assertThat(properties.enabled()).isTrue();
    assertThat(properties.fixedDelayMs()).isEqualTo(900_000L);
    assertThat(properties.batchSize()).isEqualTo(200);
}

@Test
void applicationYamlDefaultsBatchSizeToTwoHundred() throws Exception {
    MockEnvironment environment = new MockEnvironment();
    new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"))
            .forEach(environment.getPropertySources()::addLast);

    assertThat(environment.getProperty(
            "armada.ip-proxy-unavailable-recheck.batch-size", Integer.class))
            .isEqualTo(200);
}
```

In `IpProxyUnavailableRecheckJobTest`, make the enabled-path test exercise the default properties:

```java
@Test
void runOnce_enabledDelegatesDefaultBatchSizeToService() {
    when(service.recheckUnavailableProxies(200))
            .thenReturn(new IpProxyRecheckResult(3, 3, 1));
    IpProxyUnavailableRecheckJob job = new IpProxyUnavailableRecheckJob(
            service, new IpProxyUnavailableRecheckJobProperties());

    IpProxyUnavailableRecheckJob.JobResult result = job.runOnce();

    assertThat(result.scanned()).isEqualTo(3);
    assertThat(result.checked()).isEqualTo(3);
    assertThat(result.failed()).isEqualTo(1);
    verify(service).recheckUnavailableProxies(200);
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
mvn -f armada-api/pom.xml -Dtest=IpProxyUnavailableRecheckJobPropertiesTest,IpProxyUnavailableRecheckJobTest test
```

Expected: FAIL because the Java and YAML defaults are still 20 and the job calls `recheckUnavailableProxies(20)`.

- [ ] **Step 3: Change the minimal application defaults**

In `IpProxyUnavailableRecheckJobProperties.java`, update the Javadoc and field initializer:

```java
 * <p>对应 {@code armada.ip-proxy-unavailable-recheck.*} 前缀。默认每 15 分钟重检 200 个不可用 IP,
```

```java
private int batchSize = 200;
```

In `application.yml`, update only the fallback:

```yaml
  ip-proxy-unavailable-recheck:
    enabled: ${IP_PROXY_UNAVAILABLE_RECHECK_ENABLED:true}
    fixed-delay-ms: ${IP_PROXY_UNAVAILABLE_RECHECK_FIXED_DELAY_MS:900000}
    batch-size: ${IP_PROXY_UNAVAILABLE_RECHECK_BATCH_SIZE:200}
```

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run:

```bash
mvn -f armada-api/pom.xml -Dtest=IpProxyUnavailableRecheckJobPropertiesTest,IpProxyUnavailableRecheckJobTest test
```

Expected: PASS with 5 tests and no failures or errors.

- [ ] **Step 5: Commit the application default change**

```bash
git add armada-api/src/test/java/com/armada/resource/scheduler/IpProxyUnavailableRecheckJobPropertiesTest.java armada-api/src/test/java/com/armada/resource/scheduler/IpProxyUnavailableRecheckJobTest.java armada-api/src/main/java/com/armada/resource/scheduler/IpProxyUnavailableRecheckJobProperties.java armada-api/src/main/resources/application.yml
git commit -m "config: increase unavailable ip recheck batch"
```

### Task 2: Force the test Compose deployment to pass 200

**Files:**
- Modify: `armada-deploy/verify-config.mjs`
- Modify: `armada-deploy/docker-compose.rds.yml`

- [ ] **Step 1: Add failing deploy-config assertions**

Append these assertions before the final success log in `verify-config.mjs`:

```javascript
expectIncludes(
  compose,
  "      IP_PROXY_UNAVAILABLE_RECHECK_BATCH_SIZE: 200",
  "docker-compose.rds.yml"
);
expectIncludes(
  compose,
  "      ARMADA_IP_PROXY_UNAVAILABLE_RECHECK_BATCH_SIZE: 200",
  "docker-compose.rds.yml"
);
```

- [ ] **Step 2: Run the verifier and verify RED**

Run:

```bash
node armada-deploy/verify-config.mjs
```

Expected: FAIL with `docker-compose.rds.yml missing: IP_PROXY_UNAVAILABLE_RECHECK_BATCH_SIZE: 200`.

- [ ] **Step 3: Replace host-interpolated fallbacks with literals**

Change the two entries in `armada-deploy/docker-compose.rds.yml` to:

```yaml
      IP_PROXY_UNAVAILABLE_RECHECK_BATCH_SIZE: 200
      ARMADA_IP_PROXY_UNAVAILABLE_RECHECK_BATCH_SIZE: 200
```

- [ ] **Step 4: Run the verifier and verify GREEN**

Run:

```bash
node armada-deploy/verify-config.mjs
```

Expected: PASS with `armada deploy config verification passed`.

- [ ] **Step 5: Commit the test-deployment contract**

```bash
git add armada-deploy/verify-config.mjs armada-deploy/docker-compose.rds.yml
git commit -m "config: force proxy recheck batch in test compose"
```

### Task 3: Force the production package template to pass 200

**Files:**
- Modify: `armada-deploy/package-prod.test.sh`
- Modify: `armada-deploy/prod/app/docker-compose.yml`

- [ ] **Step 1: Add failing production-template assertions**

Add these assertions to `test_app_package_templates` in `package-prod.test.sh`:

```bash
  assert_file_contains "${PROD_DIR}/app/docker-compose.yml" "      IP_PROXY_UNAVAILABLE_RECHECK_BATCH_SIZE: 200"
  assert_file_contains "${PROD_DIR}/app/docker-compose.yml" "      ARMADA_IP_PROXY_UNAVAILABLE_RECHECK_BATCH_SIZE: 200"
```

- [ ] **Step 2: Run the production package tests and verify RED**

Run:

```bash
bash armada-deploy/package-prod.test.sh
```

Expected: FAIL because the production Compose template still contains `${IP_PROXY_UNAVAILABLE_RECHECK_BATCH_SIZE:-20}`.

- [ ] **Step 3: Replace production host-interpolated fallbacks with literals**

Change the two entries in `armada-deploy/prod/app/docker-compose.yml` to:

```yaml
      IP_PROXY_UNAVAILABLE_RECHECK_BATCH_SIZE: 200
      ARMADA_IP_PROXY_UNAVAILABLE_RECHECK_BATCH_SIZE: 200
```

- [ ] **Step 4: Run the production package tests and verify GREEN**

Run:

```bash
bash armada-deploy/package-prod.test.sh
```

Expected: PASS with `OK package-prod offline deployment tests passed`.

- [ ] **Step 5: Commit the production-deployment contract**

```bash
git add armada-deploy/package-prod.test.sh armada-deploy/prod/app/docker-compose.yml
git commit -m "config: force proxy recheck batch in prod compose"
```

### Task 4: Synchronize the change record

**Files:**
- Modify: `.harness/changes/ip-proxy-unavailable-recheck/summary.md`

- [ ] **Step 1: Record the new batch contract**

Change the scheduled-recheck overview bullet to:

```markdown
- 新增不可用 IP 定时重检任务,默认每 15 分钟拉取最多 200 个不可用 IP 复用现有检测逻辑;标准测试/生产 Compose 固定向容器传入 200,不受宿主机旧环境变量影响。
```

- [ ] **Step 2: Check the documentation diff**

Run:

```bash
git diff --check -- .harness/changes/ip-proxy-unavailable-recheck/summary.md
git diff -- .harness/changes/ip-proxy-unavailable-recheck/summary.md
```

Expected: no whitespace errors; the diff changes only the batch-size overview bullet.

- [ ] **Step 3: Commit the change record**

```bash
git add .harness/changes/ip-proxy-unavailable-recheck/summary.md
git commit -m "docs: record proxy recheck batch 200"
```

### Task 5: Run final verification

**Files:**
- Verify only; no planned file changes.

- [ ] **Step 1: Run focused Java tests from a clean Maven invocation**

```bash
mvn -f armada-api/pom.xml -Dtest=IpProxyUnavailableRecheckJobPropertiesTest,IpProxyUnavailableRecheckJobTest test
```

Expected: PASS with no failures or errors.

- [ ] **Step 2: Run both deployment contract suites**

```bash
node armada-deploy/verify-config.mjs
bash armada-deploy/package-prod.test.sh
```

Expected: both commands exit 0 and print their success messages.

- [ ] **Step 3: Confirm no relevant 20-value path remains**

Run:

```bash
rg -n -S "默认每 15 分钟重检 20|IP_PROXY_UNAVAILABLE_RECHECK_BATCH_SIZE.*20|ARMADA_IP_PROXY_UNAVAILABLE_RECHECK_BATCH_SIZE.*20|batch-size:.*20|batchSize\(\).*20" armada-api armada-deploy .harness/changes/ip-proxy-unavailable-recheck
```

Expected: no matches.

- [ ] **Step 4: Inspect only this task's diff and repository status**

```bash
git diff --check
git status --short
git log -5 --oneline
```

Expected: no whitespace errors; existing unrelated marketing-account changes remain untouched; the latest commits contain only this task's files.
