-- 通讯录任务批量删除权限；仅增加按钮节点，普通角色仍需管理员显式授权。
INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT parent.tenant_id, parent.id, '删除通讯录任务', 'ContactHyperlinkTaskDelete', 'B', NULL,
       NULL, 'tenant:contact_task:delete', NULL, 40, 1,
       UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, NULL,
       UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, NULL
FROM sys_menu parent
WHERE parent.menu_key = 'ContactHyperlinkTask' AND parent.menu_type = 'M';
