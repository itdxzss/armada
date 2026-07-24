# Armada 用户登录与 Redis 单会话鉴权设计

- 日期：2026-07-15
- 范围：`armada/armada-api` 登录、认证、租户身份注入与基础角色
- 状态：已被 `2026-07-25-login-authentication-rbac-design.md` 取代；仅保留历史决策背景
- 取代：`docs/superpowers/specs/2026-06-24-armada-tenant-login-foundation-design.md` 中的临时登录与请求头租户身份方案

## 1. 背景

Armada 当前已有租户表、`TenantContext`、MyBatis-Plus 租户行隔离和一个临时登录垫片。临时方案使用“租户码 + 全局统一密码”登录，返回 `dev-{tenantCode}` 占位 token；业务请求继续通过可伪造的 `X-Tenant-Code` 声明租户。它能支撑前期联调，但不构成真实身份认证。

本设计把临时垫片升级为真实的用户登录：用户使用全平台唯一的用户名、密码和图片验证码登录；Spring Security 负责认证与角色上下文；Redis 保存验证码和服务端会话；租户身份只能来自已认证用户，不能由客户端自行指定。

## 2. 已确认决策

- 登录字段为：用户名、密码、图片验证码，不输入租户码。
- 用户名全平台唯一，用户固定属于一个租户。
- 第一阶段只有租户内 `ADMIN` 和 `USER` 两个固定角色。
- `ADMIN` 不是平台管理员，不能跨租户访问。
- 使用 Spring Security，不使用手写控制器拦截代替安全框架。
- 使用高强度随机 Bearer Token，不使用 JWT。
- Redis 保存验证码和登录会话，服务端可立即使 token 失效。
- 一个用户只允许一个有效会话；新登录会使旧 token 立即失效。
- 30 分钟无操作失效，单次登录最长 24 小时。
- 公开白名单只精确包含验证码和登录接口。
- Flyway 创建用户表，并只为 `tenant_id=1` 初始化一个 `admin` 用户。
- 初始测试密码沿用当前的 `armada123`，数据库与迁移脚本只保存其 BCrypt 哈希。

## 3. 目标

1. 提供可以真实校验用户身份的登录、当前用户和退出接口。
2. 密码只保存自适应单向哈希，任何接口、日志和数据库列都不能返回明文密码。
3. 让每个受保护请求的 `tenantId` 来自服务端会话，并继续复用现有 MyBatis 租户行隔离。
4. 支持退出和新登录顶替旧登录，并提供按用户立即失效会话的服务能力，供后续停用用户或改密流程调用。
5. 在 Redis 故障、token 异常或身份缺失时失败关闭，不回退到 `X-Tenant-Code`。
6. 为后续租户管理员创建、停用普通用户预留稳定的用户与角色模型。

## 4. 非目标

本阶段不包含：

- 用户管理接口和用户管理页面；
- 修改密码、忘记密码和密码重置流程；
- 自定义角色、菜单权限、按钮权限和角色权限配置表；
- 平台级管理员和跨租户操作；
- 一个用户加入多个租户或切换租户；
- 多端同时登录；
- JWT、刷新 token 和 OAuth2/OIDC；
- 前端登录页实施。本规格定义前端所需 API 契约，前端改造另行规划。

`ADMIN` 与 `USER` 会进入认证上下文，但本阶段没有新增管理员业务接口，因此两者对现有业务接口的访问范围相同。后续用户管理接口必须显式使用 `ADMIN` 权限。

## 5. 方案比较

### 5.1 随机 Token + Redis 会话（采用）

登录成功后生成不可预测的随机 token，Redis 保存服务端会话。每个受保护请求查询 Redis，因此退出、新登录顶替和未来的用户停用都可以立即生效。客户端 token 不携带用户或租户数据，不能通过修改 token 内容切换身份。

### 5.2 JWT + Redis 白名单（不采用）

JWT 可以携带用户声明，但为支持立即失效仍需每次访问 Redis。它没有获得无状态优势，却增加签名密钥、声明过期、刷新和 Redis 状态同步的复杂度。

### 5.3 Spring Session + Cookie（不采用）

Cookie Session 适合同站传统 Web 应用，但当前管理端按 token 做路由守卫和接口认证。采用 Bearer Token 能以更小改动接入现有前端，也不需要引入基于 Cookie 的 CSRF 流程。

## 6. 总体架构

新增或调整的核心单元：

