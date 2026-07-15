package com.armada.platform.protocol.backend.android;

import com.armada.platform.kafka.config.ProtocolAndroidCommandProperties;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolMessageOutboxCommand;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.routing.MessageSendBackend;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.util.HttpUrlValidator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android Zhuan 协议营销消息 backend。
 *
 * <p>协议差异和能力校验只存在于该 adapter。Android 按钮卡片必须恰好包含一个有效
 * HTTP(S) 跳转按钮，非法命令在 Armada 本地拒绝且不写 Android outbox。</p>
 */
public final class AndroidMessageSendBackend implements MessageSendBackend {

    private static final String INVALID_BUTTON_CONFIG = "INVALID_ANDROID_BUTTON_CONFIG";

    private final ProtocolCommandOutboxService outboxService;
    private final ProtocolAndroidCommandProperties properties;

    public AndroidMessageSendBackend(
            ProtocolCommandOutboxService outboxService,
            ProtocolAndroidCommandProperties properties) {
        this.outboxService = outboxService;
        this.properties = properties;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.ANDROID;
    }

    @Override
    public MessageSendEnqueueResult enqueue(List<MessageSendCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return new MessageSendEnqueueResult(List.of());
        }
        List<ProtocolMessageOutboxCommand> acceptedCommands = new ArrayList<>(commands.size());
        Map<String, MessageSendEnqueueItem> results = new HashMap<>(commands.size());
        for (MessageSendCommand command : commands) {
            MessageSendEnqueueItem validation = validateButtonCard(command);
            if (validation != null) {
                results.put(command.commandId(), validation);
                continue;
            }
            acceptedCommands.add(toOutboxCommand(command));
            results.put(command.commandId(), MessageSendEnqueueItem.accepted(command.commandId()));
        }
        if (!acceptedCommands.isEmpty()) {
            outboxService.enqueueMessageCommands(acceptedCommands);
        }
        return new MessageSendEnqueueResult(commands.stream()
                .map(command -> results.get(command.commandId()))
                .toList());
    }

    private static MessageSendEnqueueItem validateButtonCard(MessageSendCommand command) {
        if (command.payload().type() != MessageType.BUTTON_CARD) {
            return null;
        }
        MessageSendCommand.MessageButtonCard card = command.payload().content().buttonCard();
        if (card == null || card.buttons() == null || card.buttons().size() != 1) {
            return rejected(command, "按钮数量只支持 1 个");
        }
        MessageSendCommand.MessageButton button = card.buttons().get(0);
        if (button == null || !"link".equalsIgnoreCase(button.type())) {
            return rejected(command, "只支持跳转链接按钮");
        }
        if (button.displayText() == null || button.displayText().isBlank()) {
            return rejected(command, "按钮显示文字不能为空");
        }
        if (!HttpUrlValidator.isHttpUrl(button.value())) {
            return rejected(command, "只接受有效的 HTTP(S) 跳转链接");
        }
        return null;
    }

    private static MessageSendEnqueueItem rejected(MessageSendCommand command, String message) {
        return MessageSendEnqueueItem.rejected(command.commandId(), INVALID_BUTTON_CONFIG, message);
    }

    private ProtocolMessageOutboxCommand toOutboxCommand(MessageSendCommand command) {
        MessageSendCommand.MessageCorrelation correlation = command.correlation();
        MessageSendCommand.MarketingCorrelation marketing = correlation.marketing();
        MessageSendCommand.GroupCreationCorrelation groupCreation = correlation.groupCreation();
        MessageSendCommand.MessageContent content = command.payload().content();
        AndroidMessagePayload payload = new AndroidMessagePayload(
                correlation.tenantId(),
                command.account().armadaAccountId(),
                command.account().protocolAccountId(),
                command.account().wsPhone(),
                command.target().groupJid(),
                command.payload().type().name(),
                content.text(),
                media(content.image()),
                linkCard(content.linkCard()),
                buttonCard(content.buttonCard()),
                command.payload().mentionAll(),
                correlation.source(),
                marketing == null ? null : marketing.taskId(),
                marketing == null ? null : marketing.targetId(),
                marketing == null ? null : marketing.attemptId(),
                marketing == null ? null : marketing.roundNo(),
                groupCreation == null ? null : groupCreation.taskId(),
                groupCreation == null ? null : groupCreation.itemId());
        return new ProtocolMessageOutboxCommand(
                command,
                ProtocolBackend.ANDROID,
                properties.getTopic(),
                command.account().protocolAccountId(),
                payload);
    }

    private static AndroidMediaPayload media(MessageSendCommand.MessageMedia media) {
        if (media == null) {
            return null;
        }
        return new AndroidMediaPayload(
                Base64.getEncoder().encodeToString(media.bytes()),
                media.mimetype());
    }

    private static AndroidLinkCardPayload linkCard(MessageSendCommand.MessageLinkCard card) {
        if (card == null) {
            return null;
        }
        return new AndroidLinkCardPayload(
                card.url(), card.title(), card.description(), media(card.thumbnail()));
    }

    private static AndroidButtonCardPayload buttonCard(MessageSendCommand.MessageButtonCard card) {
        if (card == null) {
            return null;
        }
        List<AndroidButtonPayload> buttons = card.buttons() == null
                ? null
                : card.buttons().stream()
                        .map(button -> new AndroidButtonPayload(
                                button.type(), button.displayText(), button.value()))
                        .toList();
        return new AndroidButtonCardPayload(card.title(), card.footer(), buttons, media(card.thumbnail()));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record AndroidMessagePayload(
            Long tenantId,
            Long accountId,
            String protocolAccountId,
            String wsPhone,
            String groupJid,
            String messageType,
            String text,
            AndroidMediaPayload image,
            AndroidLinkCardPayload linkCard,
            AndroidButtonCardPayload buttonCard,
            boolean mentionAll,
            String source,
            Long marketingTaskId,
            Long targetId,
            Long attemptId,
            Long roundNo,
            Long groupCreationTaskId,
            Long groupCreationItemId
    ) {
    }

    private record AndroidMediaPayload(String base64, String mimetype) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record AndroidLinkCardPayload(
            String url,
            String title,
            String description,
            AndroidMediaPayload thumbnail
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record AndroidButtonCardPayload(
            String title,
            String footer,
            List<AndroidButtonPayload> buttons,
            AndroidMediaPayload thumbnail
    ) {
    }

    private record AndroidButtonPayload(String type, String displayText, String value) {
    }
}
