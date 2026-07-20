package com.armada.platform.protocol.backend.web;

import com.armada.platform.kafka.config.ProtocolMasterCommandProperties;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolMessageOutboxCommand;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebMessageSendBackendTest {

    private final ProtocolCommandOutboxService outboxService = mock(ProtocolCommandOutboxService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesWebTopicPayloadAndMultipleButtonCapabilities() {
        ProtocolMasterCommandProperties properties = new ProtocolMasterCommandProperties();
        properties.setTopic("protocol.master.commands.custom");
        WebMessageSendBackend backend = new WebMessageSendBackend(outboxService, properties);
        MessageSendCommand command = buttonCommand();
        when(outboxService.enqueueMessageCommands(anyList()))
                .thenReturn(new com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult(
                        null, List.of("cmd_web"), 1));

        MessageSendEnqueueResult result = backend.enqueue(List.of(command));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.commandId()).isEqualTo("cmd_web");
            assertThat(item.accepted()).isTrue();
        });
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolMessageOutboxCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueMessageCommands(captor.capture());
        ProtocolMessageOutboxCommand outboxCommand = captor.getValue().get(0);
        assertThat(outboxCommand.backend()).isEqualTo(ProtocolBackend.WEB);
        assertThat(outboxCommand.kafkaTopic()).isEqualTo("protocol.master.commands.custom");
        assertThat(outboxCommand.kafkaKey()).isEqualTo("acc_web");

        Map<String, Object> payload = objectMapper.convertValue(
                outboxCommand.payload(), new TypeReference<>() {
                });
        assertThat(payload)
                .containsEntry("messageType", "BUTTON_CARD")
                .containsEntry("mentionAll", true)
                .containsEntry("source", "marketing_task")
                .doesNotContainKey("wsPhone")
                .doesNotContainKeys("dispatchPolicy", "notBeforeAt", "dispatchIntervalMs");
        @SuppressWarnings("unchecked")
        Map<String, Object> buttonCard = (Map<String, Object>) payload.get("buttonCard");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buttons = (List<Map<String, Object>>) buttonCard.get("buttons");
        assertThat(buttons).hasSize(2);
        assertThat(buttons.get(0)).containsEntry("type", "link");
        assertThat(buttons.get(1)).containsEntry("type", "copy");
    }

    @Test
    void encodesImageBytesAsBase64() {
        WebMessageSendBackend backend = new WebMessageSendBackend(
                outboxService, new ProtocolMasterCommandProperties());
        MessageSendCommand command = new MessageSendCommand(
                account(),
                new MessageSendCommand.MessageTarget("120363001@g.us"),
                new MessageSendCommand.MessagePayload(
                        MessageType.IMAGE,
                        new MessageSendCommand.MessageContent(
                                "caption",
                                new MessageSendCommand.MessageMedia(new byte[]{1, 2, 3}, "image/png"),
                                null,
                                null),
                        false),
                correlation(),
                "cmd_image",
                MessageSendCommand.DEFAULT_SEND_INTERVAL_MS,
                0L);
        when(outboxService.enqueueMessageCommands(anyList()))
                .thenReturn(new com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult(
                        null, List.of("cmd_image"), 1));

        backend.enqueue(List.of(command));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolMessageOutboxCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueMessageCommands(captor.capture());
        Map<String, Object> payload = objectMapper.convertValue(
                captor.getValue().get(0).payload(), new TypeReference<>() {
                });
        @SuppressWarnings("unchecked")
        Map<String, Object> image = (Map<String, Object>) payload.get("image");
        assertThat(image)
                .containsEntry("base64", "AQID")
                .containsEntry("mimetype", "image/png");
    }

    @Test
    void encodesOnlyHistoricalCorrelationForHistoricalGroupPull() {
        WebMessageSendBackend backend = new WebMessageSendBackend(
                outboxService, new ProtocolMasterCommandProperties());
        MessageSendCommand command = new MessageSendCommand(
                account(),
                new MessageSendCommand.MessageTarget("120363history@g.us"),
                new MessageSendCommand.MessagePayload(
                        MessageType.TEXT,
                        new MessageSendCommand.MessageContent("offer", null, null, null),
                        false),
                new MessageSendCommand.MessageCorrelation(
                        7L,
                        "historical_group_pull",
                        null,
                        null,
                        new MessageSendCommand.HistoricalGroupCorrelation(91L, 301L)),
                "cmd_historical_web",
                MessageSendCommand.DEFAULT_SEND_INTERVAL_MS,
                0L);
        when(outboxService.enqueueMessageCommands(anyList()))
                .thenReturn(new com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult(
                        null, List.of("cmd_historical_web"), 1));

        backend.enqueue(List.of(command));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolMessageOutboxCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueMessageCommands(captor.capture());
        Map<String, Object> payload = objectMapper.convertValue(
                captor.getValue().get(0).payload(), new TypeReference<>() {
                });
        assertThat(payload)
                .containsEntry("source", "historical_group_pull")
                .containsEntry("historicalExecutionId", 91L)
                .containsEntry("historicalMemberId", 301L)
                .doesNotContainKeys(
                        "marketingTaskId", "attemptId", "targetId", "roundNo",
                        "groupCreationTaskId", "groupCreationItemId");
    }

    private static MessageSendCommand buttonCommand() {
        return new MessageSendCommand(
                account(),
                new MessageSendCommand.MessageTarget("120363001@g.us"),
                new MessageSendCommand.MessagePayload(
                        MessageType.BUTTON_CARD,
                        new MessageSendCommand.MessageContent(
                                "body",
                                null,
                                null,
                                new MessageSendCommand.MessageButtonCard(
                                        "title",
                                        "footer",
                                        List.of(
                                                new MessageSendCommand.MessageButton(
                                                        "link", "访问", "https://example.com"),
                                                new MessageSendCommand.MessageButton(
                                                        "copy", "复制", "CODE-1")),
                                        null)),
                        true),
                correlation(),
                "cmd_web",
                MessageSendCommand.DEFAULT_SEND_INTERVAL_MS,
                2_500L);
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(100L, ProtocolBackend.WEB, "acc_web", "919000000001");
    }

    private static MessageSendCommand.MessageCorrelation correlation() {
        return new MessageSendCommand.MessageCorrelation(
                7L,
                "marketing_task",
                new MessageSendCommand.MarketingCorrelation(42L, 501L, 9001L, 1L),
                null,
                null);
    }
}
