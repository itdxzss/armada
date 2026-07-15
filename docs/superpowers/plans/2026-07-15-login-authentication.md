# Armada User Login Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Armada's tenant-code development login with username/password/image-captcha authentication backed by Spring Security and a Redis-enforced single session.

**Architecture:** A global login lookup resolves a user and its single tenant, BCrypt verifies the password, and Redis stores one opaque Bearer session per user. Spring Security restores an `AuthPrincipal` from Redis before MVC; the existing tenant interceptor then derives `TenantContext` exclusively from that principal, preserving MyBatis tenant isolation without trusting `X-Tenant-Code`.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Spring Security, Spring Data Redis/Lettuce, MyBatis-Plus 3.5.7, MySQL 8, Flyway, JUnit 5, Mockito, AssertJ, MockMvc, Testcontainers Redis.

**Design spec:** `docs/superpowers/specs/2026-07-15-login-authentication-design.md`

---

## Scope and execution notes

- This plan changes `armada/armada-api` only. The Vue login page is a separate follow-up.
- Run shell commands from `armada-api/` unless a command explicitly starts with `git` and therefore runs from the `armada/` repository root.
- Use TDD for every behavior: add the focused failing test, observe the intended failure, implement only that slice, rerun the focused test, then commit.
- Execute this plan in an isolated worktree created with `using-git-worktrees`; do not disturb the existing `.claude/worktrees` entries in the main checkout.
- Redis integration tests use `redis:7.2-alpine` through Testcontainers and therefore require a working Docker daemon.
- MySQL/Flyway tests continue to run through `armada-api/dbtest.sh`, which reads the gitignored `armada-api/.env`.

## File map

### Build and configuration

- Modify `armada-api/pom.xml`: add Security, Validation, Redis, Security Test, and Testcontainers dependencies.
- Modify `armada-api/src/main/resources/application.yml`: add Redis and auth settings; later remove `armada.dev-login`.
- Create `armada-api/src/main/java/com/armada/platform/auth/config/AuthProperties.java`: typed auth durations and limits.
- Create `armada-api/src/main/java/com/armada/boot/config/AuthConfiguration.java`: `PasswordEncoder`, `Clock`, and `SecureRandom` beans.
- Create `armada-api/src/test/java/com/armada/platform/auth/config/AuthPropertiesTest.java`: binding/default tests.

### User persistence

- Create `armada-api/src/main/resources/db/migration/V055__sys_user_authentication.sql`: `sys_user` and seed admin.
- Create `armada-api/src/main/java/com/armada/platform/user/model/LoginUserRow.java`: login-only user persistence row.
- Create `armada-api/src/main/java/com/armada/platform/user/model/UserRole.java`: `ADMIN`/`USER` enum and authorities.
- Create `armada-api/src/main/java/com/armada/platform/user/mapper/SysUserMapper.java`: global exact login lookup and guarded last-login update.
- Create `armada-api/src/main/resources/mapper/platform/user/SysUserMapper.xml`: SQL for those methods.
- Modify `armada-api/src/main/java/com/armada/platform/tenant/mapper/TenantMapper.java`: replace code lookup with exact ID lookup.
- Modify `armada-api/src/main/resources/mapper/platform/TenantMapper.xml`: fetch tenant by ID without hiding disabled tenants.
- Create `armada-api/src/test/java/com/armada/platform/user/mapper/SysUserSchemaDbTest.java`: schema/seed assertions.
- Create `armada-api/src/test/java/com/armada/platform/user/mapper/SysUserMapperDbTest.java`: login lookup and guarded update assertions.
- Modify `armada-api/src/test/java/com/armada/platform/tenant/mapper/TenantMapperDbTest.java`: test ID lookup and status visibility.

### Captcha and rate limiting

- Create `armada-api/src/main/java/com/armada/platform/auth/captcha/CaptchaChallenge.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/captcha/CaptchaStore.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/captcha/RedisCaptchaStore.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/captcha/CaptchaService.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/captcha/DefaultCaptchaService.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/exception/AuthServiceUnavailableException.java`: common fail-closed Redis exception.
- Create `armada-api/src/test/java/com/armada/platform/auth/captcha/DefaultCaptchaServiceTest.java`.
- Create `armada-api/src/test/java/com/armada/platform/auth/captcha/RedisCaptchaStoreTest.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/ratelimit/LoginRateLimiter.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/ratelimit/RedisLoginRateLimiter.java`.
- Create `armada-api/src/test/java/com/armada/platform/auth/ratelimit/RedisLoginRateLimiterTest.java`.

### Redis sessions

- Create `armada-api/src/main/java/com/armada/platform/auth/session/LoginIdentity.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/session/AuthSession.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/session/IssuedSession.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/session/SessionRepository.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/session/RedisSessionRepository.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/session/SessionTokenCodec.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/session/SessionService.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/session/DefaultSessionService.java`.
- Create `armada-api/src/test/java/com/armada/platform/auth/session/SessionTokenCodecTest.java`.
- Create `armada-api/src/test/java/com/armada/platform/auth/session/DefaultSessionServiceTest.java`.
- Create `armada-api/src/test/java/com/armada/platform/auth/session/RedisSessionRepositoryTest.java`.
- Create `armada-api/src/test/java/com/armada/testsupport/RedisIntegrationTestBase.java`: disposable Redis support shared by Redis tests.

### Authentication application service and API

- Create `armada-api/src/main/java/com/armada/platform/auth/model/LoginRequest.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/model/UserIdentityVO.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/model/TenantIdentityVO.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/model/LoginVO.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/model/CurrentUserVO.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/model/AuthPrincipal.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/service/AuthService.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/service/DefaultAuthService.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/exception/LoginRateLimitException.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/web/AuthController.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/web/BearerTokenParser.java`.
- Create `armada-api/src/test/java/com/armada/platform/auth/service/DefaultAuthServiceTest.java`.
- Create `armada-api/src/test/java/com/armada/platform/auth/web/AuthControllerTest.java`.
- Modify `armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java`: fixed auth error codes/messages.
- Modify `armada-api/src/main/java/com/armada/boot/web/GlobalExceptionHandler.java`: 429/503/validation responses.
- Create `armada-api/src/test/java/com/armada/boot/web/GlobalExceptionHandlerTest.java`.

### Spring Security and tenant context

- Create `armada-api/src/main/java/com/armada/platform/auth/security/ApiSecurityErrorWriter.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/security/ApiAuthenticationEntryPoint.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/security/ApiAccessDeniedHandler.java`.
- Create `armada-api/src/main/java/com/armada/platform/auth/security/RedisTokenAuthenticationFilter.java`.
- Create `armada-api/src/main/java/com/armada/boot/config/SecurityConfig.java`.
- Create `armada-api/src/test/java/com/armada/platform/auth/security/SecurityConfigTest.java`.
- Modify `armada-api/src/main/java/com/armada/shared/tenant/TenantContextInterceptor.java`: principal-derived tenant only.
- Modify `armada-api/src/main/java/com/armada/boot/config/WebMvcConfig.java`: no tenant-code resolver; exact public exclusions.
- Modify `armada-api/src/test/java/com/armada/shared/tenant/TenantContextInterceptorTest.java`.
- Replace `armada-api/src/test/java/com/armada/boot/config/TenantInterceptorIntegrationTest.java` with token-based assertions.
- Create `armada-api/src/test/java/com/armada/testsupport/TestAuthentication.java` and `TestAuthenticationConfiguration.java`: test-only Bearer sessions for existing MockMvc DB tests.
- Modify the five existing DB controller tests that currently send `X-Tenant-Code` to use `TestAuthentication.bearer(tenantId)`.

### Legacy removal

- Delete `armada-api/src/main/java/com/armada/platform/tenant/controller/TenantAuthController.java`.
- Delete `armada-api/src/main/java/com/armada/platform/tenant/model/dto/TenantLoginRequest.java`.
- Delete `armada-api/src/main/java/com/armada/platform/tenant/model/vo/TenantLoginVO.java`.
- Delete `armada-api/src/main/java/com/armada/platform/tenant/service/TenantAuthService.java`.
- Delete `armada-api/src/main/java/com/armada/platform/tenant/service/impl/TenantAuthServiceImpl.java`.
- Delete `armada-api/src/main/java/com/armada/platform/tenant/service/TenantCodeResolver.java`.
- Delete `armada-api/src/main/java/com/armada/platform/tenant/service/impl/TenantCodeResolverImpl.java`.
- Delete the matching obsolete tenant auth/resolver tests.

---

### Task 1: Add auth dependencies and typed configuration

**Files:**
- Modify: `armada-api/pom.xml`
- Modify: `armada-api/src/main/resources/application.yml`
- Create: `armada-api/src/main/java/com/armada/platform/auth/config/AuthProperties.java`
- Create: `armada-api/src/main/java/com/armada/boot/config/AuthConfiguration.java`
- Test: `armada-api/src/test/java/com/armada/platform/auth/config/AuthPropertiesTest.java`

- [ ] **Step 1: Write the failing property test**

Create a binder test with both defaults and overrides:

```java
class AuthPropertiesTest {

    @Test
    void defaultsMatchApprovedDesign() {
        AuthProperties p = new AuthProperties();
        assertThat(p.getCaptchaTtl()).isEqualTo(Duration.ofMinutes(2));
        assertThat(p.getSessionIdleTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.getSessionMaxLifetime()).isEqualTo(Duration.ofHours(24));
        assertThat(p.getLoginFailureWindow()).isEqualTo(Duration.ofMinutes(10));
        assertThat(p.getLoginMaxFailures()).isEqualTo(5);
        assertThat(p.getCaptchaIssueWindow()).isEqualTo(Duration.ofMinutes(1));
        assertThat(p.getCaptchaMaxIssues()).isEqualTo(20);
    }

    @Test
    void binderLoadsDurationAndCountOverrides() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "armada.auth.captcha-ttl", "90s",
                "armada.auth.session-idle-timeout", "20m",
                "armada.auth.session-max-lifetime", "12h",
                "armada.auth.login-failure-window", "8m",
                "armada.auth.login-max-failures", "4",
                "armada.auth.captcha-issue-window", "30s",
                "armada.auth.captcha-max-issues", "10")));
        AuthProperties p = Binder.get(environment)
                .bind("armada.auth", Bindable.of(AuthProperties.class))
                .orElseThrow();
        assertThat(p.getSessionIdleTimeout()).isEqualTo(Duration.ofMinutes(20));
        assertThat(p.getLoginMaxFailures()).isEqualTo(4);
        assertThat(p.getCaptchaMaxIssues()).isEqualTo(10);
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

```bash
mvn -q -Dtest=AuthPropertiesTest test
```

Expected: compilation fails because `AuthProperties` does not exist.

- [ ] **Step 3: Add managed dependencies**

Add these dependencies to `pom.xml`; rely on the Spring Boot parent for versions:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 4: Implement the configuration class and beans**

Use this mutable configuration bean so Spring's Binder and direct unit construction share the same defaults:

```java
@Validated
@ConfigurationProperties(prefix = "armada.auth")
public class AuthProperties {
    private Duration captchaTtl = Duration.ofMinutes(2);
    private Duration sessionIdleTimeout = Duration.ofMinutes(30);
    private Duration sessionMaxLifetime = Duration.ofHours(24);
    private Duration loginFailureWindow = Duration.ofMinutes(10);
    private int loginMaxFailures = 5;
    private Duration captchaIssueWindow = Duration.ofMinutes(1);
    private int captchaMaxIssues = 20;

    public Duration getCaptchaTtl() {
        return captchaTtl;
    }

    public void setCaptchaTtl(Duration captchaTtl) {
        this.captchaTtl = captchaTtl;
    }

    public Duration getSessionIdleTimeout() {
        return sessionIdleTimeout;
    }

    public void setSessionIdleTimeout(Duration sessionIdleTimeout) {
        this.sessionIdleTimeout = sessionIdleTimeout;
    }

    public Duration getSessionMaxLifetime() {
        return sessionMaxLifetime;
    }

    public void setSessionMaxLifetime(Duration sessionMaxLifetime) {
        this.sessionMaxLifetime = sessionMaxLifetime;
    }

    public Duration getLoginFailureWindow() {
        return loginFailureWindow;
    }

    public void setLoginFailureWindow(Duration loginFailureWindow) {
        this.loginFailureWindow = loginFailureWindow;
    }

    public int getLoginMaxFailures() {
        return loginMaxFailures;
    }

    public void setLoginMaxFailures(int loginMaxFailures) {
        this.loginMaxFailures = loginMaxFailures;
    }

