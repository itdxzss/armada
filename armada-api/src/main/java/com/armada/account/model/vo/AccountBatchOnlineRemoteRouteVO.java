package com.armada.account.model.vo;

/**
 * 批量上线中协议层返回的远端 owner 路由。
 *
 * <p>本切片先只回传路由信息,不在 armada 内部做二次转发。</p>
 *
 * @param accountId         armada 账号主键
 * @param protocolAccountId 协议层账号句柄
 * @param ownerWorkerId     协议层 owner worker
 * @param ownerEndpoint     owner worker endpoint
 * @param note              协议层路由提示
 */
public record AccountBatchOnlineRemoteRouteVO(
        Long accountId,
        String protocolAccountId,
        String ownerWorkerId,
        String ownerEndpoint,
        String note
) {
}
