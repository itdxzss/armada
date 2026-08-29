package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.marketing.model.enums.MarketingTargetScope;
import com.armada.marketing.model.support.MarketingSendAttemptResult;
import com.armada.marketing.model.vo.MarketingAccountOccupancyOwnerRow;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.marketing.service.impl.MarketingImmediateRetryService;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MarketingImmediateRetryServiceTest {

    private final MarketingTaskMapper mapper = mock(MarketingTaskMapper.class);
    private final MarketingAccountOccupancyService occupancyService = mock(MarketingAccountOccupancyService.class);
    private final MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
    private final MarketingTemplateFileMapper fileMapper = mock(MarketingTemplateFileMapper.class);
    private final MessageSendPort messagePort = mock(MessageSendPort.class);
    private final MarketingMessageCommandFactory messageFactory = new MarketingMessageCommandFactory(
            templateMapper,
            fileMapper,
            new MarketingMessageComposer());
    private final MarketingImmediateRetryService service = new MarketingImmediateRetryService(
            mapper,
            occupancyService,
            messageFactory,
            messagePort);

    @BeforeEach
    void setUp() {
        when(templateMapper.selectById(77L)).thenReturn(textTemplate());
    }

    @Test
    void retryIfEligible_resubmitsSameAttemptWithNewCommand() {
        stubEligibleAttempt(1);
        when(mapper.resubmitImmediateAttempt(eq(9_001L), eq("cmd_first"), anyString(), eq(2_000L)))
                .thenReturn(1);
        when(messagePort.enqueue(any())).thenAnswer(invocation -> accept(invocation.getArgument(0)));

        assertThat(service.retryIfEligible(failedEvent(0L, "cmd_first"), 2_000L)).isTrue();

        ArgumentCaptor<String> commandIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(mapper).resubmitImmediateAttempt(
                eq(9_001L), eq("cmd_first"), commandIdCaptor.capture(), eq(2_000L));
        assertThat(commandIdCaptor.getValue()).startsWith("cmd_").isNotEqualTo("cmd_first");
        verify(mapper).incrementTargetRetryCount(501L, 9_001L, 2_000L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> commandCaptor = ArgumentCaptor.forClass(List.class);
        verify(messagePort).enqueue(commandCaptor.capture());
        assertThat(commandCaptor.getValue()).singleElement().satisfies(command -> {
            assertThat(command.commandId()).isEqualTo(commandIdCaptor.getValue());
            assertThat(command.correlation().marketing().roundNo()).isZero();
            assertThat(command.notBeforeAt()).isEqualTo(2_000L);
        });
    }

    @Test
    void retryIfEligible_normalRoundNeverRetries() {
        assertThat(service.retryIfEligible(failedEvent(3L, "cmd_first"), 2_000L)).isFalse();

        verifyNoInteractions(mapper, occupancyService, messagePort);
    }

    @Test
    void retryIfEligible_secondImmediateAttemptNeverRetriesAgain() {
        MarketingTaskSendAttempt attempt = submittedImmediateAttempt(2);
        attempt.setCommandId("cmd_retry");
        attempt.setRetry(true);
        when(mapper.selectSendAttemptById(9_001L)).thenReturn(attempt);

        assertThat(service.retryIfEligible(failedEvent(0L, "cmd_retry"), 2_000L)).isFalse();

        verify(mapper, never()).selectTaskById(any());
        verifyNoInteractions(occupancyService, messagePort);
    }

    @Test
    void retryIfEligible_localRejectionFinalizesRetryOnce() {
        stubEligibleAttempt(1);
        when(mapper.resubmitImmediateAttempt(eq(9_001L), eq("cmd_first"), anyString(), eq(2_000L)))
                .thenReturn(1);
        when(messagePort.enqueue(any())).thenAnswer(invocation -> reject(invocation.getArgument(0)));
        when(mapper.markAttemptFailed(any())).thenReturn(1);

        assertThat(service.retryIfEligible(failedEvent(0L, "cmd_first"), 2_000L)).isTrue();

        ArgumentCaptor<MarketingSendAttemptResult> resultCaptor =
                ArgumentCaptor.forClass(MarketingSendAttemptResult.class);
        verify(mapper).markAttemptFailed(resultCaptor.capture());
        assertThat(resultCaptor.getValue().commandId()).startsWith("cmd_").isNotEqualTo("cmd_first");
        assertThat(resultCaptor.getValue().reasonCode()).isEqualTo("LOCAL_REJECTED");
        verify(mapper).markTargetFailedFromAttempt(
                501L, 9_001L, "LOCAL_REJECTED", "账号暂不可用", 2_000L);
        verify(mapper).incrementTaskSendCounters(42L, 0, 1, 2_000L);
    }

    private void stubEligibleAttempt(int attemptNo) {
        when(mapper.selectSendAttemptById(9_001L)).thenReturn(submittedImmediateAttempt(attemptNo));
        when(mapper.selectTaskById(42L)).thenReturn(retryEnabledTask());
        when(mapper.selectTargetById(501L)).thenReturn(dynamicTarget());
        when(occupancyService.loadActiveOwners(List.of(5_001L)))
                .thenReturn(Map.of(5_001L, owner()));
        when(mapper.selectCurrentTargetGroup(5_001L, 301L)).thenReturn(currentGroup());
    }

    private static MessageSendEnqueueResult accept(List<MessageSendCommand> commands) {
        return new MessageSendEnqueueResult(List.of(
                MessageSendEnqueueItem.accepted(commands.get(0).commandId())));
    }

    private static MessageSendEnqueueResult reject(List<MessageSendCommand> commands) {
        return new MessageSendEnqueueResult(List.of(MessageSendEnqueueItem.rejected(
                commands.get(0).commandId(), "LOCAL_REJECTED", "账号暂不可用")));
    }

    private static MarketingTaskSendAttempt submittedImmediateAttempt(int attemptNo) {
        MarketingTaskSendAttempt attempt = new MarketingTaskSendAttempt();
        attempt.setId(9_001L);
        attempt.setTenantId(1L);
        attempt.setMarketingTaskId(42L);
        attempt.setTargetId(501L);
        attempt.setGroupLinkId(301L);
        attempt.setGroupJid("120363new@g.us");
        attempt.setGroupName("新群");
        attempt.setRoundNo(0L);
        attempt.setAttemptNo(attemptNo);
        attempt.setRetry(false);
        attempt.setCommandId("cmd_first");
        attempt.setStatus(MarketingSendAttemptStatus.SUBMITTED.code());
        return attempt;
    }

    private static MarketingTask retryEnabledTask() {
        MarketingTask task = new MarketingTask();
        task.setId(42L);
        task.setTenantId(1L);
        task.setStatus(2);
        task.setMarketingTemplateId(77L);
        task.setAccountGroupSendIntervalMs(750);
        task.setAutoRetryEnabled(true);
        task.setRetryLimit(1);
        task.setTaskStartAt(1_000L);
        task.setTaskEndAt(3_000L);
        return task;
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

    private static MarketingAccountOccupancyOwnerRow owner() {
        MarketingAccountOccupancyOwnerRow owner = new MarketingAccountOccupancyOwnerRow();
        owner.setAccountId(5_001L);
        owner.setMarketingTaskId(42L);
        return owner;
    }

    private static MarketingTargetCandidateRow currentGroup() {
        MarketingTargetCandidateRow group = new MarketingTargetCandidateRow();
        group.setAccountId(5_001L);
        group.setGroupLinkId(301L);
        group.setGroupJid("120363new@g.us");
        group.setGroupName("新群");
        return group;
    }

    private static MarketingTemplate textTemplate() {
        MarketingTemplate template = new MarketingTemplate();
        template.setId(77L);
        template.setLinkMode(LinkMode.NORMAL.code());
        template.setContent("hello");
        template.setMentionAll(false);
        return template;
    }

    private static ProtocolMessageSendResultReportedEvent failedEvent(Long roundNo, String commandId) {
        return new ProtocolMessageSendResultReportedEvent(
                "evt_retry",
                1L,
                42L,
                501L,
                9_001L,
                roundNo,
                "acc_5001",
                "120363new@g.us",
                commandId,
                false,
                null,
                "SEND_FAILED",
                "rate limited",
                2_000L,
                "worker-a",
                null,
                null,
                "marketing_task",
                "NORMAL",
                "GROUP_SEND_ALLOWED",
                1_999L,
                null,
                null,
                null, null, null);
    }
}
