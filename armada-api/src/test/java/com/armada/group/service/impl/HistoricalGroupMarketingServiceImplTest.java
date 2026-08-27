package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.mapper.HistoricalGroupPullExecutionMapper;
import com.armada.group.mapper.HistoricalGroupPullMemberMapper;
import com.armada.group.model.dto.HistoricalGroupMarketingSendDTO;
import com.armada.group.model.entity.HistoricalGroupPullExecution;
import com.armada.group.model.entity.HistoricalGroupPullMember;
import com.armada.group.model.enums.HistoricalGroupMarketingStatus;
import com.armada.group.model.enums.HistoricalGroupMaterialType;
import com.armada.group.model.enums.HistoricalGroupMemberSendStatus;
import com.armada.group.model.enums.HistoricalGroupPullStatus;
import com.armada.group.model.vo.HistoricalGroupDetailVO;
import com.armada.group.service.HistoricalGroupPullExecutionService;
import com.armada.group.service.HistoricalGroupService;
import com.armada.marketing.model.vo.MarketingComposedMessageVO;
import com.armada.marketing.service.MarketingMessageCompositionService;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 历史群全部营销账号一次性发送测试。 */
@ExtendWith(MockitoExtension.class)
class HistoricalGroupMarketingServiceImplTest {

    private static final long TENANT_ID = 71L;
    private static final long EXECUTION_ID = 901L;
    private static final long TEMPLATE_ID = 801L;

    @Mock
    private HistoricalGroupPullExecutionMapper executionMapper;
    @Mock
    private HistoricalGroupPullMemberMapper memberMapper;
    @Mock
    private HistoricalGroupService historicalGroupService;
    @Mock
    private HistoricalGroupPullExecutionService executionService;
    @Mock
    private MarketingMessageCompositionService messageCompositionService;
    @Mock
    private AccountProtocolLookupService accountLookupService;
    @Mock
    private MessageSendPort messageSendPort;

