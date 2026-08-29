-- H1 超链任务列表页面与本方案使用的按钮权限；不提前创建 H2-H6 专属权限。

SET @hyperlink_task_menu_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT parent.tenant_id, parent.id, '超链任务', 'HyperlinkTaskList', 'M',
       '/hyperlink/tasks', 'hyperlink/task/index', 'tenant:hyperlink_task:view',
       NULL, 30, 1, @hyperlink_task_menu_now, NULL, @hyperlink_task_menu_now, NULL
FROM sys_menu parent
WHERE parent.menu_key = 'HyperlinkMarketing';

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT parent.tenant_id, parent.id, permission.menu_name, permission.menu_key, 'B', NULL,
       NULL, permission.perm_key, NULL, permission.sort_no, 1,
       @hyperlink_task_menu_now, NULL, @hyperlink_task_menu_now, NULL
FROM sys_menu parent
CROSS JOIN (
    SELECT '新建超链任务' AS menu_name,
           'HyperlinkTaskCreate' AS menu_key,
           'tenant:hyperlink_task:create' AS perm_key,
           10 AS sort_no
    UNION ALL
    SELECT '编辑超链任务', 'HyperlinkTaskEdit', 'tenant:hyperlink_task:edit', 20
    UNION ALL
    SELECT '操作超链任务', 'HyperlinkTaskAction', 'tenant:hyperlink_task:action', 30
    UNION ALL
    SELECT '导出超链任务', 'HyperlinkTaskExport', 'tenant:hyperlink_task:export', 40
) permission
WHERE parent.menu_key = 'HyperlinkTaskList';