    public Duration getCaptchaIssueWindow() {
        return captchaIssueWindow;
    }

    public void setCaptchaIssueWindow(Duration captchaIssueWindow) {
        this.captchaIssueWindow = captchaIssueWindow;
    }

    public int getCaptchaMaxIssues() {
        return captchaMaxIssues;
    }

    public void setCaptchaMaxIssues(int captchaMaxIssues) {
        this.captchaMaxIssues = captchaMaxIssues;
    }

    @AssertTrue(message = "all auth durations and limits must be positive")
    public boolean isPositiveConfiguration() {
        return isPositive(captchaTtl)
                && isPositive(sessionIdleTimeout)
                && isPositive(sessionMaxLifetime)
                && isPositive(loginFailureWindow)
                && isPositive(captchaIssueWindow)
                && loginMaxFailures > 0
                && captchaMaxIssues > 0;
    }

    private static boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
```

Add this bean configuration:

```java
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecureRandom authSecureRandom() {
        return new SecureRandom();
    }
}
```

Merge these exact Redis/auth defaults into the existing `spring` and `armada` mappings in `application.yml`; do not create duplicate top-level YAML keys:

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      database: ${REDIS_DATABASE:0}
      timeout: ${REDIS_TIMEOUT:2s}

armada:
  auth:
    captcha-ttl: 2m
    session-idle-timeout: 30m
    session-max-lifetime: 24h
    login-failure-window: 10m
    login-max-failures: 5
    captcha-issue-window: 1m
    captcha-max-issues: 20
```

- [ ] **Step 5: Run focused tests and compile**

```bash
mvn -q -Dtest=AuthPropertiesTest test
mvn -q -DskipTests compile
```

Expected: both commands exit 0; the property tests pass and all new dependencies resolve.

- [ ] **Step 6: Commit the foundation**

```bash
git add armada-api/pom.xml armada-api/src/main/resources/application.yml \
  armada-api/src/main/java/com/armada/platform/auth/config/AuthProperties.java \
  armada-api/src/main/java/com/armada/boot/config/AuthConfiguration.java \
  armada-api/src/test/java/com/armada/platform/auth/config/AuthPropertiesTest.java
git commit -m "build(auth): add security and redis foundation"
```

### Task 2: Create `sys_user`, seed admin, and global login lookup

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V055__sys_user_authentication.sql`
- Create: `armada-api/src/main/java/com/armada/platform/user/model/LoginUserRow.java`
- Create: `armada-api/src/main/java/com/armada/platform/user/model/UserRole.java`
- Create: `armada-api/src/main/java/com/armada/platform/user/mapper/SysUserMapper.java`
- Create: `armada-api/src/main/resources/mapper/platform/user/SysUserMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/platform/tenant/mapper/TenantMapper.java`
- Modify: `armada-api/src/main/resources/mapper/platform/TenantMapper.xml`
- Test: `armada-api/src/test/java/com/armada/platform/user/mapper/SysUserSchemaDbTest.java`
- Test: `armada-api/src/test/java/com/armada/platform/user/mapper/SysUserMapperDbTest.java`
- Modify test: `armada-api/src/test/java/com/armada/platform/tenant/mapper/TenantMapperDbTest.java`

- [ ] **Step 1: Write failing schema and mapper DB tests**

Assert the approved invariants:

```java
@Test
void sysUserSchema_hasIdentityConstraintsAndNoDeleteColumn() {
    assertThat(columnNames("sys_user")).contains(
            "id", "tenant_id", "username", "password_hash", "display_name",
            "role", "status", "last_login_at", "created_at", "updated_at");
    assertThat(columnNames("sys_user")).doesNotContain("deleted_at");
    assertThat(uniqueIndexColumns("sys_user", "uq_sys_user_username"))
            .containsExactly("username");
}

@Test
void seededAdmin_isTenantOneAdminAndPasswordIsBcrypt() {
    Map<String, Object> row = jdbc.queryForMap("""
            SELECT tenant_id, username, password_hash, role, status
            FROM sys_user WHERE username = 'admin'
            """);
    assertThat(row.get("tenant_id")).isEqualTo(1L);
    assertThat(row.get("role")).isEqualTo("ADMIN");
    assertThat(row.get("status")).isEqualTo(1);
    assertThat((String) row.get("password_hash")).startsWith("{bcrypt}");
    assertThat(passwordEncoder.matches("armada123", (String) row.get("password_hash"))).isTrue();
}

private List<String> columnNames(String tableName) {
    return jdbc.queryForList("""
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = ?
            ORDER BY ordinal_position
            """, String.class, tableName);
}

private List<String> uniqueIndexColumns(String tableName, String indexName) {
    return jdbc.queryForList("""
            SELECT column_name
            FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
            ORDER BY seq_in_index
            """, String.class, tableName, indexName);
}
```

In `SysUserMapperDbTest`, clear the inherited tenant context before the login lookup and restore it in `finally`:

```java
@Test
void selectForLoginByUsername_worksWithoutTenantContext() {
    TenantContext.clear();
    try {
        LoginUserRow row = mapper.selectForLoginByUsername("admin");
        assertThat(row.getTenantId()).isEqualTo(1L);
        assertThat(row.getRole()).isEqualTo(UserRole.ADMIN);
    } finally {
        TenantContext.set(TEST_TENANT_ID);
    }
}

