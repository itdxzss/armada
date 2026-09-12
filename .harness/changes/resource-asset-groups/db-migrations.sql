-- 审阅副本；正式发布由 Flyway 按 V189、V190 顺序执行。未对真实库执行。
-- 分组属于素材聚合；删除分组仅清除归属，不删除图片或引用。
CREATE TABLE IF NOT EXISTS resource_asset_group (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '素材分组主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    group_name VARCHAR(64) NOT NULL COMMENT '租户内唯一分组名称',
    created_at BIGINT NOT NULL COMMENT '创建时间 epoch 毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_resource_asset_group_name (tenant_id, group_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='图片素材分组';

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'marketing_template_file' AND column_name = 'group_id') = 0,
    'ALTER TABLE marketing_template_file ADD COLUMN group_id BIGINT NULL COMMENT ''素材分组 ID；NULL 为未分组''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'marketing_template_file' AND index_name = 'idx_asset_group_page') = 0,
    'CREATE INDEX idx_asset_group_page ON marketing_template_file (tenant_id, group_id, deleted_at, created_at, id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 迁移前图片保持 NULL（历史共享）；新上传由应用明确写入业务码，不按日期猜测归属。
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'marketing_template_file' AND column_name = 'asset_scope') = 0,
    'ALTER TABLE marketing_template_file ADD COLUMN asset_scope TINYINT NULL COMMENT ''图片归属：NULL历史共享，1超链，2养群，3普通营销''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'resource_asset_group' AND column_name = 'scope') = 0,
    'ALTER TABLE resource_asset_group ADD COLUMN scope TINYINT NOT NULL DEFAULT 1 COMMENT ''分组所属业务：1超链，2养群''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'resource_asset_group' AND index_name = 'uk_resource_asset_group_scope_name') = 0,
    'CREATE UNIQUE INDEX uk_resource_asset_group_scope_name ON resource_asset_group (tenant_id, scope, group_name)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'resource_asset_group' AND index_name = 'uk_resource_asset_group_name') > 0,
    'DROP INDEX uk_resource_asset_group_name ON resource_asset_group', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史图片可见于两边，各业务的分组归属必须独立，故从单列迁移为每业务一条关系。
CREATE TABLE IF NOT EXISTS resource_asset_group_ref (
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    file_id BIGINT NOT NULL COMMENT '图片文件 ID',
    scope TINYINT NOT NULL COMMENT '归属关系业务：1超链，2养群',
    group_id BIGINT NOT NULL COMMENT '本业务分组 ID',
    created_at BIGINT NOT NULL COMMENT '移组时间 epoch 毫秒',
    PRIMARY KEY (tenant_id, file_id, scope),
    KEY idx_asset_group_ref_group (tenant_id, scope, group_id, file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='图片按业务独立的分组归属';

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'marketing_template_file' AND column_name = 'group_id') > 0,
    'INSERT IGNORE INTO resource_asset_group_ref (tenant_id, file_id, scope, group_id, created_at) SELECT f.tenant_id, f.id, g.scope, g.id, COALESCE(f.updated_at, f.created_at) FROM marketing_template_file f JOIN resource_asset_group g ON g.id = f.group_id AND g.tenant_id = f.tenant_id WHERE f.group_id IS NOT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'marketing_template_file' AND index_name = 'idx_asset_group_page') > 0,
    'DROP INDEX idx_asset_group_page ON marketing_template_file', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'marketing_template_file' AND column_name = 'group_id') > 0,
    'ALTER TABLE marketing_template_file DROP COLUMN group_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'marketing_template_file' AND index_name = 'idx_asset_scope_page') = 0,
    'CREATE INDEX idx_asset_scope_page ON marketing_template_file (tenant_id, asset_scope, deleted_at, created_at, id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
