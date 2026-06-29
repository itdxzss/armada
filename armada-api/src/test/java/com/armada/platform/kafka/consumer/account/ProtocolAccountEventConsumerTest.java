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

    private ProtocolAccountEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProtocolAccountEventConsumer(new ObjectMapper(), sink, groupsReportedSink);
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
    }

    @Test
    void onMessage_malformedJson_throwsBusinessExceptionWithoutSink() {
        assertThatThrownBy(() -> consumer.onMessage("{bad-json"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("协议账号事件 JSON 解析失败");

        verifyNoInteractions(sink);
        verifyNoInteractions(groupsReportedSink);
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
        verifyNoInteractions(groupsReportedSink);
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
