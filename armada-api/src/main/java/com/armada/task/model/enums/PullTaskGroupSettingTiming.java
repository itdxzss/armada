package com.armada.task.model.enums;

/** 群资料设置顺序。 */
public enum PullTaskGroupSettingTiming {
    /** 拉人之前设置群资料。 */
    BEFORE_PULL(1),
    /** 拉人完成之后设置群资料。 */
    AFTER_PULL(2);

    private final int code;

    PullTaskGroupSettingTiming(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }

    /** @return 数据库存储值对应的枚举 */
    public static PullTaskGroupSettingTiming fromCode(int code) {
        for (PullTaskGroupSettingTiming value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知群资料设置顺序: " + code);
    }
}
