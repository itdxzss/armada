# 系统管理 RBAC 数据模型设计

## 1. 范围

本期实现租户侧用户、角色、菜单和授权关系的数据基础，并把现有前端业务菜单迁移为后端动态菜单。登录、图片验证码、审计日志、最高控、平台菜单模板、组织架构、权限缓存和会话管理不属于本期。

本期创建五张表：

- `sys_user`
- `sys_role`
- `sys_menu`
- `sys_user_role`
- `sys_role_menu`

现有 `tenant` 表、`TenantContext` 和 MyBatis 租户拦截器继续使用。当前环境可以只使用默认租户，但所有 IAM 数据仍以 `tenant_id` 隔离。

## 2. 已确认业务规则

### 2.1 用户

- 用户只属于一个租户，不同租户可以创建相同用户名。
- 用户名在租户内唯一，创建后不可修改。
- 一个用户可以绑定多个角色。
- 用户只支持启用和禁用，不提供删除。
- 昵称可为空，为空时前端展示用户名。
- 密码长度为 8 至 64 位，数据库只保存 Spring Security `DelegatingPasswordEncoder` 生成的哈希；当前编码算法为 BCrypt strength 10。
- 当前用户不能禁用自己，也不能移除自己的租户管理员角色。
- 每个租户始终至少保留一个启用状态的租户管理员用户。
- 本期不实现父级用户。

### 2.2 角色

- 角色只支持启用和禁用，不提供删除。
- 禁用角色时保留用户角色和角色菜单关系，但该角色不再产生权限；重新启用后原权限恢复。
- 每个租户预置一个 `TENANT_ADMIN` 系统角色。
- 租户管理员角色不可禁用、删除或修改编码，始终拥有本租户全部有效菜单和按钮权限，不依赖 `sys_role_menu`。
- 自定义角色编码创建后不可修改。

### 2.3 菜单和权限

- 节点类型为目录 `D`、菜单 `M`、按钮 `B`。
- 目录下可以创建目录或菜单，菜单下只能创建按钮，按钮不能有子节点。
- 可见菜单最多三级；按钮不计入可见菜单层级，因此数据库树最大深度为四级 `D → D → M → B`。这用于兼容现有“买号上量系统 → 推广管理/数据中心 → 页面”的菜单结构。
- 目录不对应页面；目录 `route_path` 由系统生成，不在租户菜单配置页面自由填写。
- 菜单对应真实前端页面，保存 `route_path` 和 `component_path`。
- 按钮不保存路由或组件，只保存权限编码。
- `component_path` 只能选择前端实际存在的页面组件，不能任意填写。
- 父节点禁用后整棵子树不生效，但不修改子节点自身状态；父节点重新启用后，原本启用的子节点自动恢复。
- 角色授权只保存菜单 `M` 和按钮 `B`，不保存目录 `D`；返回菜单树时按可访问菜单补齐祖先目录。
- 勾选按钮时必须同时拥有父菜单；取消父菜单时同时取消其全部按钮；勾选菜单不自动授予全部按钮。
- 菜单只支持启用和禁用，不提供删除。

### 2.4 关联关系

- 绑定用户角色时，只允许新增当前租户内启用的角色。
- 已绑定角色后来被禁用时保留关联；编辑用户时回显并标记角色已禁用，但不允许新增绑定已禁用角色。
- 用户角色和角色菜单解绑时物理删除关联记录。
- 两张关联表使用联合主键，不增加独立 ID、状态、时间和审计字段。
- 保存关联前必须校验所有对象属于当前租户，批量变更在一个事务中完成。

## 3. 表关系

```text
tenant 1 ── N sys_user
tenant 1 ── N sys_role
tenant 1 ── N sys_menu

sys_user N ── N sys_role  （sys_user_role）
sys_role N ── N sys_menu  （sys_role_menu，仅 M/B）

sys_menu 1 ── N sys_menu  （parent_id 菜单树）
```

## 4. 建表 SQL 草案

> 这是设计评审草案，不是可直接执行的 Flyway 迁移。实现时必须选取当时下一个可用的全局 Flyway 版本，并按目标数据库能力补充幂等守卫。

