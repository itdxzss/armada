package com.armada.platform.kafka.trace;

import com.armada.shared.trace.TraceContext;
import com.armada.shared.trace.TraceIds;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;

/**
 * Kafka 事件 Envelope 与 Header 的追踪标识解析入口。
 */
public final class KafkaTraceSupport {

    private KafkaTraceSupport() {
    }

    /**
     * 按 Envelope、Header、稳定事件 ID 的优先级建立 MDC Scope。
     *
     * @param envelope Kafka 事件 Envelope
     * @param headerTraceId Kafka Header 中的追踪标识
     * @param logger 当前消费者日志器
     * @param stableSeed 旧事件稳定追踪标识的派生种子
     * @return 关闭后恢复原 MDC 的 Scope
     */
    public static TraceContext.Scope open(
            JsonNode envelope,
            String headerTraceId,
            Logger logger,
            String stableSeed) {
        String envelopeTraceId = envelope == null
                ? null
                : envelope.path("traceId").asText(null);
        TraceIds.Resolution resolution = TraceIds.resolveCandidates(
                envelopeTraceId, headerTraceId, stableSeed);
        if (resolution.mismatch()) {
            logger.warn(
                    "event traceId mismatch envelopeTraceId={} headerTraceId={}",
                    envelopeTraceId, headerTraceId);
        }
        return TraceContext.open(resolution.traceId());
    }
}
