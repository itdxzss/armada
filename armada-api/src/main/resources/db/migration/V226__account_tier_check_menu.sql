-- 账号分组与导入之间；查看 view、写操作由 API edit 权限校验。
INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT account_directory.tenant_id, account_directory.id, '档位检测', 'AccountTierCheck', 'M',
       '/account/tier-check', 'account/tier-check/index', 'tenant:account:view', NULL, 25, 1,
       account_directory.created_at, NULL, account_directory.updated_at, NULL
FROM sys_menu account_directory
WHERE account_directory.menu_key = 'AccountManagement'
  AND account_directory.menu_type = 'D';
