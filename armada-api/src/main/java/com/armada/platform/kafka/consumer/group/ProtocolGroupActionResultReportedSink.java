package com.armada.platform.kafka.consumer.group;

/** 平台 Kafka 消费层向拉群任务域传递群动作结果的边界。 */
public interface ProtocolGroupActionResultReportedSink {

    /**
     * 应用一条已通过协议信封校验的群动作结果。
     *
     * @param event 群动作结果
     */
    void handleActionResultReported(ProtocolGroupActionResultReportedEvent event);
}
