package com.armada.account.model.vo;

/**
 * 批量上线中协议层返回的远端 owner 路由。
 *
 * <p>outbox 受理阶段还没有 worker 路由,当前列表为空。消费端执行后如需返回远端 owner,
 * 后续状态回写切片再填充本结构。</p>
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
