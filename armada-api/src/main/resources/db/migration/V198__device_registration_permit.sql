CREATE TABLE IF NOT EXISTS account_registration_device_permit (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '设备注册许可主键',
  tenant_id BIGINT NOT NULL COMMENT '租户ID',
  device_id VARCHAR(36) NOT NULL COMMENT '已配对设备UUID',
  request_id VARCHAR(36) NOT NULL COMMENT '当前单次采购授权UUID',
  country_id VARCHAR(16) NOT NULL COMMENT '本次授权国家渠道',
  unit_price DECIMAL(30,12) NOT NULL COMMENT '本次授权精确单价',
  provider_id VARCHAR(32) NULL COMMENT '默认商家,手机可在同价档选择有效商家',
  replaces_request_id VARCHAR(36) NULL COMMENT '允许替代的无订单终态请求UUID',
  expires_at BIGINT NOT NULL COMMENT '本次采购授权截止epoch毫秒',
  created_at BIGINT NOT NULL COMMENT '首次配置时间epoch毫秒',
  updated_at BIGINT NOT NULL COMMENT '当前许可更新时间epoch毫秒',
  PRIMARY KEY (id),
  UNIQUE KEY uq_registration_device_permit (tenant_id, device_id),
  UNIQUE KEY uq_registration_permit_request (tenant_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='控端管理的单设备单次采购许可,不包含认证秘密';
