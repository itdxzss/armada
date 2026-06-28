package com.armada.account.service;

/**
 * 协议层 {@code account.state_changed} 事件在账号域内的输入模型。
 *
 * <p>Kafka envelope 的 {@code accountId} 在现役协议层中对应 {@code protocol_account_id};
 * 真正消费 Kafka 时由 listener 解析 envelope/payload 后传入本模型。</p>
 *
 * @param protocolAccountId 协议账号句柄,如 {@code acc_<wsPhone>}
 * @param from              协议层原状态
 * @param to                协议层新状态
 * @param occurredAt        事件发生时间(epoch 毫秒);为空时由服务使用当前时间兜底
 * @param semantic          协议层语义分类,如 PROXY_FAILED / NEED_REAUTH / RECONNECTING
 * @param rawCode           协议层断线原始码;NEED_REAUTH 时用于区分封禁与解绑
 */
public record AccountStateChangedEvent(
        String protocolAccountId,
        String from,
        String to,
        Long occurredAt,
        String semantic,
        Integer rawCode) {
}
