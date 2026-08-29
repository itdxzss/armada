CREATE TABLE IF NOT EXISTS hyperlink_template (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '超链模板主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    template_name VARCHAR(128) NOT NULL COMMENT '模板名称',
    message_type TINYINT NOT NULL COMMENT '消息类型:1=单图文 2=双图文 3=普通按钮 4=卡片按钮',
    message_schema_version INT NOT NULL DEFAULT 1 COMMENT '消息内容契约版本',
    title VARCHAR(512) NOT NULL COMMENT '消息标题或按钮气泡上方标题',
    content TEXT DEFAULT NULL COMMENT '单图文正文或按钮气泡正文',
    link_description VARCHAR(512) DEFAULT NULL COMMENT '单图文链接描述',
    promotion_link VARCHAR(2048) DEFAULT NULL COMMENT '单图文原始推广链接',
    buttons JSON DEFAULT NULL COMMENT '版本化按钮数组;一期按钮消息恰好一个CTA_URL',
    card_text VARCHAR(500) DEFAULT NULL COMMENT '卡片底部小字',
    link_preview_asset_id BIGINT DEFAULT NULL COMMENT '链接预览图稳定素材ID;一期指向marketing_template_file.id',
    body_main_asset_id BIGINT DEFAULT NULL COMMENT '正文主图或卡片头图稳定素材ID;一期指向marketing_template_file.id',
    remark VARCHAR(255) DEFAULT NULL COMMENT '运营备注',
    version INT NOT NULL DEFAULT 1 COMMENT '乐观锁和内容来源版本',
    created_by BIGINT DEFAULT NULL COMMENT '创建人用户ID',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删除时间(epoch毫秒);NULL=未删除',
    is_active TINYINT GENERATED ALWAYS AS (
        CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END
    ) STORED COMMENT '软删唯一键辅助:有效行=1,已删行=NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uq_hyperlink_template_name (tenant_id, template_name, is_active),
    KEY idx_hyperlink_template_type (tenant_id, message_type, deleted_at, id),
    KEY idx_hyperlink_template_created (tenant_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='超链营销消息模板';
