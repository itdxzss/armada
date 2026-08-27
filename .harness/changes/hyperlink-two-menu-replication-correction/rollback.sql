-- 仅供永久撤销 V156 时人工评审，不属于普通应用版本回滚。
-- 先回退使用独立导出权限的应用；本迁移没有结构变更。

DELETE FROM sys_menu
WHERE menu_key = 'HyperlinkDataPackageExport'
  AND perm_key = 'tenant:hyperlink_data:export'
  AND menu_type = 'B';
