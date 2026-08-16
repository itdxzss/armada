-- 群资料人数和健康检测人数是两个独立事实；列表保持“检测人数优先”的现有口径。
ALTER TABLE wa_group_profile
    ADD COLUMN checked_member_count INT DEFAULT NULL
        COMMENT '最近群健康检测观察到的成员数' AFTER member_count,
    ADD CONSTRAINT ck_wa_group_profile_checked_member_count
        CHECK (checked_member_count IS NULL OR checked_member_count >= 0);
