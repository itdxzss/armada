package com.armada.platform.protocol.model.result;

/**
 * 批量上线中非当前 worker 归属账号的路由信息。
 *
 * @param protocolAccountId 协议层账号句柄
 * @param ownerWorkerId     账号归属 worker
 * @param ownerEndpoint     归属 worker endpoint
 * @param note              协议层返回的路由提示
 */
public record BatchOnlineRemoteRoute(
        String protocolAccountId,
        String ownerWorkerId,
        String ownerEndpoint,
        String note
) {
}
