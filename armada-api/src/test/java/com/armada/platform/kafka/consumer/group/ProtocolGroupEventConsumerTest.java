package com.armada.platform.kafka.consumer.group;

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
 * 协议群组事件 Kafka consumer 单测。
 *
 * <p>只验证 Kafka envelope 解析和事件分发,不启动真实 Kafka broker。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProtocolGroupEventConsumerTest {

    @Mock
    private ProtocolGroupHealthReportedSink sink;

    private ProtocolGroupEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProtocolGroupEventConsumer(new ObjectMapper(), sink);
    }

    @Test
    void onMessage_healthReportedEnvelope_dispatchesParsedHealthEvent() {
        String raw = """
                {
                  "eventId": "evt-group-1",
                  "event": "group.health_reported",
                  "version": "v1",
                  "accountId": "acc_861800000001",
                  "occurredAt": "2026-06-29T06:00:00Z",
                  "workerId": "worker-a",
                  "data": {
                    "tenantId": 1,
                    "groupLinkId": 200,
                    "groupJid": "1203630preview@g.us",
                    "health": "HEALTHY",
                    "memberCount": 88,
                    "checkedAt": "2026-06-29T06:00:01Z",
                    "subject": "运营群"
                  }
                }
                """;

        consumer.onMessage(raw);

        ArgumentCaptor<ProtocolGroupHealthReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupHealthReportedEvent.class);
        verify(sink).handleHealthReported(captor.capture());
        ProtocolGroupHealthReportedEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("evt-group-1");
        assertThat(event.tenantId()).isEqualTo(1L);
        assertThat(event.groupLinkId()).isEqualTo(200L);
        assertThat(event.groupJid()).isEqualTo("1203630preview@g.us");
        assertThat(event.health()).isEqualTo("HEALTHY");
        assertThat(event.memberCount()).isEqualTo(88);
        assertThat(event.checkedAt()).isEqualTo(1782712801000L);
        assertThat(event.subject()).isEqualTo("运营群");
        assertThat(event.protocolAccountId()).isEqualTo("acc_861800000001");
        assertThat(event.workerId()).isEqualTo("worker-a");
    }

    @Test
    void onMessage_unregisteredGroupEvent_skipsSink() {
        String raw = """
                {
                  "eventId": "evt-group-2",
                  "event": "group.previewed",
                  "accountId": "acc_861800000001",
                  "workerId": "worker-a",
                  "data": {"groupJid": "1203630preview@g.us"}
                }
                """;

        consumer.onMessage(raw);

        verifyNoInteractions(sink);
    }

    @Test
    void onMessage_healthReportedMissingTenantOrLinkId_skipsSink() {
        String raw = """
                {
                  "eventId": "evt-group-3",
                  "event": "group.health_reported",
                  "accountId": "acc_861800000001",
                  "workerId": "worker-a",
                  "data": {
                    "groupJid": "1203630preview@g.us",
                    "health": "HEALTHY"
                  }
                }
                """;

        consumer.onMessage(raw);

        verifyNoInteractions(sink);
    }

    @Test
    void onMessage_malformedJson_throwsBusinessExceptionWithoutSink() {
        assertThatThrownBy(() -> consumer.onMessage("{bad-json"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议群组事件 JSON 解析失败");

        verifyNoInteractions(sink);
    }

    @Test
    void onMessage_missingHealth_throwsBusinessExceptionWithoutSink() {
        String raw = """
                {
                  "eventId": "evt-group-4",
                  "event": "group.health_reported",
                  "accountId": "acc_861800000001",
                  "occurredAt": "2026-06-29T06:00:01Z",
                  "workerId": "worker-a",
                  "data": {
                    "tenantId": 1,
                    "groupLinkId": 200,
                    "groupJid": "1203630preview@g.us"
                  }
                }
                """;

        assertThatThrownBy(() -> consumer.onMessage(raw))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议群组健康事件缺少 data.health");

        verifyNoInteractions(sink);
    }

    @Test
    void onMessage_sinkFailureBubblesUpForKafkaContainerRetry() {
        String raw = """
                {
                  "eventId": "evt-group-5",
                  "event": "group.health_reported",
                  "accountId": "acc_861800000001",
                  "occurredAt": "2026-06-29T06:00:01Z",
                  "workerId": "worker-a",
                  "data": {
                    "tenantId": 1,
                    "groupLinkId": 200,
                    "groupJid": "1203630preview@g.us",
                    "health": "ERROR"
                  }
                }
                """;
        doThrow(new IllegalStateException("database unavailable")).when(sink).handleHealthReported(any());

        assertThatThrownBy(() -> consumer.onMessage(raw))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }
}