    private HistoricalGroupMarketingServiceImpl service;
    private HistoricalGroupPullExecution execution;
    private List<HistoricalGroupPullMember> members;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
        DataScopeContext.open(DataScope.self(11L));
        execution = execution();
        members = new ArrayList<>();
        service = new HistoricalGroupMarketingServiceImpl(
                executionMapper,
                memberMapper,
                historicalGroupService,
                executionService,
                messageCompositionService,
                accountLookupService,
                messageSendPort);
        org.mockito.Mockito.lenient().when(executionMapper.selectByTenantAndIdForScope(
                eq(TENANT_ID), eq(EXECUTION_ID), any())).thenReturn(execution);
        org.mockito.Mockito.lenient().when(memberMapper.selectOrderedByExecutionId(EXECUTION_ID))
                .thenAnswer(invocation -> List.copyOf(members));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        DataScopeContext.clear();
    }

    @Test
    void enqueuesExactlyOneCommandForEveryMarketingMemberAndNeverSendsOrdinaryMembers() {
        members.add(member(11L, "8613900000011", true));
        members.add(member(12L, "8613900000012", false));
        members.add(member(13L, "8613900000013", true));
        prepareGateAndClaim();
        when(accountLookupService.findActiveProtocolRefsByPhones(List.of("8613900000011", "8613900000013")))
                .thenReturn(Map.of(
                        "8613900000011", account(111L, "web-11", "8613900000011"),
                        "8613900000013", account(113L, "web-13", "8613900000013")));
        when(memberMapper.markSendSendingIfPending(anyLong(), anyInt(), anyInt(), any(), anyLong()))
                .thenAnswer(invocation -> markSending(invocation.getArgument(0), invocation.getArgument(3)));
        when(messageSendPort.enqueue(any())).thenAnswer(invocation -> accepted(invocation.getArgument(0)));

        service.send(EXECUTION_ID, new HistoricalGroupMarketingSendDTO(TEMPLATE_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> commands = ArgumentCaptor.forClass(List.class);
        verify(messageSendPort, times(2)).enqueue(commands.capture());
        List<MessageSendCommand> flattened = commands.getAllValues().stream().flatMap(List::stream).toList();
        org.assertj.core.api.Assertions.assertThat(flattened)
                .extracting(command -> command.correlation().historicalGroup().memberId())
                .containsExactly(11L, 13L);
        org.assertj.core.api.Assertions.assertThat(flattened)
                .allSatisfy(command -> {
                    org.assertj.core.api.Assertions.assertThat(command.target().groupJid())
                            .isEqualTo("120363target@g.us");
                    org.assertj.core.api.Assertions.assertThat(command.correlation().source())
                            .isEqualTo("historical_group_pull");
                    org.assertj.core.api.Assertions.assertThat(command.payload().mentionAll()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(command.sendIntervalMs()).isEqualTo(500);
                });
        org.assertj.core.api.Assertions.assertThat(members.get(1).getSendStatus())
                .isEqualTo(HistoricalGroupMemberSendStatus.NOT_APPLICABLE.code());
    }

    @Test
    void administratorOperationNarrowsToTheExecutionOwnerForTemplateAndAccountReads() {
        DataScopeContext.clear();
        DataScopeContext.open(DataScope.all(99L));
        when(executionMapper.selectByTenantAndIdForScope(
                eq(TENANT_ID), eq(EXECUTION_ID), eq(DataScope.all(99L))))
                .thenReturn(execution);
        members.add(member(11L, "8613900000011", true));
        when(historicalGroupService.getHistoricalGroupDetail(201L, "120363target@g.us"))
                .thenReturn(detail(true, "https://chat.whatsapp.com/fresh"));
        when(messageCompositionService.compose(TEMPLATE_ID)).thenAnswer(invocation -> {
            org.assertj.core.api.Assertions.assertThat(
                    DataScopeContext.requireCurrent().actorUserId()).isEqualTo(11L);
            org.assertj.core.api.Assertions.assertThat(
                    DataScopeContext.requireCurrent().isSelf()).isTrue();
            return textMessage();
        });
        when(executionMapper.claimMarketingIfNotStarted(
                eq(EXECUTION_ID), anyInt(), anyInt(), eq(TEMPLATE_ID), anyLong()))
                .thenReturn(1);
        when(accountLookupService.findActiveProtocolRefsByPhones(List.of("8613900000011")))
                .thenAnswer(invocation -> {
                    org.assertj.core.api.Assertions.assertThat(
                            DataScopeContext.requireCurrent().actorUserId()).isEqualTo(11L);
                    return Map.of();
                });
        when(memberMapper.markSendFailedIfPending(any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> markFailed(invocation.getArgument(0)));

        service.send(EXECUTION_ID, new HistoricalGroupMarketingSendDTO(TEMPLATE_ID));

        org.assertj.core.api.Assertions.assertThat(DataScopeContext.requireCurrent())
                .isEqualTo(DataScope.all(99L));
    }

    @Test
    void rejectsUnavailableLinkBeforeClaimAndEnqueuesNothing() {
        members.add(member(11L, "8613900000011", true));
        when(historicalGroupService.getHistoricalGroupDetail(201L, "120363target@g.us"))
                .thenReturn(detail(false, null));

        assertThatThrownBy(() -> service.send(
                EXECUTION_ID, new HistoricalGroupMarketingSendDTO(TEMPLATE_ID)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("邀请链接");

        verify(executionMapper, never()).claimMarketingIfNotStarted(
                anyLong(), anyInt(), anyInt(), anyLong(), anyLong());
        verify(messageCompositionService, never()).compose(anyLong());
        verify(messageSendPort, never()).enqueue(any());
    }

    @Test
    void recordsMissingAccountAndEnqueueExceptionThenContinuesLaterMarketingMembers() {
        members.add(member(11L, "8613900000011", true));
        members.add(member(13L, "8613900000013", true));
        members.add(member(15L, "8613900000015", true));
        prepareGateAndClaim();
        when(accountLookupService.findActiveProtocolRefsByPhones(
                List.of("8613900000011", "8613900000013", "8613900000015")))
                .thenReturn(new LinkedHashMap<>(Map.of(
                        "8613900000013", account(113L, "web-13", "8613900000013"),
                        "8613900000015", account(115L, "web-15", "8613900000015"))));
        when(memberMapper.markSendFailedIfPending(any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> markFailed(invocation.getArgument(0)));
        when(memberMapper.markSendSendingIfPending(anyLong(), anyInt(), anyInt(), any(), anyLong()))
                .thenAnswer(invocation -> markSending(invocation.getArgument(0), invocation.getArgument(3)));
        when(memberMapper.markSendFailedByCommandId(any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> markFailed(invocation.getArgument(0)));
        when(messageSendPort.enqueue(any()))
                .thenThrow(new IllegalStateException("outbox unavailable"))
                .thenAnswer(invocation -> accepted(invocation.getArgument(0)));

        service.send(EXECUTION_ID, new HistoricalGroupMarketingSendDTO(TEMPLATE_ID));

        verify(messageSendPort, times(2)).enqueue(any());
        org.assertj.core.api.Assertions.assertThat(members)
                .extracting(HistoricalGroupPullMember::getSendStatus)
                .containsExactly(
                        HistoricalGroupMemberSendStatus.FAILED.code(),
                        HistoricalGroupMemberSendStatus.FAILED.code(),
                        HistoricalGroupMemberSendStatus.SENDING.code());
        org.assertj.core.api.Assertions.assertThat(members.get(0).getSendErrorCode())
                .isEqualTo("MARKETING_ACCOUNT_UNAVAILABLE");
        org.assertj.core.api.Assertions.assertThat(members.get(1).getSendErrorCode())
                .isEqualTo("MESSAGE_ENQUEUE_FAILED");
    }

    @Test
    void recordsAndroidAsUnsupportedWithoutEnqueueingAnUncorrelatedCommand() {
        members.add(member(11L, "8613900000011", true));
        prepareGateAndClaim();
        when(accountLookupService.findActiveProtocolRefsByPhones(List.of("8613900000011")))
                .thenReturn(Map.of("8613900000011", new ProtocolAccountRef(
                        111L, ProtocolBackend.ANDROID, "android-11", "8613900000011")));
        when(memberMapper.markSendFailedIfPending(any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> markFailed(invocation.getArgument(0)));

        service.send(EXECUTION_ID, new HistoricalGroupMarketingSendDTO(TEMPLATE_ID));

        verify(messageSendPort, never()).enqueue(any());
        org.assertj.core.api.Assertions.assertThat(members.get(0).getSendStatus())
                .isEqualTo(HistoricalGroupMemberSendStatus.FAILED.code());
        org.assertj.core.api.Assertions.assertThat(members.get(0).getSendErrorCode())
                .isEqualTo("MARKETING_BACKEND_UNSUPPORTED");
        verify(executionMapper).finishMarketingIfSending(
                any(), eq(HistoricalGroupMarketingStatus.SENDING.code()));
    }

    @Test
    void duplicateOrConcurrentRequestThatLosesClaimNeverEnqueuesAgain() {
        members.add(member(11L, "8613900000011", true));
        when(historicalGroupService.getHistoricalGroupDetail(201L, "120363target@g.us"))
                .thenReturn(detail(true, "https://chat.whatsapp.com/fresh"));
        when(messageCompositionService.compose(TEMPLATE_ID)).thenReturn(textMessage());
        when(executionMapper.claimMarketingIfNotStarted(
                eq(EXECUTION_ID),
                eq(HistoricalGroupMarketingStatus.NOT_STARTED.code()),
                eq(HistoricalGroupMarketingStatus.SENDING.code()),
                eq(TEMPLATE_ID),
                anyLong())).thenReturn(0);

        service.send(EXECUTION_ID, new HistoricalGroupMarketingSendDTO(TEMPLATE_ID));

        verify(accountLookupService, never()).findActiveProtocolRefsByPhones(any());
        verify(messageSendPort, never()).enqueue(any());
    }

    @Test
    void doesNotFinalizeWhileAnyMarketingMemberRemainsPending() {
        members.add(member(11L, "8613900000011", true));
        prepareGateAndClaim();
        when(accountLookupService.findActiveProtocolRefsByPhones(List.of("8613900000011")))
                .thenReturn(Map.of("8613900000011", account(111L, "web-11", "8613900000011")));
        when(memberMapper.markSendSendingIfPending(anyLong(), anyInt(), anyInt(), any(), anyLong()))
                .thenReturn(0);

        service.send(EXECUTION_ID, new HistoricalGroupMarketingSendDTO(TEMPLATE_ID));

        verify(messageSendPort, never()).enqueue(any());
        verify(executionMapper, never()).finishMarketingIfSending(any(), anyInt());
    }

    @Test
    void finalizesFailedImmediatelyWhenEveryMarketingMemberFailsLocally() {
        members.add(member(11L, "8613900000011", true));
        members.add(member(13L, "8613900000013", true));
        prepareGateAndClaim();
        when(accountLookupService.findActiveProtocolRefsByPhones(
                List.of("8613900000011", "8613900000013"))).thenReturn(Map.of());
        when(memberMapper.markSendFailedIfPending(any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> markFailed(invocation.getArgument(0)));

        service.send(EXECUTION_ID, new HistoricalGroupMarketingSendDTO(TEMPLATE_ID));

        ArgumentCaptor<HistoricalGroupPullExecution> terminal =
                ArgumentCaptor.forClass(HistoricalGroupPullExecution.class);
        verify(executionMapper).finishMarketingIfSending(
                terminal.capture(), eq(HistoricalGroupMarketingStatus.SENDING.code()));
        org.assertj.core.api.Assertions.assertThat(terminal.getValue().getMarketingStatus())
                .isEqualTo(HistoricalGroupMarketingStatus.FAILED.code());
        org.assertj.core.api.Assertions.assertThat(terminal.getValue().getSendFailureCount()).isEqualTo(2);
        verify(messageSendPort, never()).enqueue(any());
    }

    private void prepareGateAndClaim() {
        when(historicalGroupService.getHistoricalGroupDetail(201L, "120363target@g.us"))
                .thenReturn(detail(true, "https://chat.whatsapp.com/fresh"));
        when(messageCompositionService.compose(TEMPLATE_ID)).thenReturn(textMessage());
        when(executionMapper.claimMarketingIfNotStarted(
                eq(EXECUTION_ID),
                eq(HistoricalGroupMarketingStatus.NOT_STARTED.code()),
                eq(HistoricalGroupMarketingStatus.SENDING.code()),
                eq(TEMPLATE_ID),
                anyLong())).thenReturn(1);
    }

    private int markSending(Long memberId, String commandId) {
        HistoricalGroupPullMember member = member(memberId);
        member.setSendStatus(HistoricalGroupMemberSendStatus.SENDING.code());
        member.setSendCommandId(commandId);
        return 1;
    }

    private int markFailed(HistoricalGroupPullMember update) {
        HistoricalGroupPullMember member = member(update.getId());
        member.setSendStatus(HistoricalGroupMemberSendStatus.FAILED.code());
        member.setSendErrorCode(update.getSendErrorCode());
        member.setSendErrorMessage(update.getSendErrorMessage());
        return 1;
    }

    private HistoricalGroupPullMember member(Long id) {
        return members.stream().filter(member -> id.equals(member.getId())).findFirst().orElseThrow();
    }

    private static MessageSendEnqueueResult accepted(List<MessageSendCommand> commands) {
        return new MessageSendEnqueueResult(commands.stream()
                .map(command -> MessageSendEnqueueItem.accepted(command.commandId()))
                .toList());
    }

    private static HistoricalGroupPullExecution execution() {
        HistoricalGroupPullExecution row = new HistoricalGroupPullExecution();
        row.setId(EXECUTION_ID);
        row.setTenantId(TENANT_ID);
        row.setOwnerUserId(11L);
        row.setCreatedBy(11L);
        row.setOperationAccountId(101L);
        row.setSourceAccountGroupId(201L);
        row.setGroupJid("120363target@g.us");
        row.setPullStatus(HistoricalGroupPullStatus.SUCCESS.code());
        row.setMarketingStatus(HistoricalGroupMarketingStatus.NOT_STARTED.code());
        return row;
    }

    private static HistoricalGroupPullMember member(Long id, String phone, boolean marketing) {
        HistoricalGroupPullMember row = new HistoricalGroupPullMember();
        row.setId(id);
        row.setTenantId(TENANT_ID);
        row.setExecutionId(EXECUTION_ID);
        row.setPhone(phone);
        row.setMaterialType(marketing
                ? HistoricalGroupMaterialType.MARKETING.code()
                : HistoricalGroupMaterialType.NORMAL.code());
        row.setSendStatus(marketing
                ? HistoricalGroupMemberSendStatus.PENDING.code()
                : HistoricalGroupMemberSendStatus.NOT_APPLICABLE.code());
        return row;
    }

    private static ProtocolAccountRef account(Long id, String protocolAccountId, String phone) {
        return new ProtocolAccountRef(id, ProtocolBackend.WEB, protocolAccountId, phone);
    }

    private static MarketingComposedMessageVO textMessage() {
        return new MarketingComposedMessageVO("TEXT", "完整模板", null, null, null, null, true);
    }

    private static HistoricalGroupDetailVO detail(boolean linkAvailable, String inviteUrl) {
        return new HistoricalGroupDetailVO(
                101L, "120363target@g.us", "目标群", null, null, null, null,
                null, null, inviteUrl, linkAvailable, false, null, null, null, List.of());
    }
}
