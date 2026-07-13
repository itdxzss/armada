ALTER TABLE marketing_task_send_attempt
    ADD COLUMN group_status VARCHAR(32) NULL
        COMMENT '发送时群状态:NORMAL/BANNED/NO_PERMISSION/UNCONFIRMED' AFTER message_id,
    ADD COLUMN group_status_reason VARCHAR(64) NULL
        COMMENT '发送时群状态判定原因' AFTER group_status,
    ADD COLUMN group_status_checked_at BIGINT NULL
        COMMENT '群状态判定时间(epoch毫秒)' AFTER group_status_reason;
