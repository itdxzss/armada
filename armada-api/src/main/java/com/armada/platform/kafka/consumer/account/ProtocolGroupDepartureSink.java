package com.armada.platform.kafka.consumer.account;

/** 协议退群事实下游处理口。 */
public interface ProtocolGroupDepartureSink {

    /** 保存 Android 协议明确提供的退群事实。 */
    void handleDepartures(ProtocolGroupDepartureEvent event);
}
