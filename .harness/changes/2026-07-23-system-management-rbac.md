# 变更记录：租户系统管理 RBAC

- 日期 / 分支 / worktree: 2026-07-23 至 2026-07-24 / `feature/system-management-rbac` / `.worktrees/system-management-rbac`
- 需求来源: 租户管理员版系统管理 PRD及逐项确认结论
- 状态: 第一套测试环境已联调，任务中心菜单层级修正待部署

## 目标

在不过度设计的前提下，交付租户内用户、角色、菜单及两张关联表，支持多角色、菜单树、角色授权、用户启停和密码重置。

## 已完成

- Flyway `V071` 创建 `sys_user`、`sys_role`、`sys_menu`、`sys_user_role`、`sys_role_menu`，并初始化现有业务菜单和租户管理员角色。
- Flyway `V075` 恢复原任务中心的九个菜单节点，补齐拉群营销，并停用拆分后为空的群组管理目录。
- 用户管理：列表、详情、新增、修改昵称/角色、启停、重置密码；用户名租户内唯一且不可修改。
- 角色管理：新增、修改、启停、菜单授权；系统内置 `TENANT_ADMIN` 不允许修改或禁用，并动态拥有全部有效权限。
- 菜单管理：D/M/B 树、父子类型校验、最多三级可见层级、组件白名单、状态继承和多角色权限并集。
- 密码由 `DelegatingPasswordEncoder` 生成 `{bcrypt}` 哈希；接口和日志不返回、不打印明文或哈希。
- 变更操作记录必要的业务 ID、状态和数量，不记录敏感字段。

## 关键设计决策

- 所有管理表保留 `status`，不增加逻辑删除字段；关联表解绑时物理删除。
- 租户 ID 只由现有 `TenantContext` 和 MyBatis 租户拦截器注入，不从请求参数接收。
- 禁用角色保留用户角色关系，但登录查权限时仅计算启用角色。
- 暂不增加审计表、组织层级、最高控或缓存。
- 任务中心保持原菜单结构，不再按账号、群组和任务业务域拆分展示目录。
- 当前登录没有可信用户 ID，因此不增加不安全的临时请求头；`GET /api/tenant/me/menus` 留待真实登录分支合并后接线。

## 验证

- JDK 17 生产编译：`mvn -DskipTests compile`，通过。
- SQL、Controller、Service 聚焦测试：21 项通过，0 失败、0 错误。
- Mapper XML：`xmllint --noout src/main/resources/mapper/admin/*.xml`，通过。
- 前端 API、页面、临时路由契约测试：10 项通过。
- 前端 `pnpm typecheck`：通过。
- 前端 `pnpm build`：通过；本地 pnpm 依赖链接缺少根级 `vue-demi`，补齐现有锁定版本的链接后验证成功，未修改依赖或锁文件。
- 全量后端 `test-compile`：被基线测试 `GroupPullMarketingEnumTest` 引用不存在的 `GroupPullResourceStatus.RELEASE_FAILED` 阻断，与本变更无关，未越界修改。
- 真库 Schema/Mapper DbTest：当前工作区没有数据库 `.env`，未执行；上线前必须在测试库跑完。

## 部署

- 后端提交：`07d59ea`、`1067fc0`、`d33b13a`、`4d5d44e`、`46d5217`。
- 前端提交：`83c2031`。
- Flyway 启动时自动执行 `V071__system_management_rbac.sql`；执行前按环境规范备份并核对当前 Flyway 版本。
- 2026-07-24 已部署 `test1`：后端 `59b350a`、前端 `191add6`，后端和前端健康检查均通过。
- 第一环境数据库核验：Flyway `071/system management rbac/success=1`，5 张 RBAC 表齐全，2 个启用租户各初始化 1 个 `TENANT_ADMIN`，共初始化 90 个菜单节点。
- `V075` 部署后需核验九个菜单均以 `TaskCenter` 为父节点，且 `GroupManagement` 为停用状态。

## 遗留 / 跟进

- 合并真实登录分支后，用认证上下文中的用户 ID 接通 `/api/tenant/me/menus`，再移除前端业务 Mock 路由。
- 在真实测试库执行 `SystemManagementSchemaDbTest` 和 `SystemManagementMapperDbTest`。
- 最高控和审计日志按后续独立需求设计，不在本次范围内。
