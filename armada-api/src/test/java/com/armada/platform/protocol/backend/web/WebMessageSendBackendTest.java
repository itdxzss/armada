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
                        new MessageSendCommand.HistoricalGroupCorrelation(91L, 301L),
                        null),
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

    @Test
    void encodesFrozenHyperlinkPrivateWireContract() {
        WebMessageSendBackend backend = new WebMessageSendBackend(
                outboxService, new ProtocolMasterCommandProperties());
        MessageSendCommand command = hyperlinkCommand();
        when(outboxService.enqueueMessageCommands(anyList()))
                .thenReturn(new com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult(
                        null, List.of(command.commandId()), 1));

        backend.enqueue(List.of(command));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolMessageOutboxCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueMessageCommands(captor.capture());
        Map<String, Object> payload = objectMapper.convertValue(
                captor.getValue().get(0).payload(), new TypeReference<>() { });
        assertThat(payload)
                .containsEntry("source", "hyperlink_task")
                .containsEntry("jid", "8613800000000@s.whatsapp.net")
                .containsEntry("targetKind", "PRIVATE")
                .containsEntry("hyperlinkTaskId", 11L)
                .containsEntry("hyperlinkRecipientId", 13L)
                .containsEntry("messageType", "LINK_CARD")
                .containsEntry("text", "正文")
                .doesNotContainKeys("recipientId", "groupJid", "messageContent", "schemaVersion");
    }

    @Test
    void encodesContactTaskCorrelationFields() {
        // 协议层判 contact_task 时四字段缺一即丢弃，字段名必须逐字一致
        WebMessageSendBackend backend = new WebMessageSendBackend(
                outboxService, new ProtocolMasterCommandProperties());
        MessageSendCommand command = new MessageSendCommand(
                account(),
                new MessageSendCommand.MessageTarget(
                        "8613900000001@s.whatsapp.net",
                        MessageSendCommand.TargetKind.PRIVATE),
                new MessageSendCommand.MessagePayload(
                        MessageType.TEXT,
                        new MessageSendCommand.MessageContent("hi", null, null, null),
                        false),
                new MessageSendCommand.MessageCorrelation(
                        7L,
                        "contact_task",
                        null,
                        null,
                        null,
                        new MessageSendCommand.ContactTaskCorrelation(77L, 88L, 99L, 5L),
                        null),
                "cmd_contact_web",
                800,
                0L);
        when(outboxService.enqueueMessageCommands(anyList()))
                .thenReturn(new com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult(
                        null, List.of("cmd_contact_web"), 1));

        backend.enqueue(List.of(command));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolMessageOutboxCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueMessageCommands(captor.capture());
        Map<String, Object> payload = objectMapper.convertValue(
                captor.getValue().get(0).payload(), new TypeReference<>() {
                });
        assertThat(payload)
                .containsEntry("source", "contact_task")
                .containsEntry("contactTaskId", 77L)
                .containsEntry("taskAccountId", 88L)
                .containsEntry("recipientId", 99L)
                .containsEntry("roundNo", 5L)
                .containsEntry("jid", "8613900000001@s.whatsapp.net")
                .containsEntry("targetKind", "PRIVATE")
                .doesNotContainKey("groupJid")
                .doesNotContainKeys("marketingTaskId", "attemptId", "targetId");
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

    private static MessageSendCommand hyperlinkCommand() {
        return new MessageSendCommand(
                account(),
                new MessageSendCommand.MessageTarget(
                        "8613800000000@s.whatsapp.net", MessageSendCommand.TargetKind.PRIVATE),
                new MessageSendCommand.MessagePayload(
                        MessageType.LINK_CARD,
                        new MessageSendCommand.MessageContent("正文", null,
                                new MessageSendCommand.MessageLinkCard(
                                        "https://example.com", "标题", "描述", null), null),
                        false),
                new MessageSendCommand.MessageCorrelation(7L, "hyperlink_task",
                        null, null, null,
                        new MessageSendCommand.HyperlinkCorrelation(11L, 13L)),
                "hl:7:11:13", 500, 0L);
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
                null,
                null);
    }
}
