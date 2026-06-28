# armada 租户地基 + 极简登录 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 armada 后端按租户隔离地返回真数据、并让前端能登录进系统(先测功能,JWT 后置),作为 IP/导入链接/营销三页对接的共同地基。

**Architecture:** 后端加一张 `tenant` 注册表 + 一个读 `X-Tenant-Code` 头解析 tenantId 注入 `TenantContext` 的拦截器 + 一个公开极简登录接口(配置统一密码校验)。前端把登录指向真接口、请求带 `X-Tenant-Code`、按 armada `{code,message,data}` 信封拆包;本地 `npm run dev` 走 vite 代理连后端。部署(nginx/compose)不在本计划。

**Tech Stack:** Spring Boot + MyBatis(plain `@Mapper` + 手写 XML)+ MyBatis-Plus 租户行隔离插件 + Flyway + MySQL;Vue 3 + vue-pure-admin + axios + Pinia + Vite。

## Global Constraints

- 出入参 JSON 全 **camelCase**;统一信封 `ApiResponse{code,message,data}`,`code=0` 成功,业务错误抛 `BusinessException`(由 `GlobalExceptionHandler` 转 `code≠0`、HTTP 仍 200)。
- 实体是**纯 POJO + 手写 getter/setter**:无 Lombok、无 MyBatis-Plus 注解(`@TableName/@TableId/@TableLogic/@TableField` 一律不用)。
- Mapper 是 **plain `@Mapper` 接口 + 手写 XML**(不继承 `BaseMapper`);XML 放 `src/main/resources/mapper/<domain>/<Name>.xml`,比较符用 `&lt;`/`&gt;`(禁裸 `<`/`>`);列名靠 `map-underscore-to-camel-case` 映射或 `AS` 别名。
- **tenant_id 永不手写**(MyBatis 租户拦截器注入);唯一例外是 `tenant` 注册表本身——它无 tenant_id 列,必须登记进 `MyBatisConfig.IGNORED_TABLES`。
- 后端真库测试:`DbTest` 扩展 `DbTestBase`(`@SpringBootTest webEnvironment=NONE` + `@Transactional` 回滚 + `TenantContext.set(1)`),用 `./dbtest.sh <TestClass>` 跑;本机 MySQL,schema=`armada`。断言用 AssertJ。
- SQL 迁移文件名 `V<NNN>__<topic>.sql`,**下一个版本号 = `V004`**;表/列必带 `COMMENT`;`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`。
- 前端无单元测试框架:前端任务验证 = `vue-tsc` 类型检查 + 本地浏览器手验。
- 包归属:租户注册/登录代码放 `com.armada.platform.tenant.*`(现为空壳);拦截器放 `com.armada.shared.tenant`(挨着 `TenantContext`);Web 配置放 `com.armada.boot.config`。

---

