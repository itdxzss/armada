-- 正式迁移文件：armada-api/src/main/resources/db/migration/V115__pull_task_early_call_plan.sql

SET @pull_task_standard_setting_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_standard_setting'
       AND column_name = 'early_pull_count') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN early_pull_count INT NOT NULL DEFAULT 1 COMMENT ''前期每次拉人的料子人数(不含站台)'' AFTER is_clear_existing_members',
    'SELECT 1'
);
PREPARE pull_task_standard_setting_stmt FROM @pull_task_standard_setting_ddl;
EXECUTE pull_task_standard_setting_stmt;
DEALLOCATE PREPARE pull_task_standard_setting_stmt;

SET @pull_task_standard_setting_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_standard_setting'
       AND column_name = 'early_pull_call_count') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN early_pull_call_count INT NOT NULL DEFAULT 0 COMMENT ''按前期固定人数执行的拉人调用次数;0=升级前任务不启用'' AFTER early_pull_count',
    'SELECT 1'
);
PREPARE pull_task_standard_setting_stmt FROM @pull_task_standard_setting_ddl;
EXECUTE pull_task_standard_setting_stmt;
DEALLOCATE PREPARE pull_task_standard_setting_stmt;
