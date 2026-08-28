package com.armada.platform.protocol.model.command;

import com.armada.platform.protocol.model.enums.MessageType;

import java.util.List;

/**
 * 协议无关的营销消息发送命令。
 *
 * @param account 发送账号当前协议事实
 * @param target 消息目标
 * @param payload 消息内容
 * @param correlation 营销业务关联信息
 * @param commandId 全局唯一命令 ID
 * @param sendIntervalMs 同账号相邻群消息完成后的发送间隔（毫秒）
 * @param notBeforeAt Armada 内部最早投递时间（epoch 毫秒），不进入协议 payload；0 表示立即
 */
public record MessageSendCommand(
        ProtocolAccountRef account,
        MessageTarget target,
        MessagePayload payload,
        MessageCorrelation correlation,
        String commandId,
        int sendIntervalMs,
        long notBeforeAt
) {

    /** 未显式配置时使用的同账号群消息发送间隔。 */
    public static final int DEFAULT_SEND_INTERVAL_MS = 500;

    /**
     * 消息发送目标。
     *
     * <p>语义中立：群营销填群 JID（{@code @g.us}），私聊营销填用户 JID
     * （{@code <phone>@s.whatsapp.net}）。协议后端按 JID 后缀自行分支，不再假定目标一定是群。</p>
     *
     * @param jid WhatsApp 目标 JID
     */
    public record MessageTarget(String jid) {
    }

    /**
     * 消息类型、内容和提醒选项。
     *
     * @param type 消息类型
     * @param content 消息内容
     * @param mentionAll 是否提醒所有群成员
     */
    public record MessagePayload(MessageType type, MessageContent content, boolean mentionAll) {
    }

    /**
     * 五种消息共用的内容容器，未使用的内容字段为空。
     *
     * @param text 文本或卡片正文
     * @param image 图片内容
     * @param linkCard 链接卡片
     * @param buttonCard 按钮卡片
     */
    public record MessageContent(
            String text,
            MessageMedia image,
            MessageLinkCard linkCard,
            MessageButtonCard buttonCard
    ) {
    }

    /**
     * 媒体内容。
     *
     * @param bytes 原始字节
     * @param mimetype MIME 类型
     */
    public record MessageMedia(byte[] bytes, String mimetype) {
    }

    /**
     * 链接卡片内容。
     *
     * @param url 跳转链接
     * @param title 标题
     * @param description 描述
     * @param thumbnail 缩略图
     */
    public record MessageLinkCard(
            String url,
            String title,
            String description,
            MessageMedia thumbnail
    ) {
    }

    /**
     * 按钮卡片内容。
     *
     * @param title 标题
     * @param footer 页脚
     * @param buttons 按钮列表
     * @param thumbnail 缩略图
     */
    public record MessageButtonCard(
            String title,
            String footer,
            List<MessageButton> buttons,
            MessageMedia thumbnail
    ) {
    }

    /**
     * 单个消息按钮。
     *
     * @param type 按钮类型
     * @param displayText 展示文案
     * @param value 按钮值
     */
    public record MessageButton(String type, String displayText, String value) {
    }

    /**
     * 消息与营销任务的关联信息。
     *
     * @param tenantId 租户 ID
     * @param source 命令来源
     * @param marketing 普通营销关联
     * @param groupCreation 建群营销关联
     * @param historicalGroup 历史群拉人营销关联
     */
    public record MessageCorrelation(
            Long tenantId,
            String source,
            MarketingCorrelation marketing,
            GroupCreationCorrelation groupCreation,
            HistoricalGroupCorrelation historicalGroup
    ) {
    }

    /**
     * 普通营销任务关联信息。
     *
     * @param taskId 营销任务 ID
     * @param targetId 目标 ID
     * @param attemptId 发送尝试 ID
     * @param roundNo 轮次号
     */
    public record MarketingCorrelation(Long taskId, Long targetId, Long attemptId, Long roundNo) {
    }

    /**
     * 建群营销任务关联信息。
     *
     * @param taskId 建群营销任务 ID
     * @param itemId 建群营销执行项 ID
     */
    public record GroupCreationCorrelation(Long taskId, Long itemId) {
    }

    /**
     * 历史群拉人营销结果关联。
     *
     * @param executionId 历史群单群执行 ID
     * @param memberId 本次发送账号对应的执行成员 ID
     */
    public record HistoricalGroupCorrelation(Long executionId, Long memberId) {
    }
}
