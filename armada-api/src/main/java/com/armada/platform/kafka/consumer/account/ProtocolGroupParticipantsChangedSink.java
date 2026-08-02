package com.armada.platform.kafka.consumer.account;

/** 普通 WhatsApp 群成员变化事件下游处理口。 */
public interface ProtocolGroupParticipantsChangedSink {

    /** 处理一次可包含多个成员的 add/remove/leave 事件。 */
    void handleParticipantsChanged(ProtocolGroupParticipantsChangedEvent event);
}
