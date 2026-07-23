# System Management RBAC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build tenant-scoped user, role, menu, user-role, and role-menu management; seed the existing frontend business menu tree; and prepare a clean integration seam for the later real-login branch.

**Architecture:** Add one `com.armada.admin` business domain using the existing Controller → Service → Mapper structure and MyBatis tenant interceptor. Keep the five-table RBAC model minimal, seed existing menus through Flyway, and expose CRUD/authorization services under the current tenant context. The final identity-dependent `/api/tenant/me/menus` route and self-disable guard are activated only after real authentication supplies a trustworthy user ID.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis/MySQL, Flyway, Spring Security Crypto, Vue 3, TypeScript, pure-admin, Element Plus, pnpm.

## Global Constraints

- Base both repositories on remote `origin/1.0.1-snapshot` using branch `feature/system-management-rbac`.
- Use only the five tables from the approved design; do not add audit, organization, platform-menu, cache, or session tables.
- `sys_user`, `sys_role`, and `sys_menu` use only `status=1` enabled and `status=0` disabled; there is no delete state.
- Usernames, role names/codes, menu keys, and route paths are unique per tenant; permission keys may be shared by multiple menu nodes.
- Association tables use composite primary keys and physical delete on unbind.
- Tenant ID always comes from `TenantContext`; never accept an effective `tenantId` from a request body.
- Current temporary login has no trustworthy user ID. Until real authentication lands, `created_by`/`updated_by` remain nullable and self-operation checks stay in the documented login-integration task.
- Backend baseline currently has an unrelated test-compilation failure: `GroupPullMarketingEnumTest` references missing `GroupPullResourceStatus.RELEASE_FAILED`. Do not modify that unrelated feature in this change; run focused RBAC tests plus production compilation.

---

### Task 1: Flyway Schema and Existing-Menu Seed

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V062__system_management_rbac.sql`
- Create: `armada-api/src/test/java/com/armada/admin/SystemManagementMigrationSqlTest.java`
- Create: `armada-api/src/test/java/com/armada/admin/SystemManagementSchemaDbTest.java`
- Modify: `.harness/wiki/数据模型.md` by rerunning `.harness/wiki/gen_datamodel.py` after the migration is verified

**Interfaces:**
- Produces tables `sys_user`, `sys_role`, `sys_menu`, `sys_user_role`, and `sys_role_menu` exactly as specified in `docs/superpowers/specs/2026-07-23-system-management-rbac-design.md`.
- Produces one enabled `TENANT_ADMIN` role and one current-business-menu tree for every enabled row in `tenant`.

- [ ] **Step 1: Write the failing SQL-shape test**

```java
@Test
void migrationDefinesMinimalRbacSchemaAndSeedsExistingMenus() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V062__system_management_rbac.sql"));
    assertThat(sql).contains("CREATE TABLE sys_user", "CREATE TABLE sys_role", "CREATE TABLE sys_menu");
    assertThat(sql).contains("CREATE TABLE sys_user_role", "CREATE TABLE sys_role_menu");
    assertThat(sql).contains("TENANT_ADMIN", "AccountIndex", "BuyerChannel", "SystemUser");
    assertThat(sql).doesNotContain("deleted_at", "is_active", "CREATE TABLE audit");
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home mvn -Dtest=SystemManagementMigrationSqlTest test
```

Expected: FAIL because `V062__system_management_rbac.sql` does not exist.

- [ ] **Step 3: Add the five tables and deterministic seed SQL**

Use the approved DDL. Seed parent IDs with tenant-scoped subqueries, for example:

```sql
INSERT INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, icon, sort_no, status, created_at, updated_at)
SELECT t.id, 0, '账号管理', 'Account', 'D', '/account', 'ep:user', 2, 1,
       UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000,
       UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000
FROM tenant t
WHERE t.status = 1;

INSERT INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path, perm_key,
     sort_no, status, created_at, updated_at)
SELECT t.id,
       (SELECT d.id FROM sys_menu d WHERE d.tenant_id = t.id AND d.menu_key = 'Account'),
       '账号列表', 'AccountIndex', 'M', '/account/index', 'account/index/index',
       'tenant:account:view', 1, 1,
       UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000,
       UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000