- `UserMapper`：查询登录用户、更新最后登录时间。
- `CaptchaService`：生成图片验证码、写入 Redis、一次性校验。
- `AuthService`：编排验证码、用户、租户和密码校验。
- `SessionService`：原子创建单会话、读取、续期和失效会话。
- `TokenAuthenticationFilter`：解析 Bearer Token，通过 Redis 恢复身份并写入 Spring Security 上下文。
- `AuthPrincipal`：只承载 `userId`、`tenantId`、`username` 和 `role` 等可信身份字段。
- `SecurityConfig`：定义精确公开白名单、无状态安全链、401 和 403 输出。
- `TenantContextInterceptor`：改为从 `AuthPrincipal` 读取 `tenantId`，请求完成后继续清理 `TenantContext`。

请求链路：

1. 浏览器获取验证码，服务端将答案写入 Redis 并返回 `captchaId` 和 PNG 图片。
2. 浏览器提交用户名、密码、`captchaId` 和验证码。
3. 服务端一次性消费验证码，查询全局唯一用户名，校验用户、租户与 BCrypt 密码。
4. 登录成功后生成随机 token，原子替换该用户的旧 Redis 会话。
5. 浏览器后续发送 `Authorization: Bearer <token>`。
6. Spring Security 过滤器校验 Redis 会话并建立 `AuthPrincipal`。
7. MVC 租户拦截器从 `AuthPrincipal` 设置 `TenantContext`，MyBatis 自动注入 `tenant_id` 条件。
8. 请求完成后清理 `TenantContext` 和 Spring Security 上下文。

## 7. 用户数据模型

使用 `sys_user` 表，避免与数据库保留词或业务侧 WhatsApp `account` 概念混淆。

| 列 | 类型 | 约束与语义 |
| --- | --- | --- |
| `id` | `BIGINT` | 主键，自增 |
| `tenant_id` | `BIGINT` | 非空，所属租户 |
| `username` | `VARCHAR(64)` | 非空、全局唯一；保存前去除首尾空白并转小写 |
| `password_hash` | `VARCHAR(255)` | 非空，Spring Security `DelegatingPasswordEncoder` 输出 |
| `display_name` | `VARCHAR(128)` | 非空，展示名称 |
| `role` | `VARCHAR(16)` | 非空，CHECK 约束只允许 `ADMIN` 或 `USER` |
| `status` | `TINYINT` | 非空，CHECK 约束只允许 `1=ENABLED`、`0=DISABLED` |
| `last_login_at` | `DATETIME` | 可空，最近一次成功登录时间 |
| `created_at` | `DATETIME` | 非空，创建时间 |
| `updated_at` | `DATETIME` | 非空，更新时间 |

索引：

- `PRIMARY KEY (id)`；
- `UNIQUE KEY uq_sys_user_username (username)`；
- `KEY idx_sys_user_tenant_status (tenant_id, status)`。

用户不做物理删除。停用账号保留用户名、租户归属和审计关联，避免同一用户名被重新注册后产生身份混淆。

### 7.1 登录查询与租户拦截器

登录时还没有 `TenantContext`，而 `sys_user` 带 `tenant_id`。因此只有 `selectForLoginByUsername` 这一条登录查询允许显式忽略 MyBatis 租户拦截器。该查询必须满足以下限制：

- 只接受规范化后的完整用户名，不支持模糊搜索；
- 只返回登录校验需要的单个用户；
- 不提供列表或跨租户查询能力；
- 结果只在认证服务内部使用，不直接返回客户端。

所有登录后的用户查询仍使用正常租户拦截，不得复用这条跨租户登录查询。

成功登录时更新 `last_login_at` 也发生在常规租户上下文建立之前。该更新允许使用单独的登录专用 mapper 方法忽略租户拦截器，但 SQL 必须同时精确匹配已经认证成功的 `user_id` 和 `tenant_id`；禁止只按用户 ID 做无租户条件的通用更新。

## 8. Flyway 迁移与初始管理员

使用下一个可用的 Flyway 版本创建 `sys_user` 表并插入：

- `tenant_id=1`；
- `username=admin`；
- `display_name=管理员`；
- `role=ADMIN`；
- `status=1`；
- `password_hash` 为 `armada123` 经 `DelegatingPasswordEncoder` 生成的 `{bcrypt}...` 哈希。

迁移脚本不得包含明文密码注释。`armada123` 仅作为当前测试阶段的初始凭据；非测试环境在对外开放前必须通过受控 SQL 更新为新密码的哈希。密码自助修改和管理员重置接口属于后续用户管理范围。

