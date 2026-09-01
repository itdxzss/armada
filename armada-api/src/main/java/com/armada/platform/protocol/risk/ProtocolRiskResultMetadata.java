package com.armada.platform.protocol.risk;

/**
 * 协议结果中可用于风控归因的低耦合元数据。
 *
 * <p>Kafka consumer 只负责把各自事件转换为该结构；风控域统一筛选固定信号码、校验账号绑定并
 * 保存事实，避免风控适配器反向依赖每一种业务结果 DTO。</p>
 *
 * @param event 事件信封与操作语义
 * @param account 执行账号关联
 * @param correlation 业务、命令和群关联
 * @param reasonCode 协议语义原因码
 * @param reasonMessage 已脱敏原因说明
 */
public record ProtocolRiskResultMetadata(
        Event event,
        Account account,
        Correlation correlation,
        String reasonCode,
        String reasonMessage) {

    /** 协议事件身份。 */
    public record Event(
            String eventId,
            Long tenantId,
            String source,
            String operationType,
            Long occurredAt,
            String workerId) {
    }

    /** 执行账号身份；accountId 是上游声明值，最终以租户内 protocolAccountId 绑定为准。 */
    public record Account(
            Long accountId,
            String protocolAccountId,
            String protocolBackend) {
    }

    /** 业务与命令关联；groupJid 只承载群 JID，不承载私聊目标。 */
    public record Correlation(
            String businessType,
            Long businessId,
            Long businessItemId,
            Long groupBusinessId,
            String commandId,
            String messageId,
            String targetKind,
            String groupJid,
            String rawCode) {
    }
}
