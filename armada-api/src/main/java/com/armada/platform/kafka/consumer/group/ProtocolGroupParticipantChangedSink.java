package com.armada.platform.kafka.consumer.group;

/** 群成员角色变化事件下游处理边界。 */
public interface ProtocolGroupParticipantChangedSink {

    /** 应用已经通过平台层结构校验的 promote/demote 事件。 */
    void handleParticipantChanged(ProtocolGroupParticipantChangedEvent event);
}