登录改造完成后删除以下临时能力：

- `armada.dev-login.password` / `DEV_LOGIN_PASSWORD` 配置；
- `dev-{tenantCode}` 占位 token；
- 登录请求中的 `tenantCode`；
- 业务请求对 `X-Tenant-Code` 的信任。

现有 `tenant` 表、`TenantContext` 和 MyBatis 租户行隔离保留。

## 9. 密码存储与校验

使用 Spring Security `DelegatingPasswordEncoder`，当前编码算法为 BCrypt。数据库保存的格式类似：

```text
{bcrypt}$2a$10$...
```

BCrypt 是带随机盐和计算成本的单向哈希，不可解密。登录时调用 `PasswordEncoder.matches(rawPassword, passwordHash)` 判断输入是否匹配，不能重新编码后直接比较字符串。

安全要求：

- 明文密码只能短暂存在于登录请求处理内存中；
- 禁止写入日志、Redis、响应、异常消息和审计详情；
- 未找到用户名时也对固定的虚拟 BCrypt 哈希执行一次 `matches`，减小通过响应时间枚举用户名的差异；
- 用户名不存在、密码错误、用户停用和租户停用统一返回“用户名或密码错误”；
- BCrypt strength 固定为 `10`，与 Spring Security `BCryptPasswordEncoder` 默认成本一致；以后提高成本需要独立评估并采用兼容旧哈希的渐进升级方案。

管理员不会知道或找回用户原密码。后续忘记密码流程只能生成新哈希覆盖旧哈希，并使旧会话失效。

## 10. 图片验证码

### 10.1 生成

`GET /api/public/auth/captcha` 使用 `SecureRandom` 生成 4 位字母数字验证码，排除容易混淆的字符。图片为 PNG，包含基础干扰线和噪点。

响应数据：

```json
{
  "captchaId": "随机 UUID",
  "imageBase64": "data:image/png;base64,...",
  "expiresInSeconds": 120
}
```

响应设置 `Cache-Control: no-store`。验证码答案不返回客户端，只在 Redis 保存规范化后的值。

### 10.2 Redis 与校验

```text
auth:captcha:{captchaId} -> normalizedAnswer
TTL: 120 秒
```

验证码忽略大小写。登录时使用原子 get-and-delete 读取答案；无论正确、错误还是后续登录校验失败，该验证码都不能再次使用。验证码不存在、过期或不匹配统一返回“验证码错误或已过期”。

图片验证码只是降低自动化攻击成本，不能替代登录限流。

## 11. Redis 单会话模型

### 11.1 Token

登录成功后使用安全随机源生成至少 32 字节随机值，并用 URL-safe Base64 编码为客户端 token。服务端用 SHA-256 计算 `tokenHash`，Redis 不保存可直接使用的原始 token。

### 11.2 Redis 键

```text
auth:session:{tokenHash}       -> SessionRecord JSON
auth:user-session:{userId}    -> tokenHash
```

`SessionRecord` 至少包含：

- `userId`；
- `tenantId`；
- `username`；
- `role`；
- `issuedAt`；
- `lastAccessAt`；
- `absoluteExpiresAt`。

两个键的当前 TTL 都不得超过以下两者中的较小值：

- 距最后一次有效访问 30 分钟；
- 距首次登录满 24 小时的剩余时间。

正常请求可以续期空闲 TTL，但绝不能修改 `issuedAt` 或突破 `absoluteExpiresAt`。session 键与 user-session 指针必须在同一个原子操作内同步续期，不能出现一方已续期而另一方提前过期。

### 11.3 单端登录原子性

创建新会话必须通过 Redis 原子操作完成：

1. 读取 `auth:user-session:{userId}` 的旧 `tokenHash`；
2. 删除旧的 `auth:session:{oldTokenHash}`；
3. 写入新的 session 键；
4. 将 user-session 指针替换为新 `tokenHash`；
5. 为两个新键设置一致的 TTL。

实现使用 Redis Lua 脚本完成新登录替换、双键续期和退出清理，不能用多个无保护命令拼接。验证请求除读取 session 外，还必须确认 user-session 指针仍指向当前 `tokenHash`，从而保证并发登录后只有最后一次登录有效。

退出登录只删除当前 token。删除 user-session 指针时必须先确认其仍等于当前 `tokenHash`，避免旧 token 的延迟退出误删新会话。

## 12. 登录限流

