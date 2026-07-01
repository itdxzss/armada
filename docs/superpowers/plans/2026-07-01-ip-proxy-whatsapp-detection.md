# IP Proxy WhatsApp Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Armada IP proxy detection with asynchronous full-pool detection that records real egress IP, WhatsApp official connectivity, smart-country assignment, backend check status, and per-stage timing logs.

**Architecture:** Keep the existing import flow and `IpProxyDetector` port, but change the detector contract from “ip-api reachable” to “egress IP resolved plus WhatsApp reachable through the same proxy session.” Add detection lifecycle columns to `ip_proxy`, keep allocation controlled by existing `status`, and make the background executor configurable. The frontend keeps current IP statistics UI; only manual detection calls get a longer timeout.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis XML, Flyway, MySQL, JUnit 5, AssertJ, Mockito, MockMvc, Vue 3, TypeScript, Axios.

---

## Source Spec

- Spec: `docs/superpowers/specs/2026-07-01-ip-proxy-whatsapp-detection-design.md`
- Prototype: `/Users/daishuaishuai/IdeaProjects/竞品.jpg`
- Current backend detector: `armada-api/src/main/java/com/armada/resource/check/impl/HttpIpProxyDetector.java`
- Current import service: `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`
- Current frontend IP API: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/resource-ip.ts`

## Scope Check

This plan covers one subsystem: IP proxy detection and the data it feeds into the existing IP management/statistics pages. It does not add new stats UI cards, does not build a supplier-specific IPRoyal API integration, and does not change account allocation priority rules.

## File Structure

Create:
- `armada-api/src/main/resources/db/migration/V026__ip_proxy_check_status.sql`
- `armada-api/src/main/java/com/armada/resource/model/IpProxyCheckLifecycleStatus.java`
- `armada-api/src/main/java/com/armada/resource/check/IpProxyCheckTiming.java`
- `armada-api/src/main/java/com/armada/resource/check/IpProxyCheckProperties.java`
- `armada-api/src/test/java/com/armada/resource/check/IpProxyCheckPropertiesTest.java`

Modify:
- `armada-api/src/main/java/com/armada/resource/model/entity/IpProxy.java`
- `armada-api/src/main/resources/mapper/resource/IpProxyMapper.xml`
- `armada-api/src/main/java/com/armada/resource/check/IpProxyCheckResult.java`
- `armada-api/src/main/java/com/armada/resource/check/IpProxyCheckConfiguration.java`
- `armada-api/src/main/java/com/armada/resource/check/impl/HttpIpProxyDetector.java`
- `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`
- `armada-api/src/main/resources/application.yml`
- `armada-api/src/test/java/com/armada/resource/check/impl/HttpIpProxyDetectorTest.java`
- `armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java`
- `armada-api/src/test/java/com/armada/resource/mapper/IpProxyMapperDbTest.java`
- `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/resource-ip.ts`
- `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/resource-ip.test.ts`

Do not modify:
- IP 数据统计页面 layout or summary cards.
- Account allocation SQL priority, except naturally relying on `status=1` for allocatable rows.
- Proxy username/password logging behavior.

## Version Guard

Before implementing, run:

```bash
ls armada-api/src/main/resources/db/migration | sort | tail
```

Expected today: latest is `V025__ip_proxy_detection_fields.sql`, so use `V026__ip_proxy_check_status.sql`. If another migration already uses `V026`, use the next free version and update this plan before coding.

---

## Task 1: Schema and Entity Detection Lifecycle

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V026__ip_proxy_check_status.sql`
- Create: `armada-api/src/main/java/com/armada/resource/model/IpProxyCheckLifecycleStatus.java`
- Modify: `armada-api/src/main/java/com/armada/resource/model/entity/IpProxy.java`
- Modify: `armada-api/src/main/resources/mapper/resource/IpProxyMapper.xml`
- Test: `armada-api/src/test/java/com/armada/resource/mapper/IpProxyMapperDbTest.java`

- [ ] **Step 1: Write the failing DbTest**

Add this test to `IpProxyMapperDbTest`:

```java
@Test
void insertAndUpdateDetectionResult_roundTripsCheckLifecycleAndWhatsappFields() {
    long now = System.currentTimeMillis();
    IpProxy proxy = newIdleProxy(now);
    proxy.setStatus(IpProxyStatus.UNAVAILABLE.code());
    proxy.setCheckStatus(IpProxyCheckLifecycleStatus.DETECTING.code());
    proxy.setWhatsappCheckStatus(IpProxyCheckLifecycleStatus.DETECTING.code());
    mapper.insert(proxy);

    IpProxy inserted = mapper.selectActiveById(proxy.getId());
    assertThat(inserted.getCheckStatus()).isEqualTo(IpProxyCheckLifecycleStatus.DETECTING.code());
    assertThat(inserted.getWhatsappCheckStatus()).isEqualTo(IpProxyCheckLifecycleStatus.DETECTING.code());

    IpProxy update = new IpProxy();
    update.setId(proxy.getId());
    update.setStatus(IpProxyStatus.IDLE.code());
    update.setCheckStatus(IpProxyCheckLifecycleStatus.SUCCESS.code());
    update.setWhatsappCheckStatus(IpProxyCheckLifecycleStatus.SUCCESS.code());
    update.setWhatsappHttpStatus(400);
    update.setWhatsappCheckError(null);
    update.setRegion("印度");
    update.setLastSampleCheckAt(now + 10);
    update.setDetectedCountryCode("IN");
    update.setOutboundIp("68.187.236.156");
    update.setDetectedLocation("Charlton, Massachusetts");
    update.setDetectedIsp("Charter Communications LLC");
    update.setDetectedLatitude(new java.math.BigDecimal("42.1357"));
    update.setDetectedLongitude(new java.math.BigDecimal("-71.9701"));
    update.setCheckFailCount(0);
    update.setLastCheckError(null);
    update.setUpdatedAt(now + 11);

    int updated = mapper.updateDetectionResult(update, IpProxyStatus.IN_USE.code());

    assertThat(updated).isEqualTo(1);
    IpProxy found = mapper.selectActiveById(proxy.getId());
    assertThat(found.getStatus()).isEqualTo(IpProxyStatus.IDLE.code());
    assertThat(found.getCheckStatus()).isEqualTo(IpProxyCheckLifecycleStatus.SUCCESS.code());
    assertThat(found.getWhatsappCheckStatus()).isEqualTo(IpProxyCheckLifecycleStatus.SUCCESS.code());
    assertThat(found.getWhatsappHttpStatus()).isEqualTo(400);
    assertThat(found.getWhatsappCheckError()).isNull();
    assertThat(found.getRegion()).isEqualTo("印度");
    assertThat(found.getOutboundIp()).isEqualTo("68.187.236.156");
}
```

