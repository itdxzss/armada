package com.armada.task.model.dto;

/** 为与当前粘性拉手相同的历史调用补齐分配代际。 */
public record PullTaskLegacyPullerGenerationBinding(
        long pullCallId,
        long pullerGroupAccountId,
        long assignmentSeq,
        long now) {
}
