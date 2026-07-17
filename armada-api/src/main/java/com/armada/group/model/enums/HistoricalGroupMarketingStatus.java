package com.armada.group.model.enums;

/** 历史群拉人后的营销发送状态。 */
public enum HistoricalGroupMarketingStatus {
    /** 本次没有营销料子，不执行发送。 */
    NOT_APPLICABLE(0),
    /** 存在营销料子但尚未发送。 */
    NOT_STARTED(1),
    /** 营销命令已发出，等待结果。 */
    SENDING(2),
    /** 所有营销成员均发送成功。 */
    SUCCESS(3),
    /** 同时存在发送成功和失败成员。 */
    PARTIAL_SUCCESS(4),
    /** 没有成员发送成功或发送阶段失败。 */
    FAILED(5);

    private final int code;

    HistoricalGroupMarketingStatus(int code) {
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
    public static HistoricalGroupMarketingStatus fromCode(int code) {
        for (HistoricalGroupMarketingStatus value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("非法历史群营销状态: " + code);
    }
}