Add the import:

```java
import com.armada.resource.model.IpProxyCheckLifecycleStatus;
```

- [ ] **Step 2: Run the focused DbTest and verify RED**

Run:

```bash
armada-api/dbtest.sh IpProxyMapperDbTest#insertAndUpdateDetectionResult_roundTripsCheckLifecycleAndWhatsappFields
```

Expected: FAIL because `IpProxyCheckLifecycleStatus` and new `IpProxy` fields do not exist.

- [ ] **Step 3: Add detection lifecycle enum**

Create `IpProxyCheckLifecycleStatus.java`:

```java
package com.armada.resource.model;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/**
 * IP 代理检测生命周期状态。该状态描述最近一次检测任务,不直接表示是否可分配。
 */
public enum IpProxyCheckLifecycleStatus {

    /** 检测中。 */
    DETECTING(0, "检测中"),
    /** 检测通过。 */
    SUCCESS(1, "检测通过"),
    /** 检测失败。 */
    FAILED(2, "检测失败");

    private final int code;
    private final String label;

    IpProxyCheckLifecycleStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static IpProxyCheckLifecycleStatus fromCode(Integer code) {
        if (code != null) {
            for (IpProxyCheckLifecycleStatus v : values()) {
                if (v.code == code) {
                    return v;
                }
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "非法的代理检测状态: " + code);
    }

    public static String labelOf(Integer code) {
        if (code != null) {
            for (IpProxyCheckLifecycleStatus v : values()) {
                if (v.code == code) {
                    return v.label;
                }
            }
        }
        return String.valueOf(code);
    }
}
```

- [ ] **Step 4: Add Flyway migration**

Create `V026__ip_proxy_check_status.sql`:

```sql
SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ip_proxy'
    AND column_name = 'check_status'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN check_status TINYINT NOT NULL DEFAULT 0 COMMENT ''检测生命周期:0=检测中 1=成功 2=失败'' AFTER last_check_error',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ip_proxy'
    AND column_name = 'whatsapp_check_status'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN whatsapp_check_status TINYINT NOT NULL DEFAULT 0 COMMENT ''WhatsApp检测状态:0=检测中 1=成功 2=失败'' AFTER check_status',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ip_proxy'
    AND column_name = 'whatsapp_http_status'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN whatsapp_http_status INT DEFAULT NULL COMMENT ''WhatsApp探测HTTP状态码'' AFTER whatsapp_check_status',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ip_proxy'
    AND column_name = 'whatsapp_check_error'
);

SET @ddl := IF(@column_exists = 0,
  'ALTER TABLE ip_proxy ADD COLUMN whatsapp_check_error VARCHAR(512) DEFAULT NULL COMMENT ''WhatsApp检测失败原因'' AFTER whatsapp_http_status',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
```

- [ ] **Step 5: Add entity fields and accessors**

In `IpProxy.java`, add fields after `lastCheckError`:

```java
    /** 检测生命周期:0=检测中 1=成功 2=失败。 */
    private Integer checkStatus;

    /** WhatsApp 检测状态:0=检测中 1=成功 2=失败。 */
    private Integer whatsappCheckStatus;

    /** WhatsApp 探测 HTTP 状态码。 */
    private Integer whatsappHttpStatus;

    /** WhatsApp 检测失败原因。 */
    private String whatsappCheckError;
```

Add getters/setters before `getSource()`:

```java
    public Integer getCheckStatus() {
        return checkStatus;
    }

    public void setCheckStatus(Integer checkStatus) {
        this.checkStatus = checkStatus;
    }

    public Integer getWhatsappCheckStatus() {
        return whatsappCheckStatus;
    }

    public void setWhatsappCheckStatus(Integer whatsappCheckStatus) {
        this.whatsappCheckStatus = whatsappCheckStatus;
    }

    public Integer getWhatsappHttpStatus() {
        return whatsappHttpStatus;
    }

    public void setWhatsappHttpStatus(Integer whatsappHttpStatus) {
        this.whatsappHttpStatus = whatsappHttpStatus;
    }

    public String getWhatsappCheckError() {
        return whatsappCheckError;
    }

    public void setWhatsappCheckError(String whatsappCheckError) {
        this.whatsappCheckError = whatsappCheckError;
    }
```

- [ ] **Step 6: Update MyBatis columns, insert, and detection update**

In `IpProxyMapper.xml`, update `<sql id="Columns">` to include:

```xml
        detected_latitude, detected_longitude, check_fail_count, last_check_error,
        check_status, whatsapp_check_status, whatsapp_http_status, whatsapp_check_error,
        source, ownership, allocation_mode, remark,
```

Update `insert` columns:

```xml
             detected_latitude, detected_longitude, check_fail_count, last_check_error,
             check_status, whatsapp_check_status, whatsapp_http_status, whatsapp_check_error,
             source, ownership, allocation_mode, remark,
```

Update `insert` values:

```xml
             #{detectedLatitude}, #{detectedLongitude}, #{checkFailCount}, #{lastCheckError},
             #{checkStatus}, #{whatsappCheckStatus}, #{whatsappHttpStatus}, #{whatsappCheckError},
             #{source}, #{ownership}, #{allocationMode}, #{remark}, #{createdAt}, #{updatedAt}, #{createdBy})
```

Update `updateDetectionResult` after `last_check_error`:

```xml
            check_status = #{entity.checkStatus},
            whatsapp_check_status = #{entity.whatsappCheckStatus},
            whatsapp_http_status = #{entity.whatsappHttpStatus},
            whatsapp_check_error = #{entity.whatsappCheckError},
            updated_at = #{entity.updatedAt}
```

- [ ] **Step 7: Run DbTest and verify GREEN**

Run:

```bash
armada-api/dbtest.sh IpProxyMapperDbTest#insertAndUpdateDetectionResult_roundTripsCheckLifecycleAndWhatsappFields
```

Expected: PASS.

- [ ] **Step 8: Commit schema/model slice**

```bash
git add armada-api/src/main/resources/db/migration/V026__ip_proxy_check_status.sql \
  armada-api/src/main/java/com/armada/resource/model/IpProxyCheckLifecycleStatus.java \
  armada-api/src/main/java/com/armada/resource/model/entity/IpProxy.java \
  armada-api/src/main/resources/mapper/resource/IpProxyMapper.xml \
  armada-api/src/test/java/com/armada/resource/mapper/IpProxyMapperDbTest.java
git commit -m "feat(resource): add ip proxy detection lifecycle fields"
```

