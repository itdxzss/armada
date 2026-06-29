package com.armada.platform.kafka.consumer.account;

/**
 * 协议账号当前群列表事件下游处理口。
 *
 * <p>该接口定义在 platform.kafka,使 Kafka consumer 不直接依赖 group 域实现。</p>
 */
public interface ProtocolAccountGroupsReportedSink {

    /**
     * 处理协议账号当前群列表回报事件。
     *
     * @param event 已解析出的账号群列表事件
     */
    void handleGroupsReported(ProtocolAccountGroupsReportedEvent event);
}
