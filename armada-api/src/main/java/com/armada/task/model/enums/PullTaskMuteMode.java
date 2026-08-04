package com.armada.task.model.enums;

/** 群禁言设置。 */
public enum PullTaskMuteMode {
    /** 不修改现状。 */
    UNCHANGED(0),
    /** 开启群禁言。 */
    MUTE(1),
    /** 解除群禁言。 */
    UNMUTE(2);

    private final int code;

    PullTaskMuteMode(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }

    /** @return 数据库存储值对应的枚举 */
    public static PullTaskMuteMode fromCode(int code) {
        for (PullTaskMuteMode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知群禁言设置: " + code);
    }
}
