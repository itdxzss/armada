package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.armada.marketing.scheduler.MarketingRoundSchedulerProperties;
import com.armada.marketing.service.impl.MarketingNewGroupImmediateSendServiceImpl;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class MarketingNewGroupImmediateSendServiceImplTest {

    private final MarketingTaskMapper mapper = mock(MarketingTaskMapper.class);
    private final MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
    private final MarketingTemplateFileMapper fileMapper = mock(MarketingTemplateFileMapper.class);
    private final MessageSendPort messagePort = mock(MessageSendPort.class);
    private final MarketingMessageCommandFactory messageFactory = new MarketingMessageCommandFactory(
            templateMapper,
            fileMapper,
            new MarketingMessageComposer());
    private final MarketingRoundSchedulerProperties schedulerProperties = schedulerProperties();
    private final MarketingNewGroupImmediateSendServiceImpl service =
            new MarketingNewGroupImmediateSendServiceImpl(
                    mapper, messageFactory, messagePort, schedulerProperties);

    @BeforeEach
    void setUp() {
        when(templateMapper.selectById(77L)).thenReturn(textTemplate());
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
        when(mapper.selectTaskById(42L)).thenReturn(sendingTask());
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
        task.setStatus(2);
        task.setMarketingTemplateId(77L);
        task.setAccountGroupSendIntervalMs(750);
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
