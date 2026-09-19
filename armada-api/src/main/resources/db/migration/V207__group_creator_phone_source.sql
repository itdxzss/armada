-- 创建者兼容投影只增加证据来源，不复制号码；旧非空号码维持既有确认级别。
SET @creator_source_missing = (
  SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'group_link_preview'
    AND column_name = 'creator_phone_source'
);
SET @creator_source_ddl = IF(@creator_source_missing,
  'ALTER TABLE group_link_preview ADD COLUMN creator_phone_source TINYINT NOT NULL DEFAULT 2 COMMENT ''创建者号码来源:0未知 1老格式群JID推导 2协议确认或既有号码'' AFTER owner_phone',
  'SELECT 1');
PREPARE creator_source_stmt FROM @creator_source_ddl;
EXECUTE creator_source_stmt;
DEALLOCATE PREPARE creator_source_stmt;
-- 空号码没有创建者证据，供后续 JID 兜底填充；不改变任何原手机号及地区。
UPDATE group_link_preview SET creator_phone_source = 0
WHERE NULLIF(TRIM(owner_phone), '') IS NULL AND creator_phone_source = 2;
