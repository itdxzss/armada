package com.armada.marketing.service.impl;

import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import org.springframework.util.StringUtils;

/**
 * 从同一条最后有效发送尝试派生页面群状态、执行结果和失败原因。
 *
 * <p>有效尝试仅包含成功和失败；已提交、跳过记录可以出现在群组明细中，
 * 但不得覆盖最后有效执行结果。成功记录无条件清除历史失败语义并归一为正常。</p>
 */
final class MarketingGroupExecutionNormalizer {

    /** 群组可正常发送。 */
    private static final String STATUS_NORMAL = "NORMAL";

    /** 发送账号已封禁或明确需要重新授权。 */
    private static final String STATUS_ACCOUNT_BANNED = "ACCOUNT_BANNED";

    /** WhatsApp 群组已封禁或终止。 */
    private static final String STATUS_GROUP_BANNED = "GROUP_BANNED";

    /** 当前账号仍在群内但没有发言权限。 */
    private static final String STATUS_NO_PERMISSION = "NO_PERMISSION";

    /** 当前账号已不在目标群组中。 */
    private static final String STATUS_KICKED_OUT = "KICKED_OUT";

    /** 当前协议事实不足以确认具体群组状态。 */
    private static final String STATUS_UNCONFIRMED = "UNCONFIRMED";

    /** 最后有效尝试发送成功。 */
    private static final String RESULT_SUCCESS = "SUCCESS";

    /** 最后有效尝试发送失败。 */
    private static final String RESULT_FAILED = "FAILED";

    /** 协议明确判定发送账号已封禁。 */
    private static final String REASON_ACCOUNT_BANNED = "ACCOUNT_BANNED";

    /** 业务层明确判定账号已被踢出群聊。 */
    private static final String REASON_KICKED_OUT = "KICKED_OUT";

    /** 协议群状态判定当前账号已不在目标群。 */
    private static final String REASON_ACCOUNT_NOT_PARTICIPANT = "ACCOUNT_NOT_PARTICIPANT";

    /** 业务层统一群组封禁原因码。 */
    private static final String REASON_GROUP_BANNED = "GROUP_BANNED";

    /** 旧协议回执使用的群组封禁原因码。 */
    private static final String REASON_LEGACY_BANNED = "BANNED";

    /** 协议判定群聊已暂停。 */
    private static final String REASON_CHAT_SUSPENDED = "CHAT_SUSPENDED";

    /** 协议判定群聊已终止。 */
    private static final String REASON_CHAT_TERMINATED = "CHAT_TERMINATED";

    /** 业务层统一没有发言权限原因码。 */
    private static final String REASON_NO_PERMISSION = "NO_PERMISSION";

    /** 协议判定群聊仅管理员可发言且当前账号不是管理员。 */
    private static final String REASON_ANNOUNCE_ONLY_NON_ADMIN = "ANNOUNCE_ONLY_NON_ADMIN";

    /** 页面展示的账号封禁失败原因。 */
    private static final String MESSAGE_ACCOUNT_BANNED = "账号封禁";

    /** 页面展示的账号被踢出群聊失败原因。 */
    private static final String MESSAGE_KICKED_OUT = "账号已被踢出群聊";

    /** 页面展示的群组封禁失败原因。 */
    private static final String MESSAGE_GROUP_BANNED = "群组已封禁";

    /** 页面展示的当前账号没有发言权限失败原因。 */
    private static final String MESSAGE_NO_PERMISSION = "当前账号没有发言权限";

    /** 协议没有返回可展示失败描述时的兜底文案。 */
    private static final String MESSAGE_UNKNOWN = "未知原因";

    private MarketingGroupExecutionNormalizer() {
    }

    /**
     * 从一条最后有效发送尝试归一页面需要的三个同源字段。
     *
     * <p>判定优先级固定为账号封禁、被踢出群聊、群组封禁、没有权限、未确认，
     * 避免原始群状态和失败原因分别取自不同记录。成功尝试始终返回 {@code NORMAL/SUCCESS}
     * 且失败原因为空；非成功/失败记录返回 {@code UNCONFIRMED} 且不产生执行结果。</p>
     *
     * @param attemptStatus 最后有效尝试状态：1=成功、2=失败；为空或其他值表示没有有效结果
     * @param reasonCode 最后有效尝试的稳定失败原因码
     * @param reasonMessage 最后有效尝试的脱敏失败描述
     * @param rawGroupStatus 最后有效尝试携带的协议群组状态快照
     * @param groupStatusReason 最后有效尝试携带的协议群组状态判定原因
     * @return 页面群组状态、执行结果和执行原因；返回对象本身不为空
     */
    static NormalizedExecution normalize(Integer attemptStatus,
                                          String reasonCode,
                                          String reasonMessage,
                                          String rawGroupStatus,
                                          String groupStatusReason) {
        if (Integer.valueOf(MarketingSendAttemptStatus.SUCCESS.code()).equals(attemptStatus)) {
            return new NormalizedExecution(STATUS_NORMAL, RESULT_SUCCESS, null);
        }
        if (!Integer.valueOf(MarketingSendAttemptStatus.FAILED.code()).equals(attemptStatus)) {
            return new NormalizedExecution(STATUS_UNCONFIRMED, null, null);
        }
        if (matches(reasonCode, REASON_ACCOUNT_BANNED)) {
            return failed(STATUS_ACCOUNT_BANNED, MESSAGE_ACCOUNT_BANNED);
        }
        if (matches(reasonCode, REASON_KICKED_OUT, REASON_ACCOUNT_NOT_PARTICIPANT)
                || matches(groupStatusReason, REASON_ACCOUNT_NOT_PARTICIPANT)) {
            return failed(STATUS_KICKED_OUT, MESSAGE_KICKED_OUT);
        }
        if (matches(reasonCode, REASON_GROUP_BANNED, REASON_LEGACY_BANNED,
                REASON_CHAT_SUSPENDED, REASON_CHAT_TERMINATED)
                || matches(groupStatusReason, REASON_CHAT_SUSPENDED, REASON_CHAT_TERMINATED)
                || matches(rawGroupStatus, REASON_LEGACY_BANNED)) {
            return failed(STATUS_GROUP_BANNED, MESSAGE_GROUP_BANNED);
        }
        if (matches(reasonCode, REASON_NO_PERMISSION, REASON_ANNOUNCE_ONLY_NON_ADMIN)
                || matches(groupStatusReason, REASON_ANNOUNCE_ONLY_NON_ADMIN)
                || matches(rawGroupStatus, REASON_NO_PERMISSION)) {
            return failed(STATUS_NO_PERMISSION, MESSAGE_NO_PERMISSION);
        }
        String fallback = firstText(reasonMessage, reasonCode, MESSAGE_UNKNOWN);
        return failed(STATUS_UNCONFIRMED, fallback);
    }

    private static NormalizedExecution failed(String groupStatus, String reason) {
        return new NormalizedExecution(groupStatus, RESULT_FAILED, reason);
    }

    private static String firstText(String first, String second, String fallback) {
        if (StringUtils.hasText(first)) {
            return first.trim();
        }
        if (StringUtils.hasText(second)) {
            return second.trim();
        }
        return fallback;
    }

    private static boolean matches(String value, String... expected) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        for (String item : expected) {
            if (item.equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 页面需要的三个同源派生字段。
     *
     * @param groupStatus 统一群组状态
     * @param executionResult 执行结果
     * @param executionReason 失败原因，成功或未确认时为空
     */
    record NormalizedExecution(
            String groupStatus,
            String executionResult,
            String executionReason) {
    }
}
