-- 站台/角色进群结果原因独立于账号可用性原因，供 EX-05/EX-07 回写与详情读取。
ALTER TABLE pull_task_group_account
    ADD COLUMN membership_reason_code VARCHAR(64) DEFAULT NULL
        COMMENT '进群失败或不确定原因码' AFTER membership_status,
    ADD COLUMN membership_reason_message VARCHAR(255) DEFAULT NULL
        COMMENT '进群失败或不确定原因描述(已脱敏)' AFTER membership_reason_code,
    ADD COLUMN membership_result_at BIGINT DEFAULT NULL
        COMMENT '进群结果回写时间(epoch毫秒)' AFTER membership_reason_message;
