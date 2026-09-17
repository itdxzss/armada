-- 商家筛选属于采购任务约束；旧的自动报价任务保留 NULL。
SET @sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'account_registration_task' AND column_name = 'provider_id'),
  'ALTER TABLE account_registration_task ADD COLUMN provider_id VARCHAR(32) NULL COMMENT ''手机许可指定的唯一接码商家,NULL按报价选择''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
