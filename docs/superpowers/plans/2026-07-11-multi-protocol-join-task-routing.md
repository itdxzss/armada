# Web / Android 双协议进群任务 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在只修改 Armada 的前提下，让进群任务根据账号 `protocol_id` 分别调用 Web/Baileys 与 Android Zhuan 原生 HTTP 接口，并把两套请求、响应和错误统一成 Armada 领域结果。

**Architecture:** 业务层依赖统一的 `GroupJoinPort` 与 `AccountRuntimeStatusPort`，两个 routing port 根据 `ProtocolBackend` 选择 Web 或 Android backend adapter。Web adapter 保持现有契约；Android adapter 调用 Zhuan 现有 `/ws/v1/auth/status/{phone}`、`/ws/v1/groups/invite/{phone}` 和 `/ws/v1/groups/members/{phone}`，在 Armada 内解析 `Code/Data/Msg` 并二次确认真实入群。

**Tech Stack:** Java 17、Spring Boot 3.3、Spring `RestClient`、Jackson、JUnit 5、AssertJ、Mockito、Maven

---

## Scope and execution rules

- Implementation repository: `armada/`, module `armada-api/`.
- Do not modify `armada-protocol/` or `whatsapp-server-feature-android-zhuan/`.
- Execute every production behavior with RED-GREEN-REFACTOR: add one focused test, run it and observe the expected failure, add the minimum code, rerun, then refactor only while green.
- Commit after each task. Do not combine tasks into one large commit.
- Preserve unrelated workspace changes under `.claude/worktrees/`; never add them to commits.
- Run Maven commands from `armada/armada-api`.
- The design source of truth is `docs/superpowers/specs/2026-07-11-multi-protocol-join-task-routing-design.md`.
- Slice 7 reliability work and Slice 8 marketing delivery are explicitly excluded from this implementation plan.

## Locked file structure

### New production files

```text
armada-api/src/main/java/com/armada/platform/protocol/
├── backend/android/
│   ├── AndroidAccountRuntimeStatusAdapter.java
│   ├── AndroidDecodedResponse.java
│   ├── AndroidGroupJoinErrorMapper.java
│   ├── AndroidGroupJoinResponseMapper.java
│   ├── AndroidGroupMembershipVerifier.java
│   ├── AndroidNativeClient.java
│   ├── AndroidNativeGroupJoinAdapter.java
│   ├── AndroidResponseDecoder.java
│   ├── AndroidResponseEnvelope.java
│   └── HttpAndroidNativeClient.java
├── backend/web/
│   ├── WebAccountRuntimeStatusAdapter.java
│   └── WebNativeGroupJoinAdapter.java
├── config/
│   └── ProtocolBackendHttpProperties.java
├── http/
│   └── ProtocolHttpExecutorRegistry.java
├── model/command/
│   ├── GroupJoinCommand.java
│   └── ProtocolAccountRef.java
├── model/result/
│   ├── GroupJoinOutcome.java
│   └── ProtocolAccountRuntimeStatus.java
├── port/
│   └── AccountRuntimeStatusPort.java
└── routing/
    ├── AccountRuntimeStatusBackend.java
    ├── GroupJoinBackend.java
    ├── RoutingAccountRuntimeStatusPort.java
    └── RoutingGroupJoinPort.java
```

### New test files

```text
armada-api/src/test/java/com/armada/platform/protocol/
├── backend/android/
│   ├── AndroidAccountRuntimeStatusAdapterTest.java
│   ├── AndroidGroupJoinResponseMapperTest.java
│   ├── AndroidGroupMembershipVerifierTest.java
│   ├── AndroidNativeGroupJoinAdapterTest.java
│   ├── AndroidResponseDecoderTest.java
│   └── HttpAndroidNativeClientTest.java
├── backend/web/
│   ├── WebAccountRuntimeStatusAdapterTest.java
│   └── WebNativeGroupJoinAdapterTest.java
├── http/
│   └── ProtocolHttpExecutorRegistryTest.java
└── routing/
    ├── RoutingAccountRuntimeStatusPortTest.java
    └── RoutingGroupJoinPortTest.java
```

### Modified or removed files

```text
armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolProperties.java
armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java
armada-api/src/main/java/com/armada/platform/protocol/exception/ProtocolErrorCode.java
armada-api/src/main/java/com/armada/platform/protocol/exception/ProtocolException.java
armada-api/src/main/java/com/armada/platform/protocol/http/ProtocolHttpExecutor.java
armada-api/src/main/java/com/armada/platform/protocol/model/result/GroupJoinResult.java
armada-api/src/main/java/com/armada/platform/protocol/port/GroupJoinPort.java
armada-api/src/main/java/com/armada/task/model/enums/JoinTaskFailureReason.java
armada-api/src/main/java/com/armada/task/worker/JoinTaskWorker.java
armada-api/src/main/resources/application.yml
armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java
armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolPropertiesTest.java
armada-api/src/test/java/com/armada/platform/protocol/exception/ProtocolExceptionTest.java
armada-api/src/test/java/com/armada/platform/protocol/http/ProtocolHttpExecutorTest.java
armada-api/src/test/java/com/armada/task/model/enums/JoinTaskFailureReasonTest.java
armada-api/src/test/java/com/armada/task/worker/JoinTaskWorkerTest.java
armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupJoinAdapter.java       # remove after Web adapter migration
armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupJoinAdapterTest.java  # remove after test migration
```

---

### Task 1: Add backend-scoped HTTP configuration without changing existing Web callers

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolBackendHttpProperties.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/http/ProtocolHttpExecutorRegistry.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/http/ProtocolHttpExecutorRegistryTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolProperties.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`
- Modify: `armada-api/src/main/resources/application.yml`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolPropertiesTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java`

- [ ] **Step 1: Write failing property-binding tests for independent Web and Android endpoints**

Add to `ProtocolPropertiesTest`:

```java
@Test
void bindsBackendSpecificHttpProperties() {
    contextRunner
            .withPropertyValues(
                    "armada.protocol.backends.WEB.base-url=https://web-protocol.internal",
                    "armada.protocol.backends.WEB.api-key=web-key",
                    "armada.protocol.backends.ANDROID.base-url=https://android-protocol.internal",
                    "armada.protocol.backends.ANDROID.api-key=android-key",
                    "armada.protocol.backends.ANDROID.connect-timeout-ms=2345",
                    "armada.protocol.backends.ANDROID.read-timeout-ms=6789")
            .run(context -> {
                ProtocolProperties properties = context.getBean(ProtocolProperties.class);

                ProtocolBackendHttpProperties web = properties.requireBackend(ProtocolBackend.WEB);
                ProtocolBackendHttpProperties android = properties.requireBackend(ProtocolBackend.ANDROID);
                assertThat(web.getBaseUrl()).isEqualTo("https://web-protocol.internal");
                assertThat(web.getApiKey()).isEqualTo("web-key");
                assertThat(android.getBaseUrl()).isEqualTo("https://android-protocol.internal");
                assertThat(android.getApiKey()).isEqualTo("android-key");
                assertThat(android.getConnectTimeoutMs()).isEqualTo(2345);
                assertThat(android.getReadTimeoutMs()).isEqualTo(6789);
            });
}

@Test
void legacyConnectionPropertiesRemainWebFallbackOnly() {
    contextRunner
            .withPropertyValues(
                    "armada.protocol.base-url=https://legacy-web.internal",
                    "armada.protocol.api-key=legacy-key")
            .run(context -> {
                ProtocolProperties properties = context.getBean(ProtocolProperties.class);

                assertThat(properties.requireBackend(ProtocolBackend.WEB).getBaseUrl())
                        .isEqualTo("https://legacy-web.internal");
                assertThatThrownBy(() -> properties.requireBackend(ProtocolBackend.ANDROID))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("ANDROID");
            });
}
```

Add imports:

```java
import com.armada.platform.protocol.model.enums.ProtocolBackend;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [ ] **Step 2: Run the property tests and verify RED**

Run:

```bash
mvn -Dtest=ProtocolPropertiesTest test
```

Expected: compilation fails because `ProtocolBackendHttpProperties` and `requireBackend` do not exist.

- [ ] **Step 3: Add the backend property value object**

Create `ProtocolBackendHttpProperties.java`:

```java
package com.armada.platform.protocol.config;

public class ProtocolBackendHttpProperties {

    private String baseUrl;
    private String apiKey = "";
    private int connectTimeoutMs = ProtocolProperties.DEFAULT_CONNECT_TIMEOUT_MS;
    private int readTimeoutMs = ProtocolProperties.DEFAULT_READ_TIMEOUT_MS;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public String toString() {
        return "ProtocolBackendHttpProperties{"
                + "baseUrl=<redacted>"
                + ", apiKey=<redacted>"
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", readTimeoutMs=" + readTimeoutMs
                + '}';
    }
}
```

- [ ] **Step 4: Extend ProtocolProperties with backend lookup and Web-only legacy fallback**

Add an `EnumMap`-compatible property and accessors to `ProtocolProperties.java`:

```java
private Map<ProtocolBackend, ProtocolBackendHttpProperties> backends =
        new EnumMap<>(ProtocolBackend.class);

public Map<ProtocolBackend, ProtocolBackendHttpProperties> getBackends() {
    return backends;
}

public void setBackends(Map<ProtocolBackend, ProtocolBackendHttpProperties> backends) {
    EnumMap<ProtocolBackend, ProtocolBackendHttpProperties> copy =
            new EnumMap<>(ProtocolBackend.class);
    if (backends != null) {
        copy.putAll(backends);
    }
    this.backends = copy;
}

public ProtocolBackendHttpProperties requireBackend(ProtocolBackend backend) {
    ProtocolBackend safeBackend = backend == null ? ProtocolBackend.WEB : backend;
    ProtocolBackendHttpProperties configured = backends.get(safeBackend);
    if (configured != null && configured.getBaseUrl() != null && !configured.getBaseUrl().isBlank()) {
        return configured;
    }
    if (safeBackend == ProtocolBackend.WEB && baseUrl != null && !baseUrl.isBlank()) {
        ProtocolBackendHttpProperties legacy = new ProtocolBackendHttpProperties();
        legacy.setBaseUrl(baseUrl);
        legacy.setApiKey(apiKey);
        legacy.setConnectTimeoutMs(connectTimeoutMs);
        legacy.setReadTimeoutMs(readTimeoutMs);
        return legacy;
    }
    throw new IllegalStateException("协议后端 HTTP 地址未配置 backend=" + safeBackend);
}
```

Add imports:

```java
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.EnumMap;
import java.util.Map;
```

Keep the existing legacy fields and getters intact so current deployments still resolve Web.

- [ ] **Step 5: Run property tests and verify GREEN**

Run:

```bash
mvn -Dtest=ProtocolPropertiesTest test
```

Expected: all `ProtocolPropertiesTest` tests pass.

- [ ] **Step 6: Write the failing executor registry test**

Create `ProtocolHttpExecutorRegistryTest.java`:

```java
package com.armada.platform.protocol.http;

