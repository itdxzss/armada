-- 仅在所有依赖 Trace 的应用版本均已退出后人工清理；普通应用回滚应保留这个可空列。
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'protocol_command_outbox'
       AND column_name = 'trace_id') = 1,
    'ALTER TABLE protocol_command_outbox DROP COLUMN trace_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
