package com.armada.platform.protocol.exception;

/**
 * 协议层防腐层错误码。
 *
 * <p>本枚举表达 armada 调协议层时需要编排层区分的失败类型,不直接作为前端业务错误码使用。
 * 账号编排可据此决定重试、退避、刷新 owner、重分配代理或落账号状态。</p>
 */
public enum ProtocolErrorCode {

    /** 协议层请求超时,通常可按策略重试或延后。 */
    TIMEOUT,

    /** 网络不可达、连接失败或连接被提前关闭。 */
    NETWORK,

    /** 协议层返回未细分的 HTTP 错误。 */
    HTTP_ERROR,

    /** 协议层要求账号上线前必须提供或绑定代理。 */
    PROXY_REQUIRED,

    /** 请求打到非账号 owner worker,需要刷新 ownerEndpoint 后重试。 */
    NOT_OWNER,

    /** 上线命中协议层 OnlineGate 限流。 */
    ONLINE_LIMITED,

    /** 重连命中协议层 reconnect 限流。 */
    RECONNECT_LIMITED,

    /** 单账号正在处理其它互斥操作。 */
    ACCOUNT_BUSY,

    /** 协议层 worker 当前繁忙。 */
    WORKER_BUSY,

    /** 账号凭据失效或需重新授权。 */
    NEED_REAUTH,

    /** WhatsApp 限制账号主动触达或加群,当前进群动作应明确失败。 */
    ACCOUNT_REACHOUT_RESTRICTED,

    /** 群邀请码无效,属于永久失败。 */
    INVITE_INVALID,

    /** 群邀请链接已撤销或过期,属于永久失败。 */
    INVITE_REVOKED,

    /** 群已封禁、满员、不存在或不可访问,属于永久失败。 */
    GROUP_UNAVAILABLE,

    /** WhatsApp 进群接口限流,可按策略重试。 */
    RATE_LIMITED,

    /** 网络、超时或 WhatsApp 服务临时不可用,可按策略重试。 */
    TEMPORARY_FAILURE,

    /** 尚未识别的进群错误,默认保留重试机会。 */
    GROUP_JOIN_UNKNOWN,

    /** 协议后端找不到目标账号。 */
    ACCOUNT_NOT_FOUND,

    /** 目标账号当前不在线。 */
    ACCOUNT_NOT_ONLINE,

    /** 协议请求参数不合法。 */
    BAD_REQUEST,

    /** 群邀请链接或邀请码无效。 */
    INVALID_GROUP_LINK,

    /** 协议端明确拒绝本次进群。 */
    GROUP_JOIN_REJECTED,

    /** 协议请求完成，但无法确认账号是否真实入群。 */
    JOIN_RESULT_UNCONFIRMED,

    /** Android 原生接口返回了无法识别的响应。 */
    ANDROID_RESPONSE_UNRECOGNIZED,

    /** Armada 没有注册账号所需的协议后端。 */
    UNSUPPORTED_BACKEND,

    /** 未识别或尚未映射的协议层失败。 */
    UNKNOWN
}
