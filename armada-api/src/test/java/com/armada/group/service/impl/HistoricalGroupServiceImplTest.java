package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.model.dto.HistoricalGroupParticipantActionDTO;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.model.enums.HistoricalGroupMembershipState;
import com.armada.group.model.enums.HistoricalGroupSelfRole;
import com.armada.group.model.enums.SpeechState;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.model.vo.HistoricalGroupDetailVO;
import com.armada.group.service.HistoricalGroupExecutionAccountSelector;
import com.armada.group.service.HistoricalGroupProtocolPorts;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.AccountParticipatingGroupPort;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.platform.protocol.port.GroupMetadataPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class HistoricalGroupServiceImplTest {

    private final HistoricalGroupExecutionAccountSelector executionAccountSelector =
            Mockito.mock(HistoricalGroupExecutionAccountSelector.class);
    private final AccountParticipatingGroupPort participatingGroupPort =
            Mockito.mock(AccountParticipatingGroupPort.class);
    private final FixedAccountGroupMetadataPort readMetadataPort =
            Mockito.mock(FixedAccountGroupMetadataPort.class);
    private final GroupMetadataPort writeMetadataPort = Mockito.mock(GroupMetadataPort.class);
    private final GroupInvitePort invitePort = Mockito.mock(GroupInvitePort.class);
    private final GroupParticipantPort participantPort = Mockito.mock(GroupParticipantPort.class);
    private final GroupInviteLinkService inviteLinkService =
            Mockito.mock(GroupInviteLinkService.class);
    private final HistoricalGroupServiceImpl service = new HistoricalGroupServiceImpl(
            new HistoricalGroupProtocolPorts(
                    participatingGroupPort,
                    readMetadataPort,
                    writeMetadataPort,
                    invitePort,
                    participantPort),
            executionAccountSelector,
            inviteLinkService);

    @Test
    void accountGroupDetailUsesAutomaticallySelectedAdministrator() {
        ProtocolAccountRef selected = new ProtocolAccountRef(
                77L, ProtocolBackend.ANDROID, "android-77", "8613800000077");
        when(executionAccountSelector.require(12L, "baseline@g.us"))
                .thenReturn(new GroupExecutionAccount(
                        77L, "ANDROID", "android-77", "8613800000077", true));
        when(readMetadataPort.getMetadata(selected, "baseline@g.us"))
                .thenReturn(metadata(
                        "baseline@g.us",
                        "自动路由群",
                        false,
                        participant("8613800000077@s.whatsapp.net", true, false, "admin")));
        when(invitePort.getInvite(selected, "baseline@g.us"))
                .thenReturn(new GroupInviteResult(
                        "baseline@g.us", "code", "https://chat.whatsapp.com/code"));

        HistoricalGroupDetailVO result = service.getHistoricalGroupDetail(
                12L, "baseline@g.us");

        assertThat(result.accountId()).isEqualTo(77L);
        verify(executionAccountSelector).require(12L, "baseline@g.us");
    }

    @Test
    void detailRejectsNonBaselineGroupBeforeAnyProtocolCall() {
        when(executionAccountSelector.require(20L, "outside@g.us"))
                .thenThrow(new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "目标群不属于账号组历史群: outside@g.us"));

        assertThatThrownBy(() -> service.getHistoricalGroupDetail(20L, "outside@g.us"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于账号组历史群");
        verifyNoInteractions(readMetadataPort, writeMetadataPort, invitePort, participantPort);
    }

    @Test
    void detailLoadsFullMetadataMembersAndInviteForBaselineGroup() {
        ProtocolAccountRef account = stubExecutionAccount(21L);
        when(readMetadataPort.getMetadata(account, "baseline@g.us"))
                .thenReturn(metadata(
                        "baseline@g.us",
                        "完整群名",
                        true,
                        participant("8613800000021@s.whatsapp.net", true, false, "admin"),
                        participant("8613800000099@s.whatsapp.net", false, false, null)));
        when(invitePort.getInvite(account, "baseline@g.us"))
                .thenReturn(new GroupInviteResult(
                        "baseline@g.us", "invite-code", "https://chat.whatsapp.com/invite-code"));

        HistoricalGroupDetailVO result = service.getHistoricalGroupDetail(21L, "baseline@g.us");

        assertThat(result.groupJid()).isEqualTo("baseline@g.us");
        assertThat(result.subject()).isEqualTo("完整群名");
        assertThat(result.inviteUrl()).isEqualTo("https://chat.whatsapp.com/invite-code");
        assertThat(result.linkAvailable()).isTrue();
        assertThat(result.operationAllowed()).isTrue();
        assertThat(result.members()).extracting(HistoricalGroupDetailVO.Member::participantJid)
                .containsExactly(
                        "8613800000021@s.whatsapp.net",
                        "8613800000099@s.whatsapp.net");
        assertThat(result.members().get(0).self()).isTrue();
        assertThat(result.members().get(1).phone()).isEqualTo("8613800000099");
        ArgumentCaptor<GroupInviteLinkObservation> observation =
                ArgumentCaptor.forClass(GroupInviteLinkObservation.class);
        verify(inviteLinkService).applyCurrentInvite(observation.capture());
        assertThat(observation.getValue()).satisfies(value -> {
            assertThat(value.groupJid()).isEqualTo("baseline@g.us");
            assertThat(value.inviteCode()).isEqualTo("invite-code");
            assertThat(value.protocolBackend()).isEqualTo(ProtocolBackend.WEB);
            assertThat(value.source()).isEqualTo("HISTORICAL_GROUP_DETAIL");
        });
    }

    @Test
    void administratorKeepsParticipantMutationWhenInviteLookupFails() {
        ProtocolAccountRef account = stubExecutionAccount(22L);
        when(readMetadataPort.getMetadata(account, "baseline@g.us"))
                .thenReturn(metadata(
                        "baseline@g.us",
                        "只读群名",
                        false,
                        participant("8613800000022@s.whatsapp.net", true, false, "admin"),
                        participant("8613800000099@s.whatsapp.net", false, false, null),
                        participant("8613800000088@s.whatsapp.net", true, false, "admin")));
        when(invitePort.getInvite(account, "baseline@g.us"))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.INVALID_GROUP_LINK,
                        "邀请链接读取完整失败原因"));

        HistoricalGroupDetailVO result = service.getHistoricalGroupDetail(22L, "baseline@g.us");

        assertThat(result.subject()).isEqualTo("只读群名");
        assertThat(result.members()).hasSize(3);
        assertThat(result.linkAvailable()).isFalse();
        assertThat(result.operationAllowed()).isTrue();
        assertThat(result.operationDisabledReason()).isNull();
        assertThat(result.members().get(1).operationAllowed()).isTrue();
        assertThat(result.members().get(2).operationAllowed()).isFalse();
        assertThat(result.members().get(2).operationDisabledReason()).isEqualTo("目标成员已经是管理员");
        assertThat(result.errorCode()).isEqualTo("INVALID_GROUP_LINK");
        assertThat(result.errorMessage()).isEqualTo("邀请链接读取完整失败原因");
    }

    @Test
    void detailSkipsInviteAndReturnsReadOnlyFailureWhenMetadataFails() {
        ProtocolAccountRef account = stubExecutionAccount(25L);
        when(readMetadataPort.getMetadata(account, "baseline@g.us"))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.GROUP_UNAVAILABLE,
                        "metadata 完整失败原因"));
        HistoricalGroupDetailVO result = service.getHistoricalGroupDetail(25L, "baseline@g.us");

        assertThat(result.subject()).isNull();
        assertThat(result.membershipState()).isEqualTo(HistoricalGroupMembershipState.FETCH_FAILED);
        assertThat(result.speechState()).isEqualTo(SpeechState.ABNORMAL);
        assertThat(result.members()).isEmpty();
        assertThat(result.inviteUrl()).isNull();
        assertThat(result.linkAvailable()).isFalse();
        assertThat(result.operationAllowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GROUP_UNAVAILABLE");
        assertThat(result.errorMessage()).isEqualTo("metadata 完整失败原因");
        verifyNoInteractions(invitePort);
    }

    @Test
    void ordinaryMemberReadsDetailWithoutLookingUpInvite() {
        ProtocolAccountRef account = stubExecutionAccount(29L);
        when(readMetadataPort.getMetadata(account, "baseline@g.us"))
                .thenReturn(metadata(
                        "baseline@g.us",
                        "普通成员群",
                        false,
                        participant("8613800000029@s.whatsapp.net", false, false, "participant"),
                        participant("8613800000099@s.whatsapp.net", false, false, "participant")));

        HistoricalGroupDetailVO result = service.getHistoricalGroupDetail(29L, "baseline@g.us");

        assertThat(result.subject()).isEqualTo("普通成员群");
        assertThat(result.selfRole()).isEqualTo(HistoricalGroupSelfRole.MEMBER);
        assertThat(result.members()).hasSize(2);
        assertThat(result.inviteUrl()).isNull();
        assertThat(result.linkAvailable()).isFalse();
        assertThat(result.operationAllowed()).isFalse();
        assertThat(result.operationDisabledReason()).isEqualTo("当前账号不是管理员");
        assertThat(result.errorCode()).isNull();
        assertThat(result.errorMessage()).isNull();
        verifyNoInteractions(invitePort);
    }

    @Test
    void androidAdministratorDetailAllowsParticipantPromotion() {
        ProtocolAccountRef android = stubExecutionAccount(31L, ProtocolBackend.ANDROID);
        GroupMetadataResult androidMetadata = new GroupMetadataResult(
                "120363detail@g.us",
                "安卓历史群",
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                "Android 当前不支持读取 inviteViaLink 设置状态",
                false,
                true,
                List.of(
                        new GroupParticipantResult(
                                "919000000001@s.whatsapp.net",
                                "919000000001",
                                true,
                                false,
                                "admin"),
                        new GroupParticipantResult(
                                "919000000002@s.whatsapp.net",
                                "919000000002",
                                false,
                                false,
                                "participant")));
        when(readMetadataPort.getMetadata(android, "120363detail@g.us"))
                .thenReturn(androidMetadata);
        when(invitePort.getInvite(android, "120363detail@g.us"))
                .thenReturn(new GroupInviteResult(
                        "120363detail@g.us",
                        "ABC123",
                        "https://chat.whatsapp.com/ABC123"));

        HistoricalGroupDetailVO result =
                service.getHistoricalGroupDetail(31L, "120363detail@g.us");

        assertThat(result.subject()).isEqualTo("安卓历史群");
        assertThat(result.members()).hasSize(2);
        assertThat(result.inviteUrl()).isEqualTo("https://chat.whatsapp.com/ABC123");
        assertThat(result.selfRole()).isEqualTo(HistoricalGroupSelfRole.ADMIN);
        assertThat(result.speechState()).isEqualTo(SpeechState.ABNORMAL);
        assertThat(result.operationAllowed()).isTrue();
        assertThat(result.operationDisabledReason()).isNull();
        assertThat(result.members().get(1).operationAllowed()).isTrue();
        verifyNoInteractions(writeMetadataPort, participantPort);
    }

    @Test
    void androidPromoteUsesFixedAccountMetadataWithoutInvite() {
        ProtocolAccountRef android = stubExecutionAccount(32L, ProtocolBackend.ANDROID);
        String memberJid = "919000000002@s.whatsapp.net";
        when(readMetadataPort.getMetadata(android, "120363detail@g.us"))
                .thenReturn(metadata(
                        "120363detail@g.us",
                        "安卓管理群",
                        false,
                        participant("919000000001@s.whatsapp.net", true, false, "admin"),
                        participant(memberJid, false, false, "participant")));
        when(participantPort.updateParticipants(
                android,
                "120363detail@g.us",
                List.of(memberJid),
                GroupParticipantAction.PROMOTE))
                .thenReturn(batch(item(memberJid, "OK", "200")));

        var result = service.promoteParticipants(
                new HistoricalGroupParticipantActionDTO(
                        32L,
                        "120363detail@g.us",
                        List.of(memberJid)));

        assertThat(result.ok()).isTrue();
        verify(readMetadataPort).getMetadata(android, "120363detail@g.us");
        verify(participantPort).updateParticipants(
                android,
                "120363detail@g.us",
                List.of(memberJid),
                GroupParticipantAction.PROMOTE);
        verifyNoInteractions(writeMetadataPort, invitePort);
    }

    @Test
    void promoteParticipantsDoesNotRequireFreshInvite() {
        ProtocolAccountRef account = stubExecutionAccount(23L);
        String memberJid = "8613800000099@s.whatsapp.net";
        when(readMetadataPort.getMetadata(account, "baseline@g.us"))
                .thenReturn(metadata(
                        "baseline@g.us",
                        "管理群",
                        false,
                        participant("8613800000023@s.whatsapp.net", true, false, "admin"),
                        participant(memberJid, false, false, null)));
        when(participantPort.updateParticipants(
                account,
                "baseline@g.us",
                List.of(memberJid),
                GroupParticipantAction.PROMOTE))
                .thenReturn(batch(item(memberJid, "OK", "200")));

        var result = service.promoteParticipants(new HistoricalGroupParticipantActionDTO(
                23L, "baseline@g.us", List.of(memberJid)));

        assertThat(result.ok()).isTrue();
        verifyNoInteractions(writeMetadataPort, invitePort);
    }

    @Test
    void administratorActionsProtectSelfAndOwnerAndPreserveItemOrderAndErrors() {
        ProtocolAccountRef account = stubExecutionAccount(24L);
        String selfJid = "8613800000024@s.whatsapp.net";
        String ownerJid = "8613800000001@s.whatsapp.net";
        String adminJid = "8613800000002@s.whatsapp.net";
        String memberJid = "8613800000003@s.whatsapp.net";
        GroupMetadataResult currentMetadata = metadata(
                        "baseline@g.us",
                        "管理群",
                        false,
                        participant(selfJid, true, false, "admin"),
                        participant(ownerJid, true, true, "superadmin"),
                        participant(adminJid, true, false, "admin"),
                        participant(memberJid, false, false, null));
        when(readMetadataPort.getMetadata(account, "baseline@g.us"))
                .thenReturn(currentMetadata);
        when(writeMetadataPort.getMetadata(account.protocolAccountId(), "baseline@g.us"))
                .thenReturn(currentMetadata);
        when(invitePort.getInvite(account, "baseline@g.us"))
                .thenReturn(new GroupInviteResult(
                        "baseline@g.us", "fresh", "https://chat.whatsapp.com/fresh"));
        when(participantPort.updateParticipants(
                account,
                "baseline@g.us",
                List.of(memberJid),
                GroupParticipantAction.PROMOTE))
                .thenReturn(batch(item(memberJid, "OK", "200")));
        when(participantPort.updateParticipants(
                account,
                "baseline@g.us",
                List.of(adminJid),
                GroupParticipantAction.DEMOTE))
                .thenReturn(batch(item(adminJid, "OK", "200")));
        when(participantPort.updateParticipants(
                account,
                "baseline@g.us",
                List.of(memberJid, adminJid),
                GroupParticipantAction.REMOVE))
                .thenReturn(new GroupParticipantBatchResult(true, List.of(
                        item(memberJid, "OK", "200"),
                        item(adminJid, "GROUP_PERMISSION_DENIED", "protocol complete denied reason"))));
        HistoricalGroupParticipantActionDTO promote = new HistoricalGroupParticipantActionDTO(
                24L, "baseline@g.us", List.of(memberJid));
        HistoricalGroupParticipantActionDTO demote = new HistoricalGroupParticipantActionDTO(
                24L, "baseline@g.us", List.of(selfJid, ownerJid, memberJid, adminJid));
        HistoricalGroupParticipantActionDTO remove = new HistoricalGroupParticipantActionDTO(
                24L, "baseline@g.us", List.of(memberJid, adminJid));

        assertThat(service.promoteParticipants(promote).ok()).isTrue();
        var demoteResult = service.demoteParticipants(demote);
        var removeResult = service.removeParticipants(remove);

        assertThat(demoteResult.results())
                .extracting(result -> result.participantJid() + ":" + result.errorCode())
                .containsExactly(
                        selfJid + ":SELF_PROTECTED",
                        ownerJid + ":OWNER_PROTECTED",
                        memberJid + ":ROLE_MISMATCH",
                        adminJid + ":null");
        assertThat(removeResult.ok()).isFalse();
        assertThat(removeResult.partial()).isTrue();
        assertThat(removeResult.results()).extracting(result -> result.participantJid())
                .containsExactly(memberJid, adminJid);
        assertThat(removeResult.results().get(1).errorCode()).isEqualTo("GROUP_PERMISSION_DENIED");
        assertThat(removeResult.results().get(1).errorMessage())
                .isEqualTo("protocol complete denied reason");
        verify(invitePort, times(2)).getInvite(account, "baseline@g.us");
        verify(readMetadataPort).getMetadata(account, "baseline@g.us");
        verify(writeMetadataPort, times(2))
                .getMetadata(account.protocolAccountId(), "baseline@g.us");
        verify(participantPort).updateParticipants(
                account, "baseline@g.us", List.of(memberJid), GroupParticipantAction.PROMOTE);
        verify(participantPort).updateParticipants(
                account, "baseline@g.us", List.of(adminJid), GroupParticipantAction.DEMOTE);
        verify(participantPort).updateParticipants(
                account, "baseline@g.us", List.of(memberJid, adminJid), GroupParticipantAction.REMOVE);
        verifyNoMoreInteractions(participantPort);
    }

    @Test
    void participantActionsRejectNonAdminAndNonBaselineBeforeMutation() {
        ProtocolAccountRef memberAccount = stubExecutionAccount(26L);
        when(writeMetadataPort.getMetadata(memberAccount.protocolAccountId(), "baseline@g.us"))
                .thenReturn(metadata(
                        "baseline@g.us",
                        "普通成员群",
                        false,
                        participant("8613800000026@s.whatsapp.net", false, false, null),
                        participant("8613800000099@s.whatsapp.net", false, false, null)));
        HistoricalGroupParticipantActionDTO memberRequest = new HistoricalGroupParticipantActionDTO(
                26L, "baseline@g.us", List.of("8613800000099@s.whatsapp.net"));

        assertThatThrownBy(() -> service.removeParticipants(memberRequest))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.GROUP_PERMISSION_DENIED.code());
        verify(participantPort, never()).updateParticipants(
                any(ProtocolAccountRef.class), any(), anyList(), any());

        stubExecutionAccount(27L);
        when(executionAccountSelector.require(27L, "outside@g.us"))
                .thenThrow(new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "目标群不属于账号组历史群: outside@g.us"));
        HistoricalGroupParticipantActionDTO outsideRequest = new HistoricalGroupParticipantActionDTO(
                27L, "outside@g.us", List.of("8613800000099@s.whatsapp.net"));
        assertThatThrownBy(() -> service.promoteParticipants(outsideRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于账号组历史群");
        verify(readMetadataPort, never()).getMetadata(
                org.mockito.ArgumentMatchers.any(), eq("outside@g.us"));
        verify(writeMetadataPort, never()).getMetadata("acc_27", "outside@g.us");
    }

    @Test
    void wholeParticipantProtocolFailureBecomesOrderedItemFailuresWithoutRetry() {
        ProtocolAccountRef account = stubExecutionAccount(28L);
        String firstJid = "8613800000101@s.whatsapp.net";
        String secondJid = "8613800000102@s.whatsapp.net";
        when(writeMetadataPort.getMetadata(account.protocolAccountId(), "baseline@g.us"))
                .thenReturn(metadata(
                        "baseline@g.us",
                        "管理群",
                        false,
                        participant("8613800000028@s.whatsapp.net", true, false, "admin"),
                        participant(firstJid, false, false, null),
                        participant(secondJid, false, false, null)));
        when(invitePort.getInvite(account, "baseline@g.us"))
                .thenReturn(new GroupInviteResult(
                        "baseline@g.us", "fresh", "https://chat.whatsapp.com/fresh"));
        when(participantPort.updateParticipants(
                account,
                "baseline@g.us",
                List.of(firstJid, secondJid),
                GroupParticipantAction.REMOVE))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.GROUP_PERMISSION_DENIED,
                        "协议整批完整失败原因"));

        var result = service.removeParticipants(new HistoricalGroupParticipantActionDTO(
                28L, "baseline@g.us", List.of(firstJid, secondJid)));

        assertThat(result.results()).extracting(item -> item.participantJid())
                .containsExactly(firstJid, secondJid);
        assertThat(result.results()).extracting(item -> item.errorCode())
                .containsOnly("GROUP_PERMISSION_DENIED");
        assertThat(result.results()).extracting(item -> item.errorMessage())
                .containsOnly("协议整批完整失败原因");
        verify(participantPort, times(1)).updateParticipants(
                account,
                "baseline@g.us",
                List.of(firstJid, secondJid),
                GroupParticipantAction.REMOVE);
    }

    @Test
    void allLocallyRejectedParticipantsAreNotReportedAsPartialSuccess() {
        ProtocolAccountRef account = stubExecutionAccount(29L);
        String selfJid = "8613800000029@s.whatsapp.net";
        String ownerJid = "8613800000001@s.whatsapp.net";
        String memberJid = "8613800000002@s.whatsapp.net";
        when(writeMetadataPort.getMetadata(account.protocolAccountId(), "baseline@g.us"))
                .thenReturn(metadata(
                        "baseline@g.us",
                        "管理群",
                        false,
                        participant(selfJid, true, false, "admin"),
                        participant(ownerJid, true, true, "superadmin"),
                        participant(memberJid, false, false, null)));
        when(invitePort.getInvite(account, "baseline@g.us"))
                .thenReturn(new GroupInviteResult(
                        "baseline@g.us", "fresh", "https://chat.whatsapp.com/fresh"));

        var result = service.demoteParticipants(new HistoricalGroupParticipantActionDTO(
                29L,
                "baseline@g.us",
                List.of(selfJid, ownerJid, memberJid)));

        assertThat(result.ok()).isFalse();
        assertThat(result.partial()).isFalse();
        assertThat(result.results()).extracting(item -> item.errorCode())
                .containsExactly("SELF_PROTECTED", "OWNER_PROTECTED", "ROLE_MISMATCH");
        verifyNoInteractions(participantPort);
    }

    @Test
    void participantActionsReturnCompleteMetadataGateFailureAsBusinessError() {
        ProtocolAccountRef account = stubExecutionAccount(30L);
        when(writeMetadataPort.getMetadata(account.protocolAccountId(), "baseline@g.us"))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.GROUP_UNAVAILABLE,
                        "写前 metadata 完整失败原因"));

        assertThatThrownBy(() -> service.removeParticipants(new HistoricalGroupParticipantActionDTO(
                30L,
                "baseline@g.us",
                List.of("8613800000101@s.whatsapp.net"))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("写前 metadata 完整失败原因");
        verifyNoInteractions(invitePort, participantPort);
    }

    private ProtocolAccountRef stubExecutionAccount(Long accountGroupId) {
        return stubExecutionAccount(accountGroupId, ProtocolBackend.WEB);
    }

    private ProtocolAccountRef stubExecutionAccount(
            Long accountGroupId,
            ProtocolBackend backend) {
        Long accountId = accountGroupId;
        ProtocolAccountRef account = new ProtocolAccountRef(
                accountId,
                backend,
                backend == ProtocolBackend.WEB
                        ? "acc_" + accountId
                        : "android_" + accountId,
                backend == ProtocolBackend.WEB
                        ? "86138000000" + accountId
                        : "919000000001");
        when(executionAccountSelector.require(eq(accountGroupId), any()))
                .thenReturn(new GroupExecutionAccount(
                        accountId,
                        backend.name(),
                        account.protocolAccountId(),
                        account.wsPhone(),
                        true));
        return account;
    }

    private static GroupMetadataResult metadata(
            String groupJid,
            String subject,
            Boolean announce,
            GroupParticipantResult... participants) {
        return new GroupMetadataResult(
                groupJid,
                subject,
                null,
                null,
                null,
                true,
                announce,
                false,
                true,
                false,
                0,
                true,
                true,
                null,
                false,
                true,
                List.of(participants));
    }

    private static GroupParticipantResult participant(
            String jid,
            boolean admin,
            boolean owner,
            String role) {
        String phone = jid.substring(0, jid.indexOf('@'));
        return new GroupParticipantResult(jid, phone, admin, owner, role);
    }

    private static GroupParticipantBatchResult batch(GroupParticipantBatchResult.Item... items) {
        return new GroupParticipantBatchResult(false, List.of(items));
    }

    private static GroupParticipantBatchResult.Item item(
            String jid,
            String status,
            String rawStatus) {
        return new GroupParticipantBatchResult.Item(jid, status, rawStatus);
    }
}
