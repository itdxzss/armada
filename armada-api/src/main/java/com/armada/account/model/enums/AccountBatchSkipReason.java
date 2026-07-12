package com.armada.account.model.enums;

/**
 * 批量登录跳过原因。
 *
 * <p>枚举名直接作为前后端统计 key，禁止随意改名。</p>
 */
public enum AccountBatchSkipReason {

    /** 账号已封禁，不再发起登录。 */
    BANNED,

    /** 账号已解绑，不再发起登录。 */
    UNBOUND,

    /** 账号处于抢登中，普通批量登录不得干扰抢登流程。 */
    TAKING_OVER,

    /** 账号缺少协议登录所需的有效凭据。 */
    MISSING_CREDENTIAL
}
