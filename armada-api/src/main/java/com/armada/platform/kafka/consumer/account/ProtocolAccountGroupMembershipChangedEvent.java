package com.armada.platform.kafka.consumer.account;

/**
 * platform 层解析后的协议账号自身群关系精确变更事件。
 *
 * <p>该对象保留事件路由、租户和账号关系所需的最小字段，不携带 participant 数组、手机号、PN、LID、
 * 操作者或原始 notification。consumer 构造该对象前必须确认 envelope 顶层路由账号与
 * {@code data.protocolAccountId} 完全一致，以维持 Kafka 账号分区内的事件顺序。</p>
 *
 * @param eventId 协议层事件 ID
 * @param tenantId 当前租户 ID
 * @param accountId Armada 本地账号 ID
 * @param protocolAccountId 协议账号句柄，同时也是事件路由账号
 * @param groupJid 发生关系变化的 WhatsApp 群 JID
 * @param action 关系动作，仅允许 {@code add}、{@code remove}、{@code leave}
 * @param selfParticipation 参与者分类，精确关系事件必须为 {@code SELF}
 * @param occurredAt 协议层观察到关系变化的事实时间（epoch 毫秒）
 * @param source 事件来源，可空
 * @param workerId 产生事件的协议层 worker ID，可空
 */
public record ProtocolAccountGroupMembershipChangedEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String groupJid,
        String action,
        String selfParticipation,
        Long occurredAt,
        String source,
        String workerId) {
}
