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

    @Mock
    private ProtocolGroupJoinResultReportedSink joinResultSink;

    @Mock
    private ProtocolGroupActionResultReportedSink actionResultSink;

    @Mock
    private ProtocolPullTaskBatchParticipantResultReportedSink batchParticipantResultSink;

    private ProtocolGroupEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProtocolGroupEventConsumer(
                new ObjectMapper(), sink, joinResultSink, actionResultSink,
                batchParticipantResultSink);
    }

    @Test
    void onMessage_joinResultEnvelopeDispatchesNumericAndStringIds() {
        String raw = """
                {
                  "eventId": "acc-1:group.join_result_reported:cmd-1",
                  "event": "group.join_result_reported",
                  "accountId": "acc-1",
                  "workerId": "worker-a",
                  "data": {
                    "tenantId": "1",
                    "joinTaskId": 9,
                    "joinTaskResultId": "26",
                    "accountId": 382,
                    "protocolAccountId": "acc-1",
                    "commandId": "cmd-1",
                    "attemptNo": "2",
                    "outcome": "FAILED",
                    "groupJid": null,
                    "reasonCode": "TEMPORARY_FAILURE",
                    "reasonMessage": "temporary",
                    "retryable": true,
                    "timestamp": 1782712801000
                  }
                }
                """;

        consumer.onMessage(raw);

        ArgumentCaptor<ProtocolGroupJoinResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupJoinResultReportedEvent.class);
        verify(joinResultSink).handleJoinResultReported(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ProtocolGroupJoinResultReportedEvent(
                "acc-1:group.join_result_reported:cmd-1", 1L,
                new ProtocolJoinTaskGroupJoinCorrelation(9L, 26L), 382L,
                "acc-1", "cmd-1", 2, "FAILED", null,
                "TEMPORARY_FAILURE", "temporary", true, 1782712801000L, "worker-a"));
    }

    @Test
    void onMessage_pullTaskJoinResultDispatchesExplicitCorrelation() {
        String raw = """
                {
                  "eventId": "acc-1:group.join_result_reported:cmd-pull-1",
                  "event": "group.join_result_reported",
                  "accountId": "acc-1",
                  "workerId": "worker-a",
                  "data": {
                    "tenantId": 1,
                    "pullTaskId": 100,
                    "groupExecutionId": 11,
                    "actionId": 601,
                    "source": "pull_task_manager_join",
                    "accountId": 382,
                    "protocolAccountId": "acc-1",
                    "commandId": "cmd-pull-1",
                    "attemptNo": 1,
                    "outcome": "JOINED",
                    "groupJid": "120363group@g.us",
                    "reasonCode": "",
                    "reasonMessage": "",
                    "retryable": false,
                    "timestamp": 1782712801000
                  }
                }
                """;

        consumer.onMessage(raw);

        ArgumentCaptor<ProtocolGroupJoinResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupJoinResultReportedEvent.class);
        verify(joinResultSink).handleJoinResultReported(captor.capture());
        assertThat(captor.getValue().correlation()).isEqualTo(
                new ProtocolPullTaskGroupJoinCorrelation(100L, 11L, 601L));
        assertThat(captor.getValue().groupJid()).isEqualTo("120363group@g.us");
        assertThat(captor.getValue().commandId()).isEqualTo("cmd-pull-1");
    }

    @Test
    void onMessage_contactSaveResultDispatchesStrongCorrelation() {
        String raw = """
                {
                  "eventId": "manager-901:group.action_result_reported:cmd-contact-1",
                  "event": "group.action_result_reported",
                  "accountId": "manager-901",
                  "workerId": "worker-a",
                  "data": {
                    "tenantId": 7,
                    "pullTaskId": 100,
                    "groupExecutionId": 11,
                    "actionId": 601,
                    "source": "pull_task_contact_save",
                    "operation": "CONTACT_SAVE",
                    "accountId": 901,
                    "protocolAccountId": "manager-901",
                    "commandId": "cmd-contact-1",
                    "attemptNo": 1,
                    "outcome": "SUCCESS",
                    "reasonCode": "",
                    "reasonMessage": "",
                    "retryable": false,
                    "timestamp": 1782712801000
                  }
                }
                """;

        consumer.onMessage(raw);

        ArgumentCaptor<ProtocolGroupActionResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupActionResultReportedEvent.class);
        verify(actionResultSink).handleActionResultReported(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ProtocolGroupActionResultReportedEvent(
                "manager-901:group.action_result_reported:cmd-contact-1",
                7L, 100L, 11L, 601L, "pull_task_contact_save", "CONTACT_SAVE",
                901L, "manager-901", "cmd-contact-1", 1, "SUCCESS",
                null, "", "", false, 1782712801000L, "worker-a"));
    }

    @Test
    void onMessage_contactSaveUnknownResultIsDispatchedAsIndependentTerminalFact() {
        String raw = """
                {
                  "eventId":"manager-901:group.action_result_reported:cmd-contact-1",
                  "event":"group.action_result_reported",
                  "accountId":"manager-901",
                  "workerId":"worker-a",
                  "data":{
                    "tenantId":7,"pullTaskId":100,"groupExecutionId":11,"actionId":601,
                    "source":"pull_task_contact_save","operation":"CONTACT_SAVE",
                    "accountId":901,"protocolAccountId":"manager-901",
                    "commandId":"cmd-contact-1","attemptNo":1,
                    "outcome":"UNKNOWN","reasonCode":"ACCOUNT_BUSY",
                    "reasonMessage":"busy","retryable":true,"timestamp":5000
                  }
                }
                """;

        consumer.onMessage(raw);

        ArgumentCaptor<ProtocolGroupActionResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupActionResultReportedEvent.class);
        verify(actionResultSink).handleActionResultReported(captor.capture());
        assertThat(captor.getValue().outcome()).isEqualTo("UNKNOWN");
        assertThat(captor.getValue().reasonCode()).isEqualTo("ACCOUNT_BUSY");
    }

    @Test
    void onMessage_pullerInviteUnknownResultDispatchesTargetJid() {
        String raw = """
                {
                  "eventId":"manager-901:group.action_result_reported:cmd-invite-1",
                  "event":"group.action_result_reported",
                  "accountId":"manager-901",
                  "workerId":"worker-a",
                  "data":{
                    "tenantId":7,"pullTaskId":100,"groupExecutionId":11,"actionId":701,
                    "source":"pull_task_puller_invite","operation":"PARTICIPANT_ADD",
                    "accountId":901,"protocolAccountId":"manager-901",
                    "commandId":"cmd-invite-1","attemptNo":1,
                    "targetJid":"8613800000902@s.whatsapp.net",
                    "outcome":"UNKNOWN","reasonCode":"PARTICIPANT_ADD_TIMEOUT",
                    "reasonMessage":"timed out","retryable":true,"timestamp":5000
                  }
                }
                """;

        consumer.onMessage(raw);

        ArgumentCaptor<ProtocolGroupActionResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupActionResultReportedEvent.class);
        verify(actionResultSink).handleActionResultReported(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ProtocolGroupActionResultReportedEvent(
                "manager-901:group.action_result_reported:cmd-invite-1",
                7L, 100L, 11L, 701L, "pull_task_puller_invite", "PARTICIPANT_ADD",
                901L, "manager-901", "cmd-invite-1", 1, "UNKNOWN",
                "8613800000902@s.whatsapp.net", "PARTICIPANT_ADD_TIMEOUT", "timed out",
                true, 5_000L, "worker-a"));
    }

    @Test
    void onMessage_materialAdminResultDispatchesStrongCorrelation() {
        String raw = """
                {
                  "eventId":"manager-901:group.action_result_reported:cmd-admin-1",
                  "event":"group.action_result_reported",
                  "accountId":"manager-901",
                  "workerId":"worker-a",
                  "data":{
                    "tenantId":7,"pullTaskId":100,"groupExecutionId":11,"actionId":601,
                    "source":"pull_task_material_admin","operation":"PARTICIPANT_PROMOTE",
                    "accountId":901,"protocolAccountId":"manager-901",
                    "commandId":"cmd-admin-1","attemptNo":1,
                    "targetJid":"8613900000001@s.whatsapp.net",
                    "outcome":"SUCCESS","reasonCode":"",
                    "reasonMessage":"","retryable":false,"timestamp":5000
                  }
                }
                """;

        consumer.onMessage(raw);

        ArgumentCaptor<ProtocolGroupActionResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupActionResultReportedEvent.class);
        verify(actionResultSink).handleActionResultReported(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ProtocolGroupActionResultReportedEvent(
                "manager-901:group.action_result_reported:cmd-admin-1",
                7L, 100L, 11L, 601L, "pull_task_material_admin", "PARTICIPANT_PROMOTE",
                901L, "manager-901", "cmd-admin-1", 1, "SUCCESS",
                "8613900000001@s.whatsapp.net", "", "", false, 5_000L, "worker-a"));
    }

    @Test
    void onMessage_batchAddResultDispatchesPerParticipantCorrelation() {
        String raw = """
                {
                  "eventId":"puller-902:group.action_result_reported:cmd-batch-1:8613800000903_s_whatsapp_net",
                  "event":"group.action_result_reported",
                  "accountId":"puller-902",
                  "workerId":"worker-a",
                  "data":{
                    "tenantId":7,"pullTaskId":100,"groupExecutionId":11,"pullCallId":801,
                    "source":"pull_task_batch_add","operation":"PARTICIPANT_ADD",
                    "accountId":902,"protocolAccountId":"puller-902",
                    "commandId":"cmd-batch-1","attemptNo":1,
                    "targetJid":"8613800000903@s.whatsapp.net",
                    "outcome":"UNKNOWN","reasonCode":"PARTICIPANT_ADD_TIMEOUT",
                    "reasonMessage":"timed out","retryable":true,"timestamp":5000
                  }
                }
                """;

        consumer.onMessage(raw);

        ArgumentCaptor<ProtocolPullTaskBatchParticipantResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolPullTaskBatchParticipantResultReportedEvent.class);
        verify(batchParticipantResultSink).handleBatchParticipantResultReported(captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                new ProtocolPullTaskBatchParticipantResultReportedEvent(
                        "puller-902:group.action_result_reported:cmd-batch-1:8613800000903_s_whatsapp_net",
                        7L, 100L, 11L, 801L, 902L, "puller-902", "cmd-batch-1", 1,
                        "8613800000903@s.whatsapp.net", "UNKNOWN",
                        "PARTICIPANT_ADD_TIMEOUT", "timed out", true, 5_000L, "worker-a"));
        verifyNoInteractions(actionResultSink);
    }

    @Test
    void onMessage_contactSaveResultRejectsWrongSourceOperationOrEnvelopeAccount() {
        String wrongOperation = """
                {"event":"group.action_result_reported","accountId":"manager-901","data":{
                  "tenantId":7,"pullTaskId":100,"groupExecutionId":11,"actionId":601,
                  "source":"pull_task_contact_save","operation":"PARTICIPANT_ADD",
                  "accountId":901,"protocolAccountId":"manager-901","commandId":"cmd-1",
                  "attemptNo":1,"outcome":"FAILED","retryable":false
                }}
                """;
        String wrongAccount = """
                {"event":"group.action_result_reported","accountId":"other-account","data":{
                  "tenantId":7,"pullTaskId":100,"groupExecutionId":11,"actionId":601,
                  "source":"pull_task_contact_save","operation":"CONTACT_SAVE",
                  "accountId":901,"protocolAccountId":"manager-901","commandId":"cmd-1",
                  "attemptNo":1,"outcome":"FAILED","retryable":false
                }}
                """;

        assertThatThrownBy(() -> consumer.onMessage(wrongOperation))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> consumer.onMessage(wrongAccount))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(actionResultSink);
    }

    @Test
    void onMessage_joinResultMissingCorrelationOrInvalidOutcomeIsRejected() {
        String missingCommand = """
                {"event":"group.join_result_reported","data":{
                  "tenantId":1,"joinTaskId":9,"joinTaskResultId":26,"accountId":382,
                  "protocolAccountId":"acc-1","attemptNo":1,"outcome":"JOINED","retryable":false
                }}
                """;
        String invalidOutcome = """
                {"event":"group.join_result_reported","data":{
                  "tenantId":1,"joinTaskId":9,"joinTaskResultId":26,"accountId":382,
                  "protocolAccountId":"acc-1","commandId":"cmd-1","attemptNo":1,
                  "outcome":"MAYBE","retryable":false
                }}
                """;

        assertThatThrownBy(() -> consumer.onMessage(missingCommand)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> consumer.onMessage(invalidOutcome)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(joinResultSink);
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

        verifyNoInteractions(sink, joinResultSink, actionResultSink);
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
