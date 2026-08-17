package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.armada.group.model.dto.GroupSubjectCommandDTO;
import com.armada.group.model.dto.GroupTimedMessageCommandDTO;
import com.armada.group.model.dto.GroupSettingCommandDTO;
import com.armada.group.model.dto.GroupMemberBatchCommandDTO;
import com.armada.group.model.dto.GroupParticipantObservation;
import com.armada.group.model.enums.GroupPermissionKey;
import com.armada.group.model.enums.WhatsappGroupMemberStateSource;
import com.armada.group.model.enums.GroupTimedMessageMode;
import com.armada.group.model.enums.GroupMetadataSyncStatus;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.GroupAvatarUpdateVO;
import com.armada.group.model.vo.GroupDetailVO;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.model.vo.GroupMemberBatchResultVO;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupDetailProtocolPorts;
import com.armada.group.service.GroupDetailSnapshotReader;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.group.service.WhatsappGroupBusinessDepartureService;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.model.result.GroupPictureResult;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupProfilePort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class GroupDetailServiceImplTest {

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private GroupExecutionAccountSelector selector;

    @Mock
    private FixedAccountGroupMetadataPort groupMetadataPort;

    @Mock
    private GroupProfilePort groupProfilePort;

    @Mock
    private GroupSettingsPort groupSettingsPort;

    @Mock
    private GroupParticipantPort groupParticipantPort;

    @Mock
    private GroupDetailSnapshotReader snapshotReader;

    @Mock
    private GroupMetadataSyncTaskService metadataSyncTaskService;

    @Mock
    private AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence;

    @Mock
    private GroupCurrentLocalPersistence currentLocalPersistence;

    @Mock
    private WhatsappGroupBusinessDepartureService businessDepartureService;

    private GroupDetailServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GroupDetailServiceImpl(
                groupLinkMapper,
                selector,
                new GroupDetailProtocolPorts(
                        groupMetadataPort,
                        groupProfilePort,
                        groupSettingsPort,
                        groupParticipantPort),
                snapshotReader,
                metadataSyncTaskService,
                currentSnapshotPersistence,
                currentLocalPersistence,
                businessDepartureService);
    }

    @Test
    void detailReadsPersistedMetadataAndMembersWithoutProtocolCall() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "本地群名", "本地备注"));
        GroupLinkPreview preview = preview("120363detail@g.us");
        preview.setWaSubject("真实群名");
        preview.setMetadataObservedAt(1_722_470_400_000L);
        preview.setAdminOnlyEditInfo(false);
        preview.setAnnounceOnly(true);
        preview.setMemberAddMode(true);
        preview.setMemberLinkMode(true);
        preview.setJoinApprovalMode(true);
        preview.setEphemeralDurationSeconds(604_800);
        when(snapshotReader.profile(10L)).thenReturn(preview);
        when(snapshotReader.members(10L)).thenReturn(List.of(snapshotMember()));
        when(snapshotReader.task(10L)).thenReturn(syncTask(GroupMetadataSyncStatus.SUCCEEDED));

        GroupDetailVO result = service.detail(10L);

        assertThat(result.groupName()).isEqualTo("真实群名");
        assertThat(result.remark()).isEqualTo("本地备注");
        assertThat(result.avatarUrl()).isEqualTo("https://pps.whatsapp.net/current.jpg");
        assertThat(result.liveStateAvailable()).isTrue();
        assertThat(result.permissions().editGroupSettings()).isTrue();
        assertThat(result.permissions().sendMessages()).isFalse();
        assertThat(result.permissions().addMembers()).isTrue();
        assertThat(result.permissions().inviteViaLink()).isTrue();
        assertThat(result.capabilities().inviteViaLink().supported()).isTrue();
        assertThat(result.permissions().adminApproveNewMembers()).isTrue();
        assertThat(result.timedMessageMode()).isEqualTo("7d");
        assertThat(result.members()).hasSize(1);
        assertThat(result.members().get(0).owner()).isTrue();
        assertThat(result.metadataSyncStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.metadataSyncedAt()).isEqualTo(1_722_470_400_000L);
        verifyNoInteractions(groupMetadataPort, selector);
    }

    @Test
    void detailWithoutCompletedSnapshotReturnsPendingState() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "本地群名", "本地备注"));
        when(snapshotReader.profile(10L)).thenReturn(preview("120363detail@g.us"));
        when(snapshotReader.task(10L)).thenReturn(syncTask(GroupMetadataSyncStatus.PENDING));

        GroupDetailVO result = service.detail(10L);

        assertUnavailable(result, "详情待同步");
        assertThat(result.groupName()).isEqualTo("本地群名");
        assertThat(result.remark()).isEqualTo("本地备注");
        assertThat(result.avatarUrl()).isEqualTo("https://pps.whatsapp.net/current.jpg");
        assertThat(result.metadataSyncStatus()).isEqualTo("PENDING");
        verifyNoInteractions(groupMetadataPort, selector);
    }

    @Test
    void requestMetadataSyncOnlyEnqueuesDurableTask() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "本地群名", "本地备注"));
        when(snapshotReader.profile(10L)).thenReturn(preview("120363detail@g.us"));

        var result = service.requestMetadataSync(10L);

        assertThat(result.accepted()).isTrue();
        assertThat(result.status()).isEqualTo("PENDING");
        verify(metadataSyncTaskService).enqueue(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(GroupMetadataSyncTrigger.MANUAL_REFRESH),
                org.mockito.ArgumentMatchers.anyLong());
        verifyNoInteractions(groupMetadataPort, selector);
    }

    @Test
    void detailKeepsUnknownEphemeralDurationUnknown() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "本地群名", null));
        GroupLinkPreview preview = preview("120363detail@g.us");
        preview.setMetadataObservedAt(1_722_470_400_000L);
        preview.setEphemeralDurationSeconds(123);
        when(snapshotReader.profile(10L)).thenReturn(preview);
        when(snapshotReader.members(10L)).thenReturn(List.of());
        when(snapshotReader.task(10L)).thenReturn(syncTask(GroupMetadataSyncStatus.SUCCEEDED));

        GroupDetailVO result = service.detail(10L);

        assertThat(result.liveStateAvailable()).isTrue();
        assertThat(result.timedMessageMode()).isNull();
    }

    @Test
    void membersRejectsUnavailablePersistedSnapshot() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "本地群名", null));
        when(snapshotReader.profile(10L)).thenReturn(preview("120363detail@g.us"));
        when(snapshotReader.task(10L)).thenReturn(syncTask(GroupMetadataSyncStatus.DEFERRED));

        assertThatThrownBy(() -> service.members(10L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("详情待同步");
    }

    @Test
    void updateSubjectUsesSelectedAccountAndWritesMirrorAfterProtocolSuccess() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "旧群名", "备注"));
        when(snapshotReader.profile(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        when(groupLinkMapper.updateGroupName(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("新群名"),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

        service.updateSubject(10L, new GroupSubjectCommandDTO(" 新群名 "));

        InOrder order = inOrder(groupProfilePort, groupLinkMapper);
        order.verify(groupProfilePort).updateSubject(webAccount(), "120363detail@g.us", "新群名");
        order.verify(groupLinkMapper).updateGroupName(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("新群名"),
                org.mockito.ArgumentMatchers.anyLong());
        org.mockito.ArgumentCaptor<GroupLinkPreview> currentCaptor =
                org.mockito.ArgumentCaptor.forClass(GroupLinkPreview.class);
        verify(currentSnapshotPersistence).applyConfirmedMetadata(currentCaptor.capture());
        assertThat(currentCaptor.getValue().getWaSubject()).isEqualTo("新群名");
    }

    @Test
    void updateSubjectPreservesSelectedAndroidProtocolReference() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "旧群名", null));
        when(snapshotReader.profile(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(
                7L, "ANDROID", "android_7", "919000000001", true));
        when(groupLinkMapper.updateGroupName(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("新群名"),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

        service.updateSubject(10L, new GroupSubjectCommandDTO("新群名"));

        verify(groupProfilePort).updateSubject(
                androidAccount(), "120363detail@g.us", "新群名");
    }

    @Test
    void updateSubjectTimeoutConfirmedBySameAccountWritesMirror() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "旧群名", null));
        when(snapshotReader.profile(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        doThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"))
                .when(groupProfilePort).updateSubject(webAccount(), "120363detail@g.us", "新群名");
        when(groupMetadataPort.getMetadata(webAccount(), "120363detail@g.us"))
                .thenReturn(metadata("新群名", false, false, false, false, 0));
        when(groupLinkMapper.updateGroupName(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("新群名"),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

        service.updateSubject(10L, new GroupSubjectCommandDTO("新群名"));

        verify(selector).require(10L);
        verify(groupMetadataPort).getMetadata(webAccount(), "120363detail@g.us");
        verify(groupLinkMapper).updateGroupName(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("新群名"),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void updateSubjectTimeoutUnconfirmedThrowsDedicatedError() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "旧群名", null));
        when(snapshotReader.profile(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        doThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"))
                .when(groupProfilePort).updateSubject(webAccount(), "120363detail@g.us", "新群名");
        when(groupMetadataPort.getMetadata(webAccount(), "120363detail@g.us"))
                .thenReturn(metadata("仍是旧群名", false, false, false, false, 0));

        assertThatThrownBy(() -> service.updateSubject(10L, new GroupSubjectCommandDTO("新群名")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.GROUP_PROTOCOL_TIMEOUT.code()));

        verify(selector).require(10L);
        verify(groupLinkMapper, never()).updateGroupName(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void updateSubjectMapsProtocolPermissionDeniedToBusinessError() {
        givenLiveTarget();
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        doThrow(new ProtocolException(
                ProtocolErrorCode.GROUP_PERMISSION_DENIED, "not admin"))
                .when(groupProfilePort)
                .updateSubject(webAccount(), "120363detail@g.us", "新群名");

        assertThatThrownBy(() -> service.updateSubject(
                10L, new GroupSubjectCommandDTO("新群名")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ErrorCode.GROUP_PERMISSION_DENIED.code()));
    }

    @Test
    void updateTimedMessageUsesSelectedAccountAndConfirmsMetadata() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(snapshotReader.profile(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        when(groupMetadataPort.getMetadata(webAccount(), "120363detail@g.us"))
                .thenReturn(metadata("群名", false, false, false, false, 604_800));

        service.updateTimedMessage(
                10L, new GroupTimedMessageCommandDTO(GroupTimedMessageMode.DAYS_7));

        InOrder order = inOrder(groupSettingsPort, groupMetadataPort);
        order.verify(groupSettingsPort)
                .setEphemeralDuration(webAccount(), "120363detail@g.us", 604_800);
        order.verify(groupMetadataPort).getMetadata(webAccount(), "120363detail@g.us");
        verify(selector).require(10L);
    }

    @Test
    void updateTimedMessageTimeoutConfirmedBySameAccountSucceeds() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(snapshotReader.profile(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        doThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"))
                .when(groupSettingsPort)
                .setEphemeralDuration(webAccount(), "120363detail@g.us", 604_800);
        when(groupMetadataPort.getMetadata(webAccount(), "120363detail@g.us"))
                .thenReturn(metadata("群名", false, false, false, false, 604_800));

        service.updateTimedMessage(
                10L, new GroupTimedMessageCommandDTO(GroupTimedMessageMode.DAYS_7));

        verify(selector).require(10L);
        verify(groupMetadataPort).getMetadata(webAccount(), "120363detail@g.us");
    }

    @Test
    void updateTimedMessageUnconfirmedThrowsDedicatedError() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(snapshotReader.profile(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        when(groupMetadataPort.getMetadata(webAccount(), "120363detail@g.us"))
                .thenReturn(metadata("群名", false, false, false, false, 86_400));

        assertThatThrownBy(() -> service.updateTimedMessage(
                10L, new GroupTimedMessageCommandDTO(GroupTimedMessageMode.DAYS_7)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ErrorCode.GROUP_PROTOCOL_TIMEOUT.code()));

        verify(selector).require(10L);
    }

    @Test
    void updateTimedMessageMapsProtocolPermissionDeniedToBusinessError() {
        givenLiveTarget();
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        doThrow(new ProtocolException(
                ProtocolErrorCode.GROUP_PERMISSION_DENIED, "not admin"))
                .when(groupSettingsPort)
                .setEphemeralDuration(webAccount(), "120363detail@g.us", 604_800);

        assertThatThrownBy(() -> service.updateTimedMessage(
                10L, new GroupTimedMessageCommandDTO(GroupTimedMessageMode.DAYS_7)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ErrorCode.GROUP_PERMISSION_DENIED.code()));
    }

    @Test
    void updateSettingUsesLocalAdminAndReturnsAfterProtocolSuccess() {
        givenLiveTarget();
        when(selector.requireAdmin(10L)).thenReturn(
                new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        service.updateSetting(10L, new GroupSettingCommandDTO(
                GroupPermissionKey.ADD_MEMBERS, true));

        verify(groupSettingsPort)
                .setAddMembersAllowed(webAccount(), "120363detail@g.us", true);
        verify(groupMetadataPort, never()).getMetadata(
                webAccount(), "120363detail@g.us");
        verify(currentSnapshotPersistence, never()).applyConfirmedMetadata(
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(metadataSyncTaskService);
        verify(selector).requireAdmin(10L);
        verify(selector, never()).require(10L);
    }

    @Test
    void disablingMemberEditUsesAvailableGroupAdminAndPersistsAdminOnlyMode() {
        givenLiveTarget();
        when(selector.requireAdmin(10L)).thenReturn(
                new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        service.updateSetting(10L, new GroupSettingCommandDTO(
                GroupPermissionKey.EDIT_GROUP_SETTINGS, false));

        verify(selector).requireAdmin(10L);
        verify(groupSettingsPort).setEditGroupSettingsAllowed(
                webAccount(), "120363detail@g.us", false);
        verify(groupMetadataPort, never()).getMetadata(
                webAccount(), "120363detail@g.us");
        verify(currentSnapshotPersistence, never()).applyConfirmedMetadata(
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(metadataSyncTaskService);
    }

    @Test
    void updateSendMessagesUsesAndroidSettingsAndSkipsSynchronousMetadataRead() {
        givenLiveTarget();
        when(selector.requireAdmin(10L)).thenReturn(new GroupExecutionAccount(
                7L, "ANDROID", "android_7", "919000000001", true));
        service.updateSetting(10L, new GroupSettingCommandDTO(
                GroupPermissionKey.SEND_MESSAGES, false));

        verify(groupSettingsPort).setSendMessagesAllowed(
                androidAccount(), "120363detail@g.us", false);
        verify(groupMetadataPort, never()).getMetadata(
                androidAccount(), "120363detail@g.us");
        verify(currentSnapshotPersistence, never()).applyConfirmedMetadata(
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(metadataSyncTaskService);
    }

    @Test
    void updateSettingMapsProtocolPermissionDeniedToBusinessError() {
        givenLiveTarget();
        when(selector.requireAdmin(10L)).thenReturn(
                new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        doThrow(new ProtocolException(
                ProtocolErrorCode.GROUP_PERMISSION_DENIED, "not admin"))
                .when(groupSettingsPort)
                .setSendMessagesAllowed(webAccount(), "120363detail@g.us", false);

        assertThatThrownBy(() -> service.updateSetting(10L, new GroupSettingCommandDTO(
                GroupPermissionKey.SEND_MESSAGES, false)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ErrorCode.GROUP_PERMISSION_DENIED.code()));

        verify(selector).requireAdmin(10L);
    }

    @Test
    void updateSettingDoesNotReadMetadataAfterProtocolSuccess() {
        givenLiveTarget();
        when(selector.requireAdmin(10L)).thenReturn(
                new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        service.updateSetting(10L, new GroupSettingCommandDTO(
                GroupPermissionKey.SEND_MESSAGES, false));

        verify(groupSettingsPort).setSendMessagesAllowed(
                webAccount(), "120363detail@g.us", false);
        verify(groupMetadataPort, never()).getMetadata(
                webAccount(), "120363detail@g.us");
    }

    @Test
    void updateSettingTimeoutIsReportedWithoutMetadataConfirmation() {
        givenLiveTarget();
        when(selector.requireAdmin(10L)).thenReturn(
                new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        doThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"))
                .when(groupSettingsPort)
                .setEditGroupSettingsAllowed(webAccount(), "120363detail@g.us", true);
        assertThatThrownBy(() -> service.updateSetting(10L, new GroupSettingCommandDTO(
                GroupPermissionKey.EDIT_GROUP_SETTINGS, true)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ErrorCode.GROUP_PROTOCOL_TIMEOUT.code()));

        verify(selector).requireAdmin(10L);
        verify(groupMetadataPort, never()).getMetadata(
                webAccount(), "120363detail@g.us");
        verifyNoInteractions(metadataSyncTaskService);
    }

    @Test
    void updateSettingReturnsAfterProtocolSuccessWithoutReadingMetadata() {
        givenLiveTarget();
        when(selector.requireAdmin(10L)).thenReturn(
                new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        service.updateSetting(10L, new GroupSettingCommandDTO(
                GroupPermissionKey.ADMIN_APPROVE_NEW_MEMBERS, true));

        verify(selector).requireAdmin(10L);
        verify(groupSettingsPort).setJoinApprovalEnabled(
                webAccount(), "120363detail@g.us", true);
        verify(groupMetadataPort, never()).getMetadata(
                webAccount(), "120363detail@g.us");
    }

    @Test
    void updateSettingPropagatesUnsupportedInviteViaLinkFromMutation() {
        givenLiveTarget();
        when(selector.requireAdmin(10L)).thenReturn(
                new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        doThrow(new ProtocolException(
                ProtocolErrorCode.GROUP_CAPABILITY_UNSUPPORTED, "unsupported"))
                .when(groupSettingsPort)
                .setInviteViaLinkAllowed(webAccount(), "120363detail@g.us", true);

        assertThatThrownBy(() -> service.updateSetting(10L, new GroupSettingCommandDTO(
                GroupPermissionKey.INVITE_VIA_LINK, true)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ErrorCode.GROUP_CAPABILITY_UNSUPPORTED.code()));

        verify(selector).requireAdmin(10L);
        verify(groupSettingsPort).setInviteViaLinkAllowed(
                webAccount(), "120363detail@g.us", true);
        verifyNoInteractions(groupMetadataPort);
    }

    @Test
    void updateInviteViaLinkUsesAvailableGroupAdminAndReturnsAfterProtocolSuccess() {
        givenLiveTarget();
        when(selector.requireAdmin(10L)).thenReturn(
                new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        service.updateSetting(10L, new GroupSettingCommandDTO(
                GroupPermissionKey.INVITE_VIA_LINK, true));

        verify(selector).requireAdmin(10L);
        verify(selector, never()).require(10L);
        verify(groupSettingsPort).setInviteViaLinkAllowed(
                webAccount(), "120363detail@g.us", true);
        verify(groupMetadataPort, never()).getMetadata(
                webAccount(), "120363detail@g.us");
        verify(currentSnapshotPersistence, never()).applyConfirmedMetadata(
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(metadataSyncTaskService);
    }

    @Test
    void demoteMembersProtectsOwnerAndFillsMissingProtocolResults() {
        givenLiveTarget();
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        when(groupMetadataPort.getMetadata(webAccount(), "120363detail@g.us"))
                .thenReturn(metadataWithParticipants(List.of(
                        participant("owner@s.whatsapp.net", true, true),
                        participant("admin-a@s.whatsapp.net", true, false),
                        participant("admin-b@s.whatsapp.net", true, false))));
        when(groupParticipantPort.updateParticipants(
                webAccount(),
                "120363detail@g.us",
                List.of("admin-a@s.whatsapp.net", "admin-b@s.whatsapp.net"),
                GroupParticipantAction.DEMOTE))
                .thenReturn(new GroupParticipantBatchResult(
                        true,
                        List.of(new GroupParticipantBatchResult.Item(
                                "admin-a@s.whatsapp.net", "OK", "200"))));

        GroupMemberBatchResultVO result = service.demoteMembers(
                10L,
                new GroupMemberBatchCommandDTO(List.of(
                        "owner@s.whatsapp.net",
                        "admin-a@s.whatsapp.net",
                        "admin-b@s.whatsapp.net")));

        assertThat(result.ok()).isFalse();
        assertThat(result.partial()).isTrue();
        assertThat(result.results())
                .extracting(item -> item.jid() + ":" + item.status())
                .containsExactly(
                        "owner@s.whatsapp.net:OWNER_PROTECTED",
                        "admin-a@s.whatsapp.net:OK",
                        "admin-b@s.whatsapp.net:UNKNOWN");
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<GroupParticipantObservation>> currentCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(currentSnapshotPersistence).applyParticipantObservations(currentCaptor.capture());
        assertThat(currentCaptor.getValue()).singleElement().satisfies(observation -> {
            assertThat(observation.groupJid()).isEqualTo("120363detail@g.us");
            assertThat(observation.participantJid()).isEqualTo("admin-a@s.whatsapp.net");
            assertThat(observation.admin()).isFalse();
            assertThat(observation.source()).isEqualTo(WhatsappGroupMemberStateSource.ROLE_EVENT);
        });
        verify(selector).require(10L);
    }

    @Test
    void kickMembersTimeoutConfirmsRemovalWithSameAccount() {
        givenLiveTarget();
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        when(groupMetadataPort.getMetadata(webAccount(), "120363detail@g.us"))
                .thenReturn(
                        metadataWithParticipants(List.of(participant(
                                "member@s.whatsapp.net", false, false))),
                        metadataWithParticipants(List.of()));
        when(groupParticipantPort.updateParticipants(
                webAccount(),
                "120363detail@g.us",
                List.of("member@s.whatsapp.net"),
                GroupParticipantAction.REMOVE))
                .thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"));

        GroupMemberBatchResultVO result = service.kickMembers(
                10L,
                new GroupMemberBatchCommandDTO(List.of("member@s.whatsapp.net")));

        assertThat(result.ok()).isTrue();
        assertThat(result.results().get(0).status()).isEqualTo("OK");
        verify(businessDepartureService).recordConfirmedRemovals(
                org.mockito.ArgumentMatchers.nullable(Long.class),
                org.mockito.ArgumentMatchers.eq("120363detail@g.us"),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.startsWith("group-detail:10:"));
        verify(selector).require(10L);
        verify(groupMetadataPort, org.mockito.Mockito.times(2))
                .getMetadata(webAccount(), "120363detail@g.us");
    }

    @Test
    void kickMembersAndroidProtocolOkConfirmsRemovalWithSameAccount() {
        givenLiveTarget();
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(
                7L, "ANDROID", "android_7", "919000000001", true));
        when(groupMetadataPort.getMetadata(androidAccount(), "120363detail@g.us"))
                .thenReturn(
                        metadataWithParticipants(List.of(
                                participant("919000000001@s.whatsapp.net", true, false),
                                participant("member@s.whatsapp.net", false, false))),
                        metadataWithParticipants(List.of()));
        when(groupParticipantPort.updateParticipants(
                androidAccount(),
                "120363detail@g.us",
                List.of("member@s.whatsapp.net"),
                GroupParticipantAction.REMOVE))
                .thenReturn(new GroupParticipantBatchResult(
                        false,
                        List.of(new GroupParticipantBatchResult.Item(
                                "member@s.whatsapp.net", "OK", "200"))));

        GroupMemberBatchResultVO result = service.kickMembers(
                10L,
                new GroupMemberBatchCommandDTO(List.of("member@s.whatsapp.net")));

        assertThat(result.ok()).isTrue();
        assertThat(result.results().get(0).status()).isEqualTo("OK");
        verify(groupMetadataPort, org.mockito.Mockito.times(2))
                .getMetadata(androidAccount(), "120363detail@g.us");
    }

    @Test
    void kickMembersProtocolOkButMemberStillPresentIsNotReportedSuccessful() {
        givenLiveTarget();
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        GroupMetadataResult memberStillPresent = metadataWithParticipants(List.of(participant(
                "member@s.whatsapp.net", false, false)));
        when(groupMetadataPort.getMetadata(webAccount(), "120363detail@g.us"))
                .thenReturn(memberStillPresent, memberStillPresent);
        when(groupParticipantPort.updateParticipants(
                webAccount(),
                "120363detail@g.us",
                List.of("member@s.whatsapp.net"),
                GroupParticipantAction.REMOVE))
                .thenReturn(new GroupParticipantBatchResult(
                        false,
                        List.of(new GroupParticipantBatchResult.Item(
                                "member@s.whatsapp.net", "OK", "200"))));

        GroupMemberBatchResultVO result = service.kickMembers(
                10L,
                new GroupMemberBatchCommandDTO(List.of("member@s.whatsapp.net")));

        assertThat(result.ok()).isFalse();
        assertThat(result.partial()).isTrue();
        assertThat(result.results().get(0).status()).isEqualTo("UNKNOWN");
        assertThat(result.results().get(0).reason()).contains("待确认");
        verify(groupMetadataPort, org.mockito.Mockito.times(2))
                .getMetadata(webAccount(), "120363detail@g.us");
    }

    @Test
    void promoteMembersPermissionDeniedDoesNotSelectAnotherAccount() {
        givenLiveTarget();
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        when(groupMetadataPort.getMetadata(webAccount(), "120363detail@g.us"))
                .thenReturn(metadataWithParticipants(List.of(participant(
                        "member@s.whatsapp.net", false, false))));
        when(groupParticipantPort.updateParticipants(
                webAccount(),
                "120363detail@g.us",
                List.of("member@s.whatsapp.net"),
                GroupParticipantAction.PROMOTE))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.GROUP_PERMISSION_DENIED, "not admin"));

        assertThatThrownBy(() -> service.promoteMembers(
                10L,
                new GroupMemberBatchCommandDTO(List.of("member@s.whatsapp.net"))))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ErrorCode.GROUP_PERMISSION_DENIED.code()));

        verify(selector).require(10L);
    }

    @Test
    void demoteMemberUsesAnotherFreshAdministratorWhenTargetIsReadAccount() {
        givenLiveTarget();
        GroupExecutionAccount staleExecutor = new GroupExecutionAccount(
                7L, "ANDROID", "android_7", "919000000001", true);
        GroupExecutionAccount freshExecutor = new GroupExecutionAccount(
                8L, "ANDROID", "android_8", "919000000002", true);
        when(selector.require(10L)).thenReturn(staleExecutor);
        when(groupMetadataPort.getMetadata(staleExecutor.protocolRef(), "120363detail@g.us"))
                .thenReturn(metadataWithParticipants(List.of(
                        participant("111111111111@lid", "919000000001", true, false),
                        participant("222222222222@lid", "919000000002", true, false))));
        when(selector.findAdminByPhones(10L, List.of("919000000002"), 0))
                .thenReturn(Optional.of(freshExecutor));
        when(groupParticipantPort.updateParticipants(
                freshExecutor.protocolRef(),
                "120363detail@g.us",
                List.of("111111111111@lid"),
                GroupParticipantAction.DEMOTE))
                .thenReturn(new GroupParticipantBatchResult(
                        false,
                        List.of(new GroupParticipantBatchResult.Item(
                                "111111111111@lid", "OK", "200"))));

        GroupMemberBatchResultVO result = service.demoteMembers(
                10L,
                new GroupMemberBatchCommandDTO(List.of("111111111111@lid")));

        assertThat(result.ok()).isTrue();
        verify(groupParticipantPort).updateParticipants(
                freshExecutor.protocolRef(),
                "120363detail@g.us",
                List.of("111111111111@lid"),
                GroupParticipantAction.DEMOTE);
    }

    @Test
    void updateAvatarSendsBase64AndPersistsProtocolReadbackUrl() {
        byte[] bytes = "avatar-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", bytes);
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(snapshotReader.profile(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        when(groupProfilePort.updatePicture(
                webAccount(), "120363detail@g.us", null, Base64.getEncoder().encodeToString(bytes)))
                .thenReturn(new GroupPictureResult(true, "https://pps.whatsapp.net/new.jpg"));
        GroupAvatarUpdateVO result = service.updateAvatar(10L, file);

        assertThat(result).isEqualTo(new GroupAvatarUpdateVO(
                true, true, "https://pps.whatsapp.net/new.jpg"));
        org.mockito.ArgumentCaptor<com.armada.group.model.dto.GroupCurrentLocalProfileWrite> profile =
                org.mockito.ArgumentCaptor.forClass(
                        com.armada.group.model.dto.GroupCurrentLocalProfileWrite.class);
        verify(currentLocalPersistence).applyProfile(profile.capture());
        assertThat(profile.getValue().avatarUrl()).isEqualTo("https://pps.whatsapp.net/new.jpg");
    }

    @Test
    void updateAvatarAppliedWithoutReadbackUrlDoesNotWriteMirror() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(snapshotReader.profile(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        when(groupProfilePort.updatePicture(
                webAccount(), "120363detail@g.us", null, Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})))
                .thenReturn(new GroupPictureResult(true, null));

        GroupAvatarUpdateVO result = service.updateAvatar(10L, file);

        assertThat(result).isEqualTo(new GroupAvatarUpdateVO(true, false, null));
        verify(currentLocalPersistence, never()).applyProfile(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateAvatarTimeoutConfirmedByChangedUrlUsesSameAccount() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(snapshotReader.profile(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        when(groupProfilePort.updatePicture(
                webAccount(), "120363detail@g.us", null, Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})))
                .thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"));
        when(groupProfilePort.getPictureUrl(webAccount(), "120363detail@g.us"))
                .thenReturn("https://pps.whatsapp.net/changed.jpg");
        GroupAvatarUpdateVO result = service.updateAvatar(10L, file);

        assertThat(result.applied()).isTrue();
        assertThat(result.mirrorSynced()).isTrue();
        assertThat(result.avatarUrl()).isEqualTo("https://pps.whatsapp.net/changed.jpg");
        verify(currentLocalPersistence).applyProfile(org.mockito.ArgumentMatchers.any());
        verify(selector).require(10L);
        verify(groupProfilePort).getPictureUrl(webAccount(), "120363detail@g.us");
    }

    @Test
    void updateAvatarTimeoutWithUnchangedUrlThrowsDedicatedError() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(snapshotReader.profile(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true));
        when(groupProfilePort.updatePicture(
                webAccount(), "120363detail@g.us", null, Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})))
                .thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"));
        when(groupProfilePort.getPictureUrl(webAccount(), "120363detail@g.us"))
                .thenReturn("https://pps.whatsapp.net/current.jpg");

        assertThatThrownBy(() -> service.updateAvatar(10L, file))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.GROUP_PROTOCOL_TIMEOUT.code()));

        verify(selector).require(10L);
        verify(currentLocalPersistence, never()).applyProfile(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateAvatarRejectsEmptyNonImageAndOversizedFilesBeforeSelection() {
        List<MockMultipartFile> invalidFiles = List.of(
                new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]),
                new MockMultipartFile("file", "avatar.txt", "text/plain", new byte[]{1}),
                new MockMultipartFile("file", "large.jpg", "image/jpeg", new byte[5 * 1024 * 1024 + 1]));

        for (MockMultipartFile file : invalidFiles) {
            assertThatThrownBy(() -> service.updateAvatar(10L, file))
                    .isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(selector);
    }

    private static void assertUnavailable(GroupDetailVO result, String reason) {
        assertThat(result.liveStateAvailable()).isFalse();
        assertThat(result.liveStateUnavailableReason()).isEqualTo(reason);
        assertThat(result.timedMessageMode()).isNull();
        assertThat(result.permissions().editGroupSettings()).isNull();
        assertThat(result.permissions().sendMessages()).isNull();
        assertThat(result.permissions().addMembers()).isNull();
        assertThat(result.permissions().inviteViaLink()).isNull();
        assertThat(result.permissions().adminApproveNewMembers()).isNull();
        assertThat(result.membersAvailable()).isFalse();
        assertThat(result.members()).isEmpty();
    }

    private static ProtocolAccountRef webAccount() {
        return new GroupExecutionAccount(7L, null, "acc_7", "acc_7", true).protocolRef();
    }

    private static ProtocolAccountRef androidAccount() {
        return new GroupExecutionAccount(
                7L, "ANDROID", "android_7", "919000000001", true).protocolRef();
    }

    private void givenLiveTarget() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(snapshotReader.profile(10L)).thenReturn(preview("120363detail@g.us"));
    }

    private static GroupMetadataResult metadata(
            String subject,
            boolean memberAddMode,
            boolean announce,
            boolean restrict,
            boolean joinApprovalMode,
            int ephemeralSeconds) {
        return new GroupMetadataResult(
                "120363detail@g.us",
                subject,
                null,
                null,
                null,
                true,
                announce,
                restrict,
                memberAddMode,
                joinApprovalMode,
                ephemeralSeconds,
                null,
                false,
                "Baileys 7.0.0-rc11 does not expose invite-link access",
                false,
                true,
                List.of(new GroupParticipantResult(
"8613800000000@s.whatsapp.net", null,
                        "8613800000000",
                        true,
                        true,
                        "superadmin")));
    }

    private static GroupMetadataResult metadataWithParticipants(
            List<GroupParticipantResult> participants) {
        return new GroupMetadataResult(
                "120363detail@g.us",
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
                "Baileys 7.0.0-rc11 does not expose invite-link access",
                false,
                true,
                participants);
    }

    private static GroupMetadataResult metadataWithInviteViaLink(boolean enabled) {
        return new GroupMetadataResult(
                "120363detail@g.us",
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
                enabled,
                true,
                null,
                false,
                true,
                List.of(new GroupParticipantResult(
"8613800000000@s.whatsapp.net", null,
                        "8613800000000",
                        true,
                        true,
                        "superadmin")));
    }

    private static GroupParticipantResult participant(
            String jid,
            boolean admin,
            boolean owner) {
        return participant(jid, jid.substring(0, jid.indexOf('@')), admin, owner);
    }

    private static GroupParticipantResult participant(
            String jid,
            String phone,
            boolean admin,
            boolean owner) {
        return new GroupParticipantResult(
jid, null,
                phone,
                admin,
                owner,
                owner ? "superadmin" : admin ? "admin" : null);
    }

    private static WhatsappGroupMemberSnapshot snapshotMember() {
        WhatsappGroupMemberSnapshot row = new WhatsappGroupMemberSnapshot();
        row.setParticipantJid("8613800000000@s.whatsapp.net");
        row.setPhone("8613800000000");
        row.setIsAdmin(true);
        row.setIsOwner(true);
        row.setRole("OWNER");
        return row;
    }

    private static GroupMetadataSyncTask syncTask(GroupMetadataSyncStatus status) {
        GroupMetadataSyncTask task = new GroupMetadataSyncTask();
        task.setStatus(status.code());
        task.setLastSuccessAt(status == GroupMetadataSyncStatus.SUCCEEDED
                ? 1_722_470_400_000L
                : null);
        task.setLastErrorMessage(status == GroupMetadataSyncStatus.DEFERRED
                ? "暂无可用在群账号"
                : null);
        return task;
    }

    private static GroupLink activeLink(Long id, String name, String remark) {
        GroupLink link = new GroupLink();
        link.setId(id);
        link.setGroupName(name);
        link.setRemark(remark);
        return link;
    }

    private static GroupLinkPreview preview(String groupJid) {
        GroupLinkPreview preview = new GroupLinkPreview();
        preview.setGroupJid(groupJid);
        preview.setWaSubject("预览群名");
        preview.setAvatarUrl("https://pps.whatsapp.net/current.jpg");
        return preview;
    }
}
