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