import com.armada.platform.protocol.model.enums.ProtocolBackend;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolHttpExecutorRegistryTest {

    @Test
    void returnsExecutorForEachRegisteredBackendAndRejectsMissingBackend() {
        ProtocolHttpExecutor web = executor("http://web.internal");
        ProtocolHttpExecutor android = executor("http://android.internal");
        ProtocolHttpExecutorRegistry registry = new ProtocolHttpExecutorRegistry(Map.of(
                ProtocolBackend.WEB, web,
                ProtocolBackend.ANDROID, android));

        assertThat(registry.required(ProtocolBackend.WEB)).isSameAs(web);
        assertThat(registry.required(ProtocolBackend.ANDROID)).isSameAs(android);
        assertThatThrownBy(() -> new ProtocolHttpExecutorRegistry(Map.of(ProtocolBackend.WEB, web))
                .required(ProtocolBackend.ANDROID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANDROID");
    }

    private static ProtocolHttpExecutor executor(String baseUrl) {
        return new ProtocolHttpExecutor(RestClient.builder().baseUrl(baseUrl).build());
    }
}
```

- [ ] **Step 7: Run the registry test and verify RED**

Run:

```bash
mvn -Dtest=ProtocolHttpExecutorRegistryTest test
```

Expected: compilation fails because `ProtocolHttpExecutorRegistry` does not exist.

- [ ] **Step 8: Implement the executor registry**

Create `ProtocolHttpExecutorRegistry.java`:

```java
package com.armada.platform.protocol.http;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

import java.util.EnumMap;
import java.util.Map;

public final class ProtocolHttpExecutorRegistry {

    private final Map<ProtocolBackend, ProtocolHttpExecutor> executors;

    public ProtocolHttpExecutorRegistry(Map<ProtocolBackend, ProtocolHttpExecutor> executors) {
        EnumMap<ProtocolBackend, ProtocolHttpExecutor> copy = new EnumMap<>(ProtocolBackend.class);
        if (executors != null) {
            executors.forEach((backend, executor) -> {
                if (backend != null && executor != null) {
                    copy.put(backend, executor);
                }
            });
        }
        this.executors = Map.copyOf(copy);
    }

    public ProtocolHttpExecutor required(ProtocolBackend backend) {
        ProtocolHttpExecutor executor = executors.get(backend);
        if (executor == null) {
            throw new IllegalStateException("协议后端 HTTP executor 未注册 backend=" + backend);
        }
        return executor;
    }
}
```

- [ ] **Step 9: Register the Android executor while preserving the existing Web beans**

Refactor `ProtocolConfiguration` so `protocolRestClient` uses `properties.requireBackend(WEB)`, keep `protocolHttpExecutor` as the single legacy Web bean, and add the registry:

```java
@Bean
public RestClient protocolRestClient(ProtocolProperties properties) {
    return buildRestClient(properties.requireBackend(ProtocolBackend.WEB));
}

@Bean
public ProtocolHttpExecutor protocolHttpExecutor(RestClient protocolRestClient) {
    return new ProtocolHttpExecutor(protocolRestClient);
}

@Bean
public ProtocolHttpExecutorRegistry protocolHttpExecutorRegistry(
        ProtocolProperties properties,
        ProtocolHttpExecutor protocolHttpExecutor) {
    EnumMap<ProtocolBackend, ProtocolHttpExecutor> executors = new EnumMap<>(ProtocolBackend.class);
    executors.put(ProtocolBackend.WEB, protocolHttpExecutor);
    executors.put(ProtocolBackend.ANDROID, new ProtocolHttpExecutor(
            buildRestClient(properties.requireBackend(ProtocolBackend.ANDROID))));
    return new ProtocolHttpExecutorRegistry(executors);
}

private static RestClient buildRestClient(ProtocolBackendHttpProperties properties) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(properties.getConnectTimeoutMs());
    factory.setReadTimeout(properties.getReadTimeoutMs());
    RestClient.Builder builder = RestClient.builder()
            .baseUrl(properties.getBaseUrl())
            .requestFactory(factory)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
    if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
        builder.defaultHeader(ProtocolHttpExecutor.API_KEY_HEADER, properties.getApiKey());
    }
    return builder.build();
}
```

Add imports for `ProtocolBackend`, `ProtocolHttpExecutorRegistry`, and `EnumMap`.

- [ ] **Step 10: Add backend defaults to application.yml**

Under `armada.protocol`, retain legacy keys and add:

```yaml
    backends:
      WEB:
        base-url: ${PROTOCOL_WEB_BASE_URL:${PROTOCOL_BASE_URL:http://localhost:3000}}
        api-key: ${PROTOCOL_WEB_API_KEY:${PROTOCOL_API_KEY:}}
        connect-timeout-ms: ${PROTOCOL_WEB_CONNECT_TIMEOUT_MS:3000}
        read-timeout-ms: ${PROTOCOL_WEB_READ_TIMEOUT_MS:60000}
      ANDROID:
        base-url: ${PROTOCOL_ANDROID_BASE_URL:http://localhost:8000}
        api-key: ${PROTOCOL_ANDROID_API_KEY:}
        connect-timeout-ms: ${PROTOCOL_ANDROID_CONNECT_TIMEOUT_MS:3000}
        read-timeout-ms: ${PROTOCOL_ANDROID_READ_TIMEOUT_MS:60000}
```

- [ ] **Step 11: Update configuration tests and run the focused suite**

Update `ProtocolConfigurationTest` to assert one legacy Web `RestClient`, one legacy Web `ProtocolHttpExecutor`, and one registry containing distinct Web and Android executors:

```java
assertThat(context).hasSingleBean(RestClient.class);
assertThat(context).hasSingleBean(ProtocolHttpExecutor.class);
assertThat(context).hasSingleBean(ProtocolHttpExecutorRegistry.class);

ProtocolHttpExecutorRegistry registry = context.getBean(ProtocolHttpExecutorRegistry.class);
assertThat(registry.required(ProtocolBackend.WEB))
        .isSameAs(context.getBean(ProtocolHttpExecutor.class));
assertThat(registry.required(ProtocolBackend.ANDROID))
        .isNotSameAs(context.getBean(ProtocolHttpExecutor.class));
```

Run:

```bash
mvn -Dtest=ProtocolPropertiesTest,ProtocolConfigurationTest,ProtocolHttpExecutorRegistryTest test
```

Expected: all focused tests pass.

- [ ] **Step 12: Run the existing protocol configuration regression tests**

Run:

```bash
mvn -Dtest='com.armada.platform.protocol.config.*Test' test
```

Expected: all protocol configuration tests pass.

- [ ] **Step 13: Commit Slice 1**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/config \
  armada-api/src/main/java/com/armada/platform/protocol/http/ProtocolHttpExecutorRegistry.java \
  armada-api/src/main/resources/application.yml \
  armada-api/src/test/java/com/armada/platform/protocol/config \
  armada-api/src/test/java/com/armada/platform/protocol/http/ProtocolHttpExecutorRegistryTest.java
git commit -m "feat(protocol): add backend scoped HTTP clients"
```

---

### Task 2: Introduce canonical join models, canonical errors, routing, and the Web backend

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolAccountRef.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/command/GroupJoinCommand.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/result/GroupJoinOutcome.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/GroupJoinBackend.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupJoinPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/web/WebNativeGroupJoinAdapter.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingGroupJoinPortTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/web/WebNativeGroupJoinAdapterTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/model/result/GroupJoinResult.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/port/GroupJoinPort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/exception/ProtocolErrorCode.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/exception/ProtocolException.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/ProtocolHttpExecutor.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/exception/ProtocolExceptionTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/http/ProtocolHttpExecutorTest.java`
- Remove: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupJoinAdapter.java`
- Remove: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupJoinAdapterTest.java`

- [ ] **Step 1: Write failing canonical model and routing tests**

Create `RoutingGroupJoinPortTest.java`:

```java
package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingGroupJoinPortTest {

    @Test
    void routesOnlyToTheBackendSelectedByTheAccountReference() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        RecordingBackend android = new RecordingBackend(ProtocolBackend.ANDROID);
        RoutingGroupJoinPort port = new RoutingGroupJoinPort(List.of(web, android));
        GroupJoinCommand command = command(ProtocolBackend.ANDROID);

        GroupJoinResult result = port.join(command);

        assertThat(result.outcome()).isEqualTo(GroupJoinOutcome.JOINED);
        assertThat(web.lastCommand).isNull();
        assertThat(android.lastCommand).isSameAs(command);
    }

    @Test
    void rejectsDuplicateAndMissingBackendRegistrations() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        assertThatThrownBy(() -> new RoutingGroupJoinPort(List.of(web, web)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEB");

        RoutingGroupJoinPort port = new RoutingGroupJoinPort(List.of(web));
        assertThatThrownBy(() -> port.join(command(ProtocolBackend.ANDROID)))
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_BACKEND));
    }

    private static GroupJoinCommand command(ProtocolBackend backend) {
        return new GroupJoinCommand(
                new ProtocolAccountRef(10L, backend, "acc_919000000001", "919000000001"),
                "https://chat.whatsapp.com/ABC123",
                "join-task-result:77");
    }

    private static final class RecordingBackend implements GroupJoinBackend {
        private final ProtocolBackend backend;
        private GroupJoinCommand lastCommand;

        private RecordingBackend(ProtocolBackend backend) {
            this.backend = backend;
        }

        @Override
        public ProtocolBackend backend() {
            return backend;
        }

        @Override
        public GroupJoinResult join(GroupJoinCommand command) {
            lastCommand = command;
            return new GroupJoinResult("120363joined@g.us", GroupJoinOutcome.JOINED);
        }
    }
}
```

- [ ] **Step 2: Run the routing test and verify RED**

Run:

```bash
mvn -Dtest=RoutingGroupJoinPortTest test
```

Expected: compilation fails because the canonical models and routing types do not exist.

- [ ] **Step 3: Add canonical account, command, outcome, and result types**

Create `ProtocolAccountRef.java`:

```java
package com.armada.platform.protocol.model.command;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

public record ProtocolAccountRef(
        Long armadaAccountId,
        ProtocolBackend backend,
        String protocolAccountId,
        String wsPhone
) {
    public ProtocolAccountRef {
        if (armadaAccountId == null) {
            throw new IllegalArgumentException("armadaAccountId 不能为空");
        }
        backend = backend == null ? ProtocolBackend.WEB : backend;
        protocolAccountId = requireText(protocolAccountId, "protocolAccountId");
        wsPhone = requireText(wsPhone, "wsPhone");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
```

Create `GroupJoinCommand.java`:

```java
package com.armada.platform.protocol.model.command;

public record GroupJoinCommand(
        ProtocolAccountRef account,
        String inviteLinkOrCode,
        String operationId
) {
    public GroupJoinCommand {
        if (account == null) {
            throw new IllegalArgumentException("account 不能为空");
        }
        if (inviteLinkOrCode == null || inviteLinkOrCode.isBlank()) {
            throw new IllegalArgumentException("inviteLinkOrCode 不能为空");
        }
        inviteLinkOrCode = inviteLinkOrCode.trim();
        operationId = operationId == null ? "" : operationId.trim();
    }
}
```

Create `GroupJoinOutcome.java`:

```java
package com.armada.platform.protocol.model.result;

public enum GroupJoinOutcome {
    JOINED,
    ALREADY_JOINED,
    PENDING_APPROVAL
}
```

Replace `GroupJoinResult` with:

```java
package com.armada.platform.protocol.model.result;

public record GroupJoinResult(String groupJid, GroupJoinOutcome outcome) {

    public GroupJoinResult {
        groupJid = groupJid == null ? "" : groupJid.trim();
        if (outcome == null) {
            throw new IllegalArgumentException("outcome 不能为空");
        }
    }

    public boolean joined() {
        return outcome == GroupJoinOutcome.JOINED || outcome == GroupJoinOutcome.ALREADY_JOINED;
    }
}
```

- [ ] **Step 4: Add the backend SPI and routing port**

Create `GroupJoinBackend.java`:

```java
package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinResult;

public interface GroupJoinBackend {
    ProtocolBackend backend();
    GroupJoinResult join(GroupJoinCommand command);
}
```

Change `GroupJoinPort` to:

```java
package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.result.GroupJoinResult;

public interface GroupJoinPort {
    GroupJoinResult join(GroupJoinCommand command);
}
```

Create `RoutingGroupJoinPort.java`:

```java
package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.port.GroupJoinPort;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class RoutingGroupJoinPort implements GroupJoinPort {

    private final Map<ProtocolBackend, GroupJoinBackend> backends;

    public RoutingGroupJoinPort(List<GroupJoinBackend> implementations) {
        EnumMap<ProtocolBackend, GroupJoinBackend> resolved = new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (GroupJoinBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                GroupJoinBackend previous = resolved.putIfAbsent(implementation.backend(), implementation);
                if (previous != null) {
                    throw new IllegalStateException("重复的进群协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    @Override
    public GroupJoinResult join(GroupJoinCommand command) {
        ProtocolBackend backend = command.account().backend();
        GroupJoinBackend implementation = backends.get(backend);
        if (implementation == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "进群协议后端未注册 backend=" + backend)
                    .withContext(backend, "group.join", command.operationId());
        }
        return implementation.join(command);
    }
}
```

- [ ] **Step 5: Add canonical error codes and context-aware ProtocolException**

Add these enum values to `ProtocolErrorCode` before `UNKNOWN`:

```java
ACCOUNT_NOT_FOUND,
ACCOUNT_NOT_ONLINE,
BAD_REQUEST,
INVALID_GROUP_LINK,
GROUP_JOIN_REJECTED,
JOIN_RESULT_UNCONFIRMED,
ANDROID_RESPONSE_UNRECOGNIZED,
UNSUPPORTED_BACKEND,
```

Add fields and accessors to `ProtocolException`:

```java
private final ProtocolBackend backend;
private final String operation;
private final String operationId;

public Optional<ProtocolBackend> backend() {
    return Optional.ofNullable(backend);
}

public Optional<String> operation() {
    return Optional.ofNullable(operation);
}

public Optional<String> operationId() {
    return Optional.ofNullable(operationId);
}

public boolean retryable() {
    return switch (errorCode) {
        case TIMEOUT, NETWORK, ACCOUNT_BUSY, WORKER_BUSY, JOIN_RESULT_UNCONFIRMED -> true;
        default -> false;
    };
}

public ProtocolException withContext(
        ProtocolBackend backend,
        String operation,
        String operationId) {
    return new ProtocolException(
            errorCode,
            Metadata.of(httpStatus, protocolCode, retryAfterMs, ownerEndpoint),
            getMessage(),
            getCause(),
            backend,
            normalizeText(operation),
            normalizeText(operationId));
}
```

Introduce a private seven-argument constructor and make the existing four-argument constructor delegate to it with null context:

```java
private ProtocolException(
        ProtocolErrorCode errorCode,
        Metadata metadata,
        String message,
        Throwable cause,
        ProtocolBackend backend,
        String operation,
        String operationId) {
    super(normalizeMessage(message), cause);
    Metadata safeMetadata = metadata == null ? Metadata.empty() : metadata;
    this.errorCode = errorCode == null ? ProtocolErrorCode.UNKNOWN : errorCode;
    this.httpStatus = safeMetadata.httpStatus;
    this.protocolCode = safeMetadata.protocolCode;
    this.retryAfterMs = safeMetadata.retryAfterMs;
    this.ownerEndpoint = safeMetadata.ownerEndpoint;
    this.backend = backend;
    this.operation = operation;
    this.operationId = operationId;
}
```

Replace the existing public four-argument constructor body with this delegation and add the `ProtocolBackend` import:

```java
public ProtocolException(
        ProtocolErrorCode errorCode,
        Metadata metadata,
        String message,
        Throwable cause) {
    this(errorCode, metadata, message, cause, null, null, null);
}
```

- [ ] **Step 6: Add exception behavior tests and verify GREEN**

Add to `ProtocolExceptionTest`:

```java
@Test
void withContextPreservesProtocolMetadataAndAddsCanonicalCallContext() {
    ProtocolException original = new ProtocolException(
            ProtocolErrorCode.ACCOUNT_BUSY,
            ProtocolException.Metadata.of(429, "ACCOUNT_BUSY", 3000L, null),
            "busy",
            null);

    ProtocolException contextual = original.withContext(
            ProtocolBackend.ANDROID,
            "group.join",
            "join-task-result:77");

    assertThat(contextual.errorCode()).isEqualTo(ProtocolErrorCode.ACCOUNT_BUSY);
    assertThat(contextual.protocolCode()).contains("ACCOUNT_BUSY");
    assertThat(contextual.retryAfterMs()).contains(3000L);
    assertThat(contextual.backend()).contains(ProtocolBackend.ANDROID);
    assertThat(contextual.operation()).contains("group.join");
    assertThat(contextual.operationId()).contains("join-task-result:77");
    assertThat(contextual.retryable()).isTrue();
}
```

Run:

```bash
mvn -Dtest=ProtocolExceptionTest,RoutingGroupJoinPortTest test
```

Expected: both test classes pass.

- [ ] **Step 7: Normalize Web raw error names into canonical error codes**

Change `ProtocolHttpExecutor.mapErrorCode` to normalize case and hyphens:

```java
private static ProtocolErrorCode mapErrorCode(String protocolCode, int httpStatus) {
    if (protocolCode != null && !protocolCode.isBlank()) {
        String normalized = protocolCode.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return ProtocolErrorCode.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return ProtocolErrorCode.UNKNOWN;
        }
    }
    return httpStatus >= 400 ? ProtocolErrorCode.HTTP_ERROR : ProtocolErrorCode.UNKNOWN;
}
```

Add `java.util.Locale` import. Add a `ProtocolHttpExecutorTest` case whose response is HTTP 400 with `{"code":"bad-request","message":"bad"}` and assert `errorCode()==BAD_REQUEST` while `protocolCode()` still contains `bad-request`.

- [ ] **Step 8: Write the failing Web native adapter tests**

Create `WebNativeGroupJoinAdapterTest.java` by moving the two current `HttpGroupJoinAdapterTest` cases and changing construction/calls to:

```java
WebNativeGroupJoinAdapter adapter = new WebNativeGroupJoinAdapter(
        new ProtocolHttpExecutor(builder.build()));
GroupJoinResult result = adapter.join(new GroupJoinCommand(
        new ProtocolAccountRef(1L, ProtocolBackend.WEB, "acc_861111", "861111"),
        "https://chat.whatsapp.com/ABC123",
        "join-task-result:1"));

assertThat(result.outcome()).isEqualTo(GroupJoinOutcome.JOINED);
```

For the pure code case assert `PENDING_APPROVAL`. Retain the exact request JSON assertions from the old tests.

- [ ] **Step 9: Run the Web adapter test and verify RED**

Run:

```bash
mvn -Dtest=WebNativeGroupJoinAdapterTest test
```

Expected: compilation fails because `WebNativeGroupJoinAdapter` does not exist.

- [ ] **Step 10: Implement the Web backend adapter**

Create `WebNativeGroupJoinAdapter.java`:

```java
package com.armada.platform.protocol.backend.web;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.routing.GroupJoinBackend;
import com.fasterxml.jackson.annotation.JsonInclude;

public final class WebNativeGroupJoinAdapter implements GroupJoinBackend {

    private static final String JOIN_URI = "/v1/groups/join";
    private final ProtocolHttpExecutor httpExecutor;

    public WebNativeGroupJoinAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.WEB;
    }

    @Override
    public GroupJoinResult join(GroupJoinCommand command) {
        try {
            JoinResponse response = httpExecutor.postTyped(
                    JOIN_URI,
                    request(command.account().protocolAccountId(), command.inviteLinkOrCode()),
                    JoinResponse.class);
            return new GroupJoinResult(
                    response.groupJid(),
                    response.joined() ? GroupJoinOutcome.JOINED : GroupJoinOutcome.PENDING_APPROVAL);
        } catch (ProtocolException ex) {
            throw ex.withContext(ProtocolBackend.WEB, "group.join", command.operationId());
        }
    }

    private static JoinRequest request(String accountId, String invite) {
        if (invite.startsWith("http://") || invite.startsWith("https://")) {
            return new JoinRequest(accountId, null, invite);
        }
        return new JoinRequest(accountId, invite, null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record JoinRequest(String accountId, String inviteCode, String inviteLink) {
    }

    private record JoinResponse(String groupJid, boolean joined) {
    }
}
```

- [ ] **Step 11: Wire the routing port and remove the old Web adapter**

Replace the `groupJoinPort` bean in `ProtocolConfiguration` with:

```java
@Bean
public GroupJoinBackend webGroupJoinBackend(ProtocolHttpExecutorRegistry registry) {
    return new WebNativeGroupJoinAdapter(registry.required(ProtocolBackend.WEB));
}

@Bean
public GroupJoinPort groupJoinPort(List<GroupJoinBackend> backends) {
    return new RoutingGroupJoinPort(backends);
}
```

Remove `HttpGroupJoinAdapter.java` and its old test after the new test covers both request shapes.

- [ ] **Step 12: Run the canonical join and Web regression suite**

Run:

```bash
mvn -Dtest=ProtocolExceptionTest,ProtocolHttpExecutorTest,RoutingGroupJoinPortTest,WebNativeGroupJoinAdapterTest,ProtocolConfigurationTest test
```

Expected: all focused tests pass and Spring exposes a single `GroupJoinPort`.

- [ ] **Step 13: Commit Slice 2**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol \
  armada-api/src/test/java/com/armada/platform/protocol
git commit -m "refactor(protocol): route group join by backend"
```

---

### Task 3: Split runtime status into a backend-aware narrow port

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/result/ProtocolAccountRuntimeStatus.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/port/AccountRuntimeStatusPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/AccountRuntimeStatusBackend.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingAccountRuntimeStatusPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/web/WebAccountRuntimeStatusAdapter.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingAccountRuntimeStatusPortTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/web/WebAccountRuntimeStatusAdapterTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`

- [ ] **Step 1: Write failing routing and Web status tests**

Create `RoutingAccountRuntimeStatusPortTest.java`:

```java
package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingAccountRuntimeStatusPortTest {

    @Test
    void routesStatusOnlyToTheSelectedBackend() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        RecordingBackend android = new RecordingBackend(ProtocolBackend.ANDROID);
        RoutingAccountRuntimeStatusPort port =
                new RoutingAccountRuntimeStatusPort(List.of(web, android));
        ProtocolAccountRef account = account(ProtocolBackend.ANDROID);

        ProtocolAccountRuntimeStatus result = port.status(account);

        assertThat(result.online()).isTrue();
        assertThat(web.lastAccount).isNull();
        assertThat(android.lastAccount).isSameAs(account);
    }

    @Test
    void rejectsDuplicateAndMissingStatusBackends() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        assertThatThrownBy(() -> new RoutingAccountRuntimeStatusPort(List.of(web, web)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEB");

        RoutingAccountRuntimeStatusPort port =
                new RoutingAccountRuntimeStatusPort(List.of(web));
        assertThatThrownBy(() -> port.status(account(ProtocolBackend.ANDROID)))
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.errorCode())
                                .isEqualTo(ProtocolErrorCode.UNSUPPORTED_BACKEND));
    }

    private static ProtocolAccountRef account(ProtocolBackend backend) {
        return new ProtocolAccountRef(10L, backend, "acc_919000000001", "919000000001");
    }

    private static final class RecordingBackend implements AccountRuntimeStatusBackend {
        private final ProtocolBackend backend;
        private ProtocolAccountRef lastAccount;

        private RecordingBackend(ProtocolBackend backend) {
            this.backend = backend;
        }

        @Override
        public ProtocolBackend backend() {
            return backend;
        }

        @Override
        public ProtocolAccountRuntimeStatus status(ProtocolAccountRef account) {
            lastAccount = account;
            return new ProtocolAccountRuntimeStatus("ONLINE");
        }
    }
}
```

Create `WebAccountRuntimeStatusAdapterTest`:

```java
@Test
void getsWebRuntimeStateFromTheExistingStatusEndpoint() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://web.internal");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    WebAccountRuntimeStatusAdapter adapter = new WebAccountRuntimeStatusAdapter(
            new ProtocolHttpExecutor(builder.build()));
    ProtocolAccountRef account = new ProtocolAccountRef(
            1L, ProtocolBackend.WEB, "acc_861001", "861001");

    server.expect(requestTo("http://web.internal/v1/accounts/acc_861001/status"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                    {"accountId":"acc_861001","state":"ONLINE"}
                    """, MediaType.APPLICATION_JSON));

    ProtocolAccountRuntimeStatus result = adapter.status(account);

    assertThat(result.state()).isEqualTo("ONLINE");
    assertThat(result.online()).isTrue();
    server.verify();
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
mvn -Dtest=RoutingAccountRuntimeStatusPortTest,WebAccountRuntimeStatusAdapterTest test
```

Expected: compilation fails because the new port and adapters do not exist.

- [ ] **Step 3: Implement the runtime status model and routing**

Create `ProtocolAccountRuntimeStatus.java`:

```java
package com.armada.platform.protocol.model.result;

public record ProtocolAccountRuntimeStatus(String state) {

    public ProtocolAccountRuntimeStatus {
        state = state == null ? "UNKNOWN" : state.trim();
    }

    public boolean online() {
        return "ONLINE".equalsIgnoreCase(state);
    }
}
```

Create `AccountRuntimeStatusPort.java`:

```java
package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;

public interface AccountRuntimeStatusPort {
    ProtocolAccountRuntimeStatus status(ProtocolAccountRef account);
}
```

Create `AccountRuntimeStatusBackend.java`:

```java
package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;

public interface AccountRuntimeStatusBackend {
    ProtocolBackend backend();
    ProtocolAccountRuntimeStatus status(ProtocolAccountRef account);
}
```

Create `RoutingAccountRuntimeStatusPort.java`:

```java
package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;
import com.armada.platform.protocol.port.AccountRuntimeStatusPort;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class RoutingAccountRuntimeStatusPort implements AccountRuntimeStatusPort {

    private final Map<ProtocolBackend, AccountRuntimeStatusBackend> backends;

    public RoutingAccountRuntimeStatusPort(List<AccountRuntimeStatusBackend> implementations) {
        EnumMap<ProtocolBackend, AccountRuntimeStatusBackend> resolved =
                new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (AccountRuntimeStatusBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                AccountRuntimeStatusBackend previous =
                        resolved.putIfAbsent(implementation.backend(), implementation);
                if (previous != null) {
                    throw new IllegalStateException(
                            "重复的账号运行态协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    @Override
    public ProtocolAccountRuntimeStatus status(ProtocolAccountRef account) {
        ProtocolBackend backend = account.backend();
        AccountRuntimeStatusBackend implementation = backends.get(backend);
        if (implementation == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "账号运行态协议后端未注册 backend=" + backend)
                    .withContext(backend, "account.status", "account:" + account.armadaAccountId());
        }
        return implementation.status(account);
    }
}
```

- [ ] **Step 4: Implement the Web status backend**

Create `WebAccountRuntimeStatusAdapter.java`:

```java
package com.armada.platform.protocol.backend.web;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;
import com.armada.platform.protocol.routing.AccountRuntimeStatusBackend;

public final class WebAccountRuntimeStatusAdapter implements AccountRuntimeStatusBackend {

    private final ProtocolHttpExecutor httpExecutor;

    public WebAccountRuntimeStatusAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.WEB;
    }

    @Override
    public ProtocolAccountRuntimeStatus status(ProtocolAccountRef account) {
        try {
            StatusResponse response = httpExecutor.getTyped(
                    "/v1/accounts/" + account.protocolAccountId() + "/status",
                    StatusResponse.class);
            return new ProtocolAccountRuntimeStatus(response == null ? null : response.state());
        } catch (ProtocolException ex) {
            throw ex.withContext(ProtocolBackend.WEB, "account.status", "account:" + account.armadaAccountId());
        }
    }

    private record StatusResponse(String accountId, String state) {
    }
}
```

- [ ] **Step 5: Register the Web status backend and routing port**

Add to `ProtocolConfiguration`:

```java
@Bean
public AccountRuntimeStatusBackend webAccountRuntimeStatusBackend(
        ProtocolHttpExecutorRegistry registry) {
    return new WebAccountRuntimeStatusAdapter(registry.required(ProtocolBackend.WEB));
}

@Bean
public AccountRuntimeStatusPort accountRuntimeStatusPort(
        List<AccountRuntimeStatusBackend> backends) {
    return new RoutingAccountRuntimeStatusPort(backends);
}
```

- [ ] **Step 6: Run the runtime status tests**

Run:

```bash
mvn -Dtest=RoutingAccountRuntimeStatusPortTest,WebAccountRuntimeStatusAdapterTest,ProtocolConfigurationTest test
```

Expected: all tests pass and Spring exposes a single `AccountRuntimeStatusPort`.

- [ ] **Step 7: Commit Slice 3 foundation**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol \
  armada-api/src/test/java/com/armada/platform/protocol
git commit -m "feat(protocol): add backend aware runtime status port"
```

---

### Task 4: Add the Android native HTTP client and response decoder

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidResponseEnvelope.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidDecodedResponse.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidResponseDecoder.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupJoinErrorMapper.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeClient.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/HttpAndroidNativeClient.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidResponseDecoderTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/HttpAndroidNativeClientTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`

- [ ] **Step 1: Write failing Android envelope fixture tests**

Create `AndroidResponseDecoderTest.java` with these cases:

```java
@Test
void decodesSuccessAndExtractsRawIqCodeWithoutExposingJsonToCallers() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    AndroidResponseDecoder decoder = new AndroidResponseDecoder();

    AndroidDecodedResponse success = decoder.decode(mapper.readValue("""
            {"Code":0,"Data":"通过邀请码进群成功, 群聊ID: 120363001","Msg":""}
            """, AndroidResponseEnvelope.class));
    AndroidDecodedResponse failure = decoder.decode(mapper.readValue("""
            {"Code":1003,"Data":null,"Msg":"通过邀请码进群失败, not-authorized, Code: 403"}
            """, AndroidResponseEnvelope.class));

    assertThat(success.code()).isZero();
    assertThat(success.data().asText()).contains("120363001");
    assertThat(failure.code()).isEqualTo(1003);
    assertThat(failure.rawProtocolCode()).isEqualTo("403");
}

@Test
void mapsGinValidationShapeAndRejectsMissingCode() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    AndroidResponseDecoder decoder = new AndroidResponseDecoder();

    AndroidDecodedResponse validation = decoder.decode(mapper.readValue(
            "{\"error\":\"Key: 'ScanCodeDto.Code' Error\"}",
            AndroidResponseEnvelope.class));

    assertThat(validation.validationError()).contains("ScanCodeDto.Code");
    assertThatThrownBy(() -> decoder.decode(mapper.readValue(
            "{\"Data\":null,\"Msg\":\"unknown\"}",
            AndroidResponseEnvelope.class)))
            .isInstanceOfSatisfying(ProtocolException.class,
                    ex -> assertThat(ex.errorCode())
                            .isEqualTo(ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED));
}
```

- [ ] **Step 2: Run decoder tests and verify RED**

Run:

```bash
mvn -Dtest=AndroidResponseDecoderTest test
```

Expected: compilation fails because Android response types do not exist.

- [ ] **Step 3: Implement the Android response records and decoder**

Create `AndroidResponseEnvelope.java`:

```java
package com.armada.platform.protocol.backend.android;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record AndroidResponseEnvelope(
        @JsonProperty("Code") Integer code,
        @JsonProperty("Data") JsonNode data,
        @JsonProperty("Msg") JsonNode message,
        @JsonProperty("error") String validationError
) {
}
```

Create `AndroidDecodedResponse.java`:

```java
package com.armada.platform.protocol.backend.android;

import com.fasterxml.jackson.databind.JsonNode;

public record AndroidDecodedResponse(
        int code,
        JsonNode data,
        String message,
        String validationError,
        String rawProtocolCode
) {
    public boolean success() {
        return code == 0 && (validationError == null || validationError.isBlank());
    }
}
```

Create `AndroidResponseDecoder.java`:

```java
package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AndroidResponseDecoder {

    private static final Pattern RAW_CODE = Pattern.compile("(?i)\\bCode:\\s*([^,\\s]+)");

    public AndroidDecodedResponse decode(AndroidResponseEnvelope envelope) {
        if (envelope == null) {
            throw unrecognized("Android 响应为空");
        }
        String validationError = text(envelope.validationError());
        if (envelope.code() == null && validationError == null) {
            throw unrecognized("Android 响应缺少 Code");
        }
        String message = firstText(envelope.message(), envelope.data());
        return new AndroidDecodedResponse(
                envelope.code() == null ? 1002 : envelope.code(),
                envelope.data(),
                message,
                validationError,
                rawCode(message));
    }

    private static String firstText(JsonNode primary, JsonNode fallback) {
        String value = nodeText(primary);
        return value != null ? value : nodeText(fallback);
    }

    private static String nodeText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return text(node.isTextual() ? node.asText() : node.toString());
    }

    private static String rawCode(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = RAW_CODE.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ProtocolException unrecognized(String message) {
        return new ProtocolException(ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED, message);
    }
}
```

- [ ] **Step 4: Add the Android operation error mapper**

Create `AndroidGroupJoinErrorMapper.java`:

```java
package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;

import java.util.Locale;

public final class AndroidGroupJoinErrorMapper {

    public boolean isOffline(AndroidDecodedResponse response) {
        String message = lower(response.message());
        return message.contains("不存在或已下线")
                || message.contains("不在线")
                || message.contains("离线");
    }

    public ProtocolException toException(
            AndroidDecodedResponse response,
            ProtocolAccountRef account,
            String operation,
            String operationId) {
        ProtocolErrorCode code;
        String message = lower(response.message());
        if (response.validationError() != null) {
            code = ProtocolErrorCode.BAD_REQUEST;
        } else if (isOffline(response)) {
            code = ProtocolErrorCode.ACCOUNT_NOT_ONLINE;
        } else if (message.contains("邀请码为空")) {
            code = ProtocolErrorCode.INVALID_GROUP_LINK;
        } else if ("429".equals(response.rawProtocolCode()) || message.contains("rate-overlimit")) {
            code = ProtocolErrorCode.ACCOUNT_BUSY;
        } else if (message.contains("time out") || message.contains("timeout")) {
            code = ProtocolErrorCode.TIMEOUT;
        } else if ("401".equals(response.rawProtocolCode()) || "403".equals(response.rawProtocolCode())) {
            code = ProtocolErrorCode.GROUP_JOIN_REJECTED;
        } else {
            code = ProtocolErrorCode.UNKNOWN;
        }
        ProtocolException.Metadata metadata = ProtocolException.Metadata.of(
                200,
                response.rawProtocolCode(),
                null,
                null);
        return new ProtocolException(code, metadata, safeMessage(code, response.message()), null)
                .withContext(ProtocolBackend.ANDROID, operation, operationId);
    }

    private static String safeMessage(ProtocolErrorCode code, String message) {
        int length = message == null ? 0 : message.length();
        return "Android 协议调用失败 code=" + code + " messageLength=" + length;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 5: Write failing native client request-shape tests**

Create `HttpAndroidNativeClientTest.java`:

```java
package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpAndroidNativeClientTest {

    @Test
    void sendsExistingAndroidNativeRequestShapes() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://android.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AndroidNativeClient client = new HttpAndroidNativeClient(
                new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://android.internal/ws/v1/auth/status/919000000001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"Code\":0,\"Data\":\"online\",\"Msg\":\"\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://android.internal/ws/v1/groups/invite/919000000001"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"Code\":\"ABC123\"}"))
                .andRespond(withSuccess(
                        "{\"Code\":0,\"Data\":\"通过邀请码进群成功, 群聊ID: 120363001\",\"Msg\":\"\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://android.internal/ws/v1/groups/members/919000000001"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"group_id\":\"120363001@g.us\"}"))
                .andRespond(withSuccess(
                        "{\"Code\":0,\"Data\":{\"Participants\":[]},\"Msg\":\"ok\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.status("919000000001").code()).isZero();
        assertThat(client.join("919000000001", "ABC123").code()).isZero();
        assertThat(client.members("919000000001", "120363001@g.us").code()).isZero();
        server.verify();
    }
}
```

- [ ] **Step 6: Implement the native client interface and HTTP implementation**

Create `AndroidNativeClient.java`:

```java
package com.armada.platform.protocol.backend.android;

public interface AndroidNativeClient {
    AndroidResponseEnvelope status(String wsPhone);
    AndroidResponseEnvelope join(String wsPhone, String inviteCode);
    AndroidResponseEnvelope members(String wsPhone, String groupJid);
}
```

Create `HttpAndroidNativeClient.java`:

```java
package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class HttpAndroidNativeClient implements AndroidNativeClient {

    private final ProtocolHttpExecutor httpExecutor;

    public HttpAndroidNativeClient(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public AndroidResponseEnvelope status(String wsPhone) {
        return httpExecutor.getTyped(
                "/ws/v1/auth/status/" + requireDigits(wsPhone),
                AndroidResponseEnvelope.class);
    }

    @Override
    public AndroidResponseEnvelope join(String wsPhone, String inviteCode) {
        return httpExecutor.postTyped(
                "/ws/v1/groups/invite/" + requireDigits(wsPhone),
                new JoinRequest(requireText(inviteCode, "inviteCode")),
                AndroidResponseEnvelope.class);
    }

    @Override
    public AndroidResponseEnvelope members(String wsPhone, String groupJid) {
        return httpExecutor.postTyped(
                "/ws/v1/groups/members/" + requireDigits(wsPhone),
                new MembersRequest(requireText(groupJid, "groupJid")),
                AndroidResponseEnvelope.class);
    }

    private static String requireDigits(String value) {
        String normalized = requireText(value, "wsPhone");
        if (!normalized.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("wsPhone 必须为纯数字");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private record JoinRequest(@JsonProperty("Code") String code) {
    }

    private record MembersRequest(@JsonProperty("group_id") String groupId) {
    }
}
```

- [ ] **Step 7: Register the Android native client**

Add to `ProtocolConfiguration`:

```java
@Bean
public AndroidNativeClient androidNativeClient(ProtocolHttpExecutorRegistry registry) {
    return new HttpAndroidNativeClient(registry.required(ProtocolBackend.ANDROID));
}

@Bean
public AndroidResponseDecoder androidResponseDecoder() {
    return new AndroidResponseDecoder();
}

@Bean
public AndroidGroupJoinErrorMapper androidGroupJoinErrorMapper() {
    return new AndroidGroupJoinErrorMapper();
}
```

- [ ] **Step 8: Run Android foundation tests**

Run:

```bash
mvn -Dtest=AndroidResponseDecoderTest,HttpAndroidNativeClientTest,ProtocolConfigurationTest test
```

Expected: all tests pass.

- [ ] **Step 9: Commit Android HTTP foundation**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/backend/android \
  armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android
git commit -m "feat(protocol): add Android native HTTP decoder"
```

---

### Task 5: Add the Android runtime status backend

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidAccountRuntimeStatusAdapter.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidAccountRuntimeStatusAdapterTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`

- [ ] **Step 1: Write failing status semantics tests**

Create `AndroidAccountRuntimeStatusAdapterTest.java` with a mocked `AndroidNativeClient`:

```java
@ExtendWith(MockitoExtension.class)
class AndroidAccountRuntimeStatusAdapterTest {

    @Mock AndroidNativeClient client;

    @Test
    void mapsCodeZeroToOnlineAndExplicitOfflineMessageToOffline() {
        AndroidAccountRuntimeStatusAdapter adapter = adapter();
        ProtocolAccountRef account = account();
        when(client.status("919000000001"))
                .thenReturn(envelope(0, "账号在线"))
                .thenReturn(envelope(1003, "账号919000000001不存在或已下线"));

        assertThat(adapter.status(account).online()).isTrue();
        assertThat(adapter.status(account).state()).isEqualTo("OFFLINE");
    }

    @Test
    void doesNotTurnUnknownApplicationFailureIntoOffline() {
        AndroidAccountRuntimeStatusAdapter adapter = adapter();
        when(client.status("919000000001"))
                .thenReturn(envelope(1003, "unexpected native failure"));

        assertThatThrownBy(() -> adapter.status(account()))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNKNOWN);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                });
    }

    private AndroidAccountRuntimeStatusAdapter adapter() {
        return new AndroidAccountRuntimeStatusAdapter(
                client, new AndroidResponseDecoder(), new AndroidGroupJoinErrorMapper());
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(
                1L, ProtocolBackend.ANDROID, "acc_919000000001", "919000000001");
    }

    private static AndroidResponseEnvelope envelope(int code, String message) {
        return new AndroidResponseEnvelope(
                code,
                NullNode.getInstance(),
                TextNode.valueOf(message),
                null);
    }
}
```

Use this import set in addition to the package declaration:

```java
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
```

- [ ] **Step 2: Run the status test and verify RED**

Run:

```bash
mvn -Dtest=AndroidAccountRuntimeStatusAdapterTest test
```

Expected: compilation fails because `AndroidAccountRuntimeStatusAdapter` does not exist.

- [ ] **Step 3: Implement Android runtime status mapping**

Create `AndroidAccountRuntimeStatusAdapter.java`:

```java
package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;
import com.armada.platform.protocol.routing.AccountRuntimeStatusBackend;

public final class AndroidAccountRuntimeStatusAdapter implements AccountRuntimeStatusBackend {

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupJoinErrorMapper errorMapper;

    public AndroidAccountRuntimeStatusAdapter(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupJoinErrorMapper errorMapper) {
        this.client = client;
        this.decoder = decoder;
        this.errorMapper = errorMapper;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.ANDROID;
    }

    @Override
    public ProtocolAccountRuntimeStatus status(ProtocolAccountRef account) {
        String operationId = "account:" + account.armadaAccountId();
        try {
            AndroidDecodedResponse response = decoder.decode(client.status(account.wsPhone()));
            if (response.success()) {
                return new ProtocolAccountRuntimeStatus("ONLINE");
            }
            if (errorMapper.isOffline(response)) {
                return new ProtocolAccountRuntimeStatus("OFFLINE");
            }
            throw errorMapper.toException(response, account, "account.status", operationId);
        } catch (ProtocolException ex) {
            if (ex.backend().isPresent()) {
                throw ex;
            }
            throw ex.withContext(ProtocolBackend.ANDROID, "account.status", operationId);
        }
    }
}
```

- [ ] **Step 4: Register the Android status backend**

Add a bean returning `AccountRuntimeStatusBackend`:

```java
@Bean
public AccountRuntimeStatusBackend androidAccountRuntimeStatusBackend(
        AndroidNativeClient client,
        AndroidResponseDecoder decoder,
        AndroidGroupJoinErrorMapper errorMapper) {
    return new AndroidAccountRuntimeStatusAdapter(client, decoder, errorMapper);
}
```

- [ ] **Step 5: Run status and Spring wiring tests**

Run:

```bash
mvn -Dtest=AndroidAccountRuntimeStatusAdapterTest,RoutingAccountRuntimeStatusPortTest,WebAccountRuntimeStatusAdapterTest,ProtocolConfigurationTest test
```

Expected: all tests pass; the runtime routing port has Web and Android backends.

- [ ] **Step 6: Commit Android runtime status**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidAccountRuntimeStatusAdapter.java \
  armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidAccountRuntimeStatusAdapterTest.java
git commit -m "feat(protocol): adapt Android runtime status"
```

---

### Task 6: Parse Android invitation input and native join success

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupJoinResponseMapper.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupJoinResponseMapperTest.java`

- [ ] **Step 1: Write failing invite-code and group-JID parsing tests**

Create `AndroidGroupJoinResponseMapperTest.java`:

```java
class AndroidGroupJoinResponseMapperTest {

    private final AndroidGroupJoinResponseMapper mapper = new AndroidGroupJoinResponseMapper();

    @Test
    void acceptsPureCodeAndStrictWhatsappInviteLink() {
        assertThat(mapper.inviteCode("ABC123")).isEqualTo("ABC123");
        assertThat(mapper.inviteCode("https://chat.whatsapp.com/XYZ789"))
                .isEqualTo("XYZ789");
    }

    @Test
    void rejectsWrongHostBlankCodeAndExtraPath() {
        assertThatThrownBy(() -> mapper.inviteCode("https://example.com/ABC123"))
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.INVALID_GROUP_LINK));
        assertThatThrownBy(() -> mapper.inviteCode("https://chat.whatsapp.com/"))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> mapper.inviteCode("https://chat.whatsapp.com/A/B"))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void extractsAndNormalizesGroupJidFromAndroidSuccessText() {
        JsonNode data = new TextNode("通过邀请码进群成功, 群聊ID: 120363001");

        assertThat(mapper.groupJid(data)).isEqualTo("120363001@g.us");
        assertThat(mapper.groupJid(new TextNode(
                "通过邀请码进群成功, 群聊ID: 120363002@g.us")))
                .isEqualTo("120363002@g.us");
    }

    @Test
    void rejectsSuccessPayloadWithoutGroupId() {
        assertThatThrownBy(() -> mapper.groupJid(new TextNode("进群成功")))
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.errorCode())
                                .isEqualTo(ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED));
    }
}
```

- [ ] **Step 2: Run the mapper test and verify RED**

Run:

```bash
mvn -Dtest=AndroidGroupJoinResponseMapperTest test
```

Expected: compilation fails because the mapper does not exist.

- [ ] **Step 3: Implement strict invite parsing and safe success parsing**

Create `AndroidGroupJoinResponseMapper.java`:

```java
package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AndroidGroupJoinResponseMapper {

    private static final String INVITE_HOST = "chat.whatsapp.com";
    private static final Pattern GROUP_ID = Pattern.compile("群聊ID:\\s*([0-9-]+(?:@g\\.us)?)");

    public String inviteCode(String inviteLinkOrCode) {
        String value = requireText(inviteLinkOrCode);
        if (!value.contains("://")) {
            if (value.contains("/")) {
                throw invalidLink();
            }
            return value;
        }
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !INVITE_HOST.equalsIgnoreCase(uri.getHost())) {
                throw invalidLink();
            }
            String path = uri.getPath();
            if (path == null || path.length() <= 1 || path.substring(1).contains("/")) {
                throw invalidLink();
            }
            return requireText(path.substring(1));
        } catch (IllegalArgumentException ex) {
            throw invalidLink();
        }
    }

    public String groupJid(JsonNode data) {
        String text = data == null || data.isNull() ? "" : data.asText("");
        Matcher matcher = GROUP_ID.matcher(text);
        if (!matcher.find()) {
            throw new ProtocolException(
                    ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED,
                    "Android 进群成功响应缺少群 ID");
        }
        String groupId = matcher.group(1);
        return groupId.endsWith("@g.us") ? groupId : groupId + "@g.us";
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw invalidLink();
        }
        return value.trim();
    }

    private static ProtocolException invalidLink() {
        return new ProtocolException(ProtocolErrorCode.INVALID_GROUP_LINK, "WhatsApp 群邀请链接非法");
    }
}
```

- [ ] **Step 4: Run parsing tests**

Run:

```bash
mvn -Dtest=AndroidGroupJoinResponseMapperTest test
```

Expected: all tests pass.

- [ ] **Step 5: Commit Android join parsing**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupJoinResponseMapper.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupJoinResponseMapperTest.java
git commit -m "feat(protocol): parse Android group join responses"
```

---

### Task 7: Verify Android membership and expose the Android join backend

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupMembershipVerifier.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupJoinAdapter.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupMembershipVerifierTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupJoinAdapterTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`

- [ ] **Step 1: Write failing membership verification tests**

Create `AndroidGroupMembershipVerifierTest.java` with mocked `AndroidNativeClient`:

```java
@ExtendWith(MockitoExtension.class)
class AndroidGroupMembershipVerifierTest {

    @Mock AndroidNativeClient client;

    @Test
    void confirmsJoinedWhenCurrentPhoneExistsInParticipants() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {
                          "Code": 0,
                          "Data": {
                            "Participants": [
                              {"phone":"919000000001@s.whatsapp.net","type":"participant"},
                              {"phone":"918888888888","type":"admin"}
                            ]
                          },
                          "Msg": "ok"
                        }
                        """));

        GroupJoinOutcome result = verifier().verify(account(), "120363001@g.us", "join-task-result:1");

        assertThat(result).isEqualTo(GroupJoinOutcome.JOINED);
    }

    @Test
    void returnsPendingApprovalWhenMemberQuerySucceedsWithoutCurrentPhone() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{"Participants":[{"phone":"918888888888"}]},"Msg":"ok"}
                        """));

        assertThat(verifier().verify(account(), "120363001@g.us", "join-task-result:1"))
                .isEqualTo(GroupJoinOutcome.PENDING_APPROVAL);
    }

    @Test
    void mapsUnknownMemberQueryFailureToUnconfirmedInsteadOfSuccess() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("{\"Code\":1003,\"Data\":null,\"Msg\":\"unknown\"}"));

        assertThatThrownBy(() -> verifier().verify(account(), "120363001@g.us", "join-task-result:1"))
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.errorCode())
                                .isEqualTo(ProtocolErrorCode.JOIN_RESULT_UNCONFIRMED));
    }

    private AndroidGroupMembershipVerifier verifier() {
        return new AndroidGroupMembershipVerifier(client, new AndroidResponseDecoder());
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(
                1L, ProtocolBackend.ANDROID, "acc_919000000001", "919000000001");
    }

    private static AndroidResponseEnvelope envelope(String json) throws Exception {
        return new ObjectMapper().readValue(json, AndroidResponseEnvelope.class);
    }
}
```

Use this import set in addition to the package declaration:

```java
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
```

- [ ] **Step 2: Run verifier tests and verify RED**

Run:

```bash
mvn -Dtest=AndroidGroupMembershipVerifierTest test
```

Expected: compilation fails because `AndroidGroupMembershipVerifier` does not exist.

- [ ] **Step 3: Implement membership confirmation**

Create `AndroidGroupMembershipVerifier.java`:

```java
package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.fasterxml.jackson.databind.JsonNode;

public final class AndroidGroupMembershipVerifier {

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;

    public AndroidGroupMembershipVerifier(AndroidNativeClient client, AndroidResponseDecoder decoder) {
        this.client = client;
        this.decoder = decoder;
    }

    public GroupJoinOutcome verify(
            ProtocolAccountRef account,
            String groupJid,
            String operationId) {
        try {
            AndroidDecodedResponse response = decoder.decode(client.members(account.wsPhone(), groupJid));
            if (!response.success()) {
                throw unconfirmed(account, operationId, response.rawProtocolCode(), null);
            }
            JsonNode participants = response.data() == null
                    ? null
                    : response.data().path("Participants");
            if (participants == null || !participants.isArray()) {
                throw unconfirmed(account, operationId, null, null);
            }
            for (JsonNode participant : participants) {
                if (account.wsPhone().equals(normalizePhone(participant.path("phone").asText("")))) {
                    return GroupJoinOutcome.JOINED;
                }
            }
            return GroupJoinOutcome.PENDING_APPROVAL;
        } catch (ProtocolException ex) {
            if (ex.errorCode() == ProtocolErrorCode.JOIN_RESULT_UNCONFIRMED) {
                throw ex;
            }
            throw unconfirmed(account, operationId, ex.protocolCode().orElse(null), ex);
        }
    }

    private static String normalizePhone(String value) {
        String normalized = value == null ? "" : value.trim();
        int at = normalized.indexOf('@');
        if (at >= 0) {
            normalized = normalized.substring(0, at);
        }
        int device = normalized.indexOf(':');
        return device >= 0 ? normalized.substring(0, device) : normalized;
    }

    private static ProtocolException unconfirmed(
            ProtocolAccountRef account,
            String operationId,
            String rawCode,
            Throwable cause) {
        return new ProtocolException(
                ProtocolErrorCode.JOIN_RESULT_UNCONFIRMED,
                ProtocolException.Metadata.of(0, rawCode, null, null),
                "Android 进群结果未确认",
                cause).withContext(ProtocolBackend.ANDROID, "group.members.verify", operationId);
    }
}
```

- [ ] **Step 4: Write failing end-to-end Android adapter unit tests**

Create `AndroidNativeGroupJoinAdapterTest.java`:

```java
@ExtendWith(MockitoExtension.class)
class AndroidNativeGroupJoinAdapterTest {

    @Mock AndroidNativeClient client;
    @Mock AndroidGroupMembershipVerifier verifier;

    @Test
    void sendsExtractedCodeThenReturnsConfirmedJoinedOutcome() throws Exception {
        ProtocolAccountRef account = account();
        when(client.join("919000000001", "ABC123"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":"通过邀请码进群成功, 群聊ID: 120363001","Msg":""}
                        """));
        when(verifier.verify(account, "120363001@g.us", "join-task-result:1"))
                .thenReturn(GroupJoinOutcome.JOINED);

        GroupJoinResult result = adapter().join(new GroupJoinCommand(
                account,
                "https://chat.whatsapp.com/ABC123",
                "join-task-result:1"));

        assertThat(result).isEqualTo(new GroupJoinResult(
                "120363001@g.us", GroupJoinOutcome.JOINED));
        verify(client).join("919000000001", "ABC123");
    }

    @Test
    void doesNotRunMembershipVerificationWhenNativeJoinFails() throws Exception {
        when(client.join("919000000001", "ABC123"))
                .thenReturn(envelope("""
                        {"Code":1003,"Data":null,"Msg":"通过邀请码进群失败, Code: 403"}
                        """));

        assertThatThrownBy(() -> adapter().join(new GroupJoinCommand(
                account(), "ABC123", "join-task-result:1")))
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.GROUP_JOIN_REJECTED));
        verifyNoInteractions(verifier);
    }

    private AndroidNativeGroupJoinAdapter adapter() {
        return new AndroidNativeGroupJoinAdapter(
                client,
                new AndroidResponseDecoder(),
                new AndroidGroupJoinErrorMapper(),
                new AndroidGroupJoinResponseMapper(),
                verifier);
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(
                1L, ProtocolBackend.ANDROID, "acc_919000000001", "919000000001");
    }

    private static AndroidResponseEnvelope envelope(String json) throws Exception {
        return new ObjectMapper().readValue(json, AndroidResponseEnvelope.class);
    }
}
```

Use this import set in addition to the package declaration:

```java
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
```

- [ ] **Step 5: Implement the Android group join backend**

Create `AndroidNativeGroupJoinAdapter.java`:

```java
package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.routing.GroupJoinBackend;

