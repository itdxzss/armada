package com.armada.contact.task.service;

import com.armada.account.selection.model.SelectedAccount;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.UUID;

/**
 * 通讯录营销消息内容与协议无关发送命令的组装器。
 *
 * <p>竞品的通讯录消息只有两种形态且<b>没有按钮</b>（设计 §2.3）：
 * {@code message_type=0} 链接消息落 {@code LINK_CARD}；{@code message_type=1} 图文消息
 * 有图落 {@code IMAGE}（正文作 caption），无图退化为 {@code TEXT}。</p>
 *
 * <p>图片沿用 {@code marketing_template_file} 的既有字节存储，不新建表、不复制字节
 * （数据模型 §6.1 既有结论）。</p>
 */
@Component
public class ContactTaskMessageCommandFactory {

    /** 协议层识别通讯录任务命令的来源常量，逐字固定。 */
    public static final String SOURCE_CONTACT_TASK = "contact_task";

    /** 消息类型：链接消息。 */
    private static final int MESSAGE_TYPE_LINK = 0;

    private final MarketingTemplateFileMapper fileMapper;

    /**
     * 创建组装器。
     *
     * @param fileMapper 营销模板图片数据访问
     */
    public ContactTaskMessageCommandFactory(MarketingTemplateFileMapper fileMapper) {
        this.fileMapper = fileMapper;
    }

    /**
     * 从任务组合一条可发送内容。整轮只组一次，逐条复用。
     *
     * @param task 通讯录营销任务
     * @return 已组合内容
     */
    public ComposedContactMessage composeContent(ContactFriendTask task) {
        MarketingTemplateFile file = task.getPreviewImageFileId() == null
                ? null
                : fileMapper.selectById(task.getPreviewImageFileId());
        byte[] bytes = file == null ? null : file.getContent();
        String mimetype = file == null ? null : file.getContentType();
        boolean hasImage = bytes != null && bytes.length > 0;
        if (Integer.valueOf(MESSAGE_TYPE_LINK).equals(task.getMessageType())) {
            return new ComposedContactMessage(
                    MessageType.LINK_CARD,
                    task.getContent(),
                    hasImage ? bytes : null,
                    hasImage ? mimetype : null,
                    task.getPromotionLink(),
                    task.getTitle(),
                    task.getDescription());
        }
        return new ComposedContactMessage(
                hasImage ? MessageType.IMAGE : MessageType.TEXT,
                task.getContent(),
                hasImage ? bytes : null,
                hasImage ? mimetype : null,
                null,
                null,
                null);
    }

    /**
     * 组装单条协议无关发送命令。
     *
     * @param task 通讯录营销任务
     * @param accountRow 任务账号行
     * @param recipient 收件人明细；已抢批时其 commandId 会被复用
     * @param protocolFacts 账号协议事实
     * @param content 已组合内容
     * @param roundNo 本轮轮次号
     * @param notBeforeAt Armada 内部最早投递时间（epoch 毫秒），0 表示立即
     * @param random 随机源，用于逐条取发送间隔
     * @return 协议无关消息命令
     */
    public MessageSendCommand toCommand(ContactFriendTask task,
                                        ContactFriendTaskAccount accountRow,
                                        ContactFriendTaskRecipient recipient,
                                        SelectedAccount protocolFacts,
                                        ComposedContactMessage content,
                                        long roundNo,
                                        long notBeforeAt,
                                        Random random) {
        return new MessageSendCommand(
                new ProtocolAccountRef(
                        protocolFacts.accountId(),
                        ProtocolBackend.fromProtocolId(protocolFacts.protocolId()),
                        protocolFacts.protocolAccountId(),
                        protocolFacts.wsPhone()),
                new MessageSendCommand.MessageTarget(recipient.getContactJid()),
                payload(content),
                new MessageSendCommand.MessageCorrelation(
                        task.getTenantId(),
                        SOURCE_CONTACT_TASK,
                        null,
                        null,
                        null,
                        new MessageSendCommand.ContactTaskCorrelation(
                                task.getId(), accountRow.getId(), recipient.getId(), roundNo)),
                recipient.getCommandId() == null ? newCommandId() : recipient.getCommandId(),
                ContactSendIntervalPicker.pickMs(
                        task.getMsgIntervalMinSec(), task.getMsgIntervalMaxSec(), random),
                notBeforeAt);
    }

    /**
     * 生成与协议 outbox 共用的全局命令 ID，格式与营销侧一致。
     *
     * @return 以 {@code cmd_} 开头的命令 ID
     */
    public String newCommandId() {
        return "cmd_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static MessageSendCommand.MessagePayload payload(ComposedContactMessage content) {
        MessageSendCommand.MessageMedia image = content.imageBytes() == null
                ? null
                : new MessageSendCommand.MessageMedia(
                        content.imageBytes(), content.imageMimetype());
        MessageSendCommand.MessageLinkCard linkCard = content.type() == MessageType.LINK_CARD
                ? new MessageSendCommand.MessageLinkCard(
                        content.linkUrl(), content.linkTitle(), content.linkDescription(), image)
                : null;
        return new MessageSendCommand.MessagePayload(
                content.type(),
                new MessageSendCommand.MessageContent(
                        content.text(),
                        content.type() == MessageType.IMAGE ? image : null,
                        linkCard,
                        // 通讯录消息没有按钮，这一位永远是 null
                        null),
                // 私聊没有群成员，提醒所有人无意义
                false);
    }

    /**
     * 一条已组合好的通讯录消息内容。
     *
     * @param type 协议消息类型
     * @param text 正文或 caption
     * @param imageBytes 图片字节，无图为 null
     * @param imageMimetype 图片 MIME，无图为 null
     * @param linkUrl 推广链接，仅链接消息
     * @param linkTitle 卡片标题，仅链接消息
     * @param linkDescription 卡片描述，仅链接消息
     */
    public record ComposedContactMessage(
            MessageType type,
            String text,
            byte[] imageBytes,
            String imageMimetype,
            String linkUrl,
            String linkTitle,
            String linkDescription
    ) {
    }
}