---

## Task 2: Configurable Detection Executor and Result Timing Model

**Files:**
- Create: `armada-api/src/main/java/com/armada/resource/check/IpProxyCheckProperties.java`
- Create: `armada-api/src/main/java/com/armada/resource/check/IpProxyCheckTiming.java`
- Modify: `armada-api/src/main/java/com/armada/resource/check/IpProxyCheckConfiguration.java`
- Modify: `armada-api/src/main/java/com/armada/resource/check/IpProxyCheckResult.java`
- Modify: `armada-api/src/main/resources/application.yml`
- Test: `armada-api/src/test/java/com/armada/resource/check/IpProxyCheckPropertiesTest.java`

- [ ] **Step 1: Write the failing properties test**

Create `IpProxyCheckPropertiesTest.java`:

```java
package com.armada.resource.check;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import java.util.Map;

class IpProxyCheckPropertiesTest {

    @Test
    void binderLoadsExecutorAndTimeoutProperties() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new OriginTrackedMapPropertySource(
                "test",
                Map.of(
                        "armada.ip-proxy-check.executor.core-size", "5",
                        "armada.ip-proxy-check.executor.max-size", "13",
                        "armada.ip-proxy-check.executor.queue-capacity", "777",
                        "armada.ip-proxy-check.timeout.connect-ms", "1111",
                        "armada.ip-proxy-check.timeout.read-ms", "2222",
                        "armada.ip-proxy-check.timeout.total-ms", "3333"
                )));

        IpProxyCheckProperties properties = Binder.get(environment)
                .bind("armada.ip-proxy-check", Bindable.of(IpProxyCheckProperties.class))
                .orElseThrow();

        assertThat(properties.getExecutor().getCoreSize()).isEqualTo(5);
        assertThat(properties.getExecutor().getMaxSize()).isEqualTo(13);
        assertThat(properties.getExecutor().getQueueCapacity()).isEqualTo(777);
        assertThat(properties.getTimeout().getConnectMs()).isEqualTo(1111);
        assertThat(properties.getTimeout().getReadMs()).isEqualTo(2222);
        assertThat(properties.getTimeout().getTotalMs()).isEqualTo(3333);
    }

    @Test
    void defaultsMatchSpec() {
        IpProxyCheckProperties properties = new IpProxyCheckProperties();

        assertThat(properties.getExecutor().getCoreSize()).isEqualTo(4);
        assertThat(properties.getExecutor().getMaxSize()).isEqualTo(12);
        assertThat(properties.getExecutor().getQueueCapacity()).isEqualTo(5000);
        assertThat(properties.getTimeout().getConnectMs()).isEqualTo(5000);
        assertThat(properties.getTimeout().getReadMs()).isEqualTo(8000);
        assertThat(properties.getTimeout().getTotalMs()).isEqualTo(15000);
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd armada-api && mvn -q -Dtest=IpProxyCheckPropertiesTest test
```

Expected: FAIL because `IpProxyCheckProperties` does not exist.

- [ ] **Step 3: Add properties class**

Create `IpProxyCheckProperties.java`:

```java
package com.armada.resource.check;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IP 代理检测线程池和网络超时配置。
 */
@ConfigurationProperties(prefix = "armada.ip-proxy-check")
public class IpProxyCheckProperties {

    private final ExecutorProperties executor = new ExecutorProperties();
    private final TimeoutProperties timeout = new TimeoutProperties();

    public ExecutorProperties getExecutor() {
        return executor;
    }

    public TimeoutProperties getTimeout() {
        return timeout;
    }

    public static class ExecutorProperties {
        private int coreSize = 4;
        private int maxSize = 12;
        private int queueCapacity = 5000;

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    public static class TimeoutProperties {
        private int connectMs = 5000;
        private int readMs = 8000;
        private int totalMs = 15000;

        public int getConnectMs() {
            return connectMs;
        }

        public void setConnectMs(int connectMs) {
            this.connectMs = connectMs;
        }

        public int getReadMs() {
            return readMs;
        }

        public void setReadMs(int readMs) {
            this.readMs = readMs;
        }

        public int getTotalMs() {
            return totalMs;
        }

        public void setTotalMs(int totalMs) {
            this.totalMs = totalMs;
        }
    }
}
```

- [ ] **Step 4: Update executor configuration**

Replace `IpProxyCheckConfiguration` with:

```java
package com.armada.resource.check;

import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * IP 代理真实检测后台执行器配置。
 */
@Configuration
@EnableConfigurationProperties(IpProxyCheckProperties.class)
public class IpProxyCheckConfiguration {

    @Bean(name = "ipProxyCheckExecutor")
    public Executor ipProxyCheckExecutor(IpProxyCheckProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ip-proxy-check-");
        executor.setCorePoolSize(properties.getExecutor().getCoreSize());
        executor.setMaxPoolSize(properties.getExecutor().getMaxSize());
        executor.setQueueCapacity(properties.getExecutor().getQueueCapacity());
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 5: Add timing model**

Create `IpProxyCheckTiming.java`:

```java
package com.armada.resource.check;

/**
 * IP 代理检测阶段耗时,单位毫秒。未知阶段用 0。
 */
public record IpProxyCheckTiming(
        long totalMs,
        long egressMs,
        long geoMs,
        long whatsappConnectMs,
        long whatsappProbeMs) {

    public static IpProxyCheckTiming zero() {
        return new IpProxyCheckTiming(0, 0, 0, 0, 0);
    }
}
```

- [ ] **Step 6: Expand result model**

Replace `IpProxyCheckResult.java` with:

```java
package com.armada.resource.check;

import java.math.BigDecimal;

/**
 * 单次 IP 代理出口与 WhatsApp 连通性检测结果。
 */
