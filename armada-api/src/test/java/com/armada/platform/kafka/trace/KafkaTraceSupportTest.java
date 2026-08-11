package com.armada.platform.kafka.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.shared.trace.TraceContext;
import com.armada.shared.trace.TraceIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/** Kafka 事件追踪上下文解析测试。 */
class KafkaTraceSupportTest {

    private static final String FIXED_TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_TRACE_ID = "11111111111111111111111111111111";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void open_envelopeWinsOverHeaderAndScopeIsRestored() {
        Logger logger = mock(Logger.class);
        JsonNode envelope = objectMapper.createObjectNode().put("traceId", FIXED_TRACE_ID);

        try (TraceContext.Scope ignored = KafkaTraceSupport.open(
                envelope, OTHER_TRACE_ID, logger, "event-1")) {
            assertThat(TraceContext.current()).contains(FIXED_TRACE_ID);
        }

        assertThat(TraceContext.current()).isEmpty();
        verify(logger).warn(
                "event traceId mismatch envelopeTraceId={} headerTraceId={}",
                FIXED_TRACE_ID, OTHER_TRACE_ID);
    }

    @Test
    void open_invalidCandidatesUseStableSeedWithoutLoggingRawValues() {
        Logger logger = mock(Logger.class);
        JsonNode envelope = objectMapper.createObjectNode().put("traceId", "bad\nforged=true");

        try (TraceContext.Scope ignored = KafkaTraceSupport.open(
                envelope, "ALSO-BAD", logger, "event-legacy")) {
            assertThat(TraceContext.current()).contains(TraceIds.stableFrom("event-legacy"));
        }

        assertThat(TraceContext.current()).isEmpty();
        verifyNoInteractions(logger);
    }
}