public final class AndroidNativeGroupJoinAdapter implements GroupJoinBackend {

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupJoinErrorMapper errorMapper;
    private final AndroidGroupJoinResponseMapper responseMapper;
    private final AndroidGroupMembershipVerifier verifier;

    public AndroidNativeGroupJoinAdapter(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupJoinErrorMapper errorMapper,
            AndroidGroupJoinResponseMapper responseMapper,
            AndroidGroupMembershipVerifier verifier) {
        this.client = client;
        this.decoder = decoder;
        this.errorMapper = errorMapper;
        this.responseMapper = responseMapper;
        this.verifier = verifier;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.ANDROID;
    }

    @Override
    public GroupJoinResult join(GroupJoinCommand command) {
        try {
            String inviteCode = responseMapper.inviteCode(command.inviteLinkOrCode());
            AndroidDecodedResponse response = decoder.decode(
                    client.join(command.account().wsPhone(), inviteCode));
            if (!response.success()) {
                throw errorMapper.toException(
                        response,
                        command.account(),
                        "group.join",
                        command.operationId());
            }
            String groupJid = responseMapper.groupJid(response.data());
            GroupJoinOutcome outcome = verifier.verify(
                    command.account(), groupJid, command.operationId());
            return new GroupJoinResult(groupJid, outcome);
        } catch (ProtocolException ex) {
            if (ex.backend().isPresent()) {
                throw ex;
            }
            throw ex.withContext(ProtocolBackend.ANDROID, "group.join", command.operationId());
        }
    }
}
```

- [ ] **Step 6: Register mapper, verifier, and Android group backend**

Add beans to `ProtocolConfiguration`:

```java
@Bean
public AndroidGroupJoinResponseMapper androidGroupJoinResponseMapper() {
    return new AndroidGroupJoinResponseMapper();
}

