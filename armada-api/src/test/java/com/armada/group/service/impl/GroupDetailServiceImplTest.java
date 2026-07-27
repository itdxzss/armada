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
import com.armada.group.model.dto.GroupSubjectCommandDTO;
import com.armada.group.model.dto.GroupTimedMessageCommandDTO;
import com.armada.group.model.dto.GroupSettingCommandDTO;
import com.armada.group.model.dto.GroupMemberBatchCommandDTO;
import com.armada.group.model.enums.GroupPermissionKey;
import com.armada.group.model.enums.GroupTimedMessageMode;
import com.armada.group.model.vo.GroupAvatarUpdateVO;
import com.armada.group.model.vo.GroupDetailVO;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.model.vo.GroupMemberBatchResultVO;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupDetailProtocolPorts;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.model.result.GroupPictureResult;
import com.armada.platform.protocol.port.GroupMetadataPort;
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
    private GroupLinkPreviewMapper previewMapper;

    @Mock
    private GroupExecutionAccountSelector selector;

    @Mock
    private GroupMetadataPort groupMetadataPort;

    @Mock
    private GroupProfilePort groupProfilePort;

    @Mock
    private GroupSettingsPort groupSettingsPort;

    @Mock
    private GroupParticipantPort groupParticipantPort;

    private GroupDetailServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GroupDetailServiceImpl(
                groupLinkMapper,
                previewMapper,
                selector,
                new GroupDetailProtocolPorts(
                        groupMetadataPort,
                        groupProfilePort,
                        groupSettingsPort,
                        groupParticipantPort));
    }

    @Test
    void detailCombinesLocalProfileWithOneRealtimeMetadataSnapshot() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "本地群名", "本地备注"));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.find(10L)).thenReturn(Optional.of(new GroupExecutionAccount(7L, "acc_7")));
        when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
                .thenReturn(metadata("真实群名", true, true, false, true, 604_800));

        GroupDetailVO result = service.detail(10L);

        assertThat(result.groupName()).isEqualTo("真实群名");
        assertThat(result.remark()).isEqualTo("本地备注");
        assertThat(result.avatarUrl()).isEqualTo("https://pps.whatsapp.net/current.jpg");
        assertThat(result.liveStateAvailable()).isTrue();
        assertThat(result.permissions().editGroupSettings()).isTrue();
        assertThat(result.permissions().sendMessages()).isFalse();
        assertThat(result.permissions().addMembers()).isTrue();
        assertThat(result.permissions().adminApproveNewMembers()).isTrue();
        assertThat(result.timedMessageMode()).isEqualTo("7d");
        assertThat(result.members()).hasSize(1);
        assertThat(result.members().get(0).owner()).isTrue();
        verify(groupMetadataPort).getMetadata("acc_7", "120363detail@g.us");
    }

    @Test
    void detailWithoutExecutionAccountReturnsLocalOnlyState() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "本地群名", "本地备注"));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.find(10L)).thenReturn(Optional.empty());

        GroupDetailVO result = service.detail(10L);

        assertUnavailable(result, "没有在线且仍在该群内的账号");
        assertThat(result.groupName()).isEqualTo("本地群名");
        assertThat(result.remark()).isEqualTo("本地备注");
        assertThat(result.avatarUrl()).isEqualTo("https://pps.whatsapp.net/current.jpg");
        verify(groupMetadataPort, never()).getMetadata(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void detailWhenProtocolFailsReturnsLocalOnlyState() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "本地群名", "本地备注"));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.find(10L)).thenReturn(Optional.of(new GroupExecutionAccount(7L, "acc_7")));
        when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
                .thenThrow(new ProtocolException(ProtocolErrorCode.NETWORK, "protocol unavailable"));

        GroupDetailVO result = service.detail(10L);

        assertUnavailable(result, "群实时数据读取失败");
        assertThat(result.groupName()).isEqualTo("本地群名");
    }

    @Test
    void detailKeepsUnknownEphemeralDurationUnknown() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "本地群名", null));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.find(10L)).thenReturn(Optional.of(new GroupExecutionAccount(7L, "acc_7")));
        when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
                .thenReturn(metadata("真实群名", false, false, false, false, 123));

        GroupDetailVO result = service.detail(10L);

        assertThat(result.liveStateAvailable()).isTrue();
        assertThat(result.timedMessageMode()).isNull();
    }

    @Test
    void membersRejectsUnavailableRealtimeState() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "本地群名", null));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.find(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.members(10L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("没有在线且仍在该群内的账号");
    }

    @Test
    void updateSubjectUsesSelectedAccountAndWritesMirrorAfterProtocolSuccess() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "旧群名", "备注"));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        when(groupLinkMapper.updateGroupName(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("新群名"),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

        service.updateSubject(10L, new GroupSubjectCommandDTO(" 新群名 "));

        InOrder order = inOrder(groupProfilePort, groupLinkMapper);
        order.verify(groupProfilePort).updateSubject("acc_7", "120363detail@g.us", "新群名");
        order.verify(groupLinkMapper).updateGroupName(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("新群名"),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void updateSubjectTimeoutConfirmedBySameAccountWritesMirror() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "旧群名", null));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        doThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"))
                .when(groupProfilePort).updateSubject("acc_7", "120363detail@g.us", "新群名");
        when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
                .thenReturn(metadata("新群名", false, false, false, false, 0));
        when(groupLinkMapper.updateGroupName(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("新群名"),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

        service.updateSubject(10L, new GroupSubjectCommandDTO("新群名"));

        verify(selector).require(10L);
        verify(groupMetadataPort).getMetadata("acc_7", "120363detail@g.us");
        verify(groupLinkMapper).updateGroupName(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("新群名"),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void updateSubjectTimeoutUnconfirmedThrowsDedicatedError() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "旧群名", null));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        doThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"))
                .when(groupProfilePort).updateSubject("acc_7", "120363detail@g.us", "新群名");
        when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
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
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        doThrow(new ProtocolException(
                ProtocolErrorCode.GROUP_PERMISSION_DENIED, "not admin"))
                .when(groupProfilePort)
                .updateSubject("acc_7", "120363detail@g.us", "新群名");

        assertThatThrownBy(() -> service.updateSubject(
                10L, new GroupSubjectCommandDTO("新群名")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ErrorCode.GROUP_PERMISSION_DENIED.code()));
    }

    @Test
    void updateTimedMessageUsesSelectedAccountAndConfirmsMetadata() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
                .thenReturn(metadata("群名", false, false, false, false, 604_800));

        service.updateTimedMessage(
                10L, new GroupTimedMessageCommandDTO(GroupTimedMessageMode.DAYS_7));

        InOrder order = inOrder(groupSettingsPort, groupMetadataPort);
        order.verify(groupSettingsPort)
                .setEphemeralDuration(webAccount(), "120363detail@g.us", 604_800);
        order.verify(groupMetadataPort).getMetadata("acc_7", "120363detail@g.us");
        verify(selector).require(10L);
    }

    @Test
    void updateTimedMessageTimeoutConfirmedBySameAccountSucceeds() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        doThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"))
                .when(groupSettingsPort)
                .setEphemeralDuration(webAccount(), "120363detail@g.us", 604_800);
        when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
                .thenReturn(metadata("群名", false, false, false, false, 604_800));

        service.updateTimedMessage(
                10L, new GroupTimedMessageCommandDTO(GroupTimedMessageMode.DAYS_7));

        verify(selector).require(10L);
        verify(groupMetadataPort).getMetadata("acc_7", "120363detail@g.us");
    }

    @Test
    void updateTimedMessageUnconfirmedThrowsDedicatedError() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
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
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
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
    void updateSettingUsesSelectedAccountAndConfirmsMetadata() {
        givenLiveTarget();
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
                .thenReturn(metadata("群名", true, false, false, false, 0));

        service.updateSetting(10L, new GroupSettingCommandDTO(
                GroupPermissionKey.ADD_MEMBERS, true));

        verify(groupSettingsPort)
                .setAddMembersAllowed(webAccount(), "120363detail@g.us", true);
        verify(groupMetadataPort).getMetadata("acc_7", "120363detail@g.us");
        verify(selector).require(10L);
    }

    @Test
    void updateSettingMapsProtocolPermissionDeniedToBusinessError() {
        givenLiveTarget();
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        doThrow(new ProtocolException(
                ProtocolErrorCode.GROUP_PERMISSION_DENIED, "not admin"))
                .when(groupSettingsPort)
                .setSendMessagesAllowed(webAccount(), "120363detail@g.us", false);

        assertThatThrownBy(() -> service.updateSetting(10L, new GroupSettingCommandDTO(
                GroupPermissionKey.SEND_MESSAGES, false)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ErrorCode.GROUP_PERMISSION_DENIED.code()));

        verify(selector).require(10L);
    }

    @Test
    void updateSettingTimeoutConfirmedBySameAccountSucceeds() {
        givenLiveTarget();
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        doThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"))
                .when(groupSettingsPort)
                .setEditGroupSettingsAllowed(webAccount(), "120363detail@g.us", true);
        when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
                .thenReturn(metadata("群名", false, false, false, false, 0));

        service.updateSetting(10L, new GroupSettingCommandDTO(
                GroupPermissionKey.EDIT_GROUP_SETTINGS, true));

        verify(selector).require(10L);
        verify(groupMetadataPort).getMetadata("acc_7", "120363detail@g.us");
    }

    @Test
    void updateSettingUnconfirmedThrowsDedicatedError() {
        givenLiveTarget();
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
                .thenReturn(metadata("群名", false, false, false, false, 0));

        assertThatThrownBy(() -> service.updateSetting(10L, new GroupSettingCommandDTO(
                GroupPermissionKey.ADMIN_APPROVE_NEW_MEMBERS, true)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ErrorCode.GROUP_PROTOCOL_TIMEOUT.code()));

        verify(selector).require(10L);
    }

    @Test
    void updateSettingRejectsUnsupportedInviteViaLinkBeforeMutation() {
        givenLiveTarget();
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
                .thenReturn(metadata("群名", false, false, false, false, 0));

        assertThatThrownBy(() -> service.updateSetting(10L, new GroupSettingCommandDTO(
                GroupPermissionKey.INVITE_VIA_LINK, true)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ErrorCode.GROUP_CAPABILITY_UNSUPPORTED.code()));

        verifyNoInteractions(groupSettingsPort);
    }

    @Test
    void demoteMembersProtectsOwnerAndFillsMissingProtocolResults() {
        givenLiveTarget();
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
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
        verify(selector).require(10L);
    }

    @Test
    void kickMembersTimeoutConfirmsRemovalWithSameAccount() {
        givenLiveTarget();
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
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
        verify(selector).require(10L);
        verify(groupMetadataPort, org.mockito.Mockito.times(2))
                .getMetadata("acc_7", "120363detail@g.us");
    }

    @Test
    void promoteMembersPermissionDeniedDoesNotSelectAnotherAccount() {
        givenLiveTarget();
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        when(groupMetadataPort.getMetadata("acc_7", "120363detail@g.us"))
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
    void updateAvatarSendsBase64AndPersistsProtocolReadbackUrl() {
        byte[] bytes = "avatar-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", bytes);
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        when(groupProfilePort.updatePicture(
                "acc_7", "120363detail@g.us", null, Base64.getEncoder().encodeToString(bytes)))
                .thenReturn(new GroupPictureResult(true, "https://pps.whatsapp.net/new.jpg"));
        when(previewMapper.upsertAvatarUrl(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("https://pps.whatsapp.net/new.jpg"),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

        GroupAvatarUpdateVO result = service.updateAvatar(10L, file);

        assertThat(result).isEqualTo(new GroupAvatarUpdateVO(
                true, true, "https://pps.whatsapp.net/new.jpg"));
        verify(previewMapper).upsertAvatarUrl(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("https://pps.whatsapp.net/new.jpg"),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void updateAvatarAppliedWithoutReadbackUrlDoesNotWriteMirror() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        when(groupProfilePort.updatePicture(
                "acc_7", "120363detail@g.us", null, Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})))
                .thenReturn(new GroupPictureResult(true, null));

        GroupAvatarUpdateVO result = service.updateAvatar(10L, file);

        assertThat(result).isEqualTo(new GroupAvatarUpdateVO(true, false, null));
        verify(previewMapper, never()).upsertAvatarUrl(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void updateAvatarTimeoutConfirmedByChangedUrlUsesSameAccount() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        when(groupProfilePort.updatePicture(
                "acc_7", "120363detail@g.us", null, Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})))
                .thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"));
        when(groupProfilePort.getPictureUrl("acc_7", "120363detail@g.us"))
                .thenReturn("https://pps.whatsapp.net/changed.jpg");
        when(previewMapper.upsertAvatarUrl(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("https://pps.whatsapp.net/changed.jpg"),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

        GroupAvatarUpdateVO result = service.updateAvatar(10L, file);

        assertThat(result.applied()).isTrue();
        assertThat(result.mirrorSynced()).isTrue();
        assertThat(result.avatarUrl()).isEqualTo("https://pps.whatsapp.net/changed.jpg");
        verify(selector).require(10L);
        verify(groupProfilePort).getPictureUrl("acc_7", "120363detail@g.us");
    }

    @Test
    void updateAvatarTimeoutWithUnchangedUrlThrowsDedicatedError() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
        when(selector.require(10L)).thenReturn(new GroupExecutionAccount(7L, "acc_7"));
        when(groupProfilePort.updatePicture(
                "acc_7", "120363detail@g.us", null, Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})))
                .thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"));
        when(groupProfilePort.getPictureUrl("acc_7", "120363detail@g.us"))
                .thenReturn("https://pps.whatsapp.net/current.jpg");

        assertThatThrownBy(() -> service.updateAvatar(10L, file))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.GROUP_PROTOCOL_TIMEOUT.code()));

        verify(selector).require(10L);
        verify(previewMapper, never()).upsertAvatarUrl(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
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
        return new GroupExecutionAccount(7L, "acc_7").protocolRef();
    }

    private void givenLiveTarget() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", null));
        when(previewMapper.selectByGroupLinkId(10L)).thenReturn(preview("120363detail@g.us"));
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
                        "8613800000000@s.whatsapp.net",
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

    private static GroupParticipantResult participant(
            String jid,
            boolean admin,
            boolean owner) {
        return new GroupParticipantResult(
                jid,
                jid.substring(0, jid.indexOf('@')),
                admin,
                owner,
                owner ? "superadmin" : admin ? "admin" : null);
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
