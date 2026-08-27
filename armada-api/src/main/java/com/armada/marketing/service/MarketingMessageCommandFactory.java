package com.armada.marketing.service;

import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.marketing.model.enums.MarketingBusinessType;
import com.armada.marketing.model.support.MarketingResolvedTarget;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeAccess;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 普通营销任务消息内容与协议无关发送命令的共享组装器。
 */
@Component
public class MarketingMessageCommandFactory {
    private static final String SOURCE_MARKETING_TASK = "marketing_task";
    private static final String SOURCE_GROUP_PULL_MARKETING = "group_pull_marketing";
    private static final int DEFAULT_ACCOUNT_GROUP_SEND_INTERVAL_MS = 500;

    private final MarketingTemplateMapper templateMapper;
    private final MarketingTemplateFileMapper fileMapper;
    private final MarketingMessageComposer composer;

    /**
     * 创建共享命令组装器。
     *
     * @param templateMapper 营销模板 mapper
     * @param fileMapper     营销模板文件 mapper
     * @param composer       营销内容组合器
     */
    public MarketingMessageCommandFactory(MarketingTemplateMapper templateMapper,
                                          MarketingTemplateFileMapper fileMapper,
                                          MarketingMessageComposer composer) {
        this.templateMapper = templateMapper;
        this.fileMapper = fileMapper;
        this.composer = composer;
    }

