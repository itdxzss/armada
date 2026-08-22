package com.armada.platform.kafka.consumer.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.trace.TraceContext;
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

    private static final String FIXED_TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Mock
    private ProtocolGroupHealthReportedSink sink;

    @Mock
    private ProtocolGroupJoinResultReportedSink joinResultSink;

    @Mock
    private ProtocolGroupActionResultReportedSink actionResultSink;

    @Mock
    private ProtocolPullTaskBatchParticipantResultReportedSink batchParticipantResultSink;

    @Mock
    private ProtocolGroupMembersResultReportedSink membersResultSink;

    @Mock
    private ProtocolGroupInviteLinkChangedSink inviteLinkChangedSink;

    @Mock
    private ProtocolGroupParticipantChangedSink participantChangedSink;

    @Mock
    private ProtocolGroupMetadataUpdatedSink metadataUpdatedSink;
    @Mock
    private ProtocolGroupProfileReportedSink profileReportedSink;
    @Mock
    private ProtocolGroupSnapshotResultReportedSink snapshotResultReportedSink;

    private ProtocolGroupEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProtocolGroupEventConsumer(
                new ObjectMapper(), sink, joinResultSink, actionResultSink,
                batchParticipantResultSink, membersResultSink, inviteLinkChangedSink,
                participantChangedSink, metadataUpdatedSink, profileReportedSink,
                snapshotResultReportedSink);
    }

    private void onMessage(String rawMessage) {
        onMessage(rawMessage, null);
    }

    private void onMessage(String rawMessage, String headerTraceId) {
        consumer.onMessage(rawMessage, headerTraceId);
    }

    @Test
    void onMessage_snapshotResultDispatchesStrictScopeSettlement() {
        onMessage("""
                {
                  "eventId":"acc-901:group.snapshot_result_reported:cmd-1",
                  "event":"group.snapshot_result_reported","version":"v1",
                  "accountId":"acc-901","occurredAt":"2026-08-18T04:30:02Z",
                  "workerId":"android-worker",
                  "data":{"commandId":"cmd-1","tenantId":7,"accountId":901,
                    "protocolAccountId":"acc-901","protocolBackend":"ANDROID",
                    "groupLinkId":5001,"groupJid":"120363000@g.us",
                    "taskType":"GROUP_METADATA_SYNC","taskId":9001,"attemptNo":1,
                    "scopes":{"METADATA":{"outcome":"SUCCESS","completedAt":1786854600000},
                      "INVITE_CODE":{"outcome":"FAILED","completedAt":1786854600100,
                        "errorCode":"GROUP_PERMISSION_DENIED"}}}
                }
                """);

        verify(snapshotResultReportedSink).handleSnapshotResult(
                org.mockito.ArgumentMatchers.argThat(event ->
                        event.commandId().equals("cmd-1")
                                && event.scopes().size() == 2
                                && event.scopes().get("INVITE_CODE").errorCode()
                                .equals("GROUP_PERMISSION_DENIED")));
    }

    @Test
    void onMessage_invalidPayloadSettlementAllowsRequestedUnknownScopeAndBadGroupJid() {
        onMessage("""
                {
                  "eventId":"acc-901:group.snapshot_result_reported:cmd-invalid",
                  "event":"group.snapshot_result_reported","version":"v1",
                  "accountId":"acc-901","occurredAt":"2026-08-18T04:30:02Z",
                  "workerId":"worker-1",
                  "data":{"commandId":"cmd-invalid","tenantId":7,"accountId":901,
                    "protocolAccountId":"acc-901","protocolBackend":"WEB",
                    "groupLinkId":5001,"groupJid":"bad-jid",
                    "taskType":"GROUP_METADATA_SYNC","taskId":9001,"attemptNo":1,
                    "scopes":{"UNKNOWN":{"outcome":"FAILED","completedAt":1786854600000,
                      "errorCode":"INVALID_PAYLOAD"}}}
                }
                """);

        verify(snapshotResultReportedSink).handleSnapshotResult(
                org.mockito.ArgumentMatchers.argThat(event ->
                        event.commandId().equals("cmd-invalid")
                                && event.groupJid().equals("bad-jid")
                                && event.scopes().get("UNKNOWN").errorCode()
                                .equals("INVALID_PAYLOAD")));
    }

    @Test
    void onMessage_runsSinkInsideEnvelopeTraceAndCleansScope() {
        doAnswer(invocation -> {
            assertThat(TraceContext.current()).contains(FIXED_TRACE_ID);
            return null;
        }).when(sink).handleHealthReported(any());

        onMessage("""
                {"traceId":"0123456789abcdef0123456789abcdef","eventId":"evt-group-trace-1",
                 "event":"group.health_reported","accountId":"acc_trace",
                 "occurredAt":"2026-08-11T00:00:00Z","workerId":"worker-1",
                 "data":{"tenantId":1,"groupLinkId":200,"groupJid":"1203630trace@g.us",
                         "health":"HEALTHY"}}
                """, "11111111111111111111111111111111");

        assertThat(TraceContext.current()).isEmpty();
    }

    @Test
    void onMessage_cleansScopeWhenSinkFails() {
        doAnswer(invocation -> {
            assertThat(TraceContext.current()).contains(FIXED_TRACE_ID);
            throw new IllegalStateException("database unavailable");
        }).when(sink).handleHealthReported(any());

        assertThatThrownBy(() -> onMessage("""
                {"traceId":"0123456789abcdef0123456789abcdef","eventId":"evt-group-trace-2",
                 "event":"group.health_reported","accountId":"acc_trace",
                 "occurredAt":"2026-08-11T00:00:00Z","workerId":"worker-1",
                 "data":{"tenantId":1,"groupLinkId":200,"groupJid":"1203630trace@g.us",
                         "health":"HEALTHY"}}
                """, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        assertThat(TraceContext.current()).isEmpty();
    }

    @Test
    void onMessage_inviteLinkChangedDispatchesCurrentCode() {
        onMessage("""
                {
                  "eventId":"acc-901:group.invite_link_changed:1",
                  "event":"group.invite_link_changed",
                  "accountId":"acc-901",
                  "occurredAt":"2026-08-10T06:00:00Z",
                  "workerId":"android-worker",
                  "data":{
                    "tenantId":7,"accountId":901,"protocolAccountId":"acc-901",
                    "protocolBackend":"ANDROID","groupJid":"120363group@g.us",
                    "inviteCode":"NewInviteCode_2026",
                    "author":"919000000002@s.whatsapp.net",
                    "source":"wgp2_notification"
                  }
                }
                """);

        verify(inviteLinkChangedSink).handleInviteLinkChanged(
                new ProtocolGroupInviteLinkChangedEvent(
                        "acc-901:group.invite_link_changed:1",
                        7L, 901L, "acc-901", "ANDROID",
                        "120363group@g.us", "NewInviteCode_2026",
                        "919000000002@s.whatsapp.net", "wgp2_notification",
                        1786341600000L, "android-worker", null));
    }

    @Test
    void onMessage_participantPromoteDispatchesCompleteRoleFact() {
        onMessage("""
                {
                  "eventId":"acc-901:group.participant_changed:promote-1",
                  "event":"group.participant_changed",
                  "accountId":"acc-901",
                  "occurredAt":"2026-08-10T06:00:00Z",
                  "workerId":"web-worker",
                  "data":{
                    "tenantId":7,"accountId":901,"protocolAccountId":"acc-901",
                    "protocolBackend":"WEB","groupJid":"120363group@g.us",
                    "action":"promote",
                    "participants":[{
                      "id":"123456789012345@lid",
                      "lid":"123456789012345@lid",
                      "phoneNumber":"919000000001@s.whatsapp.net"
                    }],
                    "operator":"919000000002@s.whatsapp.net",
                    "source":"wa_group_participants_update"
                  }
                }
                """);

        verify(participantChangedSink).handleParticipantChanged(
                new ProtocolGroupParticipantChangedEvent(
                        "acc-901:group.participant_changed:promote-1",
                        7L, 901L, "acc-901", "WEB", "120363group@g.us", "promote",
                        java.util.List.of(new ProtocolGroupParticipantIdentity(
                                "123456789012345@lid", "123456789012345@lid",
                                "919000000001@s.whatsapp.net")),
                        "919000000002@s.whatsapp.net", "wa_group_participants_update",
                        1786341600000L, "web-worker"));
    }

    @Test
    void onMessage_participantRoleRejectsInvalidBindingBackendGroupAndIdentity() {
        assertThatThrownBy(() -> onMessage(participantRoleJson(
                "other-account", "WEB", "120363group@g.us", "promote",
                "[{\"id\":\"919000000001@s.whatsapp.net\"}]")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onMessage(participantRoleJson(
                "acc-901", "DESKTOP", "120363group@g.us", "promote",
                "[{\"id\":\"919000000001@s.whatsapp.net\"}]")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onMessage(participantRoleJson(
                "acc-901", "ANDROID", "919000000001@s.whatsapp.net", "demote",
                "[{\"id\":\"919000000001@s.whatsapp.net\"}]")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onMessage(participantRoleJson(
                "acc-901", "ANDROID", "120363group@g.us", "demote", "[{}]")))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(participantChangedSink);
    }

    @Test
    void onMessage_participantRoleRejectsMoreThanFiveHundredIdentities() {
        String participants = "[" + java.util.stream.IntStream.range(0, 501)
                .mapToObj(index -> "{\"id\":\"919000%06d@s.whatsapp.net\"}".formatted(index))
                .collect(java.util.stream.Collectors.joining(",")) + "]";

        assertThatThrownBy(() -> onMessage(participantRoleJson(
                "acc-901", "ANDROID", "120363group@g.us", "promote", participants)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(participantChangedSink);
    }

    @Test
    void onMessage_participantAddAndRemoveDispatchCompleteMemberFact() {
        onMessage(participantRoleJson(
                "acc-901", "WEB", "120363group@g.us", "add",
                "[{\"id\":\"919000000001@s.whatsapp.net\"}]"));
        onMessage(participantRoleJson(
                "acc-901", "WEB", "120363group@g.us", "remove",
                "[{\"id\":\"919000000001@s.whatsapp.net\"}]"));

        ArgumentCaptor<ProtocolGroupParticipantChangedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupParticipantChangedEvent.class);
        verify(participantChangedSink, org.mockito.Mockito.times(2))
                .handleParticipantChanged(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ProtocolGroupParticipantChangedEvent::action)
                .containsExactly("add", "remove");
    }

    @Test
    void onMessage_participantModifyDispatchesIdentityChange() {
        onMessage(participantRoleJson(
                "acc-901", "WEB", "120363group@g.us", "modify",
                "[{\"id\":\"919000000001@s.whatsapp.net\"}]"));

        ArgumentCaptor<ProtocolGroupParticipantChangedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupParticipantChangedEvent.class);
        verify(participantChangedSink).handleParticipantChanged(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("modify");
    }

    private static String participantRoleJson(
            String envelopeAccountId,
            String backend,
            String groupJid,
            String action,
            String participants) {
        return """
                {"eventId":"role-1","event":"group.participant_changed",
                 "accountId":"%s","occurredAt":"2026-08-10T06:00:00Z","data":{
                   "tenantId":7,"accountId":901,"protocolAccountId":"acc-901",
                   "protocolBackend":"%s","groupJid":"%s","action":"%s",
                   "participants":%s,"source":"android_wgp2"}}
                """.formatted(envelopeAccountId, backend, groupJid, action, participants);
    }

    @Test
    void onMessage_memberQuerySuccessDispatchesStrictCorrelationAndFacts() {
        String raw = """
                {
                  "eventId":"manager-901:group.members.result_reported:cmd-query-1",
                  "event":"group.members.result_reported",
                  "accountId":"manager-901",
                  "workerId":"worker-a",
                  "data":{
                    "source":"pull_task_member_query",
                    "tenantId":7,"pullTaskId":100,"groupExecutionId":11,"queryId":701,
                    "purpose":"MANAGER_ADMIN_DISCOVERY",
                    "accountId":901,"protocolAccountId":"manager-901","protocolBackend":"WEB",
                    "commandId":"cmd-query-1","attemptNo":2,"outcome":"SUCCESS",
                    "groupJid":"120363group@g.us",
                    "members":[{
                      "targetJid":"8613800000902@s.whatsapp.net",
                      "participantJid":"8613800000902:5@s.whatsapp.net",
                      "phoneNumber":"8613800000902","inGroup":true,"admin":true
                    }],
                    "reasonCode":"","reasonMessage":"","retryable":false,
                    "timestamp":1782712801000
                  }
                }
                """;

        onMessage(raw);

        ArgumentCaptor<ProtocolGroupMembersResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupMembersResultReportedEvent.class);
        verify(membersResultSink).handleMembersResultReported(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ProtocolGroupMembersResultReportedEvent(
                "manager-901:group.members.result_reported:cmd-query-1",
                7L, 100L, 11L, 701L, "MANAGER_ADMIN_DISCOVERY",
                901L, "manager-901", "WEB", "cmd-query-1", 2, "SUCCESS",
                "120363group@g.us",
                java.util.List.of(new ProtocolGroupMemberFact(
                        "8613800000902@s.whatsapp.net",
                        "8613800000902:5@s.whatsapp.net", "8613800000902", true, true)),
                "", "", false, 1782712801000L, "worker-a"));
    }

    @Test
    void onMessage_memberQueryRejectsAccountMismatchAndMalformedFacts() {
        String accountMismatch = memberQueryResultJson(
                "other-account", "SUCCESS",
                "[{\"targetJid\":\"1@s.whatsapp.net\",\"inGroup\":false,\"admin\":false}]");
        String adminOutsideGroup = memberQueryResultJson(
                "manager-901", "SUCCESS",
                "[{\"targetJid\":\"1@s.whatsapp.net\",\"inGroup\":false,\"admin\":true}]");
        String failedWithMembers = memberQueryResultJson(
                "manager-901", "FAILED",
                "[{\"targetJid\":\"1@s.whatsapp.net\",\"inGroup\":false,\"admin\":false}]");

        assertThatThrownBy(() -> onMessage(accountMismatch))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onMessage(adminOutsideGroup))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onMessage(failedWithMembers))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(membersResultSink);
    }

    private static String memberQueryResultJson(
            String envelopeAccountId, String outcome, String members) {
        return """
                {"event":"group.members.result_reported","accountId":"%s","data":{
                  "source":"pull_task_member_query","tenantId":7,"pullTaskId":100,
                  "groupExecutionId":11,"queryId":701,"purpose":"MANAGER_ADMIN_MEMBERSHIP",
                  "accountId":901,"protocolAccountId":"manager-901","protocolBackend":"WEB",
                  "commandId":"cmd-query-1","attemptNo":1,"outcome":"%s",
                  "groupJid":"120363group@g.us","members":%s,
                  "reasonCode":"FAILED","reasonMessage":"failed","retryable":false,"timestamp":5
                }}
                """.formatted(envelopeAccountId, outcome, members);
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

        onMessage(raw);

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

        onMessage(raw);

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

        onMessage(raw);

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

        onMessage(raw);

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

        onMessage(raw);

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

        onMessage(raw);

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
    void onMessage_managerAdminResultRequiresPromoteAndDispatchesTargetJid() {
        String raw = """
                {
                  "eventId":"promoter-903:group.action_result_reported:cmd-promote-2",
                  "event":"group.action_result_reported",
                  "accountId":"promoter-903",
                  "workerId":"worker-a",
                  "data":{
                    "tenantId":7,"pullTaskId":100,"groupExecutionId":11,"actionId":711,
                    "source":"pull_task_manager_admin","operation":"PARTICIPANT_PROMOTE",
                    "accountId":903,"protocolAccountId":"promoter-903",
                    "commandId":"cmd-promote-2","attemptNo":2,
                    "targetJid":"15@s.whatsapp.net",
                    "outcome":"FAILED","reasonCode":"GROUP_PERMISSION_DENIED",
                    "reasonMessage":"raw","retryable":false,"timestamp":5000
                  }
                }
                """;

        onMessage(raw);

        ArgumentCaptor<ProtocolGroupActionResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupActionResultReportedEvent.class);
        verify(actionResultSink).handleActionResultReported(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ProtocolGroupActionResultReportedEvent(
                "promoter-903:group.action_result_reported:cmd-promote-2",
                7L, 100L, 11L, 711L, "pull_task_manager_admin", "PARTICIPANT_PROMOTE",
                903L, "promoter-903", "cmd-promote-2", 2, "FAILED",
                "15@s.whatsapp.net", "GROUP_PERMISSION_DENIED", "raw",
                false, 5_000L, "worker-a"));
    }

    @Test
    void onMessage_groupSettingsResultIsAcceptedWithoutTargetJid() {
        // 群设置改的是群属性，不针对任何成员，因此不带也不该要求 targetJid。
        String raw = """
                {
                  "eventId":"manager-901:group.action_result_reported:cmd-settings-1",
                  "event":"group.action_result_reported",
                  "accountId":"manager-901",
                  "workerId":"worker-a",
                  "data":{
                    "tenantId":7,"pullTaskId":100,"groupExecutionId":11,"actionId":811,
                    "source":"pull_task_group_settings","operation":"GROUP_SETTINGS_APPLY",
                    "accountId":901,"protocolAccountId":"manager-901",
                    "commandId":"cmd-settings-1","attemptNo":2,
                    "outcome":"SUCCESS","retryable":false,"timestamp":5000
                  }
                }
                """;

        onMessage(raw);

        ArgumentCaptor<ProtocolGroupActionResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupActionResultReportedEvent.class);
        verify(actionResultSink).handleActionResultReported(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ProtocolGroupActionResultReportedEvent(
                "manager-901:group.action_result_reported:cmd-settings-1",
                7L, 100L, 11L, 811L, "pull_task_group_settings", "GROUP_SETTINGS_APPLY",
                901L, "manager-901", "cmd-settings-1", 2, "SUCCESS",
                null, null, null, false, 5_000L, "worker-a"));
    }

    @Test
    void onMessage_creatorLeaveResultIsAcceptedWithoutTargetJid() {
        String raw = """
                {
                  "eventId":"owner-901:group.action_result_reported:cmd-leave-1",
                  "event":"group.action_result_reported",
                  "accountId":"owner-901",
                  "workerId":"worker-a",
                  "data":{
                    "tenantId":7,"pullTaskId":100,"groupExecutionId":11,"actionId":903,
                    "source":"pull_task_creator_leave","operation":"GROUP_LEAVE",
                    "accountId":901,"protocolAccountId":"owner-901",
                    "commandId":"cmd-leave-1","attemptNo":1,
                    "outcome":"SUCCESS","retryable":false,"timestamp":5000
                  }
                }
                """;

        onMessage(raw);

        ArgumentCaptor<ProtocolGroupActionResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupActionResultReportedEvent.class);
        verify(actionResultSink).handleActionResultReported(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ProtocolGroupActionResultReportedEvent(
                "owner-901:group.action_result_reported:cmd-leave-1",
                7L, 100L, 11L, 903L, "pull_task_creator_leave", "GROUP_LEAVE",
                901L, "owner-901", "cmd-leave-1", 1, "SUCCESS",
                null, null, null, false, 5_000L, "worker-a"));
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
                    "outcome":"UNKNOWN","executionState":"UNCERTAIN",
                    "reasonCode":"PARTICIPANT_ADD_TIMEOUT",
                    "reasonMessage":"timed out","retryable":true,"timestamp":5000
                  }
                }
                """;

        onMessage(raw);

        ArgumentCaptor<ProtocolPullTaskBatchParticipantResultReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolPullTaskBatchParticipantResultReportedEvent.class);
        verify(batchParticipantResultSink).handleBatchParticipantResultReported(captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                new ProtocolPullTaskBatchParticipantResultReportedEvent(
                        "puller-902:group.action_result_reported:cmd-batch-1:8613800000903_s_whatsapp_net",
                        7L, 100L, 11L, 801L, 902L, "puller-902", "cmd-batch-1", 1,
                        "8613800000903@s.whatsapp.net", "UNKNOWN", "UNCERTAIN",
                        "PARTICIPANT_ADD_TIMEOUT", "timed out", true, 5_000L, "worker-a"));
        verifyNoInteractions(actionResultSink);
    }

    @Test
    void onMessage_batchAddResultRejectsMissingUnknownOrIllegalExecutionState() {
        String missing = batchParticipantResultJson("FAILED", null);
        String unknown = batchParticipantResultJson("FAILED", "BROKEN");
        String wrongCase = batchParticipantResultJson("FAILED", "started");
        String illegalPair = batchParticipantResultJson("FAILED", "NOT_STARTED");

        assertThatThrownBy(() -> onMessage(missing)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onMessage(unknown)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onMessage(wrongCase)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onMessage(illegalPair)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(batchParticipantResultSink);
    }

    private static String batchParticipantResultJson(String outcome, String executionState) {
        String stateJson = executionState == null
                ? ""
                : ",\"executionState\":\"" + executionState + "\"";
        return """
                {"event":"group.action_result_reported","accountId":"puller-902","data":{
                  "tenantId":7,"pullTaskId":100,"groupExecutionId":11,"pullCallId":801,
                  "source":"pull_task_batch_add","operation":"PARTICIPANT_ADD",
                  "accountId":902,"protocolAccountId":"puller-902","commandId":"cmd-batch-1",
                  "attemptNo":1,"targetJid":"8613800000903@s.whatsapp.net",
                  "outcome":"%s"%s,"retryable":false
                }}
                """.formatted(outcome, stateJson);
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

        assertThatThrownBy(() -> onMessage(wrongOperation))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onMessage(wrongAccount))
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
        String joinedWithoutGroupJid = """
                {"event":"group.join_result_reported","data":{
                  "tenantId":1,"joinTaskId":9,"joinTaskResultId":26,"accountId":382,
                  "protocolAccountId":"acc-1","commandId":"cmd-1","attemptNo":1,
                  "outcome":"JOINED","retryable":false
                }}
                """;

        assertThatThrownBy(() -> onMessage(missingCommand)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onMessage(invalidOutcome)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> onMessage(joinedWithoutGroupJid)).isInstanceOf(BusinessException.class);
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

        onMessage(raw);

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
    void onMessage_realtimeBannedHealthWithoutLinkId_dispatchesByGroupJid() {
        String raw = """
                {
                  "eventId": "evt-group-banned",
                  "event": "group.health_reported",
                  "accountId": "acc_919096944068",
                  "occurredAt": "2026-08-07T01:32:42.912Z",
                  "workerId": "worker-4",
                  "data": {
                    "tenantId": 1,
                    "accountId": 15,
                    "protocolAccountId": "acc_919096944068",
                    "groupJid": "120363428058767969@g.us",
                    "health": "BANNED",
                    "errorCode": "CHAT_SUSPENDED",
                    "checkedAt": "2026-08-07T01:32:42.912Z"
                  }
                }
                """;

        onMessage(raw);

        ArgumentCaptor<ProtocolGroupHealthReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupHealthReportedEvent.class);
        verify(sink).handleHealthReported(captor.capture());
        assertThat(captor.getValue().groupLinkId()).isNull();
        assertThat(captor.getValue().groupJid()).isEqualTo("120363428058767969@g.us");
        assertThat(captor.getValue().health()).isEqualTo("BANNED");
        assertThat(captor.getValue().errorCode()).isEqualTo("CHAT_SUSPENDED");
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

        onMessage(raw);

        verifyNoInteractions(sink, joinResultSink, actionResultSink);
    }

    @Test
    void onMessage_healthReportedMissingTenantOrGroupJid_skipsSink() {
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

        onMessage(raw);

        verifyNoInteractions(sink);
    }

    @Test
    void onMessage_malformedJson_throwsBusinessExceptionWithoutSink() {
        assertThatThrownBy(() -> onMessage("{bad-json"))
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

        assertThatThrownBy(() -> onMessage(raw))
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

        assertThatThrownBy(() -> onMessage(raw))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }
}
