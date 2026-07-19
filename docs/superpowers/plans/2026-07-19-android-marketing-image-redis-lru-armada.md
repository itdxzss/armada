# Armada Android Marketing Image Redis Reference Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Armada write each unique Android marketing source image to shared Redis once per enqueue batch and publish only a tenant-scoped content reference in Kafka/outbox.

**Architecture:** Keep MySQL and `MessageSendCommand.MessageMedia` as the business source of truth. Add an Android-adapter-owned binary Redis store, derive `tenantId + SHA-256` asset identities, deduplicate media inside `AndroidMessageSendBackend.enqueue`, and encode `{assetRef}` instead of Base64 while leaving `WebMessageSendBackend` unchanged.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Spring Data Redis/Lettuce, Jackson, JUnit 5, Mockito, AssertJ, Maven.

---

**Design reference:** `docs/superpowers/specs/2026-07-19-android-marketing-image-redis-lru-design.md`

**Companion plan:** `docs/superpowers/plans/2026-07-19-android-marketing-image-redis-lru-zhuan.md`

**Execution boundary:** Work in an isolated Armada worktree at implementation time. Preserve the existing user changes in `MarketingTaskMapper.xml`, `MarketingTaskMapperSqlShapeTest.java`, and `.claude/worktrees`; they are unrelated to this feature.

## File map

Create:

- `armada-api/src/main/java/com/armada/platform/protocol/media/AndroidImageAsset.java` — validates source media, computes SHA-256, and exposes the Redis identity.
- `armada-api/src/main/java/com/armada/platform/protocol/media/AndroidImageAssetRef.java` — exact Kafka reference fields.
- `armada-api/src/main/java/com/armada/platform/protocol/media/AndroidImageAssetStore.java` — consumer-side interface used by the Android backend.
- `armada-api/src/main/java/com/armada/platform/protocol/media/RedisAndroidImageAssetStore.java` — binary Redis `EXPIRE`/`SET NX` implementation.
- `armada-api/src/main/java/com/armada/platform/protocol/config/AndroidImageRedisProperties.java` — standalone/cluster/TLS/namespace settings, following the existing protocol-property pattern.
- `armada-api/src/main/java/com/armada/platform/protocol/config/AndroidImageRedisConfiguration.java` — isolated Lettuce connection and `RedisTemplate<String, byte[]>` beans.
- `armada-api/src/test/java/com/armada/platform/protocol/media/AndroidImageAssetTest.java`.
- `armada-api/src/test/java/com/armada/platform/protocol/media/RedisAndroidImageAssetStoreTest.java`.
- `armada-api/src/test/java/com/armada/platform/protocol/config/AndroidImageRedisPropertiesTest.java`.

Modify:

- `armada-api/pom.xml` — add Spring Data Redis/Lettuce.
- `armada-api/src/main/resources/application.yml` — add Android image Redis settings.
- `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java` — batch-resolve media references and remove Android Base64 payloads.
- `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java` — inject the asset store into the Android backend.
- `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackendTest.java` — contract, dedupe, card-thumbnail, and failure tests.
- `armada-deploy/.env.example` — non-secret shared Redis variables for the test environment.
- `armada-deploy/docker-compose.rds.yml` — pass Redis variables to Armada.
- `armada-deploy/verify-config.mjs` — guard required variables.
- `.harness/changes/2026-07-19-android-marketing-image-redis-lru.md` — record completed Armada tasks and real verification output.

No Flyway migration, Mapper, HTTP API, controller, marketing table, or Web payload file belongs in this plan.

### Task 1: Add validated Android image Redis configuration

**Files:**

