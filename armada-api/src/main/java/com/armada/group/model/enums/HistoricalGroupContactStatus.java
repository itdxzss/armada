package com.armada.group.model.enums;

/** 历史群拉人联系人预存状态。 */
public enum HistoricalGroupContactStatus {
    /** 尚未执行联系人预存。 */
    PENDING(0),
    /** 联系人预存成功。 */
    SUCCESS(1),
    /** 联系人预存失败。 */
    FAILED(2);

    private final int code;

    HistoricalGroupContactStatus(int code) {
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
    public static HistoricalGroupContactStatus fromCode(int code) {
        for (HistoricalGroupContactStatus value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("非法历史群联系人状态: " + code);
    }
}