public record IpProxyCheckResult(
        Long id,
        boolean success,
        String outboundIp,
        String countryCode,
        String location,
        String isp,
        BigDecimal latitude,
        BigDecimal longitude,
        boolean whatsappReachable,
        Integer whatsappHttpStatus,
        String whatsappErrorMessage,
        IpProxyCheckTiming timing,
        Long checkedAt,
        String errorMessage) {

    /** 构造成功结果。 */
    public static IpProxyCheckResult success(Long id,
                                             String outboundIp,
                                             String countryCode,
                                             String location,
                                             String isp,
                                             BigDecimal latitude,
                                             BigDecimal longitude,
                                             Integer whatsappHttpStatus,
                                             IpProxyCheckTiming timing,
                                             Long checkedAt) {
        return new IpProxyCheckResult(
                id, true, outboundIp, countryCode, location, isp, latitude, longitude,
                true, whatsappHttpStatus, null, timing == null ? IpProxyCheckTiming.zero() : timing, checkedAt, null);
    }

    /** 构造失败结果。 */
    public static IpProxyCheckResult failed(Long id,
                                            String errorMessage,
                                            String whatsappErrorMessage,
                                            Integer whatsappHttpStatus,
                                            IpProxyCheckTiming timing,
                                            Long checkedAt) {
        return new IpProxyCheckResult(
                id, false, null, null, null, null, null, null,
                false, whatsappHttpStatus, whatsappErrorMessage,
                timing == null ? IpProxyCheckTiming.zero() : timing, checkedAt, errorMessage);
    }

    /** 兼容旧测试和调用点的失败工厂。 */
    public static IpProxyCheckResult failed(Long id, String errorMessage, Long checkedAt) {
        return failed(id, errorMessage, errorMessage, null, IpProxyCheckTiming.zero(), checkedAt);
    }
}
```

Update existing tests that call `IpProxyCheckResult.success(...)` by adding `400, IpProxyCheckTiming.zero()` before `checkedAt`.

- [ ] **Step 7: Add application.yml defaults**

Append under `armada:` in `application.yml`:

```yaml
  ip-proxy-check:
    executor:
      core-size: ${IP_PROXY_CHECK_EXECUTOR_CORE_SIZE:4}
      max-size: ${IP_PROXY_CHECK_EXECUTOR_MAX_SIZE:12}
      queue-capacity: ${IP_PROXY_CHECK_EXECUTOR_QUEUE_CAPACITY:5000}
    timeout:
      connect-ms: ${IP_PROXY_CHECK_CONNECT_TIMEOUT_MS:5000}
      read-ms: ${IP_PROXY_CHECK_READ_TIMEOUT_MS:8000}
      total-ms: ${IP_PROXY_CHECK_TOTAL_TIMEOUT_MS:15000}
```

- [ ] **Step 8: Run tests and verify GREEN**

Run:

```bash
cd armada-api && mvn -q -Dtest=IpProxyCheckPropertiesTest,HttpIpProxyDetectorTest,IpProxyServiceImplTest test
```

Expected: PASS after updating old `IpProxyCheckResult.success(...)` call sites.

- [ ] **Step 9: Commit config/result slice**

```bash
git add armada-api/src/main/java/com/armada/resource/check/IpProxyCheckProperties.java \
  armada-api/src/main/java/com/armada/resource/check/IpProxyCheckTiming.java \
  armada-api/src/main/java/com/armada/resource/check/IpProxyCheckConfiguration.java \
  armada-api/src/main/java/com/armada/resource/check/IpProxyCheckResult.java \
  armada-api/src/main/resources/application.yml \
  armada-api/src/test/java/com/armada/resource/check/IpProxyCheckPropertiesTest.java \
  armada-api/src/test/java/com/armada/resource/check/impl/HttpIpProxyDetectorTest.java \
  armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java
git commit -m "feat(resource): configure ip proxy check timing"
```

---

## Task 3: Detector WhatsApp Semantics

**Files:**
- Modify: `armada-api/src/main/java/com/armada/resource/check/impl/HttpIpProxyDetector.java`
- Modify: `armada-api/src/test/java/com/armada/resource/check/impl/HttpIpProxyDetectorTest.java`

- [ ] **Step 1: Add pure parsing tests**

Add tests to `HttpIpProxyDetectorTest`:

```java
@Test
void parsePlainIp_trimsPlainTextIp() {
    assertThat(HttpIpProxyDetector.parsePlainIp(" 68.187.236.156\n")).isEqualTo("68.187.236.156");
}

@Test
void parsePlainIp_rejectsBlankOrHtmlBody() {
    assertThatThrownBy(() -> HttpIpProxyDetector.parsePlainIp(" "))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("出口 IP 响应为空");
    assertThatThrownBy(() -> HttpIpProxyDetector.parsePlainIp("<html>bad</html>"))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("出口 IP 响应非法");
}

@Test
void isWhatsappStatusAcceptable_acceptsAnyExplicitHttpStatusFromWhatsapp() {
    assertThat(HttpIpProxyDetector.isWhatsappStatusAcceptable(200)).isTrue();
    assertThat(HttpIpProxyDetector.isWhatsappStatusAcceptable(301)).isTrue();
    assertThat(HttpIpProxyDetector.isWhatsappStatusAcceptable(400)).isTrue();
    assertThat(HttpIpProxyDetector.isWhatsappStatusAcceptable(503)).isTrue();
    assertThat(HttpIpProxyDetector.isWhatsappStatusAcceptable(null)).isFalse();
}
```

Add imports:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [ ] **Step 2: Run focused detector test and verify RED**

Run:

```bash
cd armada-api && mvn -q -Dtest=HttpIpProxyDetectorTest test
```

Expected: FAIL because `parsePlainIp` and `isWhatsappStatusAcceptable` do not exist.

- [ ] **Step 3: Replace detector target constants and constructor**

In `HttpIpProxyDetector.java`, add properties-backed constructor and constants:

```java
private static final String EGRESS_IP_HOST = "api.ipify.org";
private static final int EGRESS_IP_PORT = 80;
private static final String EGRESS_IP_PATH = "/";
private static final String GEO_API_HOST = "ip-api.com";
private static final int GEO_API_PORT = 80;
private static final String GEO_API_PATH_PREFIX = "/json/";
private static final String GEO_API_FIELDS = "?fields=status,message,query,countryCode,regionName,city,isp,lat,lon";
private static final String WHATSAPP_HOST = "web.whatsapp.com";
private static final int WHATSAPP_PORT = 443;
private static final String WHATSAPP_PROBE_REQUEST = "GET / HTTP/1.1\r\n"
        + "Host: web.whatsapp.com\r\n"
        + "User-Agent: armada-ip-proxy-check/1.0\r\n"
        + "Connection: close\r\n\r\n";

private final IpProxyCheckProperties properties;

public HttpIpProxyDetector(IpProxyCheckProperties properties) {
    this.properties = properties;
}

