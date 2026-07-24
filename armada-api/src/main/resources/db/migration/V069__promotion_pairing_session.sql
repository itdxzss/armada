-- 推广落地页手机号配对会话。只保存流程状态和令牌摘要，不保存会话明文令牌或协议凭据。
CREATE TABLE promotion_pairing_session (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '配对会话主键,例如 7001',
    tenant_id BIGINT NOT NULL COMMENT '渠道所属租户ID,例如 1',
    promotion_channel_id BIGINT NOT NULL COMMENT '获客渠道ID(→promotion_channel.id),例如 5001',
    channel_name VARCHAR(128) NOT NULL COMMENT '创建会话时渠道名称快照,例如 印度投放',
    owner_user_id BIGINT DEFAULT NULL COMMENT '创建会话时渠道归属用户ID快照,例如 81',
    session_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '一次性会话令牌SHA-256十六进制摘要,例如 64位摘要',
    phone VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '完整国际手机号,只含数字,例如 919876543210',
    protocol_account_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '本次尝试的一次性协议账号句柄,例如 acc_pair_7d9ca2f10b8e4c31',
    pairing_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '协议层本次配对ID,例如 pairing_01',
    pairing_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '协议层随机配对码,仅等待确认阶段展示,例如 A1B2C3D4',
    status TINYINT NOT NULL COMMENT '状态:1请求中 2待确认 3落库中 4成功 5失败 6过期,例如 2',
    proxy_id BIGINT DEFAULT NULL COMMENT '本次预留代理ID(→ip_proxy.id),例如 1001',
    proxy_session_id VARCHAR(64) DEFAULT NULL COMMENT 'sticky代理会话ID,例如 sticky001',
    proxy_region VARCHAR(64) DEFAULT NULL COMMENT '实际分配代理国家或区域快照,例如 印度',
    proxy_source VARCHAR(64) DEFAULT NULL COMMENT '实际分配代理来源快照,例如 provider-a',
    account_id BIGINT DEFAULT NULL COMMENT '成功后账号ID(→account.id),未成功为NULL,例如 9001',
    expires_at BIGINT NOT NULL COMMENT '配对码到期时间(epoch毫秒),例如 1800000000000',
    error_code VARCHAR(64) DEFAULT NULL COMMENT '脱敏失败码,例如 PROTOCOL_REJECTED',
    error_message VARCHAR(255) DEFAULT NULL COMMENT '可展示失败摘要,例如 配对已失效请重试',
    completed_at BIGINT DEFAULT NULL COMMENT '成功或失败完成时间(epoch毫秒),例如 1800000000000',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒),例如 1800000000000',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒),例如 1800000000000',
    active_protocol_account_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
        GENERATED ALWAYS AS (CASE WHEN status IN (1, 2, 3) THEN protocol_account_id ELSE NULL END) VIRTUAL
        COMMENT '未结束会话的协议账号唯一键辅助列',
    active_phone VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
        GENERATED ALWAYS AS (CASE WHEN status IN (1, 2, 3) THEN phone ELSE NULL END) VIRTUAL
        COMMENT '未结束会话的手机号唯一键辅助列',
    PRIMARY KEY (id),
    UNIQUE KEY uq_promotion_pairing_token (session_token_hash),
    UNIQUE KEY uq_promotion_pairing_active_account (active_protocol_account_id),
    UNIQUE KEY uq_promotion_pairing_active_phone (active_phone),
    KEY idx_promotion_pairing_channel_created (tenant_id, promotion_channel_id, created_at),
    KEY idx_promotion_pairing_expiry_scan (status, expires_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='推广落地页WhatsApp手机号配对会话';
