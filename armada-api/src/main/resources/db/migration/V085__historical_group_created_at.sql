SET @sql = IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link_preview'
       AND column_name = 'group_created_at') = 0,
    'ALTER TABLE group_link_preview ADD COLUMN group_created_at BIGINT DEFAULT NULL COMMENT ''WhatsApp群创建时间(Unix秒)'' AFTER announce_only',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
