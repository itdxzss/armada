package com.armada.platform.kafka.consumer.group;

/**
 * 单群完整资料上报事件的下游处理边界。
 *
 * <p>platform.kafka 只做解析与协议契约校验，资料字段与成员事实的落库由 group 域实现。</p>
 */
public interface ProtocolGroupProfileReportedSink {

    /**
     * 处理单群完整资料上报事件。
     *
     * @param event 已完成协议契约校验的事件
     */
    void handleProfileReported(ProtocolGroupProfileReportedEvent event);
}
