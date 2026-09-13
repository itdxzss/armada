package com.armada.task.model.dto;

/** 批次提交前依据仍有效的参与者计划同步计数；只允许没有命令和提交时间的计划态。 */
public record PullTaskPlannedCallCounts(
        long pullCallId,
        int materialCount,
        int stationCount,
        int expectedCallStatus,
        long now) {
}