```sql
CREATE TABLE sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id     BIGINT       NOT NULL                COMMENT '所属租户ID',
    username      VARCHAR(64)  NOT NULL                COMMENT '登录用户名，租户内唯一，创建后不可修改',
    nickname      VARCHAR(64)  DEFAULT NULL            COMMENT '用户昵称，为空时展示用户名',
    password_hash VARCHAR(255) NOT NULL                COMMENT 'DelegatingPasswordEncoder单向密码哈希',
    status        TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1启用 0禁用',
    created_at    BIGINT       NOT NULL                COMMENT '创建时间(epoch毫秒)',
    created_by    BIGINT       DEFAULT NULL            COMMENT '创建人用户ID',
    updated_at    BIGINT       NOT NULL                COMMENT '更新时间(epoch毫秒)',
    updated_by    BIGINT       DEFAULT NULL            COMMENT '最后修改人用户ID',
    PRIMARY KEY (id),
    UNIQUE KEY uq_sys_user_tenant_username (tenant_id, username),
    KEY idx_sys_user_tenant_status (tenant_id, status)
) COMMENT='系统用户';

CREATE TABLE sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id   BIGINT       NOT NULL                COMMENT '所属租户ID',
    role_name   VARCHAR(64)  NOT NULL                COMMENT '角色名称，租户内唯一',
    role_code   VARCHAR(64)  NOT NULL                COMMENT '角色编码，租户内唯一，创建后不可修改',
    status      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1启用 0禁用',
    is_system   TINYINT      NOT NULL DEFAULT 0      COMMENT '是否系统内置角色：1是 0否',
    remark      VARCHAR(255) DEFAULT NULL            COMMENT '角色说明',
    created_at  BIGINT       NOT NULL                COMMENT '创建时间(epoch毫秒)',
    created_by  BIGINT       DEFAULT NULL            COMMENT '创建人用户ID',
    updated_at  BIGINT       NOT NULL                COMMENT '更新时间(epoch毫秒)',
    updated_by  BIGINT       DEFAULT NULL            COMMENT '最后修改人用户ID',
    PRIMARY KEY (id),
    UNIQUE KEY uq_sys_role_tenant_name (tenant_id, role_name),
    UNIQUE KEY uq_sys_role_tenant_code (tenant_id, role_code),
    KEY idx_sys_role_tenant_status (tenant_id, status)
) COMMENT='系统角色';

CREATE TABLE sys_menu (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id      BIGINT       NOT NULL                COMMENT '所属租户ID',
    parent_id      BIGINT       NOT NULL DEFAULT 0      COMMENT '父节点ID，根目录为0',
    menu_name      VARCHAR(64)  NOT NULL                COMMENT '节点名称',
    menu_key       VARCHAR(64)  NOT NULL                COMMENT '节点稳定标识，租户内唯一',
    menu_type      CHAR(1)      NOT NULL                COMMENT '节点类型：D目录 M菜单 B按钮',
    route_path     VARCHAR(128) DEFAULT NULL            COMMENT '目录或菜单路由，仅D/M使用；D由系统生成',
    component_path VARCHAR(128) DEFAULT NULL            COMMENT '前端组件路径，仅M使用',
    perm_key       VARCHAR(128) DEFAULT NULL            COMMENT '页面或按钮权限编码，仅M/B使用',
    icon           VARCHAR(64)  DEFAULT NULL            COMMENT '菜单图标，仅D/M使用',
    sort_no        INT          NOT NULL DEFAULT 0      COMMENT '同级排序，数值越小越靠前',
    status         TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1启用 0禁用',
    created_at     BIGINT       NOT NULL                COMMENT '创建时间(epoch毫秒)',
    created_by     BIGINT       DEFAULT NULL            COMMENT '创建人用户ID',
    updated_at     BIGINT       NOT NULL                COMMENT '更新时间(epoch毫秒)',
    updated_by     BIGINT       DEFAULT NULL            COMMENT '最后修改人用户ID',
    PRIMARY KEY (id),
    UNIQUE KEY uq_sys_menu_tenant_key (tenant_id, menu_key),
    UNIQUE KEY uq_sys_menu_tenant_route (tenant_id, route_path),
    KEY idx_sys_menu_tenant_perm (tenant_id, perm_key),
    KEY idx_sys_menu_tenant_parent_sort (tenant_id, parent_id, sort_no),
    KEY idx_sys_menu_tenant_status (tenant_id, status)
) COMMENT='系统菜单与权限节点';

CREATE TABLE sys_user_role (
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    user_id   BIGINT NOT NULL COMMENT '用户ID',
    role_id   BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (tenant_id, user_id, role_id),
    KEY idx_sys_user_role_role (tenant_id, role_id, user_id)
) COMMENT='用户角色关联';

CREATE TABLE sys_role_menu (
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    role_id   BIGINT NOT NULL COMMENT '角色ID',
    menu_id   BIGINT NOT NULL COMMENT '菜单或按钮节点ID，不保存目录节点',
    PRIMARY KEY (tenant_id, role_id, menu_id),
    KEY idx_sys_role_menu_menu (tenant_id, menu_id, role_id)
) COMMENT='角色菜单权限关联';
```

## 5. 数据约束

数据库唯一键保证租户内用户名、角色名称、角色编码、菜单标识和菜单路由唯一。以下跨行、跨表规则由 Service 在事务内校验：

