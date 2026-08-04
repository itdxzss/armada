package com.armada.platform.kafka.consumer.group;

/** 普通链接拉群批量加成员逐成员结果的下游处理边界。 */
public interface ProtocolPullTaskBatchParticipantResultReportedSink {

    /**
     * 消费一个批量加成员命令中的单成员结果。
     *
     * @param event 已通过 Kafka 信封和强关联字段校验的结果事件
     */
    void handleBatchParticipantResultReported(
            ProtocolPullTaskBatchParticipantResultReportedEvent event);
}
