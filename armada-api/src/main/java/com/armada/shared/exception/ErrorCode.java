package com.armada.shared.exception;

/**
 * 业务错误码。配合 {@link BusinessException} 使用,禁止魔法值。
 */
public enum ErrorCode {

    /** 参数/业务校验失败。 */
    VALIDATION(40001, "参数校验失败"),

    /** 资源不存在。 */
    NOT_FOUND(40401, "资源不存在"),

    /** 资源冲突(如名称重复)。 */
    CONFLICT(40901, "资源冲突"),

    /** 超链任务状态、版本或动作前置条件冲突。 */
    HYPERLINK_TASK_STATE_CONFLICT(40910, "超链任务状态已变化"),

    /** 超链任务报价过期或冻结人数已经变化。 */
    HYPERLINK_QUOTE_STALE(40911, "超链任务报价已失效"),

    /** 超链任务可用余额不足。 */
    HYPERLINK_BALANCE_INSUFFICIENT(40912, "超链任务可用余额不足"),

    /** 超链任务没有可用发信账号。 */
    HYPERLINK_ACCOUNT_UNAVAILABLE(42210, "没有符合条件的超链发信账号"),

    /** 超链任务请求并发超过协议容量。 */
    HYPERLINK_PROTOCOL_CAPACITY_INSUFFICIENT(42211, "协议容量不足"),

    /** 超链计费提供方不可用或结果需要恢复。 */
    HYPERLINK_BILLING_UNAVAILABLE(50310, "超链计费服务暂不可用"),

    /** 跨任务账号发送并发保护不可用。 */
    HYPERLINK_DISPATCH_GUARD_UNAVAILABLE(50311, "超链发送保护暂不可用"),

    /** 超链任务审计落点未配置或不可写。 */
    HYPERLINK_AUDIT_UNAVAILABLE(50312, "超链任务审计服务暂不可用"),

    /** 当前执行线程缺少服务端认证建立的租户上下文。 */
    TENANT_MISSING(40101, "缺少租户上下文，请重新登录"),

    /** 租户码无效或租户已停用。 */
    TENANT_NOT_FOUND(40102, "租户不存在或已停用"),

    /** 账号不存在或密码错误，统一提示避免枚举用户。 */
    LOGIN_FAILED(40103, "账号或密码错误"),

    /** Bearer Token 缺失、过期、退出或被新登录顶替。 */
    AUTH_INVALID(40104, "登录已失效，请重新登录"),

    /** 密码校验通过，但登录账号已被管理员禁用。 */
    ACCOUNT_DISABLED(40105, "账号已禁用，请联系管理员"),

    /** 图片验证码错误、已过期或已使用。 */
    CAPTCHA_INVALID(40002, "验证码错误或已过期"),

    /** 当前身份没有接口所需角色或操作权限。 */
    ACCESS_DENIED(40302, "无权执行此操作"),

    /** Redis 等认证基础设施不可用，必须失败关闭。 */
    AUTH_SERVICE_UNAVAILABLE(50301, "认证服务暂不可用，请稍后重试"),

    /** 没有在线且仍在群内的执行账号。 */
    GROUP_EXECUTOR_UNAVAILABLE(42201, "没有在线且仍在该群内的账号"),

    /** 执行账号不是目标群管理员。 */
    GROUP_PERMISSION_DENIED(40301, "执行账号没有管理员权限"),

    /** 当前 WhatsApp 或协议版本没有暴露目标群设置。 */
    GROUP_CAPABILITY_UNSUPPORTED(42202, "当前 WhatsApp/协议版本不支持该设置"),

    /** 目标成员已不在群内。 */
    GROUP_MEMBER_NOT_FOUND(40402, "目标成员已不在群内"),

    /** 群主不能被降级或踢出。 */
    GROUP_OWNER_PROTECTED(40902, "群主不能被降级或踢出"),

    /** 一次批量群成员操作只成功了一部分。 */
    GROUP_OPERATION_PARTIAL(20701, "部分成员操作成功，部分失败"),

    /** 协议调用超时且无法当场确认操作结果。 */
    GROUP_PROTOCOL_TIMEOUT(50401, "协议调用超时，操作结果待确认"),

    /** 账号 WS 号码导出执行失败，调用方可提示重试。 */
    ACCOUNT_WS_PHONE_EXPORT_FAILED(50001, "导出失败，请重新操作。");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
