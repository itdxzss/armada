package com.armada.platform.protocol.model.result;

import java.time.Instant;

/**
 * 协议层账号状态快照。
 *
 * <p>对应协议层 {@code GET /v1/accounts/{id}/status} 响应。该对象只表达协议层返回的快照,
 * 不代表 armada 已经把状态落库;账号状态落库仍以后续 Kafka 事件为准。</p>
 *
 * @param protocolAccountId 协议层账号句柄
 * @param state             协议层账号状态
 * @param stateSource       状态来源
 * @param accountType       账号类型
 * @param lastStateSyncTime 最近状态同步时间
 * @param cooldownUntil     风控冷却到期时间
 * @param reportedAt        协议层报告时间
 * @param needReauth        是否需要重新授权
 * @param reauthReason      重新授权原因
 * @param workerId          当前 worker
 */
public record ProtocolAccountStatus(
        String protocolAccountId,
        String state,
        String stateSource,
        String accountType,
        Instant lastStateSyncTime,
        Instant cooldownUntil,
        Instant reportedAt,
        boolean needReauth,
        String reauthReason,
        String workerId
) {
}
