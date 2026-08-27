-- 超链数据包与超链营销模板一期前向执行入口。
-- 正式部署由 Flyway 自动执行；手工审阅/隔离测试时仅在仓库根目录启动 mysql 客户端使用。
-- 执行前必须确认目标数据库、当前 Flyway 最高版本、备份和 V141～V143 未发生版本冲突。

SOURCE armada-api/src/main/resources/db/migration/V141__hyperlink_data_package.sql;
SOURCE armada-api/src/main/resources/db/migration/V142__hyperlink_template.sql;
SOURCE armada-api/src/main/resources/db/migration/V143__hyperlink_marketing_menu_rbac.sql;

-- 只读验收：预期五张业务表存在，且每个启用租户拥有 11 个超链菜单节点。
SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
    'data_package',
    'data_package_phone',
    'data_package_stat',
    'data_package_import',
    'hyperlink_template'
  )
ORDER BY table_name;

SELECT tenant_id, COUNT(*) AS hyperlink_menu_nodes
FROM sys_menu
WHERE menu_key IN (
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
)
GROUP BY tenant_id
ORDER BY tenant_id;
