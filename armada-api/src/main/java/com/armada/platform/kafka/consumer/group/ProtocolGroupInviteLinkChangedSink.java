package com.armada.platform.kafka.consumer.group;

/** 群邀请链接变更事件下游处理口。 */
public interface ProtocolGroupInviteLinkChangedSink {

    /**
     * 保存协议层观察到的当前群邀请码。
     *
     * @param event 已完成信封和字段校验的事件
     */
    void handleInviteLinkChanged(ProtocolGroupInviteLinkChangedEvent event);
}
