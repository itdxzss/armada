package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.mapper.HistoricalGroupPullExecutionMapper;
import com.armada.group.mapper.HistoricalGroupPullMemberMapper;
import com.armada.group.model.entity.HistoricalGroupPullExecution;
import com.armada.group.model.entity.HistoricalGroupPullMember;
import com.armada.group.model.enums.HistoricalGroupAddStatus;
import com.armada.group.model.enums.HistoricalGroupContactStatus;
import com.armada.group.model.enums.HistoricalGroupMarketingStatus;
import com.armada.group.model.enums.HistoricalGroupMaterialType;
import com.armada.group.model.enums.HistoricalGroupMemberSendStatus;
import com.armada.group.model.enums.HistoricalGroupPullStatus;
import com.armada.group.service.HistoricalGroupPullProtocolPorts;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.shared.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 历史群一次性拉人 worker 测试。 */
@ExtendWith(MockitoExtension.class)
class HistoricalGroupPullWorkerImplTest {

    private static final long TENANT_ID = 71L;
    private static final long EXECUTION_ID = 901L;

    @Mock
    private AccountProtocolLookupService accountLookupService;
    @Mock
    private HistoricalGroupPullExecutionMapper executionMapper;
    @Mock
    private HistoricalGroupPullMemberMapper memberMapper;
    @Mock
    private GroupJoinPort groupJoinPort;
    @Mock
    private ContactPort contactPort;
    @Mock
    private GroupParticipantPort participantPort;

    private HistoricalGroupPullWorkerImpl worker;
    private HistoricalGroupPullExecution execution;
    private Map<Long, HistoricalGroupPullMember> membersById;