验证码通过后、执行 BCrypt 校验前，按用户名和客户端 IP 执行失败限流。初始规则：任一维度在 10 分钟内连续失败 5 次，暂时拒绝新的密码校验；成功登录后清理对应失败计数。

限流键使用哈希后的规范化用户名，避免 Redis 运维视图直接暴露登录名。默认以 Servlet `remoteAddr` 作为客户端 IP；只有部署明确配置受信任反向代理后，才允许由 Spring 的标准 forwarded-header 处理解析代理头，业务代码不能自行信任任意 `X-Forwarded-For`。

达到限制时返回“登录尝试过于频繁，请稍后再试”。验证码错误不消耗 BCrypt 失败次数。验证码生成接口按 IP 限制为每分钟最多 20 次，超出后返回相同的频率限制错误，防止批量生成图片。

## 13. API 契约

所有响应继续使用 Armada 的统一结构：

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

### 13.1 获取验证码

```text
GET /api/public/auth/captcha
认证：不需要
```

返回第 10 节定义的验证码数据。

### 13.2 登录

```text
POST /api/public/auth/login
认证：不需要
Content-Type: application/json
```

请求：

```json
{
  "username": "admin",
  "password": "用户输入的明文密码",
  "captchaId": "验证码 ID",
  "captchaCode": "图片中的字符"
}
```

成功响应数据：

```json
{
  "token": "只返回一次的随机 token",
  "tokenType": "Bearer",
  "idleTimeoutSeconds": 1800,
  "absoluteExpiresAt": 1784123456789,
  "user": {
    "id": 1,
    "username": "admin",
    "displayName": "管理员",
    "role": "ADMIN"
  },
  "tenant": {
    "id": 1,
    "code": "demo",
    "name": "演示租户A"
  }
}
```

成功后更新 `last_login_at`。若 Redis 会话写入失败，登录整体失败，不能只返回 token 而没有服务端会话。

### 13.3 当前用户

```text
GET /api/auth/me
认证：Bearer Token
```

返回与登录响应中 `user`、`tenant` 一致的可信身份信息，不返回密码哈希和 token。

### 13.4 退出

```text
POST /api/auth/logout
认证：Bearer Token
```

删除当前会话后返回成功。重复使用已退出 token 访问受保护接口时返回未认证。

## 14. Spring Security 设计

安全链使用无状态模式：

- `SessionCreationPolicy.STATELESS`；
- 不创建 HttpSession；
- Bearer Token 不依赖 Cookie，因此关闭 CSRF；
- CORS 只允许部署配置中明确的管理端来源，不使用带凭据的通配来源；
- 自定义 `AuthenticationEntryPoint` 和 `AccessDeniedHandler` 输出 `ApiResponse` JSON。

公开白名单只包含：

```text
GET  /api/public/auth/captcha
POST /api/public/auth/login
```

除必要的 CORS 预检外，其余请求默认要求认证。不能使用 `/api/public/**` 通配放行；未来新增公开接口必须逐个写入白名单并接受安全审查。

角色转换为 Spring Security Authority：

```text
ADMIN -> ROLE_ADMIN
USER  -> ROLE_USER
```

未来租户管理员接口使用 `@PreAuthorize("hasRole('ADMIN')")`。业务服务仍需依赖 `TenantContext` 做数据边界，角色检查不能代替租户隔离。

## 15. TenantContext 改造

现有 `TenantContextInterceptor` 不再读取或解析 `X-Tenant-Code`。受保护请求进入 MVC 时：

1. 从 Spring Security 上下文取得 `AuthPrincipal`；
2. 缺少合法 principal 时拒绝请求；
3. 将 principal 中的 `tenantId` 写入 `TenantContext`；
4. 请求结束后无条件 `TenantContext.clear()`。

客户端即使继续发送 `X-Tenant-Code`，后端也必须忽略。测试必须证明：属于租户 1 的 token 加上 `X-Tenant-Code: demo2` 后仍只能访问租户 1 数据。

Spring Security 过滤器链在请求结束时负责清理自己的安全上下文；MVC 拦截器继续单独清理 ThreadLocal 租户上下文，防止 Tomcat 线程复用串号。

## 16. 错误处理

新增或调整的业务错误语义：

