package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskPullCallStatus;

/** 迟到成功取消一个未提交参与者后，收缩原冻结调用人数。 */
public record PullTaskPlannedCallPrune(
        long pullCallId,
        int participantType,
        int expectedCallStatus,
        long now,
        Target target) {

    /** 业务构造入口；数据库目标常量由 Java 枚举统一提供。 */
    public PullTaskPlannedCallPrune(
            long pullCallId,
            int participantType,
            int expectedCallStatus,
            long now) {
        this(pullCallId, participantType, expectedCallStatus, now, Target.DEFAULT);
    }

    /** 裁剪 SQL 的状态和原因目标值。 */
    public record Target(
            int materialParticipantType,
            int stationParticipantType,
            int canceledCallStatus,
            String emptyCallReasonCode,
            String emptyCallReasonMessage) {

        private static final Target DEFAULT = new Target(
                PullTaskParticipantType.MATERIAL.code(),
                PullTaskParticipantType.STATION.code(),
                PullTaskPullCallStatus.CANCELED.code(),
                "LATE_PARTICIPANT_SUCCESS",
                "迟到成功使未提交调用变为空调用");
    }
}