FROM tenant t
WHERE t.status = 1;
```

Seed only current production business menus, buyer button permissions already present in `v-auth/auths`, and the three system-management pages/buttons defined by this feature. Do not seed home/login/error/redirect routes or pure-admin permission demos.

- [ ] **Step 4: Write and run the true-database schema test**

The DbTest must assert:

```java
assertThat(columnNames("sys_user")).containsExactlyInAnyOrder(
    "id", "tenant_id", "username", "nickname", "password_hash", "status",
    "created_at", "created_by", "updated_at", "updated_by"
);
assertThat(queryInt("SELECT COUNT(*) FROM sys_menu WHERE tenant_id=1 AND menu_key='BuyerChannel'")).isEqualTo(1);
assertThat(queryInt("SELECT COUNT(*) FROM sys_menu WHERE menu_key='PermissionPage'")).isZero();
```

Run the repository `dbtest.sh` command for this class. Expected: PASS against the confirmed test database.

- [ ] **Step 5: Refresh schema documentation and commit**

```bash
git add armada-api/src/main/resources/db/migration/V062__system_management_rbac.sql \
        armada-api/src/test/java/com/armada/admin/SystemManagementMigrationSqlTest.java \
        armada-api/src/test/java/com/armada/admin/SystemManagementSchemaDbTest.java \
        .harness/wiki/数据模型.md
git commit -m "feat: add tenant RBAC schema and menu seed"
```

### Task 2: RBAC Entities, Enums, and Mappers

**Files:**
- Create: `armada-api/src/main/java/com/armada/admin/model/entity/SysUser.java`
- Create: `armada-api/src/main/java/com/armada/admin/model/entity/SysRole.java`
- Create: `armada-api/src/main/java/com/armada/admin/model/entity/SysMenu.java`
- Create: `armada-api/src/main/java/com/armada/admin/model/enums/SystemStatus.java`
- Create: `armada-api/src/main/java/com/armada/admin/model/enums/MenuType.java`
- Create: `armada-api/src/main/java/com/armada/admin/mapper/SysUserMapper.java`
- Create: `armada-api/src/main/java/com/armada/admin/mapper/SysRoleMapper.java`
- Create: `armada-api/src/main/java/com/armada/admin/mapper/SysMenuMapper.java`
- Create: `armada-api/src/main/resources/mapper/admin/SysUserMapper.xml`
- Create: `armada-api/src/main/resources/mapper/admin/SysRoleMapper.xml`
- Create: `armada-api/src/main/resources/mapper/admin/SysMenuMapper.xml`
- Test: `armada-api/src/test/java/com/armada/admin/mapper/SystemManagementMapperDbTest.java`

**Interfaces:**
- Produces tenant-scoped mapper methods used by Tasks 3–5.
- Status values are `DISABLED(0)` and `ENABLED(1)`; menu values are `DIRECTORY("D")`, `MENU("M")`, and `BUTTON("B")`.

- [ ] **Step 1: Write failing mapper DbTests**

Cover tenant-filtered username lookup, role lookup with user count, ordered menu children, batch user-role replacement, and batch role-menu replacement. Assert cross-tenant IDs cannot be returned by ordinary mapper methods.

- [ ] **Step 2: Run focused mapper tests and verify RED**

Expected: test compilation fails because mapper interfaces do not exist.

- [ ] **Step 3: Implement minimal entities and Mapper/XML methods**

Use explicit method signatures:

```java
Optional<SysUser> findById(long id);
Optional<SysUser> findByUsername(String username);
List<Long> findRoleIdsByUserId(long userId);
void replaceUserRoles(long userId, List<Long> roleIds);

Optional<SysRole> findById(long id);
long countEnabledTenantAdmins();
List<Long> findMenuIdsByRoleId(long roleId);

List<SysMenu> findAllOrdered();
List<SysMenu> findByIds(Collection<Long> ids);
void replaceRoleMenus(long roleId, List<Long> menuIds);
```

Replacement methods must delete then batch insert within the caller's transaction; never accept tenant ID from DTOs.

- [ ] **Step 4: Run mapper DbTests and XML parser checks**

Expected: PASS; `xmllint --noout` passes for all three mapper XML files.

- [ ] **Step 5: Commit**

```bash
git add armada-api/src/main/java/com/armada/admin armada-api/src/main/resources/mapper/admin \
        armada-api/src/test/java/com/armada/admin/mapper
