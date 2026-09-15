-- 新号注册独立放在账号管理下，沿用账号编辑权限。
-- 租户管理员动态拥有全部有效菜单，普通角色继续通过角色菜单权限显式授权。
INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT account_directory.tenant_id, account_directory.id, '新号注册', 'AccountRegistration', 'M',
       '/account/registration', 'account/registration/index', 'tenant:account:edit', NULL, 40, 1,
       account_directory.created_at, NULL, account_directory.updated_at, NULL
FROM sys_menu account_directory
WHERE account_directory.menu_key = 'AccountManagement'
  AND account_directory.menu_type = 'D';
