-- 破坏性回滚脚本：会删除素材标签、菜单权限和素材管理元数据。
-- 仅允许在旧版前后端已恢复、素材元数据已备份、所有引用均已确认后执行。
-- Flyway 已登记 V158 的环境还必须按部署规范处理 schema history，不能只执行本文件。

DELETE role_menu
FROM sys_role_menu role_menu
INNER JOIN sys_menu menu ON menu.id = role_menu.menu_id
WHERE menu.menu_key IN (
  'HyperlinkResourceAsset',
  'HyperlinkResourceAssetUpload',
  'HyperlinkResourceAssetEdit',
  'HyperlinkResourceAssetDelete'
);

DELETE FROM sys_menu
WHERE menu_key IN (
  'HyperlinkResourceAssetUpload',
  'HyperlinkResourceAssetEdit',
  'HyperlinkResourceAssetDelete',
  'HyperlinkResourceAsset'
);

UPDATE sys_menu
SET sort_no = CASE menu_key
        WHEN 'HyperlinkDataPackage' THEN 10
        WHEN 'HyperlinkTemplate' THEN 20
        ELSE sort_no
    END
WHERE menu_key IN ('HyperlinkDataPackage', 'HyperlinkTemplate');

DROP TABLE IF EXISTS resource_asset_tag_ref;
DROP TABLE IF EXISTS resource_asset_tag;

ALTER TABLE hyperlink_template DROP INDEX idx_hyperlink_template_body_asset;
ALTER TABLE hyperlink_template DROP INDEX idx_hyperlink_template_link_asset;
ALTER TABLE marketing_template DROP INDEX idx_marketing_template_image_file;
ALTER TABLE marketing_template_file DROP INDEX idx_marketing_template_file_name;

ALTER TABLE marketing_template_file DROP COLUMN updated_at;
ALTER TABLE marketing_template_file DROP COLUMN created_by;
ALTER TABLE marketing_template_file DROP COLUMN height;
ALTER TABLE marketing_template_file DROP COLUMN width;
ALTER TABLE marketing_template_file DROP COLUMN asset_name;
