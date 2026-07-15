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
                    "groupStatus":"NO_PERMISSION",
                    "groupStatusReason":"ANNOUNCE_ONLY_NON_ADMIN",
                    "groupStatusCheckedAt":1783159199000,
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
        assertThat(event.groupStatus()).isEqualTo("NO_PERMISSION");
        assertThat(event.groupStatusReason()).isEqualTo("ANNOUNCE_ONLY_NON_ADMIN");
        assertThat(event.groupStatusCheckedAt()).isEqualTo(1783159199000L);
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
    void onMessage_androidMentionAllResolutionFailure_preservesStableReason() {
        String raw = """
                {
                  "eventId":"acc_android_1:message.send_result_reported:cmd_mention_failed",
                  "event":"message.send_result_reported",
                  "version":"v1",
                  "accountId":"acc_android_1",
                  "occurredAt":"2026-07-15T10:00:00Z",
                  "workerId":"whatsapp-server-feature-android-zhuan",
                  "data":{
                    "tenantId":1,
                    "accountId":2,
                    "marketingTaskId":42,
                    "targetId":501,
                    "attemptId":9001,
                    "roundNo":1,
                    "protocolAccountId":"acc_android_1",
                    "groupJid":"120363001@g.us",
                    "commandId":"cmd_mention_failed",
                    "success":false,
                    "reasonCode":"MENTION_ALL_RESOLUTION_FAILED",
                    "reasonMessage":"无法获取完整群成员，消息未发送",
                    "timestamp":1784109600000,
                    "source":"marketing_task",
                    "groupStatus":"UNCONFIRMED",
                    "groupStatusReason":"STATUS_RESOLUTION_UNAVAILABLE",
                    "groupStatusCheckedAt":1784109600000
                  }
                }
                """;

        consumer.onMessage(raw);

        ArgumentCaptor<ProtocolMessageSendResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolMessageSendResultReportedEvent.class);
        verify(sink).handleSendResultReported(captor.capture());
        ProtocolMessageSendResultReportedEvent event = captor.getValue();
        assertThat(event.success()).isFalse();
        assertThat(event.reasonCode()).isEqualTo("MENTION_ALL_RESOLUTION_FAILED");
        assertThat(event.reasonMessage()).isEqualTo("无法获取完整群成员，消息未发送");
        assertThat(event.groupStatus()).isEqualTo("UNCONFIRMED");
        assertThat(event.groupStatusReason()).isEqualTo("STATUS_RESOLUTION_UNAVAILABLE");
    }

    @Test
    void onMessage_androidUnknownSendResult_preservesGroupCreationReference() {
        String raw = """
                {
                  "eventId":"acc_android_1:message.send_result_reported:cmd_unknown",
                  "event":"message.send_result_reported",
                  "version":"v1",
                  "accountId":"acc_android_1",
                  "occurredAt":"2026-07-15T10:00:00Z",
                  "workerId":"whatsapp-server-feature-android-zhuan",
                  "data":{
                    "tenantId":1,
                    "accountId":2,
                    "groupCreationTaskId":22,
                    "groupCreationItemId":11,
                    "protocolAccountId":"acc_android_1",
                    "groupJid":"120363001@g.us",
                    "commandId":"cmd_unknown",
                    "success":false,
                    "reasonCode":"SEND_RESULT_UNKNOWN",
                    "reasonMessage":"发送进程中断，无法确认 WhatsApp 是否已接收；为避免重复触达不自动重发",
                    "timestamp":1784109600000,
                    "source":"group_creation_marketing",
                    "groupStatus":"UNCONFIRMED",
                    "groupStatusReason":"STATUS_RESOLUTION_UNAVAILABLE",
                    "groupStatusCheckedAt":1784109600000
                  }
                }
                """;

        consumer.onMessage(raw);

        ArgumentCaptor<ProtocolMessageSendResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolMessageSendResultReportedEvent.class);
        verify(sink).handleSendResultReported(captor.capture());
        ProtocolMessageSendResultReportedEvent event = captor.getValue();
        assertThat(event.success()).isFalse();
        assertThat(event.reasonCode()).isEqualTo("SEND_RESULT_UNKNOWN");
        assertThat(event.groupCreationTaskId()).isEqualTo(22L);
        assertThat(event.groupCreationItemId()).isEqualTo(11L);
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