@Test
void updateLastLogin_requiresBothUserAndTenant() {
    LoginUserRow row = mapper.selectForLoginByUsername("admin");
    LocalDateTime now = LocalDateTime.of(2026, 7, 15, 12, 0);
    assertThat(mapper.updateLastLoginAtForAuthenticatedUser(row.getId(), 999L, now)).isZero();
    assertThat(mapper.updateLastLoginAtForAuthenticatedUser(row.getId(), 1L, now)).isOne();
}
```

Add constraint coverage to `SysUserSchemaDbTest`:

```java
@Test
void sysUserSchema_rejectsDuplicateUsernameInvalidRoleAndInvalidStatus() {
    assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO sys_user
                (tenant_id, username, password_hash, display_name, role, status)
            VALUES (1, 'admin', '{bcrypt}unused', '重复用户', 'USER', 1)
            """))
            .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO sys_user
                (tenant_id, username, password_hash, display_name, role, status)
            VALUES (1, 'invalid-role', '{bcrypt}unused', '非法角色', 'OWNER', 1)
            """))
            .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO sys_user
                (tenant_id, username, password_hash, display_name, role, status)
            VALUES (1, 'invalid-status', '{bcrypt}unused', '非法状态', 'USER', 2)
            """))
            .isInstanceOf(DataIntegrityViolationException.class);
}
```

Replace the old code-based tests in `TenantMapperDbTest` with exact ID behavior, including a disabled tenant that authentication must still see and reject itself:

```java
@Test
void selectById_returnsSeededTenant() {
    Tenant tenant = tenantMapper.selectById(1L);
    assertThat(tenant).isNotNull();
    assertThat(tenant.getTenantCode()).isEqualTo("demo");
    assertThat(tenant.getStatus()).isEqualTo(1);
}

@Test
void selectById_returnsDisabledTenantWithoutHidingStatus() {
    jdbc.update("UPDATE tenant SET status = 0 WHERE id = 1");
    assertThat(tenantMapper.selectById(1L).getStatus()).isZero();
}

@Test
void selectById_unknownId_returnsNull() {
    assertThat(tenantMapper.selectById(Long.MAX_VALUE)).isNull();
}
```

- [ ] **Step 2: Run DB tests and verify RED**

```bash
./dbtest.sh 'SysUserSchemaDbTest,SysUserMapperDbTest,TenantMapperDbTest'
```

Expected: FAIL because `sys_user`, its mapper, and `TenantMapper.selectById` do not exist.

- [ ] **Step 3: Add Flyway V055**

Use this migration exactly; the committed value is a BCrypt strength-10 hash of `armada123`, not plaintext:

```sql
CREATE TABLE sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户主键',
    tenant_id     BIGINT       NOT NULL                COMMENT '所属租户ID',
    username      VARCHAR(64)  NOT NULL                COMMENT '全平台唯一登录名,规范化为小写',
    password_hash VARCHAR(255) NOT NULL                COMMENT 'DelegatingPasswordEncoder单向哈希',
    display_name  VARCHAR(128) NOT NULL                COMMENT '展示名称',
    role          VARCHAR(16)  NOT NULL                COMMENT '租户内角色:ADMIN/USER',
    status        TINYINT      NOT NULL DEFAULT 1      COMMENT '状态:1=启用 0=停用',
    last_login_at DATETIME     DEFAULT NULL            COMMENT '最近成功登录时间',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sys_user_username (username),
    KEY idx_sys_user_tenant_status (tenant_id, status),
    CONSTRAINT chk_sys_user_role CHECK (role IN ('ADMIN', 'USER')),
    CONSTRAINT chk_sys_user_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Armada租户用户';

INSERT INTO sys_user
    (tenant_id, username, password_hash, display_name, role, status)
VALUES
    (1, 'admin', '{bcrypt}$2y$10$prdOI2HhX2KU/5LbbnA0x.01YEtzOAxvfRN7aZxk292bOuw3yH2Cu',
     '管理员', 'ADMIN', 1);
```

- [ ] **Step 4: Implement mapper contracts**

`UserRole` exposes the Spring authority without accepting arbitrary strings:

```java
public enum UserRole {
    ADMIN,
    USER;

    public String authority() {
        return "ROLE_" + name();
    }
}
```

`LoginUserRow` is a normal Java bean with `Long id`, `Long tenantId`, `String username`, `String passwordHash`, `String displayName`, `UserRole role`, `Integer status`, and `LocalDateTime lastLoginAt` plus getters/setters.

The mapper methods are the only user operations that bypass tenant injection:

```java
@Mapper
public interface SysUserMapper {

    @InterceptorIgnore(tenantLine = "true")
    LoginUserRow selectForLoginByUsername(@Param("username") String username);

    @InterceptorIgnore(tenantLine = "true")
    int updateLastLoginAtForAuthenticatedUser(
            @Param("userId") Long userId,
            @Param("tenantId") Long tenantId,
            @Param("lastLoginAt") LocalDateTime lastLoginAt);
}
```

`SysUserMapper.xml` uses exact predicates and no list query:

```xml
<select id="selectForLoginByUsername" resultType="com.armada.platform.user.model.LoginUserRow">
    SELECT id, tenant_id, username, password_hash, display_name, role, status, last_login_at
    FROM sys_user
    WHERE username = #{username}
    LIMIT 1
</select>

<update id="updateLastLoginAtForAuthenticatedUser">
    UPDATE sys_user
    SET last_login_at = #{lastLoginAt}
    WHERE id = #{userId} AND tenant_id = #{tenantId}
</update>
```

Replace the tenant-code mapper method with:

```java
Tenant selectById(@Param("id") Long id);
```

Replace its XML statement with an ID lookup that deliberately has no status predicate. The authentication service, not the mapper, decides whether status `0` may log in:

```xml
<select id="selectById" resultType="com.armada.platform.tenant.model.entity.Tenant">
    SELECT id, tenant_code, name, status, created_at, updated_at
    FROM tenant
    WHERE id = #{id}
    LIMIT 1
</select>
```

- [ ] **Step 5: Run DB tests and verify GREEN**

```bash
./dbtest.sh 'SysUserSchemaDbTest,SysUserMapperDbTest,TenantMapperDbTest'
```

Expected: PASS; the seed password matches through `DelegatingPasswordEncoder`, no `deleted_at` exists, and the global mapper only updates the authenticated `(userId, tenantId)` pair.

- [ ] **Step 6: Commit schema and persistence**

```bash
git add armada-api/src/main/resources/db/migration/V055__sys_user_authentication.sql \
  armada-api/src/main/java/com/armada/platform/user \
  armada-api/src/main/resources/mapper/platform/user \
  armada-api/src/main/java/com/armada/platform/tenant/mapper/TenantMapper.java \
  armada-api/src/main/resources/mapper/platform/TenantMapper.xml \
  armada-api/src/test/java/com/armada/platform/user \
  armada-api/src/test/java/com/armada/platform/tenant/mapper/TenantMapperDbTest.java
git commit -m "feat(auth): add tenant user identity model"
```

### Task 3: Implement one-time Redis image captchas

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/auth/captcha/CaptchaChallenge.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/captcha/CaptchaStore.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/captcha/RedisCaptchaStore.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/captcha/CaptchaService.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/captcha/DefaultCaptchaService.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/exception/AuthServiceUnavailableException.java`
- Test: `armada-api/src/test/java/com/armada/platform/auth/captcha/DefaultCaptchaServiceTest.java`
- Test: `armada-api/src/test/java/com/armada/platform/auth/captcha/RedisCaptchaStoreTest.java`
- Create test support: `armada-api/src/test/java/com/armada/testsupport/RedisIntegrationTestBase.java`

- [ ] **Step 1: Write failing service tests**

Use a mocked `CaptchaStore` and captured answer:

```java
@Test
void create_returnsPngDataUrlAndStoresFourAllowedCharacters() {
    CaptchaChallenge challenge = service.create();
    ArgumentCaptor<String> answer = ArgumentCaptor.forClass(String.class);
    verify(store).save(eq(challenge.captchaId()), answer.capture(), eq(Duration.ofMinutes(2)));
    assertThat(answer.getValue()).matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}");
    assertThat(challenge.imageBase64()).startsWith("data:image/png;base64,");
    byte[] png = Base64.getDecoder().decode(
            challenge.imageBase64().substring("data:image/png;base64,".length()));
    assertThat(png).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
    assertThat(challenge.expiresInSeconds()).isEqualTo(120);
}

@Test
void verify_isCaseInsensitiveAndConsumesExactlyOnce() {
    when(store.consume("cap-1")).thenReturn(Optional.of("A7KD"), Optional.empty());
    assertThatCode(() -> service.verify("cap-1", "a7kd")).doesNotThrowAnyException();
    assertThatThrownBy(() -> service.verify("cap-1", "A7KD"))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCode.CAPTCHA_INVALID.code());
}

@Test
void verify_wrongAnswerIsStillConsumed() {
    when(store.consume("cap-2")).thenReturn(Optional.of("A7KD"), Optional.empty());
    assertThatThrownBy(() -> service.verify("cap-2", "WRNG"))
            .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.verify("cap-2", "A7KD"))
            .isInstanceOf(BusinessException.class);
    verify(store, times(2)).consume("cap-2");
}
```

- [ ] **Step 2: Write the failing Redis consumption test**

Create this reusable Redis test base and flush the selected DB in `@BeforeEach`:

```java
@Testcontainers
public abstract class RedisIntegrationTestBase {
    @Container
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    protected static LettuceConnectionFactory connectionFactory;
    protected StringRedisTemplate redis;

    @BeforeAll
    static void connectRedis() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
    }

    @AfterAll
    static void disconnectRedis() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void resetRedis() {
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        RedisConnection connection = connectionFactory.getConnection();
        try {
            connection.serverCommands().flushDb();
        } finally {
            connection.close();
        }
    }
}
```

Then assert atomic one-time consumption:

```java
@Test
void consume_getsAndDeletesValue() {
    store.save("cap-1", "ABCD", Duration.ofMinutes(2));
    assertThat(store.consume("cap-1")).contains("ABCD");
    assertThat(store.consume("cap-1")).isEmpty();
}
```

- [ ] **Step 3: Run tests and verify RED**

```bash
mvn -q -Dtest=DefaultCaptchaServiceTest,RedisCaptchaStoreTest test
```

Expected: compilation fails because the captcha types do not exist.

- [ ] **Step 4: Implement store and service**

Use these contracts:

```java
public record CaptchaChallenge(String captchaId, String imageBase64, long expiresInSeconds) {}

public interface CaptchaStore {
    void save(String captchaId, String normalizedAnswer, Duration ttl);
    Optional<String> consume(String captchaId);
}

public interface CaptchaService {
    CaptchaChallenge create();
    void verify(String captchaId, String captchaCode);
}
```

`RedisCaptchaStore` uses one fixed prefix and fail-closed exception conversion:

```java
@Repository
public class RedisCaptchaStore implements CaptchaStore {
    private static final String PREFIX = "auth:captcha:";
    private final StringRedisTemplate redis;

    public RedisCaptchaStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(String captchaId, String normalizedAnswer, Duration ttl) {
        try {
            redis.opsForValue().set(PREFIX + captchaId, normalizedAnswer, ttl);
        } catch (DataAccessException ex) {
            throw new AuthServiceUnavailableException(ex);
        }
    }

    @Override
    public Optional<String> consume(String captchaId) {
        try {
            return Optional.ofNullable(redis.opsForValue().getAndDelete(PREFIX + captchaId));
        } catch (DataAccessException ex) {
            throw new AuthServiceUnavailableException(ex);
        }
    }
}
```

Implement the service with the fixed unambiguous alphabet and JDK Java2D only:

```java
@Service
public class DefaultCaptchaService implements CaptchaService {
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DATA_URL_PREFIX = "data:image/png;base64,";

    private final CaptchaStore store;
    private final AuthProperties properties;
    private final SecureRandom random;

    public DefaultCaptchaService(
            CaptchaStore store, AuthProperties properties, SecureRandom random) {
        this.store = store;
        this.properties = properties;
        this.random = random;
    }

    @Override
    public CaptchaChallenge create() {
        StringBuilder answer = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            answer.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        String captchaId = UUID.randomUUID().toString();
        store.save(captchaId, answer.toString(), properties.getCaptchaTtl());
        return new CaptchaChallenge(
                captchaId,
                DATA_URL_PREFIX + Base64.getEncoder().encodeToString(renderPng(answer.toString())),
                properties.getCaptchaTtl().toSeconds());
    }

    @Override
    public void verify(String captchaId, String captchaCode) {
        if (captchaId == null || captchaId.isBlank()) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID);
        }
        Optional<String> expected = store.consume(captchaId);
        String supplied = captchaCode == null ? "" : captchaCode.trim().toUpperCase(Locale.ROOT);
        if (expected.isEmpty() || !expected.get().equals(supplied)) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID);
        }
    }

    private byte[] renderPng(String answer) {
        BufferedImage image = new BufferedImage(160, 50, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(245, 247, 250));
            g.fillRect(0, 0, 160, 50);
            for (int i = 0; i < 5; i++) {
                g.setColor(randomColor(100, 200));
                g.drawLine(random.nextInt(160), random.nextInt(50),
                        random.nextInt(160), random.nextInt(50));
            }
            for (int i = 0; i < 40; i++) {
                image.setRGB(random.nextInt(160), random.nextInt(50), randomColor(80, 180).getRGB());
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 32));
            for (int i = 0; i < answer.length(); i++) {
                g.setColor(randomColor(20, 120));
                g.drawString(String.valueOf(answer.charAt(i)), 18 + i * 34, 37);
            }
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG writer unavailable");
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("captcha rendering failed", ex);
        }
    }

    private Color randomColor(int min, int maxExclusive) {
        int span = maxExclusive - min;
        return new Color(
                min + random.nextInt(span),
                min + random.nextInt(span),
                min + random.nextInt(span));
    }
}
```

Annotate `RedisCaptchaStore` with `@Repository` and `DefaultCaptchaService` with `@Service` so later authentication wiring uses the tested implementations.

- [ ] **Step 5: Add captcha and Redis infrastructure errors**

Add this exact enum value to `ErrorCode`:

```java
CAPTCHA_INVALID(40002, "验证码错误或已过期"),
AUTH_SERVICE_UNAVAILABLE(50301, "认证服务暂不可用,请稍后重试"),
```

Create the common Redis failure type now because captcha, rate limiting, and sessions all use it:

```java
public class AuthServiceUnavailableException extends RuntimeException {
    public AuthServiceUnavailableException(Throwable cause) {
        super(ErrorCode.AUTH_SERVICE_UNAVAILABLE.defaultMessage(), cause);
    }
}
```

- [ ] **Step 6: Run tests and verify GREEN**

```bash
mvn -q -Dtest=DefaultCaptchaServiceTest,RedisCaptchaStoreTest test
```

Expected: PASS; the second Redis consume is empty and service verification ignores case.

- [ ] **Step 7: Commit captcha support**

```bash
git add armada-api/src/main/java/com/armada/platform/auth/captcha \
  armada-api/src/main/java/com/armada/platform/auth/exception/AuthServiceUnavailableException.java \
  armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java \
  armada-api/src/test/java/com/armada/platform/auth/captcha \
  armada-api/src/test/java/com/armada/testsupport/RedisIntegrationTestBase.java
git commit -m "feat(auth): add one-time image captcha"
```

### Task 4: Add Redis login and captcha rate limits

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/auth/ratelimit/LoginRateLimiter.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/ratelimit/RedisLoginRateLimiter.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/exception/LoginRateLimitException.java`
- Test: `armada-api/src/test/java/com/armada/platform/auth/ratelimit/RedisLoginRateLimiterTest.java`

- [ ] **Step 1: Write failing Redis limit tests**

Cover both approved limits:

```java
@Test
void captchaIssue_twentyAllowed_twentyFirstRejected() {
    for (int i = 0; i < 20; i++) {
        assertThatCode(() -> limiter.checkCaptchaIssue("192.0.2.10")).doesNotThrowAnyException();
    }
    assertThatThrownBy(() -> limiter.checkCaptchaIssue("192.0.2.10"))
            .isInstanceOf(LoginRateLimitException.class);
}

@Test
void fiveFailures_blockBothUsernameAndIpUntilCleared() {
    for (int i = 0; i < 5; i++) {
        limiter.recordLoginFailure("admin", "192.0.2.10");
    }
    assertThatThrownBy(() -> limiter.checkLoginAllowed("admin", "198.51.100.2"))
            .isInstanceOf(LoginRateLimitException.class);
    assertThatThrownBy(() -> limiter.checkLoginAllowed("someone-else", "192.0.2.10"))
            .isInstanceOf(LoginRateLimitException.class);
    limiter.clearLoginFailures("admin", "192.0.2.10");
    assertThatCode(() -> limiter.checkLoginAllowed("admin", "192.0.2.10"))
            .doesNotThrowAnyException();
}
```

Also assert Redis keys do not contain literal `admin` or the literal IP.

```java
@Test
void keysHashUsernameAndIpInsteadOfExposingIdentifiers() {
    limiter.recordLoginFailure("admin", "192.0.2.10");
    Set<String> keys = redis.keys("auth:limit:*");
    assertThat(keys).hasSize(2);
    assertThat(keys).noneMatch(key -> key.contains("admin") || key.contains("192.0.2.10"));
}

@Test
void countersReceiveConfiguredWindowsWithoutSleeping() {
    limiter.checkCaptchaIssue("192.0.2.10");
    limiter.recordLoginFailure("admin", "192.0.2.10");
    Set<String> captchaKeys = redis.keys("auth:limit:captcha-ip:*");
    Set<String> loginKeys = redis.keys("auth:limit:login-*:*");
    assertThat(captchaKeys).singleElement().satisfies(key ->
            assertThat(redis.getExpire(key)).isBetween(1L, 60L));
    assertThat(loginKeys).allSatisfy(key ->
            assertThat(redis.getExpire(key)).isBetween(1L, 600L));
}
```

- [ ] **Step 2: Run and verify RED**

```bash
mvn -q -Dtest=RedisLoginRateLimiterTest test
```

Expected: compilation fails because the rate limiter does not exist.

- [ ] **Step 3: Implement the exact contract**

```java
public interface LoginRateLimiter {
    void checkCaptchaIssue(String clientIp);
    void checkLoginAllowed(String normalizedUsername, String clientIp);
    void recordLoginFailure(String normalizedUsername, String clientIp);
    void clearLoginFailures(String normalizedUsername, String clientIp);
}
```

Use SHA-256 hex digests in these key shapes:

```text
auth:limit:captcha-ip:{ipHash}
auth:limit:login-user:{usernameHash}
auth:limit:login-ip:{ipHash}
```

Use this script for captcha issuance; its only argument is the window in milliseconds:

```lua
local count = redis.call('INCR', KEYS[1])
if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
return count
```

Use one two-key script for a failed login so username and IP counters cannot diverge:

```lua
for i = 1, #KEYS do
  local count = redis.call('INCR', KEYS[i])
  if count == 1 then redis.call('PEXPIRE', KEYS[i], ARGV[1]) end
end
return 1
```

`checkCaptchaIssue` rejects the returned value when it is greater than `captchaMaxIssues`. `checkLoginAllowed` uses one `MGET` for the username and IP counters and rejects when either is greater than or equal to `loginMaxFailures`. `clearLoginFailures` uses one two-key `DEL`.

Annotate `RedisLoginRateLimiter` with `@Service`. Catch `DataAccessException` from every Redis operation and wrap it in `AuthServiceUnavailableException` so login never bypasses a broken limiter.

`LoginRateLimitException` is fixed to one business error:

```java
public class LoginRateLimitException extends BusinessException {
    public LoginRateLimitException() {
        super(ErrorCode.LOGIN_RATE_LIMITED);
    }
}
```

Add:

```java
LOGIN_RATE_LIMITED(42901, "登录尝试过于频繁,请稍后再试"),
```

- [ ] **Step 4: Run and verify GREEN**

```bash
mvn -q -Dtest=RedisLoginRateLimiterTest test
```

Expected: PASS, including username/IP independence and reset after success.

- [ ] **Step 5: Commit rate limiting**

```bash
git add armada-api/src/main/java/com/armada/platform/auth/ratelimit \
  armada-api/src/main/java/com/armada/platform/auth/exception/LoginRateLimitException.java \
  armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java \
  armada-api/src/test/java/com/armada/platform/auth/ratelimit
git commit -m "feat(auth): rate limit login attempts"
```

### Task 5: Implement token encoding and session lifetime logic

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/auth/session/LoginIdentity.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/session/AuthSession.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/session/IssuedSession.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/session/SessionRepository.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/session/SessionTokenCodec.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/session/SessionService.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/session/DefaultSessionService.java`
- Test: `armada-api/src/test/java/com/armada/platform/auth/session/SessionTokenCodecTest.java`
- Test: `armada-api/src/test/java/com/armada/platform/auth/session/DefaultSessionServiceTest.java`

- [ ] **Step 1: Write failing token and lifetime tests**

Put `generate_usesThirtyTwoRandomBytesAndStoresOnlySha256Hash` in `SessionTokenCodecTest` with a real `SessionTokenCodec(new SecureRandom())`. Put the remaining tests and the following `@BeforeEach` in `DefaultSessionServiceTest`, using a mutable `Clock` plus mocked `SessionRepository` and `SessionTokenCodec`:

```java
@Test
void generate_usesThirtyTwoRandomBytesAndStoresOnlySha256Hash() {
    SessionTokenCodec codec = new SessionTokenCodec(new SecureRandom());
    String token = codec.generate();
    assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
    assertThat(codec.hash(token)).matches("[0-9a-f]{64}");
    assertThat(codec.hash(token)).doesNotContain(token);
}
```

In `DefaultSessionServiceTest`:

```java
private SessionRepository repository;
private SessionTokenCodec codec;
private MutableClock clock;
private DefaultSessionService service;

@BeforeEach
void setUp() {
    repository = mock(SessionRepository.class);
    codec = mock(SessionTokenCodec.class);
    clock = new MutableClock();
    service = new DefaultSessionService(repository, codec, new AuthProperties(), clock);
}

@Test
void findAndTouch_capsTtlAtIdleAndAbsoluteExpiry() {
    when(codec.hash("raw-token")).thenReturn("hash");
    AuthSession issued = sessionAt(instant("2026-07-15T00:00:00Z"),
            instant("2026-07-16T00:00:00Z"));
    when(repository.find("hash")).thenReturn(Optional.of(issued));
    when(repository.touch(eq("hash"), any(AuthSession.class), eq(Duration.ofMinutes(15))))
            .thenReturn(true);
    clock.setInstant(instant("2026-07-15T23:45:00Z"));
    assertThat(service.findAndTouch("raw-token")).isPresent();
    verify(repository).touch(eq("hash"), any(AuthSession.class), eq(Duration.ofMinutes(15)));
}

@Test
void findAndTouch_afterAbsoluteExpiry_invalidatesAndReturnsEmpty() {
    when(codec.hash("raw-token")).thenReturn("hash");
    AuthSession issued = sessionAt(instant("2026-07-15T00:00:00Z"),
            instant("2026-07-16T00:00:00Z"));
    when(repository.find("hash")).thenReturn(Optional.of(issued));
    clock.setInstant(instant("2026-07-16T00:00:01Z"));
    assertThat(service.findAndTouch("raw-token")).isEmpty();
    verify(repository).logout(issued.userId(), "hash");
}

@Test
void findAndTouch_duringNormalUse_refreshesThirtyMinuteIdleTtl() {
    when(codec.hash("raw-token")).thenReturn("hash");
    AuthSession issued = sessionAt(instant("2026-07-15T00:00:00Z"),
            instant("2026-07-16T00:00:00Z"));
    when(repository.find("hash")).thenReturn(Optional.of(issued));
    when(repository.touch(eq("hash"), any(AuthSession.class), eq(Duration.ofMinutes(30))))
            .thenReturn(true);
    clock.setInstant(instant("2026-07-15T01:00:00Z"));

    assertThat(service.findAndTouch("raw-token")).isPresent();
    verify(repository).touch(eq("hash"), any(AuthSession.class), eq(Duration.ofMinutes(30)));
}

@Test
void findAndTouch_whenPointerWasReplaced_returnsEmpty() {
    when(codec.hash("old-token")).thenReturn("old-hash");
    AuthSession issued = sessionAt(instant("2026-07-15T00:00:00Z"),
            instant("2026-07-16T00:00:00Z"));
    when(repository.find("old-hash")).thenReturn(Optional.of(issued));
    when(repository.touch(eq("old-hash"), any(AuthSession.class), any(Duration.class)))
            .thenReturn(false);
    clock.setInstant(instant("2026-07-15T01:00:00Z"));

    assertThat(service.findAndTouch("old-token")).isEmpty();
}

private AuthSession sessionAt(Instant issuedAt, Instant absoluteExpiresAt) {
    return new AuthSession(
            1L, 1L, "admin", "管理员", UserRole.ADMIN, "demo", "演示租户A",
            issuedAt.toEpochMilli(), issuedAt.toEpochMilli(), absoluteExpiresAt.toEpochMilli());
}

private static Instant instant(String value) {
    return Instant.parse(value);
}

private LoginIdentity identity() {
    return new LoginIdentity(
            1L, 1L, "admin", "管理员", UserRole.ADMIN, "demo", "演示租户A");
}

@Test
void issue_usesThirtyMinuteIdleTtl() {
    when(codec.generate()).thenReturn("raw-token");
    when(codec.hash("raw-token")).thenReturn("hash");
    clock.setInstant(instant("2026-07-15T00:00:00Z"));
    IssuedSession issued = service.issue(identity());
    verify(repository).replace(eq("hash"), eq(issued.session()), eq(Duration.ofMinutes(30)));
    assertThat(issued.session().absoluteExpiresAt() - issued.session().issuedAt())
            .isEqualTo(Duration.ofHours(24).toMillis());
}

@Test
void logoutHashesRawTokenBeforeRepositoryDelete() {
    when(codec.hash("raw-token")).thenReturn("hash");
    AuthSession session = sessionAt(instant("2026-07-15T00:00:00Z"),
            instant("2026-07-16T00:00:00Z"));
    when(repository.find("hash")).thenReturn(Optional.of(session));
    service.logout("raw-token");
    verify(repository).logout(session.userId(), "hash");
}

@Test
void invalidateUserDoesNotRequireToken() {
    service.invalidateUser(7L);
    verify(repository).invalidateUser(7L);
    verifyNoInteractions(codec);
}
```

Define this nested test clock so expiry tests remain deterministic and sleep-free:

```java
private static final class MutableClock extends Clock {
    private Instant current = Instant.EPOCH;

    void setInstant(Instant current) {
        this.current = current;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (!ZoneOffset.UTC.equals(zone)) {
            throw new IllegalArgumentException("test clock only supports UTC");
        }
        return this;
    }

    @Override
    public Instant instant() {
        return current;
    }
}
```

- [ ] **Step 2: Run and verify RED**

```bash
mvn -q -Dtest=SessionTokenCodecTest,DefaultSessionServiceTest test
```

Expected: compilation fails because session types do not exist.

- [ ] **Step 3: Define immutable session contracts**

```java
public record LoginIdentity(
        Long userId,
        Long tenantId,
        String username,
        String displayName,
        UserRole role,
        String tenantCode,
        String tenantName) {}

public record AuthSession(
        Long userId,
        Long tenantId,
        String username,
        String displayName,
        UserRole role,
        String tenantCode,
        String tenantName,
        long issuedAt,
        long lastAccessAt,
        long absoluteExpiresAt) {}

public record IssuedSession(String token, AuthSession session, long idleTimeoutSeconds) {}

public interface SessionRepository {
    void replace(String tokenHash, AuthSession session, Duration ttl);
    Optional<AuthSession> find(String tokenHash);
    boolean touch(String tokenHash, AuthSession session, Duration ttl);
    void logout(Long userId, String tokenHash);
    void invalidateUser(Long userId);
}

public interface SessionService {
    IssuedSession issue(LoginIdentity identity);
    Optional<AuthSession> findAndTouch(String rawToken);
    void logout(String rawToken);
    void invalidateUser(Long userId);
}
```

- [ ] **Step 4: Implement the codec and service**

Implement token generation and hashing without ever persisting the raw value:

```java
@Component
public class SessionTokenCodec {
    private final SecureRandom random;

    public SessionTokenCodec(SecureRandom random) {
        this.random = random;
    }

    public String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
```

`DefaultSessionService` remains unannotated in this task so the application context is not wired before a repository implementation exists:

```java
public class DefaultSessionService implements SessionService {
    private final SessionRepository repository;
    private final SessionTokenCodec codec;
    private final AuthProperties properties;
    private final Clock clock;

    public DefaultSessionService(SessionRepository repository, SessionTokenCodec codec,
            AuthProperties properties, Clock clock) {
        this.repository = repository;
        this.codec = codec;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public IssuedSession issue(LoginIdentity identity) {
        String rawToken = codec.generate();
        String tokenHash = codec.hash(rawToken);
        long now = clock.millis();
        long absoluteExpiresAt = now + properties.getSessionMaxLifetime().toMillis();
        AuthSession session = new AuthSession(
                identity.userId(), identity.tenantId(), identity.username(), identity.displayName(),
                identity.role(), identity.tenantCode(), identity.tenantName(),
                now, now, absoluteExpiresAt);
        Duration initialTtl = properties.getSessionIdleTimeout().compareTo(
                properties.getSessionMaxLifetime()) <= 0
                ? properties.getSessionIdleTimeout()
                : properties.getSessionMaxLifetime();
        repository.replace(tokenHash, session, initialTtl);
        return new IssuedSession(
                rawToken, session, properties.getSessionIdleTimeout().toSeconds());
    }

    @Override
    public Optional<AuthSession> findAndTouch(String rawToken) {
        String tokenHash = codec.hash(rawToken);
        Optional<AuthSession> found = repository.find(tokenHash);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AuthSession session = found.get();
        long now = clock.millis();
        if (now >= session.absoluteExpiresAt()) {
            repository.logout(session.userId(), tokenHash);
            return Optional.empty();
        }
        AuthSession touched = new AuthSession(
                session.userId(), session.tenantId(), session.username(), session.displayName(),
                session.role(), session.tenantCode(), session.tenantName(),
                session.issuedAt(), now, session.absoluteExpiresAt());
        Duration ttl = Duration.ofMillis(Math.min(
                properties.getSessionIdleTimeout().toMillis(),
                session.absoluteExpiresAt() - now));
        return repository.touch(tokenHash, touched, ttl)
                ? Optional.of(touched)
                : Optional.empty();
    }

    @Override
    public void logout(String rawToken) {
        String tokenHash = codec.hash(rawToken);
        repository.find(tokenHash).ifPresent(session ->
                repository.logout(session.userId(), tokenHash));
    }

    @Override
    public void invalidateUser(Long userId) {
        repository.invalidateUser(userId);
    }
}
```

- [ ] **Step 5: Run and verify GREEN**

```bash
mvn -q -Dtest=SessionTokenCodecTest,DefaultSessionServiceTest test
```

Expected: PASS with no real sleep and exact 30-minute/24-hour boundaries.

- [ ] **Step 6: Commit session domain logic**

```bash
git add armada-api/src/main/java/com/armada/platform/auth/session \
  armada-api/src/test/java/com/armada/platform/auth/session/SessionTokenCodecTest.java \
  armada-api/src/test/java/com/armada/platform/auth/session/DefaultSessionServiceTest.java
git commit -m "feat(auth): define opaque session lifecycle"
```

### Task 6: Implement atomic Redis single-session storage

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/auth/session/RedisSessionRepository.java`
- Test: `armada-api/src/test/java/com/armada/platform/auth/session/RedisSessionRepositoryTest.java`

- [ ] **Step 1: Write failing Redis atomicity tests**

Test replacement, pointer checking, synchronized touch, and stale logout:

```java
@Test
void replace_secondLoginInvalidatesFirstToken() {
    repository.replace("hash-1", session(1L, 1000L), Duration.ofMinutes(30));
    repository.replace("hash-2", session(1L, 2000L), Duration.ofMinutes(30));
    assertThat(repository.find("hash-1")).isEmpty();
    assertThat(repository.find("hash-2")).isPresent();
}

@Test
void staleLogoutCannotDeleteNewUserPointer() {
    repository.replace("hash-1", session(1L, 1000L), Duration.ofMinutes(30));
    repository.replace("hash-2", session(1L, 2000L), Duration.ofMinutes(30));
    repository.logout(1L, "hash-1");
    assertThat(repository.touch("hash-2", session(1L, 3000L), Duration.ofMinutes(30))).isTrue();
}

@Test
void touchRejectsSessionWhoseUserPointerChanged() {
    repository.replace("hash-1", session(1L, 1000L), Duration.ofMinutes(30));
    redis.opsForValue().set("auth:user-session:1", "other-hash");
    assertThat(repository.touch("hash-1", session(1L, 2000L), Duration.ofMinutes(30))).isFalse();
}

@Test
void touchRefreshesSessionAndPointerWithSameBoundedTtl() {
    repository.replace("hash-1", session(1L, 1000L), Duration.ofMinutes(30));
    assertThat(repository.touch(
            "hash-1", session(1L, 2000L), Duration.ofMinutes(12))).isTrue();
    long sessionTtl = redis.getExpire("auth:session:hash-1", TimeUnit.MILLISECONDS);
    long pointerTtl = redis.getExpire("auth:user-session:1", TimeUnit.MILLISECONDS);
    assertThat(sessionTtl).isBetween(719_000L, 720_000L);
    assertThat(Math.abs(sessionTtl - pointerTtl)).isLessThan(100L);
}

private AuthSession session(long userId, long lastAccessAt) {
    return new AuthSession(
            userId, 1L, "admin", "管理员", UserRole.ADMIN, "demo", "演示租户A",
            1000L, lastAccessAt, 86_400_000L);
}
```

- [ ] **Step 2: Run and verify RED**

```bash
mvn -q -Dtest=RedisSessionRepositoryTest test
```

Expected: compilation fails because `RedisSessionRepository` does not exist.

- [ ] **Step 3: Implement JSON storage and exact Lua scripts**

Use `ObjectMapper` for `AuthSession` JSON and these prefixes:

```java
private static final String SESSION_PREFIX = "auth:session:";
private static final String USER_PREFIX = "auth:user-session:";
```

The replace script must delete the old session and set both new keys atomically:

```lua
local oldHash = redis.call('GET', KEYS[1])
if oldHash then
  redis.call('DEL', ARGV[4] .. oldHash)
end
redis.call('PSETEX', KEYS[2], ARGV[3], ARGV[2])
redis.call('PSETEX', KEYS[1], ARGV[3], ARGV[1])
return oldHash or ''
```

Arguments are `newHash`, `sessionJson`, `ttlMillis`, `SESSION_PREFIX`; keys are `userKey`, `newSessionKey`.

The touch script checks the pointer, confirms the session still exists, then `PSETEX` updates both JSON and TTL:

```lua
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
if redis.call('EXISTS', KEYS[2]) == 0 then return 0 end
redis.call('PSETEX', KEYS[2], ARGV[3], ARGV[2])
redis.call('PSETEX', KEYS[1], ARGV[3], ARGV[1])
return 1
```

The logout script always deletes the named session key but deletes the user pointer only when it still equals the supplied hash:

```lua
redis.call('DEL', KEYS[2])
if redis.call('GET', KEYS[1]) == ARGV[1] then
  redis.call('DEL', KEYS[1])
end
return 1
```

`invalidateUser` uses one script to delete the current pointer and its session together:

```lua
local currentHash = redis.call('GET', KEYS[1])
if currentHash then
  redis.call('DEL', ARGV[1] .. currentHash)
end
redis.call('DEL', KEYS[1])
return currentHash or ''
```

The key is `userKey` and the only argument is `SESSION_PREFIX`. Wrap Redis/JSON failures in `AuthServiceUnavailableException`; do not log JSON or raw token material.

Annotate `RedisSessionRepository` with `@Repository` and annotate `DefaultSessionService` with `@Service` in this task, after both sides of the dependency are present.

- [ ] **Step 4: Run and verify GREEN**

```bash
mvn -q -Dtest=RedisSessionRepositoryTest test
```

Expected: PASS; only the latest session survives and stale logout cannot affect it.

- [ ] **Step 5: Commit Redis session storage**

```bash
git add armada-api/src/main/java/com/armada/platform/auth/session/RedisSessionRepository.java \
  armada-api/src/main/java/com/armada/platform/auth/session/DefaultSessionService.java \
  armada-api/src/test/java/com/armada/platform/auth/session/RedisSessionRepositoryTest.java
git commit -m "feat(auth): enforce single redis session atomically"
```

### Task 7: Implement username/password authentication orchestration

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/auth/model/LoginRequest.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/model/UserIdentityVO.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/model/TenantIdentityVO.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/model/LoginVO.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/model/CurrentUserVO.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/model/AuthPrincipal.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/service/AuthService.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/service/DefaultAuthService.java`
- Test: `armada-api/src/test/java/com/armada/platform/auth/service/DefaultAuthServiceTest.java`
- Modify: `armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java`

- [ ] **Step 1: Write failing authentication service tests**

Cover ordering and uniform failure semantics:

```java
@Test
void createCaptcha_checksIpLimitBeforeGeneratingImage() {
    CaptchaChallenge challenge = new CaptchaChallenge(
            "cap-1", "data:image/png;base64,AA==", 120L);
    when(captchaService.create()).thenReturn(challenge);

    assertThat(service.createCaptcha("192.0.2.1")).isSameAs(challenge);

    InOrder order = inOrder(rateLimiter, captchaService);
    order.verify(rateLimiter).checkCaptchaIssue("192.0.2.1");
    order.verify(captchaService).create();
}

@Test
void login_validatesCaptchaBeforeLookingUpUser() {
    doThrow(new BusinessException(ErrorCode.CAPTCHA_INVALID))
            .when(captchaService).verify("cap-1", "BAD1");
    assertThatThrownBy(() -> service.login(request(" ADMIN ", "armada123", "BAD1"), "192.0.2.1"))
            .isInstanceOf(BusinessException.class);
    verifyNoInteractions(rateLimiter, userMapper, tenantMapper, passwordEncoder, sessionService);
}

@Test
void login_normalizesUsernameAndIssuesTenantIdentity() {
    when(userMapper.selectForLoginByUsername("admin")).thenReturn(enabledAdmin());
    when(tenantMapper.selectById(1L)).thenReturn(enabledTenant());
    when(passwordEncoder.matches("armada123", enabledAdmin().getPasswordHash())).thenReturn(true);
    when(sessionService.issue(any())).thenReturn(issuedSession());
    when(userMapper.updateLastLoginAtForAuthenticatedUser(eq(1L), eq(1L), any())).thenReturn(1);

    LoginVO result = service.login(request(" ADMIN ", "armada123", "A7KD"), "192.0.2.1");

    assertThat(result.user().role()).isEqualTo(UserRole.ADMIN);
    assertThat(result.tenant().id()).isEqualTo(1L);
    verify(rateLimiter).clearLoginFailures("admin", "192.0.2.1");
}

@ParameterizedTest
@MethodSource("invalidIdentities")
void login_unknownWrongPasswordDisabledUserOrTenant_returnsSameLoginError(
        LoginUserRow user, Tenant tenant, boolean matches) {
    when(userMapper.selectForLoginByUsername("admin")).thenReturn(user);
    if (user != null) when(tenantMapper.selectById(user.getTenantId())).thenReturn(tenant);
    when(passwordEncoder.matches(anyString(), anyString())).thenReturn(matches);
    assertThatThrownBy(() -> service.login(validRequest(), "192.0.2.1"))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCode.LOGIN_FAILED.code());
    verify(rateLimiter).recordLoginFailure("admin", "192.0.2.1");
}
```

Also assert unknown users still call `passwordEncoder.matches` with a fixed dummy hash, rate-limit checking occurs before BCrypt, session creation failure does not update `last_login_at`, and last-login update failure logs out the newly issued token before failing.

```java
@Test
void login_unknownUser_stillRunsBcryptAgainstStableDummyHash() {
    when(userMapper.selectForLoginByUsername("admin")).thenReturn(null);
    when(passwordEncoder.matches(eq("armada123"), anyString())).thenReturn(false);

    assertThatThrownBy(() -> service.login(validRequest(), "192.0.2.1"))
            .isInstanceOf(BusinessException.class);

    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    verify(passwordEncoder).matches(eq("armada123"), hash.capture());
    assertThat(hash.getValue()).startsWith("{bcrypt}$2");
}

@Test
void login_checksRateLimitBeforeReadingUserOrRunningBcrypt() {
    when(userMapper.selectForLoginByUsername("admin")).thenReturn(enabledAdmin());
    when(tenantMapper.selectById(1L)).thenReturn(enabledTenant());
    when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

    assertThatThrownBy(() -> service.login(validRequest(), "192.0.2.1"))
            .isInstanceOf(BusinessException.class);

    InOrder order = inOrder(captchaService, rateLimiter, userMapper, passwordEncoder);
    order.verify(captchaService).verify("cap-1", "A7KD");
    order.verify(rateLimiter).checkLoginAllowed("admin", "192.0.2.1");
    order.verify(userMapper).selectForLoginByUsername("admin");
    order.verify(passwordEncoder).matches(anyString(), anyString());
}

@Test
void login_sessionFailure_doesNotUpdateLastLogin() {
    stubValidIdentity();
    when(sessionService.issue(any())).thenThrow(
            new AuthServiceUnavailableException(new RedisConnectionFailureException("down")));

    assertThatThrownBy(() -> service.login(validRequest(), "192.0.2.1"))
            .isInstanceOf(AuthServiceUnavailableException.class);
    verify(userMapper, never()).updateLastLoginAtForAuthenticatedUser(anyLong(), anyLong(), any());
}

@Test
void login_lastLoginUpdateFailure_invalidatesIssuedToken() {
    stubValidIdentity();
    when(sessionService.issue(any())).thenReturn(issuedSession());
    when(userMapper.updateLastLoginAtForAuthenticatedUser(eq(1L), eq(1L), any()))
            .thenReturn(0);

    assertThatThrownBy(() -> service.login(validRequest(), "192.0.2.1"))
            .isInstanceOf(IllegalStateException.class);
    verify(sessionService).logout("raw-token");
}

private void stubValidIdentity() {
    LoginUserRow user = enabledAdmin();
    when(userMapper.selectForLoginByUsername("admin")).thenReturn(user);
    when(tenantMapper.selectById(1L)).thenReturn(enabledTenant());
    when(passwordEncoder.matches("armada123", user.getPasswordHash())).thenReturn(true);
}

private static Stream<Arguments> invalidIdentities() {
    LoginUserRow disabledUser = enabledAdmin();
    disabledUser.setStatus(0);
    Tenant disabledTenant = enabledTenant();
    disabledTenant.setStatus(0);
    return Stream.of(
            arguments(null, null, false),
            arguments(enabledAdmin(), enabledTenant(), false),
            arguments(disabledUser, enabledTenant(), true),
            arguments(enabledAdmin(), disabledTenant, true));
}

private static LoginUserRow enabledAdmin() {
    LoginUserRow user = new LoginUserRow();
    user.setId(1L);
    user.setTenantId(1L);
    user.setUsername("admin");
    user.setPasswordHash("{bcrypt}$2y$10$prdOI2HhX2KU/5LbbnA0x.01YEtzOAxvfRN7aZxk292bOuw3yH2Cu");
    user.setDisplayName("管理员");
    user.setRole(UserRole.ADMIN);
    user.setStatus(1);
    return user;
}

private static Tenant enabledTenant() {
    Tenant tenant = new Tenant();
    tenant.setId(1L);
    tenant.setTenantCode("demo");
    tenant.setName("演示租户A");
    tenant.setStatus(1);
    return tenant;
}

private LoginRequest validRequest() {
    return request("admin", "armada123", "A7KD");
}

private LoginRequest request(String username, String password, String captchaCode) {
    return new LoginRequest(username, password, "cap-1", captchaCode);
}

private IssuedSession issuedSession() {
    AuthSession session = new AuthSession(
            1L, 1L, "admin", "管理员", UserRole.ADMIN, "demo", "演示租户A",
            1_752_537_600_000L, 1_752_537_600_000L, 1_752_624_000_000L);
    return new IssuedSession("raw-token", session, 1800L);
}
```

- [ ] **Step 2: Run and verify RED**

```bash
mvn -q -Dtest=DefaultAuthServiceTest test
```

Expected: compilation fails because the authentication service and models do not exist.

- [ ] **Step 3: Define request/response and principal records**

```java
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String captchaId,
        @NotBlank String captchaCode) {}

public record UserIdentityVO(Long id, String username, String displayName, UserRole role) {}
public record TenantIdentityVO(Long id, String code, String name) {}

public record LoginVO(
        String token,
        String tokenType,
        long idleTimeoutSeconds,
        long absoluteExpiresAt,
        UserIdentityVO user,
        TenantIdentityVO tenant) {}

public record CurrentUserVO(UserIdentityVO user, TenantIdentityVO tenant) {}

public record AuthPrincipal(
        Long userId,
        Long tenantId,
        String username,
        String displayName,
        UserRole role,
        String tenantCode,
        String tenantName) {

    public CurrentUserVO toCurrentUser() {
        return new CurrentUserVO(
                new UserIdentityVO(userId, username, displayName, role),
                new TenantIdentityVO(tenantId, tenantCode, tenantName));
    }
}
```

- [ ] **Step 4: Implement the authentication sequence**

`AuthService` has only the two public-login operations:

```java
public interface AuthService {
    CaptchaChallenge createCaptcha(String clientIp);
    LoginVO login(LoginRequest request, String clientIp);
}
```

Annotate `DefaultAuthService` with `@Service`. Its `login` method performs this exact order:

```java
private static final String DUMMY_BCRYPT_HASH =
        "{bcrypt}$2y$10$prdOI2HhX2KU/5LbbnA0x.01YEtzOAxvfRN7aZxk292bOuw3yH2Cu";

@Override
public CaptchaChallenge createCaptcha(String clientIp) {
    rateLimiter.checkCaptchaIssue(clientIp);
    return captchaService.create();
}

@Override
public LoginVO login(LoginRequest request, String clientIp) {
captchaService.verify(request.captchaId(), request.captchaCode());
String username = request.username().trim().toLowerCase(Locale.ROOT);
rateLimiter.checkLoginAllowed(username, clientIp);
LoginUserRow user = userMapper.selectForLoginByUsername(username);
String storedHash = user == null ? DUMMY_BCRYPT_HASH : user.getPasswordHash();
boolean passwordMatches = passwordEncoder.matches(request.password(), storedHash);
Tenant tenant = user == null ? null : tenantMapper.selectById(user.getTenantId());
if (user == null || !passwordMatches || user.getStatus() != 1
        || tenant == null || tenant.getStatus() != 1) {
    rateLimiter.recordLoginFailure(username, clientIp);
    throw new BusinessException(ErrorCode.LOGIN_FAILED);
}
LoginIdentity identity = toLoginIdentity(user, tenant);
IssuedSession issued = sessionService.issue(identity);
try {
    int updated = userMapper.updateLastLoginAtForAuthenticatedUser(
            user.getId(), user.getTenantId(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    if (updated != 1) throw new IllegalStateException("authenticated user disappeared");
    rateLimiter.clearLoginFailures(username, clientIp);
} catch (RuntimeException e) {
    sessionService.logout(issued.token());
    throw e;
}
return toLoginVO(issued, identity);
}

private LoginIdentity toLoginIdentity(LoginUserRow user, Tenant tenant) {
    return new LoginIdentity(
            user.getId(), tenant.getId(), user.getUsername(), user.getDisplayName(),
            user.getRole(), tenant.getTenantCode(), tenant.getName());
}

private LoginVO toLoginVO(IssuedSession issued, LoginIdentity identity) {
    return new LoginVO(
            issued.token(), "Bearer", issued.idleTimeoutSeconds(),
            issued.session().absoluteExpiresAt(),
            new UserIdentityVO(
                    identity.userId(), identity.username(), identity.displayName(), identity.role()),
            new TenantIdentityVO(
                    identity.tenantId(), identity.tenantCode(), identity.tenantName()));
}
```

`createCaptcha` calls `rateLimiter.checkCaptchaIssue(clientIp)` before `captchaService.create()`.

Use the common `AuthServiceUnavailableException` created in Task 3 for Redis failures. Change the old `LOGIN_FAILED` message to `用户名或密码错误` and add `UNAUTHENTICATED(40104, "登录已失效,请重新登录")` plus `ACCESS_DENIED(40301, "无权执行此操作")`.

- [ ] **Step 5: Run and verify GREEN**

```bash
mvn -q -Dtest=DefaultAuthServiceTest test
```

Expected: PASS; all credential/account/tenant failures share `LOGIN_FAILED`, and no session survives a post-issue failure.

- [ ] **Step 6: Commit authentication service**

```bash
git add armada-api/src/main/java/com/armada/platform/auth/model \
  armada-api/src/main/java/com/armada/platform/auth/service \
  armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java \
  armada-api/src/test/java/com/armada/platform/auth/service/DefaultAuthServiceTest.java
git commit -m "feat(auth): authenticate tenant users"
```

### Task 8: Add the authentication HTTP API and response statuses

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/auth/web/AuthController.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/web/BearerTokenParser.java`
- Test: `armada-api/src/test/java/com/armada/platform/auth/web/AuthControllerTest.java`
- Modify: `armada-api/src/main/java/com/armada/boot/web/GlobalExceptionHandler.java`
- Test: `armada-api/src/test/java/com/armada/boot/web/GlobalExceptionHandlerTest.java`
- Delete: `armada-api/src/main/java/com/armada/platform/tenant/controller/TenantAuthController.java`
- Delete: `armada-api/src/main/java/com/armada/platform/tenant/model/dto/TenantLoginRequest.java`
- Delete: `armada-api/src/main/java/com/armada/platform/tenant/model/vo/TenantLoginVO.java`
- Delete: `armada-api/src/main/java/com/armada/platform/tenant/service/TenantAuthService.java`
- Delete: `armada-api/src/main/java/com/armada/platform/tenant/service/impl/TenantAuthServiceImpl.java`
- Delete: `armada-api/src/test/java/com/armada/platform/tenant/service/TenantAuthServiceImplDbTest.java`

- [ ] **Step 1: Write failing standalone controller tests**

```java
@Test
void captcha_returnsChallengeAndNoStoreHeader() throws Exception {
    when(authService.createCaptcha("127.0.0.1"))
            .thenReturn(new CaptchaChallenge("cap-1", "data:image/png;base64,AA==", 120));
    mockMvc.perform(get("/api/public/auth/captcha")
                    .header("X-Forwarded-For", "203.0.113.9"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.data.captchaId").value("cap-1"));
    verify(authService).createCaptcha("127.0.0.1");
}

@Test
void login_acceptsApprovedFourFields() throws Exception {
    when(authService.login(any(), eq("127.0.0.1"))).thenReturn(loginVO());
    mockMvc.perform(post("/api/public/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"username":"admin","password":"armada123",
                             "captchaId":"cap-1","captchaCode":"A7KD"}
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.data.user.role").value("ADMIN"));
}

@Test
void me_returnsPrincipalWithoutPasswordOrToken() throws Exception {
    AuthPrincipal principal = principal();
    mockMvc.perform(get("/api/auth/me").principal(authentication(principal)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.user.username").value("admin"))
            .andExpect(jsonPath("$.data.token").doesNotExist())
            .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
}
```

The direct `X-Forwarded-For` value above must have no effect. Keep business code on `request.getRemoteAddr()`; a deployment behind a trusted proxy may opt into Spring Boot's standard `server.forward-headers-strategy`, provided the edge proxy strips client-supplied forwarding headers. Do not parse `X-Forwarded-For` in Armada code.

Add logout coverage proving the parsed raw Bearer token is passed only to `SessionService.logout`.

```java
@Test
void logout_invalidatesOnlyParsedBearerToken() throws Exception {
    when(tokenParser.require(any(HttpServletRequest.class))).thenReturn("raw-token");
    mockMvc.perform(post("/api/auth/logout")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer raw-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    verify(sessionService).logout("raw-token");
}

private LoginVO loginVO() {
    return new LoginVO(
            "raw-token", "Bearer", 1800L, 1_752_624_000_000L,
            new UserIdentityVO(1L, "admin", "管理员", UserRole.ADMIN),
            new TenantIdentityVO(1L, "demo", "演示租户A"));
}

private AuthPrincipal principal() {
    return new AuthPrincipal(
            1L, 1L, "admin", "管理员", UserRole.ADMIN, "demo", "演示租户A");
}

private Authentication authentication(AuthPrincipal principal) {
    return UsernamePasswordAuthenticationToken.authenticated(
            principal, null, List.of(new SimpleGrantedAuthority(principal.role().authority())));
}
```

- [ ] **Step 2: Write failing exception handler tests**

Add the validation case to `AuthControllerTest`:

```java
@Test
void login_blankFields_returnsValidationBusinessCode() throws Exception {
    mockMvc.perform(post("/api/public/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"username":"","password":"","captchaId":"","captchaCode":""}
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION.code()));
    verifyNoInteractions(authService);
}
```

In `GlobalExceptionHandlerTest`, use a standalone test controller and assert the two non-200 mappings:

```java
@RestController
static class FailureController {
    @GetMapping("/test/rate-limit")
    ApiResponse<Void> rateLimit() {
        throw new LoginRateLimitException();
    }

    @GetMapping("/test/redis-down")
    ApiResponse<Void> redisDown() {
        throw new AuthServiceUnavailableException(
                new RedisConnectionFailureException("down"));
    }
}

@Test
void rateLimit_returns429AndStableBusinessCode() throws Exception {
    mockMvc.perform(get("/test/rate-limit"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value(ErrorCode.LOGIN_RATE_LIMITED.code()));
}

@Test
void redisFailure_returns503AndStableBusinessCode() throws Exception {
    mockMvc.perform(get("/test/redis-down"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value(ErrorCode.AUTH_SERVICE_UNAVAILABLE.code()));
}
```

Build `mockMvc` with `MockMvcBuilders.standaloneSetup(new FailureController()).setControllerAdvice(new GlobalExceptionHandler()).build()`.

- [ ] **Step 3: Run and verify RED**

```bash
mvn -q -Dtest=AuthControllerTest,GlobalExceptionHandlerTest test
```

Expected: compilation fails because the controller/parser and specialized handlers do not exist.

- [ ] **Step 4: Implement controller and parser**

```java
@RestController
public class AuthController {
    private final AuthService authService;
    private final SessionService sessionService;
    private final BearerTokenParser tokenParser;

    @GetMapping("/api/public/auth/captcha")
    public ResponseEntity<ApiResponse<CaptchaChallenge>> captcha(HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(authService.createCaptcha(request.getRemoteAddr())));
    }

    @PostMapping("/api/public/auth/login")
    public ApiResponse<LoginVO> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return ApiResponse.ok(authService.login(body, request.getRemoteAddr()));
    }

    @GetMapping("/api/auth/me")
    public ApiResponse<CurrentUserVO> me(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(principal.toCurrentUser());
    }

    @PostMapping("/api/auth/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        sessionService.logout(tokenParser.require(request));
        return ApiResponse.ok();
    }
}
```

`BearerTokenParser` accepts exactly one `Authorization` value with case-insensitive `Bearer ` prefix and a nonblank token. It never logs the header:

```java
@Component
public class BearerTokenParser {
    private static final String PREFIX = "Bearer ";

    public Optional<String> resolve(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        if (values.size() != 1) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        String value = values.get(0);
        if (!value.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        String token = value.substring(PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return Optional.of(token);
    }

    public String require(HttpServletRequest request) {
        return resolve(request).orElseThrow(
                () -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }
}
```

Annotate `BearerTokenParser` with `@Component`. In the standalone controller test setup, register both validation and the security principal resolver:

```java
LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
validator.afterPropertiesSet();
mockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler())
        .setValidator(validator)
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .build();
```

- [ ] **Step 5: Implement exact exception statuses**

Add handlers before the general `BusinessException` handler:

```java
@ExceptionHandler(LoginRateLimitException.class)
ResponseEntity<ApiResponse<Void>> handleRateLimit(LoginRateLimitException ex) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(ApiResponse.error(ex.getCode(), ex.getMessage()));
}

@ExceptionHandler(AuthServiceUnavailableException.class)
ResponseEntity<ApiResponse<Void>> handleAuthUnavailable(AuthServiceUnavailableException ex) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiResponse.error(ErrorCode.AUTH_SERVICE_UNAVAILABLE.code(),
                    ErrorCode.AUTH_SERVICE_UNAVAILABLE.defaultMessage()));
}

@ExceptionHandler(MethodArgumentNotValidException.class)
ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
    return ApiResponse.error(ErrorCode.VALIDATION.code(), ErrorCode.VALIDATION.defaultMessage());
}
```

Do not log rejected password/captcha field values.

- [ ] **Step 6: Remove the old tenant login endpoint implementation**

Delete `TenantAuthController`, `TenantLoginRequest`, `TenantLoginVO`, `TenantAuthService`, `TenantAuthServiceImpl`, and `TenantAuthServiceImplDbTest`. Keep tenant resolver pieces until Task 10 because `WebMvcConfig` still compiles against them.

- [ ] **Step 7: Run and verify GREEN**

```bash
mvn -q -Dtest=AuthControllerTest,GlobalExceptionHandlerTest test
```

Expected: PASS for response shape, no-store header, validation, 429, and 503.

- [ ] **Step 8: Commit the API slice**

```bash
git add armada-api/src/main/java/com/armada/platform/auth/web \
  armada-api/src/main/java/com/armada/boot/web/GlobalExceptionHandler.java \
  armada-api/src/test/java/com/armada/platform/auth/web \
  armada-api/src/test/java/com/armada/boot/web/GlobalExceptionHandlerTest.java
git add -u armada-api/src/main/java/com/armada/platform/tenant/controller/TenantAuthController.java \
  armada-api/src/main/java/com/armada/platform/tenant/model/dto/TenantLoginRequest.java \
  armada-api/src/main/java/com/armada/platform/tenant/model/vo/TenantLoginVO.java \
  armada-api/src/main/java/com/armada/platform/tenant/service/TenantAuthService.java \
  armada-api/src/main/java/com/armada/platform/tenant/service/impl/TenantAuthServiceImpl.java \
  armada-api/src/test/java/com/armada/platform/tenant/service/TenantAuthServiceImplDbTest.java
git commit -m "feat(auth): expose captcha login and logout api"
```

### Task 9: Install the Spring Security Bearer filter chain

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/auth/security/ApiSecurityErrorWriter.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/security/ApiAuthenticationEntryPoint.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/security/ApiAccessDeniedHandler.java`
- Create: `armada-api/src/main/java/com/armada/platform/auth/security/RedisTokenAuthenticationFilter.java`
- Create: `armada-api/src/main/java/com/armada/boot/config/SecurityConfig.java`
- Test: `armada-api/src/test/java/com/armada/platform/auth/security/SecurityConfigTest.java`

- [ ] **Step 1: Write failing filter-chain tests**

Use `@SpringBootTest`, `@AutoConfigureMockMvc`, `@MockBean SessionService`, `@MockBean AuthService`, and a test-only controller under `/security-test/**`. Stub `authService.createCaptcha(anyString())` to return a fixed `CaptchaChallenge` so the public-route assertion never touches Redis. Keep authenticated test routes outside `/api/**` in this task because the old header-based tenant interceptor is replaced in Task 10:

```java
@RestController
@RequestMapping("/security-test")
static class SecurityTestController {
    @GetMapping("/authenticated")
    String authenticated() {
        return "authenticated";
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin")
    String admin(@AuthenticationPrincipal AuthPrincipal principal) {
        return principal.username();
    }
}
```

```java
@Test
void exactPublicEndpointsAreAllowedButPublicWildcardIsNot() throws Exception {
    mockMvc.perform(get("/api/public/auth/captcha"))
            .andExpect(status().isOk());
    when(authService.login(any(), anyString())).thenReturn(loginVO());
    mockMvc.perform(post("/api/public/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"username":"admin","password":"armada123",
                             "captchaId":"cap-1","captchaCode":"A7KD"}
                            """))
            .andExpect(status().isOk());
    mockMvc.perform(get("/api/public/security-test/not-allowed"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.code()));
}

