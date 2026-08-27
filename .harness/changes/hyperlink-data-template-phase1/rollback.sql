-- 破坏性回滚脚本：会删除超链一期菜单、模板、数据包及号码/导入历史。
-- 仅允许在旧版前后端已恢复、任务模块未引用这些资源、目标环境已确认并完成备份后执行。
-- Flyway 已登记 V141～V143 的环境还必须按部署规范处理 schema history，不能只执行本文件。

DELETE role_menu
FROM sys_role_menu role_menu
INNER JOIN sys_menu menu ON menu.id = role_menu.menu_id
WHERE menu.menu_key IN (
  'HyperlinkMarketing',
  'HyperlinkDataPackage',
  'HyperlinkTemplate',
  'HyperlinkDataPackageCreate',
  'HyperlinkDataPackageImport',
  'HyperlinkDataPackageEdit',
  'HyperlinkDataPackageDelete',
  'HyperlinkTemplateCreate',
  'HyperlinkTemplateEdit',
  'HyperlinkTemplateCopy',
  'HyperlinkTemplateDelete'
);

DELETE FROM sys_menu
WHERE menu_key IN (
  'HyperlinkDataPackageCreate',
  'HyperlinkDataPackageImport',
  'HyperlinkDataPackageEdit',
  'HyperlinkDataPackageDelete',
  'HyperlinkTemplateCreate',
  'HyperlinkTemplateEdit',
  'HyperlinkTemplateCopy',
  'HyperlinkTemplateDelete',
  'HyperlinkDataPackage',
  'HyperlinkTemplate',
  'HyperlinkMarketing'
);

DROP TABLE IF EXISTS hyperlink_template;
DROP TABLE IF EXISTS data_package_import;
DROP TABLE IF EXISTS data_package_stat;
DROP TABLE IF EXISTS data_package_phone;
DROP TABLE IF EXISTS data_package;
