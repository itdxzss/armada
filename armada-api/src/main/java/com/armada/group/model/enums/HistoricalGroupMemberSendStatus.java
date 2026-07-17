package com.armada.group.model.enums;

/** 历史群营销成员发送状态。 */
public enum HistoricalGroupMemberSendStatus {
    /** 普通料子成员，不发送营销消息。 */
    NOT_APPLICABLE(0),
    /** 等待创建发送命令。 */
    PENDING(1),
    /** 命令已创建，等待结果事件。 */
    SENDING(2),
    /** 结果事件确认发送成功。 */
    SUCCESS(3),
    /** 结果事件确认发送失败。 */
    FAILED(4);

    private final int code;

    HistoricalGroupMemberSendStatus(int code) {
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
    public static HistoricalGroupMemberSendStatus fromCode(int code) {
        for (HistoricalGroupMemberSendStatus value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("非法历史群成员发送状态: " + code);
    }
}
