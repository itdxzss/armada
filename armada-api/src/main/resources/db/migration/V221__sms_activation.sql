-- 后台手动接码：运营取一个号、看到号码与验证码；与自动注册的 account_registration_* 互不读写。
CREATE TABLE IF NOT EXISTS sms_activation (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id BIGINT NOT NULL COMMENT '租户ID',
  activation_id VARCHAR(40) NOT NULL COMMENT 'Grizzly订单ID',
  phone_number VARCHAR(32) NOT NULL COMMENT '完整号码,禁止日志输出',
  service_code VARCHAR(64) NOT NULL COMMENT '供应商服务代码,当前为WhatsApp',
  country_id VARCHAR(16) NOT NULL COMMENT '供应商国家目录ID',
  cost DECIMAL(30,12) NOT NULL COMMENT '供应商返回实际花费,不隐含币种',
  currency INT NOT NULL COMMENT '供应商返回ISO4217数字币种',
  state TINYINT NOT NULL COMMENT '1等码2已收码3已完成4已取消5已过期',
  sms_code VARCHAR(16) DEFAULT NULL COMMENT '收到的验证码,禁止日志输出',
  received_at BIGINT DEFAULT NULL COMMENT '收到验证码时间epoch毫秒',
  cancel_after BIGINT NOT NULL COMMENT '最早可取消时间epoch毫秒',
  expires_at BIGINT NOT NULL COMMENT '号码到期时间epoch毫秒',
  created_by BIGINT DEFAULT NULL COMMENT '取号操作人用户ID',
  created_at BIGINT NOT NULL COMMENT '创建时间epoch毫秒',
  updated_at BIGINT NOT NULL COMMENT '变更时间epoch毫秒',
  PRIMARY KEY (id),
  UNIQUE KEY uq_sms_activation (activation_id),
  KEY idx_sms_activation_page (tenant_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台手动接码订单';
