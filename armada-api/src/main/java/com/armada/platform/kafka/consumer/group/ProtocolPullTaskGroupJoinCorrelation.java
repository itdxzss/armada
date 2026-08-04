package com.armada.platform.kafka.consumer.group;

/**
 * 普通拉群管理员踩链接结果关联。
 *
 * @param pullTaskId 普通拉群任务 ID
 * @param groupExecutionId 群链接执行行 ID
 * @param actionId 踩链接动作行 ID
 */
public record ProtocolPullTaskGroupJoinCorrelation(
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId
) implements ProtocolGroupJoinCorrelation {
}
