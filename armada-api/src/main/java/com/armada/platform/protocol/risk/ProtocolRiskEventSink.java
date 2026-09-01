package com.armada.platform.protocol.risk;

import com.armada.platform.kafka.consumer.account.ProtocolAccountRestrictedEvent;

/** Kafka 风控信号到追加事实和调度投影的统一边界。 */
public interface ProtocolRiskEventSink {

    /** 保存账号级平台限制状态并更新可逆的消息发送限制投影。 */
    void handleAccountRestricted(ProtocolAccountRestrictedEvent event);

    /** 从任意协议结果记录三类固定风控信号。 */
    void handleResult(ProtocolRiskResultMetadata metadata);
}
