# 变更记录：用户登录认证

- 分支 / worktree：`feature/system-management-rbac` / `.worktrees/system-management-rbac`
- 状态：代码完成并通过本地聚焦验证，待第一测试环境真库与 Redis 联调

## 目标

以默认租户为当前阶段边界，替换临时租户登录，接入用户名、密码、图片验证码、Spring Security、
Redis 单会话及现有 RBAC 动态权限。

## 任务

- [x] 修订登录契约并初始化默认管理员
- [x] 图片验证码和登录认证
- [x] Redis 单会话和 Bearer Token 过滤器
- [x] TenantContext、当前用户、动态菜单和权限接线
- [x] 前端登录及认证请求层改造
- [x] 聚焦测试、编译、前端类型检查与生产构建
- [ ] 第一测试环境真库、Redis 和浏览器联调

## 数据库变更

不新增表。新增下一版 Flyway 迁移，为默认租户初始化管理员用户并绑定 `TENANT_ADMIN`。

## API 变更

- 新增 `GET /api/public/auth/captcha`
- 替换 `POST /api/public/auth/login` 请求与响应
- 新增 `GET /api/auth/me`
- 新增 `POST /api/auth/logout`
- 接通 `GET /api/tenant/me/menus`

## Redis 变更

- `auth:captcha:{captchaId}`：验证码答案
- `auth:session:{tokenHash}`：服务端会话
- `auth:user-session:{userId}`：用户当前 Token 哈希

## 关键约束

- 当前只认证配置的默认租户；暂不做多租户选择。
- Token、密码、验证码和 Authorization 头不得进入日志。
- Redis 故障失败关闭，不回退到租户请求头或固定权限。

## 回滚

回退认证相关代码与前端改造；Flyway 初始化用户属于可保留数据，不删除已有 RBAC 表。

## 本地验证

- 后端 `mvn -DskipTests test-compile`：通过，313 个测试源编译成功。
- 后端聚焦测试：10 项通过，0 失败、0 错误。
- Mapper XML `xmllint --noout`：通过。
- 前端 `pnpm typecheck`：通过。
- 前端 `pnpm build`：通过，生产包构建完成。
- 全量 `mvn test` 在前 29 项通过后进入真库 DbTest；因当前 worktree 缺少 `.env`，停止执行，未冒充真库通过。