@Test
void validRedisSessionBuildsPrincipalAndAuthority() throws Exception {
    when(sessionService.findAndTouch("raw-token")).thenReturn(Optional.of(adminSession()));
    mockMvc.perform(get("/security-test/admin")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer raw-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value("admin"));
}

@Test
void userRoleCannotCallAdminEndpoint() throws Exception {
    when(sessionService.findAndTouch("user-token")).thenReturn(Optional.of(userSession()));
    mockMvc.perform(get("/security-test/admin")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.code()));
}

@Test
void redisFailureReturns503InsteadOfAnonymousFallback() throws Exception {
    when(sessionService.findAndTouch("raw-token"))
            .thenThrow(new AuthServiceUnavailableException(new RedisConnectionFailureException("down")));
    mockMvc.perform(get("/security-test/authenticated")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer raw-token"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value(ErrorCode.AUTH_SERVICE_UNAVAILABLE.code()));
}
```

Add exact 401 coverage. Expired and superseded tokens both appear as an empty `SessionService` result at the filter boundary; their distinct storage/lifetime causes are covered in Tasks 5 and 6:

```java
@Test
void missingToken_returns401Json() throws Exception {
    mockMvc.perform(get("/security-test/authenticated"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.code()));
}

@ParameterizedTest
@ValueSource(strings = {"Basic abc", "Bearer "})
void malformedAuthorization_returns401Json(String header) throws Exception {
    mockMvc.perform(get("/security-test/authenticated")
                    .header(HttpHeaders.AUTHORIZATION, header))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.code()));
}

