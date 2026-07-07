# Protocol Restart Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a manual Armada account-list button that restarts the fixed PM2 protocol master plus four workers, waits for all five `/readyz` endpoints, and reports success or failure without automatically taking accounts offline or online.

**Architecture:** `armada-api` owns the operational endpoint `POST /api/protocol/restart`; it executes the fixed `pm2 restart protocol-master protocol-worker-1 protocol-worker-2 protocol-worker-3 protocol-worker-4 --update-env` command with `ProcessBuilder` and polls fixed readiness URLs. `wheel-saas-pure-web` adds a typed API wrapper and a single toolbar button on the existing account list. The implementation assumes the `armada-api` runtime can execute `pm2` and can reach `127.0.0.1:8080..8084`; it intentionally does not add SSH or arbitrary command execution.

**Tech Stack:** Java 17, Spring Boot 3.3.5, JUnit 5, Mockito, Spring `ApplicationContextRunner`, Java `HttpClient`, Vue 3 `<script setup>`, TypeScript, Element Plus, pure-admin-thin, Node test runner.

---

## File Structure

### Armada Backend

- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartProperties.java`
  - Binds fixed PM2 process names, fixed ready URLs, and timeout values under `armada.protocol-restart`.
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartConfiguration.java`
  - Enables `ProtocolRestartProperties`.
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProcessCommandResult.java`
  - Immutable command result for PM2 execution.
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolProcessCommandRunner.java`
  - Testable boundary for running the fixed PM2 command.
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/DefaultProtocolProcessCommandRunner.java`
  - `ProcessBuilder` implementation with timeout and clipped stdout/stderr.
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ReadyProbeResult.java`
  - Immutable readiness probe result.
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolReadyProbe.java`
  - Testable boundary for probing readiness URLs.
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/HttpProtocolReadyProbe.java`
  - Java `HttpClient` implementation for GET `/readyz`.
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartProcessVO.java`
  - Per-process response row.
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartVO.java`
  - Endpoint response payload.
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolProcessRestartService.java`
  - Service interface.
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolProcessRestartServiceImpl.java`
  - Executes PM2, polls readiness, returns `success=false` for operational failures.
- Create: `armada-api/src/main/java/com/armada/platform/protocol/controller/ProtocolProcessController.java`
  - Exposes `POST /api/protocol/restart`.
- Modify: `armada-api/src/main/resources/application.yml`
  - Adds the default `armada.protocol-restart` block.

### Backend Tests

- Create: `armada-api/src/test/java/com/armada/platform/protocol/process/ProtocolRestartPropertiesTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/process/ProtocolProcessRestartServiceImplTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/controller/ProtocolProcessControllerTest.java`

### Frontend

- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/protocol.ts`
  - Typed API wrapper for `POST /api/protocol/restart`.
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/protocol.test.ts`
  - Verifies the API wrapper URL and method.
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/composables/useAccountListPage.ts`
  - Adds restart state, confirmation flow, and API call.
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/components/AccountListTable.vue`
  - Adds the toolbar button and loading prop.
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/components/AccountListTable.test.ts`
  - Source-level guard for the new button wiring.
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/index.vue`
  - Passes restart state and handler to `AccountListTable`.

## Assumptions

- This is the simple local-PM2 version approved in the spec. If the deployed backend runs inside Docker on a different host from protocol PM2, this first version will need a mounted local script or a separate SSH-based change.
- The button does not enqueue account offline or online commands.
- PM2 and `/readyz` failures are operation results, not transport exceptions: the backend returns `ApiResponse.ok(data)` with `data.success=false`.

---

