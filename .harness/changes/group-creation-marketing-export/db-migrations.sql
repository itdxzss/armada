SET @gcm_send_member_count_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'group_creation_marketing_item'
      AND column_name = 'send_member_count'
);

SET @gcm_send_member_count_sql := IF(
    @gcm_send_member_count_exists = 0,
    'ALTER TABLE group_creation_marketing_item ADD COLUMN send_member_count INT DEFAULT NULL COMMENT ''发送营销消息前查询到的群人数快照'' AFTER participant_result_json',
    'SELECT 1'
);

PREPARE gcm_send_member_count_stmt FROM @gcm_send_member_count_sql;
EXECUTE gcm_send_member_count_stmt;
DEALLOCATE PREPARE gcm_send_member_count_stmt;

SET @gcm_send_member_count_checked_at_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'group_creation_marketing_item'
      AND column_name = 'send_member_count_checked_at'
);

SET @gcm_send_member_count_checked_at_sql := IF(
    @gcm_send_member_count_checked_at_exists = 0,
    'ALTER TABLE group_creation_marketing_item ADD COLUMN send_member_count_checked_at BIGINT DEFAULT NULL COMMENT ''群人数快照查询时间(epoch毫秒)'' AFTER send_member_count',
    'SELECT 1'
);

PREPARE gcm_send_member_count_checked_at_stmt FROM @gcm_send_member_count_checked_at_sql;
EXECUTE gcm_send_member_count_checked_at_stmt;
DEALLOCATE PREPARE gcm_send_member_count_checked_at_stmt;
