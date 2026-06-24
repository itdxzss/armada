-- 营销模板(素材管理)。对照 wheel marketing_template 清理:
--   button_content + button_items 两列 → 合并为单列 buttons(json)
--   link_mode 字符串 → tinyint 枚举;message_body/message_text → 语义化 content/body_text
--   新增 text_type(搜索用);删 enabled(需求无启用开关)
CREATE TABLE marketing_template (
    id             bigint        NOT NULL AUTO_INCREMENT          COMMENT '主键',
    tenant_id      bigint        NOT NULL                         COMMENT '租户 ID',
    template_name  varchar(128)  NOT NULL                         COMMENT '模板名',
    link_mode      tinyint       NOT NULL DEFAULT 1               COMMENT '超链模式:1=普通超链 2=按钮超链',
    text_type      varchar(64)            DEFAULT NULL            COMMENT '文本类型(搜索筛选,dict 配置)',
    image_file_id  bigint                 DEFAULT NULL            COMMENT '图片文件 ID(≤500KB)',
    content        text                                          COMMENT '内容(标题/核心卖点)',
    body_text      text                                          COMMENT '文本(正文)',
    buttons        json                   DEFAULT NULL            COMMENT '消息按钮 [{type,text,param}] 最多 3,仅按钮超链',
    promotion_link varchar(512)           DEFAULT NULL            COMMENT '推广链接(二期)',
    remark         varchar(255)           DEFAULT NULL            COMMENT '备注',
    created_at     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP                            COMMENT '创建时间',
    updated_at     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_by     bigint                 DEFAULT NULL            COMMENT '创建人 user_id',
    deleted_at     datetime               DEFAULT NULL            COMMENT '软删除时间;NULL=未删',
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_name (tenant_id, template_name, deleted_at),
    KEY idx_tenant_type (tenant_id, text_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '营销模板';
