package com.armada.platform.kafka.consumer.group;

/** 平台统一结果消费者向新建普群状态机传递强类型结果的边界。 */
public interface ProtocolNormalGroupCreationResultReportedSink {

    /** 应用一条已通过信封、action、协议类型和账号一致性校验的最终结果。 */
    void handleNormalGroupCreationResult(ProtocolNormalGroupCreationResultReportedEvent event);
}
