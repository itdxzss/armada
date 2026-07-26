package com.armada.platform.kafka.consumer.account;

/**
 * 协议账号状态变更 Kafka 事件。
 *
 * <p>Kafka envelope 顶层 {@code accountId} 对应协议账号句柄,用于 Kafka key 顺序;
 * {@code data.tenantId/data.accountId} 对应 Armada 本地账号,用于恢复租户上下文后回写状态。</p>
 *
 * @param eventId           协议层事件 ID,用于日志排查和后续幂等
 * @param tenantId          Armada 租户 ID,用于 Kafka listener 线程恢复租户上下文
 * @param accountId         Armada 本地账号主键
 * @param protocolAccountId 协议账号句柄,如 {@code acc_<wsPhone>}
 * @param from              协议层原状态
 * @param to                协议层新状态
 * @param occurredAt        协议层事件发生时间(epoch 毫秒)
 * @param semantic          协议层语义分类
 * @param rawCode           协议层断线原始码,可空
 * @param source            触发本次状态变更的命令来源,用于区分用户下线和协议普通离线
 * @param onlineAttemptId   当前状态事件关联的上线尝试 ID,旧协议事件可为空
 * @param proxyId           本次状态事件关联的代理 ID,旧协议事件可为空
 * @param workerId          产生事件的协议层 worker ID
 */
public record ProtocolAccountStateChangedEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String from,
        String to,
        Long occurredAt,
        String semantic,
        Integer rawCode,
        String source,
        String onlineAttemptId,
        Long proxyId,
        String workerId) {
}
