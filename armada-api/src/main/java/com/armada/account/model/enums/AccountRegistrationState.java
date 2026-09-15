package com.armada.account.model.enums;

/** 一次固定采购尝试的状态；验证码到达、注册完成和实际在线分别记录。 */
public enum AccountRegistrationState {
    /** 尚未采购。 */ PENDING(1),
    /** 采购请求已提交意图，崩溃恢复时不得重复采购。 */ PURCHASING(2),
    /** 已取得号码，等待注册服务发码及短信到达。 */ WAITING_CODE(3),
    /** 已记录提交验证码意图，查询远端确认注册结果。 */ REGISTERING(4),
    /** 注册完成，准备导入六段。 */ IMPORTING(5),
    /** 已导入，等待 Android 协议回报在线。 */ WAITING_ONLINE(6),
    /** Android 协议实际回报在线。 */ SUCCEEDED(7),
    /** 已知失败，不补购。 */ FAILED(8),
    /** 外部结果不明，需要核对，不补购。 */ UNKNOWN(9),
    /** 尚未采购的条目已取消。 */ CANCELLED(10);

    /** 数据库存储值。 */
    private final int code;
    AccountRegistrationState(int code) { this.code = code; }
    /** @return 数据库存储值 */
    public int code() { return code; }
    /** @return 是否不再被自动执行 */
    public boolean isTerminal() { return code >= SUCCEEDED.code; }
    /** @param code 数据库值 @return 对应工作流状态 */
    public static AccountRegistrationState fromCode(int code) {
        for (var state : values()) { if (state.code == code) { return state; } }
        throw new IllegalArgumentException("Invalid registration state");
    }
}
