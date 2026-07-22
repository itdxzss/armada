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

    @Mock
    private ProtocolAccountGroupsReportedSink groupsReportedSink;

    @Mock
    private ProtocolAccountOfflineDiagnosedSink offlineDiagnosedSink;

    @Mock
    private ProtocolAccountGroupMembershipChangedSink membershipChangedSink;

    private ProtocolAccountEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProtocolAccountEventConsumer(
                new ObjectMapper(),
                sink,
                groupsReportedSink,
                offlineDiagnosedSink,
                membershipChangedSink);
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
                    "onlineAttemptId": "oa_state_1"
                  }
                }
                """;

        consumer.onMessage(raw);

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
        assertThat(event.workerId()).isEqualTo("worker-a");
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
                        "avatarUrl": "https://example.test/avatar.jpg"
                      }
                    ]
                  }
                }
                """;

        consumer.onMessage(raw);

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
        });
        verifyNoInteractions(sink);
    }

    @Test
    void onMessage_membershipChangedDispatchesSafeEvent() {
        consumer.onMessage("""
                {"eventId":"evt-membership-1","event":"account.group_membership_changed","version":"v1",
                 "accountId":"acc_android_1","occurredAt":"2026-07-22T02:00:00Z","workerId":"android-1",
                 "data":{"tenantId":7,"accountId":100,"protocolAccountId":"acc_android_1",
                         "groupJid":"120363001@g.us","action":"remove",
                         "selfParticipation":"SELF","source":"android_wgp2"}}
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
        assertThat(event.occurredAt()).isEqualTo(1784685600000L);
        assertThat(event.source()).isEqualTo("android_wgp2");
        assertThat(event.workerId()).isEqualTo("android-1");
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

        assertThatThrownBy(() -> consumer.onMessage(raw))
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

        assertThatThrownBy(() -> consumer.onMessage(raw))
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

        consumer.onMessage(raw);

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

        consumer.onMessage(raw);

        ArgumentCaptor<ProtocolAccountOfflineDiagnosedEvent> captor =
                ArgumentCaptor.forClass(ProtocolAccountOfflineDiagnosedEvent.class);
        verify(offlineDiagnosedSink).handleOfflineDiagnosed(captor.capture());
        assertThat(captor.getValue().evidenceJson()).isEqualTo("{\"source\":\"data\"}");
        verifyNoInteractions(sink, groupsReportedSink);
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
        verifyNoInteractions(groupsReportedSink);
        verifyNoInteractions(offlineDiagnosedSink);
        verifyNoInteractions(membershipChangedSink);
    }

    @Test
    void onMessage_malformedJson_throwsBusinessExceptionWithoutSink() {
        assertThatThrownBy(() -> consumer.onMessage("{bad-json"))
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

        assertThatThrownBy(() -> consumer.onMessage(raw))
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

        assertThatThrownBy(() -> consumer.onMessage(raw))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }
}
