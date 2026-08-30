-- V169 补齐超链一期菜单顺序与任务归因权限。
-- 素材对任务引用的删除保护由共享 Mapper 在应用层完成；V158 已提供两列引用索引。

SET @hyperlink_integration_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

UPDATE sys_menu
SET sort_no = CASE menu_key
        WHEN 'HyperlinkTaskList' THEN 10
        WHEN 'HyperlinkDataPackage' THEN 20
        WHEN 'HyperlinkTemplate' THEN 30
        WHEN 'HyperlinkResourceAsset' THEN 50
        ELSE sort_no
    END,
    updated_at = @hyperlink_integration_now
WHERE menu_key IN (
    'HyperlinkTaskList',
    'HyperlinkDataPackage',
    'HyperlinkTemplate',
    'HyperlinkResourceAsset');

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT task_menu.tenant_id, task_menu.id, '查看超链归因敏感数据',
       'HyperlinkTaskAttributionSensitive', 'B', NULL, NULL,
       'tenant:hyperlink_task:attribution_sensitive', NULL, 50, 1,
       @hyperlink_integration_now, NULL, @hyperlink_integration_now, NULL
FROM sys_menu task_menu
WHERE task_menu.menu_key = 'HyperlinkTaskList';
