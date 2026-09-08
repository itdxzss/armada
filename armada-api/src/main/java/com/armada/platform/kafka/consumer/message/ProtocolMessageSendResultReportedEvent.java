package com.armada.platform.kafka.consumer.message;

/**
 * 协议层消息发送结果事件。
 *
 * <p>该事件由协议 worker 在执行 {@code message.send.requested} 后发布。成功事件带
 * WhatsApp {@code messageId};失败事件带 {@code reasonCode/reasonMessage}。API 侧用
 * {@code attemptId + commandId} 做幂等回写，并用 {@code commandId} 做跨层排查关联。</p>
 *
 * @param eventId         协议事件 ID
 * @param tenantId        租户 ID
 * @param marketingTaskId 营销任务 ID
 * @param targetId        营销任务目标 ID
 * @param attemptId       发送尝试 ID
 * @param roundNo         营销轮次号
 * @param protocolAccountId 协议层账号句柄
 * @param groupJid        WhatsApp 群 JID
 * @param commandId       协议 outbox 命令 ID
 * @param success         是否发送成功
 * @param messageId       WhatsApp message id;失败时为空
 * @param reasonCode      失败原因码;成功时为空
 * @param reasonMessage   失败原因描述;成功时为空
 * @param timestamp       协议层结果时间(epoch毫秒)
 * @param workerId        处理该命令的协议 worker
 * @param groupCreationTaskId 建群营销任务 ID;source=group_creation_marketing 时使用
 * @param groupCreationItemId 建群营销执行项 ID;source=group_creation_marketing 时使用
 * @param source          命令来源
 * @param groupStatus     发送时群状态快照
 * @param groupStatusReason 群状态判定原因
 * @param groupStatusCheckedAt 群状态判定时间(epoch毫秒)
 * @param historicalExecutionId 历史群单群执行 ID;source=historical_group_pull 时使用
 * @param historicalMemberId 历史群营销成员 ID;source=historical_group_pull 时使用
 * @param contactTaskId 通讯录营销任务 ID;source=contact_task 时使用
 * @param taskAccountId 通讯录任务账号行 ID;source=contact_task 时使用
 * @param recipientId 通讯录任务收件人 ID;source=contact_task 时使用
 * @param jid             通用目标 JID;群目标与 groupJid 同值
 * @param targetKind      目标类型 GROUP/PRIVATE
 * @param hyperlinkTaskId 超链任务 ID;source=hyperlink_task 时使用
 * @param hyperlinkRecipientId 超链任务收件人 ID;source=hyperlink_task 时使用
 * @param outcome         协议层判定结果
 * @param terminal        是否终态
 */
