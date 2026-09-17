-- 同一采购明细持久化有限重试；已分配或结果不明的请求绝不重购。
SET @sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'account_registration_item' AND column_name = 'purchase_attempts'),
  'ALTER TABLE account_registration_item ADD COLUMN purchase_attempts INT NOT NULL DEFAULT 0 COMMENT ''已发起取号次数,包含首次,上限50''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'account_registration_item' AND column_name = 'next_purchase_at'),
  'ALTER TABLE account_registration_item ADD COLUMN next_purchase_at BIGINT NULL COMMENT ''明确无号码后最早再次取号epoch毫秒''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