- Modify: `armada-api/pom.xml`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/config/AndroidImageRedisProperties.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/config/AndroidImageRedisConfiguration.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/config/AndroidImageRedisPropertiesTest.java`

- [ ] **Step 1: Write failing configuration-property tests**

Create `AndroidImageRedisPropertiesTest.java` with table-driven coverage for the accepted modes and rejected namespace/database shapes:

```java
package com.armada.platform.protocol.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AndroidImageRedisPropertiesTest {

    @Test
    void acceptsStandaloneAndClusterConfigurations() {
        AndroidImageRedisProperties standalone = properties("standalone", "cache:6379", 1, "android-zhuan:");
        AndroidImageRedisProperties cluster = properties(
                "cluster", "cache-a:6379,cache-b:6379", 0, "android-zhuan-perf:");

        assertThatCode(standalone::afterPropertiesSet).doesNotThrowAnyException();
        assertThatCode(cluster::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeOrIncompatibleConfigurations() {
        assertThatThrownBy(() -> properties("cluster", "cache:6379", 1, "android:").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database 0");
        assertThatThrownBy(() -> properties("standalone", " ", 0, "android:").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("address");
        assertThatThrownBy(() -> properties("standalone", "cache:6379", 0, "android").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("colon");
    }

    private static AndroidImageRedisProperties properties(
            String mode, String addresses, int database, String keyPrefix) {
        AndroidImageRedisProperties properties = new AndroidImageRedisProperties();
        properties.setMode(mode);
        properties.setAddresses(addresses);
        properties.setDatabase(database);
        properties.setKeyPrefix(keyPrefix);
        return properties;
    }
}
```

- [ ] **Step 2: Run the focused test and verify the red state**

Run from `armada/armada-api`:

```bash
mvn -Dtest=AndroidImageRedisPropertiesTest test
```

Expected: compilation fails because `AndroidImageRedisProperties` does not exist.

- [ ] **Step 3: Add Spring Data Redis and implement the properties class**

Add this dependency to `armada-api/pom.xml` next to `spring-kafka`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

Implement `AndroidImageRedisProperties.java`:

```java
package com.armada.platform.protocol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.InitializingBean;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@ConfigurationProperties("armada.protocol.android-image-cache.redis")
public final class AndroidImageRedisProperties implements InitializingBean {

    private String mode = "standalone";
    private String addresses = "localhost:6379";
    private String username = "";
    private String password = "";
    private int database;
    private boolean tls;
    private String keyPrefix = "android-zhuan:";

    @Override
    public void afterPropertiesSet() {
        String normalizedMode = normalizedMode();
        if (!normalizedMode.equals("standalone") && !normalizedMode.equals("cluster")) {
            throw new IllegalStateException("Android image Redis mode must be standalone or cluster");
        }
        if (addressList().isEmpty()) {
            throw new IllegalStateException("Android image Redis address is required");
        }
        if (normalizedMode.equals("standalone") && addressList().size() != 1) {
            throw new IllegalStateException("Standalone Android image Redis requires exactly one address");
        }
        if (normalizedMode.equals("cluster") && database != 0) {
            throw new IllegalStateException("Cluster Android image Redis requires database 0");
        }
        if (keyPrefix == null || keyPrefix.isBlank() || !keyPrefix.trim().endsWith(":")) {
            throw new IllegalStateException("Android image Redis key prefix must end with a colon");
        }
        for (String address : addressList()) {
            int separator = address.lastIndexOf(':');
            if (separator <= 0 || separator == address.length() - 1) {
                throw new IllegalStateException("Android image Redis address must use host:port");
            }
            Integer.parseInt(address.substring(separator + 1));
        }
    }

    public String normalizedMode() {
        return mode == null ? "standalone" : mode.trim().toLowerCase(Locale.ROOT);
    }

    public List<String> addressList() {
        if (addresses == null) {
            return List.of();
        }
        return Arrays.stream(addresses.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getAddresses() { return addresses; }
    public void setAddresses(String addresses) { this.addresses = addresses; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public int getDatabase() { return database; }
    public void setDatabase(int database) { this.database = database; }
    public boolean isTls() { return tls; }
    public void setTls(boolean tls) { this.tls = tls; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
}
```

Keep credentials as strings only inside the configuration object; never log them or include them in validation messages.

Add Chinese Javadoc following `ProtocolAndroidCommandProperties`: the class describes the dedicated Android image Redis connection; `afterPropertiesSet` describes startup validation; each getter/setter names its exact field (mode, address list, username, password, database, TLS, or global Key prefix). Password comments must describe configuration purpose without showing a value.

- [ ] **Step 4: Implement an isolated binary Redis connection**

Create `AndroidImageRedisConfiguration.java`. Use named beans so future Armada Redis features cannot accidentally reuse the image connection or serializers:

```java
package com.armada.platform.protocol.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AndroidImageRedisProperties.class)
public class AndroidImageRedisConfiguration {

    @Bean("androidImageRedisConnectionFactory")
    public LettuceConnectionFactory androidImageRedisConnectionFactory(
            AndroidImageRedisProperties properties) {
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client =
                LettuceClientConfiguration.builder();
        if (properties.isTls()) {
            client.useSsl();
        }
        if (properties.normalizedMode().equals("cluster")) {
            RedisClusterConfiguration cluster = new RedisClusterConfiguration(properties.addressList());
            applyAuthentication(cluster, properties);
            return new LettuceConnectionFactory(cluster, client.build());
        }
        RedisNode node = redisNode(properties.addressList().get(0));
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(node.getHost(), node.getPort());
        standalone.setDatabase(properties.getDatabase());
        applyAuthentication(standalone, properties);
        return new LettuceConnectionFactory(standalone, client.build());
    }

    @Bean("androidImageRedisTemplate")
    public RedisTemplate<String, byte[]> androidImageRedisTemplate(
            @Qualifier("androidImageRedisConnectionFactory")
            LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return template;
    }

    private static RedisNode redisNode(String address) {
        int separator = address.lastIndexOf(':');
        return new RedisNode(address.substring(0, separator),
                Integer.parseInt(address.substring(separator + 1)));
    }

    private static void applyAuthentication(
            RedisStandaloneConfiguration configuration,
            AndroidImageRedisProperties properties) {
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            configuration.setUsername(properties.getUsername().trim());
        }
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            configuration.setPassword(RedisPassword.of(properties.getPassword()));
        }
    }

    private static void applyAuthentication(
            RedisClusterConfiguration configuration,
            AndroidImageRedisProperties properties) {
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            configuration.setUsername(properties.getUsername().trim());
        }
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            configuration.setPassword(RedisPassword.of(properties.getPassword()));
        }
    }
}
```

Remove any unused import after compilation; do not introduce a generic application-wide `RedisTemplate` bean.

Add class and public bean-method Javadocs in Chinese. The configuration class comment must state that values are raw bytes and that its connection is isolated from future unrelated Armada Redis uses.

- [ ] **Step 5: Run the focused tests and compile the configuration**

Run:

```bash
mvn -Dtest=AndroidImageRedisPropertiesTest test
```

Expected: `BUILD SUCCESS`, all property tests pass, and Spring Data Redis classes compile.

- [ ] **Step 6: Commit the configuration unit**

```bash
git add armada-api/pom.xml \
  armada-api/src/main/java/com/armada/platform/protocol/config/AndroidImageRedisProperties.java \
  armada-api/src/main/java/com/armada/platform/protocol/config/AndroidImageRedisConfiguration.java \
  armada-api/src/test/java/com/armada/platform/protocol/config/AndroidImageRedisPropertiesTest.java
git commit -m "feat: configure Android image Redis"
```

### Task 2: Implement tenant-scoped binary image storage

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/protocol/media/AndroidImageAsset.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/media/AndroidImageAssetRef.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/media/AndroidImageAssetStore.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/media/RedisAndroidImageAssetStore.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/media/AndroidImageAssetTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/media/RedisAndroidImageAssetStoreTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/AndroidImageRedisConfiguration.java`

- [ ] **Step 1: Write failing asset identity tests**

Create `AndroidImageAssetTest.java`:

```java
package com.armada.platform.protocol.media;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AndroidImageAssetTest {

    @Test
    void derivesStableTenantScopedReference() {
        byte[] source = "same-image".getBytes();
        AndroidImageAsset first = AndroidImageAsset.from(7L, source, "image/png");
        AndroidImageAsset second = AndroidImageAsset.from(7L, source, "image/png");

        assertThat(first.identity()).isEqualTo(second.identity());
        assertThat(first.reference().sha256()).hasSize(64);
        assertThat(first.reference().sizeBytes()).isEqualTo(source.length);
        assertThat(first.reference().mimetype()).isEqualTo("image/png");
        assertThat(first.reference().transformProfile()).isEqualTo("marketing-image-v1");
    }

    @Test
    void rejectsMissingTenantOrMediaButDoesNotRepeatTheFiveHundredKilobyteGate() {
        assertThatThrownBy(() -> AndroidImageAsset.from(null, new byte[]{1}, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AndroidImageAsset.from(7L, new byte[0], "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AndroidImageAsset.from(7L, new byte[500 * 1024 + 1], "image/png").reference().sizeBytes())
                .isEqualTo(500 * 1024 + 1);
    }
}
```

The last assertion protects the confirmed requirement: dispatch code must not add a second 500KB business gate.

- [ ] **Step 2: Write failing Redis touch/write tests**

Create `RedisAndroidImageAssetStoreTest.java` using Mockito. The essential cases are:

```java
package com.armada.platform.protocol.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisAndroidImageAssetStoreTest {

    private final RedisTemplate<String, byte[]> redis = mock(RedisTemplate.class);
    private final ValueOperations<String, byte[]> values = mock(ValueOperations.class);
    private final RedisAndroidImageAssetStore store =
            new RedisAndroidImageAssetStore(redis, "android-zhuan:");

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void existingAssetOnlyRefreshesTwentyFourHourTtl() {
        AndroidImageAsset asset = AndroidImageAsset.from(7L, "image".getBytes(), "image/png");
        when(redis.expire(asset.redisKey("android-zhuan:"), Duration.ofHours(24))).thenReturn(true);

        store.ensure(asset);

        verify(values, never()).setIfAbsent(any(), any(), any(Duration.class));
    }

    @Test
    void missingAssetWritesRawBytesWithTtl() {
        AndroidImageAsset asset = AndroidImageAsset.from(7L, "raw-image".getBytes(), "image/png");
        when(redis.expire(asset.redisKey("android-zhuan:"), Duration.ofHours(24))).thenReturn(false);
        when(values.setIfAbsent(eq(asset.redisKey("android-zhuan:")), any(), eq(Duration.ofHours(24))))
                .thenReturn(true);

        store.ensure(asset);

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(values).setIfAbsent(eq(asset.redisKey("android-zhuan:")), bytes.capture(),
                eq(Duration.ofHours(24)));
        assertThat(bytes.getValue()).containsExactly("raw-image".getBytes());
    }

    @Test
    void unresolvedExpireSetRaceFailsClosed() {
        AndroidImageAsset asset = AndroidImageAsset.from(7L, "image".getBytes(), "image/png");
        when(redis.expire(asset.redisKey("android-zhuan:"), Duration.ofHours(24))).thenReturn(false);
        when(values.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> store.ensure(asset))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ensure Android image asset");
    }
}
```

- [ ] **Step 3: Run the two tests and verify they fail**

Run:

```bash
mvn -Dtest=AndroidImageAssetTest,RedisAndroidImageAssetStoreTest test
```

Expected: compilation fails because the asset types and store do not exist.

- [ ] **Step 4: Implement the exact reference and source-asset types**

Create `AndroidImageAssetRef.java`:

```java
package com.armada.platform.protocol.media;

public record AndroidImageAssetRef(
        String sha256,
        int sizeBytes,
        String mimetype,
        String transformProfile
) {
}
```

Add record Javadoc describing this as the Android Kafka wire reference and document all four components.

Create `AndroidImageAsset.java`:

```java
package com.armada.platform.protocol.media;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record AndroidImageAsset(
        Long tenantId,
        String sha256,
        byte[] sourceBytes,
        String mimetype
) {
    public static final String TRANSFORM_PROFILE = "marketing-image-v1";
    private static final String LOGICAL_PREFIX = "marketing:image:v1:";

    public static AndroidImageAsset from(Long tenantId, byte[] sourceBytes, String mimetype) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (sourceBytes == null || sourceBytes.length == 0) {
            throw new IllegalArgumentException("image bytes are required");
        }
        if (mimetype == null || mimetype.isBlank()) {
            throw new IllegalArgumentException("image mimetype is required");
        }
        return new AndroidImageAsset(tenantId, sha256(sourceBytes), sourceBytes, mimetype.trim());
    }

    public String identity() {
        return tenantId + ":" + sha256;
    }

    public AndroidImageAssetRef reference() {
        return new AndroidImageAssetRef(
                sha256, sourceBytes.length, mimetype, TRANSFORM_PROFILE);
    }

    public String redisKey(String keyPrefix) {
        return keyPrefix + LOGICAL_PREFIX + tenantId + ":" + sha256;
    }

    private static String sha256(byte[] source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
```

Do not clone `sourceBytes`; the existing message model already treats media bytes as read-only, and cloning once per command would recreate the memory amplification this feature removes.

Add class/record and public-method Javadocs covering tenant isolation, SHA identity, raw-byte immutability, reference creation, and physical Key derivation.

Create `AndroidImageAssetStore.java`:

```java
package com.armada.platform.protocol.media;

public interface AndroidImageAssetStore {
    void ensure(AndroidImageAsset asset);
}
```

Add interface and method Javadocs stating that `ensure` must finish before outbox persistence and throws an infrastructure exception when Redis cannot guarantee availability.

- [ ] **Step 5: Implement binary Redis ensure semantics**

Create `RedisAndroidImageAssetStore.java`:

```java
package com.armada.platform.protocol.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;

public final class RedisAndroidImageAssetStore implements AndroidImageAssetStore {

    private static final Logger log = LoggerFactory.getLogger(RedisAndroidImageAssetStore.class);
    private static final Duration TTL = Duration.ofHours(24);

    private final RedisTemplate<String, byte[]> redis;
    private final String keyPrefix;

    public RedisAndroidImageAssetStore(
            RedisTemplate<String, byte[]> redis,
            String keyPrefix) {
        this.redis = redis;
        this.keyPrefix = keyPrefix.trim();
    }

    @Override
    public void ensure(AndroidImageAsset asset) {
        String key = asset.redisKey(keyPrefix);
        long startedAt = System.nanoTime();
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (Boolean.TRUE.equals(redis.expire(key, TTL))) {
                    log.debug("Android image Redis TTL refreshed tenantId={} shaPrefix={} elapsedMicros={}",
                            asset.tenantId(), asset.sha256().substring(0, 8), elapsedMicros(startedAt));
                    return;
                }
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, asset.sourceBytes(), TTL))) {
                    log.info("Android image cached tenantId={} shaPrefix={} sizeBytes={} elapsedMicros={}",
                            asset.tenantId(), asset.sha256().substring(0, 8),
                            asset.sourceBytes().length, elapsedMicros(startedAt));
                    return;
                }
            }
            throw new IllegalStateException("ensure Android image asset: Redis key changed repeatedly");
        } catch (RuntimeException exception) {
            log.warn("Android image Redis ensure failed tenantId={} shaPrefix={} sizeBytes={}",
                    asset.tenantId(), asset.sha256().substring(0, 8),
                    asset.sourceBytes().length, exception);
            throw exception;
        }
    }

    private static long elapsedMicros(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000L;
    }
}
```

This emits at most one info log for a new unique image and only debug logs for TTL touches; it never logs bytes, a full Key, or a full SHA.

- [ ] **Step 6: Register the store bean**

Add to `AndroidImageRedisConfiguration.java`:

```java
import com.armada.platform.protocol.media.AndroidImageAssetStore;
import com.armada.platform.protocol.media.RedisAndroidImageAssetStore;

@Bean
public AndroidImageAssetStore androidImageAssetStore(
        @Qualifier("androidImageRedisTemplate") RedisTemplate<String, byte[]> redis,
        AndroidImageRedisProperties properties) {
    return new RedisAndroidImageAssetStore(redis, properties.getKeyPrefix());
}
```

- [ ] **Step 7: Run the focused tests and verify green**

Run:

```bash
mvn -Dtest=AndroidImageAssetTest,RedisAndroidImageAssetStoreTest,AndroidImageRedisPropertiesTest test
```

Expected: `BUILD SUCCESS`; all identity, raw-binary, 24-hour TTL, hit, miss, and race tests pass.

- [ ] **Step 8: Commit the asset store unit**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/media \
  armada-api/src/test/java/com/armada/platform/protocol/media \
  armada-api/src/main/java/com/armada/platform/protocol/config/AndroidImageRedisConfiguration.java
git commit -m "feat: cache Android marketing images in Redis"
```

### Task 3: Replace Android Base64 media with deduplicated asset references

**Files:**

- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackendTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`

- [ ] **Step 1: Update the test fixture constructor and write the failing image-reference contract test**

Add a mock store to `AndroidMessageSendBackendTest` and construct the backend with it:

```java
private final AndroidImageAssetStore assetStore = mock(AndroidImageAssetStore.class);
private final AndroidMessageSendBackend backend =
        new AndroidMessageSendBackend(outboxService, properties, assetStore);
```

Add this test, with an `imageCommand(commandId, bytes)` helper that builds `MessageType.IMAGE` using the existing `account()` and `correlation()` helpers:

```java
@Test
void writesImageReferenceWithoutBase64() {
    byte[] source = "source-image".getBytes();
    MessageSendCommand command = imageCommand("cmd_image", source);
    when(outboxService.enqueueMessageCommands(anyList()))
            .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd_image"), 1));

    backend.enqueue(List.of(command));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ProtocolMessageOutboxCommand>> captor = ArgumentCaptor.forClass(List.class);
    verify(outboxService).enqueueMessageCommands(captor.capture());
    Map<String, Object> payload = objectMapper.convertValue(
            captor.getValue().get(0).payload(), new TypeReference<>() {});
    @SuppressWarnings("unchecked")
    Map<String, Object> image = (Map<String, Object>) payload.get("image");
    @SuppressWarnings("unchecked")
    Map<String, Object> assetRef = (Map<String, Object>) image.get("assetRef");

    assertThat(image).doesNotContainKeys("base64", "mimetype");
    assertThat(assetRef)
            .containsEntry("sizeBytes", source.length)
            .containsEntry("mimetype", "image/png")
            .containsEntry("transformProfile", "marketing-image-v1");
    assertThat(assetRef.get("sha256").toString()).hasSize(64);
}
```

- [ ] **Step 2: Write the failing batch-deduplication and card-thumbnail tests**

Add tests that capture `AndroidImageAssetStore.ensure`:

```java
@Test
void ensuresSameImageOnlyOnceForManyGroupsInOneBatch() {
    byte[] source = "shared-template-image".getBytes();
    MessageSendCommand first = imageCommand("cmd_1", source);
    MessageSendCommand second = imageCommand("cmd_2", source);
    when(outboxService.enqueueMessageCommands(anyList()))
            .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd_1", "cmd_2"), 2));

    backend.enqueue(List.of(first, second));

    ArgumentCaptor<AndroidImageAsset> assets = ArgumentCaptor.forClass(AndroidImageAsset.class);
    verify(assetStore).ensure(assets.capture());
    assertThat(assets.getValue().tenantId()).isEqualTo(7L);
    assertThat(assets.getValue().sourceBytes()).isSameAs(source);
}

@Test
void encodesLinkAndButtonThumbnailsAsAssetReferences() {
    byte[] source = "shared-card-image".getBytes();
    MessageSendCommand link = linkCardCommand("cmd_link", source);
    MessageSendCommand button = buttonCardCommand("cmd_button", source);
    when(outboxService.enqueueMessageCommands(anyList()))
            .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                    null, List.of("cmd_link", "cmd_button"), 2));

    backend.enqueue(List.of(link, button));

    verify(assetStore).ensure(any(AndroidImageAsset.class));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ProtocolMessageOutboxCommand>> captor = ArgumentCaptor.forClass(List.class);
    verify(outboxService).enqueueMessageCommands(captor.capture());
    List<Map<String, Object>> payloads = captor.getValue().stream()
            .map(value -> objectMapper.convertValue(
                    value.payload(), new TypeReference<Map<String, Object>>() {}))
            .toList();
    assertThat(payloads.toString()).contains("assetRef").doesNotContain("base64");
}
```

Use `verify(assetStore, times(1))` if Mockito selects the varargs overload; the assertion must prove one ensure call for the shared source.

Add these complete helpers to the test class:

```java
private static MessageSendCommand imageCommand(String commandId, byte[] source) {
    return new MessageSendCommand(
            account(),
            new MessageSendCommand.MessageTarget("120363001@g.us"),
            new MessageSendCommand.MessagePayload(
                    MessageType.IMAGE,
                    new MessageSendCommand.MessageContent(
                            "caption",
                            new MessageSendCommand.MessageMedia(source, "image/png"),
                            null,
                            null),
                    false),
            correlation(), commandId, 750, 0L);
}

private static MessageSendCommand linkCardCommand(String commandId, byte[] source) {
    MessageSendCommand.MessageMedia thumbnail =
            new MessageSendCommand.MessageMedia(source, "image/png");
    return new MessageSendCommand(
            account(),
            new MessageSendCommand.MessageTarget("120363002@g.us"),
            new MessageSendCommand.MessagePayload(
                    MessageType.LINK_CARD,
                    new MessageSendCommand.MessageContent(
                            "body", null,
                            new MessageSendCommand.MessageLinkCard(
                                    "https://example.com/card", "title", "description", thumbnail),
                            null),
                    false),
            correlation(), commandId, 750, 0L);
}

private static MessageSendCommand buttonCardCommand(String commandId, byte[] source) {
    MessageSendCommand.MessageMedia thumbnail =
            new MessageSendCommand.MessageMedia(source, "image/png");
    MessageSendCommand.MessageButtonCard buttonCard = new MessageSendCommand.MessageButtonCard(
            "title", "footer",
            List.of(new MessageSendCommand.MessageButton(
                    "link", "查看详情", "https://example.com/button")),
            thumbnail);
    return new MessageSendCommand(
            account(),
            new MessageSendCommand.MessageTarget("120363003@g.us"),
            new MessageSendCommand.MessagePayload(
                    MessageType.BUTTON_CARD,
                    new MessageSendCommand.MessageContent("body", null, null, buttonCard),
                    false),
            correlation(), commandId, 750, 0L);
}
```

- [ ] **Step 3: Run the backend test and verify red**

Run:

```bash
mvn -Dtest=AndroidMessageSendBackendTest test
```

Expected: compilation fails because the backend constructor and payload still use Base64.

- [ ] **Step 4: Add batch media resolution before outbox encoding**

In `AndroidMessageSendBackend`, remove the Base64 import, add the asset store field and constructor argument, and split `enqueue` into validation, asset resolution, and encoding:

```java
private final AndroidImageAssetStore assetStore;

public AndroidMessageSendBackend(
        ProtocolCommandOutboxService outboxService,
        ProtocolAndroidCommandProperties properties,
        AndroidImageAssetStore assetStore) {
    this.outboxService = outboxService;
    this.properties = properties;
    this.assetStore = assetStore;
}
```

After collecting button-valid commands, resolve media before calling `toOutboxCommand`:

```java
List<MessageSendCommand> acceptedBusinessCommands = new ArrayList<>(commands.size());
Map<String, MessageSendEnqueueItem> results = new HashMap<>(commands.size());
for (MessageSendCommand command : commands) {
    MessageSendEnqueueItem validation = validateButtonCard(command);
    if (validation != null) {
        results.put(command.commandId(), validation);
        continue;
    }
    acceptedBusinessCommands.add(command);
    results.put(command.commandId(), MessageSendEnqueueItem.accepted(command.commandId()));
}

ResolvedMediaRegistry mediaRegistry = resolveMedia(acceptedBusinessCommands);
List<ProtocolMessageOutboxCommand> acceptedCommands =
        new ArrayList<>(acceptedBusinessCommands.size());
for (MessageSendCommand command : acceptedBusinessCommands) {
    acceptedCommands.add(toOutboxCommand(command, mediaRegistry));
}
if (!acceptedCommands.isEmpty()) {
    outboxService.enqueueMessageCommands(acceptedCommands);
}
return new MessageSendEnqueueResult(commands.stream()
        .map(command -> results.get(command.commandId()))
        .toList());
```

Add private records and methods that memoize SHA by source-array identity, isolate tenant lookups, and ensure each unique `tenantId + SHA` once:

```java
private ResolvedMediaRegistry resolveMedia(List<MessageSendCommand> commands) {
    IdentityHashMap<byte[], String> hashes = new IdentityHashMap<>();
    Map<AssetKey, AndroidImageAsset> uniqueAssets = new LinkedHashMap<>();
    Map<Long, IdentityHashMap<MessageSendCommand.MessageMedia, AndroidImageAssetRef>> references =
            new HashMap<>();

    for (MessageSendCommand command : commands) {
        Long tenantId = command.correlation().tenantId();
        MessageSendCommand.MessageContent content = command.payload().content();
        registerMedia(tenantId, content.image(), hashes, uniqueAssets, references);
        if (content.linkCard() != null) {
            registerMedia(tenantId, content.linkCard().thumbnail(), hashes, uniqueAssets, references);
        }
        if (content.buttonCard() != null) {
            registerMedia(tenantId, content.buttonCard().thumbnail(), hashes, uniqueAssets, references);
        }
    }
    uniqueAssets.values().forEach(assetStore::ensure);
    return new ResolvedMediaRegistry(references);
}

private static void registerMedia(
        Long tenantId,
        MessageSendCommand.MessageMedia media,
        IdentityHashMap<byte[], String> hashes,
        Map<AssetKey, AndroidImageAsset> uniqueAssets,
        Map<Long, IdentityHashMap<MessageSendCommand.MessageMedia, AndroidImageAssetRef>> references) {
    if (media == null) {
        return;
    }
    String sha = hashes.computeIfAbsent(media.bytes(), AndroidMessageSendBackend::sha256);
    AndroidImageAsset asset = new AndroidImageAsset(tenantId, sha, media.bytes(), media.mimetype().trim());
    uniqueAssets.putIfAbsent(new AssetKey(tenantId, sha), asset);
    references.computeIfAbsent(tenantId, ignored -> new IdentityHashMap<>())
            .put(media, asset.reference());
}

private static String sha256(byte[] source) {
    try {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
    } catch (NoSuchAlgorithmException exception) {
        throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
}

private record AssetKey(Long tenantId, String sha256) {}

private record ResolvedMediaRegistry(
        Map<Long, IdentityHashMap<MessageSendCommand.MessageMedia, AndroidImageAssetRef>> references) {
    AndroidImageAssetRef get(Long tenantId, MessageSendCommand.MessageMedia media) {
        if (media == null) {
            return null;
        }
        IdentityHashMap<MessageSendCommand.MessageMedia, AndroidImageAssetRef> tenantReferences =
                references.get(tenantId);
        AndroidImageAssetRef reference = tenantReferences == null ? null : tenantReferences.get(media);
        if (reference == null) {
            throw new IllegalStateException("Android image asset reference is missing");
        }
        return reference;
    }
}
```

Import `MessageDigest`, `NoSuchAlgorithmException`, `HexFormat`, `IdentityHashMap`, and `LinkedHashMap`. Keep all these types private to the Android adapter.

- [ ] **Step 5: Encode only `{assetRef}` for every Android media slot**

Change `toOutboxCommand`, `media`, `linkCard`, and `buttonCard` to receive `tenantId` plus `ResolvedMediaRegistry`. The media wire record becomes:

```java
private static AndroidMediaPayload media(
        Long tenantId,
        MessageSendCommand.MessageMedia media,
        ResolvedMediaRegistry registry) {
    if (media == null) {
        return null;
    }
    return new AndroidMediaPayload(registry.get(tenantId, media));
}

private record AndroidMediaPayload(AndroidImageAssetRef assetRef) {}
```

Construct card payloads with the same method:

```java
private static AndroidLinkCardPayload linkCard(
        Long tenantId,
        MessageSendCommand.MessageLinkCard card,
        ResolvedMediaRegistry registry) {
    if (card == null) {
        return null;
    }
    return new AndroidLinkCardPayload(
            card.url(), card.title(), card.description(),
            media(tenantId, card.thumbnail(), registry));
}
```

Implement `buttonCard` with the same explicit `media(tenantId, card.thumbnail(), registry)` call; do not route Web media through this registry.

- [ ] **Step 6: Inject the store from `ProtocolConfiguration`**

Change the bean method to:

```java
@Bean
public MessageSendBackend androidMessageSendBackend(
        ProtocolCommandOutboxService outboxService,
        ProtocolAndroidCommandProperties properties,
        AndroidImageAssetStore assetStore) {
    return new AndroidMessageSendBackend(outboxService, properties, assetStore);
}
```

- [ ] **Step 7: Run backend and Web regression tests**

Run:

```bash
mvn -Dtest=AndroidMessageSendBackendTest,WebMessageSendBackendTest test
```

Expected: Android payload assertions pass with `assetRef` and no `base64`; Web tests still pass with the existing Base64 shape.

- [ ] **Step 8: Commit the Android Kafka contract change**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java \
  armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackendTest.java
git commit -m "feat: publish Android image asset references"
```

### Task 4: Wire test-environment Redis variables and guard the namespace

**Files:**

- Modify: `armada-api/src/main/resources/application.yml`
- Modify: `armada-deploy/.env.example`
- Modify: `armada-deploy/docker-compose.rds.yml`
- Modify: `armada-deploy/verify-config.mjs`

- [ ] **Step 1: Add the application property block**

Under `armada.protocol` in `application.yml`, add:

```yaml
    android-image-cache:
      redis:
        mode: ${ANDROID_IMAGE_REDIS_MODE:standalone}
        addresses: ${ANDROID_IMAGE_REDIS_ADDRESSES:localhost:6379}
        username: ${ANDROID_IMAGE_REDIS_USERNAME:}
        password: ${ANDROID_IMAGE_REDIS_PASSWORD:}
        database: ${ANDROID_IMAGE_REDIS_DATABASE:0}
        tls: ${ANDROID_IMAGE_REDIS_TLS:false}
        # Must equal the Android Zhuan [redis].keyprefix in the same environment.
        key-prefix: ${ANDROID_IMAGE_REDIS_KEY_PREFIX:android-zhuan:}
```

Do not add a feature flag, configurable LRU size, or configurable image-size gate.

- [ ] **Step 2: Add non-secret environment examples**

Add to `armada-deploy/.env.example`:

```dotenv
ANDROID_IMAGE_REDIS_MODE=standalone
ANDROID_IMAGE_REDIS_ADDRESSES=127.0.0.1:6379
ANDROID_IMAGE_REDIS_USERNAME=
ANDROID_IMAGE_REDIS_PASSWORD=
ANDROID_IMAGE_REDIS_DATABASE=0
ANDROID_IMAGE_REDIS_TLS=false
ANDROID_IMAGE_REDIS_KEY_PREFIX=android-zhuan:
```

These are examples only. Do not put the real shared Redis password or endpoint in Git.

- [ ] **Step 3: Pass the variables through Compose**

Add to `armada-deploy/docker-compose.rds.yml` backend environment:

```yaml
      ANDROID_IMAGE_REDIS_MODE: ${ANDROID_IMAGE_REDIS_MODE:-standalone}
      ANDROID_IMAGE_REDIS_ADDRESSES: ${ANDROID_IMAGE_REDIS_ADDRESSES:-127.0.0.1:6379}
      ANDROID_IMAGE_REDIS_USERNAME: ${ANDROID_IMAGE_REDIS_USERNAME:-}
      ANDROID_IMAGE_REDIS_PASSWORD: ${ANDROID_IMAGE_REDIS_PASSWORD:-}
      ANDROID_IMAGE_REDIS_DATABASE: ${ANDROID_IMAGE_REDIS_DATABASE:-0}
      ANDROID_IMAGE_REDIS_TLS: ${ANDROID_IMAGE_REDIS_TLS:-false}
      ANDROID_IMAGE_REDIS_KEY_PREFIX: ${ANDROID_IMAGE_REDIS_KEY_PREFIX:-android-zhuan:}
```

- [ ] **Step 4: Make `verify-config.mjs` fail if the shared Redis contract disappears**

Add explicit `expectIncludes` calls for the `.env.example` key prefix and each Compose variable. The minimum namespace assertion is:

```javascript
expectIncludes(
  envExample,
  "ANDROID_IMAGE_REDIS_KEY_PREFIX=android-zhuan:",
  ".env.example"
);
expectIncludes(
  compose,
  "ANDROID_IMAGE_REDIS_KEY_PREFIX: ${ANDROID_IMAGE_REDIS_KEY_PREFIX:-android-zhuan:}",
  "docker-compose.rds.yml"
);
```

Add equivalent checks for mode, addresses, username, password, database, and TLS; never assert a real secret value.

- [ ] **Step 5: Run deploy-config and focused application tests**

Run from `armada`:

```bash
node armada-deploy/verify-config.mjs
cd armada-api
mvn -Dtest=AndroidImageRedisPropertiesTest,AndroidImageAssetTest,RedisAndroidImageAssetStoreTest,AndroidMessageSendBackendTest,WebMessageSendBackendTest test
```

Expected: config script prints `armada deploy config verification passed`; Maven prints `BUILD SUCCESS`.

- [ ] **Step 6: Commit the environment contract**

```bash
git add armada-api/src/main/resources/application.yml \
  armada-deploy/.env.example \
  armada-deploy/docker-compose.rds.yml \
  armada-deploy/verify-config.mjs
git commit -m "chore: configure shared Android image Redis"
```

### Task 5: Run the Armada quality gate and record evidence

**Files:**

- Modify: `.harness/changes/2026-07-19-android-marketing-image-redis-lru.md`

- [ ] **Step 1: Confirm the feature commits exclude unrelated files**

Run from `armada`:

```bash
git status --short
git log --oneline -4
git show --stat --oneline HEAD
```

Expected: feature commits contain only files listed in this plan. Existing unrelated Mapper and `.claude/worktrees` changes remain outside these commits.

- [ ] **Step 2: Run the full Armada test suite**

Run:

```bash
cd armada-api
mvn test
```

Expected: `BUILD SUCCESS` with zero test failures. No DbTest is required because this plan has no database, Mapper, SQL, tenant query, or Flyway change.

- [ ] **Step 3: Run static repository checks**

Run from `armada`:

```bash
node armada-deploy/verify-config.mjs
git diff --check
```

Expected: deploy verification passes and `git diff --check` prints no errors.

- [ ] **Step 4: Record exact command output in the change record**

Update the Armada task checkboxes and append the actual Maven summary, config verification line, commit IDs, and the explicit statement “未执行远程 Redis/Kafka/WhatsApp 验收” to `.harness/changes/2026-07-19-android-marketing-image-redis-lru.md`.

- [ ] **Step 5: Commit only the evidence update**

```bash
git add .harness/changes/2026-07-19-android-marketing-image-redis-lru.md
git commit -m "docs: record Armada image cache verification"
```

Do not claim end-to-end completion until the companion Zhuan plan and the test-environment acceptance task have also completed.
