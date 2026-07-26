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

    /** 上线命令已受理或协议层正在 VERIFYING，不重复释放和分配代理。 */
    ALREADY_PENDING,

    /** 账号已在线，相同上线请求作为幂等跳过。 */
    ALREADY_ONLINE,

    /** 账号缺少协议登录所需的有效凭据。 */
    MISSING_CREDENTIAL
}
