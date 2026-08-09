package com.armada.task.model.dto;

import java.util.List;

/** 按 Java 已确定的稳定序号把一条开放历史调用挂到初始波次。 */
public record PullTaskLegacyCallWaveBinding(
        Scope scope,
        Target target,
        long now) {

    /** 历史调用身份和允许挂接的开放状态。 */
    public record Scope(
            long callId,
            long groupExecutionId,
            List<Integer> expectedStatuses) {

        /** 固化开放状态集合。 */
        public Scope {
            expectedStatuses = List.copyOf(expectedStatuses);
            if (expectedStatuses.isEmpty()) {
                throw new IllegalArgumentException("历史开放调用状态不能为空");
            }
        }
    }

    /** 新波次身份和波次内稳定序号。 */
    public record Target(long pullWaveId, int waveCallSeq) {
    }
}