public HttpIpProxyDetector() {
    this(new IpProxyCheckProperties());
}
```

Update imports:

```java
import com.armada.resource.check.IpProxyCheckProperties;
import com.armada.resource.check.IpProxyCheckTiming;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
```

- [ ] **Step 4: Add parse helpers**

Add package-private static helpers near `parseIpApiJson`:

```java
static String parsePlainIp(String body) throws IOException {
    String value = body == null ? "" : body.trim();
    if (!StringUtils.hasText(value)) {
        throw new IOException("出口 IP 响应为空");
    }
    if (!value.matches("[0-9a-fA-F:.]{3,64}")) {
        throw new IOException("出口 IP 响应非法: " + value);
    }
    return value;
}

static boolean isWhatsappStatusAcceptable(Integer statusCode) {
    return statusCode != null && statusCode >= 100 && statusCode <= 599;
}
```

Keep `parseIpApiJson(...)`, but change its purpose to direct geo lookup by IP. Its success result can still carry `query`, country, location, ISP, lat, lon.

- [ ] **Step 5: Implement detector orchestration**

Replace `check(...)` with:

```java
@Override
public IpProxyCheckResult check(IpProxyCheckRequest request) {
    long started = System.nanoTime();
    long checkedAt = System.currentTimeMillis();
    long egressMs = 0;
    long geoMs = 0;
    long whatsappConnectMs = 0;
    long whatsappProbeMs = 0;
    try {
        validateRequest(request);

        long egressStarted = System.nanoTime();
        String outboundIp = resolveOutboundIp(request);
        egressMs = elapsedMs(egressStarted);

        long whatsappStarted = System.nanoTime();
        WhatsappProbeResult whatsapp = probeWhatsapp(request);
        whatsappConnectMs = whatsapp.connectMs();
        whatsappProbeMs = whatsapp.probeMs();
        if (!isWhatsappStatusAcceptable(whatsapp.httpStatus())) {
            throw new IOException("WhatsApp 未返回明确响应");
        }

        long geoStarted = System.nanoTime();
        IpProxyCheckResult geo = lookupGeo(outboundIp, request.id(), checkedAt);
        geoMs = elapsedMs(geoStarted);
        if (!geo.success()) {
            throw new IOException(geo.errorMessage());
        }

        return IpProxyCheckResult.success(
                request.id(),
                outboundIp,
                geo.countryCode(),
                geo.location(),
                geo.isp(),
                geo.latitude(),
                geo.longitude(),
                whatsapp.httpStatus(),
                new IpProxyCheckTiming(elapsedMs(started), egressMs, geoMs, whatsappConnectMs, whatsappProbeMs),
                checkedAt);
    } catch (Exception e) {
        String error = safeError(request, e);
        return IpProxyCheckResult.failed(
                request == null ? null : request.id(),
                error,
                error,
                null,
                new IpProxyCheckTiming(elapsedMs(started), egressMs, geoMs, whatsappConnectMs, whatsappProbeMs),
                checkedAt);
    }
}
```

Add helper:

```java
private static long elapsedMs(long startedNano) {
    return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNano);
}
```

- [ ] **Step 6: Implement outbound IP resolution**

Add methods:

```java
private String resolveOutboundIp(IpProxyCheckRequest request) throws IOException {
    String body = ProxyProtocol.HTTP.code() == request.protocol()
            ? checkHttpProxyPlain(request, "http://" + EGRESS_IP_HOST + EGRESS_IP_PATH, EGRESS_IP_HOST)
            : checkSocks5HttpPlain(request, EGRESS_IP_HOST, EGRESS_IP_PORT, EGRESS_IP_PATH);
    return parsePlainIp(body);
}

private String checkHttpProxyPlain(IpProxyCheckRequest request, String absoluteUrl, String hostHeader)
        throws IOException {
    try (Socket socket = openSocket(request.host(), request.port())) {
        OutputStream out = socket.getOutputStream();
        StringBuilder headers = new StringBuilder()
                .append("GET ").append(absoluteUrl).append(" HTTP/1.0\r\n")
                .append("Host: ").append(hostHeader).append("\r\n")
                .append("Connection: close\r\n");
        appendProxyAuthorization(headers, request);
        headers.append("\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return readHttpBody(socket.getInputStream());
    }
}

private String checkSocks5HttpPlain(IpProxyCheckRequest request, String targetHost, int targetPort, String path)
        throws IOException {
    try (Socket socket = openSocket(request.host(), request.port())) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        socks5Handshake(out, in, request);
        connectSocks5Target(out, in, targetHost, targetPort);
        String get = "GET " + path + " HTTP/1.0\r\n"
                + "Host: " + targetHost + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(get.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return readHttpBody(in);
    }
}

private static void appendProxyAuthorization(StringBuilder headers, IpProxyCheckRequest request) {
    if (StringUtils.hasText(request.username()) || StringUtils.hasText(request.password())) {
        String credential = value(request.username()) + ":" + value(request.password());
        headers.append("Proxy-Authorization: Basic ")
                .append(Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8)))
                .append("\r\n");
    }
}
```

Because `openSocket` becomes properties-aware, implement it as an instance method:

```java
private Socket openSocket(String host, Integer port) throws IOException {
    Socket socket = new Socket();
    socket.connect(new InetSocketAddress(host, port), properties.getTimeout().getConnectMs());
    socket.setSoTimeout(properties.getTimeout().getReadMs());
    return socket;
}
```

Use this instance `openSocket` throughout the detector. Remove or rename the old static `openSocket`.

- [ ] **Step 7: Implement direct geo lookup**

Add:

```java
private IpProxyCheckResult lookupGeo(String outboundIp, Long id, long checkedAt) throws IOException {
    try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress(GEO_API_HOST, GEO_API_PORT), properties.getTimeout().getConnectMs());
        socket.setSoTimeout(properties.getTimeout().getReadMs());
        OutputStream out = socket.getOutputStream();
        String path = GEO_API_PATH_PREFIX + outboundIp + GEO_API_FIELDS;
        String request = "GET " + path + " HTTP/1.0\r\n"
                + "Host: " + GEO_API_HOST + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return parseIpApiJson(id, readHttpBody(socket.getInputStream()), checkedAt);
    }
}
```

- [ ] **Step 8: Implement WhatsApp probe record and HTTP CONNECT path**

Add nested record:

```java
record WhatsappProbeResult(Integer httpStatus, long connectMs, long probeMs) {
}
```

Add:

```java
private WhatsappProbeResult probeWhatsapp(IpProxyCheckRequest request) throws IOException {
    return ProxyProtocol.HTTP.code() == request.protocol()
            ? probeWhatsappViaHttpProxy(request)
            : probeWhatsappViaSocks5(request);
}