### Task 1: Backend Properties and Response Models

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartProperties.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartConfiguration.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProcessCommandResult.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ReadyProbeResult.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartProcessVO.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartVO.java`
- Modify: `armada-api/src/main/resources/application.yml`
- Test: `armada-api/src/test/java/com/armada/platform/protocol/process/ProtocolRestartPropertiesTest.java`

- [ ] **Step 1: Write the failing properties test**

Create `armada-api/src/test/java/com/armada/platform/protocol/process/ProtocolRestartPropertiesTest.java`:

```java
package com.armada.platform.protocol.process;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProtocolRestartPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsRestartPropertiesAndBuildsFixedCommand() {
        contextRunner
                .withPropertyValues(
                        "armada.protocol-restart.pm2-bin=/usr/bin/pm2",
                        "armada.protocol-restart.process-names[0]=protocol-master",
                        "armada.protocol-restart.process-names[1]=protocol-worker-1",
                        "armada.protocol-restart.ready-urls[0]=http://127.0.0.1:8080/readyz",
                        "armada.protocol-restart.ready-urls[1]=http://127.0.0.1:8081/readyz",
                        "armada.protocol-restart.command-timeout-ms=12345",
                        "armada.protocol-restart.ready-timeout-ms=23456",
                        "armada.protocol-restart.ready-poll-interval-ms=345",
                        "armada.protocol-restart.ready-request-timeout-ms=456")
                .run(context -> {
                    ProtocolRestartProperties properties = context.getBean(ProtocolRestartProperties.class);

                    assertThat(properties.getPm2Bin()).isEqualTo("/usr/bin/pm2");
                    assertThat(properties.getProcessNames()).containsExactly("protocol-master", "protocol-worker-1");
                    assertThat(properties.getReadyUrls()).containsExactly(
                            "http://127.0.0.1:8080/readyz",
                            "http://127.0.0.1:8081/readyz");
                    assertThat(properties.getCommandTimeoutMs()).isEqualTo(12345);
                    assertThat(properties.getReadyTimeoutMs()).isEqualTo(23456);
                    assertThat(properties.getReadyPollIntervalMs()).isEqualTo(345);
                    assertThat(properties.getReadyRequestTimeoutMs()).isEqualTo(456);
                    assertThat(properties.restartCommand()).containsExactly(
                            "/usr/bin/pm2",
                            "restart",
                            "protocol-master",
                            "protocol-worker-1",
                            "--update-env");
                });
    }

    @Test
    void providesDefaultProtocolMasterAndFourWorkers() {
        contextRunner.run(context -> {
            ProtocolRestartProperties properties = context.getBean(ProtocolRestartProperties.class);

            assertThat(properties.getPm2Bin()).isEqualTo("pm2");
            assertThat(properties.getProcessNames()).containsExactly(
                    "protocol-master",
                    "protocol-worker-1",
                    "protocol-worker-2",
                    "protocol-worker-3",
                    "protocol-worker-4");
            assertThat(properties.getReadyUrls()).containsExactly(
                    "http://127.0.0.1:8080/readyz",
                    "http://127.0.0.1:8081/readyz",
                    "http://127.0.0.1:8082/readyz",
                    "http://127.0.0.1:8083/readyz",
                    "http://127.0.0.1:8084/readyz");
            assertThat(properties.getCommandTimeoutMs()).isEqualTo(30_000);
            assertThat(properties.getReadyTimeoutMs()).isEqualTo(60_000);
            assertThat(properties.getReadyPollIntervalMs()).isEqualTo(1_000);
            assertThat(properties.getReadyRequestTimeoutMs()).isEqualTo(2_000);
        });
    }

    @EnableConfigurationProperties(ProtocolRestartProperties.class)
    static class TestConfig {
    }
}
```

- [ ] **Step 2: Run the properties test to verify it fails**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=ProtocolRestartPropertiesTest test
```

Expected: FAIL because `ProtocolRestartProperties` does not exist.

- [ ] **Step 3: Add backend model and properties classes**

Create `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartProperties.java`:

```java
package com.armada.platform.protocol.process;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "armada.protocol-restart")
public class ProtocolRestartProperties {

    private String pm2Bin = "pm2";
    private List<String> processNames = new ArrayList<>(List.of(
            "protocol-master",
            "protocol-worker-1",
            "protocol-worker-2",
            "protocol-worker-3",
            "protocol-worker-4"));
    private List<String> readyUrls = new ArrayList<>(List.of(
            "http://127.0.0.1:8080/readyz",
            "http://127.0.0.1:8081/readyz",
            "http://127.0.0.1:8082/readyz",
            "http://127.0.0.1:8083/readyz",
            "http://127.0.0.1:8084/readyz"));
    private long commandTimeoutMs = 30_000L;
    private long readyTimeoutMs = 60_000L;
    private long readyPollIntervalMs = 1_000L;
    private long readyRequestTimeoutMs = 2_000L;

    public List<String> restartCommand() {
        List<String> command = new ArrayList<>();
        command.add(pm2Bin);
        command.add("restart");
        command.addAll(processNames);
        command.add("--update-env");
        return command;
    }

    public void validate() {
        if (pm2Bin == null || pm2Bin.isBlank()) {
            throw new IllegalStateException("armada.protocol-restart.pm2-bin 不能为空");
        }
        if (processNames == null || processNames.isEmpty()) {
            throw new IllegalStateException("armada.protocol-restart.process-names 不能为空");
        }
        if (readyUrls == null || readyUrls.size() != processNames.size()) {
            throw new IllegalStateException("armada.protocol-restart.ready-urls 数量必须等于 process-names");
        }
        if (commandTimeoutMs <= 0 || readyTimeoutMs < 0 || readyPollIntervalMs < 0 || readyRequestTimeoutMs <= 0) {
            throw new IllegalStateException("armada.protocol-restart timeout 配置非法");
        }
    }

    public String getPm2Bin() {
        return pm2Bin;
    }

    public void setPm2Bin(String pm2Bin) {
        this.pm2Bin = pm2Bin;
    }

    public List<String> getProcessNames() {
        return processNames;
    }

    public void setProcessNames(List<String> processNames) {
        this.processNames = processNames;
    }

    public List<String> getReadyUrls() {
        return readyUrls;
    }

    public void setReadyUrls(List<String> readyUrls) {
        this.readyUrls = readyUrls;
    }

    public long getCommandTimeoutMs() {
        return commandTimeoutMs;
    }

    public void setCommandTimeoutMs(long commandTimeoutMs) {
        this.commandTimeoutMs = commandTimeoutMs;
    }

    public long getReadyTimeoutMs() {
        return readyTimeoutMs;
    }

    public void setReadyTimeoutMs(long readyTimeoutMs) {
        this.readyTimeoutMs = readyTimeoutMs;
    }

    public long getReadyPollIntervalMs() {
        return readyPollIntervalMs;
    }

    public void setReadyPollIntervalMs(long readyPollIntervalMs) {
        this.readyPollIntervalMs = readyPollIntervalMs;
    }

    public long getReadyRequestTimeoutMs() {
        return readyRequestTimeoutMs;
    }

    public void setReadyRequestTimeoutMs(long readyRequestTimeoutMs) {
        this.readyRequestTimeoutMs = readyRequestTimeoutMs;
    }
}
```