@ParameterizedTest
@ValueSource(strings = {"unknown-token", "expired-token", "superseded-token"})
void nonCurrentSession_returns401Json(String token) throws Exception {
    when(sessionService.findAndTouch(token)).thenReturn(Optional.empty());
    mockMvc.perform(get("/security-test/authenticated")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.code()));
}

@Test
void logoutMakesSameTokenUnauthorizedOnNextRequest() throws Exception {
    AtomicBoolean loggedOut = new AtomicBoolean();
    when(sessionService.findAndTouch("logout-token")).thenAnswer(invocation ->
            loggedOut.get() ? Optional.empty() : Optional.of(adminSession()));
    doAnswer(invocation -> {
        loggedOut.set(true);
        return null;
    }).when(sessionService).logout("logout-token");

    mockMvc.perform(post("/api/auth/logout")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer logout-token"))
            .andExpect(status().isOk());
    mockMvc.perform(get("/security-test/authenticated")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer logout-token"))
            .andExpect(status().isUnauthorized());
}

private AuthSession adminSession() {
    return session(UserRole.ADMIN);
}

private AuthSession userSession() {
    return session(UserRole.USER);
}

private AuthSession session(UserRole role) {
    return new AuthSession(
            1L, 1L, "admin", "管理员", role, "demo", "演示租户A",
            1_752_537_600_000L, 1_752_537_600_000L, 1_752_624_000_000L);
}

private LoginVO loginVO() {
    return new LoginVO(
            "raw-token", "Bearer", 1800L, 1_752_624_000_000L,
            new UserIdentityVO(1L, "admin", "管理员", UserRole.ADMIN),
            new TenantIdentityVO(1L, "demo", "演示租户A"));
}
```

- [ ] **Step 2: Run and verify RED**

```bash
./dbtest.sh SecurityConfigTest
```

Expected: FAIL because the explicit security chain and JSON security handlers do not exist.

- [ ] **Step 3: Implement the filter**

`RedisTokenAuthenticationFilter` extends `OncePerRequestFilter`:

```java
try {
    Optional<String> token = tokenParser.resolve(request);
    if (token.isPresent()) {
        Optional<AuthSession> session = sessionService.findAndTouch(token.get());
        if (session.isPresent()) {
            AuthPrincipal principal = toPrincipal(session.get());
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority(principal.role().authority())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
    }
} catch (AuthServiceUnavailableException ex) {
    errorWriter.write(response, HttpStatus.SERVICE_UNAVAILABLE,
            ErrorCode.AUTH_SERVICE_UNAVAILABLE);
    return;
} catch (BusinessException ex) {
    errorWriter.write(response, HttpStatus.UNAUTHORIZED,
            ErrorCode.UNAUTHENTICATED);
    return;
}
filterChain.doFilter(request, response);
```

Never log the raw token or Authorization header. Let the authorization rules invoke the 401 entry point when no valid session was restored.

Implement `toPrincipal` as a field-for-field conversion from `AuthSession`:

```java
private static AuthPrincipal toPrincipal(AuthSession session) {
    return new AuthPrincipal(
            session.userId(), session.tenantId(), session.username(), session.displayName(),
            session.role(), session.tenantCode(), session.tenantName());
}
```

- [ ] **Step 4: Configure exact authorization rules**

```java
@Bean
SecurityFilterChain apiSecurity(
        HttpSecurity http,
        RedisTokenAuthenticationFilter tokenFilter,
        ApiAuthenticationEntryPoint entryPoint,
        ApiAccessDeniedHandler deniedHandler) throws Exception {
    return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/api/public/auth/captcha").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/public/auth/login").permitAll()
                    .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(deniedHandler))
            .addFilterBefore(tokenFilter, AnonymousAuthenticationFilter.class)
            .build();
}

@Bean
UserDetailsService disabledLocalUserDetailsService() {
    return username -> {
        throw new UsernameNotFoundException("local Spring Security users are disabled");
    };
}
```

Enable method security with `@EnableMethodSecurity`. The explicit rejecting `UserDetailsService` prevents Spring Boot from creating and logging an unused generated default password; all real credentials continue through `DefaultAuthService`. `ApiSecurityErrorWriter` writes `ApiResponse.error` with JSON content type and the supplied HTTP status; the entry point uses `UNAUTHENTICATED`, and the denied handler uses `ACCESS_DENIED`.

```java
@Component
public class ApiSecurityErrorWriter {
    private final ObjectMapper objectMapper;

    public ApiSecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, HttpStatus status, ErrorCode error)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.error(error.code(), error.defaultMessage()));
    }
}

