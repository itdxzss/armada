package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.model.vo.AccountGroupMembershipLookup;
import com.armada.group.model.vo.AccountGroupMessageSendPermissionSnapshot;
import com.armada.group.service.AccountGroupMembershipStatusService;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.marketing.model.enums.MarketingTargetScope;
import com.armada.marketing.model.vo.MarketingAccountOccupancyOwnerRow;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import com.armada.marketing.scheduler.MarketingRoundSchedulerProperties;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.marketing.service.impl.MarketingNewGroupImmediateSendServiceImpl;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

class MarketingNewGroupImmediateSendServiceImplTest {

    private final MarketingTaskMapper mapper = mock(MarketingTaskMapper.class);
    private final MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
    private final MarketingTemplateFileMapper fileMapper = mock(MarketingTemplateFileMapper.class);
    private final MessageSendPort messagePort = mock(MessageSendPort.class);
    private final MarketingAccountOccupancyService occupancyService = mock(MarketingAccountOccupancyService.class);
    private final AccountGroupMembershipStatusService membershipStatusService =
            mock(AccountGroupMembershipStatusService.class);
    private final MarketingMessageCommandFactory messageFactory = new MarketingMessageCommandFactory(
            templateMapper,
            fileMapper,
            new MarketingMessageComposer());
    private final MarketingRoundSchedulerProperties schedulerProperties = schedulerProperties();
    private final MarketingNewGroupImmediateSendServiceImpl service =
            new MarketingNewGroupImmediateSendServiceImpl(
                    mapper, messageFactory, messagePort, schedulerProperties,
                    occupancyService, membershipStatusService);

    @BeforeEach
    void setUp() {
        when(templateMapper.selectById(77L)).thenReturn(textTemplate());
        when(mapper.markAttemptOutboxAccepted(anyLong(), any(), anyLong())).thenReturn(1);
        when(membershipStatusService.findCurrentMessageSendPermissions(any())).thenReturn(List.of());
    }