Create `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartConfiguration.java`:

```java
package com.armada.platform.protocol.process;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProtocolRestartProperties.class)
public class ProtocolRestartConfiguration {
}
```

Create `armada-api/src/main/java/com/armada/platform/protocol/process/ProcessCommandResult.java`:

```java
package com.armada.platform.protocol.process;

public record ProcessCommandResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut
) {
}
```

Create `armada-api/src/main/java/com/armada/platform/protocol/process/ReadyProbeResult.java`:

```java
package com.armada.platform.protocol.process;

public record ReadyProbeResult(
        String readyUrl,
        boolean ready,
        Integer statusCode,
        String error
) {
}
```

Create `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartProcessVO.java`:

```java
package com.armada.platform.protocol.process;

public record ProtocolRestartProcessVO(
        String processName,
        String readyUrl,
        boolean ready,
        Integer statusCode,
        String error,
        long checkedAt
) {
}
```

Create `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartVO.java`:

```java
package com.armada.platform.protocol.process;

import java.util.List;

public record ProtocolRestartVO(
        boolean success,
        String command,
        long startedAt,
        long finishedAt,
        long elapsedMs,
        List<ProtocolRestartProcessVO> processes,
        String message
) {
}
```

In `armada-api/src/main/resources/application.yml`, add under `armada:`:

```yaml
  protocol-restart:
    pm2-bin: ${ARMADA_PROTOCOL_RESTART_PM2_BIN:pm2}
    process-names:
      - ${ARMADA_PROTOCOL_RESTART_MASTER_PROCESS:protocol-master}
      - ${ARMADA_PROTOCOL_RESTART_WORKER_1_PROCESS:protocol-worker-1}
      - ${ARMADA_PROTOCOL_RESTART_WORKER_2_PROCESS:protocol-worker-2}
      - ${ARMADA_PROTOCOL_RESTART_WORKER_3_PROCESS:protocol-worker-3}
      - ${ARMADA_PROTOCOL_RESTART_WORKER_4_PROCESS:protocol-worker-4}
    ready-urls:
      - ${ARMADA_PROTOCOL_RESTART_MASTER_READY_URL:http://127.0.0.1:8080/readyz}
      - ${ARMADA_PROTOCOL_RESTART_WORKER_1_READY_URL:http://127.0.0.1:8081/readyz}
      - ${ARMADA_PROTOCOL_RESTART_WORKER_2_READY_URL:http://127.0.0.1:8082/readyz}
      - ${ARMADA_PROTOCOL_RESTART_WORKER_3_READY_URL:http://127.0.0.1:8083/readyz}
      - ${ARMADA_PROTOCOL_RESTART_WORKER_4_READY_URL:http://127.0.0.1:8084/readyz}
    command-timeout-ms: ${ARMADA_PROTOCOL_RESTART_COMMAND_TIMEOUT_MS:30000}
    ready-timeout-ms: ${ARMADA_PROTOCOL_RESTART_READY_TIMEOUT_MS:60000}
    ready-poll-interval-ms: ${ARMADA_PROTOCOL_RESTART_READY_POLL_INTERVAL_MS:1000}
    ready-request-timeout-ms: ${ARMADA_PROTOCOL_RESTART_READY_REQUEST_TIMEOUT_MS:2000}
```

- [ ] **Step 4: Run the properties test to verify it passes**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=ProtocolRestartPropertiesTest test
```

Expected: PASS.

- [ ] **Step 5: Commit backend properties and models**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartProperties.java \
  armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartConfiguration.java \
  armada-api/src/main/java/com/armada/platform/protocol/process/ProcessCommandResult.java \
  armada-api/src/main/java/com/armada/platform/protocol/process/ReadyProbeResult.java \
  armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartProcessVO.java \
  armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolRestartVO.java \
  armada-api/src/main/resources/application.yml \
  armada-api/src/test/java/com/armada/platform/protocol/process/ProtocolRestartPropertiesTest.java
git commit -m "feat: add protocol restart configuration"
```

Expected: commit contains only these files.

---

