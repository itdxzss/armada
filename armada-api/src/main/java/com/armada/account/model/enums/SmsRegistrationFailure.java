package com.armada.account.model.enums;

/** 办理进入“需处理”的固定原因分类，落库为枚举名；不保存号码、验证码或 Go 响应原文。 */
public enum SmsRegistrationFailure {
    /** 订单已不是等码状态（被取消或已被其它方式处理），不再向 Go 发请求。 */
    ORDER_NOT_WAITING,
    /** 订单超过有效期，号商不会再出码。 */
    ORDER_EXPIRED,
    /** 号商侧订单已取消。 */
    SMS_CANCELLED,
    /** Go 明确拒绝本次请求（422、400、404 等），重复提交不会改变结果。 */
    GO_REJECTED,
    /** Go 报告冲突（409）：同号码已有事务占用、幂等参数变化或状态冲突。 */
    GO_CONFLICT,
    /** Go 缺少网络能力、配置或未启用（503）。 */
    GO_UNAVAILABLE,
    /** Go 报告外部操作结果不明；为避免重复发码，自动流程不再继续。 */
    GO_OUTCOME_UNKNOWN,
    /** Go 要求人工处理（二步验证、图形挑战等），自动流程不处理。 */
    GO_ACTION_REQUIRED,
    /** 订单到期时仍没有 Go 的明确结果。 */
    NO_RESULT_BEFORE_EXPIRY,
    /** 占用的代理读取失败或连接串非法，无法保证同一代理重放，自动流程停止。 */
    PROXY_UNAVAILABLE
}