private WhatsappProbeResult probeWhatsappViaHttpProxy(IpProxyCheckRequest request) throws IOException {
    long connectStarted = System.nanoTime();
    try (Socket socket = openSocket(request.host(), request.port())) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        StringBuilder headers = new StringBuilder()
                .append("CONNECT ").append(WHATSAPP_HOST).append(":").append(WHATSAPP_PORT).append(" HTTP/1.1\r\n")
                .append("Host: ").append(WHATSAPP_HOST).append(":").append(WHATSAPP_PORT).append("\r\n");
        appendProxyAuthorization(headers, request);
        headers.append("\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        int connectStatus = readHttpStatus(in);
        if (connectStatus < 200 || connectStatus >= 300) {
            throw new IOException("WhatsApp CONNECT 失败: HTTP " + connectStatus);
        }
        long connectMs = elapsedMs(connectStarted);
        long probeStarted = System.nanoTime();
        int whatsappStatus = probeTlsHttp(socket, in, out);
        return new WhatsappProbeResult(whatsappStatus, connectMs, elapsedMs(probeStarted));
    }
}
```

- [ ] **Step 9: Implement SOCKS5 WhatsApp path and TLS probe**

Add:

```java
private WhatsappProbeResult probeWhatsappViaSocks5(IpProxyCheckRequest request) throws IOException {
    long connectStarted = System.nanoTime();
    try (Socket socket = openSocket(request.host(), request.port())) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        socks5Handshake(out, in, request);
        connectSocks5Target(out, in, WHATSAPP_HOST, WHATSAPP_PORT);
        long connectMs = elapsedMs(connectStarted);
        long probeStarted = System.nanoTime();
        int whatsappStatus = probeTlsHttp(socket, in, out);
        return new WhatsappProbeResult(whatsappStatus, connectMs, elapsedMs(probeStarted));
    }
}

private int probeTlsHttp(Socket socket, InputStream ignoredIn, OutputStream ignoredOut) throws IOException {
    SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
    try (SSLSocket ssl = (SSLSocket) factory.createSocket(socket, WHATSAPP_HOST, WHATSAPP_PORT, false)) {
        ssl.setUseClientMode(true);
        ssl.setSoTimeout(properties.getTimeout().getReadMs());
        ssl.startHandshake();
        OutputStream out = ssl.getOutputStream();
        out.write(WHATSAPP_PROBE_REQUEST.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return readHttpStatus(ssl.getInputStream());
    }
}
```

Refactor existing SOCKS5 methods:

```java
private void socks5Handshake(OutputStream out, InputStream in, IpProxyCheckRequest request) throws IOException {
    boolean hasCredential = StringUtils.hasText(request.username()) || StringUtils.hasText(request.password());
    if (hasCredential) {
        out.write(new byte[]{0x05, 0x02, 0x02, 0x00});
    } else {
        out.write(new byte[]{0x05, 0x01, 0x00});
    }
    out.flush();
    byte[] method = readExactly(in, 2);
    if (method[0] != 0x05 || method[1] == (byte) 0xFF) {
        throw new IOException("SOCKS5代理不支持可用认证方式");
    }
    if (method[1] == 0x02) {
        authenticateSocks5(out, in, request);
    }
}

private static void connectSocks5Target(OutputStream out, InputStream in, String hostName, int port)
        throws IOException {
    byte[] host = hostName.getBytes(StandardCharsets.ISO_8859_1);
    out.write(new byte[]{0x05, 0x01, 0x00, 0x03, (byte) host.length});
    out.write(host);
    out.write(new byte[]{(byte) ((port >> 8) & 0xFF), (byte) (port & 0xFF)});
    out.flush();

    byte[] head = readExactly(in, 4);
    if (head[1] != 0x00) {
        throw new IOException("SOCKS5连接目标失败,响应码=" + (head[1] & 0xFF));
    }
    int atyp = head[3] & 0xFF;
    if (atyp == 0x01) {
        readExactly(in, 4);
    } else if (atyp == 0x03) {
        int len = readExactly(in, 1)[0] & 0xFF;
        readExactly(in, len);
    } else if (atyp == 0x04) {
        readExactly(in, 16);
    } else {
        throw new IOException("SOCKS5响应地址类型非法");
    }
    readExactly(in, 2);
}
```

Add HTTP status reader:

```java
private static int readHttpStatus(InputStream in) throws IOException {
    String header = readHttpHeader(in);
    int firstLineEnd = header.indexOf("\r\n");
    String statusLine = firstLineEnd < 0 ? header : header.substring(0, firstLineEnd);
    String[] parts = statusLine.split(" ");
    if (parts.length < 2) {
        throw new IOException("代理返回非HTTP响应");
    }
    try {
        return Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
        throw new IOException("HTTP状态码非法: " + statusLine, e);
    }
}

private static String readHttpHeader(InputStream in) throws IOException {
    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
    int previous3 = -1;
    int previous2 = -1;
    int previous1 = -1;
    int current;
    while ((current = in.read()) != -1) {
        buffer.write(current);
        if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
            return buffer.toString(StandardCharsets.ISO_8859_1);
        }
        previous3 = previous2;
        previous2 = previous1;
        previous1 = current;
    }
    throw new IOException("HTTP响应头提前结束");
}
```

Update `readHttpBody` to call `readHttpHeader` first and then `in.readAllBytes()` for body.

- [ ] **Step 10: Run detector tests and verify GREEN**

Run:

```bash
cd armada-api && mvn -q -Dtest=HttpIpProxyDetectorTest test
```

Expected: PASS without accessing public internet.

- [ ] **Step 11: Commit detector semantics**

```bash
git add armada-api/src/main/java/com/armada/resource/check/impl/HttpIpProxyDetector.java \
  armada-api/src/test/java/com/armada/resource/check/impl/HttpIpProxyDetectorTest.java
