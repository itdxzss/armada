-- 原生失败事实属于注册明细；不改变采购次数或短信订单。
SET @sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'account_registration_item' AND column_name = 'failure_kind'),
  'ALTER TABLE account_registration_item ADD COLUMN failure_kind TINYINT NULL COMMENT ''手机原生失败类别:1号码2限频3未知,NULL非手机失败''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'account_registration_item' AND column_name = 'failure_detail'),
  'ALTER TABLE account_registration_item ADD COLUMN failure_detail VARCHAR(256) NULL COMMENT ''手机原生失败详情,最多256字符,不进入日志''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
