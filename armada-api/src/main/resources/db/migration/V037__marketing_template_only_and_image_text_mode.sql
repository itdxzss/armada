-- 需求回收:群组营销任务恢复为只能选择营销模板,不再支持任务内纯文本发送内容。
-- V036 已在测试环境执行过,不能删除;这里通过新迁移把最终结构改回模板必选。
-- 同时营销模板消息类型新增 3=图文内容。

ALTER TABLE marketing_task
    DROP COLUMN text_content,
    DROP COLUMN send_content_type,
    MODIFY COLUMN marketing_template_id BIGINT NOT NULL COMMENT '营销模板ID(→marketing_template.id)',
    MODIFY COLUMN marketing_template_name VARCHAR(128) NOT NULL COMMENT '营销模板名称快照';

ALTER TABLE marketing_template
    MODIFY COLUMN link_mode TINYINT NOT NULL DEFAULT 1 COMMENT '消息类型:1=普通超链 2=按钮超链 3=图文内容';
