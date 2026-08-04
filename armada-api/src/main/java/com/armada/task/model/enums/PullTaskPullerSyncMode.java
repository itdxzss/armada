package com.armada.task.model.enums;

/** 拉手同步模式。 */
public enum PullTaskPullerSyncMode {
    /** 单个拉手依次同步。 */
    SINGLE(1),
    /** 多个拉手批量同步。 */
    BATCH(2);

    private final int code;

    PullTaskPullerSyncMode(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }

    /** @return 数据库存储值对应的枚举 */
    public static PullTaskPullerSyncMode fromCode(int code) {
        for (PullTaskPullerSyncMode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知拉手同步模式: " + code);
    }
}
