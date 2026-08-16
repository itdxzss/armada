-- 已解析群的健康检测按 group_jid 执行，事实归属群资料；不能依赖群是否已有邀请码。
ALTER TABLE wa_group_profile
    ADD COLUMN health_status TINYINT DEFAULT NULL
        COMMENT '群检测状态:NULL未检测 1可用 2链接失效 3不可用' AFTER member_count,
    ADD COLUMN banned TINYINT DEFAULT NULL
        COMMENT '群封禁标记:NULL未知 0否 1是' AFTER health_status,
    ADD COLUMN last_checked_at BIGINT DEFAULT NULL
        COMMENT '最近群检测时间(epoch毫秒)' AFTER banned,
    ADD COLUMN last_error_code VARCHAR(64) DEFAULT NULL
        COMMENT '最近群检测稳定错误码' AFTER last_checked_at,
    ADD COLUMN failure_count INT NOT NULL DEFAULT 0
        COMMENT '连续群检测失败次数' AFTER last_error_code,
    ADD KEY idx_wa_group_profile_health
        (tenant_id, health_status, banned, group_id),
    ADD CONSTRAINT ck_wa_group_profile_health
        CHECK (health_status IS NULL OR health_status IN (1, 2, 3)),
    ADD CONSTRAINT ck_wa_group_profile_banned
        CHECK (banned IS NULL OR banned IN (0, 1)),
    ADD CONSTRAINT ck_wa_group_profile_failure_count
        CHECK (failure_count >= 0);
