-- 同一注册聚合支持手机执行；默认值保留旧 Cobalt 行为。
SET @sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'account_registration_task' AND column_name = 'execution_mode'),
  'ALTER TABLE account_registration_task ADD COLUMN execution_mode TINYINT NOT NULL DEFAULT 1 COMMENT ''执行端:1Cobalt2iOS手机''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'account_registration_task' AND column_name = 'device_id'),
  'ALTER TABLE account_registration_task ADD COLUMN device_id VARCHAR(36) DEFAULT NULL COMMENT ''手机执行设备UUID,由注册许可绑定''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'account_registration_task' AND column_name = 'purchase_before'),
  'ALTER TABLE account_registration_task ADD COLUMN purchase_before BIGINT DEFAULT NULL COMMENT ''手机采购许可截止epoch毫秒''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
ALTER TABLE account_registration_task MODIFY COLUMN account_group_id BIGINT NULL COMMENT 'Cobalt导入目标分组,手机注册为空';
ALTER TABLE account_registration_task MODIFY COLUMN ip_allocation_mode VARCHAR(16) NULL COMMENT 'Cobalt上线IP模式,手机注册为空';
ALTER TABLE account_registration_item MODIFY COLUMN state TINYINT NOT NULL DEFAULT 1 COMMENT '1待采购2采购中3等码4注册中5导入中6等待在线7成功8失败9结果不明10已取消11等待取消12手机报告注册成功';
