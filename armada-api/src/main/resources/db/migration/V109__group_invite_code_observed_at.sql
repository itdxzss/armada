ALTER TABLE group_link_preview
    ADD COLUMN invite_code_observed_at BIGINT DEFAULT NULL
        COMMENT '当前邀请码观察时间(epoch毫秒);防止乱序事件覆盖' AFTER invite_code;
