package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.mapper.WhatsappGroupMemberSnapshotMapper;
import com.armada.group.model.dto.GroupMemberBatchCommandDTO;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.model.vo.GroupMemberBatchResultVO;
import com.armada.group.service.GroupDetailProtocolPorts;
import com.armada.group.service.GroupDetailSnapshotReader;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.group.service.WhatsappGroupBusinessDepartureService;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.GroupProfilePort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupDetailMemberRemovalIdentityTest {

    private static final long GROUP_LINK_ID = 10L;
    private static final String GROUP_JID = "120363detail@g.us";
    private static final String REQUESTED_JID = "919123456789@s.whatsapp.net";
    private static final String LID_JID = "123456789012345@lid";
    private static final String OTHER_LID_JID = "987654321098765@lid";
    private static final String OTHER_PN_JID = "918000000000@s.whatsapp.net";
    private static final String PHONE = "919123456789";

    @Mock private GroupLinkMapper groupLinkMapper;
    @Mock private GroupLinkPreviewMapper previewMapper;
    @Mock private GroupExecutionAccountSelector selector;
    @Mock private FixedAccountGroupMetadataPort groupMetadataPort;
    @Mock private GroupProfilePort groupProfilePort;
    @Mock private GroupSettingsPort groupSettingsPort;
    @Mock private GroupParticipantPort groupParticipantPort;
    @Mock private GroupDetailSnapshotReader snapshotReader;
    @Mock private WhatsappGroupMemberSnapshotMapper memberSnapshotMapper;
    @Mock private GroupMetadataSyncTaskService metadataSyncTaskService;
    @Mock private AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence;
    @Mock private GroupCurrentLocalPersistence currentLocalPersistence;
    @Mock private WhatsappGroupBusinessDepartureService businessDepartureService;

    private GroupDetailServiceImpl service;
    private GroupExecutionAccount account;

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        service = new GroupDetailServiceImpl(
                groupLinkMapper,
                previewMapper,
                selector,
                new GroupDetailProtocolPorts(
                        groupMetadataPort,
                        groupProfilePort,
                        groupSettingsPort,
                        groupParticipantPort),
                snapshotReader,
                memberSnapshotMapper,
                metadataSyncTaskService,
                currentSnapshotPersistence,
                currentLocalPersistence,
                businessDepartureService);
        account = new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true);
        givenLiveTarget();
        when(selector.require(GROUP_LINK_ID)).thenReturn(account);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void kickMatchesPnRequestToLidMetadataByPhone() {
        when(groupMetadataPort.getMetadata(account.protocolRef(), GROUP_JID))
                .thenReturn(
                        metadata(List.of(participant(LID_JID, PHONE))),
                        metadata(List.of()));
        givenProtocolSuccess();

        GroupMemberBatchResultVO result = kickRequestedMember();

        assertThat(result.ok()).isTrue();
        assertThat(result.results()).singleElement().satisfies(item -> {
            assertThat(item.jid()).isEqualTo(REQUESTED_JID);
            assertThat(item.status()).isEqualTo("OK");
        });
        verify(businessDepartureService).recordConfirmedRemovals(
                eq(7L), eq(GROUP_JID), anyMap(), anyLong(), startsWith("group-detail:10:"));
        verify(memberSnapshotMapper).deleteParticipants(
                GROUP_LINK_ID, List.of(REQUESTED_JID));
    }

    @Test
    void reportedRemovalWithPnToLidIdentityChangeRemainsUnknown() {
        when(groupMetadataPort.getMetadata(account.protocolRef(), GROUP_JID))
                .thenReturn(
                        metadata(List.of(participant(REQUESTED_JID, PHONE))),
                        metadata(List.of(participant(LID_JID, PHONE))));
        givenProtocolSuccess();

        GroupMemberBatchResultVO result = kickRequestedMember();

        assertUnknown(result, REQUESTED_JID);
        verify(memberSnapshotMapper, never()).deleteParticipants(anyLong(), anyList());
        verifyNoInteractions(businessDepartureService, metadataSyncTaskService);
    }

    @Test
    void reportedRemovalWithPhoneLessLidReadbackRemainsUnknown() {
        when(groupMetadataPort.getMetadata(account.protocolRef(), GROUP_JID))
                .thenReturn(
                        metadata(List.of(participant(REQUESTED_JID, null))),
                        metadata(List.of(participant(LID_JID, null))));
        givenProtocolSuccess();

        GroupMemberBatchResultVO result = kickRequestedMember();

        assertUnknown(result, REQUESTED_JID);
        verify(memberSnapshotMapper, never()).deleteParticipants(anyLong(), anyList());
        verifyNoInteractions(businessDepartureService, metadataSyncTaskService);
    }

    @Test
    void timedOutRemovalWithPnToLidIdentityChangeRemainsUnknown() {
        when(groupMetadataPort.getMetadata(account.protocolRef(), GROUP_JID))
                .thenReturn(
                        metadata(List.of(participant(REQUESTED_JID, PHONE))),
                        metadata(List.of(participant(LID_JID, PHONE))));
        when(groupParticipantPort.updateParticipants(
                account.protocolRef(), GROUP_JID, List.of(REQUESTED_JID),
                GroupParticipantAction.REMOVE))
                .thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"));

        GroupMemberBatchResultVO result = kickRequestedMember();

        assertUnknown(result, REQUESTED_JID);
        verify(memberSnapshotMapper, never()).deleteParticipants(anyLong(), anyList());
        verifyNoInteractions(businessDepartureService, metadataSyncTaskService);
    }

    @Test
    void timedOutRemovalWithPhoneLessLidIdentityChangeRemainsUnknown() {
        when(groupMetadataPort.getMetadata(account.protocolRef(), GROUP_JID))
                .thenReturn(
                        metadata(List.of(participant(LID_JID, null))),
                        metadata(List.of(participant(OTHER_LID_JID, null))));
        when(groupParticipantPort.updateParticipants(
                account.protocolRef(), GROUP_JID, List.of(LID_JID),
                GroupParticipantAction.REMOVE))
                .thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"));

        GroupMemberBatchResultVO result = service.kickMembers(
                GROUP_LINK_ID, new GroupMemberBatchCommandDTO(List.of(LID_JID)));

        assertUnknown(result, LID_JID);
        verify(memberSnapshotMapper, never()).deleteParticipants(anyLong(), anyList());
        verifyNoInteractions(businessDepartureService, metadataSyncTaskService);
    }

    @Test
    void confirmedMissingMemberDualWritesRemovalAndSkipsStaleRefresh() {
        when(groupMetadataPort.getMetadata(account.protocolRef(), GROUP_JID))
                .thenReturn(
                        metadata(List.of(participant(REQUESTED_JID, PHONE))),
                        metadata(List.of()));
        givenProtocolSuccess();

        GroupMemberBatchResultVO result = kickRequestedMember();

        assertThat(result.ok()).isTrue();
        assertThat(result.results()).singleElement().satisfies(item -> {
            assertThat(item.jid()).isEqualTo(REQUESTED_JID);
            assertThat(item.status()).isEqualTo("OK");
        });
        verify(businessDepartureService).recordConfirmedRemovals(
                eq(7L), eq(GROUP_JID), anyMap(), anyLong(), startsWith("group-detail:10:"));
        verify(memberSnapshotMapper).deleteParticipants(
                GROUP_LINK_ID, List.of(REQUESTED_JID));
        verifyNoInteractions(metadataSyncTaskService);
    }

    @Test
    void reportedRemovalWithOnlyDifferentPnMemberConfirmsRemoval() {
        when(groupMetadataPort.getMetadata(account.protocolRef(), GROUP_JID))
                .thenReturn(
                        metadata(List.of(participant(REQUESTED_JID, null))),
                        metadata(List.of(participant(OTHER_PN_JID, null))));
        givenProtocolSuccess();

        GroupMemberBatchResultVO result = kickRequestedMember();

        assertThat(result.ok()).isTrue();
        verify(businessDepartureService).recordConfirmedRemovals(
                eq(7L), eq(GROUP_JID), anyMap(), anyLong(), startsWith("group-detail:10:"));
        verify(memberSnapshotMapper).deleteParticipants(
                GROUP_LINK_ID, List.of(REQUESTED_JID));
        verifyNoInteractions(metadataSyncTaskService);
    }

    private static void assertUnknown(GroupMemberBatchResultVO result, String jid) {
        assertThat(result.ok()).isFalse();
        assertThat(result.results()).singleElement().satisfies(item -> {
            assertThat(item.jid()).isEqualTo(jid);
            assertThat(item.status()).isEqualTo("UNKNOWN");
        });
    }

    private GroupMemberBatchResultVO kickRequestedMember() {
        return service.kickMembers(
                GROUP_LINK_ID,
                new GroupMemberBatchCommandDTO(List.of(REQUESTED_JID)));
    }

    private void givenProtocolSuccess() {
        when(groupParticipantPort.updateParticipants(
                account.protocolRef(), GROUP_JID, List.of(REQUESTED_JID),
                GroupParticipantAction.REMOVE))
                .thenReturn(new GroupParticipantBatchResult(
                        false,
                        List.of(new GroupParticipantBatchResult.Item(
                                REQUESTED_JID, "OK", "200"))));
    }

    private void givenLiveTarget() {
        GroupLink link = new GroupLink();
        link.setId(GROUP_LINK_ID);
        link.setGroupName("群名");
        when(groupLinkMapper.selectActiveById(GROUP_LINK_ID)).thenReturn(link);

        GroupLinkPreview preview = new GroupLinkPreview();
        preview.setGroupJid(GROUP_JID);
        preview.setWaSubject("群名");
        when(snapshotReader.profile(GROUP_LINK_ID)).thenReturn(preview);
    }

    private static GroupMetadataResult metadata(
            List<GroupParticipantResult> participants) {
        return new GroupMetadataResult(
                GROUP_JID,
                "群名",
                null,
                null,
                null,
                true,
                false,
                false,
                false,
                false,
                0,
                null,
                false,
                "协议未声明邀请能力",
                false,
                true,
                participants);
    }

    private static GroupParticipantResult participant(String jid, String phone) {
        return new GroupParticipantResult(jid, phone, false, false, null);
    }
}