### Task 2: Backend Command Runner and Ready Probe

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolProcessCommandRunner.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/DefaultProtocolProcessCommandRunner.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolReadyProbe.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/HttpProtocolReadyProbe.java`

- [ ] **Step 1: Add command and probe boundaries**

Create `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolProcessCommandRunner.java`:

```java
package com.armada.platform.protocol.process;

import java.time.Duration;
import java.util.List;

public interface ProtocolProcessCommandRunner {
    ProcessCommandResult run(List<String> command, Duration timeout);
}
```

Create `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolReadyProbe.java`:

```java
package com.armada.platform.protocol.process;

import java.time.Duration;

public interface ProtocolReadyProbe {
    ReadyProbeResult probe(String readyUrl, Duration timeout);
}
```

- [ ] **Step 2: Add ProcessBuilder implementation**

Create `armada-api/src/main/java/com/armada/platform/protocol/process/DefaultProtocolProcessCommandRunner.java`:

```java
package com.armada.platform.protocol.process;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class DefaultProtocolProcessCommandRunner implements ProtocolProcessCommandRunner {

    private static final int OUTPUT_LIMIT = 2_000;

    @Override
    public ProcessCommandResult run(List<String> command, Duration timeout) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            Process started = process;
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> readLimited(started.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readLimited(started.getErrorStream()));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessCommandResult(-1, stdoutNow(stdout), stdoutNow(stderr), true);
            }
            return new ProcessCommandResult(process.exitValue(), stdout.join(), stderr.join(), false);
        } catch (IOException ex) {
            return new ProcessCommandResult(-1, "", ex.getMessage(), false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new ProcessCommandResult(-1, "", "interrupted", true);
        }
    }

    private static String readLimited(InputStream inputStream) {
        try (inputStream) {
            byte[] bytes = inputStream.readAllBytes();
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.length() <= OUTPUT_LIMIT ? text : text.substring(0, OUTPUT_LIMIT);
        } catch (IOException ex) {
            return ex.getMessage();
        }
    }

    private static String stdoutNow(CompletableFuture<String> future) {
        return future.getNow("");
    }
}
```

- [ ] **Step 3: Add HTTP readiness implementation**

Create `armada-api/src/main/java/com/armada/platform/protocol/process/HttpProtocolReadyProbe.java`:

```java
package com.armada.platform.protocol.process;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class HttpProtocolReadyProbe implements ProtocolReadyProbe {

    @Override
    public ReadyProbeResult probe(String readyUrl, Duration timeout) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(readyUrl))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            return new ReadyProbeResult(readyUrl, status >= 200 && status < 300, status, null);
        } catch (Exception ex) {
            return new ReadyProbeResult(readyUrl, false, null, ex.getMessage());
        }
    }
}
```

- [ ] **Step 4: Compile the new boundaries**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -DskipTests compile
```

Expected: compile succeeds.

- [ ] **Step 5: Commit command runner and probe**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolProcessCommandRunner.java \
  armada-api/src/main/java/com/armada/platform/protocol/process/DefaultProtocolProcessCommandRunner.java \
  armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolReadyProbe.java \
  armada-api/src/main/java/com/armada/platform/protocol/process/HttpProtocolReadyProbe.java
git commit -m "feat: add protocol process execution adapters"
```

Expected: commit contains only these files.

---

### Task 3: Backend Restart Service

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolProcessRestartService.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolProcessRestartServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/platform/protocol/process/ProtocolProcessRestartServiceImplTest.java`

- [ ] **Step 1: Write the failing service tests**

Create `armada-api/src/test/java/com/armada/platform/protocol/process/ProtocolProcessRestartServiceImplTest.java`:

