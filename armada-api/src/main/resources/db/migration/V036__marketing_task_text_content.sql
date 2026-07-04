-- 营销任务发送内容二选一:
-- 1) 模板任务继续使用 marketing_template_id/name;
-- 2) 纯文本任务不引用模板,直接保存 text_content;
-- 3) 历史任务通过 send_content_type 默认值保持模板模式。

ALTER TABLE marketing_task
    MODIFY COLUMN marketing_template_id BIGINT NULL COMMENT '营销模板ID;send_content_type=1时必填',
    MODIFY COLUMN marketing_template_name VARCHAR(128) NULL COMMENT '营销模板名称快照;send_content_type=1时必填',
    ADD COLUMN send_content_type TINYINT NOT NULL DEFAULT 1
        COMMENT '发送内容类型:1=营销模板 2=纯文本'
        AFTER marketing_template_name,
    ADD COLUMN text_content TEXT NULL
        COMMENT '纯文本发送内容;send_content_type=2时使用'
        AFTER send_content_type;
