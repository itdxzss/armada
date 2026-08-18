package com.armada.platform.kafka.consumer.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 锁定 group.metadata_updated 的协议契约校验。
 *
 * <p>重点是三种语义可区分：字段未进 mask 时即使 payload 带值也必须忽略；进了 mask 的布尔与
 * 秒数必须有明确值；只有描述允许进 mask 且为 null，表示明确清空。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProtocolGroupMetadataUpdatedConsumerTest {

    @Mock
    private ProtocolGroupHealthReportedSink healthSink;
    @Mock
    private ProtocolGroupJoinResultReportedSink joinResultSink;
    @Mock
    private ProtocolGroupActionResultReportedSink actionResultSink;
    @Mock
    private ProtocolPullTaskBatchParticipantResultReportedSink batchParticipantSink;
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

    private ProtocolGroupEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProtocolGroupEventConsumer(
                new ObjectMapper(), healthSink, joinResultSink, actionResultSink,
                batchParticipantSink, membersResultSink, inviteLinkChangedSink,
                participantChangedSink, metadataUpdatedSink, profileReportedSink);
    }

    @Test
    void acceptsPatchAndKeepsExplicitFalseAndClearedDescription() {
        consumer.onMessage(envelope("""
                "fieldMask": ["description", "announceOnly"],
                "description": null,
                "announceOnly": false
                """), null);

        ProtocolGroupMetadataUpdatedEvent event = captured();
        assertThat(event.fieldMask()).containsExactly("description", "announceOnly");
        assertThat(event.announceOnly()).as("明确 false 必须保留").isFalse();
        assertThat(event.description()).as("描述明确清空时值为 null，语义由 mask 承载").isNull();
        assertThat(event.groupJid()).isEqualTo("120363-abc@g.us");
        assertThat(event.occurredAt())
                .as("occurredAt 取协议事件发生时间，不得用消费时间伪造")
                .isEqualTo(Instant.parse("2026-08-16T04:31:40.000Z").toEpochMilli());
    }

    @Test
    void ignoresValuesOfFieldsOutsideMask() {
        consumer.onMessage(envelope("""
                "fieldMask": ["subject"],
                "subject": "New name",
                "announceOnly": true,
                "ephemeralDurationSeconds": 86400
                """), null);

        ProtocolGroupMetadataUpdatedEvent event = captured();
        assertThat(event.subject()).isEqualTo("New name");
        assertThat(event.announceOnly())
                .as("未进 mask 的字段即使带值也必须忽略，否则会覆盖控端已知事实")
                .isNull();
        assertThat(event.ephemeralDurationSeconds()).isNull();
    }

    @Test
    void deduplicatesFieldMaskIgnoringCase() {
        consumer.onMessage(envelope("""
                "fieldMask": ["subject", "SUBJECT", "subject"],
                "subject": "One"
                """), null);

        assertThat(captured().fieldMask()).containsExactly("subject");
    }

    @Test
    void acceptsZeroEphemeralAsExplicitDisable() {
        consumer.onMessage(envelope("""
                "fieldMask": ["ephemeralDurationSeconds"],
                "ephemeralDurationSeconds": 0
                """), null);

        assertThat(captured().ephemeralDurationSeconds())
                .as("0 表示明确关闭限时消息，不是缺失")
                .isZero();
    }

    @Test
    void rejectsBooleanFieldWithoutExplicitValue() {
        assertThatThrownBy(() -> consumer.onMessage(envelope("""
                "fieldMask": ["announceOnly"],
                "announceOnly": null
                """), null))
                .isInstanceOf(BusinessException.class);
        verify(metadataUpdatedSink, never()).handleMetadataUpdated(any());
    }

    @Test
    void rejectsNegativeEphemeralDuration() {
        assertThatThrownBy(() -> consumer.onMessage(envelope("""
                "fieldMask": ["ephemeralDurationSeconds"],
                "ephemeralDurationSeconds": -1
                """), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsBlankSubjectInMask() {
        assertThatThrownBy(() -> consumer.onMessage(envelope("""
                "fieldMask": ["subject"],
                "subject": "  "
                """), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsEmptyOrNonArrayFieldMask() {
        assertThatThrownBy(() -> consumer.onMessage(envelope("\"fieldMask\": []"), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> consumer.onMessage(envelope("\"fieldMask\": \"subject\""), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> consumer.onMessage(envelope("\"fieldMask\": [1, 2]"), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsAccountMismatchBackendAndGroupJid() {
        assertThatThrownBy(() -> consumer.onMessage(raw(
                "other-account", "WEB", "120363-abc@g.us", "\"fieldMask\": [\"subject\"],"
                        + "\"subject\":\"x\""), null))
                .as("envelope accountId 必须等于 data.protocolAccountId")
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> consumer.onMessage(raw(
                "protocol-account-100", "IOS", "120363-abc@g.us",
                "\"fieldMask\": [\"subject\"],\"subject\":\"x\""), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> consumer.onMessage(raw(
                "protocol-account-100", "WEB", "8613800000000@s.whatsapp.net",
                "\"fieldMask\": [\"subject\"],\"subject\":\"x\""), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void unknownFieldNamesAreForwardedForDomainToSkip() {
        // 未识别字段名不在 platform 层拒绝：白名单属于业务模型，由 group 域计指标后跳过，
        // 否则一个未知字段会阻塞同一事件里已识别的字段。
        assertThatCode(() -> consumer.onMessage(envelope("""
                "fieldMask": ["subject", "avatarUrl"],
                "subject": "One"
                """), null)).doesNotThrowAnyException();

        assertThat(captured().fieldMask()).containsExactly("subject", "avatarUrl");
    }

    private ProtocolGroupMetadataUpdatedEvent captured() {
        ArgumentCaptor<ProtocolGroupMetadataUpdatedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupMetadataUpdatedEvent.class);
        verify(metadataUpdatedSink).handleMetadataUpdated(captor.capture());
        return captor.getValue();
    }

    private static String envelope(String dataFields) {
        return raw("protocol-account-100", "WEB", "120363-abc@g.us", dataFields);
    }

    private static String raw(
            String protocolAccountId, String backend, String groupJid, String dataFields) {
        return """
                {
                  "eventId": "acc-100:group.metadata_updated:1",
                  "event": "group.metadata_updated",
                  "version": "v1",
                  "accountId": "protocol-account-100",
                  "occurredAt": "2026-08-16T04:31:40.000Z",
                  "workerId": "worker-1",
                  "data": {
                    "tenantId": 1,
                    "accountId": 100,
                    "protocolAccountId": "%s",
                    "protocolBackend": "%s",
                    "groupJid": "%s",
                    "source": "wa_groups_update",
                    %s
                  }
                }
                """.formatted(protocolAccountId, backend, groupJid, dataFields);
    }
}
