-- 超链图片素材库：复用 marketing_template_file 保存唯一图片字节，补充管理元数据、标签、引用索引和 RBAC。
-- V157 由超链任务占用但尚未合入当前分支；本迁移不得预判或引用其表结构。
-- 发布前必须先合入并应用 V157；禁止在缺少 V157 的环境抢先执行 V158。

SET @resource_asset_schema := DATABASE();

SET @resource_asset_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @resource_asset_schema
       AND table_name = 'marketing_template_file'
       AND column_name = 'asset_name') = 0,
    'ALTER TABLE marketing_template_file ADD COLUMN asset_name VARCHAR(128) DEFAULT NULL COMMENT ''素材业务名称'' AFTER content',
    'SELECT 1');
PREPARE resource_asset_stmt FROM @resource_asset_sql;
EXECUTE resource_asset_stmt;
DEALLOCATE PREPARE resource_asset_stmt;

SET @resource_asset_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @resource_asset_schema
       AND table_name = 'marketing_template_file'
       AND column_name = 'width') = 0,
    'ALTER TABLE marketing_template_file ADD COLUMN width INT DEFAULT NULL COMMENT ''图片宽度像素'' AFTER asset_name',
    'SELECT 1');
PREPARE resource_asset_stmt FROM @resource_asset_sql;
EXECUTE resource_asset_stmt;
DEALLOCATE PREPARE resource_asset_stmt;

SET @resource_asset_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @resource_asset_schema
       AND table_name = 'marketing_template_file'
       AND column_name = 'height') = 0,
    'ALTER TABLE marketing_template_file ADD COLUMN height INT DEFAULT NULL COMMENT ''图片高度像素'' AFTER width',
    'SELECT 1');
PREPARE resource_asset_stmt FROM @resource_asset_sql;
EXECUTE resource_asset_stmt;
DEALLOCATE PREPARE resource_asset_stmt;

SET @resource_asset_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @resource_asset_schema
       AND table_name = 'marketing_template_file'
       AND column_name = 'created_by') = 0,
    'ALTER TABLE marketing_template_file ADD COLUMN created_by BIGINT DEFAULT NULL COMMENT ''上传人用户ID'' AFTER height',
    'SELECT 1');
PREPARE resource_asset_stmt FROM @resource_asset_sql;
EXECUTE resource_asset_stmt;
DEALLOCATE PREPARE resource_asset_stmt;

SET @resource_asset_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @resource_asset_schema
       AND table_name = 'marketing_template_file'
       AND column_name = 'updated_at') = 0,
    'ALTER TABLE marketing_template_file ADD COLUMN updated_at BIGINT DEFAULT NULL COMMENT ''更新时间(epoch毫秒)'' AFTER created_at',
    'SELECT 1');
PREPARE resource_asset_stmt FROM @resource_asset_sql;
EXECUTE resource_asset_stmt;
DEALLOCATE PREPARE resource_asset_stmt;

UPDATE marketing_template_file
SET asset_name = LEFT(
        COALESCE(NULLIF(TRIM(original_filename), ''), CONCAT('素材 #', id)),
        128)
WHERE asset_name IS NULL OR TRIM(asset_name) = '';

UPDATE marketing_template_file
SET updated_at = created_at
WHERE updated_at IS NULL;

SET @resource_asset_sql := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = @resource_asset_schema
       AND table_name = 'marketing_template_file'
       AND index_name = 'idx_marketing_template_file_name') = 0,
    'ALTER TABLE marketing_template_file ADD KEY idx_marketing_template_file_name (tenant_id, deleted_at, asset_name, id)',
    'SELECT 1');
PREPARE resource_asset_stmt FROM @resource_asset_sql;
EXECUTE resource_asset_stmt;
DEALLOCATE PREPARE resource_asset_stmt;