@Bean
public AndroidGroupMembershipVerifier androidGroupMembershipVerifier(
        AndroidNativeClient client,
        AndroidResponseDecoder decoder) {
    return new AndroidGroupMembershipVerifier(client, decoder);
}

@Bean
public GroupJoinBackend androidGroupJoinBackend(
        AndroidNativeClient client,
        AndroidResponseDecoder decoder,
        AndroidGroupJoinErrorMapper errorMapper,
        AndroidGroupJoinResponseMapper responseMapper,
        AndroidGroupMembershipVerifier verifier) {
    return new AndroidNativeGroupJoinAdapter(
            client, decoder, errorMapper, responseMapper, verifier);
}
```

- [ ] **Step 7: Run Android group join and routing tests**

Run:

```bash
mvn -Dtest=AndroidGroupMembershipVerifierTest,AndroidNativeGroupJoinAdapterTest,AndroidGroupJoinResponseMapperTest,RoutingGroupJoinPortTest,WebNativeGroupJoinAdapterTest,ProtocolConfigurationTest test
```

Expected: all tests pass; the group join router has both Web and Android backends.

- [ ] **Step 8: Commit Android group join**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/backend/android \
  armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android
git commit -m "feat(protocol): adapt Android group join"
```

---

### Task 8: Integrate the backend-aware ports into JoinTaskWorker

