package com.armada.group.model.enums;

/** 历史群拉人执行状态。 */
public enum HistoricalGroupPullStatus {
    /** 等待 worker 领取。 */
    PENDING(0),
    /** 已领取并正在联系人预存或拉人。 */
    RUNNING(1),
    /** 所有有效成员均拉人成功。 */
    SUCCESS(2),
    /** 同时存在拉人成功和失败成员。 */
    PARTIAL_SUCCESS(3),
    /** 没有成员拉人成功或执行阶段失败。 */
    FAILED(4);

    private final int code;

    HistoricalGroupPullStatus(int code) {
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
    public static HistoricalGroupPullStatus fromCode(int code) {
        for (HistoricalGroupPullStatus value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("非法历史群拉人状态: " + code);
    }
}
