package com.armada.account.model.enums;

/** 后台手动接码订单状态；只描述接码订单本身，不代表号码已注册成功。 */
public enum SmsActivationState {
    /** 已取得号码，等待短信到达。 */ WAITING_CODE(1),
    /** 已收到验证码，等待运营确认完成。 */ RECEIVED(2),
    /** 运营确认完成，供应商已接受完成指令。 */ COMPLETED(3),
    /** 运营取消或供应商侧已取消。 */ CANCELLED(4),
    /** 超过号码有效期仍未收到验证码。 */ EXPIRED(5);

    /** 数据库存储值。 */
    private final int code;
    SmsActivationState(int code) { this.code = code; }
    /** @return 数据库存储值 */
    public int code() { return code; }
    /** @param code 数据库值 @return 对应订单状态 */
    public static SmsActivationState fromCode(int code) {
        for (var state : values()) { if (state.code == code) { return state; } }
        throw new IllegalArgumentException("Invalid sms activation state");
    }
}