**Files:**
- Modify: `armada-api/src/main/java/com/armada/task/worker/JoinTaskWorker.java`
- Modify: `armada-api/src/test/java/com/armada/task/worker/JoinTaskWorkerTest.java`
- Modify: `armada-api/src/main/java/com/armada/task/model/enums/JoinTaskFailureReason.java`
- Modify: `armada-api/src/test/java/com/armada/task/model/enums/JoinTaskFailureReasonTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java`

- [ ] **Step 1: Rewrite the worker tests against canonical ports and verify RED**

Replace the `AccountLifecyclePort` mock with `AccountRuntimeStatusPort`. Update account helpers to populate all routing fields:

```java
private static Account account(
        Long id,
        String protocolId,
        String protocolAccountId,
        String wsPhone) {
    Account account = new Account();
    account.setId(id);
    account.setProtocolId(protocolId);
    account.setProtocolAccountId(protocolAccountId);
    account.setWsPhone(wsPhone);
    return account;
}
```

Update the Web success test:

```java
Account account = account(100L, "WEB", "acc_861001", "861001");
ProtocolAccountRef ref = new ProtocolAccountRef(
        100L, ProtocolBackend.WEB, "acc_861001", "861001");
GroupJoinCommand command = new GroupJoinCommand(
        ref,
        row.getLink(),
        "join-task-result:70");
when(accountRuntimeStatusPort.status(ref))
        .thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
when(groupJoinPort.join(command))
        .thenReturn(new GroupJoinResult("120363joined@g.us", GroupJoinOutcome.JOINED));
```

