package com.armada.marketing.grouppull.service;

import com.armada.marketing.grouppull.model.enums.GroupPullSpeakPermission;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import java.util.Locale;
import java.util.Set;

/** 拉群执行阶段共用的有限重试和结果判定规则。 */
public final class GroupPullRetryPolicy {

    /** 建群及群配置关键操作失败后的固定重试次数，不包含首次。 */
    private static final int FIXED_GROUP_RETRY_COUNT = 2;

    /** 单成员操作明确成功或成员已在群内的状态码。 */
    private static final Set<String> PARTICIPANT_SUCCESS_CODES =
            Set.of("OK", "ALREADY_IN", "200");

    /** 协议原始错误中明确表示群组已封禁或终止的代码。 */
    private static final Set<String> BANNED_GROUP_CODES = Set.of(
            "GROUP_BANNED", "BANNED", "CHAT_SUSPENDED", "CHAT_TERMINATED");

    /** 禁止实例化纯规则类。 */
    private GroupPullRetryPolicy() {
    }

    /**
     * 把页面配置的好友重试次数转换为包含首次的最大尝试次数。
     *
     * @param configuredRetryCount 好友失败重试次数，不包含首次
     * @return 包含首次的最大尝试次数，最少为 1
     */
    public static int friendAttempts(int configuredRetryCount) {
        return Math.max(configuredRetryCount, 0) + 1;
    }

    /**
     * 返回建群及群配置关键操作包含首次的固定最大尝试次数。
     *
     * @return 固定最大尝试次数 3
     */
    public static int groupOperationAttempts() {
        return FIXED_GROUP_RETRY_COUNT + 1;
    }

    /**
     * 判断协议层单成员添加结果是否表示已经成功进群。
     *
     * @param item 协议层单成员结果；可空
     * @return 新增成功或成员已经在群内时返回 true
     */
    public static boolean isParticipantSuccess(GroupParticipantBatchResult.Item item) {
        if (item == null) {
            return false;
        }
        return isParticipantSuccessCode(item.status())
                || isParticipantSuccessCode(item.rawStatus());
    }

    /**
     * 判断协议异常是否明确表示目标群已封禁或终止。
     *
     * @param exception 协议调用异常
     * @return 统一错误码或协议原始码明确表示群不可用时返回 true
     */
    public static boolean isGroupBanned(ProtocolException exception) {
        if (exception.errorCode() == ProtocolErrorCode.GROUP_UNAVAILABLE) {
            return true;
        }
        return exception.protocolCode()
                .map(GroupPullRetryPolicy::isGroupBannedCode)
                .orElse(false);
    }

    /**
     * 判断单成员操作结果是否明确表示目标群已封禁或终止。
     *
     * @param item 协议层单成员结果；可空
     * @return 归一状态或原始状态明确表示群不可用时返回 true
     */
    public static boolean isGroupBanned(GroupParticipantBatchResult.Item item) {
        return item != null
                && (isGroupBannedCode(item.status()) || isGroupBannedCode(item.rawStatus()));
    }

    private static boolean isGroupBannedCode(String code) {
        return code != null
                && BANNED_GROUP_CODES.contains(code.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean isParticipantSuccessCode(String code) {
        return code != null
                && PARTICIPANT_SUCCESS_CODES.contains(code.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 按已确认的群权限联动规则判断营销账号是否必须设置为管理员。
     *
     * @param permission 群组发言权限配置
     * @param builderExitEnabled 建群账号完成后是否退出群组
     * @return 禁言或建群账号退出时返回 true
     */
    public static boolean adminRequired(
            GroupPullSpeakPermission permission,
            boolean builderExitEnabled) {
        return permission == GroupPullSpeakPermission.MUTED || builderExitEnabled;
    }
}