```java
package com.armada.platform.protocol.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolProcessRestartServiceImplTest {

    @Test
    void restart_runsFixedPm2CommandAndReturnsSuccessWhenAllProcessesBecomeReady() {
        ProtocolRestartProperties properties = properties();
        FakeRunner runner = new FakeRunner(new ProcessCommandResult(0, "ok", "", false));
        FakeProbe probe = new FakeProbe(List.of(
                new ReadyProbeResult("http://127.0.0.1:8080/readyz", true, 200, null),
                new ReadyProbeResult("http://127.0.0.1:8081/readyz", true, 200, null)));
        ProtocolProcessRestartService service = new ProtocolProcessRestartServiceImpl(properties, runner, probe);

        ProtocolRestartVO result = service.restart();

        assertThat(result.success()).isTrue();
        assertThat(result.command()).isEqualTo("pm2 restart protocol-master protocol-worker-1 --update-env");
        assertThat(result.processes()).hasSize(2);
        assertThat(result.processes()).allMatch(ProtocolRestartProcessVO::ready);
        assertThat(result.message()).isEqualTo("协议进程已重启");
        assertThat(runner.commands).containsExactly(List.of(
                "pm2", "restart", "protocol-master", "protocol-worker-1", "--update-env"));
    }

    @Test
    void restart_returnsFailureWhenPm2CommandFails() {
        ProtocolRestartProperties properties = properties();
        FakeRunner runner = new FakeRunner(new ProcessCommandResult(1, "", "process not found", false));
        FakeProbe probe = new FakeProbe(List.of());
        ProtocolProcessRestartService service = new ProtocolProcessRestartServiceImpl(properties, runner, probe);

        ProtocolRestartVO result = service.restart();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("PM2 重启失败");
        assertThat(result.message()).contains("exitCode=1");
        assertThat(result.message()).contains("process not found");
        assertThat(result.processes()).isEmpty();
        assertThat(probe.urls).isEmpty();
    }

    @Test
    void restart_returnsFailureWhenPm2CommandTimesOut() {
        ProtocolRestartProperties properties = properties();
        FakeRunner runner = new FakeRunner(new ProcessCommandResult(-1, "", "", true));
        FakeProbe probe = new FakeProbe(List.of());
        ProtocolProcessRestartService service = new ProtocolProcessRestartServiceImpl(properties, runner, probe);

        ProtocolRestartVO result = service.restart();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("PM2 重启超时");
        assertThat(result.processes()).isEmpty();
    }

    @Test
    void restart_returnsFailureWhenAnyReadyUrlDoesNotBecomeReady() {
        ProtocolRestartProperties properties = properties();
        properties.setReadyTimeoutMs(0);
        FakeRunner runner = new FakeRunner(new ProcessCommandResult(0, "ok", "", false));
        FakeProbe probe = new FakeProbe(List.of(
                new ReadyProbeResult("http://127.0.0.1:8080/readyz", true, 200, null),
                new ReadyProbeResult("http://127.0.0.1:8081/readyz", false, 503, "not_ready")));
        ProtocolProcessRestartService service = new ProtocolProcessRestartServiceImpl(properties, runner, probe);

        ProtocolRestartVO result = service.restart();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("协议进程未全部 ready");
        assertThat(result.processes()).hasSize(2);
        assertThat(result.processes().get(0).ready()).isTrue();
        assertThat(result.processes().get(1).ready()).isFalse();
        assertThat(result.processes().get(1).statusCode()).isEqualTo(503);
    }

    private static ProtocolRestartProperties properties() {
        ProtocolRestartProperties properties = new ProtocolRestartProperties();
        properties.setProcessNames(List.of("protocol-master", "protocol-worker-1"));
        properties.setReadyUrls(List.of(
                "http://127.0.0.1:8080/readyz",
                "http://127.0.0.1:8081/readyz"));
        properties.setReadyPollIntervalMs(0);
        properties.setReadyTimeoutMs(1);
        properties.setReadyRequestTimeoutMs(1);
        return properties;
    }

    private static final class FakeRunner implements ProtocolProcessCommandRunner {
        private final ProcessCommandResult result;
        private final List<List<String>> commands = new ArrayList<>();

        private FakeRunner(ProcessCommandResult result) {
            this.result = result;
        }

        @Override
        public ProcessCommandResult run(List<String> command, Duration timeout) {
            commands.add(command);
            return result;
        }
    }

    private static final class FakeProbe implements ProtocolReadyProbe {
        private final List<ReadyProbeResult> results;
        private final List<String> urls = new ArrayList<>();

        private FakeProbe(List<ReadyProbeResult> results) {
            this.results = results;
        }

        @Override
        public ReadyProbeResult probe(String readyUrl, Duration timeout) {
            urls.add(readyUrl);
            return results.stream()
                    .filter(result -> result.readyUrl().equals(readyUrl))
                    .findFirst()
                    .orElse(new ReadyProbeResult(readyUrl, false, null, "missing fake result"));
        }
    }
}
```

- [ ] **Step 2: Run the service tests to verify they fail**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=ProtocolProcessRestartServiceImplTest test
```

Expected: FAIL because `ProtocolProcessRestartService` and implementation do not exist.

- [ ] **Step 3: Add restart service implementation**

Create `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolProcessRestartService.java`:

```java
package com.armada.platform.protocol.process;

