package com.armada.platform.protocol.backend.android;

import com.armada.platform.kafka.config.ProtocolAndroidCommandProperties;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolMessageOutboxCommand;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AndroidMessageSendBackendTest {

    private final ProtocolCommandOutboxService outboxService = mock(ProtocolCommandOutboxService.class);
    private final ProtocolAndroidCommandProperties properties = new ProtocolAndroidCommandProperties();
    private final AndroidMessageSendBackend backend = new AndroidMessageSendBackend(outboxService, properties);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @MethodSource("invalidButtonCards")
    void rejectsInvalidAndroidButtonCards(
            MessageSendCommand.MessageButtonCard card,
            String expectedMessage) {
        MessageSendCommand command = buttonCommand("cmd_bad", card);

        MessageSendEnqueueResult result = backend.enqueue(List.of(command));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.accepted()).isFalse();
            assertThat(item.reasonCode()).isEqualTo("INVALID_ANDROID_BUTTON_CONFIG");
            assertThat(item.reasonMessage()).contains(expectedMessage);
        });
        verify(outboxService, never()).enqueueMessageCommands(anyList());
    }

    @Test
    void writesOnlyValidCommandsFromMixedBatch() {
        MessageSendCommand invalid = buttonCommand(
                "cmd_bad",
                card(List.of(
                        button("link", "A", "https://example.com/a"),
                        button("link", "B", "https://example.com/b"))));
        MessageSendCommand text = textCommand("cmd_text");
        when(outboxService.enqueueMessageCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd_text"), 1));

        MessageSendEnqueueResult result = backend.enqueue(List.of(invalid, text));

        assertThat(result.items()).extracting(item -> item.commandId())
                .containsExactly("cmd_bad", "cmd_text");
        assertThat(result.items()).extracting(item -> item.accepted())
                .containsExactly(false, true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolMessageOutboxCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueMessageCommands(captor.capture());
        assertThat(captor.getValue()).extracting(item -> item.command().commandId())
                .containsExactly("cmd_text");
    }

    @Test
    void writesValidSingleLinkButtonToAndroidTopicWithExplicitPhone() {
        properties.setTopic("protocol.android.commands.custom");
        MessageSendCommand command = buttonCommand(
                "cmd_button",
                card(List.of(button("link", "查看详情", "https://example.com/promo"))));
        when(outboxService.enqueueMessageCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd_button"), 1));

        MessageSendEnqueueResult result = backend.enqueue(List.of(command));

        assertThat(result.items()).singleElement().satisfies(item -> assertThat(item.accepted()).isTrue());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolMessageOutboxCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueMessageCommands(captor.capture());
        ProtocolMessageOutboxCommand outboxCommand = captor.getValue().get(0);
        assertThat(outboxCommand.backend()).isEqualTo(ProtocolBackend.ANDROID);
        assertThat(outboxCommand.kafkaTopic()).isEqualTo("protocol.android.commands.custom");
        Map<String, Object> payload = objectMapper.convertValue(
                outboxCommand.payload(), new TypeReference<>() {
                });
        assertThat(payload)
                .containsEntry("protocolAccountId", "acc_android")
                .containsEntry("wsPhone", "919000000001")
                .containsEntry("marketingTaskId", 42L)
                .containsEntry("attemptId", 9001L)
                .containsEntry("targetId", 501L)
                .containsEntry("roundNo", 1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> buttonCard = (Map<String, Object>) payload.get("buttonCard");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buttons = (List<Map<String, Object>>) buttonCard.get("buttons");
        assertThat(buttons).singleElement().satisfies(button -> assertThat(button)
                .containsEntry("type", "link")
                .containsEntry("displayText", "查看详情")
                .containsEntry("value", "https://example.com/promo"));
    }

    private static Stream<Arguments> invalidButtonCards() {
        return Stream.of(
                Arguments.of(card(List.of()), "数量只支持 1 个"),
                Arguments.of(card(List.of(
                        button("link", "A", "https://example.com/a"),
                        button("link", "B", "https://example.com/b"))), "数量只支持 1 个"),
                Arguments.of(card(List.of(button("copy", "复制", "CODE"))), "只支持跳转链接按钮"),
                Arguments.of(card(List.of(button("quick", "确认", "yes"))), "只支持跳转链接按钮"),
                Arguments.of(card(List.of(button("link", " ", "https://example.com"))), "显示文字不能为空"),
                Arguments.of(card(List.of(button("link", "访问", "ftp://example.com"))), "HTTP(S)"),
                Arguments.of(card(List.of(button("link", "访问", "/relative"))), "HTTP(S)"));
    }

    private static MessageSendCommand textCommand(String commandId) {
        return new MessageSendCommand(
                account(),
                new MessageSendCommand.MessageTarget("120363001@g.us"),
                new MessageSendCommand.MessagePayload(
                        MessageType.TEXT,
                        new MessageSendCommand.MessageContent("hello", null, null, null),
                        false),
                correlation(),
                commandId);
    }

    private static MessageSendCommand buttonCommand(
            String commandId,
            MessageSendCommand.MessageButtonCard card) {
        return new MessageSendCommand(
                account(),
                new MessageSendCommand.MessageTarget("120363001@g.us"),
                new MessageSendCommand.MessagePayload(
                        MessageType.BUTTON_CARD,
                        new MessageSendCommand.MessageContent("body", null, null, card),
                        true),
                correlation(),
                commandId);
    }

    private static MessageSendCommand.MessageButtonCard card(List<MessageSendCommand.MessageButton> buttons) {
        return new MessageSendCommand.MessageButtonCard("title", "footer", buttons, null);
    }

    private static MessageSendCommand.MessageButton button(String type, String text, String value) {
        return new MessageSendCommand.MessageButton(type, text, value);
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(
                100L, ProtocolBackend.ANDROID, "acc_android", "919000000001");
    }

    private static MessageSendCommand.MessageCorrelation correlation() {
        return new MessageSendCommand.MessageCorrelation(
                7L,
                "marketing_task",
                new MessageSendCommand.MarketingCorrelation(42L, 501L, 9001L, 1L),
                null);
    }
}
