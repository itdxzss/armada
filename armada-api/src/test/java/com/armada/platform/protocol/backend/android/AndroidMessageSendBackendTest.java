package com.armada.platform.protocol.backend.android;

import com.armada.platform.kafka.config.ProtocolAndroidCommandProperties;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolMessageOutboxCommand;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.media.AndroidImageAsset;
import com.armada.platform.protocol.media.AndroidImageAssetStore;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AndroidMessageSendBackendTest {

    private final ProtocolCommandOutboxService outboxService = mock(ProtocolCommandOutboxService.class);
    private final ProtocolAndroidCommandProperties properties = new ProtocolAndroidCommandProperties();
    private final AndroidImageAssetStore assetStore = mock(AndroidImageAssetStore.class);
    private final AndroidMessageSendBackend backend =
            new AndroidMessageSendBackend(outboxService, properties, assetStore);
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
        properties.setMessageTopic("protocol.android.message.commands.custom");
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
        assertThat(outboxCommand.kafkaTopic()).isEqualTo("protocol.android.message.commands.custom");
        Map<String, Object> payload = objectMapper.convertValue(
                outboxCommand.payload(), new TypeReference<>() {
                });
        assertThat(payload)
                .containsEntry("protocolAccountId", "acc_android")
                .containsEntry("wsPhone", "919000000001")
                .containsEntry("marketingTaskId", 42L)
                .containsEntry("attemptId", 9001L)
                .containsEntry("targetId", 501L)
                .containsEntry("roundNo", 1L)
                .containsEntry("sendIntervalMs", 750)
                .doesNotContainKeys("dispatchPolicy", "notBeforeAt", "dispatchIntervalMs");
        @SuppressWarnings("unchecked")
        Map<String, Object> buttonCard = (Map<String, Object>) payload.get("buttonCard");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buttons = (List<Map<String, Object>>) buttonCard.get("buttons");
        assertThat(buttons).singleElement().satisfies(button -> assertThat(button)
                .containsEntry("type", "link")
                .containsEntry("displayText", "查看详情")
                .containsEntry("value", "https://example.com/promo"));
    }

    @Test
    void encodesOnlyHistoricalCorrelationForHistoricalGroupPull() {
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
                "cmd_historical_android",
                MessageSendCommand.DEFAULT_SEND_INTERVAL_MS,
                0L);
        when(outboxService.enqueueMessageCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        null, List.of("cmd_historical_android"), 1));

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
        MessageSendCommand command = hyperlinkCommand();
        when(outboxService.enqueueMessageCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
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
        // 协议层判 contact_task 时四字段缺一即丢弃，字段名必须逐字一致；
        // wsPhone 是 Android 独有字段，加 correlation 不能把它丢掉
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
                "cmd_contact_android",
                800,
                0L);
        when(outboxService.enqueueMessageCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        null, List.of("cmd_contact_android"), 1));

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
                .containsEntry("wsPhone", "919000000001");
    }

    @Test
    void writesImageReferenceWithoutBase64() {
        byte[] source = "source-image".getBytes();
        MessageSendCommand command = imageCommand("cmd_image", source);
        when(outboxService.enqueueMessageCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        null, List.of("cmd_image"), 1));

        backend.enqueue(List.of(command));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolMessageOutboxCommand>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueMessageCommands(captor.capture());
        Map<String, Object> payload = objectMapper.convertValue(
                captor.getValue().get(0).payload(), new TypeReference<>() {
                });
        @SuppressWarnings("unchecked")
        Map<String, Object> image = (Map<String, Object>) payload.get("image");
        @SuppressWarnings("unchecked")
        Map<String, Object> assetRef = (Map<String, Object>) image.get("assetRef");

        assertThat(image).doesNotContainKeys("base64", "mimetype");
        assertThat(assetRef)
                .containsEntry("sizeBytes", source.length)
                .containsEntry("mimetype", "image/png")
                .containsEntry("transformProfile", "marketing-image-v1");
        assertThat(assetRef.get("sha256").toString()).hasSize(64);
    }

    @Test
    void ensuresSameTemplateImageOnlyOnceForManyGroupsInOneBatch() {
        byte[] source = "shared-template-image".getBytes();
        MessageSendCommand first = imageCommand("cmd_1", source);
        MessageSendCommand second = imageCommand("cmd_2", source.clone());
        when(outboxService.enqueueMessageCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        null, List.of("cmd_1", "cmd_2"), 2));

        backend.enqueue(List.of(first, second));

        ArgumentCaptor<AndroidImageAsset> assets =
                ArgumentCaptor.forClass(AndroidImageAsset.class);
        verify(assetStore, times(1)).ensure(assets.capture());
        assertThat(assets.getValue().tenantId()).isEqualTo(7L);
        assertThat(assets.getValue().sourceBytes()).isSameAs(source);
    }

    @Test
    void encodesLinkAndButtonThumbnailsAsOneSharedAssetReference() {
        byte[] source = "shared-card-image".getBytes();
        MessageSendCommand link = linkCardCommand("cmd_link", source);
        MessageSendCommand button = buttonCardCommand("cmd_button", source);
        when(outboxService.enqueueMessageCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        null, List.of("cmd_link", "cmd_button"), 2));

        backend.enqueue(List.of(link, button));

        verify(assetStore, times(1)).ensure(any(AndroidImageAsset.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolMessageOutboxCommand>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueMessageCommands(captor.capture());
        List<Map<String, Object>> payloads = captor.getValue().stream()
                .map(value -> objectMapper.convertValue(
                        value.payload(), new TypeReference<Map<String, Object>>() {
                        }))
                .toList();
        assertThat(payloads.toString())
                .contains("assetRef")
                .doesNotContain("base64");
    }

    @Test
    void doesNotPersistOutboxWhenRedisCannotEnsureAsset() {
        MessageSendCommand command = imageCommand("cmd_image", "image".getBytes());
        doThrow(new IllegalStateException("redis unavailable"))
                .when(assetStore).ensure(any(AndroidImageAsset.class));

        assertThatThrownBy(() -> backend.enqueue(List.of(command)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis unavailable");

        verify(outboxService, never()).enqueueMessageCommands(anyList());
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
                commandId,
                750,
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

    private static MessageSendCommand imageCommand(String commandId, byte[] source) {
        return new MessageSendCommand(
                account(),
                new MessageSendCommand.MessageTarget("120363001@g.us"),
                new MessageSendCommand.MessagePayload(
                        MessageType.IMAGE,
                        new MessageSendCommand.MessageContent(
                                "caption",
                                new MessageSendCommand.MessageMedia(source, "image/png"),
                                null,
                                null),
                        false),
                correlation(),
                commandId,
                750,
                0L);
    }

    private static MessageSendCommand linkCardCommand(String commandId, byte[] source) {
        MessageSendCommand.MessageMedia thumbnail =
                new MessageSendCommand.MessageMedia(source, "image/png");
        return new MessageSendCommand(
                account(),
                new MessageSendCommand.MessageTarget("120363002@g.us"),
                new MessageSendCommand.MessagePayload(
                        MessageType.LINK_CARD,
                        new MessageSendCommand.MessageContent(
                                "body",
                                null,
                                new MessageSendCommand.MessageLinkCard(
                                        "https://example.com/card",
                                        "title",
                                        "description",
                                        thumbnail),
                                null),
                        false),
                correlation(),
                commandId,
                750,
                0L);
    }

    private static MessageSendCommand buttonCardCommand(String commandId, byte[] source) {
        MessageSendCommand.MessageMedia thumbnail =
                new MessageSendCommand.MessageMedia(source, "image/png");
        MessageSendCommand.MessageButtonCard buttonCard = new MessageSendCommand.MessageButtonCard(
                "title",
                "footer",
                List.of(button("link", "查看详情", "https://example.com/button")),
                thumbnail);
        return buttonCommand(commandId, buttonCard);
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
                commandId,
                750,
                2_500L);
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
                null,
                null,
                null);
    }
}