public interface ProtocolProcessRestartService {
    ProtocolRestartVO restart();
}
```

Create `armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolProcessRestartServiceImpl.java`:

```java
package com.armada.platform.protocol.process;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProtocolProcessRestartServiceImpl implements ProtocolProcessRestartService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolProcessRestartServiceImpl.class);
    private static final int MESSAGE_LIMIT = 500;

    private final ProtocolRestartProperties properties;
    private final ProtocolProcessCommandRunner commandRunner;
    private final ProtocolReadyProbe readyProbe;

    public ProtocolProcessRestartServiceImpl(ProtocolRestartProperties properties,
                                             ProtocolProcessCommandRunner commandRunner,
                                             ProtocolReadyProbe readyProbe) {
        this.properties = properties;
        this.commandRunner = commandRunner;
        this.readyProbe = readyProbe;
    }

    @Override
    public ProtocolRestartVO restart() {
        properties.validate();
        long startedAt = System.currentTimeMillis();
        List<String> command = properties.restartCommand();
        String commandText = String.join(" ", command);
        log.warn("协议进程重启开始 command={}", commandText);

        ProcessCommandResult commandResult = commandRunner.run(
                command,
                Duration.ofMillis(properties.getCommandTimeoutMs()));

        if (commandResult.timedOut()) {
            return finish(false, commandText, startedAt, List.of(), "PM2 重启超时");
        }
        if (commandResult.exitCode() != 0) {
            String detail = firstText(commandResult.stderr(), commandResult.stdout());
            return finish(false, commandText, startedAt, List.of(),
                    "PM2 重启失败 exitCode=" + commandResult.exitCode() + " " + clip(detail));
        }

        List<ProtocolRestartProcessVO> processes = waitForReady();
        boolean allReady = processes.stream().allMatch(ProtocolRestartProcessVO::ready);
        if (!allReady) {
            return finish(false, commandText, startedAt, processes, "协议进程未全部 ready");
        }
        return finish(true, commandText, startedAt, processes, "协议进程已重启");
    }

    private List<ProtocolRestartProcessVO> waitForReady() {
        long deadline = System.currentTimeMillis() + properties.getReadyTimeoutMs();
        List<ProtocolRestartProcessVO> latest = probeAll();
        while (!allReady(latest) && System.currentTimeMillis() < deadline) {
            sleep(properties.getReadyPollIntervalMs());
            latest = probeAll();
        }
        return latest;
    }

    private List<ProtocolRestartProcessVO> probeAll() {
        List<ProtocolRestartProcessVO> results = new ArrayList<>();
        Duration timeout = Duration.ofMillis(properties.getReadyRequestTimeoutMs());
        for (int i = 0; i < properties.getProcessNames().size(); i++) {
            String processName = properties.getProcessNames().get(i);
            String readyUrl = properties.getReadyUrls().get(i);
            ReadyProbeResult probeResult = readyProbe.probe(readyUrl, timeout);
            results.add(new ProtocolRestartProcessVO(
                    processName,
                    readyUrl,
                    probeResult.ready(),
                    probeResult.statusCode(),
                    probeResult.error(),
                    System.currentTimeMillis()));
        }
        return results;
    }

    private static boolean allReady(List<ProtocolRestartProcessVO> results) {
        return !results.isEmpty() && results.stream().allMatch(ProtocolRestartProcessVO::ready);
    }

    private static void sleep(long intervalMs) {
        if (intervalMs <= 0) {
            return;
        }
        try {
            Thread.sleep(intervalMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static ProtocolRestartVO finish(boolean success,
                                            String command,
                                            long startedAt,
                                            List<ProtocolRestartProcessVO> processes,
                                            String message) {
        long finishedAt = System.currentTimeMillis();
        return new ProtocolRestartVO(
                success,
                command,
                startedAt,
                finishedAt,
                finishedAt - startedAt,
                processes,
                message);
    }

    private static String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() <= MESSAGE_LIMIT ? normalized : normalized.substring(0, MESSAGE_LIMIT);
    }
}
```

- [ ] **Step 4: Run the service tests to verify they pass**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=ProtocolProcessRestartServiceImplTest test
```

Expected: PASS.

- [ ] **Step 5: Commit restart service**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolProcessRestartService.java \
  armada-api/src/main/java/com/armada/platform/protocol/process/ProtocolProcessRestartServiceImpl.java \
  armada-api/src/test/java/com/armada/platform/protocol/process/ProtocolProcessRestartServiceImplTest.java
git commit -m "feat: add protocol restart service"
```

Expected: commit contains only these files.

---

### Task 4: Backend Restart Endpoint

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/controller/ProtocolProcessController.java`
- Test: `armada-api/src/test/java/com/armada/platform/protocol/controller/ProtocolProcessControllerTest.java`

- [ ] **Step 1: Write the failing controller test**

Create `armada-api/src/test/java/com/armada/platform/protocol/controller/ProtocolProcessControllerTest.java`:

```java
package com.armada.platform.protocol.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.platform.protocol.process.ProtocolProcessRestartService;
import com.armada.platform.protocol.process.ProtocolRestartProcessVO;
import com.armada.platform.protocol.process.ProtocolRestartVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ProtocolProcessControllerTest {

    @Mock
    private ProtocolProcessRestartService restartService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProtocolProcessController(restartService))
                .build();
    }

    @Test
    void postRestart_delegatesToRestartServiceAndReturnsApiResponse() throws Exception {
        ProtocolRestartVO vo = new ProtocolRestartVO(
                true,
                "pm2 restart protocol-master protocol-worker-1 --update-env",
                1_783_420_000_000L,
                1_783_420_002_000L,
                2_000L,
                List.of(new ProtocolRestartProcessVO(
                        "protocol-master",
                        "http://127.0.0.1:8080/readyz",
                        true,
                        200,
                        null,
                        1_783_420_001_000L)),
                "协议进程已重启");
        when(restartService.restart()).thenReturn(vo);

        mockMvc.perform(post("/api/protocol/restart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.command").value("pm2 restart protocol-master protocol-worker-1 --update-env"))
                .andExpect(jsonPath("$.data.processes[0].processName").value("protocol-master"))
                .andExpect(jsonPath("$.data.processes[0].ready").value(true))
                .andExpect(jsonPath("$.data.message").value("协议进程已重启"));

        verify(restartService).restart();
    }
}
```

- [ ] **Step 2: Run the controller test to verify it fails**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=ProtocolProcessControllerTest test
```

Expected: FAIL because `ProtocolProcessController` does not exist.

- [ ] **Step 3: Add the controller**

Create `armada-api/src/main/java/com/armada/platform/protocol/controller/ProtocolProcessController.java`:

```java
package com.armada.platform.protocol.controller;

import com.armada.platform.protocol.process.ProtocolProcessRestartService;
import com.armada.platform.protocol.process.ProtocolRestartVO;
import com.armada.shared.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/protocol")
public class ProtocolProcessController {

    private final ProtocolProcessRestartService restartService;

    public ProtocolProcessController(ProtocolProcessRestartService restartService) {
        this.restartService = restartService;
    }

    @PostMapping("/restart")
    public ApiResponse<ProtocolRestartVO> restart() {
        return ApiResponse.ok(restartService.restart());
    }
}
```

- [ ] **Step 4: Run backend restart tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=ProtocolRestartPropertiesTest,ProtocolProcessRestartServiceImplTest,ProtocolProcessControllerTest test
```

Expected: PASS.

- [ ] **Step 5: Commit endpoint**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/platform/protocol/controller/ProtocolProcessController.java \
  armada-api/src/test/java/com/armada/platform/protocol/controller/ProtocolProcessControllerTest.java
git commit -m "feat: expose protocol restart endpoint"
```

Expected: commit contains only these files.

---

### Task 5: Frontend Protocol Restart API

**Files:**
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/protocol.ts`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/protocol.test.ts`

- [ ] **Step 1: Write the failing frontend API test**

Create `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/protocol.test.ts`:

```ts
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { armadaCalls, resetArmadaMock } from "./__tests__/armada-test-double";
import { restartProtocolProcesses } from "./protocol";

describe("protocol operation API", () => {
  it("posts protocol restart requests to armada", async () => {
    resetArmadaMock({
      success: true,
      command:
        "pm2 restart protocol-master protocol-worker-1 protocol-worker-2 protocol-worker-3 protocol-worker-4 --update-env",
      startedAt: 1783420000000,
      finishedAt: 1783420002000,
      elapsedMs: 2000,
      processes: [
        {
          processName: "protocol-master",
          readyUrl: "http://127.0.0.1:8080/readyz",
          ready: true,
          statusCode: 200,
          error: null,
          checkedAt: 1783420001000
        }
      ],
      message: "协议进程已重启"
    });

    const result = await restartProtocolProcesses();

    assert.equal(result.success, true);
    assert.equal(result.processes[0].processName, "protocol-master");
    assert.deepEqual(armadaCalls(), [
      {
        method: "post",
        url: "/api/protocol/restart",
        opts: undefined
      }
    ]);
  });
});
```

- [ ] **Step 2: Run the frontend API test to verify it fails**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import tsx --import ./src/api/__tests__/node-test-alias.mjs src/api/protocol.test.ts
```

Expected: FAIL because `src/api/protocol.ts` does not exist.

- [ ] **Step 3: Add the protocol API wrapper**

Create `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/protocol.ts`:

```ts
import { armadaRequest } from "@/api/armada";

export interface ProtocolRestartProcess {
  processName: string;
  readyUrl: string;
  ready: boolean;
  statusCode: number | null;
  error: string | null;
  checkedAt: number;
}

export interface ProtocolRestartResult {
  success: boolean;
  command: string;
  startedAt: number;
  finishedAt: number;
  elapsedMs: number;
  processes: ProtocolRestartProcess[];
  message: string;
}

export function restartProtocolProcesses(): Promise<ProtocolRestartResult> {
  return armadaRequest<ProtocolRestartResult>(
    "post",
    "/api/protocol/restart"
  );
}
```

- [ ] **Step 4: Run the frontend API test to verify it passes**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import tsx --import ./src/api/__tests__/node-test-alias.mjs src/api/protocol.test.ts
```

Expected: PASS.

- [ ] **Step 5: Commit frontend API wrapper**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git add src/api/protocol.ts src/api/protocol.test.ts
git commit -m "feat: add protocol restart API"
```

Expected: commit contains only these files.

---

### Task 6: Account List Button Wiring

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/composables/useAccountListPage.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/components/AccountListTable.vue`
- Create: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/components/AccountListTable.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/index.vue`

- [ ] **Step 1: Write the failing source-level component test**

Create `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/components/AccountListTable.test.ts`:

```ts
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, it } from "node:test";

const source = readFileSync(
  fileURLToPath(new URL("./AccountListTable.vue", import.meta.url)),
  "utf8"
);

describe("AccountListTable protocol restart button", () => {
  it("exposes a loading restart button that emits restart-protocol", () => {
    assert.match(source, /protocolRestarting: boolean/);
    assert.match(source, /\(event: "restart-protocol"\): void/);
    assert.match(source, /重启协议/);
    assert.match(source, /:loading="protocolRestarting"/);
    assert.match(source, /emit\('restart-protocol'\)/);
  });
});
```

- [ ] **Step 2: Run the component test to verify it fails**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import tsx src/views/account/index/components/AccountListTable.test.ts
```

Expected: FAIL because the table has no protocol restart button.

- [ ] **Step 3: Wire restart state and confirmation into the composable**

In `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/composables/useAccountListPage.ts`:

Add imports:

```ts
import { ElMessage, ElMessageBox } from "element-plus";
import { restartProtocolProcesses } from "@/api/protocol";
```

Replace the existing `import { ElMessage } from "element-plus";` with the combined import above.

Add to `AccountListPageState`:

```ts
  protocolRestarting: Ref<boolean>;
  restartProtocol: () => Promise<void>;
```

Add state near other refs:

```ts
  const protocolRestarting = ref(false);
```

Add the handler near the other submit functions:

```ts
  async function restartProtocol(): Promise<void> {
    if (protocolRestarting.value) return;
    try {
      await ElMessageBox.confirm(
        "会重启协议 master 和 4 个 worker，当前在线连接会断开；账号下线/上线请继续使用现有批量操作。",
        "确认重启协议",
        {
          confirmButtonText: "重启协议",
          cancelButtonText: "取消",
          type: "warning"
        }
      );
    } catch {
      return;
    }

    protocolRestarting.value = true;
    try {
      const result = await restartProtocolProcesses();
      if (result.success) {
        ElMessage.success(result.message || "协议已重启");
      } else {
        ElMessage.error(result.message || "协议重启失败");
      }
    } catch (error) {
      ElMessage.error(apiErrorMessage(error, "协议重启失败"));
    } finally {
      protocolRestarting.value = false;
    }
  }
```

Add to the returned object:

```ts
    protocolRestarting,
    restartProtocol,
```

- [ ] **Step 4: Wire the button into `AccountListTable.vue`**

In `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/components/AccountListTable.vue`, add import:

```ts
import RefreshRight from "~icons/ep/refresh-right";
```

Add prop:

```ts
  protocolRestarting: boolean;
```

Add emit:

```ts
  (event: "restart-protocol"): void;
```

In `<template #buttons>`, before the existing `<el-dropdown>`, add:

```vue
      <el-button
        type="warning"
        plain
        :loading="protocolRestarting"
        :icon="useRenderIcon(RefreshRight)"
        @click="emit('restart-protocol')"
      >
        重启协议
      </el-button>
```

- [ ] **Step 5: Wire the page container**

In `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/index.vue`, add to the destructuring from `useAccountListPage()`:

```ts
  protocolRestarting,
  restartProtocol,
```

Add props and event to `<AccountListTable>`:

```vue
      :protocol-restarting="protocolRestarting"
      @restart-protocol="restartProtocol"
```

- [ ] **Step 6: Run the button source test**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import tsx src/views/account/index/components/AccountListTable.test.ts
```

Expected: PASS.

- [ ] **Step 7: Run frontend typecheck**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
pnpm typecheck
```

Expected: PASS.

- [ ] **Step 8: Commit frontend button wiring**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git add src/views/account/index/composables/useAccountListPage.ts \
  src/views/account/index/components/AccountListTable.vue \
  src/views/account/index/components/AccountListTable.test.ts \
  src/views/account/index/index.vue
git commit -m "feat: add protocol restart button"
```

Expected: commit contains only these files.

---

### Task 7: Final Verification

**Files:**
- No new files.

- [ ] **Step 1: Run targeted backend tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=ProtocolRestartPropertiesTest,ProtocolProcessRestartServiceImplTest,ProtocolProcessControllerTest test
```

Expected: PASS.

- [ ] **Step 2: Run backend compile**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -DskipTests compile
```

Expected: PASS.

- [ ] **Step 3: Run targeted frontend tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --import tsx --import ./src/api/__tests__/node-test-alias.mjs src/api/protocol.test.ts
node --import tsx src/views/account/index/components/AccountListTable.test.ts
```

Expected: both commands PASS.

- [ ] **Step 4: Run frontend typecheck**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
pnpm typecheck
```

Expected: PASS.

- [ ] **Step 5: Manual smoke check where PM2 is available**

Run against an environment where the Armada backend process can execute `pm2` and can reach the protocol ports on localhost:

```bash
curl -sS -X POST http://127.0.0.1:8080/api/protocol/restart
```

Expected successful shape:

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "success": true,
    "message": "协议进程已重启"
  }
}
```

If the environment lacks PM2 or the protocol processes are on another host, expected shape is still HTTP 200 with `code=0` and `data.success=false`; record `data.message` in the final handoff.

- [ ] **Step 6: Check dirty worktrees before final response**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git status --short
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git status --short
```

Expected: only task-related files are changed by this implementation; existing unrelated user changes remain untouched.
