ALTER TABLE hyperlink_task_recipient
    ADD COLUMN dispatch_attempt INT NOT NULL DEFAULT 1
        COMMENT '当前逻辑收件人的派发尝试序号;账号受限换号时递增'
        AFTER command_id;