public record ProtocolMessageSendResultReportedEvent(
        String eventId,
        Long tenantId,
        Long marketingTaskId,
        Long targetId,
        Long attemptId,
        Long roundNo,
        String protocolAccountId,
        String groupJid,
        String commandId,
        boolean success,
        String messageId,
        String reasonCode,
        String reasonMessage,
        Long timestamp,
        String workerId,
        Long groupCreationTaskId,
        Long groupCreationItemId,
        String source,
        String groupStatus,
        String groupStatusReason,
        Long groupStatusCheckedAt,
        Long historicalExecutionId,
        Long historicalMemberId,
        Long contactTaskId,
        Long taskAccountId,
        Long recipientId,
        String jid,
        String targetKind,
        Long hyperlinkTaskId,
        Long hyperlinkRecipientId,
        Long feedTaskId,
        Long feedTaskAccountId,
        String outcome,
        Boolean terminal
) {
    /** 上游 29 参构造兼容：不触碰上游既有调用点，通讯录三字段默认为空。 */
    public ProtocolMessageSendResultReportedEvent(
            String eventId, Long tenantId, Long marketingTaskId, Long targetId, Long attemptId,
            Long roundNo, String protocolAccountId, String groupJid, String commandId,
            boolean success, String messageId, String reasonCode, String reasonMessage,
            Long timestamp, String workerId, Long groupCreationTaskId, Long groupCreationItemId,
            String source, String groupStatus, String groupStatusReason, Long groupStatusCheckedAt,
            Long historicalExecutionId, Long historicalMemberId, String jid, String targetKind,
            Long hyperlinkTaskId, Long hyperlinkRecipientId, String outcome, Boolean terminal) {
        this(eventId, tenantId, marketingTaskId, targetId, attemptId, roundNo, protocolAccountId,
                groupJid, commandId, success, messageId, reasonCode, reasonMessage, timestamp,
                workerId, groupCreationTaskId, groupCreationItemId, source, groupStatus,
                groupStatusReason, groupStatusCheckedAt, historicalExecutionId, historicalMemberId,
                null, null, null, jid, targetKind, hyperlinkTaskId, hyperlinkRecipientId,
                null, null, outcome, terminal);
    }

    /** 通讯录营销事件构造兼容：超链与 outcome 字段默认为空。 */
    public ProtocolMessageSendResultReportedEvent(
            String eventId, Long tenantId, Long marketingTaskId, Long targetId, Long attemptId,
            Long roundNo, String protocolAccountId, String groupJid, String commandId,
            boolean success, String messageId, String reasonCode, String reasonMessage,
            Long timestamp, String workerId, Long groupCreationTaskId, Long groupCreationItemId,
            String source, String groupStatus, String groupStatusReason, Long groupStatusCheckedAt,
            Long historicalExecutionId, Long historicalMemberId, Long contactTaskId, Long taskAccountId, Long recipientId) {
        this(eventId, tenantId, marketingTaskId, targetId, attemptId, roundNo, protocolAccountId,
                groupJid, commandId, success, messageId, reasonCode, reasonMessage, timestamp,
                workerId, groupCreationTaskId, groupCreationItemId, source, groupStatus,
                groupStatusReason, groupStatusCheckedAt, historicalExecutionId, historicalMemberId,
                contactTaskId, taskAccountId, recipientId, groupJid,
                groupJid == null ? null : "GROUP", null, null, null, null, null, null);
    }

    /** 已扩展通用 target/correlation、但尚无 outcome 的事件构造兼容。 */
    public ProtocolMessageSendResultReportedEvent(
            String eventId, Long tenantId, Long marketingTaskId, Long targetId, Long attemptId,
            Long roundNo, String protocolAccountId, String groupJid, String commandId,
            boolean success, String messageId, String reasonCode, String reasonMessage,
            Long timestamp, String workerId, Long groupCreationTaskId, Long groupCreationItemId,
            String source, String groupStatus, String groupStatusReason, Long groupStatusCheckedAt,
            Long historicalExecutionId, Long historicalMemberId, String jid, String targetKind,
            Long hyperlinkTaskId, Long hyperlinkRecipientId) {
        this(eventId, tenantId, marketingTaskId, targetId, attemptId, roundNo, protocolAccountId,
                groupJid, commandId, success, messageId, reasonCode, reasonMessage, timestamp,
                workerId, groupCreationTaskId, groupCreationItemId, source, groupStatus,
                groupStatusReason, groupStatusCheckedAt, historicalExecutionId, historicalMemberId,
                null, null, null, jid, targetKind, hyperlinkTaskId, hyperlinkRecipientId,
                null, null, null, null);
    }

    /** 存量群营销事件构造兼容；通用 target 与 hyperlink 关联默认为空。 */
    public ProtocolMessageSendResultReportedEvent(
            String eventId, Long tenantId, Long marketingTaskId, Long targetId, Long attemptId,
            Long roundNo, String protocolAccountId, String groupJid, String commandId,
            boolean success, String messageId, String reasonCode, String reasonMessage,
            Long timestamp, String workerId, Long groupCreationTaskId, Long groupCreationItemId,
            String source, String groupStatus, String groupStatusReason, Long groupStatusCheckedAt,
            Long historicalExecutionId, Long historicalMemberId) {
        this(eventId, tenantId, marketingTaskId, targetId, attemptId, roundNo, protocolAccountId,
                groupJid, commandId, success, messageId, reasonCode, reasonMessage, timestamp,
                workerId, groupCreationTaskId, groupCreationItemId, source, groupStatus,
                groupStatusReason, groupStatusCheckedAt, historicalExecutionId, historicalMemberId,
                null, null, null, groupJid, "GROUP", null, null, null, null, null, null);
    }
}
