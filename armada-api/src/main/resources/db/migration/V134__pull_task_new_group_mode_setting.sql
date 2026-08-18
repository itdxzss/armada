-- 新群模式：任务冻结配置增加建群相关字段。
--
-- 全部落在既有的 pull_task_standard_setting（普通拉群任务冻结执行配置），不另建配置表。
-- 群链接模式的任务这些列为空或取默认值，行为不变。
--
-- initial_station_count 默认 0：存量任务没有「建群时进群」的站台，
-- 默认非 0 会让既有资源校验凭空多出一笔需求。
--
-- 注意 group_settings_timing 的语义（ADR-0012 之外的独立配置）：
--   BEFORE_PULL = 拉人前设置，在建群阶段第 6 步执行；
--   AFTER_PULL  = 拉完人后设置，留到收口阶段执行。
--   群链接模式的存量任务为 NULL，沿用其既有的固定时机，不受影响。

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_standard_setting'
       AND column_name = 'creator_group_id') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN creator_group_id BIGINT DEFAULT NULL COMMENT ''建群人账号分组ID(→account_group.id);新群模式必填,群链接模式为空'' AFTER station_group_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_standard_setting'
       AND column_name = 'creator_group_name') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN creator_group_name VARCHAR(100) DEFAULT NULL COMMENT ''建群人分组名称快照'' AFTER creator_group_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_standard_setting'
       AND column_name = 'group_name_source') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN group_name_source VARCHAR(32) DEFAULT NULL COMMENT ''群名来源:MATERIAL_FILE_NAME取本执行行绑定的料子TXT文件名 MANUAL手动填写'' AFTER creator_group_name',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_standard_setting'
       AND column_name = 'group_name_text') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN group_name_text VARCHAR(255) DEFAULT NULL COMMENT ''手动群名;group_name_source=MANUAL时必填'' AFTER group_name_source',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_standard_setting'
       AND column_name = 'group_description') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN group_description VARCHAR(512) DEFAULT NULL COMMENT ''群描述;为空表示不设置'' AFTER group_name_text',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_standard_setting'
       AND column_name = 'group_avatar_material_id') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN group_avatar_material_id BIGINT DEFAULT NULL COMMENT ''群头像素材ID;为空表示不设置'' AFTER group_description',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_standard_setting'
       AND column_name = 'initial_station_count') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN initial_station_count INT NOT NULL DEFAULT 0 COMMENT ''建群时作为初始成员加入的站台数量;与station_count_per_call是两笔独立需求,资源校验须同时计入'' AFTER group_avatar_material_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_standard_setting'
       AND column_name = 'group_settings_timing') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN group_settings_timing VARCHAR(16) DEFAULT NULL COMMENT ''群设置执行时机:BEFORE_PULL拉人前(建群阶段执行) AFTER_PULL拉完人后(收口阶段执行);群链接模式为空'' AFTER initial_station_count',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
