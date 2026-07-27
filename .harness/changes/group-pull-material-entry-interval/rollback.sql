-- 前置条件：先回退应用代码，并确认没有新版本实例继续读写该字段。
-- 删除配置列会丢失任务自定义拉料间隔。

SET @material_entry_interval_col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'group_pull_marketing_task'
      AND column_name = 'material_entry_interval_seconds'
);
SET @sql := IF(
    @material_entry_interval_col_exists > 0,
    'ALTER TABLE group_pull_marketing_task DROP COLUMN material_entry_interval_seconds',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
