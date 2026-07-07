package com.armada.platform.kafka.consumer.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ProtocolMessageEventConsumerTest {

    @Mock
    private ProtocolMessageSendResultReportedSink sink;

    private ProtocolMessageEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProtocolMessageEventConsumer(new ObjectMapper(), sink);
    }

    @Test
    void onMessage_sendResultEnvelope_dispatchesParsedEvent() {
        String raw = """
                {
                  "eventId":"evt_1",
                  "event":"message.send_result_reported",
                  "version":"v1",
                  "accountId":"acc_8613800138000",
                  "occurredAt":"2026-07-04T10:00:00.000Z",
                  "workerId":"worker-a",
                  "data":{
                    "tenantId":1,
                    "marketingTaskId":42,
                    "targetId":501,
                    "attemptId":9001,
                    "roundNo":1,
                    "protocolAccountId":"acc_8613800138000",
                    "groupJid":"120363001@g.us",
                    "commandId":"cmd_1",
                    "success":true,
                    "messageId":"wamid.1",
                    "timestamp":1783159200000
                  }
                }
                """;

        consumer.onMessage(raw);

        ArgumentCaptor<ProtocolMessageSendResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolMessageSendResultReportedEvent.class);
        verify(sink).handleSendResultReported(captor.capture());
        ProtocolMessageSendResultReportedEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("evt_1");
        assertThat(event.tenantId()).isEqualTo(1L);
        assertThat(event.marketingTaskId()).isEqualTo(42L);
        assertThat(event.targetId()).isEqualTo(501L);
        assertThat(event.attemptId()).isEqualTo(9001L);
        assertThat(event.roundNo()).isEqualTo(1L);
        assertThat(event.protocolAccountId()).isEqualTo("acc_8613800138000");
        assertThat(event.groupJid()).isEqualTo("120363001@g.us");
        assertThat(event.commandId()).isEqualTo("cmd_1");
        assertThat(event.success()).isTrue();
        assertThat(event.messageId()).isEqualTo("wamid.1");
        assertThat(event.timestamp()).isEqualTo(1783159200000L);
        assertThat(event.workerId()).isEqualTo("worker-a");
    }

    @Test
    void onMessage_groupCreationSendResultDispatchesParsedItemReference() {
        String raw = """
                {
                  "eventId":"evt_gcm_1",
                  "event":"message.send_result_reported",
                  "version":"v1",
                  "accountId":"acc_8613800138000",
                  "occurredAt":"2026-07-04T10:00:00.000Z",
                  "workerId":"worker-a",
                  "data":{
                    "tenantId":1,
                    "groupCreationTaskId":22,
                    "groupCreationItemId":11,
                    "protocolAccountId":"acc_8613800138000",
                    "groupJid":"120363001@g.us",
                    "commandId":"cmd_gcm_item_11",
                    "success":true,
                    "messageId":"wamid.1",
                    "timestamp":1783159200000,
                    "source":"group_creation_marketing"
                  }
                }
                """;

        consumer.onMessage(raw);

        ArgumentCaptor<ProtocolMessageSendResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolMessageSendResultReportedEvent.class);
        verify(sink).handleSendResultReported(captor.capture());
        ProtocolMessageSendResultReportedEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("evt_gcm_1");
        assertThat(event.tenantId()).isEqualTo(1L);
        assertThat(event.marketingTaskId()).isNull();
        assertThat(event.targetId()).isNull();
        assertThat(event.attemptId()).isNull();
        assertThat(event.roundNo()).isNull();
        assertThat(event.groupCreationTaskId()).isEqualTo(22L);
        assertThat(event.groupCreationItemId()).isEqualTo(11L);
        assertThat(event.commandId()).isEqualTo("cmd_gcm_item_11");
        assertThat(event.source()).isEqualTo("group_creation_marketing");
    }

    @Test
    void onMessage_unregisteredMessageEvent_skipsSink() {
        String raw = """
                {
                  "eventId":"evt_2",
                  "event":"message.received",
                  "accountId":"acc_1",
                  "data":{}
                }
                """;

        consumer.onMessage(raw);

        verifyNoInteractions(sink);
    }
}
