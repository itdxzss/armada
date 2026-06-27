-- 对齐 group_link 注释到群组列表新语义。
-- 只修改 COMMENT,不改数据与字段类型。

ALTER TABLE group_link COMMENT = '群组入口主表(跨业务共享群组池)';

ALTER TABLE group_link
    MODIFY COLUMN group_name VARCHAR(128) DEFAULT NULL COMMENT '运营侧自定义群名称;导入链接不写;群组列表可编辑';
