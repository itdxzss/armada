package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 群 metadata 当前模型持久化单测。 */
@ExtendWith(MockitoExtension.class)
class GroupMetadataSnapshotPersistenceImplTest {

    @Mock private GroupLinkPreviewMapper previewMapper;
    @Mock private GroupLinkMapper groupLinkMapper;
    @Mock private AccountGroupMembershipMapper membershipMapper;
    @Mock private GroupInviteLinkService inviteLinkService;
    @Mock private AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence;
    @Mock private GroupCurrentLocalPersistence currentLocalPersistence;

    @Test
    void currentFactsDoNotDependOnCompatibilityWriterAffectedRows() {
        GroupLinkPreview preview = preview("新群名");

        assertThat(service().persist(preview, List.of())).isTrue();

        verify(previewMapper, never()).upsertCreatorCompatibility(anyList());
        verify(groupLinkMapper).updateGroupName(10L, "新群名", 2_000L);
        verify(currentSnapshotPersistence).replaceCompleteGroupMetadataSnapshot(
                preview, List.of(), 2_000L, "metadata:10:2000");
    }

    @Test
    void creatorCompatibilityWritesOnlyWhenCreatorWasObserved() {
        GroupLinkPreview preview = preview(null);
        preview.setOwnerPhone("15550000001");
        preview.setOwnerPhoneObserved(true);
        when(previewMapper.upsertCreatorCompatibility(List.of(preview))).thenReturn(0);

        assertThat(service().persist(preview, List.of())).isTrue();

        verify(previewMapper).upsertCreatorCompatibility(List.of(preview));
        verify(groupLinkMapper, never()).updateGroupName(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void metadataInviteUsesCurrentInviteWriter() {
        GroupLinkPreview preview = preview("群名");
        preview.setInviteCode("current-invite");

        assertThat(service().persist(preview, List.of())).isTrue();

        ArgumentCaptor<GroupInviteLinkObservation> observation =
                ArgumentCaptor.forClass(GroupInviteLinkObservation.class);
        verify(inviteLinkService).applyCurrentInvite(observation.capture());
        assertThat(observation.getValue()).satisfies(value -> {
            assertThat(value.groupLinkId()).isEqualTo(10L);
            assertThat(value.groupJid()).isEqualTo("120363history@g.us");
            assertThat(value.inviteCode()).isEqualTo("current-invite");
            assertThat(value.observedAt()).isEqualTo(2_000L);
        });
    }

    @Test
    void completeMembersAndControlledRolesWriteCurrentModel() {
        GroupLinkPreview preview = preview("群名");
        AccountGroupMembership controlled = new AccountGroupMembership();
        controlled.setAccountId(301L);
        controlled.setGroupJid("120363history@g.us");
        controlled.setAdmin(true);
        when(membershipMapper.selectControlledMembershipsByGroupLinkId(10L))
                .thenReturn(List.of(controlled));

        WhatsappGroupMemberSnapshot member = new WhatsappGroupMemberSnapshot();
        member.setParticipantJid("1001@s.whatsapp.net");
        member.setPhone("1001");
        member.setIsAdmin(true);
        member.setIsOwner(false);
        member.setSnapshotAt(2_100L);

        assertThat(service().persist(preview, List.of(member))).isTrue();

        verify(currentSnapshotPersistence).replaceCompleteGroupMetadataSnapshot(
                preview,
                List.of(new GroupParticipantResult(
"1001@s.whatsapp.net", null, "1001", true, false, null)),
                2_100L,
                "metadata:10:2100");
        verify(currentSnapshotPersistence).applyControlledParticipantObservation(
                301L, "120363history@g.us", true, true,
                2_000L, "metadata:10:2000", "GROUP_SNAPSHOT");
    }

    private GroupMetadataSnapshotPersistenceImpl service() {
        return new GroupMetadataSnapshotPersistenceImpl(
                previewMapper, groupLinkMapper, membershipMapper, inviteLinkService,
                currentSnapshotPersistence, currentLocalPersistence);
    }

    private static GroupLinkPreview preview(String subject) {
        GroupLinkPreview preview = new GroupLinkPreview();
        preview.setGroupLinkId(10L);
        preview.setGroupJid("120363history@g.us");
        preview.setWaSubject(subject);
        preview.setMetadataObservedAt(2_000L);
        preview.setUpdatedAt(2_000L);
        return preview;
    }
}