    /**
     * 从任务当前模板组合一条可发送内容。
     *
     * @param task 普通营销任务
     * @return 已组合消息内容
     * @throws BusinessException 模板不存在或内容配置无效时抛出
     */
    public MarketingMessageComposer.ComposedMessage composeTaskMessage(MarketingTask task) {
        DataScope scope = DataScopeAccess.requireCurrent();
        MarketingTemplate template = templateMapper.selectByIdForScope(
                task.getMarketingTemplateId(), scope);
        if (template == null) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "营销模板不存在: " + task.getMarketingTemplateId());
        }
        DataScopeAccess.requireSameOwner(
                Arrays.asList(task.getOwnerUserId(), template.getOwnerUserId()),
                "营销任务与模板");
        MarketingTemplateFile image = template.getImageFileId() == null
                ? null
                : fileMapper.selectByIdForScope(template.getImageFileId(), scope);
        if (template.getImageFileId() != null && image == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "营销模板图片不存在");
        }
        if (image != null) {
            DataScopeAccess.requireSameOwner(
                    Arrays.asList(template.getOwnerUserId(), image.getOwnerUserId()),
                    "营销模板与图片");
        }
        return composer.compose(template, image);
    }

    /**
     * 把任务、实际群和 attempt 组装为协议无关消息命令。
     *
     * @param task        普通营销任务
     * @param resolved    实际群目标
     * @param attempt     已持久化发送尝试
     * @param message     已组合消息内容
     * @param notBeforeAt 最早发送时间(epoch 毫秒)
     * @return 协议无关消息命令
     */
    public MessageSendCommand toCommand(MarketingTask task,
                                        MarketingResolvedTarget resolved,
                                        MarketingTaskSendAttempt attempt,
                                        MarketingMessageComposer.ComposedMessage message,
                                        long notBeforeAt) {
        MarketingTaskTarget target = resolved.target();
        return new MessageSendCommand(
                accountRef(target),
                new MessageSendCommand.MessageTarget(resolved.groupJid()),
                payload(message),
                new MessageSendCommand.MessageCorrelation(
                        task.getTenantId(),
                        source(task),
                        new MessageSendCommand.MarketingCorrelation(
                                task.getId(), target.getId(), attempt.getId(), attempt.getRoundNo()),
                        null,
                        null),
                attempt.getCommandId(),
                accountGroupSendIntervalMs(task),
                notBeforeAt);
    }

    /**
     * 生成 attempt 与协议 outbox 共用的全局命令 ID。
     *
     * @return 以 {@code cmd_} 开头的命令 ID
     */
    public String newCommandId() {
        return "cmd_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 获取同一账号向多个群发送时的命令间隔。
     *
     * @param task 普通营销任务
     * @return 正数毫秒间隔，无效配置兜底为 500 毫秒
     */
    public int accountGroupSendIntervalMs(MarketingTask task) {
        if (task != null
                && Integer.valueOf(MarketingBusinessType.GROUP_PULL.code())
                        .equals(task.getBusinessType())) {
            return 0;
        }
        Integer configured = task.getAccountGroupSendIntervalMs();
        return configured == null || configured < 1
                ? DEFAULT_ACCOUNT_GROUP_SEND_INTERVAL_MS
                : configured;
    }

    private static String source(MarketingTask task) {
        return task != null
                && Integer.valueOf(MarketingBusinessType.GROUP_PULL.code())
                        .equals(task.getBusinessType())
                ? SOURCE_GROUP_PULL_MARKETING
                : SOURCE_MARKETING_TASK;
    }

    /**
     * 判断消息是否携带会放大 outbox 体积的图片内容。
     *
     * @param message 已组合消息内容
     * @return 图片消息或卡片缩略图存在时返回 true
     */
    public boolean hasLargeMediaPayload(MarketingMessageComposer.ComposedMessage message) {
        return "IMAGE".equals(message.messageType())
                || (message.linkCard() != null && message.linkCard().thumbnail() != null)
                || (message.buttonCard() != null && message.buttonCard().thumbnail() != null);
    }

    private static MessageSendCommand.MessagePayload payload(
            MarketingMessageComposer.ComposedMessage message) {
        return new MessageSendCommand.MessagePayload(
                MessageType.valueOf(message.messageType()),
                new MessageSendCommand.MessageContent(
                        message.text(),
                        mediaPayload(message.imageBytes(), message.imageMimetype()),
                        linkCardPayload(message.linkCard()),
                        buttonCardPayload(message.buttonCard())),
                message.mentionAll());
    }

    private static MessageSendCommand.MessageLinkCard linkCardPayload(
            MarketingMessageComposer.LinkCardPayload linkCard) {
        if (linkCard == null) {
            return null;
        }
        return new MessageSendCommand.MessageLinkCard(
                linkCard.url(),
                linkCard.title(),
                linkCard.description(),
                mediaPayload(linkCard.thumbnail()));
    }

    private static MessageSendCommand.MessageButtonCard buttonCardPayload(
            MarketingMessageComposer.ButtonCardPayload buttonCard) {
        if (buttonCard == null) {
            return null;
        }
        return new MessageSendCommand.MessageButtonCard(
                buttonCard.title(),
                buttonCard.footer(),
                buttonCard.buttons().stream()
                        .map(button -> new MessageSendCommand.MessageButton(
                                button.type(), button.displayText(), button.value()))
                        .toList(),
                mediaPayload(buttonCard.thumbnail()));
    }

    private static MessageSendCommand.MessageMedia mediaPayload(
            MarketingMessageComposer.MediaPayload media) {
        if (media == null || media.bytes() == null || media.bytes().length == 0) {
            return null;
        }
        return new MessageSendCommand.MessageMedia(media.bytes(), media.mimetype());
    }

    private static MessageSendCommand.MessageMedia mediaPayload(byte[] bytes, String mimetype) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return new MessageSendCommand.MessageMedia(bytes, mimetype);
    }

    private static ProtocolAccountRef accountRef(MarketingTaskTarget target) {
        if (!StringUtils.hasText(target.getProtocolAccountId())
                || !StringUtils.hasText(target.getProtocolWsPhone())) {
            throw new BusinessException(
                    ErrorCode.VALIDATION,
                    "营销目标缺少协议账号事实: targetId=" + target.getId());
        }
        return new ProtocolAccountRef(
                target.getAccountId(),
                ProtocolBackend.fromProtocolId(target.getProtocolId()),
                target.getProtocolAccountId(),
                target.getProtocolWsPhone());
    }
}
