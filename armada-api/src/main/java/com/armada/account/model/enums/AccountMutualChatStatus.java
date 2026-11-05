package com.armada.account.model.enums;
/** 单方向发送结果，UNKNOWN 仅经用户确认后人工重试。 */
public enum AccountMutualChatStatus {
    /** 尚未派发。 */ PENDING(1),
    /** 已入 Outbox，等待协议回执。 */ SUBMITTED(2),
    /** 协议返回消息 ID 并确认发送成功。 */ SUCCESS(3),
    /** 明确失败。 */ FAILED(4),
    /** 已派发但结果待确认。 */ UNKNOWN(5),
    /** 停止时未派发的操作。 */ CANCELED(6);
    private final int code;
    AccountMutualChatStatus(int code) {
        this.code = code;
    }
    public int code() {
        return code;
    }
}