    @Test
    void enqueueNewGroups_createsRoundZeroAttemptsAndSpacedCommands() {
        stubOwnedSendingTask();
        assignAttemptIds(9_000L);
        when(messagePort.enqueue(any())).thenAnswer(invocation -> acceptAll(invocation.getArgument(0)));

        service.enqueueNewGroups(
                5_001L,
                List.of(
                        new MarketingNewGroupDTO(301L, "120363a@g.us", "群A"),
                        new MarketingNewGroupDTO(302L, "120363b@g.us", "群B")),
                2_000L);

        ArgumentCaptor<MarketingTaskSendAttempt> attemptCaptor =
                ArgumentCaptor.forClass(MarketingTaskSendAttempt.class);
        verify(mapper, times(2)).insertSendAttempt(attemptCaptor.capture());
        assertThat(attemptCaptor.getAllValues())
                .extracting(MarketingTaskSendAttempt::getRoundNo)
                .containsOnly(0L);
        assertThat(attemptCaptor.getAllValues())
                .extracting(MarketingTaskSendAttempt::getAttemptNo)
                .containsOnly(1);
        assertThat(attemptCaptor.getAllValues())
                .extracting(MarketingTaskSendAttempt::getRetry)
                .containsOnly(false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> commandCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(messagePort).enqueue(commandCaptor.capture());
        assertThat(commandCaptor.getValue())
                .extracting(MessageSendCommand::notBeforeAt)
                .containsExactly(2_000L, 2_750L);
    }

    @Test
    void enqueueNewGroups_delayEnabledCreatesWaitingAttemptsWithoutOutbox() {
        MarketingTask task = sendingTask();
        task.setNewGroupDelayEnabled(true);
        task.setNewGroupDelayValue(30);
        task.setNewGroupDelayUnit(1);
        when(mapper.selectOwnedSendingDynamicTarget(5_001L, 2_000L)).thenReturn(dynamicTarget());
        when(mapper.selectTaskByIdForUpdate(42L)).thenReturn(task);
        assignAttemptIds(9_000L);

        service.enqueueNewGroups(
                5_001L,
                List.of(new MarketingNewGroupDTO(301L, "120363a@g.us", "群A")),
                2_000L);

        ArgumentCaptor<MarketingTaskSendAttempt> captor =
                ArgumentCaptor.forClass(MarketingTaskSendAttempt.class);
        verify(mapper).insertSendAttempt(captor.capture());
        MarketingTaskSendAttempt waiting = captor.getValue();
        assertThat(waiting.getStatus()).isEqualTo(MarketingSendAttemptStatus.WAITING.code());
        assertThat(waiting.getCommandId()).isNull();
        assertThat(waiting.getDetectedAt()).isEqualTo(2_000L);
        assertThat(waiting.getScheduledSendAt()).isEqualTo(1_802_000L);
        assertThat(waiting.getSubmittedAt()).isNull();
        assertThat(waiting.getAttemptedAt()).isEqualTo(2_000L);
        InOrder lockOrder = inOrder(mapper);
        lockOrder.verify(mapper).selectOwnedSendingDynamicTarget(5_001L, 2_000L);
        lockOrder.verify(mapper).selectTaskByIdForUpdate(42L);
        lockOrder.verify(mapper).insertSendAttempt(any());
        verify(messagePort, never()).enqueue(any());
    }

    @Test
    void enqueueNewGroups_pausedDelayEnabledCreatesWaitingAttemptsWithoutOutbox() {
        MarketingTask task = sendingTask();
        task.setStatus(5);
        task.setNewGroupDelayEnabled(true);
        task.setNewGroupDelayValue(30);
        task.setNewGroupDelayUnit(1);
        when(mapper.selectOwnedSendingDynamicTarget(5_001L, 2_000L)).thenReturn(dynamicTarget());
        when(mapper.selectTaskByIdForUpdate(42L)).thenReturn(task);
        assignAttemptIds(9_000L);

        service.enqueueNewGroups(
                5_001L,
                List.of(new MarketingNewGroupDTO(301L, "120363a@g.us", "群A")),
                2_000L);

        ArgumentCaptor<MarketingTaskSendAttempt> captor =
                ArgumentCaptor.forClass(MarketingTaskSendAttempt.class);
        verify(mapper).insertSendAttempt(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(MarketingSendAttemptStatus.WAITING.code());
        assertThat(captor.getValue().getScheduledSendAt()).isEqualTo(1_802_000L);
        verify(messagePort, never()).enqueue(any());
    }

    @Test
    void enqueueNewGroups_pausedDelayDisabledDoesNothing() {
        MarketingTask task = sendingTask();
        task.setStatus(5);
        task.setNewGroupDelayEnabled(false);
        when(mapper.selectOwnedSendingDynamicTarget(5_001L, 2_000L)).thenReturn(dynamicTarget());
        when(mapper.selectTaskByIdForUpdate(42L)).thenReturn(task);

        service.enqueueNewGroups(
                5_001L,
                List.of(new MarketingNewGroupDTO(301L, "120363a@g.us", "群A")),
                2_000L);

        verify(mapper, never()).insertSendAttempt(any());
        verify(messagePort, never()).enqueue(any());
    }

    @Test
    void enqueueDelayedNewGroups_delayEnabledCreatesWaitingWithoutOutbox() {
        MarketingTask task = sendingTask();
        task.setNewGroupDelayEnabled(true);
        task.setNewGroupDelayValue(30);
        task.setNewGroupDelayUnit(1);
        when(mapper.selectOwnedSendingDynamicTarget(5_001L, 2_000L)).thenReturn(dynamicTarget());
        when(mapper.selectTaskById(42L)).thenReturn(task);
        when(mapper.selectTaskByIdForUpdate(42L)).thenReturn(task);
        assignAttemptIds(9_000L);

        service.enqueueDelayedNewGroups(
                5_001L,
                List.of(new MarketingNewGroupDTO(301L, "120363a@g.us", null)),
                2_000L);

        ArgumentCaptor<MarketingTaskSendAttempt> captor =
                ArgumentCaptor.forClass(MarketingTaskSendAttempt.class);
        verify(mapper).insertSendAttempt(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(MarketingSendAttemptStatus.WAITING.code());
        assertThat(captor.getValue().getScheduledSendAt()).isEqualTo(1_802_000L);
        verify(messagePort, never()).enqueue(any());
    }

    @Test
    void enqueueDelayedNewGroups_delayDisabledDoesNotChangeImmediateBehavior() {
        MarketingTask task = sendingTask();
        task.setNewGroupDelayEnabled(false);
        when(mapper.selectOwnedSendingDynamicTarget(5_001L, 2_000L)).thenReturn(dynamicTarget());
        when(mapper.selectTaskById(42L)).thenReturn(task);

        service.enqueueDelayedNewGroups(
                5_001L,
                List.of(new MarketingNewGroupDTO(301L, "120363a@g.us", null)),
                2_000L);

        verify(mapper, never()).insertSendAttempt(any());
        verify(mapper, never()).selectTaskByIdForUpdate(anyLong());
        verify(messagePort, never()).enqueue(any());
    }

    @Test
    void enqueueDelayedNewGroups_missingTaskDoesNotLockOrWrite() {
        when(mapper.selectOwnedSendingDynamicTarget(5_001L, 2_000L)).thenReturn(dynamicTarget());
        when(mapper.selectTaskById(42L)).thenReturn(null);

        service.enqueueDelayedNewGroups(
                5_001L,
                List.of(new MarketingNewGroupDTO(null, "120363a@g.us", null)),
                2_000L);

        verify(mapper, never()).insertSendAttempt(any());
        verify(mapper, never()).selectTaskByIdForUpdate(anyLong());
        verify(messagePort, never()).enqueue(any());
    }

    @Test
    void submitDueWaitingAttempts_validAttemptUsesSharedOutboxSubmission() {
        MarketingTaskSendAttempt waiting = waitingAttempt();
        MarketingTask task = sendingTask();
        task.setNewGroupDelayEnabled(true);
        task.setNextRoundAt(3_000L);
        task.setMarketingTemplateId(88L);
        MarketingTemplate currentTemplate = textTemplate();
        currentTemplate.setId(88L);
        currentTemplate.setContent("到期时任务当前模板消息");
        when(templateMapper.selectById(88L)).thenReturn(currentTemplate);
        MarketingTaskTarget target = dynamicTarget();
        MarketingAccountOccupancyOwnerRow owner = new MarketingAccountOccupancyOwnerRow();
        owner.setAccountId(target.getAccountId());
        owner.setMarketingTaskId(task.getId());
        when(mapper.selectTaskByIdForUpdate(42L)).thenReturn(task);
        when(mapper.selectWaitingAttemptsForUpdate(1L, 42L, List.of(9_001L), 3_000L))
                .thenReturn(List.of(waiting));
        when(mapper.selectTargetById(501L)).thenReturn(target);
        when(occupancyService.loadActiveOwners(List.of(5_001L)))
                .thenReturn(Map.of(5_001L, owner));
        when(mapper.selectAccountTargetCandidate(eq(8L), eq(5_001L), any()))
                .thenReturn(accountCandidate());
        when(mapper.selectCurrentTargetGroup(5_001L, 301L)).thenReturn(groupCandidate());
        when(mapper.countOrdinarySubmittedOrSuccessfulAttempts(501L, "120363a@g.us"))
                .thenReturn(0);
        when(mapper.markWaitingAttemptSubmitted(eq(9_001L), any(), eq(3_000L))).thenReturn(1);
        when(messagePort.enqueue(any())).thenAnswer(invocation -> acceptAll(invocation.getArgument(0)));

        service.submitDueWaitingAttempts(1L, 42L, List.of(9_001L), 3_000L);

        InOrder lockOrder = inOrder(mapper);
        lockOrder.verify(mapper).selectTaskByIdForUpdate(42L);
        lockOrder.verify(mapper).selectWaitingAttemptsForUpdate(1L, 42L, List.of(9_001L), 3_000L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> commandCaptor = ArgumentCaptor.forClass(List.class);
        verify(messagePort).enqueue(commandCaptor.capture());
        assertThat(commandCaptor.getValue()).hasSize(1);
        MessageSendCommand command = commandCaptor.getValue().get(0);
        assertThat(command.target().groupJid()).isEqualTo("120363a@g.us");
        assertThat(command.payload().content().text()).isEqualTo("到期时任务当前模板消息");
        assertThat(command.correlation().marketing().attemptId()).isEqualTo(9_001L);
        verify(templateMapper).selectById(88L);
        verify(mapper).markWaitingAttemptSubmitted(eq(9_001L), any(), eq(3_000L));
        verify(mapper, never()).markWaitingAttemptSkipped(any(), any(), any(), anyLong());
    }

    @Test
    void submitDueWaitingAttempts_knownAnnounceOnlyNonAdminRecordsNoPermissionForThisAttempt() {
        MarketingTaskSendAttempt waiting = waitingAttempt();
        MarketingTask task = sendingTask();
        task.setNewGroupDelayEnabled(true);
        MarketingTaskTarget target = dynamicTarget();
        MarketingAccountOccupancyOwnerRow owner = new MarketingAccountOccupancyOwnerRow();
        owner.setAccountId(target.getAccountId());
        owner.setMarketingTaskId(task.getId());
        when(mapper.selectTaskByIdForUpdate(42L)).thenReturn(task);
        when(mapper.selectWaitingAttemptsForUpdate(1L, 42L, List.of(9_001L), 3_000L))
                .thenReturn(List.of(waiting));
        when(mapper.selectTargetById(501L)).thenReturn(target);
        when(occupancyService.loadActiveOwners(List.of(5_001L)))
                .thenReturn(Map.of(5_001L, owner));
        when(mapper.selectAccountTargetCandidate(eq(8L), eq(5_001L), any()))
                .thenReturn(accountCandidate());
        when(mapper.selectCurrentTargetGroup(5_001L, 301L)).thenReturn(groupCandidate());
        when(mapper.countOrdinarySubmittedOrSuccessfulAttempts(501L, "120363a@g.us"))
                .thenReturn(0);
        when(membershipStatusService.findCurrentMessageSendPermissions(List.of(
                new AccountGroupMembershipLookup(5_001L, "120363a@g.us"))))
                .thenReturn(List.of(new AccountGroupMessageSendPermissionSnapshot(
                        5_001L,
                        "120363a@g.us",
                        Boolean.FALSE)));
        when(mapper.markWaitingAttemptFailed(
                9_001L, "NO_PERMISSION", "当前账号没有发言权限", 3_000L)).thenReturn(1);

        service.submitDueWaitingAttempts(1L, 42L, List.of(9_001L), 3_000L);

        verify(mapper).markWaitingAttemptFailed(
                9_001L, "NO_PERMISSION", "当前账号没有发言权限", 3_000L);
        verify(mapper).markTargetFailedFromAttempt(
                501L, 9_001L, "NO_PERMISSION", "当前账号没有发言权限", 3_000L);
        verify(mapper).incrementTaskSendCounters(42L, 0, 1, 3_000L);
        verify(messagePort, never()).enqueue(any());
        verify(templateMapper, never()).selectById(anyLong());
    }

    @Test
    void submitDueWaitingAttempts_allMembersAllowedStillUsesSharedOutboxSubmission() {
        MarketingTaskSendAttempt waiting = waitingAttempt();
        MarketingTask task = sendingTask();
        task.setNewGroupDelayEnabled(true);
        MarketingTaskTarget target = dynamicTarget();
        MarketingAccountOccupancyOwnerRow owner = new MarketingAccountOccupancyOwnerRow();
        owner.setAccountId(target.getAccountId());
        owner.setMarketingTaskId(task.getId());
        when(mapper.selectTaskByIdForUpdate(42L)).thenReturn(task);
        when(mapper.selectWaitingAttemptsForUpdate(1L, 42L, List.of(9_001L), 3_000L))
                .thenReturn(List.of(waiting));
        when(mapper.selectTargetById(501L)).thenReturn(target);
        when(occupancyService.loadActiveOwners(List.of(5_001L)))
                .thenReturn(Map.of(5_001L, owner));
        when(mapper.selectAccountTargetCandidate(eq(8L), eq(5_001L), any()))
                .thenReturn(accountCandidate());
        when(mapper.selectCurrentTargetGroup(5_001L, 301L)).thenReturn(groupCandidate());
        when(mapper.countOrdinarySubmittedOrSuccessfulAttempts(501L, "120363a@g.us"))
                .thenReturn(0);
        when(membershipStatusService.findCurrentMessageSendPermissions(List.of(
                new AccountGroupMembershipLookup(5_001L, "120363a@g.us"))))
                .thenReturn(List.of(new AccountGroupMessageSendPermissionSnapshot(
                        5_001L,
                        "120363a@g.us",
                        Boolean.TRUE)));
        when(mapper.markWaitingAttemptSubmitted(eq(9_001L), any(), eq(3_000L))).thenReturn(1);
        when(messagePort.enqueue(any())).thenAnswer(invocation -> acceptAll(invocation.getArgument(0)));

        service.submitDueWaitingAttempts(1L, 42L, List.of(9_001L), 3_000L);

        verify(messagePort).enqueue(any());
        verify(mapper).markWaitingAttemptSubmitted(eq(9_001L), any(), eq(3_000L));
        verify(mapper, never()).markWaitingAttemptFailed(anyLong(), any(), any(), anyLong());
    }

    @Test
    void submitDueWaitingAttempts_realtimeAttemptWithoutGroupLinkUsesCurrentJidFacts() {
        MarketingTaskSendAttempt waiting = waitingAttempt();
        waiting.setGroupLinkId(null);
        MarketingTask task = sendingTask();
        task.setNewGroupDelayEnabled(true);
        MarketingTaskTarget target = dynamicTarget();
        MarketingAccountOccupancyOwnerRow owner = new MarketingAccountOccupancyOwnerRow();
        owner.setAccountId(target.getAccountId());
        owner.setMarketingTaskId(task.getId());
        when(mapper.selectTaskByIdForUpdate(42L)).thenReturn(task);
        when(mapper.selectWaitingAttemptsForUpdate(1L, 42L, List.of(9_001L), 3_000L))
                .thenReturn(List.of(waiting));
        when(mapper.selectTargetById(501L)).thenReturn(target);
        when(occupancyService.loadActiveOwners(List.of(5_001L)))
                .thenReturn(Map.of(5_001L, owner));
        when(mapper.selectAccountTargetCandidate(eq(8L), eq(5_001L), any()))
                .thenReturn(accountCandidate());
        MarketingTargetCandidateRow realtimeGroup = groupCandidate();
        realtimeGroup.setGroupLinkId(null);
        when(mapper.selectCurrentTargetGroupByJid(5_001L, "120363a@g.us"))
                .thenReturn(realtimeGroup);
        when(mapper.countOrdinarySubmittedOrSuccessfulAttempts(501L, "120363a@g.us"))
                .thenReturn(0);
        when(mapper.markWaitingAttemptSubmitted(eq(9_001L), any(), eq(3_000L))).thenReturn(1);
        when(messagePort.enqueue(any())).thenAnswer(invocation -> acceptAll(invocation.getArgument(0)));

        service.submitDueWaitingAttempts(1L, 42L, List.of(9_001L), 3_000L);

        verify(mapper).selectCurrentTargetGroupByJid(5_001L, "120363a@g.us");
        verify(mapper, never()).selectCurrentTargetGroup(anyLong(), anyLong());
        verify(messagePort).enqueue(any());
    }

    @Test
    void submitDueWaitingAttempts_pausedTaskKeepsWaitingWithoutOutbox() {
        MarketingTask task = sendingTask();
        task.setStatus(5);
        when(mapper.selectTaskByIdForUpdate(42L)).thenReturn(task);
        when(mapper.selectWaitingAttemptsForUpdate(1L, 42L, List.of(9_001L), 3_000L))
                .thenReturn(List.of(waitingAttempt()));

        service.submitDueWaitingAttempts(1L, 42L, List.of(9_001L), 3_000L);

        verify(messagePort, never()).enqueue(any());
        verify(mapper, never()).markWaitingAttemptSkipped(any(), any(), any(), anyLong());
        verify(mapper, never()).markWaitingAttemptSubmitted(anyLong(), any(), anyLong());
    }

    @Test
    void submitDueWaitingAttempts_ordinaryRoundCoveredMarksSkippedWithoutOutbox() {
        MarketingTask task = sendingTask();
        task.setNewGroupDelayEnabled(true);
        when(mapper.selectTaskByIdForUpdate(42L)).thenReturn(task);
        when(mapper.selectWaitingAttemptsForUpdate(1L, 42L, List.of(9_001L), 3_000L))
                .thenReturn(List.of(waitingAttempt()));
        when(mapper.selectTargetById(501L)).thenReturn(dynamicTarget());
        when(mapper.countOrdinarySubmittedOrSuccessfulAttempts(501L, "120363a@g.us"))
                .thenReturn(1);
        when(mapper.markWaitingAttemptSkipped(
                9_001L, "ORDINARY_ROUND_COVERED", "已被普通轮次覆盖", 3_000L)).thenReturn(1);

        service.submitDueWaitingAttempts(1L, 42L, List.of(9_001L), 3_000L);

        verify(mapper).markWaitingAttemptSkipped(
                9_001L, "ORDINARY_ROUND_COVERED", "已被普通轮次覆盖", 3_000L);
        verify(messagePort, never()).enqueue(any());
    }

    @Test
    void enqueueNewGroups_withoutOwnedSendingDynamicTargetDoesNothing() {
        when(mapper.selectOwnedSendingDynamicTarget(5_001L, 2_000L)).thenReturn(null);

        service.enqueueNewGroups(
                5_001L,
                List.of(new MarketingNewGroupDTO(301L, "120363a@g.us", "群A")),
                2_000L);

        verify(mapper, never()).insertSendAttempt(any());
        verify(messagePort, never()).enqueue(any());
    }

    @Test
    void enqueueNewGroups_duplicateInitialAttemptDoesNotWriteAnotherCommand() {
        stubOwnedSendingTask();
        when(mapper.insertSendAttempt(any()))
                .thenThrow(new DuplicateKeyException("uq_marketing_task_attempt_group_round"));

        service.enqueueNewGroups(
                5_001L,
                List.of(new MarketingNewGroupDTO(301L, "120363a@g.us", "群A")),
                2_000L);

        verify(messagePort, never()).enqueue(any());
    }

    @Test
    void enqueueNewGroups_localRejectionFailsOnlyRejectedAttempt() {
        stubOwnedSendingTask();
        assignAttemptIds(9_000L);
        when(messagePort.enqueue(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MessageSendCommand> commands = invocation.getArgument(0, List.class);
            return new MessageSendEnqueueResult(List.of(
                    MessageSendEnqueueItem.rejected(
                            commands.get(0).commandId(), "LOCAL_REJECTED", "账号暂不可用"),
                    MessageSendEnqueueItem.accepted(commands.get(1).commandId())));
        });
        when(mapper.markAttemptFailed(any())).thenReturn(1);

        service.enqueueNewGroups(
                5_001L,
                List.of(
                        new MarketingNewGroupDTO(301L, "120363a@g.us", "群A"),
                        new MarketingNewGroupDTO(302L, "120363b@g.us", "群B")),
                2_000L);

        verify(mapper).markAttemptFailed(any());
        verify(mapper).markTargetFailedFromAttempt(
                eq(501L), eq(9_001L), eq("LOCAL_REJECTED"), eq("账号暂不可用"), eq(2_000L));
        verify(mapper).incrementTaskSendCounters(42L, 0, 1, 2_000L);
    }

    @Test
    void enqueueNewGroups_moreThanOutboxLimitUsesOrderedBatches() {
        stubOwnedSendingTask();
        assignAttemptIds(9_000L);
        when(messagePort.enqueue(any())).thenAnswer(invocation -> acceptAll(invocation.getArgument(0)));
        List<MarketingNewGroupDTO> groups = java.util.stream.LongStream.rangeClosed(1, 501)
                .mapToObj(index -> new MarketingNewGroupDTO(
                        300L + index,
                        "120363batch" + index + "@g.us",
                        "群" + index))
                .toList();

        service.enqueueNewGroups(5_001L, groups, 2_000L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> commandCaptor = ArgumentCaptor.forClass(List.class);
        verify(messagePort, times(2)).enqueue(commandCaptor.capture());
        assertThat(commandCaptor.getAllValues()).extracting(List::size).containsExactly(500, 1);
        assertThat(commandCaptor.getAllValues().stream().flatMap(List::stream).toList())
                .extracting(MessageSendCommand::notBeforeAt)
                .startsWith(2_000L, 2_750L)
                .endsWith(377_000L);
    }

    private void stubOwnedSendingTask() {
        when(mapper.selectOwnedSendingDynamicTarget(5_001L, 2_000L)).thenReturn(dynamicTarget());
        when(mapper.selectTaskByIdForUpdate(42L)).thenReturn(sendingTask());
    }

    private void assignAttemptIds(long startingId) {
        long[] nextId = {startingId};
        doAnswer(invocation -> {
            MarketingTaskSendAttempt attempt = invocation.getArgument(0);
            attempt.setId(++nextId[0]);
            return 1;
        }).when(mapper).insertSendAttempt(any());
    }

    private static MessageSendEnqueueResult acceptAll(List<MessageSendCommand> commands) {
        return new MessageSendEnqueueResult(commands.stream()
                .map(command -> MessageSendEnqueueItem.accepted(command.commandId()))
                .toList());
    }

    private static MarketingTask sendingTask() {
        MarketingTask task = new MarketingTask();
        task.setId(42L);
        task.setTenantId(1L);
        task.setBusinessType(1);
        task.setStatus(2);
        task.setMarketingTemplateId(77L);
        task.setAccountGroupId(8L);
        task.setAccountGroupSendIntervalMs(750);
        return task;
    }

    private static MarketingTaskSendAttempt waitingAttempt() {
        MarketingTaskSendAttempt attempt = new MarketingTaskSendAttempt();
        attempt.setId(9_001L);
        attempt.setTenantId(1L);
        attempt.setMarketingTaskId(42L);
        attempt.setTargetId(501L);
        attempt.setGroupLinkId(301L);
        attempt.setGroupJid("120363a@g.us");
        attempt.setGroupName("群A");
        attempt.setRoundNo(0L);
        attempt.setAttemptNo(1);
        attempt.setRetry(false);
        attempt.setStatus(MarketingSendAttemptStatus.WAITING.code());
        attempt.setDetectedAt(2_000L);
        attempt.setScheduledSendAt(2_500L);
        attempt.setAttemptedAt(2_000L);
        attempt.setCreatedAt(2_000L);
        return attempt;
    }

    private static MarketingTargetCandidateRow accountCandidate() {
        MarketingTargetCandidateRow row = new MarketingTargetCandidateRow();
        row.setAccountId(5_001L);
        return row;
    }

    private static MarketingTargetCandidateRow groupCandidate() {
        MarketingTargetCandidateRow row = accountCandidate();
        row.setGroupLinkId(301L);
        row.setGroupJid("120363a@g.us");
        row.setGroupName("群A");
        row.setMembershipStatus(1);
        return row;
    }

    private static MarketingTaskTarget dynamicTarget() {
        MarketingTaskTarget target = new MarketingTaskTarget();
        target.setId(501L);
        target.setTenantId(1L);
        target.setMarketingTaskId(42L);
        target.setAccountId(5_001L);
        target.setProtocolAccountId("acc_5001");
        target.setProtocolId("WEB");
        target.setProtocolWsPhone("923000001");
        target.setTargetScope(MarketingTargetScope.ACCOUNT_DYNAMIC.code());
        return target;
    }

    private static MarketingTemplate textTemplate() {
        MarketingTemplate template = new MarketingTemplate();
        template.setId(77L);
        template.setTemplateName("即时营销模板");
        template.setLinkMode(LinkMode.NORMAL.code());
        template.setContent("hello");
        template.setMentionAll(false);
        return template;
    }

    private static MarketingRoundSchedulerProperties schedulerProperties() {
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setOutboxBatchSize(500);
        properties.setImageOutboxBatchSize(200);
        return properties;
    }
}