@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ApiSecurityErrorWriter writer;

    public ApiAuthenticationEntryPoint(ApiSecurityErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException ex) throws IOException {
        writer.write(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
    }
}

@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {
    private final ApiSecurityErrorWriter writer;

    public ApiAccessDeniedHandler(ApiSecurityErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException ex) throws IOException {
        writer.write(response, HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED);
    }
}
```

Annotate `SecurityConfig` with `@Configuration`, `RedisTokenAuthenticationFilter` and the three JSON handler/writer classes with `@Component`. Default CORS behavior remains same-origin; this task does not add a wildcard origin or a separate preflight whitelist.

- [ ] **Step 5: Run and verify GREEN**

```bash
./dbtest.sh SecurityConfigTest
```

Expected: PASS with exact public matching, 401, 403, role authority, and fail-closed 503.

- [ ] **Step 6: Commit Spring Security**

```bash
git add armada-api/src/main/java/com/armada/platform/auth/security \
  armada-api/src/main/java/com/armada/boot/config/SecurityConfig.java \
  armada-api/src/test/java/com/armada/platform/auth/security/SecurityConfigTest.java
git commit -m "feat(auth): secure api with redis bearer sessions"
```

### Task 10: Derive tenant context from the authenticated principal and remove resolver legacy

**Files:**
- Modify: `armada-api/src/main/java/com/armada/shared/tenant/TenantContextInterceptor.java`
- Modify: `armada-api/src/main/java/com/armada/boot/config/WebMvcConfig.java`
- Modify: `armada-api/src/main/java/com/armada/shared/tenant/TenantContext.java`
- Modify: `armada-api/src/main/java/com/armada/platform/tenant/model/entity/Tenant.java`
- Modify: `armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java`
- Modify: `armada-api/src/test/java/com/armada/shared/tenant/TenantContextInterceptorTest.java`
- Replace: `armada-api/src/test/java/com/armada/boot/config/TenantInterceptorIntegrationTest.java`
- Delete: `armada-api/src/main/java/com/armada/platform/tenant/service/TenantCodeResolver.java`
- Delete: `armada-api/src/main/java/com/armada/platform/tenant/service/impl/TenantCodeResolverImpl.java`
- Delete: `armada-api/src/test/java/com/armada/platform/tenant/service/TenantCodeResolverImplDbTest.java`
- Modify: `armada-api/src/main/resources/application.yml`: remove dev login.

- [ ] **Step 1: Rewrite the interceptor unit tests first**

```java
@Test
void preHandle_setsTenantFromAuthenticatedPrincipalAndIgnoresHeader() {
    AuthPrincipal principal = principalWithTenant(1L);
    SecurityContextHolder.getContext().setAuthentication(authentication(principal));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Tenant-Code", "demo2");
    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    assertThat(TenantContext.get()).isEqualTo(1L);
}

