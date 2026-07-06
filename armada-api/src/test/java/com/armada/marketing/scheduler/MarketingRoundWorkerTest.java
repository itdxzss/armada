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
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.platform.protocol.model.command.ProtocolMarketingMessageCommandRequest;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    void backlogAtThresholdPostponesRoundWithoutOutbox() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);

        MarketingTask task = task();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(2000L);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets(1000));

        MarketingRoundWorker worker = worker(taskMapper, outbox, properties);
        worker.runRound(1L, 42L);

        verify(taskMapper).postponeDueRound(any(), anyLong(), anyLong());
        verify(taskMapper, never()).claimDueRound(any(), anyLong(), anyLong());
        verify(outbox, never()).enqueueMarketingMessageCommands(any());
    }

    @Test
    void dueRoundCreatesSubmittedAttemptsAndOutboxCommands() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);
        properties.setOutboxBatchSize(500);

        MarketingTask task = task();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets(2));
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

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketingTaskSendAttempt>> attemptsCaptor = ArgumentCaptor.forClass(List.class);
        verify(taskMapper).insertSendAttempts(attemptsCaptor.capture());
        List<MarketingTaskSendAttempt> attempts = attemptsCaptor.getValue();
        assertThat(attempts).hasSize(2);
        assertThat(attempts).extracting(MarketingTaskSendAttempt::getRoundNo).containsOnly(1L);
        assertThat(attempts).extracting(MarketingTaskSendAttempt::getCommandId)
                .allSatisfy(commandId -> assertThat(commandId).asString().startsWith("cmd_"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolMarketingMessageCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outbox).enqueueMarketingMessageCommands(commandsCaptor.capture());
        List<ProtocolMarketingMessageCommandRequest> commands = commandsCaptor.getValue();
        assertThat(commands).hasSize(2);
        assertThat(commands).extracting(ProtocolMarketingMessageCommandRequest::attemptId)
                .containsExactly(9001L, 9002L);
        assertThat(commands).extracting(ProtocolMarketingMessageCommandRequest::commandId)
                .containsExactlyElementsOf(attempts.stream().map(MarketingTaskSendAttempt::getCommandId).toList());
        assertThat(commands).extracting(ProtocolMarketingMessageCommandRequest::messageType).containsOnly("TEXT");
    }

    @Test
    void accountDynamicTargetExpandsCurrentGroupsBeforeSending() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);

        MarketingTask task = task();
        MarketingTaskTarget target = dynamicTarget();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(List.of(target));
        when(taskMapper.selectDynamicTargetGroups(5001L)).thenReturn(List.of(
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

        MarketingRoundWorker worker = worker(taskMapper, outbox, properties);
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
        ArgumentCaptor<List<ProtocolMarketingMessageCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outbox).enqueueMarketingMessageCommands(commandsCaptor.capture());
        assertThat(commandsCaptor.getValue()).extracting(ProtocolMarketingMessageCommandRequest::groupJid)
                .containsExactly("12036308101@g.us", "12036308102@g.us");
    }

    @Test
    void accountDynamicTargetWithoutResolvedGroupsPostponesRound() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();

        MarketingTask task = task();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(List.of(dynamicTarget()));
        when(taskMapper.selectDynamicTargetGroups(5001L)).thenReturn(List.of());

        MarketingRoundWorker worker = worker(taskMapper, outbox, properties);
        worker.runRound(1L, 42L);

        verify(taskMapper).postponeDueRound(any(), anyLong(), anyLong());
        verify(taskMapper, never()).claimDueRound(any(), anyLong(), anyLong());
        verify(taskMapper, never()).insertSendAttempts(any());
        verify(outbox, never()).enqueueMarketingMessageCommands(any());
    }

    @Test
    void dueRoundLogsRoundGenerationSummary() {
        Logger logger = (Logger) LoggerFactory.getLogger(MarketingRoundWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
            ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
            MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
            properties.setBacklogMultiplier(2);

            MarketingTask task = task();
            when(taskMapper.selectTaskById(42L)).thenReturn(task);
            when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
            when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets(2));
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
        ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);
        properties.setOutboxBatchSize(500);

        MarketingTask task = task();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets(450));
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
        MarketingRoundWorker worker = new MarketingRoundWorker(taskMapper, templateMapper, fileMapper,
                new MarketingMessageComposer(), outbox, properties);

        worker.runRound(1L, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolMarketingMessageCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outbox, times(3)).enqueueMarketingMessageCommands(commandsCaptor.capture());
        List<List<ProtocolMarketingMessageCommandRequest>> batches = commandsCaptor.getAllValues();
        assertThat(batches).extracting(List::size).containsExactly(200, 200, 50);
        assertThat(batches.stream().flatMap(List::stream).toList())
                .extracting(ProtocolMarketingMessageCommandRequest::messageType)
                .containsOnly("IMAGE");
    }

    @Test
    void normalLinkCardRoundEnqueuesLinkCardCommand() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);
        properties.setImageOutboxBatchSize(200);

        MarketingTask task = task();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets(1));
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
        MarketingRoundWorker worker = new MarketingRoundWorker(taskMapper, templateMapper, fileMapper,
                new MarketingMessageComposer(), outbox, properties);

        worker.runRound(1L, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolMarketingMessageCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outbox).enqueueMarketingMessageCommands(commandsCaptor.capture());
        ProtocolMarketingMessageCommandRequest command = commandsCaptor.getValue().get(0);
        assertThat(command.messageType()).isEqualTo("LINK_CARD");
        assertThat(command.text()).isEqualTo("https://example.com/promo");
        assertThat(command.linkCard().url()).isEqualTo("https://example.com/promo");
        assertThat(command.linkCard().thumbnail().base64()).isEqualTo("AQID");
        assertThat(command.buttonCard()).isNull();
    }

    @Test
    void buttonCardRoundEnqueuesButtonCardCommand() throws JsonProcessingException {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);

        MarketingTask task = task();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets(1));
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
        MarketingRoundWorker worker = new MarketingRoundWorker(taskMapper, templateMapper, fileMapper,
                new MarketingMessageComposer(), outbox, properties);

        worker.runRound(1L, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolMarketingMessageCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outbox).enqueueMarketingMessageCommands(commandsCaptor.capture());
        ProtocolMarketingMessageCommandRequest command = commandsCaptor.getValue().get(0);
        assertThat(command.messageType()).isEqualTo("BUTTON_CARD");
        assertThat(command.buttonCard().buttons()).hasSize(1);
        assertThat(command.buttonCard().buttons().get(0).type()).isEqualTo("quick");
        assertThat(command.linkCard()).isNull();
    }

    @Test
    void invalidButtonTemplateCreatesLocalFailuresWithoutOutbox() {
        MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
        ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);

        MarketingTask task = task();
        when(taskMapper.selectTaskById(42L)).thenReturn(task);
        when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
        when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets(2));
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
        MarketingRoundWorker worker = new MarketingRoundWorker(taskMapper, templateMapper, fileMapper,
                new MarketingMessageComposer(), outbox, properties);

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
        verify(outbox, never()).enqueueMarketingMessageCommands(any());
    }

    private MarketingRoundWorker worker(MarketingTaskMapper taskMapper,
                                        ProtocolCommandOutboxService outbox,
                                        MarketingRoundSchedulerProperties properties) {
        MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
        MarketingTemplateFileMapper fileMapper = mock(MarketingTemplateFileMapper.class);
        when(templateMapper.selectById(77L)).thenReturn(template());
        return new MarketingRoundWorker(taskMapper, templateMapper, fileMapper,
                new MarketingMessageComposer(), outbox, properties);
    }

    private static MarketingTask task() {
        MarketingTask task = new MarketingTask();
        task.setId(42L);
        task.setTenantId(1L);
        task.setStatus(2);
        task.setSendIntervalSeconds(30);
        task.setCurrentRoundNo(0L);
        task.setMarketingTemplateId(77L);
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

    private static MarketingTemplate template() {
        MarketingTemplate template = new MarketingTemplate();
        template.setId(77L);
        template.setTemplateName("template");
        template.setLinkMode(LinkMode.NORMAL.code());
        template.setContent("hello");
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
}
