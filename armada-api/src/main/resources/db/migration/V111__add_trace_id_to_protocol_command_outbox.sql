-- Trace 只属于一次命令执行链路，持久化在事务 Outbox 以跨越提交、重试和进程重启。
-- 历史行保持 NULL，由发布端按 command_id 稳定派生；首期不回填，也不创建索引。
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'protocol_command_outbox'
       AND column_name = 'trace_id') = 0,
    'ALTER TABLE protocol_command_outbox ADD COLUMN trace_id VARCHAR(32) NULL COMMENT ''全链路追踪标识'' AFTER payload_json',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
