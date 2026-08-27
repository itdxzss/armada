-- 超链数据包完整复刻纠偏：号码导出使用独立敏感权限。
-- 点击事实尚无写入方，本迁移不提前增加死列。

SET @hyperlink_correction_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT parent.tenant_id, parent.id, '导出数据包号码', 'HyperlinkDataPackageExport', 'B', NULL,
       NULL, 'tenant:hyperlink_data:export', NULL, 50, 1,
       @hyperlink_correction_now, NULL, @hyperlink_correction_now, NULL
FROM sys_menu parent
WHERE parent.menu_key = 'HyperlinkDataPackage';
