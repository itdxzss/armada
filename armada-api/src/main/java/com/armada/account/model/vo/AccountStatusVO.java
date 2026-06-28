package com.armada.account.model.vo;

/**
 * 账号协议状态快照出参。
 *
 * @param accountId          armada 账号 ID
 * @param protocolAccountId  协议层账号句柄
 * @param state              协议层状态
 * @param stateSource        状态来源
 * @param accountType        账号类型
 * @param lastStateSyncTime  最近状态同步时间(epoch 毫秒)
 * @param cooldownUntil      风控冷却到期时间(epoch 毫秒)
 * @param reportedAt         协议层报告时间(epoch 毫秒)
 * @param needReauth         是否需要重新授权
 * @param reauthReason       重新授权原因
 * @param workerId           当前 worker
 */
public record AccountStatusVO(
        Long accountId,
        String protocolAccountId,
        String state,
        String stateSource,
        String accountType,
        Long lastStateSyncTime,
        Long cooldownUntil,
        Long reportedAt,
        boolean needReauth,
        String reauthReason,
        String workerId
) {
}
