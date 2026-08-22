-- 标准拉人任务可选群主退群，以及单群执行的最小结果。

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_standard_setting'
       AND column_name = 'is_creator_leave_after_pull') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN is_creator_leave_after_pull TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''单群拉人结束后是否执行群主退群:0=否 1=是''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_account'
       AND column_name = 'role_type') = 1,
    'ALTER TABLE pull_task_group_account MODIFY COLUMN role_type TINYINT NOT NULL COMMENT ''角色:1=管理 2=拉手 3=站台 4=提权管理员/建群者 5=群主退群待提升普通成员''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_account_action'
       AND column_name = 'action_type') = 1,
    'ALTER TABLE pull_task_account_action MODIFY COLUMN action_type TINYINT NOT NULL COMMENT ''动作类型:1=保存联系人 2=邀请入群 3=踩链接入群 4=设置任务管理员 5=放开加人权限 6=关闭进群审核 7=应用群资料设置 8=提升群主接管成员 9=建群者退群''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution'
       AND column_name = 'creator_leave_result') = 0,
    'ALTER TABLE pull_task_group_execution ADD COLUMN creator_leave_result TINYINT NOT NULL DEFAULT 0 COMMENT ''群主退群结果:0=未执行 1=成功 2=非建群者 3=建群者不可用 4=无控端管理员或普通成员 5=管理员设置失败 6=退群失败''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution'
       AND column_name = 'creator_leave_reason') = 0,
    'ALTER TABLE pull_task_group_execution ADD COLUMN creator_leave_reason VARCHAR(255) DEFAULT NULL COMMENT ''群主退群未执行或失败原因''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
