package com.armada.hyperlink.task.service;

import java.util.Set;

/** 超链只依据确定的发送事实重试；会话、设备和密钥错误不构成目标无效证据。 */
public final class HyperlinkSendFailurePolicy {
    /** 交由轮次调度暂停任务的待发时间标记；不作为失败终态。 */
    public static final long RECOVERY_HOLD = Long.MAX_VALUE;
    private static final int ATTEMPTS_PER_RECOVERY = 3;
    private static final Set<String> TARGET_CODES = Set.of("RECIPIENT_UNREGISTERED", "INVALID_TARGET_JID");
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
    private static final Set<String> TRANSIENT_ACK_CODES = Set.of(
            "RATE_LIMITED", "WA_ACK_REJECTED_429", "WA_ACK_REJECTED_500", "WA_ACK_REJECTED_502",
            "WA_ACK_REJECTED_503", "WA_ACK_REJECTED_504");

    private HyperlinkSendFailurePolicy() { }

    /** 精确匹配证据码，不以包含 404、session 或 LID 的字符串推断号码无效。 */
    public static boolean targetFailure(String code) { return code != null && TARGET_CODES.contains(code); }

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

    /** 有限自动恢复，每三次失败暂停；内容或不认识的明确拒绝直接等待修复。 */
    public static long nextRetryAt(Integer dispatchAttempt, String code, long now) {
        int attempt = dispatchAttempt == null ? 1 : Math.max(1, dispatchAttempt);
        boolean retryable = code != null && (PRE_SEND_CODES.contains(code)
                || RESOURCE_CODES.contains(code) || TRANSIENT_ACK_CODES.contains(code));
        if (!retryable || attempt % ATTEMPTS_PER_RECOVERY == 0) { return RECOVERY_HOLD; }
        return now + 30_000L * (attempt % ATTEMPTS_PER_RECOVERY);
    }
}
