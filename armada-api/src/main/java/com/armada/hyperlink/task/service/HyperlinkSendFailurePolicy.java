package com.armada.hyperlink.task.service;

import java.util.Set;

/** 超链只依据确定的发送事实重试；会话、设备和密钥错误不构成目标无效证据。 */
public final class HyperlinkSendFailurePolicy {
    /** 单条服务器 463 拒绝，不能据此停用发信人对其他目标的发送。 */
    public static final String ACK_REJECTED_463 = "WA_ACK_REJECTED_463";
    /** 旧版本遗留的挂起标记，仅用于释放历史记录；新错误不再生成。 */
    public static final long RECOVERY_HOLD = Long.MAX_VALUE;
    private static final long FIRST_RETRY_DELAY_MS = 30_000L;
    private static final int MAX_BACKOFF_SHIFT = 2;
    private static final Set<String> UNREGISTERED_CODES = Set.of("UNREGISTERED", "RECIPIENT_UNREGISTERED");
    private static final Set<String> PRE_SEND_CODES = Set.of(
            "ACCOUNT_OFFLINE", "SENDER_UNAVAILABLE", "SEND_PREPARE_FAILED", "MEDIA_LOAD_FAILED",
            "RECIPIENT_SESSION_UNAVAILABLE", "RECIPIENT_CAPABILITY_QUERY_FAILED",
            "RECIPIENT_REGISTRATION_UNKNOWN", "RECIPIENT_DEVICE_UNSUPPORTED",
            "SENDER_DEVICE_QUERY_FAILED", "RECIPIENT_LID_UNAVAILABLE",
            "LID_TARGET_CIPHERTEXT_MISSING", "LID_MESSAGE_ENCRYPT_FAILED",
            "LID_DEVICE_QUERY_FAILED", "LID_TARGET_DEVICES_UNAVAILABLE", "LID_KEY_QUERY_FAILED",
            "LID_KEY_RESPONSE_INVALID", "LID_KEY_QUERY_REJECTED", "LID_PRIMARY_SESSION_FAILED",
            "LID_PRIMARY_KEY_MISSING");
    private static final Set<String> RESOURCE_CODES = Set.of(
            "ACCOUNT_BANNED", "ACCOUNT_OFFLINE", "DEVICE_DELETED", "DEVICE_REMOVED",
            "LOGGED_OUT", "PRIMARY_DEVICE_LOGGED_OUT", "PRIMARY_DEVICE_WAS_LOGGED_OUT",
            "ACCOUNT_UNBOUND", "ACCOUNT_INVALID", "ACCOUNT_REACHOUT_RESTRICTED");

    private HyperlinkSendFailurePolicy() { }

    /** 精确匹配证据码，不以包含 404、session 或 LID 的字符串推断号码无效。 */
    public static boolean targetFailure(String code) {
        return unregisteredTarget(code) || "INVALID_TARGET_JID".equals(code);
    }

    /** 同时识别 Android 原生码和通用协议码，兼容已经发布的明确未注册结果。 */
    public static boolean unregisteredTarget(String code) {
        return code != null && UNREGISTERED_CODES.contains(code);
    }

    /** 已有准备出口或明确 NOT_SENT/服务器拒绝才允许结束旧 attempt。 */
    public static boolean definitelyNotSent(String outcome, String code) {
        if ("NOT_SENT".equals(outcome)) { return true; }
        if (code == null) { return false; }
        return ("FAILED".equals(outcome) && "RATE_LIMITED".equals(code))
                || PRE_SEND_CODES.contains(code) || "ACCOUNT_REACHOUT_RESTRICTED".equals(code)
                || code.startsWith("WA_ACK_REJECTED_");
    }

    /** 不可用账号退出当前任务；不把局部会话错误写成全局账号限制。 */
    public static boolean unavailableAccount(String code) { return code != null && RESOURCE_CODES.contains(code); }

    /** 已确认未发送的错误有限退避后继续选号；尝试号不再触发整任务暂停或463目标失败。 */
    public static long nextRetryAt(Integer dispatchAttempt, String code, long now) {
        int attempt = dispatchAttempt == null ? 1 : Math.max(1, dispatchAttempt);
        return now + (FIRST_RETRY_DELAY_MS << Math.min(attempt - 1, MAX_BACKOFF_SHIFT));
    }
}