git commit -m "feat(resource): probe whatsapp through ip proxy"
```

---

## Task 4: Service State Flow and Timing Logs

**Files:**
- Modify: `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java`

- [ ] **Step 1: Add service tests for smart initial state and detection transitions**

Add imports:

```java
import com.armada.resource.model.IpProxyCheckLifecycleStatus;
import com.armada.resource.check.IpProxyCheckTiming;
```

Add tests:

```java
@Test
void importProxies_smartRowsStartDetectingAndUnavailableUntilBackgroundCheckCompletes() {
    when(countryService.resolveIpRegion(null)).thenReturn(null);
    when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);
    doAnswer(invocation -> {
        IpProxy row = invocation.getArgument(0, IpProxy.class);
        row.setId(10L);
        return 1;
    }).when(mapper).insert(any());

    IpProxyImportResultVO result = service.importProxies(
            new IpProxyImportDTO(null, 1, "供应商A", "1.1.1.1:8080:user1:pass1", null, "smart"));

    assertThat(result.insertedRows()).isEqualTo(1);
    ArgumentCaptor<IpProxy> insertCaptor = ArgumentCaptor.forClass(IpProxy.class);
    verify(mapper).insert(insertCaptor.capture());
    IpProxy inserted = insertCaptor.getValue();
    assertThat(inserted.getStatus()).isEqualTo(IpProxyStatus.UNAVAILABLE.code());
    assertThat(inserted.getCheckStatus()).isEqualTo(IpProxyCheckLifecycleStatus.DETECTING.code());
    assertThat(inserted.getWhatsappCheckStatus()).isEqualTo(IpProxyCheckLifecycleStatus.DETECTING.code());
    assertThat(inserted.getRegion()).isNull();
}

@Test
void checkProxy_successMarksCheckSuccessAndWhatsappSuccess() {
    IpProxy row = idleProxy();
    row.setStatus(IpProxyStatus.UNAVAILABLE.code());
    row.setCheckStatus(IpProxyCheckLifecycleStatus.DETECTING.code());
    IpProxy updated = idleProxy();
    updated.setRegion("印度");
    updated.setCheckStatus(IpProxyCheckLifecycleStatus.SUCCESS.code());
    updated.setWhatsappCheckStatus(IpProxyCheckLifecycleStatus.SUCCESS.code());
    updated.setWhatsappHttpStatus(400);
    updated.setDetectedCountryCode("IN");
    updated.setOutboundIp("68.187.236.156");
    when(mapper.selectActiveById(10L)).thenReturn(row, updated);
    when(countryService.resolveIpRegionByIso2("IN")).thenReturn("印度");
    when(detector.check(any())).thenReturn(IpProxyCheckResult.success(
            10L,
            "68.187.236.156",
            "IN",
            "Charlton, Massachusetts",
            "Charter Communications LLC",
            null,
            null,
            400,
            new IpProxyCheckTiming(6400, 1000, 200, 3000, 2200),
            1_719_800_000_000L));
    when(mapper.updateDetectionResult(any(), eq(IpProxyStatus.IN_USE.code()))).thenReturn(1);

    IpProxyCheckResultVO result = service.checkProxy(10L);

    assertThat(result.checkStatus()).isEqualTo("success");
    ArgumentCaptor<IpProxy> updateCaptor = ArgumentCaptor.forClass(IpProxy.class);
    verify(mapper).updateDetectionResult(updateCaptor.capture(), eq(IpProxyStatus.IN_USE.code()));
    IpProxy update = updateCaptor.getValue();
    assertThat(update.getStatus()).isEqualTo(IpProxyStatus.IDLE.code());
    assertThat(update.getCheckStatus()).isEqualTo(IpProxyCheckLifecycleStatus.SUCCESS.code());
    assertThat(update.getWhatsappCheckStatus()).isEqualTo(IpProxyCheckLifecycleStatus.SUCCESS.code());
    assertThat(update.getWhatsappHttpStatus()).isEqualTo(400);
    assertThat(update.getWhatsappCheckError()).isNull();
    assertThat(update.getRegion()).isEqualTo("印度");
}

@Test
void checkProxy_failureMarksCheckFailedAndWhatsappFailed() {
    IpProxy row = idleProxy();
    row.setStatus(IpProxyStatus.UNAVAILABLE.code());
    row.setCheckFailCount(2);
    when(mapper.selectActiveById(10L)).thenReturn(row, row);
    when(detector.check(any())).thenReturn(IpProxyCheckResult.failed(
            10L,
            "WhatsApp CONNECT 超时",
            "WhatsApp CONNECT 超时",
            null,
            new IpProxyCheckTiming(10032, 0, 0, 0, 0),
            1_719_800_000_000L));
    when(mapper.updateDetectionResult(any(), eq(IpProxyStatus.IN_USE.code()))).thenReturn(1);

    service.checkProxy(10L);

    ArgumentCaptor<IpProxy> updateCaptor = ArgumentCaptor.forClass(IpProxy.class);
    verify(mapper).updateDetectionResult(updateCaptor.capture(), eq(IpProxyStatus.IN_USE.code()));
    IpProxy update = updateCaptor.getValue();
    assertThat(update.getStatus()).isEqualTo(IpProxyStatus.UNAVAILABLE.code());
    assertThat(update.getCheckStatus()).isEqualTo(IpProxyCheckLifecycleStatus.FAILED.code());
    assertThat(update.getWhatsappCheckStatus()).isEqualTo(IpProxyCheckLifecycleStatus.FAILED.code());
    assertThat(update.getWhatsappCheckError()).isEqualTo("WhatsApp CONNECT 超时");
    assertThat(update.getCheckFailCount()).isEqualTo(3);
}
```

- [ ] **Step 2: Run service tests and verify RED**

Run:

```bash
cd armada-api && mvn -q -Dtest=IpProxyServiceImplTest test
```

Expected: FAIL because service does not set lifecycle fields or new initial states yet.

- [ ] **Step 3: Change imported row initial state**

In `persistProxy`, replace initial status setup with:

```java
row.setStatus(IpProxyStatus.UNAVAILABLE.code());
row.setCheckStatus(IpProxyCheckLifecycleStatus.DETECTING.code());
row.setWhatsappCheckStatus(IpProxyCheckLifecycleStatus.DETECTING.code());
row.setWhatsappHttpStatus(null);
row.setWhatsappCheckError(null);
row.setCheckFailCount(0);
```

Keep `row.setRegion(dto.region())`; `normalizeImport` already returns `null` for smart and mixed label for mixed.

- [ ] **Step 4: Update detection success/failure application**

Import:

```java
import com.armada.resource.model.IpProxyCheckLifecycleStatus;
import com.armada.resource.check.IpProxyCheckTiming;
```

In `applyDetectionSuccess`, add:

```java
update.setCheckStatus(IpProxyCheckLifecycleStatus.SUCCESS.code());
update.setWhatsappCheckStatus(IpProxyCheckLifecycleStatus.SUCCESS.code());
update.setWhatsappHttpStatus(result.whatsappHttpStatus());
update.setWhatsappCheckError(null);
```

In `applyDetectionFailure`, add:

```java
update.setCheckStatus(IpProxyCheckLifecycleStatus.FAILED.code());
update.setWhatsappCheckStatus(IpProxyCheckLifecycleStatus.FAILED.code());
update.setWhatsappHttpStatus(null);
update.setWhatsappCheckError(truncate(StringUtils.hasText(errorMessage) ? errorMessage : "WhatsApp 检测失败"));
```

- [ ] **Step 5: Add structured timing log**

At the end of `detectAndUpdate`, just before `return toCheckResultVO(...)`, add:

```java
logDetectionResult(proxy, result, update, checkSuccess);
```

Add helper method:

```java
private void logDetectionResult(IpProxy proxy, IpProxyCheckResult result, IpProxy update, boolean checkSuccess) {
    IpProxyCheckTiming timing = result == null || result.timing() == null
            ? IpProxyCheckTiming.zero()
            : result.timing();
    log.info("IP代理检测完成 proxyId={} protocol={} host={} port={} result={} checkStatus={} status={} region={} "
                    + "outboundIp={} totalMs={} egressMs={} geoMs={} whatsappConnectMs={} whatsappProbeMs={} "
                    + "whatsappHttpStatus={} error={}",
            proxy.getId(),
            ProxyProtocol.labelOf(proxy.getProtocol()),
            proxy.getHost(),
            proxy.getPort(),
            checkSuccess ? "success" : "failed",
            IpProxyCheckLifecycleStatus.labelOf(update.getCheckStatus()),
            IpProxyStatus.labelOf(update.getStatus()),
            update.getRegion(),
            update.getOutboundIp(),
            timing.totalMs(),
            timing.egressMs(),
            timing.geoMs(),
            timing.whatsappConnectMs(),
            timing.whatsappProbeMs(),
            update.getWhatsappHttpStatus(),
            update.getLastCheckError());
}
```

This log intentionally excludes username, password, and full proxy URL.

- [ ] **Step 6: Run service tests and verify GREEN**

Run:

```bash
cd armada-api && mvn -q -Dtest=IpProxyServiceImplTest test
```

Expected: PASS.

- [ ] **Step 7: Commit service state/log slice**

```bash
git add armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java \
  armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java
