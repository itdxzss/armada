-- 同步群关系与退群事实的 UNKNOWN 枚举说明；仅更新列注释，不改写业务数据。
ALTER TABLE account_group_membership
    MODIFY COLUMN last_exit_type TINYINT NULL
        COMMENT '最近退出方式:3被踢 4主动退出 5退出原因未知';

ALTER TABLE whatsapp_group_departed_member
    MODIFY COLUMN exit_type VARCHAR(16) NOT NULL
        COMMENT '退出方式:LEFT主动退群/REMOVED被移出/UNKNOWN原因未识别';
