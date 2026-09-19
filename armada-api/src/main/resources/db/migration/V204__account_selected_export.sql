-- 账号交接独立于营销报表导出；账号原始材料仍以导入明细为唯一来源。
CREATE TABLE account_export_job (
    id VARCHAR(36) NOT NULL COMMENT '请求幂等 UUID',
    tenant_id BIGINT NOT NULL COMMENT '租户 ID',
    created_by BIGINT NOT NULL COMMENT '创建用户 ID',
    status VARCHAR(16) NOT NULL COMMENT 'READY待交付 COMPLETED已移除 CANCELLED已取消',
    account_ids_json TEXT NOT NULL COMMENT '规范化选中账号 ID 快照',
    account_count INT NOT NULL COMMENT '选中账号数量',
    filename VARCHAR(100) NOT NULL COMMENT '下载 ZIP 文件名',
    sha256 CHAR(64) NOT NULL COMMENT 'ZIP SHA-256',
    file_size INT NOT NULL COMMENT 'ZIP 完整字节数',
    archive MEDIUMBLOB NULL COMMENT '敏感凭据 ZIP 到期清理 不得记录日志',
    created_at BIGINT NOT NULL COMMENT '创建时间毫秒',
    expires_at BIGINT NOT NULL COMMENT '下载截止时间毫秒',
    completed_at BIGINT NULL COMMENT '控端移除完成时间毫秒',
    PRIMARY KEY (id),
    KEY idx_account_export_owner (tenant_id, created_by, created_at),
    KEY idx_account_export_expiry (tenant_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号所选原格式导出与交付作业';

CREATE TABLE account_export_item (
    tenant_id BIGINT NOT NULL COMMENT '租户 ID',
    job_id VARCHAR(36) NOT NULL COMMENT '所属导出作业',
    account_id BIGINT NOT NULL COMMENT '原账号 ID 保留历史关联',
    previous_state TINYINT NULL COMMENT '取消未交付导出时恢复的业务状态',
    PRIMARY KEY (job_id, account_id),
    KEY idx_account_export_item_account (tenant_id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导出账号选择快照与取消恢复信息';
