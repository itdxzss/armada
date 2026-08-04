package com.armada.task.model.enums;

/** 群邀请链接权限。 */
public enum PullTaskLinkPermissionMode {
    /** 所有成员均可邀请。 */
    ALL(1),
    /** 仅管理员可邀请。 */
    ADMIN_ONLY(2);

    private final int code;

    PullTaskLinkPermissionMode(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }

    /** @return 数据库存储值对应的枚举 */
    public static PullTaskLinkPermissionMode fromCode(int code) {
        for (PullTaskLinkPermissionMode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知群链接权限: " + code);
    }
}
