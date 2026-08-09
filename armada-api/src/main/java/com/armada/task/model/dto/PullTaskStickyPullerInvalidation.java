package com.armada.task.model.dto;

/** 仅当拉手身份和分配代际仍匹配时清空粘性拉手。 */
public record PullTaskStickyPullerInvalidation(
        long executionId,
        long expectedPullerGroupAccountId,
        long expectedAssignmentSeq,
        String reasonCode,
        long now) {
}
