package com.armada.platform.kafka.consumer.group;

/**
 * 平台 Kafka 消费层向进群任务域传递结果的边界。
 *
 * <p>消费层只负责校验协议事件信封和字段类型，业务幂等、重试决策及同账号下一行激活由实现方处理。</p>
 */
public interface ProtocolGroupJoinResultReportedSink {

    /**
     * 应用一条已经通过信封校验的 Web/Android 统一进群结果。
     *
     * @param event 协议层进群结果；包含用于幂等匹配的明细 ID、命令 ID 和尝试序号
     */
    void handleJoinResultReported(ProtocolGroupJoinResultReportedEvent event);
}