git commit -m "feat(resource): record ip proxy check state and timings"
```

---

## Task 5: Manual Detection Frontend Timeout

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/resource-ip.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/resource-ip.test.ts`

- [ ] **Step 1: Add failing frontend API tests**

In `resource-ip.test.ts`, extend the existing single and batch detection tests to assert the config timeout:

```ts
assert.equal(httpDouble.calls[0].config?.timeout, 30000);
```

For batch detection, use:

```ts
assert.equal(httpDouble.calls[0].config?.timeout, 120000);
```

- [ ] **Step 2: Run frontend API tests and verify RED**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web && pnpm test src/api/resource-ip.test.ts
```

Expected: FAIL because detection calls do not pass custom timeout config.

- [ ] **Step 3: Add custom timeout to detection API calls**

Update `checkIpProxy`:

```ts
export function checkIpProxy(id: number): Promise<IpProxyCheckResult> {
  return armadaRequest<IpProxyCheckResult>(
    "post",
    `/api/ip-proxies/${id}/check`,
    undefined,
    { timeout: 30000 }
  );
}
```

Update `batchCheckIpProxies`:

```ts
export function batchCheckIpProxies(
  ids: number[]
): Promise<IpProxyCheckResult[]> {
  return armadaRequest<IpProxyCheckResult[]>(
    "post",
    "/api/ip-proxies/check",
    { data: { ids } },
    { timeout: 120000 }
  );
}
```

- [ ] **Step 4: Run frontend API tests and verify GREEN**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web && pnpm test src/api/resource-ip.test.ts
```

Expected: PASS.

- [ ] **Step 5: Commit frontend timeout slice**

```bash
git -C /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web add src/api/resource-ip.ts src/api/resource-ip.test.ts
git -C /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web commit -m "fix(resource): extend ip proxy detection timeout"
```

---

## Task 6: Integration Verification

**Files:**
- No new files.
- Verify backend and frontend changes.

- [ ] **Step 1: Run backend unit tests**

Run:

```bash
cd armada-api && mvn -q -Dtest=HttpIpProxyDetectorTest,IpProxyCheckPropertiesTest,IpProxyServiceImplTest test
```

Expected: PASS.

- [ ] **Step 2: Run backend DbTests**

Run:

```bash
armada-api/dbtest.sh IpProxyMapperDbTest
```

Expected: PASS.

- [ ] **Step 3: Run frontend API tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web && pnpm test src/api/resource-ip.test.ts
```

Expected: PASS.

- [ ] **Step 4: Run backend package build**

Run:

```bash
cd armada-api && mvn -q -DskipTests package
```

Expected: exit code 0 and jar generated at `armada-api/target/armada-api-1.0.0-SNAPSHOT.jar`.

- [ ] **Step 5: Inspect git status in both repos**

Run:

```bash
git -C /Users/daishuaishuai/IdeaProjects/armada status --short
git -C /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web status --short
```

Expected:
- Armada only has intentional commits from this plan plus pre-existing `.claude/worktrees` noise.
- wheel-saas-pure-web only has the frontend timeout commit if Task 5 was implemented.

---

## Self-Review

Spec coverage:
- Upload returns immediately: Task 4 keeps import async and only changes initial state.
- Backend full detection: Tasks 2-4 replace detector semantics and state update.
- Smart/mixed country rules: Task 4 success/failure state tests and implementation cover smart/mixed behavior.
- Backend-only detecting state: Task 1 adds `check_status`; Task 5 does not expose it in frontend.
- IP stats follow prototype: No stats UI change; existing stats consume `status` and `region`.
- Timing logs: Task 4 adds structured `totalMs` and stage timings.
- Sensitive logging: Task 4 log helper excludes username/password.
- Manual detection timeout: Task 5 covers frontend API timeout.

Placeholder scan:
- No unresolved placeholder markers or deferred implementation steps.
- Every code-changing step includes concrete code snippets and commands.

Type consistency:
- `IpProxyCheckLifecycleStatus` is used consistently for `checkStatus` and `whatsappCheckStatus`.
- `IpProxyCheckTiming` fields match the log helper and detector result model.
- `whatsappHttpStatus` and `whatsappCheckError` names match entity, mapper, and service snippets.
