SET @gcm_send_interval_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'group_creation_marketing_task'
      AND column_name = 'send_interval_seconds'
);

SET @gcm_send_interval_sql := IF(
    @gcm_send_interval_exists = 0,
    'ALTER TABLE group_creation_marketing_task ADD COLUMN send_interval_seconds INT NOT NULL DEFAULT 30 COMMENT ''持续营销发送间隔秒数'' AFTER abandoned_count',
    'SELECT 1'
);

PREPARE gcm_send_interval_stmt FROM @gcm_send_interval_sql;
EXECUTE gcm_send_interval_stmt;
DEALLOCATE PREPARE gcm_send_interval_stmt;

ALTER TABLE group_creation_marketing_task
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1=待执行 2=执行中 3=成功 4=失败 5=部分失败 6=已停止';
