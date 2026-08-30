package com.armada.platform.kafka.consumer.account;

/** 协议账号类型检测事件下游处理口。 */
public interface ProtocolAccountTypeDetectedSink {

    /**
     * 处理已经完成信封校验的账号类型检测事实。
     *
     * @param event 检测事件
     */
    void handleTypeDetected(ProtocolAccountTypeDetectedEvent event);
}
