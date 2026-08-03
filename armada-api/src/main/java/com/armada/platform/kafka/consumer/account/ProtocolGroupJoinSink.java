package com.armada.platform.kafka.consumer.account;

/** 协议群成员进群事实下游处理口。 */
public interface ProtocolGroupJoinSink {

    /** 处理一批同群进群事实。 */
    void handleJoins(ProtocolGroupJoinEvent event);
}