### Task 1: tenant 注册表 + 实体 + Mapper + 排除租户隔离

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V004__tenant.sql`
- Create: `armada-api/src/main/java/com/armada/platform/tenant/model/entity/Tenant.java`
- Create: `armada-api/src/main/java/com/armada/platform/tenant/mapper/TenantMapper.java`
- Create: `armada-api/src/main/resources/mapper/platform/TenantMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/boot/config/MyBatisConfig.java:30`
- Test: `armada-api/src/test/java/com/armada/platform/tenant/mapper/TenantMapperDbTest.java`

**Interfaces:**
- Produces: `Tenant`(POJO:`Long getId()`, `String getTenantCode()`, `String getName()`, `Integer getStatus()`, `LocalDateTime getCreatedAt()/getUpdatedAt()`);`TenantMapper.selectActiveByCode(@Param("code") String code) → Tenant`(无则 null)。
- Consumes: 现有 `DbTestBase`、`MyBatisConfig.IGNORED_TABLES`。

- [ ] **Step 1: 写迁移 V004(建表 + seed 两个测试租户)**

`armada-api/src/main/resources/db/migration/V004__tenant.sql`:
```sql
-- 租户注册表(平台级)。本身无 tenant_id 列,不参与租户行隔离 —— 必须登记进 MyBatisConfig.IGNORED_TABLES,
-- 否则解析租户时该查询会被注入 AND tenant_id=? 抛 Unknown column。
-- 先测阶段:登录按 tenant_code + 配置统一密码校验;拦截器据 tenant_code 解析 tenant_id 注入上下文;JWT 后置。
CREATE TABLE tenant (
    id          BIGINT       NOT NULL AUTO_INCREMENT                    COMMENT '主键,即业务表里用的 tenant_id',
    tenant_code VARCHAR(64)  NOT NULL                                   COMMENT '租户码(前端 X-Tenant-Code 头 / 登录入参)',
    name        VARCHAR(128) NOT NULL                                   COMMENT '租户名',
    status      TINYINT      NOT NULL DEFAULT 1                         COMMENT '状态:1=启用 0=停用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                            COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_code (tenant_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '租户注册表';

-- 先测种子租户(id 固定;业务数据按此 tenant_id 隔离)。
INSERT INTO tenant (id, tenant_code, name, status) VALUES
    (1, 'demo',  '演示租户A', 1),
    (2, 'demo2', '演示租户B', 1);
```

- [ ] **Step 2: 写 Tenant 实体(纯 POJO)**

`armada-api/src/main/java/com/armada/platform/tenant/model/entity/Tenant.java`:
```java
package com.armada.platform.tenant.model.entity;

import java.time.LocalDateTime;

/** 租户实体,映射 tenant 表一行。普通类 + getter/setter(无 Lombok),Mapper 直出。 */
public class Tenant {

    private Long id;            // 主键,即 tenant_id
    private String tenantCode;  // 租户码
    private String name;        // 租户名
    private Integer status;     // 1=启用 0=停用
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantCode() { return tenantCode; }
    public void setTenantCode(String tenantCode) { this.tenantCode = tenantCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 3: 写 TenantMapper 接口 + XML**

`armada-api/src/main/java/com/armada/platform/tenant/mapper/TenantMapper.java`:
```java
package com.armada.platform.tenant.mapper;

import com.armada.platform.tenant.model.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 租户注册表数据访问。tenant 表无 tenant_id 列,已登记进 MyBatisConfig.IGNORED_TABLES,
 * 故此处查询不会被注入 tenant_id 过滤,登录/解析阶段(无租户上下文)可用。
 */
@Mapper
public interface TenantMapper {

    /** 按租户码查启用(status=1)的租户;无则返回 null。 */
    Tenant selectActiveByCode(@Param("code") String code);
}
```

`armada-api/src/main/resources/mapper/platform/TenantMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.armada.platform.tenant.mapper.TenantMapper">

    <select id="selectActiveByCode" resultType="com.armada.platform.tenant.model.entity.Tenant">
        SELECT id, tenant_code, name, status, created_at, updated_at
        FROM tenant
        WHERE tenant_code = #{code} AND status = 1
        LIMIT 1
    </select>
</mapper>
```

- [ ] **Step 4: 把 `tenant` 登记进 IGNORED_TABLES**

Modify `armada-api/src/main/java/com/armada/boot/config/MyBatisConfig.java:30` —
将 `private static final Set<String> IGNORED_TABLES = Set.of();`
改为:
```java
    private static final Set<String> IGNORED_TABLES = Set.of("tenant");
```

- [ ] **Step 5: 写失败测试**

`armada-api/src/test/java/com/armada/platform/tenant/mapper/TenantMapperDbTest.java`:
```java
package com.armada.platform.tenant.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.platform.tenant.model.entity.Tenant;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** TenantMapper 真库测试:验种子租户可按码查到、未知码返回 null、tenant 表不被租户隔离误伤。 */
class TenantMapperDbTest extends DbTestBase {

    @Autowired private TenantMapper tenantMapper;

    @Test
    void selectActiveByCode_returnsSeededTenant() {
        Tenant t = tenantMapper.selectActiveByCode("demo");
        assertThat(t).isNotNull();
        assertThat(t.getId()).isEqualTo(1L);
        assertThat(t.getName()).isEqualTo("演示租户A");
        assertThat(t.getStatus()).isEqualTo(1);
    }

    @Test
    void selectActiveByCode_unknownCode_returnsNull() {
        assertThat(tenantMapper.selectActiveByCode("nope")).isNull();
    }
}
```

- [ ] **Step 6: 跑测试确认失败**

Run: `cd armada-api && ./dbtest.sh TenantMapperDbTest`
Expected: 编译期/启动期通过 Flyway 建表后,若 Step 1-4 未全做则 FAIL(找不到 mapper / 表不存在 / 解析报错)。先在仅有测试时跑应 FAIL。

- [ ] **Step 7: 跑测试确认通过**

Run: `cd armada-api && ./dbtest.sh TenantMapperDbTest`
Expected: PASS(2 tests)。若报 `Unknown column 'tenant_id'`,说明 Step 4 漏做(未登记 IGNORED_TABLES)。

- [ ] **Step 8: 提交**

```bash
git add armada-api/src/main/resources/db/migration/V004__tenant.sql \
        armada-api/src/main/java/com/armada/platform/tenant/model/entity/Tenant.java \
        armada-api/src/main/java/com/armada/platform/tenant/mapper/TenantMapper.java \
        armada-api/src/main/resources/mapper/platform/TenantMapper.xml \
        armada-api/src/main/java/com/armada/boot/config/MyBatisConfig.java \
        armada-api/src/test/java/com/armada/platform/tenant/mapper/TenantMapperDbTest.java
git commit -m "feat(tenant): add tenant registry table + mapper, exclude from row isolation"
```

---

### Task 2: TenantCodeResolver(租户码 → tenantId)

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/tenant/service/TenantCodeResolver.java`
- Create: `armada-api/src/main/java/com/armada/platform/tenant/service/impl/TenantCodeResolverImpl.java`
- Test: `armada-api/src/test/java/com/armada/platform/tenant/service/TenantCodeResolverImplDbTest.java`

**Interfaces:**
- Consumes: `TenantMapper.selectActiveByCode` (Task 1)。
- Produces: `TenantCodeResolver.resolveTenantId(String tenantCode) → Optional<Long>`(空白/未知/停用 → `Optional.empty()`)。拦截器(Task 3)和登录(Task 5)都用它/它的底层 mapper。

- [ ] **Step 1: 写接口**

`armada-api/src/main/java/com/armada/platform/tenant/service/TenantCodeResolver.java`:
```java
package com.armada.platform.tenant.service;

import java.util.Optional;

/**
 * 把租户码解析成 tenant_id。先测阶段来源是 X-Tenant-Code 头;接 JWT 后改为从 token 取,本接口不变。
 */
public interface TenantCodeResolver {

    /** @return 启用租户的 id;租户码为空/未知/停用时返回空。 */
    Optional<Long> resolveTenantId(String tenantCode);
}
```

- [ ] **Step 2: 写失败测试**

`armada-api/src/test/java/com/armada/platform/tenant/service/TenantCodeResolverImplDbTest.java`:
```java
package com.armada.platform.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** TenantCodeResolver 真库测试:种子码可解析、空白/未知码返回空。 */
class TenantCodeResolverImplDbTest extends DbTestBase {

    @Autowired private TenantCodeResolver resolver;

    @Test
    void resolvesSeededCode() {
        assertThat(resolver.resolveTenantId("demo")).contains(1L);
        assertThat(resolver.resolveTenantId("demo2")).contains(2L);
    }

    @Test
    void unknownCode_empty() {
        assertThat(resolver.resolveTenantId("nope")).isEmpty();
    }

    @Test
    void blankCode_empty() {
        assertThat(resolver.resolveTenantId("  ")).isEmpty();
        assertThat(resolver.resolveTenantId(null)).isEmpty();
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd armada-api && ./dbtest.sh TenantCodeResolverImplDbTest`
Expected: FAIL —— 无 `TenantCodeResolver` 实现 bean,上下文启动失败 / `No qualifying bean`。

- [ ] **Step 4: 写实现**

`armada-api/src/main/java/com/armada/platform/tenant/service/impl/TenantCodeResolverImpl.java`:
```java
package com.armada.platform.tenant.service.impl;

import com.armada.platform.tenant.mapper.TenantMapper;
import com.armada.platform.tenant.model.entity.Tenant;
import com.armada.platform.tenant.service.TenantCodeResolver;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 查 tenant 表解析租户码 → tenant_id。 */
@Service
public class TenantCodeResolverImpl implements TenantCodeResolver {

    private final TenantMapper tenantMapper;

    public TenantCodeResolverImpl(TenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    @Override
    public Optional<Long> resolveTenantId(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return Optional.empty();
        }
        Tenant tenant = tenantMapper.selectActiveByCode(tenantCode.trim());
        return tenant == null ? Optional.empty() : Optional.of(tenant.getId());
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd armada-api && ./dbtest.sh TenantCodeResolverImplDbTest`
Expected: PASS(3 tests)。

- [ ] **Step 6: 提交**

```bash
git add armada-api/src/main/java/com/armada/platform/tenant/service/TenantCodeResolver.java \
        armada-api/src/main/java/com/armada/platform/tenant/service/impl/TenantCodeResolverImpl.java \
        armada-api/src/test/java/com/armada/platform/tenant/service/TenantCodeResolverImplDbTest.java
git commit -m "feat(tenant): add TenantCodeResolver resolving tenant code to id"
```

---

### Task 3: TenantContextInterceptor + 错误码

**Files:**
- Modify: `armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java`(加两个常量)
- Create: `armada-api/src/main/java/com/armada/shared/tenant/TenantContextInterceptor.java`
- Test: `armada-api/src/test/java/com/armada/shared/tenant/TenantContextInterceptorTest.java`

**Interfaces:**
- Consumes: `TenantCodeResolver`(Task 2)、`TenantContext`、`BusinessException`、`ErrorCode`。
- Produces: `TenantContextInterceptor`(构造参 `TenantCodeResolver`)+ 常量 `TenantContextInterceptor.TENANT_CODE_HEADER = "X-Tenant-Code"`;`ErrorCode.TENANT_MISSING`、`ErrorCode.TENANT_NOT_FOUND`。Task 4 注册它。

- [ ] **Step 1: 加错误码**

Modify `armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java` —— 在 `CONFLICT(40901, "资源冲突");` 之前(即把分号挪后),加入两个常量:
```java
    /** 资源冲突(如名称重复)。 */
    CONFLICT(40901, "资源冲突"),

    /** 缺少租户标识(请求未带 X-Tenant-Code)。 */
    TENANT_MISSING(40101, "缺少租户标识,请重新登录"),

    /** 租户码无效或租户已停用。 */
    TENANT_NOT_FOUND(40102, "租户不存在或已停用");
```
(原本 `CONFLICT(...)` 是最后一个、以分号结尾;改成逗号,新增两个常量,最后一个 `TENANT_NOT_FOUND(...)` 以分号结尾。)

- [ ] **Step 2: 写失败测试(纯单元,无 Spring)**

`armada-api/src/test/java/com/armada/shared/tenant/TenantContextInterceptorTest.java`:
```java
package com.armada.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.platform.tenant.service.TenantCodeResolver;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 拦截器单元测试:无头抛 TENANT_MISSING、无效码抛 TENANT_NOT_FOUND、有效码设上下文、afterCompletion 清理。 */
class TenantContextInterceptorTest {

    // 手写假 resolver:demo→1,其余空。
    private final TenantCodeResolver resolver =
            code -> "demo".equals(code) ? Optional.of(1L) : Optional.empty();
    private final TenantContextInterceptor interceptor = new TenantContextInterceptor(resolver);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void setsContext_whenHeaderResolves() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TenantContextInterceptor.TENANT_CODE_HEADER, "demo");

        boolean proceed = interceptor.preHandle(req, new MockHttpServletResponse(), new Object());

        assertThat(proceed).isTrue();
        assertThat(TenantContext.get()).isEqualTo(1L);
    }

    @Test
    void throwsTenantMissing_whenNoHeader() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.TENANT_MISSING.code());
    }

    @Test
    void throwsTenantNotFound_whenUnresolvable() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TenantContextInterceptor.TENANT_CODE_HEADER, "nope");
        assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.TENANT_NOT_FOUND.code());
    }

    @Test
    void afterCompletion_clearsContext() {
        TenantContext.set(9L);
        interceptor.afterCompletion(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);
        assertThat(TenantContext.get()).isNull();
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd armada-api && mvn -q -Dtest=TenantContextInterceptorTest -DfailIfNoTests=false test`
Expected: FAIL/编译错 —— `TenantContextInterceptor` 尚不存在。

- [ ] **Step 4: 写拦截器**

`armada-api/src/main/java/com/armada/shared/tenant/TenantContextInterceptor.java`:
```java
package com.armada.shared.tenant;

import com.armada.platform.tenant.service.TenantCodeResolver;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户上下文拦截器:从请求头 {@code X-Tenant-Code} 解析 tenant_id 写入 {@link TenantContext},
 * 供 MyBatis 租户行隔离拦截器读取。无头/无效码 fail-closed 抛业务异常(经 GlobalExceptionHandler 转 code≠0)。
 *
 * <p>先测阶段:租户码来自前端登录后存的头,无 JWT、可被伪造,无真安全;接 JWT 后改为从 token 取,本类结构不变。</p>
 * <p>铁律:{@code afterCompletion} 必清 ThreadLocal,防线程池复用串号。</p>
 */
public class TenantContextInterceptor implements HandlerInterceptor {

    public static final String TENANT_CODE_HEADER = "X-Tenant-Code";

    private static final Logger log = LoggerFactory.getLogger(TenantContextInterceptor.class);

    private final TenantCodeResolver tenantCodeResolver;

    public TenantContextInterceptor(TenantCodeResolver tenantCodeResolver) {
        this.tenantCodeResolver = tenantCodeResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String code = request.getHeader(TENANT_CODE_HEADER);
        if (code == null || code.isBlank()) {
            log.warn("tenant.reject reason=missing method={} path={}",
                    request.getMethod(), request.getRequestURI());
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        Long tenantId = tenantCodeResolver.resolveTenantId(code)
                .orElseThrow(() -> {
                    log.warn("tenant.reject reason=not_found code={} method={} path={}",
                            code, request.getMethod(), request.getRequestURI());
                    return new BusinessException(ErrorCode.TENANT_NOT_FOUND);
                });
        TenantContext.set(tenantId);
        log.debug("tenant.set tenantId={} code={} path={}", tenantId, code, request.getRequestURI());
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd armada-api && mvn -q -Dtest=TenantContextInterceptorTest -DfailIfNoTests=false test`
Expected: PASS(4 tests)。

- [ ] **Step 6: 提交**

```bash
git add armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java \
        armada-api/src/main/java/com/armada/shared/tenant/TenantContextInterceptor.java \
        armada-api/src/test/java/com/armada/shared/tenant/TenantContextInterceptorTest.java
git commit -m "feat(tenant): add X-Tenant-Code interceptor + TENANT_MISSING/NOT_FOUND codes"
```

---

### Task 4: 注册拦截器(WebMvcConfig)+ 集成验证

**Files:**
- Create: `armada-api/src/main/java/com/armada/boot/config/WebMvcConfig.java`
- Test: `armada-api/src/test/java/com/armada/boot/config/TenantInterceptorIntegrationTest.java`

**Interfaces:**
- Consumes: `TenantCodeResolver`(Spring bean,Task 2)、`TenantContextInterceptor`(Task 3)、现有 `GET /api/ip-proxies`。
- Produces: 拦截器挂在 `/api/**`、排除 `/api/public/**`。

- [ ] **Step 1: 写失败的集成测试**

`armada-api/src/test/java/com/armada/boot/config/TenantInterceptorIntegrationTest.java`:
```java
package com.armada.boot.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.Application;
import com.armada.shared.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 拦截器集成验证:/api/** 缺头 → TENANT_MISSING;带有效头 → code=0;/api/public/** 不被拦。
 * 走真库(只读 GET,不需事务回滚)。
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class TenantInterceptorIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void protectedEndpoint_withoutTenantHeader_returnsTenantMissing() throws Exception {
        mockMvc.perform(get("/api/ip-proxies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.TENANT_MISSING.code()));
    }

    @Test
    void protectedEndpoint_withValidTenantHeader_returnsSuccess() throws Exception {
        mockMvc.perform(get("/api/ip-proxies").header("X-Tenant-Code", "demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd armada-api && ./dbtest.sh TenantInterceptorIntegrationTest`
Expected: FAIL —— 第一个用例拿到 `code=0`(拦截器还没挂,无头也没被拦),断言 `TENANT_MISSING` 失败。

- [ ] **Step 3: 写 WebMvcConfig 注册拦截器**

`armada-api/src/main/java/com/armada/boot/config/WebMvcConfig.java`:
```java
package com.armada.boot.config;

import com.armada.platform.tenant.service.TenantCodeResolver;
import com.armada.shared.tenant.TenantContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * HTTP 横切配置:把租户上下文拦截器挂到所有业务接口 /api/**,放行公开接口 /api/public/**(登录)。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantCodeResolver tenantCodeResolver;

    public WebMvcConfig(TenantCodeResolver tenantCodeResolver) {
        this.tenantCodeResolver = tenantCodeResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TenantContextInterceptor(tenantCodeResolver))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/public/**");
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd armada-api && ./dbtest.sh TenantInterceptorIntegrationTest`
Expected: PASS(2 tests)。`without header` → `$.code == 40101`;`with demo` → `$.code == 0`。
(若 `with demo` 不是 0,检查 `GET /api/ip-proxies` 是否对空结果也返回 `ApiResponse.ok`。)

- [ ] **Step 5: 提交**

```bash
git add armada-api/src/main/java/com/armada/boot/config/WebMvcConfig.java \
        armada-api/src/test/java/com/armada/boot/config/TenantInterceptorIntegrationTest.java
git commit -m "feat(tenant): register X-Tenant-Code interceptor on /api/** except /api/public/**"
```

---

### Task 5: 极简登录(配置密码 + tenant 表校验)

**Files:**
- Modify: `armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java`(加 `LOGIN_FAILED`)
- Create: `armada-api/src/main/java/com/armada/platform/tenant/model/dto/TenantLoginRequest.java`
- Create: `armada-api/src/main/java/com/armada/platform/tenant/model/vo/TenantLoginVO.java`
- Create: `armada-api/src/main/java/com/armada/platform/tenant/service/TenantAuthService.java`
- Create: `armada-api/src/main/java/com/armada/platform/tenant/service/impl/TenantAuthServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/platform/tenant/controller/TenantAuthController.java`
- Modify: `armada-api/src/main/resources/application.yml`(加 `armada.dev-login.password`)
- Test: `armada-api/src/test/java/com/armada/platform/tenant/service/TenantAuthServiceImplDbTest.java`

**Interfaces:**
- Consumes: `TenantMapper.selectActiveByCode`(Task 1)、`BusinessException`/`ErrorCode`。
- Produces: `POST /api/public/auth/login` 收 `TenantLoginRequest{tenantCode,password}`,返 `ApiResponse<TenantLoginVO{tenantCode,tenantName,token}>`;失败抛 `ErrorCode.LOGIN_FAILED`(统一提示)。

- [ ] **Step 1: 加 LOGIN_FAILED 错误码**

Modify `armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java` —— 把 `TENANT_NOT_FOUND(40102, "租户不存在或已停用");` 末尾分号改逗号并追加:
```java
    /** 租户码无效或租户已停用。 */
    TENANT_NOT_FOUND(40102, "租户不存在或已停用"),

    /** 登录校验失败(租户码或密码错误,统一提示不暴露细节)。 */
    LOGIN_FAILED(40103, "租户码或密码错误");
```

- [ ] **Step 2: 写请求/响应模型**

`armada-api/src/main/java/com/armada/platform/tenant/model/dto/TenantLoginRequest.java`:
```java
package com.armada.platform.tenant.model.dto;

/** 租户登录入参。 */
public record TenantLoginRequest(String tenantCode, String password) {}
```

`armada-api/src/main/java/com/armada/platform/tenant/model/vo/TenantLoginVO.java`:
```java
package com.armada.platform.tenant.model.vo;

/**
 * 登录出参。{@code token} 为占位串(满足前端路由守卫),先测阶段非真鉴权凭据;前端后续以
 * {@code tenantCode} 作 X-Tenant-Code 头。
 */
public record TenantLoginVO(String tenantCode, String tenantName, String token) {}
```

- [ ] **Step 3: 写服务接口**

`armada-api/src/main/java/com/armada/platform/tenant/service/TenantAuthService.java`:
```java
package com.armada.platform.tenant.service;

import com.armada.platform.tenant.model.dto.TenantLoginRequest;
import com.armada.platform.tenant.model.vo.TenantLoginVO;
import com.armada.shared.exception.BusinessException;

/** 极简租户登录(先测,无 JWT)。 */
public interface TenantAuthService {

    /** @throws BusinessException 租户码或密码错误(ErrorCode.LOGIN_FAILED) */
    TenantLoginVO login(TenantLoginRequest request);
}
```

- [ ] **Step 4: 写失败测试**

`armada-api/src/test/java/com/armada/platform/tenant/service/TenantAuthServiceImplDbTest.java`:
```java
package com.armada.platform.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.platform.tenant.model.dto.TenantLoginRequest;
import com.armada.platform.tenant.model.vo.TenantLoginVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** 登录服务真库测试:密码对+租户在→成功;密码错/租户不存在→统一 LOGIN_FAILED。 */
@TestPropertySource(properties = "armada.dev-login.password=armada123")
class TenantAuthServiceImplDbTest extends DbTestBase {

    @Autowired private TenantAuthService authService;

    @Test
    void login_ok_returnsTenantAndPlaceholderToken() {
        TenantLoginVO vo = authService.login(new TenantLoginRequest("demo", "armada123"));
        assertThat(vo.tenantCode()).isEqualTo("demo");
        assertThat(vo.tenantName()).isEqualTo("演示租户A");
        assertThat(vo.token()).isNotBlank();
    }

    @Test
    void login_wrongPassword_fails() {
        assertThatThrownBy(() -> authService.login(new TenantLoginRequest("demo", "bad")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED.code());
    }

    @Test
    void login_unknownTenant_fails() {
        assertThatThrownBy(() -> authService.login(new TenantLoginRequest("nope", "armada123")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED.code());
    }
}
```

- [ ] **Step 5: 跑测试确认失败**

Run: `cd armada-api && ./dbtest.sh TenantAuthServiceImplDbTest`
Expected: FAIL —— 无 `TenantAuthService` 实现 bean。

- [ ] **Step 6: 写服务实现 + 控制器 + 配置**

`armada-api/src/main/java/com/armada/platform/tenant/service/impl/TenantAuthServiceImpl.java`:
```java
package com.armada.platform.tenant.service.impl;

import com.armada.platform.tenant.mapper.TenantMapper;
import com.armada.platform.tenant.model.dto.TenantLoginRequest;
import com.armada.platform.tenant.model.entity.Tenant;
import com.armada.platform.tenant.model.vo.TenantLoginVO;
import com.armada.platform.tenant.service.TenantAuthService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 极简登录:校验「密码==配置 armada.dev-login.password」且「租户码在 tenant 表且启用」。
 * 任一不满足统一抛 LOGIN_FAILED(不暴露是码错还是密码错)。无 JWT,token 为占位串。
 */
@Service
public class TenantAuthServiceImpl implements TenantAuthService {

    private static final Logger log = LoggerFactory.getLogger(TenantAuthServiceImpl.class);

    private final TenantMapper tenantMapper;
    private final String devPassword;

    public TenantAuthServiceImpl(
            TenantMapper tenantMapper,
            @Value("${armada.dev-login.password:}") String devPassword) {
        this.tenantMapper = tenantMapper;
        this.devPassword = devPassword;
    }

    @Override
    public TenantLoginVO login(TenantLoginRequest request) {
        if (request == null || request.tenantCode() == null || request.password() == null) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        // 配置未设密码(空)则一律拒绝,避免空密码登录。
        if (devPassword == null || devPassword.isBlank() || !devPassword.equals(request.password())) {
            log.warn("login.reject reason=password tenantCode={}", request.tenantCode());
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        Tenant tenant = tenantMapper.selectActiveByCode(request.tenantCode().trim());
        if (tenant == null) {
            log.warn("login.reject reason=tenant_not_found tenantCode={}", request.tenantCode());
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        log.info("login.ok tenantId={} tenantCode={}", tenant.getId(), tenant.getTenantCode());
        return new TenantLoginVO(tenant.getTenantCode(), tenant.getName(), "dev-" + tenant.getTenantCode());
    }
}
```

`armada-api/src/main/java/com/armada/platform/tenant/controller/TenantAuthController.java`:
```java
package com.armada.platform.tenant.controller;

import com.armada.platform.tenant.model.dto.TenantLoginRequest;
import com.armada.platform.tenant.model.vo.TenantLoginVO;
import com.armada.platform.tenant.service.TenantAuthService;
import com.armada.shared.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 租户登录公开入口(免租户拦截:路径在 /api/public/** 排除名单内)。 */
@RestController
@RequestMapping("/api/public/auth")
public class TenantAuthController {

    private final TenantAuthService authService;

    public TenantAuthController(TenantAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<TenantLoginVO> login(@RequestBody TenantLoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }
}
```

Modify `armada-api/src/main/resources/application.yml` —— 在文件末尾(`server:` 块之后)追加新顶级块:
```yaml

armada:
  # 先测阶段统一登录密码(临时垫片,JWT 接上后删除);可用环境变量 DEV_LOGIN_PASSWORD 覆盖。
  dev-login:
    password: ${DEV_LOGIN_PASSWORD:armada123}
```

- [ ] **Step 7: 跑测试确认通过**

Run: `cd armada-api && ./dbtest.sh TenantAuthServiceImplDbTest`
Expected: PASS(3 tests)。

- [ ] **Step 8: 全量回归 + 提交**

Run: `cd armada-api && mvn -q test`(跑全部单测,确认未破坏既有 117 测试)
Expected: BUILD SUCCESS。
```bash
git add armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java \
        armada-api/src/main/java/com/armada/platform/tenant/model/dto/TenantLoginRequest.java \
        armada-api/src/main/java/com/armada/platform/tenant/model/vo/TenantLoginVO.java \
        armada-api/src/main/java/com/armada/platform/tenant/service/TenantAuthService.java \
        armada-api/src/main/java/com/armada/platform/tenant/service/impl/TenantAuthServiceImpl.java \
        armada-api/src/main/java/com/armada/platform/tenant/controller/TenantAuthController.java \
        armada-api/src/main/resources/application.yml \
        armada-api/src/test/java/com/armada/platform/tenant/service/TenantAuthServiceImplDbTest.java
git commit -m "feat(tenant): add minimal dev login POST /api/public/auth/login"
```

---

### Task 6: 前端 — vite dev 代理到后端

**Files:**
- Modify: `wheel-saas-pure-web/vite.config.ts`(`server.proxy`)

**Interfaces:**
- Produces: 本地 `npm run dev` 时 `/api/**` 转发到 `http://127.0.0.1:8080`。

- [ ] **Step 1: 加代理**

Modify `wheel-saas-pure-web/vite.config.ts` —— 把 `proxy: {},` 改为:
```ts
      // 本地开发跨域代理:/api 转发到本地 armada 后端(仅 npm run dev 生效;测试环境由 nginx 同源反代)
      proxy: {
        "/api": {
          target: "http://127.0.0.1:8080",
          changeOrigin: true
        }
      },
```
(不加 `rewrite`:armada 接口本身就在 `/api/...` 下,路径透传。)

- [ ] **Step 2: 验证代理生效**

前置:本地已起 armada 后端(`cd armada-api && mvn spring-boot:run`,或 IDE 跑 `Application`,确保连到本机 MySQL `armada` 库)。
Run: `cd wheel-saas-pure-web && npm run dev`,另开终端:
```bash
curl -i -X POST http://127.0.0.1:8848/api/public/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"tenantCode":"demo","password":"armada123"}'
```
Expected: HTTP 200,body `{"code":0,"message":"ok","data":{"tenantCode":"demo","tenantName":"演示租户A","token":"dev-demo"}}`(经 vite 代理打到 8080)。

- [ ] **Step 3: 提交**

```bash
git add wheel-saas-pure-web/vite.config.ts
git commit -m "chore(dev): proxy /api to local armada backend for npm run dev"
```

---

### Task 7: 前端 — armada 信封拆包 helper + X-Tenant-Code 头 + 租户码存储

**Files:**
- Create: `wheel-saas-pure-web/src/api/armada.ts`
- Modify: `wheel-saas-pure-web/src/utils/auth.ts`(加租户码存取)
- Modify: `wheel-saas-pure-web/src/utils/http/index.ts`(请求拦截器注入 X-Tenant-Code)

**Interfaces:**
- Produces: `armadaRequest<T>(method, url, opts?) → Promise<T>`(按 `code===0` 拆 `data`,非 0 throw `Error(message)`);`getTenantCode()/setTenantCode(code)/removeTenantCode()`;请求自动带 `X-Tenant-Code`(存在时)。
- Consumes: 现有 `http`(PureHttp)、`storageLocal`。

- [ ] **Step 1: 写 armada 拆包 helper**

`wheel-saas-pure-web/src/api/armada.ts`:
```ts
import { http } from "@/utils/http";
import type { AxiosRequestConfig } from "axios";
import type { RequestMethods } from "@/utils/http/types.d";

/** armada 统一响应信封。code=0 成功,非 0 业务错误(HTTP 仍 200)。 */
export interface ArmadaResp<T> {
  code: number;
  message: string;
  data: T;
}

/**
 * 调 armada 接口并按信封拆包:code===0 返回 data,否则抛 Error(message)。
 * 业务页/登录统一用它,避免每处手写 code 判定。
 */
export async function armadaRequest<T>(
  method: RequestMethods,
  url: string,
  opts?: AxiosRequestConfig
): Promise<T> {
  const resp = await http.request<ArmadaResp<T>>(method, url, opts);
  if (!resp || resp.code !== 0) {
    throw new Error(resp?.message ?? "请求失败");
  }
  return resp.data;
}
```

- [ ] **Step 2: 加租户码存取(auth.ts)**

Modify `wheel-saas-pure-web/src/utils/auth.ts` —— 在 `multipleTabsKey` 声明之后追加:
```ts
/** 当前登录租户码(先测阶段作 X-Tenant-Code 头;接 JWT 后由 token 携带,可移除)。 */
export const tenantCodeKey = "tenant-code";

/** 取当前租户码;无则空串。 */
export function getTenantCode(): string {
  return storageLocal().getItem<string>(tenantCodeKey) ?? "";
}

/** 存当前租户码。 */
export function setTenantCode(code: string) {
  storageLocal().setItem(tenantCodeKey, code);
}

/** 清除租户码。 */
export function removeTenantCode() {
  storageLocal().removeItem(tenantCodeKey);
}
```

- [ ] **Step 3: 请求拦截器注入 X-Tenant-Code**

Modify `wheel-saas-pure-web/src/utils/http/index.ts`:
首部 import 处,在 `import { getToken, formatToken } from "@/utils/auth";` 下加:
```ts
import { getTenantCode } from "@/utils/auth";
```
然后在 `httpInterceptorsRequest` 的请求拦截回调里,**在 `if (typeof config.beforeRequestCallback === "function")` 之前**(确保所有请求含登录都先尝试带头)插入:
```ts
        // 注入租户头(存在才带;登录前无租户码,自然不带)
        const tenantCode = getTenantCode();
        if (tenantCode) {
          config.headers["X-Tenant-Code"] = tenantCode;
        }
```

- [ ] **Step 4: 类型检查**

Run: `cd wheel-saas-pure-web && npm run typecheck`
(若无该脚本则 `npx vue-tsc --noEmit`)
Expected: 无类型错误。

- [ ] **Step 5: 提交**

```bash
git add wheel-saas-pure-web/src/api/armada.ts \
        wheel-saas-pure-web/src/utils/auth.ts \
        wheel-saas-pure-web/src/utils/http/index.ts
git commit -m "feat(web): add armada envelope unwrap helper + X-Tenant-Code header + tenant code storage"
```

---

### Task 8: 前端 — 真登录接口 + store + 登录页,端到端手验

**Files:**
- Create: `wheel-saas-pure-web/src/api/auth.ts`
- Modify: `wheel-saas-pure-web/src/store/modules/user.ts`(`loginByUsername`、`logOut`)
- Modify: `wheel-saas-pure-web/src/views/login/index.vue`(表单字段 + 失败提示)

**Interfaces:**
- Consumes: `armadaRequest`(Task 7)、`setTenantCode/removeTenantCode`(Task 7)、现有 `setToken/removeToken`。
- Produces: 登录页用「租户码 + 密码」登录,成功存租户码 + 占位 token,进入系统。

- [ ] **Step 1: 写 auth api**

`wheel-saas-pure-web/src/api/auth.ts`:
```ts
import { armadaRequest } from "./armada";

/** armada 登录出参。token 为占位串(先测,无 JWT)。 */
export interface TenantLoginResult {
  tenantCode: string;
  tenantName: string;
  token: string;
}

/** 租户登录(免鉴权公开接口)。 */
export const loginTenant = (data: { tenantCode: string; password: string }) =>
  armadaRequest<TenantLoginResult>("post", "/api/public/auth/login", { data });
```

- [ ] **Step 2: 改 user store 的 loginByUsername / logOut**

Modify `wheel-saas-pure-web/src/store/modules/user.ts`:
顶部 import 区,把
```ts
import { type DataInfo, setToken, removeToken, userKey } from "@/utils/auth";
```
改为:
```ts
import {
  type DataInfo,
  setToken,
  removeToken,
  userKey,
  setTenantCode,
  removeTenantCode
} from "@/utils/auth";
import { loginTenant } from "@/api/auth";
```
把整个 `loginByUsername` action 替换为(入参 `username` 当租户码;失败 resolve `{success:false}` 以复用登录页判定):
```ts
    /** 登入(先测:username 即租户码,密码为统一测试密码) */
    async loginByUsername(data) {
      return new Promise<any>(resolve => {
        loginTenant({ tenantCode: data.username, password: data.password })
          .then(res => {
            // res = { tenantCode, tenantName, token };先测无 JWT,构造占位会话满足 pure-admin 机制。
            setTenantCode(res.tenantCode);
            setToken({
              accessToken: res.token,
              refreshToken: res.token,
              // 占位过期时间:7 天后(pure-admin 用 Date 解析)
              expires: new Date(Date.now() + 7 * 24 * 60 * 60 * 1000),
              username: res.tenantName,
              roles: ["admin"],
              permissions: ["*:*:*"]
            } as any);
            resolve({ success: true, data: res });
          })
          .catch(err => {
            resolve({ success: false, message: err?.message ?? "登录失败" });
          });
      });
    },
```
把 `logOut` action 里 `removeToken();` 一行替换为:
```ts
      removeToken();
      removeTenantCode();
```

- [ ] **Step 3: 改登录页字段 + 失败提示**

Modify `wheel-saas-pure-web/src/views/login/index.vue`:
把 `const ruleForm = reactive({ username: "admin", password: "admin123" });` 改为:
```ts
const ruleForm = reactive({
  username: "demo", // 租户码
  password: ""
});
```
把账号输入框的 `placeholder="账号"` 改为 `placeholder="租户码"`,其 `el-form-item` 校验消息 `message: '请输入账号'` 改为 `message: '请输入租户码'`。
把 `onLogin` 里失败分支
```ts
          } else {
            message("登录失败", { type: "error" });
          }
```
改为(用后端返回的提示):
```ts
          } else {
            message(res.message ?? "登录失败", { type: "error" });
          }
```

- [ ] **Step 4: 类型检查**

Run: `cd wheel-saas-pure-web && npm run typecheck`(或 `npx vue-tsc --noEmit`)
Expected: 无类型错误。

- [ ] **Step 5: 端到端手验(本地浏览器)**

前置:armada 后端在本地 8080 跑、连本机 MySQL `armada` 库(Flyway 已建 tenant 表 + seed);`npm run dev` 起前端。
1. 浏览器开 `http://127.0.0.1:8848`,登录页输 **租户码 `demo` / 密码 `armada123`** → 点登录。
   Expected: 提示"登录成功",进入系统(welcome 页)。
2. 打开浏览器 DevTools → Network,触发任一业务请求(或手动)`GET /api/ip-proxies`。
   Expected: 该请求带 `X-Tenant-Code: demo` 头,响应 `{"code":0,...}`。
3. 输错密码登录 → 提示"租户码或密码错误"(后端 message)。
4. 登出 → 回登录页;localStorage 的 `tenant-code` 被清。
(说明:`/api/public/auth/login` 不带租户头也能调通;业务接口 `/api/**` 不带头会收到 `code=40101`。)

- [ ] **Step 6: 提交**

```bash
git add wheel-saas-pure-web/src/api/auth.ts \
        wheel-saas-pure-web/src/store/modules/user.ts \
        wheel-saas-pure-web/src/views/login/index.vue
git commit -m "feat(web): wire real tenant login (tenant code + password), store tenant code + placeholder session"
```

---

## 完成定义

- 后端:`mvn test` 全绿(新增 ~12 个测试 + 既有回归);拦截器集成测试证明 `/api/**` 缺头 `code=40101`、带 `demo` 头 `code=0`、`/api/public/**` 放行。
- 前端:`vue-tsc` 净;本地浏览器用 `demo/armada123` 能登录进系统,业务请求自动带 `X-Tenant-Code`。
- 不在范围(后续):IP/导入链接/营销三页对接(IP 先);nginx/compose 部署(另起部署 agent);JWT/用户表/account-IAM(后置)。
