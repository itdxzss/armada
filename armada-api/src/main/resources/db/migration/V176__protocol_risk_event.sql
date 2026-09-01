CREATE TABLE IF NOT EXISTS protocol_risk_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '协议风控事件主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    event_id VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '协议事件幂等ID',
    signal_code VARCHAR(64) NOT NULL
        COMMENT '固定信号:RATE_LIMITED/ACCOUNT_REACHOUT_RESTRICTED/CHAT_SUSPENDED',
    scope_type VARCHAR(16) NOT NULL COMMENT '作用域:OPERATION/ACCOUNT/CHAT',
    operation_type VARCHAR(64) DEFAULT NULL COMMENT '触发信号的具体操作',
    account_id BIGINT DEFAULT NULL COMMENT '租户内协议绑定解析出的Armada账号ID',
    protocol_account_id VARCHAR(191) DEFAULT NULL COMMENT '协议账号句柄',
    protocol_backend VARCHAR(32) DEFAULT NULL COMMENT '协议后端快照:WEB/ANDROID',
    source VARCHAR(64) NOT NULL COMMENT '协议事件来源',
    business_type VARCHAR(64) DEFAULT NULL COMMENT '业务来源类型',
    business_id BIGINT DEFAULT NULL COMMENT '任务或群链接ID',
    business_item_id BIGINT DEFAULT NULL COMMENT 'recipient/执行项ID',
    group_business_id BIGINT DEFAULT NULL COMMENT '群链接/群执行/建群项关联ID',
    command_id VARCHAR(191) DEFAULT NULL COMMENT '协议命令ID',
    message_id VARCHAR(191) DEFAULT NULL COMMENT 'WhatsApp消息ID',
    target_kind VARCHAR(16) DEFAULT NULL COMMENT '目标类型:PRIVATE/GROUP',
    chat_jid VARCHAR(191) DEFAULT NULL COMMENT '群聊JID;私聊号码不落本表',
    raw_code VARCHAR(64) DEFAULT NULL COMMENT '协议原始错误码',
    reason_message VARCHAR(255) DEFAULT NULL COMMENT '脱敏后的协议原因摘要',
    is_active TINYINT DEFAULT NULL COMMENT '平台限制是否仍生效:0否1是',
    enforcement_type VARCHAR(64) DEFAULT NULL COMMENT '平台限制类型',
    restricted_until BIGINT DEFAULT NULL COMMENT '平台限制截止时间(epoch毫秒)',
    trace_id VARCHAR(64) DEFAULT NULL COMMENT '跨层追踪ID',
    worker_id VARCHAR(128) DEFAULT NULL COMMENT '协议worker标识',
    occurred_at BIGINT NOT NULL COMMENT '协议事实发生时间(epoch毫秒)',
    received_at BIGINT NOT NULL COMMENT 'Armada首次接收时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_protocol_risk_event (tenant_id, event_id),
    KEY idx_protocol_risk_signal
        (tenant_id, signal_code, occurred_at, id),
    KEY idx_protocol_risk_account
        (tenant_id, account_id, occurred_at, id),
    KEY idx_protocol_risk_business
        (tenant_id, business_type, business_id, occurred_at, id),
    KEY idx_protocol_risk_chat
        (tenant_id, chat_jid, occurred_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='协议风控信号追加事实;当前状态投影不得覆盖本表';