| 场景 | HTTP 状态 | 业务码 | 客户端消息 |
| --- | --- | --- | --- |
| 验证码错误或过期 | 200 | `40002` | 验证码错误或已过期 |
| 用户名、密码、用户状态或租户状态失败 | 200 | `40103` | 用户名或密码错误 |
| 缺少、错误、过期或被顶替的 token | 401 | `40104` | 登录已失效，请重新登录 |
| 角色无权访问 | 403 | `40301` | 无权执行此操作 |
| 登录尝试过于频繁 | 429 | `42901` | 登录尝试过于频繁，请稍后再试 |
| Redis 无法完成验证码或会话操作 | 503 | `50301` | 认证服务暂不可用，请稍后重试 |

Spring Security 异常发生在控制器之前，必须由安全组件的 JSON handler 输出统一响应，不能依赖 `GlobalExceptionHandler` 捕获。

Redis 故障时失败关闭：

- 获取验证码失败，不返回不可验证的图片；
- 登录创建会话失败，不返回 token；
- 业务鉴权失败，不将 Redis 故障视为匿名放行，也不信任请求头租户码。

日志可以记录错误类型、`userId`、`tenantId`、请求方法和路径，但不得记录密码、验证码答案、原始 token、完整 Authorization 头或 Redis 会话正文。

## 17. 配置与依赖

后端新增：

- `spring-boot-starter-security`；
- `spring-boot-starter-data-redis`。

图片验证码使用 JDK Java2D 和 `SecureRandom` 生成，第一阶段不额外引入验证码框架。

配置项至少包含：

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
```

测试可覆盖配置值，但生产配置不得关闭验证码、单会话检查或 Redis 会话校验。

## 18. 测试策略

### 18.1 单元测试

- 验证码字符集、PNG 输出、2 分钟过期、忽略大小写和一次性消费；
- 正确密码、错误密码和虚拟哈希路径；
- 用户不存在、用户停用、租户停用统一登录失败；
- token 使用安全随机源，Redis 键只使用 token 哈希；
- 新登录原子删除旧会话；
- 旧 token 退出不能误删新会话；
- 30 分钟空闲过期、正常续期和 24 小时绝对过期；
- 登录限流窗口、成功后清零和受信任代理 IP 解析；
- Redis 异常全部失败关闭。

时间相关服务注入 `Clock`，测试不能依赖真实 sleep。

### 18.2 Spring Security 集成测试

- 仅验证码和登录接口无需认证；
- `/api/public/` 下新增的其他测试路径不会自动公开；
- 无 token、格式错误、会话不存在和被顶替 token 返回 401 JSON；
- 合法 token 可以访问业务接口；
- `ADMIN`、`USER` 正确映射为 Spring Authority；
- 无权限返回 403 JSON；
- 退出后原 token 立即无效。

### 18.3 数据库与租户隔离测试

- Flyway 建表、唯一用户名、合法角色和状态约束；
- 初始 `admin` 属于租户 1，密码列不是明文；
- 登录查询可以在无 `TenantContext` 时精确找到全局用户；
- 普通用户查询继续受 MyBatis 租户拦截器限制；
- token 中的租户 1 身份不能通过伪造 `X-Tenant-Code` 访问租户 2；
- 正常结束和异常结束都清理 `TenantContext`。

### 18.4 回归

现有依赖 `X-Tenant-Code` 的控制器测试统一改用认证测试工具注入 Redis 会话，不保留生产代码的请求头后门。运行完整 Maven 测试，重点回归账号、IP、群链接、营销和进群任务的租户隔离。

## 19. 验收标准

满足以下条件才认为登录功能完成：

1. 使用 `admin`、正确密码和一次性图片验证码可以登录。
2. 数据库中没有明文密码，日志中没有密码、验证码或原始 token。
3. 受保护接口只接受 Redis 中存在且仍是用户当前会话的 token。
4. 同一用户第二次登录后，第一个 token 立即返回 401。
5. 连续 30 分钟无请求后 token 失效；持续使用也会在 24 小时后失效。
6. 退出后当前 token 立即失效。
7. 客户端不能通过 `X-Tenant-Code` 或其他参数改变 token 所属租户。
8. Redis 故障时认证相关请求失败关闭。
9. 验证码、密码、会话、安全过滤器和租户隔离测试全部通过。
10. 当前 `dev-*` token、统一测试密码配置和请求头租户信任逻辑已删除。

## 20. 后续演进

完成本规格后，下一阶段可以独立设计租户用户管理：管理员创建用户、停用用户、重置密码和查看本租户用户列表。届时所有用户状态或密码变更必须调用 `SessionService` 使该用户当前会话立即失效。

再后续如确有菜单或操作级权限需求，再从固定角色演进为角色、权限和关联表；本阶段不提前建设完整 RBAC。
