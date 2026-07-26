# 用户登录与现有 RBAC 接入设计

## 1. 范围

本期把临时“租户码 + 统一密码 + dev token”替换为真实登录，并直接复用现有
`sys_user`、`sys_role`、`sys_menu`、`sys_user_role`、`sys_role_menu`。

本期实现用户名、密码、图片验证码、Spring Security、Redis 单会话、当前用户、退出登录、
动态菜单和按钮权限接线。暂不实现多租户选择、刷新 Token、JWT、最高控和审计日志。

## 2. 当前阶段租户口径

- 用户名保持租户内唯一，一个用户属于一个租户，一个用户可绑定多个角色。
- 登录页不输入租户码，也不能通过域名确定租户。
- 当前阶段从 `armada.auth.default-tenant-id` 确定唯一默认租户，登录只查询该租户用户。
- 后期开放多租户时，再增加密码校验后的租户选择；本期不预留选择接口或临时 Token。

## 3. 认证方案

- `GET /api/public/auth/captcha` 生成四位图片验证码，Redis 保存答案 120 秒且登录时一次性消费。
- `POST /api/public/auth/login` 校验验证码、默认租户、用户状态、租户状态和 BCrypt 密码。
- 登录成功生成 32 字节安全随机 Bearer Token，Redis 只使用 Token 的 SHA-256 哈希作为键。
- 同一用户只允许一个有效会话；新登录原子删除旧会话。
- 会话空闲 30 分钟失效，首次登录 24 小时后绝对失效。
- `GET /api/auth/me` 返回可信用户和租户信息，`POST /api/auth/logout` 立即删除当前会话。

## 4. 请求鉴权和租户隔离

`TokenAuthenticationFilter` 校验 Redis 会话，从数据库读取当前启用用户、启用角色和有效权限，
建立 Spring Security 上下文，并在进入业务链前把会话中的 `tenantId` 写入 `TenantContext`。
请求结束后无条件清理两个上下文。

后端不再读取 `X-Tenant-Code`。角色或菜单状态变更后，下一次请求重新计算权限，不依赖权限缓存，
因此不会继续沿用已失效权限。

`/api/public/**` 中现有推广落地页接口继续公开；其余 `/api/**` 默认必须认证。

## 5. 权限

- Spring Authority 包含启用角色的 `ROLE_<roleCode>` 和有效菜单/按钮的 `perm_key`。
- `TENANT_ADMIN` 继续由现有菜单服务动态获得本租户全部有效权限。
- `/api/tenant/me/menus` 使用认证用户 ID 返回动态菜单树。
- 系统管理写接口按既有 `perm_key` 使用 `@PreAuthorize`；权限校验不能代替租户隔离。

## 6. 数据与初始化

不新增认证用户表，不修改已执行的 V071。新增下一版 Flyway 迁移，为默认租户初始化 `admin`
用户并绑定已有 `TENANT_ADMIN` 角色。数据库只保存 `{bcrypt}...` 哈希，不保存或注释明文密码。

## 7. 删除的临时路径

- `armada.dev-login.password` / `DEV_LOGIN_PASSWORD`
- 登录请求中的 `tenantCode`
- `dev-{tenantCode}` 占位 Token
- `X-Tenant-Code` 请求头租户身份
- 前端固定管理员角色与通配权限

## 8. 验证

- 验证码一次性、过期、错误场景。
- 正确/错误密码、停用用户、停用租户。
- 新登录顶替旧 Token、退出、空闲过期和绝对过期。
- 无 Token、错误 Token 返回统一 401 JSON；无权限返回统一 403 JSON。
- 伪造 `X-Tenant-Code` 不能改变会话租户。
- 初始管理员、用户查询和租户隔离使用真库 DbTest 验证。
