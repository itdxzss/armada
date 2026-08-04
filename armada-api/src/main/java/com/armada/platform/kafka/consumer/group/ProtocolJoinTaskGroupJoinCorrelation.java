package com.armada.platform.kafka.consumer.group;

/**
 * 进群任务结果关联。
 *
 * @param joinTaskId 进群任务 ID
 * @param joinTaskResultId 进群任务明细 ID
 */
public record ProtocolJoinTaskGroupJoinCorrelation(
        Long joinTaskId,
        Long joinTaskResultId
) implements ProtocolGroupJoinCorrelation {
}
