-- “群组管理”仅是历史菜单重组遗留的空目录，实际业务菜单已迁回“任务中心”。
-- 只在目录没有子节点时清理，避免误删后续重新挂载的真实业务菜单。
DELETE role_menu
FROM sys_role_menu role_menu
JOIN sys_menu menu
  ON menu.tenant_id = role_menu.tenant_id
 AND menu.id = role_menu.menu_id
LEFT JOIN sys_menu child
  ON child.tenant_id = menu.tenant_id
 AND child.parent_id = menu.id
WHERE menu.menu_key = 'GroupManagement'
  AND child.id IS NULL;

DELETE menu
FROM sys_menu menu
LEFT JOIN sys_menu child
  ON child.tenant_id = menu.tenant_id
 AND child.parent_id = menu.id
WHERE menu.menu_key = 'GroupManagement'
  AND child.id IS NULL;
