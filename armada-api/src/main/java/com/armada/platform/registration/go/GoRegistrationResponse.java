package com.armada.platform.registration.go;

/**
 * 一次 Go 调用的分类结果。调用方只按 {@link Kind} 决定下一步，不解析 HTTP 状态码。
 * @param kind 结果分类
 * @param snapshot 响应里的事务视图，没有时为空视图
 */
public record GoRegistrationResponse(Kind kind, GoRegistrationSnapshot snapshot) {

    /** Go 调用结果分类；除 DONE 外都不代表操作成功。 */
    public enum Kind {
        /** HTTP 200 且业务码为 0：确定完成，状态以 snapshot 为准。 */ DONE,
        /** 202 处理中：Go 仍在执行原操作，下一轮用同一把键重放即可。 */ IN_PROGRESS,
        /** 429：Go 本地限流（并发额度、冷却或次数），首次发码时尚未向外发出任何请求。 */ RATE_LIMITED,
        /** 202 结果不明：Go 不确定外部请求是否生效，不得自动重发。 */ OUTCOME_UNKNOWN,
        /** 409：同号码已有事务、幂等参数变化或状态冲突。 */ CONFLICT,
        /** 503：Go 缺网络能力、配置或未启用。 */ UNAVAILABLE,
        /** 明确拒绝（参数、鉴权、422 等），重复提交不会改变结果。 */ REJECTED,
        /** 没有拿到可用响应（超时、连接失败、网关错误、Go 内部错误），用同一把键重放是安全的。 */ NO_RESPONSE
    }

    /** @param kind 结果分类 @return 不带事务视图的结果 */
    public static GoRegistrationResponse of(Kind kind) {
        return new GoRegistrationResponse(kind, GoRegistrationSnapshot.empty());
    }
}
