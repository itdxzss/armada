-- 价格不符时按供应商取消窗口恢复执行；不复用采购时间或执行租约。
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'account_registration_item' AND column_name = 'cancel_after') = 0,
    'ALTER TABLE account_registration_item ADD COLUMN cancel_after BIGINT NULL COMMENT ''价格不符订单最早取消时间epoch毫秒''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ALTER TABLE account_registration_item MODIFY COLUMN state TINYINT NOT NULL DEFAULT 1 COMMENT '1待采购2采购中3等码4注册中5导入中6等待在线7成功8失败9结果不明10已取消11等待取消';
