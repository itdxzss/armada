-- 超链图片素材库前向执行入口。
-- 正式部署由 Flyway 自动执行；手工审阅或隔离测试时仅在仓库根目录启动 mysql 客户端使用。
-- 执行前必须确认目标数据库、完成备份，并确认 V157 未在目标环境被其他迁移占用。

SOURCE armada-api/src/main/resources/db/migration/V157__hyperlink_image_asset_library.sql;

-- 只读验收：预期标签表、素材元数据列、引用索引和每个启用租户的四个菜单节点存在。
SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('resource_asset_tag', 'resource_asset_tag_ref')
ORDER BY table_name;

SELECT column_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'marketing_template_file'
  AND column_name IN ('asset_name', 'width', 'height', 'created_by', 'updated_at')
ORDER BY ordinal_position;

SELECT table_name, index_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND index_name IN (
    'idx_marketing_template_file_name',
    'idx_marketing_template_image_file',
    'idx_hyperlink_template_link_asset',
    'idx_hyperlink_template_body_asset'
  )
ORDER BY table_name, index_name;

SELECT tenant_id, COUNT(*) AS resource_asset_menu_nodes
FROM sys_menu
WHERE menu_key IN (
  'HyperlinkResourceAsset',
  'HyperlinkResourceAssetUpload',
  'HyperlinkResourceAssetEdit',
  'HyperlinkResourceAssetDelete'
)
GROUP BY tenant_id
ORDER BY tenant_id;
