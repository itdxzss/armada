ALTER TABLE marketing_template
    ADD COLUMN mention_all TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '发送群消息时是否提醒所有成员:0否,1是'
        AFTER promotion_link;
