package com.armada.group.model.dto;

/**
 * group 域使用的账号自身群关系精确变更事实。
 *
 * <p>该对象由 platform 层完成协议事件结构校验后创建。{@code protocolAccountId} 用于确认事件仍属于
 * 账号当前绑定的协议实例，{@code occurredAt} 用于阻止迟到事件覆盖更新的关系状态。</p>
 *
 * @param tenantId 当前租户 ID
 * @param accountId Armada 本地账号 ID
 * @param protocolAccountId 产生事件的协议账号句柄
 * @param groupJid 发生关系变化的 WhatsApp 群 JID
 * @param action 关系动作，仅允许 {@code add}、{@code remove}、{@code leave}
 * @param occurredAt 协议层观察到关系变化的事实时间（epoch 毫秒）
 * @param eventId 协议层事件 ID，用于日志排查和幂等识别
 * @param source 事件来源，可空
 */
public record AccountGroupMembershipChangedEvent(
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String groupJid,
        String action,
        Long occurredAt,
        String eventId,
        String source) {
}