- 用户、角色、菜单和关联记录必须属于同一租户。
- 根节点必须为 `D`；`D` 的父节点可以为根或 `D`；`M` 的父节点必须为 `D`；`B` 的父节点必须为 `M`。
- `D` 必须有系统生成的 `route_path`，不允许填写 `component_path` 和 `perm_key`；`M` 必须填写 `route_path`、`component_path` 和 `perm_key`；`B` 只填写 `perm_key`。
- `route_path` 和 `menu_key` 在租户内唯一。`perm_key` 不要求唯一，因为现有“导入链接”和“群组列表”等不同页面可能复用同一个后端查看权限。
- 同一父节点下按 `sort_no`、`id` 稳定排序，不要求排序值唯一。
- `component_path` 必须存在于服务端允许的前端组件白名单中。
- `sys_role_menu` 不允许保存 `D` 类型节点。
- 不能禁用系统角色，不能让租户失去最后一个启用的租户管理员用户。

## 6. 权限计算

普通用户：

```text
启用用户
→ 所有已绑定且启用的角色
→ 角色关联的启用菜单和按钮
→ 过滤掉任一祖先已禁用的节点
→ 补齐菜单的启用祖先目录
```

租户管理员：

```text
启用用户 + TENANT_ADMIN 系统角色
→ 当前租户全部自身启用且祖先有效的菜单和按钮
```

多个角色的权限取并集并按菜单 ID 去重。

## 7. 现有前端菜单迁移与初始化

### 7.1 保留静态的框架路由

以下路由不受租户角色控制，继续保留在前端：

- 首页和欢迎页；
- 登录页；
- 403、404、500；
- 重定向和加载页。

pure-admin 自带的“权限管理”示例页只用于开发演示，不写入生产初始化数据。

### 7.2 迁移到 `sys_menu` 的业务路由

以下现有业务菜单从 `mock/asyncRoutes.ts` 和 `src/router/modules/buyer.ts` 迁入初始化 SQL：

```text
账号管理
├─ 账号列表
└─ 账号分组

任务中心
├─ 账号导入
├─ 导入链接
├─ 群组列表
├─ 历史群管理
├─ 拉群任务
├─ 进群任务
├─ 营销任务
└─ 建群营销

素材管理
└─ 营销模版

运营管理
├─ IP 管理
└─ IP 数据统计

买号上量系统
├─ 推广管理
│  ├─ 模板管理（二期）
│  └─ 渠道管理（二期）
└─ 数据中心
   └─ 渠道统计（二期）

系统管理
├─ 用户管理
├─ 角色管理
└─ 菜单管理
```

买号上量现有按钮权限作为 `B` 节点初始化，包括模板可见性、模板备注、渠道新增/编辑/检测/停用、统计编辑和导出。系统管理按钮权限按本期实际页面操作初始化。其他页面目前只有页面级 `perm_key`，不凭空补造未在前端和后端使用的按钮权限。

### 7.3 初始化 SQL 原则

- Flyway 建表迁移同时为每个现有启用租户初始化一套租户管理员角色和业务菜单。
- 初始化语句使用 `tenant_id + menu_key` 查询父节点，不依赖跨环境不稳定的固定自增 ID。
- 初始化必须可重复校验，不允许同一租户生成重复 `TENANT_ADMIN`、`menu_key` 或 `route_path`。
- 租户管理员不初始化 `sys_role_menu`，其全部权限由服务端动态计算。
- 本期不初始化默认管理员用户；用户种子和密码由后续真实登录接入统一处理，避免产生另一套临时身份。
- 前端动态菜单接口从模板 `/get-async-routes` 切换到真实 `/api/tenant/me/menus`。
- `src/router/modules/buyer.ts` 的业务路由迁入数据库后从静态模块移除，但对应 `src/views/buyer/**` 页面组件继续保留。

## 8. 后续但不属于本期

- 登录接入时增加或接管 `last_login_at`、验证码、Redis 会话和会话失效逻辑。
- 登录分支原有的全平台用户名唯一约束必须改成租户内唯一；单 `role` 字段必须改成 `sys_user_role` 多角色关系；原 `display_name` 应与本设计的 `nickname` 统一。
- 最高控落地时再设计平台菜单模板和租户功能开通关系，不在当前表中预留死字段。
- 审计落地时统一记录用户、角色、菜单、授权和状态变更，不在当前关联表中保留历史记录。

## 9. 验证重点

- 两个租户可以创建相同用户名、角色编码和菜单编码，同一租户内不能重复。
- 跨租户绑定用户角色、角色菜单全部失败且不产生部分写入。
- 禁用角色后关联仍在但权限消失，重新启用后权限恢复。
- 禁用父目录或菜单后整棵子树不生效，重新启用后子节点按自身状态恢复。
- 多角色权限正确合并和去重。
- 租户管理员无需 `sys_role_menu` 即拥有全部有效权限。
- 不能禁用最后一个有效租户管理员。
- 初始化后的动态路由与迁移前现有业务菜单层级、地址、组件和权限一致。
- 首页、登录和错误页继续由前端静态路由提供，pure-admin 权限示例页不进入生产菜单。
