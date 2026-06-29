package com.armada.platform.kafka.consumer.group;

/**
 * 协议群组健康检测事件下游处理口。
 *
 * <p>该接口定义在 platform.kafka,使 Kafka consumer 不直接依赖 group 域实现。</p>
 */
public interface ProtocolGroupHealthReportedSink {

    /**
     * 处理协议群组健康检测回报事件。
     *
     * @param event 已解析出的健康检测事件
     */
    void handleHealthReported(ProtocolGroupHealthReportedEvent event);
}
