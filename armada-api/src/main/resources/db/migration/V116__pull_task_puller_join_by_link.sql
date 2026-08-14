-- 普通群链接拉群任务增加“拉手踩链接进群”配置，历史任务保持管理员邀请方式。

SET @pull_task_standard_setting_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_standard_setting'
       AND column_name = 'is_puller_join_by_link') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN is_puller_join_by_link TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''拉手是否踩链接进群:0否 1是'' AFTER is_clear_existing_members',
    'SELECT 1'
);
PREPARE pull_task_standard_setting_stmt FROM @pull_task_standard_setting_ddl;
EXECUTE pull_task_standard_setting_stmt;
DEALLOCATE PREPARE pull_task_standard_setting_stmt;
