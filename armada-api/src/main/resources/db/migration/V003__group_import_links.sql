-- group_link_label(WS链接分组)
CREATE TABLE IF NOT EXISTS group_link_label (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID(拦截器注入)',
    name VARCHAR(100) NOT NULL COMMENT 'WS链接分组名称(租户内不可重复)',
    region VARCHAR(64) DEFAULT NULL COMMENT '使用国家/区域展示名(可「混合」)',
    remark VARCHAR(255) DEFAULT NULL COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间(UTC)',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间(UTC)',
    created_by BIGINT DEFAULT NULL COMMENT '创建人user_id',
    deleted_at DATETIME DEFAULT NULL COMMENT '软删时间;NULL=未删',
    PRIMARY KEY (id),
    UNIQUE KEY uq_name (tenant_id, name),
    KEY idx_tenant_created (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WS链接分组';

-- group_link(群链接·import身份段)
CREATE TABLE IF NOT EXISTS group_link (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID(拦截器注入)',
    link_url VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '归一化后群邀请链接(租户内唯一,按字节精确去重)',
    group_name VARCHAR(128) DEFAULT NULL COMMENT '业务群名(导入时填,可空)',
    label_id BIGINT DEFAULT NULL COMMENT '所属WS链接分组(关联group_link_label.id;只导入链接菜单写;拉群/进群粘贴=NULL)',
    import_batch_id BIGINT DEFAULT NULL COMMENT '来源导入批次(关联group_link_import_batch.id)',
    remark VARCHAR(255) DEFAULT NULL COMMENT '备注(纯导入备注)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间(UTC)',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间(UTC)',
    created_by BIGINT DEFAULT NULL COMMENT '创建人user_id',
    deleted_at DATETIME DEFAULT NULL COMMENT '软删时间;NULL=未删',
    PRIMARY KEY (id),
    UNIQUE KEY uq_url (tenant_id, link_url),
    KEY idx_tenant_label (tenant_id, label_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='群链接(跨业务共享群组表-import身份段)';

-- group_link_import_batch(导入批次)
CREATE TABLE IF NOT EXISTS group_link_import_batch (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID(拦截器注入)',
    label_id BIGINT NOT NULL COMMENT '导入目标WS链接分组(关联;导入时固定)',
    batch_name VARCHAR(255) NOT NULL COMMENT '来源文件/批次名称(用户填)',
    source_file_name VARCHAR(255) DEFAULT NULL COMMENT '上传文件原名;纯text导入=NULL',
    total_rows INT NOT NULL DEFAULT 0 COMMENT '解析总行数',
    inserted_rows INT NOT NULL DEFAULT 0 COMMENT '新增行数',
    adopted_rows INT NOT NULL DEFAULT 0 COMMENT '收编行数',
    skipped_rows INT NOT NULL DEFAULT 0 COMMENT '批内重复跳过行数',
    failed_rows INT NOT NULL DEFAULT 0 COMMENT '格式不合格行数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '导入时间(UTC)',
    created_by BIGINT DEFAULT NULL COMMENT '创建人user_id',
    deleted_at DATETIME DEFAULT NULL COMMENT '软删时间;随分组级联软删',
    PRIMARY KEY (id),
    KEY idx_tenant_label (tenant_id, label_id),
    KEY idx_tenant_created (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='群链接导入批次';

-- group_link_import_detail(导入明细)
CREATE TABLE IF NOT EXISTS group_link_import_detail (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID(拦截器注入)',
    batch_id BIGINT NOT NULL COMMENT '所属批次(关联;分组维度经JOIN batch.label_id取)',
    line_no INT NOT NULL COMMENT '拼接后行号',
    raw_url VARCHAR(255) DEFAULT NULL COMMENT '原文链接(失败行也保留)',
    group_name VARCHAR(128) DEFAULT NULL COMMENT '群名称(可空)',
    result TINYINT NOT NULL COMMENT '导入结果:1=成功新增 2=收编 3=批内重复 4=格式错误',
    fail_reason VARCHAR(255) DEFAULT NULL COMMENT '失败原因(result>=3时)',
    group_link_id BIGINT DEFAULT NULL COMMENT '成功/收编时关联group_link.id',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间(UTC)',
    PRIMARY KEY (id),
    KEY idx_tenant_batch (tenant_id, batch_id),
    KEY idx_tenant_batch_result (tenant_id, batch_id, result)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='群链接导入明细';
