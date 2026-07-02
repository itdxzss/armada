package com.armada.task.model.enums;

/**
 * 进群任务明细失败原因展示枚举。
 *
 * <p>{@code code} 保持与 {@code join_task_result.reason} 中保存的机器码一致,
 * {@code label} 用于详情列表页面展示。未知机器码统一展示为通用失败文案,
 * 原始 {@code reason} 仍随 VO 返回,方便排查协议层问题。</p>
 */
public enum JoinTaskFailureReason {

    /** 群链接格式不是 WhatsApp 群邀请链接。 */
    INVALID_GROUP_LINK("非群链接", "非群链接"),

    /** 明细绑定的账号不存在或已不可用。 */
    ACCOUNT_NOT_FOUND("ACCOUNT_NOT_FOUND", "账号不存在"),

    /** 账号当前未在协议层在线。 */
    ACCOUNT_NOT_ONLINE("ACCOUNT_NOT_ONLINE", "账号未在线"),

    /** 群设置需要管理员审核,协议层已提交进群申请但未立即入群。 */
    JOIN_PENDING_APPROVAL("JOIN_PENDING_APPROVAL", "进群待审核"),

    /** 协议层或 worker 返回了未细分的内部错误。 */
    INTERNAL_ERROR("INTERNAL_ERROR", "进群失败，请检查群链接或稍后重试"),

    /** 协议层返回请求参数无效,常见于群链接/邀请码格式不正确。 */
    BAD_REQUEST("bad-request", "进群失败，请检查群链接或稍后重试"),

    /** 协议层请求超时。 */
    TIMEOUT("TIMEOUT", "协议层请求超时"),

    /** 调用协议层时网络不可达或连接异常。 */
    NETWORK("NETWORK", "协议层网络异常"),

    /** 协议层返回未细分的 HTTP 错误。 */
    HTTP_ERROR("HTTP_ERROR", "协议层返回错误"),

    /** 账号上线或进群前缺少可用代理。 */
    PROXY_REQUIRED("PROXY_REQUIRED", "账号未绑定代理"),

    /** 请求未命中账号所属 worker。 */
    NOT_OWNER("NOT_OWNER", "账号路由异常，请稍后重试"),

    /** 协议层上线操作限流。 */
    ONLINE_LIMITED("ONLINE_LIMITED", "协议层限流，请稍后重试"),

    /** 协议层重连操作限流。 */
    RECONNECT_LIMITED("RECONNECT_LIMITED", "协议层限流，请稍后重试"),

    /** 单账号正在处理其它互斥操作。 */
    ACCOUNT_BUSY("ACCOUNT_BUSY", "账号正在处理其他任务"),

    /** 协议 worker 当前繁忙。 */
    WORKER_BUSY("WORKER_BUSY", "协议 worker 繁忙"),

    /** 账号凭据失效,需要重新登录。 */
    NEED_REAUTH("NEED_REAUTH", "账号需重新登录"),

    /** 未识别的协议层失败。 */
    UNKNOWN("UNKNOWN", "进群失败");

    private static final String DEFAULT_LABEL = "进群失败";

    private final String code;
    private final String label;

    JoinTaskFailureReason(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /**
     * 根据库中失败原因码返回页面展示文案。
     *
     * @param code 失败原因码或摘要
     * @return 中文展示文案;空原因返回空字符串
     */
    public static String labelOf(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        for (JoinTaskFailureReason value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value.label;
            }
        }
        return DEFAULT_LABEL;
    }
}
