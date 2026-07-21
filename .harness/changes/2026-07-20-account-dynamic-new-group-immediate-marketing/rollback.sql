ALTER TABLE marketing_task_send_attempt
    MODIFY COLUMN round_no BIGINT NOT NULL DEFAULT 0
    COMMENT '所属营销轮次;从1开始';
