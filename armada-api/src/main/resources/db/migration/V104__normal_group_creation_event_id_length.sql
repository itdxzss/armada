-- 协议结果事件ID由协议账号、事件类型和命令ID组合生成，长度可能超过原64字符限制。
ALTER TABLE normal_group_creation_item
    MODIFY COLUMN last_event_id VARCHAR(255) NULL DEFAULT NULL
    COMMENT '当前执行租约或最近处理的业务事件ID';