git commit -m "feat: add RBAC persistence layer"
```

### Task 3: Role Management Service and API

**Files:**
- Create: `armada-api/src/main/java/com/armada/admin/controller/RoleManagementController.java`
- Create: `armada-api/src/main/java/com/armada/admin/service/RoleManagementService.java`
- Create: `armada-api/src/main/java/com/armada/admin/service/impl/RoleManagementServiceImpl.java`
- Create DTO/VO files under `armada-api/src/main/java/com/armada/admin/model/dto/` and `model/vo/`
- Modify: `armada-api/src/main/java/com/armada/shared/exception/ErrorCode.java`
- Test: `armada-api/src/test/java/com/armada/admin/service/RoleManagementServiceImplTest.java`
- Test: `armada-api/src/test/java/com/armada/admin/controller/RoleManagementControllerTest.java`

**Interfaces:**
- `GET /api/admin/roles`
- `POST /api/admin/roles`
- `PUT /api/admin/roles/{id}`
- `PATCH /api/admin/roles/{id}/status`
- `GET /api/admin/roles/{id}/menus`
- `PUT /api/admin/roles/{id}/menus`

- [ ] Write failing tests for tenant uniqueness, immutable role code, immutable system role, disabled-role relationship retention, M/B-only authorization, and transactional replacement.
- [ ] Run focused tests and verify RED.
- [ ] Implement DTO validation and Service transactions. `TENANT_ADMIN` authorization queries return all effective M/B nodes without reading `sys_role_menu`.
- [ ] Run focused tests and verify GREEN.
- [ ] Commit with `git commit -m "feat: add role management"`.

### Task 4: Menu Management and Effective Tree

**Files:**
- Create: `armada-api/src/main/java/com/armada/admin/controller/MenuManagementController.java`
- Create: `armada-api/src/main/java/com/armada/admin/service/MenuManagementService.java`
- Create: `armada-api/src/main/java/com/armada/admin/service/impl/MenuManagementServiceImpl.java`
- Create DTO/VO files under the admin model packages
- Test: `armada-api/src/test/java/com/armada/admin/service/MenuManagementServiceImplTest.java`

**Interfaces:**
- `GET /api/admin/menus/tree`
- `POST /api/admin/menus`
- `PUT /api/admin/menus/{id}`
- `PATCH /api/admin/menus/{id}/status`
- Service seam: `List<MenuRouteVO> findEffectiveRoutesForUser(long userId)` for later authenticated `/api/tenant/me/menus` wiring.

- [ ] Write failing tests for D→D/M and M→B validation, maximum visible depth three, component whitelist, parent-disabled effective status, stable ordering, and multi-role union.
- [ ] Run focused tests and verify RED.
- [ ] Implement validation and tree assembly without in-memory pagination; full-tree loading is allowed because the endpoint returns the complete tenant menu tree.
- [ ] Run focused tests and verify GREEN.
- [ ] Commit with `git commit -m "feat: add menu management and permission tree"`.

### Task 5: User Management and Password Reset

**Files:**
- Modify: `armada-api/pom.xml` to add only `spring-security-crypto` if real-auth dependencies are not already present
- Create: `armada-api/src/main/java/com/armada/boot/config/PasswordConfiguration.java`
- Create: `armada-api/src/main/java/com/armada/admin/controller/UserManagementController.java`
- Create: `armada-api/src/main/java/com/armada/admin/service/UserManagementService.java`
- Create: `armada-api/src/main/java/com/armada/admin/service/impl/UserManagementServiceImpl.java`
- Create DTO/VO files under the admin model packages
- Test: `armada-api/src/test/java/com/armada/admin/service/UserManagementServiceImplTest.java`

**Interfaces:**
- `GET /api/admin/users`
- `POST /api/admin/users`
- `GET /api/admin/users/{id}`
- `PUT /api/admin/users/{id}`
- `POST /api/admin/users/{id}/reset-password`
- `PATCH /api/admin/users/{id}/status`

- [ ] Write failing tests for tenant-local username uniqueness, immutable username, 8–64 password validation, `{bcrypt}` hash output, enabled-role-only new bindings, disabled-role retention on edit, and last-enabled-admin protection.
- [ ] Run focused tests and verify RED.
- [ ] Implement transactional user writes and role replacement. Never return `password_hash` in a VO or log raw passwords.
- [ ] Run focused tests and verify GREEN.
- [ ] Commit with `git commit -m "feat: add user management"`.

### Task 6: Frontend API Contracts and System Pages

**Files (wheel-saas-pure-web worktree):**
- Create: `src/api/system-user.ts`, `src/api/system-role.ts`, `src/api/system-menu.ts`
- Create: `src/views/system/user/index.vue`
- Create: `src/views/system/role/index.vue`
- Create: `src/views/system/menu/index.vue`
- Create focused API and page-contract tests alongside those files
- Modify: `mock/asyncRoutes.ts` only to expose the three new pages during the current temporary-login development phase

**Interfaces:**
- Consume the Task 3–5 endpoints and camelCase VO/DTO fields.
- Use `RePureTableBar`, Element Plus forms/tables/dialogs/drawers, and existing permission directives.

- [ ] Write failing API contract tests for every endpoint.
- [ ] Implement typed API functions and make tests pass.
- [ ] Write failing page contract tests for filters, columns, status actions, role multi-select, role permission tree, and menu tree fields.
- [ ] Implement the three pages with loading/empty/error states and no direct Axios calls.
- [ ] Run `pnpm typecheck` and focused tests.
- [ ] Commit with `git commit -m "feat: add system management pages"`.

### Task 7: Existing Menu Dynamic-Route Cutover After Real Authentication

**Files:**
- Backend: add authenticated controller for `GET /api/tenant/me/menus`
- Frontend: modify `src/api/routes.ts`, `src/router/utils.ts`, `src/router/modules/buyer.ts`, `mock/asyncRoutes.ts`
- Tests: `src/api/routes.test.ts`, router boundary tests, backend authenticated menu tests

**Interfaces:**
- Consume a trustworthy authenticated `userId` and `tenantId` from the later login branch.
- Produce pure-admin route records with `path`, `name`, `component`, `meta.title`, `meta.icon`, `meta.rank`, and button `auths`.

- [ ] Merge or rebase the approved real-authentication implementation first; do not invent an `X-User-Id` header.
- [ ] Write failing tests proving ordinary users receive only effective role menus, administrators receive all effective menus, and disabled ancestors suppress descendants.
- [ ] Wire `findEffectiveRoutesForUser(userId)` to `/api/tenant/me/menus` and update `src/api/routes.ts` from `/get-async-routes`.
- [ ] Remove business routes from `buyer.ts` and production dependence on `mock/asyncRoutes.ts`; retain home/login/error/redirect routes.
- [ ] Run backend focused tests, frontend typecheck, router tests, and build.
- [ ] Commit backend and frontend cutover separately.

### Task 8: Final Verification and Change Record

**Files:**
- Create: `.harness/changes/2026-07-23-system-management-rbac.md`
- Create: `.harness/changes/system-management-rbac/db-migrations.sql`
- Create: `.harness/changes/system-management-rbac/rollback.sql`
- Update design and plan only if implementation facts changed

- [ ] Record the known unrelated baseline failure and every focused test command/output.
- [ ] Run production compilation with JDK 17, focused service/controller tests, mapper DbTests, Flyway DbTest, frontend typecheck, focused frontend tests, and frontend build.
- [ ] Run an expert review focused on tenant isolation, transactionality, permission leakage, password secrecy, and static-menu removal.
- [ ] Verify both worktrees contain only system-management changes.
- [ ] Commit documentation with `git commit -m "docs: record system management verification"`.

## Execution Order

Tasks 1–5 are the current independent backend slice. Task 6 may proceed after API contracts stabilize. Task 7 is explicitly blocked on the real-authentication branch because a trustworthy current user is required. Task 8 closes the feature after the cutover is available; if authentication remains unavailable, record Task 7 as a named dependency rather than introducing an insecure temporary identity mechanism.
