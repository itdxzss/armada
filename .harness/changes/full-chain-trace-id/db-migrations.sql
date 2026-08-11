-- 与 Flyway V111 保持一致；只给协议命令 Outbox 增加可空 Trace 列。
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'protocol_command_outbox'
       AND column_name = 'trace_id') = 0,
    'ALTER TABLE protocol_command_outbox ADD COLUMN trace_id VARCHAR(32) NULL COMMENT ''全链路追踪标识'' AFTER payload_json',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
