-- 引用内容与去掉引用的原因均属于单次发送事实；旧任务字段为空。
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'script_marketing_send_record' AND column_name = 'quote_context_json') = 0,
    'ALTER TABLE script_marketing_send_record ADD COLUMN quote_context_json JSON NULL COMMENT ''原消息实际发送者与最小引用内容快照''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'script_marketing_send_record' AND column_name = 'reply_fallback_reason') = 0,
    'ALTER TABLE script_marketing_send_record ADD COLUMN reply_fallback_reason VARCHAR(64) NULL COMMENT ''本次去掉引用继续发送的原因，独立于发送结果''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
