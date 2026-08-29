package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountUsage;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskContent;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.template.model.HyperlinkButton;
import com.armada.marketing.model.vo.MarketingTemplateFileContent;
import com.armada.marketing.service.MarketingTemplateFileService;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;

/** 把冻结内容映射到既有 messageType/text/image/linkCard/buttonCard wire。 */
@Component
public class HyperlinkMessageCommandFactory {
    private final MarketingTemplateFileService fileService;
    private final ObjectMapper objectMapper;
    private final HyperlinkShortLinkGuard shortLinkGuard;

    public HyperlinkMessageCommandFactory(MarketingTemplateFileService fileService,
            ObjectMapper objectMapper, HyperlinkShortLinkGuard shortLinkGuard) {
        this.fileService = fileService;
        this.objectMapper = objectMapper;
        this.shortLinkGuard = shortLinkGuard;
    }

    public MessageSendCommand create(HyperlinkTask task, HyperlinkTaskContent content,
            HyperlinkTaskRecipient recipient, HyperlinkTaskAccountUsage usage, long notBeforeAt) {
        HyperlinkMessageDeliveryGuard.requireSupported(content);
        String commandId = commandId(task.getTenantId(), task.getId(), recipient.getId());
        MessageSendCommand.MessagePayload payload = switch (content.getMessageType()) {
            case 1 -> linkCardPayload(task, content, recipient);
            case 3, 4 -> buttonPayload(content, recipient);
            default -> throw new IllegalStateException("发送门禁未拦截未知超链消息类型");
        };
        return new MessageSendCommand(
                new ProtocolAccountRef(usage.getAccountId(), backend(usage.getProtocolBackend()),
                        usage.getProtocolAccountIdSnapshot(), usage.getAccountPhoneSnapshot()),
                new MessageSendCommand.MessageTarget(privateJid(recipient.getRecipientPhoneSnapshot()),
                        MessageSendCommand.TargetKind.PRIVATE),
                payload,
                new MessageSendCommand.MessageCorrelation(task.getTenantId(), "hyperlink_task",
                        null, null, null,
                        new MessageSendCommand.HyperlinkCorrelation(task.getId(), recipient.getId())),
                commandId,
                Math.max(0, task.getMsgIntervalMinMs()),
                notBeforeAt);
    }

    public String commandId(long tenantId, long taskId, long recipientId) {
        return "hl:" + tenantId + ":" + taskId + ":" + recipientId;
    }

    private MessageSendCommand.MessagePayload linkCardPayload(HyperlinkTask task,
            HyperlinkTaskContent content, HyperlinkTaskRecipient recipient) {
        String targetUrl = Boolean.TRUE.equals(task.getShortLinkEnabled())
                ? shortLinkGuard.publicUrl(recipient.getShortCode()) : content.getPromotionLink();
        return new MessageSendCommand.MessagePayload(MessageType.LINK_CARD,
                new MessageSendCommand.MessageContent(content.getContent(), null,
                        new MessageSendCommand.MessageLinkCard(targetUrl,
                                content.getTitle(), content.getLinkDescription(),
                                media(content.getLinkPreviewAssetId())), null), false);
    }

    private MessageSendCommand.MessagePayload buttonPayload(HyperlinkTaskContent content,
            HyperlinkTaskRecipient recipient) {
        HyperlinkButton button = buttons(content.getButtons()).get(0);
        String targetUrl = Boolean.TRUE.equals(button.useShortLink())
                ? shortLinkGuard.publicUrl(recipient.getShortCode()) : button.targetValue();
        MessageSendCommand.MessageButtonCard card = new MessageSendCommand.MessageButtonCard(
                content.getTitle(), content.getMessageType() == 4 ? content.getCardText() : null,
                List.of(new MessageSendCommand.MessageButton(
                        "link", button.displayText(), targetUrl)),
                media(content.getBodyMainAssetId()));
        return new MessageSendCommand.MessagePayload(MessageType.BUTTON_CARD,
                new MessageSendCommand.MessageContent(content.getContent(), null, null, card), false);
    }

    private List<HyperlinkButton> buttons(String json) {
        try {
            List<HyperlinkButton> buttons = objectMapper.readValue(json, new TypeReference<>() { });
            if (buttons.size() != 1) { throw new IllegalStateException("冻结按钮数量不是 1"); }
            return buttons;
        } catch (IOException exception) {
            throw new IllegalStateException("冻结按钮 JSON 无法解析", exception);
        }
    }

    private MessageSendCommand.MessageMedia media(Long assetId) {
        if (assetId == null) { return null; }
        MarketingTemplateFileContent file = fileService.content(assetId);
        return new MessageSendCommand.MessageMedia(file.content(), file.contentType());
    }

    private ProtocolBackend backend(Integer value) {
        return Integer.valueOf(2).equals(value) ? ProtocolBackend.ANDROID : ProtocolBackend.WEB;
    }

    private String privateJid(String phone) {
        return phone.contains("@") ? phone : phone + "@s.whatsapp.net";
    }

}
