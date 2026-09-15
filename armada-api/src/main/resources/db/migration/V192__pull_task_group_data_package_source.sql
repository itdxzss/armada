-- 拉群保留原执行模型，仅记录独立资源的可追溯来源。
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution' AND column_name = 'source_package_id') = 0,
    'ALTER TABLE pull_task_group_execution ADD COLUMN source_package_id BIGINT NULL COMMENT ''独立数据包来源；原文件上传为空''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution' AND column_name = 'source_package_generation') = 0,
    'ALTER TABLE pull_task_group_execution ADD COLUMN source_package_generation INT NULL COMMENT ''草稿冻结的数据包代次；防覆盖后的陈旧提交''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pull_task_material_member' AND column_name = 'source_package_phone_id') = 0,
    'ALTER TABLE pull_task_material_member ADD COLUMN source_package_phone_id BIGINT NULL COMMENT ''数据包号码来源；换群重试原样保留''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pull_task_material_member' AND column_name = 'source_allocation_version') = 0,
    'ALTER TABLE pull_task_material_member ADD COLUMN source_allocation_version BIGINT NULL COMMENT ''任务领取资源的分配版本；拒绝旧分配回写''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution' AND index_name = 'idx_execution_package_source') = 0,
    'CREATE INDEX idx_execution_package_source ON pull_task_group_execution (tenant_id, source_package_id, task_id, seq, attempt_no)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'pull_task_material_member' AND index_name = 'idx_material_package_source') = 0,
    'CREATE INDEX idx_material_package_source ON pull_task_material_member (tenant_id, group_execution_id, source_package_phone_id, source_allocation_version)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
