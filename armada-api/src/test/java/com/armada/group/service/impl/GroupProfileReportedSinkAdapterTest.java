package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.armada.group.model.dto.GroupMetadataPatch;
import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.mapper.GroupBatchTaskItemMapper;
import com.armada.group.model.dto.GroupMetadataPatchField;
import com.armada.group.model.enums.GroupMetadataFieldSource;
import com.armada.group.service.GroupMetadataPatchService;
import com.armada.platform.kafka.consumer.group.ProtocolGroupProfileReportedEvent;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 锁定单群资料上报事件的接线：资料走字段级 reducer，成员走完整快照落库。
 *
 * <p>最关键的一条是成员部分只在协议声明列表完整时才执行——既有的完整快照落库会把库里有而列表里
 * 没有的成员判为已退群，误判会直接影响营销选号与拉群选管理员。</p>
 */
@ExtendWith(MockitoExtension.class)
class GroupProfileReportedSinkAdapterTest {

    @Mock
    private GroupMetadataPatchService patchService;

    @Mock
    private AccountGroupCurrentSnapshotPersistenceImpl snapshotPersistence;

    @Mock
    private GroupMetadataSyncTaskMapper taskMapper;

    @Mock
    private GroupBatchTaskItemMapper batchItemMapper;

    @InjectMocks
    private GroupProfileReportedSinkAdapter adapter;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void completeMembersAreWrittenAsFullSnapshot() {
        adapter.handleProfileReported(event(true, List.of(
                new ProtocolGroupProfileReportedEvent.Member(
                        "123456789012345@lid", "123456789012345@lid", "919000000001",
                        true, false, "admin"),
                new ProtocolGroupProfileReportedEvent.Member(
                        "919000000002@s.whatsapp.net", null, "919000000002",
                        false, false, null))));

        ArgumentCaptor<List<GroupParticipantResult>> captor = ArgumentCaptor.captor();
        verify(snapshotPersistence).replaceCompleteParticipantSnapshot(
                anyString(), captor.capture(), anyLong(), anyString());
        List<GroupParticipantResult> participants = captor.getValue();
        assertThat(participants).hasSize(2);
        assertThat(participants.get(0).phone()).isEqualTo("919000000001");
        assertThat(participants.get(0).pnJid())
                .as("号码要还原成 PN JID 供落库层归位到 pn_jid 列")
                .isEqualTo("919000000001@s.whatsapp.net");
        assertThat(participants.get(0).admin()).isTrue();
    }

    @Test
    void completeEmptyMembersStillClearsDepartedMembers() {
        ProtocolGroupProfileReportedEvent event = event(true, List.of());

        adapter.handleProfileReported(event);

        verify(snapshotPersistence).replaceCompleteParticipantSnapshot(
                eq(event.groupJid()), eq(List.of()), eq(event.occurredAt()), eq(event.eventId()));
    }

    @Test
    void incompleteMembersAreSkippedToAvoidFalseDeparture() {
        adapter.handleProfileReported(event(false, List.of(
                new ProtocolGroupProfileReportedEvent.Member(
                        "919000000002@s.whatsapp.net", null, "919000000002",
                        false, false, null))));

        verify(snapshotPersistence, never())
                .replaceCompleteParticipantSnapshot(anyString(), any(), anyLong(), anyString());
        // 资料字段仍须写入：成员不可信不代表资料不可信。
        verify(patchService).applyPatch(any());
    }

    @Test
    void profileFieldsUseSnapshotSourceSoEventsWinAtSameTime() {
        adapter.handleProfileReported(event(true, List.of(
                new ProtocolGroupProfileReportedEvent.Member(
                        "919000000002@s.whatsapp.net", null, "919000000002",
                        false, false, null))));

        ArgumentCaptor<GroupMetadataPatch> captor =
                ArgumentCaptor.forClass(GroupMetadataPatch.class);
        verify(patchService).applyPatch(captor.capture());
        GroupMetadataPatch patch = captor.getValue();
        assertThat(patch.source())
                .as("完整快照的可信度必须低于精确变更事件，否则迟到快照会压过新事件")
                .isEqualTo(GroupMetadataFieldSource.PROFILE_SNAPSHOT);
        assertThat(patch.fieldMask()).containsExactly(GroupMetadataPatchField.SUBJECT);
    }

    @Test
    void missingMembersMeanProfileOnly() {
        adapter.handleProfileReported(event(false, List.of()));

        verify(snapshotPersistence, never())
                .replaceCompleteParticipantSnapshot(anyString(), any(), anyLong(), anyString());
        verify(patchService).applyPatch(any());
    }

    @Test
    void tenantContextIsClearedAfterHandling() {
        adapter.handleProfileReported(event(false, List.of()));

        assertThat(TenantContext.get())
                .as("消费线程被复用，租户上下文必须归还")
                .isNull();
    }

    @Test
    void phoneAlreadyInJidFormIsNotSuffixedTwice() {
        adapter.handleProfileReported(event(true, List.of(
                new ProtocolGroupProfileReportedEvent.Member(
                        "919000000003@s.whatsapp.net", null, "919000000003@s.whatsapp.net",
                        false, false, null))));

        ArgumentCaptor<List<GroupParticipantResult>> captor = ArgumentCaptor.captor();
        verify(snapshotPersistence).replaceCompleteParticipantSnapshot(
                anyString(), captor.capture(), anyLong(), anyString());
        assertThat(captor.getValue().get(0).pnJid())
                .as("协议侧已把号码还原成完整 JID 时不得再拼一次后缀："
                        + "绑定按 pn_jid 等值关联，双后缀会让受控账号永远匹配不上自己的群")
                .isEqualTo("919000000003@s.whatsapp.net");
    }

    private static ProtocolGroupProfileReportedEvent event(
            boolean membersComplete, List<ProtocolGroupProfileReportedEvent.Member> members) {
        return new ProtocolGroupProfileReportedEvent(
                "acc-100:group.profile_reported:1",
                1L,
                100L,
                "protocol-account-100",
                "WEB",
                "120363-abc@g.us",
                List.of("subject"),
                "Alpha",
                null, null, null, null, null, null,
                members,
                membersComplete,
                "online_full_metadata",
                2_000L,
                "worker-1",
                null);
    }
}
