package com.armada.platform.kafka.consumer.message;

public interface ProtocolMessageSendResultReportedSink {

    /**
     * 判断当前业务处理器是否唯一负责该来源的发送结果。
     *
     * @param event 已完成协议字段解析的发送结果
     * @return {@code true} 表示由当前处理器消费
     */
    boolean supports(ProtocolMessageSendResultReportedEvent event);

    /** 以业务幂等规则回写发送结果。 */
    void handleSendResultReported(ProtocolMessageSendResultReportedEvent event);
}
