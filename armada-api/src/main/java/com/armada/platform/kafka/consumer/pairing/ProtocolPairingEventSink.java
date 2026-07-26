package com.armada.platform.kafka.consumer.pairing;

/** 配对事件进入业务域的端口。 */
public interface ProtocolPairingEventSink {

    /** 处理一个已经完成结构校验的协议配对事件。 */
    void handle(ProtocolPairingEvent event);
}
