package com.armada.platform.kafka.consumer.account;

/** 协议层单群详情同步请求下游处理口。 */
public interface ProtocolGroupMetadataSyncRequestedSink {

    /**
     * 处理已完成 envelope 校验的单群详情同步请求。
     *
     * @param event 同步请求事件
     */
    void handleGroupMetadataSyncRequested(ProtocolGroupMetadataSyncRequestedEvent event);
}
