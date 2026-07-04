package com.armada.platform.protocol.model.command;

/**
 * 营销任务向协议层发送消息的 outbox 命令请求。
 *
 * <p>一条 request 对应一行 {@code marketing_task_send_attempt}。API 侧先写 attempt,
 * 再用同一个 {@code commandId} 写协议 outbox,方便后续排查 Kafka 投递和协议发送结果。</p>
 *
 * @param tenantId        租户 ID
 * @param marketingTaskId 营销任务 ID
 * @param attemptId       本次发送尝试 ID
 * @param targetId        任务目标 ID
 * @param roundNo         营销轮次号,从 1 开始
 * @param accountId       Armada 账号 ID
 * @param protocolAccountId 协议层账号句柄,用于 master 路由到 owner worker
 * @param groupJid        WhatsApp 群 JID
 * @param messageType     协议消息类型,当前支持 TEXT/LINK/IMAGE
 * @param text            文本正文;图片消息时作为 caption
 * @param imageBase64     图片内容 base64;仅 IMAGE 使用
 * @param imageMimetype   图片 MIME 类型;仅 IMAGE 使用
 * @param source          命令来源,默认 marketing_task
 * @param commandId       预生成协议命令 ID;为空时 outbox service 会生成
 */
public record ProtocolMarketingMessageCommandRequest(
        Long tenantId,
        Long marketingTaskId,
        Long attemptId,
        Long targetId,
        Long roundNo,
        Long accountId,
        String protocolAccountId,
        String groupJid,
        String messageType,
        String text,
        String imageBase64,
        String imageMimetype,
        String source,
        String commandId
) {
    /**
     * 兼容旧调用方的构造器。没有预生成 commandId 时,由 outbox service 在入库前生成。
     */
    public ProtocolMarketingMessageCommandRequest(
            Long tenantId,
            Long marketingTaskId,
            Long attemptId,
            Long targetId,
            Long roundNo,
            Long accountId,
            String protocolAccountId,
            String groupJid,
            String messageType,
            String text,
            String imageBase64,
            String imageMimetype,
            String source) {
        this(tenantId, marketingTaskId, attemptId, targetId, roundNo, accountId,
                protocolAccountId, groupJid, messageType, text, imageBase64, imageMimetype,
                source, null);
    }
}
