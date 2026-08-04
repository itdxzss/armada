package com.armada.task.model.enums;

/** 群限时消息设置。 */
public enum PullTaskDisappearingMessageMode {
    /** 不修改现状。 */
    UNCHANGED(0),
    /** 一天后消失。 */
    ONE_DAY(1),
    /** 七天后消失。 */
    SEVEN_DAYS(2),
    /** 九十天后消失。 */
    NINETY_DAYS(3),
    /** 关闭限时消息。 */
    OFF(4);

    private final int code;

    PullTaskDisappearingMessageMode(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }

    /** @return 数据库存储值对应的枚举 */
    public static PullTaskDisappearingMessageMode fromCode(int code) {
        for (PullTaskDisappearingMessageMode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知群限时消息设置: " + code);
    }
}