Add these three tests to `JoinTaskWorkerTest`:

```java
@Test
void runTask_routesWebAndAndroidRowsThroughCanonicalCommands() {
    JoinTask task = runningTask(20L);
    JoinTaskResult webRow = pendingRow(201L, 1001L, "https://chat.whatsapp.com/WEB001");
    JoinTaskResult androidRow = pendingRow(202L, 1002L, "https://chat.whatsapp.com/ANDROID002");
    Account webAccount = account(1001L, "WEB", "acc_861001", "861001");
    Account androidAccount = account(1002L, "ANDROID", "acc_919002", "919002");
    ProtocolAccountRef webRef = new ProtocolAccountRef(
            1001L, ProtocolBackend.WEB, "acc_861001", "861001");
    ProtocolAccountRef androidRef = new ProtocolAccountRef(
            1002L, ProtocolBackend.ANDROID, "acc_919002", "919002");
    GroupJoinCommand webCommand = new GroupJoinCommand(
            webRef, webRow.getLink(), "join-task-result:201");
    GroupJoinCommand androidCommand = new GroupJoinCommand(
            androidRef, androidRow.getLink(), "join-task-result:202");

    when(joinTaskMapper.selectByTenantAndId(20L)).thenReturn(task);
    when(resultMapper.selectPendingResultsByTask(20L))
            .thenReturn(List.of(webRow, androidRow), List.of());
    when(accountMapper.selectActiveById(1001L)).thenReturn(webAccount);
    when(accountMapper.selectActiveById(1002L)).thenReturn(androidAccount);
    when(accountRuntimeStatusPort.status(webRef))
            .thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
    when(accountRuntimeStatusPort.status(androidRef))
            .thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
    when(groupJoinPort.join(webCommand))
            .thenReturn(new GroupJoinResult("120363web@g.us", GroupJoinOutcome.JOINED));
    when(groupJoinPort.join(androidCommand))
            .thenReturn(new GroupJoinResult("120363android@g.us", GroupJoinOutcome.JOINED));

    worker.runTask(1L, 20L);

    verify(groupJoinPort).join(webCommand);
    verify(groupJoinPort).join(androidCommand);
    verify(resultMapper).updateResultSuccess(eq(201L), eq("120363web@g.us"), anyLong());
    verify(resultMapper).updateResultSuccess(eq(202L), eq("120363android@g.us"), anyLong());
}

@Test
void runTask_doesNotMarkAccountOfflineWhenRuntimeStatusCallHasNetworkFailure() {
    JoinTask task = runningTask(21L);
    JoinTaskResult row = pendingRow(211L, 1101L, "https://chat.whatsapp.com/NETWORK");
    Account account = account(1101L, "ANDROID", "acc_919101", "919101");
    ProtocolAccountRef ref = new ProtocolAccountRef(
            1101L, ProtocolBackend.ANDROID, "acc_919101", "919101");
    when(joinTaskMapper.selectByTenantAndId(21L)).thenReturn(task);
    when(resultMapper.selectPendingResultsByTask(21L)).thenReturn(List.of(row), List.of());
    when(accountMapper.selectActiveById(1101L)).thenReturn(account);
    when(accountRuntimeStatusPort.status(ref))
            .thenThrow(new ProtocolException(ProtocolErrorCode.NETWORK, "network"));

    worker.runTask(1L, 21L);

    verifyNoInteractions(accountStateMapper);
    verifyNoInteractions(groupJoinPort);
    verify(resultMapper).updateResultFailed(eq(211L), eq("NETWORK"), anyLong());
}

@Test
void runTask_neverMarksUnconfirmedAndroidJoinAsSuccess() {
    JoinTask task = runningTask(22L);
    JoinTaskResult row = pendingRow(221L, 1201L, "https://chat.whatsapp.com/UNCONFIRMED");
    Account account = account(1201L, "ANDROID", "acc_919201", "919201");
    ProtocolAccountRef ref = new ProtocolAccountRef(
            1201L, ProtocolBackend.ANDROID, "acc_919201", "919201");
    GroupJoinCommand command = new GroupJoinCommand(
            ref, row.getLink(), "join-task-result:221");
    when(joinTaskMapper.selectByTenantAndId(22L)).thenReturn(task);
    when(resultMapper.selectPendingResultsByTask(22L)).thenReturn(List.of(row), List.of());
    when(accountMapper.selectActiveById(1201L)).thenReturn(account);
    when(accountRuntimeStatusPort.status(ref))
            .thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
    when(groupJoinPort.join(command))
            .thenThrow(new ProtocolException(
                    ProtocolErrorCode.JOIN_RESULT_UNCONFIRMED,
                    "unconfirmed"));

    worker.runTask(1L, 22L);

    verify(resultMapper, never()).updateResultSuccess(eq(221L), any(), anyLong());
    verify(resultMapper).updateResultFailed(
            eq(221L), eq("JOIN_RESULT_UNCONFIRMED"), anyLong());
}
```

