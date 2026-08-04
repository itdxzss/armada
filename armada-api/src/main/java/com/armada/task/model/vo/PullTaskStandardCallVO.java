package com.armada.task.model.vo;

/** 一次真实批量拉人调用的详情投影。 */
public record PullTaskStandardCallVO(
        long callId,
        int callSeq,
        long pullerAccountId,
        int plannedMaterialCount,
        int plannedStationCount,
        int callStatus,
        String reasonCode,
        String reasonMessage,
        Long submittedAt,
        Long resultAt) {
}
