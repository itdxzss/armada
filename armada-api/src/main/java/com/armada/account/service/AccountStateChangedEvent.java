package com.armada.account.service;

/**
 * 协议层 {@code account.state_changed} 事件在账号域内的输入模型。
 *
 * <p>Kafka envelope 的顶层 {@code accountId} 对应协议账号句柄;{@code tenantId/accountId}
 * 来自事件 data,用于 Kafka listener 线程恢复租户上下文后定位本地账号。</p>
 *
 * @param tenantId          Armada 租户 ID
 * @param accountId         Armada 本地账号主键
 * @param protocolAccountId 协议账号句柄,如 {@code acc_<wsPhone>}
 * @param from              协议层原状态
 * @param to                协议层新状态
 * @param occurredAt        事件发生时间(epoch 毫秒);为空时由服务使用当前时间兜底
 * @param semantic          协议层语义分类,如 PROXY_FAILED / NEED_REAUTH / RECONNECTING
 * @param rawCode           协议层断线原始码;NEED_REAUTH 时用于区分封禁与解绑
 * @param source            触发本次状态事件的命令来源;用于区分用户下线停止抢登与协议普通离线
 * @param onlineAttemptId   当前状态事件关联的上线尝试 ID;旧协议事件可为空
 */
public record AccountStateChangedEvent(
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String from,
        String to,
        Long occurredAt,
        String semantic,
        Integer rawCode,
        String source,
        String onlineAttemptId) {
}
