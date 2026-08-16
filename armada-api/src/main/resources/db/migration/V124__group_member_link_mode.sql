-- 独立保存普通成员访问/分享群邀请链接的实时权限。
-- true 对应 WhatsApp all_member_link，false 对应 admin_link。

ALTER TABLE group_link_preview
    ADD COLUMN member_link_mode TINYINT DEFAULT NULL
        COMMENT '链接邀请权限:NULL未知 0仅管理员 1所有成员'
        AFTER member_add_mode;

ALTER TABLE wa_group_profile
    ADD COLUMN member_link_mode TINYINT DEFAULT NULL
        COMMENT '链接邀请权限:NULL未知 0仅管理员 1所有成员'
        AFTER member_add_mode,
    ADD CONSTRAINT ck_wa_group_profile_member_link
        CHECK (member_link_mode IS NULL OR member_link_mode IN (0, 1));
