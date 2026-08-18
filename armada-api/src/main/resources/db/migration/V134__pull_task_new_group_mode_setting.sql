-- 新群模式：任务冻结配置增加建群人分组与建群时初始站台数量。
--
-- 只加这三列。群名来源、手工群名、群头像、群描述、群设置执行时机，以及禁言/群链接权限/
-- 限时消息/编辑权限，V095 建的 pull_task_standard_group_setting 已经全部具备：
--   setting_timing                     设置顺序:1=拉人前 2=拉完后
--   group_name                         手工群名
--   is_material_filename_as_group_name 是否用对应 TXT 文件名当群名
--   avatar_file_key                    群头像
--   group_description                  群描述
-- 新群模式一律复用这些既有列，不另起一套，否则同一份配置会有两个真相。
--
-- 注意 setting_timing 目前只写不读：PullTaskGroupSettingsGate 不按它分支。
-- 让它真正生效属于后续切片，不在本迁移范围。
--
-- initial_station_count 默认 0：存量任务没有「建群时进群」的站台，
-- 默认非 0 会让既有资源校验凭空多出一笔需求。

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
       AND column_name = 'initial_station_count') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN initial_station_count INT NOT NULL DEFAULT 0 COMMENT ''建群时作为初始成员加入的站台数量;与station_count_per_call是两笔独立需求,资源校验须同时计入'' AFTER station_count_per_call',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
