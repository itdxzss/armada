package com.armada.marketing.scheduler;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.ButtonType;
import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.MessageButton;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.marketing.model.enums.MarketingTargetScope;
import com.armada.marketing.model.support.MarketingSendAttemptResult;
import com.armada.marketing.model.vo.MarketingAccountOccupancyOwnerRow;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import com.armada.marketing.service.MarketingMessageCommandFactory;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingRoundWorkerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void mixedProtocolTargetsKeepWebSubmittedAndFailAndroidInvalidButtonLocally()
            throws JsonProcessingException {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        MessageSendPort messageSendPort = mock(MessageSendPort.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        List<MarketingTaskTarget> targets = targets(2);
        targets.get(0).setProtocolId("WEB");
        targets.get(0).setProtocolWsPhone("923000001");
        targets.get(1).setProtocolId("ANDROID");
        targets.get(1).setProtocolWsPhone("923000002");
        when(taskMapper.selectTaskById(42L)).thenReturn(task());
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets);
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
        assignAttemptIds(taskMapper, 9_800L);
        when(taskMapper.markAttemptFailed(any(MarketingSendAttemptResult.class)))
                .thenReturn(1);
        when(messageSendPort.enqueue(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MessageSendCommand> commands = invocation.getArgument(0, List.class);
            return new MessageSendEnqueueResult(commands.stream()
                    .map(command -> command.account().backend() == ProtocolBackend.WEB
                            ? MessageSendEnqueueItem.accepted(command.commandId())
                            : MessageSendEnqueueItem.rejected(
                                    command.commandId(),
                                    "INVALID_ANDROID_BUTTON_CONFIG",
                                    "按钮数量只支持 1 个"))
                    .toList());
        });
        MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
        MarketingTemplateFileMapper fileMapper = mock(MarketingTemplateFileMapper.class);
        when(templateMapper.selectById(77L)).thenReturn(buttonTemplateWithTwoLinks());
        MarketingRoundWorker worker = new MarketingRoundWorker(
                taskMapper,
                defaultOccupancyService(),
                messageFactory(templateMapper, fileMapper),
                messageSendPort,
                properties,
                Clock.systemUTC());

        worker.runRound(1L, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(messageSendPort).enqueue(commandsCaptor.capture());
        assertThat(commandsCaptor.getValue())
                .extracting(command -> command.account().backend())
                .containsExactly(ProtocolBackend.WEB, ProtocolBackend.ANDROID);
        assertThat(commandsCaptor.getValue())
                .extracting(command -> command.account().wsPhone())
                .containsExactly("923000001", "923000002");
        verify(taskMapper).markAttemptFailed(argThat(result ->
                Long.valueOf(9_802L).equals(result.attemptId())
                        && result.commandId().startsWith("cmd_")
                        && "INVALID_ANDROID_BUTTON_CONFIG".equals(result.reasonCode())
                        && result.reasonMessage().contains("按钮数量")
                        && targets.get(1).getGroupJid().equals(result.groupJid())));
        verify(taskMapper).markTargetFailedFromAttempt(
                eq(targets.get(1).getId()),
                eq(9_802L),
                eq("INVALID_ANDROID_BUTTON_CONFIG"),
                contains("按钮数量"),
                anyLong());
        verify(taskMapper).incrementTaskSendCounters(eq(42L), eq(0), eq(1), anyLong());
    }

    @Test
    void futureSendingTaskReturnsToWaitingWithoutGeneratingMessages() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        MessageSendPort outbox = acceptingMessagePort();
        MarketingAccountOccupancyService occupancyService = mock(MarketingAccountOccupancyService.class);
        MarketingTask task = task();
        task.setTaskStartAt(System.currentTimeMillis() + 60_000L);
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.deferEarlySendingTask(eq(42L), anyLong())).thenReturn(1);

        MarketingRoundWorker worker = worker(
                taskMapper, outbox, new MarketingRoundSchedulerProperties(), Clock.systemUTC(), occupancyService);
        worker.runRound(1L, 42L);

        verify(taskMapper).deferEarlySendingTask(eq(42L), anyLong());
        verify(occupancyService, never()).releaseTaskAccounts(42L);
        verify(taskMapper, never()).selectTargetsByTaskId(anyLong());
        verify(taskMapper, never()).insertSendAttempts(any());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void taskCrossingEndTimeDuringTargetResolutionDoesNotClaimOrGenerateMessages() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        MessageSendPort outbox = acceptingMessagePort();
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1_000L, 2_000L);
        MarketingTask task = task();
        task.setTaskEndAt(1_500L);
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets(1));
        when(taskMapper.endExpiredTask(42L, 2_000L)).thenReturn(1);
        MarketingAccountOccupancyService occupancyService = mock(MarketingAccountOccupancyService.class);

        MarketingRoundWorker worker = worker(
                taskMapper, outbox, new MarketingRoundSchedulerProperties(), clock, occupancyService);
        worker.runRound(1L, 42L);

        verify(taskMapper).endExpiredTask(42L, 2_000L);
        verify(occupancyService).releaseTaskAccounts(42L);
        verify(taskMapper, never()).claimDueRound(any(), anyLong(), anyLong());
        verify(taskMapper, never()).insertSendAttempts(any());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void backlogAtThresholdPostponesRoundWithoutOutbox() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        MessageSendPort outbox = acceptingMessagePort();
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);

        MarketingTask task = task();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(2000L);
        List<MarketingTaskTarget> targets = targets(1000);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets);
        stubCurrentTargets(taskMapper, targets);

        MarketingRoundWorker worker = worker(taskMapper, outbox, properties);
        worker.runRound(1L, 42L);

        verify(taskMapper).postponeDueRound(any(), anyLong(), anyLong());
        verify(taskMapper, never()).claimDueRound(any(), anyLong(), anyLong());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void dueRoundCreatesSubmittedAttemptsAndOutboxCommands() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        MessageSendPort outbox = acceptingMessagePort();
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);
        properties.setOutboxBatchSize(500);

        MarketingTask task = task();
        task.setAccountGroupSendIntervalMs(750);
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        List<MarketingTaskTarget> targets = targets(2);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets);
        stubCurrentTargets(taskMapper, targets);
        when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MarketingTaskSendAttempt> attempts = invocation.getArgument(0, List.class);
            long id = 9000L;
            for (MarketingTaskSendAttempt attempt : attempts) {
                attempt.setId(++id);
            }
            return attempts.size();
        }).when(taskMapper).insertSendAttempts(any());

        MarketingRoundWorker worker = worker(
                taskMapper, outbox, properties, fixedClock(2_000L));
        worker.runRound(1L, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketingTaskSendAttempt>> attemptsCaptor = ArgumentCaptor.forClass(List.class);
        verify(taskMapper).insertSendAttempts(attemptsCaptor.capture());
        List<MarketingTaskSendAttempt> attempts = attemptsCaptor.getValue();
        assertThat(attempts).hasSize(2);
        assertThat(attempts).extracting(MarketingTaskSendAttempt::getRoundNo).containsOnly(1L);
        assertThat(attempts).extracting(MarketingTaskSendAttempt::getCommandId)
                .allSatisfy(commandId -> assertThat(commandId).asString().startsWith("cmd_"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outbox).enqueue(commandsCaptor.capture());
        List<MessageSendCommand> commands = commandsCaptor.getValue();
        assertThat(commands).hasSize(2);
        assertThat(commands).extracting(command -> command.correlation().marketing().attemptId())
                .containsExactly(9001L, 9002L);
        assertThat(commands).extracting(MessageSendCommand::commandId)
                .containsExactlyElementsOf(attempts.stream().map(MarketingTaskSendAttempt::getCommandId).toList());
        assertThat(commands).extracting(command -> command.payload().type().name()).containsOnly("TEXT");
        assertThat(commands).extracting(command -> command.payload().mentionAll()).containsOnly(true);
        assertThat(commands).extracting(MessageSendCommand::sendIntervalMs)
                .containsOnly(750);
        assertThat(commands).extracting(MessageSendCommand::notBeforeAt)
                .containsExactly(2_000L, 2_000L);
    }

    @Test
    void dueRound_sendsOwnedAccountAndRecordsOccupiedAccountAsSkipped() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        MessageSendPort outbox = acceptingMessagePort();
        MarketingAccountOccupancyService occupancyService = mock(MarketingAccountOccupancyService.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        List<MarketingTaskTarget> targets = targets(2);
        MarketingAccountOccupancyOwnerRow currentOwner = owner(5001L, 42L, "当前任务", 5_000L);
        MarketingAccountOccupancyOwnerRow otherOwner = owner(5002L, 99L, "其它任务", 6_000L);
        when(taskMapper.selectTaskById(42L)).thenReturn(task());
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets);
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
        when(occupancyService.acquireAndLoadTaskAccounts(any(), anyLong()))
                .thenReturn(Map.of(5001L, currentOwner, 5002L, otherOwner));
        when(occupancyService.occupiedAttemptMessage(otherOwner))
                .thenReturn("账号已被其它任务占用，本轮未发送。");
        assignAttemptIds(taskMapper, 9_500L);

        MarketingRoundWorker worker = worker(
                taskMapper, outbox, properties, Clock.systemUTC(), occupancyService);
        worker.runRound(1L, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketingTaskSendAttempt>> attemptsCaptor = ArgumentCaptor.forClass(List.class);
        verify(taskMapper).insertSendAttempts(attemptsCaptor.capture());
        assertThat(attemptsCaptor.getValue()).hasSize(2);
        assertThat(attemptsCaptor.getValue()).filteredOn(
                        attempt -> attempt.getStatus() == MarketingSendAttemptStatus.SKIPPED.code())
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getReasonCode()).isEqualTo("ACCOUNT_OCCUPIED");
                    assertThat(attempt.getReasonMessage()).contains("本轮未发送");
                    assertThat(attempt.getGroupJid()).isEqualTo(targets.get(1).getGroupJid());
                });
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> commandsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(outbox).enqueue(commandsCaptor.capture());
        assertThat(commandsCaptor.getValue()).singleElement().satisfies(command ->
                assertThat(command.account().protocolAccountId()).isEqualTo(targets.get(0).getProtocolAccountId()));
        verify(taskMapper, never()).incrementTaskSendCounters(eq(42L), eq(0), anyInt(), anyLong());
    }

    @Test
    void occupiedAccount_releasedBeforeLaterRound_isAcquiredAndSent() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        MessageSendPort outbox = acceptingMessagePort();
        MarketingAccountOccupancyService occupancyService = mock(MarketingAccountOccupancyService.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        MarketingTask firstRoundTask = task();
        MarketingTask secondRoundTask = task();
        secondRoundTask.setCurrentRoundNo(1L);
        MarketingTaskTarget target = targets(1).get(0);
        MarketingAccountOccupancyOwnerRow otherOwner = owner(5001L, 99L, "其它任务", 6_000L);
        MarketingAccountOccupancyOwnerRow currentOwner = owner(5001L, 42L, "当前任务", 7_000L);
        when(taskMapper.selectTaskById(42L)).thenReturn(firstRoundTask, secondRoundTask);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(List.of(target));
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
        when(occupancyService.acquireAndLoadTaskAccounts(any(), anyLong()))
                .thenReturn(Map.of(5001L, otherOwner), Map.of(5001L, currentOwner));
        when(occupancyService.occupiedAttemptMessage(otherOwner))
                .thenReturn("账号已被其它任务占用，本轮未发送。");
        assignAttemptIds(taskMapper, 9_600L);

        MarketingRoundWorker worker = worker(
                taskMapper, outbox, properties, Clock.systemUTC(), occupancyService);
        worker.runRound(1L, 42L);
        worker.runRound(1L, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketingTaskSendAttempt>> attemptsCaptor = ArgumentCaptor.forClass(List.class);
        verify(taskMapper, times(2)).insertSendAttempts(attemptsCaptor.capture());
        assertThat(attemptsCaptor.getAllValues().get(0)).singleElement().satisfies(attempt ->
                assertThat(attempt.getStatus()).isEqualTo(MarketingSendAttemptStatus.SKIPPED.code()));
        assertThat(attemptsCaptor.getAllValues().get(1)).singleElement().satisfies(attempt ->
                assertThat(attempt.getStatus()).isEqualTo(MarketingSendAttemptStatus.SUBMITTED.code()));
        verify(outbox, times(1)).enqueue(any());
    }

    @Test
    void fixedGroupTargetMissingCurrentMembershipStillUsesSavedSnapshot() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        MessageSendPort outbox = acceptingMessagePort();
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);

        MarketingTask task = task();
        MarketingTaskTarget target = targets(1).get(0);
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(List.of(target));
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MarketingTaskSendAttempt> attempts = invocation.getArgument(0, List.class);
            attempts.get(0).setId(9051L);
            return attempts.size();
        }).when(taskMapper).insertSendAttempts(any());

        MarketingRoundWorker worker = worker(
                taskMapper, outbox, properties, fixedClock(2_000L));
        worker.runRound(1L, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketingTaskSendAttempt>> attemptsCaptor = ArgumentCaptor.forClass(List.class);
        verify(taskMapper).insertSendAttempts(attemptsCaptor.capture());
        assertThat(attemptsCaptor.getValue()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getGroupLinkId()).isEqualTo(target.getGroupLinkId());
            assertThat(attempt.getGroupJid()).isEqualTo(target.getGroupJid());
            assertThat(attempt.getGroupName()).isEqualTo(target.getGroupName());
        });
        verify(taskMapper, never()).selectCurrentTargetGroup(anyLong(), anyLong());
    }

    @Test
    void fixedGroupTargetMissingSavedGroupJidPostponesWithoutAttempt() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        MessageSendPort outbox = acceptingMessagePort();
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);

        MarketingTask task = task();
        MarketingTaskTarget target = targets(1).get(0);
        target.setGroupJid(null);
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(List.of(target));

        MarketingRoundWorker worker = worker(taskMapper, outbox, properties);
        worker.runRound(1L, 42L);

        verify(taskMapper).postponeDueRound(eq(42L), anyLong(), anyLong());
        verify(taskMapper, never()).claimDueRound(any(), anyLong(), anyLong());
        verify(taskMapper, never()).insertSendAttempts(any());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void accountDynamicTargetExpandsCurrentGroupsBeforeSending() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        MessageSendPort outbox = acceptingMessagePort();
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);

        MarketingTask task = task();
        MarketingTaskTarget target = dynamicTarget();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(List.of(target));
        when(taskMapper.selectDynamicTargetGroups(5001L, 1_000L)).thenReturn(List.of(
                dynamicGroup(8101L, "12036308101@g.us", "新增群A"),
                dynamicGroup(8102L, "12036308102@g.us", "新增群B")));
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MarketingTaskSendAttempt> attempts = invocation.getArgument(0, List.class);
            long id = 9100L;
            for (MarketingTaskSendAttempt attempt : attempts) {
                attempt.setId(++id);
            }
            return attempts.size();
        }).when(taskMapper).insertSendAttempts(any());

        MarketingRoundWorker worker = worker(
                taskMapper, outbox, properties, fixedClock(2_000L));
        worker.runRound(1L, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketingTaskSendAttempt>> attemptsCaptor = ArgumentCaptor.forClass(List.class);
        verify(taskMapper).insertSendAttempts(attemptsCaptor.capture());
        List<MarketingTaskSendAttempt> attempts = attemptsCaptor.getValue();
        assertThat(attempts).hasSize(2);
        assertThat(attempts).extracting(MarketingTaskSendAttempt::getTargetId).containsOnly(7101L);
        assertThat(attempts).extracting(MarketingTaskSendAttempt::getGroupLinkId).containsExactly(8101L, 8102L);
        assertThat(attempts).extracting(MarketingTaskSendAttempt::getGroupJid)
                .containsExactly("12036308101@g.us", "12036308102@g.us");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outbox).enqueue(commandsCaptor.capture());
        assertThat(commandsCaptor.getValue()).extracting(command -> command.target().groupJid())
                .containsExactly("12036308101@g.us", "12036308102@g.us");
        assertThat(commandsCaptor.getValue()).extracting(MessageSendCommand::sendIntervalMs)
                .containsOnly(500);
        assertThat(commandsCaptor.getValue()).extracting(MessageSendCommand::notBeforeAt)
                .containsExactly(2_000L, 2_500L);
    }

    @Test
    void accountDynamicTargetWithoutResolvedGroupsPostponesRound() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        MessageSendPort outbox = acceptingMessagePort();
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();

        MarketingTask task = task();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(List.of(dynamicTarget()));
        when(taskMapper.selectDynamicTargetGroups(5001L, 1_000L)).thenReturn(List.of());

        MarketingRoundWorker worker = worker(taskMapper, outbox, properties);
        worker.runRound(1L, 42L);

        verify(taskMapper).postponeDueRound(any(), anyLong(), anyLong());
        verify(taskMapper, never()).claimDueRound(any(), anyLong(), anyLong());
        verify(taskMapper, never()).insertSendAttempts(any());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void dueRoundLogsRoundGenerationSummary() {
        Logger logger = (Logger) LoggerFactory.getLogger(MarketingRoundWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
            MessageSendPort outbox = acceptingMessagePort();
            MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
            properties.setBacklogMultiplier(2);

            MarketingTask task = task();
            when(taskMapper.selectTaskById(42L)).thenReturn(task);
            when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
            List<MarketingTaskTarget> targets = targets(2);
            when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets);
            stubCurrentTargets(taskMapper, targets);
            when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
            doAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                List<MarketingTaskSendAttempt> attempts = invocation.getArgument(0, List.class);
                long id = 9000L;
                for (MarketingTaskSendAttempt attempt : attempts) {
                    attempt.setId(++id);
                }
                return attempts.size();
            }).when(taskMapper).insertSendAttempts(any());

            MarketingRoundWorker worker = worker(taskMapper, outbox, properties);
            worker.runRound(1L, 42L);

            assertThat(appender.list)
                    .anyMatch(event -> event.getFormattedMessage().contains("营销任务轮次发送命令已生成")
                            && event.getFormattedMessage().contains("tenantId=1")
                            && event.getFormattedMessage().contains("taskId=42")
                            && event.getFormattedMessage().contains("roundNo=1")
                            && event.getFormattedMessage().contains("targetCount=2")
                            && event.getFormattedMessage().contains("messageType=TEXT"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void imageRoundUsesTwoHundredCommandBatchSize() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        MessageSendPort outbox = acceptingMessagePort();
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);
        properties.setOutboxBatchSize(500);

        MarketingTask task = task();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        List<MarketingTaskTarget> targets = targets(450);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets);
        stubCurrentTargets(taskMapper, targets);
        when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MarketingTaskSendAttempt> attempts = invocation.getArgument(0, List.class);
            long id = 9000L;
            for (MarketingTaskSendAttempt attempt : attempts) {
                attempt.setId(++id);
            }
            return attempts.size();
        }).when(taskMapper).insertSendAttempts(any());

        MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
        MarketingTemplateFileMapper fileMapper = mock(MarketingTemplateFileMapper.class);
        when(templateMapper.selectById(77L)).thenReturn(imageTemplate());
        when(fileMapper.selectById(88L)).thenReturn(imageFile());
        MarketingRoundWorker worker = new MarketingRoundWorker(
                taskMapper,
                defaultOccupancyService(),
                messageFactory(templateMapper, fileMapper),
                outbox,
                properties,
                Clock.systemUTC());

        worker.runRound(1L, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outbox, times(3)).enqueue(commandsCaptor.capture());
        List<List<MessageSendCommand>> batches = commandsCaptor.getAllValues();
        assertThat(batches).extracting(List::size).containsExactly(200, 200, 50);
        assertThat(batches.stream().flatMap(List::stream).toList())
                .extracting(command -> command.payload().type().name())
                .containsOnly("IMAGE");
    }

    @Test
    void normalLinkCardRoundEnqueuesLinkCardCommand() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        MessageSendPort outbox = acceptingMessagePort();
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);
        properties.setImageOutboxBatchSize(200);

        MarketingTask task = task();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        List<MarketingTaskTarget> targets = targets(1);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets);
        stubCurrentTargets(taskMapper, targets);
        when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MarketingTaskSendAttempt> attempts = invocation.getArgument(0, List.class);
            attempts.get(0).setId(9201L);
            return attempts.size();
        }).when(taskMapper).insertSendAttempts(any());
        MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
        MarketingTemplateFileMapper fileMapper = mock(MarketingTemplateFileMapper.class);
        when(templateMapper.selectById(77L)).thenReturn(normalLinkCardTemplate());
        when(fileMapper.selectById(88L)).thenReturn(imageFile());
        MarketingRoundWorker worker = new MarketingRoundWorker(
                taskMapper,
                defaultOccupancyService(),
                messageFactory(templateMapper, fileMapper),
                outbox,
                properties,
                Clock.systemUTC());

        worker.runRound(1L, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outbox).enqueue(commandsCaptor.capture());
        MessageSendCommand command = commandsCaptor.getValue().get(0);
        assertThat(command.payload().type().name()).isEqualTo("LINK_CARD");
        assertThat(command.payload().content().text()).isEqualTo("https://example.com/promo");
        assertThat(command.payload().content().linkCard().url()).isEqualTo("https://example.com/promo");
        assertThat(command.payload().content().linkCard().thumbnail().bytes()).containsExactly(1, 2, 3);
        assertThat(command.payload().content().buttonCard()).isNull();
    }

    @Test
    void buttonCardRoundEnqueuesButtonCardCommand() throws JsonProcessingException {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        MessageSendPort outbox = acceptingMessagePort();
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);

        MarketingTask task = task();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        List<MarketingTaskTarget> targets = targets(1);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets);
        stubCurrentTargets(taskMapper, targets);
        when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MarketingTaskSendAttempt> attempts = invocation.getArgument(0, List.class);
            attempts.get(0).setId(9301L);
            return attempts.size();
        }).when(taskMapper).insertSendAttempts(any());
        MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
        MarketingTemplateFileMapper fileMapper = mock(MarketingTemplateFileMapper.class);
        when(templateMapper.selectById(77L)).thenReturn(buttonTemplateWithButtons());
        MarketingRoundWorker worker = new MarketingRoundWorker(
                taskMapper,
                defaultOccupancyService(),
                messageFactory(templateMapper, fileMapper),
                outbox,
                properties,
                Clock.systemUTC());

        worker.runRound(1L, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outbox).enqueue(commandsCaptor.capture());
        MessageSendCommand command = commandsCaptor.getValue().get(0);
        assertThat(command.payload().type().name()).isEqualTo("BUTTON_CARD");
        assertThat(command.payload().content().buttonCard().buttons()).hasSize(1);
        assertThat(command.payload().content().buttonCard().buttons().get(0).type()).isEqualTo("quick");
        assertThat(command.payload().content().linkCard()).isNull();
    }

    @Test
    void invalidButtonTemplateCreatesLocalFailuresWithoutOutbox() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        MessageSendPort outbox = acceptingMessagePort();
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);

        MarketingTask task = task();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        List<MarketingTaskTarget> targets = targets(2);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets);
        stubCurrentTargets(taskMapper, targets);
        when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MarketingTaskSendAttempt> attempts = invocation.getArgument(0, List.class);
            long id = 9400L;
            for (MarketingTaskSendAttempt attempt : attempts) {
                attempt.setId(++id);
            }
            return attempts.size();
        }).when(taskMapper).insertSendAttempts(any());
        MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
        MarketingTemplateFileMapper fileMapper = mock(MarketingTemplateFileMapper.class);
        when(templateMapper.selectById(77L)).thenReturn(invalidButtonTemplate());
        MarketingRoundWorker worker = new MarketingRoundWorker(
                taskMapper,
                defaultOccupancyService(),
                messageFactory(templateMapper, fileMapper),
                outbox,
                properties,
                Clock.systemUTC());

        worker.runRound(1L, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketingTaskSendAttempt>> attemptsCaptor = ArgumentCaptor.forClass(List.class);
        verify(taskMapper).insertSendAttempts(attemptsCaptor.capture());
        List<MarketingTaskSendAttempt> attempts = attemptsCaptor.getValue();
        assertThat(attempts).hasSize(2);
        assertThat(attempts).extracting(MarketingTaskSendAttempt::getStatus)
                .containsOnly(MarketingSendAttemptStatus.FAILED.code());
        assertThat(attempts).extracting(MarketingTaskSendAttempt::getReasonCode)
                .containsOnly("INVALID_TEMPLATE_CONFIG");
        verify(taskMapper).incrementTaskSendCounters(42L, 0, 2, attempts.get(0).getResultAt());
        verify(taskMapper, times(2)).markTargetFailedFromAttempt(anyLong(), anyLong(),
                eq("INVALID_TEMPLATE_CONFIG"), contains("按钮超链消息类型"), anyLong());
        verify(outbox, never()).enqueue(any());
    }

    private MarketingRoundWorker worker(MarketingTaskMapper taskMapper,
                                        MessageSendPort outbox,
                                        MarketingRoundSchedulerProperties properties) {
        return worker(taskMapper, outbox, properties, Clock.systemUTC());
    }

    private MarketingRoundWorker worker(MarketingTaskMapper taskMapper,
                                        MessageSendPort outbox,
                                        MarketingRoundSchedulerProperties properties,
                                        Clock clock) {
        return worker(taskMapper, outbox, properties, clock, defaultOccupancyService());
    }

    private MarketingRoundWorker worker(MarketingTaskMapper taskMapper,
                                        MessageSendPort outbox,
                                        MarketingRoundSchedulerProperties properties,
                                        Clock clock,
                                        MarketingAccountOccupancyService occupancyService) {
        MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
        MarketingTemplateFileMapper fileMapper = mock(MarketingTemplateFileMapper.class);
        when(templateMapper.selectById(77L)).thenReturn(template());
        return new MarketingRoundWorker(
                taskMapper,
                occupancyService,
                messageFactory(templateMapper, fileMapper),
                outbox,
                properties,
                clock);
    }

    private static MarketingMessageCommandFactory messageFactory(
            MarketingTemplateMapper templateMapper,
            MarketingTemplateFileMapper fileMapper) {
        return new MarketingMessageCommandFactory(
                templateMapper,
                fileMapper,
                new MarketingMessageComposer());
    }

    private static MarketingAccountOccupancyService defaultOccupancyService() {
        MarketingAccountOccupancyService service = mock(MarketingAccountOccupancyService.class);
        when(service.acquireAndLoadTaskAccounts(any(), anyLong())).thenReturn(currentTaskOwners());
        return service;
    }

    private static MessageSendPort acceptingMessagePort() {
        MessageSendPort port = mock(MessageSendPort.class);
        when(port.enqueue(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MessageSendCommand> commands = invocation.getArgument(0, List.class);
            return new MessageSendEnqueueResult(commands.stream()
                    .map(command -> MessageSendEnqueueItem.accepted(command.commandId()))
                    .toList());
        });
        return port;
    }

    private static Map<Long, MarketingAccountOccupancyOwnerRow> currentTaskOwners() {
        Map<Long, MarketingAccountOccupancyOwnerRow> owners = new LinkedHashMap<>();
        for (long accountId = 5_001L; accountId <= 6_000L; accountId++) {
            owners.put(accountId, owner(accountId, 42L, "当前任务", 9_000L));
        }
        return owners;
    }

    private static MarketingAccountOccupancyOwnerRow owner(Long accountId,
                                                            Long taskId,
                                                            String taskName,
                                                            Long taskEndAt) {
        MarketingAccountOccupancyOwnerRow owner = new MarketingAccountOccupancyOwnerRow();
        owner.setAccountId(accountId);
        owner.setMarketingTaskId(taskId);
        owner.setTaskName(taskName);
        owner.setTaskEndAt(taskEndAt);
        return owner;
    }

    private static void assignAttemptIds(MarketingTaskMapper taskMapper, long startingId) {
        long[] nextId = {startingId};
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MarketingTaskSendAttempt> attempts = invocation.getArgument(0, List.class);
            for (MarketingTaskSendAttempt attempt : attempts) {
                attempt.setId(++nextId[0]);
            }
            return attempts.size();
        }).when(taskMapper).insertSendAttempts(any());
    }

    private static MarketingTask task() {
        MarketingTask task = new MarketingTask();
        task.setId(42L);
        task.setTenantId(1L);
        task.setStatus(2);
        task.setSendIntervalSeconds(30);
        task.setAccountGroupSendIntervalMs(500);
        task.setCurrentRoundNo(0L);
        task.setMarketingTemplateId(77L);
        task.setAccountGroupSendAt(1_000L);
        return task;
    }

    private static List<MarketingTaskTarget> targets(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> {
                    MarketingTaskTarget target = new MarketingTaskTarget();
                    target.setId(7000L + i);
                    target.setMarketingTaskId(42L);
                    target.setAccountId(5000L + i);
                    target.setAccountPhone("92300000" + i);
                    target.setProtocolAccountId("acc_92300000" + i);
                    target.setProtocolId("WEB");
                    target.setProtocolWsPhone("92300000" + i);
                    target.setTargetScope(MarketingTargetScope.GROUP_FIXED.code());
                    target.setGroupLinkId(8000L + i);
                    target.setGroupJid("12036300" + i + "@g.us");
                    return target;
                })
                .toList();
    }

    private static MarketingTaskTarget dynamicTarget() {
        MarketingTaskTarget target = new MarketingTaskTarget();
        target.setId(7101L);
        target.setMarketingTaskId(42L);
        target.setAccountId(5001L);
        target.setAccountPhone("923000001");
        target.setProtocolAccountId("acc_923000001");
        target.setProtocolId("WEB");
        target.setProtocolWsPhone("923000001");
        target.setTargetScope(MarketingTargetScope.ACCOUNT_DYNAMIC.code());
        return target;
    }

    private static MarketingTargetCandidateRow dynamicGroup(Long groupLinkId, String groupJid, String groupName) {
        MarketingTargetCandidateRow row = new MarketingTargetCandidateRow();
        row.setAccountId(5001L);
        row.setAccountPhone("923000001");
        row.setGroupLinkId(groupLinkId);
        row.setGroupJid(groupJid);
        row.setGroupName(groupName);
        return row;
    }

    private static void stubCurrentTargets(MarketingTaskMapper taskMapper, List<MarketingTaskTarget> targets) {
        for (MarketingTaskTarget target : targets) {
            when(taskMapper.selectCurrentTargetGroup(target.getAccountId(), target.getGroupLinkId()))
                    .thenReturn(currentGroup(target));
        }
    }

    private static MarketingTargetCandidateRow currentGroup(MarketingTaskTarget target) {
        MarketingTargetCandidateRow row = new MarketingTargetCandidateRow();
        row.setAccountId(target.getAccountId());
        row.setAccountPhone(target.getAccountPhone());
        row.setGroupLinkId(target.getGroupLinkId());
        row.setGroupJid(target.getGroupJid());
        row.setGroupLinkUrl(target.getGroupLinkUrl());
        row.setGroupName(target.getGroupName());
        return row;
    }

    private static MarketingTemplate template() {
        MarketingTemplate template = new MarketingTemplate();
        template.setId(77L);
        template.setTemplateName("template");
        template.setLinkMode(LinkMode.NORMAL.code());
        template.setContent("hello");
        template.setMentionAll(true);
        return template;
    }

    private static MarketingTemplate normalLinkCardTemplate() {
        MarketingTemplate template = template();
        template.setLinkMode(LinkMode.NORMAL.code());
        template.setImageFileId(88L);
        template.setContent("标题");
        template.setBodyText("正文");
        template.setPromotionLink("https://example.com/promo");
        return template;
    }

    private static MarketingTemplate buttonTemplateWithButtons() throws JsonProcessingException {
        MarketingTemplate template = template();
        template.setLinkMode(LinkMode.BUTTON.code());
        template.setContent("按钮标题");
        template.setBodyText("按钮正文");
        template.setButtons(OBJECT_MAPPER.writeValueAsString(List.of(
                new MessageButton(ButtonType.QUICK_REPLY, "我要参加", null))));
        return template;
    }

    private static MarketingTemplate buttonTemplateWithTwoLinks() throws JsonProcessingException {
        MarketingTemplate template = template();
        template.setLinkMode(LinkMode.BUTTON.code());
        template.setContent("按钮标题");
        template.setButtons(OBJECT_MAPPER.writeValueAsString(List.of(
                new MessageButton(ButtonType.LINK_JUMP, "活动一", "https://example.com/one"),
                new MessageButton(ButtonType.LINK_JUMP, "活动二", "https://example.com/two"))));
        return template;
    }

    private static MarketingTemplate invalidButtonTemplate() {
        MarketingTemplate template = template();
        template.setLinkMode(LinkMode.BUTTON.code());
        template.setContent("按钮标题");
        template.setBodyText("按钮正文");
        template.setButtons("[]");
        return template;
    }

    private static MarketingTemplate imageTemplate() {
        MarketingTemplate template = template();
        template.setLinkMode(LinkMode.IMAGE_TEXT.code());
        template.setImageFileId(88L);
        return template;
    }

    private static MarketingTemplateFile imageFile() {
        MarketingTemplateFile file = new MarketingTemplateFile();
        file.setId(88L);
        file.setContentType("image/png");
        file.setContent(new byte[] {1, 2, 3});
        return file;
    }

    private static Clock fixedClock(long epochMillis) {
        return Clock.fixed(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }
}
