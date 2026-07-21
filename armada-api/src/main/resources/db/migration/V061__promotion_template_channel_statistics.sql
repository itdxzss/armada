-- V061：推广落地页模板与渠道管理数据模型；渠道统计相关表延后建设。
-- 业务表统一 tenant_id 行隔离、BIGINT epoch毫秒时间和 utf8mb4；不创建物理外键。

-- 落地页模板表，保存渠道可以绑定的落地页模板，多个渠道绑定一个模板
CREATE TABLE promotion_landing_template (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '落地页模板主键,例如 1001',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID,例如 1',
    template_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '稳定程序编码,例如 base_sex',
    template_name VARCHAR(128) NOT NULL COMMENT '运营展示名称,例如 基础约会-投男粉',
    preview_uri VARCHAR(512) DEFAULT NULL COMMENT '预览资源URI,例如 /preview/base_sex.png',
    supported_params JSON DEFAULT NULL COMMENT '支持参数JSON数组,例如 ["themeColor","showAppDownload"]',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '模板状态:1=启用 0=禁用,例如 1',
    remark VARCHAR(500) DEFAULT NULL COMMENT '运营备注,例如 巴西渠道默认模板',
    created_by BIGINT DEFAULT NULL COMMENT '创建人用户ID,例如 20001',
    updated_by BIGINT DEFAULT NULL COMMENT '最近修改人用户ID,例如 20002',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒),例如 1784217600000',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒),例如 1784217660000',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);未删除为NULL,例如 NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uq_promotion_landing_template_code (tenant_id, template_code),
    KEY idx_promotion_landing_template_available (tenant_id, status, deleted_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='推广落地页模板';

-- 域名与模板绑定表，一个域名只能绑定同一个模板，但是一个模板和域名可以创建多个渠道，这样可以防止跨租户抢占同一个域名。如果业务允许不同租户共享域名，这个唯一约束就需要调整
CREATE TABLE promotion_domain (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '推广域名记录主键,例如 3001',
    tenant_id BIGINT NOT NULL COMMENT '域名所属租户ID,例如 1',
    domain_host VARCHAR(253) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化小写或Punycode主机名,例如 go.example.com',
    landing_template_id BIGINT NOT NULL COMMENT '绑定落地页模板ID(→promotion_landing_template.id),例如 1001',
    created_by BIGINT DEFAULT NULL COMMENT '创建人用户ID,例如 20001',
    updated_by BIGINT DEFAULT NULL COMMENT '最近修改人用户ID,例如 20002',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒),例如 1784217600000',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒),例如 1784217660000',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);未删除为NULL,例如 NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uq_promotion_domain_host (domain_host),
    KEY idx_promotion_domain_template (tenant_id, landing_template_id, deleted_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='推广域名与落地页模板绑定';

-- 推广渠道主表，渠道管理页面最核心的业务表，保存一个渠道的基础定义，渠道不直接报错模板ID，是因为模板绑定关系由域名决定
CREATE TABLE promotion_channel (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '推广渠道主键,例如 5001',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID,例如 1',
    channel_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '稳定公开短码,例如 A8K2M9QX',
    channel_name VARCHAR(128) NOT NULL COMMENT '运营渠道名称,例如 KK-代投印度-抽奖',
    owner_user_id BIGINT NOT NULL COMMENT '渠道归属用户ID,例如 20001',
    promotion_domain_id BIGINT NOT NULL COMMENT '访问域名记录ID(→promotion_domain.id),例如 3001',
    target_country_id BIGINT DEFAULT NULL COMMENT '目标国家ID(→country.id);NULL=混合不限国家,例如 102',
    preselected_country_id BIGINT NOT NULL COMMENT '落地页预选区号国家ID(→country.id),例如 102',
    platform TINYINT NOT NULL COMMENT '推广平台:1=Facebook 2=TikTok 3=快手 4=MGSKY,例如 1',
    is_in_app_open_allowed TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否允许平台内置浏览器打开:0=否 1=是,例如 1',
    is_marketing_allowed TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否允许该渠道参加营销活动:0=否 1=是,例如 1',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '渠道状态:1=启用 0=禁用,例如 1',
    created_by BIGINT NOT NULL COMMENT '创建人用户ID;当前与归属用户ID一致,例如 20001',
    updated_by BIGINT NOT NULL COMMENT '最近修改人用户ID;新增时与归属用户ID一致,例如 20001',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒),例如 1784217600000',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒),例如 1784217660000',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);未删除为NULL,例如 NULL',
    PRIMARY KEY (id),
	UNIQUE KEY uq_promotion_channel_code (tenant_id, channel_code),
	KEY idx_promotion_channel_list (tenant_id, deleted_at, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='推广渠道主数据';

-- Pixel/CAPI追踪配置表，保存渠道向 Facebook、TikTok 等广告平台上报转化事件所需要的敏感配置，不放到渠道主表的原因是安全问题
CREATE TABLE promotion_channel_tracking_config (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '渠道追踪配置主键,例如 6001',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID,例如 1',
    channel_id BIGINT NOT NULL COMMENT '推广渠道ID(→promotion_channel.id),例如 5001',
    provider_type TINYINT NOT NULL COMMENT '追踪平台:1=Facebook 2=TikTok 3=快手 4=MGSKY,例如 1',
    tracking_id VARCHAR(128) DEFAULT NULL COMMENT 'Pixel或平台追踪标识,例如 123456789012345',
    access_token_ciphertext VARBINARY(4096) DEFAULT NULL COMMENT 'Access Token应用层加密密文字节,例如 AES-GCM密文',
    encryption_key_id VARCHAR(64) DEFAULT NULL COMMENT 'Token加密密钥版本标识,例如 kms-key-v3',
    token_fingerprint BINARY(32) DEFAULT NULL COMMENT 'Token不可逆SHA-256指纹,例如 32字节摘要',
    token_expires_at BIGINT DEFAULT NULL COMMENT 'Token到期时间(epoch毫秒),例如 1786813200000',
    lead_event_name VARCHAR(64) DEFAULT NULL COMMENT '意向用户上报事件名,例如 Lead',
    login_request_event_name VARCHAR(64) DEFAULT NULL COMMENT '请求登录上报事件名,例如 InitiateCheckout',
    login_success_event_name VARCHAR(64) DEFAULT NULL COMMENT '登录成功上报事件名,例如 CompleteRegistration',
    last_probe_status TINYINT DEFAULT NULL COMMENT '最近探测状态:NULL=未探测 0=探测中 1=成功 2=失败,例如 1',
    last_probe_event_name VARCHAR(64) DEFAULT NULL COMMENT '最近探测事件名,例如 PageView',
    last_probe_event_id VARCHAR(128) DEFAULT NULL COMMENT '平台返回的探测事件ID,例如 evt_20260717_001',
    last_probe_error_code VARCHAR(64) DEFAULT NULL COMMENT '最近探测脱敏错误码,例如 TOKEN_EXPIRED',
    last_probe_error_message VARCHAR(255) DEFAULT NULL COMMENT '最近探测脱敏错误摘要,例如 访问令牌已过期',
    last_probed_at BIGINT DEFAULT NULL COMMENT '最近探测时间(epoch毫秒),例如 1784217660000',
    created_by BIGINT DEFAULT NULL COMMENT '创建人用户ID,例如 20001',
    updated_by BIGINT DEFAULT NULL COMMENT '最近修改人用户ID,例如 20002',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒),例如 1784217600000',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒),例如 1784217660000',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);未删除为NULL,例如 NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uq_promotion_channel_tracking (tenant_id, channel_id),
    KEY idx_promotion_channel_tracking_probe (tenant_id, last_probe_status, last_probed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='渠道Pixel和CAPI敏感配置';

-- account 兼容:历史行允许 promotion_channel_id 为空,channel_name 继续保留获客时名称快照。
SET @promotion_channel_id_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account'
      AND column_name = 'promotion_channel_id'
);
SET @promotion_channel_id_sql := IF(
    @promotion_channel_id_exists = 0,
    'ALTER TABLE account ADD COLUMN promotion_channel_id BIGINT DEFAULT NULL COMMENT ''稳定推广渠道ID(→promotion_channel.id),历史账号可为空,例如 5001'' AFTER channel_name',
    'SELECT 1'
);
PREPARE promotion_channel_id_stmt FROM @promotion_channel_id_sql;
EXECUTE promotion_channel_id_stmt;
DEALLOCATE PREPARE promotion_channel_id_stmt;

ALTER TABLE account
    MODIFY COLUMN channel_name VARCHAR(128) DEFAULT NULL
        COMMENT '推广渠道名称快照,历史筛选兼容字段,例如 KK-代投印度-抽奖';

SET @promotion_channel_index_exists := (
    SELECT COUNT(DISTINCT index_name)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'account'
      AND index_name = 'idx_account_promotion_channel'
);
SET @promotion_channel_index_sql := IF(
    @promotion_channel_index_exists = 0,
    'ALTER TABLE account ADD INDEX idx_account_promotion_channel (tenant_id, promotion_channel_id, deleted_at, created_at)',
    'SELECT 1'
);
PREPARE promotion_channel_index_stmt FROM @promotion_channel_index_sql;
EXECUTE promotion_channel_index_stmt;
DEALLOCATE PREPARE promotion_channel_index_stmt;