Add imports:

```java
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;
import com.armada.platform.protocol.port.AccountRuntimeStatusPort;

import static org.mockito.ArgumentMatchers.any;
```

- [ ] **Step 2: Run the worker tests and verify RED**

Run:

```bash
mvn -Dtest=JoinTaskWorkerTest test
```

Expected: compilation fails because the Worker still depends on `AccountLifecyclePort` and calls the old join signature.

- [ ] **Step 3: Replace Worker dependencies with the canonical ports**

Change fields and constructors:

```java
private final GroupJoinPort groupJoinPort;
private final AccountRuntimeStatusPort accountRuntimeStatusPort;
```

Replace `AccountLifecyclePort` constructor arguments with `AccountRuntimeStatusPort` in both constructors.

Add the account conversion helper:

```java
private static ProtocolAccountRef protocolAccount(Account account) {
    return new ProtocolAccountRef(
            account.getId(),
            ProtocolBackend.fromProtocolId(account.getProtocolId()),
            account.getProtocolAccountId(),
            account.getWsPhone());
}
```

Strengthen `resolveAccount` so an account with blank `wsPhone` is also rejected as `ACCOUNT_NOT_FOUND`.

- [ ] **Step 4: Replace the status preflight with backend-aware runtime status**

Replace `isProtocolOnline(Account)` with:

