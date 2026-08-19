ALTER TABLE marketing_task
    ADD COLUMN is_new_group_delay_enabled TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '账号动态目标检测到新群后是否延迟首次发送:0=否 1=是',
    ADD COLUMN new_group_delay_value INT NOT NULL DEFAULT 30
        COMMENT '新群首次发送延迟数值',
    ADD COLUMN new_group_delay_unit TINYINT NOT NULL DEFAULT 1
        COMMENT '新群首次发送延迟单位:1=分钟 2=小时';

ALTER TABLE marketing_task_send_attempt
    ADD COLUMN detected_at BIGINT DEFAULT NULL
        COMMENT 'Armada确认检测到新群时间(epoch毫秒)',
    ADD COLUMN scheduled_send_at BIGINT DEFAULT NULL
        COMMENT '新群首次业务计划发送时间(epoch毫秒)',
    ADD COLUMN outbox_accepted_at BIGINT DEFAULT NULL
        COMMENT 'Outbox确认接受命令时间(epoch毫秒)',
    MODIFY COLUMN status TINYINT NOT NULL
        COMMENT '尝试状态:0=已提交 1=成功 2=失败 3=跳过 4=等待发送',
    ADD KEY idx_marketing_attempt_wait_due (status, scheduled_send_at, id);

UPDATE marketing_task_send_attempt attempt
JOIN protocol_command_outbox outbox
  ON outbox.tenant_id = attempt.tenant_id
 AND outbox.command_id = attempt.command_id
SET attempt.outbox_accepted_at = COALESCE(outbox.created_at, attempt.submitted_at, attempt.created_at)
WHERE attempt.round_no > 0
  AND attempt.outbox_accepted_at IS NULL;
