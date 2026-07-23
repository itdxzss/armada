package com.armada.marketing.grouppull.service;

import com.armada.marketing.grouppull.model.enums.GroupPullSpeakPermission;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;

/** 拉群执行阶段共用的有限重试和结果判定规则。 */
public final class GroupPullRetryPolicy {

    private static final int FIXED_GROUP_RETRY_COUNT = 2;

    private GroupPullRetryPolicy() {
    }

    public static int friendAttempts(int configuredRetryCount) {
        return Math.max(configuredRetryCount, 0) + 1;
    }

    public static int groupOperationAttempts() {
        return FIXED_GROUP_RETRY_COUNT + 1;
    }

    public static boolean isParticipantSuccess(GroupParticipantBatchResult.Item item) {
        if (item == null) {
            return false;
        }
        return "OK".equalsIgnoreCase(item.status())
                || "ALREADY_IN".equalsIgnoreCase(item.status())
                || "200".equals(item.rawStatus());
    }

    public static boolean adminRequired(
            GroupPullSpeakPermission permission,
            boolean builderExitEnabled) {
        return permission == GroupPullSpeakPermission.MUTED || builderExitEnabled;
    }
}
