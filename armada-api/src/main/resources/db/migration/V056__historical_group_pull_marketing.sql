SET @baseline_group_subjects_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group_baseline'
      AND column_name = 'baseline_group_subjects'
);

SET @baseline_group_subjects_sql := IF(
    @baseline_group_subjects_exists = 0,
    'ALTER TABLE account_group_baseline ADD COLUMN baseline_group_subjects JSON NULL COMMENT ''首次拍基线时轻量载荷已有的JID到静态群名映射;不表示当前成员关系'' AFTER baseline_group_jids',
    'SELECT 1'
);

PREPARE baseline_group_subjects_stmt FROM @baseline_group_subjects_sql;
EXECUTE baseline_group_subjects_stmt;
DEALLOCATE PREPARE baseline_group_subjects_stmt;