```java
private boolean isProtocolOnline(Account account, ProtocolAccountRef ref) {
    try {
        ProtocolAccountRuntimeStatus status = accountRuntimeStatusPort.status(ref);
        if (status != null && status.online()) {
            return true;
        }
        markAccountOffline(account, STATE_SOURCE_JOIN_TASK_STATUS);
        log.warn("进群任务账号协议状态非 ONLINE accountId={} backend={} protocolAccountId={} protocolState={}",
                account.getId(), ref.backend(), ref.protocolAccountId(), status == null ? null : status.state());
        return false;
    } catch (ProtocolException ex) {
        if (ex.errorCode() != ProtocolErrorCode.ACCOUNT_NOT_FOUND
                && ex.errorCode() != ProtocolErrorCode.ACCOUNT_NOT_ONLINE) {
            throw ex;
        }
        markAccountOffline(account, STATE_SOURCE_JOIN_TASK_STATUS_NOT_FOUND);
        log.warn("进群任务账号协议状态不可用 accountId={} backend={} protocolAccountId={} code={}",
                account.getId(), ref.backend(), ref.protocolAccountId(), ex.errorCode());
        return false;
    }
}
```

This preserves explicit offline convergence while preventing `NETWORK`, `TIMEOUT`, and unknown Android errors from being written as local OFFLINE.

- [ ] **Step 5: Call the canonical join command and handle outcomes**

Replace the join section in `processRow` with:

```java
ProtocolAccountRef ref = protocolAccount(account);
if (!isProtocolOnline(account, ref)) {
    fail(row, REASON_ACCOUNT_NOT_ONLINE);
    return;
}
GroupJoinResult result = groupJoinPort.join(new GroupJoinCommand(
        ref,
        row.getLink(),
        "join-task-result:" + row.getId()));
if (result != null && result.joined()) {
    resultMapper.updateResultSuccess(
            row.getId(),
            nullToEmpty(result.groupJid()),
            System.currentTimeMillis());
    return;
}
fail(row, REASON_JOIN_PENDING_APPROVAL);
```

Change protocol exception reason selection to always persist the canonical code:

```java
private static String reason(RuntimeException ex) {
    if (ex instanceof ProtocolException protocolException) {
        return protocolException.errorCode().name();
    }
    return ex.getMessage();
}
```

Remove `PROTOCOL_CODE_ACCOUNT_NOT_FOUND`, `ProtocolAccountStatus`, `AccountLifecyclePort`, and `isProtocolAccountNotFound` from the Worker.

- [ ] **Step 6: Add failure labels for new canonical codes**

Add enum entries to `JoinTaskFailureReason`:

```java
PROTOCOL_INVALID_GROUP_LINK("INVALID_GROUP_LINK", "群邀请链接无效"),
GROUP_JOIN_REJECTED("GROUP_JOIN_REJECTED", "协议拒绝进群"),
JOIN_RESULT_UNCONFIRMED("JOIN_RESULT_UNCONFIRMED", "进群结果未确认"),
ANDROID_RESPONSE_UNRECOGNIZED("ANDROID_RESPONSE_UNRECOGNIZED", "Android 协议响应无法识别"),
UNSUPPORTED_BACKEND("UNSUPPORTED_BACKEND", "账号协议类型暂不支持"),
BAD_REQUEST_UNDERSCORE("BAD_REQUEST", "进群失败，请检查群链接或稍后重试"),
```

Keep the existing `BAD_REQUEST("bad-request", ...)` entry for stored historical values. Add this test to `JoinTaskFailureReasonTest`:

```java
@Test
void labelOfMapsNewCanonicalJoinFailureCodes() {
    assertThat(labelOf("INVALID_GROUP_LINK")).isEqualTo("群邀请链接无效");
    assertThat(labelOf("GROUP_JOIN_REJECTED")).isEqualTo("协议拒绝进群");
    assertThat(labelOf("JOIN_RESULT_UNCONFIRMED")).isEqualTo("进群结果未确认");
    assertThat(labelOf("ANDROID_RESPONSE_UNRECOGNIZED")).isEqualTo("Android 协议响应无法识别");
    assertThat(labelOf("UNSUPPORTED_BACKEND")).isEqualTo("账号协议类型暂不支持");
    assertThat(labelOf("BAD_REQUEST")).isEqualTo("进群失败，请检查群链接或稍后重试");
    assertThat(labelOf("bad-request")).isEqualTo("进群失败，请检查群链接或稍后重试");
}
```

- [ ] **Step 7: Run worker and failure-label tests**

Run:

```bash
mvn -Dtest=JoinTaskWorkerTest,JoinTaskFailureReasonTest test
```

Expected: all tests pass, including mixed Web/Android routing and no-offline-on-network behavior.

- [ ] **Step 8: Run the complete protocol and join-task focused suite**

Run:

```bash
mvn -Dtest=ProtocolPropertiesTest,ProtocolConfigurationTest,ProtocolHttpExecutorTest,ProtocolHttpExecutorRegistryTest,ProtocolExceptionTest,RoutingGroupJoinPortTest,RoutingAccountRuntimeStatusPortTest,WebNativeGroupJoinAdapterTest,WebAccountRuntimeStatusAdapterTest,AndroidResponseDecoderTest,HttpAndroidNativeClientTest,AndroidAccountRuntimeStatusAdapterTest,AndroidGroupJoinResponseMapperTest,AndroidGroupMembershipVerifierTest,AndroidNativeGroupJoinAdapterTest,JoinTaskWorkerTest,JoinTaskFailureReasonTest test
```

Expected: all selected tests pass with zero failures and zero errors.

- [ ] **Step 9: Verify no native Android details leaked into Worker**

Run:

```bash
rg -n '/ws/v1|AndroidResponse|Code:|群聊ID|groups/invite|groups/members' \
  src/main/java/com/armada/task/worker/JoinTaskWorker.java
```

Expected: no matches.

Run:

```bash
rg -n 'ProtocolBackend\.ANDROID|if \(.*ANDROID|switch \(.*backend' \
  src/main/java/com/armada/task/worker/JoinTaskWorker.java
```

Expected: no matches.

- [ ] **Step 10: Run the full Armada API test suite**

Run:

```bash
mvn test
```

Expected: build succeeds with zero test failures and zero test errors.

- [ ] **Step 11: Inspect the final diff and commit Slice 6**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only intended Armada source/test/config files appear, plus the pre-existing `.claude/worktrees` statuses which remain unstaged.

Commit:

```bash
git add armada-api/src/main/java/com/armada/task/worker/JoinTaskWorker.java \
  armada-api/src/main/java/com/armada/task/model/enums/JoinTaskFailureReason.java \
  armada-api/src/test/java/com/armada/task/worker/JoinTaskWorkerTest.java \
  armada-api/src/test/java/com/armada/task/model/enums/JoinTaskFailureReasonTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java
git commit -m "feat(task): route join tasks to Web and Android protocols"
```

---

## Final acceptance verification

- [ ] Run all tests once more from `armada/armada-api`:

```bash
mvn test
```

Expected: `BUILD SUCCESS`, zero failures, zero errors.

- [ ] Verify protocol repositories were not modified:

```bash
git -C ../../armada-protocol status --short
git -C ../../whatsapp-server-feature-android-zhuan status --short
```

Expected: no new changes attributable to this implementation. Existing unrelated changes, if any, remain untouched.

- [ ] Verify commit sequence from `armada/`:

```bash
git log --oneline --decorate -8
```

Expected commit subjects include, in order:

```text
feat(protocol): add backend scoped HTTP clients
refactor(protocol): route group join by backend
feat(protocol): add backend aware runtime status port
feat(protocol): add Android native HTTP decoder
feat(protocol): adapt Android runtime status
feat(protocol): parse Android group join responses
feat(protocol): adapt Android group join
feat(task): route join tasks to Web and Android protocols
```

- [ ] Confirm the acceptance requirements manually:

```text
1. Web and Android each have independent configured HTTP executors.
2. JoinTaskWorker contains no protocol-native URL, request field, response text, or Android branch.
3. Web join still sends accountId + inviteLink/inviteCode and preserves pending approval.
4. Android join calls existing Zhuan invite endpoint with Code.
5. Android join success is followed by existing Zhuan members endpoint verification.
6. Unknown membership verification never becomes SUCCESS.
7. Network/status uncertainty does not force local account OFFLINE.
8. Stored join failure reasons use Armada canonical codes.
9. Neither protocol repository is modified.
10. Full Maven tests pass.
```
