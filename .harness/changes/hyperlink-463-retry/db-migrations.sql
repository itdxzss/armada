-- 目标与发信人的拒绝关系独立持久化；换号重试及重启后不得再次选中同一拒绝配对。
CREATE TABLE IF NOT EXISTS hyperlink_recipient_sender_rejection (
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    recipient_id BIGINT NOT NULL COMMENT '超链任务逻辑收件人ID',
    account_id BIGINT NOT NULL COMMENT '已拒绝该目标的发信账号ID',
    reason_code VARCHAR(64) NOT NULL COMMENT '明确拒绝码，目前为WA_ACK_REJECTED_463',
    created_at BIGINT NOT NULL COMMENT '首次记录拒绝的时间(epoch毫秒)',
    PRIMARY KEY (tenant_id, recipient_id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='超链收件人与发信人拒绝配对事实';
