-- 独立的云端动态受众快照，不覆盖具名通讯录/好友统计。
CREATE TABLE IF NOT EXISTS account_status_audience (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    account_id BIGINT NOT NULL COMMENT '账号ID',
    sync_status TINYINT NOT NULL DEFAULT 0 COMMENT '0待准备 1准备中 2完整 3失败',
    request_token VARCHAR(36) NULL COMMENT '抓取代次CAS令牌',
    lease_until BIGINT NULL COMMENT '抓取租约截止毫秒',
    jids_json MEDIUMTEXT NULL COMMENT '完整LID候选数组，不含加密元数据',
    contact_num INT NOT NULL DEFAULT 0 COMMENT '完整候选人数，非双向好友数',
    snapshot_version VARCHAR(256) NULL COMMENT '云端分页版本',
    synced_at BIGINT NULL COMMENT '完整抓取时间毫秒',
    expires_at BIGINT NULL COMMENT '快照有效期毫秒',
    fail_code VARCHAR(64) NULL COMMENT '安全失败码',
    fail_reason VARCHAR(255) NULL COMMENT '安全失败说明',
    created_at BIGINT NOT NULL COMMENT '创建时间毫秒',
    updated_at BIGINT NOT NULL COMMENT '更新时间毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uq_account_status_audience (tenant_id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号动态云端候选受众快照';
