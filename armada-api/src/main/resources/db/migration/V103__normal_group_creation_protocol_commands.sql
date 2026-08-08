-- 新建普群改为 Web/Android 协议 Topic 命令 + 统一结果 Topic 后的命令关联字段。
-- 每一个 WhatsApp 副作用都持久化真实 command_id，结果必须精确匹配后才能推进。

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_item'
       AND column_name = 'create_command_id') = 0,
    'ALTER TABLE normal_group_creation_item ADD COLUMN create_command_id VARCHAR(64) DEFAULT NULL COMMENT ''GROUP_CREATE 当前协议命令ID'' AFTER create_attempt_count',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_item'
       AND column_name = 'settings_command_id') = 0,
    'ALTER TABLE normal_group_creation_item ADD COLUMN settings_command_id VARCHAR(64) DEFAULT NULL COMMENT ''GROUP_SETTINGS_APPLY 当前协议命令ID'' AFTER create_command_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_item'
       AND column_name = 'leave_command_id') = 0,
    'ALTER TABLE normal_group_creation_item ADD COLUMN leave_command_id VARCHAR(64) DEFAULT NULL COMMENT ''GROUP_LEAVE 当前协议命令ID;KEEP策略为空'' AFTER settings_command_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_item'
       AND index_name = 'uq_normal_group_creation_create_command') = 0,
    'ALTER TABLE normal_group_creation_item ADD UNIQUE KEY uq_normal_group_creation_create_command (create_command_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_item'
       AND index_name = 'uq_normal_group_creation_settings_command') = 0,
    'ALTER TABLE normal_group_creation_item ADD UNIQUE KEY uq_normal_group_creation_settings_command (settings_command_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_item'
       AND index_name = 'uq_normal_group_creation_leave_command') = 0,
    'ALTER TABLE normal_group_creation_item ADD UNIQUE KEY uq_normal_group_creation_leave_command (leave_command_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_item_member'
       AND column_name = 'creator_save_command_id') = 0,
    'ALTER TABLE normal_group_creation_item_member ADD COLUMN creator_save_command_id VARCHAR(64) DEFAULT NULL COMMENT ''建群人保存该成员的CONTACT_PREPARE命令ID'' AFTER member_saved_creator_status',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_item_member'
       AND column_name = 'member_save_command_id') = 0,
    'ALTER TABLE normal_group_creation_item_member ADD COLUMN member_save_command_id VARCHAR(64) DEFAULT NULL COMMENT ''该成员保存建群人的CONTACT_PREPARE命令ID'' AFTER creator_save_command_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_item_member'
       AND index_name = 'uq_normal_group_creation_creator_save_command') = 0,
    'ALTER TABLE normal_group_creation_item_member ADD UNIQUE KEY uq_normal_group_creation_creator_save_command (creator_save_command_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_item_member'
       AND index_name = 'uq_normal_group_creation_member_save_command') = 0,
    'ALTER TABLE normal_group_creation_item_member ADD UNIQUE KEY uq_normal_group_creation_member_save_command (member_save_command_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
