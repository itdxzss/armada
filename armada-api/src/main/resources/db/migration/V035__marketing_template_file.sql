CREATE TABLE IF NOT EXISTS marketing_template_file (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id         BIGINT       NOT NULL                COMMENT '租户ID',
    original_filename VARCHAR(255) NOT NULL                COMMENT '原始文件名',
    content_type      VARCHAR(128) NOT NULL                COMMENT 'MIME类型',
    size_bytes        BIGINT       NOT NULL                COMMENT '文件大小(字节)',
    content           MEDIUMBLOB   NOT NULL                COMMENT '图片内容',
    created_at        BIGINT       NOT NULL                COMMENT '创建时间(epoch毫秒)',
    deleted_at        BIGINT               DEFAULT NULL    COMMENT '软删时间(epoch毫秒);NULL=未删',
    PRIMARY KEY (id),
    KEY idx_marketing_template_file_tenant (tenant_id, deleted_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='营销模板图片文件';
