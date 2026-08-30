package com.armada.platform.kafka.consumer.account;

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
import java.time.Instant;
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

    private static final String FIXED_TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Mock
    private ProtocolAccountStateChangedSink sink;

    @Mock
    private ProtocolAccountGroupsReportedSink groupsReportedSink;

    @Mock
    private ProtocolAccountOfflineDiagnosedSink offlineDiagnosedSink;

    @Mock
    private ProtocolAccountTypeDetectedSink typeDetectedSink;

    @Mock
    private ProtocolAccountGroupMembershipChangedSink membershipChangedSink;

    @Mock
    private ProtocolGroupDepartureSink groupDepartureSink;

    @Mock
    private ProtocolGroupJoinSink groupJoinSink;

    @Mock
    private ProtocolGroupMetadataSyncRequestedSink metadataSyncRequestedSink;

    private ProtocolAccountEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProtocolAccountEventConsumer(
                new ObjectMapper(),
                sink,
                typeDetectedSink,
                groupsReportedSink,
                offlineDiagnosedSink,
                new ProtocolAccountGroupEventSinks(
                        membershipChangedSink, groupDepartureSink, groupJoinSink),
                metadataSyncRequestedSink);
    }

    private void onStateMessage(String rawMessage) {
        onStateMessage(rawMessage, null);
    }

    private void onStateMessage(String rawMessage, String headerTraceId) {
        consumer.onStateMessage(rawMessage, headerTraceId);
    }

    private void onGroupSyncMessage(String rawMessage) {
        consumer.onGroupSyncMessage(rawMessage, null);
    }

    @Test
    void onStateMessage_runsSinkInsideEnvelopeTraceAndCleansScope() {
        doAnswer(invocation -> {
            assertThat(TraceContext.current()).contains(FIXED_TRACE_ID);
            return null;
        }).when(sink).handleStateChanged(any());

        onStateMessage("""
                {"traceId":"0123456789abcdef0123456789abcdef","eventId":"evt-trace-1",
                 "event":"account.state_changed","accountId":"acc_trace",
                 "occurredAt":"2026-08-11T00:00:00Z","workerId":"worker-1",
                 "data":{"tenantId":1,"accountId":100,"from":"VERIFYING","to":"ONLINE"}}
                """, "11111111111111111111111111111111");

        assertThat(TraceContext.current()).isEmpty();
    }

    @Test
    void onStateMessage_cleansScopeWhenSinkFails() {
        doAnswer(invocation -> {
            assertThat(TraceContext.current()).contains(FIXED_TRACE_ID);
            throw new IllegalStateException("database unavailable");
        }).when(sink).handleStateChanged(any());

        assertThatThrownBy(() -> onStateMessage("""
                {"traceId":"0123456789abcdef0123456789abcdef","eventId":"evt-trace-2",
                 "event":"account.state_changed","accountId":"acc_trace",
                 "occurredAt":"2026-08-11T00:00:00Z","workerId":"worker-1",
                 "data":{"tenantId":1,"accountId":100,"from":"VERIFYING","to":"ONLINE"}}
                """, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        assertThat(TraceContext.current()).isEmpty();
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
                    "tenantId": 1,
                    "accountId": 100,
                    "protocolAccountId": "acc_861800000001",
                    "from": "RECONNECTING",
                    "to": "ONLINE",
                    "reason": "connected",
                    "semantic": "RECONNECTING",
                    "rawCode": 515,
                    "source": "batch_offline",
                    "onlineAttemptId": "oa_state_1",
                    "proxyId": 7
                  }
                }
                """;

        onStateMessage(raw);

        ArgumentCaptor<ProtocolAccountStateChangedEvent> captor =
                ArgumentCaptor.forClass(ProtocolAccountStateChangedEvent.class);
        verify(sink).handleStateChanged(captor.capture());
        ProtocolAccountStateChangedEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("evt-1");
        assertThat(event.tenantId()).isEqualTo(1L);
        assertThat(event.accountId()).isEqualTo(100L);
        assertThat(event.protocolAccountId()).isEqualTo("acc_861800000001");
        assertThat(event.from()).isEqualTo("RECONNECTING");
        assertThat(event.to()).isEqualTo("ONLINE");
        assertThat(event.occurredAt()).isEqualTo(1782626401000L);
        assertThat(event.semantic()).isEqualTo("RECONNECTING");
        assertThat(event.rawCode()).isEqualTo(515);
        assertThat(event.source()).isEqualTo("batch_offline");
        assertThat(event.onlineAttemptId()).isEqualTo("oa_state_1");
        assertThat(event.proxyId()).isEqualTo(7L);
        assertThat(event.workerId()).isEqualTo("worker-a");
    }

    @Test
    void onStateMessage_typeDetectedEnvelope_dispatchesCorrelatedDetection() {
        onStateMessage("""
                {
                  "eventId": "evt-type-1",
                  "event": "account.type_detected",
                  "accountId": "acc_861800000001",
                  "occurredAt": "2026-08-30T01:00:00Z",
                  "workerId": "android-1",
                  "data": {
                    "tenantId": 7,
                    "accountId": 100,
                    "protocolAccountId": "acc_861800000001",
                    "onlineAttemptId": "oa-1",
                    "commandId": "cmd-1",
                    "protocolBackend": "ANDROID",
                    "credentialVersion": 1788000000000,
                    "declaredAccountType": 1,
                    "detectedAccountType": "BUSINESS_STANDARD",
                    "verificationLevel": "HIGH",
                    "source": "business_profile_query",
                    "detectedAt": "2026-08-30T00:59:59Z"
                  }
                }
                """);

        ArgumentCaptor<ProtocolAccountTypeDetectedEvent> captor =
                ArgumentCaptor.forClass(ProtocolAccountTypeDetectedEvent.class);
        verify(typeDetectedSink).handleTypeDetected(captor.capture());
        assertThat(captor.getValue()).satisfies(event -> {
            assertThat(event.eventId()).isEqualTo("evt-type-1");
            assertThat(event.tenantId()).isEqualTo(7L);
            assertThat(event.accountId()).isEqualTo(100L);
            assertThat(event.protocolAccountId()).isEqualTo("acc_861800000001");
            assertThat(event.credentialVersion()).isEqualTo(1_788_000_000_000L);
            assertThat(event.declaredAccountType()).isEqualTo(1);
            assertThat(event.detectedAccountType()).isEqualTo("BUSINESS_STANDARD");
            assertThat(event.verificationLevel()).isEqualTo("HIGH");
            assertThat(event.source()).isEqualTo("business_profile_query");
            assertThat(event.detectedAt())
                    .isEqualTo(Instant.parse("2026-08-30T00:59:59Z").toEpochMilli());
        });
    }

    @Test
    void onMessage_groupsReportedEnvelope_dispatchesParsedGroupsEvent() {
        String raw = """
                {
                  "eventId": "evt-groups-1",
                  "event": "account.groups_reported",
                  "version": "v1",
                  "accountId": "acc_861800000001",
                  "occurredAt": "2026-06-28T06:00:01Z",
                  "workerId": "worker-a",
                  "data": {
                    "tenantId": 1,
                    "accountId": 100,
                    "source": "wa_groups_dirty",
                    "commandId": "groups-sync-command-1",
                    "snapshotId": "snapshot-1",
                    "queryStartedAt": "2026-06-28T06:00:00Z",
                    "snapshotCutoff": "2026-06-28T06:00:00Z",
                    "snapshotComplete": false,
                    "skippedGroupCount": 1,
                    "groups": [
                      {
                        "groupJid": "120363000000001@g.us",
                        "subject": "运营群",
                        "memberCount": 88,
                        "ownerJid": "861300000000@s.whatsapp.net",
                        "isAdmin": true,
                        "announce": false,
                        "avatarUrl": "https://example.test/avatar.jpg",
                        "postControlObservedAt": 1782626401000,
                        "sourceEventId": "wgp2-self-add-1"
                      }
                    ]
                  }
                }
                """;

        onGroupSyncMessage(raw);

        ArgumentCaptor<ProtocolAccountGroupsReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolAccountGroupsReportedEvent.class);
        verify(groupsReportedSink).handleGroupsReported(captor.capture());
        ProtocolAccountGroupsReportedEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("evt-groups-1");
        assertThat(event.tenantId()).isEqualTo(1L);
        assertThat(event.accountId()).isEqualTo(100L);
        assertThat(event.protocolAccountId()).isEqualTo("acc_861800000001");
        assertThat(event.reportedAt()).isEqualTo(1782626401000L);
        assertThat(event.workerId()).isEqualTo("worker-a");
        assertThat(event.source()).isEqualTo("wa_groups_dirty");
        assertThat(event.commandId()).isEqualTo("groups-sync-command-1");
        assertThat(event.snapshotId()).isEqualTo("snapshot-1");
        assertThat(event.queryStartedAt()).isEqualTo(1782626400000L);
        assertThat(event.snapshotCutoff()).isEqualTo(1782626400000L);
        assertThat(event.snapshotComplete()).isFalse();
        assertThat(event.skippedGroupCount()).isEqualTo(1);
        assertThat(event.groups()).singleElement().satisfies(group -> {
            assertThat(group.groupJid()).isEqualTo("120363000000001@g.us");
            assertThat(group.subject()).isEqualTo("运营群");
            assertThat(group.memberCount()).isEqualTo(88);
            assertThat(group.ownerJid()).isEqualTo("861300000000@s.whatsapp.net");
            assertThat(group.admin()).isTrue();
            assertThat(group.announceOnly()).isFalse();
            assertThat(group.avatarUrl()).isEqualTo("https://example.test/avatar.jpg");
            assertThat(group.postControlObservedAt()).isEqualTo(1782626401000L);
            assertThat(group.sourceEventId()).isEqualTo("wgp2-self-add-1");
        });
        verifyNoInteractions(sink);
    }

    @Test
    void onMessage_groupsReportedRejectsMissingOccurredAt() {
        assertThatThrownBy(() -> onGroupSyncMessage("""
                {
                  "eventId": "evt-groups-missing-time",
                  "event": "account.groups_reported",
                  "version": "v1",
                  "accountId": "acc_861800000001",
                  "workerId": "worker-a",
                  "data": {
                    "tenantId": 1,
                    "accountId": 100,
                    "source": "wa_groups_dirty",
                    "snapshotComplete": true,
                    "skippedGroupCount": 0,
                    "groups": []
                  }
                }
                """))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议账号群列表事件缺少 occurredAt");

        verifyNoInteractions(groupsReportedSink);
    }

    @Test
    void onMessage_membershipChangedDispatchesSafeEvent() {
        onGroupSyncMessage("""
                {"eventId":"evt-membership-1","event":"account.group_membership_changed","version":"v1",
                 "accountId":"acc_android_1","occurredAt":"2026-07-22T02:00:00Z","workerId":"android-1",
                 "data":{"tenantId":7,"accountId":100,"protocolAccountId":"acc_android_1",
                         "groupJid":"120363001@g.us","action":"remove",
                         "selfParticipation":"SELF","sourceEventId":"wgp2-remove-1",
                         "source":"android_wgp2"}}
                """);

        ArgumentCaptor<ProtocolAccountGroupMembershipChangedEvent> captor =
                ArgumentCaptor.forClass(ProtocolAccountGroupMembershipChangedEvent.class);
        verify(membershipChangedSink).handleMembershipChanged(captor.capture());
        ProtocolAccountGroupMembershipChangedEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("evt-membership-1");
        assertThat(event.tenantId()).isEqualTo(7L);
        assertThat(event.accountId()).isEqualTo(100L);
        assertThat(event.protocolAccountId()).isEqualTo("acc_android_1");
        assertThat(event.groupJid()).isEqualTo("120363001@g.us");
        assertThat(event.action()).isEqualTo("remove");
        assertThat(event.selfParticipation()).isEqualTo("SELF");
        assertThat(event.sourceEventId()).isEqualTo("wgp2-remove-1");
        assertThat(event.occurredAt()).isEqualTo(1784685600000L);
        assertThat(event.source()).isEqualTo("android_wgp2");
        assertThat(event.workerId()).isEqualTo("android-1");
    }

    @Test
    void onMessage_membershipChangedRejectsMissingOriginalSourceEventId() {
        assertThatThrownBy(() -> onGroupSyncMessage("""
                {"eventId":"evt-membership-missing-source","event":"account.group_membership_changed",
                 "version":"v1","accountId":"acc_android_1",
                 "occurredAt":"2026-07-22T02:00:00Z","workerId":"android-1",
                 "data":{"tenantId":7,"accountId":100,"protocolAccountId":"acc_android_1",
                         "groupJid":"120363001@g.us","action":"add",
                         "selfParticipation":"SELF","source":"android_wgp2"}}
                """))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议账号群关系事件缺少 data.sourceEventId");

        verifyNoInteractions(membershipChangedSink);
    }

    @Test
    void onGroupSyncMessageDispatchesPastParticipants() {
        onGroupSyncMessage("""
                {"eventId":"history-1","event":"account.group_past_participants.reported","version":"v1",
                 "accountId":"android-1","occurredAt":"2026-08-03T02:00:00Z","workerId":"worker-1",
                 "data":{"tenantId":7,"accountId":10,"protocolAccountId":"android-1",
                         "groupJid":"120363-test@g.us","source":"HISTORY_SYNC","participants":[
                    {"participantJid":"15550000001@s.whatsapp.net","phone":"15550000001",
                     "exitType":"LEFT","exitedAt":1785722400000,"sourceEventId":"source-1"},
                    {"participantJid":"15550000002@s.whatsapp.net","phone":"15550000002",
                     "exitType":"REMOVED","exitedAt":1785722400001,"sourceEventId":"source-2"}]}}
                """);

        ArgumentCaptor<ProtocolGroupDepartureEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupDepartureEvent.class);
        verify(groupDepartureSink).handleDepartures(captor.capture());
        assertThat(captor.getValue().groupJid()).isEqualTo("120363-test@g.us");
        assertThat(captor.getValue().sourceType()).isEqualTo("HISTORY_SYNC");
        assertThat(captor.getValue().participants())
                .extracting(ProtocolGroupDepartureEvent.Participant::exitType)
                .containsExactly("LEFT", "REMOVED");
        assertThat(captor.getValue().participants().get(0)).satisfies(participant -> {
            assertThat(participant.participantJid()).isEqualTo("15550000001@s.whatsapp.net");
            assertThat(participant.exitedAt()).isEqualTo(1785722400000L);
        });
    }

    @Test
    void onGroupSyncMessageNormalizesAmbiguousWgp2RemovedAndAcceptsUnknown() {
        onGroupSyncMessage("""
                {"eventId":"departure-1","event":"account.group_participant_departed","version":"v1",
                 "accountId":"android-1","occurredAt":"2026-08-03T02:00:00Z","workerId":"worker-1",
                 "data":{"tenantId":7,"accountId":10,"protocolAccountId":"android-1",
                         "groupJid":"120363-test@g.us","source":"WGP2_NOTIFICATION","participants":[
                           {"participantJid":"15550000001@s.whatsapp.net","phone":"15550000001",
                            "exitType":"REMOVED","exitedAt":1785722400000,"sourceEventId":"source-remove-1"},
                           {"participantJid":"15550000002@s.whatsapp.net","phone":"15550000002",
                            "exitType":"UNKNOWN","exitedAt":1785722400001,"sourceEventId":"source-unknown-1"}]}}
                """);

        ArgumentCaptor<ProtocolGroupDepartureEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupDepartureEvent.class);
        verify(groupDepartureSink).handleDepartures(captor.capture());
        assertThat(captor.getValue().sourceType()).isEqualTo("WGP2_NOTIFICATION");
        assertThat(captor.getValue().participants())
                .extracting(ProtocolGroupDepartureEvent.Participant::exitType)
                .containsExactly("UNKNOWN", "UNKNOWN");
    }

    @Test
    void onGroupSyncMessageAcceptsWgp2RemovedWithActorDifferentEvidence() {
        onGroupSyncMessage("""
                {"eventId":"departure-verified","event":"account.group_participant_departed","version":"v1",
                 "accountId":"android-1","occurredAt":"2026-08-06T02:00:00Z","workerId":"worker-1",
                 "data":{"tenantId":7,"accountId":10,"protocolAccountId":"android-1",
                         "groupJid":"120363-test@g.us","source":"WGP2_NOTIFICATION","participants":[
                           {"participantJid":"15550000001@s.whatsapp.net","phone":"15550000001",
                            "exitType":"REMOVED","exitEvidence":"WGP2_ACTOR_DIFFERENT",
                            "exitedAt":1785722400000,"sourceEventId":"source-remove-verified"}]}}
                """);

        ArgumentCaptor<ProtocolGroupDepartureEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupDepartureEvent.class);
        verify(groupDepartureSink).handleDepartures(captor.capture());
        assertThat(captor.getValue().participants()).singleElement().satisfies(participant -> {
            assertThat(participant.exitType()).isEqualTo("REMOVED");
        });
    }

    @Test
    void onGroupSyncMessageDispatchesJoinedParticipants() {
        onGroupSyncMessage("""
                {"eventId":"join-1","event":"account.group_participant_joined","version":"v1",
                 "accountId":"android-1","occurredAt":"2026-08-03T02:00:00Z","workerId":"worker-1",
                 "data":{"tenantId":7,"accountId":10,"protocolAccountId":"android-1",
                         "groupJid":"120363-test@g.us","source":"WGP2_NOTIFICATION",
                         "joinedParticipants":[
                           {"participantJid":"15550000001@s.whatsapp.net","phone":"15550000001",
                            "joinedAt":1785722400000,"sourceEventId":"source-add-1"}]}}
                """);

        ArgumentCaptor<ProtocolGroupJoinEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupJoinEvent.class);
        verify(groupJoinSink).handleJoins(captor.capture());
        assertThat(captor.getValue().groupJid()).isEqualTo("120363-test@g.us");
        assertThat(captor.getValue().sourceType()).isEqualTo("WGP2_NOTIFICATION");
        assertThat(captor.getValue().participants()).singleElement().satisfies(participant -> {
            assertThat(participant.participantJid()).isEqualTo("15550000001@s.whatsapp.net");
            assertThat(participant.joinedAt()).isEqualTo(1785722400000L);
            assertThat(participant.sourceEventId()).isEqualTo("source-add-1");
        });
    }

    @Test
    void onGroupSyncMessageRejectsInvalidDepartureParticipantJid() {
        assertThatThrownBy(() -> onGroupSyncMessage("""
                {"eventId":"history-invalid","event":"account.group_past_participants.reported","version":"v1",
                 "accountId":"android-1","occurredAt":"2026-08-03T02:00:00Z","workerId":"worker-1",
                 "data":{"tenantId":7,"accountId":10,"protocolAccountId":"android-1",
                         "groupJid":"120363-test@g.us","source":"HISTORY_SYNC","participants":[
                           {"participantJid":"abc@s.whatsapp.net","phone":"15550000001",
                            "exitType":"LEFT","exitedAt":1785722400000,"sourceEventId":"source-1"}]}}
                """))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议群退群事件 participantJid 非法");

        verifyNoInteractions(groupDepartureSink);
    }

    @Test
    void onGroupSyncMessageRejectsInvalidDepartureGroupJid() {
        assertThatThrownBy(() -> onGroupSyncMessage("""
                {"eventId":"history-invalid","event":"account.group_past_participants.reported","version":"v1",
                 "accountId":"android-1","occurredAt":"2026-08-03T02:00:00Z","workerId":"worker-1",
                 "data":{"tenantId":7,"accountId":10,"protocolAccountId":"android-1",
                         "groupJid":"bad@@g.us","source":"HISTORY_SYNC","participants":[
                           {"participantJid":"15550000001@s.whatsapp.net","phone":"15550000001",
                            "exitType":"LEFT","exitedAt":1785722400000,"sourceEventId":"source-1"}]}}
                """))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议群退群事件 groupJid 非法");

        verifyNoInteractions(groupDepartureSink);
    }

    @Test
    void onGroupSyncMessageRejectsDepartureSourceThatDoesNotMatchEventType() {
        String raw = """
                {"eventId":"history-1","event":"account.group_past_participants.reported","version":"v1",
                 "accountId":"android-1","occurredAt":"2026-08-03T02:00:00Z",
                 "data":{"tenantId":7,"accountId":10,"protocolAccountId":"android-1",
                         "groupJid":"120363-test@g.us","source":"WGP2_NOTIFICATION","participants":[
                           {"participantJid":"15550000001@s.whatsapp.net","exitType":"LEFT",
                            "exitedAt":1785722400000,"sourceEventId":"source-1"}]}}
                """;

        assertThatThrownBy(() -> onGroupSyncMessage(raw))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议群退群事件类型与来源不一致");

        verifyNoInteractions(groupDepartureSink);
    }

    @Test
    void onMessage_groupMetadataSyncRequestedDispatchesValidatedEvent() {
        onGroupSyncMessage("""
                {"eventId":"evt-metadata-1","event":"account.group_metadata_sync_requested","version":"v1",
                 "accountId":"acc_web_22","occurredAt":"2026-08-05T02:00:00Z","workerId":"web-1",
                 "data":{"tenantId":7,"accountId":22,"protocolAccountId":"acc_web_22",
                         "groupJid":"120363001@g.us","trigger":"PARTICIPANT_CHANGED",
                         "source":"wa_group_participants_update"}}
                """);

        ArgumentCaptor<ProtocolGroupMetadataSyncRequestedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupMetadataSyncRequestedEvent.class);
        verify(metadataSyncRequestedSink).handleGroupMetadataSyncRequested(captor.capture());
        ProtocolGroupMetadataSyncRequestedEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("evt-metadata-1");
        assertThat(event.tenantId()).isEqualTo(7L);
        assertThat(event.accountId()).isEqualTo(22L);
        assertThat(event.protocolAccountId()).isEqualTo("acc_web_22");
        assertThat(event.groupJid()).isEqualTo("120363001@g.us");
        assertThat(event.trigger()).isEqualTo("PARTICIPANT_CHANGED");
        assertThat(event.occurredAt()).isEqualTo(1785895200000L);
        assertThat(event.source()).isEqualTo("wa_group_participants_update");
        assertThat(event.workerId()).isEqualTo("web-1");
    }

    @Test
    void onMessage_groupMetadataSyncRequestedRejectsInvalidRoutingAndFacts() {
        assertThatThrownBy(() -> onGroupSyncMessage("""
                {"eventId":"evt-metadata-2","event":"account.group_metadata_sync_requested",
                 "accountId":"acc_stale","occurredAt":"2026-08-05T02:00:00Z",
                 "data":{"tenantId":7,"accountId":22,"protocolAccountId":"acc_web_22",
                         "groupJid":"120363001@g.us","trigger":"METADATA_CHANGED"}}
                """))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议群详情同步事件路由账号不一致");
        assertThatThrownBy(() -> onGroupSyncMessage("""
                {"eventId":"evt-metadata-3","event":"account.group_metadata_sync_requested",
                 "accountId":"acc_web_22","occurredAt":"2026-08-05T02:00:00Z",
                 "data":{"tenantId":0,"accountId":22,"protocolAccountId":"acc_web_22",
                         "groupJid":"not-a-group","trigger":"UNKNOWN"}}
                """))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(metadataSyncRequestedSink);
    }

    @Test
    void onMessage_membershipChangedWithoutRoutingAccountRejectsEvent() {
        String raw = """
                {"eventId":"evt-membership-2","event":"account.group_membership_changed","version":"v1",
                 "occurredAt":"2026-07-22T02:00:00Z","workerId":"android-1",
                 "data":{"tenantId":7,"accountId":100,"protocolAccountId":"acc_android_1",
                         "groupJid":"120363001@g.us","action":"remove",
                         "selfParticipation":"SELF","source":"android_wgp2"}}
                """;

        assertThatThrownBy(() -> onGroupSyncMessage(raw))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议账号群关系事件缺少 accountId");

        verifyNoInteractions(membershipChangedSink);
    }

    @Test
    void onMessage_membershipChangedWithMismatchedRoutingAccountRejectsEvent() {
        String raw = """
                {"eventId":"evt-membership-3","event":"account.group_membership_changed","version":"v1",
                 "accountId":"acc_android_stale","occurredAt":"2026-07-22T02:00:00Z","workerId":"android-1",
                 "data":{"tenantId":7,"accountId":100,"protocolAccountId":"acc_android_1",
                         "groupJid":"120363001@g.us","action":"remove",
                         "selfParticipation":"SELF","source":"android_wgp2"}}
                """;

        assertThatThrownBy(() -> onGroupSyncMessage(raw))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议账号群关系事件路由账号不一致");

        verifyNoInteractions(membershipChangedSink);
    }

    @Test
    void onMessage_offlineDiagnosedEnvelope_dispatchesParsedDiagnosisEvent() {
        String raw = """
                {
                  "eventId": "evt-diagnosis-1",
                  "event": "account.offline_diagnosed",
                  "version": "v1",
                  "accountId": "acc_252625852450",
                  "occurredAt": "2026-07-02T10:18:00.123Z",
                  "workerId": "w3",
                  "evidence": {
                    "connectionField": "connecting",
                    "wsOpen": false
                  },
                  "data": {
                    "tenantId": 1,
                    "accountId": 9,
                    "protocolAccountId": "acc_252625852450",
                    "onlineAttemptId": "oa_20260702101716_x7k9m2",
                    "previousOnlineAttemptId": null,
                    "commandId": "cmd_1",
                    "batchId": "batch_1",
                    "proxyId": 4035,
                    "source": "batch_online",
                    "from": "VERIFYING",
                    "to": "PROXY_FAILED",
                    "diagnosisCode": "VERIFY_TIMEOUT_NO_CONNECTION_UPDATE",
                    "diagnosisClass": "PROXY_OR_WA_CONNECTIVITY",
                    "rawCode": 408,
                    "rawReason": "no connection.update open/close before verify timeout",
                    "recoverability": "RETRYABLE",
                    "actionTaken": "MARK_PROXY_FAILED_RELEASE_SLOT"
                  }
                }
                """;

        onStateMessage(raw);

        ArgumentCaptor<ProtocolAccountOfflineDiagnosedEvent> captor =
                ArgumentCaptor.forClass(ProtocolAccountOfflineDiagnosedEvent.class);
        verify(offlineDiagnosedSink).handleOfflineDiagnosed(captor.capture());
        ProtocolAccountOfflineDiagnosedEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("evt-diagnosis-1");
        assertThat(event.tenantId()).isEqualTo(1L);
        assertThat(event.accountId()).isEqualTo(9L);
        assertThat(event.protocolAccountId()).isEqualTo("acc_252625852450");
        assertThat(event.onlineAttemptId()).isEqualTo("oa_20260702101716_x7k9m2");
        assertThat(event.proxyId()).isEqualTo(4035L);
        assertThat(event.from()).isEqualTo("VERIFYING");
        assertThat(event.to()).isEqualTo("PROXY_FAILED");
        assertThat(event.diagnosisCode()).isEqualTo("VERIFY_TIMEOUT_NO_CONNECTION_UPDATE");
        assertThat(event.rawCode()).isEqualTo(408);
        assertThat(event.occurredAt()).isEqualTo(1782987480123L);
        assertThat(event.workerId()).isEqualTo("w3");
        assertThat(event.evidenceJson()).contains("\"connectionField\":\"connecting\"");
        verifyNoInteractions(sink, groupsReportedSink);
    }

    @Test
    void onMessage_offlineDiagnosedEnvelope_usesDataEvidenceFallback() {
        String raw = """
                {
                  "eventId": "evt-diagnosis-2",
                  "event": "account.offline_diagnosed",
                  "version": "v1",
                  "accountId": "acc_252625852450",
                  "occurredAt": "2026-07-02T10:18:00.123Z",
                  "workerId": "w3",
                  "data": {
                    "tenantId": 1,
                    "accountId": 9,
                    "protocolAccountId": "acc_252625852450",
                    "onlineAttemptId": "oa_20260702101716_x7k9m2",
                    "to": "PROXY_FAILED",
                    "diagnosisCode": "VERIFY_TIMEOUT_NO_CONNECTION_UPDATE",
                    "diagnosisClass": "PROXY_OR_WA_CONNECTIVITY",
                    "evidence": {
                      "source": "data"
                    }
                  }
                }
                """;

        onStateMessage(raw);

        ArgumentCaptor<ProtocolAccountOfflineDiagnosedEvent> captor =
                ArgumentCaptor.forClass(ProtocolAccountOfflineDiagnosedEvent.class);
        verify(offlineDiagnosedSink).handleOfflineDiagnosed(captor.capture());
        assertThat(captor.getValue().evidenceJson()).isEqualTo("{\"source\":\"data\"}");
        verifyNoInteractions(sink, groupsReportedSink);
    }

    @Test
    void onStateMessage_unregisteredAccountEventRejectsWrongTopicEvent() {
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

        assertThatThrownBy(() -> onStateMessage(raw))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议账号状态 Topic 收到非法事件类型: account.heartbeat");

        verifyNoInteractions(sink);
        verifyNoInteractions(groupsReportedSink);
        verifyNoInteractions(offlineDiagnosedSink);
        verifyNoInteractions(membershipChangedSink);
    }

    @Test
    void onMessage_malformedJson_throwsBusinessExceptionWithoutSink() {
        assertThatThrownBy(() -> onStateMessage("{bad-json"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议账号事件 JSON 解析失败");

        verifyNoInteractions(sink);
        verifyNoInteractions(groupsReportedSink);
        verifyNoInteractions(offlineDiagnosedSink);
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
                  "data": {
                    "tenantId": 1,
                    "accountId": 100,
                    "from": "RECONNECTING"
                  }
                }
                """;

        assertThatThrownBy(() -> onStateMessage(raw))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议账号状态事件缺少 data.to");

        verifyNoInteractions(sink);
        verifyNoInteractions(groupsReportedSink);
        verifyNoInteractions(offlineDiagnosedSink);
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
                  "data": {
                    "tenantId": 1,
                    "accountId": 100,
                    "from": "RECONNECTING",
                    "to": "ONLINE"
                  }
                }
                """;
        doThrow(new IllegalStateException("database unavailable")).when(sink).handleStateChanged(any());

        assertThatThrownBy(() -> onStateMessage(raw))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }

    @Test
    void onGroupSyncMessage_stateEventRejectsWrongTopicEvent() {
        String raw = """
                {"eventId":"evt-wrong-topic","event":"account.state_changed","version":"v1",
                 "accountId":"acc_1","occurredAt":"2026-07-24T02:00:00Z","workerId":"web-1",
                 "data":{"tenantId":1,"accountId":1,"to":"ONLINE"}}
                """;

        assertThatThrownBy(() -> onGroupSyncMessage(raw))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议账号群同步 Topic 收到非法事件类型: account.state_changed");

        verifyNoInteractions(sink, groupsReportedSink, offlineDiagnosedSink, membershipChangedSink);
    }
}
