package com.armada.task.model.enums;

/** 群资料编辑权限设置。 */
public enum PullTaskEditPermissionMode {
    /** 不修改现状。 */
    UNCHANGED(0),
    /** 允许全部成员编辑。 */
    ALLOW(1),
    /** 只允许管理员编辑。 */
    DISALLOW(2);

    private final int code;

    PullTaskEditPermissionMode(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }

    /** @return 数据库存储值对应的枚举 */
    public static PullTaskEditPermissionMode fromCode(int code) {
        for (PullTaskEditPermissionMode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知群资料编辑权限: " + code);
    }
}
