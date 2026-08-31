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
     * @param jid WhatsApp 群或私聊 JID
     * @param kind 目标类型
     */
    public record MessageTarget(String jid, TargetKind kind, List<String> statusJidList) {
        public MessageTarget(String jid, TargetKind kind) {
            this(jid, kind, List.of());
        }

        /** Java 调用兼容：存量单参数构造均为群目标。 */
        public MessageTarget(String groupJid) {
            this(groupJid, TargetKind.GROUP, List.of());
        }

        /** Java 读取兼容：存量群营销断言仍可读取 groupJid。 */
        public String groupJid() {
            return kind == TargetKind.GROUP ? jid : null;
        }
    }

    /** 通用消息目标类型。 */
    public enum TargetKind {
        /** 群聊目标，继续透传兼容 groupJid。 */
        GROUP,
        /** 私聊目标。 */
        PRIVATE,
        /** WhatsApp Status 广播目标。 */
        STATUS
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
            MessageButtonCard buttonCard,
            String backgroundColor,
            String textColor
    ) {
        public MessageContent(String text, MessageMedia image, MessageLinkCard linkCard,
                MessageButtonCard buttonCard) {
            this(text, image, linkCard, buttonCard, null, null);
        }
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
     * @param contactTask 通讯录营销关联
     * @param hyperlink 超链任务唯一 recipient 关联
     */
    public record MessageCorrelation(
            Long tenantId,
            String source,
            MarketingCorrelation marketing,
            GroupCreationCorrelation groupCreation,
            HistoricalGroupCorrelation historicalGroup,
            ContactTaskCorrelation contactTask,
            FeedTaskCorrelation feedTask,
            HyperlinkCorrelation hyperlink
    ) {
        /** 上游 6 参构造兼容：不触碰上游既有调用点，contactTask 默认为空。 */
        public MessageCorrelation(Long tenantId, String source, MarketingCorrelation marketing,
                GroupCreationCorrelation groupCreation, HistoricalGroupCorrelation historicalGroup,
                HyperlinkCorrelation hyperlink) {
            this(tenantId, source, marketing, groupCreation, historicalGroup, null, null, hyperlink);
        }

        /** 存量群营销 Java 构造兼容，contactTask 与 hyperlink 默认为空。 */
        public MessageCorrelation(Long tenantId, String source, MarketingCorrelation marketing,
                GroupCreationCorrelation groupCreation, HistoricalGroupCorrelation historicalGroup,
                ContactTaskCorrelation contactTask, HyperlinkCorrelation hyperlink) {
            this(tenantId, source, marketing, groupCreation, historicalGroup, contactTask, null, hyperlink);
        }

        public MessageCorrelation(Long tenantId, String source, MarketingCorrelation marketing,
                GroupCreationCorrelation groupCreation, HistoricalGroupCorrelation historicalGroup) {
            this(tenantId, source, marketing, groupCreation, historicalGroup, null, null, null);
        }
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

    /**
     * 通讯录营销任务关联信息。
     *
     * <p>四个字段是协议层的硬契约：{@code source='contact_task'} 时缺任一，
     * 协议层判 {@code invalid message send payload} 直接丢弃。wire 名分别是
     * {@code contactTaskId} / {@code taskAccountId} / {@code recipientId} / {@code roundNo}。</p>
     *
     * @param taskId 通讯录营销任务 ID
     * @param taskAccountId 任务账号行 ID
     * @param recipientId 收件人明细 ID
     * @param roundNo 轮次号
     */
    public record ContactTaskCorrelation(
            Long taskId,
            Long taskAccountId,
            Long recipientId,
            Long roundNo
    ) {
    }

    /** WhatsApp Status 动态发布任务关联。 */
    public record FeedTaskCorrelation(Long taskId, Long taskAccountId, Long roundNo) {
    }

    /** 超链任务唯一发送事实关联，不包含 attempt。 */
    public record HyperlinkCorrelation(Long taskId, Long recipientId) {
    }
}
