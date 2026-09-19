package com.armada.account.model.enums;
/** 单方向保存结果，UNKNOWN 不允许直接产生新的副作用。 */
public enum AccountMutualContactStatus {
    /** 尚未派发。 */ PENDING(1),
    /** 已入 Outbox，等待协议回执。 */ SUBMITTED(2),
    /** 协议明确保存成功。 */ SUCCESS(3),
    /** 明确失败。 */ FAILED(4),
    /** 已派发但结果待确认。 */ UNKNOWN(5),
    /** 停止时未派发的操作。 */ CANCELED(6);
    private final int code;
    AccountMutualContactStatus(int code) {
        this.code = code;
    }
    public int code() {
        return code;
    }
}
