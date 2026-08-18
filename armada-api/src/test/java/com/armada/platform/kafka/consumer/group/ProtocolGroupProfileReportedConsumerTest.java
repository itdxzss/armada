package com.armada.platform.kafka.consumer.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 锁定 group.profile_reported 的协议契约校验。
 *
 * <p>该事件是首次建档的逐群上报，同时承载资料字段与成员列表。最关键的约束是
 * {@code membersComplete}：它是退群判定的授权开关，缺省必须按"不能判定"处理，声明完整却给空列表
 * 必须直接拒绝——否则控端会把全群成员判为已退群。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProtocolGroupProfileReportedConsumerTest {

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
    void acceptsProfileWithMembersAndCompleteFlag() {
        consumer.onMessage(envelope("""
                "fieldMask": ["subject", "announceOnly"],
                "subject": "Alpha",
                "announceOnly": false,
                "membersComplete": true,
                "members": [
                  {"jid":"123456789012345@lid","lid":"123456789012345@lid",
                   "phone":"919000000001","admin":true,"owner":false,"role":"admin"},
                  {"jid":"919000000002@s.whatsapp.net","phone":"919000000002",
                   "admin":false,"owner":false}
                ]
                """), null);

        ProtocolGroupProfileReportedEvent event = captured();
        assertThat(event.fieldMask()).containsExactly("subject", "announceOnly");
        assertThat(event.subject()).isEqualTo("Alpha");
        assertThat(event.announceOnly()).isFalse();
        assertThat(event.membersComplete()).isTrue();
        assertThat(event.members()).hasSize(2);
        assertThat(event.members().get(0).phone())
                .as("号码由协议侧还原后带上来，控端据它关联受控账号")
                .isEqualTo("919000000001");
        assertThat(event.members().get(0).admin()).isTrue();
    }

    @Test
    void membersCompleteDefaultsToFalseWhenAbsent() {
        consumer.onMessage(envelope("""
                "fieldMask": ["subject"],
                "subject": "Alpha",
                "members": [{"jid":"919000000002@s.whatsapp.net","phone":"919000000002"}]
                """), null);

        assertThat(captured().membersComplete())
                .as("退群判定必须由协议明确授权，缺字段时按不能判定处理")
                .isFalse();
    }

    @Test
    void rejectsCompleteFlagWithEmptyMemberList() {
        assertThatThrownBy(() -> consumer.onMessage(envelope("""
                "fieldMask": ["subject"],
                "subject": "Alpha",
                "membersComplete": true,
                "members": []
                """), null))
                .as("声明完整却没有成员会让控端把全群判为退群")
                .isInstanceOf(BusinessException.class);
        verify(profileReportedSink, never()).handleProfileReported(any());
    }

    @Test
    void allowsProfileWithoutMembers() {
        assertThatCode(() -> consumer.onMessage(envelope("""
                "fieldMask": ["subject"],
                "subject": "Alpha"
                """), null)).doesNotThrowAnyException();

        ProtocolGroupProfileReportedEvent event = captured();
        assertThat(event.members()).as("只观察资料不观察成员是合法形态").isEmpty();
        assertThat(event.membersComplete()).isFalse();
    }

    @Test
    void rejectsMemberWithoutAnyIdentity() {
        assertThatThrownBy(() -> consumer.onMessage(envelope("""
                "fieldMask": ["subject"],
                "subject": "Alpha",
                "members": [{"admin":true}]
                """), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void reusesMetadataFieldValidationRules() {
        // 资料字段的类型校验与 group.metadata_updated 共用同一套规则。
        assertThatThrownBy(() -> consumer.onMessage(envelope("""
                "fieldMask": ["announceOnly"],
                "announceOnly": null
                """), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> consumer.onMessage(envelope("""
                "fieldMask": ["ephemeralDurationSeconds"],
                "ephemeralDurationSeconds": -1
                """), null))
                .isInstanceOf(BusinessException.class);
    }

    private ProtocolGroupProfileReportedEvent captured() {
        ArgumentCaptor<ProtocolGroupProfileReportedEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupProfileReportedEvent.class);
        verify(profileReportedSink).handleProfileReported(captor.capture());
        return captor.getValue();
    }

    private static String envelope(String dataFields) {
        return """
                {
                  "eventId": "acc-100:group.profile_reported:1",
                  "event": "group.profile_reported",
                  "version": "v1",
                  "accountId": "protocol-account-100",
                  "occurredAt": "2026-08-16T04:31:40.000Z",
                  "workerId": "worker-1",
                  "data": {
                    "tenantId": 1,
                    "accountId": 100,
                    "protocolAccountId": "protocol-account-100",
                    "protocolBackend": "WEB",
                    "groupJid": "120363-abc@g.us",
                    "source": "online_full_metadata",
                    %s
                  }
                }
                """.formatted(dataFields);
    }
}
