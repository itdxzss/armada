ALTER TABLE marketing_task
    ADD COLUMN current_round_no BIGINT NOT NULL DEFAULT 0
        COMMENT '营销轮次序号;每成功抢占一轮递增1' AFTER retry_limit,
    ADD COLUMN next_round_at BIGINT DEFAULT NULL
        COMMENT '下一轮应生成时间(epoch毫秒)' AFTER started_at,
    ADD COLUMN last_round_started_at BIGINT DEFAULT NULL
        COMMENT '最近一轮生成开始时间(epoch毫秒)' AFTER next_round_at,
    ADD KEY idx_marketing_task_round_due (tenant_id, status, next_round_at, id);

ALTER TABLE marketing_task_send_attempt
    ADD COLUMN round_no BIGINT NOT NULL DEFAULT 0
        COMMENT '所属营销轮次;从1开始' AFTER target_id,
    ADD COLUMN command_id VARCHAR(64) DEFAULT NULL
        COMMENT '协议命令ID;用于排查Kafka投递' AFTER is_retry,
    ADD COLUMN message_id VARCHAR(128) DEFAULT NULL
        COMMENT '协议层返回的WhatsApp消息ID' AFTER reason_message,
    ADD COLUMN submitted_at BIGINT DEFAULT NULL
        COMMENT '写入协议outbox时间(epoch毫秒)' AFTER message_id,
    ADD COLUMN result_at BIGINT DEFAULT NULL
        COMMENT '协议层发送结果回写时间(epoch毫秒)' AFTER submitted_at,
    MODIFY COLUMN status TINYINT NOT NULL
        COMMENT '尝试状态:0=已提交 1=成功 2=失败 3=跳过';

ALTER TABLE marketing_task_send_attempt
    DROP INDEX uq_marketing_task_attempt_no,
    ADD UNIQUE KEY uq_marketing_task_attempt_round (tenant_id, target_id, round_no),
    ADD KEY idx_marketing_task_attempt_command (tenant_id, command_id);
