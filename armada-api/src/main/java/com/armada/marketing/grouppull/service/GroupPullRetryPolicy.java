package com.armada.marketing.grouppull.service;

import com.armada.marketing.grouppull.model.enums.GroupPullSpeakPermission;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;

/** 拉群执行阶段共用的有限重试和结果判定规则。 */
public final class GroupPullRetryPolicy {

    /** 建群及群配置关键操作失败后的固定重试次数，不包含首次。 */
    private static final int FIXED_GROUP_RETRY_COUNT = 2;

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
        return "OK".equalsIgnoreCase(item.status())
                || "ALREADY_IN".equalsIgnoreCase(item.status())
                || "200".equals(item.rawStatus());
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
