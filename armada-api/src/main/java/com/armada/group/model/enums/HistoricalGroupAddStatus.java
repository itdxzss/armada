package com.armada.group.model.enums;

/** 历史群逐成员拉人状态。 */
public enum HistoricalGroupAddStatus {
    /** 尚未调用协议层拉人。 */
    PENDING(0),
    /** 协议层确认拉人成功。 */
    SUCCESS(1),
    /** 协议层确认拉人失败。 */
    FAILED(2);

    private final int code;

    HistoricalGroupAddStatus(int code) {
        this.code = code;
    }

    /** @return TINYINT 落库码 */
    public int code() {
        return code;
    }

    /**
     * @param code TINYINT 落库码
     * @return 对应状态
     * @throws IllegalArgumentException 状态码非法时抛出
     */
    public static HistoricalGroupAddStatus fromCode(int code) {
        for (HistoricalGroupAddStatus value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("非法历史群拉人明细状态: " + code);
    }
}