    @BeforeEach
    void setUp() {
        execution = execution();
        membersById = new LinkedHashMap<>();
        HistoricalGroupPullExecutionFinalizer finalizer =
                new HistoricalGroupPullExecutionFinalizer(executionMapper, memberMapper);
        worker = new HistoricalGroupPullWorkerImpl(
                accountLookupService,
                executionMapper,
                memberMapper,
                new HistoricalGroupPullProtocolPorts(groupJoinPort, contactPort, participantPort),
                finalizer);
        when(executionMapper.selectByTenantAndId(TENANT_ID, EXECUTION_ID)).thenReturn(execution);
        when(executionMapper.finishIfRunning(any(), eq(HistoricalGroupPullStatus.RUNNING.code())))
                .thenReturn(1);
        doAnswer(invocation -> updateContact(
                invocation.getArgument(0), invocation.getArgument(2),
                invocation.getArgument(3), invocation.getArgument(4)))
                .when(memberMapper).updateContactResultIfPending(
                        anyLong(), anyInt(), anyInt(), any(), any(), anyLong());
        doAnswer(invocation -> updateAdd(
                invocation.getArgument(0), invocation.getArgument(2),
                invocation.getArgument(3), invocation.getArgument(4)))
                .when(memberMapper).updateAddResultIfPending(
                        anyLong(), anyInt(), anyInt(), any(), any(), anyLong());
        when(memberMapper.selectOrderedByExecutionId(EXECUTION_ID))
                .thenAnswer(invocation -> List.copyOf(membersById.values()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void usesOneRandomPullerPersistedLinkMarketingFirstBatchesAndAddBasedStatistics() {
        HistoricalGroupPullMember marketingOne = member(11L, 3, "8613900000011", true);
        HistoricalGroupPullMember marketingTwo = member(12L, 8, "8613900000012", true);
        HistoricalGroupPullMember normal = member(13L, 1, "8613800000013", false);
        putMembers(marketingOne, marketingTwo, normal);
        preparePullerAssignment();
        ProtocolAccountRef puller = puller();
        when(accountLookupService.findRandomOnlineNormalWebByGroupId(301L)).thenReturn(Optional.of(puller));
        when(groupJoinPort.join(any())).thenReturn(
                new GroupJoinResult("120363target@g.us", GroupJoinOutcome.JOINED));
        doThrow(new ProtocolException(ProtocolErrorCode.NETWORK, "完整联系人保存失败"))
                .when(contactPort).saveContact("puller-protocol", "8613900000011", "8613900000011");
        when(participantPort.updateParticipants(
                eq("puller-protocol"), eq("120363target@g.us"), any(), eq(GroupParticipantAction.ADD)))
                .thenReturn(new GroupParticipantBatchResult(true, List.of(
                                new GroupParticipantBatchResult.Item(
                                        "8613900000011@s.whatsapp.net", "OK", "200"),
                                new GroupParticipantBatchResult.Item(
                                        "8613900000012@s.whatsapp.net", "NOT_AUTHORIZED", "403"))),
                        new GroupParticipantBatchResult(false, List.of(
                                new GroupParticipantBatchResult.Item(
                                        "8613800000013@s.whatsapp.net", "OK", "200"))));
        TenantContext.set(999L);

        worker.execute(TENANT_ID, EXECUTION_ID);

        assertThat(TenantContext.get()).isEqualTo(999L);
        verify(accountLookupService, times(1)).findRandomOnlineNormalWebByGroupId(301L);
        ArgumentCaptor<GroupJoinCommand> joinCaptor = ArgumentCaptor.forClass(GroupJoinCommand.class);
        verify(groupJoinPort, times(1)).join(joinCaptor.capture());
        assertThat(joinCaptor.getValue().inviteLinkOrCode()).isEqualTo("persisted-invite-link");
        assertThat(joinCaptor.getValue().account()).isEqualTo(puller);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(participantPort, times(2)).updateParticipants(
                eq("puller-protocol"), eq("120363target@g.us"), batchCaptor.capture(),
                eq(GroupParticipantAction.ADD));
        assertThat(batchCaptor.getAllValues()).containsExactly(
                List.of("8613900000011@s.whatsapp.net", "8613900000012@s.whatsapp.net"),
                List.of("8613800000013@s.whatsapp.net"));
        assertThat(marketingOne.getContactStatus()).isEqualTo(HistoricalGroupContactStatus.FAILED.code());
        assertThat(marketingOne.getContactErrorMessage()).isEqualTo("完整联系人保存失败");
        assertThat(marketingOne.getAddStatus()).isEqualTo(HistoricalGroupAddStatus.SUCCESS.code());
        assertThat(marketingTwo.getAddErrorCode()).isEqualTo("NOT_AUTHORIZED");
        assertThat(marketingTwo.getAddErrorMessage()).isEqualTo("403");
        ArgumentCaptor<HistoricalGroupPullExecution> finishCaptor =
                ArgumentCaptor.forClass(HistoricalGroupPullExecution.class);
        verify(executionMapper).finishIfRunning(
                finishCaptor.capture(), eq(HistoricalGroupPullStatus.RUNNING.code()));
        assertThat(finishCaptor.getValue().getPullStatus())
                .isEqualTo(HistoricalGroupPullStatus.PARTIAL_SUCCESS.code());
        assertThat(finishCaptor.getValue().getPullSuccessCount()).isEqualTo(2);
        assertThat(finishCaptor.getValue().getPullFailureCount()).isEqualTo(1);
    }

    @Test
    void noPullerFailsExecutionAndMembersWithoutAnyProtocolAttempt() {
        HistoricalGroupPullMember member = member(21L, 1, "8613800000021", false);
        putMembers(member);
        when(accountLookupService.findRandomOnlineNormalWebByGroupId(301L)).thenReturn(Optional.empty());
        when(accountLookupService.findRandomOnlineNormalByGroupId(301L)).thenReturn(Optional.empty());

        worker.execute(TENANT_ID, EXECUTION_ID);

        verify(groupJoinPort, never()).join(any());
        verify(contactPort, never()).saveContact(any(), any(), any());
        verify(participantPort, never()).updateParticipants(any(), any(), any(), any());
        assertThat(member.getContactStatus()).isEqualTo(HistoricalGroupContactStatus.FAILED.code());
        assertThat(member.getAddStatus()).isEqualTo(HistoricalGroupAddStatus.FAILED.code());
        ArgumentCaptor<HistoricalGroupPullExecution> finishCaptor =
                ArgumentCaptor.forClass(HistoricalGroupPullExecution.class);
        verify(executionMapper).finishIfRunning(
                finishCaptor.capture(), eq(HistoricalGroupPullStatus.RUNNING.code()));
        assertThat(finishCaptor.getValue().getPullStatus()).isEqualTo(HistoricalGroupPullStatus.FAILED.code());
        assertThat(finishCaptor.getValue().getFailureStage()).isEqualTo("PULLER_SELECT");
        assertThat(finishCaptor.getValue().getErrorCode()).isEqualTo("PULLER_UNAVAILABLE");
        assertThat(finishCaptor.getValue().getErrorMessage())
                .isEqualTo("拉手账号分组中没有在线正常且协议身份完整的账号");
    }

    @Test
    void joinFailureStopsBeforeMemberProtocolsAndNeverSelectsReplacement() {
        HistoricalGroupPullMember member = member(31L, 1, "8613800000031", false);
        putMembers(member);
        preparePullerAssignment();
        when(accountLookupService.findRandomOnlineNormalWebByGroupId(301L))
                .thenReturn(Optional.of(puller()));
        doThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "完整进群超时错误"))
                .when(groupJoinPort).join(any());

        assertDoesNotThrow(() -> worker.execute(TENANT_ID, EXECUTION_ID));

        verify(accountLookupService, times(1)).findRandomOnlineNormalWebByGroupId(301L);
        verify(groupJoinPort, times(1)).join(any());
        verify(contactPort, never()).saveContact(any(), any(), any());
        verify(participantPort, never()).updateParticipants(any(), any(), any(), any());
        ArgumentCaptor<HistoricalGroupPullExecution> finishCaptor =
                ArgumentCaptor.forClass(HistoricalGroupPullExecution.class);
        verify(executionMapper).finishIfRunning(
                finishCaptor.capture(), eq(HistoricalGroupPullStatus.RUNNING.code()));
        assertThat(finishCaptor.getValue().getFailureStage()).isEqualTo("GROUP_JOIN");
        assertThat(finishCaptor.getValue().getErrorCode()).isEqualTo("TIMEOUT");
        assertThat(finishCaptor.getValue().getErrorMessage()).isEqualTo("完整进群超时错误");
    }

    @Test
    void addBatchExceptionFailsThatBatchAndContinuesNextBatchWithoutRetry() {
        execution.setSingleAddCount(1);
        HistoricalGroupPullMember first = member(41L, 1, "8613800000041", false);
        HistoricalGroupPullMember second = member(42L, 2, "8613800000042", false);
        putMembers(first, second);
        preparePullerAssignment();
        when(accountLookupService.findRandomOnlineNormalWebByGroupId(301L))
                .thenReturn(Optional.of(puller()));
        when(groupJoinPort.join(any())).thenReturn(
                new GroupJoinResult("120363target@g.us", GroupJoinOutcome.ALREADY_JOINED));
        when(participantPort.updateParticipants(
                eq("puller-protocol"), eq("120363target@g.us"), any(), eq(GroupParticipantAction.ADD)))
                .thenThrow(new ProtocolException(ProtocolErrorCode.GROUP_PERMISSION_DENIED, "完整 ADD 拒绝"))
                .thenReturn(new GroupParticipantBatchResult(false, List.of(
                        new GroupParticipantBatchResult.Item(
                                "8613800000042@s.whatsapp.net", "OK", "200"))));

        worker.execute(TENANT_ID, EXECUTION_ID);

        verify(participantPort, times(2)).updateParticipants(
                eq("puller-protocol"), eq("120363target@g.us"), any(), eq(GroupParticipantAction.ADD));
        assertThat(first.getAddStatus()).isEqualTo(HistoricalGroupAddStatus.FAILED.code());
        assertThat(first.getAddErrorCode()).isEqualTo("GROUP_PERMISSION_DENIED");
        assertThat(first.getAddErrorMessage()).isEqualTo("完整 ADD 拒绝");
        assertThat(second.getAddStatus()).isEqualTo(HistoricalGroupAddStatus.SUCCESS.code());
    }

    @Test
    void rejectsUnexpectedAndroidCandidateBeforeJoinOrAnyMemberProtocol() {
        HistoricalGroupPullMember member = member(51L, 1, "8613800000051", false);
        putMembers(member);
        ProtocolAccountRef android =
                new ProtocolAccountRef(601L, ProtocolBackend.ANDROID, "android-601", "8613700000601");
        when(accountLookupService.findRandomOnlineNormalWebByGroupId(301L))
                .thenReturn(Optional.empty());
        when(accountLookupService.findRandomOnlineNormalByGroupId(301L))
                .thenReturn(Optional.of(android));

        worker.execute(TENANT_ID, EXECUTION_ID);

        verify(executionMapper, never()).assignPullerIfRunning(anyLong(), anyLong(), anyInt(), anyLong());
        verify(groupJoinPort, never()).join(any());
        verify(contactPort, never()).saveContact(any(), any(), any());
        verify(participantPort, never()).updateParticipants(any(), any(), any(), any());
        ArgumentCaptor<HistoricalGroupPullExecution> finishCaptor =
                ArgumentCaptor.forClass(HistoricalGroupPullExecution.class);
        verify(executionMapper).finishIfRunning(
                finishCaptor.capture(), eq(HistoricalGroupPullStatus.RUNNING.code()));
        assertThat(finishCaptor.getValue().getFailureStage()).isEqualTo("PULLER_SELECT");
        assertThat(finishCaptor.getValue().getErrorCode()).isEqualTo("PULLER_BACKEND_UNSUPPORTED");
    }

    private int updateContact(Long memberId, Integer targetStatus, String errorCode, String errorMessage) {
        HistoricalGroupPullMember member = membersById.get(memberId);
        member.setContactStatus(targetStatus);
        member.setContactErrorCode(errorCode);
        member.setContactErrorMessage(errorMessage);
        return 1;
    }

    private int updateAdd(Long memberId, Integer targetStatus, String errorCode, String errorMessage) {
        HistoricalGroupPullMember member = membersById.get(memberId);
        member.setAddStatus(targetStatus);
        member.setAddErrorCode(errorCode);
        member.setAddErrorMessage(errorMessage);
        return 1;
    }

    private void putMembers(HistoricalGroupPullMember... members) {
        for (HistoricalGroupPullMember member : members) {
            membersById.put(member.getId(), member);
        }
    }

    private void preparePullerAssignment() {
        when(executionMapper.assignPullerIfRunning(
                eq(EXECUTION_ID), anyLong(), eq(HistoricalGroupPullStatus.RUNNING.code()), anyLong()))
                .thenReturn(1);
    }

    private static HistoricalGroupPullExecution execution() {
        HistoricalGroupPullExecution row = new HistoricalGroupPullExecution();
        row.setId(EXECUTION_ID);
        row.setTenantId(TENANT_ID);
        row.setOperationAccountId(101L);
        row.setGroupJid("120363target@g.us");
        row.setInviteLink("persisted-invite-link");
        row.setPullerAccountGroupId(301L);
        row.setSingleAddCount(2);
        row.setPullStatus(HistoricalGroupPullStatus.RUNNING.code());
        row.setMarketingStatus(HistoricalGroupMarketingStatus.NOT_STARTED.code());
        return row;
    }

    private static HistoricalGroupPullMember member(Long id, int lineNo, String phone, boolean marketing) {
        HistoricalGroupPullMember row = new HistoricalGroupPullMember();
        row.setId(id);
        row.setTenantId(TENANT_ID);
        row.setExecutionId(EXECUTION_ID);
        row.setLineNo(lineNo);
        row.setPhone(phone);
        row.setMaterialType(marketing
                ? HistoricalGroupMaterialType.MARKETING.code()
                : HistoricalGroupMaterialType.NORMAL.code());
        row.setContactStatus(HistoricalGroupContactStatus.PENDING.code());
        row.setAddStatus(HistoricalGroupAddStatus.PENDING.code());
        row.setSendStatus(marketing
                ? HistoricalGroupMemberSendStatus.PENDING.code()
                : HistoricalGroupMemberSendStatus.NOT_APPLICABLE.code());
        return row;
    }

    private static ProtocolAccountRef puller() {
        return new ProtocolAccountRef(501L, ProtocolBackend.WEB, "puller-protocol", "8613700000501");
    }
}
