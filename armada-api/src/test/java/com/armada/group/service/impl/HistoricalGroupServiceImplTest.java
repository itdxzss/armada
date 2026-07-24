package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.dto.HistoricalGroupParticipantActionDTO;
import com.armada.group.model.enums.HistoricalGroupMembershipState;
import com.armada.group.model.enums.HistoricalGroupSelfRole;
import com.armada.group.model.enums.RoleCategory;
import com.armada.group.model.enums.SpeechState;
import com.armada.group.model.vo.AccountGroupBaselineRow;
import com.armada.group.model.vo.HistoricalGroupDetailVO;
import com.armada.group.model.vo.HistoricalGroupItemVO;
import com.armada.group.service.HistoricalGroupProtocolPorts;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountGroupMetadataSummaryResult;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.AccountParticipatingGroupPort;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.platform.protocol.port.GroupMetadataPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class HistoricalGroupServiceImplTest {

    private final AccountProtocolLookupService accountLookupService =
            Mockito.mock(AccountProtocolLookupService.class);
    private final AccountGroupMembershipMapper membershipMapper =
            Mockito.mock(AccountGroupMembershipMapper.class);
    private final AccountParticipatingGroupPort participatingGroupPort =
            Mockito.mock(AccountParticipatingGroupPort.class);
    private final GroupMetadataPort metadataPort = Mockito.mock(GroupMetadataPort.class);
    private final GroupInvitePort invitePort = Mockito.mock(GroupInvitePort.class);
    private final GroupParticipantPort participantPort = Mockito.mock(GroupParticipantPort.class);
    private final HistoricalGroupServiceImpl service = new HistoricalGroupServiceImpl(
            accountLookupService,
            membershipMapper,
            new HistoricalGroupProtocolPorts(
                    participatingGroupPort, metadataPort, invitePort, participantPort),
            new ObjectMapper());

    @Test
    void detailRejectsNonBaselineGroupBeforeAnyProtocolCall() {
        stubBaseline(20L, "[\"baseline@g.us\"]", null);

        assertThatThrownBy(() -> service.getHistoricalGroupDetail(20L, "outside@g.us"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于操作账号 baseline");
        verifyNoInteractions(metadataPort, invitePort, participantPort);
    }

    @Test
    void detailLoadsFullMetadataMembersAndInviteForBaselineGroup() {
        ProtocolAccountRef account = stubBaseline(21L, "[\"baseline@g.us\"]", null);
        when(metadataPort.getMetadata(account.protocolAccountId(), "baseline@g.us"))
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
    }

    @Test
    void detailKeepsMetadataReadOnlyAndReturnsCompleteInviteFailure() {
        ProtocolAccountRef account = stubBaseline(22L, "[\"baseline@g.us\"]", null);
        when(metadataPort.getMetadata(account.protocolAccountId(), "baseline@g.us"))
                .thenReturn(metadata(
                        "baseline@g.us",
                        "只读群名",
                        false,
                        participant("8613800000022@s.whatsapp.net", true, false, "admin"),
                        participant("8613800000099@s.whatsapp.net", false, false, null)));
        when(invitePort.getInvite(account, "baseline@g.us"))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.INVALID_GROUP_LINK,
                        "邀请链接读取完整失败原因"));

        HistoricalGroupDetailVO result = service.getHistoricalGroupDetail(22L, "baseline@g.us");

        assertThat(result.subject()).isEqualTo("只读群名");
        assertThat(result.members()).hasSize(2);
        assertThat(result.linkAvailable()).isFalse();
        assertThat(result.operationAllowed()).isFalse();
        assertThat(result.operationDisabledReason()).isEqualTo("邀请链接读取完整失败原因");
        assertThat(result.errorCode()).isEqualTo("INVALID_GROUP_LINK");
        assertThat(result.errorMessage()).isEqualTo("邀请链接读取完整失败原因");
    }

    @Test
    void detailStillFetchesInviteAndReturnsReadOnlyFailureWhenMetadataFails() {
        ProtocolAccountRef account = stubBaseline(
                25L,
                "[\"baseline@g.us\"]",
                "{\"baseline@g.us\":\"历史群名\"}");
        when(metadataPort.getMetadata(account.protocolAccountId(), "baseline@g.us"))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.GROUP_UNAVAILABLE,
                        "metadata 完整失败原因"));
        when(invitePort.getInvite(account, "baseline@g.us"))
                .thenReturn(new GroupInviteResult(
                        "baseline@g.us", "still-current", "https://chat.whatsapp.com/still-current"));

        HistoricalGroupDetailVO result = service.getHistoricalGroupDetail(25L, "baseline@g.us");

        assertThat(result.subject()).isEqualTo("历史群名");
        assertThat(result.membershipState()).isEqualTo(HistoricalGroupMembershipState.FETCH_FAILED);
        assertThat(result.speechState()).isEqualTo(SpeechState.ABNORMAL);
        assertThat(result.members()).isEmpty();
        assertThat(result.inviteUrl()).isEqualTo("https://chat.whatsapp.com/still-current");
        assertThat(result.linkAvailable()).isTrue();
        assertThat(result.operationAllowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GROUP_UNAVAILABLE");
        assertThat(result.errorMessage()).isEqualTo("metadata 完整失败原因");
        verify(invitePort).getInvite(account, "baseline@g.us");
    }

    @Test
    void promoteParticipantsRejectsMissingFreshInviteBeforeWrite() {
        ProtocolAccountRef account = stubBaseline(23L, "[\"baseline@g.us\"]", null);
        when(metadataPort.getMetadata(account.protocolAccountId(), "baseline@g.us"))
                .thenReturn(metadata(
                        "baseline@g.us",
                        "管理群",
                        false,
                        participant("8613800000023@s.whatsapp.net", true, false, "admin"),
                        participant("8613800000099@s.whatsapp.net", false, false, null)));
        when(invitePort.getInvite(account, "baseline@g.us"))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.INVALID_GROUP_LINK,
                        "写前邀请链接完整失败原因"));

        assertThatThrownBy(() -> service.promoteParticipants(new HistoricalGroupParticipantActionDTO(
                23L,
                "baseline@g.us",
                List.of("8613800000099@s.whatsapp.net"))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("写前邀请链接完整失败原因");
        verify(participantPort, never()).updateParticipants(
                any(ProtocolAccountRef.class), any(), anyList(), any());
    }

    @Test
    void administratorActionsProtectSelfAndOwnerAndPreserveItemOrderAndErrors() {
        ProtocolAccountRef account = stubBaseline(24L, "[\"baseline@g.us\"]", null);
        String selfJid = "8613800000024@s.whatsapp.net";
        String ownerJid = "8613800000001@s.whatsapp.net";
        String adminJid = "8613800000002@s.whatsapp.net";
        String memberJid = "8613800000003@s.whatsapp.net";
        when(metadataPort.getMetadata(account.protocolAccountId(), "baseline@g.us"))
                .thenReturn(metadata(
                        "baseline@g.us",
                        "管理群",
                        false,
                        participant(selfJid, true, false, "admin"),
                        participant(ownerJid, true, true, "superadmin"),
                        participant(adminJid, true, false, "admin"),
                        participant(memberJid, false, false, null)));
        when(invitePort.getInvite(account, "baseline@g.us"))
                .thenReturn(new GroupInviteResult(
                        "baseline@g.us", "fresh", "https://chat.whatsapp.com/fresh"));
        when(participantPort.updateParticipants(
                account.protocolAccountId(),
                "baseline@g.us",
                List.of(memberJid),
                GroupParticipantAction.PROMOTE))
                .thenReturn(batch(item(memberJid, "OK", "200")));
        when(participantPort.updateParticipants(
                account.protocolAccountId(),
                "baseline@g.us",
                List.of(adminJid),
                GroupParticipantAction.DEMOTE))
                .thenReturn(batch(item(adminJid, "OK", "200")));
        when(participantPort.updateParticipants(
                account.protocolAccountId(),
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
        verify(invitePort, times(3)).getInvite(account, "baseline@g.us");
        verify(metadataPort, times(3)).getMetadata(account.protocolAccountId(), "baseline@g.us");
        verify(participantPort).updateParticipants(
                account.protocolAccountId(), "baseline@g.us", List.of(memberJid), GroupParticipantAction.PROMOTE);
        verify(participantPort).updateParticipants(
                account.protocolAccountId(), "baseline@g.us", List.of(adminJid), GroupParticipantAction.DEMOTE);
        verify(participantPort).updateParticipants(
                account.protocolAccountId(), "baseline@g.us", List.of(memberJid, adminJid), GroupParticipantAction.REMOVE);
        verifyNoMoreInteractions(participantPort);
    }

    @Test
    void participantActionsRejectNonAdminAndNonBaselineBeforeMutation() {
        ProtocolAccountRef memberAccount = stubBaseline(26L, "[\"baseline@g.us\"]", null);
        when(metadataPort.getMetadata(memberAccount.protocolAccountId(), "baseline@g.us"))
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

        stubBaseline(27L, "[\"baseline@g.us\"]", null);
        HistoricalGroupParticipantActionDTO outsideRequest = new HistoricalGroupParticipantActionDTO(
                27L, "outside@g.us", List.of("8613800000099@s.whatsapp.net"));
        assertThatThrownBy(() -> service.promoteParticipants(outsideRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于操作账号 baseline");
        verify(metadataPort, never()).getMetadata("acc_27", "outside@g.us");
    }

    @Test
    void wholeParticipantProtocolFailureBecomesOrderedItemFailuresWithoutRetry() {
        ProtocolAccountRef account = stubBaseline(28L, "[\"baseline@g.us\"]", null);
        String firstJid = "8613800000101@s.whatsapp.net";
        String secondJid = "8613800000102@s.whatsapp.net";
        when(metadataPort.getMetadata(account.protocolAccountId(), "baseline@g.us"))
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
                account.protocolAccountId(),
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
                account.protocolAccountId(),
                "baseline@g.us",
                List.of(firstJid, secondJid),
                GroupParticipantAction.REMOVE);
    }

    @Test
    void allLocallyRejectedParticipantsAreNotReportedAsPartialSuccess() {
        ProtocolAccountRef account = stubBaseline(29L, "[\"baseline@g.us\"]", null);
        String selfJid = "8613800000029@s.whatsapp.net";
        String ownerJid = "8613800000001@s.whatsapp.net";
        String memberJid = "8613800000002@s.whatsapp.net";
        when(metadataPort.getMetadata(account.protocolAccountId(), "baseline@g.us"))
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
        ProtocolAccountRef account = stubBaseline(30L, "[\"baseline@g.us\"]", null);
        when(metadataPort.getMetadata(account.protocolAccountId(), "baseline@g.us"))
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

    @Test
    void listHistoricalGroups_returnsBaselineOnlyInStoredOrderAsUnverified() {
        ProtocolAccountRef account = new ProtocolAccountRef(7L, ProtocolBackend.WEB, "acc_7", "8613800000007");
        AccountGroupBaselineRow baseline = new AccountGroupBaselineRow();
        baseline.setAccountId(7L);
        baseline.setBaselineGroupJidsJson("[\"group-b@g.us\",\"group-a@g.us\"]");
        baseline.setBaselineGroupSubjectsJson("{\"group-a@g.us\":\"历史群 A\"}");
        when(accountLookupService.findActiveProtocolRef(7L)).thenReturn(Optional.of(account));
        when(membershipMapper.selectAccountBaselineRow(7L)).thenReturn(baseline);

        List<HistoricalGroupItemVO> result = service.listHistoricalGroups(7L);

        assertThat(result).extracting(HistoricalGroupItemVO::groupJid)
                .containsExactly("group-b@g.us", "group-a@g.us");
        assertThat(result).extracting(HistoricalGroupItemVO::subject)
                .containsExactly(null, "历史群 A");
        assertThat(result).extracting(HistoricalGroupItemVO::membershipState)
                .containsOnly(HistoricalGroupMembershipState.UNVERIFIED);
        verifyNoInteractions(participatingGroupPort);
    }

    @Test
    void refreshHistoricalGroups_wholeLightListFailureMarksEveryBaselineFetchFailed() {
        ProtocolAccountRef account = stubBaseline(
                8L,
                "[\"group-a@g.us\",\"group-b@g.us\"]",
                "{\"group-a@g.us\":\"历史群 A\"}");
        when(participatingGroupPort.listCurrent(account))
                .thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "轻量列表完整失败原因"));

        List<HistoricalGroupItemVO> result = service.refreshHistoricalGroups(8L);

        assertThat(result).extracting(HistoricalGroupItemVO::membershipState)
                .containsOnly(HistoricalGroupMembershipState.FETCH_FAILED);
        assertThat(result).extracting(HistoricalGroupItemVO::subject)
                .containsExactly("历史群 A", null);
        assertThat(result).extracting(HistoricalGroupItemVO::speechState)
                .containsOnly(SpeechState.ABNORMAL);
        assertThat(result).extracting(HistoricalGroupItemVO::errorMessage)
                .containsOnly("轻量列表完整失败原因");
        verify(participatingGroupPort, never()).summarize(eq(account), anyList(), anyInt());
    }

    @Test
    void refreshHistoricalGroups_intersectsBaselineAndExcludesCurrentOnlyGroups() {
        ProtocolAccountRef account = stubBaseline(
                9L,
                "[\"group-in@g.us\",\"group-out@g.us\"]",
                null);
        when(participatingGroupPort.listCurrent(account)).thenReturn(List.of(
                currentGroup("group-in@g.us", "当前群名"),
                currentGroup("current-only@g.us", "非 baseline 群")));
        when(participatingGroupPort.summarize(account, List.of("group-in@g.us"), 8)).thenReturn(List.of(
                summary("group-in@g.us", true, null, "摘要群名", 21,
                        "MEMBER", false, false)));

        List<HistoricalGroupItemVO> result = service.refreshHistoricalGroups(9L);

        assertThat(result).extracting(HistoricalGroupItemVO::groupJid)
                .containsExactly("group-in@g.us", "group-out@g.us");
        assertThat(result).extracting(HistoricalGroupItemVO::membershipState)
                .containsExactly(
                        HistoricalGroupMembershipState.CURRENT_IN_GROUP,
                        HistoricalGroupMembershipState.CURRENT_NOT_IN_GROUP);
        assertThat(result.get(0).subject()).isEqualTo("摘要群名");
        assertThat(result.get(0).roleCategory()).isEqualTo(RoleCategory.MEMBER);
        assertThat(result.get(0).speechState()).isEqualTo(SpeechState.NORMAL);
        verify(participatingGroupPort).summarize(account, List.of("group-in@g.us"), 8);
        verify(membershipMapper).selectAccountBaselineRow(9L);
        verifyNoMoreInteractions(membershipMapper);
    }

    @Test
    void refreshHistoricalGroups_mapsRolesSpeechAndItemFailures() {
        List<String> groupJids = List.of(
                "owner@g.us", "admin@g.us", "member@g.us", "normal@g.us", "failed@g.us", "abnormal@g.us");
        ProtocolAccountRef account = stubBaseline(
                10L,
                "[\"owner@g.us\",\"admin@g.us\",\"member@g.us\","
                        + "\"normal@g.us\",\"failed@g.us\",\"abnormal@g.us\"]",
                "{}");
        when(participatingGroupPort.listCurrent(account)).thenReturn(groupJids.stream()
                .map(groupJid -> currentGroup(groupJid, "当前-" + groupJid))
                .toList());
        when(participatingGroupPort.summarize(account, groupJids, 8)).thenReturn(List.of(
                summary("owner@g.us", true, null, "群主群", 31, "OWNER", true, false),
                summary("admin@g.us", true, null, "管理群", 32, "ADMIN", true, false),
                summary("member@g.us", true, null, "成员群", 33, "MEMBER", true, false),
                summary("normal@g.us", true, null, "普通群", 34, "MEMBER", false, false),
                summary("failed@g.us", false, "单项 metadata 完整错误", null, null, null, null, false),
                summary("abnormal@g.us", true, "群状态完整异常", "异常群", 35, "ADMIN", true, true)));

        List<HistoricalGroupItemVO> result = service.refreshHistoricalGroups(10L);

        assertThat(result.get(0).selfRole()).isEqualTo(HistoricalGroupSelfRole.OWNER);
        assertThat(result.get(0).roleCategory()).isEqualTo(RoleCategory.ADMIN);
        assertThat(result.get(0).speechState()).isEqualTo(SpeechState.ADMIN_CAN_SPEAK);
        assertThat(result.get(0).subject()).isEqualTo("群主群");
        assertThat(result.get(0).memberSize()).isEqualTo(31);
        assertThat(result.get(0).announceOnly()).isTrue();
        assertThat(result.get(1).selfRole()).isEqualTo(HistoricalGroupSelfRole.ADMIN);
        assertThat(result.get(1).roleCategory()).isEqualTo(RoleCategory.ADMIN);
        assertThat(result.get(1).speechState()).isEqualTo(SpeechState.ADMIN_CAN_SPEAK);
        assertThat(result.get(2).roleCategory()).isEqualTo(RoleCategory.MEMBER);
        assertThat(result.get(2).speechState()).isEqualTo(SpeechState.CANNOT_SPEAK);
        assertThat(result.get(3).speechState()).isEqualTo(SpeechState.NORMAL);
        assertThat(result.get(4).membershipState()).isEqualTo(HistoricalGroupMembershipState.CURRENT_IN_GROUP);
        assertThat(result.get(4).speechState()).isEqualTo(SpeechState.ABNORMAL);
        assertThat(result.get(4).errorMessage()).isEqualTo("单项 metadata 完整错误");
        assertThat(result.get(5).speechState()).isEqualTo(SpeechState.ABNORMAL);
        assertThat(result.get(5).errorMessage()).isEqualTo("群状态完整异常");
    }

    @Test
    void refreshHistoricalGroups_wholeSummaryFailureOnlyMarksIntersectionAbnormal() {
        ProtocolAccountRef account = stubBaseline(
                11L,
                "[\"current@g.us\",\"exited@g.us\"]",
                "{\"exited@g.us\":\"已退出历史群\"}");
        when(participatingGroupPort.listCurrent(account))
                .thenReturn(List.of(currentGroup("current@g.us", "当前群")));
        when(participatingGroupPort.summarize(account, List.of("current@g.us"), 8))
                .thenThrow(new ProtocolException(ProtocolErrorCode.NETWORK, "摘要整体完整失败"));

        List<HistoricalGroupItemVO> result = service.refreshHistoricalGroups(11L);

        assertThat(result.get(0).membershipState()).isEqualTo(HistoricalGroupMembershipState.CURRENT_IN_GROUP);
        assertThat(result.get(0).speechState()).isEqualTo(SpeechState.ABNORMAL);
        assertThat(result.get(0).errorMessage()).isEqualTo("摘要整体完整失败");
        assertThat(result.get(1).membershipState()).isEqualTo(HistoricalGroupMembershipState.CURRENT_NOT_IN_GROUP);
        assertThat(result.get(1).subject()).isEqualTo("已退出历史群");
        assertThat(result.get(1).speechState()).isNull();
        assertThat(result.get(1).errorMessage()).isNull();
    }

    @Test
    void listHistoricalGroups_acceptsHistoricalNullSubjectsAndRejectsInvisibleAccount() {
        stubBaseline(12L, "[\"legacy@g.us\"]", null);

        assertThat(service.listHistoricalGroups(12L).get(0).subject()).isNull();

        when(accountLookupService.findActiveProtocolRef(13L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.listHistoricalGroups(13L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.code());
        verify(membershipMapper, never()).selectAccountBaselineRow(13L);
    }

    private ProtocolAccountRef stubBaseline(Long accountId, String groupJidsJson, String subjectsJson) {
        ProtocolAccountRef account = new ProtocolAccountRef(
                accountId,
                ProtocolBackend.WEB,
                "acc_" + accountId,
                "86138000000" + accountId);
        AccountGroupBaselineRow baseline = new AccountGroupBaselineRow();
        baseline.setAccountId(accountId);
        baseline.setBaselineGroupJidsJson(groupJidsJson);
        baseline.setBaselineGroupSubjectsJson(subjectsJson);
        when(accountLookupService.findActiveProtocolRef(accountId)).thenReturn(Optional.of(account));
        when(membershipMapper.selectAccountBaselineRow(accountId)).thenReturn(baseline);
        return account;
    }

    private static AccountParticipatingGroupResult.Group currentGroup(String groupJid, String subject) {
        return new AccountParticipatingGroupResult.Group(groupJid, subject, null, null, null, null);
    }

    private static AccountGroupMetadataSummaryResult summary(
            String groupJid,
            boolean success,
            String error,
            String subject,
            Integer memberSize,
            String selfRole,
            Boolean announceOnly,
            boolean stateAbnormal) {
        return new AccountGroupMetadataSummaryResult(
                groupJid,
                success,
                error,
                subject,
                memberSize,
                selfRole,
                announceOnly,
                stateAbnormal);
    }

    private static GroupMetadataResult metadata(
            String groupJid,
            String subject,
            Boolean announce,
            GroupParticipantResult... participants) {
        return new GroupMetadataResult(
                groupJid,
                subject,
                announce,
                false,
                true,
                false,
                0,
                true,
                true,
                null,
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
