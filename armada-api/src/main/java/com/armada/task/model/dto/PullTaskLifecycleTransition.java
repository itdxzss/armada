package com.armada.task.model.dto;

/** 父任务生命周期条件更新的完整输入。 */
public record PullTaskLifecycleTransition(
        long taskId,
        String expectedStatus,
        String targetStatus,
        int expectedVersion,
        String blockingReason,
        Long startedAt,
        Long finishedAt,
        long now) {
}
