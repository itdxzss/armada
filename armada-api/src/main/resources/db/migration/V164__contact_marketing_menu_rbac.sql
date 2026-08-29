-- 通讯录营销目录、页面与按钮权限。
-- 租户管理员按现有动态规则拥有全部节点；普通角色仍由运营显式授权。
-- 竞品的任务 API 与行操作均没有删除，因此不建 delete 权限节点。

SET @contact_menu_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT tenant.id, 0, '通讯录营销', 'ContactMarketing', 'D', '/contact', NULL,
       NULL, 'ep:phone', 56, 1,
       @contact_menu_now, NULL, @contact_menu_now, NULL
FROM tenant
WHERE tenant.status = 1;

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT tenant.id, parent.id, page.menu_name, page.menu_key, 'M', page.route_path,
       page.component_path, page.perm_key, NULL, page.sort_no, 1,
       @contact_menu_now, NULL, @contact_menu_now, NULL
FROM tenant
INNER JOIN sys_menu parent
    ON parent.tenant_id = tenant.id
   AND parent.menu_key = 'ContactMarketing'
CROSS JOIN (
    SELECT '通讯录超链任务' AS menu_name,
           'ContactHyperlinkTask' AS menu_key,
           '/contact/hyperlink' AS route_path,
           'contact/hyperlink/index' AS component_path,
           'tenant:contact_task:view' AS perm_key,
           10 AS sort_no
    UNION ALL
    SELECT '通讯录剧本任务', 'ContactScriptTask', '/contact/script',
           'contact/script/index', 'tenant:contact_task:view', 20
) page
WHERE tenant.status = 1;

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT parent.tenant_id, parent.id, permission.menu_name, permission.menu_key, 'B', NULL,
       NULL, permission.perm_key, NULL, permission.sort_no, 1,
       @contact_menu_now, NULL, @contact_menu_now, NULL
FROM sys_menu parent
INNER JOIN (
    SELECT 'ContactHyperlinkTask' AS parent_key,
           '创建通讯录任务' AS menu_name,
           'ContactHyperlinkTaskCreate' AS menu_key,
           'tenant:contact_task:create' AS perm_key,
           10 AS sort_no
    UNION ALL
    SELECT 'ContactHyperlinkTask', '编辑通讯录任务', 'ContactHyperlinkTaskEdit',
           'tenant:contact_task:edit', 20
    UNION ALL
    SELECT 'ContactHyperlinkTask', '启停通讯录任务', 'ContactHyperlinkTaskOperate',
           'tenant:contact_task:operate', 30
) permission
    ON permission.parent_key = parent.menu_key
WHERE parent.menu_key = 'ContactHyperlinkTask';
