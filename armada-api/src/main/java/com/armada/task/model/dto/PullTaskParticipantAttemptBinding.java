package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;

/** 把参与者聚合行原子绑定到当前逐号码执行记录。 */
public record PullTaskParticipantAttemptBinding(
        long participantId,
        long attemptId,
        long pullCallId,
        long pullerGroupAccountId,
        long now) {

    /** 绑定 CAS 的状态、失败上限以及站台角色守卫。 */
    public record Guard(
            int expectedStatus,
            long maxFailureCount,
            Integer participantRole,
            Integer availabilityStatus) {
    }

    /** @return 料子待拉池绑定守卫 */
    public static Guard materialGuard() {
        return new Guard(
                PullTaskMaterialPullStatus.UNCONSUMED.code(),
                4L, null, null);
    }

    /** @return 站台待拉池绑定守卫 */
    public static Guard stationGuard() {
        return new Guard(
                PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(),
                4L,
                PullTaskGroupAccountRole.STATION.code(),
                PullTaskGroupAccountAvailability.AVAILABLE.code());
    }
}
