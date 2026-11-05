-- 手动接码订单的 Go 注册办理：一个订单最多一条，后台定时任务据此推进发码、等码、验码。
-- 号码、验证码、供应商订单号只保存在 sms_activation，本表不复制，避免同一事实两处存放。
CREATE TABLE IF NOT EXISTS sms_registration (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id BIGINT NOT NULL COMMENT '租户ID',
  sms_activation_id BIGINT NOT NULL COMMENT '接码订单ID,一个订单最多一条办理记录',
  state TINYINT NOT NULL COMMENT '1申请中2等码3验证中4已注册5需处理',
  go_profile_id BIGINT NOT NULL COMMENT 'Go注册配置ID,开始时固定,重放必须一致',
  proxy_id BIGINT DEFAULT NULL COMMENT '本次占用的代理ID,调用Go发码前已写入',
  go_transaction_id VARCHAR(36) DEFAULT NULL COMMENT 'Go注册事务ID,发码成功后写入',
  go_version BIGINT DEFAULT NULL COMMENT '发码成功时Go返回的version,只写一次,验码永远使用它',
  failure_code VARCHAR(40) NOT NULL DEFAULT '' COMMENT '需处理原因固定分类,非需处理时为空串',
  created_by BIGINT DEFAULT NULL COMMENT '点击开始注册的操作人用户ID',
  created_at BIGINT NOT NULL COMMENT '创建时间epoch毫秒',
  updated_at BIGINT NOT NULL COMMENT '变更时间epoch毫秒',
  PRIMARY KEY (id),
  UNIQUE KEY uq_sms_registration_activation (sms_activation_id),
  KEY idx_sms_registration_state (state, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接码订单的Go注册办理';
