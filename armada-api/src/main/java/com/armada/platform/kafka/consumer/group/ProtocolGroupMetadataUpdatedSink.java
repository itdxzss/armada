package com.armada.platform.kafka.consumer.group;

/**
 * 群资料字段级变更事件的下游处理边界。
 *
 * <p>platform.kafka 只负责解析与协议契约校验，业务落库由 group 域实现该接口，保持依赖方向
 * 由 group 指向 platform。</p>
 */
public interface ProtocolGroupMetadataUpdatedSink {

    /**
     * 处理群资料字段级变更事件。
     *
     * @param event 已完成协议契约校验的事件
     */
    void handleMetadataUpdated(ProtocolGroupMetadataUpdatedEvent event);
}
