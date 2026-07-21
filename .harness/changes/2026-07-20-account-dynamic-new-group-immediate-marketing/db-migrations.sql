ALTER TABLE marketing_task_send_attempt
    MODIFY COLUMN round_no BIGINT NOT NULL DEFAULT 0
    COMMENT '营销轮次:0=新群首次即时发送 1+=正常任务轮次';