@Test
void preHandle_withoutAuthPrincipal_failsClosed() {
    assertThatThrownBy(() -> interceptor.preHandle(
            new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCode.UNAUTHENTICATED.code());
}

@Test
void afterCompletion_alwaysClearsThreadLocal() {
    TenantContext.set(9L);
    interceptor.afterCompletion(request(), response(), new Object(), null);
    assertThat(TenantContext.get()).isNull();
}

private AuthPrincipal principalWithTenant(Long tenantId) {
    return new AuthPrincipal(
            1L, tenantId, "admin", "管理员", UserRole.ADMIN, "demo", "演示租户A");
}

private Authentication authentication(AuthPrincipal principal) {
    return UsernamePasswordAuthenticationToken.authenticated(
            principal, null, List.of(new SimpleGrantedAuthority(principal.role().authority())));
}

private MockHttpServletRequest request() {
    return new MockHttpServletRequest();
}

private MockHttpServletResponse response() {
    return new MockHttpServletResponse();
}

@AfterEach
void clearContexts() {
    TenantContext.clear();
    SecurityContextHolder.clearContext();
}
```

- [ ] **Step 2: Add the end-to-end tenant spoofing test**

Replace the old header-based integration test with `@MockBean SessionService` and import this probe controller:

```java
@RestController
static class TenantProbeController {
    @GetMapping("/api/test/tenant-context")
    ApiResponse<Long> currentTenant() {
        return ApiResponse.ok(TenantContext.get());
    }

    @GetMapping("/api/test/tenant-context/failure")
    ApiResponse<Void> failAfterTenantWasSet() {
        throw new BusinessException(ErrorCode.VALIDATION);
    }
}

@BeforeEach
void stubTenantOneSession() {
    when(sessionService.findAndTouch("tenant-one-token")).thenReturn(Optional.of(
            new AuthSession(
                    1L, 1L, "admin", "管理员", UserRole.ADMIN, "demo", "演示租户A",
                    1_752_537_600_000L, 1_752_537_600_000L, 1_752_624_000_000L)));
}

@Test
void authenticatedTenantCannotBeChangedByTenantHeader() throws Exception {
    mockMvc.perform(get("/api/test/tenant-context")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer tenant-one-token")
                    .header("X-Tenant-Code", "demo2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(1L));
    assertThat(TenantContext.get()).isNull();
}

@Test
void headerWithoutTokenIsStillUnauthorized() throws Exception {
    mockMvc.perform(get("/api/test/tenant-context")
                    .header("X-Tenant-Code", "demo"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.code()));
}

@Test
void exceptionalRequestAlsoClearsTenantContext() throws Exception {
    mockMvc.perform(get("/api/test/tenant-context/failure")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer tenant-one-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION.code()));
    assertThat(TenantContext.get()).isNull();
}
```

Register the nested controller with `@Import(TenantProbeController.class)`. These assertions prove tenant 1 comes from the session, the spoofed `demo2` header is ignored, and the interceptor clears its thread-local after both successful and exceptional MVC execution.

- [ ] **Step 3: Run and verify RED**

```bash
./dbtest.sh 'TenantContextInterceptorTest,TenantInterceptorIntegrationTest'
```

Expected: FAIL because the interceptor still reads `X-Tenant-Code` and the old resolver remains wired.

- [ ] **Step 4: Replace header resolution with principal resolution**

`TenantContextInterceptor` becomes constructor-free and uses:

```java
@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
            || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
    TenantContext.set(principal.tenantId());
    return true;
}

@Override
public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
        Object handler, Exception ex) {
    TenantContext.clear();
}
```

`WebMvcConfig` registers it for `/api/**` and excludes exactly:

```java
registry.addInterceptor(new TenantContextInterceptor())
        .addPathPatterns("/api/**")
        .excludePathPatterns(
                "/api/public/auth/captcha",
                "/api/public/auth/login");
```

Update `TenantContext` documentation to say Spring Security identity, not tenant header, supplies the HTTP tenant.

- [ ] **Step 5: Remove resolver and development configuration**

Delete `TenantCodeResolver`, `TenantCodeResolverImpl`, and their DB test. Remove obsolete `TENANT_MISSING` and `TENANT_NOT_FOUND` error codes. Remove the entire `armada.dev-login.password` block from `application.yml`. Update `Tenant` Javadocs so `tenantCode` is only an internal/display identifier, not a request header or login field. The tenant table and `TenantMapper.selectById` remain.

- [ ] **Step 6: Run and verify GREEN**

```bash
./dbtest.sh 'TenantContextInterceptorTest,TenantInterceptorIntegrationTest'
```

Expected: PASS; a spoofed tenant header has no effect and a header-only request is 401.

- [ ] **Step 7: Commit tenant identity cutover**

```bash
git add armada-api/src/main/java/com/armada/shared/tenant/TenantContext.java \
  armada-api/src/main/java/com/armada/shared/tenant/TenantContextInterceptor.java \
  armada-api/src/main/java/com/armada/boot/config/WebMvcConfig.java \
  armada-api/src/main/java/com/armada/platform/tenant/model/entity/Tenant.java \
  armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java \
  armada-api/src/main/resources/application.yml \
  armada-api/src/test/java/com/armada/shared/tenant/TenantContextInterceptorTest.java \
  armada-api/src/test/java/com/armada/boot/config/TenantInterceptorIntegrationTest.java
git add -u armada-api/src/main/java/com/armada/platform/tenant/service/TenantCodeResolver.java \
  armada-api/src/main/java/com/armada/platform/tenant/service/impl/TenantCodeResolverImpl.java \
  armada-api/src/test/java/com/armada/platform/tenant/service/TenantCodeResolverImplDbTest.java
git commit -m "refactor(auth): derive tenant from authenticated user"
```

### Task 11: Migrate existing MockMvc DB tests and run full verification

**Files:**
- Create: `armada-api/src/test/java/com/armada/testsupport/TestAuthentication.java`
- Create: `armada-api/src/test/java/com/armada/testsupport/TestAuthenticationConfiguration.java`
- Modify: `armada-api/src/test/java/com/armada/account/controller/AccountControllerDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/account/controller/AccountImportControllerDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/admin/controller/CountryControllerDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/controller/MarketingTaskControllerDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/controller/JoinTaskControllerDbTest.java`

- [ ] **Step 1: Add test-only authentication support**

Create a primary test `SessionService` that accepts only tokens produced by this helper:

```java
public final class TestAuthentication {
    private TestAuthentication() {}

    public static String bearer(long tenantId) {
        return "Bearer test-tenant-" + tenantId;
    }

    static Optional<AuthSession> resolve(String rawToken) {
        if (!rawToken.startsWith("test-tenant-")) return Optional.empty();
        long tenantId = Long.parseLong(rawToken.substring("test-tenant-".length()));
        return Optional.of(new AuthSession(
                10_000L + tenantId, tenantId, "test-user-" + tenantId,
                "测试用户", UserRole.ADMIN, "test-" + tenantId, "测试租户",
                0L, 0L, Long.MAX_VALUE));
    }
}
```

`TestAuthenticationConfiguration` lives only under `src/test` and is imported explicitly by affected DB controller tests:

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestAuthenticationConfiguration {

    @Bean
    @Primary
    SessionService testSessionService() {
        return new SessionService() {
            @Override
            public IssuedSession issue(LoginIdentity identity) {
                throw new UnsupportedOperationException("DB controller tests do not log in");
            }

            @Override
            public Optional<AuthSession> findAndTouch(String rawToken) {
                return TestAuthentication.resolve(rawToken);
            }

            @Override
            public void logout(String rawToken) {
                // DB controller tests do not call the auth logout endpoint.
            }

            @Override
            public void invalidateUser(Long userId) {
                // DB controller tests do not mutate Armada users.
            }
        };
    }
}
```

- [ ] **Step 2: Replace header setup in existing full-context tests**

For each listed DB controller test:

1. add `@Import(TestAuthenticationConfiguration.class)`;
2. delete `TENANT_HEADER` constants;
3. replace `.header("X-Tenant-Code", "demo")` with:

```java
.header(HttpHeaders.AUTHORIZATION, TestAuthentication.bearer(TEST_TENANT_ID))
```

Do not change standalone controller tests because they intentionally do not install the Spring Security filter chain.

- [ ] **Step 3: Search for legacy paths and headers**

```bash
rg -n "X-Tenant-Code|DEV_LOGIN_PASSWORD|armada\.dev-login|dev-|TenantCodeResolver|TenantAuthService|TENANT_MISSING|TENANT_NOT_FOUND" \
  src/main src/test
```

Expected: no production match. Test matches are allowed only in the explicit spoofing assertion proving the header is ignored.

- [ ] **Step 4: Run focused auth verification**

```bash
mvn -q -Dtest='AuthPropertiesTest,DefaultCaptchaServiceTest,RedisCaptchaStoreTest,RedisLoginRateLimiterTest,SessionTokenCodecTest,DefaultSessionServiceTest,RedisSessionRepositoryTest,DefaultAuthServiceTest,AuthControllerTest,GlobalExceptionHandlerTest,TenantContextInterceptorTest' test
./dbtest.sh 'SecurityConfigTest,SysUserSchemaDbTest,SysUserMapperDbTest,TenantMapperDbTest,TenantInterceptorIntegrationTest'
```

Expected: all selected unit, Redis, security, MySQL, and tenant isolation tests pass with zero failures/errors.

- [ ] **Step 5: Run all controller DB tests affected by authentication**

```bash
./dbtest.sh 'AccountControllerDbTest,AccountImportControllerDbTest,CountryControllerDbTest,MarketingTaskControllerDbTest,JoinTaskControllerDbTest'
```

Expected: PASS using only test-source Bearer authentication.

- [ ] **Step 6: Run the complete backend suite and package**

```bash
./dbtest.sh '*'
mvn -q -DskipTests package
```

Expected: the full Maven suite reports zero failures/errors, then packaging exits 0.

- [ ] **Step 7: Inspect secret/logging and diff boundaries**

```bash
rg -n "password|captcha|Authorization|token" src/main/java/com/armada/platform/auth
git diff --check
git status --short
```

Review every logging statement returned by the search. Expected: no statement logs the password, captcha answer, raw token, Authorization header, Redis JSON, or password hash; `git diff --check` is silent; only in-scope auth/test files are changed.

- [ ] **Step 8: Commit test migration and final cleanup**

```bash
git add armada-api/src/test/java/com/armada/testsupport/TestAuthentication.java \
  armada-api/src/test/java/com/armada/testsupport/TestAuthenticationConfiguration.java \
  armada-api/src/test/java/com/armada/account/controller/AccountControllerDbTest.java \
  armada-api/src/test/java/com/armada/account/controller/AccountImportControllerDbTest.java \
  armada-api/src/test/java/com/armada/admin/controller/CountryControllerDbTest.java \
  armada-api/src/test/java/com/armada/marketing/controller/MarketingTaskControllerDbTest.java \
  armada-api/src/test/java/com/armada/task/controller/JoinTaskControllerDbTest.java
git commit -m "test(auth): migrate controller tests to bearer sessions"
```

- [ ] **Step 9: Final history verification**

If final searches or full-suite verification reveal a defect, return to the task that owns that behavior, add a focused regression test, fix it, rerun that task's verification, and use that task's exact scoped commit command. Do not create a catch-all or empty commit. Then verify the implementation history and clean diff:

```bash
git log --oneline --decorate -12
git status --short
```

Expected: the auth commits are present; no in-scope file remains modified or untracked. Pre-existing unrelated worktree status entries may remain and must not be changed.

---

## Acceptance checklist

- [ ] `admin` plus `armada123` and a fresh captcha can log in; the DB password starts with `{bcrypt}$2` and is not plaintext.
- [ ] Captchas expire in two minutes, are case-insensitive, and are consumed once even on failure.
- [ ] CAPTCHA issuance is limited to 20/IP/minute; login is limited to 5 failures per username or IP per 10 minutes.
- [ ] A second login atomically invalidates the first token.
- [ ] Sessions expire after 30 minutes idle and never survive beyond 24 hours.
- [ ] Logout invalidates the current session; stale logout cannot invalidate a newer session.
- [ ] Only exact captcha/login routes are public.
- [ ] Missing/invalid token is 401 JSON; wrong role is 403 JSON; Redis auth outage is 503 JSON.
- [ ] `X-Tenant-Code` cannot select or alter the authenticated tenant.
- [ ] `TenantContext` is cleared after both successful and exceptional requests.
- [ ] No `deleted_at` exists on `sys_user`; disabling is the only account removal state.
- [ ] No password, captcha answer, raw token, Authorization header, or Redis session body appears in logs.
- [ ] Focused tests, affected controller DB tests, the full Maven suite, and packaging all pass.
