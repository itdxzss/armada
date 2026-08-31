package com.armada.feed.task.service;

import com.armada.account.selection.model.SelectedAccount;
import com.armada.feed.task.model.entity.FeedTask;
import com.armada.feed.task.model.entity.FeedTaskAccount;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** 动态发布任务的协议无关状态命令组装器。 */
@Component
public class FeedTaskMessageCommandFactory {

    public static final String SOURCE_FEED_TASK = "feed_task";
    private static final String STATUS_BROADCAST_JID = "status@broadcast";

    private final MarketingTemplateFileMapper fileMapper;

    public FeedTaskMessageCommandFactory(MarketingTemplateFileMapper fileMapper) {
        this.fileMapper = fileMapper;
    }

    /** 从任务组合一条 Status 内容。 */
    public ComposedFeedStatus composeContent(FeedTask task) {
        MarketingTemplateFile file = task.getLinkPreviewImageFileId() == null
                ? null : fileMapper.selectById(task.getLinkPreviewImageFileId());
        byte[] imageBytes = file == null ? null : file.getContent();
        String mimetype = file == null ? null : file.getContentType();
        return new ComposedFeedStatus(
                statusText(task),
                imageBytes == null || imageBytes.length == 0 ? null : imageBytes,
                imageBytes == null || imageBytes.length == 0 ? null : mimetype);
    }

    /** 组装 status.publish.requested 的统一消息命令。 */
    public MessageSendCommand toCommand(FeedTask task,
                                        FeedTaskAccount accountRow,
                                        SelectedAccount protocolFact,
                                        ComposedFeedStatus content,
                                        List<String> statusJidList,
                                        long roundNo,
                                        String commandId) {
        MessageSendCommand.MessageMedia image = content.imageBytes() == null
                ? null
                : new MessageSendCommand.MessageMedia(content.imageBytes(), content.imageMimetype());
        return new MessageSendCommand(
                new ProtocolAccountRef(
                        protocolFact.accountId(),
                        ProtocolBackend.fromProtocolId(protocolFact.protocolId()),
                        protocolFact.protocolAccountId(),
                        protocolFact.wsPhone()),
                new MessageSendCommand.MessageTarget(
                        STATUS_BROADCAST_JID, MessageSendCommand.TargetKind.STATUS, statusJidList),
                new MessageSendCommand.MessagePayload(
                        MessageType.STATUS,
                        new MessageSendCommand.MessageContent(
                                content.text(), image, null, null,
                                task.getBackgroundColor(), task.getTextColor()),
                        false),
                new MessageSendCommand.MessageCorrelation(
                        task.getTenantId(), SOURCE_FEED_TASK,
                        null, null, null, null,
                        new MessageSendCommand.FeedTaskCorrelation(
                                task.getId(), accountRow.getId(), roundNo),
                        null),
                commandId,
                MessageSendCommand.DEFAULT_SEND_INTERVAL_MS,
                0L);
    }

    /** 生成全局命令 ID。 */
    public String newCommandId() {
        return "cmd_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String statusText(FeedTask task) {
        return Stream.of(task.getTitle(), task.getDescription(), task.getContent(), task.getPromotionLink())
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .reduce((left, right) -> left + "\n" + right)
                .orElse(task.getContent());
    }

    /** 已组合好的 Status 内容。 */
    public record ComposedFeedStatus(String text, byte[] imageBytes, String imageMimetype) {
    }
}
