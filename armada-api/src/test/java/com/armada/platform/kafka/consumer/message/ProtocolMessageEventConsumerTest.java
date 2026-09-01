package com.armada.platform.kafka.consumer.message;

import com.armada.shared.trace.TraceContext;
import com.armada.shared.exception.BusinessException;
import com.armada.platform.protocol.risk.ProtocolRiskEventSink;
import com.armada.platform.protocol.risk.ProtocolRiskResultMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProtocolMessageEventConsumerTest {

    private static final String FIXED_TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Mock
    private ProtocolMessageSendResultReportedSink sink;

    @Mock
    private ProtocolRiskEventSink riskEventSink;
    @Mock
    private ProtocolMessageAckSink ackSink;

    private ProtocolMessageEventConsumer consumer;

    @BeforeEach
    void setUp() {
        // 个别路由所有权测试会构造独立 sink 列表，默认 sink 在这些用例中不会被访问。
        lenient().when(sink.supports(any())).thenReturn(true);
        lenient().when(ackSink.supports(any())).thenReturn(true);
        consumer = new ProtocolMessageEventConsumer(
                new ObjectMapper(), java.util.List.of(sink), java.util.List.of(ackSink),
                riskEventSink);
    }

    @Test
    void onMessage_forwardsSemanticRiskCodeBeforeBusinessSink() {
        onMessage("""
                {"eventId":"evt-risk-message","event":"message.send_result_reported",
                 "accountId":"acc_17","occurredAt":"2026-09-01T10:00:00Z",
                 "data":{"tenantId":7,"accountId":17,"protocolAccountId":"acc_17",
                 "protocolBackend":"WEB",
                 "commandId":"hl:7:8:9","success":false,
                 "reasonCode":"RATE_LIMITED","reasonMessage":"slow down","rawCode":429,
                 "timestamp":1788256800000,"source":"hyperlink_task",
                 "jid":"15550001@s.whatsapp.net","targetKind":"PRIVATE",
                 "hyperlinkTaskId":8,"hyperlinkRecipientId":9,
                 "outcome":"FAILED","terminal":true}}
                """);

        ArgumentCaptor<ProtocolRiskResultMetadata> captor =
                ArgumentCaptor.forClass(ProtocolRiskResultMetadata.class);
        verify(riskEventSink).handleResult(captor.capture());
        assertThat(captor.getValue().reasonCode()).isEqualTo("RATE_LIMITED");
        assertThat(captor.getValue().account().accountId()).isEqualTo(17L);
        assertThat(captor.getValue().account().protocolBackend()).isEqualTo("WEB");
        assertThat(captor.getValue().correlation().rawCode()).isEqualTo("429");
        assertThat(captor.getValue().correlation().businessId()).isEqualTo(8L);
        assertThat(captor.getValue().correlation().targetKind()).isEqualTo("PRIVATE");
    }

    @Test
    void onMessage_ackAlsoForwardsRiskMetadata() {
        onMessage("""
                {"eventId":"evt-risk-ack","event":"message.ack","accountId":"acc_17",
                 "data":{"tenantId":7,"accountId":17,"protocolAccountId":"acc_17",
                 "protocolBackend":"ANDROID","source":"hyperlink_task",
                 "hyperlinkTaskId":8,"hyperlinkRecipientId":9,"commandId":"hl:7:8:9",
                 "jid":"15550001@s.whatsapp.net","targetKind":"PRIVATE","messageId":"m-1",
                 "ackStatus":"FAILED","success":false,
                 "reasonCode":"ACCOUNT_REACHOUT_RESTRICTED","rawCode":463,
                 "reasonMessage":"restricted","timestamp":1788256800000}}
                """);

        ArgumentCaptor<ProtocolRiskResultMetadata> captor =
                ArgumentCaptor.forClass(ProtocolRiskResultMetadata.class);
        verify(riskEventSink).handleResult(captor.capture());
        assertThat(captor.getValue().event().source()).isEqualTo("message.ack");
        assertThat(captor.getValue().reasonCode()).isEqualTo("ACCOUNT_REACHOUT_RESTRICTED");
        assertThat(captor.getValue().correlation().rawCode()).isEqualTo("463");
        verify(ackSink).handleAck(any());
    }

    @Test
    void onMessage_unknownEventWithRiskSignalIsRejectedInsteadOfSkipped() {
        assertThatThrownBy(() -> onMessage("""
                {"eventId":"evt-unknown-risk","event":"message.future_result",
                 "data":{"signalCode":"RATE_LIMITED"}}
                """))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("携带风控信号");
        verifyNoInteractions(riskEventSink);
    }

    private void onMessage(String rawMessage) {
        onMessage(rawMessage, null);
    }

    private void onMessage(String rawMessage, String headerTraceId) {
        consumer.onMessage(rawMessage, headerTraceId);
    }

    @Test
    void onMessage_sendResultEnvelope_dispatchesParsedEvent() {
        doAnswer(invocation -> {
            assertThat(TraceContext.current()).contains(FIXED_TRACE_ID);
            return null;
        }).when(sink).handleSendResultReported(any());
        String raw = """
                {
                  "traceId":"0123456789abcdef0123456789abcdef",
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

        onMessage(raw);

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
        assertThat(TraceContext.current()).isEmpty();
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

        onMessage(raw);

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

        onMessage(raw);

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

        onMessage(raw);

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
    void onMessage_historicalGroupPullDispatchesExecutionMemberReferenceToOnlySupportingSink() {
        ProtocolMessageSendResultReportedSink marketingSink = mock(ProtocolMessageSendResultReportedSink.class);
        ProtocolMessageSendResultReportedSink historicalSink = mock(ProtocolMessageSendResultReportedSink.class);
        when(marketingSink.supports(any())).thenReturn(false);
        when(historicalSink.supports(any())).thenReturn(true);
        ProtocolMessageEventConsumer historicalConsumer = new ProtocolMessageEventConsumer(
                new ObjectMapper(), java.util.List.of(marketingSink, historicalSink),
                java.util.List.of(), riskEventSink);
        String raw = """
                {
                  "eventId":"evt_historical_1",
                  "event":"message.send_result_reported",
                  "accountId":"acc_marketing_1",
                  "workerId":"worker-a",
                  "data":{
                    "tenantId":1,
                    "historicalExecutionId":91,
                    "historicalMemberId":301,
                    "protocolAccountId":"acc_marketing_1",
                    "groupJid":"120363history@g.us",
                    "commandId":"cmd_historical_91_301",
                    "success":false,
                    "reasonCode":"SEND_FAILED",
                    "reasonMessage":"administrator permission denied",
                    "timestamp":1783159200000,
                    "source":"historical_group_pull",
                    "groupStatus":"UNCONFIRMED",
                    "groupStatusReason":"PRECHECK_SKIPPED_BY_SOURCE",
                    "groupStatusCheckedAt":1783159199000
                  }
                }
                """;

        historicalConsumer.onMessage(raw, null);

        ArgumentCaptor<ProtocolMessageSendResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolMessageSendResultReportedEvent.class);
        verify(historicalSink).handleSendResultReported(captor.capture());
        verify(marketingSink, org.mockito.Mockito.never()).handleSendResultReported(any());
        ProtocolMessageSendResultReportedEvent event = captor.getValue();
        assertThat(event.historicalExecutionId()).isEqualTo(91L);
        assertThat(event.historicalMemberId()).isEqualTo(301L);
        assertThat(event.marketingTaskId()).isNull();
        assertThat(event.groupCreationTaskId()).isNull();
        assertThat(event.reasonMessage()).isEqualTo("administrator permission denied");
        assertThat(event.groupStatusReason()).isEqualTo("PRECHECK_SKIPPED_BY_SOURCE");
    }

    @Test
    void onMessage_rejectsAmbiguousSinkOwnership() {
        ProtocolMessageSendResultReportedSink first = mock(ProtocolMessageSendResultReportedSink.class);
        ProtocolMessageSendResultReportedSink second = mock(ProtocolMessageSendResultReportedSink.class);
        when(first.supports(any())).thenReturn(true);
        when(second.supports(any())).thenReturn(true);
        ProtocolMessageEventConsumer ambiguousConsumer = new ProtocolMessageEventConsumer(
                new ObjectMapper(), java.util.List.of(first, second),
                java.util.List.of(), riskEventSink);
        String raw = """
                {
                  "eventId":"evt_ambiguous",
                  "event":"message.send_result_reported",
                  "data":{
                    "tenantId":1,
                    "marketingTaskId":42,
                    "targetId":501,
                    "attemptId":9001,
                    "roundNo":1,
                    "protocolAccountId":"acc_1",
                    "groupJid":"120363001@g.us",
                    "success":true
                  }
                }
                """;

        assertThatThrownBy(() -> ambiguousConsumer.onMessage(raw, null))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("唯一处理器");
        verify(first, org.mockito.Mockito.never()).handleSendResultReported(any());
        verify(second, org.mockito.Mockito.never()).handleSendResultReported(any());
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

        onMessage(raw);

        verifyNoInteractions(sink);
    }

    @Test
    void onMessage_contactTaskEnvelope_parsesCorrelationWithoutMarketingFields() {
        // contact_task 事件没有 marketingTaskId/targetId/attemptId，不能因此判非法
        String raw = """
                {
                  "eventId":"evt_contact",
                  "event":"message.send_result_reported",
                  "version":"v1",
                  "accountId":"acc_1",
                  "occurredAt":"2026-08-29T10:00:00.000Z",
                  "workerId":"worker-a",
                  "data":{
                    "tenantId":5,
                    "source":"contact_task",
                    "contactTaskId":1,
                    "taskAccountId":101,
                    "recipientId":999,
                    "roundNo":7,
                    "protocolAccountId":"acc_1",
                    "groupJid":"8613900000001@s.whatsapp.net",
                    "commandId":"cmd_1",
                    "success":true,
                    "messageId":"wamid.ABC",
                    "timestamp":1999
                  }
                }
                """;

        onMessage(raw);

        ArgumentCaptor<ProtocolMessageSendResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolMessageSendResultReportedEvent.class);
        verify(sink).handleSendResultReported(captor.capture());
        ProtocolMessageSendResultReportedEvent event = captor.getValue();
        assertThat(event.contactTaskId()).isEqualTo(1L);
        assertThat(event.taskAccountId()).isEqualTo(101L);
        assertThat(event.recipientId()).isEqualTo(999L);
        assertThat(event.roundNo()).isEqualTo(7L);
        assertThat(event.marketingTaskId()).isNull();
        assertThat(event.targetId()).isNull();
        assertThat(event.attemptId()).isNull();
    }

    @Test
    void onMessage_contactTaskEnvelopeMissingRecipientId_isRejected() {
        // 四字段是硬契约，缺一就没法定位回写目标，必须失败重投而不是静默丢弃
        String raw = """
                {
                  "eventId":"evt_contact_bad",
                  "event":"message.send_result_reported",
                  "version":"v1",
                  "accountId":"acc_1",
                  "occurredAt":"2026-08-29T10:00:00.000Z",
                  "workerId":"worker-a",
                  "data":{
                    "tenantId":5,
                    "source":"contact_task",
                    "contactTaskId":1,
                    "taskAccountId":101,
                    "roundNo":7,
                    "protocolAccountId":"acc_1",
                    "groupJid":"8613900000001@s.whatsapp.net",
                    "commandId":"cmd_1",
                    "success":true,
                    "timestamp":1999
                  }
                }
                """;

        assertThatThrownBy(() -> onMessage(raw))
                .hasMessageContaining("recipientId");
    }
}
