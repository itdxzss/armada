package com.armada.platform.kafka.consumer.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.trace.TraceContext;
import com.armada.platform.protocol.risk.ProtocolRiskEventSink;
import com.armada.platform.protocol.risk.ProtocolRiskResultMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProtocolNormalGroupCreationEventConsumerTest {

    private static final String FIXED_TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Mock
    private ProtocolNormalGroupCreationResultReportedSink resultSink;
    @Mock
    private ProtocolRiskEventSink riskEventSink;

    private ProtocolNormalGroupCreationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProtocolNormalGroupCreationEventConsumer(
                new ObjectMapper(), resultSink, riskEventSink);
    }

    private void onMessage(String rawMessage) {
        onMessage(rawMessage, null);
    }

    private void onMessage(String rawMessage, String headerTraceId) {
        consumer.onMessage(rawMessage, headerTraceId);
    }

    @Test
    void onMessage_forwardsRiskMetadataWithTaskItemCommandAndGroup() {
        onMessage("""
                {"eventId":"evt-normal-risk","event":"group.action_result_reported",
                 "accountId":"acc-android-1","workerId":"android-worker-1",
                 "data":{"tenantId":7,"taskId":100,"itemId":200,
                 "source":"normal_group_creation","operation":"GROUP_CREATE",
                 "accountId":901,"protocolAccountId":"acc-android-1",
                 "protocolBackend":"ANDROID","commandId":"cmd-normal-risk","attemptNo":1,
                 "outcome":"FAILED","groupJid":"120363normal@g.us",
                 "reasonCode":"CHAT_SUSPENDED","rawCode":403,
                 "reasonMessage":"chat suspended","retryable":false,"timestamp":5000}}
                """);

        ArgumentCaptor<ProtocolRiskResultMetadata> captor =
                ArgumentCaptor.forClass(ProtocolRiskResultMetadata.class);
        verify(riskEventSink).handleResult(captor.capture());
        ProtocolRiskResultMetadata metadata = captor.getValue();
        assertThat(metadata.reasonCode()).isEqualTo("CHAT_SUSPENDED");
        assertThat(metadata.correlation().businessId()).isEqualTo(100L);
        assertThat(metadata.correlation().businessItemId()).isEqualTo(200L);
        assertThat(metadata.correlation().groupBusinessId()).isEqualTo(200L);
        assertThat(metadata.correlation().commandId()).isEqualTo("cmd-normal-risk");
        assertThat(metadata.correlation().groupJid()).isEqualTo("120363normal@g.us");
        assertThat(metadata.correlation().rawCode()).isEqualTo("403");
    }

    @Test
    void onMessage_validAndroidResultDispatchesProtocolSpecificActor() {
        doAnswer(invocation -> {
            assertThat(TraceContext.current()).contains(FIXED_TRACE_ID);
            return null;
        }).when(resultSink).handleNormalGroupCreationResult(any());
        String raw = """
                {
                  "traceId":"0123456789abcdef0123456789abcdef",
                  "eventId":"acc-android-1:group.action_result_reported:cmd-normal-1",
                  "event":"group.action_result_reported",
                  "accountId":"acc-android-1",
                  "workerId":"android-worker-1",
                  "data":{
                    "tenantId":7,"taskId":100,"itemId":200,
                    "source":"normal_group_creation","operation":"GROUP_CREATE",
                    "accountId":901,"protocolAccountId":"acc-android-1",
                    "protocolBackend":"ANDROID","commandId":"cmd-normal-1","attemptNo":1,
                    "outcome":"SUCCESS","groupJid":"120363normal@g.us",
                    "reasonCode":"","reasonMessage":"","retryable":false,"timestamp":5000
                  }
                }
                """;

        onMessage(raw);

        ArgumentCaptor<ProtocolNormalGroupCreationResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolNormalGroupCreationResultReportedEvent.class);
        verify(resultSink).handleNormalGroupCreationResult(captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                new ProtocolNormalGroupCreationResultReportedEvent(
                        "acc-android-1:group.action_result_reported:cmd-normal-1",
                        7L, 100L, 200L, null, null, "GROUP_CREATE",
                        901L, "acc-android-1", "ANDROID", "cmd-normal-1", 1,
                        "SUCCESS", "120363normal@g.us", "", "", false,
                        5_000L, "android-worker-1"));
        assertThat(TraceContext.current()).isEmpty();
    }

    @Test
    void onMessage_wrongTopicContractEnvelopeSourceOrBackendIsRejected() {
        String wrongEvent = """
                {"event":"group.join_result_reported","accountId":"acc-web-1","data":{}}
                """;
        String wrongSource = """
                {"event":"group.action_result_reported","accountId":"acc-web-1","data":{
                  "source":"pull_task_contact_save"
                }}
                """;
        String wrongEnvelope = """
                {"event":"group.action_result_reported","accountId":"other","data":{
                  "tenantId":7,"taskId":100,"itemId":200,
                  "source":"normal_group_creation","operation":"GROUP_CREATE",
                  "accountId":901,"protocolAccountId":"acc-web-1","protocolBackend":"WEB",
                  "commandId":"cmd-1","attemptNo":1,"outcome":"SUCCESS",
                  "groupJid":"120363normal@g.us","retryable":false
                }}
                """;
        String wrongBackend = """
                {"event":"group.action_result_reported","accountId":"acc-web-1","data":{
                  "tenantId":7,"taskId":100,"itemId":200,
                  "source":"normal_group_creation","operation":"GROUP_CREATE",
                  "accountId":901,"protocolAccountId":"acc-web-1","protocolBackend":"DESKTOP",
                  "commandId":"cmd-1","attemptNo":1,"outcome":"SUCCESS",
                  "groupJid":"120363normal@g.us","retryable":false
                }}
                """;

        assertThatThrownBy(() -> onMessage(wrongEvent))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onMessage(wrongSource))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onMessage(wrongEnvelope))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onMessage(wrongBackend))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(resultSink);
    }

    @Test
    void onMessage_nonCreateActionMustNotCarryGroupJid() {
        String raw = """
                {"event":"group.action_result_reported","accountId":"acc-web-1","data":{
                  "tenantId":7,"taskId":100,"itemId":200,
                  "source":"normal_group_creation","operation":"GROUP_SETTINGS_APPLY",
                  "accountId":901,"protocolAccountId":"acc-web-1","protocolBackend":"WEB",
                  "commandId":"cmd-1","attemptNo":1,"outcome":"SUCCESS",
                  "groupJid":"120363normal@g.us","retryable":false
                }}
                """;

        assertThatThrownBy(() -> onMessage(raw))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(resultSink);
    }
}
