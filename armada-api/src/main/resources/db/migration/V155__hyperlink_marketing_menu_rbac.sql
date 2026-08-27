-- 超链营销一期目录、页面与按钮权限。
-- 租户管理员按现有动态规则拥有全部节点；普通角色仍由运营显式授权。

SET @hyperlink_menu_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT tenant.id, 0, '超链营销', 'HyperlinkMarketing', 'D', '/hyperlink', NULL,
       NULL, 'ep:link', 55, 1,
       @hyperlink_menu_now, NULL, @hyperlink_menu_now, NULL
FROM tenant
WHERE tenant.status = 1;

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT tenant.id, parent.id, page.menu_name, page.menu_key, 'M', page.route_path,
       page.component_path, page.perm_key, NULL, page.sort_no, 1,
       @hyperlink_menu_now, NULL, @hyperlink_menu_now, NULL
FROM tenant
INNER JOIN sys_menu parent
    ON parent.tenant_id = tenant.id
   AND parent.menu_key = 'HyperlinkMarketing'
CROSS JOIN (
    SELECT '超链数据包' AS menu_name,
           'HyperlinkDataPackage' AS menu_key,
           '/hyperlink/data' AS route_path,
           'hyperlink/data/index' AS component_path,
           'tenant:hyperlink_data:view' AS perm_key,
           10 AS sort_no
    UNION ALL
    SELECT '超链营销模板', 'HyperlinkTemplate', '/hyperlink/templates',
           'hyperlink/templates/index', 'tenant:hyperlink_template:view', 20
) page
WHERE tenant.status = 1;

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT parent.tenant_id, parent.id, permission.menu_name, permission.menu_key, 'B', NULL,
       NULL, permission.perm_key, NULL, permission.sort_no, 1,
       @hyperlink_menu_now, NULL, @hyperlink_menu_now, NULL
FROM sys_menu parent
INNER JOIN (
    SELECT 'HyperlinkDataPackage' AS parent_key,
           '创建数据包' AS menu_name,
           'HyperlinkDataPackageCreate' AS menu_key,
           'tenant:hyperlink_data:create' AS perm_key,
           10 AS sort_no
    UNION ALL
    SELECT 'HyperlinkDataPackage', '导入数据包', 'HyperlinkDataPackageImport',
           'tenant:hyperlink_data:import', 20
    UNION ALL
    SELECT 'HyperlinkDataPackage', '编辑数据包', 'HyperlinkDataPackageEdit',
           'tenant:hyperlink_data:edit', 30
    UNION ALL
    SELECT 'HyperlinkDataPackage', '删除数据包', 'HyperlinkDataPackageDelete',
           'tenant:hyperlink_data:delete', 40
    UNION ALL
    SELECT 'HyperlinkTemplate', '创建超链模板', 'HyperlinkTemplateCreate',
           'tenant:hyperlink_template:create', 10
    UNION ALL
    SELECT 'HyperlinkTemplate', '编辑超链模板', 'HyperlinkTemplateEdit',
           'tenant:hyperlink_template:edit', 20
    UNION ALL
    SELECT 'HyperlinkTemplate', '复制超链模板', 'HyperlinkTemplateCopy',
           'tenant:hyperlink_template:copy', 30
    UNION ALL
    SELECT 'HyperlinkTemplate', '删除超链模板', 'HyperlinkTemplateDelete',
           'tenant:hyperlink_template:delete', 40
) permission
    ON permission.parent_key = parent.menu_key
WHERE parent.menu_key IN ('HyperlinkDataPackage', 'HyperlinkTemplate');
