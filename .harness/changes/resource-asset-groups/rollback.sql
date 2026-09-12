-- 审阅脚本，未执行。确认目标环境并备份后，停止写入；在回退到引用 group_id 的旧应用前执行。
-- 保留新 scope / 关系表及养群关系。旧应用不支持业务隔离，回退会恢复混合可见行为。
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'marketing_template_file' AND column_name = 'group_id') = 0,
    'ALTER TABLE marketing_template_file ADD COLUMN group_id BIGINT NULL COMMENT ''旧版兼容分组 ID''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE marketing_template_file f
LEFT JOIN resource_asset_group_ref r ON r.tenant_id = f.tenant_id AND r.file_id = f.id AND r.scope = 1
SET f.group_id = r.group_id;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'marketing_template_file' AND index_name = 'idx_asset_group_page') = 0,
    'CREATE INDEX idx_asset_group_page ON marketing_template_file (tenant_id, group_id, deleted_at, created_at, id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
-- 不恢复旧 tenant_id + group_name 唯一键：两业务可能存在合法同名组。
-- 不删除任何新列/新表，不改 Flyway history。再次前进发布前需核对回退期间写入的图片/分组关系。