CREATE TABLE IF NOT EXISTS resource_asset_tag (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '标签主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    tag_name VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '大小写敏感标签名',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_resource_asset_tag (tenant_id, tag_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='图片素材标签字典';

CREATE TABLE IF NOT EXISTS resource_asset_tag_ref (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '关系主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    file_id BIGINT NOT NULL COMMENT 'marketing_template_file.id',
    resource_asset_tag_id BIGINT NOT NULL COMMENT 'resource_asset_tag.id',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_resource_asset_tag_ref (tenant_id, file_id, resource_asset_tag_id),
    KEY idx_resource_asset_tag_ref_tag (tenant_id, resource_asset_tag_id, file_id),
    KEY idx_resource_asset_tag_ref_file (tenant_id, file_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='图片素材标签关系';

SET @resource_asset_sql := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = @resource_asset_schema
       AND table_name = 'marketing_template'
       AND index_name = 'idx_marketing_template_image_file') = 0,
    'ALTER TABLE marketing_template ADD KEY idx_marketing_template_image_file (tenant_id, image_file_id, deleted_at, id)',
    'SELECT 1');
PREPARE resource_asset_stmt FROM @resource_asset_sql;
EXECUTE resource_asset_stmt;
DEALLOCATE PREPARE resource_asset_stmt;

SET @resource_asset_sql := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = @resource_asset_schema
       AND table_name = 'hyperlink_template'
       AND index_name = 'idx_hyperlink_template_link_asset') = 0,
    'ALTER TABLE hyperlink_template ADD KEY idx_hyperlink_template_link_asset (tenant_id, link_preview_asset_id, deleted_at, id)',
    'SELECT 1');
PREPARE resource_asset_stmt FROM @resource_asset_sql;
EXECUTE resource_asset_stmt;
DEALLOCATE PREPARE resource_asset_stmt;

SET @resource_asset_sql := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = @resource_asset_schema
       AND table_name = 'hyperlink_template'
       AND index_name = 'idx_hyperlink_template_body_asset') = 0,
    'ALTER TABLE hyperlink_template ADD KEY idx_hyperlink_template_body_asset (tenant_id, body_main_asset_id, deleted_at, id)',
    'SELECT 1');
PREPARE resource_asset_stmt FROM @resource_asset_sql;
EXECUTE resource_asset_stmt;
DEALLOCATE PREPARE resource_asset_stmt;

SET @resource_asset_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

UPDATE sys_menu
SET sort_no = CASE menu_key
        WHEN 'HyperlinkDataPackage' THEN 20
        WHEN 'HyperlinkTemplate' THEN 30
        ELSE sort_no
    END,
    updated_at = @resource_asset_now
WHERE menu_key IN ('HyperlinkDataPackage', 'HyperlinkTemplate');

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT tenant.id, parent.id, '图片素材', 'HyperlinkResourceAsset', 'M', '/hyperlink/library',
       'hyperlink/library/index', 'tenant:resource_asset:view', 'solar:gallery-wide-bold-duotone', 50, 1,
       @resource_asset_now, NULL, @resource_asset_now, NULL
FROM tenant
INNER JOIN sys_menu parent
    ON parent.tenant_id = tenant.id
   AND parent.menu_key = 'HyperlinkMarketing'
WHERE tenant.status = 1;

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT parent.tenant_id, parent.id, permission.menu_name, permission.menu_key, 'B', NULL,
       NULL, permission.perm_key, NULL, permission.sort_no, 1,
       @resource_asset_now, NULL, @resource_asset_now, NULL
FROM sys_menu parent
INNER JOIN (
    SELECT '上传图片素材' AS menu_name, 'HyperlinkResourceAssetUpload' AS menu_key,
           'tenant:resource_asset:upload' AS perm_key, 10 AS sort_no
    UNION ALL
    SELECT '编辑图片素材', 'HyperlinkResourceAssetEdit', 'tenant:resource_asset:edit', 20
    UNION ALL
    SELECT '删除图片素材', 'HyperlinkResourceAssetDelete', 'tenant:resource_asset:delete', 30
) permission
WHERE parent.menu_key = 'HyperlinkResourceAsset';
