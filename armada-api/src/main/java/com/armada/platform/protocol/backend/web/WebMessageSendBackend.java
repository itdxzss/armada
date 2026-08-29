package com.armada.platform.protocol.backend.web;

import com.armada.platform.kafka.config.ProtocolMasterCommandProperties;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolMessageOutboxCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.routing.MessageSendBackend;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Base64;
import java.util.List;

/**
 * Baileys Web 协议营销消息 backend。
 *
 * <p>该实现保持现有 master topic 和消息 wire 字段，不向 Web payload 泄漏 Android 的
 * {@code wsPhone}。</p>
 */
public final class WebMessageSendBackend implements MessageSendBackend {

    private final ProtocolCommandOutboxService outboxService;
    private final ProtocolMasterCommandProperties properties;

    public WebMessageSendBackend(
            ProtocolCommandOutboxService outboxService,
            ProtocolMasterCommandProperties properties) {
        this.outboxService = outboxService;
        this.properties = properties;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.WEB;
    }

    @Override
    public MessageSendEnqueueResult enqueue(List<MessageSendCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return new MessageSendEnqueueResult(List.of());
        }
        List<ProtocolMessageOutboxCommand> outboxCommands = commands.stream()
                .map(this::toOutboxCommand)
                .toList();
        outboxService.enqueueMessageCommands(outboxCommands);
        return new MessageSendEnqueueResult(commands.stream()
                .map(command -> MessageSendEnqueueItem.accepted(command.commandId()))
                .toList());
    }

    private ProtocolMessageOutboxCommand toOutboxCommand(MessageSendCommand command) {
        MessageSendCommand.MessageCorrelation correlation = command.correlation();
        MessageSendCommand.MarketingCorrelation marketing = correlation.marketing();
        MessageSendCommand.GroupCreationCorrelation groupCreation = correlation.groupCreation();
        MessageSendCommand.HistoricalGroupCorrelation historicalGroup = correlation.historicalGroup();
        MessageSendCommand.ContactTaskCorrelation contactTask = correlation.contactTask();
        MessageSendCommand.HyperlinkCorrelation hyperlink = correlation.hyperlink();
        MessageSendCommand.MessageContent content = command.payload().content();
        WebMessagePayload payload = new WebMessagePayload(
                correlation.tenantId(),
                marketing == null ? null : marketing.taskId(),
                marketing == null ? null : marketing.attemptId(),
                marketing == null ? null : marketing.targetId(),
                // 轮次号是普通营销和通讯录营销共用的 wire 字段，两种来源都要能填上
                marketing != null
                        ? marketing.roundNo()
                        : contactTask == null ? null : contactTask.roundNo(),
                command.account().armadaAccountId(),
                command.account().protocolAccountId(),
                command.target().jid(),
                command.target().kind().name(),
                command.target().kind() == MessageSendCommand.TargetKind.GROUP
                        ? command.target().jid() : null,
                command.payload().type().name(),
                content.text(),
                media(content.image()),
                linkCard(content.linkCard()),
                buttonCard(content.buttonCard()),
                command.payload().mentionAll(),
                correlation.source(),
                groupCreation == null ? null : groupCreation.taskId(),
                groupCreation == null ? null : groupCreation.itemId(),
                historicalGroup == null ? null : historicalGroup.executionId(),
                historicalGroup == null ? null : historicalGroup.memberId(),
                contactTask == null ? null : contactTask.taskId(),
                contactTask == null ? null : contactTask.taskAccountId(),
                contactTask == null ? null : contactTask.recipientId(),
                hyperlink == null ? null : hyperlink.taskId(),
                hyperlink == null ? null : hyperlink.recipientId());
        return new ProtocolMessageOutboxCommand(
                command,
                ProtocolBackend.WEB,
                properties.getTopic(),
                command.account().protocolAccountId(),
                payload);
    }

    private static WebMediaPayload media(MessageSendCommand.MessageMedia media) {
        if (media == null) {
            return null;
        }
        return new WebMediaPayload(
                Base64.getEncoder().encodeToString(media.bytes()),
                media.mimetype());
    }

    private static WebLinkCardPayload linkCard(MessageSendCommand.MessageLinkCard card) {
        if (card == null) {
            return null;
        }
        return new WebLinkCardPayload(
                card.url(), card.title(), card.description(), media(card.thumbnail()));
    }

    private static WebButtonCardPayload buttonCard(MessageSendCommand.MessageButtonCard card) {
        if (card == null) {
            return null;
        }
        List<WebButtonPayload> buttons = card.buttons() == null
                ? null
                : card.buttons().stream()
                        .map(button -> new WebButtonPayload(
                                button.type(), button.displayText(), button.value()))
                        .toList();
        return new WebButtonCardPayload(card.title(), card.footer(), buttons, media(card.thumbnail()));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record WebMessagePayload(
            Long tenantId,
            Long marketingTaskId,
            Long attemptId,
            Long targetId,
            Long roundNo,
            Long accountId,
            String protocolAccountId,
            String jid,
            String targetKind,
            String groupJid,
            String messageType,
            String text,
            WebMediaPayload image,
            WebLinkCardPayload linkCard,
            WebButtonCardPayload buttonCard,
            boolean mentionAll,
            String source,
            Long groupCreationTaskId,
            Long groupCreationItemId,
            Long historicalExecutionId,
            Long historicalMemberId,
            Long contactTaskId,
            Long taskAccountId,
            Long recipientId,
            Long hyperlinkTaskId,
            Long hyperlinkRecipientId
    ) {
    }

    private record WebMediaPayload(String base64, String mimetype) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record WebLinkCardPayload(
            String url,
            String title,
            String description,
            WebMediaPayload thumbnail
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record WebButtonCardPayload(
            String title,
            String footer,
            List<WebButtonPayload> buttons,
            WebMediaPayload thumbnail
    ) {
    }

    private record WebButtonPayload(String type, String displayText, String value) {
    }
}
