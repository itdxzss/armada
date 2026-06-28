package com.armada.platform.kafka.consumer.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 协议账号事件 Kafka consumer 单测。
 *
 * <p>只验证 Kafka envelope 解析和事件分发,不启动真实 Kafka broker。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProtocolAccountEventConsumerTest {

    @Mock
    private ProtocolAccountStateChangedSink sink;

    private ProtocolAccountEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProtocolAccountEventConsumer(new ObjectMapper(), sink);
    }

    @Test
    void onMessage_stateChangedEnvelope_dispatchesParsedStateChangedEvent() {
        String raw = """
                {
                  "eventId": "evt-1",
                  "event": "account.state_changed",
                  "version": "v1",
                  "accountId": "acc_861800000001",
                  "occurredAt": "2026-06-28T06:00:01Z",
                  "workerId": "worker-a",
                  "data": {
                    "from": "RECONNECTING",
                    "to": "ONLINE",
                    "reason": "connected",
                    "semantic": "RECONNECTING",
                    "rawCode": 515
                  }
                }
                """;

        consumer.onMessage(raw);

        ArgumentCaptor<ProtocolAccountStateChangedEvent> captor =
                ArgumentCaptor.forClass(ProtocolAccountStateChangedEvent.class);
        verify(sink).handleStateChanged(captor.capture());
        ProtocolAccountStateChangedEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("evt-1");
        assertThat(event.protocolAccountId()).isEqualTo("acc_861800000001");
        assertThat(event.from()).isEqualTo("RECONNECTING");
        assertThat(event.to()).isEqualTo("ONLINE");
        assertThat(event.occurredAt()).isEqualTo(1782626401000L);
        assertThat(event.semantic()).isEqualTo("RECONNECTING");
        assertThat(event.rawCode()).isEqualTo(515);
        assertThat(event.workerId()).isEqualTo("worker-a");
    }

    @Test
    void onMessage_unregisteredAccountEvent_skipsSink() {
        String raw = """
                {
                  "eventId": "evt-2",
                  "event": "account.heartbeat",
                  "version": "v1",
                  "accountId": "acc_861800000001",
                  "occurredAt": "2026-06-28T06:00:01Z",
                  "workerId": "worker-a",
                  "data": {"state": "ONLINE"}
                }
                """;

        consumer.onMessage(raw);

        verifyNoInteractions(sink);
    }

    @Test
    void onMessage_malformedJson_throwsBusinessExceptionWithoutSink() {
        assertThatThrownBy(() -> consumer.onMessage("{bad-json"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议账号事件 JSON 解析失败");

        verifyNoInteractions(sink);
    }

    @Test
    void onMessage_missingTargetState_throwsBusinessExceptionWithoutSink() {
        String raw = """
                {
                  "eventId": "evt-3",
                  "event": "account.state_changed",
                  "accountId": "acc_861800000001",
                  "occurredAt": "2026-06-28T06:00:01Z",
                  "workerId": "worker-a",
                  "data": {"from": "RECONNECTING"}
                }
                """;

        assertThatThrownBy(() -> consumer.onMessage(raw))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议账号状态事件缺少 data.to");

        verifyNoInteractions(sink);
    }

    @Test
    void onMessage_sinkFailureBubblesUpForKafkaContainerRetry() {
        String raw = """
                {
                  "eventId": "evt-4",
                  "event": "account.state_changed",
                  "accountId": "acc_861800000001",
                  "occurredAt": "2026-06-28T06:00:01Z",
                  "workerId": "worker-a",
                  "data": {"from": "RECONNECTING", "to": "ONLINE"}
                }
                """;
        doThrow(new IllegalStateException("database unavailable")).when(sink).handleStateChanged(any());

        assertThatThrownBy(() -> consumer.onMessage(raw))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }
}
