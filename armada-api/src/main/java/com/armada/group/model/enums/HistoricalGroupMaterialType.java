package com.armada.group.model.enums;

/** 历史群拉人料子类型。 */
public enum HistoricalGroupMaterialType {
    /** 仅拉入群，不发送营销消息。 */
    NORMAL(1),
    /** 拉入群后需要发送营销消息。 */
    MARKETING(2);

    private final int code;

    HistoricalGroupMaterialType(int code) {
        this.code = code;
    }

    /** @return TINYINT 落库码 */
    public int code() {
        return code;
    }

    /**
     * @param code TINYINT 落库码
     * @return 对应类型
     * @throws IllegalArgumentException 类型码非法时抛出
     */
    public static HistoricalGroupMaterialType fromCode(int code) {
        for (HistoricalGroupMaterialType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("非法历史群料子类型: " + code);
    }
}
