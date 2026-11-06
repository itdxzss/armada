package com.armada.account.model.enums;

/**
 * 单个账号“新聊天额度”批量查询的处理结果，区分“拿到了额度”与各类没拿到的原因。
 */
public enum AccountMessageCapQueryStatus {

    /** 平台返回了可解析的额度响应，具体完整度见 cap.resultStatus。 */
    SUCCESS,

    /** 平台或协议层明确拒绝了本次查询，诊断在 cap.failure 中，不代表账号异常。 */
    REJECTED,

    /** 账号当前没有可用的在线会话，需先上线后再查。 */
    OFFLINE,

    /** 账号所属协议后端暂不支持额度查询（目前仅 Android）。 */
    UNSUPPORTED_BACKEND,

    /** 账号不存在、已删除、不属于当前租户，或缺少手机号/协议账号标识。 */
    ACCOUNT_UNAVAILABLE,

    /** 网络、超时或协议层异常，本次没有获得额度，errorCode 给出协议异常类型。 */
    FAILED
}
