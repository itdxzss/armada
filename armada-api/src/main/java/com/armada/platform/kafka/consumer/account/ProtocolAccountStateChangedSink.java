package com.armada.platform.kafka.consumer.account;

/**
 * 协议账号状态变更事件下游处理口。
 *
 * <p>该接口定义在 platform.kafka,使 Kafka consumer 不直接依赖 account 域实现。
 * 业务域可实现本接口,把平台层事件转换为自身应用服务入参。</p>
 */
public interface ProtocolAccountStateChangedSink {

    /**
     * 处理协议账号状态变更事件。
     *
     * @param event 已解析出的状态变更事件
     */
    void handleStateChanged(ProtocolAccountStateChangedEvent event);
}
