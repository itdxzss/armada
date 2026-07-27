-- 拉群营销逐个添加料子时的基准间隔，历史任务按 5 分钟执行。

SET @material_entry_interval_col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'group_pull_marketing_task'
      AND column_name = 'material_entry_interval_seconds'
);
SET @sql := IF(
    @material_entry_interval_col_exists = 0,
    'ALTER TABLE group_pull_marketing_task
       ADD COLUMN material_entry_interval_seconds INT NOT NULL DEFAULT 300
       COMMENT ''逐个拉料的基准间隔秒数;实际按上下20%随机''
       AFTER material_per_group',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
